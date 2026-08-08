package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.Rarity;
import org.junit.Assert;
import org.junit.Test;

/** Fixed-seed distribution and math checks — no live client needed. */
public class RollMathTest
{
	private ChestService serviceWithSeed(long seed)
	{
		// only rollRarity is exercised; collaborators may be null
		return new ChestService(null, null, null, null, new GachaRng(seed), new com.google.gson.Gson(), null, null);
	}

	@Test
	public void rarityDistributionMatchesOddsWithinTolerance()
	{
		ChestService service = serviceWithSeed(42L);
		double[] odds = Tuning.CHEST_ODDS.get(Tuning.Chest.BATTERED);
		int n = 200_000;
		int[] counts = new int[Rarity.values().length];
		for (int i = 0; i < n; i++)
		{
			counts[service.rollRarity(odds, 0).ordinal()]++;
		}
		double total = 0;
		for (double o : odds)
		{
			total += o;
		}
		for (int i = 0; i < odds.length; i++)
		{
			double expected = odds[i] / total;
			double actual = counts[i] / (double) n;
			Assert.assertEquals("rarity " + Rarity.values()[i], expected, actual, expected * 0.15 + 0.002);
		}
	}

	@Test
	public void pityBonusShiftsMassUpward()
	{
		ChestService service = serviceWithSeed(7L);
		double[] odds = Tuning.CHEST_ODDS.get(Tuning.Chest.BATTERED);
		int n = 100_000;
		int epicPlusBase = 0;
		int epicPlusPity = 0;
		for (int i = 0; i < n; i++)
		{
			if (service.rollRarity(odds, 0).atLeast(Rarity.EPIC))
			{
				epicPlusBase++;
			}
			if (service.rollRarity(odds, 20).atLeast(Rarity.EPIC))
			{
				epicPlusPity++;
			}
		}
		Assert.assertTrue("pity should raise Epic+ rate: " + epicPlusBase + " vs " + epicPlusPity,
			epicPlusPity > epicPlusBase * 3);
	}

	@Test
	public void splitmixIsDeterministic()
	{
		Assert.assertEquals(WeeklyShopService.splitmix64(123L), WeeklyShopService.splitmix64(123L));
		Assert.assertNotEquals(WeeklyShopService.splitmix64(123L), WeeklyShopService.splitmix64(124L));
	}

	@Test
	public void killCbMultiplierCurve()
	{
		// within 5 levels below: flat base
		Assert.assertEquals(1.0, Tuning.killCbMultiplier(100, 95), 1e-9);
		Assert.assertEquals(1.0, Tuning.killCbMultiplier(100, 99), 1e-9);
		// matching your level pays a bonus
		Assert.assertEquals(1.1, Tuning.killCbMultiplier(100, 100), 1e-9);
		// stronger pays progressively more
		double plus10 = Tuning.killCbMultiplier(100, 110);
		double plus30 = Tuning.killCbMultiplier(100, 130);
		Assert.assertTrue(plus10 > 1.1 && plus30 > plus10);
		// the same absolute gap means far more at low levels (ratio scaling)
		Assert.assertTrue("+5 at cb 3 must dwarf +5 at cb 70",
			Tuning.killCbMultiplier(3, 8) > 2 * Tuning.killCbMultiplier(70, 75));
		// far above caps
		Assert.assertEquals(Tuning.KILL_DIFF_CAP, Tuning.killCbMultiplier(50, 700), 1e-9);
		// trivial mobs decay toward the floor
		Assert.assertTrue(Tuning.killCbMultiplier(100, 80) < 1.0);
		Assert.assertEquals(Tuning.KILL_DIFF_FLOOR, Tuning.killCbMultiplier(126, 2), 1e-9);
		// monotonic: never pays more for an easier monster
		double prev = -1;
		for (int npc = 2; npc <= 300; npc++)
		{
			double mult = Tuning.killCbMultiplier(100, npc);
			Assert.assertTrue("non-monotonic at npc " + npc, mult >= prev);
			prev = mult;
		}
	}

	@Test
	public void lowLevelMultiplierTapers()
	{
		Assert.assertEquals(1.0 + Tuning.LOWLEVEL_MAX_BONUS, Tuning.lowLevelMultiplier(3), 1e-9);
		Assert.assertEquals(1.0, Tuning.lowLevelMultiplier(Tuning.LOWLEVEL_CEILING), 1e-9);
		Assert.assertEquals(1.0, Tuning.lowLevelMultiplier(126), 1e-9);
		double prev = Double.MAX_VALUE;
		for (int cb = 3; cb <= 126; cb++)
		{
			double mult = Tuning.lowLevelMultiplier(cb);
			Assert.assertTrue("must never increase with level (cb " + cb + ")", mult <= prev);
			Assert.assertTrue(mult >= 1.0);
			prev = mult;
		}
	}

	@Test
	public void violationPenaltyRules()
	{
		Assert.assertEquals(Tuning.VIOLATION_ATTACK_PENALTY_NO_TASK, ComplianceService.penaltyFor(null));
		com.gachaman.model.ActiveTask task = com.gachaman.model.ActiveTask.builder()
			.difficulty(com.gachaman.model.TaskDifficulty.HARD)
			.monsterName("Fire giant")
			.killsRequired(50)
			.perKillGc(16)
			.completionGc(950)
			.acceptedAtMs(1L)
			.build();
		Assert.assertEquals(32, ComplianceService.penaltyFor(task));
	}

}
