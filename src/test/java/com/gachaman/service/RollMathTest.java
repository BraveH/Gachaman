package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import org.junit.*;

/** Fixed-seed distribution and math checks — no live client needed. */
public class RollMathTest
{
	private ChestService serviceWithSeed(long seed)
	{
		// only rollRarity is exercised; collaborators may be null
		return new ChestService(null, null, null, null, new GachaRng(seed), new com.google.gson.Gson(), null, null, null);
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

	/**
	 * The low-level compensation multiplier is gone on purpose (see Tuning).
	 * What replaces its test is the property it was violating: a kill must not
	 * pay more just because the player is low level. Combat level now reaches
	 * the payout ONLY through the npc-vs-player ratio, so a player and a
	 * monster in the same relative position pay the same at every level.
	 */
	@Test
	public void payoutDependsOnTheGapNotOnBeingLowLevel()
	{
		// same ratio (npc = 1.35x player) at four very different levels
		double at12 = Tuning.killCbMultiplier(12, 16);
		double at30 = Tuning.killCbMultiplier(30, 40);
		double at70 = Tuning.killCbMultiplier(70, 94);
		double at100 = Tuning.killCbMultiplier(100, 135);
		Assert.assertEquals(at12, at30, 0.05);
		Assert.assertEquals(at12, at70, 0.05);
		Assert.assertEquals(at12, at100, 0.05);
	}

	@Test
	public void combinedKillBonusIsBoundedAndAdditive()
	{
		// the runaway this replaced: killCb x combo compounded. Added, the pair
		// cannot exceed the sum of their two ceilings.
		double maxKillCb = Tuning.KILL_DIFF_CAP - 1.0;
		double maxCombo = Tuning.comboMultiplier(Tuning.COMBO_MAX_STACKS) - 1.0;
		double ceiling = 1.0 + maxKillCb + maxCombo;
		Assert.assertTrue("combined ceiling should stay under 5x", ceiling < 5.0);
		for (int cb = 3; cb <= 126; cb++)
		{
			for (int npc = 1; npc <= 200; npc += 7)
			{
				double bonus = (Tuning.killCbMultiplier(cb, npc) - 1.0)
					+ (Tuning.comboMultiplier(Tuning.COMBO_MAX_STACKS) - 1.0);
				Assert.assertTrue("cb " + cb + " vs npc " + npc, 1.0 + bonus <= ceiling + 1e-9);
			}
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
