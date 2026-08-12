package com.gachaman.ui.panel;

import org.junit.*;

/**
 * The Patrons page's "last shared" stamp.
 *
 * Every boundary here is a real one a player hits — a partner from last night,
 * from last month, from last year — and the whole reason the age is rendered
 * relatively is that an absolute "MMM d" with no year reads as THIS year for a
 * patron you last rolled with fourteen months ago.
 */
public class PatronsTabAgoTest
{
	private static final long DAY = 86_400_000L;

	/** An arbitrary fixed "now". Wall-clock time would make this unassertable. */
	private static final long NOW = 1_700_000_000_000L;

	@Test
	public void anUnrecordedStampPrintsNothing()
	{
		// a record written before lastSharedAt existed carries 0, and rendering
		// that would date the partnership to 1970 — the caller drops the whole
		// segment on an empty string rather than printing a wrong age
		Assert.assertEquals("", PatronsTab.ago(0, NOW));
		Assert.assertEquals("", PatronsTab.ago(-1, NOW));
	}

	@Test
	public void theFirstTwoDaysReadAsWords()
	{
		Assert.assertEquals("today", PatronsTab.ago(NOW, NOW));
		Assert.assertEquals("today", PatronsTab.ago(NOW - DAY + 1, NOW));
		Assert.assertEquals("yesterday", PatronsTab.ago(NOW - DAY, NOW));
		Assert.assertEquals("yesterday", PatronsTab.ago(NOW - 2 * DAY + 1, NOW));
	}

	@Test
	public void eachUnitTakesOverAtItsBoundary()
	{
		Assert.assertEquals("2d ago", PatronsTab.ago(NOW - 2 * DAY, NOW));
		Assert.assertEquals("6d ago", PatronsTab.ago(NOW - 6 * DAY, NOW));
		Assert.assertEquals("1w ago", PatronsTab.ago(NOW - 7 * DAY, NOW));
		Assert.assertEquals("4w ago", PatronsTab.ago(NOW - 29 * DAY, NOW));
		Assert.assertEquals("1mo ago", PatronsTab.ago(NOW - 30 * DAY, NOW));
		Assert.assertEquals("12mo ago", PatronsTab.ago(NOW - 364 * DAY, NOW));
		Assert.assertEquals("1y ago", PatronsTab.ago(NOW - 365 * DAY, NOW));
		Assert.assertEquals("3y ago", PatronsTab.ago(NOW - 1200 * DAY, NOW));
	}

	@Test
	public void aBackwardsClockReadsAsToday()
	{
		// a resync, a save copied from another machine, or a hand-edited stamp
		// can all put the last contract in the future. "shared -3 days ago" is
		// the one answer here that is certainly wrong.
		Assert.assertEquals("today", PatronsTab.ago(NOW + DAY, NOW));
		Assert.assertEquals("today", PatronsTab.ago(NOW + 500 * DAY, NOW));
	}

	@Test
	public void everyAgeFitsTheColumn()
	{
		// the sidebar is ~200px and this shares a line with the tier label, so a
		// long form would wrap the row and push the next patron off-screen
		for (long days = 0; days <= 4000; days++)
		{
			String text = PatronsTab.ago(NOW - days * DAY, NOW);
			Assert.assertNotNull(text);
			Assert.assertFalse("no age may be blank at " + days + " days", text.isEmpty());
			Assert.assertTrue("too long at " + days + " days: " + text, text.length() <= 9);
		}
	}
}
