package com.gachaman.party;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskOffer;
import com.gachaman.service.GachaRng;
import com.gachaman.service.GachaStateService;
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
 * A party roll exists only by unanimous consent of every TASK-LESS member:
 * one member proposes, members with an active contract auto-report busy, and
 * once every remaining member agrees the roll executes DETERMINISTICALLY on
 * every client — all participants roll with the seed candidate of the
 * participant with the LOWEST member id, the pool restricted to free-to-play
 * when ANY participant is free (membership is exchanged in this handshake,
 * nowhere else), and scaled to the lowest combat/slayer levels so no offer is
 * impossible for anyone. Identical offers appear on every screen; clicking
 * one casts a VOTE, and only a unanimous vote accepts the contract — shared:
 * every participant's kills count toward one pooled quota.
 */
@Slf4j
@Singleton
public class PartyRollService implements TaskService.Listener
{
	private static final int PROPOSAL_TTL_TICKS = 100;

	@Value
	private static class Stance
	{
		int response; // PartyRollResponseMessage.AGREE / DECLINE / BUSY
		long seedCandidate;
		boolean members;
		int combatLevel;
		int slayerLevel;
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
	private Set<Long> participants = new HashSet<>();
	private final Map<Long, Integer> votes = new HashMap<>();
	private List<TaskOffer> partyOffers;

	private boolean taskLive;
	private final Map<Long, Integer> partyKills = new HashMap<>();
	private int lastOthersProgressTick;

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
			proposalLive = true;
			proposerId = safeMemberIdOrZero();
			proposalExpiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS;
			Stance mine = localStance(PartyRollResponseMessage.AGREE);
			stances.put(safeLocalMember().getMemberId(), mine);
			safeSend(new PartyRollProposeMessage(proposalId, mine.getSeedCandidate(),
				mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel()));
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
			proposalLive = true;
			proposerId = msg.getMemberId();
			// small grace past the proposer's deadline: the proposer decides,
			// this client only times out when no start message ever arrives
			proposalExpiresAtTick = client.getTickCount() + PROPOSAL_TTL_TICKS + 25;
			stances.put(msg.getMemberId(), new Stance(PartyRollResponseMessage.AGREE,
				msg.getSeedCandidate(), msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel()));
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
				msg.isMembers(), msg.getCombatLevel(), msg.getSlayerLevel()));
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
		boolean members = true;
		int cb = Integer.MAX_VALUE;
		int slayer = Integer.MAX_VALUE;
		for (long id : agreed)
		{
			Stance stance = stances.get(id);
			members &= stance.isMembers();
			cb = Math.min(cb, stance.getCombatLevel());
			slayer = Math.min(slayer, stance.getSlayerLevel());
		}
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
		participants = new HashSet<>(agreed);
		partyOffers = offers;
		chat("Party roll ready (" + agreed.size() + " members) — click a contract to VOTE;"
			+ " it is accepted when the vote is unanimous.");
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
		safeSend(new PartyRollVoteMessage(proposalId, offerIndex));
		chat("You voted: " + describeOffer(partyOffers.get(offerIndex))
			+ " (" + votesFor(offerIndex) + "/" + participants.size() + ")");
		evaluateVotes();
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
			chat(memberName(msg.getMemberId()) + " voted: " + describeOffer(partyOffers.get(index))
				+ " (" + votesFor(index) + "/" + participants.size() + ")");
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

	private void evaluateVotes()
	{
		// participants who left the party stop counting toward unanimity
		participants.retainAll(rosterIds());
		if (participants.size() < 2)
		{
			cancelVoting("The party shrank — the shared roll dissolved.");
			return;
		}
		Integer unanimous = null;
		for (long id : participants)
		{
			Integer vote = votes.get(id);
			if (vote == null)
			{
				return; // someone has not voted yet
			}
			if (unanimous == null)
			{
				unanimous = vote;
			}
			else if (!unanimous.equals(vote))
			{
				return; // split — players re-vote by clicking another scroll
			}
		}
		if (unanimous == null)
		{
			return;
		}
		int index = unanimous;
		votingLive = false;
		taskLive = true;
		partyKills.clear();
		lastOthersProgressTick = client.getTickCount();
		taskService.acceptPartyOffer(index, "Party of " + participants.size());
		chat("Unanimous! Party contract accepted: " + describeOffer(partyOffers.get(index))
			+ " — every member's kills count toward the shared quota.");
	}

	// =====================================================================
	// SHARED TASK
	// =====================================================================

	@Override
	public void onDuoProgress(ActiveTask task)
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
			if (!taskLive || msg.getProposalId() != proposalId
				|| !participants.contains(msg.getMemberId()))
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

	@Subscribe
	public void onPartyCompleteMessage(PartyCompleteMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		clientThread.invokeLater(() -> {
			if (taskLive && msg.getProposalId() == proposalId
				&& participants.contains(msg.getMemberId()))
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
			&& summary.getTask().getDuoPartnerName() != null)
		{
			if (safeLocalMember() != null)
			{
				safeSend(new PartyCompleteMessage(proposalId));
			}
			resetAll();
		}
	}

	// =====================================================================
	// WATCHDOG
	// =====================================================================

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		int now = client.getTickCount();
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
		if (votingLive && (now % 25 == 0))
		{
			evaluateVotes(); // roster changes cancel even without a vote arriving
		}
		if (!taskLive || now % 25 != 0)
		{
			return;
		}
		// carry clause: all other participants gone, or nobody progressing
		Set<Long> others = new HashSet<>(participants);
		others.remove(safeMemberIdOrZero());
		others.retainAll(rosterIds());
		boolean everyoneGone = others.isEmpty();
		boolean idle = now - lastOthersProgressTick > Tuning.DUO_IDLE_TICKS;
		if (everyoneGone || idle)
		{
			GachaState state = stateService.get();
			ActiveTask task = state == null ? null : state.getActiveTask();
			if (task != null && task.isDuo())
			{
				taskService.convertDuoToSolo();
				chat((everyoneGone
					? "Your party has left. Carry clause: the contract continues solo at "
					: "Your party has gone quiet. Carry clause: the contract continues solo at ")
					+ (int) (Tuning.DUO_CARRY_MULT * 100) + "% completion pay.");
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
		stances.clear();
		votes.clear();
		partyKills.clear();
		participants = new HashSet<>();
		partyOffers = null;
	}

	/** Debug support (::gachacleartask): drop any proposal/vote/shared-task state. */
	public void resetForDebug()
	{
		resetAll();
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
			mine.isMembers(), mine.getCombatLevel(), mine.getSlayerLevel()));
	}

	private Stance localStance(int response)
	{
		return new Stance(response, ThreadLocalRandom.current().nextLong(),
			taskService.localIsMembers(), taskService.playerCombatLevel(),
			client.getRealSkillLevel(net.runelite.api.Skill.SLAYER));
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
