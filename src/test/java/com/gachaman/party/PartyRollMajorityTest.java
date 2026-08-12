package com.gachaman.party;

import java.util.*;
import org.junit.*;

/**
 * The majority rule and its tiebreak, in isolation. Statics only —
 * constructing PartyRollService would need a Client and a PartyService, and
 * evaluateVotes/hostResolve read the tick counter and the party roster.
 *
 * What this does NOT prove: that two clients settle on the same contract.
 * They don't have to — only the HOST resolves, and it broadcasts the result
 * (PartyRollResolveMessage). Convergence is a property of that message, and
 * only a two-client run can check it.
 */
public class PartyRollMajorityTest
{
	// --- A. the threshold ---

	@Test
	public void thresholdIsAStrictMajorityOfTheParty()
	{
		Assert.assertEquals("a pair must agree — there is no majority of two", 2,
			PartyRollService.majorityThreshold(2));
		Assert.assertEquals(2, PartyRollService.majorityThreshold(3));
		Assert.assertEquals("half of four is a TIE, not a majority", 3,
			PartyRollService.majorityThreshold(4));
		Assert.assertEquals(3, PartyRollService.majorityThreshold(5));
		Assert.assertEquals(4, PartyRollService.majorityThreshold(6));
	}

	@Test
	public void onlyOneContractCanEverHoldTheThreshold()
	{
		// the property the local-vote display leans on: with a STRICT majority,
		// two offers reaching it at once is arithmetically impossible, so the
		// count shown next to a vote can never promise a contract that loses
		for (int party = 2; party <= 12; party++)
		{
			int threshold = PartyRollService.majorityThreshold(party);
			Assert.assertTrue("two contracts could both reach " + threshold + " of " + party,
				2 * threshold > party);
		}
	}

	// --- B. reading the tally ---

	@Test
	public void topTalliesFindsTheLead()
	{
		Assert.assertEquals(Arrays.asList(1),
			PartyRollService.topTallies(new int[]{1, 3, 2, 0}));
		Assert.assertEquals("a lead at the last index still wins", Arrays.asList(3),
			PartyRollService.topTallies(new int[]{1, 1, 1, 2}));
	}

	@Test
	public void topTalliesReportsEveryTiedLeader()
	{
		Assert.assertEquals(Arrays.asList(0, 2),
			PartyRollService.topTallies(new int[]{2, 1, 2, 0}));
		Assert.assertEquals("a four-way tie is still a tie", Arrays.asList(0, 1, 2, 3),
			PartyRollService.topTallies(new int[]{1, 1, 1, 1}));
	}

	@Test
	public void anUnvotedContractIsNeverALeader()
	{
		// zero votes is not a lead to be tied with — otherwise a vote nobody
		// cast could be drawn out of an all-zero board
		Assert.assertTrue(PartyRollService.topTallies(new int[]{0, 0, 0, 0}).isEmpty());
		Assert.assertTrue(PartyRollService.topTallies(new int[0]).isEmpty());
		Assert.assertEquals(Arrays.asList(2), PartyRollService.topTallies(new int[]{0, 0, 1, 0}));
	}

	// --- C. the draw ---

	@Test
	public void tiebreakPicksFromTheTiedContractsOnly()
	{
		List<Integer> tied = Arrays.asList(1, 3);
		for (long seed = 0; seed < 200; seed++)
		{
			int drawn = PartyRollService.tiebreakIndex(seed, seed * 7 + 1, tied);
			Assert.assertTrue("drew " + drawn + ", which nobody voted for", tied.contains(drawn));
		}
	}

	@Test
	public void tiebreakIsReproducibleForTheSameRoll()
	{
		// the draw is seeded from the roll's anchor seed and proposal id, never
		// from an ambient random: the same vote must always draw the same
		// contract, or a bug report can never be replayed
		List<Integer> tied = Arrays.asList(0, 1, 2, 3);
		int first = PartyRollService.tiebreakIndex(12345L, 6789L, tied);
		Assert.assertEquals(first, PartyRollService.tiebreakIndex(12345L, 6789L, tied));
		Assert.assertEquals(first, PartyRollService.tiebreakIndex(12345L, 6789L, tied));
	}

	@Test
	public void tiebreakIsNotConstantAcrossRolls()
	{
		// a fixed seed is not the same as a fixed OUTCOME — if every party's
		// tie drew index 0, the "random" tiebreak would just be "first offer"
		List<Integer> tied = Arrays.asList(0, 1, 2, 3);
		boolean varies = false;
		int baseline = PartyRollService.tiebreakIndex(1L, 1L, tied);
		for (long seed = 2; seed < 60 && !varies; seed++)
		{
			varies = PartyRollService.tiebreakIndex(seed, seed, tied) != baseline;
		}
		Assert.assertTrue("every roll drew the same contract", varies);
	}

	@Test
	public void tiebreakSurvivesAnEmptyLead()
	{
		// hostResolve guards this, but a helper that throws on an empty list is
		// a landmine for the next caller: -1 is the "nothing to sign" answer
		Assert.assertEquals(-1, PartyRollService.tiebreakIndex(1L, 2L, null));
		Assert.assertEquals(-1, PartyRollService.tiebreakIndex(1L, 2L, Arrays.asList()));
	}

	@Test
	public void aSingleTiedContractDrawsItself()
	{
		Assert.assertEquals(2, PartyRollService.tiebreakIndex(99L, 44L, Arrays.asList(2)));
	}
}
