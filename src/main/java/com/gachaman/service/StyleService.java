package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * The style lock. Rolls are uniform 1/3 and may legitimately re-pick the
 * current style; the roulette ceremony is purely cosmetic (outcome committed
 * immediately — nothing here is exploitable by closing the overlay).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StyleService {
	@Value
	public static class StyleRollResult {
		AttackStyle previous; // null on the very first roll
		AttackStyle rolled;
		int cycleTarget;
	}

	private final GachaStateService stateService;
	private final ComplianceService complianceService;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;

	public boolean hasRolled() {
		GachaState state = stateService.get();
		return state != null && state.getAllowedStyle() != null;
	}

	/** Roll (or re-roll) the allowed style, commit, and queue the roulette ceremony. */
	public StyleRollResult roll(int currentTick) {
		GachaState state = stateService.get();
		if (state == null) {
			return null;
		}
		AttackStyle previous = state.getAllowedStyle() == null
			? null : AttackStyle.valueOf(state.getAllowedStyle());
		AttackStyle rolled = AttackStyle.values()[rng.nextInt(AttackStyle.values().length)];
		int target = Tuning.CYCLE_TASKS;
		// A fresh account is exactly the one with no previous style, and that same
		// fact arms the free First Colours chest. Armed inside THIS mutate rather
		// than a second one: a separate write would leave a window where the style
		// is committed but the gift is not owed, and every mutate pays a full
		// gzip + SHA-256 of the whole save.
		final boolean firstEver = previous == null;

		stateService.mutate(s -> {
			GachaState next = s
				.withAllowedStyle(rolled.name())
				.withCycleProgress(0)
				.withCycleTarget(target)
				.withStyleRolledAtMs(System.currentTimeMillis());
			return firstEver ? next.withFirstColoursChestOwed(true) : next;
		});
		complianceService.noteStyleChanged(currentTick);

		StyleRollResult result = new StyleRollResult(previous, rolled, target);
		ceremonyBus.submit(CeremonyBus.Type.STYLE_ROLL, result);
		log.debug("Style rolled: {} (target {} tasks)", rolled, target);
		return result;
	}

	/** Advance the cycle by a completed task; true when a re-roll is due. */
	public boolean advanceCycle(String appliedCharge) {
		GachaState state = stateService.get();
		if (state == null || state.getAllowedStyle() == null) {
			return false;
		}
		double weight = 1.0;
		if ("COMPACTOR".equals(appliedCharge)) {
			weight = Tuning.COMPACTOR_WEIGHT;
		}
		else if ("EXTENDER".equals(appliedCharge)) {
			weight = Tuning.EXTENDER_WEIGHT;
		}
		final double w = weight;
		GachaState next = stateService.mutate(s -> s.withCycleProgress(s.getCycleProgress() + w));
		return next != null && next.getCycleProgress() >= next.getCycleTarget();
	}
}
