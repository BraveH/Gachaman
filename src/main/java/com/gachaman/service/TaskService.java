package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.MonsterStats;
import com.gachaman.model.PersonalBest;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;

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
		net.runelite.api.coords.LocalPoint deathLocation;
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
		return ironman && assistedByOther && (task == null || !task.isDuo());
	}

	public interface Listener
	{
		void onKillFeedback(KillFeedback feedback);

		void onSideBetHit(SideBet bet, String monsterName);

		void onTaskCompleted(TaskCompletionSummary summary);

		void onOffersRolled(List<TaskOffer> offers);

		/** Duo hook: local player progressed a duo task. */
		void onDuoProgress(ActiveTask task);
	}

	private final Client client;
	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final ComplianceService complianceService;
	private final StyleService styleService;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;
	private final MonsterTable monsterTable;

	private final List<Listener> listeners = new ArrayList<>();
	/** Optional hook the plugin wires for the party layer. */
	private java.util.function.Consumer<TaskOffer> offerAcceptedHook;
	/** Party layer: clicking a party-roll offer casts a VOTE instead of accepting. */
	private java.util.function.IntConsumer partyVoteHook;
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

	@Inject
	public TaskService(Client client, GachaStateService stateService, CreditSink creditSink,
		ComplianceService complianceService, StyleService styleService, CeremonyBus ceremonyBus,
		GachaRng rng, MonsterTable monsterTable)
	{
		this.client = client;
		this.stateService = stateService;
		this.creditSink = creditSink;
		this.complianceService = complianceService;
		this.styleService = styleService;
		this.ceremonyBus = ceremonyBus;
		this.rng = rng;
		this.monsterTable = monsterTable;
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

	public void setOfferAcceptedHook(java.util.function.Consumer<TaskOffer> hook)
	{
		this.offerAcceptedHook = hook;
	}

	public void setPartyVoteHook(java.util.function.IntConsumer hook)
	{
		this.partyVoteHook = hook;
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
			localIsMembers(), state.getTaint() > 0, rng);
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
			&& client.getVarpValue(net.runelite.api.gameval.VarPlayerID.ACCOUNT_CREDIT) > 0;
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
		stateService.mutate(s -> s.withPendingOffers(offers));
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, offers);
		for (Listener listener : listeners)
		{
			listener.onOffersRolled(offers);
		}
		return true;
	}

	/**
	 * Party layer: the party dissolved before a unanimous vote, but rolls
	 * cannot be undone — the SAME offers remain, demoted to personal ones
	 * (clicking now accepts for this player alone instead of voting).
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
			// a party offer is not accepted — it is VOTED for; unanimity
			// accepts it on every participant's client via acceptPartyOffer
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
		acceptInternal(offer, null, 0);
		return true;
	}

	/**
	 * Party layer: unanimity reached — every participant's client accepts the
	 * same offer as a SHARED contract (kills from all participants count).
	 */
	public boolean acceptPartyOffer(int index, String partyLabel)
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null
			|| state.getPendingOffers() == null || index >= state.getPendingOffers().size())
		{
			return false;
		}
		TaskOffer offer = state.getPendingOffers().get(index);
		acceptInternal(offer, partyLabel, 0);
		return true;
	}

	private void acceptInternal(TaskOffer offer, @Nullable String partyLabel, long partyAnchorId)
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
			.duoPartnerName(partyLabel) // non-null = shared contract (party)
			.duoPartnerMemberId(partyAnchorId)
			.build();
		stateService.mutate(s -> s
			.withActiveTask(task)
			.withPendingOffers(new ArrayList<>()));
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
		if (task == null || !task.isDuo() || othersTotal <= task.getDuoPartnerKills())
		{
			return;
		}
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask().withDuoPartnerKills(othersTotal)));
		completeSharedIfReached();
	}

	/** Complete the shared contract when the pooled quota is reached. */
	public void completeSharedIfReached()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task != null && task.isDuo()
			&& task.getKillsDone() + task.getDuoPartnerKills() >= task.getKillsRequired())
		{
			completeTask();
		}
	}

	/** A participant's client reported the shared contract complete (sync backstop). */
	public void forcePartyComplete()
	{
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null || !task.isDuo())
		{
			return;
		}
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask()
				.withDuoPartnerKills(Math.max(s.getActiveTask().getDuoPartnerKills(),
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
		boolean tainted = complianceService.forbiddenAttackBetween(
			kill.getEngagementStartTick(), kill.getTick());
		long awarded = 0;
		if (tainted)
		{
			complianceService.addTaint();
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
		boolean finalKill = newKills + (task.isDuo() ? task.getDuoPartnerKills() : 0)
			>= task.getKillsRequired();
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask()
				.withKillsDone(newKills)
				.withHalfKillPending(advance.isHalfPending())));

		fireKillFeedback(new KillFeedback(kill.getNpcName(), awarded, true, tainted, finalKill,
			newKills, task.getKillsRequired(), assistedPenalty, kill.getDeathLocation()));

		for (Listener listener : listeners)
		{
			if (stateService.get() != null && stateService.get().getActiveTask() != null
				&& stateService.get().getActiveTask().isDuo())
			{
				listener.onDuoProgress(stateService.get().getActiveTask());
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
		String key = monsterName.toLowerCase(java.util.Locale.ROOT);
		if (state.getSpeciesDiscovered().contains(key))
		{
			return;
		}
		GachaState next = stateService.mutate(s -> {
			java.util.Set<String> discovered = new java.util.HashSet<>(s.getSpeciesDiscovered());
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

	private void completeTask()
	{
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() == null)
		{
			return;
		}
		ActiveTask task = state.getActiveTask();
		long duration = System.currentTimeMillis() - task.getAcceptedAtMs();

		double completionMult = 1.0;
		if (task.isDuo())
		{
			// shared contract: the co-op bonus applies party-wide; the style
			// clash bonus needs a known partner style (2-player parties)
			completionMult = Tuning.DUO_REWARD_MULT;
			AttackStyle mine = state.getAllowedStyle() == null ? null : AttackStyle.valueOf(state.getAllowedStyle());
			if (mine != null && task.getDuoPartnerStyle() != null && mine != task.getDuoPartnerStyle())
			{
				completionMult += Tuning.DUO_STYLE_CLASH_BONUS;
			}
		}
		else if (task.isDuoConvertedToSolo())
		{
			completionMult = Tuning.DUO_CARRY_MULT;
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
			&& state.getDeededSlots().size() < com.gachaman.model.GearSlot.values().length)
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
		GachaState afterMutate = stateService.mutate(s -> {
			if (s.getActiveTask() != null)
			{
				chargeApplied[0] = s.getActiveTask().getAppliedCharge();
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
			return next;
		});

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

	// --- Duo carry clause ---

	public void convertDuoToSolo()
	{
		stateService.mutate(s -> s.getActiveTask() == null || !s.getActiveTask().isDuo() ? s
			: s.withActiveTask(s.getActiveTask().withDuoConvertedToSolo(true)));
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
			&& client.getVarbitValue(net.runelite.api.gameval.VarbitID.IRONMAN) > 0;
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
