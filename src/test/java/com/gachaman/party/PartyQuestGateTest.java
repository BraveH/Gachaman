package com.gachaman.party;

import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.junit.*;

/**
 * A quest lock cuts monsters out of the pool, and the pool decides how many
 * values {@code rng.pick} draws against — so two clients rolling one seed
 * against two different quest sets diverge from the first pick, then vote by
 * INDEX on boards they never saw. Party contracts are binding.
 *
 * That is why quest state is TRANSMITTED and intersected rather than read
 * locally, and why everything below is pinned: the agreed set must be a pure
 * function of the transmitted lists and the transmitted protocols, order must
 * not matter, and every unclear answer must withhold monsters rather than
 * offer them.
 *
 * Statics only, like {@link PartySizingChoiceTest}: executeRoll needs a Client,
 * a PartyService and a live proposal.
 */
public class PartyQuestGateTest
{
	private static final int OLD = PartyRollService.ROLL_PROTOCOL_SIZING_CHOICE;
	private static final int NOW = PartyRollService.ROLL_PROTOCOL_QUEST_GATE;

	private static List<MonsterTable.Monster> monsters;

	private final Gson gson = new Gson();

	@BeforeClass
	public static void load() throws Exception
	{
		try (InputStreamReader reader = new InputStreamReader(
			PartyQuestGateTest.class.getResourceAsStream("/com/gachaman/data/monsters.json"),
			StandardCharsets.UTF_8))
		{
			monsters = new Gson().fromJson(reader, MonstersShape.class).monsters;
		}
	}

	private static class MonstersShape
	{
		List<MonsterTable.Monster> monsters;
	}

	private static List<Integer> protocols(int... values)
	{
		List<Integer> list = new ArrayList<>(values.length);
		for (int value : values)
		{
			list.add(value);
		}
		return list;
	}

	// --- A. the intersection ------------------------------------------------

	@Test
	public void onlyQuestsTheWholePartyHasFinishedSurvive()
	{
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY", "DRAGON_SLAYER_I"),
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			Arrays.asList("PRIEST_IN_PERIL", "REGICIDE"));
		Assert.assertEquals(Set.of("PRIEST_IN_PERIL"),
			PartyRollService.agreedQuests(perMember, protocols(NOW, NOW, NOW)));
	}

	@Test
	public void oneMemberWhoHasDoneNothingGatesTheWholeParty()
	{
		// the point of the feature: three maxed accounts do not get to sign a
		// contract on a monster the fourth cannot walk up to
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			Collections.emptyList());
		Assert.assertEquals(Collections.emptySet(),
			PartyRollService.agreedQuests(perMember, protocols(NOW, NOW, NOW)));
	}

	@Test
	public void aPartyThatAllFinishedTheSameQuestsKeepsThemAll()
	{
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			Arrays.asList("LOST_CITY", "PRIEST_IN_PERIL"));
		Assert.assertEquals(Set.of("PRIEST_IN_PERIL", "LOST_CITY"),
			PartyRollService.agreedQuests(perMember, protocols(NOW, NOW)));
	}

	@Test
	public void orderDoesNotChangeTheAgreedSet()
	{
		// the roster iterates in whatever order each client's map happens to
		// hold, and each member's own list is sorted by its own TreeSet — so
		// neither order may reach the pool filter
		List<List<String>> ab = Arrays.asList(
			Arrays.asList("LOST_CITY", "PRIEST_IN_PERIL", "REGICIDE"),
			Arrays.asList("REGICIDE", "LOST_CITY"));
		List<List<String>> ba = Arrays.asList(
			Arrays.asList("LOST_CITY", "REGICIDE"),
			Arrays.asList("REGICIDE", "PRIEST_IN_PERIL", "LOST_CITY"));
		Assert.assertEquals(PartyRollService.agreedQuests(ab, protocols(NOW, NOW)),
			PartyRollService.agreedQuests(ba, protocols(NOW, NOW)));
		Assert.assertEquals(Set.of("LOST_CITY", "REGICIDE"),
			PartyRollService.agreedQuests(ab, protocols(NOW, NOW)));
	}

	@Test
	public void aSoloMemberIsItsOwnAgreedSet()
	{
		Assert.assertEquals(Set.of("PRIEST_IN_PERIL"), PartyRollService.agreedQuests(
			Collections.singletonList(Arrays.asList("PRIEST_IN_PERIL")), protocols(NOW)));
	}

	@Test
	public void aDuplicateNameIsNotAnExtraVote()
	{
		// a member listing the same quest twice must not survive an intersection
		// with a member who never did it
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("LOST_CITY", "LOST_CITY"),
			Arrays.asList("PRIEST_IN_PERIL"));
		Assert.assertEquals(Collections.emptySet(),
			PartyRollService.agreedQuests(perMember, protocols(NOW, NOW)));
	}

	// --- B. every unclear answer withholds ----------------------------------

	@Test
	public void aMemberWhoSentNoListCountsAsHavingFinishedNothing()
	{
		// on the current protocol a null is a bug, not a legacy client — and the
		// safe reading of a bug is "offer less", never "offer on the strength of
		// a value nobody sent"
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			null);
		Assert.assertEquals(Collections.emptySet(),
			PartyRollService.agreedQuests(perMember, protocols(NOW, NOW)));
	}

	@Test
	public void anEmptyRosterGatesNothingRatherThanEverything()
	{
		// null means "no gating" (roll the whole table), which is only correct
		// because there is no member to gate on — a roll with no stances never
		// reaches the pool filter anyway
		Assert.assertNull(PartyRollService.agreedQuests(null, protocols(NOW)));
		Assert.assertNull(PartyRollService.agreedQuests(
			Collections.emptyList(), protocols(NOW)));
	}

	@Test
	public void theAgreedSetCannotBeMutatedByItsCaller()
	{
		Set<String> agreed = PartyRollService.agreedQuests(
			Arrays.asList(Arrays.asList("LOST_CITY"), Arrays.asList("LOST_CITY")),
			protocols(NOW, NOW));
		Assert.assertNotNull(agreed);
		try
		{
			agreed.add("PRIEST_IN_PERIL");
			Assert.fail("the agreed set is read by the pool filter and must not be writable");
		}
		catch (UnsupportedOperationException expected)
		{
			// the board would change under one client and not the others
		}
	}

	// --- C. the protocol gate is all-or-nothing -----------------------------

	@Test
	public void oneOlderClientPutsTheWholePartyOnTheUnfilteredTable()
	{
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL"),
			Arrays.asList("PRIEST_IN_PERIL"),
			null); // an older build sends no field at all
		Assert.assertFalse(PartyRollService.questGateAgreed(protocols(NOW, NOW, OLD)));
		// null, NOT the empty set: an unaware client filters on nothing, and the
		// fallback has to be what THAT client computes or the boards diverge
		Assert.assertNull(PartyRollService.agreedQuests(perMember, protocols(NOW, NOW, OLD)));
	}

	@Test
	public void theGateNeedsEveryoneAndAnUnknownRosterIsNotEveryone()
	{
		Assert.assertTrue(PartyRollService.questGateAgreed(protocols(NOW, NOW)));
		Assert.assertFalse(PartyRollService.questGateAgreed(protocols(NOW, 0)));
		Assert.assertFalse(PartyRollService.questGateAgreed(protocols(OLD)));
		Assert.assertFalse(PartyRollService.questGateAgreed(null));
		Assert.assertFalse(PartyRollService.questGateAgreed(Collections.emptyList()));
		// a FUTURE build still filters quests, so it must not be read as legacy
		Assert.assertTrue(PartyRollService.questGateAgreed(protocols(NOW, NOW + 7)));
	}

	@Test
	public void theOlderGatesStillHoldOnAQuestGatingParty()
	{
		// the protocol is one ladder, so shipping rung 3 must not quietly drop
		// rungs 1 and 2 for a party that is entirely on the current build
		Assert.assertTrue(PartyRollService.meanSizingAgreed(protocols(NOW, NOW)));
		Assert.assertTrue(PartyRollService.sizingChoiceAgreed(protocols(NOW, NOW)));
	}

	// --- D. the reason all of the above matters -----------------------------

	@Test
	public void twoClientsAgreeingOnQuestsDealTheSameBoardAndOtherwiseDoNot()
	{
		List<List<String>> perMember = Arrays.asList(
			Arrays.asList("PRIEST_IN_PERIL", "LOST_CITY"),
			Arrays.asList("LOST_CITY", "PRIEST_IN_PERIL"));
		Set<String> host = PartyRollService.agreedQuests(perMember, protocols(NOW, NOW));
		Set<String> guest = PartyRollService.agreedQuests(
			Arrays.asList(perMember.get(1), perMember.get(0)), protocols(NOW, NOW));
		Assert.assertEquals(board(host), board(guest));
		// and the gate really is what moves the board — otherwise this test
		// would pass just as well with the filter deleted
		Assert.assertNotEquals(board(host), board(Collections.emptySet()));
		Assert.assertNotEquals(board(host), board(null));
	}

	private static List<String> board(Set<String> completedQuests)
	{
		List<String> names = new ArrayList<>();
		for (TaskOffer offer : TaskGenerator.generateOffers(monsters, 100, 99, true,
			completedQuests, false, new GachaRng(31337L)))
		{
			names.add(offer.getMonsterName());
		}
		return names;
	}

	// --- E. the wire --------------------------------------------------------

	@Test
	public void questsRoundTripOnBothMessagesWithoutMovingTheOlderFields()
	{
		PartyRollProposeMessage propose = gson.fromJson(gson.toJson(
			new PartyRollProposeMessage(7L, 3L, true, 50, 12, "MAGIC", NOW,
				PartySizing.WEAKEST_MAN.name(), Arrays.asList("LOST_CITY", "PRIEST_IN_PERIL"), null)),
			PartyRollProposeMessage.class);
		Assert.assertEquals(Arrays.asList("LOST_CITY", "PRIEST_IN_PERIL"),
			propose.getCompletedQuests());
		// appending a field is only safe if it appended — @AllArgsConstructor is
		// positional, so a field inserted above shifts every existing call site
		Assert.assertEquals(7L, propose.getProposalId());
		Assert.assertEquals(3L, propose.getSeedCandidate());
		Assert.assertTrue(propose.isMembers());
		Assert.assertEquals(50, propose.getCombatLevel());
		Assert.assertEquals(12, propose.getSlayerLevel());
		Assert.assertEquals("MAGIC", propose.getAllowedStyle());
		Assert.assertEquals(NOW, propose.getRollProtocol());
		Assert.assertEquals(PartySizing.WEAKEST_MAN.name(), propose.getSizingMode());

		PartyRollResponseMessage response = gson.fromJson(gson.toJson(
			new PartyRollResponseMessage(7L, PartyRollResponseMessage.AGREE, 3L, true, 50, 12,
				"RANGED", NOW, Arrays.asList("REGICIDE"), null)), PartyRollResponseMessage.class);
		Assert.assertEquals(Arrays.asList("REGICIDE"), response.getCompletedQuests());
		Assert.assertEquals(PartyRollResponseMessage.AGREE, response.getResponse());
		Assert.assertEquals("RANGED", response.getAllowedStyle());
		Assert.assertEquals(NOW, response.getRollProtocol());
	}

	@Test
	public void anOlderClientsMessageLeavesTheQuestListNullNotEmpty()
	{
		// null and empty are NOT interchangeable here: null rides through to the
		// protocol check and falls the party back to the unfiltered table, while
		// empty would claim a member who has genuinely finished nothing
		PartyRollProposeMessage propose = gson.fromJson(
			"{\"proposalId\":7,\"seedCandidate\":3,\"members\":true,\"combatLevel\":50,"
				+ "\"slayerLevel\":12,\"allowedStyle\":\"MAGIC\",\"rollProtocol\":2,"
				+ "\"sizingMode\":\"WEAKEST_MAN\"}", PartyRollProposeMessage.class);
		Assert.assertNull(propose.getCompletedQuests());
		Assert.assertEquals(OLD, propose.getRollProtocol());

		PartyRollResponseMessage response = gson.fromJson(
			"{\"proposalId\":7,\"response\":0,\"seedCandidate\":3,\"members\":true,"
				+ "\"combatLevel\":50,\"slayerLevel\":12,\"rollProtocol\":2}",
			PartyRollResponseMessage.class);
		Assert.assertNull(response.getCompletedQuests());
	}

	@Test
	public void anEmptyListSurvivesTheWireAsAnEmptyList()
	{
		// the questless account's answer must not be flattened into "no answer"
		// by a round trip, or it would read as a legacy client and unlock the
		// very monsters it cannot reach
		PartyRollResponseMessage response = gson.fromJson(gson.toJson(
			new PartyRollResponseMessage(7L, PartyRollResponseMessage.AGREE, 3L, true, 50, 12,
				null, NOW, Collections.emptyList(), null)), PartyRollResponseMessage.class);
		Assert.assertNotNull(response.getCompletedQuests());
		Assert.assertTrue(response.getCompletedQuests().isEmpty());
	}
}
