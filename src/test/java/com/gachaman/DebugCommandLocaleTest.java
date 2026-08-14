package com.gachaman;

import java.util.*;
import org.junit.*;

/**
 * ::gachachest's tier argument must fold case the same way on every client.
 *
 * <p>Turkish and Azeri fold "i" to the DOTTED capital "İ", so under those
 * locales "gilded".toUpperCase() is "GİLDED" — a string no Tuning.Chest
 * constant matches. valueOf threw IllegalArgumentException and the command
 * died for those players. Locale.ROOT is the fix; this pins it by forcing the
 * hostile locale rather than trusting the machine the suite happens to run on.
 */
public class DebugCommandLocaleTest
{
	private Locale previous;

	@Before
	public void forceTurkishDefault()
	{
		previous = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr"));
	}

	@After
	public void restoreDefault()
	{
		// JUnit 4 runs the whole suite in one JVM and other code folds case
		// with the default locale (CardDatabase's name index, for one), so
		// leaving Turkish installed would break tests unrelated to this file
		Locale.setDefault(previous);
	}

	@Test
	public void everyChestTierParsesUnderAHostileLocale()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			String typed = tier.name().toLowerCase(Locale.ROOT);
			Assert.assertEquals("::gachachest " + typed,
				tier, GachamanPlugin.chestArg(new String[]{typed}));
		}
	}

	@Test
	public void mixedCaseStillParses()
	{
		Assert.assertEquals(Tuning.Chest.GILDED,
			GachamanPlugin.chestArg(new String[]{"GiLdEd"}));
	}

	@Test
	public void noArgumentMeansBattered()
	{
		Assert.assertEquals(Tuning.Chest.BATTERED, GachamanPlugin.chestArg(new String[0]));
	}

	@Test
	public void theDefaultLocaleFoldIsWhatWasBroken()
	{
		// the shape of the defect itself, so nobody "tidies" the explicit
		// Locale.ROOT back out of chestArg on the grounds that it looks noisy
		Assert.assertNotEquals("GILDED", "gilded".toUpperCase());
		Assert.assertEquals("GILDED", "gilded".toUpperCase(Locale.ROOT));
	}
}
