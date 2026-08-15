package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import java.util.*;
import lombok.*;

/**
 * The roll's own arithmetic, lifted out of ChestService so the odds disclosure can
 * quote the numbers the roll actually uses instead of a parallel transcription that
 * would drift the first time either side is retuned. Pure and static throughout:
 * nothing here reads a Client, a GachaState or the RNG, which is also what makes it
 * the testable half of the feature (ChestService.isReachable dereferences Client).
 */
public final class RollOdds {
	/**
	 * Bucket key for cards the proximity gate never level-checks at all. Deliberately
	 * the empty string rather than null so it can be a map key and compared with
	 * equals() without a null branch at every use site.
	 */
	public static final String UNTIERED = "";

	/** One disclosure row: a tier ladder in one reach band. */
	@Value
	public static class TierBand {
		/** tierKey, or UNTIERED. */
		String tierKey;
		/**
		 * True when the cards behind this row pass isReachable(card, false). A tier can
		 * legitimately appear in BOTH bands: ChestService.ladderWithinReach applies
		 * reqDefence to BODY only, so hardleather chaps can be wieldable while the
		 * hardleather body is not.
		 */
		boolean wieldableNow;
	}

	/**
	 * The row one card falls in. Public so the disclosure can collect the card
	 * names behind each row from the same key {@link #tierShares} counts them
	 * under — two independent derivations of "which row is this?" would be free
	 * to disagree, and the tooltip would then name cards from the wrong band.
	 */
	public static TierBand bandOf(CardDefinition card, boolean wieldableNow) {
		String key = card.getTierKey() == null ? UNTIERED : card.getTierKey();
		return new TierBand(key, wieldableNow);
	}

	private RollOdds() {
	}

	/**
	 * Verbatim lift of the block that used to sit inline in ChestService.rollRarity:
	 * shift the pity bonus into EPIC+LEGENDARY mass, taken from COMMON. Clones on
	 * purpose — the caller passes Tuning.CHEST_ODDS's shared array and must never see
	 * it move.
	 */
	public static double[] adjustOdds(double[] odds, double pityBonusPercent) {
		double[] adjusted = odds.clone();
		if (pityBonusPercent > 0) {
			double shift = Math.min(adjusted[0] - 1, pityBonusPercent);
			adjusted[0] -= shift;
			adjusted[3] += shift * 0.7;
			adjusted[4] += shift * 0.3;
		}
		return adjusted;
	}

	/** Never returns 0: headroom gear stays possible, just rarer. */
	public static double leanWeight(boolean wieldableNow) {
		return wieldableNow ? 1.0 : Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
	}

	/**
	 * The weighted draw itself: walk the cumulative weights until {@code roll} is
	 * passed. {@code roll} must already be scaled to the weight total, and the total
	 * must have been accumulated from these very values so the two cannot disagree in
	 * the last bit; the final index is returned if it does anyway, so a rounding crumb
	 * costs one skewed pick rather than an exception.
	 *
	 * <p>Static and RNG-free because the alternative — a private walk inside
	 * ChestService — could only be tested through isReachable, which dereferences
	 * Client and is therefore untestable headless.
	 */
	public static int weightedIndex(double roll, double[] weights) {
		double cumulative = 0;
		for (int i = 0; i < weights.length; i++) {
			cumulative += weights[i];
			if (roll < cumulative)
				return i;
		}
		return weights.length - 1;
	}

	/** Weights to probabilities. An empty or all-zero vector returns all zeroes. */
	public static double[] normalize(double[] weights) {
		double total = 0;
		for (double w : weights) {
			total += w;
		}
		double[] out = new double[weights.length];
		if (total <= 0)
			return out;
		for (int i = 0; i < weights.length; i++) {
			out[i] = weights[i] / total;
		}
		return out;
	}

	/**
	 * How one pick from this candidate list distributes over (tier, band) rows.
	 *
	 * <p>{@code leaned} is false for Epic+ and for the unfiltered fallback, which both
	 * draw uniformly, so passing it through keeps the disclosure honest about those
	 * branches instead of quietly reporting a lean that never ran.
	 */
	public static Map<TierBand, Double> tierShares(List<CardDefinition> candidates,
		boolean[] wieldableNow, boolean leaned) {
		Map<TierBand, Double> shares = new LinkedHashMap<>();
		if (candidates.isEmpty())
			return shares;
		double[] weights = new double[candidates.size()];
		double total = 0;
		for (int i = 0; i < candidates.size(); i++) {
			weights[i] = leaned ? leanWeight(wieldableNow[i]) : 1.0;
			total += weights[i];
		}
		if (total <= 0)
			return shares;
		for (int i = 0; i < candidates.size(); i++) {
			shares.merge(bandOf(candidates.get(i), wieldableNow[i]), weights[i] / total,
				Double::sum);
		}
		return shares;
	}
}
