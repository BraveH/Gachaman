package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.ContractRecord;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.MonsterStats;
import com.gachaman.model.PersonalBest;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The kill-task engine: offer rolls, acceptance, per-kill crediting with
 * taint rules, side bets, completion rewards, journal/PBs, style-cycle
 * advancement and deed milestones.
 */
@Slf4j
@Singleton
public class TaskService implements KillTracker.KillListener, ComplianceService.Listener,
	StyleTracker.AttackListener
{
	@Value
	public static class TaskCompletionSummary
	{
		ActiveTask task;
		long completionGcAwarded;
		int sideBetsHit;
		long taskDurationMs;
		boolean newFastestPb;
		boolean newHaulPb;
		boolean redemptionCleared;
		boolean cycleTriggered;
		int deedMilestoneEarned; // 0 = none, else the milestone task count
		int fragmentsEarned;     // Deed Fragments granted by THIS completion
		int fragmentsTotal;      // running fragment count after this completion
		boolean fragmentDeedForged; // this completion forged the fragment deed
	}

	@Value
	public static class KillFeedback
	{
		String monsterName;
		long gcAwarded;      // 0 for tainted / off-task
		boolean onTask;
		boolean tainted;
		boolean finalKill;
		int killsDone;
		int killsRequired;
		/** The ironman assisted-kill penalty applied (half count, half GC). */
		boolean assistedHalfCredit;
		LocalPoint deathLocation;
	}

	/**
	 * Pure kill-count advance rule: a Compactor doubles the count, the ironman
	 * assisted-kill penalty halves it — an assisted normal kill banks/redeems
	 * a pending half (two assisted kills = one count), an assisted Compactor
	 * kill lands back on exactly 1.
	 */
	@Value
	public static class KcAdvance
	{
		int increment;
		boolean halfPending;
	}

	static KcAdvance kcAdvance(boolean compactor, boolean assistedPenalty, boolean pendingHalf)
	{
		if (!assistedPenalty)
		{
			return new KcAdvance(compactor ? 2 : 1, pendingHalf);
		}
		if (compactor)
		{
			return new KcAdvance(1, pendingHalf); // 2 x 0.5
		}
		return pendingHalf ? new KcAdvance(1, false) : new KcAdvance(0, true);
	}

	/**
	 * The ironman honor rule halves assisted kills — but on a shared party
	 * contract teammates are EXPECTED to pile onto the same monsters, so
	 * their "assists" are just the party playing together (hitsplats cannot
	 * be attributed to a specific player, so the whole rule stands down while
	 * the contract is shared). It re-arms the moment the carry clause
	 * converts the contract back to solo.
	 */
	static boolean assistedPenaltyApplies(boolean ironman, boolean assistedByOther, ActiveTask task)
	{
		return ironman && assistedByOther && (task == null || !task.isParty());
	}

	/**
	 * The Ante's stake: a percent of the purse, clamped to the legal band and to
	 * the absolute cap, floored to whole GC.
	 *
	 * Returns 0 — meaning "no wager is offered" — for a purse under the floor.
	 * That is not a rounding accident: at 200 GC the minimum stake is 20, which
	 * is not a risk worth a confirmation dialog, and a player who is that broke
	 * is the one least able to absorb it.
	 */
	public static int anteStakeFor(long gc, int percent)
	{
		if (gc < Tuning.ANTE_MIN_PURSE_GC || percent <= 0)
		{
			return 0;
		}
		int pct = Math.max(Tuning.ANTE_MIN_PERCENT, Math.min(Tuning.ANTE_MAX_PERCENT, percent));
		long stake = Math.min(gc * pct / 100, Tuning.ANTE_MAX_GC);
		// can only ever bind by the cap, never by the purse (pct <= 50), but the
		// clamp is kept so a future band change cannot overdraw the account
		return (int) Math.max(0, Math.min(stake, gc));
	}

	/**
	 * The Ante rides on the hardest contracts ONLY. A wager the player can take
	 * on an easy contract is a wager with no downside worth speaking of, and the
	 * point of this one is that INSANE contracts are where dying is plausible.
	 */
	public static boolean anteEligible(@Nullable TaskOffer offer)
	{
		return offer != null && offer.getDifficulty() == TaskDifficulty.INSANE;
	}

	public interface Listener
	{
		void onKillFeedback(KillFeedback feedback);

		void onSideBetHit(SideBet bet, String monsterName);

		void onTaskCompleted(TaskCompletionSummary summary);

		void onOffersRolled(List<TaskOffer> offers);

		/** Party hook: local player progressed a shared party contract. */
		void onPartyProgress(ActiveTask task);
	}

	private final Client client;
	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final ComplianceService complianceService;
	private final StyleService styleService;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;
	private final MonsterTable monsterTable;
	private final QuestUnlockService questUnlockService;

	private final List<Listener> listeners = new ArrayList<>();
	/** Optional hook the plugin wires for the party layer. */
	private Consumer<TaskOffer> offerAcceptedHook;
	/** Party layer: clicking a party-roll offer casts a VOTE instead of accepting. */
	private IntConsumer partyVoteHook;
	/**
	 * Double Docket: supplies the live Slayer assignment name, or null. A hook
	 * rather than an injected dependency so the whole payout path stays testable
	 * without a Client — every unit test leaves it unwired and gets no bonus.
	 */
	private Supplier<String> slayerTargetHook;
	/** Double Docket: fired once, the moment a contract latches on to the bonus. */
	private Runnable slayerLatchHook;
	/** Recent credited kill ticks for SPEED_KILLS side bets. */
	private final Deque<Integer> recentKillTicks = new ArrayDeque<>();
	/** Tick of the most recent kill; completeTask runs inside onKill so this is "now". */
	private int lastKillTick;
	/** Monster name -> tags, built lazily from the table. */
	private Map<String, List<String>> tagsByMonster;

	// Rhythm Combo (transient, never persisted): consecutive on-task kills
	// within the window build stacks; a forbidden attack breaks it, and it
	// cancels only after the idle window passes with NO attacks at all —
	// fighting a tanky monster keeps the chain alive even between kills.
	private int comboStacks;
	private int comboLastKillTick = -1;
	/** Last activity (kill OR attack) keeping the chain alive. */
	private int comboIdleAnchorTick = -1;

	/**
	 * The Ante: the percent the player armed for the contract in front of them,
	 * or 0 for "no wager". Deliberately TRANSIENT and deliberately cleared the
	 * moment a contract is signed or a fresh set of offers arrives — arming is a
	 * decision about one specific board, and a stake that survived a logout or a
	 * re-roll would be a wager the player does not remember making.
	 *
	 * volatile: the panel arms it on the Swing thread, the accept path reads it
	 * on the client thread.
	 */
	private volatile int antePercentArmed;

	@Inject
	public TaskService(Client client, GachaStateService stateService, CreditSink creditSink,
		ComplianceService complianceService, StyleService styleService, CeremonyBus ceremonyBus,
		GachaRng rng, MonsterTable monsterTable, QuestUnlockService questUnlockService)
	{
		this.client = client;
		this.stateService = stateService;
		this.creditSink = creditSink;
		this.complianceService = complianceService;
		this.styleService = styleService;
		this.ceremonyBus = ceremonyBus;
		this.rng = rng;
		this.monsterTable = monsterTable;
		this.questUnlockService = questUnlockService;
	}

	public void addListener(Listener listener)
	{
		if (!listeners.contains(listener))
		{
			listeners.add(listener);
		}
	}

	public void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	public void setOfferAcceptedHook(Consumer<TaskOffer> hook)
	{
		this.offerAcceptedHook = hook;
	}

	public void setPartyVoteHook(IntConsumer hook)
	{
		this.partyVoteHook = hook;
	}

	public void setSlayerTargetHook(Supplier<String> hook)
	{
		this.slayerTargetHook = hook;
	}

	public void setSlayerLatchHook(Runnable hook)
	{
		this.slayerLatchHook = hook;
	}

	/**
	 * The live Slayer assignment, or null. Swallows hook failures: a broken
	 * Slayer read must cost the player a bonus, never a kill credit.
	 */
	@Nullable
	private String liveSlayerTarget()
	{
		if (slayerTargetHook == null)
		{
			return null;
		}
		try
		{
			return slayerTargetHook.get();
		}
		catch (Exception e)
		{
			log.warn("slayer target hook failed", e);
			return null;
		}
	}

	// --- The Ante ---

	/**
	 * Arm (or, with 0, disarm) the wager for the offers currently on the board.
	 * Arming alone stakes nothing: the GC only leaves the purse when a contract
	 * is actually signed, and only if the contract turns out to be eligible.
	 */
	public void armAnte(int percent)
	{
		antePercentArmed = percent <= 0 ? 0
			: Math.max(Tuning.ANTE_MIN_PERCENT, Math.min(Tuning.ANTE_MAX_PERCENT, percent));
	}

	public int getArmedAntePercent()
	{
		return antePercentArmed;
	}

	public boolean anteArmed()
	{
		return antePercentArmed > 0;
	}

	/** What the armed percent would stake against the purse right now, or 0. */
	public int previewAnteStake()
	{
		return previewAnteStake(antePercentArmed);
	}

	/** What a percent WOULD stake right now. Arms nothing — a preview only. */
	public int previewAnteStake(int percent)
	{
		GachaState state = stateService.get();
		return state == null ? 0 : anteStakeFor(state.getGc(), percent);
	}

	/** GC currently escrowed on the active contract, or 0. */
	public int getActiveAnteStake()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		return task == null ? 0 : task.getAnteStake();
	}

	/**
	 * The local player died. The stake is already out of the purse (escrowed at
	 * accept), so losing it is simply never giving it back: zero the escrow and
	 * the completion path has nothing to return.
	 *
	 * Guarded inside the mutate rather than around it, so a contract completing
	 * on the same tick cannot be charged twice — and a death with nothing staked
	 * returns the identical state instance, which costs no re-encode.
	 */
	@Override
	public void onLocalPlayerDeath()
	{
		final int[] lost = {0};
		stateService.mutate(s -> {
			ActiveTask task = s.getActiveTask();
			if (task == null || task.getAnteStake() <= 0)
			{
				return s;
			}
			lost[0] = task.getAnteStake();
			return s.withActiveTask(task.withAnteStake(0));
		});
		if (lost[0] > 0)
		{
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.MEDIUM, "The Ante is lost",
				lost[0] + " GC staked on this contract is gone. The contract stands.", null));
		}
	}

	// --- Offers ---

	public boolean canRollOffers()
	{
		GachaState state = stateService.get();
		return state != null && state.getActiveTask() == null
			&& (state.getPendingOffers() == null || state.getPendingOffers().isEmpty());
	}

	/** Are rolled-but-undecided offers waiting? */
	public boolean hasPendingOffers()
	{
		GachaState state = stateService.get();
		return state != null && state.getPendingOffers() != null && !state.getPendingOffers().isEmpty();
	}

	/**
	 * Are the waiting offers party-flagged (clicking VOTES rather than accepts)?
	 * Deliberately the SAME first-element convention demotePartyOffers uses, so
	 * the predicate and the demotion can never disagree about a given offer set.
	 */
	public boolean hasPendingPartyOffers()
	{
		GachaState state = stateService.get();
		return state != null && state.getPendingOffers() != null && !state.getPendingOffers().isEmpty()
			&& state.getPendingOffers().get(0).isPartyRoll();
	}

	/** Re-present the already-rolled offers (Esc'd earlier) — never re-rolls. */
	public boolean presentOffers()
	{
		GachaState state = stateService.get();
		if (state == null || state.getPendingOffers() == null || state.getPendingOffers().isEmpty())
		{
			return false;
		}
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, state.getPendingOffers());
		return true;
	}

	/** Personal roll (party rolls go through the party layer's agreement flow). */
	@Nullable
	public List<TaskOffer> rollOffers()
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null || hasPendingOffers())
		{
			// existing offers must be decided (or viewed again) — no free rerolls
			return null;
		}
		int cb = playerCombatLevel();
		List<TaskOffer> offers = TaskGenerator.generateOffers(
			monsterTable.getMonsters(), cb, client.getRealSkillLevel(Skill.SLAYER),
			localIsMembers(), questUnlockService.completedQuests(), state.getTaint() > 0, rng);
		antePercentArmed = 0; // a new board is a new decision
		stateService.mutate(s -> s.withPendingOffers(offers));
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, offers);
		for (Listener listener : listeners)
		{
			listener.onOffersRolled(offers);
		}
		return offers;
	}

	/**
	 * Members world AND an actual membership on the account (ACCOUNT_CREDIT =
	 * membership days remaining) — free accounts never see members tasks. The
	 * party layer shares this in its roll handshake.
	 */
	public boolean localIsMembers()
	{
		return client != null && client.getWorldType().contains(WorldType.MEMBERS)
			&& client.getVarpValue(VarPlayerID.ACCOUNT_CREDIT) > 0;
	}

	/**
	 * Party layer: install an externally generated (seed-shared, identical on
	 * every participant's client) offer set as the pending offers.
	 */
	public boolean presentPartyOffers(List<TaskOffer> offers)
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null || hasPendingOffers()
			|| offers == null || offers.isEmpty())
		{
			return false;
		}
		antePercentArmed = 0; // a new board is a new decision
		stateService.mutate(s -> s.withPendingOffers(offers));
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, offers);
		for (Listener listener : listeners)
		{
			listener.onOffersRolled(offers);
		}
		return true;
	}

	/**
	 * Party layer: the vote died before it bound this player (the party
	 * dissolved, the host went quiet, or a minority settled it without their
	 * vote), but rolls cannot be undone — the SAME offers remain, demoted to
	 * personal ones (clicking now accepts for this player alone).
	 */
	public void demotePartyOffers()
	{
		stateService.mutate(s -> {
			List<TaskOffer> pending = s.getPendingOffers();
			if (pending == null || pending.isEmpty() || !pending.get(0).isPartyRoll())
			{
				return s;
			}
			List<TaskOffer> personal = new ArrayList<>(pending.size());
			for (TaskOffer offer : pending)
			{
				personal.add(new TaskOffer(offer.getDifficulty(), offer.getMonsterName(),
					offer.getMonsterCombatLevel(), offer.getKillsRequired(), offer.getPerKillGc(),
					offer.getCompletionGc(), offer.getSideBets(), offer.isRedemption(), false));
			}
			return s.withPendingOffers(personal);
		});
	}

	public boolean acceptOffer(int index)
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null
			|| state.getPendingOffers() == null || index >= state.getPendingOffers().size())
		{
			return false;
		}
		TaskOffer offer = state.getPendingOffers().get(index);
		if (offer.isPartyRoll())
		{
			// a party offer is not accepted — it is VOTED for; the host's
			// settlement accepts it on every member's client via acceptPartyOffer
			if (partyVoteHook != null)
			{
				try
				{
					partyVoteHook.accept(index);
				}
				catch (Exception e)
				{
					log.warn("party vote hook failed", e);
				}
			}
			return true;
		}
		// solo: the arming IS the consent, taken through the panel's confirmation
		acceptInternal(offer, null, 0, null, true);
		return true;
	}

	/**
	 * Party layer: the vote is settled — every bound member's client accepts
	 * the same offer as a SHARED contract (kills from all of them count).
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		@Nullable List<AttackStyle> partyStyles)
	{
		return acceptPartyOffer(index, partyLabel, partyStyles, false, null);
	}

	/**
	 * Party layer with the Ante verdict. The wager is a SEPARATE decision from
	 * the contract: {@code anteRequested} false signs exactly the same contract,
	 * so a party that could not agree on the wager still hunts together.
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		@Nullable List<AttackStyle> partyStyles, boolean anteRequested)
	{
		return acceptPartyOffer(index, partyLabel, partyStyles, anteRequested, null);
	}

	/**
	 * As above, recording the roll's proposal id on the contract. That id is what
	 * lets a client that restarts mid-contract be recognised by the rest of the
	 * party again — see ActiveTask.partyProposalId. Null signs a contract that can
	 * never be rejoined, which is right for the solo path and for tests.
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		@Nullable List<AttackStyle> partyStyles, boolean anteRequested,
		@Nullable Long proposalId)
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null
			|| state.getPendingOffers() == null || index >= state.getPendingOffers().size())
		{
			return false;
		}
		TaskOffer offer = state.getPendingOffers().get(index);
		acceptInternal(offer, partyLabel, 0, partyStyles, anteRequested, proposalId);
		return true;
	}

	private void acceptInternal(TaskOffer offer, @Nullable String partyLabel, long partyAnchorId,
		@Nullable List<AttackStyle> partyStyles, boolean anteRequested)
	{
		acceptInternal(offer, partyLabel, partyAnchorId, partyStyles, anteRequested, null);
	}

	private void acceptInternal(TaskOffer offer, @Nullable String partyLabel, long partyAnchorId,
		@Nullable List<AttackStyle> partyStyles, boolean anteRequested,
		@Nullable Long partyProposalId)
	{
		ActiveTask task = ActiveTask.builder()
			.difficulty(offer.getDifficulty())
			.monsterName(offer.getMonsterName())
			.monsterCombatLevel(offer.getMonsterCombatLevel())
			.killsRequired(offer.getKillsRequired())
			.killsDone(0)
			.perKillGc(offer.getPerKillGc())
			.completionGc(offer.getCompletionGc())
			.sideBets(offer.getSideBets())
			.redemption(offer.isRedemption())
			.acceptedAtMs(System.currentTimeMillis())
			.appliedCharge(null) // charges are bought DURING a task, slayer-bracelet style
			.partyLabel(partyLabel) // non-null = shared contract (party)
			.partyAnchorId(partyAnchorId)
			.partyProposalId(partyProposalId) // the only handle that survives a restart
			.partyStyles(partyStyles) // snapshot: the clash bonus is priced at signing
			.slayerAligned(SlayerAlignment.matches(offer.getMonsterName(), liveSlayerTarget()))
			.build();
		// The Ante is priced and escrowed INSIDE the mutate that signs the
		// contract, off the balance read there rather than the one read here: a
		// purchase landing in between would otherwise let the stake exceed the
		// purse, and the deduction would silently clamp to less than the contract
		// says is at risk. An ineligible offer, an unarmed player or a purse
		// under the floor all price to 0 and sign the identical contract — the
		// wager can never block or alter the contract itself.
		final int percent = anteRequested && anteEligible(offer) ? antePercentArmed : 0;
		final int[] staked = {0};
		stateService.mutate(s -> {
			int stake = anteStakeFor(s.getGc(), percent);
			staked[0] = stake;
			return s
				.withActiveTask(stake > 0 ? task.withAnteStake(stake) : task)
				.withGc(s.getGc() - stake)
				.withPendingOffers(new ArrayList<>());
		});
		antePercentArmed = 0; // one arming, one contract
		if (staked[0] > 0)
		{
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.MEDIUM, "The Ante is staked",
				staked[0] + " GC is held against this contract — finish it for "
					+ (long) staked[0] * Tuning.ANTE_PAYOUT_MULT + " GC back, die and it is gone.",
				null));
		}
		recentKillTicks.clear();
		resetCombo(); // each contract starts its own rhythm
		if (offerAcceptedHook != null)
		{
			try
			{
				offerAcceptedHook.accept(offer);
			}
			catch (Exception e)
			{
				log.warn("offer accepted hook failed", e);
			}
		}
	}

	/**
	 * Buy a Style Compactor/Extender for the CURRENT task. Requires an active
	 * task with no charge applied yet; one purchase locks both until the task
	 * ends and a new one is assigned. A free starter voucher, when held, is
	 * consumed INSTEAD of GC — never both. The whole purchase (guards, payment
	 * and charge application) is ONE atomic mutate, so a task completing
	 * concurrently can never burn the voucher/GC without applying the charge.
	 */
	public boolean purchaseCharge(boolean compactor)
	{
		String charge = compactor ? "COMPACTOR" : "EXTENDER";
		int price = compactor ? Tuning.COMPACTOR_PRICE_GC : Tuning.EXTENDER_PRICE_GC;
		final boolean[] applied = {false};
		stateService.mutate(s -> {
			if (s.getActiveTask() == null || s.getActiveTask().getAppliedCharge() != null)
			{
				return s;
			}
			GachaState next;
			if (compactor ? s.getFreeCompactors() > 0 : s.getFreeExtenders() > 0)
			{
				next = compactor
					? s.withFreeCompactors(s.getFreeCompactors() - 1)
					: s.withFreeExtenders(s.getFreeExtenders() - 1);
			}
			else if (s.getGc() >= price)
			{
				next = s.withGc(s.getGc() - price);
			}
			else
			{
				return s;
			}
			applied[0] = true;
			return next.withActiveTask(next.getActiveTask().withAppliedCharge(charge));
		});
		return applied[0];
	}

	/**
	 * Party layer: total kills contributed by the OTHER participants (summed
	 * from their broadcasts). Completion triggers when own + others reaches
	 * the shared quota.
	 */
	public void syncPartyKills(int othersTotal)
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null || !task.isParty() || othersTotal <= task.getPartyOtherKills())
		{
			return;
		}
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask().withPartyOtherKills(othersTotal)));
		completeSharedIfReached();
	}

	/** Complete the shared contract when the pooled quota is reached. */
	public void completeSharedIfReached()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task != null && task.isParty()
			&& task.getKillsDone() + task.getPartyOtherKills() >= task.getKillsRequired())
		{
			completeTask();
		}
	}

	/** A participant's client reported the shared contract complete (sync backstop). */
	public void forcePartyComplete()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null || !task.isParty())
		{
			return;
		}
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask()
				.withPartyOtherKills(Math.max(s.getActiveTask().getPartyOtherKills(),
					s.getActiveTask().getKillsRequired() - s.getActiveTask().getKillsDone()))));
		completeSharedIfReached();
	}

	// A contract is a contract: there is deliberately NO abandonTask(). Once a
	// task is accepted the only way out is completing it.

	// --- Rhythm Combo ---

	/** Current combo stacks (0 when no chain is running). */
	public int comboStacks()
	{
		return comboStacks;
	}

	/** Tick of the kill that last fed the combo, or -1. */
	public int comboLastKillTick()
	{
		return comboLastKillTick;
	}

	/** Stacks the chain is worth at the given tick (0 once the idle cutoff passes). */
	public int comboStacksAt(int nowTick)
	{
		if (comboLastKillTick < 0 || nowTick - comboIdleAnchorTick > Tuning.COMBO_IDLE_RESET_TICKS)
		{
			return 0;
		}
		return comboStacks;
	}

	/** Fraction of the growth window remaining at the given tick (0 = chain held, not growing). */
	public double comboWindowFraction(int nowTick)
	{
		if (comboLastKillTick < 0)
		{
			return 0;
		}
		return Math.max(0, 1.0 - (double) (nowTick - comboLastKillTick) / Tuning.COMBO_WINDOW_TICKS);
	}

	/** Ticks left before an alive chain cancels from idling (0 when no chain). */
	public int comboIdleTicksRemaining(int nowTick)
	{
		if (comboLastKillTick < 0)
		{
			return 0;
		}
		return Math.max(0, Tuning.COMBO_IDLE_RESET_TICKS - (nowTick - comboIdleAnchorTick));
	}

	private void resetCombo()
	{
		comboStacks = 0;
		comboLastKillTick = -1;
		comboIdleAnchorTick = -1;
	}

	/**
	 * Logout/profile-switch hygiene: transient combat state must never leak
	 * across characters or let a logout park a combo past its idle window.
	 */
	public void resetTransientCombat()
	{
		resetCombo();
		recentKillTicks.clear();
	}

	/** Advance the chain for an on-task compliant kill; returns the new stack count. */
	private int advanceCombo(int killTick)
	{
		if (comboLastKillTick < 0 || killTick - comboIdleAnchorTick > Tuning.COMBO_IDLE_RESET_TICKS)
		{
			comboStacks = 1; // fresh chain (dead chains stay dead — attacks after
			// the idle cutoff cannot revive them, they start this new one)
		}
		else if (killTick - comboLastKillTick <= Tuning.COMBO_WINDOW_TICKS)
		{
			comboStacks = Math.min(Tuning.COMBO_MAX_STACKS, comboStacks + 1);
		}
		// past the growth window the chain survives (attacks kept it alive) but
		// this kill does not stack
		comboLastKillTick = killTick;
		comboIdleAnchorTick = killTick;
		return comboStacks;
	}

	// StyleTracker.AttackListener: any judged attack keeps a LIVE chain alive —
	// the idle countdown only runs while no attack commands are being issued
	@Override
	public void onAttack(AttackStyle style, int tick)
	{
		if (comboLastKillTick >= 0 && tick - comboIdleAnchorTick <= Tuning.COMBO_IDLE_RESET_TICKS)
		{
			comboIdleAnchorTick = tick;
		}
	}

	// ComplianceService.Listener: a forbidden-style attack breaks the rhythm
	@Override
	public void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc)
	{
		resetCombo();
	}

	@Override
	public void onTaintAdded(int newTaint)
	{
	}

	@Override
	public void onTaintCleared(int cleared, int remaining)
	{
	}

	// --- Kill handling ---

	@Override
	public void onKill(KillTracker.Kill kill)
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return;
		}
		lastKillTick = kill.getTick();
		ActiveTask task = state.getActiveTask();
		boolean onTask = task != null && task.getMonsterName().equalsIgnoreCase(kill.getNpcName());
		if (!onTask)
		{
			journalKill(kill.getNpcName(), 0);
			fireKillFeedback(new KillFeedback(kill.getNpcName(), 0, false, false, false,
				task == null ? 0 : task.getKillsDone(), task == null ? 0 : task.getKillsRequired(),
				false, kill.getDeathLocation()));
			return;
		}

		// ironman honor rule: a kill another player damaged is only half yours
		boolean assistedPenalty = assistedPenaltyApplies(isIronman(), kill.isAssistedByOther(), task);

		// bounded by the DEATH tick: kills are emitted a few ticks late (loot
		// oracle), and a forbidden attack on the NEXT target inside that gap
		// must never taint this finished kill
		int convictingTick = complianceService.convictingAttackTick(
			kill.getEngagementStartTick(), kill.getTick());
		boolean tainted = convictingTick >= 0;
		long awarded = 0;
		if (tainted)
		{
			// name the conviction: the pardon that retracts that verdict a few
			// ticks later must be able to reverse this exact point
			complianceService.addTaint(convictingTick);
			resetCombo(); // a forbidden-style kill has no rhythm
		}
		else
		{
			// rhythm advances on every compliant on-task kill, even when the
			// contract pays nothing per kill (redemption tasks have perKillGc 0)
			int stacks = advanceCombo(kill.getTick());
			// award BEFORE working off taint: with taint > 0 this kill's income
			// (and its side bets) must still be halved — the debt clears after
			if (task.getPerKillGc() > 0)
			{
				// scaled by how the NPC compares to YOUR combat level (trivial
				// mobs pay dust, peers pay a bonus, stronger pays much more),
				// plus the early-game bonus compensating slower kill speeds,
				// plus the rhythm combo for keeping the chain alive, halved
				// when an ironman's kill was assisted
				int playerCb = playerCombatLevel();
				double mult = Tuning.killCbMultiplier(playerCb, kill.getNpcCombatLevel())
					* Tuning.lowLevelMultiplier(playerCb)
					* Tuning.comboMultiplier(stacks, playerCb)
					* (assistedPenalty ? Tuning.ASSISTED_KILL_MULT : 1.0);
				long scaled = Math.round(task.getPerKillGc() * mult);
				awarded = creditSink.award(scaled, new CreditSink.GcContext(
					CreditSink.Source.KILL, kill.getNpcName(), tagsFor(kill.getNpcName())));
			}
			recordDiscovery(kill.getNpcName());
			checkSideBets(kill);
			complianceService.workOffTaint();
		}
		journalKill(kill.getNpcName(), awarded);

		// Compactor doubles the count (expeditious-bracelet fast-forward; the
		// skipped count pays no GC); the assisted penalty halves it, banking a
		// pending half when needed. Clamped so the counter never overshoots.
		KcAdvance advance = kcAdvance("COMPACTOR".equals(task.getAppliedCharge()),
			assistedPenalty, task.isHalfKillPending());
		int newKills = Math.min(task.getKillsRequired(), task.getKillsDone() + advance.getIncrement());
		// shared (party) contracts pool everyone's kills toward the quota
		boolean finalKill = newKills + (task.isParty() ? task.getPartyOtherKills() : 0)
			>= task.getKillsRequired();
		// Double Docket re-checks per kill, so a contract signed before the
		// player picked up the matching assignment still latches on. Skipped
		// once latched: the flag is sticky, so there is nothing further to learn
		// and no reason to keep reading the Slayer config. It rides the kill-count
		// mutate rather than taking one of its own — a second mutate here would
		// re-encode and re-hash the entire save on every kill.
		boolean docketNow = !task.isSlayerAligned()
			&& SlayerAlignment.matches(task.getMonsterName(), liveSlayerTarget());
		// The Dossier's clean flag rides this same mutate for the same reason the
		// docket latch does: a mutate of its own would re-gzip and re-hash the
		// entire save on every out-of-style kill, on the very path that is
		// already the hottest in the plugin.
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask()
				.withKillsDone(newKills)
				.withHalfKillPending(advance.isHalfPending())
				.withSlayerAligned(s.getActiveTask().isSlayerAligned() || docketNow)
				.withTaintedKills(s.getActiveTask().getTaintedKills() + (tainted ? 1 : 0))));

		fireKillFeedback(new KillFeedback(kill.getNpcName(), awarded, true, tainted, finalKill,
			newKills, task.getKillsRequired(), assistedPenalty, kill.getDeathLocation()));

		if (docketNow && slayerLatchHook != null)
		{
			try
			{
				slayerLatchHook.run();
			}
			catch (Exception e)
			{
				log.warn("slayer latch hook failed", e);
			}
		}

		for (Listener listener : listeners)
		{
			if (stateService.get() != null && stateService.get().getActiveTask() != null
				&& stateService.get().getActiveTask().isParty())
			{
				listener.onPartyProgress(stateService.get().getActiveTask());
			}
		}

		if (finalKill)
		{
			completeTask();
		}
	}

	private void checkSideBets(KillTracker.Kill kill)
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() == null)
		{
			return;
		}
		recentKillTicks.add(kill.getTick());
		while (recentKillTicks.size() > 10)
		{
			recentKillTicks.poll();
		}
		ActiveTask task = state.getActiveTask();
		List<SideBet> bets = task.getSideBets();
		if (bets == null || bets.isEmpty())
		{
			return;
		}
		List<SideBet> updated = new ArrayList<>(bets.size());
		boolean changed = false;
		for (SideBet bet : bets)
		{
			if (bet.isCompleted())
			{
				updated.add(bet);
				continue;
			}
			boolean hit = false;
			switch (bet.getKind())
			{
				case BIG_HIT:
					hit = kill.getMaxHitDealt() >= bet.getThreshold();
					break;
				case DAMAGELESS_KILL:
					hit = !kill.isTookDamageDuringEngagement();
					break;
				case SPEED_KILLS:
					hit = countKillsWithin(bet.getWindowTicks(), kill.getTick()) >= bet.getThreshold();
					break;
				case CLUTCH_KILL:
					hit = killTrackerLowHp();
					break;
			}
			if (hit)
			{
				SideBet done = bet.withCompleted(true);
				updated.add(done);
				changed = true;
				creditSink.award(bet.getPayoutGc(), new CreditSink.GcContext(
					CreditSink.Source.SIDE_BET, kill.getNpcName(), tagsFor(kill.getNpcName())));
				for (Listener listener : listeners)
				{
					listener.onSideBetHit(done, kill.getNpcName());
				}
				ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
					CeremonyBus.Fanfare.Size.SMALL,
					bet.isSealed() ? "Sealed bet revealed!" : "Side bet hit!",
					describeSideBet(done) + " +" + bet.getPayoutGc() + " GC", null));
			}
			else
			{
				updated.add(bet);
			}
		}
		if (changed)
		{
			stateService.mutate(s -> s.getActiveTask() == null ? s
				: s.withActiveTask(s.getActiveTask().withSideBets(updated)));
		}
	}

	/**
	 * Bestiary: the first on-task compliant kill of a new species stamps the
	 * codex and pays a discovery bonus; crossing a codex milestone pays more.
	 */
	private void recordDiscovery(String monsterName)
	{
		GachaState state = stateService.get();
		if (state == null || state.getSpeciesDiscovered() == null)
		{
			return;
		}
		// Locale.ROOT: persisted keys must not vary with the JVM locale
		String key = monsterName.toLowerCase(Locale.ROOT);
		if (state.getSpeciesDiscovered().contains(key))
		{
			return;
		}
		GachaState next = stateService.mutate(s -> {
			Set<String> discovered = new HashSet<>(s.getSpeciesDiscovered());
			discovered.add(key);
			return s.withSpeciesDiscovered(discovered);
		});
		creditSink.award(Tuning.DISCOVERY_GC, new CreditSink.GcContext(
			CreditSink.Source.DISCOVERY, monsterName, tagsFor(monsterName)));
		int count = next == null ? 0 : next.getSpeciesDiscovered().size();
		// only the first ten discoveries get a banner (early-game flood control);
		// after that the codex meter and milestone fanfares carry the feedback
		if (count <= 10)
		{
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.SMALL, "New species: " + monsterName,
				"Codex entry " + count + " — +" + Tuning.DISCOVERY_GC + " GC", null));
		}
		for (int i = 0; i < Tuning.BESTIARY_MILESTONES.length; i++)
		{
			if (count == Tuning.BESTIARY_MILESTONES[i])
			{
				int bonus = Tuning.BESTIARY_MILESTONE_GC[i];
				creditSink.award(bonus, new CreditSink.GcContext(
					CreditSink.Source.DISCOVERY, null, null));
				ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
					CeremonyBus.Fanfare.Size.MEDIUM, count + " species discovered!",
					"The codex swells — +" + bonus + " GC", null));
			}
		}
	}

	private boolean killTrackerLowHp()
	{
		int boosted = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int real = client.getRealSkillLevel(Skill.HITPOINTS);
		return real > 0 && boosted * 4 <= real;
	}

	private int countKillsWithin(int windowTicks, int nowTick)
	{
		int count = 0;
		for (int t : recentKillTicks)
		{
			if (nowTick - t <= windowTicks)
			{
				count++;
			}
		}
		return count;
	}

	public static String describeSideBet(SideBet bet)
	{
		switch (bet.getKind())
		{
			case BIG_HIT:
				return "Land a hit of " + bet.getThreshold() + "+";
			case DAMAGELESS_KILL:
				return "A kill without taking damage";
			case SPEED_KILLS:
				return bet.getThreshold() + " kills within " + (bet.getWindowTicks() * 3 / 5) + "s";
			case CLUTCH_KILL:
				return "Finish a kill under 25% HP";
			default:
				return "?";
		}
	}

	// --- Completion ---

	/**
	 * How many DISTINCT styles a shared contract was signed with. Null is the
	 * legacy case (a save written before the snapshot existed, or a solo task)
	 * and null ENTRIES are real too — a participant on an older client sends
	 * no style, and Gson keeps null array elements across a save/load — so
	 * both must count as no contribution rather than throw.
	 */
	static int distinctStyles(@Nullable List<AttackStyle> styles)
	{
		if (styles == null || styles.isEmpty())
		{
			return 0;
		}
		EnumSet<AttackStyle> seen = EnumSet.noneOf(AttackStyle.class);
		for (AttackStyle style : styles)
		{
			if (style != null)
			{
				seen.add(style);
			}
		}
		return seen.size();
	}

	private void completeTask()
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() == null)
		{
			return;
		}
		ActiveTask task = state.getActiveTask();
		// one clock read shared by the personal best and by the Dossier record, so
		// the filed timestamp and the filed duration cannot disagree
		long completedAtMs = System.currentTimeMillis();
		long duration = completedAtMs - task.getAcceptedAtMs();

		double completionMult = 1.0;
		if (task.isParty())
		{
			// Shared contract: the co-op bonus applies party-wide, plus a FLAT
			// clash bonus if the party covers 2+ distinct styles. Flat, not per
			// extra style — a trio running all three styles pays exactly what a
			// pair running two pays. Computed over the accept-time snapshot
			// (self included), so every client pays the same and a mid-contract
			// style re-roll cannot reprice a signed contract.
			completionMult = Tuning.PARTY_REWARD_MULT;
			if (distinctStyles(task.getPartyStyles()) > 1)
			{
				completionMult += Tuning.PARTY_STYLE_CLASH_BONUS;
			}
		}
		else if (task.isPartyConvertedToSolo())
		{
			completionMult = Tuning.PARTY_CARRY_MULT;
		}

		// Double Docket stacks MULTIPLICATIVELY on whatever the party chain came
		// to, so it is worth the same proportion of a shared contract as of a
		// solo one. The taint halving still lands after all of this, inside the
		// sink, exactly as it does for every other completion modifier.
		if (task.isSlayerAligned())
		{
			completionMult *= Tuning.DOUBLE_DOCKET_MULT;
		}

		// Redemption clears taint BEFORE the award so its own completion
		// reward is not halved by the debt it just paid off.
		boolean redemptionCleared = false;
		if (task.isRedemption())
		{
			complianceService.clearAllTaint();
			redemptionCleared = true;
		}

		long completionAwarded = creditSink.award(Math.round(task.getCompletionGc() * completionMult),
			new CreditSink.GcContext(CreditSink.Source.TASK_COMPLETION, task.getMonsterName(),
				tagsFor(task.getMonsterName())));

		int sideBetsHit = 0;
		long sideBetGc = 0;
		if (task.getSideBets() != null)
		{
			for (SideBet bet : task.getSideBets())
			{
				if (bet.isCompleted())
				{
					sideBetsHit++;
					sideBetGc += bet.getPayoutGc();
				}
			}
		}
		long haul = completionAwarded + sideBetGc + (long) task.getPerKillGc() * task.getKillsRequired();

		// journal + PBs
		boolean newFastest = false;
		boolean newHaul = false;
		String pbKey = task.getDifficulty().name();
		PersonalBest pb = state.getPersonalBests().get(pbKey);
		if (pb == null)
		{
			pb = new PersonalBest(0, null, 0, null);
		}
		PersonalBest updatedPb = pb;
		if (pb.getFastestTaskMs() == 0 || duration < pb.getFastestTaskMs())
		{
			updatedPb = updatedPb.withFastestTaskMs(duration).withFastestMonster(task.getMonsterName());
			newFastest = pb.getFastestTaskMs() != 0;
		}
		if (haul > pb.getBiggestHaulGc())
		{
			updatedPb = updatedPb.withBiggestHaulGc((int) haul).withBiggestHaulMonster(task.getMonsterName());
			newHaul = pb.getBiggestHaulGc() != 0;
		}
		if (newFastest || newHaul)
		{
			creditSink.award(Tuning.PB_RECORD_GC, new CreditSink.GcContext(
				CreditSink.Source.RECORD, null, null));
		}

		final PersonalBest pbFinal = updatedPb;
		final String monsterName = task.getMonsterName();
		int newTotal = state.getTotalTasksCompleted() + 1;

		// deed milestone?
		int milestone = 0;
		int claimed = state.getDeedMilestonesClaimed();
		if (claimed < Tuning.DEED_TASK_MILESTONES.length
			&& newTotal >= Tuning.DEED_TASK_MILESTONES[claimed]
			&& state.getDeededSlots().size() < GearSlot.values().length)
		{
			milestone = Tuning.DEED_TASK_MILESTONES[claimed];
		}
		final int milestoneFinal = milestone;

		// Deed Fragments: harder contracts during the first ten tasks pay
		// fragments; ten forge the one-per-account bonus deed
		int fragmentsEarned = 0;
		if (!state.isFragmentDeedForged() && newTotal <= Tuning.FRAGMENT_WINDOW_TASKS)
		{
			fragmentsEarned = Tuning.fragmentsFor(task.getDifficulty());
		}
		final int fragmentsEarnedFinal = fragmentsEarned;

		// the applied charge is read INSIDE the clearing mutate so a purchase
		// landing after this method's task snapshot still reaches advanceCycle
		final String[] chargeApplied = {task.getAppliedCharge()};
		// ...and so is the stake, for the same reason in reverse: a death landing
		// after the snapshot has already zeroed the escrow, and returning the
		// stale figure would refund a wager that was lost.
		final int[] anteStake = {task.getAnteStake()};
		// ...and the violation count, so a final kill convicted between this
		// method's snapshot and the mutate still files the contract as dirty
		final int[] taintedKills = {task.getTaintedKills()};
		GachaState afterMutate = stateService.mutate(s -> {
			if (s.getActiveTask() != null)
			{
				chargeApplied[0] = s.getActiveTask().getAppliedCharge();
				anteStake[0] = s.getActiveTask().getAnteStake();
				taintedKills[0] = s.getActiveTask().getTaintedKills();
			}
			Map<String, PersonalBest> pbs = new HashMap<>(s.getPersonalBests());
			pbs.put(pbKey, pbFinal);
			Map<String, Integer> byDiff = new HashMap<>(s.getTasksCompletedByDifficulty());
			byDiff.merge(task.getDifficulty().name(), 1, Integer::sum);
			Map<String, MonsterStats> stats = new HashMap<>(s.getMonsterStats());
			MonsterStats ms = stats.getOrDefault(monsterName, new MonsterStats(0, 0, 0));
			stats.put(monsterName, ms.withTasksCompleted(ms.getTasksCompleted() + 1));
			GachaState next = s
				.withActiveTask(null)
				.withPersonalBests(pbs)
				.withTasksCompletedByDifficulty(byDiff)
				.withMonsterStats(stats)
				.withTotalTasksCompleted(newTotal);
			if (anteStake[0] > 0)
			{
				// The principal comes back RAW, in the same mutate that retires
				// the contract, because it was never income — it is the player's
				// own GC coming out of escrow. Through the sink it would be
				// halved by taint and would inflate lifetime earnings by money
				// the player merely got back. Only the PROFIT is awarded, below.
				next = next.withGc(next.getGc() + anteStake[0]);
			}
			if (milestoneFinal > 0)
			{
				next = next.withDeedMilestonesClaimed(s.getDeedMilestonesClaimed() + 1)
					.withPendingDeeds(s.getPendingDeeds() + 1);
			}
			if (fragmentsEarnedFinal > 0 && !s.isFragmentDeedForged())
			{
				int frags = s.getDeedFragments() + fragmentsEarnedFinal;
				if (frags >= Tuning.FRAGMENTS_REQUIRED)
				{
					next = next.withDeedFragments(Tuning.FRAGMENTS_REQUIRED)
						.withFragmentDeedForged(true)
						.withPendingDeeds(next.getPendingDeeds() + 1);
				}
				else
				{
					next = next.withDeedFragments(frags);
				}
			}
			// File the contract. This rides the completion mutate rather than
			// taking one of its own, and the style is read off s HERE — before
			// styleService.advanceCycle() runs below — so the Dossier files the
			// style the contract was RUN under, not the one rolled as its reward.
			// getKillsRequired() is the quota the pay was computed against; on a
			// shared contract those kills were pooled, which the row's party tag
			// makes plain.
			next = next.withContractLog(ContractRecord.appendCapped(s.getContractLog(),
				new ContractRecord(completedAtMs, monsterName, task.getDifficulty().name(),
					task.getKillsRequired(), haul, duration, s.getAllowedStyle(), taintedKills[0],
					task.getPartyLabel(), task.isPartyConvertedToSolo(), task.isRedemption()),
				Tuning.DOSSIER_MAX_RECORDS));
			return next;
		});

		if (anteStake[0] > 0)
		{
			// Only the winnings are income. SIDE_BET is the honest source: the
			// Ante is the same kind of thing as the contract's own side bets,
			// and taint halving the PROFIT (never the principal) is exactly the
			// treatment every other bet on this contract gets.
			long profit = creditSink.award((long) anteStake[0] * (Tuning.ANTE_PAYOUT_MULT - 1),
				new CreditSink.GcContext(CreditSink.Source.SIDE_BET, task.getMonsterName(),
					tagsFor(task.getMonsterName())));
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.MEDIUM, "The Ante pays",
				anteStake[0] + " GC staked returns with " + profit + " GC won.", null));
		}

		boolean forgedNow = fragmentsEarned > 0 && !state.isFragmentDeedForged()
			&& afterMutate != null && afterMutate.isFragmentDeedForged();
		int fragmentsTotal = afterMutate == null
			? state.getDeedFragments() : afterMutate.getDeedFragments();

		boolean cycleTriggered = styleService.advanceCycle(chargeApplied[0]);

		TaskCompletionSummary summary = new TaskCompletionSummary(task, completionAwarded,
			sideBetsHit, duration, newFastest, newHaul, redemptionCleared, cycleTriggered, milestone,
			fragmentsEarned, fragmentsTotal, forgedNow);
		ceremonyBus.submit(CeremonyBus.Type.TASK_COMPLETE, summary);
		for (Listener listener : listeners)
		{
			listener.onTaskCompleted(summary);
		}
		if (milestone > 0)
		{
			ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, milestone);
		}
		if (forgedNow)
		{
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.LARGE, "Deed forged from fragments!",
				Tuning.FRAGMENTS_REQUIRED + " fragments fuse into a bonus Slot Deed — choose a slot.",
				null));
			ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
		}
		if (cycleTriggered)
		{
			styleService.roll(lastKillTick);
		}
		resetCombo(); // rhythm does not carry across contracts
	}

	// --- Party carry clause ---

	/**
	 * The Ante deliberately survives this. A stake is personal and the contract
	 * is binding, so a partner leaving does not release this player's wager: the
	 * carry clause already prices the extra difficulty (PARTY_CARRY_MULT), and
	 * refunding here would make "partner logs out" the cheap way out of a losing
	 * bet. Finish it alone and it still pays double.
	 */
	public void convertPartyToSolo()
	{
		stateService.mutate(s -> s.getActiveTask() == null || !s.getActiveTask().isParty() ? s
			: s.withActiveTask(s.getActiveTask().withPartyConvertedToSolo(true)));
	}

	// --- Helpers ---

	private void journalKill(String monsterName, long gcAwarded)
	{
		stateService.mutate(s -> {
			Map<String, MonsterStats> stats = new HashMap<>(s.getMonsterStats());
			MonsterStats ms = stats.getOrDefault(monsterName, new MonsterStats(0, 0, 0));
			stats.put(monsterName, ms.withKills(ms.getKills() + 1).withGcEarned(ms.getGcEarned() + gcAwarded));
			return s.withMonsterStats(stats);
		});
	}

	private void fireKillFeedback(KillFeedback feedback)
	{
		for (Listener listener : new ArrayList<>(listeners))
		{
			try
			{
				listener.onKillFeedback(feedback);
			}
			catch (Exception e)
			{
				log.warn("kill feedback listener failed", e);
			}
		}
	}

	@Nullable
	private List<String> tagsFor(String monsterName)
	{
		if (tagsByMonster == null)
		{
			tagsByMonster = new HashMap<>();
			for (MonsterTable.Monster monster : monsterTable.getMonsters())
			{
				tagsByMonster.put(monster.getName().toLowerCase(), monster.getTags());
			}
		}
		return tagsByMonster.get(monsterName.toLowerCase());
	}

	/** Any ironman variant (varbit 1777 nonzero: IM/UIM/HCIM/GIM...). */
	private boolean isIronman()
	{
		return client != null
			&& client.getVarbitValue(VarbitID.IRONMAN) > 0;
	}

	public int playerCombatLevel()
	{
		return Experience.getCombatLevel(
			client.getRealSkillLevel(Skill.ATTACK),
			client.getRealSkillLevel(Skill.STRENGTH),
			client.getRealSkillLevel(Skill.DEFENCE),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.MAGIC),
			client.getRealSkillLevel(Skill.RANGED),
			client.getRealSkillLevel(Skill.PRAYER));
	}
}
