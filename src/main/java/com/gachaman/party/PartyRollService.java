package com.gachaman.party;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskOffer;
import com.gachaman.service.GachaRng;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.PatronMark;
import com.gachaman.service.TaskGenerator;
import com.gachaman.service.TaskService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;

/**
 * The party contract layer.
 *
 * A party roll is opt-in for every TASK-LESS member: one member proposes,
 * members with an active contract auto-report busy, and once the rest have
 * answered the roll executes DETERMINISTICALLY on every client — all
 * participants roll with the seed candidate of the participant with the
 * LOWEST member id, the pool restricted to free-to-play when ANY participant
 * is free (membership is exchanged in this handshake, nowhere else), sized to
 * the party's AVERAGE combat level and gated by its LOWEST slayer level.
 * Identical offers appear on every screen; clicking one casts a VOTE.
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
public class PartyRollService implements TaskService.Listener
{
	private static final int PROPOSAL_TTL_TICKS = 100;
	/**
	 * The voting phase's shot clock — ~2 minutes, double the proposal window,
	 * because reading four contracts takes longer than answering yes/no. Like
	 * the proposal's deadline it is the HOST's clock that decides; every other
	 * client only self-cancels after a grace period, if the host went silent.
	 */
	private static final int VOTE_TTL_TICKS = 200;

	/**
	 * Roll-rule version. Bumped ONLY when a change would make two clients
	 * generate different offers from the same seed — here, sizing by the
	 * party's average rather than its lowest combat level. The mixed-version
	 * fallback is all-or-nothing on purpose: a party is only ever on one rule
	 * at a time, because half a party on each is exactly the split it prevents.
	 */
	static final int ROLL_PROTOCOL_FIGHTING_WEIGHT = 1;
	static final int ROLL_PROTOCOL = ROLL_PROTOCOL_FIGHTING_WEIGHT;

	@Value
	private static class Stance
	{
		int response; // PartyRollResponseMessage.AGREE / DECLINE / BUSY
		long seedCandidate;
		boolean members;
		int combatLevel;
		int slayerLevel;
		String allowedStyle; // AttackStyle name; null from a pre-clash-bonus client
		int rollProtocol;    // 0 from a client that predates Fighting Weight
	}

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final MonsterTable monsterTable;
	private final com.gachaman.GachamanConfig config;

	// --- proposal / vote / task state (transient; one proposal at a time) ---
	private long proposalId;
	private boolean proposalLive;
	private int proposalExpiresAtTick;
	/** The proposal's authority: the client that fixes the participant set. */
	private long proposerId;
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

	/** Plugin-wired: pokes the sidebar so host/status widgets track proposals. */
	@Nullable
	private Runnable refreshHook;

	public void setRefreshHook(@Nullable Runnable hook)
	{
		this.refreshHook = hook;
	}

	private void refreshPanel()
	{
		if (refreshHook != null)
		{
			try
			{
				refreshHook.run();
			}
			catch (Exception e)
			{
				log.debug("panel refresh hook failed", e);
			}
		}
	}

	/** Plugin-wired: force-closes a modal ceremony whose meaning just changed. */
	@Nullable
	private Runnable ceremonyAbortHook;

	public void setCeremonyAbortHook(@Nullable Runnable hook)
	{
		this.ceremonyAbortHook = hook;
	}

	private void abortCeremony()
	{
		if (ceremonyAbortHook != null)
		{
			try
			{
				ceremonyAbortHook.run();
			}
			catch (Exception e)
			{
				log.debug("ceremony abort hook failed", e);
			}
		}
	}

	@Inject
	public PartyRollService(Client client, ClientThread clientThread, PartyService partyService,
		TaskService taskService, GachaStateService stateService,
		ChatMessageManager chatMessageManager, MonsterTable monsterTable,
		com.gachaman.GachamanConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.taskService = taskService;
		this.stateService = stateService;
		this.chatMessageManager = chatMessageManager;
		this.monsterTable = monsterTable;
		this.config = config;
	}

	// =====================================================================
	// PROPOSAL
	// =====================================================================

	/** Local player proposes a party roll (panel button / ::gachaparty). */
	public void propose()
	{
		clientThread.invokeLater(() -> {
			if (!partyService.isInParty() || safeLocalMember() == null)
			{
				chat("You are not in a party.");
				return;
			}
			if (partyService.getMembers().size() < 2)
			{
				chat("A party roll needs at least one other member.");
				return;
			}
			if (!config.partyRollsEnabled())
			{
				chat("Party contracts are disabled in your Gachaman settings.");
				return;
			}
			if (proposalLive || votingLive)
			{
				chat("A party roll is already in progress.");
				return;
			}
			if (localBusy())
			{
				chat("You have a contract or undecided rolls — party rolls are for members"
					+ " with a clean slate (rolls cannot be undone).");
				return;
			}
			resetAll();
			proposalId = ThreadLocalRandom.current().nextLong();
			mySeedCandidate = ThreadLocalRandom.current().nextLong();
			proposalLive = true;
			proposerId = safeMemberIdOrZero();
			proposalExpiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS;
			Stance mine = localStance(PartyRollResponseMessage.AGREE);
			stances.put(safeLocalMember().getMemberId(), mine);
			safeSend(new PartyRollProposeMessage(proposalId, mine.getSeedCandidate(),
				mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel(),
				mine.getAllowedStyle(), mine.getRollProtocol()));
			chat("Party roll proposed — ::gachaparty to join. It starts once everyone answers"
				+ " (or in ~60s) with whoever agreed, minimum 2. As host you can Start Roll"
				+ " early from the Overview tab.");
			evaluateProposal();
			refreshPanel();
		});
	}

	/** ::gachaparty — agree to the live proposal (or propose when none). */
	public void agree()
	{
		clientThread.invokeLater(() -> {
			if (!proposalLive)
			{
				propose();
				return;
			}
			PartyMember local = safeLocalMember();
			if (local == null)
			{
				return;
			}
			if (stances.containsKey(local.getMemberId()))
			{
				chat("You already answered this party roll.");
				return;
			}
			if (!config.partyRollsEnabled())
			{
				chat("Party contracts are disabled in your Gachaman settings — you sit out.");
				sendResponse(PartyRollResponseMessage.BUSY);
				return;
			}
			if (localBusy())
			{
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
	public void decline()
	{
		clientThread.invokeLater(() -> {
			if (!proposalLive || safeLocalMember() == null)
			{
				chat("No party roll to decline.");
				return;
			}
			sendResponse(PartyRollResponseMessage.DECLINE);
			chat("You sit this party roll out — the others may still task up.");
			evaluateProposal();
		});
	}

	@Subscribe
	public void onPartyRollProposeMessage(PartyRollProposeMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (proposalLive || votingLive)
			{
				return; // one at a time; the stale side expires on its own
			}
			resetAll();
			proposalId = msg.getProposalId();
			mySeedCandidate = ThreadLocalRandom.current().nextLong();
			proposalLive = true;
			proposerId = msg.getMemberId();
			// small grace past the proposer's deadline: the proposer decides,
			// this client only times out when no start message ever arrives
			proposalExpiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS + 25;
			stances.put(msg.getMemberId(), new Stance(PartyRollResponseMessage.AGREE,
				msg.getSeedCandidate(), msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel(),
				msg.getAllowedStyle(), msg.getRollProtocol()));
			String name = memberName(msg.getMemberId());
			if (!config.partyRollsEnabled())
			{
				// setting-off members count as ineligible: excuse immediately so
				// the proposer never waits on them
				sendResponse(PartyRollResponseMessage.BUSY);
				stances.put(safeMemberIdOrZero(), localStance(PartyRollResponseMessage.BUSY));
				chat(name + " proposed a party roll — party contracts are disabled in your"
					+ " Gachaman settings, so you sit out.");
			}
			else if (localBusy())
			{
				sendResponse(PartyRollResponseMessage.BUSY);
				stances.put(safeMemberIdOrZero(), localStance(PartyRollResponseMessage.BUSY));
				chat(name + " proposed a party roll — you have a contract or undecided rolls"
					+ " and sit out.");
			}
			else
			{
				chat(name + " proposed a party roll — ::gachaparty to join, ::gachaparty no to sit out.");
			}
			refreshPanel();
		});
	}

	@Subscribe
	public void onPartyRollResponseMessage(PartyRollResponseMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (!proposalLive || msg.getProposalId() != proposalId)
			{
				return;
			}
			stances.put(msg.getMemberId(), new Stance(msg.getResponse(), msg.getSeedCandidate(),
				msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel(), msg.getAllowedStyle(),
				msg.getRollProtocol()));
			if (msg.getResponse() == PartyRollResponseMessage.DECLINE)
			{
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
	private void evaluateProposal()
	{
		evaluateProposal(false);
	}

	private void evaluateProposal(boolean force)
	{
		if (!proposalLive || safeMemberIdOrZero() != proposerId)
		{
			return; // non-proposers wait for the start message (TTL+grace covers loss)
		}
		List<PartyMember> roster;
		try
		{
			roster = partyService.getMembers();
		}
		catch (Exception e)
		{
			return;
		}
		boolean allAnswered = true;
		List<Long> agreed = new ArrayList<>();
		for (PartyMember member : roster)
		{
			Stance stance = stances.get(member.getMemberId());
			if (stance == null)
			{
				allAnswered = false;
				continue; // silent so far — plugin-less members never answer
			}
			if (stance.getResponse() == PartyRollResponseMessage.AGREE)
			{
				agreed.add(member.getMemberId());
			}
		}
		boolean deadline = client.getTickCount() >= proposalExpiresAtTick;
		if (!allAnswered && !deadline && !force)
		{
			return; // keep waiting for stragglers until the deadline (or host start)
		}
		if (agreed.size() < 2)
		{
			if (deadline || allAnswered)
			{
				cancelProposal("Not enough members agreed to the party roll.");
			}
			return;
		}
		java.util.Collections.sort(agreed);
		safeSend(new PartyRollStartMessage(proposalId, agreed));
		executeRoll(agreed);
	}

	// --- Host controls / UI state ---

	/** Is a proposal currently collecting answers? (any member's view) */
	public boolean isProposalLive()
	{
		return proposalLive;
	}

	/** Only the proposer counts as host and may force-start early. */
	public boolean canForceStart()
	{
		return proposalLive && safeMemberIdOrZero() == proposerId;
	}

	/** Members who agreed so far (host's start button shows this). */
	public int agreedCount()
	{
		int count = 0;
		for (Stance stance : stances.values())
		{
			if (stance.getResponse() == PartyRollResponseMessage.AGREE)
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Host-only: start the roll NOW with whoever has agreed, instead of
	 * waiting out the deadline for silent members.
	 */
	public void forceStart()
	{
		clientThread.invokeLater(() -> {
			if (!canForceStart())
			{
				chat("Only the proposing host can start the party roll early.");
				return;
			}
			if (agreedCount() < 2)
			{
				chat("Nobody else has agreed yet — need at least 2 participants.");
				return;
			}
			evaluateProposal(true);
		});
	}

	/** The host may abort a proposal OR a rolled-but-unaccepted vote. */
	public boolean canCancelRoll()
	{
		return (proposalLive || votingLive) && safeMemberIdOrZero() == proposerId;
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
	public boolean isPartyRollLive()
	{
		return proposalLive || votingLive || taskLive;
	}

	/**
	 * Host-only: cancel the party roll for EVERY client that joined it. An
	 * accepted shared contract is binding and cannot be cancelled this way.
	 */
	public void cancelRoll()
	{
		clientThread.invokeLater(() -> {
			if (!canCancelRoll())
			{
				chat("Only the hosting proposer can cancel the party roll.");
				return;
			}
			safeSend(new PartyRollCancelMessage(proposalId));
			if (votingLive)
			{
				cancelVoting("You cancelled the party roll.");
			}
			else
			{
				cancelProposal("You cancelled the party roll.");
			}
		});
	}

	@Subscribe
	public void onPartyRollCancelMessage(PartyRollCancelMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			// only the proposal's host may cancel it remotely
			if (msg.getProposalId() != proposalId || msg.getMemberId() != proposerId)
			{
				return;
			}
			if (votingLive)
			{
				cancelVoting("The host cancelled the party roll.");
			}
			else if (proposalLive)
			{
				cancelProposal("The host cancelled the party roll.");
			}
		});
	}

	@Subscribe
	public void onPartyRollStartMessage(PartyRollStartMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (!proposalLive || msg.getProposalId() != proposalId
				|| msg.getMemberId() != proposerId || msg.getParticipantIds() == null)
			{
				return;
			}
			List<Long> list = msg.getParticipantIds();
			long self = safeMemberIdOrZero();
			if (!list.contains(self))
			{
				proposalLive = false;
				String note = stances.containsKey(self)
					&& stances.get(self).getResponse() == PartyRollResponseMessage.AGREE
					? "The party roll started without you (your agreement arrived too late)."
					: "The party roll started with " + list.size() + " members (you sat out).";
				chat(note);
				resetAll();
				return;
			}
			for (long id : list)
			{
				if (!stances.containsKey(id))
				{
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
	 * Runs IDENTICALLY on every participant's client: same participants, same
	 * anchor seed (lowest member id), same pool restrictions, same generator
	 * — therefore the same four offers everywhere.
	 */
	private void executeRoll(List<Long> agreed)
	{
		proposalLive = false;
		long anchorId = Long.MAX_VALUE;
		for (long id : agreed)
		{
			anchorId = Math.min(anchorId, id);
		}
		Stance anchor = stances.get(anchorId);
		anchorSeed = anchor.getSeedCandidate();
		boolean members = true;
		int weakest = Integer.MAX_VALUE;
		int slayer = Integer.MAX_VALUE;
		List<Integer> combatLevels = new ArrayList<>(agreed.size());
		List<Integer> protocols = new ArrayList<>(agreed.size());
		for (long id : agreed)
		{
			Stance stance = stances.get(id);
			members &= stance.isMembers();
			// A slayer requirement is a GATE, not a difficulty knob: a monster
			// you lack the level for cannot be damaged at all, so averaging here
			// would roll contracts some members literally cannot start. Combat
			// level is the knob, and combat level is what gets averaged.
			slayer = Math.min(slayer, stance.getSlayerLevel());
			// unclamped on purpose: the legacy branch below has to reproduce the
			// old arithmetic EXACTLY or a mixed-version party still splits
			weakest = Math.min(weakest, stance.getCombatLevel());
			combatLevels.add(stance.getCombatLevel());
			protocols.add(stance.getRollProtocol());
		}
		int cb = meanSizingAgreed(protocols) ? fightingWeight(combatLevels) : weakest;
		List<TaskOffer> raw = TaskGenerator.generateOffers(monsterTable.getMonsters(),
			cb, slayer, members, false, new GachaRng(anchor.getSeedCandidate()));
		List<TaskOffer> offers = new ArrayList<>(raw.size());
		for (TaskOffer offer : raw)
		{
			offers.add(new TaskOffer(offer.getDifficulty(), offer.getMonsterName(),
				offer.getMonsterCombatLevel(), offer.getKillsRequired(), offer.getPerKillGc(),
				offer.getCompletionGc(), offer.getSideBets(), offer.isRedemption(), true));
		}
		if (!taskService.presentPartyOffers(offers))
		{
			// local slot occupied after all (race) — sit out quietly
			resetAll();
			chat("The party roll could not be presented (you have offers or a task).");
			return;
		}
		votingLive = true;
		// The host's deadline is the real one; everyone else takes the same grace
		// margin the proposal phase uses, so their clock can only fire when the
		// host's broadcast cancel never arrived (host left, host not updated).
		// Anything closer would let two clients disagree about a late vote.
		voteExpiresAtTick = client.getTickCount() + VOTE_TTL_TICKS
			+ (safeMemberIdOrZero() == proposerId ? 0 : 25);
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
		for (long id : agreed)
		{
			partyStyles.add(parseStyle(stances.get(id).getAllowedStyle()));
		}
		// The sizing level is disclosed BEFORE the vote on purpose: a shared
		// contract can outclass the party's lowest member, and there is no
		// abandoning one once it is signed, so he has to be able to see what he
		// is voting on rather than find out at the first kill.
		chat("Party roll ready (" + agreed.size() + " members, contracts sized to combat "
			+ cb + ") — click a contract to VOTE; a majority ("
			+ majorityThreshold(agreed.size()) + " of " + agreed.size()
			+ ") signs it for the party.");
		if (anteOffered())
		{
			for (TaskOffer offer : offers)
			{
				if (TaskService.anteEligible(offer))
				{
					// said BEFORE the vote, because arming after clicking a scroll
					// is too late — the vote carries the consent with it
					chat("One of these contracts can carry the Ante. Arm it in the Gachaman"
						+ " panel BEFORE you vote: it takes every member's consent, and each"
						+ " member stakes their own GC.");
					break;
				}
			}
		}
		refreshPanel();
	}

	/** Wired as TaskService's party vote hook: local click on a party offer. */
	public void voteLocal(int offerIndex)
	{
		if (!votingLive || partyOffers == null
			|| offerIndex < 0 || offerIndex >= partyOffers.size())
		{
			return;
		}
		long self = safeMemberIdOrZero();
		if (self == 0 || !participants.contains(self))
		{
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
		chat("You voted: " + describeOffer(partyOffers.get(offerIndex))
			+ " (" + votesFor(offerIndex) + " of "
			+ majorityThreshold(participants.size()) + " needed)"
			+ anteSuffix(wantsAnte));
		evaluateVotes();
	}

	/** One short, sober note on a vote line: the Ante needs EVERY member. */
	private String anteSuffix(boolean wantsAnte)
	{
		return wantsAnte ? " — Ante: yes" : "";
	}

	/**
	 * Is the wager on the table at all for this client? Only gates what this
	 * client OFFERS and says; a peer's incoming consent is still recorded, so
	 * turning it off never makes this client misreport someone else's answer.
	 */
	private boolean anteOffered()
	{
		try
		{
			return config != null && config.anteEnabled();
		}
		catch (Exception e)
		{
			return false;
		}
	}

	@Subscribe
	public void onPartyRollVoteMessage(PartyRollVoteMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (!votingLive || msg.getProposalId() != proposalId
				|| !participants.contains(msg.getMemberId()))
			{
				return;
			}
			int index = msg.getOfferIndex();
			if (partyOffers == null || index < 0 || index >= partyOffers.size())
			{
				return;
			}
			votes.put(msg.getMemberId(), index);
			anteVotes.put(msg.getMemberId(), msg.isAnte());
			chat(memberName(msg.getMemberId()) + " voted: " + describeOffer(partyOffers.get(index))
				+ " (" + votesFor(index) + " of "
				+ majorityThreshold(participants.size()) + " needed)"
				+ anteSuffix(msg.isAnte()));
			evaluateVotes();
		});
	}

	private int votesFor(int index)
	{
		int count = 0;
		for (int vote : votes.values())
		{
			if (vote == index)
			{
				count++;
			}
		}
		return count;
	}

	/** Votes per offer index, in offer order. */
	private int[] tally()
	{
		int[] counts = new int[partyOffers == null ? 0 : partyOffers.size()];
		for (int vote : votes.values())
		{
			if (vote >= 0 && vote < counts.length)
			{
				counts[vote]++;
			}
		}
		return counts;
	}

	/**
	 * How many votes sign the contract for everyone: a STRICT majority of the
	 * members still in the party (2 of 2, 2 of 3, 3 of 4, 3 of 5). Strict, so
	 * at most one contract can ever hold it — an even split is a tie, and ties
	 * go to the deadline path below rather than to whoever was counted first.
	 */
	static int majorityThreshold(int participantCount)
	{
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
	static boolean anteUnanimous(java.util.Collection<Long> roster, Map<Long, Boolean> consent)
	{
		if (roster == null || roster.isEmpty() || consent == null)
		{
			return false;
		}
		for (Long id : roster)
		{
			if (!Boolean.TRUE.equals(consent.get(id)))
			{
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
	private void evaluateVotes()
	{
		if (!votingLive || partyOffers == null)
		{
			return;
		}
		// members who left the party stop counting, and so do their votes: a
		// departed member must not carry a contract they will not be on
		participants.retainAll(rosterIds());
		if (participants.size() < 2)
		{
			cancelVoting("The party shrank — the shared roll dissolved.");
			return;
		}
		votes.keySet().retainAll(participants);
		anteVotes.keySet().retainAll(participants);
		if (safeMemberIdOrZero() != proposerId)
		{
			return;
		}
		int[] counts = tally();
		int threshold = majorityThreshold(participants.size());
		for (int index = 0; index < counts.length; index++)
		{
			if (counts[index] >= threshold)
			{
				// A majority binds the WHOLE party, abstainers included — that
				// is what majority rule means here: the party hunts what most
				// of it picked. Only the weaker outcomes below fall back to
				// binding the voters alone.
				broadcastResolve(index, new ArrayList<>(participants),
					PartyRollResolveMessage.MODE_MAJORITY);
				return;
			}
		}
		if (votes.keySet().containsAll(participants))
		{
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
	private void hostResolve(String why)
	{
		if (!votingLive || partyOffers == null || safeMemberIdOrZero() != proposerId)
		{
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
		if (votes.size() < 2 || index < 0)
		{
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

	private void broadcastResolve(int index, List<Long> memberIds, int mode)
	{
		java.util.Collections.sort(memberIds); // stable payload; ids are the identity
		// The Ante verdict is settled here, by the host, against the roster it
		// just fixed — the same authority and the same instant as the contract,
		// so no client can be staking against a roster that has since narrowed.
		boolean ante = TaskService.anteEligible(partyOffers.get(index))
			&& anteUnanimous(memberIds, anteVotes);
		safeSend(new PartyRollResolveMessage(proposalId, index, memberIds, mode, ante));
		applyResolve(index, memberIds, mode, ante);
	}

	@Subscribe
	public void onPartyRollResolveMessage(PartyRollResolveMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			// only the proposal's host may settle it, and only once
			if (!votingLive || msg.getProposalId() != proposalId
				|| msg.getMemberId() != proposerId || msg.getMemberIds() == null
				|| partyOffers == null)
			{
				return;
			}
			int index = msg.getOfferIndex();
			if (index < 0 || index >= partyOffers.size())
			{
				return;
			}
			// a roster the roll never included cannot be on the contract
			List<Long> roster = new ArrayList<>(msg.getMemberIds());
			if (rollOrder != null)
			{
				roster.retainAll(rollOrder);
			}
			applyResolve(index, roster, msg.getMode(), msg.isAnte());
		});
	}

	/** Sign (or, for an abstainer, decline) the contract the host settled on. */
	private void applyResolve(int index, List<Long> memberIds, int mode, boolean ante)
	{
		if (!votingLive || partyOffers == null || index < 0 || index >= partyOffers.size())
		{
			return;
		}
		votingLive = false;
		if (!memberIds.contains(safeMemberIdOrZero()))
		{
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
			stylesFor(memberIds), stakeLocally, proposalId))
		{
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
		switch (mode)
		{
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
		if (anteOffered() && TaskService.anteEligible(partyOffers.get(index)))
		{
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
	private List<AttackStyle> stylesFor(List<Long> memberIds)
	{
		if (rollOrder == null || partyStyles == null)
		{
			return partyStyles; // legacy/absent snapshot: no clash bonus, no throw
		}
		List<AttackStyle> narrowed = new ArrayList<>(memberIds.size());
		for (int i = 0; i < rollOrder.size() && i < partyStyles.size(); i++)
		{
			if (memberIds.contains(rollOrder.get(i)))
			{
				narrowed.add(partyStyles.get(i));
			}
		}
		return narrowed;
	}

	/**
	 * The offer indices sharing the highest vote count; empty when nobody
	 * voted. More than one entry means the lead is tied and must be drawn.
	 */
	static List<Integer> topTallies(int[] counts)
	{
		List<Integer> top = new ArrayList<>();
		int best = 0;
		for (int index = 0; index < counts.length; index++)
		{
			if (counts[index] == 0 || counts[index] < best)
			{
				continue;
			}
			if (counts[index] > best)
			{
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
	static int tiebreakIndex(long anchorSeed, long proposalId, List<Integer> tied)
	{
		if (tied == null || tied.isEmpty())
		{
			return -1;
		}
		return tied.get(new GachaRng(anchorSeed * 31 + proposalId).nextInt(tied.size()));
	}

	// =====================================================================
	// SHARED TASK
	// =====================================================================

	@Override
	public void onPartyProgress(ActiveTask task)
	{
		if (taskLive && task != null && safeLocalMember() != null)
		{
			safeSend(new PartyKillsMessage(proposalId, task.getKillsDone()));
		}
	}

	@Subscribe
	public void onPartyKillsMessage(PartyKillsMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (!onContract(msg.getMemberId(), msg.getProposalId()))
			{
				return;
			}
			partyKills.merge(msg.getMemberId(), msg.getKills(), Math::max);
			lastOthersProgressTick = client.getTickCount();
			int othersTotal = 0;
			for (int kills : partyKills.values())
			{
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
	private boolean onContract(long memberId, long msgProposalId)
	{
		if (!taskLive || msgProposalId != proposalId)
		{
			return false;
		}
		if (participants.contains(memberId))
		{
			return true;
		}
		Set<Long> roster = rosterIds();
		if (!roster.contains(memberId))
		{
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
	private void admitReturningMember(long memberId, Set<Long> roster)
	{
		boolean firstContact = resumedAtTick >= 0 && participants.isEmpty();
		dropDepartedKills(partyKills, roster);
		participants.add(memberId);
		rememberNames(java.util.Collections.singletonList(memberId));
		if (firstContact)
		{
			chat("Your party is back in sync — " + memberName(memberId)
				+ " is still on the contract with you.");
			refreshPanel();
		}
	}

	@Subscribe
	public void onPartyCompleteMessage(PartyCompleteMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (onContract(msg.getMemberId(), msg.getProposalId()))
			{
				chat(memberName(msg.getMemberId()) + "'s client completed the party contract.");
				taskService.forcePartyComplete();
			}
		});
	}

	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
	{
		if (taskLive && summary != null && summary.getTask() != null
			&& summary.getTask().getPartyLabel() != null)
		{
			if (safeLocalMember() != null)
			{
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
	private void creditPatrons()
	{
		try
		{
			long self = safeMemberIdOrZero();
			List<String> partners = new ArrayList<>();
			for (long id : participants)
			{
				if (id == self)
				{
					continue;
				}
				// live roster first (it tracks a mid-contract rename), the
				// signature-time snapshot second (it survives a logout)
				String name = PatronMark.normalizeName(liveDisplayName(id));
				if (name == null)
				{
					name = PatronMark.normalizeName(partnerNameCache.get(id));
				}
				if (name != null)
				{
					partners.add(name);
				}
			}
			if (partners.isEmpty())
			{
				return;
			}
			GachaState before = stateService.get();
			if (before == null)
			{
				return;
			}
			Map<String, Integer> was = before.getPartnerContracts();
			GachaState after = stateService.mutate(s -> {
				Map<String, Integer> next = PatronMark.credit(s.getPartnerContracts(),
					partners, Tuning.PATRON_MAX_PARTNERS);
				// handing back the SAME instance makes mutate short-circuit,
				// so a completion with nothing to credit pays for no encode
				return next == s.getPartnerContracts() ? s : s.withPartnerContracts(next);
			});
			if (after == null)
			{
				return;
			}
			for (String name : partners)
			{
				int from = PatronMark.countFor(was, name);
				int to = PatronMark.countFor(after.getPartnerContracts(), name);
				if (PatronMark.crossedTier(from, to))
				{
					chat(name + " has stood with you " + to + " times — "
						+ PatronMark.tierLabel(to) + ".");
				}
			}
		}
		catch (Exception e)
		{
			log.debug("patron credit failed", e);
		}
	}

	/** Snapshot the roster's names while the party is still fully synced. */
	private void rememberNames(List<Long> memberIds)
	{
		for (Long id : memberIds)
		{
			String name = PatronMark.normalizeName(liveDisplayName(id));
			if (name != null)
			{
				partnerNameCache.put(id, name);
			}
		}
	}

	/**
	 * The roster's raw display name, or null. Deliberately distinct from
	 * memberName(), whose "A party member" fallback is right for a chat line
	 * and must NEVER be persisted as though it were a partner.
	 */
	@Nullable
	private String liveDisplayName(long memberId)
	{
		try
		{
			PartyMember member = partyService.getMemberById(memberId);
			return member == null ? null : member.getDisplayName();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// =====================================================================
	// WATCHDOG
	// =====================================================================

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		int now = client.getTickCount();
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
		if (proposalLive && proposalExpiresAtTick - now > PROPOSAL_TTL_TICKS + 25)
		{
			proposalExpiresAtTick = now + PROPOSAL_TTL_TICKS + 25;
		}
		if (votingLive && voteExpiresAtTick - now > VOTE_TTL_TICKS + 25)
		{
			voteExpiresAtTick = now + VOTE_TTL_TICKS + 25;
		}
		if (proposalLive && now >= proposalExpiresAtTick)
		{
			if (safeMemberIdOrZero() == proposerId)
			{
				// deadline: start with whoever agreed (min 2) or cancel
				evaluateProposal();
			}
			else
			{
				// grace passed with no start message — the proposer is gone
				cancelProposal("The party roll proposal expired.");
			}
			return;
		}
		// checked EVERY tick, deliberately NOT folded into the %25 sweep below:
		// that sweep's phase differs per client, so a straggler clicking inside
		// the skew would leave the already-expired clients demoted while the rest
		// settle the vote and sign a shared contract nobody else is on.
		if (votingLive && voteExpired(now, voteExpiresAtTick))
		{
			if (safeMemberIdOrZero() == proposerId)
			{
				// one clock, not N: the host settles the vote for everyone, and
				// broadcasts either the winning contract or a cancel, so every
				// client lands the same way
				hostResolve("The vote timed out");
			}
			else
			{
				// grace passed with no word from the host — the host is gone
				cancelVoting("The party roll went quiet — the vote timed out.");
			}
			return;
		}
		if (votingLive && (now % 25 == 0))
		{
			evaluateVotes(); // roster changes cancel even without a vote arriving
		}
		if (!taskLive || now % 25 != 0)
		{
			return;
		}
		// A relog or world hop restarts getTickCount(), which leaves every stamp
		// below reading as far in the FUTURE — and a future stamp parks its timer
		// for as long as the old count was high, so the carry clause would simply
		// stop existing for an hour. Re-anchor rather than stall.
		if (lastOthersProgressTick > now)
		{
			lastOthersProgressTick = now;
		}
		if (resumedAtTick > now)
		{
			resumedAtTick = now;
		}
		if (othersGoneSinceTick > now)
		{
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
		if (resumedAtTick >= 0 && now - resumedAtTick <= Tuning.PARTY_DEPART_GRACE_TICKS)
		{
			GachaState resumeState = stateService.get();
			ActiveTask resumeTask = resumeState == null ? null : resumeState.getActiveTask();
			if (resumeTask != null && resumeTask.isParty() && safeLocalMember() != null)
			{
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
		if (!knewOthers || anyOtherPresent)
		{
			othersGoneSinceTick = -1;
		}
		else if (othersGoneSinceTick < 0)
		{
			othersGoneSinceTick = now;
		}
		boolean everyoneGone = everyoneGone(knewOthers, anyOtherPresent,
			othersGoneSinceTick < 0 ? 0 : now - othersGoneSinceTick,
			resumedAtTick >= 0, now - resumedAtTick, roster.size());
		boolean idle = now - lastOthersProgressTick > Tuning.PARTY_IDLE_TICKS;
		if (everyoneGone || idle)
		{
			GachaState state = stateService.get();
			ActiveTask task = state == null ? null : state.getActiveTask();
			if (task != null && task.isParty())
			{
				taskService.convertPartyToSolo();
				chat((everyoneGone
					? "Your party has left. Carry clause: the contract continues solo at "
					: "Your party has gone quiet. Carry clause: the contract continues solo at ")
					+ (int) (Tuning.PARTY_CARRY_MULT * 100) + "% completion pay.");
			}
			resetAll();
		}
	}

	// =====================================================================
	// Helpers
	// =====================================================================

	private void cancelProposal(String message)
	{
		if (proposalLive)
		{
			proposalLive = false;
			stances.clear();
			chat(message);
			refreshPanel();
		}
	}

	private void cancelVoting(String message)
	{
		votingLive = false;
		// Close the scrolls first if they are on screen. The payload up there
		// still reads "vote", but the click behind it is about to mean "sign this
		// contract, solo, forever" — and the chat line explaining that is hidden
		// under the modal. Making the player reopen the board is the only way the
		// meaning change is visible before they act on it.
		abortCeremony();
		// rolls cannot be undone: the offers stay, demoted to personal ones
		taskService.demotePartyOffers();
		chat(message + " The rolled contracts remain — pick one for yourself.");
		resetAll();
		refreshPanel();
	}

	private void resetAll()
	{
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
		partnerNameCache.clear();
	}

	/**
	 * Debug support (::gachacleartask) and RS profile switches: drop any
	 * proposal/vote/shared-task state.
	 */
	public void resetForDebug()
	{
		resetAll();
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
	public void recoverPartySession()
	{
		if (proposalLive || votingLive || taskLive)
		{
			return;
		}
		if (taskService.hasPendingPartyOffers())
		{
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
	private void resurrectPartyContract()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null || !task.isParty())
		{
			return;
		}
		if (task.getPartyProposalId() == null)
		{
			// Signed before the id was persisted, so nothing identifies it on the
			// wire and no partner could ever be matched to it. Left alone it would
			// hold the shared multiplier forever; settled here on exactly the terms
			// the carry clause would have reached had it been able to run.
			taskService.convertPartyToSolo();
			chat("Your shared contract predates party resume and cannot be rejoined."
				+ " Carry clause: it continues solo at "
				+ (int) (Tuning.PARTY_CARRY_MULT * 100) + "% completion pay.");
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
			+ " — if nobody does, the carry clause continues it solo at "
			+ (int) (Tuning.PARTY_CARRY_MULT * 100) + "% completion pay.");
		refreshPanel();
	}

	/**
	 * Subtraction rather than {@code now >= expiresAt} so a deadline that has
	 * already slipped past can never read as not-yet-due.
	 */
	static boolean voteExpired(int now, int expiresAt)
	{
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
		int ticksSinceOthersGone, boolean resumed, int ticksSinceResume, int rosterSize)
	{
		if (knewOthers)
		{
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
	static void dropDepartedKills(Map<Long, Integer> partyKills, Set<Long> roster)
	{
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
	static int fightingWeight(@Nullable List<Integer> combatLevels)
	{
		if (combatLevels == null || combatLevels.isEmpty())
		{
			// not 0: a 0 collapses TaskGenerator's cap to max(2, 0) and rolls a
			// degenerate board of the two lowest monsters in the table
			return Tuning.COMBAT_LEVEL_MIN;
		}
		long sum = 0;
		for (int level : combatLevels)
		{
			sum += Math.max(Tuning.COMBAT_LEVEL_MIN, Math.min(Tuning.COMBAT_LEVEL_MAX, level));
		}
		// the clamp keeps the sum non-negative, so integer division is a true floor
		return (int) (sum / combatLevels.size());
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
	static boolean meanSizingAgreed(@Nullable List<Integer> rollProtocols)
	{
		if (rollProtocols == null || rollProtocols.isEmpty())
		{
			return false;
		}
		for (int protocol : rollProtocols)
		{
			// >= and not ==, so a FUTURE protocol is never misread as legacy
			if (protocol < ROLL_PROTOCOL_FIGHTING_WEIGHT)
			{
				return false;
			}
		}
		return true;
	}

	private void sendResponse(int response)
	{
		Stance mine = localStance(response);
		PartyMember local = safeLocalMember();
		if (local != null)
		{
			stances.put(local.getMemberId(), mine);
		}
		safeSend(new PartyRollResponseMessage(proposalId, response, mine.getSeedCandidate(),
			mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel(),
			mine.getAllowedStyle(), mine.getRollProtocol()));
	}

	private Stance localStance(int response)
	{
		GachaState state = stateService.get();
		return new Stance(response, mySeedCandidate,
			taskService.localIsMembers(), taskService.playerCombatLevel(),
			client.getRealSkillLevel(net.runelite.api.Skill.SLAYER),
			state == null ? null : state.getAllowedStyle(), ROLL_PROTOCOL);
	}

	private boolean localBusy()
	{
		GachaState state = stateService.get();
		return state == null || state.getActiveTask() != null
			|| (state.getPendingOffers() != null && !state.getPendingOffers().isEmpty());
	}

	private Set<Long> rosterIds()
	{
		Set<Long> ids = new HashSet<>();
		try
		{
			for (PartyMember member : partyService.getMembers())
			{
				ids.add(member.getMemberId());
			}
		}
		catch (Exception e)
		{
			log.debug("party roster read failed", e);
		}
		return ids;
	}

	/** A member who has not rolled a style yet, or an older client, contributes none. */
	@Nullable
	static AttackStyle parseStyle(@Nullable String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return AttackStyle.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static String describeOffer(TaskOffer offer)
	{
		return offer.getKillsRequired() + "x " + offer.getMonsterName()
			+ " (" + offer.getDifficulty().getDisplayName() + ")";
	}

	private String memberName(long memberId)
	{
		try
		{
			PartyMember member = partyService.getMemberById(memberId);
			return member != null && member.getDisplayName() != null
				? member.getDisplayName() : "A party member";
		}
		catch (Exception e)
		{
			return "A party member";
		}
	}

	@Nullable
	private PartyMember safeLocalMember()
	{
		try
		{
			return partyService.getLocalMember();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private long safeMemberIdOrZero()
	{
		PartyMember local = safeLocalMember();
		return local == null ? 0 : local.getMemberId();
	}

	private boolean isSelfEcho(long memberId)
	{
		PartyMember local = safeLocalMember();
		return local == null || memberId == local.getMemberId();
	}

	private boolean safeSend(net.runelite.client.party.messages.PartyMemberMessage msg)
	{
		try
		{
			partyService.send(msg);
			return true;
		}
		catch (Exception e)
		{
			log.debug("party send failed", e);
			return false;
		}
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> " + message)
			.build());
	}

	// --- TaskService.Listener no-ops ---

	@Override
	public void onKillFeedback(TaskService.KillFeedback feedback)
	{
	}

	@Override
	public void onSideBetHit(SideBet bet, String monsterName)
	{
	}

	@Override
	public void onOffersRolled(List<TaskOffer> offers)
	{
	}
}
