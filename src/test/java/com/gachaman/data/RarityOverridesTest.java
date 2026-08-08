package com.gachaman.data;

import com.gachaman.model.Rarity;
import org.junit.Assert;
import org.junit.Test;

/** Landmarks from the 2026-08 full-database rarity audit + curated originals. */
public class RarityOverridesTest
{
	@Test
	public void auditLandmarksResolve()
	{
		Assert.assertEquals(Rarity.LEGENDARY, RarityOverrides.lookup("Max cape"));
		Assert.assertEquals(Rarity.LEGENDARY, RarityOverrides.lookup("3rd age druidic robe top"));
		Assert.assertEquals(Rarity.LEGENDARY, RarityOverrides.lookup("Justiciar chestguard"));
		Assert.assertEquals(Rarity.EPIC, RarityOverrides.lookup("Attack cape"));
		Assert.assertEquals(Rarity.EPIC, RarityOverrides.lookup("Gilded scimitar"));
		Assert.assertEquals(Rarity.RARE, RarityOverrides.lookup("Dragon scimitar"));
		Assert.assertEquals(Rarity.RARE, RarityOverrides.lookup("Black mask"));
		Assert.assertEquals(Rarity.RARE, RarityOverrides.lookup("Magic shortbow"));
		Assert.assertEquals(Rarity.UNCOMMON, RarityOverrides.lookup("Bone mace"));
		Assert.assertEquals(Rarity.UNCOMMON, RarityOverrides.lookup("Air battlestaff"));
		Assert.assertEquals(Rarity.COMMON, RarityOverrides.lookup("Training sword"));
		Assert.assertEquals(Rarity.COMMON, RarityOverrides.lookup("Frying pan"));
	}

	@Test
	public void curatedOriginalsSurviveTheAuditMerge()
	{
		Assert.assertEquals(Rarity.LEGENDARY, RarityOverrides.lookup("Twisted bow"));
		Assert.assertEquals(Rarity.EPIC, RarityOverrides.lookup("Abyssal whip"));
		Assert.assertEquals(Rarity.RARE, RarityOverrides.lookup("Barrows gloves"));
		Assert.assertNull(RarityOverrides.lookup("Bronze dagger"));
	}
}
