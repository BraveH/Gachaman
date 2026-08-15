package com.gachaman.service;

// COLLISION RESOLVER, do not "tidy" into the java.util.* wildcard below: RuneLite
// ships its own net.runelite.api.Deque (the client's linked-list container), which
// the net.runelite.api.* wildcard also drags in, and the two make the bare name
// ambiguous. The single-class import outranks both wildcards and picks java.util's.
import java.util.Deque;
import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.gameval.*;

/**
 * The kill-task engine: offer rolls, acceptance, per-kill crediting with
 * taint rules, side bets, completion rewards, journal/PBs, style-cycle
 * advancement and deed milestones.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TaskService implements KillTracker.KillListener, ComplianceService.Listener,
	StyleTracker.AttackListener {
	@Value
	public static class TaskCompletionSummary {
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
		/** Lifetime contract number this completion was, counting from 1. */
		int taskNumber;
		/** Slayer-style milestone multiple this completion paid; 1.0 = ordinary. */
		double completionMilestoneMult;
	}

	@Value
	public static class KillFeedback {
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
	public static class KcAdvance {
		int increment;
		boolean halfPending;
	}

	static KcAdvance kcAdvance(boolean compactor, boolean assistedPenalty, boolean pendingHalf) {
		if (!assistedPenalty)
			return new KcAdvance(compactor ? 2 : 1, pendingHalf);
		if (compactor) {
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
	static boolean assistedPenaltyApplies(boolean ironman, boolean assistedByOther, ActiveTask task) {
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
	public static int anteStakeFor(long gc, int percent) {
		if (gc < Tuning.ANTE_MIN_PURSE_GC || percent <= 0)
			return 0;
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
	public static boolean anteEligible(TaskOffer offer) {
		return offer != null && offer.getDifficulty() == TaskDifficulty.INSANE;
	}

	/**
	 * Optional hooks default to no-ops: most listeners care about exactly one
	 * of them, and four empty bodies per implementor is noise that hides which
	 * ones a class actually acts on.
	 *
	 * <p>{@link #onTaskCompleted} is deliberately NOT defaulted. It is the hook
	 * that books rewards and records service, so a new listener that forgets it
	 * should fail to compile rather than silently drop them.
	 */
	public interface Listener {
		default void onKillFeedback(KillFeedback feedback) {
		}

		default void onSideBetHit(SideBet bet, String monsterName) {
		}

		void onTaskCompleted(TaskCompletionSummary summary);

		default void onOffersRolled(List<TaskOffer> offers) {
		}

		/** Party hook: local player progressed a shared party contract. */
		default void onPartyProgress(ActiveTask task) {
		}
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
	private final MaxHitService maxHitService;
	/**
	 * The Preferred Weapon pair. StyleTracker is asked what was in hand at the
	 * killing blow (never what is in hand now — see {@link #weaponMultFor}), and
	 * WeaponTypeService decides whether that satisfies the wheel's named category.
	 *
	 * <p>Declared LAST on purpose: Lombok builds the constructor in field order,
	 * so appending here leaves every existing positional call site — all of them
	 * headless tests — able to say what it always said plus the new arguments.
	 */
	private final StyleTracker styleTracker;
	private final WeaponTypeService weaponTypeService;
	/**
	 * The Consignment, which owns the moment a style roll comes due. Injected
	 * rather than hooked (the four @Setter hooks above exist so the payout path
	 * stays testable without a Client) because this one is not optional: a
	 * completion that silently skipped it would drop the deferred roll AND the
	 * feature, and Guice refusing to build the service is a far better failure
	 * than a wheel that quietly never offers.
	 */
	private final ConsignmentService consignmentService;

	private final List<Listener> listeners = new ArrayList<>();
	/** Optional hook the plugin wires for the party layer. */
	@Setter
	private Consumer<TaskOffer> offerAcceptedHook;
	/** Party layer: clicking a party-roll offer casts a VOTE instead of accepting. */
	@Setter
	private IntConsumer partyVoteHook;
	/**
	 * Double Docket: supplies the live Slayer assignment name, or null. A hook
	 * rather than an injected dependency so the whole payout path stays testable
	 * without a Client — every unit test leaves it unwired and gets no bonus.
	 */
	@Setter
	private Supplier<String> slayerTargetHook;
	/** Double Docket: fired once, the moment a contract latches on to the bonus. */
	@Setter
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
	/** Compliant kills banked on the running chain; stacks are earned from these. */
	private int comboKills;
	/**
	 * Last activity (kill OR attack) keeping the chain alive, or -1 for no
	 * chain. Only a kill starts one; attacks merely hold an existing chain
	 * open, which is why this doubles as the "is a chain running" sentinel.
	 */
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

	public void addListener(Listener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	/**
	 * The contract in force, or null. Deliberately conflates "no state loaded"
	 * with "no contract": every caller of this already treated the two the same
	 * way, and one read of the snapshot means the thing tested and the thing
	 * used are the same object by construction.
	 *
	 * NOT usable where the two cases must be told apart — canRollOffers must say
	 * "no" while the state is still loading, and onKill must not journal a kill
	 * against a state that does not exist yet.
	 */
	@Nullable
	private ActiveTask activeTask() {
		GachaState state = stateService.get();
		return state == null ? null : state.getActiveTask();
	}

	/**
	 * The offers sitting on the board, never null. A missing state and a missing
	 * list both mean "the board is empty", which is exactly what each caller's
	 * old null test spelled out by hand. The state's OWN list comes back
	 * unchanged when there is one, so a caller that hands it to the ceremony bus
	 * still passes the same instance it always did.
	 */
	private List<TaskOffer> pending() {
		GachaState state = stateService.get();
		List<TaskOffer> offers = state == null ? null : state.getPendingOffers();
		return offers == null ? List.of() : offers;
	}

	/**
	 * The live Slayer assignment, or null. Swallows hook failures: a broken
	 * Slayer read must cost the player a bonus, never a kill credit.
	 */
	@Nullable
	private String liveSlayerTarget() {
		if (slayerTargetHook == null)
			return null;
		try {
			return slayerTargetHook.get();
		}
		catch (Exception e) {
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
	public void armAnte(int percent) {
		antePercentArmed = percent <= 0 ? 0
			: Math.max(Tuning.ANTE_MIN_PERCENT, Math.min(Tuning.ANTE_MAX_PERCENT, percent));
	}

	public int getArmedAntePercent() {
		return antePercentArmed;
	}

	public boolean anteArmed() {
		return antePercentArmed > 0;
	}

	/** What the armed percent would stake against the purse right now, or 0. */
	public int previewAnteStake() {
		return previewAnteStake(antePercentArmed);
	}

	/** What a percent WOULD stake right now. Arms nothing — a preview only. */
	public int previewAnteStake(int percent) {
		GachaState state = stateService.get();
		return state == null ? 0 : anteStakeFor(state.getGc(), percent);
	}

	/** GC currently escrowed on the active contract, or 0. */
	public int getActiveAnteStake() {
		ActiveTask task = activeTask();
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
	public void onLocalPlayerDeath() {
		final int[] lost = {0};
		stateService.mutate(s -> {
			ActiveTask task = s.getActiveTask();
			if (task == null || task.getAnteStake() <= 0)
				return s;
			lost[0] = task.getAnteStake();
			return s.withActiveTask(task.withAnteStake(0));
		});
		if (lost[0] > 0) {
			fanfare(CeremonyBus.Fanfare.Size.MEDIUM, "The Ante is lost",
				lost[0] + " GC staked on this contract is gone. The contract stands.");
		}
	}

	// --- Offers ---

	public boolean canRollOffers() {
		GachaState state = stateService.get();
		return state != null && state.getActiveTask() == null
			&& (state.getPendingOffers() == null || state.getPendingOffers().isEmpty());
	}

	/** Are rolled-but-undecided offers waiting? */
	public boolean hasPendingOffers() {
		return !pending().isEmpty();
	}

	/**
	 * Are the waiting offers party-flagged (clicking VOTES rather than accepts)?
	 * Deliberately the SAME first-element convention demotePartyOffers uses, so
	 * the predicate and the demotion can never disagree about a given offer set.
	 */
	public boolean hasPendingPartyOffers() {
		List<TaskOffer> offers = pending();
		return !offers.isEmpty() && offers.get(0).isPartyRoll();
	}

	/** Re-present the already-rolled offers (Esc'd earlier) — never re-rolls. */
	public boolean presentOffers() {
		List<TaskOffer> offers = pending();
		if (offers.isEmpty())
			return false;
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, offers);
		return true;
	}

	/**
	 * Put a freshly decided offer set on the board: disarm the wager, persist the
	 * offers, present them and tell the listeners. Shared by the personal roll and
	 * by the party layer's installer so the two can never drift — a board that
	 * kept a stale arming, or one the panel never heard about, would be a bug
	 * visible only on whichever of the two paths forgot a line.
	 */
	private void installOffers(List<TaskOffer> offers) {
		antePercentArmed = 0; // a new board is a new decision
		stateService.mutate(s -> s.withPendingOffers(offers));
		ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, offers);
		for (Listener listener : listeners) {
			listener.onOffersRolled(offers);
		}
	}

	/** Personal roll (party rolls go through the party layer's agreement flow). */
	@Nullable
	public List<TaskOffer> rollOffers() {
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null || hasPendingOffers()) {
			// existing offers must be decided (or viewed again) — no free rerolls
			return null;
		}
		List<TaskOffer> offers = TaskGenerator.generateOffers(
			monsterTable.getMonsters(), playerCombatLevel(), lvl(Skill.SLAYER),
			localIsMembers(), questUnlockService.completedQuests(), state.getTaint() > 0,
			maxHitService.estimateFor(state.getAllowedStyle()),
			// solo: the wheel has locked THIS player, and their style is always
			// known, so a melee-unreachable contract can never be dealt here
			AttackStyle.MELEE.name().equals(state.getAllowedStyle()), rng);
		installOffers(offers);
		return offers;
	}

	/**
	 * Members world AND an actual membership on the account (ACCOUNT_CREDIT =
	 * membership days remaining) — free accounts never see members tasks. The
	 * party layer shares this in its roll handshake.
	 */
	public boolean localIsMembers() {
		return client != null && client.getWorldType().contains(WorldType.MEMBERS)
			&& client.getVarpValue(VarPlayerID.ACCOUNT_CREDIT) > 0;
	}

	/**
	 * Party layer: install an externally generated (seed-shared, identical on
	 * every participant's client) offer set as the pending offers.
	 */
	public boolean presentPartyOffers(List<TaskOffer> offers) {
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null || hasPendingOffers()
			|| offers == null || offers.isEmpty()) {
			return false;
		}
		installOffers(offers);
		return true;
	}

	/**
	 * Party layer: the vote died before it bound this player (the party
	 * dissolved, the host went quiet, or a minority settled it without their
	 * vote), but rolls cannot be undone — the SAME offers remain, demoted to
	 * personal ones (clicking now accepts for this player alone).
	 */
	public void demotePartyOffers() {
		stateService.mutate(s -> {
			List<TaskOffer> pending = s.getPendingOffers();
			if (pending == null || pending.isEmpty() || !pending.get(0).isPartyRoll())
				return s;
			List<TaskOffer> personal = new ArrayList<>(pending.size());
			for (TaskOffer offer : pending) {
				personal.add(new TaskOffer(offer.getDifficulty(), offer.getMonsterName(),
					offer.getMonsterCombatLevel(), offer.getKillsRequired(), offer.getPerKillGc(),
					offer.getCompletionGc(), offer.getSideBets(), offer.isRedemption(), false));
			}
			return s.withPendingOffers(personal);
		});
	}

	/**
	 * The offer at a board position, or null when there is nothing there to
	 * accept — no state, a contract already in force, an empty board, or an index
	 * past the end.
	 *
	 * Deliberately reads the state itself instead of composing pending(): the
	 * guard order here (null list tested BEFORE the bounds test) is the one both
	 * accept paths have always had, and routing it through a never-null list
	 * would quietly turn a negative index from today's throw into a false.
	 */
	@Nullable
	private TaskOffer offerAt(int index) {
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() != null
			|| state.getPendingOffers() == null || index >= state.getPendingOffers().size()) {
			return null;
		}
		return state.getPendingOffers().get(index);
	}

	public boolean acceptOffer(int index) {
		TaskOffer offer = offerAt(index);
		if (offer == null)
			return false;
		if (offer.isPartyRoll()) {
			// a party offer is not accepted — it is VOTED for; the host's
			// settlement accepts it on every member's client via acceptPartyOffer
			Listeners.fireHook(partyVoteHook, h -> h.accept(index), "party vote hook failed");
			return true;
		}
		// solo: the arming IS the consent, taken through the panel's confirmation
		acceptInternal(offer, null, null, true, null);
		return true;
	}

	/**
	 * Party layer: the vote is settled — every bound member's client accepts
	 * the same offer as a SHARED contract (kills from all of them count).
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		List<AttackStyle> partyStyles) {
		return acceptPartyOffer(index, partyLabel, partyStyles, false, null);
	}

	/**
	 * Party layer with the Ante verdict. The wager is a SEPARATE decision from
	 * the contract: {@code anteRequested} false signs exactly the same contract,
	 * so a party that could not agree on the wager still hunts together.
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		List<AttackStyle> partyStyles, boolean anteRequested) {
		return acceptPartyOffer(index, partyLabel, partyStyles, anteRequested, null);
	}

	/**
	 * As above, recording the roll's proposal id on the contract. That id is what
	 * lets a client that restarts mid-contract be recognised by the rest of the
	 * party again — see ActiveTask.partyProposalId. Null signs a contract that can
	 * never be rejoined, which is right for the solo path and for tests.
	 */
	public boolean acceptPartyOffer(int index, String partyLabel,
		List<AttackStyle> partyStyles, boolean anteRequested,
		Long proposalId) {
		TaskOffer offer = offerAt(index);
		if (offer == null)
			return false;
		acceptInternal(offer, partyLabel, partyStyles, anteRequested, proposalId);
		return true;
	}

	/**
	 * Signs the contract. There is no partyAnchorId parameter: every caller ever
	 * passed 0, because RuneLite regenerates party member ids each client session
	 * and the id is therefore worthless for recognising anyone — partyProposalId
	 * is what actually reunites a restarted client with its contract. The
	 * persisted ActiveTask.partyAnchorId field stays (removing it would change
	 * the save format) and simply keeps its 0 default, exactly as before.
	 */
	private void acceptInternal(TaskOffer offer, String partyLabel,
		List<AttackStyle> partyStyles, boolean anteRequested,
		Long partyProposalId) {
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
			// partyAnchorId is not set: the builder leaves it 0, which is the value
			// every accept has written since the field existed (see above)
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
		if (staked[0] > 0) {
			fanfare(CeremonyBus.Fanfare.Size.MEDIUM, "The Ante is staked",
				staked[0] + " GC is held against this contract — finish it for "
					+ (long) staked[0] * Tuning.ANTE_PAYOUT_MULT + " GC back, die and it is gone.");
		}
		recentKillTicks.clear();
		resetCombo(); // each contract starts its own rhythm
		Listeners.fireHook(offerAcceptedHook, h -> h.accept(offer), "offer accepted hook failed");
	}

	/**
	 * Buy a Style Compactor/Extender for the CURRENT task. Requires an active
	 * task with no charge applied yet; one purchase locks both until the task
	 * ends and a new one is assigned. A free starter voucher, when held, is
	 * consumed INSTEAD of GC — never both. The whole purchase (guards, payment
	 * and charge application) is ONE atomic mutate, so a task completing
	 * concurrently can never burn the voucher/GC without applying the charge.
	 */
	public boolean purchaseCharge(boolean compactor) {
		String charge = compactor ? "COMPACTOR" : "EXTENDER";
		int price = compactor ? Tuning.COMPACTOR_PRICE_GC : Tuning.EXTENDER_PRICE_GC;
		final boolean[] applied = {false};
		stateService.mutate(s -> {
			if (s.getActiveTask() == null || s.getActiveTask().getAppliedCharge() != null)
				return s;
			GachaState next;
			if (compactor ? s.getFreeCompactors() > 0 : s.getFreeExtenders() > 0) {
				next = compactor
					? s.withFreeCompactors(s.getFreeCompactors() - 1)
					: s.withFreeExtenders(s.getFreeExtenders() - 1);
			}
			else if (s.getGc() >= price) {
				next = s.withGc(s.getGc() - price);
			}
			else {
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
	public void syncPartyKills(int othersTotal) {
		ActiveTask task = activeTask();
		if (task == null || !task.isParty() || othersTotal <= task.getPartyOtherKills())
			return;
		stateService.mutate(s -> s.getActiveTask() == null ? s
			: s.withActiveTask(s.getActiveTask().withPartyOtherKills(othersTotal)));
		completeSharedIfReached();
	}

	/** Complete the shared contract when the pooled quota is reached. */
	public void completeSharedIfReached() {
		ActiveTask task = activeTask();
		if (task != null && task.isParty()
			&& task.getKillsDone() + task.getPartyOtherKills() >= task.getKillsRequired()) {
			completeTask();
		}
	}

	/** A participant's client reported the shared contract complete (sync backstop). */
	public void forcePartyComplete() {
		ActiveTask task = activeTask();
		if (task == null || !task.isParty())
			return;
		// the mutate re-reads the contract off its OWN snapshot (s), not the one
		// guarded above, so a completion landing in between cannot be overwritten
		stateService.mutate(s -> {
			ActiveTask live = s.getActiveTask();
			return live == null ? s : s.withActiveTask(live.withPartyOtherKills(Math.max(
				live.getPartyOtherKills(), live.getKillsRequired() - live.getKillsDone())));
		});
		completeSharedIfReached();
	}

	// A contract is a contract: there is deliberately NO abandonTask(). Once a
	// task is accepted the only way out is completing it.

	// --- Rhythm Combo ---

	/** Whether a chain is still running at the given tick. */
	private boolean comboAlive(int nowTick) {
		return comboIdleAnchorTick >= 0
			&& nowTick - comboIdleAnchorTick <= Tuning.COMBO_IDLE_RESET_TICKS;
	}

	/** Stacks the chain is worth at the given tick (0 once the idle cutoff passes). */
	public int comboStacksAt(int nowTick) {
		return comboAlive(nowTick) ? Tuning.comboStacks(comboKills) : 0;
	}

	/**
	 * How far the chain has come toward its next stack, 0-1. Flat 0 on a dead
	 * or already-maxed chain, so the meter has nothing left to promise.
	 */
	public double comboProgressAt(int nowTick) {
		if (!comboAlive(nowTick) || comboKills >= Tuning.COMBO_MAX_KILLS)
			return 0;
		return (comboKills % Tuning.COMBO_KILLS_PER_STACK) / (double) Tuning.COMBO_KILLS_PER_STACK;
	}

	/** Ticks left before an alive chain cancels from idling (0 when no chain). */
	public int comboIdleTicksRemaining(int nowTick) {
		if (comboIdleAnchorTick < 0)
			return 0;
		return Math.max(0, Tuning.COMBO_IDLE_RESET_TICKS - (nowTick - comboIdleAnchorTick));
	}

	private void resetCombo() {
		comboKills = 0;
		comboIdleAnchorTick = -1;
	}

	/**
	 * Logout/profile-switch hygiene: transient combat state must never leak
	 * across characters or let a logout park a combo past its idle window.
	 */
	public void resetTransientCombat() {
		resetCombo();
		recentKillTicks.clear();
	}

	/** Advance the chain for an on-task compliant kill; returns the new stack count. */
	int advanceCombo(int killTick) {
		if (!comboAlive(killTick)) {
			comboKills = 1; // fresh chain (dead chains stay dead — attacks after
			// the idle cutoff cannot revive them, they start this new one)
		}
		else {
			// every kill on a live chain banks, however long it took to land;
			// held at the cap so a long chain cannot bank credit it can't spend
			comboKills = Math.min(Tuning.COMBO_MAX_KILLS, comboKills + 1);
		}
		comboIdleAnchorTick = killTick;
		return Tuning.comboStacks(comboKills);
	}

	// StyleTracker.AttackListener: any judged attack keeps a LIVE chain alive —
	// the idle countdown only runs while no attack commands are being issued
	@Override
	public void onAttack(AttackStyle style, int tick) {
		if (comboAlive(tick)) {
			// max, not assignment: this tick comes from StyleTracker's counter
			// while kills anchor with KillTracker's, and the meter reads back
			// with KillTracker's again. Those three agree today only because
			// both services increment on the same GameTick and are registered
			// together — a coincidence, not a guarantee. Taking the later of the
			// two means a swing can only ever EXTEND a chain, never cut it short,
			// so any future skew degrades to a slightly generous timer instead
			// of a chain that stops resetting while the player is still fighting.
			comboIdleAnchorTick = Math.max(comboIdleAnchorTick, tick);
		}
	}

	// ComplianceService.Listener: a forbidden-style attack breaks the rhythm
	@Override
	public void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc) {
		resetCombo();
	}


	// --- Kill handling ---

	@Override
	public void onKill(KillTracker.Kill kill) {
		GachaState state = stateService.get();
		if (state == null)
			return;
		lastKillTick = kill.getTick();
		ActiveTask task = state.getActiveTask();
		boolean onTask = task != null && task.getMonsterName().equalsIgnoreCase(kill.getNpcName());
		if (!onTask) {
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
		if (tainted) {
			// name the conviction: the pardon that retracts that verdict a few
			// ticks later must be able to reverse this exact point
			complianceService.addTaint(convictingTick);
			resetCombo(); // a forbidden-style kill has no rhythm
		}
		else {
			// rhythm advances on every compliant on-task kill, even when the
			// contract pays nothing per kill (redemption tasks have perKillGc 0)
			int stacks = advanceCombo(kill.getTick());
			// award BEFORE working off taint: with taint > 0 this kill's income
			// (and its side bets) must still be halved — the debt clears after
			if (task.getPerKillGc() > 0) {
				// The rhythm combo and the combat-level scaling ADD their
				// bonuses rather than compounding. Multiplied, a low-level
				// player punching up stacked 2.5 x 5.0 x 2.5 into a 31x kill and
				// the early game paid better per hour than the late game. Added,
				// and with KILL_DIFF_CAP now 1.75, the pair tops out at 3.25x and
				// the ladder stays legible: six stacks is "+150%", the level-gap
				// term is at most "+75%", together +225%.
				int playerCb = playerCombatLevel();
				double bonus = (Tuning.killCbMultiplier(playerCb, kill.getNpcCombatLevel()) - 1.0)
					+ (Tuning.comboMultiplier(stacks) - 1.0);
				// The Preferred Weapon multiplies the WHOLE award rather than joining
				// that additive term. Folded in additively a "1.5x" bonus would be
				// worth about +31% at the top of the ladder, and an interface that says
				// 1.5x while the player measures +31% is a lie; Tuning.WEAPON_BONUS_MULT
				// carries the rest of that argument. The real attainable ceiling on one
				// kill is therefore 3.25 x 1.5 = 4.875x, reached with six stacks and the
				// level-gap term sitting on its cap.
				double weaponMult = weaponMultFor(state, kill);
				// the assist penalty stays multiplicative: it is a halving of
				// whatever was earned, not a bonus competing with the others
				double mult = (1.0 + bonus) * weaponMult
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

		if (docketNow) {
			Listeners.fireHook(slayerLatchHook, Runnable::run, "slayer latch hook failed");
		}

		// The re-read stays INSIDE the loop deliberately: a listener is allowed to
		// end the contract, and the listeners queued behind it must then observe
		// that and stay silent. Hoisting the check above the loop would be a real
		// behaviour change, not a cleanup.
		//
		// What changed is that one iteration now makes ONE observation instead of
		// four. The old form re-read the volatile snapshot for each null test, for
		// isParty(), and a fourth time to build the argument — so the value that
		// reached the listener was never the value the three guards had approved.
		// Nothing pins the four reads to each other: a snapshot swap landing
		// between the last guard and the argument hands the listener a different
		// task, or a null, on a callback whose whole contract is "here is the live
		// shared contract". One local makes the thing tested and the thing passed
		// the same object by construction. It also takes the hottest path in the
		// plugin from four state reads per listener per credited kill down to one.
		for (Listener listener : listeners) {
			GachaState live = stateService.get();
			ActiveTask shared = live == null ? null : live.getActiveTask();
			if (shared != null && shared.isParty()) {
				listener.onPartyProgress(shared);
			}
		}

		if (finalKill) {
			completeTask();
		}
	}

	/**
	 * The Preferred Weapon factor for one kill: {@link Tuning#WEAPON_BONUS_MULT}
	 * when the wheel's named category was in the player's hands at the killing
	 * blow, 1.0 otherwise.
	 *
	 * <p><b>The category is not read here, and that is the whole feature.</b>
	 * onKill runs several ticks after the death — KillTracker holds every kill
	 * back for the loot oracle — so a varbit read at this moment would report
	 * what is equipped NOW, and swapping the named weapon in during that window
	 * would collect a bonus the fight never earned. StyleTracker stamps every
	 * judged attack with what it was made with, and only a stamp inside this
	 * kill's own engagement window counts; a kill with no judged attack in that
	 * window (a thrall's kill, damage dealt off-screen) pays nothing rather than
	 * inheriting the previous fight's weapon.
	 *
	 * <p>Called from inside the NON-TAINTED branch above, which is what makes
	 * "a tainted kill pays no weapon bonus" structural rather than a rule
	 * somebody has to remember: a tainted kill never reaches the award at all.
	 * WeaponBonusTest pins that so a later refactor of that branch cannot
	 * quietly change it.
	 *
	 * <p>Neither collaborator is null-guarded, unlike the Client reads elsewhere
	 * in this file. Both are constructor-injected, so Guice cannot leave either
	 * null in the live plugin — it would refuse to build the service at all — and
	 * a guard here would exist purely for headless harnesses, which the token
	 * budget does not have room to spend on. A test that reaches this line wires
	 * a real StyleTracker and a real WeaponTypeService; both are safe with a null
	 * Client (nothing is ever sampled, so nothing is ever satisfied) and cost a
	 * line each.
	 */
	private double weaponMultFor(GachaState state, KillTracker.Kill kill) {
		StyleTracker.WeaponSample sample = styleTracker.weaponAt(
			kill.getEngagementStartTick(), kill.getTick());
		return sample != null && weaponTypeService.satisfies(state.getPreferredWeaponType(),
			sample.getCategory(), sample.getComMode())
			? Tuning.WEAPON_BONUS_MULT : 1.0;
	}

	private void checkSideBets(KillTracker.Kill kill) {
		// one read, taken before the tick bookkeeping: nothing between here and
		// the old second read touched the state, so this is the same contract
		ActiveTask task = activeTask();
		if (task == null)
			return;
		recentKillTicks.add(kill.getTick());
		while (recentKillTicks.size() > 10) {
			recentKillTicks.poll();
		}
		List<SideBet> bets = task.getSideBets();
		if (bets == null || bets.isEmpty())
			return;
		List<SideBet> updated = new ArrayList<>(bets.size());
		boolean changed = false;
		for (SideBet bet : bets) {
			if (bet.isCompleted()) {
				updated.add(bet);
				continue;
			}
			boolean hit = false;
			switch (bet.getKind()) {
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
			if (hit) {
				SideBet done = bet.withCompleted(true);
				updated.add(done);
				changed = true;
				creditSink.award(bet.getPayoutGc(), new CreditSink.GcContext(
					CreditSink.Source.SIDE_BET, kill.getNpcName(), tagsFor(kill.getNpcName())));
				for (Listener listener : listeners) {
					listener.onSideBetHit(done, kill.getNpcName());
				}
				fanfare(CeremonyBus.Fanfare.Size.SMALL,
					bet.isSealed() ? "Sealed bet revealed!" : "Side bet hit!",
					describeSideBet(done) + " +" + bet.getPayoutGc() + " GC");
			}
			else {
				updated.add(bet);
			}
		}
		if (changed) {
			stateService.mutate(s -> s.getActiveTask() == null ? s
				: s.withActiveTask(s.getActiveTask().withSideBets(updated)));
		}
	}

	/**
	 * Bestiary: the first on-task compliant kill of a new species stamps the
	 * codex and pays a discovery bonus; crossing a codex milestone pays more.
	 */
	private void recordDiscovery(String monsterName) {
		GachaState state = stateService.get();
		if (state == null || state.getSpeciesDiscovered() == null)
			return;
		// Locale.ROOT: persisted keys must not vary with the JVM locale
		String key = monsterName.toLowerCase(Locale.ROOT);
		if (state.getSpeciesDiscovered().contains(key))
			return;
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
		if (count <= 10) {
			fanfare(CeremonyBus.Fanfare.Size.SMALL, "New species: " + monsterName,
				"Codex entry " + count + " — +" + Tuning.DISCOVERY_GC + " GC");
		}
		for (int i = 0; i < Tuning.BESTIARY_MILESTONES.length; i++) {
			if (count == Tuning.BESTIARY_MILESTONES[i]) {
				int bonus = Tuning.BESTIARY_MILESTONE_GC[i];
				creditSink.award(bonus, new CreditSink.GcContext(
					CreditSink.Source.DISCOVERY, null, null));
				fanfare(CeremonyBus.Fanfare.Size.MEDIUM, count + " species discovered!",
					"The codex swells — +" + bonus + " GC");
			}
		}
	}

	private boolean killTrackerLowHp() {
		int boosted = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int real = lvl(Skill.HITPOINTS);
		return real > 0 && boosted * 4 <= real;
	}

	private int countKillsWithin(int windowTicks, int nowTick) {
		int count = 0;
		for (int t : recentKillTicks) {
			if (nowTick - t <= windowTicks) {
				count++;
			}
		}
		return count;
	}

	public static String describeSideBet(SideBet bet) {
		switch (bet.getKind()) {
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
	static int distinctStyles(List<AttackStyle> styles) {
		if (styles == null || styles.isEmpty())
			return 0;
		EnumSet<AttackStyle> seen = EnumSet.noneOf(AttackStyle.class);
		for (AttackStyle style : styles) {
			if (style != null) {
				seen.add(style);
			}
		}
		return seen.size();
	}

	private void completeTask() {
		GachaState state = stateService.get();
		if (state == null || state.getActiveTask() == null)
			return;
		ActiveTask task = state.getActiveTask();
		// one clock read shared by the personal best and by the Dossier record, so
		// the filed timestamp and the filed duration cannot disagree
		long completedAtMs = System.currentTimeMillis();
		long duration = completedAtMs - task.getAcceptedAtMs();

		double completionMult = 1.0;
		if (task.isParty()) {
			// Shared contract: the co-op bonus applies party-wide, plus a FLAT
			// clash bonus if the party covers 2+ distinct styles. Flat, not per
			// extra style — a trio running all three styles pays exactly what a
			// pair running two pays. Computed over the accept-time snapshot
			// (self included), so every client pays the same and a mid-contract
			// style re-roll cannot reprice a signed contract.
			completionMult = Tuning.PARTY_REWARD_MULT;
			if (distinctStyles(task.getPartyStyles()) > 1) {
				completionMult += Tuning.PARTY_STYLE_CLASH_BONUS;
			}
		}
		else if (task.isPartyConvertedToSolo()) {
			completionMult = Tuning.PARTY_CARRY_MULT;
		}

		// Double Docket stacks MULTIPLICATIVELY on whatever the party chain came
		// to, so it is worth the same proportion of a shared contract as of a
		// solo one. The taint halving still lands after all of this, inside the
		// sink, exactly as it does for every other completion modifier.
		if (task.isSlayerAligned()) {
			completionMult *= Tuning.DOUBLE_DOCKET_MULT;
		}

		// Milestone contracts (every 5th/10th/50th/100th/250th) multiply the
		// completion reward, Slayer-point style. Stacks multiplicatively with
		// the party and Double Docket chain above for the same reason the
		// docket does: a milestone should be worth the same PROPORTION of a
		// shared or aligned contract as of a plain one.
		//
		// This completion's lifetime contract number — and, being one past the old
		// total, also the new total the mutate below files. Deed milestones, the
		// fragment window and the milestone multiple are all read off this single
		// value so they cannot disagree about which contract this was.
		int taskNumber = state.getTotalTasksCompleted() + 1;
		double milestoneMult = Tuning.completionMilestoneMult(taskNumber);
		completionMult *= milestoneMult;

		// Redemption clears taint BEFORE the award so its own completion
		// reward is not halved by the debt it just paid off.
		boolean redemptionCleared = false;
		if (task.isRedemption()) {
			complianceService.clearAllTaint();
			redemptionCleared = true;
		}

		long completionAwarded = creditSink.award(Math.round(task.getCompletionGc() * completionMult),
			new CreditSink.GcContext(CreditSink.Source.TASK_COMPLETION, task.getMonsterName(),
				tagsFor(task.getMonsterName())));

		int sideBetsHit = 0;
		long sideBetGc = 0;
		if (task.getSideBets() != null) {
			for (SideBet bet : task.getSideBets()) {
				if (bet.isCompleted()) {
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
		if (pb == null) {
			pb = new PersonalBest(0, null, 0, null);
		}
		PersonalBest updatedPb = pb;
		if (pb.getFastestTaskMs() == 0 || duration < pb.getFastestTaskMs()) {
			updatedPb = updatedPb.withFastestTaskMs(duration).withFastestMonster(task.getMonsterName());
			newFastest = pb.getFastestTaskMs() != 0;
		}
		if (haul > pb.getBiggestHaulGc()) {
			updatedPb = updatedPb.withBiggestHaulGc((int) haul).withBiggestHaulMonster(task.getMonsterName());
			newHaul = pb.getBiggestHaulGc() != 0;
		}
		if (newFastest || newHaul) {
			creditSink.award(Tuning.PB_RECORD_GC, new CreditSink.GcContext(
				CreditSink.Source.RECORD, null, null));
		}

		final PersonalBest pbFinal = updatedPb;
		final String monsterName = task.getMonsterName();

		// deed milestone?
		int milestone = 0;
		int claimed = state.getDeedMilestonesClaimed();
		if (claimed < Tuning.DEED_TASK_MILESTONES.length
			&& taskNumber >= Tuning.DEED_TASK_MILESTONES[claimed]
			&& state.getDeededSlots().size() < GearSlot.values().length) {
			milestone = Tuning.DEED_TASK_MILESTONES[claimed];
		}
		final int milestoneFinal = milestone;

		// Deed Fragments: harder contracts during the first ten tasks pay
		// fragments; ten forge the one-per-account bonus deed
		int fragmentsEarned = 0;
		if (!state.isFragmentDeedForged() && taskNumber <= Tuning.FRAGMENT_WINDOW_TASKS) {
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
			if (s.getActiveTask() != null) {
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
				.withTotalTasksCompleted(taskNumber);
			if (anteStake[0] > 0) {
				// The principal comes back RAW, in the same mutate that retires
				// the contract, because it was never income — it is the player's
				// own GC coming out of escrow. Through the sink it would be
				// halved by taint and would inflate lifetime earnings by money
				// the player merely got back. Only the PROFIT is awarded, below.
				next = next.withGc(next.getGc() + anteStake[0]);
			}
			if (milestoneFinal > 0) {
				next = next.withDeedMilestonesClaimed(s.getDeedMilestonesClaimed() + 1)
					.withPendingDeeds(s.getPendingDeeds() + 1);
			}
			if (fragmentsEarnedFinal > 0 && !s.isFragmentDeedForged()) {
				int frags = s.getDeedFragments() + fragmentsEarnedFinal;
				if (frags >= Tuning.FRAGMENTS_REQUIRED) {
					next = next.withDeedFragments(Tuning.FRAGMENTS_REQUIRED)
						.withFragmentDeedForged(true)
						.withPendingDeeds(next.getPendingDeeds() + 1);
				}
				else {
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

		if (anteStake[0] > 0) {
			// Only the winnings are income. SIDE_BET is the honest source: the
			// Ante is the same kind of thing as the contract's own side bets,
			// and taint halving the PROFIT (never the principal) is exactly the
			// treatment every other bet on this contract gets.
			long profit = creditSink.award((long) anteStake[0] * (Tuning.ANTE_PAYOUT_MULT - 1),
				new CreditSink.GcContext(CreditSink.Source.SIDE_BET, task.getMonsterName(),
					tagsFor(task.getMonsterName())));
			fanfare(CeremonyBus.Fanfare.Size.MEDIUM, "The Ante pays",
				anteStake[0] + " GC staked returns with " + profit + " GC won.");
		}

		boolean forgedNow = fragmentsEarned > 0 && !state.isFragmentDeedForged()
			&& afterMutate != null && afterMutate.isFragmentDeedForged();
		int fragmentsTotal = afterMutate == null
			? state.getDeedFragments() : afterMutate.getDeedFragments();

		boolean cycleTriggered = styleService.advanceCycle(chargeApplied[0]);

		TaskCompletionSummary summary = new TaskCompletionSummary(task, completionAwarded,
			sideBetsHit, duration, newFastest, newHaul, redemptionCleared, cycleTriggered, milestone,
			fragmentsEarned, fragmentsTotal, forgedNow, taskNumber, milestoneMult);
		ceremonyBus.submit(CeremonyBus.Type.TASK_COMPLETE, summary);
		for (Listener listener : listeners) {
			listener.onTaskCompleted(summary);
		}
		if (milestone > 0) {
			ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, milestone);
		}
		if (forgedNow) {
			fanfare(CeremonyBus.Fanfare.Size.LARGE, "Deed forged from fragments!",
				Tuning.FRAGMENTS_REQUIRED + " fragments fuse into a bonus Slot Deed — choose a slot.");
			ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
		}
		if (cycleTriggered) {
			// NOT styleService.roll(): a roll coming due is the only moment the
			// Consignment may be offered, so this is the one call site allowed to
			// put it up. offerOrRoll always leaves the roll either taken or owed,
			// which is why nothing is read back — and with no presenter wired it
			// takes the ordinary wheel, exactly as this line did before.
			consignmentService.offerOrRoll(lastKillTick);
		}
		resetCombo(); // rhythm does not carry across contracts
		// A completion banks the reward, the deed, the fragments and the style
		// roll in one moment. Losing that to a crash in the debounce window is
		// the single worst thing this save can drop, and completions are rare
		// enough that an immediate write costs nothing.
		stateService.checkpoint();
	}

	// --- Party carry clause ---

	/**
	 * The Ante deliberately survives this. A stake is personal and the contract
	 * is binding, so a partner leaving does not release this player's wager: the
	 * carry clause already prices the extra difficulty (PARTY_CARRY_MULT), and
	 * refunding here would make "partner logs out" the cheap way out of a losing
	 * bet. Finish it alone and it still pays double.
	 */
	public void convertPartyToSolo() {
		stateService.mutate(s -> s.getActiveTask() == null || !s.getActiveTask().isParty() ? s
			: s.withActiveTask(s.getActiveTask().withPartyConvertedToSolo(true)));
	}

	// --- Helpers ---

	/**
	 * Raise a banner. Every celebration this service submits is the same shape —
	 * a size, a title, one line of detail and no item icon — so the constructor
	 * ceremony is written once here instead of at seven call sites. It still goes
	 * through submit(), so the taps fire and the SMALL-banner coalescing inside
	 * enqueue() behaves exactly as it did.
	 */
	private void fanfare(CeremonyBus.Fanfare.Size size, String title, String detail) {
		ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(size, title, detail, null));
	}

	/**
	 * Base (unboosted) level shorthand. Nine sites read levels off the client,
	 * seven of them inside one combat-level computation; the delegation is exact,
	 * so the arguments still reach Experience.getCombatLevel in the same order.
	 */
	private int lvl(Skill skill) {
		return client.getRealSkillLevel(skill);
	}

	private void journalKill(String monsterName, long gcAwarded) {
		stateService.mutate(s -> {
			Map<String, MonsterStats> stats = new HashMap<>(s.getMonsterStats());
			MonsterStats ms = stats.getOrDefault(monsterName, new MonsterStats(0, 0, 0));
			stats.put(monsterName, ms.withKills(ms.getKills() + 1).withGcEarned(ms.getGcEarned() + gcAwarded));
			return s.withMonsterStats(stats);
		});
	}

	private void fireKillFeedback(KillFeedback feedback) {
		Listeners.fire(listeners, l -> l.onKillFeedback(feedback), "kill feedback listener failed");
	}

	@Nullable
	private List<String> tagsFor(String monsterName) {
		if (tagsByMonster == null) {
			tagsByMonster = new HashMap<>();
			for (MonsterTable.Monster monster : monsterTable.getMonsters()) {
				tagsByMonster.put(monster.getName().toLowerCase(), monster.getTags());
			}
		}
		return tagsByMonster.get(monsterName.toLowerCase());
	}

	/** Any ironman variant (varbit 1777 nonzero: IM/UIM/HCIM/GIM...). */
	private boolean isIronman() {
		return client != null
			&& client.getVarbitValue(VarbitID.IRONMAN) > 0;
	}

	public int playerCombatLevel() {
		return Experience.getCombatLevel(
			lvl(Skill.ATTACK), lvl(Skill.STRENGTH), lvl(Skill.DEFENCE), lvl(Skill.HITPOINTS),
			lvl(Skill.MAGIC), lvl(Skill.RANGED), lvl(Skill.PRAYER));
	}
}
