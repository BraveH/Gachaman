package com.gachaman.ui.panel;

import com.gachaman.*;
import java.awt.*;
import org.junit.*;

/**
 * The Shop tab's per-tier lookups are ordinal-indexed arrays rather than
 * switches, and this is the safety net that trade depends on.
 *
 * <p>A {@code switch} with a {@code default:} arm answers SOMETHING for a
 * constant nobody wrote a case for — in ShopTab's case it answered "Ornate" for
 * every unhandled tier, quietly. The arrays throw instead, which is the better
 * failure, but only if the mismatch is caught here rather than at paint time:
 * add a fifth chest tier and these assertions fail before anything renders.
 *
 * <p>The expected values are the literals the switches returned, written out
 * again on purpose. Deriving them from the arrays under test would assert
 * nothing.
 */
public class ShopTabTablesTest
{
	@Test
	public void everyChestTierHasANameAndATrimColour()
	{
		Assert.assertEquals("a chest tier has no display name",
			Tuning.Chest.values().length, ShopTab.CHEST_NAMES.length);
		Assert.assertEquals("a chest tier has no trim colour",
			Tuning.Chest.values().length, ShopTab.CHEST_TRIMS.length);
	}

	@Test
	public void theNamesAreTheOnesTheSwitchReturned()
	{
		Assert.assertEquals("Rusty Chest", ShopTab.chestName(Tuning.Chest.RUSTY));
		Assert.assertEquals("Battered Chest", ShopTab.chestName(Tuning.Chest.BATTERED));
		Assert.assertEquals("Gilded Chest", ShopTab.chestName(Tuning.Chest.GILDED));
		Assert.assertEquals("Ornate Chest", ShopTab.chestName(Tuning.Chest.ORNATE));
	}

	@Test
	public void theTrimColoursAreTheOnesTheSwitchReturned()
	{
		Assert.assertEquals(new Color(154, 96, 52), ShopTab.trimColor(Tuning.Chest.RUSTY));
		Assert.assertEquals(new Color(146, 126, 96), ShopTab.trimColor(Tuning.Chest.BATTERED));
		Assert.assertEquals(new Color(230, 190, 80), ShopTab.trimColor(Tuning.Chest.GILDED));
		Assert.assertEquals(new Color(255, 196, 60), ShopTab.trimColor(Tuning.Chest.ORNATE));
	}
}
