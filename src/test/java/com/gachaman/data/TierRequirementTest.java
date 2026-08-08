package com.gachaman.data;

import com.gachaman.Tuning;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tier reach: the dhide and robes ladders rank by power, not by level, so they
 * carry real requirements in tiers.json instead of borrowing metal's rank table.
 * ChestService.isReachable cannot be tested directly — it returns true whenever
 * Client is null, which is every headless test — so the comparison lives in
 * Tuning.withinReach and is pinned here.
 */
public class TierRequirementTest
{
	private static final String[] LADDER_TIERS = {
		"bronze", "iron", "steel", "black", "white", "mithril", "adamant", "rune", "granite", "dragon",
		"leather", "hardleather", "studded", "snakeskin", "frog_leather",
		"green_dhide", "blue_dhide", "red_dhide", "black_dhide",
		"wizard", "xerician", "mystic", "enchanted", "splitbark", "infinity", "ancestral"};

	private TierTable table()
	{
		return TierTable.load(new Gson());
	}

	/**
	 * The load-bearing guard. A tier that loses its requirements falls back to the
	 * fail-closed sentinel and drops out of every gated roll — and pickCardOfRarity's
	 * unfiltered fallback would then mask that as "the gate quietly stopped working".
	 */
	@Test
	public void everyTierLoadsWithRequirements()
	{
		TierTable tiers = table();
		for (String tierKey : LADDER_TIERS)
		{
			Assert.assertNotEquals(tierKey + " has no reqLevel in tiers.json",
				99, tiers.reqLevelOf(tierKey));
			Assert.assertNotEquals(tierKey + " has no reqDefence in tiers.json",
				99, tiers.reqDefenceOf(tierKey));
			Assert.assertTrue(tierKey + " reqLevel must be >= 1", tiers.reqLevelOf(tierKey) >= 1);
			Assert.assertTrue(tierKey + " reqDefence must be >= 1", tiers.reqDefenceOf(tierKey) >= 1);
		}
	}

	/** An unannotated tier must fail closed (out of reach), never open at level 1. */
	@Test
	public void unknownTierFailsClosed()
	{
		Assert.assertEquals(99, table().reqLevelOf("no-such-tier"));
		Assert.assertEquals(99, table().reqDefenceOf("no-such-tier"));
	}

	/** Pinned against the wiki, so a data typo cannot silently reopen the gate. */
	@Test
	public void requirementsMatchRealOsrs()
	{
		TierTable t = table();
		Assert.assertEquals(1, t.reqLevelOf("iron"));
		Assert.assertEquals(5, t.reqLevelOf("steel"));
		Assert.assertEquals(10, t.reqLevelOf("black"));
		Assert.assertEquals(20, t.reqLevelOf("mithril"));
		Assert.assertEquals(40, t.reqLevelOf("rune"));
		Assert.assertEquals(50, t.reqLevelOf("granite"));
		Assert.assertEquals(60, t.reqLevelOf("dragon"));

		Assert.assertEquals(1, t.reqLevelOf("leather"));
		Assert.assertEquals(1, t.reqLevelOf("hardleather"));
		Assert.assertEquals(10, t.reqDefenceOf("hardleather"));
		Assert.assertEquals(20, t.reqLevelOf("studded"));
		Assert.assertEquals(30, t.reqLevelOf("snakeskin"));
		Assert.assertEquals(40, t.reqLevelOf("green_dhide"));
		Assert.assertEquals(70, t.reqLevelOf("black_dhide"));

		Assert.assertEquals(1, t.reqLevelOf("wizard"));
		Assert.assertEquals(20, t.reqLevelOf("xerician"));
		Assert.assertEquals(10, t.reqDefenceOf("xerician"));
		Assert.assertEquals(40, t.reqLevelOf("mystic"));
		Assert.assertEquals(20, t.reqDefenceOf("mystic"));
		Assert.assertEquals(40, t.reqDefenceOf("splitbark"));
		Assert.assertEquals(50, t.reqLevelOf("infinity"));
		Assert.assertEquals(25, t.reqDefenceOf("infinity"));
		Assert.assertEquals(75, t.reqLevelOf("ancestral"));
		Assert.assertEquals(65, t.reqDefenceOf("ancestral"));
	}

	/**
	 * A higher rank must never be cheaper than a lower one on the same ladder.
	 * Compared pairwise rather than as a sorted run because ranks are not unique —
	 * black/white share 4, rune/granite share 7, snakeskin/frog_leather share 4.
	 */
	@Test
	public void requirementsRiseWithRankWithinEachLadder()
	{
		TierTable t = table();
		for (String a : LADDER_TIERS)
		{
			for (String b : LADDER_TIERS)
			{
				if (!java.util.Objects.equals(t.ladderOf(a), t.ladderOf(b))
					|| t.rankOf(a) >= t.rankOf(b))
				{
					continue;
				}
				Assert.assertTrue(a + " (rank " + t.rankOf(a) + ") requires more than "
						+ b + " (rank " + t.rankOf(b) + ")",
					t.reqLevelOf(a) <= t.reqLevelOf(b));
			}
		}
	}

	/**
	 * Regression pin for B2-tier-clamp. Under the old rank table these three resolved
	 * to 1, 10 and 1 respectively, which is what let a level-1 account roll them.
	 */
	@Test
	public void regressionB2TierClamp()
	{
		TierTable t = table();
		Assert.assertTrue("xerician must not read as a level-1 tier", t.reqLevelOf("xerician") >= 20);
		Assert.assertTrue("mystic must not read as a level-10 tier", t.reqLevelOf("mystic") >= 40);
		Assert.assertTrue("hardleather body needs 10 Defence", t.reqDefenceOf("hardleather") >= 10);
	}

	// --- display names, as printed by the Chest Odds disclosure ---

	/**
	 * The odds panel prints one row per tier, so a tier with no label would show up as
	 * a raw key with an underscore in it. Names are harvested from the hologram entries
	 * rather than authored twice, which is what this guards.
	 */
	@Test
	public void everyLadderTierHasADisplayName()
	{
		TierTable t = table();
		for (String tierKey : LADDER_TIERS)
		{
			String name = t.displayNameOf(tierKey);
			Assert.assertNotNull(tierKey + " has no display name", name);
			Assert.assertFalse(tierKey + " has an empty display name", name.isEmpty());
			Assert.assertFalse(tierKey + " leaks its raw key into the UI", name.contains("_"));
			Assert.assertFalse(tierKey + " kept the \" Hologram\" suffix", name.endsWith("Hologram"));
		}
	}

	/** A tier authored without a hologram must still read as words, not as a key. */
	@Test
	public void unknownTierFallsBackToWords()
	{
		Assert.assertEquals("No Such Tier", table().displayNameOf("no_such_tier"));
	}

	// --- the reach comparison itself ---

	/**
	 * The headline symptom: a fresh account must stop rolling Mystic (40 Magic) and
	 * Xerician (20 Magic), and Snakeskin/Studded on the dhide side.
	 */
	@Test
	public void freshAccountCannotReachMidLadderRobesOrDhide()
	{
		int h = Tuning.ROLL_LEVEL_HEADROOM;
		Assert.assertFalse("mystic at Magic 1", Tuning.withinReach(1, 1, 40, 20, h));
		Assert.assertFalse("xerician at Magic 1", Tuning.withinReach(1, 1, 20, 10, h));
		Assert.assertFalse("splitbark at Magic 1", Tuning.withinReach(1, 1, 40, 40, h));
		Assert.assertFalse("studded at Ranged 1", Tuning.withinReach(1, 1, 20, 20, h));
		Assert.assertFalse("snakeskin at Ranged 1", Tuning.withinReach(1, 1, 30, 30, h));
	}

	/**
	 * Headroom still has to feel aspirational, not dead: hardleather (10 Defence) stays
	 * rollable on a fresh account, the dhide analogue of black metal at level 1.
	 */
	@Test
	public void headroomStillReachesTheNextStepUp()
	{
		int h = Tuning.ROLL_LEVEL_HEADROOM;
		Assert.assertTrue("leather", Tuning.withinReach(1, 1, 1, 1, h));
		Assert.assertTrue("hardleather body", Tuning.withinReach(1, 1, 1, 10, h));
	}

	/**
	 * The Rusty pool calls isReachable with no headroom, so at level 1 only genuinely
	 * level-1 tiers may survive. Xerician top and Hardleather body both passed before.
	 */
	@Test
	public void rustyPoolAdmitsOnlyLevelOneGear()
	{
		Assert.assertTrue("leather / wizard", Tuning.withinReach(1, 1, 1, 1, 0));
		Assert.assertFalse("xerician top", Tuning.withinReach(1, 1, 20, 10, 0));
		Assert.assertFalse("hardleather body", Tuning.withinReach(1, 1, 1, 10, 0));
	}

	/** A maxed Ranged, level-1 Defence account is still blocked from splitbark-style gear. */
	@Test
	public void defenceIsEnforcedIndependentlyOfThePrimarySkill()
	{
		Assert.assertFalse(Tuning.withinReach(99, 1, 1, 40, 0));
	}

	/**
	 * D'hide chaps and vambraces carry no Defence requirement while the body needs 40.
	 * ChestService applies reqDefence to BODY only; this pins the resulting arithmetic
	 * (the slot branch itself needs a Client and cannot be tested).
	 */
	@Test
	public void dhideAccessoriesIgnoreTheBodyDefenceRequirement()
	{
		// Ranged 50 / Defence 1: blue d'hide chaps yes, blue d'hide body no
		Assert.assertTrue("blue d'hide chaps", Tuning.withinReach(50, 1, 50, 1, 0));
		Assert.assertFalse("blue d'hide body", Tuning.withinReach(50, 1, 50, 40, 0));
	}

	/**
	 * Metal is deliberately NOT retuned by this fix — TIER_RANK_LEVELS transcribes the
	 * melee ladder exactly, so metal was already correct. Pin the whole reach curve so
	 * a later change to the reach model cannot quietly move it. Values are the highest
	 * metal rank a gated roll may land on at each level.
	 */
	@Test
	public void metalReachCurveIsUnchanged()
	{
		int[][] levelToMaxRank = {{1, 4}, {5, 5}, {10, 6}, {20, 7}, {30, 8}, {40, 9}, {50, 9}, {60, 10}};
		for (int[] row : levelToMaxRank)
		{
			Assert.assertEquals("metal reach moved at level " + row[0], row[1],
				Tuning.maxRankForLevel(row[0]) + Tuning.ROLL_TIER_HEADROOM);
		}
	}

	/**
	 * Regression pin for the fix that was rejected: metal resolves on
	 * max(ATTACK, DEFENCE) because metal weapons gate on Attack and metal armour on
	 * Defence, never both. A 60 Attack / 1 Defence pure must still reach rune (rank 7)
	 * and dragon (rank 8) weapons, with and without headroom.
	 */
	@Test
	public void attackPureStillReachesHighMetal()
	{
		int metalLevel = Math.max(60, 1);
		Assert.assertTrue("rune, no headroom", 7 <= Tuning.maxRankForLevel(metalLevel));
		Assert.assertTrue("dragon, with headroom",
			8 <= Tuning.maxRankForLevel(metalLevel) + Tuning.ROLL_TIER_HEADROOM);
		// and the ordinary mid-game case the rejected fix also broke
		Assert.assertTrue("rune at 40 Attack / 20 Defence",
			7 <= Tuning.maxRankForLevel(Math.max(40, 20)) + Tuning.ROLL_TIER_HEADROOM);
	}
}
