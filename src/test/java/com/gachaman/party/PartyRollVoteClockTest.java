package com.gachaman.party;

import org.junit.*;

/**
 * The vote shot clock's deadline test, in isolation. Statics only —
 * constructing PartyRollService would need a Client and a PartyService, and
 * everything around this predicate (voteLocal, evaluateVotes, cancelVoting,
 * onGameTick) reads client.getTickCount() or the party roster.
 *
 * Note what this does NOT prove: convergence. Two clients agreeing on when a
 * tick counts as expired says nothing about them agreeing on the DEADLINE —
 * that comes from the host owning the clock and broadcasting the cancel, and
 * only a two-client run can check it.
 */
public class PartyRollVoteClockTest
{
	@Test
	public void voteExpiryBoundary()
	{
		Assert.assertFalse("one tick short is still live",
			PartyRollService.voteExpired(199, 200));
		Assert.assertTrue("the deadline tick itself expires",
			PartyRollService.voteExpired(200, 200));
		Assert.assertTrue("and every tick after it",
			PartyRollService.voteExpired(201, 200));
	}

	@Test
	public void aMissedDeadlineNeverReadsAsLive()
	{
		// the watchdog can be starved (paused client, dropped ticks), so the
		// predicate has to hold for any lateness, not just the exact tick
		Assert.assertTrue(PartyRollService.voteExpired(5000, 200));
	}
}
