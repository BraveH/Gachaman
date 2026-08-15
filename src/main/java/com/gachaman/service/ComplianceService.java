package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import java.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.client.util.*;

/**
 * Style-rule enforcement. On a re-roll the player is only WARNED; a violation
 * begins when an attack is actually made with a forbidden style. Each
 * forbidden attack costs GC; kills finished while violating pay zero and add
 * taint (halving all income until worked off).
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ComplianceService implements StyleTracker.AttackListener {
	private static final int VIOLATING_DISPLAY_TICKS = 8;

	public interface Listener {
		void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc);

		default void onTaintAdded(int newTaint) {
		}

		default void onTaintCleared(int cleared, int remaining) {
		}

		/**
		 * A forbidden-attack verdict was retracted (the delayed Magic XP
		 * proved the attack was actually a compliant cast); its penalty has
		 * been refunded.
		 */
		default void onForbiddenPardoned(int tick, long refundedGc) {
		}

		/**
		 * A pardon reversed the taint its own conviction had caused. NOT a
		 * cleanse: nothing was worked off, so this must never be confused with
		 * {@link #onTaintCleared} (which pays the first-cleanse bounty).
		 */
		default void onTaintRolledBack(int lifted, int remaining) {
		}
	}

	/** One convicted forbidden attack: when, and what it cost (for pardon refunds). */
	@RequiredArgsConstructor
	private static final class ForbiddenAttack {
		final int tick;
		final long deductedGc;
		/**
		 * Taint this conviction is still answerable for. A kill tainted by this
		 * attack adds a point here, so a later pardon can reverse exactly its
		 * own taint and never someone else's.
		 */
		int taintPoints;
	}

	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final Client client;
	private final QuestExemptionService questExemptionService;

	@Getter
	private int lastForbiddenAttackTick = -1;
	@Getter
	private int lastCompliantAttackTick = -1;
	/**
	 * Recent forbidden attacks (kills are judged a few ticks after death, and
	 * pardons arrive a few ticks after the attack).
	 */
	private final ArrayDeque<ForbiddenAttack> recentForbiddenAttacks = new ArrayDeque<>();
	/** Tick of the most recent style re-roll (drives the "switch gear" warning chip). */
	@Getter
	private int styleChangedTick = -1;

	/**
	 * The six fan-outs below used to iterate this list directly, without the
	 * defensive copy every other service in the package takes; routing them
	 * through {@link Listeners#fire} gives them one.
	 *
	 * <p>That widens what is legal rather than changing what happens. Registering
	 * or dropping a listener from inside a compliance callback used to throw
	 * ConcurrentModificationException straight out of onAttack; now it does not.
	 * Nothing did it: the only callers of addListener/removeListener in the whole
	 * of src/main are GachamanPlugin's startUp and shutDown, which is a fact
	 * about call sites and so cannot be invalidated by a change inside any
	 * listener.
	 */
	private final List<Listener> listeners = new ArrayList<>();
	private int currentTick;

	public void addListener(Listener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	public void noteStyleChanged(int tick) {
		styleChangedTick = tick;
	}

	/** True while the "in violation" chip should show. */
	public boolean isViolating(int tick) {
		return lastForbiddenAttackTick >= 0 && tick - lastForbiddenAttackTick <= VIOLATING_DISPLAY_TICKS
			&& lastCompliantAttackTick < lastForbiddenAttackTick;
	}

	/**
	 * Was a forbidden attack made inside [fromTick, toTick]? Kill processing
	 * is deferred past death for the loot oracle, so the judgement must be
	 * bounded by the DEATH tick — an attack on the next target during the
	 * settle window must never taint the finished kill.
	 */
	public boolean forbiddenAttackBetween(int fromTick, int toTick) {
		return convictingAttackTick(fromTick, toTick) >= 0;
	}

	/**
	 * Which forbidden attack convicts a kill spanning [fromTick, toTick], or -1
	 * for none. Same window as {@link #forbiddenAttackBetween}, but it keeps the
	 * convicting attack's identity so the taint it causes can be attributed to
	 * it and reversed if that verdict is later pardoned. Returns the OLDEST
	 * match: with two convictions in one window the taint stays pinned to the
	 * first, so pardoning only the second correctly lifts nothing.
	 */
	public int convictingAttackTick(int fromTick, int toTick) {
		for (ForbiddenAttack attack : recentForbiddenAttacks) {
			if (attack.tick >= fromTick && attack.tick <= toTick)
				return attack.tick;
		}
		return -1;
	}

	@Override
	public void onAttack(AttackStyle style, int tick) {
		currentTick = tick;
		GachaState state = stateService.get();
		if (state == null || state.getAllowedStyle() == null
			|| (client != null && TutorialGate.onTutorial(client))) {
			return;
		}
		AttackStyle allowed = AttackStyle.valueOf(state.getAllowedStyle());
		if (style == allowed) {
			lastCompliantAttackTick = tick;
			return;
		}
		// quest fights are style-exempt: some quest bosses require a specific
		// style and a lock would soft-lock the quest
		if (client != null) {
			Actor target = client.getLocalPlayer() == null
				? null : client.getLocalPlayer().getInteracting();
			if (target instanceof NPC && target.getName() != null
				&& questExemptionService.isQuestTarget(
					Text.removeTags(target.getName()))) {
				lastCompliantAttackTick = tick;
				return;
			}
		}
		lastForbiddenAttackTick = tick;
		long penalty = penaltyFor(state.getActiveTask());
		long deducted = creditSink.deduct(penalty);
		recentForbiddenAttacks.addLast(new ForbiddenAttack(tick, deducted));
		while (recentForbiddenAttacks.size() > 32) {
			recentForbiddenAttacks.removeFirst();
		}
		Listeners.fire(listeners, l -> l.onForbiddenAttack(style, allowed, deducted),
			"compliance listener failed");
	}

	/**
	 * The StyleTracker retracted a stance verdict: the delayed Magic XP proved
	 * the "forbidden melee/ranged attack" at judgedTick was actually a spell
	 * cast. Undo everything the conviction did — remove it from the taint
	 * window, refund the penalty, reverse any taint it already caused, and count
	 * the attack as compliant. The kill that taint came from stays unpaid: its
	 * combo, side bets and KC were already scored and cannot be replayed.
	 */
	@Override
	public void onAttackPardoned(int judgedTick) {
		ForbiddenAttack pardoned = null;
		for (ForbiddenAttack attack : recentForbiddenAttacks) {
			if (attack.tick == judgedTick) {
				pardoned = attack;
				break;
			}
		}
		if (pardoned == null) {
			return; // that verdict was compliant anyway — nothing to undo
		}
		recentForbiddenAttacks.remove(pardoned);
		long refunded = creditSink.refund(pardoned.deductedGc);
		// the verdict was wrong, so the taint it caused was wrong too — but only
		// reverse taint that is still STANDING. A work-off, a cleanse or a
		// profile switch may already have burned it, and lifting more than that
		// would hand back taint the player genuinely earned elsewhere.
		GachaState beforeRollback = stateService.get();
		int standingTaint = beforeRollback == null ? 0 : beforeRollback.getTaint();
		int lifted = Math.min(pardoned.taintPoints, standingTaint);
		GachaState afterRollback = lifted > 0
			? stateService.mutate(s -> s.withTaint(s.getTaint() - lifted))
			: null;
		int latestRemaining = -1;
		for (ForbiddenAttack attack : recentForbiddenAttacks) {
			latestRemaining = Math.max(latestRemaining, attack.tick);
		}
		lastForbiddenAttackTick = latestRemaining;
		lastCompliantAttackTick = Math.max(lastCompliantAttackTick, judgedTick);
		Listeners.fire(listeners, l -> l.onForbiddenPardoned(judgedTick, refunded),
			"compliance listener failed");
		// value-based, not null-based: @With returns the same instance when the
		// value is unchanged and mutate hands that straight back, so a non-null
		// state is no proof that any taint actually came off
		if (afterRollback != null && afterRollback.getTaint() < standingTaint) {
			// a second fan-out takes a second copy, so a listener registered by the
			// pardon notice above is seen here exactly as it was when hand-written
			Listeners.fire(listeners, l -> l.onTaintRolledBack(lifted, afterRollback.getTaint()),
				"compliance listener failed");
		}
	}

	static long penaltyFor(ActiveTask task) {
		if (task == null)
			return Tuning.VIOLATION_ATTACK_PENALTY_NO_TASK;
		return Math.max(Tuning.VIOLATION_ATTACK_PENALTY_FLOOR,
			(long) task.getPerKillGc() * Tuning.VIOLATION_ATTACK_PENALTY_MULT);
	}

	/**
	 * Called by the task engine when a kill was tainted, naming the conviction
	 * that tainted it (see {@link #convictingAttackTick}) so a pardon of that
	 * attack can reverse this point. An unknown tick still taints — it is just
	 * unattributable, and therefore permanent until worked off.
	 */
	public void addTaint(int convictionTick) {
		var state = stateService.mutate(s -> s.withTaint(s.getTaint() + 1));
		if (state != null) {
			for (ForbiddenAttack attack : recentForbiddenAttacks) {
				if (attack.tick == convictionTick) {
					attack.taintPoints++;
					break;
				}
			}
			Listeners.fire(listeners, l -> l.onTaintAdded(state.getTaint()),
				"compliance listener failed");
		}
	}

	/** Called on each compliant credited kill; works one taint off. */
	public void workOffTaint() {
		GachaState state = stateService.get();
		if (state == null || state.getTaint() <= 0)
			return;
		var next = stateService.mutate(s -> s.withTaint(Math.max(0, s.getTaint() - 1)));
		if (next != null) {
			// taint points are fungible against the global counter, so retire the
			// OLDEST attributed point first — that keeps the ledger's total from
			// running AHEAD of the counter, which is what stops a pardon from
			// lifting taint that was already worked off. It cannot keep the two
			// EQUAL: this deque is bounded, so an evicted attack takes its
			// unretired points with it while the counter keeps them. The ledger
			// is therefore only ever <= the counter, and the pardon path still
			// needs its own Math.min against the standing taint.
			for (ForbiddenAttack attack : recentForbiddenAttacks) {
				if (attack.taintPoints > 0) {
					attack.taintPoints--;
					break;
				}
			}
			Listeners.fire(listeners, l -> l.onTaintCleared(1, next.getTaint()),
				"compliance listener failed");
		}
	}

	/** Redemption task completion clears everything. */
	public void clearAllTaint() {
		GachaState state = stateService.get();
		if (state == null || state.getTaint() <= 0)
			return;
		int cleared = state.getTaint();
		stateService.mutate(s -> s.withTaint(0));
		// the counter is empty, so no conviction is answerable for anything any
		// more — a later pardon must not "refund" taint redemption already wiped
		for (ForbiddenAttack attack : recentForbiddenAttacks) {
			attack.taintPoints = 0;
		}
		Listeners.fire(listeners, l -> l.onTaintCleared(cleared, 0),
			"compliance listener failed");
	}

	/**
	 * Logout/profile-switch hygiene: the conviction ledger is in-memory while
	 * the taint it accounts for is persisted per-profile. A conviction that
	 * outlived its own save would let a pardon reverse the NEXT character's
	 * taint, so the convictions die with the profile.
	 */
	public void resetTransient() {
		recentForbiddenAttacks.clear();
		lastForbiddenAttackTick = -1;
		lastCompliantAttackTick = -1;
		// the "switch your gear" chip is about a re-roll the PREVIOUS profile
		// saw; left set, it warns the next character about nothing
		styleChangedTick = -1;
	}
}
