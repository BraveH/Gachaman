package com.gachaman.party;

import com.gachaman.Tuning;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

/**
 * The host picks the party's sizing rule — Fighting Weight (average) or
 * Weakest Man (lowest) — and every client in the roll obeys THAT choice, not
 * its own setting.
 *
 * Statics only, like {@link PartyFightingWeightTest}: executeRoll needs a
 * Client, a PartyService and a live proposal. What is pinned here is the
 * property the cross-client claim rests on — the sizing level is a pure
 * function of the TRANSMITTED protocols, the TRANSMITTED levels and the ONE
 * transmitted host choice. Nothing local feeds it, so two clients holding the
 * same stances compute the same number.
 */
public class PartySizingChoiceTest
{
	private final Gson gson = new Gson();

	// --- A. Weakest Man itself ---

	@Test
	public void weakestManIsTheLowestLevel()
	{
		Assert.assertEquals("a level 40 in a party of 90s sizes the board at 40", 40,
			PartyRollService.weakestMan(Arrays.asList(40, 90, 90, 90)));
		Assert.assertEquals(50, PartyRollService.weakestMan(Arrays.asList(50, 50)));
		Assert.assertEquals(40, PartyRollService.weakestMan(Arrays.asList(40, 90)));
	}

	@Test
	public void orderDoesNotChangeTheWeakestMan()
	{
		// the roster is host-sorted, but the rule must not DEPEND on that —
		// order-independence is the whole cross-client-identity claim
		int expected = PartyRollService.weakestMan(Arrays.asList(40, 90, 90, 90));
		Assert.assertEquals(expected, PartyRollService.weakestMan(Arrays.asList(90, 90, 40, 90)));
		Assert.assertEquals(expected, PartyRollService.weakestMan(Arrays.asList(90, 90, 90, 40)));
	}

	@Test
	public void aSoloLevelIsItsOwnWeakestMan()
	{
		Assert.assertEquals(70, PartyRollService.weakestMan(Collections.singletonList(70)));
	}

	@Test
	public void aBrokenLevelIsClampedBeforeItSizesTheBoard()
	{
		// NOT 0: a 0 collapses TaskGenerator's cap to max(2, 0) and rolls a
		// degenerate board of the two lowest monsters in the table. Lowering the
		// board is harmless under Fighting Weight (it averages away) but this
		// rule takes the minimum DIRECTLY, so one bad value is the whole answer
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.weakestMan(Arrays.asList(0, 90)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.weakestMan(Arrays.asList(-5, 90)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.weakestMan(Arrays.asList(Integer.MIN_VALUE, 90)));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MAX,
			PartyRollService.weakestMan(Arrays.asList(Integer.MAX_VALUE, Integer.MAX_VALUE)));
	}

	@Test
	public void anEmptyOrMissingRosterFallsToTheFloor()
	{
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN, PartyRollService.weakestMan(null));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.weakestMan(Collections.emptyList()));
	}

	// --- B. the legacy rule stays bug-for-bug ---

	@Test
	public void theLegacyRuleIsStillUnclamped()
	{
		// a party that falls back to this contains a client running that exact
		// code, and "nearly the same level" deals a different board. Weakest Man
		// and the legacy rule agree on every sane input and MUST diverge on a
		// broken one — that divergence is the clamp, and it is deliberate
		Assert.assertEquals(40, PartyRollService.legacyLowest(Arrays.asList(40, 90)));
		Assert.assertEquals(0, PartyRollService.legacyLowest(Arrays.asList(0, 90)));
		Assert.assertEquals(Integer.MIN_VALUE,
			PartyRollService.legacyLowest(Arrays.asList(Integer.MIN_VALUE, 90)));
		Assert.assertNotEquals(PartyRollService.weakestMan(Arrays.asList(0, 90)),
			PartyRollService.legacyLowest(Arrays.asList(0, 90)));
	}

	// --- C. the mixed-version gate, one rung up ---

	@Test
	public void weakestManNeedsEveryClientToUnderstandTheChoice()
	{
		// a protocol-1 client never reads the host's choice and sizes to the
		// average regardless, so one of them is enough to override the host
		Assert.assertTrue(PartyRollService.sizingChoiceAgreed(Arrays.asList(2, 2, 2)));
		Assert.assertFalse("one Fighting-Weight-only member is enough to fall back",
			PartyRollService.sizingChoiceAgreed(Arrays.asList(2, 2, 1)));
		Assert.assertFalse(PartyRollService.sizingChoiceAgreed(Arrays.asList(2, 0)));
		Assert.assertFalse(PartyRollService.sizingChoiceAgreed(null));
		Assert.assertFalse(PartyRollService.sizingChoiceAgreed(Collections.emptyList()));
	}

	@Test
	public void aFutureProtocolStillUnderstandsTheChoice()
	{
		Assert.assertTrue(PartyRollService.sizingChoiceAgreed(Arrays.asList(3, 2)));
		Assert.assertTrue(PartyRollService.sizingChoiceAgreed(
			Collections.singletonList(Integer.MAX_VALUE)));
	}

	@Test
	public void thisBuildAnnouncesTheSizingChoiceProtocol()
	{
		Assert.assertTrue("this build must claim a protocol its own gate accepts",
			PartyRollService.sizingChoiceAgreed(
				Collections.singletonList(PartyRollService.ROLL_PROTOCOL)));
		// the gates are nested, so understanding the choice implies understanding
		// Fighting Weight — a build that answered yes to one and no to the other
		// would make sizingLevel's branch order meaningless
		Assert.assertTrue(PartyRollService.meanSizingAgreed(
			Collections.singletonList(PartyRollService.ROLL_PROTOCOL)));
		Assert.assertTrue(PartyRollService.ROLL_PROTOCOL_SIZING_CHOICE
			> PartyRollService.ROLL_PROTOCOL_FIGHTING_WEIGHT);
	}

	// --- D. parsing the host's choice ---

	@Test
	public void everyUnrecognisedChoiceLandsOnTheDefault()
	{
		// what matters is not WHICH value garbage maps to, but that it maps to
		// the same one on every client — a roll that cannot agree on its rule is
		// worse than a roll on the wrong rule
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT, PartySizing.fromWire(null));
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT, PartySizing.fromWire(""));
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT, PartySizing.fromWire("STRONGEST_MAN"));
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT, PartySizing.fromWire("weakest_man"));
		Assert.assertEquals(PartySizing.WEAKEST_MAN, PartySizing.fromWire("WEAKEST_MAN"));
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT, PartySizing.fromWire("FIGHTING_WEIGHT"));
	}

	@Test
	public void everyConstantSurvivesItsOwnRoundTrip()
	{
		// the wire carries name(), not toString() — a display label that drifted
		// into the transmitted value would read as garbage on the far side
		for (PartySizing sizing : PartySizing.values())
		{
			Assert.assertEquals(sizing, PartySizing.fromWire(sizing.name()));
		}
	}

	// --- E. the whole decision ---

	@Test
	public void theHostsChoiceDecidesWhenEveryoneCanObeyIt()
	{
		Assert.assertEquals("Weakest Man sizes to the lowest", 40,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 2),
				"WEAKEST_MAN"));
		Assert.assertEquals("Fighting Weight sizes to the average", 65,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 2),
				"FIGHTING_WEIGHT"));
		Assert.assertEquals("an unset choice is Fighting Weight", 65,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 2), null));
	}

	@Test
	public void oneOlderClientOverridesAWeakestManHost()
	{
		// NOT 40: the fallback has to be the value the unaware client would have
		// picked, which is the average — falling back to the host's preference
		// would split the board it was meant to keep identical
		Assert.assertEquals(65,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 1),
				"WEAKEST_MAN"));
	}

	@Test
	public void aPreFightingWeightClientOverridesEveryoneIncludingTheHost()
	{
		// the legacy branch is checked FIRST and ignores the choice entirely
		Assert.assertEquals(40,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 0),
				"FIGHTING_WEIGHT"));
		Assert.assertEquals(40,
			PartyRollService.sizingLevel(Arrays.asList(40, 90), Arrays.asList(2, 0),
				"WEAKEST_MAN"));
	}

	@Test
	public void theLegacyBranchKeepsItsMissingClampEvenNowThatWeakestManHasOne()
	{
		// the same party, the same broken level, two rules: legacy passes the 0
		// straight through because the old client will, and Weakest Man clamps
		// because nothing on the wire has to match it
		Assert.assertEquals(0,
			PartyRollService.sizingLevel(Arrays.asList(0, 90), Arrays.asList(0, 2),
				"WEAKEST_MAN"));
		Assert.assertEquals(Tuning.COMBAT_LEVEL_MIN,
			PartyRollService.sizingLevel(Arrays.asList(0, 90), Arrays.asList(2, 2),
				"WEAKEST_MAN"));
	}

	@Test
	public void theResolvedRuleIsNamedRatherThanAppliedSilently()
	{
		Assert.assertEquals(PartySizing.WEAKEST_MAN,
			PartyRollService.resolvedSizing(Arrays.asList(2, 2), "WEAKEST_MAN"));
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT,
			PartyRollService.resolvedSizing(Arrays.asList(2, 1), "WEAKEST_MAN"));

		// the pre-vote disclosure has to print what actually happened, because
		// the member it protects is voting on a binding contract
		Assert.assertEquals("Weakest Man",
			PartyRollService.sizingRuleLabel(Arrays.asList(2, 2), "WEAKEST_MAN"));
		Assert.assertEquals("a silently overridden host is the one case that must not read"
				+ " like the host got their way", "Fighting Weight",
			PartyRollService.sizingRuleLabel(Arrays.asList(2, 1), "WEAKEST_MAN"));
		Assert.assertTrue(PartyRollService.sizingRuleLabel(Arrays.asList(2, 0), "WEAKEST_MAN")
			.contains("pre-Fighting Weight"));
	}

	// --- F. the wire ---

	@Test
	public void sizingModeIsAbsentFromAnOlderClientsWire()
	{
		// a String and not an ordinal: Gson leaves null here, whereas an int
		// would deserialize to 0 and fabricate a real choice
		PartyRollProposeMessage propose = gson.fromJson(
			"{\"proposalId\":7,\"seedCandidate\":3,\"members\":true,"
				+ "\"combatLevel\":50,\"slayerLevel\":12,\"rollProtocol\":1}",
			PartyRollProposeMessage.class);
		Assert.assertNull(propose.getSizingMode());
		Assert.assertEquals(PartySizing.FIGHTING_WEIGHT,
			PartySizing.fromWire(propose.getSizingMode()));
	}

	@Test
	public void sizingModeRoundTripsAndDidNotDisturbTheOlderFields()
	{
		PartyRollProposeMessage propose = gson.fromJson(gson.toJson(
			new PartyRollProposeMessage(7L, 3L, true, 50, 12, "MAGIC",
				PartyRollService.ROLL_PROTOCOL, PartySizing.WEAKEST_MAN.name(),
				java.util.List.of(), null)),
			PartyRollProposeMessage.class);
		Assert.assertEquals("WEAKEST_MAN", propose.getSizingMode());
		// appended LAST, so every older field must still land where it did — a
		// positional @AllArgsConstructor gives no other warning
		Assert.assertEquals(7L, propose.getProposalId());
		Assert.assertEquals(3L, propose.getSeedCandidate());
		Assert.assertTrue(propose.isMembers());
		Assert.assertEquals(50, propose.getCombatLevel());
		Assert.assertEquals(12, propose.getSlayerLevel());
		Assert.assertEquals("MAGIC", propose.getAllowedStyle());
		Assert.assertEquals(PartyRollService.ROLL_PROTOCOL, propose.getRollProtocol());
	}
}
