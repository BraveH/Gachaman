package com.gachaman.party;

import com.gachaman.*;
import java.util.*;
import org.junit.*;

/**
 * The rules that let a shared contract survive a client restart.
 *
 * A restart destroys the whole party session — member ids are drawn fresh every
 * time RuneLite starts — while the contract itself comes back off disk still
 * flagged as shared. The contract is resurrected from its persisted proposal id
 * and its participant set is rebuilt by whoever calls in quoting that id, which
 * leaves the carry-clause watchdog reading an EMPTY participant set for a party
 * that may be perfectly alive. These are the two pure decisions in that path.
 */
public class PartyResumeTest
{
	private static Set<Long> roster(long... ids)
	{
		Set<Long> set = new LinkedHashSet<>();
		for (long id : ids)
		{
			set.add(id);
		}
		return set;
	}

	// =====================================================================
	// everyoneGone
	// =====================================================================

	private static final int GONE = Tuning.PARTY_DEPART_GRACE_TICKS + 1;

	@Test
	public void aPartyStillTogetherIsNotGone()
	{
		Assert.assertFalse(PartyRollService.everyoneGone(true, true, 0, false, 0, 3));
	}

	@Test
	public void knownPartnersWhoStayGoneFire()
	{
		Assert.assertTrue(PartyRollService.everyoneGone(true, false, GONE, false, 0, 1));
	}

	@Test
	public void aPartnerRestartingTheirClientIsNotWrittenOffAtOnce()
	{
		// the other half of the resume defect. Closing a client removes you from the
		// roster IMMEDIATELY, so with no grace a party of two converts about fifteen
		// seconds into a partner's restart — and the resurrection on their side then
		// comes back to a contract that was already settled without them. The feature
		// would be inert for the commonest party size.
		Assert.assertFalse(PartyRollService.everyoneGone(true, false, 0, false, 0, 1));
		Assert.assertFalse("still within the relaunch window",
			PartyRollService.everyoneGone(true, false,
				Tuning.PARTY_DEPART_GRACE_TICKS, false, 0, 1));
	}

	@Test
	public void aPartnerWhoComesBackInsideTheGraceCancelsIt()
	{
		// what the caller's latch buys: presence outranks any elapsed time
		Assert.assertFalse(PartyRollService.everyoneGone(true, true, GONE, false, 0, 2));
	}

	@Test
	public void theDepartureGraceIsShorterThanTheIdleTimer()
	{
		// a departed party must settle on the "your party has left" branch, not sit
		// out the full idle timer and settle on the "gone quiet" one
		Assert.assertTrue(Tuning.PARTY_DEPART_GRACE_TICKS < Tuning.PARTY_IDLE_TICKS);
	}

	@Test
	public void aResumedContractDoesNotConvertOnItsFirstSweep()
	{
		// the defect this rule exists to prevent. A resurrected contract knows
		// nobody, because ids do not survive a restart — reading that as "everyone
		// left" would drop a live party to the carry multiplier within 25 ticks of
		// logging back in, with nobody having gone anywhere.
		Assert.assertFalse("a populated party we simply have not heard from yet",
			PartyRollService.everyoneGone(false, false, 0, true, 0, 3));
		Assert.assertFalse("and it must not start converting once the grace lapses either",
			PartyRollService.everyoneGone(false, false, 0, true,
				Tuning.PARTY_RESYNC_TICKS * 100, 3));
	}

	@Test
	public void aResumedContractAloneInAnEmptyPartyWaitsOutTheResyncGrace()
	{
		// RuneLite rejoins the previous party ASYNCHRONOUSLY, so the roster is
		// usually still empty at the moment the save loads. Converting there would
		// punish every relog for the client's own startup ordering.
		Assert.assertFalse(PartyRollService.everyoneGone(false, false, 0, true, 0, 0));
		Assert.assertFalse(PartyRollService.everyoneGone(false, false, 0, true,
			Tuning.PARTY_RESYNC_TICKS, 1));
	}

	@Test
	public void aResumedContractStillAloneAfterTheGraceConverts()
	{
		// closes the relog exploit: without this branch nobody can ever call in from
		// an empty roster, so parking on the idle timer would hand a dead party's
		// contract a fresh ten-minute window on every restart, indefinitely
		Assert.assertTrue(PartyRollService.everyoneGone(false, false, 0, true,
			Tuning.PARTY_RESYNC_TICKS + 1, 1));
		Assert.assertTrue("no party at all is the same case",
			PartyRollService.everyoneGone(false, false, 0, true,
				Tuning.PARTY_RESYNC_TICKS + 1, 0));
	}

	@Test
	public void theResyncGraceIsShorterThanTheIdleTimer()
	{
		// otherwise the branch it guards is unreachable — the idle clause would
		// always settle a silent contract first and the party-of-one case would
		// never be distinguished from any other quiet one
		Assert.assertTrue(Tuning.PARTY_RESYNC_TICKS < Tuning.PARTY_IDLE_TICKS);
	}

	@Test
	public void aSessionThatKnowsItsOwnRosterIgnoresTheResumeGraceEntirely()
	{
		// once anyone has called in, knewOthers is true and the resume arguments
		// stop being consulted: a party that reassembles and THEN disbands settles
		// on the ordinary rule, with no second grace period bought by the restart
		Assert.assertTrue(PartyRollService.everyoneGone(true, false, GONE, true, 0, 1));
		Assert.assertFalse(PartyRollService.everyoneGone(true, true, GONE, true, 0, 3));
	}

	@Test
	public void aSessionThatNeverResumedAndKnowsNobodyParksOnTheIdleTimer()
	{
		// not a state the roll can produce — a signed contract always records its
		// own participants — but the fallback must be the forgiving one, since an
		// unexpected empty set is a bug in US and should not cost the player GC
		Assert.assertFalse(PartyRollService.everyoneGone(false, false, 0, false, 0, 0));
		Assert.assertFalse(PartyRollService.everyoneGone(false, false, GONE, false, 99999, 1));
	}

	@Test
	public void aRewoundClockNeverConvertsEarly()
	{
		// a relog restarts getTickCount(), so the caller re-anchors its stamps and
		// feeds 0 rather than a negative elapsed. Pin that a negative would be safe
		// anyway — the failure direction has to be "waits too long", never "converts
		// a contract the player still has a party for".
		Assert.assertFalse(PartyRollService.everyoneGone(true, false, -5000, false, 0, 1));
		Assert.assertFalse(PartyRollService.everyoneGone(false, false, 0, true, -5000, 0));
	}

	// =====================================================================
	// dropDepartedKills
	// =====================================================================

	@Test
	public void aReturningMemberIsNotCountedTwice()
	{
		// the exact double-count: 12 kills banked under the id they held before
		// their client restarted, then the same 12 re-reported under the new one.
		// Summed blind that is 24 kills of shared quota for 12 kills of work.
		Map<Long, Integer> partyKills = new HashMap<>();
		partyKills.put(111L, 12); // pre-restart id, no longer in the party
		partyKills.put(333L, 5);  // a partner who never left

		PartyRollService.dropDepartedKills(partyKills, roster(999L, 222L, 333L));
		partyKills.merge(222L, 12, Math::max); // the same player, new id

		Assert.assertFalse(partyKills.containsKey(111L));
		int othersTotal = 0;
		for (int kills : partyKills.values())
		{
			othersTotal += kills;
		}
		Assert.assertEquals("12 kills of work must buy 12 kills of shared quota",
			17, othersTotal);
	}

	@Test
	public void pruningIsANoOpWhenNobodyHasLeft()
	{
		Map<Long, Integer> partyKills = new HashMap<>();
		partyKills.put(111L, 12);
		partyKills.put(222L, 3);
		PartyRollService.dropDepartedKills(partyKills, roster(999L, 111L, 222L));
		Assert.assertEquals(2, partyKills.size());
		Assert.assertEquals(Integer.valueOf(12), partyKills.get(111L));
	}

	@Test
	public void pruningToleratesEmptyInputs()
	{
		Map<Long, Integer> empty = new HashMap<>();
		PartyRollService.dropDepartedKills(empty, new HashSet<>());
		Assert.assertTrue(empty.isEmpty());

		Map<Long, Integer> all = new HashMap<>();
		all.put(111L, 12);
		// only ever called with a roster containing the member being admitted, so
		// this cannot happen in practice — pin that it degrades to "trust nobody"
		// rather than throwing, since the total can only fall and syncPartyKills
		// refuses to move its high-water mark down
		PartyRollService.dropDepartedKills(all, new HashSet<>());
		Assert.assertTrue(all.isEmpty());
	}

	@Test
	public void pruningSurvivesAnImmutableRoster()
	{
		// rosterIds() hands back a set we do not own; the prune must not write to it
		Map<Long, Integer> partyKills = new HashMap<>();
		partyKills.put(111L, 12);
		partyKills.put(222L, 3);
		Set<Long> immutable = java.util.Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList(222L)));
		PartyRollService.dropDepartedKills(partyKills, immutable);
		Assert.assertEquals(1, partyKills.size());
		Assert.assertEquals(Integer.valueOf(3), partyKills.get(222L));
	}
}
