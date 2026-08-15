package com.gachaman.service;

import org.junit.*;

/**
 * The weapon sample cache: what StyleTracker remembers about the weapon in hand
 * at each judged attack, and which of those samples a kill is allowed to claim.
 *
 * <p>This is the half of the Preferred Weapon that has to be right for the bonus
 * to be honest at all. A kill reaches the award code 2-30 ticks after the death
 * (KillTracker holds it for the loot oracle), so the award cannot read a varbit
 * — it reads back a stamped sample instead, and every rule about WHICH sample it
 * may read lives here.
 *
 * <p>Headless: {@code recordWeapon} is the package-private seam that takes the
 * two varbit values as arguments, exactly so the retention rule can be exercised
 * without a live Client, the same arrangement {@code resolve} and
 * {@code shouldPardon} already use in that file.
 */
public class WeaponSampleTest
{
	/** Arbitrary; the category int is opaque to everything under test here. */
	private static final int SCIMITAR = 6;
	private static final int BOW = 3;

	private StyleTracker tracker()
	{
		return new StyleTracker(null, null);
	}

	@Test
	public void aSampleInsideTheKillWindowIsFound()
	{
		StyleTracker tracker = tracker();
		tracker.recordWeapon(10, SCIMITAR, 0);
		StyleTracker.WeaponSample sample = tracker.weaponAt(7, 10);
		Assert.assertNotNull(sample);
		Assert.assertEquals(10, sample.getTick());
		Assert.assertEquals(SCIMITAR, sample.getCategory());
		Assert.assertEquals(0, sample.getComMode());
	}

	@Test
	public void nothingJudgedInsideTheWindowMeansNoSample()
	{
		// the thrall kill / off-screen damage case: the player never swung inside
		// this kill's own engagement, so there is nothing to claim and the award
		// must not inherit whatever the last fight was fought with
		StyleTracker tracker = tracker();
		tracker.recordWeapon(3, SCIMITAR, 0);
		Assert.assertNull(tracker.weaponAt(7, 10));
		Assert.assertNull(tracker().weaponAt(7, 10));
	}

	@Test
	public void aSampleTakenAfterTheDeathIsNotClaimable()
	{
		// THE loot-oracle swap, and the regression this whole mechanism exists to
		// stop: the monster died at tick 10, the player swapped to the preferred
		// weapon and hit the next target at 11, and onKill only runs at 12+. A
		// bonus paid off that sample would be a bonus the finished fight never
		// earned.
		StyleTracker tracker = tracker();
		tracker.recordWeapon(11, SCIMITAR, 0);
		Assert.assertNull(tracker.weaponAt(7, 10));
	}

	@Test
	public void theNewestSampleInTheWindowWins()
	{
		// the killing blow is the last attack of the engagement, so a player who
		// swings the named weapon for the final hit has genuinely landed the kill
		// with it
		StyleTracker tracker = tracker();
		tracker.recordWeapon(7, BOW, 0);
		tracker.recordWeapon(9, SCIMITAR, 0);
		Assert.assertEquals(SCIMITAR, tracker.weaponAt(7, 10).getCategory());
		// ...and narrowing the window to before that swap finds the earlier one,
		// so "newest" really is newest-in-window and not simply newest
		Assert.assertEquals(BOW, tracker.weaponAt(7, 8).getCategory());
	}

	@Test
	public void aSampleSurvivesTheWholeLootOracleWindow()
	{
		// The property the TTL is sized for. KillTracker emits a kill up to
		// PENDING_TIMEOUT_TICKS (30) after the death, and the player keeps
		// attacking the next monster all the way through that gap — one judgement
		// per tick, every tick. If the cache could not carry the killing blow
		// across it, the bonus would fail to pay on the ordinary case (fighting
		// continuously) and pay only when the player stopped, which is backwards.
		StyleTracker tracker = tracker();
		tracker.recordWeapon(10, SCIMITAR, 0);
		for (int tick = 11; tick <= 40; tick++)
		{
			tracker.recordWeapon(tick, BOW, 0);
		}
		StyleTracker.WeaponSample sample = tracker.weaponAt(7, 10);
		Assert.assertNotNull("the killing blow must outlive the loot-oracle window", sample);
		Assert.assertEquals(SCIMITAR, sample.getCategory());
	}

	@Test
	public void ancientSamplesAreEventuallyPruned()
	{
		// bounded, so a long session cannot grow it: past the TTL the old sample is
		// gone, which costs nothing because no kill can still be waiting on it
		StyleTracker tracker = tracker();
		tracker.recordWeapon(10, SCIMITAR, 0);
		for (int tick = 11; tick <= 200; tick++)
		{
			tracker.recordWeapon(tick, BOW, 0);
		}
		Assert.assertNull(tracker.weaponAt(7, 10));
		Assert.assertEquals(BOW, tracker.weaponAt(199, 200).getCategory());
	}

	@Test
	public void comModeIsCarriedAlongsideTheCategory()
	{
		// the autocast slot is the taxonomy's one pseudo-type: it is not a weapon
		// category at all, so the pair has to be sampled together or a spell-cast
		// preference could never be satisfied from a stamp
		StyleTracker tracker = tracker();
		tracker.recordWeapon(10, 0, 4);
		Assert.assertEquals(4, tracker.weaponAt(10, 10).getComMode());
	}

	@Test
	public void aWindowThatEndsBeforeItStartsClaimsNothing()
	{
		// nonsense in, nothing out — never an exception on the kill path
		StyleTracker tracker = tracker();
		tracker.recordWeapon(10, SCIMITAR, 0);
		Assert.assertNull(tracker.weaponAt(12, 8));
	}
}
