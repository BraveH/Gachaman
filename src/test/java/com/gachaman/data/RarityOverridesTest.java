package com.gachaman.data;

import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

/** Landmarks from the 2026-08 full-database rarity audit + curated originals. */
public class RarityOverridesTest
{
	/**
	 * Injected in the plugin — the Plugin Hub forbids a fresh Gson in shipped
	 * code — but a test has no injector, so it builds its own.
	 */
	private static final RarityOverrides OVERRIDES = new RarityOverrides(new Gson());

	@Test
	public void auditLandmarksResolve()
	{
		Assert.assertEquals(Rarity.LEGENDARY, OVERRIDES.lookup("Max cape"));
		Assert.assertEquals(Rarity.LEGENDARY, OVERRIDES.lookup("3rd age druidic robe top"));
		Assert.assertEquals(Rarity.LEGENDARY, OVERRIDES.lookup("Justiciar chestguard"));
		Assert.assertEquals(Rarity.EPIC, OVERRIDES.lookup("Attack cape"));
		Assert.assertEquals(Rarity.EPIC, OVERRIDES.lookup("Gilded scimitar"));
		Assert.assertEquals(Rarity.RARE, OVERRIDES.lookup("Dragon scimitar"));
		Assert.assertEquals(Rarity.RARE, OVERRIDES.lookup("Black mask"));
		Assert.assertEquals(Rarity.RARE, OVERRIDES.lookup("Magic shortbow"));
		Assert.assertEquals(Rarity.UNCOMMON, OVERRIDES.lookup("Bone mace"));
		Assert.assertEquals(Rarity.UNCOMMON, OVERRIDES.lookup("Air battlestaff"));
		Assert.assertEquals(Rarity.COMMON, OVERRIDES.lookup("Training sword"));
		Assert.assertEquals(Rarity.COMMON, OVERRIDES.lookup("Frying pan"));
	}

	@Test
	public void curatedOriginalsSurviveTheAuditMerge()
	{
		Assert.assertEquals(Rarity.LEGENDARY, OVERRIDES.lookup("Twisted bow"));
		Assert.assertEquals(Rarity.EPIC, OVERRIDES.lookup("Abyssal whip"));
		Assert.assertEquals(Rarity.RARE, OVERRIDES.lookup("Barrows gloves"));
		Assert.assertNull(OVERRIDES.lookup("Bronze dagger"));
	}
}
