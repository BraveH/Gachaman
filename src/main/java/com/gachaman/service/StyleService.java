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
 *
 * <p>The wheel names two things: the style, and a preferred weapon CATEGORY
 * inside it. The category is purely a bonus — a compliant on-task kill landed
 * with that category in hand pays {@link Tuning#WEAPON_BONUS_MULT}, and a kill
 * landed without it pays exactly what it always did. See {@link
 * WeaponTypeService} for the taxonomy, and for why a category this build cannot
 * name is silently no bonus rather than a penalty.
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
		/**
		 * The weapon category named alongside the style, or null when none was —
		 * an empty pool, which means the taxonomy resource failed to load.
		 *
		 * <p>The whole VALUE rather than its key, so an announcing caller can reach
		 * {@code getDisplayName()} without injecting WeaponTypeService to translate.
		 * That matters more than it looks: the key for category 0 is "unarmed", and
		 * the owner's rule is that player-facing text says "no weapon equipped",
		 * because the game reports that category for every non-weapon held item
		 * too. Handing callers the key alone would make the easy thing the wrong
		 * thing. Deeply immutable, so the ceremony payload is safe on the EDT.
		 */
		WeaponTypeService.WeaponType weaponType;
	}

	private final GachaStateService stateService;
	private final ComplianceService complianceService;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;
	private final WeaponTypeService weaponTypeService;

	public boolean hasRolled() {
		GachaState state = stateService.get();
		return state != null && state.getAllowedStyle() != null;
	}

	/** Roll (or re-roll) the allowed style, commit, and queue the roulette ceremony. */
	public StyleRollResult roll(int currentTick) {
		return roll(currentTick, null);
	}

	/**
	 * As {@link #roll(int)}, but with the style NAMED instead of drawn — the
	 * Consignment's deferred path, where the house picks the style as the price of
	 * a free crate. See {@link ConsignmentService} for why that roll is held back
	 * rather than taken and overwritten.
	 *
	 * <p><b>An overload here rather than a commit written over there.</b> The wheel
	 * names more than the style, and it will name more again: a duplicated mutate
	 * inside the Consignment would set the style, the cycle and the timestamp and
	 * silently drop the preferred weapon type, so an accepted deal would cost the
	 * player the whole cycle's {@link Tuning#WEAPON_BONUS_MULT} with nothing on
	 * screen to explain it. One commit path means whatever the wheel learns to name
	 * next lands in both routes at once, with no sync burden on either.
	 *
	 * <p>Nothing here excludes a forced style from the first-ever roll, and nothing
	 * needs to: {@code ConsignmentService.canOffer} refuses to make an offer at all
	 * while {@code allowedStyle} is null, because an account with no album cannot be
	 * worst dressed for anything. Were a forced roll ever to land first anyway,
	 * {@code firstEver} below still arms the free First Colours chest — the
	 * discriminator is the absent PREVIOUS style, not the presence of a draw.
	 *
	 * @param forced the style to commit, or null to spin the wheel
	 */
	public StyleRollResult roll(int currentTick, AttackStyle forced) {
		GachaState state = stateService.get();
		if (state == null)
			return null;
		AttackStyle previous = state.getAllowedStyle() == null
			? null : AttackStyle.valueOf(state.getAllowedStyle());
		AttackStyle rolled = forced != null
			? forced : AttackStyle.values()[rng.nextInt(AttackStyle.values().length)];
		int target = Tuning.CYCLE_TASKS;
		// A fresh account is exactly the one with no previous style, and that same
		// fact arms the free First Colours chest. Armed inside THIS mutate rather
		// than a second one: a separate write would leave a window where the style
		// is committed but the gift is not owed, and every mutate pays a full
		// gzip + SHA-256 of the whole save.
		final boolean firstEver = previous == null;
		// The weapon category rides the same mutate for the same reason, and the
		// window it closes is nastier than the chest's: two writes would leave a
		// moment in which the NEW style is live and the preference is still the
		// LAST cycle's, so a crash there ships a MAGIC cycle whose preferred
		// category is a scimitar — a bonus the player can never earn and cannot see
		// is wrong. Drawn out here and captured, following the same shape as
		// `rolled` and `target`, so the draw is unambiguously once.
		//
		// AFTER the style draw, deliberately: the style stays the first number off
		// the stream, which is what lets a test predict a seeded wheel with a bare
		// `new GachaRng(seed).nextInt(3)`.
		//
		// And it draws from the INJECTED rng — the singleton, never the party's. A
		// seeded party roll builds its own `new GachaRng(seed)` and passes it down
		// as an argument (PartyRollService), so this stream is not that one, and it
		// must stay that way: an extra draw taken inside a seeded path by one client
		// and not another shifts every later draw and deals two different boards.
		// Tuning's Double Docket comment warns about exactly that failure. The
		// preference is strictly per-player and rides the per-player style roll —
		// party members legitimately hold different styles and different categories,
		// and each earns their own bonus on their own kills against the shared quota.
		final WeaponTypeService.WeaponType weaponType = weaponTypeService.roll(rolled);

		stateService.mutate(s -> {
			GachaState next = s
				.withAllowedStyle(rolled.name())
				.withCycleProgress(0)
				.withCycleTarget(target)
				.withStyleRolledAtMs(System.currentTimeMillis())
				// Written unconditionally, null included. A cycle the taxonomy could
				// not name a category for must CLEAR the last one rather than inherit
				// it — an "only write it when we have one" guard would leave a stale
				// preference live against a style it was never rolled for.
				.withPreferredWeaponType(weaponType == null ? null : weaponType.getKey());
			return firstEver ? next.withFirstColoursChestOwed(true) : next;
		});
		complianceService.noteStyleChanged(currentTick);

		StyleRollResult result = new StyleRollResult(previous, rolled, target, weaponType);
		ceremonyBus.submit(CeremonyBus.Type.STYLE_ROLL, result);
		log.debug("Style rolled: {} (target {} tasks, weapon {})", rolled, target,
			weaponType == null ? "none" : weaponType.getKey());
		return result;
	}

	/** Advance the cycle by a completed task; true when a re-roll is due. */
	public boolean advanceCycle(String appliedCharge) {
		GachaState state = stateService.get();
		if (state == null || state.getAllowedStyle() == null)
			return false;
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
