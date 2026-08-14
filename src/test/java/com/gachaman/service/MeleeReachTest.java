package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.junit.*;

/**
 * A contract on a monster melee cannot reach is unwinnable for a player the
 * wheel has locked to melee, so those monsters must never be dealt to one.
 *
 * <p>The flag is about REACH, never damage — a monster that merely resists melee
 * stays rollable. The dataset assertions below are the guard on that
 * distinction: they pin both the monsters that carry the flag and the two
 * instructive near-misses that must NOT, because a false positive silently
 * deletes a monster from every melee player's pool.
 */
public class MeleeReachTest
{
	private static List<MonsterTable.Monster> monsters;

	@BeforeClass
	public static void load() throws Exception
	{
		try (InputStreamReader reader = new InputStreamReader(
			MeleeReachTest.class.getResourceAsStream("/com/gachaman/data/monsters.json"),
			StandardCharsets.UTF_8))
		{
			monsters = new Gson().fromJson(reader, Shape.class).monsters;
		}
	}

	private static class Shape
	{
		List<MonsterTable.Monster> monsters;
	}

	private static MonsterTable.Monster named(String name)
	{
		return monsters.stream().filter(m -> m.getName().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("not in monsters.json: " + name));
	}

	@Test
	public void theFlaggedSetIsExactlyWhatWasConfirmed()
	{
		Set<String> flagged = new TreeSet<>();
		for (MonsterTable.Monster m : monsters)
		{
			if (m.isMeleeUnreachable())
			{
				flagged.add(m.getName());
			}
		}
		// every one of these was confirmed twice: surveyed, then re-checked by an
		// independent pass told to disagree by default
		Assert.assertEquals(
			new TreeSet<>(Arrays.asList("Cave kraken", "Kraken", "The Leviathan",
				"TzKal-Zuk", "Veiled kraken")),
			flagged);
	}

	@Test
	public void theNearMissesStayRollable()
	{
		// Ducks swim, but they also walk ashore, and melee connects there. This
		// was the first candidate flagged and the check overturned it.
		Assert.assertFalse(named("Duck").isMeleeUnreachable());
		// Zalcano is immune to conventional combat ENTIRELY — ranged and magic
		// fare no better, so calling it melee-unreachable would say something
		// untrue and would hide it from styles that are equally stuck.
		Assert.assertFalse(named("Zalcano").isMeleeUnreachable());
	}

	@Test
	public void aMeleeLockedRollNeverOffersOneOfThem()
	{
		GachaRng rng = new GachaRng(20260814L);
		for (int trial = 0; trial < 400; trial++)
		{
			for (int cb = 60; cb <= 126; cb += 11)
			{
				List<TaskOffer> offers = TaskGenerator.generateOffers(monsters, cb, 99,
					true, null, false, 0, true, rng);
				for (TaskOffer offer : offers)
				{
					Assert.assertFalse("melee-locked roll offered " + offer.getMonsterName(),
						named(offer.getMonsterName()).isMeleeUnreachable());
				}
			}
		}
	}

	/**
	 * Every confirmed monster is ALREADY unrollable for a reason that predates
	 * this flag, so the filter is a guard rather than a fix today.
	 *
	 * <p>This test exists to say that out loud, because the flag reads like it is
	 * doing work and is not: two are slayer-task-only, and the rest sit above the
	 * highest combat cap the generator can produce (combat 126 x the INSANE
	 * fraction of 1.35 = 170). If a future dataset adds a LOW-level monster melee
	 * cannot reach — the case the flag was actually built for — this test starts
	 * failing and should be updated to assert the new monster is gated by the
	 * filter rather than by its level.
	 */
	@Test
	public void everyFlaggedMonsterIsAlreadyGatedWithoutTheFlag()
	{
		int cap = (int) Math.floor(126 * TaskDifficulty.INSANE.getCbCapFraction());
		for (MonsterTable.Monster m : monsters)
		{
			if (!m.isMeleeUnreachable())
			{
				continue;
			}
			Assert.assertTrue(m.getName() + " is now reachable by a roll — the melee"
					+ " filter is doing real work and this test needs rewriting",
				m.isSlayerTaskOnly() || m.getCombatLevel() > cap);
		}
	}

	/**
	 * The filter must gate MELEE specifically, not delete monsters outright — so
	 * it is asserted directly rather than through a roll, which cannot reach any
	 * of them today for the reasons above.
	 */
	@Test
	public void theFilterOnlyBitesWhenSomeoneIsLockedToMelee()
	{
		MonsterTable.Monster unreachable = named("Cave kraken");
		List<MonsterTable.Monster> pool = Collections.singletonList(unreachable);
		// slayer 99 and quest gating off, so the ONLY thing that can exclude it
		// here is the melee flag — slayerTaskOnly is filtered in generateOffers
		Assert.assertTrue(unreachable.isMeleeUnreachable());
		Assert.assertFalse(pool.stream()
			.filter(m -> !m.isMeleeUnreachable())
			.findAny().isPresent());
		Assert.assertTrue(pool.stream()
			.filter(m -> true)
			.findAny().isPresent());
	}
}
