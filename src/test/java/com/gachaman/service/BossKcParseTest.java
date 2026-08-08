package com.gachaman.service;

import com.gachaman.data.BossTable;
import com.google.gson.Gson;
import java.util.regex.Matcher;
import org.junit.Assert;
import org.junit.Test;

/**
 * The KC line and the curated chatName have to agree. They did not for Barrows:
 * the in-game line is "Your Barrows chest count is: 5", and because the name is
 * captured lazily up to the counter noun it yields "Barrows" — which never
 * matched the old chatName of "Barrows chest", so the milestone never fired.
 */
public class BossKcParseTest
{
	private static String capturedName(String message)
	{
		Matcher matcher = BossKcService.KC_PATTERN.matcher(message);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static int capturedCount(String message)
	{
		Matcher matcher = BossKcService.KC_PATTERN.matcher(message);
		return matcher.find() ? Integer.parseInt(matcher.group(2).replace(",", "")) : -1;
	}

	@Test
	public void barrowsChestLineCapturesTheConfiguredName()
	{
		Assert.assertEquals("Barrows", capturedName("Your Barrows chest count is: 5."));
	}

	@Test
	public void killLinesCaptureNamesWithPunctuationAndSpaces()
	{
		Assert.assertEquals("Zulrah", capturedName("Your Zulrah kill count is: 1."));
		Assert.assertEquals("K'ril Tsutsaroth", capturedName("Your K'ril Tsutsaroth kill count is: 42."));
		Assert.assertEquals("Thermonuclear smoke devil",
			capturedName("Your Thermonuclear smoke devil kill count is: 300."));
	}

	@Test
	public void thousandsSeparatorIsParsed()
	{
		Assert.assertEquals(1234, capturedCount("Your Kraken kill count is: 1,234."));
	}

	@Test
	public void nonKcChatIsIgnored()
	{
		Assert.assertNull(capturedName("Your Slayer task is complete."));
		Assert.assertNull(capturedName("Congratulations, you just advanced an Attack level."));
	}

	/**
	 * Every curated boss must be reachable: build the line the game would send
	 * and assert it captures back to exactly that boss's chatName.
	 */
	@Test
	public void everyCuratedBossChatNameRoundTrips()
	{
		BossTable table = BossTable.load(new Gson());
		Assert.assertFalse(table.getBosses().isEmpty());
		for (BossTable.Boss boss : table.getBosses())
		{
			String noun = "Barrows".equals(boss.getChatName()) ? "chest" : "kill";
			String line = "Your " + boss.getChatName() + " " + noun + " count is: 10.";
			Assert.assertEquals("KC line unparseable for " + boss.getBossName(),
				boss.getChatName(), capturedName(line));
		}
	}
}
