package com.gachaman;

import org.junit.*;

/**
 * Pins {@link GachamanPlugin#ordinal(int)} against the branch-per-case switch
 * it replaced.
 *
 * <p>The switch spelled the 11/12/13 band out as an early return and the three
 * suffixes as case arms. The current form folds the teens into a sentinel 0 and
 * chains three ternaries, which is much smaller but is exactly the kind of
 * rewrite that can slip a boundary: 11-13 and their multiples of 100 (111,
 * 1013) must be "th" even though they end in 1, 2 and 3, while 21/32/43 must
 * NOT be. Both shapes are reproduced here independently of the implementation,
 * so a future compaction that gets the band wrong fails loudly.
 *
 * <p>This is the whole reason ordinal() is package-private rather than private:
 * the milestone chat line it feeds ("your 12th completion paid x2") only prints
 * from a live client, so the arithmetic could not otherwise be checked.
 */
public class OrdinalSuffixTest
{
	/** The suffix rule written the long way, deliberately NOT the same code. */
	private static String expected(int n)
	{
		int teens = Math.abs(n) % 100;
		if (teens >= 11 && teens <= 13)
		{
			return n + "th";
		}
		switch (Math.abs(n) % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}

	@Test
	public void theFirstFewReadTheWayAPlayerExpects()
	{
		Assert.assertEquals("1st", GachamanPlugin.ordinal(1));
		Assert.assertEquals("2nd", GachamanPlugin.ordinal(2));
		Assert.assertEquals("3rd", GachamanPlugin.ordinal(3));
		Assert.assertEquals("4th", GachamanPlugin.ordinal(4));
	}

	@Test
	public void theTeensAreTheExceptionBand()
	{
		Assert.assertEquals("11th", GachamanPlugin.ordinal(11));
		Assert.assertEquals("12th", GachamanPlugin.ordinal(12));
		Assert.assertEquals("13th", GachamanPlugin.ordinal(13));
		// the band's edges: 10 and 14 are ordinary, and the band repeats every
		// hundred, which is the case a naive n % 10 rule gets wrong
		Assert.assertEquals("10th", GachamanPlugin.ordinal(10));
		Assert.assertEquals("14th", GachamanPlugin.ordinal(14));
		Assert.assertEquals("111th", GachamanPlugin.ordinal(111));
		Assert.assertEquals("112th", GachamanPlugin.ordinal(112));
		Assert.assertEquals("113th", GachamanPlugin.ordinal(113));
	}

	@Test
	public void theSuffixReturnsOutsideTheBand()
	{
		Assert.assertEquals("21st", GachamanPlugin.ordinal(21));
		Assert.assertEquals("22nd", GachamanPlugin.ordinal(22));
		Assert.assertEquals("23rd", GachamanPlugin.ordinal(23));
		Assert.assertEquals("101st", GachamanPlugin.ordinal(101));
		Assert.assertEquals("1002nd", GachamanPlugin.ordinal(1002));
	}

	@Test
	public void everyNonNegativeCompletionNumberAgreesWithTheLongForm()
	{
		// a contract count is a non-negative running total, so this range is the
		// whole reachable domain several times over
		for (int n = 0; n <= 5000; n++)
		{
			Assert.assertEquals("ordinal(" + n + ")", expected(n), GachamanPlugin.ordinal(n));
		}
	}

	@Test
	public void negativesCannotThrowOrProduceAStraySuffix()
	{
		// unreachable through the milestone path, but the old switch fell to
		// "th" on a negative n % 10 and the new sentinel form must too — pinning
		// it stops the next rewrite from "fixing" that into a crash
		for (int n = -1; n >= -200; n--)
		{
			Assert.assertTrue("ordinal(" + n + ") = " + GachamanPlugin.ordinal(n),
				GachamanPlugin.ordinal(n).endsWith("th"));
		}
		Assert.assertEquals(Integer.MIN_VALUE + "th", GachamanPlugin.ordinal(Integer.MIN_VALUE));
	}
}
