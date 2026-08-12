package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import org.junit.*;

/**
 * The Ante's two pure rules: what a stake is worth, and which contracts may
 * carry one. Both are arithmetic over a purse and an enum, so they are testable
 * in isolation — the escrow itself needs a state service and lives in
 * {@link TaskServiceAnteTest}.
 */
public class AnteStakeTest
{
	private static TaskOffer offer(TaskDifficulty difficulty)
	{
		return new TaskOffer(difficulty, "Goblin", 2, 10, 8, 250, null, false, false);
	}

	// --- A. eligibility: the hardest contracts only ---

	@Test
	public void onlyInsaneContractsCanCarryTheAnte()
	{
		Assert.assertTrue(TaskService.anteEligible(offer(TaskDifficulty.INSANE)));
		Assert.assertFalse(TaskService.anteEligible(offer(TaskDifficulty.HARD)));
		Assert.assertFalse(TaskService.anteEligible(offer(TaskDifficulty.MEDIUM)));
		Assert.assertFalse(TaskService.anteEligible(offer(TaskDifficulty.EASY)));
	}

	@Test
	public void anAbsentOfferIsNeverEligible()
	{
		// the accept path asks this before pricing anything; a throw here would
		// take the CONTRACT down with the wager
		Assert.assertFalse(TaskService.anteEligible(null));
	}

	@Test
	public void aPartyFlaggedInsaneOfferIsStillEligible()
	{
		// the party path routes through the same predicate, so the party flag
		// must not accidentally disqualify a contract the party voted for
		TaskOffer partyOffer = new TaskOffer(TaskDifficulty.INSANE, "Goblin", 2, 10, 8, 250,
			null, false, true);
		Assert.assertTrue(TaskService.anteEligible(partyOffer));
	}

	// --- B. the purse floor ---

	@Test
	public void aPurseUnderTheFloorIsOfferedNoWager()
	{
		Assert.assertEquals(0, TaskService.anteStakeFor(0, 50));
		Assert.assertEquals(0, TaskService.anteStakeFor(Tuning.ANTE_MIN_PURSE_GC - 1, 50));
		Assert.assertEquals("a broke player must not be taxed for being broke", 0,
			TaskService.anteStakeFor(Tuning.ANTE_MIN_PURSE_GC - 1, Tuning.ANTE_MIN_PERCENT));
	}

	@Test
	public void exactlyTheFloorIsAlreadyEnough()
	{
		Assert.assertEquals(Tuning.ANTE_MIN_PURSE_GC * Tuning.ANTE_MIN_PERCENT / 100,
			TaskService.anteStakeFor(Tuning.ANTE_MIN_PURSE_GC, Tuning.ANTE_MIN_PERCENT));
	}

	@Test
	public void aNegativePurseCannotProduceANegativeStake()
	{
		// a corrupt save must not hand the player GC by "staking" a debt
		Assert.assertEquals(0, TaskService.anteStakeFor(-5000, 50));
	}

	// --- C. the percent band ---

	@Test
	public void theStakeIsThePercentOfThePurse()
	{
		Assert.assertEquals(100, TaskService.anteStakeFor(1000, 10));
		Assert.assertEquals(300, TaskService.anteStakeFor(1000, 30));
		Assert.assertEquals(500, TaskService.anteStakeFor(1000, 50));
	}

	@Test
	public void percentsOutsideTheBandAreClampedIntoIt()
	{
		Assert.assertEquals("below the band reads as the minimum",
			TaskService.anteStakeFor(1000, Tuning.ANTE_MIN_PERCENT),
			TaskService.anteStakeFor(1000, 1));
		Assert.assertEquals("above the band reads as the maximum",
			TaskService.anteStakeFor(1000, Tuning.ANTE_MAX_PERCENT),
			TaskService.anteStakeFor(1000, 9999));
	}

	@Test
	public void zeroPercentIsDisarmedRatherThanClampedUpToTheMinimum()
	{
		// the arming field's "no wager" value is 0, and it travels through this
		// same function on every accept — clamping it up would stake a player
		// who never armed anything
		Assert.assertEquals(0, TaskService.anteStakeFor(1_000_000, 0));
		Assert.assertEquals(0, TaskService.anteStakeFor(1_000_000, -20));
	}

	// --- D. the absolute cap ---

	@Test
	public void aRichPurseIsCappedInAbsoluteGc()
	{
		Assert.assertEquals(Tuning.ANTE_MAX_GC, TaskService.anteStakeFor(10_000_000, 50));
		Assert.assertEquals("one contract must never be an economy-sized swing",
			Tuning.ANTE_MAX_GC, TaskService.anteStakeFor(Long.MAX_VALUE / 100, 50));
	}

	@Test
	public void theCapBitesExactlyAtItsThreshold()
	{
		long atCap = Tuning.ANTE_MAX_GC * 100L / Tuning.ANTE_MAX_PERCENT;
		Assert.assertEquals(Tuning.ANTE_MAX_GC,
			TaskService.anteStakeFor(atCap, Tuning.ANTE_MAX_PERCENT));
		Assert.assertTrue("just under the threshold must still be under the cap",
			TaskService.anteStakeFor(atCap - 100, Tuning.ANTE_MAX_PERCENT) < Tuning.ANTE_MAX_GC);
	}

	// --- E. the invariant the escrow depends on ---

	@Test
	public void theStakeNeverExceedsThePurse()
	{
		// acceptInternal subtracts this straight off the balance inside a mutate;
		// a stake larger than the purse would sign a contract with negative GC
		for (long gc = 0; gc <= 20_000; gc += 137)
		{
			for (int percent = 0; percent <= 60; percent += 5)
			{
				int stake = TaskService.anteStakeFor(gc, percent);
				Assert.assertTrue("stake " + stake + " over purse " + gc, stake <= gc);
				Assert.assertTrue("negative stake at gc " + gc, stake >= 0);
			}
		}
	}

	@Test
	public void aFatterPurseNeverStakesLess()
	{
		int previous = 0;
		for (long gc = 0; gc <= 60_000; gc += 250)
		{
			int stake = TaskService.anteStakeFor(gc, Tuning.ANTE_MAX_PERCENT);
			Assert.assertTrue("stake fell from " + previous + " to " + stake + " at gc " + gc,
				stake >= previous);
			previous = stake;
		}
	}
}
