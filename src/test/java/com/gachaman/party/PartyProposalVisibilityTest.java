package com.gachaman.party;

import org.junit.Assert;
import org.junit.Test;

/**
 * The one-party-at-a-time rule.
 *
 * A player can be offered several party rolls at once — several members of one
 * RuneLite party can each propose — but can only ever be in one. The moment
 * they join one, or host one, or sign a contract, the rest stop being offers
 * and start being noise they could accidentally click.
 *
 * This is the whole of that rule, isolated from the Client so it can be stated
 * as a table rather than reasoned about through a running party.
 */
public class PartyProposalVisibilityTest
{
	@Test
	public void anUncommittedPlayerIsShownOffers()
	{
		Assert.assertFalse(PartyRollService.spokenFor(false, false, false));
	}

	@Test
	public void joiningAProposalHidesTheRest()
	{
		// proposalLive is set by joining OR by proposing, so this one flag covers
		// both "I answered someone" and "I am the host" — a host must not be
		// shopping through other people's rolls while their own collects answers
		Assert.assertTrue(PartyRollService.spokenFor(true, false, false));
	}

	@Test
	public void aRunningVoteHidesTheRest()
	{
		// between the roll and the contract there is no proposal live, but the
		// player is very much committed — offers must not reappear in the gap
		Assert.assertTrue(PartyRollService.spokenFor(false, true, false));
	}

	@Test
	public void anActiveContractHidesTheRest()
	{
		Assert.assertTrue(PartyRollService.spokenFor(false, false, true));
	}

	@Test
	public void anyCommitmentIsEnoughOnItsOwn()
	{
		// no combination un-commits a player: the rule is an OR, and a test that
		// only ever sets one flag would pass just as well against an AND
		for (int mask = 1; mask < 8; mask++)
		{
			boolean proposal = (mask & 1) != 0;
			boolean voting = (mask & 2) != 0;
			boolean task = (mask & 4) != 0;
			Assert.assertTrue("mask " + mask,
				PartyRollService.spokenFor(proposal, voting, task));
		}
	}
}
