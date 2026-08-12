package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDefinition;
import com.gachaman.model.GearSlot;
import com.gachaman.model.Rarity;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/** Pure math for the early-game round: combo, fragments, bestiary, stardust bands. */
public class EarlyGameMathTest
{
	@Test
	public void comboMaxBonusFadesToPermanentFloor()
	{
		Assert.assertEquals(Tuning.COMBO_MAX_BONUS_LOW, Tuning.comboMaxBonus(3), 1e-9);
		Assert.assertEquals(Tuning.COMBO_MAX_BONUS_LOW, Tuning.comboMaxBonus(Tuning.COMBO_FADE_START_CB), 1e-9);
		Assert.assertEquals(Tuning.COMBO_MAX_BONUS_FLOOR, Tuning.comboMaxBonus(Tuning.COMBO_FADE_END_CB), 1e-9);
		// the floor is permanent — never zero, even at max combat
		Assert.assertEquals(Tuning.COMBO_MAX_BONUS_FLOOR, Tuning.comboMaxBonus(126), 1e-9);
		// midpoint interpolates linearly
		int mid = (Tuning.COMBO_FADE_START_CB + Tuning.COMBO_FADE_END_CB) / 2;
		Assert.assertEquals((Tuning.COMBO_MAX_BONUS_LOW + Tuning.COMBO_MAX_BONUS_FLOOR) / 2,
			Tuning.comboMaxBonus(mid), 1e-9);
		// monotonic non-increasing
		double prev = Double.MAX_VALUE;
		for (int cb = 3; cb <= 126; cb++)
		{
			double bonus = Tuning.comboMaxBonus(cb);
			Assert.assertTrue("fade must never increase (cb " + cb + ")", bonus <= prev);
			Assert.assertTrue(bonus >= Tuning.COMBO_MAX_BONUS_FLOOR);
			prev = bonus;
		}
	}

	@Test
	public void comboMultiplierStacksAndClamps()
	{
		Assert.assertEquals(1.0, Tuning.comboMultiplier(0, 3), 1e-9);
		Assert.assertEquals(1.0 + Tuning.COMBO_MAX_BONUS_LOW, Tuning.comboMultiplier(10, 3), 1e-9);
		// above the cap clamps, below zero clamps
		Assert.assertEquals(Tuning.comboMultiplier(10, 3), Tuning.comboMultiplier(99, 3), 1e-9);
		Assert.assertEquals(1.0, Tuning.comboMultiplier(-5, 3), 1e-9);
		// per-stack step is maxBonus / MAX_STACKS
		Assert.assertEquals(1.0 + Tuning.COMBO_MAX_BONUS_LOW / Tuning.COMBO_MAX_STACKS,
			Tuning.comboMultiplier(1, 3), 1e-9);
		// at high combat the full chain still pays the floor
		Assert.assertEquals(1.0 + Tuning.COMBO_MAX_BONUS_FLOOR, Tuning.comboMultiplier(10, 100), 1e-9);
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
