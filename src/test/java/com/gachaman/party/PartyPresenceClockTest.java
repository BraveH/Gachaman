package com.gachaman.party;

import com.gachaman.Tuning;
import org.junit.Assert;
import org.junit.Test;

/**
 * The presence broadcast's pure rules: when we speak, when we stop believing
 * someone else, and what counts as a change worth speaking about. Every
 * assertion pins a BOUNDARY rather than a constant's value, so retuning the
 * heartbeat does not rewrite the suite.
 */
public class PartyPresenceClockTest
{
	@Test
	public void firstBroadcastAlwaysSends()
	{
		Assert.assertTrue("a client that has never announced must not wait out a heartbeat",
			PartyPresenceService.shouldBroadcast(0, 0, "x", null));
	}

	@Test
	public void anyChangeBroadcastsImmediately()
	{
		Assert.assertTrue(PartyPresenceService.shouldBroadcast(101, 100, "a", "b"));
	}

	@Test
	public void unchangedPresenceWaitsForTheExactHeartbeat()
	{
		int last = 100;
		Assert.assertFalse(PartyPresenceService.shouldBroadcast(
			last + Tuning.PARTY_PRESENCE_HEARTBEAT_TICKS - 1, last, "a", "a"));
		Assert.assertTrue(PartyPresenceService.shouldBroadcast(
			last + Tuning.PARTY_PRESENCE_HEARTBEAT_TICKS, last, "a", "a"));
	}

	@Test
	public void aBackwardsTickCounterReAnnounces()
	{
		// a relog or world hop restarts getTickCount(): without this we would
		// look silent to the whole party for as long as the old count was high
		Assert.assertTrue(PartyPresenceService.shouldBroadcast(5, 4000, "a", "a"));
	}

	@Test
	public void signatureSeparatorPreventsAdjacentFieldCollision()
	{
		// the exact bug plain concatenation causes: a real change swallowed by
		// the heartbeat because two different lines flatten to one string
		Assert.assertNotEquals(
			PartyPresenceService.signature("MELEE", 70, "Goblin", 1, 12, false),
			PartyPresenceService.signature("MELEE", 70, "Goblin1", 1, 2, false));
	}

	@Test
	public void nullStyleAndNullTaskAreStableAndDistinct()
	{
		String none = PartyPresenceService.signature(null, 3, null, 0, 0, false);
		Assert.assertEquals(none, PartyPresenceService.signature(null, 3, null, 0, 0, false));
		Assert.assertNotEquals(none,
			PartyPresenceService.signature("MELEE", 3, null, 0, 0, false));
		Assert.assertNotEquals(none,
			PartyPresenceService.signature(null, 3, "Goblin", 0, 0, false));
		Assert.assertNotEquals(none,
			PartyPresenceService.signature(null, 3, null, 0, 0, true));
	}

	@Test
	public void stalenessBoundary()
	{
		int heard = 100;
		Assert.assertFalse(PartyPresenceService.isStale(
			heard + Tuning.PARTY_PRESENCE_STALE_TICKS - 1, heard));
		Assert.assertTrue(PartyPresenceService.isStale(
			heard + Tuning.PARTY_PRESENCE_STALE_TICKS, heard));
	}

	@Test
	public void aBackwardsClockForgivesTheSender()
	{
		// deliberate asymmetry with shouldBroadcast: OUR clock restarting is our
		// problem to re-announce, not grounds to grey out every other member
		Assert.assertFalse(PartyPresenceService.isStale(5, 4000));
	}

	@Test
	public void progressFractionNeverDividesByZeroAndClamps()
	{
		Assert.assertEquals(0, PartyPresenceService.progressFraction(3, 0), 0.0);
		Assert.assertEquals(0, PartyPresenceService.progressFraction(0, 10), 0.0);
		Assert.assertEquals(1, PartyPresenceService.progressFraction(20, 10), 0.0);
		Assert.assertEquals(0, PartyPresenceService.progressFraction(-4, 10), 0.0);
		Assert.assertEquals(0.5, PartyPresenceService.progressFraction(5, 10), 1e-9);
	}

	@Test
	public void clipBoundsARemoteName()
	{
		Assert.assertNull(PartyPresenceService.clip(null, 40));
		Assert.assertNull("whitespace is not a name", PartyPresenceService.clip("   ", 40));
		Assert.assertEquals("Goblin", PartyPresenceService.clip("Goblin", 40));
		Assert.assertEquals("Goblin", PartyPresenceService.clip("  Goblin  ", 40));

		StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			huge.append('x');
		}
		String clipped = PartyPresenceService.clip(huge.toString(), 40);
		Assert.assertNotNull(clipped);
		Assert.assertEquals("a hostile client must not be able to blow up the row layout",
			40, clipped.length());
	}
}
