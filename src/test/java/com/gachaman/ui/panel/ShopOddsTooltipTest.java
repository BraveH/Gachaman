package com.gachaman.ui.panel;

import org.junit.Assert;
import org.junit.Test;

/**
 * The percentage the Chest Odds rows print, and the one place it is handed to a
 * Swing HTML parser.
 *
 * A tier whose chance rounds to 0.0% would read as impossible — the one claim
 * the headroom band must never make — so {@link ShopTab#pct} floors it at
 * "&lt;0.1%" instead. That answer opens with a literal {@code <}, and the folded
 * "+N more tiers" tooltip is built as {@code <html>}: Swing's parser is HTML 3.2
 * and reads {@code <0.1%} as the start of a tag, swallowing every row after it.
 * Escaping is what keeps the two compatible, so both halves are pinned here.
 */
public class ShopOddsTooltipTest
{
	@Test
	public void aVanishinglySmallChanceIsFlooredNotRoundedAway()
	{
		// 0.04% — non-zero, but below the 0.05 that "%.1f" would round up
		Assert.assertEquals("<0.1%", ShopTab.pct(0.0004));
		Assert.assertEquals("<0.1%", ShopTab.pct(0.0000001));
	}

	@Test
	public void anImpossibleRowIsStillPrintedAsZero()
	{
		// exactly zero is a real answer, not a rounding artefact, so it does not
		// get the "<" treatment — a locked tier should read 0.0%
		Assert.assertEquals("0.0%", ShopTab.pct(0));
	}

	@Test
	public void ordinaryChancesKeepOneDecimal()
	{
		Assert.assertEquals("3.3%", ShopTab.pct(0.033));
		Assert.assertEquals("100.0%", ShopTab.pct(1));
		// at and above the floor boundary the normal format takes over again
		Assert.assertEquals("0.1%", ShopTab.pct(0.0005));
	}

	@Test
	public void theFlooredAnswerSurvivesTheHtmlTooltip()
	{
		// what addBand actually appends; unescaped, the parser eats from here on
		Assert.assertEquals("&lt;0.1%", GachamanPanel.escape(ShopTab.pct(0.0004)));
		Assert.assertFalse(GachamanPanel.escape(ShopTab.pct(0.0004)).contains("<"));
	}

	@Test
	public void everyPercentageIsSafeToInterpolate()
	{
		for (double fraction : new double[]{0, 0.0000001, 0.0004, 0.0005, 0.033, 0.5, 1})
		{
			String escaped = GachamanPanel.escape(ShopTab.pct(fraction));
			Assert.assertFalse("raw < from " + fraction, escaped.contains("<"));
			Assert.assertFalse("raw > from " + fraction, escaped.contains(">"));
		}
	}

	@Test
	public void escapingHandlesTheAmpersandFirst()
	{
		// order matters: replacing "<" before "&" would turn a tier name's own
		// ampersand into "&amp;lt;" and print the entity as visible text
		Assert.assertEquals("&amp;lt;", GachamanPanel.escape("&lt;"));
		Assert.assertEquals("Saradomin &amp; Zamorak", GachamanPanel.escape("Saradomin & Zamorak"));
		Assert.assertEquals("", GachamanPanel.escape(null));
	}
}
