package com.gachaman.ui.panel;

import com.gachaman.*;
import com.gachaman.model.*;
import org.junit.*;

/**
 * The honesty claim the Overview's Preferred Weapon block makes, pinned against
 * the real Tuning tables.
 *
 * <p>The claim is that the x1.5 is worth a very different amount on an EASY
 * contract than on an INSANE one, because it multiplies kill GC and kill GC is
 * a small slice of one and a large slice of the other. That is the whole reason
 * the panel prints a break-even instead of the multiplier alone — so if the
 * tables ever move far enough to make the two ends comparable, the prose has
 * stopped being true and this test is the thing that says so.
 *
 * <p>Both ends are computed from {@link Tuning#PER_KILL_GC},
 * {@link Tuning#COMPLETION_GC} and {@link TaskDifficulty}'s own kill band rather
 * than from copied literals: a test that hard-codes 4 and 400 would go on
 * passing after a rebalance that had already made the panel lie.
 */
public class OverviewWeaponWorthTest
{
	private static double slackPercent(TaskDifficulty difficulty, int kills)
	{
		return OverviewTab.killShareFraction(Tuning.PER_KILL_GC.get(difficulty), kills,
			Tuning.COMPLETION_GC.get(difficulty)) * (Tuning.WEAPON_BONUS_MULT - 1.0) * 100;
	}

	/** The middle of a difficulty's own kill band — the contract a player usually gets. */
	private static int typicalKills(TaskDifficulty difficulty)
	{
		return (difficulty.getMinKills() + difficulty.getMaxKills()) / 2;
	}

	/** The load-bearing comparison, on the contract each difficulty usually deals. */
	@Test
	public void theBonusIsWorthFarLessOnEasyThanOnInsane()
	{
		double easy = slackPercent(TaskDifficulty.EASY, typicalKills(TaskDifficulty.EASY));
		double insane = slackPercent(TaskDifficulty.INSANE, typicalKills(TaskDifficulty.INSANE));
		Assert.assertTrue("an easy contract's break-even should be single digits, was " + easy,
			easy < 10);
		Assert.assertTrue("an insane contract's break-even should be far larger, was " + insane,
			insane > 20);
		Assert.assertTrue("the two ends must stay far enough apart for the panel's prose"
			+ " to be worth printing: easy " + easy + " vs insane " + insane,
			insane > 2 * easy);
	}

	/**
	 * And the claim must not merely hold on average — it must not INVERT at the
	 * extremes, which is the pairing that would make the prose wrong for a real
	 * player: the longest easy contract (most kill GC, so the biggest share it
	 * can have) against the shortest insane one (the least).
	 */
	@Test
	public void theClaimSurvivesTheWorstPairingOfContractLengths()
	{
		double easy = slackPercent(TaskDifficulty.EASY, TaskDifficulty.EASY.getMaxKills());
		double insane = slackPercent(TaskDifficulty.INSANE, TaskDifficulty.INSANE.getMinKills());
		Assert.assertTrue("even the longest easy against the shortest insane must keep the"
			+ " difficulties in the same order: easy " + easy + " vs insane " + insane,
			insane > 1.5 * easy);
	}

	/**
	 * The break-even the panel prints, checked against its own derivation rather
	 * than against itself: running a contract entirely with the named category
	 * pays 1.5K + C where it would have paid K + C, so GC per hour breaks even
	 * exactly when the weapon is (1.5K + C) / (K + C) - 1 slower per kill.
	 */
	@Test
	public void theBreakEvenMatchesTheIncomeItIsDerivedFrom()
	{
		int perKill = 16;
		int kills = 60;
		int completion = 1900;
		double k = (double) perKill * kills;
		double expected = ((Tuning.WEAPON_BONUS_MULT * k + completion) / (k + completion)) - 1;
		double printed = OverviewTab.killShareFraction(perKill, kills, completion)
			* (Tuning.WEAPON_BONUS_MULT - 1.0);
		Assert.assertEquals(expected, printed, 1e-12);
	}

	@Test
	public void aContractWithNoKillIncomeIsAllCompletionAndNoShare()
	{
		// a Redemption contract: perKillGc 0, so the weapon multiplies nothing
		Assert.assertEquals(0, OverviewTab.killShareFraction(0, 40, 900), 0);
		// and no contract at all divides by nothing rather than by zero
		Assert.assertEquals(0, OverviewTab.killShareFraction(0, 0, 0), 0);
	}

	@Test
	public void everyReasonToShowNothingSaysNoBonusAvailable()
	{
		String noContract = OverviewTab.noBonusReason(null);
		String redemption = OverviewTab.noBonusReason(ActiveTask.builder()
			.difficulty(TaskDifficulty.HARD).monsterName("Anything")
			.killsRequired(20).perKillGc(0).completionGc(1900).redemption(true).build());
		Assert.assertNotNull(noContract);
		Assert.assertNotNull(redemption);
		// the owner's wording, and never anything that reads as the player's fault
		Assert.assertTrue(noContract, noContract.contains("no bonus available"));
		Assert.assertTrue(redemption, redemption.contains("no bonus available"));
		Assert.assertNotEquals("the two cases have different fixes and must be told apart",
			noContract, redemption);
	}

	@Test
	public void aPayingContractHasNoReasonToShowNothing()
	{
		Assert.assertNull(OverviewTab.noBonusReason(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY).monsterName("Anything")
			.killsRequired(20).perKillGc(4).completionGc(400).build()));
	}
}
