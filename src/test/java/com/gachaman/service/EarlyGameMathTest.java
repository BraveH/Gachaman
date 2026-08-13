package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

/** Pure math for the early-game round: combo, fragments, bestiary, stardust bands. */
public class EarlyGameMathTest
{
	@Test
	public void comboLadderIsFlatQuarterSteps()
	{
		// the whole ladder, spelled out rather than derived — the point of the
		// rewrite was that a player can read it, so a typo in the step must fail
		double[] expected = {1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5};
		Assert.assertEquals(expected.length, Tuning.COMBO_MAX_STACKS + 1);
		for (int stacks = 0; stacks < expected.length; stacks++)
		{
			Assert.assertEquals("stack " + stacks, expected[stacks],
				Tuning.comboMultiplier(stacks), 1e-9);
		}
		// above the cap clamps, below zero clamps
		Assert.assertEquals(2.5, Tuning.comboMultiplier(99), 1e-9);
		Assert.assertEquals(1.0, Tuning.comboMultiplier(-5), 1e-9);
	}

	@Test
	public void comboStacksAreEarnedEveryFiveKills()
	{
		Assert.assertEquals(0, Tuning.comboStacks(0));
		// four kills is still nothing — the stack lands on the fifth
		Assert.assertEquals(0, Tuning.comboStacks(Tuning.COMBO_KILLS_PER_STACK - 1));
		Assert.assertEquals(1, Tuning.comboStacks(Tuning.COMBO_KILLS_PER_STACK));
		Assert.assertEquals(2, Tuning.comboStacks(2 * Tuning.COMBO_KILLS_PER_STACK));
		// a maxed chain is MAX_STACKS * KILLS_PER_STACK kills deep, and holds there
		Assert.assertEquals(Tuning.COMBO_MAX_STACKS, Tuning.comboStacks(Tuning.COMBO_MAX_KILLS));
		Assert.assertEquals(Tuning.COMBO_MAX_STACKS, Tuning.comboStacks(Tuning.COMBO_MAX_KILLS * 9));
		Assert.assertEquals(0, Tuning.comboStacks(-3));
	}

	@Test
	public void everyPerKillBaseStaysWholeAcrossTheLadder()
	{
		// the ladder is only readable because a quarter step never produces a
		// fraction of a GC; a base that is not a multiple of 4 would break that
		for (int gc : Tuning.PER_KILL_GC.values())
		{
			Assert.assertEquals("base " + gc + " must be a multiple of 4", 0, gc % 4);
			for (int stacks = 0; stacks <= Tuning.COMBO_MAX_STACKS; stacks++)
			{
				double paid = gc * Tuning.comboMultiplier(stacks);
				Assert.assertEquals("base " + gc + " at stack " + stacks,
					Math.rint(paid), paid, 1e-9);
			}
		}
	}

	@Test
	public void fragmentMapping()
	{
		Assert.assertEquals(0, Tuning.fragmentsFor(TaskDifficulty.EASY));
		Assert.assertEquals(1, Tuning.fragmentsFor(TaskDifficulty.MEDIUM));
		Assert.assertEquals(2, Tuning.fragmentsFor(TaskDifficulty.HARD));
		Assert.assertEquals(3, Tuning.fragmentsFor(TaskDifficulty.INSANE));
		// all-hard across the whole window exactly forges; mediums-only misses
		Assert.assertEquals(Tuning.FRAGMENTS_REQUIRED,
			Tuning.FRAGMENT_WINDOW_TASKS * Tuning.fragmentsFor(TaskDifficulty.HARD));
		Assert.assertTrue(Tuning.FRAGMENT_WINDOW_TASKS * Tuning.fragmentsFor(TaskDifficulty.MEDIUM)
			< Tuning.FRAGMENTS_REQUIRED);
	}

	@Test
	public void bestiaryMilestonesAreConsistent()
	{
		Assert.assertEquals(Tuning.BESTIARY_MILESTONES.length, Tuning.BESTIARY_MILESTONE_GC.length);
		int prev = 0;
		for (int milestone : Tuning.BESTIARY_MILESTONES)
		{
			Assert.assertTrue(milestone > prev);
			prev = milestone;
		}
	}

	@Test
	public void rustyRollsCommonOnly()
	{
		double[] odds = Tuning.CHEST_ODDS.get(Tuning.Chest.RUSTY);
		Assert.assertEquals(0, odds[Rarity.UNCOMMON.ordinal()], 1e-9);
		Assert.assertEquals(0, odds[Rarity.RARE.ordinal()], 1e-9);
		Assert.assertEquals(0, odds[Rarity.EPIC.ordinal()], 1e-9);
		Assert.assertEquals(0, odds[Rarity.LEGENDARY.ordinal()], 1e-9);
		// structural guarantee: rollRarity can only ever land COMMON
		ChestService service = new ChestService(null, null, null, null,
			new GachaRng(42L), new com.google.gson.Gson(), null, null, null);
		for (int i = 0; i < 50_000; i++)
		{
			Assert.assertEquals(Rarity.COMMON, service.rollRarity(odds, 0));
		}
		// Rusty is priced, deals one card, and rolls no deeds
		Assert.assertEquals(150, (int) Tuning.CHEST_PRICE_GC.get(Tuning.Chest.RUSTY));
		Assert.assertEquals(1, (int) Tuning.CHEST_CARDS.get(Tuning.Chest.RUSTY));
		Assert.assertFalse(Tuning.DEED_CHANCE.containsKey(Tuning.Chest.RUSTY));
	}

	@Test
	public void shinyNearMissBandsBehave()
	{
		ChestService service = new ChestService(null, null, null, null,
			new GachaRng(1234L), new com.google.gson.Gson(), null, null, null);
		List<CardDefinition> pool = new ArrayList<>();
		pool.add(new CardDefinition(1, "Bronze scimitar", GearSlot.WEAPON, "bronze", 1,
			"scimitar", Rarity.COMMON, Set.of(1), true));

		int n = 200_000;
		int shiny = 0;
		int nearMiss = 0;
		for (int i = 0; i < n; i++)
		{
			ChestService.RolledSlot slot = service.rollSlot(pool, Rarity.COMMON, false,
				Tuning.SHINY_CHANCE, 1, Set.of());
			if (slot.getVariant() == Variant.SHINY)
			{
				shiny++;
				Assert.assertFalse("a shiny can never also be a near-miss", slot.isNearMiss());
			}
			else if (slot.isNearMiss())
			{
				nearMiss++;
			}
		}
		double shinyRate = shiny / (double) n;
		double nearMissRate = nearMiss / (double) n;
		Assert.assertEquals(Tuning.SHINY_CHANCE, shinyRate, Tuning.SHINY_CHANCE * 0.2);
		// near-miss band is (mult - 1) times the shiny mass
		double expectedNearMiss = Tuning.SHINY_CHANCE * (Tuning.STARDUST_NEAR_MISS_MULT - 1);
		Assert.assertEquals(expectedNearMiss, nearMissRate, expectedNearMiss * 0.2);
	}

	@Test
	public void blessedDoubleAttemptRoughlyDoublesShiny()
	{
		List<CardDefinition> pool = new ArrayList<>();
		pool.add(new CardDefinition(1, "Bronze scimitar", GearSlot.WEAPON, "bronze", 1,
			"scimitar", Rarity.COMMON, Set.of(1), true));
		int n = 150_000;

		ChestService single = new ChestService(null, null, null, null,
			new GachaRng(9L), new com.google.gson.Gson(), null, null, null);
		int shinySingle = 0;
		for (int i = 0; i < n; i++)
		{
			if (single.rollSlot(pool, Rarity.COMMON, false, Tuning.SHINY_CHANCE, 1, Set.of())
				.getVariant() == Variant.SHINY)
			{
				shinySingle++;
			}
		}
		ChestService blessed = new ChestService(null, null, null, null,
			new GachaRng(9L), new com.google.gson.Gson(), null, null, null);
		int shinyBlessed = 0;
		for (int i = 0; i < n; i++)
		{
			if (blessed.rollSlot(pool, Rarity.COMMON, false, Tuning.SHINY_CHANCE, 2, Set.of())
				.getVariant() == Variant.SHINY)
			{
				shinyBlessed++;
			}
		}
		Assert.assertTrue("blessed (" + shinyBlessed + ") must clearly beat single (" + shinySingle + ")",
			shinyBlessed > shinySingle * 1.6);
	}

	@Test
	public void unshinyEligibleCardsNeverSparkle()
	{
		ChestService service = new ChestService(null, null, null, null,
			new GachaRng(5L), new com.google.gson.Gson(), null, null, null);
		List<CardDefinition> pool = new ArrayList<>();
		pool.add(new CardDefinition(2, "Training sword", GearSlot.WEAPON, null, 0,
			null, Rarity.COMMON, Set.of(2), false));
		for (int i = 0; i < 10_000; i++)
		{
			ChestService.RolledSlot slot = service.rollSlot(pool, Rarity.COMMON, false,
				Tuning.RUSTY_SHINY_CHANCE, 2, Set.of());
			Assert.assertEquals(Variant.NORMAL, slot.getVariant());
			Assert.assertFalse(slot.isNearMiss());
		}
	}
}
