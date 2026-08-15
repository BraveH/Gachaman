package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import org.junit.*;

/**
 * Pins the claim {@link Tuning#KILL_DIFF_CAP}'s own javadoc makes: that dropping
 * the punching-up ceiling from 2.5 to 1.75 is "a literal no-op for every SOLO
 * kill".
 *
 * <p>This class is named in that javadoc and did not exist until the weapon-type
 * commission landed, so the constant carried a citation to nothing. It is written
 * now because the claim is load-bearing in two directions: it is the reason the
 * owner judged the drop safe for solo play, and it is the reason the drop bites
 * exactly where it was aimed — the party carry.
 *
 * <p><b>Why the cap cannot bind solo.</b> The contract generator never deals a
 * monster above {@code floor(playerCb * difficulty.getCbCapFraction())}, and the
 * largest fraction is INSANE's 1.35. Feed ratio 1.35 through the curve:
 *
 * <pre>
 *   1 + 0.1 + 0.35 * 1.5 + 0.35^2 * 0.75  =  1.716875
 * </pre>
 *
 * which is under 1.75 with 0.033 to spare. The floor in the cap expression only
 * ever pushes the real ratio further below 1.35, so the headroom is a minimum
 * rather than a typical case.
 *
 * <p><b>Why this is a range test and not one arithmetic check.</b> The margin is
 * only 0.033. A retune of KILL_RATIO_LINEAR, KILL_RATIO_QUAD or
 * KILL_DIFF_EQUAL_BONUS could close it without anybody touching KILL_DIFF_CAP
 * itself, and the failure would be silent — solo players would quietly start
 * clamping. Walking every combat level the game has against every difficulty
 * catches that whoever causes it.
 */
public class KillDiffCapTest
{
	/**
	 * The highest monster combat level the generator can deal to this player at
	 * this difficulty, mirroring {@code TaskGenerator.eligibleMonsters}' cap
	 * expression exactly. Duplicated rather than called because that method needs
	 * a monster pool and this test is about the arithmetic, not the table.
	 */
	private static int hardestMonsterFor(int playerCb, TaskDifficulty difficulty)
	{
		return (int) Math.max(2, Math.floor(playerCb * difficulty.getCbCapFraction()));
	}

	@Test
	public void theCapIsANoOpForEverySoloContractTheGeneratorCanDeal()
	{
		// Combat 3 is a fresh account off Tutorial Island; 126 is the maximum.
		for (int playerCb = 3; playerCb <= 126; playerCb++)
		{
			for (TaskDifficulty difficulty : TaskDifficulty.values())
			{
				int npcCb = hardestMonsterFor(playerCb, difficulty);
				double mult = Tuning.killCbMultiplier(playerCb, npcCb);
				Assert.assertTrue(
					"the cap must not bind solo: cb " + playerCb + " vs " + difficulty
						+ " monster cb " + npcCb + " paid " + mult,
					mult < Tuning.KILL_DIFF_CAP);
			}
		}
	}

	/**
	 * The no-op claim is only interesting if the OLD cap was also a no-op over the
	 * same range — otherwise the drop would have changed solo payouts and the
	 * javadoc would be wrong. Pins that nothing solo reaches even 1.75, which is
	 * what makes "2.5 -> 1.75 changed nothing" true rather than merely untested.
	 */
	@Test
	public void theWorstSoloPairingLandsJustUnderTheCapWithKnownHeadroom()
	{
		// INSANE at a high level is the tightest the ratio ever gets: floor()
		// rounding matters least when playerCb is large.
		int playerCb = 100;
		int npcCb = hardestMonsterFor(playerCb, TaskDifficulty.INSANE);
		Assert.assertEquals("INSANE caps at 1.35x the player's level", 135, npcCb);

		double mult = Tuning.killCbMultiplier(playerCb, npcCb);
		Assert.assertEquals("the curve's value at ratio 1.35, as quoted in Tuning's javadoc",
			1.716875, mult, 1e-9);
		Assert.assertTrue("and it must sit under the cap", mult < Tuning.KILL_DIFF_CAP);
	}

	/**
	 * The other half of the owner's reasoning: the drop is NOT a no-op for the
	 * path it was aimed at. A cb-30 member carried in a cb-90 party fights a
	 * monster sized to the party average, so the award — which reads the LOCAL
	 * player's level — sees a ratio the solo generator could never produce.
	 *
	 * <p>Without this the test class would prove only that the cap is harmless,
	 * and a future edit could raise it back to 2.5 with every assertion above
	 * still green.
	 */
	@Test
	public void theCapDoesBindOnAPartyCarry()
	{
		// Party average cb 90, INSANE: monsters up to cb 121. The carried
		// member is cb 30, so the ratio the award sees is ~4.0.
		int carriedCb = 30;
		int partySizedNpcCb = hardestMonsterFor(90, TaskDifficulty.INSANE);

		Assert.assertTrue("the carry must exceed any solo ratio",
			(double) partySizedNpcCb / carriedCb > TaskDifficulty.INSANE.getCbCapFraction());
		Assert.assertEquals("and it must clamp to the cap",
			Tuning.KILL_DIFF_CAP, Tuning.killCbMultiplier(carriedCb, partySizedNpcCb), 1e-9);
	}

	/**
	 * The weapon bonus multiplies the whole award, so it compounds with whatever
	 * the level term paid. This pins the true worst case a single kill can reach,
	 * which is the number {@link Tuning#WEAPON_BONUS_MULT}'s neighbours in
	 * TaskService now quote: the cap, times the bonus.
	 */
	@Test
	public void theWeaponBonusCompoundsOnTopOfTheCapRatherThanBeingSwallowedByIt()
	{
		double clamped = Tuning.killCbMultiplier(30, hardestMonsterFor(90, TaskDifficulty.INSANE));
		Assert.assertEquals(Tuning.KILL_DIFF_CAP, clamped, 1e-9);
		Assert.assertEquals("1.75 x 1.5 — the ceiling the level term and the weapon can reach together",
			2.625, clamped * Tuning.WEAPON_BONUS_MULT, 1e-9);
	}
}
