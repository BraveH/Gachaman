package com.gachaman.ui.panel;

import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

/**
 * The indefinite article the Shop's confirm dialogs put in front of a noun.
 *
 * These dialogs are the last thing a player reads before spending GC, and the
 * two nouns feeding them are both live tables — {@code GearSlot} and the chest
 * ladder. A slot or a chest tier added later with a vowel on the front would
 * silently start printing "a Amulet chest" with nothing to catch it, so the
 * expectations below are spelled out per entry rather than derived.
 */
public class GachamanPanelArticleTest
{
	@Test
	public void aMissingNounStillGetsAnArticle()
	{
		// getDisplayName() is non-null for every enum constant today, but the
		// caller passes "" for a null slot rather than branching — so the
		// degenerate case has to produce a word, not an NPE or an empty gap
		Assert.assertEquals("a", GachamanPanel.article(null));
		Assert.assertEquals("a", GachamanPanel.article(""));
	}

	@Test
	public void everyGearSlotReadsCorrectly()
	{
		Map<GearSlot, String> expected = new LinkedHashMap<>();
		expected.put(GearSlot.HEAD, "a");
		expected.put(GearSlot.CAPE, "a");
		expected.put(GearSlot.AMULET, "an");
		expected.put(GearSlot.WEAPON, "a");
		expected.put(GearSlot.BODY, "a");
		expected.put(GearSlot.SHIELD, "a");
		expected.put(GearSlot.LEGS, "a");
		expected.put(GearSlot.HANDS, "a");
		expected.put(GearSlot.FEET, "a");
		expected.put(GearSlot.RING, "a");
		expected.put(GearSlot.AMMO, "an");

		// a new slot must be given an answer here, not inherit one by accident
		Assert.assertEquals("every gear slot needs an expected article",
			GearSlot.values().length, expected.size());

		for (Map.Entry<GearSlot, String> entry : expected.entrySet())
		{
			String name = entry.getKey().getDisplayName();
			Assert.assertEquals(name, entry.getValue(), GachamanPanel.article(name));
		}
	}

	@Test
	public void everyChestNameReadsCorrectly()
	{
		// ShopTab.chestName is private; these are its four returns verbatim
		Assert.assertEquals("a", GachamanPanel.article("Rusty Chest"));
		Assert.assertEquals("a", GachamanPanel.article("Battered Chest"));
		Assert.assertEquals("a", GachamanPanel.article("Gilded Chest"));
		Assert.assertEquals("an", GachamanPanel.article("Ornate Chest"));
	}

	@Test
	public void caseDoesNotChangeTheAnswer()
	{
		// the nouns arrive title-cased from a display name today, but the shop
		// also interpolates raw card and charge names, which are not
		Assert.assertEquals("an", GachamanPanel.article("epic"));
		Assert.assertEquals("an", GachamanPanel.article("Epic"));
		Assert.assertEquals("a", GachamanPanel.article("compactor"));
		Assert.assertEquals("a", GachamanPanel.article("Compactor"));
	}
}
