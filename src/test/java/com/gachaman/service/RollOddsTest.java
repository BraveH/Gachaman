package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDefinition;
import com.gachaman.model.GearSlot;
import com.gachaman.model.Rarity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * The house lean and the odds disclosure share this arithmetic on purpose, so these
 * assertions pin BOTH at once: if a number here moves, the roll and the panel move
 * together or the build goes red. ChestService itself cannot be tested here — the
 * lean's reach test dereferences Client — which is exactly why the arithmetic was
 * lifted out into RollOdds.
 */
public class RollOddsTest
{
	private static final double EPS = 1e-9;

	private static CardDefinition card(int id, String tierKey, Rarity rarity)
	{
		return new CardDefinition(id, "card" + id, GearSlot.WEAPON, tierKey,
			tierKey == null ? 0 : 1, tierKey == null ? null : "scimitar", rarity, Set.of(id), true);
	}

	private static double shareOf(Map<RollOdds.TierBand, Double> shares, String tierKey,
		boolean wieldableNow)
	{
		Double value = shares.get(new RollOdds.TierBand(tierKey, wieldableNow));
		return value == null ? 0 : value;
	}

	// --- pity arithmetic, lifted verbatim out of rollRarity ---

	/**
	 * The regression that matters most: this is the block that used to sit inline in
	 * ChestService.rollRarity, so these five numbers are the old behaviour transcribed.
	 * Gilded is {55, 26, 12, 5, 2}; a 6% pity bonus moves 6 out of Common, 70% of it to
	 * Epic and 30% to Legendary.
	 */
	@Test
	public void adjustOddsMatchesTheOldInlineArithmetic()
	{
		double[] out = RollOdds.adjustOdds(Tuning.CHEST_ODDS.get(Tuning.Chest.GILDED), 6.0);
		Assert.assertArrayEquals(new double[]{49.0, 26.0, 12.0, 9.2, 3.8}, out, 1e-9);
	}

	/** Tuning.CHEST_ODDS hands out a shared array; a caller must never see it move. */
	@Test
	public void adjustOddsClonesAndNeverMutatesTheCallerArray()
	{
		double[] odds = Tuning.CHEST_ODDS.get(Tuning.Chest.GILDED);
		double[] before = odds.clone();
		double[] out = RollOdds.adjustOdds(odds, 25.0);
		Assert.assertArrayEquals("adjustOdds mutated Tuning.CHEST_ODDS", before, odds, 0);
		Assert.assertNotSame(odds, out);
	}

	/**
	 * A Common share of 0 would make the rarity walk's index-0 fallback unreachable and
	 * the shift is clamped for exactly that reason. Pinned at an absurd bonus so the
	 * clamp is what is being measured, not the tuning.
	 */
	@Test
	public void adjustOddsNeverDrivesCommonBelowOne()
	{
		double[] out = RollOdds.adjustOdds(Tuning.CHEST_ODDS.get(Tuning.Chest.ORNATE), 500.0);
		Assert.assertEquals(1.0, out[0], EPS);
		double total = 0;
		for (double v : out)
		{
			total += v;
		}
		double before = 0;
		for (double v : Tuning.CHEST_ODDS.get(Tuning.Chest.ORNATE))
		{
			before += v;
		}
		Assert.assertEquals("pity redistributes mass, it never creates it", before, total, EPS);
	}

	@Test
	public void adjustOddsIsANoOpWithoutPity()
	{
		double[] odds = Tuning.CHEST_ODDS.get(Tuning.Chest.RUSTY);
		Assert.assertArrayEquals(odds, RollOdds.adjustOdds(odds, 0), 0);
	}

	// --- the lean itself ---

	/**
	 * The whole contract of the feature in one assertion: strictly less than wieldable
	 * gear (or there is no lean) and strictly more than zero (or the headroom band is
	 * dead, and it is deliberate aspirational slack — see Tuning.ROLL_TIER_HEADROOM).
	 */
	@Test
	public void leanWeightIsAStrictLeanThatNeverClosesTheHeadroom()
	{
		Assert.assertEquals(1.0, RollOdds.leanWeight(true), 0);
		Assert.assertTrue("headroom gear must stay possible", RollOdds.leanWeight(false) > 0);
		Assert.assertTrue("headroom gear must be rarer than wieldable gear",
			RollOdds.leanWeight(false) < RollOdds.leanWeight(true));
		Assert.assertTrue("HOUSE_LEAN_HEADROOM_WEIGHT must stay in (0,1)",
			Tuning.HOUSE_LEAN_HEADROOM_WEIGHT > 0 && Tuning.HOUSE_LEAN_HEADROOM_WEIGHT < 1);
	}

	@Test
	public void normalizeSumsToOneAndSurvivesAnAllZeroVector()
	{
		double[] out = RollOdds.normalize(new double[]{55, 26, 12, 5, 2});
		double total = 0;
		for (double v : out)
		{
			total += v;
		}
		Assert.assertEquals(1.0, total, EPS);
		Assert.assertEquals(0.55, out[0], EPS);
		// an empty candidate list must read as "no odds", not as a divide by zero
		Assert.assertArrayEquals(new double[]{0, 0, 0}, RollOdds.normalize(new double[]{0, 0, 0}), 0);
		Assert.assertEquals(0, RollOdds.normalize(new double[0]).length);
	}

	/**
	 * The draw pickLeaned actually performs. Boundaries are half-open on the low side
	 * (roll == a boundary lands on the NEXT bucket), which is what makes the widths add
	 * up to the total exactly once.
	 */
	@Test
	public void weightedIndexWalksTheCumulativeWeights()
	{
		double w = Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
		double[] weights = {1.0, 1.0, w};
		Assert.assertEquals(0, RollOdds.weightedIndex(0.0, weights));
		Assert.assertEquals(0, RollOdds.weightedIndex(0.999, weights));
		Assert.assertEquals(1, RollOdds.weightedIndex(1.0, weights));
		Assert.assertEquals(1, RollOdds.weightedIndex(1.999, weights));
		Assert.assertEquals(2, RollOdds.weightedIndex(2.0, weights));
		Assert.assertEquals(2, RollOdds.weightedIndex(2 + w - 1e-9, weights));
	}

	/**
	 * A roll of exactly the total (or a rounding crumb past it) must land on the last
	 * bucket rather than fall off the end — the headroom card is usually last, so the
	 * fallback has to be the generous one, not an exception.
	 */
	@Test
	public void weightedIndexClampsInsteadOfFallingOffTheEnd()
	{
		double[] weights = {1.0, 1.0, Tuning.HOUSE_LEAN_HEADROOM_WEIGHT};
		Assert.assertEquals(2, RollOdds.weightedIndex(2 + Tuning.HOUSE_LEAN_HEADROOM_WEIGHT, weights));
		Assert.assertEquals(2, RollOdds.weightedIndex(Double.MAX_VALUE, weights));
	}

	/**
	 * Every index must stay drawable, headroom included. Sweeping the roll space is the
	 * strongest form of "never impossible" available without an RNG.
	 */
	@Test
	public void everyBucketIncludingTheHeadroomIsReachableAcrossTheRollSpace()
	{
		double w = Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
		double[] weights = {1.0, 1.0, w};
		double total = 2 + w;
		int[] hits = new int[3];
		int samples = 10_000;
		for (int i = 0; i < samples; i++)
		{
			hits[RollOdds.weightedIndex((i + 0.5) / samples * total, weights)]++;
		}
		Assert.assertTrue("the headroom bucket was never drawn", hits[2] > 0);
		// the sweep is uniform, so hit counts are the probabilities back again
		Assert.assertEquals(1 / total, (double) hits[0] / samples, 1e-3);
		Assert.assertEquals(w / total, (double) hits[2] / samples, 1e-3);
		Assert.assertTrue("headroom must be drawn less often than wieldable gear",
			hits[2] < hits[0]);
	}

	// --- tier shares ---

	/**
	 * Hand-computed: two wieldable rune cards at weight 1 each and one dragon card in
	 * the headroom at weight w, over a total of 2 + w.
	 */
	@Test
	public void tierSharesReproduceAHandComputedDistribution()
	{
		List<CardDefinition> candidates = Arrays.asList(
			card(1, "rune", Rarity.COMMON),
			card(2, "rune", Rarity.COMMON),
			card(3, "dragon", Rarity.COMMON));
		double w = Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
		double total = 2 + w;
		Map<RollOdds.TierBand, Double> shares =
			RollOdds.tierShares(candidates, new boolean[]{true, true, false}, true);
		Assert.assertEquals(2, shares.size());
		Assert.assertEquals(2 / total, shareOf(shares, "rune", true), EPS);
		Assert.assertEquals(w / total, shareOf(shares, "dragon", false), EPS);
	}

	/**
	 * The headline claim the disclosure makes: dragon gets rarer than a uniform draw
	 * would give it, but never reaches zero.
	 */
	@Test
	public void theHeadroomTierGetsRarerButNeverImpossible()
	{
		List<CardDefinition> candidates = Arrays.asList(
			card(1, "rune", Rarity.COMMON),
			card(2, "rune", Rarity.COMMON),
			card(3, "dragon", Rarity.COMMON));
		boolean[] flags = {true, true, false};
		double leaned = shareOf(RollOdds.tierShares(candidates, flags, true), "dragon", false);
		double uniform = shareOf(RollOdds.tierShares(candidates, flags, false), "dragon", false);
		Assert.assertEquals("uniform draw is 1 in 3", 1.0 / 3, uniform, EPS);
		Assert.assertTrue("the lean must actually lean", leaned < uniform);
		Assert.assertTrue("the lean must never close the band", leaned > 0);
	}

	/**
	 * Why pickLeaned may short-circuit to rng.pick when every candidate sits in one
	 * band: a flat weight vector normalizes to the uniform draw exactly, so skipping
	 * the weighted walk is a substitution, not an approximation — which is what keeps
	 * the fixed-seed roll tests from moving.
	 */
	@Test
	public void aFlatBandNormalizesExactlyLikeAUniformDraw()
	{
		List<CardDefinition> candidates = Arrays.asList(
			card(1, "rune", Rarity.COMMON),
			card(2, "dragon", Rarity.COMMON),
			card(3, "adamant", Rarity.COMMON));
		boolean[] allOut = {false, false, false};
		Map<RollOdds.TierBand, Double> leaned = RollOdds.tierShares(candidates, allOut, true);
		Map<RollOdds.TierBand, Double> uniform = RollOdds.tierShares(candidates, allOut, false);
		Assert.assertEquals(uniform.keySet(), leaned.keySet());
		for (RollOdds.TierBand band : uniform.keySet())
		{
			Assert.assertEquals(band.getTierKey(), uniform.get(band), leaned.get(band), EPS);
			Assert.assertEquals(1.0 / 3, leaned.get(band), EPS);
		}
	}

	/**
	 * Untiered gear is gate-EXEMPT, not gate-passing: ChestService.isReachable returns
	 * true for a null tierKey, so it always carries full weight and must never be
	 * folded into the "wieldable" ladder rows.
	 */
	@Test
	public void untieredCardsGroupUnderTheUntieredKeyAtFullWeight()
	{
		List<CardDefinition> candidates = Arrays.asList(
			card(1, null, Rarity.COMMON),
			card(2, null, Rarity.COMMON),
			card(3, "dragon", Rarity.COMMON));
		double w = Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
		Map<RollOdds.TierBand, Double> shares =
			RollOdds.tierShares(candidates, new boolean[]{true, true, false}, true);
		Assert.assertEquals(2 / (2 + w), shareOf(shares, RollOdds.UNTIERED, true), EPS);
		Assert.assertEquals(0, shareOf(shares, RollOdds.UNTIERED, false), EPS);
	}

	/**
	 * One tier legitimately straddles both bands: ChestService applies reqDefence to
	 * BODY only, so hardleather chaps can be wieldable while the body is not. The
	 * disclosure has to be able to say that rather than pick a side.
	 */
	@Test
	public void oneTierCanAppearInBothBands()
	{
		List<CardDefinition> candidates = Arrays.asList(
			card(1, "hardleather", Rarity.COMMON),
			card(2, "hardleather", Rarity.COMMON));
		double w = Tuning.HOUSE_LEAN_HEADROOM_WEIGHT;
		Map<RollOdds.TierBand, Double> shares =
			RollOdds.tierShares(candidates, new boolean[]{true, false}, true);
		Assert.assertEquals(2, shares.size());
		Assert.assertEquals(1 / (1 + w), shareOf(shares, "hardleather", true), EPS);
		Assert.assertEquals(w / (1 + w), shareOf(shares, "hardleather", false), EPS);
	}

	/** Whatever the mix, the panel's rows must add up to one card. */
	@Test
	public void tierSharesAlwaysTotalOne()
	{
		List<CardDefinition> candidates = new ArrayList<>();
		String[] tiers = {"bronze", "iron", null, "rune", "dragon", "mystic", null, "black_dhide"};
		boolean[] flags = new boolean[tiers.length];
		for (int i = 0; i < tiers.length; i++)
		{
			candidates.add(card(i + 1, tiers[i], Rarity.COMMON));
			flags[i] = i % 3 != 0;
		}
		for (boolean leaned : new boolean[]{true, false})
		{
			double total = 0;
			for (double share : RollOdds.tierShares(candidates, flags, leaned).values())
			{
				total += share;
			}
			Assert.assertEquals("leaned=" + leaned, 1.0, total, EPS);
		}
	}

	/** An empty bucket must read as "no rows", not throw — oddsFor walks every rarity. */
	@Test
	public void anEmptyCandidateListYieldsNoRows()
	{
		Assert.assertTrue(RollOdds.tierShares(new ArrayList<>(), new boolean[0], true).isEmpty());
	}
}
