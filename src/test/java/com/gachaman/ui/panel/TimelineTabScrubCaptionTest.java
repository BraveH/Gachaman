package com.gachaman.ui.panel;

import java.text.*;
import java.util.*;
import org.junit.*;

/**
 * The caption under the Timeline's scrub slider.
 *
 * It updates on every drag tick, so it is read more than any other line on the
 * page, and the count it carries walks down through one on the way to zero —
 * which is exactly where "(1 events)" used to appear.
 *
 * The date half is deliberately compared against a locally built formatter
 * rather than a hardcoded string: the pattern pins the locale but not the
 * timezone, so a literal expectation would pass only on this machine.
 */
public class TimelineTabScrubCaptionTest
{
	/** Same pattern and locale TimelineTab uses; default zone, as it does. */
	private static final SimpleDateFormat RANGE =
		new SimpleDateFormat("d MMM yy HH:mm", Locale.ENGLISH);

	private static final long WHEN = 1_700_000_000_000L;

	private static String stamp(long millis)
	{
		return RANGE.format(new Date(millis));
	}

	@Test
	public void oneEventIsSingular()
	{
		Assert.assertEquals("Up to " + stamp(WHEN) + "  (1 event)",
			TimelineTab.scrubCaption(WHEN, 1));
	}

	@Test
	public void everyOtherCountIsPlural()
	{
		// zero included: an empty window is "0 events", never "0 event"
		Assert.assertEquals("Up to " + stamp(WHEN) + "  (0 events)",
			TimelineTab.scrubCaption(WHEN, 0));
		Assert.assertEquals("Up to " + stamp(WHEN) + "  (2 events)",
			TimelineTab.scrubCaption(WHEN, 2));
		Assert.assertEquals("Up to " + stamp(WHEN) + "  (500 events)",
			TimelineTab.scrubCaption(WHEN, 500));
	}

	@Test
	public void theCaptionTracksTheScrubPosition()
	{
		// the slider is the only thing that moves this value, so two positions
		// an hour apart must not render the same caption
		String early = TimelineTab.scrubCaption(WHEN, 3);
		String later = TimelineTab.scrubCaption(WHEN + 3_600_000L, 3);
		Assert.assertNotEquals(early, later);
		Assert.assertTrue(early.startsWith("Up to "));
		Assert.assertTrue(later.startsWith("Up to "));
	}

	@Test
	public void theCaptionFitsTheColumn()
	{
		// a non-scrolling tab: 230px at ~4.6px/char for the small font is about
		// 50 characters, and this label does not wrap
		for (int shown : new int[]{0, 1, 9, 10, 99, 100, 999, 9999})
		{
			String text = TimelineTab.scrubCaption(WHEN, shown);
			Assert.assertTrue("too long at " + shown + ": " + text, text.length() <= 44);
		}
	}
}
