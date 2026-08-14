package com.gachaman.overlay;

import java.awt.*;
import org.junit.*;

/**
 * The "one left" signal: at exactly one kill remaining, the contract's targets
 * and its progress bar turn a pulsing gold.
 *
 * <p>The overlays themselves need a live Client, so what is pinned here is the
 * pure piece — the pulse — plus the arithmetic both halves share. The two halves
 * MUST agree: {@code TaskNpcHighlightOverlay} is behind a config toggle, so if
 * they ever disagreed a player with highlighting off would get no warning while
 * a player with it on got two.
 */
public class OneLeftTest
{
	/** The same expression both overlays use, kept here as the spec. */
	private static int remaining(int required, int done, int partyOther, boolean party)
	{
		return required - (done + (party ? partyOther : 0));
	}

	@Test
	public void theSignalIsExactlyOneKillOut()
	{
		Assert.assertEquals(1, remaining(20, 19, 0, false));
		Assert.assertEquals(2, remaining(20, 18, 0, false));
		// and it is == 1, never <= 1: a completion landing mid-frame, or pooled
		// party kills overshooting the quota, must not re-arm it at zero or below
		Assert.assertEquals(0, remaining(20, 20, 0, false));
		Assert.assertEquals(-1, remaining(20, 21, 0, false));
	}

	@Test
	public void aSharedContractCountsTheWholeParty()
	{
		// 12 of mine + 7 of theirs against 20 is one out, even though my own
		// kill count alone is nowhere near it
		Assert.assertEquals(1, remaining(20, 12, 7, true));
		// the same numbers on a SOLO contract are not one out
		Assert.assertEquals(8, remaining(20, 12, 7, false));
	}

	@Test
	public void thePulseStaysInsideAVisibleAlphaBand()
	{
		// it breathes rather than blinks: never invisible, never flat
		int min = 255;
		int max = 0;
		for (int i = 0; i < 400; i++)
		{
			Color c = TaskNpcHighlightOverlay.finalTargetGold();
			Assert.assertEquals(255, c.getRed());
			Assert.assertEquals(205, c.getGreen());
			Assert.assertEquals(70, c.getBlue());
			min = Math.min(min, c.getAlpha());
			max = Math.max(max, c.getAlpha());
			try
			{
				Thread.sleep(1);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
		Assert.assertTrue("never fades out of sight: " + min, min >= 165);
		Assert.assertTrue("never exceeds opaque: " + max, max <= 255);
		Assert.assertTrue("it actually moves", max > min);
	}
}
