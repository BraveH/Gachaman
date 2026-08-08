package com.gachaman.party;

import com.gachaman.Tuning;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

/**
 * Party contracts are sized to the party's AVERAGE combat level (its fighting
 * weight) rather than its lowest. Statics only — executeRoll needs a Client, a
 * PartyService and a live proposal, so the rule is extracted and tested here.
 *
 * What this does NOT prove: that two clients roll the same board. Only a
 * two-client run can check that, as PartyRollMajorityTest already says. What
 * it DOES pin is the property the cross-client claim rests on — the sizing
 * input is a pure, order-independent function of the TRANSMITTED stances, so
 * two clients feeding generateOffers the same stances feed it the same cb. It
 * is never recomputed from local party membership.
 */
public class PartyFightingWeightTest
{
	private final Gson gson = new Gson();

	// --- A. the weight itself ---

	@Test
	public void fightingWeightIsTheFloorOfTheMean()
	{
		Assert.assertEquals("a level 40 in a party of 90s sizes the board at 77", 77,
			PartyRollService.fightingWeight(Arrays.asList(40, 90, 90, 90)));
		Assert.assertEquals(50, PartyRollService.fightingWeight(Arrays.asList(50, 50)));
		Assert.assertEquals(65, PartyRollService.fightingWeight(Arrays.asList(40, 90)));
	}

	@Test
	public void fightingWeightFloorsAndNeverRounds()
	{
		// pins integer division. A later "tidy-up" to Math.round would size a
		// pair ABOVE its own average, which is the one direction the weakest
		// member can never absorb
		Assert.assertEquals(3, PartyRollService.fightingWeight(Arrays.asList(3, 4)));
		Assert.assertEquals(10, PartyRollService.fightingWeight(Arrays.asList(10, 11)));
	}

	@Test
	public void aSoloLevelIsItsOwnWeight()
	{
		// a one-member roster must be byte-identical to the old lowest-level
		// rule, so an executeRoll that ever runs with one agreed member is
		// unchanged by this feature
		Assert.assertEquals(70, PartyRollService.fightingWeight(Collections.singletonList(70)));
	}

	@Test
	public void orderDoesNotChangeTheWeight()
	{
		// the roster is host-sorted, but the mean must not DEPEND on that —
		// order-independence is the whole cross-client-identity claim
		int expected = PartyRollService.fightingWeight(Arrays.asList(40, 90, 90, 90));
		Assert.assertEquals(expected, PartyRollService.fightingWeight(Arrays.asList(90, 90, 40, 90)));
		Assert.assertEquals(expected, PartyRollService.fightingWeight(Arrays.asList(90, 40, 90, 90)));
		Assert.assertEquals(expected, PartyRollService.fightingWeight(Arrays.asList(90, 90, 90, 40)));
	}

	// --- B. hostile or broken transmitted levels ---

	@Test
	public void anAbsurdLevelIsClampedBeforeAveraging()
	{
		// under the old lowest-level rule a bad combatLevel could only make
		// contracts EASIER, so it was self-limiting. An average is not: one
		// inflated value would drag the whole party onto an unkillable monster,
		// and a party contract cannot be abandoned. (126 + 90) / 2 = 108, and
		// an unclamped int sum would have wrapped negative here
		Assert.assertEquals(108,
			PartyRollService.fightingWeight(Arrays.asList(90, Integer.MAX_VALUE)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MAX,
			PartyRollService.fightingWeight(Arrays.asList(Integer.MAX_VALUE, Integer.MAX_VALUE)));
	}

	@Test
	public void aNonsenseLowLevelIsClampedToTheCombatFloor()
	{
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.fightingWeight(Arrays.asList(0, 0)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.fightingWeight(Arrays.asList(-5, -5)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.fightingWeight(Arrays.asList(Integer.MIN_VALUE, Integer.MIN_VALUE)));
	}

	@Test
	public void anEmptyOrMissingRosterFallsToTheFloor()
	{
		// NOT 0. A 0 collapses TaskGenerator's cap to max(2, 0) = 2 and rolls a
		// degenerate board of the two lowest monsters in the table
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN, PartyRollService.fightingWeight(null));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.fightingWeight(Collections.emptyList()));
	}

	// --- C. the mixed-version gate ---

	@Test
	public void oneOldClientPutsTheWholePartyBackOnTheOldRule()
	{
		// all-or-nothing: sizing changes the eligible pool, the pool changes the
		// bound rng.pick draws against, and a different bound consumes a
		// different number of Random.next() calls. Two clients on two rules
		// would vote by INDEX on boards they never saw
		Assert.assertTrue(PartyRollService.meanSizingAgreed(Arrays.asList(1, 1, 1)));
		Assert.assertFalse("one legacy member is enough to fall back",
			PartyRollService.meanSizingAgreed(Arrays.asList(1, 1, 0)));
		Assert.assertFalse(PartyRollService.meanSizingAgreed(Arrays.asList(0, 0)));
	}

	@Test
	public void aFutureProtocolIsNotMistakenForALegacyOne()
	{
		// the gate is >= and not ==, so a client from a LATER round that still
		// implements Fighting Weight is not shoved back onto the old rule
		Assert.assertTrue(PartyRollService.meanSizingAgreed(Arrays.asList(2, 1)));
		Assert.assertTrue(PartyRollService.meanSizingAgreed(
			Collections.singletonList(Integer.MAX_VALUE)));
	}

	@Test
	public void anUnknownRosterNeverAssumesTheNewRule()
	{
		// no answer is not a yes: the safe default is the rule every build in
		// existence already agrees on
		Assert.assertFalse(PartyRollService.meanSizingAgreed(null));
		Assert.assertFalse(PartyRollService.meanSizingAgreed(Collections.emptyList()));
	}

	@Test
	public void thisBuildAnnouncesTheFightingWeightProtocol()
	{
		Assert.assertTrue("this build must claim a protocol its own gate accepts",
			PartyRollService.meanSizingAgreed(
				Collections.singletonList(PartyRollService.ROLL_PROTOCOL)));
	}

	// --- D. the wire ---

	@Test
	public void rollProtocolIsAbsentFromAnOlderClientsWire()
	{
		// exactly the value meanSizingAgreed treats as legacy — the fallback is
		// driven by Gson's int default, not by a version string anyone sends
		PartyRollProposeMessage propose = gson.fromJson(
			"{\"proposalId\":7,\"seedCandidate\":3,\"members\":true,"
				+ "\"combatLevel\":50,\"slayerLevel\":12}", PartyRollProposeMessage.class);
		Assert.assertEquals(0, propose.getRollProtocol());

		PartyRollResponseMessage response = gson.fromJson(
			"{\"proposalId\":7,\"response\":0,\"seedCandidate\":3,\"members\":false,"
				+ "\"combatLevel\":50,\"slayerLevel\":12}", PartyRollResponseMessage.class);
		Assert.assertEquals(0, response.getRollProtocol());
		Assert.assertFalse(PartyRollService.meanSizingAgreed(
			Arrays.asList(PartyRollService.ROLL_PROTOCOL, propose.getRollProtocol())));
	}

	@Test
	public void rollProtocolRoundTripsOnBothMessages()
	{
		PartyRollProposeMessage propose = gson.fromJson(gson.toJson(
			new PartyRollProposeMessage(7L, 3L, true, 50, 12, "MAGIC",
				PartyRollService.ROLL_PROTOCOL)), PartyRollProposeMessage.class);
		Assert.assertEquals(PartyRollService.ROLL_PROTOCOL, propose.getRollProtocol());
		// the field was appended LAST, so the older fields must still land where
		// they did — a positional @AllArgsConstructor gives no other warning
		Assert.assertEquals(50, propose.getCombatLevel());
		Assert.assertEquals(12, propose.getSlayerLevel());
		Assert.assertEquals("MAGIC", propose.getAllowedStyle());

		PartyRollResponseMessage response = gson.fromJson(gson.toJson(
			new PartyRollResponseMessage(7L, PartyRollResponseMessage.AGREE, 3L, true, 50, 12,
				"RANGED", PartyRollService.ROLL_PROTOCOL)), PartyRollResponseMessage.class);
		Assert.assertEquals(PartyRollService.ROLL_PROTOCOL, response.getRollProtocol());
		Assert.assertEquals(50, response.getCombatLevel());
		Assert.assertEquals(12, response.getSlayerLevel());
		Assert.assertEquals("RANGED", response.getAllowedStyle());
	}
}
