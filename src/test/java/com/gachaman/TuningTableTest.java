package com.gachaman;

import com.gachaman.model.*;
import org.junit.*;

/**
 * Guards the positional enum tables in {@link Tuning}.
 *
 * <p>Those tables no longer name their keys — the Nth value belongs to the Nth
 * enum constant — which trades a compile-time safety net for token budget. This
 * test IS the replacement net, and it costs nothing: the Plugin Hub bot counts
 * only src/main/java.
 *
 * <p>What it catches: a value list shorter or longer than its enum, an enum
 * gaining a constant without its tables gaining a row, and the deliberate gap
 * (a Rusty chest rolls no deeds) being closed by accident.
 */
public class TuningTableTest
{
	@Test
	public void everyTableCoversItsWholeEnum()
	{
		Assert.assertEquals(TaskDifficulty.values().length, Tuning.PER_KILL_GC.size());
		Assert.assertEquals(TaskDifficulty.values().length, Tuning.COMPLETION_GC.size());
		Assert.assertEquals(Tuning.Chest.values().length, Tuning.CHEST_PRICE_GC.size());
		Assert.assertEquals(Tuning.Chest.values().length, Tuning.CHEST_CARDS.size());
		Assert.assertEquals(Tuning.Chest.values().length, Tuning.CHEST_ODDS.size());
		Assert.assertEquals(Rarity.values().length, Tuning.DUPLICATE_GC.size());
		Assert.assertEquals(Rarity.values().length, Tuning.SHOP_PRICE_GC.size());
	}

	@Test
	public void positionalRowsLandOnTheRightConstants()
	{
		// spelled out against the enum BY NAME, which is exactly what the table
		// literals stopped doing — so a reordered enum fails here loudly
		Assert.assertEquals(4, (int) Tuning.PER_KILL_GC.get(TaskDifficulty.EASY));
		Assert.assertEquals(28, (int) Tuning.PER_KILL_GC.get(TaskDifficulty.INSANE));
		Assert.assertEquals(400, (int) Tuning.COMPLETION_GC.get(TaskDifficulty.EASY));
		Assert.assertEquals(3600, (int) Tuning.COMPLETION_GC.get(TaskDifficulty.INSANE));
		Assert.assertEquals(150, (int) Tuning.CHEST_PRICE_GC.get(Tuning.Chest.RUSTY));
		Assert.assertEquals(1000, (int) Tuning.CHEST_PRICE_GC.get(Tuning.Chest.ORNATE));
		Assert.assertEquals(3, (int) Tuning.CHEST_CARDS.get(Tuning.Chest.ORNATE));
		Assert.assertEquals(25, (int) Tuning.DUPLICATE_GC.get(Rarity.COMMON));
		Assert.assertEquals(1000, (int) Tuning.DUPLICATE_GC.get(Rarity.LEGENDARY));
		Assert.assertEquals(800, (int) Tuning.SHOP_PRICE_GC.get(Rarity.COMMON));
		Assert.assertEquals(20000, (int) Tuning.SHOP_PRICE_GC.get(Rarity.LEGENDARY));
		// Rusty rolls COMMON only; Ornate carries the whole spread
		Assert.assertEquals(100.0, Tuning.CHEST_ODDS.get(Tuning.Chest.RUSTY)[0], 1e-9);
		Assert.assertEquals(3.5, Tuning.CHEST_ODDS.get(Tuning.Chest.ORNATE)[4], 1e-9);
	}

	@Test
	public void theOneDeliberateGapStaysAGap()
	{
		// a null row means "absent", not "zero" — Rusty must roll no deeds at all
		Assert.assertFalse(Tuning.DEED_CHANCE.containsKey(Tuning.Chest.RUSTY));
		Assert.assertEquals(Tuning.Chest.values().length - 1, Tuning.DEED_CHANCE.size());
		Assert.assertTrue(Tuning.DEED_CHANCE.get(Tuning.Chest.ORNATE)
			> Tuning.DEED_CHANCE.get(Tuning.Chest.BATTERED));
	}
}
