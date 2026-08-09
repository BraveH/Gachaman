package com.gachaman.ui.panel;

import com.gachaman.service.ChestService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The tooltip on a tier row in the Chest Odds bands.
 *
 * A tier ladder legitimately lands in BOTH bands at once — the Defence gate is
 * applied to the body only, and metal-prefixed ranged gear is measured against
 * Ranged rather than the melee rank table — so "Hardleather" appears under
 * Wieldable now AND under Headroom. That reads as double-counting until the
 * tooltip names the pieces that are actually the ones out of reach, which is
 * what these tests pin.
 */
public class ShopBandTooltipTest
{
	private static ChestService.TierOdds row(String name, boolean wieldable, double probability,
		String... cards)
	{
		return new ChestService.TierOdds(name.toLowerCase(java.util.Locale.ROOT), name, wieldable,
			probability, Arrays.asList(cards));
	}

	@Test
	public void aWieldableRowJustStatesItsOdds()
	{
		// the cards behind a reachable row are not news — the player can wear them
		String tip = ShopTab.rowTooltip(row("Bronze", true, 0.039, "Bronze med helm"), false);
		Assert.assertEquals("<html>Bronze - 3.9% per card</html>", tip);
	}

	@Test
	public void aWieldableRowStaysQuietEvenWhenTheLadderIsSplit()
	{
		String tip = ShopTab.rowTooltip(row("Hardleather", true, 0.001, "Hard leather chaps"), true);
		Assert.assertFalse(tip.contains("Hard leather chaps"));
		Assert.assertFalse(tip.contains("out of reach"));
	}

	@Test
	public void aHeadroomRowNamesTheCardsBehindIt()
	{
		String tip = ShopTab.rowTooltip(
			row("Black", false, 0.01, "Black platebody", "Black kiteshield"), false);
		Assert.assertTrue(tip, tip.contains("Out of reach:"));
		Assert.assertTrue(tip, tip.contains("Black platebody"));
		Assert.assertTrue(tip, tip.contains("Black kiteshield"));
	}

	@Test
	public void aSplitLadderExplainsWhyItAppearsTwice()
	{
		String tip = ShopTab.rowTooltip(
			row("Hardleather", false, 0.0004, "Hard leather body"), true);
		Assert.assertTrue(tip, tip.contains("Some Hardleather gear is already within reach."));
		Assert.assertTrue(tip, tip.contains("Still out of reach:"));
		Assert.assertTrue(tip, tip.contains("Hard leather body"));
		// the unsplit wording must not also appear, or the sentence reads twice
		Assert.assertFalse(tip, tip.contains(">Out of reach:"));
	}

	@Test
	public void theFlooredPercentageCannotCloseTheTooltip()
	{
		// pct() answers "<0.1%" here, and Swing's HTML 3.2 parser would read a raw
		// "<0" as the start of a tag and swallow every card name after it
		String tip = ShopTab.rowTooltip(row("Hardleather", false, 0.0000001, "Hard leather body"),
			true);
		Assert.assertTrue(tip, tip.contains("&lt;0.1% per card"));
		Assert.assertTrue(tip, tip.contains("Hard leather body"));
		// only the wrapper and the line breaks are real markup
		Assert.assertEquals(0, countOutsideMarkup(tip));
	}

	@Test
	public void cardNamesFromTheItemCacheAreEscaped()
	{
		String tip = ShopTab.rowTooltip(row("Black", false, 0.01, "Bow & <arrow>"), false);
		Assert.assertTrue(tip, tip.contains("Bow &amp; &lt;arrow&gt;"));
		Assert.assertFalse(tip, tip.contains("<arrow>"));
	}

	@Test
	public void anOverlongListCountsTheRemainderRatherThanDroppingIt()
	{
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			many.add("Card " + i);
		}
		String tip = ShopTab.rowTooltip(new ChestService.TierOdds("k", "Big", false, 0.5, many),
			false);
		Assert.assertTrue(tip, tip.contains("Card 0"));
		Assert.assertTrue(tip, tip.contains("Card 23"));
		Assert.assertFalse(tip, tip.contains("Card 24"));
		// the tail is disclosed as a count, never silently trimmed
		Assert.assertTrue(tip, tip.contains("+6 more"));
	}

	@Test
	public void aHeadroomRowWithNoNamesDegradesQuietly()
	{
		String empty = ShopTab.rowTooltip(
			new ChestService.TierOdds("k", "Mithril", false, 0.008, Collections.emptyList()), false);
		Assert.assertEquals("<html>Mithril - 0.8% per card</html>", empty);

		String missing = ShopTab.rowTooltip(
			new ChestService.TierOdds("k", "Mithril", false, 0.008, null), true);
		Assert.assertEquals("<html>Mithril - 0.8% per card</html>", missing);
	}

	/** Counts angle brackets that are not one of the tags this tooltip may emit. */
	private static int countOutsideMarkup(String html)
	{
		String stripped = html
			.replace("<html>", "")
			.replace("</html>", "")
			.replace("<br>", "");
		int count = 0;
		for (int i = 0; i < stripped.length(); i++)
		{
			char c = stripped.charAt(i);
			if (c == '<' || c == '>')
			{
				count++;
			}
		}
		return count;
	}
}
