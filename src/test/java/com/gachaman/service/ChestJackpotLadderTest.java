package com.gachaman.service;

import com.gachaman.*;
import org.junit.*;

/**
 * The jackpot upgrade in ChestService.roll() promotes a chest with
 * {@code Tuning.Chest.values()[tier.ordinal() + 1]} instead of an explicit
 * if/else chain, which makes the ENUM'S DECLARATION ORDER load-bearing: it is
 * the only thing that still says BATTERED promotes to GILDED and GILDED to
 * ORNATE. Reorder or insert a tier without reading roll() and the shop starts
 * handing out the wrong box — silently, because nothing else would fail.
 *
 * <p>These are deliberately enum-only assertions with no service, no RNG and no
 * card database, so a red result here means exactly one thing: the ladder moved.
 * (Tuning's own tables — CHEST_PRICE_GC, CHEST_CARDS, CHEST_ODDS — are built
 * positionally from Chest.values() too, so the coupling this pins was already
 * real before roll() leaned on it.)
 */
public class ChestJackpotLadderTest
{
	@Test
	public void theChestLadderIsRustyBatteredGildedOrnateInThatOrder()
	{
		Assert.assertArrayEquals(
			new Tuning.Chest[]{
				Tuning.Chest.RUSTY,
				Tuning.Chest.BATTERED,
				Tuning.Chest.GILDED,
				Tuning.Chest.ORNATE,
			},
			Tuning.Chest.values());
	}

	@Test
	public void oneStepUpFromBatteredIsGildedAndFromGildedIsOrnate()
	{
		// the two promotions roll() actually performs, written the way roll()
		// writes them
		Assert.assertEquals(Tuning.Chest.GILDED,
			Tuning.Chest.values()[Tuning.Chest.BATTERED.ordinal() + 1]);
		Assert.assertEquals(Tuning.Chest.ORNATE,
			Tuning.Chest.values()[Tuning.Chest.GILDED.ordinal() + 1]);
	}

	@Test
	public void ornateIsTheTopOfTheLadderSoPromotingItWouldRunOffTheEnd()
	{
		// why roll() excludes ORNATE from the promotion rather than relying on the
		// array bound: its jackpot pays a fourth card instead of a better box, and
		// an unguarded ordinal + 1 here would be an AIOOBE on every ornate jackpot
		Assert.assertEquals(Tuning.Chest.values().length - 1, Tuning.Chest.ORNATE.ordinal());
	}

	@Test
	public void rustyIsBelowEveryOtherTierSoItCanNeverBeAPromotionTarget()
	{
		// roll() gates the jackpot on !rusty; this pins the other half of that
		// reasoning — nothing sits below RUSTY that could promote INTO it
		Assert.assertEquals(0, Tuning.Chest.RUSTY.ordinal());
	}
}
