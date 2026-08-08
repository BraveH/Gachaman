package com.gachaman.party;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Ante's party rule, in isolation: UNANIMOUS consent of the FINAL roster.
 * Deliberately a stricter bar than the contract's own majority — a contract is
 * an activity the party does together, a stake is each member's own GC.
 *
 * Statics only; the surrounding vote plumbing needs a Client and a PartyService.
 * What this does NOT prove is convergence between clients: the host alone
 * evaluates this and broadcasts the verdict, and each client then re-checks its
 * own recorded consent before any GC moves.
 */
public class AnteUnanimityTest
{
	private static Map<Long, Boolean> consent(Object... pairs)
	{
		Map<Long, Boolean> map = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			map.put(((Number) pairs[i]).longValue(), (Boolean) pairs[i + 1]);
		}
		return map;
	}

	private static final List<Long> TRIO = Arrays.asList(1L, 2L, 3L);

	// --- A. unanimity ---

	@Test
	public void everyMemberSayingYesCarriesTheAnte()
	{
		Assert.assertTrue(PartyRollService.anteUnanimous(TRIO,
			consent(1L, true, 2L, true, 3L, true)));
	}

	@Test
	public void oneRefusalSinksItForEveryone()
	{
		Assert.assertFalse(PartyRollService.anteUnanimous(TRIO,
			consent(1L, true, 2L, false, 3L, true)));
		Assert.assertFalse("the last member counts as much as the first",
			PartyRollService.anteUnanimous(TRIO, consent(1L, true, 2L, true, 3L, false)));
	}

	@Test
	public void silenceIsARefusal()
	{
		// a member bound by a MAJORITY may never have clicked at all, and a
		// client too old to know the field sends nothing — neither consented to
		// stake anything, so neither can be counted as a yes
		Assert.assertFalse(PartyRollService.anteUnanimous(TRIO, consent(1L, true, 2L, true)));
		Assert.assertFalse(PartyRollService.anteUnanimous(TRIO, consent()));
	}

	@Test
	public void aSoloRosterStillNeedsItsOwnConsent()
	{
		Assert.assertTrue(PartyRollService.anteUnanimous(Arrays.asList(7L), consent(7L, true)));
		Assert.assertFalse(PartyRollService.anteUnanimous(Arrays.asList(7L), consent(7L, false)));
		Assert.assertFalse(PartyRollService.anteUnanimous(Arrays.asList(7L), consent(8L, true)));
	}

	// --- B. the roster is what counts, not the ballot box ---

	@Test
	public void consentFromSomeoneOffTheContractDoesNotCount()
	{
		// a plurality narrows the roster to the voters; a member who agreed to
		// the wager but is NOT on the resulting contract must neither carry it
		// nor block it
		Assert.assertTrue("an outsider's yes must not be needed",
			PartyRollService.anteUnanimous(Arrays.asList(1L, 2L),
				consent(1L, true, 2L, true, 9L, false)));
		Assert.assertFalse("an outsider's yes must not substitute for a member's",
			PartyRollService.anteUnanimous(Arrays.asList(1L, 2L),
				consent(1L, true, 9L, true)));
	}

	// --- C. degenerate input never stakes anything ---

	@Test
	public void anEmptyOrAbsentRosterCarriesNothing()
	{
		// the safe direction is always "no wager": broadcastResolve calls this
		// with whatever roster it just fixed, and a true here moves real GC
		Assert.assertFalse(PartyRollService.anteUnanimous(null, consent(1L, true)));
		Assert.assertFalse(PartyRollService.anteUnanimous(Arrays.asList(), consent(1L, true)));
		Assert.assertFalse(PartyRollService.anteUnanimous(TRIO, null));
	}
}
