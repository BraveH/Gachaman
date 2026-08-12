package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import org.junit.*;

/**
 * First Colours: the steered pick, and the flag that makes the gift happen
 * exactly once per account no matter where the client dies.
 */
public class FirstColoursConstraintTest
{
	private static CardDefinition card(int id, String name)
	{
		return new CardDefinition(id, name, GearSlot.WEAPON, null, 0, null,
			Rarity.COMMON, Set.of(id), true);
	}

	private static List<CardDefinition> pool()
	{
		return new ArrayList<>(Arrays.asList(
			card(1, "Bronze scimitar"),
			card(2, "Shortbow"),
			card(3, "Staff"),
			card(4, "Iron mace")));
	}

	@Test
	public void nullConstraintHandsBackTheVerySameList()
	{
		// THE determinism guarantee. rng.pick is nextInt(list.size()), so an
		// unconstrained draw must see a list built exactly as it was before this
		// feature existed — same instance, therefore same size, therefore the
		// same number of Random.next() calls consumed. Every fixed-seed test in
		// the suite depends on this identity.
		List<CardDefinition> candidates = pool();
		Assert.assertSame(candidates, ChestService.constrained(candidates, null));
	}

	@Test
	public void aConstraintThatMatchesNothingHandsBackTheFullList()
	{
		// the steer is a preference, never a filter: a chest must always deal
		List<CardDefinition> candidates = pool();
		Predicate<CardDefinition> impossible = c -> c.getCardId() == 999;
		Assert.assertSame(candidates, ChestService.constrained(candidates, impossible));
	}

	@Test
	public void aMatchingConstraintNarrowsToExactlyThoseCardsInOrder()
	{
		List<CardDefinition> candidates = pool();
		Set<Integer> preferred = Set.of(3, 1);
		List<CardDefinition> narrowed = ChestService.constrained(candidates,
			c -> preferred.contains(c.getCardId()));

		Assert.assertEquals(2, narrowed.size());
		// relative order is preserved, so a fixed seed picks predictably
		Assert.assertEquals(1, narrowed.get(0).getCardId());
		Assert.assertEquals(3, narrowed.get(1).getCardId());
		// and the caller's list was not mutated out from under it
		Assert.assertEquals(4, candidates.size());
	}

	@Test
	public void aConstraintMatchingEverythingStillNarrowsToEverything()
	{
		List<CardDefinition> candidates = pool();
		List<CardDefinition> narrowed = ChestService.constrained(candidates, c -> true);
		Assert.assertEquals(candidates, narrowed);
	}

	@Test
	public void anEmptyListSurvivesAnyConstraint()
	{
		List<CardDefinition> empty = new ArrayList<>();
		Assert.assertSame(empty, ChestService.constrained(empty, null));
		Assert.assertSame(empty, ChestService.constrained(empty, c -> true));
		Assert.assertSame(empty, ChestService.constrained(empty, c -> false));
	}

	@Test
	public void theGiftIsDueOnlyWhenEverySafetyTermAgrees()
	{
		GachaState owed = GachaState.fresh(3).withFirstColoursChestOwed(true);
		Assert.assertTrue(ChestService.firstColoursDue(owed, false, true));

		// nothing owed: the overwhelmingly common case, every login forever
		Assert.assertFalse(ChestService.firstColoursDue(GachaState.fresh(3), false, true));
		// no state loaded yet
		Assert.assertFalse(ChestService.firstColoursDue(null, false, true));
		// a reveal is already on screen — the modal queue owns the player
		Assert.assertFalse(ChestService.firstColoursDue(owed, true, true));
		// the card database cannot yet say what anything is; rolling now would
		// draw from an empty pool
		Assert.assertFalse(ChestService.firstColoursDue(owed, false, false));
	}

	@Test
	public void theGiftNeverOverwritesAChestThePlayerPaidFor()
	{
		// a client that died mid-reveal left its outcome serialized in state.
		// Dealing the gift before recoverPending commits that blob would
		// silently destroy a bought chest, so the flag waits its turn.
		GachaState interrupted = GachaState.fresh(3)
			.withFirstColoursChestOwed(true)
			.withPendingChestBlob("{\"purchasedTier\":\"ORNATE\"}");
		Assert.assertFalse(ChestService.firstColoursDue(interrupted, false, true));

		// once that blob is committed and cleared, the gift is still owed
		Assert.assertTrue(ChestService.firstColoursDue(
			interrupted.withPendingChestBlob(null), false, true));
	}

	@Test
	public void anEstablishedSaveIsNeverRetroGiftedAChest()
	{
		// The flag is phrased as OWED, not DONE, precisely for this: a save
		// written before the field existed has no key for it, and Gson leaves a
		// missing primitive at false. False must therefore mean "nothing owed" —
		// a "firstColoursDone" flag would read as "not done" and hand every
		// established account a free chest on its next login.
		Gson gson = new Gson();
		String legacy = "{\"gc\":5000,\"allowedStyle\":\"MELEE\",\"totalTasksCompleted\":140}";
		GachaState old = gson.fromJson(legacy, GachaState.class).normalized();

		Assert.assertFalse("a missing key must never owe a gift", old.isFirstColoursChestOwed());
		Assert.assertFalse(ChestService.firstColoursDue(old, false, true));
		// and a brand-new account is not owed one either until the style rolls
		Assert.assertFalse(GachaState.fresh(3).isFirstColoursChestOwed());
	}
}
