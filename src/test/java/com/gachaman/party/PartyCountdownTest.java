package com.gachaman.party;

import org.junit.*;

/**
 * The tick-to-seconds conversion behind every countdown the party UI shows.
 *
 * One function feeds the proposal cards, the "party roll pending" line and the
 * vote clock, so a wrong constant here is wrong on every screen at once. Game
 * ticks are 0.6s: 5 ticks make 3 seconds.
 */
public class PartyCountdownTest
{
	@Test
	public void ticksConvertAtSixHundredMilliseconds()
	{
		Assert.assertEquals(0, PartyRollService.ticksToSeconds(0));
		Assert.assertEquals(3, PartyRollService.ticksToSeconds(5));
		Assert.assertEquals(6, PartyRollService.ticksToSeconds(10));
		Assert.assertEquals(60, PartyRollService.ticksToSeconds(100));
	}

	@Test
	public void aPartTickRoundsDownRatherThanUp()
	{
		// a countdown that rounds up says "1s left" when there is none, and the
		// deadline is real: the vote stops counting
		Assert.assertEquals(0, PartyRollService.ticksToSeconds(1));
		Assert.assertEquals(1, PartyRollService.ticksToSeconds(2));
		Assert.assertEquals(2, PartyRollService.ticksToSeconds(4));
	}

	@Test
	public void anExpiredClockNeverReadsNegative()
	{
		// the caller subtracts a tick count that can overshoot the deadline
		// between the sweep and the repaint; "-2s left" is not a countdown
		Assert.assertEquals(0, PartyRollService.ticksToSeconds(-1));
		Assert.assertEquals(0, PartyRollService.ticksToSeconds(-100));
	}

	@Test
	public void theCountdownNeverRunsBackwards()
	{
		int previous = Integer.MAX_VALUE;
		for (int ticks = 200; ticks >= 0; ticks--)
		{
			int seconds = PartyRollService.ticksToSeconds(ticks);
			Assert.assertTrue("rose at " + ticks, seconds <= previous);
			previous = seconds;
		}
		Assert.assertEquals(0, previous);
	}
}
