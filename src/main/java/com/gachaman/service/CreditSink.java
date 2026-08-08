package com.gachaman.service;

import com.gachaman.Tuning;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * The single funnel every GC award flows through. Modifiers (set perks, party
 * bonus, prestige, taint) register once and compose multiplicatively.
 */
@Slf4j
@Singleton
public class CreditSink
{
	public enum Source
	{
		KILL, TASK_COMPLETION, SIDE_BET, DUPLICATE, DEED_SATURATED, RECORD,
		DISCOVERY, GRADUATION, FIRST, OTHER
	}

	@Value
	public static class GcContext
	{
		Source source;
		String monsterName;   // null when not kill-scoped
		List<String> monsterTags; // null when unknown
	}

	public interface Modifier
	{
		/** Multiplicative factor for this award; return 1.0 when not applicable. */
		double factor(GcContext context);
	}

	public interface Listener
	{
		void onGcAwarded(long amount, GcContext context, long newBalance);
	}

	private final GachaStateService stateService;
	private final List<Modifier> modifiers = new ArrayList<>();
	private final List<Listener> listeners = new ArrayList<>();

	@Inject
	public CreditSink(GachaStateService stateService)
	{
		this.stateService = stateService;
	}

	public synchronized void registerModifier(Modifier modifier)
	{
		if (!modifiers.contains(modifier))
		{
			modifiers.add(modifier);
		}
	}

	public synchronized void unregisterModifier(Modifier modifier)
	{
		modifiers.remove(modifier);
	}

	public synchronized void addListener(Listener listener)
	{
		listeners.add(listener);
	}

	public synchronized void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	@Value
	public static class AwardResult
	{
		long amount;
		/** Post-mutate snapshot; null when state was not loaded. */
		com.gachaman.model.GachaState state;
	}

	/**
	 * Atomic variant: applies extraMutation AND the GC award in ONE state
	 * mutation, so an unload/profile-switch can never separate a one-shot
	 * marker (e.g. a Firsts stamp) from its bounty. The award lands only when
	 * extraMutation actually changed the state.
	 */
	public synchronized AwardResult awardWith(long baseAmount, GcContext context,
		java.util.function.UnaryOperator<com.gachaman.model.GachaState> extraMutation)
	{
		if (stateService.get() == null)
		{
			return new AwardResult(0, null);
		}
		double factor = 1.0;
		for (Modifier modifier : modifiers)
		{
			try
			{
				factor *= modifier.factor(context);
			}
			catch (Exception e)
			{
				log.warn("GC modifier failed", e);
			}
		}
		if (stateService.get().getTaint() > 0)
		{
			factor *= Tuning.TAINT_INCOME_MULT;
		}
		long amount = baseAmount <= 0 ? 0 : Math.max(0, Math.round(baseAmount * factor));
		final boolean[] applied = {false};
		var next = stateService.mutate(s -> {
			com.gachaman.model.GachaState mutated = extraMutation.apply(s);
			if (mutated == null || mutated == s)
			{
				return s; // marker did not land — no award either
			}
			applied[0] = true;
			if (amount > 0)
			{
				mutated = mutated.withGc(mutated.getGc() + amount)
					.withLifetimeGcEarned(mutated.getLifetimeGcEarned() + amount);
			}
			return mutated;
		});
		if (next == null || !applied[0])
		{
			return new AwardResult(0, next);
		}
		if (amount > 0)
		{
			for (Listener listener : new ArrayList<>(listeners))
			{
				try
				{
					listener.onGcAwarded(amount, context, next.getGc());
				}
				catch (Exception e)
				{
					log.warn("GC listener failed", e);
				}
			}
		}
		return new AwardResult(amount, next);
	}

	/** Award GC (may be reduced by taint / boosted by perks). Returns the net amount. */
	public synchronized long award(long baseAmount, GcContext context)
	{
		if (baseAmount <= 0 || stateService.get() == null)
		{
			return 0;
		}
		double factor = 1.0;
		for (Modifier modifier : modifiers)
		{
			try
			{
				factor *= modifier.factor(context);
			}
			catch (Exception e)
			{
				log.warn("GC modifier failed", e);
			}
		}
		if (stateService.get().getTaint() > 0)
		{
			factor *= Tuning.TAINT_INCOME_MULT;
		}
		long amount = Math.max(0, Math.round(baseAmount * factor));
		if (amount == 0)
		{
			return 0;
		}
		var newState = stateService.mutate(s -> s
			.withGc(s.getGc() + amount)
			.withLifetimeGcEarned(s.getLifetimeGcEarned() + amount));
		long balance = newState == null ? 0 : newState.getGc();
		for (Listener listener : new ArrayList<>(listeners))
		{
			try
			{
				listener.onGcAwarded(amount, context, balance);
			}
			catch (Exception e)
			{
				log.warn("GC listener failed", e);
			}
		}
		return amount;
	}

	/**
	 * Restore GC that was deducted in error (style pardons). Raw restitution:
	 * bypasses modifiers, taint halving and lifetime earnings — it is not
	 * income, it is undoing a wrongful deduction.
	 */
	public synchronized long refund(long amount)
	{
		if (amount <= 0 || stateService.get() == null)
		{
			return 0;
		}
		stateService.mutate(s -> s.withGc(s.getGc() + amount));
		return amount;
	}

	/** Deduct GC (violation penalties, purchases). Returns amount actually deducted. */
	public synchronized long deduct(long amount)
	{
		if (amount <= 0 || stateService.get() == null)
		{
			return 0;
		}
		long current = stateService.get().getGc();
		long actual = Math.min(current, amount);
		stateService.mutate(s -> s.withGc(s.getGc() - actual));
		return actual;
	}

	/** Try to spend exactly amount; false (and no change) when unaffordable. */
	public synchronized boolean spend(long amount)
	{
		if (stateService.get() == null || stateService.get().getGc() < amount)
		{
			return false;
		}
		stateService.mutate(s -> s.withGc(s.getGc() - amount));
		return true;
	}
}
