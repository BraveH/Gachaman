package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

public class FirstsServiceTest
{
	@Test
	public void payoutsCoverEveryStampAndSumJustUnder500()
	{
		int sum = 0;
		for (FirstStamp stamp : FirstStamp.values())
		{
			Integer gc = Tuning.FIRSTS_GC.get(stamp);
			Assert.assertNotNull("missing payout for " + stamp, gc);
			Assert.assertTrue(stamp + " must pay something", gc > 0);
			sum += gc;
		}
		Assert.assertEquals(495, sum);
		Assert.assertTrue("total must stay under one Battered chest",
			sum < Tuning.CHEST_PRICE_GC.get(Tuning.Chest.BATTERED));
	}

	@Test
	public void everyStampHasNameAndExplainer()
	{
		for (FirstStamp stamp : FirstStamp.values())
		{
			Assert.assertFalse(stamp + " display name blank", stamp.getDisplayName().isBlank());
			Assert.assertFalse(stamp + " explainer blank", stamp.getExplainer().isBlank());
		}
	}

	@Test
	public void alreadyClaimedNullSetMeansUnclaimed()
	{
		Assert.assertFalse(FirstsService.alreadyClaimed(null, FirstStamp.FIRST_KILL));
		Assert.assertFalse(FirstsService.alreadyClaimed(Set.of(), FirstStamp.FIRST_KILL));
		Assert.assertTrue(FirstsService.alreadyClaimed(Set.of("FIRST_KILL"), FirstStamp.FIRST_KILL));
		Assert.assertFalse(FirstsService.alreadyClaimed(Set.of("FIRST_KILL"), FirstStamp.FIRST_TASK));
	}

	@Test
	public void stampsForSlotsDetectsRarityVariantAndDupes()
	{
		List<ChestService.RolledSlot> slots = new ArrayList<>();
		// plain common non-dupe: nothing
		slots.add(new ChestService.RolledSlot(Rarity.COMMON, 1, null, Variant.NORMAL, false, false, false));
		Assert.assertTrue(FirstsService.stampsForSlots(slots).isEmpty());

		slots.clear();
		slots.add(new ChestService.RolledSlot(Rarity.UNCOMMON, 2, null, Variant.NORMAL, false, false, false));
		slots.add(new ChestService.RolledSlot(Rarity.RARE, 3, null, Variant.NORMAL, true, false, false));
		slots.add(new ChestService.RolledSlot(Rarity.LEGENDARY, 4, null, Variant.SHINY, false, false, false));
		List<FirstStamp> stamps = FirstsService.stampsForSlots(slots);
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_UNCOMMON));
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_RARE));
		Assert.assertTrue("LEGENDARY counts as Epic-or-better", stamps.contains(FirstStamp.FIRST_EPIC));
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_SHINY));
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_DUPE));

		// hologram slot (cardId -1) counts for rarity and dupes
		slots.clear();
		slots.add(new ChestService.RolledSlot(Rarity.EPIC, -1, "dragon", Variant.HOLOGRAM, true, false, false));
		stamps = FirstsService.stampsForSlots(slots);
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_EPIC));
		Assert.assertTrue(stamps.contains(FirstStamp.FIRST_DUPE));
		Assert.assertFalse(stamps.contains(FirstStamp.FIRST_SHINY));

		// no duplicates in the returned list
		slots.clear();
		slots.add(new ChestService.RolledSlot(Rarity.UNCOMMON, 5, null, Variant.NORMAL, false, false, false));
		slots.add(new ChestService.RolledSlot(Rarity.UNCOMMON, 6, null, Variant.NORMAL, false, false, false));
		stamps = FirstsService.stampsForSlots(slots);
		Assert.assertEquals(1, stamps.size());
	}
}
