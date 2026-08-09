package com.gachaman.party;

import org.junit.Assert;
import org.junit.Test;

/**
 * The display name the Party page and its chat lines put on a remote member.
 *
 * Every value here is self-reported over the party channel, so the name is the
 * one field a hostile or merely broken client can point anywhere: absent, blank,
 * the literal "&lt;unknown&gt;" RuneLite hands out before a member's name has
 * arrived, or long enough to widen the whole tab. The tab has no horizontal
 * scrollbar, so an over-long name does not ellipsise — it clips every row's
 * right edge instead.
 */
public class PartyPresenceNameTest
{
	@Test
	public void anAbsentNameFallsBackToAPhrase()
	{
		// null and blank both mean "the name has not arrived yet", and both get
		// a phrase that reads correctly in the chat lines that interpolate it
		// ("A party member accepted the contract")
		Assert.assertEquals("A party member", PartyPresenceService.memberName(null));
		Assert.assertEquals("A party member", PartyPresenceService.memberName(""));
		Assert.assertEquals("A party member", PartyPresenceService.memberName("   "));
		Assert.assertEquals("A party member", PartyPresenceService.memberName("\t\n "));
	}

	@Test
	public void runelitesPlaceholderIsNotPrintedRaw()
	{
		// the party plugin uses this literal before a member's name resolves;
		// printing it would also hand angle brackets to the chat tag parser,
		// which eats everything between them
		Assert.assertEquals("A party member", PartyPresenceService.memberName("<unknown>"));
		Assert.assertEquals("A party member", PartyPresenceService.memberName("  <unknown>  "));
	}

	@Test
	public void anOrdinaryNameSurvivesIntact()
	{
		Assert.assertEquals("Zezima", PartyPresenceService.memberName("Zezima"));
		// RSN space, and the surrounding whitespace the wire may carry
		Assert.assertEquals("Iron Talka", PartyPresenceService.memberName("  Iron Talka  "));
	}

	@Test
	public void anOverlongNameIsCutToTheColumnBudget()
	{
		// a real RSN is at most 12 characters; anything past the cap is a
		// malformed or hostile broadcast, and the cap is what keeps it from
		// widening a tab that cannot scroll sideways
		String flood = repeat("x", 400);
		String clipped = PartyPresenceService.memberName(flood);
		Assert.assertEquals(40, clipped.length());
		Assert.assertEquals(repeat("x", 40), clipped);
	}

	@Test
	public void aNameExactlyAtTheCapIsNotTouched()
	{
		String exact = repeat("y", 40);
		Assert.assertEquals(exact, PartyPresenceService.memberName(exact));
		Assert.assertEquals(repeat("y", 40), PartyPresenceService.memberName(repeat("y", 41)));
	}

	@Test
	public void everyResultIsUsableAsASentenceSubject()
	{
		for (String candidate : new String[]{null, "", "   ", "<unknown>", "Zezima",
			repeat("z", 400)})
		{
			String name = PartyPresenceService.memberName(candidate);
			Assert.assertNotNull(name);
			Assert.assertFalse("blank name from " + candidate, name.trim().isEmpty());
			Assert.assertTrue("too long from " + candidate, name.length() <= 40);
		}
	}

	/** Java 8 has no String.repeat. */
	private static String repeat(String unit, int times)
	{
		StringBuilder builder = new StringBuilder(unit.length() * times);
		for (int i = 0; i < times; i++)
		{
			builder.append(unit);
		}
		return builder.toString();
	}
}
