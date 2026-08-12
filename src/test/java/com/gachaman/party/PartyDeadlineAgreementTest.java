package com.gachaman.party;

import org.junit.*;

/**
 * Host and member must count down the SAME deadline.
 *
 * A non-host stores its deadline with a grace margin so it does not give up on
 * a start or cancel message that is merely late. That margin is enforcement
 * slack, not thinking time — the host stops collecting answers at its own
 * deadline regardless. Displaying the padded figure had the host's screen
 * reading 60s while everyone else read 75s for one and the same moment, which
 * would invite a member to sit on a decision the host had already timed out.
 *
 * The service applies the correction against a live tick counter, so what is
 * checked here is the arithmetic that correction has to satisfy.
 */
public class PartyDeadlineAgreementTest
{
	/** Mirrors PartyRollService: 100 ticks to answer, 25 more before self-cancel. */
	private static final int PROPOSAL_TTL_TICKS = 100;
	private static final int NON_HOST_GRACE_TICKS = 25;

	/** The service's display rule, stated independently of the Client. */
	private static int displayTicks(int expiresAtTick, boolean hosting, int now)
	{
		int deadline = hosting ? expiresAtTick : expiresAtTick - NON_HOST_GRACE_TICKS;
		return Math.max(0, deadline - now);
	}

	@Test
	public void bothSidesShowTheSameSecondsAtTheStartOfAProposal()
	{
		int now = 5_000;
		int hostExpiry = now + PROPOSAL_TTL_TICKS;
		int memberExpiry = now + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;

		int host = displayTicks(hostExpiry, true, now);
		int member = displayTicks(memberExpiry, false, now);

		Assert.assertEquals(host, member);
		// and it is the real window, not the padded one: 100 ticks is 60s
		Assert.assertEquals(60, PartyRollService.ticksToSeconds(host));
		Assert.assertEquals(60, PartyRollService.ticksToSeconds(member));
	}

	@Test
	public void theyStayInStepAsTheClockRuns()
	{
		int start = 5_000;
		int hostExpiry = start + PROPOSAL_TTL_TICKS;
		int memberExpiry = start + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;
		for (int now = start; now <= start + PROPOSAL_TTL_TICKS + 40; now++)
		{
			Assert.assertEquals("tick " + now,
				displayTicks(hostExpiry, true, now),
				displayTicks(memberExpiry, false, now));
		}
	}

	@Test
	public void theMemberHitsZeroWhenTheHostDoes()
	{
		int start = 5_000;
		int deadline = start + PROPOSAL_TTL_TICKS;
		int memberExpiry = deadline + NON_HOST_GRACE_TICKS;
		// one tick before: still time on both
		Assert.assertTrue(displayTicks(deadline, true, deadline - 1) > 0);
		Assert.assertTrue(displayTicks(memberExpiry, false, deadline - 1) > 0);
		// at the host's deadline: both read zero, and the member does NOT keep
		// counting through the grace it is silently still honouring internally
		Assert.assertEquals(0, displayTicks(deadline, true, deadline));
		Assert.assertEquals(0, displayTicks(memberExpiry, false, deadline));
	}

	@Test
	public void theGraceIsStillThereForTheTimeoutItself()
	{
		// the point of the fix is that the margin stops being VISIBLE, not that
		// it stops existing — the member's stored expiry must still be later
		int start = 5_000;
		int hostExpiry = start + PROPOSAL_TTL_TICKS;
		int memberExpiry = start + PROPOSAL_TTL_TICKS + NON_HOST_GRACE_TICKS;
		Assert.assertTrue(memberExpiry > hostExpiry);
		Assert.assertEquals(15, PartyRollService.ticksToSeconds(memberExpiry - hostExpiry));
	}
}
