package com.gachaman.service;

import org.junit.*;

/**
 * The max-hit curve behind the "land a hit of N+" side bet. The client-reading
 * half of {@link MaxHitService} needs a live client; the arithmetic does not,
 * and the arithmetic is the part that decides whether a bet is winnable.
 *
 * <p>Reference values are the standard OSRS formula
 * {@code floor(0.5 + (level + 8) * (strBonus + 64) / 640)}, unboosted and
 * unprayed — the same steps the Plugin Hub max-hit calculators use, minus the
 * multipliers this deliberately leaves out.
 */
public class MaxHitServiceTest
{
	@Test
	public void bronzeTierAccountMaxesAboutThree()
	{
		// the case that exposed the bug: ~combat 12, bronze sword (str +7)
		// (10 + 8) * (7 + 64) / 640 = 2.0 -> +0.5 -> floor 2
		Assert.assertEquals(2, MaxHitService.gearedMaxHit(10, 7));
		// a few levels and a slightly better weapon still lands in low single digits
		Assert.assertEquals(3, MaxHitService.gearedMaxHit(20, 10));
		// which is why "land a hit of 6+" was unwinnable there
		Assert.assertTrue(MaxHitService.gearedMaxHit(20, 10) < 6);
	}

	@Test
	public void curveRisesWithBothLevelAndGear()
	{
		Assert.assertTrue(MaxHitService.gearedMaxHit(70, 60) > MaxHitService.gearedMaxHit(40, 60));
		Assert.assertTrue(MaxHitService.gearedMaxHit(70, 90) > MaxHitService.gearedMaxHit(70, 30));
		// a maxed melee account sits in the expected 40s, not the hundreds
		int maxed = MaxHitService.gearedMaxHit(99, 120);
		Assert.assertTrue("maxed melee looked wrong: " + maxed, maxed >= 30 && maxed <= 60);
	}

	@Test
	public void neverReturnsZeroOrNegative()
	{
		// an unarmed, level-1 account still has a floor: a threshold of 0 would
		// make the bet auto-win, which is as broken as auto-lose
		Assert.assertTrue(MaxHitService.gearedMaxHit(1, 0) >= 1);
		Assert.assertTrue(MaxHitService.gearedMaxHit(0, -10) >= 1);
		Assert.assertTrue(MaxHitService.magicMaxHit(1, 0) >= 1);
	}

	@Test
	public void spellLadderTracksMagicLevel()
	{
		Assert.assertEquals(2, MaxHitService.spellBaseMaxHit(1));
		Assert.assertEquals(2, MaxHitService.spellBaseMaxHit(12));
		Assert.assertEquals(8, MaxHitService.spellBaseMaxHit(13));   // fire strike
		Assert.assertEquals(12, MaxHitService.spellBaseMaxHit(35));  // fire bolt
		Assert.assertEquals(16, MaxHitService.spellBaseMaxHit(59));  // fire blast
		Assert.assertEquals(24, MaxHitService.spellBaseMaxHit(99));  // fire surge
		int prev = 0;
		for (int lvl = 1; lvl <= 99; lvl++)
		{
			int base = MaxHitService.spellBaseMaxHit(lvl);
			Assert.assertTrue("ladder must never fall at level " + lvl, base >= prev);
			prev = base;
		}
	}

	@Test
	public void magicDamageBonusScalesTheSpell()
	{
		// fire surge at +0% vs +20% magic damage
		Assert.assertEquals(24, MaxHitService.magicMaxHit(99, 0));
		Assert.assertEquals(28, MaxHitService.magicMaxHit(99, 20));
	}

	@Test
	public void everyThresholdItFeedsStaysWinnable()
	{
		// the end-to-end property: whatever the curve says, the bet built from
		// it must be reachable. This is the guard the old combat-level version
		// failed for every account under combat 24.
		for (int level = 1; level <= 99; level++)
		{
			for (int bonus = 0; bonus <= 150; bonus += 10)
			{
				int max = MaxHitService.gearedMaxHit(level, bonus);
				Assert.assertTrue("level " + level + " bonus " + bonus,
					TaskGenerator.bigHitThreshold(max) <= max);
			}
		}
	}
}
