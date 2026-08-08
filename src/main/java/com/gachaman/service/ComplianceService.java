package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Style-rule enforcement. On a re-roll the player is only WARNED; a violation
 * begins when an attack is actually made with a forbidden style. Each
 * forbidden attack costs GC; kills finished while violating pay zero and add
 * taint (halving all income until worked off).
 */
@Slf4j
@Singleton
public class ComplianceService implements StyleTracker.AttackListener
{
	private static final int VIOLATING_DISPLAY_TICKS = 8;

	public interface Listener
	{
		void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc);

		void onTaintAdded(int newTaint);

		void onTaintCleared(int cleared, int remaining);

		/**
		 * A forbidden-attack verdict was retracted (the delayed Magic XP
		 * proved the attack was actually a compliant cast); its penalty has
		 * been refunded.
		 */
		default void onForbiddenPardoned(int tick, long refundedGc)
		{
		}
	}

	/** One convicted forbidden attack: when, and what it cost (for pardon refunds). */
	private static final class ForbiddenAttack
	{
		final int tick;
		final long deductedGc;

		ForbiddenAttack(int tick, long deductedGc)
		{
			this.tick = tick;
			this.deductedGc = deductedGc;
		}
	}

	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final net.runelite.api.Client client;
	private final QuestExemptionService questExemptionService;

	@Getter
	private int lastForbiddenAttackTick = -1;
	@Getter
	private int lastCompliantAttackTick = -1;
	/**
	 * Recent forbidden attacks (kills are judged a few ticks after death, and
	 * pardons arrive a few ticks after the attack).
	 */
	private final java.util.ArrayDeque<ForbiddenAttack> recentForbiddenAttacks = new java.util.ArrayDeque<>();
	/** Tick of the most recent style re-roll (drives the "switch gear" warning chip). */
	@Getter
	private int styleChangedTick = -1;

	private java.util.List<Listener> listeners = new java.util.ArrayList<>();
	private int currentTick;

	@Inject
	public ComplianceService(GachaStateService stateService, CreditSink creditSink,
		net.runelite.api.Client client, QuestExemptionService questExemptionService)
	{
		this.stateService = stateService;
		this.creditSink = creditSink;
		this.client = client;
		this.questExemptionService = questExemptionService;
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

	public void noteStyleChanged(int tick)
	{
		styleChangedTick = tick;
	}

	/** True while the "in violation" chip should show. */
	public boolean isViolating(int tick)
	{
		return lastForbiddenAttackTick >= 0 && tick - lastForbiddenAttackTick <= VIOLATING_DISPLAY_TICKS
			&& lastCompliantAttackTick < lastForbiddenAttackTick;
	}

	/**
	 * Was a forbidden attack made inside [fromTick, toTick]? Kill processing
	 * is deferred past death for the loot oracle, so the judgement must be
	 * bounded by the DEATH tick — an attack on the next target during the
	 * settle window must never taint the finished kill.
	 */
	public boolean forbiddenAttackBetween(int fromTick, int toTick)
	{
		for (ForbiddenAttack attack : recentForbiddenAttacks)
		{
			if (attack.tick >= fromTick && attack.tick <= toTick)
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public void onAttack(AttackStyle style, int tick)
	{
		currentTick = tick;
		GachaState state = stateService.get();
		if (state == null || state.getAllowedStyle() == null
			|| (client != null && TutorialGate.onTutorial(client)))
		{
			return;
		}
		AttackStyle allowed = AttackStyle.valueOf(state.getAllowedStyle());
		if (style == allowed)
		{
			lastCompliantAttackTick = tick;
			return;
		}
		// quest fights are style-exempt: some quest bosses require a specific
		// style and a lock would soft-lock the quest
		if (client != null)
		{
			net.runelite.api.Actor target = client.getLocalPlayer() == null
				? null : client.getLocalPlayer().getInteracting();
			if (target instanceof net.runelite.api.NPC && target.getName() != null
				&& questExemptionService.isQuestTarget(
					net.runelite.client.util.Text.removeTags(target.getName())))
			{
				lastCompliantAttackTick = tick;
				return;
			}
		}
		lastForbiddenAttackTick = tick;
		long penalty = penaltyFor(state.getActiveTask());
		long deducted = creditSink.deduct(penalty);
		recentForbiddenAttacks.addLast(new ForbiddenAttack(tick, deducted));
		while (recentForbiddenAttacks.size() > 32)
		{
			recentForbiddenAttacks.removeFirst();
		}
		for (Listener listener : listeners)
		{
			try
			{
				listener.onForbiddenAttack(style, allowed, deducted);
			}
			catch (Exception e)
			{
				log.warn("compliance listener failed", e);
			}
		}
	}

	/**
	 * The StyleTracker retracted a stance verdict: the delayed Magic XP proved
	 * the "forbidden melee/ranged attack" at judgedTick was actually a spell
	 * cast. Undo everything the conviction did — remove it from the taint
	 * window, refund the penalty, and count the attack as compliant.
	 */
	@Override
	public void onAttackPardoned(int judgedTick)
	{
		ForbiddenAttack pardoned = null;
		for (ForbiddenAttack attack : recentForbiddenAttacks)
		{
			if (attack.tick == judgedTick)
			{
				pardoned = attack;
				break;
			}
		}
		if (pardoned == null)
		{
			return; // that verdict was compliant anyway — nothing to undo
		}
		recentForbiddenAttacks.remove(pardoned);
		long refunded = creditSink.refund(pardoned.deductedGc);
		int latestRemaining = -1;
		for (ForbiddenAttack attack : recentForbiddenAttacks)
		{
			latestRemaining = Math.max(latestRemaining, attack.tick);
		}
		lastForbiddenAttackTick = latestRemaining;
		lastCompliantAttackTick = Math.max(lastCompliantAttackTick, judgedTick);
		for (Listener listener : listeners)
		{
			try
			{
				listener.onForbiddenPardoned(judgedTick, refunded);
			}
			catch (Exception e)
			{
				log.warn("compliance listener failed", e);
			}
		}
	}

	static long penaltyFor(ActiveTask task)
	{
		if (task == null)
		{
			return Tuning.VIOLATION_ATTACK_PENALTY_NO_TASK;
		}
		return Math.max(Tuning.VIOLATION_ATTACK_PENALTY_FLOOR,
			(long) task.getPerKillGc() * Tuning.VIOLATION_ATTACK_PENALTY_MULT);
	}

	/** Called by the task engine when a kill was tainted. */
	public void addTaint()
	{
		var state = stateService.mutate(s -> s.withTaint(s.getTaint() + 1));
		if (state != null)
		{
			for (Listener listener : listeners)
			{
				try
				{
					listener.onTaintAdded(state.getTaint());
				}
				catch (Exception e)
				{
					log.warn("compliance listener failed", e);
				}
			}
		}
	}

	/** Called on each compliant credited kill; works one taint off. */
	public void workOffTaint()
	{
		GachaState state = stateService.get();
		if (state == null || state.getTaint() <= 0)
		{
			return;
		}
		var next = stateService.mutate(s -> s.withTaint(Math.max(0, s.getTaint() - 1)));
		if (next != null)
		{
			for (Listener listener : listeners)
			{
				try
				{
					listener.onTaintCleared(1, next.getTaint());
				}
				catch (Exception e)
				{
					log.warn("compliance listener failed", e);
				}
			}
		}
	}

	/** Redemption task completion clears everything. */
	public void clearAllTaint()
	{
		GachaState state = stateService.get();
		if (state == null || state.getTaint() <= 0)
		{
			return;
		}
		int cleared = state.getTaint();
		stateService.mutate(s -> s.withTaint(0));
		for (Listener listener : listeners)
		{
			try
			{
				listener.onTaintCleared(cleared, 0);
			}
			catch (Exception e)
			{
				log.warn("compliance listener failed", e);
			}
		}
	}
}
