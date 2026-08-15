package com.gachaman.service;

import java.lang.reflect.*;
import java.util.*;
import org.junit.*;

/**
 * Why {@code MAX_WEAPON_SAMPLES} and {@code MAX_RECENT_VERDICTS} are
 * belt-and-braces rather than working code — and why they are kept anyway.
 *
 * <p>A compression pass proposed deleting the weapon-sample cap as dead code, on
 * the argument that at most one judgement lands per tick (the animation path
 * refuses a second, and the XP fallback waits out XP_FALLBACK_QUIET_TICKS), so
 * the 40-tick TTL already bounds the deque to 41 entries and the 41-entry cap can
 * never bite. That argument is CORRECT, and this test pins the half of it that a
 * headless harness can actually reach: the age-based prune alone holds the deque
 * at TTL + 1 when samples arrive one per tick.
 *
 * <p>It is pinned rather than acted on. The cap's own comment says it exists for
 * the day that one-judgement-per-tick invariant breaks, which makes it a designed
 * backstop and not vestigial code — and the invariant it guards lives in two
 * other methods that a future edit could change without ever looking at this one.
 * Removing it would buy 46 tokens and cost the only thing standing between a
 * broken invariant and an unbounded deque on the kill path.
 *
 * <p>The sibling {@code MAX_RECENT_VERDICTS} is unreachable by exactly the same
 * reasoning over the 5-tick pardon window, and stays for exactly the same reason;
 * the two comments cross-reference each other, so they live or die together.
 */
public class WeaponSampleBoundTest
{
	/** The deque, read straight off the tracker — nothing exposes its size. */
	@SuppressWarnings("unchecked")
	private static Deque<StyleTracker.WeaponSample> samples(StyleTracker tracker) throws Exception
	{
		Field field = StyleTracker.class.getDeclaredField("weaponSamples");
		field.setAccessible(true);
		return (Deque<StyleTracker.WeaponSample>) field.get(tracker);
	}

	private static int constant(String name) throws Exception
	{
		Field field = StyleTracker.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	/**
	 * One sample per tick for far longer than the TTL: the age prune alone never
	 * lets the deque past TTL + 1, so the hard cap is never the thing that
	 * discards an entry.
	 */
	@Test
	public void theAgePruneAloneBoundsTheDeque() throws Exception
	{
		StyleTracker tracker = new StyleTracker(null, null);
		int ttl = constant("WEAPON_SAMPLE_TTL_TICKS");
		int cap = constant("MAX_WEAPON_SAMPLES");
		Assert.assertEquals("the cap is sized as TTL + 1; if that changed, re-derive"
			+ " the unreachability argument before trusting it", ttl + 1, cap);

		int highWater = 0;
		for (int tick = 0; tick < ttl * 5; tick++)
		{
			tracker.recordWeapon(tick, 6, 0);
			highWater = Math.max(highWater, samples(tracker).size());
		}
		Assert.assertEquals("one judgement per tick can never reach the hard cap —"
			+ " the TTL prune gets there first", ttl + 1, highWater);
		Assert.assertTrue("so the cap never bites", highWater <= cap);
	}

	/**
	 * The clear on LOGGED_IN is NOT hygiene-only, which is the other thing the
	 * compression pass proposed removing.
	 *
	 * <p>The argument for removing it was that the tick counter never resets, so
	 * stale samples fall out of every future window anyway. That holds for a kill
	 * fought after the hop — but a kill whose engagement window sits BEFORE it is
	 * exactly the case the clear changes: with the samples cleared such a kill
	 * finds nothing and pays no weapon bonus, and without the clear it can still
	 * match a sample from a session that has ended. So the clear has observable
	 * behaviour, and this pins the direction it picks.
	 */
	@Test
	public void aClearedCacheStrandsAPreLogoutKillRatherThanPayingIt() throws Exception
	{
		StyleTracker tracker = new StyleTracker(null, null);
		tracker.recordWeapon(10, 6, 0);
		Assert.assertNotNull("precondition: the sample is claimable before the hop",
			tracker.weaponAt(7, 10));

		samples(tracker).clear(); // what onGameStateChanged(LOGGED_IN) does
		Assert.assertNull("a kill carried across the hop must pay no weapon bonus rather"
			+ " than claim a sample from the session that ended", tracker.weaponAt(7, 10));
	}
}
