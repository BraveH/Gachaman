package com.gachaman.data;

import com.gachaman.model.Rarity;
import org.junit.Assert;
import org.junit.Test;

public class CardNameRulesTest
{
	@Test
	public void cardNameCleaningRules()
	{
		Assert.assertEquals("Dharok's helm", CardDatabase.cleanName("Dharok's helm 100"));
		Assert.assertEquals("Abyssal whip", CardDatabase.cleanName("Abyssal whip"));
		Assert.assertEquals("Dragon dagger", CardDatabase.cleanName("Dragon dagger (p++)"));
		Assert.assertEquals("Amulet of glory", CardDatabase.cleanName("Amulet of glory (4)"));
		Assert.assertEquals("Toxic blowpipe", CardDatabase.cleanName("Toxic blowpipe (empty)"));
		Assert.assertEquals("Rune platebody", CardDatabase.cleanName("Rune platebody (g)"));
	}

	@Test
	public void rarityBandsAreOrdered()
	{
		Assert.assertEquals(Rarity.COMMON, CardDatabase.rarityForRank(1));
		Assert.assertEquals(Rarity.UNCOMMON, CardDatabase.rarityForRank(4));
		Assert.assertEquals(Rarity.RARE, CardDatabase.rarityForRank(7));
		Assert.assertEquals(Rarity.EPIC, CardDatabase.rarityForRank(8));
		Assert.assertEquals(Rarity.COMMON, CardDatabase.rarityForPower(3));
		Assert.assertEquals(Rarity.LEGENDARY, CardDatabase.rarityForPower(150));
	}
}
