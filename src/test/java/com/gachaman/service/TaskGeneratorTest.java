package com.gachaman.service;

import com.gachaman.data.MonsterTable;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TaskGeneratorTest
{
	private static List<MonsterTable.Monster> monsters;

	@BeforeClass
	public static void load() throws Exception
	{
		// parse the real bundled dataset via the loader's own Gson shape
		try (InputStreamReader reader = new InputStreamReader(
			TaskGeneratorTest.class.getResourceAsStream("/com/gachaman/data/monsters.json"),
			StandardCharsets.UTF_8))
		{
			MonstersShape shape = new Gson().fromJson(reader, MonstersShape.class);
			monsters = shape.monsters;
		}
		Assert.assertTrue("dataset should have 200+ monsters", monsters.size() >= 200);
	}

	private static class MonstersShape
	{
		List<MonsterTable.Monster> monsters;
	}

	@Test
	public void everyCombatLevelGetsValidOffers()
	{
		GachaRng rng = new GachaRng(1234L);
		for (int cb = 3; cb <= 126; cb++)
		{
			List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, cb, true, false, rng);
			Assert.assertEquals(4, offers.size());
			for (TaskOffer offer : offers)
			{
				Assert.assertNotNull(offer.getMonsterName());
				Assert.assertTrue("kills in range", offer.getKillsRequired() >= offer.getDifficulty().getMinKills());
				// the cap only applies when the pool allows it; sanity: never a boss 3x the player
				if (cb >= 40)
				{
					Assert.assertTrue("cb " + cb + " got monster lvl " + offer.getMonsterCombatLevel(),
						offer.getMonsterCombatLevel() <= cb * offer.getDifficulty().getCbCapFraction() + 1);
				}
			}
		}
	}

	@Test
	public void f2pWorldsNeverGetMembersMonsters()
	{
		GachaRng rng = new GachaRng(99L);
		for (int i = 0; i < 200; i++)
		{
			List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, 80, false, false, rng);
			for (TaskOffer offer : offers)
			{
				MonsterTable.Monster monster = monsters.stream()
					.filter(m -> m.getName().equals(offer.getMonsterName()))
					.findFirst().orElseThrow(AssertionError::new);
				Assert.assertFalse("members monster on f2p: " + monster.getName(), monster.isMembers());
			}
		}
	}

	@Test
	public void taintAddsRedemptionOffer()
	{
		GachaRng rng = new GachaRng(7L);
		List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, 90, true, true, rng);
		Assert.assertEquals(5, offers.size());
		TaskOffer redemption = offers.get(4);
		Assert.assertTrue(redemption.isRedemption());
		Assert.assertEquals(0, redemption.getPerKillGc());
		Assert.assertTrue(redemption.getSideBets().isEmpty());
	}

	@Test
	public void sameSeedProducesIdenticalOffers()
	{
		// party rolls rely on this: every participant rolls with the anchor's
		// seed and must see byte-identical offers
		List<TaskOffer> first = TaskGenerator.generateOffers(monsters, 55, 40, true, false,
			new GachaRng(31337L));
		List<TaskOffer> second = TaskGenerator.generateOffers(monsters, 55, 40, true, false,
			new GachaRng(31337L));
		Assert.assertEquals(first.size(), second.size());
		for (int i = 0; i < first.size(); i++)
		{
			TaskOffer a = first.get(i);
			TaskOffer b = second.get(i);
			Assert.assertEquals(a.getMonsterName(), b.getMonsterName());
			Assert.assertEquals(a.getKillsRequired(), b.getKillsRequired());
			Assert.assertEquals(a.getDifficulty(), b.getDifficulty());
			Assert.assertEquals(a.getSideBets().size(), b.getSideBets().size());
		}
	}

	@Test
	public void slayerGatingFiltersUnreachableMonsters()
	{
		GachaRng rng = new GachaRng(77L);
		List<MonsterTable.Monster> pool = new java.util.ArrayList<>(monsters);
		pool.add(new MonsterTable.Monster("Taskonly Boss", 90, List.of("boss"), true, 0, true, false));
		pool.add(new MonsterTable.Monster("Highslayer Fiend", 90, List.of("slayer"), true, 85, false, false));
		for (int i = 0; i < 300; i++)
		{
			// slayer level 1: neither may ever appear
			for (TaskOffer offer : TaskGenerator.generateOffers(pool, 100, 1, true, false, rng))
			{
				Assert.assertNotEquals("Taskonly Boss", offer.getMonsterName());
				Assert.assertNotEquals("Highslayer Fiend", offer.getMonsterName());
			}
		}
		boolean fiendSeen = false;
		for (int i = 0; i < 500 && !fiendSeen; i++)
		{
			// slayer level 90: the level-gated one becomes rollable, task-only never
			for (TaskOffer offer : TaskGenerator.generateOffers(pool, 100, 90, true, false, rng))
			{
				Assert.assertNotEquals("Taskonly Boss", offer.getMonsterName());
				fiendSeen |= "Highslayer Fiend".equals(offer.getMonsterName());
			}
		}
		Assert.assertTrue("level-gated monster should roll once the level is met", fiendSeen);
	}

	@Test
	public void noMonsterAppearsTwiceInOneRoll()
	{
		GachaRng rng = new GachaRng(4242L);
		for (int trial = 0; trial < 500; trial++)
		{
			for (int cb = 20; cb <= 126; cb += 10)
			{
				List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, cb, true, true, rng);
				long distinct = offers.stream().map(TaskOffer::getMonsterName).distinct().count();
				Assert.assertEquals("duplicate monster at cb " + cb, offers.size(), distinct);
			}
		}
	}

	@Test
	public void insanePaysMoreThanEasy()
	{
		GachaRng rng = new GachaRng(11L);
		List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, 110, true, false, rng);
		TaskOffer easy = offers.get(0);
		TaskOffer insane = offers.get(3);
		Assert.assertTrue(insane.getCompletionGc() > easy.getCompletionGc() * 4);
		Assert.assertTrue(insane.getPerKillGc() > easy.getPerKillGc() * 2);
	}
}
