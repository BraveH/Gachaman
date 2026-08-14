package com.gachaman.party;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.party.*;
import net.runelite.client.party.messages.*;

/**
 * The party contract layer.
 *
 * A party roll is opt-in for every TASK-LESS member: one member proposes,
 * members with an active contract auto-report busy, and once the rest have
 * answered the roll executes DETERMINISTICALLY on every client — all
 * participants roll with the seed candidate of the participant with the
 * LOWEST member id, the pool restricted to free-to-play when ANY participant
 * is free (membership is exchanged in this handshake, nowhere else), sized to
 * the combat level the HOST's {@link PartySizing} setting picks — the party's
 * average or its lowest — and gated by its LOWEST slayer level. Identical
 * offers appear on every screen; clicking one casts a VOTE.
 *
 * Votes are settled by MAJORITY, and only by the host (see
 * {@link PartyRollResolveMessage}). A strict majority signs the contract for
 * the whole party; otherwise, at the deadline, the leading contract — drawn at
 * random from the leaders if they are tied — binds the members who voted. The
 * contract is shared either way: every member's kills count toward one pooled
 * quota.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PartyRollService implements TaskService.Listener {
	private static final int PROPOSAL_TTL_TICKS = 100;
	/**
	 * The voting phase's shot clock — ~2 minutes, double the proposal window,
	 * because reading four contracts takes longer than answering yes/no. Like
	 * the proposal's deadline it is the HOST's clock that decides; every other
	 * client only self-cancels after a grace period, if the host went silent.
	 */
	private static final int VOTE_TTL_TICKS = 200;

	/**
	 * Extra ticks a non-host waits past the host's deadline before giving up on
	 * its own — 15s of slack for a start or cancel message that is merely late.
	 *
	 * <p>Enforcement only: it must NEVER reach a countdown. Showing it made the
	 * host's screen read 60s while everyone else read 75s for the same deadline,
	 * which is not a rounding disagreement — it invites a member to sit on a
	 * decision for fifteen seconds that the host has already timed out. The
	 * clock the party is racing is the host's; see {@link #displayTicksLeft}.
	 */
	private static final int NON_HOST_GRACE_TICKS = 25;

	/**
	 * The tail of every carry-clause notice: what a contract pays once the party
	 * behind it is gone. Three chat lines end on it — the watchdog's conversion,
	 * the pre-resume-id fallback and the resurrection notice — and they must all
	 * quote {@link Tuning#PARTY_CARRY_MULT}, so the rate is spelled out once and
	 * cannot drift between them. Each line keeps its own leading sentence; this
	 * begins at the digit, so the sites read "... continues solo at " + CARRY_PAY.
	 */
	private static final String CARRY_PAY =
		(int) (Tuning.PARTY_CARRY_MULT * 100) + "% completion pay.";

	/**
	 * Roll-rule version. Bumped ONLY when a change would make two clients
	 * generate different offers from the same seed — here, sizing by the
	 * party's average rather than its lowest combat level. The mixed-version
	 * fallback is all-or-nothing on purpose: a party is only ever on one rule
	 * at a time, because half a party on each is exactly the split it prevents.
	 */
	static final int ROLL_PROTOCOL_FIGHTING_WEIGHT = 1;
	/**
	 * Second bump: the host may now choose Weakest Man instead of Fighting
	 * Weight ({@link PartySizing}). A protocol-1 client never reads the choice
	 * off the wire and would size to the average regardless, so a party that
	 * contains one CANNOT honour a Weakest Man host — it falls back to the
	 * average, which is the rule every build from 1 up already agrees on.
	 * Nothing here changes what protocol 1 does on its own.
	 */
	static final int ROLL_PROTOCOL_SIZING_CHOICE = 2;
	/**
	 * Third bump: monsters behind unfinished quests are cut from the pool, using
	 * the INTERSECTION of what every agreeing member has finished. A protocol-2
	 * client sends no quest list and does no such filtering, so a party with one
	 * in it rolls the unfiltered pool — the board every build from 1 up agrees
	 * on. That is worse for the players (a contract can land on a monster
	 * someone cannot reach) but it is the only answer that keeps one seed
	 * producing one board, and the roll says so out loud before the vote.
	 */
	static final int ROLL_PROTOCOL_QUEST_GATE = 3;
	static final int ROLL_PROTOCOL = ROLL_PROTOCOL_QUEST_GATE;

	// package-private, not private: the sizing label and the agreement count are
	// pure functions of a set of these, and src/test builds them directly to pin
	// both rules without a Client or a PartyService
	@Value
	static class Stance {
		int response; // PartyRollResponseMessage.AGREE / DECLINE / BUSY
		long seedCandidate;
		boolean members;
		int combatLevel;
		int slayerLevel;
		String allowedStyle; // AttackStyle name; null from a pre-clash-bonus client
		int rollProtocol;    // 0 from a client that predates Fighting Weight
		/** Finished gating quests; null from a client that predates quest gating. */
		List<String> completedQuests;
		/**
		 * The member's AccountKey; null from an older client or one not logged
		 * in. Read by nothing that decides the board — see the propose message.
		 */
		String accountKey;
	}

	/**
	 * A proposal this client has HEARD but not joined.
	 *
	 * <p>Deliberately separate from the committed proposal above rather than
	 * making that state plural: the seeded roll, the majority vote and the ante
	 * all assume exactly one board, and turning them into maps would put every
	 * determinism guarantee in play to solve a display problem. An inbox entry
	 * carries only what a card needs and what joining it costs — promote one and
	 * the committed path runs exactly as it always has.
	 */
	private static class Inbox {
		long proposalId;
		long proposerId;
		String sizingMode;
		int expiresAtTick;
		/** Answers heard for THIS proposal, including the host's implicit AGREE. */
		final Map<Long, Stance> stances = new HashMap<>();
		/** True once this client has excused itself, so the card stops offering Join. */
		boolean answered;
	}

	/**
	 * Proposal id -> the proposal, in arrival order. Linked so the cards do not
	 * reshuffle under the pointer between rebuilds.
	 */
	private final Map<Long, Inbox> inbox = new LinkedHashMap<>();

	/** One member's line on a proposal card. */
	@Value
	public static class ProposalMember {
		String name;
		int combatLevel;
		/** {@link PartyRollResponseMessage} AGREE / DECLINE / BUSY, or -1 for silent. */
		int response;
		boolean host;
		boolean self;
		/** Offer index this member voted for, or -1 when no vote is open or cast. */
		int vote;
		/** That contract, named — null when they have not voted. */
		@Nullable
		String voteLabel;
	}

	/**
	 * One roll group inside the RuneLite party: who is hosting, under what rule,
	 * and who is in it.
	 *
	 * <p>Several can be live at once — a RuneLite party of six can hold two or
	 * three independent rolls — so this is always a LIST, and the client's own
	 * group is one of them rather than a special case rendered elsewhere.
	 */
	@Value
	public static class PendingProposal {
		long proposalId;
		String hostName;
		int hostCombatLevel;
		/** The rule this roll would ACTUALLY use — see effectiveSizingLabel. */
		String sizingLabel;
		int agreed;
		int ticksLeft;
		/** True when this client cannot join (busy, or contracts disabled). */
		boolean blocked;
		/**
		 * True when this is the group the client belongs to — joined or hosting.
		 * Such a group is never joinable, and is the only one shown once the
		 * client is committed.
		 */
		boolean mine;
		/** True once the group is voting rather than collecting answers. */
		boolean voting;
		List<ProposalMember> members;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final MonsterTable monsterTable;
	private final QuestUnlockService questUnlockService;
	private final AccountKeyService accountKeyService;
	private final GachamanConfig config;

	// --- proposal / vote / task state (transient; one proposal at a time) ---
	//
	// Every field and every collection below is CLIENT THREAD ONLY. Nothing here
	// is read from the Swing EDT any more; the panel reads {@link #view}, which
	// is built from these on the thread that owns them. See View.
	private long proposalId;
	private boolean proposalLive;
	private int proposalExpiresAtTick;
	/** The proposal's authority: the client that fixes the participant set. */
	private long proposerId;
	/**
	 * The host's sizing rule for the live proposal ({@link PartySizing} name),
	 * or null when nobody has proposed one.
	 *
	 * Kept beside {@link #proposerId} rather than inside a {@link Stance},
	 * because it is a property of the PROPOSAL and not of a member: only the
	 * host's answer to it is ever read, so storing one per member would leave a
	 * field that is authoritative in exactly one map entry and dead in the
	 * rest. Set from local config when this client proposes, and from the
	 * propose message otherwise — identical on every client by construction,
	 * which is what the seeded roll needs.
	 */
	private String hostSizingMode;
	private final Map<Long, Stance> stances = new HashMap<>();

	private boolean votingLive;
	private int voteExpiresAtTick;
	private Set<Long> participants = new HashSet<>();
	private final Map<Long, Integer> votes = new HashMap<>();
	/**
	 * The Ante: participant id -> personally willing to stake. Separate from
	 * {@link #votes} because it answers a different question — votes pick the
	 * contract by MAJORITY, this one binds only by UNANIMITY, and a member with
	 * no entry here has not consented to anything.
	 */
	private final Map<Long, Boolean> anteVotes = new HashMap<>();
	private List<TaskOffer> partyOffers;
	/** Every participant's style as of the roll — frozen with the offers, see executeRoll. */
	private List<AttackStyle> partyStyles;
	/** The roll's participant ids in the SAME order as partyStyles, so a final roster maps back. */
	private List<Long> rollOrder;
	/** The anchor's seed, kept past executeRoll so a tied vote can be drawn from it. */
	private long anchorSeed;
	/**
	 * This client's seed candidate for the live proposal, drawn ONCE.
	 *
	 * <p>It used to be drawn inside {@link #localStance}, which is once per MESSAGE.
	 * agree() hides that behind its "you already answered" guard, but decline() has
	 * none — and it should not, since changing your mind before the roll starts is
	 * legitimate. So "::gachaparty no" twice, or agree then no, broadcasts the same
	 * proposalId with two DIFFERENT candidates, and each client keeps whichever
	 * happened to arrive last before it evaluated. If this client is the anchor, the
	 * board is dealt from its candidate ({@link #anchorSeed}), so the party ends up
	 * looking at two different sets of offers under one proposal and voting on
	 * contracts the others cannot see. Drawn once, a repeated answer restates the
	 * same number and every client converges on one board.
	 *
	 * <p>ThreadLocalRandom and not GachaRng, deliberately: this is a shared nonce
	 * rather than gameplay randomness, so changing how often it is drawn cannot
	 * shift any seeded roll.
	 */
	private long mySeedCandidate;

	private boolean taskLive;
	private final Map<Long, Integer> partyKills = new HashMap<>();
	private int lastOthersProgressTick;
	/**
	 * Tick at which a shared contract was resurrected from disk, or -1 when this
	 * session signed its own contract and therefore knows its own roster.
	 *
	 * A resurrected session starts with NOBODY in {@link #participants} — member ids
	 * do not survive a client restart, so the set is refilled by whoever calls in
	 * quoting the contract's proposal id. Until then an empty participant set means
	 * "not yet resynced", not "everyone left", and the watchdog needs to tell those
	 * apart. Transient, like the rest of the session.
	 */
	private int resumedAtTick = -1;
	/**
	 * Tick at which the LAST known participant dropped off the party roster, or -1
	 * while at least one is still there.
	 *
	 * The mirror image of {@link #resumedAtTick}: that one keeps a restarting client
	 * from writing off its party, this one keeps the party from writing off the
	 * restarting client. Closing a client removes you from the roster immediately, so
	 * without this latch a party of two would carry-convert about fifteen seconds
	 * into a partner's restart — long before they could possibly be back to resume.
	 * Transient, like the rest of the session.
	 */
	private int othersGoneSinceTick = -1;
	/**
	 * Participant id -> display name, snapshotted when the contract is signed.
	 * A partner who logs out in the last seconds of a shared contract is off
	 * the roster before the completion fires, and without this their Patron's
	 * Mark would silently go missing — which is precisely the partner who
	 * earned it. Transient: it dies with the session, like partyKills.
	 */
	private final Map<Long, String> partnerNameCache = new HashMap<>();
	/**
	 * Participant id -> account key, snapshotted off the propose/response
	 * handshake. Separate from the name cache because the two have different
	 * SOURCES: a name can be re-read from the live roster at any moment, but a
	 * key exists only in the message that carried it, so missing it once means
	 * missing it for the whole contract. Transient, for the same reason.
	 */
	private final Map<Long, String> partnerKeyCache = new HashMap<>();

	/**
	 * Everything the sidebar and the offer scrolls read off this service, as ONE
	 * immutable object built on the client thread.
	 *
	 * <p>The panel does not run on the thread this service does.
	 * GachamanPanel.refresh() coalesces onto the Swing EDT, so every accessor
	 * that used to compute its answer on the spot was walking these maps from
	 * the EDT while the client thread was still writing them. The tightest case
	 * is not theoretical: the inbox sweep in {@link #onGameTick} calls
	 * {@code it.remove()} on {@link #inbox} every tick, the panel rebuilds every
	 * two, and a ConcurrentModificationException raised on the EDT aborts the
	 * whole rebuild — the player's sidebar simply stops drawing. Handing the EDT
	 * a finished object is the shape PartyPresenceService already publishes its
	 * rows with; this is the same move for the roll layer.
	 *
	 * <p>One object rather than six volatile fields, deliberately: these six
	 * values are drawn side by side on one card, so they have to describe ONE
	 * instant. Read separately they can contradict each other — "Start Roll Now
	 * (2 agreed)" sitting under a proposal card that has already been cancelled.
	 */
	@Value
	private static class View {
		List<PendingProposal> groups;
		@Nullable
		VoteView vote;
		boolean proposalLive;
		boolean canForceStart;
		boolean canCancel;
		int agreed;
		/**
		 * This client is committed to a party roll — hosting, joined, or with a
		 * vote running. Published rather than read live because the sidebar asks
		 * from the EDT, and it exists at all because a host who declines their
		 * OWN proposal now stays vote authority without being dealt a board: that
		 * leaves a live vote with an EMPTY offer list, a combination that used to
		 * be unreachable and that the Contract section reads as "nothing going on,
		 * offer them Roll Contracts". Rolling a personal board there gets those
		 * scrolls force-closed when the party vote settles.
		 */
		boolean committed;
	}

	/** Nothing live. Shared, so a quiet client republishes no garbage at all. */
	private static final View IDLE = new View(Collections.emptyList(), null,
		false, false, false, 0, false);

	/**
	 * Published from the client thread, read from the EDT and from the overlay's
	 * render pass. Volatile for the safe publication of the View's final fields.
	 */
	private volatile View view = IDLE;

	/**
	 * Rebuild the published snapshot.
	 *
	 * <p>CLIENT THREAD ONLY: it walks every mutable map in this service, which is
	 * precisely what no other thread may do. Cheap when nothing is live — the
	 * ternary hands back the shared IDLE instance without allocating, so calling
	 * it on every tick of a session with no party costs nothing.
	 */
	private void publishView() {
		view = proposalLive || votingLive || !inbox.isEmpty()
			? new View(buildGroups(), buildVoteView(), proposalLive,
				hostOfProposal(), hostOfRoll(),
				// only the host's start button reads it, and only while a proposal
				// is collecting answers — so an inbox-only client, which publishes
				// on every tick like everyone else, never builds a roster for it
				proposalLive ? agreedNow() : 0,
				spokenFor(proposalLive, votingLive, taskLive))
			: IDLE;
	}

	/** Plugin-wired: pokes the sidebar so host/status widgets track proposals. */
	@Nullable
	@Setter
	private Runnable refreshHook;


	private void refreshPanel() {
		// snapshot FIRST: the hook hands the rebuild to the EDT, which reads
		// nothing but the published view, so it must already be current when the
		// poke goes out or the panel redraws the state before this change
		publishView();
		if (refreshHook != null) {
			try {
				refreshHook.run();
			}
			catch (Exception e) {
				log.debug("panel refresh hook failed", e);
			}
		}
	}

	/** Plugin-wired: force-closes a modal ceremony whose meaning just changed. */
	@Nullable
	@Setter
	private Runnable ceremonyAbortHook;


	private void abortCeremony() {
		if (ceremonyAbortHook != null) {
			try {
				ceremonyAbortHook.run();
			}
			catch (Exception e) {
				log.debug("ceremony abort hook failed", e);
			}
		}
	}

	// =====================================================================
	// PROPOSAL
	// =====================================================================

	/** Local player proposes a party roll (panel button / ::gachaparty). */
	public void propose() {
		clientThread.invokeLater(() -> {
			if (!partyService.isInParty() || safeLocalMember() == null) {
				chat("You are not in a party.");
				return;
			}
			if (partyService.getMembers().size() < 2) {
				chat("A party roll needs at least one other member.");
				return;
			}
			if (!config.partyRollsEnabled()) {
				chat("Party contracts are disabled in your Gachaman settings.");
				return;
			}
			if (proposalLive || votingLive) {
				chat("A party roll is already in progress.");
				return;
			}
			if (localBusy()) {
				chat("You have a contract or undecided rolls — party rolls are for members"
					+ " with a clean slate (rolls cannot be undone).");
				return;
			}
			resetAll();
			proposalId = ThreadLocalRandom.current().nextLong();
			mySeedCandidate = ThreadLocalRandom.current().nextLong();
			proposalLive = true;
			proposerId = safeMemberIdOrZero();
			// YOUR setting, because you are the host — read once, here, and
			// broadcast, so every client sizes this roll off one value rather
			// than each consulting its own config and dealing its own board
			hostSizingMode = config.partySizing().name();
			proposalExpiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS;
			Stance mine = localStance(PartyRollResponseMessage.AGREE);
			stances.put(safeLocalMember().getMemberId(), mine);
			safeSend(new PartyRollProposeMessage(proposalId, mine.getSeedCandidate(),
				mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel(),
				mine.getAllowedStyle(), mine.getRollProtocol(), hostSizingMode,
				mine.getCompletedQuests(), mine.getAccountKey()));
			chat("Party roll proposed, sizing to " + config.partySizing()
				+ " — ::gachaparty to join. It starts once everyone answers"
				+ " (or in ~60s) with whoever agreed, minimum 2. As host you can Start Roll"
				+ " early from the Overview tab.");
			evaluateProposal();
			refreshPanel();
		});
	}

	/** ::gachaparty — agree to the live proposal (or propose when none). */
	public void agree() {
		clientThread.invokeLater(() -> {
			if (!proposalLive) {
				// a heard-but-unjoined proposal is what this command means now;
				// only propose when there is genuinely nothing to join, or the
				// command would start a rival roll instead of answering the one
				// the player just read in chat
				List<Inbox> open = joinableInbox();
				if (open.size() == 1) {
					joinProposal(open.get(0).proposalId);
					return;
				}
				if (open.size() > 1) {
					chat("More than one party roll is on offer — join one from the"
						+ " Contract panel.");
					return;
				}
				propose();
				return;
			}
			PartyMember local = safeLocalMember();
			if (local == null) {
				return;
			}
			if (stances.containsKey(local.getMemberId())) {
				chat("You already answered this party roll.");
				return;
			}
			if (!config.partyRollsEnabled()) {
				chat("Party contracts are disabled in your Gachaman settings — you sit out.");
				sendResponse(PartyRollResponseMessage.BUSY);
				return;
			}
			if (localBusy()) {
				chat("You have a contract or undecided rolls — you sit this party roll out.");
				sendResponse(PartyRollResponseMessage.BUSY);
				return;
			}
			sendResponse(PartyRollResponseMessage.AGREE);
			chat("You agreed to the party roll.");
			evaluateProposal();
		});
	}

	/** ::gachaparty no — sit this roll out (the rest may still proceed). */
	public void decline() {
		clientThread.invokeLater(() -> {
			if (!proposalLive || safeLocalMember() == null) {
				// not committed to one: the command still has an obvious target
				// while exactly one card is on offer, and is ambiguous past that
				List<Inbox> open = joinableInbox();
				if (open.size() == 1) {
					declineProposalNow(open.get(0).proposalId);
					return;
				}
				chat(open.isEmpty()
					? "No party roll to decline."
					: "More than one party roll is on offer — decline one from the Contract panel.");
				return;
			}
			sendResponse(PartyRollResponseMessage.DECLINE);
			chat("You sit this party roll out — the others may still take a contract.");
			evaluateProposal();
		});
	}

	// --- Multi-proposal inbox: cards, joining, and the one-at-a-time rule ---

	/** Inbox entries this client could still join. */
	private List<Inbox> joinableInbox() {
		List<Inbox> open = new ArrayList<>();
		for (Inbox entry : inbox.values()) {
			if (!entry.answered) {
				open.add(entry);
			}
		}
		return open;
	}

	/**
	 * Is this client already spoken for — committed to a roll, hosting one, or
	 * working a contract? While it is, no other proposal is offered.
	 */
	public boolean isCommittedElsewhere() {
		return spokenFor(proposalLive, votingLive, taskLive);
	}

	/**
	 * The same answer off the published snapshot, for the sidebar.
	 *
	 * <p>{@link #isCommittedElsewhere()} reads the live fields and so is only
	 * safe on the client thread; the Contract section asks from the EDT.
	 */
	public boolean committedSnapshot() {
		return view.isCommitted();
	}

	/**
	 * The one-party-at-a-time rule, by itself.
	 *
	 * <p>A player is spoken for once they have joined a proposal, once a vote is
	 * running, or once a contract is signed — and a HOST is covered by the first
	 * of those, because proposing commits this client to its own roll. Static and
	 * argument-only so the rule can be tested without a Client.
	 */
	static boolean spokenFor(boolean proposalLive, boolean votingLive, boolean taskLive) {
		return proposalLive || votingLive || taskLive;
	}

	/**
	 * The roll groups to show in the Contract section, newest last.
	 *
	 * <p>Committed or hosting, this is exactly ONE group: the client's own, with
	 * its roster and — once voting opens — everyone's vote. Uncommitted, it is
	 * every group on offer. So "one party at a time" hides the OTHERS; it never
	 * hides the one you are in, which is the group you most need to watch while
	 * a majority is being counted.
	 *
	 * <p>Served from the published snapshot rather than computed here — see
	 * {@link View} for why the EDT may not walk this service's maps itself.
	 */
	public List<PendingProposal> proposalGroups() {
		return view.getGroups();
	}

	/**
	 * The groups themselves, read off the live maps — CLIENT THREAD ONLY, and
	 * private for that reason. {@link #proposalGroups()} is what the panel calls.
	 */
	private List<PendingProposal> buildGroups() {
		if (proposalLive || votingLive) {
			PendingProposal mine = describeCommitted();
			return mine == null
				? Collections.emptyList()
				: Collections.singletonList(mine);
		}
		if (taskLive) {
			// the contract itself owns the section from here; the roster lives on
			// the Party page, grouped by the shared contract
			return Collections.emptyList();
		}
		List<PendingProposal> out = new ArrayList<>(inbox.size());
		for (Inbox entry : inbox.values()) {
			out.add(describe(entry));
		}
		// unmodifiable because it crosses a thread boundary: the other two arms
		// already hand back immutable lists, and a published snapshot that one
		// side could still edit is not a snapshot
		return Collections.unmodifiableList(out);
	}

	/**
	 * The client's own group, during answer collection or voting.
	 *
	 * <p>Built from {@link #participants} once a vote is open and from
	 * {@link #stances} before that: the participant set is what the host's start
	 * message froze, so after the roll it is the authority on who is actually in
	 * — a member who answered but was not included would otherwise show as part
	 * of a vote they cannot cast.
	 */
	@Nullable
	private PendingProposal describeCommitted() {
		long self = safeMemberIdOrZero();
		Set<Long> roster = votingLive ? participants : stances.keySet();
		if (roster.isEmpty()) {
			return null;
		}
		// The party roster, to count agreements the same way evaluateProposal
		// does. A member who agreed and then left is still listed on the card —
		// dropping their row would hide what happened — but they cannot be in the
		// roll, so counting them would put a number on this card that the host's
		// Start button (see agreedNow) provably disagrees with.
		Set<Long> party = rosterIds();
		List<ProposalMember> members = new ArrayList<>(roster.size());
		int agreed = 0;
		for (Long id : roster) {
			Stance stance = stances.get(id);
			int response = stance == null ? PartyRollResponseMessage.AGREE : stance.getResponse();
			if (response == PartyRollResponseMessage.AGREE && party.contains(id)) {
				agreed++;
			}
			Integer vote = votingLive ? votes.get(id) : null;
			members.add(new ProposalMember(memberName(id),
				stance == null ? 0 : stance.getCombatLevel(), response,
				id == proposerId, id == self, vote == null ? -1 : vote,
				vote == null ? null : voteLabelFor(id, vote)));
		}
		members.sort((a, b) -> Boolean.compare(b.isHost(), a.isHost()));
		Stance host = stances.get(proposerId);
		return new PendingProposal(proposalId, memberName(proposerId),
			host == null ? 0 : host.getCombatLevel(),
			// the rule this roll would ACTUALLY use, which is what the field's own
			// javadoc promises and what the not-yet-joined card beside it already
			// showed. Quoting the host's DECLARED choice here told a member who had
			// joined "Sizing: Weakest Man" for a roll a protocol-1 member had
			// already forced down to Fighting Weight
			effectiveSizingLabel(stances.values(), hostSizingMode), agreed,
			votingLive
				? displayTicksLeft(voteExpiresAtTick, self == proposerId)
				: displayTicksLeft(proposalExpiresAtTick, self == proposerId),
			true, true, votingLive, members);
	}

	/** Join one proposal, and excuse this client from every other one on offer. */
	public void joinProposal(long id) {
		clientThread.invokeLater(() -> {
			Inbox entry = inbox.get(id);
			if (entry == null || entry.answered) {
				chat("That party roll is no longer on offer.");
				refreshPanel();
				return;
			}
			if (isCommittedElsewhere()) {
				chat("You are already committed to a party roll.");
				return;
			}
			if (!config.partyRollsEnabled()) {
				chat("Party contracts are disabled in your Gachaman settings.");
				return;
			}
			if (localBusy()) {
				chat("You have a contract or undecided rolls — you cannot join a party roll.");
				return;
			}
			// promote: from here the committed path is byte-for-byte the one that
			// has always run, so nothing about the seeded roll changes
			resetAll();
			proposalId = entry.proposalId;
			proposerId = entry.proposerId;
			hostSizingMode = entry.sizingMode;
			mySeedCandidate = ThreadLocalRandom.current().nextLong();
			proposalLive = true;
			proposalExpiresAtTick = entry.expiresAtTick;
			stances.putAll(entry.stances);
			stances.remove(safeMemberIdOrZero()); // answered fresh, below
			// resetAll() a few lines up wiped partnerKeyCache, and the HOST's key
			// rides on their propose message alone — a host never sends a Response
			// to their own proposal, and Start/Vote/Resolve carry no key — so
			// nothing later would ever re-learn it and creditPatrons would silently
			// skip the one partner every joiner is guaranteed to have. The stances
			// just inherited carry each answer's key verbatim; re-seed from those.
			rememberPartners(partnerKeyCache, stances);
			inbox.remove(id);

			sendResponse(PartyRollResponseMessage.AGREE);
			chat("You joined " + memberName(proposerId) + "'s party roll.");
			// every other host is waiting on an answer they will now never get
			// from a player who is spoken for; tell them rather than time out
			for (Inbox other : new ArrayList<>(inbox.values())) {
				if (!other.answered) {
					excuse(other, PartyRollResponseMessage.BUSY);
				}
			}
			evaluateProposal();
			refreshPanel();
		});
	}

	/** Decline one proposal outright; the rest of the inbox is untouched. */
	public void declineProposal(long id) {
		clientThread.invokeLater(() -> declineProposalNow(id));
	}

	private void declineProposalNow(long id) {
		Inbox entry = inbox.get(id);
		if (entry == null) {
			chat("That party roll is no longer on offer.");
			refreshPanel();
			return;
		}
		if (!entry.answered) {
			excuse(entry, PartyRollResponseMessage.DECLINE);
		}
		inbox.remove(id);
		chat("You sit " + memberName(entry.proposerId) + "'s party roll out"
			+ " — the others may still take a contract.");
		refreshPanel();
	}

	/** Snapshot one inbox entry for the panel. */
	private PendingProposal describe(Inbox entry) {
		List<ProposalMember> members = new ArrayList<>();
		int agreed = 0;
		long self = safeMemberIdOrZero();
		for (Map.Entry<Long, Stance> e : entry.stances.entrySet()) {
			Stance stance = e.getValue();
			if (stance.getResponse() == PartyRollResponseMessage.AGREE) {
				agreed++;
			}
			// no vote column on a group this client has not joined: the votes are
			// only broadcast to participants, so any number here would be a guess
			members.add(new ProposalMember(memberName(e.getKey()), stance.getCombatLevel(),
				stance.getResponse(), e.getKey() == entry.proposerId, e.getKey() == self,
				-1, null));
		}
		// host first, then the rest as heard — a card whose first line is not the
		// host reads as a member list with a stray name on top
		members.sort((a, b) -> Boolean.compare(b.isHost(), a.isHost()));
		Stance host = entry.stances.get(entry.proposerId);
		boolean blocked = entry.answered || localBusy() || !config.partyRollsEnabled();
		return new PendingProposal(entry.proposalId, memberName(entry.proposerId),
			host == null ? 0 : host.getCombatLevel(),
			effectiveSizingLabel(entry.stances.values(), entry.sizingMode), agreed,
			// never hosting: an inbox entry is by definition someone else's roll
			displayTicksLeft(entry.expiresAtTick, false), blocked, false, false, members);
	}

	/**
	 * The sizing rule this roll would ACTUALLY use, not the one the host set.
	 *
	 * <p>The rule is an all-or-nothing gate over every participant: one member on
	 * an older build drops the whole party down a rung regardless of what the
	 * host chose. Showing the declared rule would make the card lie to exactly
	 * the player it is trying to inform. Computed over the answers heard so far,
	 * so it can only get more pessimistic as more members join — never less.
	 *
	 * <p>Takes the answers rather than an {@link Inbox} so the COMMITTED card can
	 * ask the same question: a member who has joined is looking at the same roll
	 * as a member who has not, and was being shown the host's declared rule while
	 * their neighbour was shown the real one. Static and argument-only, so the
	 * rule is pinnable without a Client.
	 *
	 * <p>Every heard answer counts, including a BUSY or DECLINE one — the same
	 * reading the inbox card has always used. It errs pessimistic (a member who
	 * sat out cannot actually drag the rule down) and never optimistic, which is
	 * the direction a label a player votes on should fail in.
	 */
	static String effectiveSizingLabel(Collection<Stance> heard, @Nullable String sizingMode) {
		List<Integer> protocols = new ArrayList<>(heard.size() + 1);
		for (Stance stance : heard) {
			// AGREE only, because executeRoll builds its own protocol list from
			// `agreed` and nothing else. Counting every answer let a member who
			// DECLINED still drag the label down: one old build saying no printed
			// "Fighting Weight (a member's build cannot read the host's rule)" on
			// the card while the roll it describes really did use Weakest Man and
			// said so in chat. The card and the chat line must name one rule.
			if (stance.getResponse() == PartyRollResponseMessage.AGREE) {
				protocols.add(stance.getRollProtocol());
			}
		}
		// this client is in it, or would be joining it; harmless when its own
		// stance is already in the map, since the gate is a minimum over protocols
		protocols.add(ROLL_PROTOCOL);
		if (!meanSizingAgreed(protocols)) {
			return PartySizing.WEAKEST_MAN + " (a member's build is too old to average)";
		}
		if (!sizingChoiceAgreed(protocols)) {
			return PartySizing.FIGHTING_WEIGHT + " (a member's build cannot read the host's rule)";
		}
		return PartySizing.fromWire(sizingMode).toString();
	}

	@Subscribe
	public void onPartyRollProposeMessage(PartyRollProposeMessage msg) {
		fromPeer(msg, () -> {
			// Recorded, never dropped. This used to return early whenever anything
			// was already live, which silently binned a second member's roll — the
			// proposer waited out their whole TTL for an answer nobody's client had
			// even been told to give.
			Inbox entry = inbox.get(msg.getProposalId());
			if (entry == null) {
				entry = new Inbox();
				entry.proposalId = msg.getProposalId();
				inbox.put(msg.getProposalId(), entry);
			}
			entry.proposerId = msg.getMemberId();
			// the HOST's rule, taken as sent and never reconciled against local
			// config — this client's own preference applies to rolls it proposes
			entry.sizingMode = msg.getSizingMode();
			// small grace past the proposer's deadline: the proposer decides,
			// this client only times out when no start message ever arrives
			entry.expiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;
			entry.stances.put(msg.getMemberId(), new Stance(PartyRollResponseMessage.AGREE,
				msg.getSeedCandidate(), msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel(),
				msg.getAllowedStyle(), msg.getRollProtocol(), msg.getCompletedQuests(),
				msg.getAccountKey()));
			rememberPartner(msg.getMemberId(), msg.getAccountKey());
			String name = memberName(msg.getMemberId());
			if (!config.partyRollsEnabled()) {
				// setting-off members count as ineligible: excuse immediately so
				// the proposer never waits on them
				excuse(entry, PartyRollResponseMessage.BUSY);
				chat(name + " proposed a party roll — party contracts are disabled in your"
					+ " Gachaman settings, so you sit out.");
			}
			else if (localBusy() || proposalLive || votingLive || taskLive) {
				// already committed elsewhere, or ineligible: excuse THIS proposal
				// so its host stops waiting, and leave the rest of the inbox alone
				excuse(entry, PartyRollResponseMessage.BUSY);
				chat(name + " proposed a party roll — you are already committed and sit out.");
			}
			else {
				chat(name + " proposed a party roll, sizing to "
					+ effectiveSizingLabel(entry.stances.values(), entry.sizingMode)
					+ " — join it from the Contract panel, or ::gachaparty.");
			}
			refreshPanel();
		});
	}

	/**
	 * Answer one inbox proposal without joining it, and stop offering it.
	 *
	 * <p>BUSY rather than DECLINE for the auto-answers: both leave the roll to
	 * proceed without this client, but BUSY is the true one — a player already
	 * committed to another party is not declining on the merits, and the hosts'
	 * chat lines read differently for the two.
	 */
	private void excuse(Inbox entry, int response) {
		entry.answered = true;
		Stance mine = localStance(response);
		entry.stances.put(safeMemberIdOrZero(), mine);
		sendStance(entry.proposalId, response, mine);
	}

	/**
	 * Put this client's answer on the wire, for ONE proposal id.
	 *
	 * <p>The two callers quote different ids — {@link #excuse} answers an inbox
	 * entry, {@link #sendResponse} the committed proposal — and are otherwise the
	 * same ten fields, so the message is spelled out here alone.
	 *
	 * <p>Deliberately the SEND and nothing else. The stance bookkeeping around it
	 * stays at each call site because the two guard it differently: excuse()
	 * records under {@link #safeMemberIdOrZero()} unconditionally, sendResponse()
	 * only when there is a local member at all, and folding those together would
	 * change what happens to a client that is not in a party.
	 */
	private void sendStance(long id, int response, Stance mine) {
		safeSend(new PartyRollResponseMessage(id, response, mine.getSeedCandidate(),
			mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel(),
			mine.getAllowedStyle(), mine.getRollProtocol(), mine.getCompletedQuests(),
			mine.getAccountKey()));
	}

	@Subscribe
	public void onPartyRollResponseMessage(PartyRollResponseMessage msg) {
		fromPeer(msg, () -> {
			// One answer, read once: the two branches below record the IDENTICAL
			// stance and differ only in which map it lands in. Built before the
			// guard rather than inside each arm — a @Value has no side effects, so
			// a message that reaches neither map simply discards it.
			//
			// The rememberPartner calls stay where they are, one per branch: the
			// inbox arm only runs when the entry exists, and hoisting it would
			// start caching account keys for proposals this client does not track.
			Stance heard = new Stance(msg.getResponse(), msg.getSeedCandidate(),
				msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel(),
				msg.getAllowedStyle(), msg.getRollProtocol(), msg.getCompletedQuests(),
				msg.getAccountKey());
			if (!proposalLive || msg.getProposalId() != proposalId) {
				// not the committed one — but it may well be a card on screen, and
				// a card with no roster on it is the thing the player is trying to
				// judge. Route it to the inbox instead of dropping it.
				Inbox entry = inbox.get(msg.getProposalId());
				if (entry != null) {
					entry.stances.put(msg.getMemberId(), heard);
					rememberPartner(msg.getMemberId(), msg.getAccountKey());
					refreshPanel();
				}
				return;
			}
			stances.put(msg.getMemberId(), heard);
			rememberPartner(msg.getMemberId(), msg.getAccountKey());
			if (msg.getResponse() == PartyRollResponseMessage.DECLINE) {
				chat(memberName(msg.getMemberId()) + " sits this party roll out.");
			}
			evaluateProposal();
			refreshPanel();
		});
	}

	/**
	 * PROPOSER ONLY: start the roll once everyone answered — or, at the
	 * deadline, with whoever agreed. Declines and silent members (no plugin)
	 * simply sit out; at least 2 agreed participants are required. The
	 * proposer broadcasts the FINAL participant list so every client rolls
	 * from the exact same set (a last-second agree can never split clients).
	 */
	private void evaluateProposal() {
		evaluateProposal(false);
	}

	private void evaluateProposal(boolean force) {
		if (!proposalLive || safeMemberIdOrZero() != proposerId) {
			return; // non-proposers wait for the start message (TTL+grace covers loss)
		}
		List<PartyMember> roster;
		try {
			roster = partyService.getMembers();
		}
		catch (Exception e) {
			return;
		}
		boolean allAnswered = true;
		List<Long> agreed = new ArrayList<>();
		for (PartyMember member : roster) {
			Stance stance = stances.get(member.getMemberId());
			if (stance == null) {
				allAnswered = false;
				continue; // silent so far — plugin-less members never answer
			}
			if (stance.getResponse() == PartyRollResponseMessage.AGREE) {
				agreed.add(member.getMemberId());
			}
		}
		boolean deadline = client.getTickCount() >= proposalExpiresAtTick;
		if (!allAnswered && !deadline && !force) {
			return; // keep waiting for stragglers until the deadline (or host start)
		}
		if (agreed.size() < 2) {
			if (deadline || allAnswered) {
				cancelProposal("Not enough members agreed to the party roll.");
			}
			return;
		}
		Collections.sort(agreed);
		safeSend(new PartyRollStartMessage(proposalId, agreed));
		executeRoll(agreed);
	}

	// --- Host controls / UI state ---

	/** Game ticks are 0.6s; countdowns are shown in whole seconds. */
	public static int ticksToSeconds(int ticks) {
		return Math.max(0, ticks) * 3 / 5;
	}

	/**
	 * Ticks until the HOST's deadline — the only clock worth putting on screen.
	 *
	 * <p>A non-host's stored deadline carries {@link #NON_HOST_GRACE_TICKS} of
	 * slack so it does not give up on a merely late message. That slack is not
	 * time to decide in: the host stops collecting at its own deadline, so a
	 * member shown the padded figure would be told they had fifteen seconds that
	 * had, in fact, already gone. Subtracting it here leaves every screen in the
	 * party counting down the same number, while the timeout keeps its margin.
	 *
	 * @param hosting whether the local client owns the deadline being shown
	 */
	private int displayTicksLeft(int expiresAtTick, boolean hosting) {
		int deadline = hosting ? expiresAtTick : expiresAtTick - NON_HOST_GRACE_TICKS;
		return Math.max(0, deadline - client.getTickCount());
	}

	// The proposal and vote countdowns used to have live accessors of their own
	// here. Both are gone, and nothing on screen lost a number: every countdown
	// the panel draws now comes off PendingProposal.ticksLeft, which
	// describeCommitted fills with the same displayTicksLeft() call for whichever
	// phase is running, and OverviewTab renders through ticksToSeconds(). One
	// clock, published with the card it belongs to, cannot disagree with it.

	/** Is a proposal collecting answers? Panel-facing, from the snapshot. */
	public boolean isProposalLive() {
		return view.isProposalLive();
	}

	/** Only the proposer counts as host and may force-start early. */
	public boolean canForceStart() {
		return view.isCanForceStart();
	}

	/**
	 * The same question asked LIVE, for the guards that then ACT on the answer.
	 *
	 * <p>The public accessors above answer from the published snapshot, which is
	 * right for a button label — it agrees with the card drawn beside it — and
	 * wrong for a check that is about to broadcast a message, because it can be
	 * up to a tick behind. Client thread only, like everything it reads.
	 */
	private boolean hostOfProposal() {
		return proposalLive && safeMemberIdOrZero() == proposerId;
	}

	private boolean hostOfRoll() {
		return (proposalLive || votingLive) && safeMemberIdOrZero() == proposerId;
	}

	/** Members who agreed so far (host's start button shows this). */
	public int agreedCount() {
		return view.getAgreed();
	}

	/**
	 * Agreements that would actually COUNT, live.
	 *
	 * <p>Measured against the party roster, exactly as {@link #evaluateProposal}
	 * measures it. Counting every AGREE in {@link #stances} instead meant a host
	 * whose agreeing partner disconnected read "Start Roll Now (2 agreed)" and
	 * clicked a button that found fewer than two agreed members still on the
	 * roster, hit neither the deadline nor the all-answered branch, and returned
	 * without a chat line or a state change. The button did nothing, silently.
	 * One rule, counted once, and the label cannot promise what the rule refuses.
	 */
	private int agreedNow() {
		return agreedAmong(stances, rosterIds());
	}

	/**
	 * The counting rule itself: answers that say AGREE, from members who are
	 * still in the party. Pure, so it pins without a Client or a PartyService.
	 */
	static int agreedAmong(Map<Long, Stance> stances, Set<Long> roster) {
		int count = 0;
		for (Map.Entry<Long, Stance> answer : stances.entrySet()) {
			if (answer.getValue().getResponse() == PartyRollResponseMessage.AGREE
				&& roster.contains(answer.getKey())) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Host-only: start the roll NOW with whoever has agreed, instead of
	 * waiting out the deadline for silent members.
	 */
	public void forceStart() {
		clientThread.invokeLater(() -> {
			if (!hostOfProposal()) {
				chat("Only the proposing host can start the party roll early.");
				return;
			}
			if (agreedNow() < 2) {
				chat("Nobody else has agreed yet — need at least 2 participants.");
				return;
			}
			evaluateProposal(true);
		});
	}

	/** The host may abort a proposal OR a rolled-but-unaccepted vote. */
	public boolean canCancelRoll() {
		return view.isCanCancel();
	}

	/**
	 * Any live party-roll stage: a proposal collecting answers, a vote sitting on
	 * the board, or a shared contract already running. The Charter Office refuses
	 * to sell into all three — a board the party is voting on is not this
	 * player's alone to append to, and a deed binds one purse, not five.
	 *
	 * Defence in depth rather than the only guard: a chartered board is a
	 * non-empty pendingOffers, which localBusy() already treats as busy.
	 */
	public boolean isPartyRollLive() {
		return proposalLive || votingLive || taskLive;
	}

	/**
	 * Host-only: cancel the party roll for EVERY client that joined it. An
	 * accepted shared contract is binding and cannot be cancelled this way.
	 */
	public void cancelRoll() {
		clientThread.invokeLater(() -> {
			// the LIVE test, not the snapshot the button was labelled from: this
			// one broadcasts a cancel and demotes the rolled offers
			if (!hostOfRoll()) {
				chat("Only the hosting proposer can cancel the party roll.");
				return;
			}
			safeSend(new PartyRollCancelMessage(proposalId));
			if (votingLive) {
				cancelVoting("You cancelled the party roll.");
			}
			else {
				cancelProposal("You cancelled the party roll.");
			}
		});
	}

	@Subscribe
	public void onPartyRollCancelMessage(PartyRollCancelMessage msg) {
		fromPeer(msg, () -> {
			// A cancel for a proposal this client only ever HEARD about used to be
			// dropped here, because the guard compared against the committed one.
			// The card then sat there offering Join on a roll nobody was collecting
			// until its TTL ran out. Clear it on the message, like a member would.
			Inbox heard = inbox.get(msg.getProposalId());
			if (heard != null && heard.proposerId == msg.getMemberId()) {
				inbox.remove(msg.getProposalId());
				chat(memberName(msg.getMemberId()) + " cancelled their party roll.");
				refreshPanel();
				return;
			}
			// only the proposal's host may cancel it remotely
			if (msg.getProposalId() != proposalId || msg.getMemberId() != proposerId) {
				return;
			}
			if (votingLive) {
				cancelVoting("The host cancelled the party roll.");
			}
			else if (proposalLive) {
				cancelProposal("The host cancelled the party roll.");
			}
		});
	}

	@Subscribe
	public void onPartyRollStartMessage(PartyRollStartMessage msg) {
		fromPeer(msg, () -> {
			if (!proposalLive || msg.getProposalId() != proposalId
				|| msg.getMemberId() != proposerId || msg.getParticipantIds() == null) {
				return;
			}
			List<Long> list = msg.getParticipantIds();
			long self = safeMemberIdOrZero();
			if (!partOfRoll(list, self)) {
				proposalLive = false;
				String note = stances.containsKey(self)
					&& stances.get(self).getResponse() == PartyRollResponseMessage.AGREE
					? "The party roll started without you (your agreement arrived too late)."
					: "The party roll started with " + list.size() + " members (you sat out).";
				chat(note);
				resetAll();
				return;
			}
			for (long id : list) {
				if (!stances.containsKey(id)) {
					// a listed participant's stance never reached this client —
					// cannot roll deterministically, bow out rather than desync
					proposalLive = false;
					chat("Party roll data incomplete on your client — you sit this one out.");
					resetAll();
					return;
				}
			}
			executeRoll(list);
		});
	}

	// =====================================================================
	// DETERMINISTIC ROLL + VOTE
	// =====================================================================

	/**
	 * Is this client on the roll whose FINAL participant list is {@code agreed}?
	 *
	 * <p>The one test that decides whether a client deals itself a board, asked
	 * identically on both routes into {@link #executeRoll} — the host's own
	 * evaluation and every other member's start message. Two spellings of it is
	 * how the host path came to be missing it: a host who declines their own
	 * proposal is not in the list it just broadcast, and rolled anyway.
	 *
	 * <p>Membership of the list, and nothing else: not "did I agree", which is a
	 * different question at the moment the host freezes the roster.
	 */
	static boolean partOfRoll(@Nullable Collection<Long> agreed, long self) {
		return agreed != null && agreed.contains(self);
	}

	/**
	 * Runs IDENTICALLY on every participant's client: same participants, same
	 * anchor seed (lowest member id), same pool restrictions, same generator
	 * — therefore the same four offers everywhere.
	 */
	private void executeRoll(List<Long> agreed) {
		proposalLive = false;
		/*
		 * Is this client actually ON the roll it is about to run?
		 *
		 * For every member reached through the start message, yes — that path
		 * turns away a client the list omits before it ever gets here. The one
		 * client that arrives here without being listed is a HOST who declined
		 * its own proposal (::gachaparty no after ::gachaparty): evaluateProposal
		 * still recognises it as the proposer, so it broadcasts the start and
		 * rolls locally, and a full board was dealt to the member who had just
		 * said no. They could not then vote (voteLocal wants a participant) and
		 * fell out at resolve as an abstainer holding four PERSONAL offers they
		 * were now obliged to decide, because a roll cannot be handed back.
		 *
		 * The roll still runs here rather than returning, and that is deliberate:
		 * the proposer is the party's ONLY vote authority (evaluateVotes and
		 * hostResolve both refuse to settle on any other client, and a resolve
		 * message from anyone else is dropped). Bowing out would leave the party
		 * voting on a board nobody could ever settle, until every member's shot
		 * clock ran down and demoted its contracts — trading a board this player
		 * did not want for a dissolved roll the whole party did. So the host
		 * keeps the tally and skips the board.
		 *
		 * Read ONCE, here, and reused for the deadline below: two reads of
		 * safeMemberIdOrZero() in one roll can disagree if the party layer blinks
		 * between them, and this one decides whether a board is dealt at all.
		 */
		long self = safeMemberIdOrZero();
		boolean rolling = partOfRoll(agreed, self);
		long anchorId = Long.MAX_VALUE;
		for (long id : agreed) {
			anchorId = Math.min(anchorId, id);
		}
		Stance anchor = stances.get(anchorId);
		anchorSeed = anchor.getSeedCandidate();
		boolean members = true;
		int slayer = Integer.MAX_VALUE;
		List<Integer> combatLevels = new ArrayList<>(agreed.size());
		List<Integer> protocols = new ArrayList<>(agreed.size());
		List<List<String>> questSets = new ArrayList<>(agreed.size());
		for (long id : agreed) {
			Stance stance = stances.get(id);
			members &= stance.isMembers();
			// A slayer requirement is a GATE, not a difficulty knob: a monster
			// you lack the level for cannot be damaged at all, so averaging here
			// would roll contracts some members literally cannot start. Combat
			// level is the knob, and combat level is what gets sized on.
			slayer = Math.min(slayer, stance.getSlayerLevel());
			combatLevels.add(stance.getCombatLevel());
			protocols.add(stance.getRollProtocol());
			questSets.add(stance.getCompletedQuests());
		}
		int cb = sizingLevel(combatLevels, protocols, hostSizingMode);
		// A quest lock is a gate like the slayer level above, so it intersects
		// rather than averaging: a monster the party's least-questful member
		// cannot reach is a contract nobody in the party can finish.
		Set<String> quests = agreedQuests(questSets, protocols);
		List<TaskOffer> raw = TaskGenerator.generateOffers(monsterTable.getMonsters(),
			// bestHitSeen is deliberately 0, NOT the local player's: every client
			// in the party must generate byte-identical offers from the shared
			// seed, and the biggest hit each has landed differs per client. A
			// party board therefore always deals the floor BIG_HIT
			cb, slayer, members, quests, false, 0, new GachaRng(anchor.getSeedCandidate()));
		List<TaskOffer> offers = new ArrayList<>(raw.size());
		for (TaskOffer offer : raw) {
			// the generator deals plain contracts; the only thing that makes them
			// the PARTY's is this flag, which turns a click into a vote
			offers.add(offer.withPartyRoll(true));
		}
		if (rolling && !taskService.presentPartyOffers(offers)) {
			// local slot occupied after all (race) — sit out quietly
			resetAll();
			chat("The party roll could not be presented (you have offers or a contract).");
			return;
		}
		votingLive = true;
		// The host's deadline is the real one; everyone else takes the same grace
		// margin the proposal phase uses, so their clock can only fire when the
		// host's broadcast cancel never arrived (host left, host not updated).
		// Anything closer would let two clients disagree about a late vote.
		voteExpiresAtTick = client.getTickCount() + VOTE_TTL_TICKS
			+ (self == proposerId ? 0 : NON_HOST_GRACE_TICKS);
		participants = new HashSet<>(agreed);
		partyOffers = offers;
		// Freeze the styles here, with the offers, and NOT at accept time: this
		// is the last participant set with a proven cross-client identity (the
		// proposer's start message fixes it, and a client missing any listed
		// stance bows out above rather than desyncing). Reading each member's
		// LIVE style at accept time would let a mid-vote style re-roll reprice
		// a contract, and would price it differently on every client.
		//
		// rollOrder keeps this list addressable: the final roster (which the
		// host broadcasts) can be narrower than the roll, so stylesFor() maps
		// ids back to positions here rather than re-reading anything live.
		rollOrder = new ArrayList<>(agreed);
		partyStyles = new ArrayList<>(agreed.size());
		for (long id : agreed) {
			partyStyles.add(parseStyle(stances.get(id).getAllowedStyle()));
		}
		// The sizing level is disclosed BEFORE the vote on purpose: a shared
		// contract can outclass the party's lowest member, and there is no
		// abandoning one once it is signed, so he has to be able to see what he
		// is voting on rather than find out at the first kill. The RULE is named
		// alongside it, because the level alone cannot tell him whether the host
		// sized on the party's average or on him.
		chat("Party roll ready (" + agreed.size() + " members, contracts sized to combat "
			+ cb + " — " + sizingRuleLabel(protocols, hostSizingMode)
			+ ") — click a contract to VOTE; a majority ("
			+ majorityThreshold(agreed.size()) + " of " + agreed.size()
			+ ") signs it for the party.");
		if (meanSizingAgreed(protocols) && !sizingChoiceAgreed(protocols)
			&& PartySizing.fromWire(hostSizingMode) == PartySizing.WEAKEST_MAN) {
			// said out loud rather than swallowed: the host set a rule and did not
			// get it, and the members it would have protected are the ones voting
			chat("The host asked for Weakest Man, but a member's build predates that"
				+ " choice — the whole party falls back to Fighting Weight so every"
				+ " client deals the same board.");
		}
		if (quests == null) {
			// The one fallback that can put an unfinishable contract on the
			// board, so it is stated plainly and BEFORE the vote — a party
			// contract cannot be handed back once a majority signs it.
			chat("A member's build predates quest gating, so this board was dealt from the"
				+ " whole table — check you can all reach a monster before voting for it.");
		}
		if (anteOffered()) {
			for (TaskOffer offer : offers) {
				if (TaskService.anteEligible(offer)) {
					// said BEFORE the vote, because arming after clicking a scroll
					// is too late — the vote carries the consent with it
					chat("One of these contracts can carry the Ante. Arm it in the Gachaman"
						+ " panel BEFORE you vote: it takes every member's consent, and each"
						+ " member stakes their own GC.");
					break;
				}
			}
		}
		if (!rolling) {
			// Said plainly, because the board they proposed is about to appear on
			// everyone's screen except theirs and the panel will show them a vote
			// they cannot cast. Nothing above is suppressed — the sizing, fallback
			// and Ante disclosures are about the PARTY's board, and the host who
			// dealt it is entitled to read what it dealt.
			chat("You sat this party roll out, so no contracts were dealt to you — your"
				+ " client still counts the votes and settles the contract for the party."
				// named because the panel's Cancel button rides on holding the
				// rolled board, which this client deliberately does not: the chat
				// route is the one that is still open to a host who sat out
				+ " ::gachaparty cancel calls the whole roll off.");
		}
		refreshPanel();
	}

	/**
	 * Wired as TaskService's party vote hook: local click on a party offer.
	 *
	 * <p>Deferred to the client thread like every other entry point on this
	 * service, and for a sharper reason than symmetry: this one is reached from
	 * RevealOverlay.handleClick, which RuneLite's MouseManager calls on the AWT
	 * thread — so the body used to write {@link #votes} and {@link #anteVotes},
	 * queue chat and send on the wire from a thread that owns none of them,
	 * while the game thread was reading the same maps to tally. Deferring makes
	 * the client thread the sole writer, which is what {@link View} and the vote
	 * snapshot both rest on. The cost is that the vote lands on the next tick
	 * rather than inside the click; nothing waits on it — TaskService.acceptOffer
	 * returns true for a party offer regardless, and the board closes either way.
	 */
	public void voteLocal(int offerIndex) {
		clientThread.invokeLater(() -> {
			if (!votingLive || partyOffers == null
				|| offerIndex < 0 || offerIndex >= partyOffers.size()) {
				return;
			}
			long self = safeMemberIdOrZero();
			if (self == 0 || !participants.contains(self)) {
				return;
			}
			votes.put(self, offerIndex);
			// The Ante rides on the local player's own arming, re-read at vote time
			// so it is this client — and only this client — that consents to stake
			// this player's GC. A purse under the floor prices to 0 and reads as no.
			boolean wantsAnte = anteOffered()
				&& taskService.anteArmed() && taskService.previewAnteStake() > 0;
			anteVotes.put(self, wantsAnte);
			safeSend(new PartyRollVoteMessage(proposalId, offerIndex, wantsAnte));
			chatVote("You", offerIndex, wantsAnte);
			evaluateVotes();
			publishView(); // your own vote shows on the scrolls at once, as before
		});
	}

	/** One short, sober note on a vote line: the Ante needs EVERY member. */
	private String anteSuffix(boolean wantsAnte) {
		return wantsAnte ? " — Ante: yes" : "";
	}

	/**
	 * One vote, announced identically whoever cast it: the contract backed, the
	 * tally so far against the bar it has to clear, and whether that voter
	 * staked. "You" is just a name here — the local click and a peer's message
	 * print the same sentence, which is what makes a party's chat logs line up.
	 *
	 * <p>Called only after the vote and the Ante have been recorded, at both
	 * sites, because {@link #votesFor} counts the map as it stands.
	 */
	private void chatVote(String who, int index, boolean ante) {
		chat(who + " voted: " + describeOffer(partyOffers.get(index))
			+ " (" + votesFor(index) + " of "
			+ majorityThreshold(participants.size()) + " needed)"
			+ anteSuffix(ante));
	}

	/**
	 * Is the wager on the table at all for this client? Only gates what this
	 * client OFFERS and says; a peer's incoming consent is still recorded, so
	 * turning it off never makes this client misreport someone else's answer.
	 */
	private boolean anteOffered() {
		try {
			return config != null && config.anteEnabled();
		}
		catch (Exception e) {
			return false;
		}
	}

	@Subscribe
	public void onPartyRollVoteMessage(PartyRollVoteMessage msg) {
		fromPeer(msg, () -> {
			if (!votingLive || msg.getProposalId() != proposalId
				|| !participants.contains(msg.getMemberId())) {
				return;
			}
			int index = msg.getOfferIndex();
			if (partyOffers == null || index < 0 || index >= partyOffers.size()) {
				return;
			}
			votes.put(msg.getMemberId(), index);
			anteVotes.put(msg.getMemberId(), msg.isAnte());
			chatVote(memberName(msg.getMemberId()), index, msg.isAnte());
			evaluateVotes();
			// a vote lands on the scrolls the moment it arrives, as it always has:
			// neither vote path pokes the panel, so without this the offer cards
			// would carry the new tally only from the next tick's publish
			publishView();
		});
	}

	private int votesFor(int index) {
		int count = 0;
		for (int vote : votes.values()) {
			if (vote == index) {
				count++;
			}
		}
		return count;
	}

	/** Votes per offer index, in offer order. */
	private int[] tally() {
		int[] counts = new int[partyOffers == null ? 0 : partyOffers.size()];
		for (int vote : votes.values()) {
			if (vote >= 0 && vote < counts.length) {
				counts[vote]++;
			}
		}
		return counts;
	}

	/** One name on a contract: who backed it, ready to draw. */
	@Value
	public static class Voter {
		String name;
		/** The party avatar, or null for a member who has none. */
		@Nullable
		BufferedImage avatar;
		boolean self;
	}

	/**
	 * The live vote picture, for the offer scrolls and the party page.
	 *
	 * <p>Read by the overlay while it paints, so it is a SNAPSHOT: both the map
	 * and the lists are built fresh by {@link #buildVoteView()}, so a caller
	 * cannot hold a window onto this service's mutable state. That build and
	 * every mutation of {@link #votes} run on the client thread, so the snapshot
	 * is never torn.
	 *
	 * <p>Only what somebody draws is carried. A tally, the majority threshold and
	 * a member-id-to-offer-index map were all published here too and read by
	 * nobody: the resolve path tallies {@link #votes} itself, the chat notice
	 * computes its own threshold, and the panel wants the contract's NAME, which
	 * is what {@link #labelByMember} holds.
	 */
	@Value
	public static class VoteView {
		/**
		 * Member id -> the contract they backed, named. The panel shows this
		 * rather than the index: "vote 2" means nothing beside a player's name
		 * once the board is off screen.
		 */
		Map<Long, String> labelByMember;
		/**
		 * Who voted for each offer, indexed by offer.
		 *
		 * <p>Names and faces rather than a count: on a board of four contracts a
		 * bare "2 votes" tells a player how close the vote is but not who they
		 * would be siding with, which is the actual question when the contract
		 * binds the whole party. Resolved here because this service holds the
		 * PartyService the avatars come from.
		 */
		List<List<Voter>> voters;
	}

	/**
	 * The vote picture while a party vote is open, or null when none is.
	 *
	 * <p>Deliberately readable by members who have NOT voted yet: seeing the
	 * balance is the whole point of a majority vote, and hiding it until you
	 * commit would make the last voter guess at what they are deciding.
	 */
	@Nullable
	public VoteView voteView() {
		return view.getVote();
	}

	/**
	 * The vote picture off the live maps. CLIENT THREAD ONLY — it walks
	 * {@link #votes} and reads the party roster, which is why the panel and the
	 * offer scrolls both take the published copy instead.
	 */
	@Nullable
	private VoteView buildVoteView() {
		if (!votingLive || partyOffers == null) {
			return null;
		}
		long self = safeMemberIdOrZero();
		List<List<Voter>> voters = new ArrayList<>(partyOffers.size());
		for (int i = 0; i < partyOffers.size(); i++) {
			voters.add(new ArrayList<>());
		}
		Map<Long, String> labels = new HashMap<>();
		// Faces and labels in ONE pass. The bounds check above the label lines is
		// the one the labels already obeyed: voters is sized from partyOffers, and
		// voteLabelFor -> offerLabel refuses the same out-of-range index by
		// returning null, so every entry this continue skips is an entry that
		// would have produced no label anyway.
		for (Map.Entry<Long, Integer> entry : votes.entrySet()) {
			int index = entry.getValue() == null ? -1 : entry.getValue();
			if (index < 0 || index >= voters.size()) {
				continue; // a vote for an offer this client never saw
			}
			voters.get(index).add(new Voter(memberName(entry.getKey()),
				avatarOf(entry.getKey()), entry.getKey() == self));
			String label = voteLabelFor(entry.getKey(), index);
			if (label != null) {
				labels.put(entry.getKey(), label);
			}
		}
		return new VoteView(labels, voters);
	}

	/** A member's party avatar, or null — absent member, no avatar, or no party. */
	@Nullable
	private BufferedImage avatarOf(long memberId) {
		try {
			PartyMember member = partyService.getMemberById(memberId);
			return member == null ? null : member.getAvatar();
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * How many votes sign the contract for everyone: a STRICT majority of the
	 * members still in the party (2 of 2, 2 of 3, 3 of 4, 3 of 5). Strict, so
	 * at most one contract can ever hold it — an even split is a tie, and ties
	 * go to the deadline path below rather than to whoever was counted first.
	 */
	static int majorityThreshold(int participantCount) {
		return participantCount / 2 + 1;
	}

	/**
	 * The Ante binds the party only by UNANIMOUS consent of the FINAL roster —
	 * deliberately a different bar from the contract's majority. A contract is
	 * an activity the party does together; a stake is each member's own GC, and
	 * no majority is entitled to spend it.
	 *
	 * Silence is a NO. An abstainer bound by a majority never consented to
	 * anything, a member whose purse is under the floor votes no on their own
	 * client, and a client too old to know the field sends false — every one of
	 * those sinks the wager for everyone, and the contract proceeds regardless.
	 */
	static boolean anteUnanimous(Collection<Long> roster, Map<Long, Boolean> consent) {
		if (roster == null || roster.isEmpty() || consent == null) {
			return false;
		}
		for (Long id : roster) {
			if (!Boolean.TRUE.equals(consent.get(id))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Only the host decides. A majority could in principle be spotted locally
	 * — it is unique, so every client would agree — but the OTHER two outcomes
	 * (a plurality at the deadline, a drawn tie) depend on exactly who had
	 * voted at one instant, which differs per client. Routing all three
	 * through one authority means there is one tally that matters, so a vote
	 * still in flight can never split the party across two contracts.
	 */
	private void evaluateVotes() {
		if (!votingLive || partyOffers == null) {
			return;
		}
		// members who left the party stop counting, and so do their votes: a
		// departed member must not carry a contract they will not be on
		participants.retainAll(rosterIds());
		if (participants.size() < 2) {
			cancelVoting("The party shrank — the shared roll dissolved.");
			return;
		}
		votes.keySet().retainAll(participants);
		anteVotes.keySet().retainAll(participants);
		if (safeMemberIdOrZero() != proposerId) {
			return;
		}
		int[] counts = tally();
		int threshold = majorityThreshold(participants.size());
		for (int index = 0; index < counts.length; index++) {
			if (counts[index] >= threshold) {
				// A majority binds the WHOLE party, abstainers included — that
				// is what majority rule means here: the party hunts what most
				// of it picked. Only the weaker outcomes below fall back to
				// binding the voters alone.
				broadcastResolve(index, new ArrayList<>(participants),
					PartyRollResolveMessage.MODE_MAJORITY);
				return;
			}
		}
		if (votes.keySet().containsAll(participants)) {
			// everyone present has spoken and no contract holds a majority —
			// nothing further will arrive, so settle it now instead of making
			// the party sit out the rest of the shot clock
			hostResolve("Every vote is in");
		}
	}

	/**
	 * Host-only: settle a vote that never reached a majority. The leader on
	 * votes wins; a tie for the lead is DRAWN from the tied contracts, and
	 * either way only the members who actually voted are bound — a contract
	 * decided by a minority should not be forced on someone who abstained.
	 */
	private void hostResolve(String why) {
		if (!votingLive || partyOffers == null || safeMemberIdOrZero() != proposerId) {
			return;
		}
		// The deadline can fire between two of evaluateVotes' sweeps, so narrow
		// here too rather than settling on a tally that still counts someone who
		// has already left the party.
		participants.retainAll(rosterIds());
		votes.keySet().retainAll(participants);
		anteVotes.keySet().retainAll(participants);
		List<Integer> top = topTallies(tally());
		int index = top.size() == 1 ? top.get(0)
			: tiebreakIndex(anchorSeed, proposalId, top);
		if (votes.size() < 2 || index < 0) {
			// one voter cannot be a party: cancel for everyone, exactly the way
			// the host's cancel button does, so every client lands the same way
			safeSend(new PartyRollCancelMessage(proposalId));
			cancelVoting(why + ", but fewer than two members voted.");
			return;
		}
		broadcastResolve(index, new ArrayList<>(votes.keySet()), top.size() == 1
			? PartyRollResolveMessage.MODE_PLURALITY
			: PartyRollResolveMessage.MODE_TIEBREAK);
	}

	private void broadcastResolve(int index, List<Long> memberIds, int mode) {
		Collections.sort(memberIds); // stable payload; ids are the identity
		// The Ante verdict is settled here, by the host, against the roster it
		// just fixed — the same authority and the same instant as the contract,
		// so no client can be staking against a roster that has since narrowed.
		boolean ante = TaskService.anteEligible(partyOffers.get(index))
			&& anteUnanimous(memberIds, anteVotes);
		safeSend(new PartyRollResolveMessage(proposalId, index, memberIds, mode, ante));
		applyResolve(index, memberIds, mode, ante);
	}

	@Subscribe
	public void onPartyRollResolveMessage(PartyRollResolveMessage msg) {
		fromPeer(msg, () -> {
			// only the proposal's host may settle it, and only once
			if (!votingLive || msg.getProposalId() != proposalId
				|| msg.getMemberId() != proposerId || msg.getMemberIds() == null
				|| partyOffers == null) {
				return;
			}
			int index = msg.getOfferIndex();
			if (index < 0 || index >= partyOffers.size()) {
				return;
			}
			// a roster the roll never included cannot be on the contract
			List<Long> roster = new ArrayList<>(msg.getMemberIds());
			if (rollOrder != null) {
				roster.retainAll(rollOrder);
			}
			applyResolve(index, roster, msg.getMode(), msg.isAnte());
		});
	}

	/** Sign (or, for an abstainer, decline) the contract the host settled on. */
	private void applyResolve(int index, List<Long> memberIds, int mode, boolean ante) {
		if (!votingLive || partyOffers == null || index < 0 || index >= partyOffers.size()) {
			return;
		}
		votingLive = false;
		if (!memberIds.contains(safeMemberIdOrZero())) {
			// A minority settled on a contract without this player's vote. The
			// rolled offers stay (rolls cannot be undone), demoted to personal
			// ones — they pick for themselves.
			cancelVoting("The party settled on a contract without your vote.");
			return;
		}
		// A majority binds members who never clicked, so the scrolls can still be
		// open on this screen, still reading "vote", over a contract that is
		// already signed. Close them — same reason cancelVoting does.
		abortCeremony();
		// The host's verdict says the PARTY agreed; this second check says THIS
		// player did. Both must hold before any of this player's GC moves, so a
		// stale, buggy or hostile true cannot stake someone who never said yes.
		boolean stakeLocally = ante && Boolean.TRUE.equals(anteVotes.get(safeMemberIdOrZero()));
		if (!taskService.acceptPartyOffer(index, "Party of " + memberIds.size(),
			stylesFor(memberIds), stakeLocally, proposalId)) {
			// the local slot filled between the roll and the signature
			cancelVoting("The party contract could not be signed on your client.");
			return;
		}
		taskLive = true;
		partyKills.clear();
		lastOthersProgressTick = client.getTickCount();
		participants = new HashSet<>(memberIds);
		rememberNames(memberIds);
		String lead;
		switch (mode) {
			case PartyRollResolveMessage.MODE_TIEBREAK:
				lead = "The vote tied — the House drew: ";
				break;
			case PartyRollResolveMessage.MODE_PLURALITY:
				lead = "No majority — the most-voted contract stands: ";
				break;
			default:
				lead = "Majority! Party contract accepted: ";
				break;
		}
		chat(lead + describeOffer(partyOffers.get(index))
			+ " — every member's kills count toward the shared quota.");
		// Stated either way, and only where a wager was possible at all: a stake
		// that appeared without a word, or a stake the player expected and did
		// not get, are both worse than one line of plain accounting.
		if (anteOffered() && TaskService.anteEligible(partyOffers.get(index))) {
			int staked = stakeLocally ? taskService.getActiveAnteStake() : 0;
			chat(staked > 0
				? "The Ante rides: " + staked + " GC of yours is staked. Finish the contract"
					+ " and it returns doubled; die and it is gone. Only YOUR stake is yours"
					+ " to lose — the rest of the party's rides on."
				: "No Ante on this contract — it takes every member's consent.");
		}
		refreshPanel();
	}

	/**
	 * The style snapshot narrowed to a FINAL roster, in roll order. The clash
	 * bonus is priced off this, so it must describe the members actually on the
	 * contract: a party of five that resolves to two voters should not be paid
	 * for three styles nobody on it is running.
	 */
	@Nullable
	private List<AttackStyle> stylesFor(List<Long> memberIds) {
		if (rollOrder == null || partyStyles == null) {
			return partyStyles; // legacy/absent snapshot: no clash bonus, no throw
		}
		List<AttackStyle> narrowed = new ArrayList<>(memberIds.size());
		for (int i = 0; i < rollOrder.size() && i < partyStyles.size(); i++) {
			if (memberIds.contains(rollOrder.get(i))) {
				narrowed.add(partyStyles.get(i));
			}
		}
		return narrowed;
	}

	/**
	 * The offer indices sharing the highest vote count; empty when nobody
	 * voted. More than one entry means the lead is tied and must be drawn.
	 */
	static List<Integer> topTallies(int[] counts) {
		List<Integer> top = new ArrayList<>();
		int best = 0;
		for (int index = 0; index < counts.length; index++) {
			if (counts[index] == 0 || counts[index] < best) {
				continue;
			}
			if (counts[index] > best) {
				best = counts[index];
				top.clear();
			}
			top.add(index);
		}
		return top;
	}

	/**
	 * Draw one of the equally-voted contracts. Seeded from the roll's anchor
	 * seed and proposal id rather than an ambient random so the same vote
	 * always draws the same contract — reproducible from a bug report, and
	 * pinnable by a test. Returns -1 for an empty list; only the host runs it.
	 */
	static int tiebreakIndex(long anchorSeed, long proposalId, List<Integer> tied) {
		if (tied == null || tied.isEmpty()) {
			return -1;
		}
		return tied.get(new GachaRng(anchorSeed * 31 + proposalId).nextInt(tied.size()));
	}

	// =====================================================================
	// SHARED TASK
	// =====================================================================

	@Override
	public void onPartyProgress(ActiveTask task) {
		if (taskLive && task != null && safeLocalMember() != null) {
			safeSend(new PartyKillsMessage(proposalId, task.getKillsDone()));
		}
	}

	@Subscribe
	public void onPartyKillsMessage(PartyKillsMessage msg) {
		fromPeer(msg, () -> {
			if (!onContract(msg.getMemberId(), msg.getProposalId())) {
				return;
			}
			partyKills.merge(msg.getMemberId(), msg.getKills(), Math::max);
			lastOthersProgressTick = client.getTickCount();
			int othersTotal = 0;
			for (int kills : partyKills.values()) {
				othersTotal += kills;
			}
			taskService.syncPartyKills(othersTotal);
		});
	}

	/**
	 * Is this message from someone on OUR contract?
	 *
	 * Normally the participant set settled at the vote answers it outright. But a
	 * member who restarts their client comes back under a BRAND NEW member id —
	 * RuneLite draws it fresh every session — so after a restart the two sides stop
	 * recognising each other and every kill message is dropped in silence. Identity
	 * cannot be the test.
	 *
	 * The proposal id can. It is a 64-bit random drawn once per roll and held only
	 * by the clients that were on it, so quoting it is proof of membership with no
	 * handshake to negotiate. Paired with "is in the party right now" it re-seats a
	 * returning member and nobody else. This widens who is HEARD, not what they may
	 * do: every member of a RuneLite party can already send every message, and the
	 * two callers here only pool kills and complete a contract they are on.
	 */
	private boolean onContract(long memberId, long msgProposalId) {
		if (!taskLive || msgProposalId != proposalId) {
			return false;
		}
		if (participants.contains(memberId)) {
			return true;
		}
		Set<Long> roster = rosterIds();
		if (!roster.contains(memberId)) {
			return false;
		}
		admitReturningMember(memberId, roster);
		return true;
	}

	/**
	 * Re-seat a member who came back under a new id.
	 *
	 * Their OLD id may still be sitting in {@link #partyKills} holding a pre-restart
	 * total, and those values are SUMMED — left there it would be counted a second
	 * time under the new id, inflating the shared quota. Ids that have left the
	 * roster are dropped at this one moment rather than on every tick, so a partner
	 * who simply logged out and stayed out keeps the kills they banked.
	 *
	 * A drop can only ever LOWER the total, and TaskService.syncPartyKills ignores
	 * any total that does not beat the recorded high-water mark, so nothing already
	 * credited to the contract can be taken back by this.
	 */
	private void admitReturningMember(long memberId, Set<Long> roster) {
		boolean firstContact = resumedAtTick >= 0 && participants.isEmpty();
		dropDepartedKills(partyKills, roster);
		participants.add(memberId);
		rememberNames(Collections.singletonList(memberId));
		if (firstContact) {
			chat("Your party is back in sync — " + memberName(memberId)
				+ " is still on the contract with you.");
			refreshPanel();
		}
	}

	@Subscribe
	public void onPartyCompleteMessage(PartyCompleteMessage msg) {
		fromPeer(msg, () -> {
			if (onContract(msg.getMemberId(), msg.getProposalId())) {
				chat(memberName(msg.getMemberId()) + "'s client completed the party contract.");
				taskService.forcePartyComplete();
			}
		});
	}

	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary) {
		if (taskLive && summary != null && summary.getTask() != null
			&& summary.getTask().getPartyLabel() != null) {
			if (safeLocalMember() != null) {
				safeSend(new PartyCompleteMessage(proposalId));
			}
			creditPatrons();
			resetAll();
		}
	}

	/**
	 * The Patron's Mark: one cosmetic tally per partner who finished this
	 * contract with you. STRICTLY COSMETIC — it pays no GC, moves no
	 * multiplier and gates nothing, deliberately, because any economic value
	 * here would make farming a friend the correct play. Written exactly ONCE,
	 * here, at completion, and never per tick or per kill.
	 *
	 * Every client keeps its own private ledger of its own partners, so there
	 * is nothing to agree on and nothing that can desync — no wire message, no
	 * host authority, no protocol number. Wrapped whole because resetAll() MUST
	 * run afterwards whatever happens in here: a throw would strand taskLive
	 * and leave the party session live forever, refusing every later proposal.
	 */
	private void creditPatrons() {
		try {
			long self = safeMemberIdOrZero();
			String selfKey = accountKeyService.key();
			// keyed by ACCOUNT KEY, so one account in the party from two clients
			// under two member ids collapses to one mark before credit() ever
			// sees it — the dedupe is structural rather than a pass
			Map<String, String> partners = new LinkedHashMap<>();
			for (long id : participants) {
				if (id == self) {
					continue;
				}
				String key = AccountKey.normalize(partnerKeyCache.get(id));
				if (key == null) {
					// no identity, no mark. An older client that never sent one
					// is simply uncounted: crediting it under its display name
					// would put a second, un-mergeable row in a keyed ledger.
					continue;
				}
				if (AccountKey.same(key, selfKey)) {
					// the member id guard above only catches one CLIENT; the same
					// account dual-logged into the party arrives under a second id
					// and would otherwise make you your own patron
					continue;
				}
				// live roster first (it tracks a mid-contract rename), the
				// signature-time snapshot second (it survives a logout)
				String name = PatronMark.normalizeName(liveDisplayName(id));
				if (name == null) {
					name = PatronMark.normalizeName(partnerNameCache.get(id));
				}
				partners.put(key, name);
			}
			if (partners.isEmpty()) {
				return;
			}
			GachaState before = stateService.get();
			if (before == null) {
				return;
			}
			Map<String, PatronRecord> was = before.getPatrons();
			long now = System.currentTimeMillis();
			GachaState after = stateService.mutate(s -> {
				Map<String, PatronRecord> next = PatronMark.credit(s.getPatrons(),
					partners, Tuning.PATRON_MAX_PARTNERS, now);
				// handing back the SAME instance makes mutate short-circuit,
				// so a completion with nothing to credit pays for no encode
				return next == s.getPatrons() ? s : s.withPatrons(next);
			});
			if (after == null) {
				return;
			}
			for (Map.Entry<String, String> partner : partners.entrySet()) {
				int from = PatronMark.countFor(was, partner.getKey());
				int to = PatronMark.countFor(after.getPatrons(), partner.getKey());
				if (PatronMark.crossedTier(from, to)) {
					// the LEDGER's name, not the loop's: at the cap the newcomer
					// may have been turned away, and the stored label is the one
					// that actually belongs to the count being announced
					String name = PatronMark.displayName(
						PatronMark.recordFor(after.getPatrons(), partner.getKey()));
					chat(name + " has stood with you " + to + " times — "
						+ PatronMark.tierLabel(to) + ".");
				}
			}
		}
		catch (Exception e) {
			log.debug("patron credit failed", e);
		}
	}

	/** Snapshot the roster's names while the party is still fully synced. */
	private void rememberNames(List<Long> memberIds) {
		for (Long id : memberIds) {
			String name = PatronMark.normalizeName(liveDisplayName(id));
			if (name != null) {
				partnerNameCache.put(id, name);
			}
		}
	}

	/**
	 * Snapshot a member's account key off the handshake.
	 *
	 * The wire is the ONLY source for it — nothing in the RuneLite API maps
	 * another player to their account — so it is captured when their propose or
	 * response arrives and kept for the whole contract. By completion the
	 * stances map has long been reset and the member may have logged out
	 * entirely; the credit still has to know who they were.
	 */
	private void rememberPartner(long memberId, @Nullable String accountKey) {
		String key = AccountKey.normalize(accountKey);
		if (key != null) {
			partnerKeyCache.put(memberId, key);
		}
	}

	/**
	 * The same snapshot taken from a whole set of heard answers at once.
	 *
	 * <p>For the one moment a client inherits answers it did not hear itself:
	 * joining a proposal promotes an inbox entry whose stances arrived while the
	 * key cache belonged to a different roll, and the promotion resets that
	 * cache. Every {@link Stance} carries the key its message did, so the answers
	 * are their own record — no re-handshake, and nothing to ask the host for.
	 *
	 * <p>Normalises exactly as the single-member form does: a malformed or absent
	 * claim leaves no entry rather than a bad one, because creditPatrons keys a
	 * PERSISTED ledger by this value.
	 */
	static void rememberPartners(Map<Long, String> cache, Map<Long, Stance> heard) {
		for (Map.Entry<Long, Stance> answer : heard.entrySet()) {
			String key = AccountKey.normalize(answer.getValue().getAccountKey());
			if (key != null) {
				cache.put(answer.getKey(), key);
			}
		}
	}

	/**
	 * The roster's raw display name, or null. This is the form the LEDGER paths
	 * take (rememberNames, creditPatrons), and the null must survive: memberName()
	 * wraps this one to add its "A party member" fallback, which is right for a
	 * chat line and must NEVER be persisted as though it were a partner.
	 */
	@Nullable
	private String liveDisplayName(long memberId) {
		try {
			PartyMember member = partyService.getMemberById(memberId);
			return member == null ? null : member.getDisplayName();
		}
		catch (Exception e) {
			return null;
		}
	}

	// =====================================================================
	// WATCHDOG
	// =====================================================================

	@Subscribe
	public void onGameTick(GameTick tick) {
		int now = client.getTickCount();
		// Republish before anything below moves: the panel and the offer scrolls
		// read only the snapshot now, and several paths reach a new state without
		// poking the panel at all — the sit-out branch of onPartyRollStartMessage
		// resets the whole session in silence. One publish a tick puts a floor of
		// 0.6s under how stale a card can be and keeps the countdowns moving; it
		// is free while nothing is live, because publishView hands back IDLE
		// without allocating.
		publishView();
		// Both shot clocks below are absolute stamps taken from the tick count that
		// was running when the phase opened, and a relog or world hop RESTARTS that
		// count from near zero. The stamp then reads as far in the future, its branch
		// stops firing for as long as the old count was high — up to several hours —
		// and proposalLive/votingLive stay true the whole time, which keeps
		// localBusy() true and locks the player out of rolling again for the session.
		// The re-anchor block further down handles the same hazard for the carry
		// clause, but it sits below the `!taskLive` gate and so never runs during a
		// vote. Nothing legitimate is ever further out than its own TTL plus the
		// non-host grace, so anything beyond that is a restarted clock, not patience.
		// Subtraction rather than `>` for the same reason as voteExpired().
		if (proposalLive && proposalExpiresAtTick - now > PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS) {
			proposalExpiresAtTick = now + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;
		}
		if (votingLive && voteExpiresAtTick - now > VOTE_TTL_TICKS + NON_HOST_GRACE_TICKS) {
			voteExpiresAtTick = now + VOTE_TTL_TICKS + NON_HOST_GRACE_TICKS;
		}
		// inbox cards carry their own deadline: without this a host who logs off
		// leaves a Join button on screen forever, and clicking it would answer a
		// proposal nobody is collecting. Same clock-jump guard as the committed
		// one above — a hostile or restarted client must not pin a card open.
		if (!inbox.isEmpty()) {
			boolean dropped = false;
			for (Iterator<Inbox> it = inbox.values().iterator(); it.hasNext(); ) {
				Inbox entry = it.next();
				if (entry.expiresAtTick - now > PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS) {
					entry.expiresAtTick = now + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;
				}
				if (now >= entry.expiresAtTick) {
					it.remove();
					dropped = true;
				}
			}
			if (dropped) {
				refreshPanel();
			}
		}
		// Redraw while anything is counting down. Every countdown on the panel —
		// the cards, the pending line, the vote clock — is derived from the tick
		// counter, and nothing else on those screens changes while a proposal
		// sits collecting answers, so without this the numbers freeze at whatever
		// they read when the last unrelated event happened to fire. Every other
		// tick rather than every one: a whole-panel rebuild under the pointer is
		// the thing the player is about to click Join on.
		if ((proposalLive || votingLive || !inbox.isEmpty()) && now % 2 == 0) {
			refreshPanel();
		}
		if (proposalLive && now >= proposalExpiresAtTick) {
			if (safeMemberIdOrZero() == proposerId) {
				// deadline: start with whoever agreed (min 2) or cancel
				evaluateProposal();
			}
			else {
				// grace passed with no start message — the proposer is gone
				cancelProposal("The party roll proposal expired.");
			}
			return;
		}
		// checked EVERY tick, deliberately NOT folded into the %25 sweep below:
		// that sweep's phase differs per client, so a straggler clicking inside
		// the skew would leave the already-expired clients demoted while the rest
		// settle the vote and sign a shared contract nobody else is on.
		if (votingLive && voteExpired(now, voteExpiresAtTick)) {
			if (safeMemberIdOrZero() == proposerId) {
				// one clock, not N: the host settles the vote for everyone, and
				// broadcasts either the winning contract or a cancel, so every
				// client lands the same way
				hostResolve("The vote timed out");
			}
			else {
				// grace passed with no word from the host — the host is gone
				cancelVoting("The party roll went quiet — the vote timed out.");
			}
			return;
		}
		if (votingLive && (now % 25 == 0)) {
			evaluateVotes(); // roster changes cancel even without a vote arriving
		}
		if (!taskLive || now % 25 != 0) {
			return;
		}
		// A relog or world hop restarts getTickCount(), which leaves every stamp
		// below reading as far in the FUTURE — and a future stamp parks its timer
		// for as long as the old count was high, so the carry clause would simply
		// stop existing for an hour. Re-anchor rather than stall.
		if (lastOthersProgressTick > now) {
			lastOthersProgressTick = now;
		}
		if (resumedAtTick > now) {
			resumedAtTick = now;
		}
		if (othersGoneSinceTick > now) {
			othersGoneSinceTick = now;
		}
		// A restarting client comes back under a BRAND NEW member id, and the only
		// thing that has ever announced one is a KILL — onPartyProgress fires on
		// progress and on nothing else. So a partner who restarts and then banks,
		// walks or reads their quest log announces nothing at all: the other side
		// goes on watching for an id that will never speak again, and converts the
		// shared contract to solo the moment PARTY_DEPART_GRACE_TICKS runs out, at
		// 0.8x and dropping the returning partner's banked kills. Beacon exactly the
		// message progress would have sent, on the sweep, for as long as the other
		// side's departure grace can still be running — after that they have settled
		// and there is nothing left to re-seat.
		//
		// Deliberately NOT gated on participants.isEmpty(): hearing from ONE partner
		// empties that condition, and in a party of three the partner who has not
		// spoken is precisely the one still needing to hear from us.
		if (resumedAtTick >= 0 && now - resumedAtTick <= Tuning.PARTY_DEPART_GRACE_TICKS) {
			GachaState resumeState = stateService.get();
			ActiveTask resumeTask = resumeState == null ? null : resumeState.getActiveTask();
			if (resumeTask != null && resumeTask.isParty() && safeLocalMember() != null) {
				safeSend(new PartyKillsMessage(proposalId, resumeTask.getKillsDone()));
			}
		}
		// carry clause: all other participants gone, or nobody progressing
		Set<Long> roster = rosterIds();
		Set<Long> others = new HashSet<>(participants);
		others.remove(safeMemberIdOrZero());
		boolean knewOthers = !others.isEmpty();
		others.retainAll(roster);
		boolean anyOtherPresent = !others.isEmpty();
		// latch the moment the last one left, so the grace measures how long they
		// have been gone rather than restarting on every sweep
		if (!knewOthers || anyOtherPresent) {
			othersGoneSinceTick = -1;
		}
		else if (othersGoneSinceTick < 0) {
			othersGoneSinceTick = now;
		}
		boolean everyoneGone = everyoneGone(knewOthers, anyOtherPresent,
			othersGoneSinceTick < 0 ? 0 : now - othersGoneSinceTick,
			resumedAtTick >= 0, now - resumedAtTick, roster.size());
		boolean idle = now - lastOthersProgressTick > Tuning.PARTY_IDLE_TICKS;
		if (everyoneGone || idle) {
			GachaState state = stateService.get();
			ActiveTask task = state == null ? null : state.getActiveTask();
			if (task != null && task.isParty()) {
				taskService.convertPartyToSolo();
				chat((everyoneGone
					? "Your party has left. Carry clause: the contract continues solo at "
					: "Your party has gone quiet. Carry clause: the contract continues solo at ")
					+ CARRY_PAY);
			}
			resetAll();
		}
	}

	// =====================================================================
	// Helpers
	// =====================================================================

	private void cancelProposal(String message) {
		if (proposalLive) {
			proposalLive = false;
			stances.clear();
			chat(message);
			refreshPanel();
		}
	}

	private void cancelVoting(String message) {
		votingLive = false;
		// Close the scrolls first if they are on screen. The payload up there
		// still reads "vote", but the click behind it is about to mean "sign this
		// contract, solo, forever" — and the chat line explaining that is hidden
		// under the modal. Making the player reopen the board is the only way the
		// meaning change is visible before they act on it.
		abortCeremony();
		// Asked BEFORE the demote, which clears the party flag it tests. The
		// second sentence is only true of a client that is actually holding the
		// rolled board: a host who sat out its own roll never had one presented
		// (see executeRoll) and would be sent looking for contracts that are not
		// there, and the same is true of applyResolve's branch where
		// acceptPartyOffer has just failed on an emptied slot. Every
		// client that DOES hold the board still reads exactly the line it always
		// has — this suppresses the sentence only where it is false.
		boolean held = taskService.hasPendingPartyOffers();
		// rolls cannot be undone: the offers stay, demoted to personal ones
		taskService.demotePartyOffers();
		chat(message + (held ? " The rolled contracts remain — pick one for yourself." : ""));
		resetAll();
		refreshPanel();
	}

	private void resetAll() {
		proposalLive = false;
		votingLive = false;
		taskLive = false;
		resumedAtTick = -1;
		othersGoneSinceTick = -1;
		stances.clear();
		votes.clear();
		anteVotes.clear();
		partyKills.clear();
		participants = new HashSet<>();
		partyOffers = null;
		partyStyles = null;
		rollOrder = null;
		anchorSeed = 0;
		mySeedCandidate = 0;
		// null and not the local default: a stale host choice surviving into the
		// next proposal would size someone else's roll by the last host's setting
		hostSizingMode = null;
		partnerNameCache.clear();
		partnerKeyCache.clear();
	}

	/**
	 * Debug support (::gachacleartask) and RS profile switches: drop any
	 * proposal/vote/shared-task state.
	 */
	public void resetForDebug() {
		resetAll();
		// resetAll clears the COMMITTED proposal only; the inbox is a separate
		// list of other people's offers, and a debug reset means all of it
		inbox.clear();
	}

	/**
	 * Called once per state load. Everything the party layer keeps lives in this
	 * @Singleton's fields and dies with the process, while the OFFERS and the
	 * CONTRACT are persisted — so a load can surface either half of a session
	 * whose other half is gone. Both are settled here.
	 *
	 * The guard is what makes this safe on a world hop or a plugin re-enable,
	 * where the fields survived and the session is genuinely still live; it is
	 * only a valid proxy for "a live session owns THIS" because a profile switch
	 * tears the session down (see GachamanPlugin's onRuneScapeProfileChanged) —
	 * offers and contracts are per-profile, the session is not.
	 */
	public void recoverPartySession() {
		if (proposalLive || votingLive || taskLive) {
			return;
		}
		if (taskService.hasPendingPartyOffers()) {
			// A vote whose session died: every click would route into a tally
			// nobody is counting, and the roll gate would stay shut because these
			// offers are still pending. Same exit as a host cancel or a shrunken
			// party — one path, so a recovered client is byte-identical to a
			// cancelled one. An accepted contract clears the offers, so this and
			// the resurrection below can never both apply.
			cancelVoting("The party roll behind your rolled contracts is gone.");
			return;
		}
		resurrectPartyContract();
	}

	/**
	 * Rebuild the session behind a shared contract that came back off disk.
	 *
	 * Without this a client that restarts mid-contract loads a task whose
	 * isParty() is true forever while taskLive is false: it broadcasts nothing,
	 * hears nothing, can never complete as a party and — because the watchdog is
	 * the only thing that ever converts one — never converts either. The contract
	 * would keep its 1.6x with no party to earn it, and the ironman assisted-kill
	 * rule, which stands down for the duration of a shared contract, would stay
	 * down for good. Restarting was, in effect, the way out of both.
	 *
	 * Only the proposal id is restored, because it is the only persisted thing
	 * that still means anything on the wire. {@link #participants} is left EMPTY
	 * on purpose and refilled by whoever calls in quoting that id, so a live party
	 * resyncs on its next kill with no handshake and no penalty, and an abandoned
	 * one is settled by the existing carry clause on the existing terms.
	 */
	private void resurrectPartyContract() {
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null || !task.isParty()) {
			return;
		}
		if (task.getPartyProposalId() == null) {
			// Signed before the id was persisted, so nothing identifies it on the
			// wire and no partner could ever be matched to it. Left alone it would
			// hold the shared multiplier forever; settled here on exactly the terms
			// the carry clause would have reached had it been able to run.
			taskService.convertPartyToSolo();
			chat("Your shared contract predates party resume and cannot be rejoined."
				+ " Carry clause: it continues solo at " + CARRY_PAY);
			refreshPanel();
			return;
		}
		proposalId = task.getPartyProposalId();
		taskLive = true;
		resumedAtTick = client.getTickCount();
		lastOthersProgressTick = resumedAtTick;
		othersGoneSinceTick = -1; // we know of nobody to be missing yet
		participants = new HashSet<>();
		partyKills.clear();
		// partyKills starts empty while the contract remembers partyOtherKills, and
		// that is the right way round: syncPartyKills only ever moves its high-water
		// mark UP, so the pooled progress already banked is safe and the party simply
		// has to out-report it — which its next broadcast does, since every client
		// sends its own running total rather than a delta.
		chat("Your shared contract is still running. Waiting for the party to check in"
			+ " — if nobody does, the carry clause continues it solo at " + CARRY_PAY);
		refreshPanel();
	}

	/**
	 * Subtraction rather than {@code now >= expiresAt} so a deadline that has
	 * already slipped past can never read as not-yet-due.
	 */
	static boolean voteExpired(int now, int expiresAt) {
		return now - expiresAt >= 0;
	}

	/**
	 * Has every other participant left, so the carry clause should fire NOW rather
	 * than wait out the idle timer?
	 *
	 * The plain reading — "no other participant is still in the party" — is wrong for
	 * a contract resurrected from disk, because such a contract knows NOBODY: member
	 * ids do not survive a client restart, so the participant set starts empty and is
	 * refilled by whoever calls in ({@link #admitReturningMember}). Reading that
	 * emptiness as "everyone left" would convert a perfectly live party on the very
	 * first sweep after a relog, at 0.8x, with nobody having gone anywhere. So when we
	 * never knew any others, this parks on the idle timer instead and lets the normal
	 * ten-minute rule settle it.
	 *
	 * The one exception is a party of one. Nobody can call in from an empty roster, so
	 * parking there would mean a player who relogs every few minutes keeps a dead
	 * party's 1.6x indefinitely — each restart hands the contract a fresh idle window.
	 * The resync grace exists for that branch alone: RuneLite rejoins the previous
	 * party ASYNCHRONOUSLY, so the roster is very often still empty at the moment the
	 * save loads, and a roster of one right then means "not back yet", not "alone".
	 *
	 * The known-partner branch gets a grace of its own, for the same restart seen from
	 * the other side. A closing client leaves the roster at once, so firing the instant
	 * the last partner disappears would convert the contract about fifteen seconds into
	 * their restart — and every resurrection would then come back to a contract that
	 * had already been settled without it. See {@link Tuning#PARTY_DEPART_GRACE_TICKS}.
	 *
	 * @param knewOthers            this session saw at least one other participant
	 * @param anyOtherStillInRoster one of those others is in the party right now
	 * @param ticksSinceOthersGone  ticks since the last one left (0 if any remain)
	 * @param resumed               the contract was resurrected from disk this session
	 * @param ticksSinceResume      ticks since that resurrection (ignored if !resumed)
	 * @param rosterSize            party members right now, self included
	 */
	static boolean everyoneGone(boolean knewOthers, boolean anyOtherStillInRoster,
		int ticksSinceOthersGone, boolean resumed, int ticksSinceResume, int rosterSize) {
		if (knewOthers) {
			return !anyOtherStillInRoster
				&& ticksSinceOthersGone > Tuning.PARTY_DEPART_GRACE_TICKS;
		}
		return resumed && ticksSinceResume > Tuning.PARTY_RESYNC_TICKS && rosterSize <= 1;
	}

	/**
	 * Drop pooled-kill entries whose member id is no longer in the party.
	 *
	 * Called only when re-seating a returning member, where the stale entry is that
	 * same player's pre-restart id and would otherwise be summed alongside their new
	 * one. Safe by construction: a drop can only LOWER the pooled total, and
	 * TaskService.syncPartyKills ignores any total that fails to beat the high-water
	 * mark already banked on the contract.
	 */
	static void dropDepartedKills(Map<Long, Integer> partyKills, Set<Long> roster) {
		partyKills.keySet().removeIf(id -> !roster.contains(id));
	}

	/**
	 * The party's fighting weight: the FLOOR of its members' combat levels.
	 *
	 * Deliberately NOT clamped toward the weakest member. Every clamp that was
	 * considered fails on the very case the rule exists for. An additive one
	 * (min + 30) sizes a party of level-126s around a level-3 at 33 — it hands
	 * back the full-price-contract-for-a-trivial-monster exploit precisely
	 * where it pays most. A multiplicative one (min * 1.5) scales the giveaway
	 * DOWN with the weakest member's level, the same failure inverted. And a
	 * clamp that guarantees the weakest can still solo every offer reduces,
	 * arithmetically, to "size by the weakest" — it IS the old rule.
	 *
	 * The weakest member is protected structurally instead: the quota is POOLED
	 * so his party carries it, per-kill GC still runs on HIS own combat level
	 * (TaskService keeps that multiplier chain local), the EASY band still
	 * floors at 0, and the roll is opt-in and majority-voted with the sizing
	 * level printed in chat before anyone votes.
	 *
	 * Levels are clamped INDIVIDUALLY before averaging — see
	 * {@link Tuning#COMBAT_LEVEL_MIN}.
	 */
	static int fightingWeight(@Nullable List<Integer> combatLevels) {
		if (combatLevels == null || combatLevels.isEmpty()) {
			// not 0: a 0 collapses TaskGenerator's cap to max(2, 0) and rolls a
			// degenerate board of the two lowest monsters in the table
			return Tuning.COMBAT_LEVEL_MIN;
		}
		long sum = 0;
		for (int level : combatLevels) {
			sum += Math.max(Tuning.COMBAT_LEVEL_MIN, Math.min(Tuning.COMBAT_LEVEL_MAX, level));
		}
		// the clamp keeps the sum non-negative, so integer division is a true floor
		return (int) (sum / combatLevels.size());
	}

	/**
	 * The party's weakest man: the LOWEST of its members' combat levels.
	 *
	 * The host's alternative to {@link #fightingWeight}, for a party that would
	 * rather every contract be one its smallest member could have taken alone
	 * than one worth the party's weight. Nothing else about a shared contract
	 * changes: the quota is still pooled, per-kill GC still runs on each
	 * member's own combat level, and the roll is still opt-in and majority-voted.
	 *
	 * Clamped per level exactly as {@link #fightingWeight} is, and for the same
	 * reason — a 0 collapses TaskGenerator's cap to max(2, 0) and rolls a
	 * degenerate board of the two lowest monsters in the table. That makes it
	 * deliberately NOT identical to {@link #legacyLowest}, which must stay
	 * unclamped; the two never apply to the same roll.
	 */
	static int weakestMan(@Nullable List<Integer> combatLevels) {
		if (combatLevels == null || combatLevels.isEmpty()) {
			return Tuning.COMBAT_LEVEL_MIN;
		}
		int lowest = Tuning.COMBAT_LEVEL_MAX;
		for (int level : combatLevels) {
			lowest = Math.min(lowest,
				Math.max(Tuning.COMBAT_LEVEL_MIN, Math.min(Tuning.COMBAT_LEVEL_MAX, level)));
		}
		return lowest;
	}

	/**
	 * The pre-Fighting-Weight rule: the raw, UNCLAMPED lowest transmitted level.
	 *
	 * Reproduces the old arithmetic exactly, bug-for-bug, because a party that
	 * falls back to it contains a client running that very code — and "nearly
	 * the same level" deals a different board. Under this rule a broken or
	 * hostile combat level could only ever make contracts EASIER, which is why
	 * it was self-limiting without a clamp and why every rule after it needs one.
	 *
	 * The empty case is unreachable (a roll needs 2 agreed participants) and
	 * answers with the floor rather than Integer.MAX_VALUE, which is the one
	 * place this deviates from the original.
	 */
	static int legacyLowest(@Nullable List<Integer> combatLevels) {
		if (combatLevels == null || combatLevels.isEmpty()) {
			return Tuning.COMBAT_LEVEL_MIN;
		}
		int lowest = Integer.MAX_VALUE;
		for (int level : combatLevels) {
			lowest = Math.min(lowest, level);
		}
		return lowest;
	}

	/**
	 * The combat level this roll sizes to — the ONLY place the three rules are
	 * chosen between, and a pure function of the TRANSMITTED stances plus the
	 * host's transmitted choice. Nothing local feeds it, which is what lets two
	 * clients claim they computed the same number.
	 */
	static int sizingLevel(@Nullable List<Integer> combatLevels,
		@Nullable List<Integer> rollProtocols, @Nullable String hostSizingMode) {
		if (!meanSizingAgreed(rollProtocols)) {
			return legacyLowest(combatLevels);
		}
		return resolvedSizing(rollProtocols, hostSizingMode) == PartySizing.WEAKEST_MAN
			? weakestMan(combatLevels)
			: fightingWeight(combatLevels);
	}

	/**
	 * The rule the party can actually run, given the host's choice and what its
	 * members' builds understand. Weakest Man needs EVERY client to be reading
	 * the choice off the wire; one that is not would size to the average and
	 * deal a different board, so the whole party takes the average instead.
	 * Falling back to the host's own preference is not an option — the fallback
	 * has to be the value an unaware client would have picked.
	 */
	static PartySizing resolvedSizing(@Nullable List<Integer> rollProtocols,
		@Nullable String hostSizingMode) {
		return sizingChoiceAgreed(rollProtocols)
			? PartySizing.fromWire(hostSizingMode)
			: PartySizing.FIGHTING_WEIGHT;
	}

	/** What the pre-vote disclosure calls the rule it just applied. */
	static String sizingRuleLabel(@Nullable List<Integer> rollProtocols,
		@Nullable String hostSizingMode) {
		return meanSizingAgreed(rollProtocols)
			? resolvedSizing(rollProtocols, hostSizingMode).toString()
			: "lowest level, a member is on a pre-Fighting Weight build";
	}

	/**
	 * All-or-nothing: one participant on an older build puts the WHOLE party
	 * back on the lowest-level rule. Sizing changes the eligible pool, the pool
	 * changes the bound that rng.pick draws against, and a different bound
	 * consumes a different number of Random.next() calls — so two clients
	 * rolling one seed under two rules diverge from the first pick onward. They
	 * would then vote by INDEX on boards they never saw, and party contracts
	 * are binding.
	 */
	static boolean meanSizingAgreed(@Nullable List<Integer> rollProtocols) {
		return everyoneAtLeast(rollProtocols, ROLL_PROTOCOL_FIGHTING_WEIGHT);
	}

	/**
	 * The same all-or-nothing gate one rung up: can every participant read the
	 * host's sizing choice off the wire? A protocol-1 client cannot, and sizes
	 * to the average no matter what the host set.
	 */
	static boolean sizingChoiceAgreed(@Nullable List<Integer> rollProtocols) {
		return everyoneAtLeast(rollProtocols, ROLL_PROTOCOL_SIZING_CHOICE);
	}

	/**
	 * The same all-or-nothing gate one rung up again: does every participant
	 * filter quest-locked monsters out of the pool? A protocol-2 client does
	 * not, and would deal from the whole table.
	 */
	static boolean questGateAgreed(@Nullable List<Integer> rollProtocols) {
		return everyoneAtLeast(rollProtocols, ROLL_PROTOCOL_QUEST_GATE);
	}

	/**
	 * The quests the WHOLE party has finished — the intersection of what each
	 * agreeing member transmitted, or null when a member's build cannot filter
	 * on them at all and the party must roll the unfiltered pool to stay in
	 * step. Pure: nothing local feeds it, so two clients handed the same
	 * stances compute the same set.
	 *
	 * <p>A member who is on the current protocol but sent nothing is read as
	 * having finished nothing, not as "ignore me". That withholds monsters
	 * rather than offering them, which is the direction a bad answer should
	 * fail in — the party is never dealt a contract on the strength of a null.
	 *
	 * <p>Set semantics throughout: members answer in whatever order the roster
	 * happens to iterate in, and the pool filter is a {@code containsAll}, so
	 * two clients that walked the same members in different orders still agree
	 * monster for monster.
	 */
	@Nullable
	static Set<String> agreedQuests(@Nullable List<List<String>> perMember,
		@Nullable List<Integer> rollProtocols) {
		if (!questGateAgreed(rollProtocols) || perMember == null || perMember.isEmpty()) {
			return null;
		}
		Set<String> shared = null;
		for (List<String> theirs : perMember) {
			if (theirs == null) {
				return Collections.emptySet();
			}
			if (shared == null) {
				shared = new HashSet<>(theirs);
			}
			else {
				shared.retainAll(theirs);
			}
		}
		return Collections.unmodifiableSet(shared == null ? new HashSet<>() : shared);
	}

	private static boolean everyoneAtLeast(@Nullable List<Integer> rollProtocols, int required) {
		if (rollProtocols == null || rollProtocols.isEmpty()) {
			// no answer is not a yes: an unknown roster never assumes the newer rule
			return false;
		}
		for (int protocol : rollProtocols) {
			// >= and not ==, so a FUTURE protocol is never misread as legacy
			if (protocol < required) {
				return false;
			}
		}
		return true;
	}

	private void sendResponse(int response) {
		Stance mine = localStance(response);
		PartyMember local = safeLocalMember();
		if (local != null) {
			stances.put(local.getMemberId(), mine);
		}
		sendStance(proposalId, response, mine);
	}

	private Stance localStance(int response) {
		GachaState state = stateService.get();
		return new Stance(response, mySeedCandidate,
			taskService.localIsMembers(), taskService.playerCombatLevel(),
			client.getRealSkillLevel(Skill.SLAYER),
			state == null ? null : state.getAllowedStyle(), ROLL_PROTOCOL,
			// only the gating quests, sorted — the full 195 would be most of a
			// kilobyte of wire per member to say nothing the pool filter reads
			questUnlockService.completedQuestsForWire(),
			accountKeyService.key());
	}

	private boolean localBusy() {
		GachaState state = stateService.get();
		return state == null || state.getActiveTask() != null
			|| (state.getPendingOffers() != null && !state.getPendingOffers().isEmpty());
	}

	private Set<Long> rosterIds() {
		Set<Long> ids = new HashSet<>();
		try {
			for (PartyMember member : partyService.getMembers()) {
				ids.add(member.getMemberId());
			}
		}
		catch (Exception e) {
			log.debug("party roster read failed", e);
		}
		return ids;
	}

	/** A member who has not rolled a style yet, or an older client, contributes none. */
	@Nullable
	static AttackStyle parseStyle(@Nullable String name) {
		if (name == null) {
			return null;
		}
		try {
			return AttackStyle.valueOf(name);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String describeOffer(TaskOffer offer) {
		return offer.getKillsRequired() + "x " + offer.getMonsterName()
			+ " (" + offer.getDifficulty().getDisplayName() + ")";
	}

	/**
	 * A vote, named rather than numbered: "Goblin [EASY]".
	 *
	 * <p>Shorter than {@link #describeOffer} because this one sits beside a
	 * player's name in a fixed-width column, not in a chat line. The kill count
	 * is dropped for the same reason — what a reader wants off a vote list is
	 * WHICH contract someone backed, and an index into a board they may have
	 * scrolled away from answers that only if they can still see the board.
	 */
	private String offerLabel(int index) {
		if (partyOffers == null || index < 0 || index >= partyOffers.size()) {
			return null;
		}
		TaskOffer offer = partyOffers.get(index);
		String tier = offer.isRedemption()
			? "REDEMPTION"
			: offer.getDifficulty().getDisplayName().toUpperCase(Locale.ROOT);
		return offer.getMonsterName() + " [" + tier + "]";
	}

	/**
	 * One member's vote as the panels print it: the contract they backed, and
	 * whether they staked on it.
	 *
	 * <p>The Ante rides along because it binds by UNANIMITY, not majority. A
	 * member reading the list needs to know that the wager is one refusal away
	 * from being off — a column that showed only the contract would have the
	 * party agreeing on the work while silently disagreeing about the money.
	 */
	@Nullable
	private String voteLabelFor(long memberId, int index) {
		String label = offerLabel(index);
		if (label == null) {
			return null;
		}
		Boolean ante = anteVotes.get(memberId);
		return ante != null && ante ? label + "  +ante" : label;
	}

	/**
	 * A member's name for a CHAT line or a card, with a fallback that is always
	 * printable. {@link #liveDisplayName} answers the same question and returns
	 * raw null for all three failure modes this one covers — no party, no such
	 * member, no display name — so the fallback is the only thing added here.
	 */
	private String memberName(long memberId) {
		String name = liveDisplayName(memberId);
		return name == null ? "A party member" : name;
	}

	@Nullable
	private PartyMember safeLocalMember() {
		try {
			return partyService.getLocalMember();
		}
		catch (Exception e) {
			return null;
		}
	}

	private long safeMemberIdOrZero() {
		PartyMember local = safeLocalMember();
		return local == null ? 0 : local.getMemberId();
	}

	private boolean isSelfEcho(long memberId) {
		PartyMember local = safeLocalMember();
		return local == null || memberId == local.getMemberId();
	}

	/**
	 * The two steps every peer message takes before its body may run: drop this
	 * client's own echo, and hop off the EventBus thread onto the client thread,
	 * which is the sole writer of every map this service keeps (see {@link View}).
	 *
	 * <p>Written once rather than at the top of each of the eight peer-message
	 * handlers, which differ only in what they do AFTER those two steps. The
	 * handlers themselves keep their signatures — RuneLite's EventBus dispatches
	 * on the concrete parameter type, so only the bodies moved in here.
	 *
	 * <p>The null guard is carried over exactly as the eight handlers spelled it,
	 * deliberately unexamined: whether the bus can deliver one is the bus's
	 * business, and this refactor is not the place to find out.
	 */
	private void fromPeer(@Nullable PartyMemberMessage msg, Runnable body) {
		if (msg != null && !isSelfEcho(msg.getMemberId())) {
			clientThread.invokeLater(body);
		}
	}

	private boolean safeSend(PartyMemberMessage msg) {
		try {
			partyService.send(msg);
			return true;
		}
		catch (Exception e) {
			log.debug("party send failed", e);
			return false;
		}
	}

	private void chat(String message) {
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> " + message)
			.build());
	}

	// --- TaskService.Listener no-ops ---

}
