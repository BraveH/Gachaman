package com.gachaman.party;

import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

/**
 * How the party page decides what is one block and what is several.
 *
 * A party is routinely not one thing: two members can be working one shared
 * contract while two more work another and a fifth is still mid-roll. The
 * grouping rules are pure statics precisely so this — the part a player
 * actually reads — can be pinned without a Client or a PartyService.
 *
 * Every input is SELF-REPORTED. So the recurring question in this file is not
 * "is the claim true" but "does a false claim stay contained", and the answer
 * has to be yes for every case here.
 */
public class PartyPresenceGroupTest
{
	private static PartyPresenceService.Row row(long memberId, String name,
		String taskName, int killsDone, int killsRequired, Long contractId)
	{
		return new PartyPresenceService.Row(memberId, name, true, false, true,
			AttackStyle.MELEE, 99, taskName, killsDone, killsRequired, false,
			null, false, contractId, null);
	}

	/** A member with nothing signed: no quarry, no quota, no contract id. */
	private static PartyPresenceService.Row idle(long memberId, String name)
	{
		return row(memberId, name, null, 0, 0, null);
	}

	private static List<String> names(PartyPresenceService.Group group)
	{
		List<String> out = new ArrayList<>();
		for (PartyPresenceService.Row member : group.getMembers())
		{
			out.add(member.getName());
		}
		return out;
	}

	@Test
	public void nothingToGroupIsNoBlocks()
	{
		Assert.assertTrue(PartyPresenceService.group(null).isEmpty());
		Assert.assertTrue(PartyPresenceService.group(Collections.emptyList()).isEmpty());
	}

	@Test
	public void membersOnOneContractAreOneBlock()
	{
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Alpha", "Goblin", 4, 20, 77L),
			row(2, "Bravo", "Goblin", 6, 20, 77L)));

		Assert.assertEquals("one contract draws one meter, not two", 1, groups.size());
		PartyPresenceService.Group only = groups.get(0);
		Assert.assertTrue(only.isShared());
		Assert.assertTrue(only.isOnContract());
		Assert.assertEquals(Arrays.asList("Alpha", "Bravo"), names(only));
		Assert.assertEquals("Goblin", only.getTaskName());
		Assert.assertEquals(20, only.getKillsRequired());
	}

	@Test
	public void pooledProgressIsTheMaxNotTheSum()
	{
		// a shared contract has ONE quota that every member's kills fill, so
		// every client should report the same number — but a client that has
		// gone quiet reports a stale LOWER one. Summing would multiply the quota
		// by the party size and show 18/20 for a job that is 6/20 done.
		PartyPresenceService.Group group = PartyPresenceService.group(Arrays.asList(
			row(1, "Alpha", "Goblin", 6, 20, 77L),
			row(2, "Bravo", "Goblin", 6, 20, 77L),
			row(3, "Charlie", "Goblin", 6, 20, 77L))).get(0);

		Assert.assertEquals(6, group.getKillsDone());
		Assert.assertEquals(20, group.getKillsRequired());
	}

	@Test
	public void theFreshestClientSetsThePooledProgress()
	{
		// and it is order-independent: the stale row first, then the stale row
		// last. A "first row wins" reading would draw a different number for the
		// same party depending on who happened to sort to the top.
		Assert.assertEquals(9, PartyPresenceService.group(Arrays.asList(
			row(1, "Stale", "Goblin", 2, 20, 77L),
			row(2, "Fresh", "Goblin", 9, 20, 77L))).get(0).getKillsDone());
		Assert.assertEquals(9, PartyPresenceService.group(Arrays.asList(
			row(1, "Fresh", "Goblin", 9, 20, 77L),
			row(2, "Stale", "Goblin", 2, 20, 77L))).get(0).getKillsDone());
	}

	@Test
	public void twoContractsInOnePartyAreTwoBlocks()
	{
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Alpha", "Goblin", 4, 20, 77L),
			row(2, "Bravo", "Goblin", 4, 20, 77L),
			row(3, "Charlie", "Zulrah", 1, 5, 88L),
			row(4, "Delta", "Zulrah", 1, 5, 88L)));

		Assert.assertEquals(2, groups.size());
		Assert.assertEquals(Arrays.asList("Alpha", "Bravo"), names(groups.get(0)));
		Assert.assertEquals(Arrays.asList("Charlie", "Delta"), names(groups.get(1)));
		Assert.assertEquals("Goblin", groups.get(0).getTaskName());
		Assert.assertEquals("Zulrah", groups.get(1).getTaskName());
	}

	@Test
	public void everyoneElseIsTheirOwnBlock()
	{
		// a solo contract, an idle member and a member mid-roll each stand alone
		// — and two idle members must not merge into one "nobody" block
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Solo", "Goblin", 4, 20, null),
			idle(2, "Idle"),
			idle(3, "AlsoIdle")));

		Assert.assertEquals(3, groups.size());
		Assert.assertFalse("no contract id is no shared contract", groups.get(0).isShared());
		Assert.assertTrue(groups.get(0).isOnContract());
		Assert.assertEquals("Goblin", groups.get(0).getTaskName());

		for (int i = 1; i < groups.size(); i++)
		{
			Assert.assertFalse(groups.get(i).isShared());
			Assert.assertFalse(groups.get(i).isOnContract());
			Assert.assertNull(groups.get(i).getTaskName());
			Assert.assertEquals(0, groups.get(i).getKillsRequired());
		}
	}

	@Test
	public void anIdClaimedByOneMemberIsStillAGroupOfOne()
	{
		// their partner may have left the party or gone quiet and the carry
		// clause has not fired yet. Calling this "solo" would be a guess about
		// somebody else's state, and the page would flip the label back the
		// moment they reconnect.
		PartyPresenceService.Group group = PartyPresenceService.group(
			Collections.singletonList(row(1, "Alpha", "Goblin", 4, 20, 77L))).get(0);
		Assert.assertTrue(group.isShared());
		Assert.assertEquals(1, group.getMembers().size());
	}

	@Test
	public void agreeingOnTheIdButNotTheQuarryDoesNotMerge()
	{
		// the reason the bucket key is id AND task, never the id alone. Two
		// clients that disagree about the monster are not on the same contract
		// by any reading, and merging them would draw one pooled meter over two
		// different jobs. Splitting renders the disagreement, which is honest
		// and also self-limiting: a hostile client that guesses an id can at
		// worst appear beside the party under the party's own monster name.
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Honest", "Goblin", 4, 20, 77L),
			row(2, "Liar", "Zulrah", 19, 20, 77L)));

		Assert.assertEquals(2, groups.size());
		Assert.assertEquals("Goblin", groups.get(0).getTaskName());
		Assert.assertEquals("the forged progress stays in the forger's own block",
			4, groups.get(0).getKillsDone());
		Assert.assertEquals("Zulrah", groups.get(1).getTaskName());
	}

	@Test
	public void theBucketKeyIsSeparated()
	{
		// plain concatenation collides: id 7 on "0Goblin" and id 70 on "Goblin"
		// flatten to the same string and two unrelated contracts become one
		// block with one meter
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Alpha", "0Goblin", 4, 20, 7L),
			row(2, "Bravo", "Goblin", 4, 20, 70L)));
		Assert.assertEquals(2, groups.size());
	}

	@Test
	public void aClaimedIdOfZeroIsARealClaim()
	{
		// proposal ids come from nextLong(), so 0 is as legal as any other and
		// must not read as "claims nothing"
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Alpha", "Goblin", 4, 20, 0L),
			row(2, "Bravo", "Goblin", 4, 20, 0L)));
		Assert.assertEquals(1, groups.size());
		Assert.assertTrue(groups.get(0).isShared());
		Assert.assertEquals(Long.valueOf(0L), groups.get(0).getContractId());
	}

	@Test
	public void anIdWithoutAQuotaIsNotAContract()
	{
		// a stale id left behind after a contract finished would otherwise pull
		// an idle member into a block whose own line says they have no work
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			row(1, "Working", "Goblin", 4, 20, 77L),
			row(2, "Finished", null, 0, 0, 77L)));

		Assert.assertEquals(2, groups.size());
		Assert.assertTrue(groups.get(0).isShared());
		Assert.assertFalse(groups.get(1).isShared());
		Assert.assertFalse(groups.get(1).isOnContract());
	}

	@Test
	public void groupOrderFollowsRowOrderSoSelfStaysOnTop()
	{
		// the row list is already the display order (self first), and the page
		// draws groups in the order it gets them — so a party where your own
		// contract is not first would bury your own meter below strangers'
		PartyPresenceService.Row self = new PartyPresenceService.Row(9, "You", true, true, true,
			AttackStyle.MAGIC, 99, "Zulrah", 2, 5, false, null, false, 88L, null);
		List<PartyPresenceService.Group> groups = PartyPresenceService.group(Arrays.asList(
			self,
			row(1, "Alpha", "Goblin", 4, 20, 77L),
			row(2, "Bravo", "Goblin", 4, 20, 77L)));

		Assert.assertEquals(2, groups.size());
		Assert.assertEquals(Collections.singletonList("You"), names(groups.get(0)));
		Assert.assertTrue(groups.get(0).getMembers().get(0).isSelf());
	}

	@Test
	public void eligibilityNeedsSilenceOnBothSides()
	{
		// what the page prints under a member who cannot join a shared roll.
		// A dealt-but-unsigned board is the non-obvious half: they look idle in
		// every other respect, and the party would sit waiting on someone who
		// cannot answer until they sign or clear it.
		Assert.assertTrue(idle(1, "Ready").isEligibleToRoll());
		Assert.assertFalse("a signed contract is not eligible",
			row(1, "Working", "Goblin", 0, 20, null).isEligibleToRoll());
		Assert.assertFalse("a dealt board with nothing signed is not eligible",
			new PartyPresenceService.Row(1, "Choosing", true, false, true,
				null, 99, null, 0, 0, false, null, true, null, null).isEligibleToRoll());
		Assert.assertFalse("no signal is not a yes",
			new PartyPresenceService.Row(1, "Silent", true, false, false,
				null, 99, null, 0, 0, false, null, false, null, null).isEligibleToRoll());
		Assert.assertFalse("logged out is not a yes",
			new PartyPresenceService.Row(1, "Offline", false, false, true,
				null, 99, null, 0, 0, false, null, false, null, null).isEligibleToRoll());
	}

	@Test
	public void aGroupsMemberListIsNotTheCallersToEdit()
	{
		// it is handed straight to a panel loop, where an accidental sort would
		// silently disagree with the row order the meter was computed from
		try
		{
			PartyPresenceService.group(Collections.singletonList(idle(1, "Alpha")))
				.get(0).getMembers().clear();
			Assert.fail("the member list must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// intended
		}
	}
}
