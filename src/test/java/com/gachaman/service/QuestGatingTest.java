package com.gachaman.service;

import com.gachaman.data.MonsterTable;
import com.gachaman.model.TaskOffer;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A quest lock is a HARD gate: a monster behind an unfinished quest cannot be
 * reached or damaged, and a Gachaman contract cannot be abandoned. So the pool
 * is filtered before the roll, and everything below exists to prove the filter
 * fails in the safe direction — offering LESS on a bad answer, never a contract
 * the player cannot start.
 */
public class QuestGatingTest
{
	private static List<MonsterTable.Monster> monsters;

	@BeforeClass
	public static void load() throws Exception
	{
		try (InputStreamReader reader = new InputStreamReader(
			QuestGatingTest.class.getResourceAsStream("/com/gachaman/data/monsters.json"),
			StandardCharsets.UTF_8))
		{
			monsters = new Gson().fromJson(reader, MonstersShape.class).monsters;
		}
	}

	private static class MonstersShape
	{
		List<MonsterTable.Monster> monsters;
	}

	private static MonsterTable.Monster monster(String name, int cb, String... quests)
	{
		return new MonsterTable.Monster(name, cb, List.of("starter"), false, 0, false, false,
			List.of(quests));
	}

	// --- the predicate ------------------------------------------------------

	@Test
	public void anUngatedMonsterIsAlwaysSatisfied()
	{
		MonsterTable.Monster free = monster("Chicken", 1);
		Assert.assertTrue(TaskGenerator.questsSatisfied(free, Set.of()));
		Assert.assertTrue(TaskGenerator.questsSatisfied(free, null));
		Assert.assertTrue(TaskGenerator.questsSatisfied(free, Set.of("PRIEST_IN_PERIL")));
	}

	@Test
	public void nullMeansGatingOffButEmptyMeansAPlayerWhoHasFinishedNothing()
	{
		MonsterTable.Monster gated = monster("Banshee", 23, "PRIEST_IN_PERIL");
		// the two are NOT interchangeable: null is the mixed-version fallback
		Assert.assertTrue(TaskGenerator.questsSatisfied(gated, null));
		Assert.assertFalse(TaskGenerator.questsSatisfied(gated, Set.of()));
	}

	@Test
	public void everyListedQuestIsRequired()
	{
		MonsterTable.Monster nex = monster("Nex", 1001, "THE_FROZEN_DOOR", "TROLL_STRONGHOLD");
		Assert.assertFalse(TaskGenerator.questsSatisfied(nex, Set.of("THE_FROZEN_DOOR")));
		Assert.assertFalse(TaskGenerator.questsSatisfied(nex, Set.of("TROLL_STRONGHOLD")));
		Assert.assertTrue(TaskGenerator.questsSatisfied(nex,
			Set.of("THE_FROZEN_DOOR", "TROLL_STRONGHOLD")));
	}

	@Test
	public void anUnrecognisedNameWithholdsTheMonsterRatherThanOfferingIt()
	{
		// a typo can never be satisfied, so the monster stays locked — the
		// direction a bad dataset should fail in
		MonsterTable.Monster typo = monster("Ghost", 20, "PREIST_IN_PERIL");
		Assert.assertFalse(TaskGenerator.questsSatisfied(typo, Set.of("PRIEST_IN_PERIL")));
	}

	// --- the pool -----------------------------------------------------------

	@Test
	public void aQuestlessAccountIsNeverOfferedAQuestLockedMonster()
	{
		Map<String, MonsterTable.Monster> byName = new HashMap<>();
		for (MonsterTable.Monster m : monsters)
		{
			byName.put(m.getName(), m);
		}
		GachaRng rng = new GachaRng(4242L);
		for (int i = 0; i < 400; i++)
		{
			for (TaskOffer offer : TaskGenerator.generateOffers(monsters, 60 + (i % 60), 99,
				true, Set.of(), false, rng))
			{
				MonsterTable.Monster picked = byName.get(offer.getMonsterName());
				Assert.assertNotNull(offer.getMonsterName(), picked);
				Assert.assertTrue(offer.getMonsterName() + " needs "
					+ picked.getQuests() + " and should not have been offered",
					picked.getQuests() == null || picked.getQuests().isEmpty());
			}
		}
	}

	@Test
	public void finishingTheQuestPutsTheMonsterBackInThePool()
	{
		List<MonsterTable.Monster> pool = List.of(
			monster("Cow", 2),
			monster("Crawling Hand", 8, "PRIEST_IN_PERIL"));
		Assert.assertTrue(seenNames(pool, Set.of()).contains("Cow"));
		Assert.assertFalse(seenNames(pool, Set.of()).contains("Crawling Hand"));
		Assert.assertTrue(seenNames(pool, Set.of("PRIEST_IN_PERIL")).contains("Crawling Hand"));
	}

	private static Set<String> seenNames(List<MonsterTable.Monster> pool, Set<String> completed)
	{
		Set<String> names = new HashSet<>();
		GachaRng rng = new GachaRng(9L);
		for (int i = 0; i < 60; i++)
		{
			for (TaskOffer offer : TaskGenerator.generateOffers(pool, 40, 99, true, completed,
				false, rng))
			{
				names.add(offer.getMonsterName());
			}
		}
		return names;
	}

	/**
	 * The pool decides how many values {@code rng.pick} draws against, and a
	 * different bound consumes a different number of {@code Random.next()}
	 * calls. That is the whole reason quest state is transmitted rather than
	 * read locally, so it is worth pinning that it really does move the board.
	 */
	@Test
	public void adifferentQuestSetDealsADifferentBoard()
	{
		List<String> withNone = names(TaskGenerator.generateOffers(monsters, 90, 99, true,
			Set.of(), false, new GachaRng(77L)));
		List<String> withPip = names(TaskGenerator.generateOffers(monsters, 90, 99, true,
			Set.of("PRIEST_IN_PERIL"), false, new GachaRng(77L)));
		Assert.assertNotEquals(withNone, withPip);
		// and identical inputs still deal identically, or nothing else holds
		Assert.assertEquals(withNone, names(TaskGenerator.generateOffers(monsters, 90, 99, true,
			Set.of(), false, new GachaRng(77L))));
	}

	private static List<String> names(List<TaskOffer> offers)
	{
		List<String> names = new ArrayList<>(offers.size());
		for (TaskOffer offer : offers)
		{
			names.add(offer.getMonsterName());
		}
		return names;
	}

	// --- the Charter Office -------------------------------------------------

	@Test
	public void aDeedCannotBuyPastTheGateTheRollEnforces()
	{
		MonsterTable.Monster gargoyle = monster("Gargoyle", 111, "PRIEST_IN_PERIL");
		Assert.assertFalse(TaskGenerator.charterEligible(gargoyle, 126, 99, true, Set.of()));
		Assert.assertTrue(TaskGenerator.charterEligible(gargoyle, 126, 99, true,
			Set.of("PRIEST_IN_PERIL")));
		// the no-quest-argument overload is the pre-gate behaviour, not a bypass
		// of the other gates
		Assert.assertTrue(TaskGenerator.charterEligible(gargoyle, 126, 99, true));
	}

	@Test
	public void theDatasetGatesTheMonstersItClaimsTo()
	{
		int gated = 0;
		for (MonsterTable.Monster m : monsters)
		{
			if (m.getQuests() != null && !m.getQuests().isEmpty())
			{
				gated++;
			}
		}
		Assert.assertTrue("expected the bundled table to carry quest gates", gated >= 100);
	}
}
