package com.gachaman.ui;

import com.gachaman.model.CardWear;
import java.awt.Rectangle;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CardRendererTest
{
	/** The album thumbnail, which is the tightest surface the wear art has to fit. */
	private static final int THUMB_W = 90;
	private static final int THUMB_H = 120;
	/**
	 * The card corner has room for four characters, not six. Integer math only,
	 * so this cannot flake on a machine with a different default Locale.
	 */
	@Test
	public void serviceTextBoundaries()
	{
		Assert.assertEquals("0", CardRenderer.serviceText(0));
		Assert.assertEquals("1", CardRenderer.serviceText(1));
		Assert.assertEquals("999", CardRenderer.serviceText(999));
		Assert.assertEquals("1.0k", CardRenderer.serviceText(1000));
		Assert.assertEquals("1.2k", CardRenderer.serviceText(1234));
		Assert.assertEquals("9.9k", CardRenderer.serviceText(9999));
		Assert.assertEquals("10k", CardRenderer.serviceText(10000));
		Assert.assertEquals("999k", CardRenderer.serviceText(999999));
		Assert.assertEquals("1m", CardRenderer.serviceText(1000000));
	}

	/**
	 * Four characters is the whole reachable domain. A kill costs at least one
	 * game tick, so 999,999,999 kills is roughly nineteen thousand years of
	 * uninterrupted combat — the badge is not sized for anything past it.
	 */
	@Test
	public void serviceTextNeverExceedsFourCharacters()
	{
		int[] samples = {0, 9, 99, 999, 1000, 5555, 9999, 10000, 87654, 999999, 1000000,
			12345678, 999999999};
		for (int n : samples)
		{
			Assert.assertTrue("too wide for the corner pill at " + n,
				CardRenderer.serviceText(n).length() <= 4);
		}
	}

	/**
	 * A card with no service record must render pixel-identically to the way it
	 * did before this feature existed, so the label span has to collapse back to
	 * the plain 4px inset.
	 */
	@Test
	public void noBadgeLeavesTheRarityLabelSpanUntouched()
	{
		Assert.assertEquals(4, CardRenderer.rarityLabelLeft(0, 90, -1));
		Assert.assertEquals(104, CardRenderer.rarityLabelLeft(100, 150, -1));
	}

	/**
	 * The badge and the centred rarity label share the top band. The label span
	 * must start clear of the pill at every size the card is ever drawn at —
	 * album thumbnails (90px) through reveal cards (150px) — and the clearance
	 * has to come from the shared geometry, not from a lucky font measurement.
	 */
	@Test
	public void theRarityLabelNeverStartsUnderTheServiceBadge()
	{
		for (int w = 40; w <= 400; w++)
		{
			for (int textW = 0; textW <= 60; textW++)
			{
				int badgeRight = CardRenderer.serviceBadgeX(0, w)
					+ CardRenderer.serviceBadgeWidth(w, textW);
				int labelLeft = CardRenderer.rarityLabelLeft(0, w, textW);
				Assert.assertTrue("label overlaps the badge at w=" + w + " textW=" + textW,
					labelLeft > badgeRight);
			}
		}
	}

	/**
	 * Reserving on the left only (rather than mirroring the margin to keep the
	 * label centred on the card) is what keeps "LEGENDARY" whole on the album
	 * thumbnail. Pin the span so a later "let's re-centre it" edit has to face
	 * the number it would cost.
	 */
	@Test
	public void theAlbumThumbnailKeepsRoomForTheLongestRarityWord()
	{
		// 90px thumbnail, a four-character record measuring ~18px at 8pt bold
		int span = (0 + 90 - 4) - CardRenderer.rarityLabelLeft(0, 90, 18);
		Assert.assertTrue("only " + span + "px left for LEGENDARY", span >= 50);
	}

	/** Only ASCII digits, '.', 'k' and 'm' — the badge font has no exotic glyphs. */
	@Test
	public void serviceTextIsPlainAscii()
	{
		int[] samples = {0, 7, 999, 1000, 4321, 9999, 10000, 250000, 1000000, 999999999};
		for (int n : samples)
		{
			Assert.assertTrue("unexpected characters at " + n,
				CardRenderer.serviceText(n).matches("[0-9.km]+"));
		}
	}

	// --- cosmetic wear (Cracked Cards) ---

	private static final int[] SEEDS = {0, 1, -1, 7, 42, -913, 100003, Integer.MIN_VALUE,
		Integer.MAX_VALUE, "Abyssal whip".hashCode(), "Bandos chestplate".hashCode(),
		"Twisted bow".hashCode()};

	/**
	 * The four sprite shapes the card layout can produce: no sprite at all, the
	 * common wide-and-short item, a narrow tall one, and a full-bleed sprite that
	 * leaves no side gutter whatsoever. The wide case mirrors the real measured
	 * rect on a 90px thumbnail (x+12..x+78, y+14..y+76).
	 */
	private static Rectangle artShape(int style, int x, int y, int w, int h)
	{
		switch (style)
		{
			case 1:
				return new Rectangle(x + w * 13 / 100, y + h * 12 / 100, w * 74 / 100, h * 52 / 100);
			case 2:
				return new Rectangle(x + w * 33 / 100, y + h * 12 / 100, w * 34 / 100, h * 52 / 100);
			case 3:
				return new Rectangle(x + 1, y + h * 12 / 100, w - 2, h * 52 / 100);
			default:
				return null;
		}
	}

	/** The band edges drawFace hands the wear pass, re-derived from its own arithmetic. */
	private static int topBandBottom(int y, int w, int h)
	{
		return y + h / 14 + 4 + Math.max(8, w / 11);
	}

	private static int nameBandTop(int y, int h)
	{
		return y + h - Math.max(16, h / 6) - h / 8;
	}

	/**
	 * The painted footprint of one round-capped segment, tested against the
	 * protected rects with arithmetic of its own. Deliberately NOT a call to
	 * CardRenderer.blocked() — wearSegments already filters with that, so reusing
	 * it would assert only that the filter agrees with itself.
	 */
	private static void assertInkIsClear(String where, int[] seg, Rectangle[] protect, float reach)
	{
		double x0 = Math.min(seg[0], seg[2]) - reach;
		double y0 = Math.min(seg[1], seg[3]) - reach;
		double x1 = Math.max(seg[0], seg[2]) + reach;
		double y1 = Math.max(seg[1], seg[3]) + reach;
		String[] names = {"the rarity label / service pill", "the item icon", "the card name"};
		for (int i = 0; i < protect.length; i++)
		{
			Rectangle r = protect[i];
			if (r == null)
			{
				continue;
			}
			boolean overlaps = x1 > r.getMinX() && x0 < r.getMaxX()
				&& y1 > r.getMinY() && y0 < r.getMaxY();
			Assert.assertFalse(where + ": gold covers " + names[i] + " " + r, overlaps);
		}
	}

	/**
	 * The whole promise of this feature in one assertion: wear is art added to a
	 * card you already own, so it may never sit on top of the three things you
	 * read the card BY. Swept across every card size the plugin draws, both
	 * origins, all four sprite shapes, all stages and a spread of name hashes,
	 * because a guarantee that holds only for the sizes someone happened to look
	 * at is not a guarantee.
	 */
	@Test
	public void crackArtNeverCoversTheIconTheNameOrTheRarityLabel()
	{
		for (int w = 40; w <= 260; w += 2)
		{
			int h = w * 29 / 20;
			int x = (w % 7) * 13;
			int y = (w % 5) * 9;
			int top = topBandBottom(y, w, h);
			int bottom = nameBandTop(y, h);
			for (int style = 0; style < 4; style++)
			{
				Rectangle art = artShape(style, x, y, w, h);
				Rectangle[] protect = CardRenderer.wearProtect(x, y, w, h, top, bottom, art);
				List<Rectangle> lanes = CardRenderer.wearCorridors(x, y, w, h, top, bottom, art);
				for (CardWear wear : CardWear.values())
				{
					for (int seed : SEEDS)
					{
						String where = "w=" + w + " style=" + style + " " + wear + " seed=" + seed;
						for (int[] seg : CardRenderer.wearSegments(w, wear, seed, lanes, protect))
						{
							assertInkIsClear(where, seg, protect, CardRenderer.wearInkReach(w));
						}
					}
				}
			}
		}
	}

	/**
	 * The routing inset must always be at least the ink reach, at every size.
	 * Corridors and the blocked() backstop are both measured in the pad, so a pad
	 * that ever fell below the reach would let the gold creep over the sprite
	 * without any check firing.
	 */
	@Test
	public void theRoutingPadAlwaysCoversTheInk()
	{
		for (int w = 1; w <= 2000; w++)
		{
			Assert.assertTrue("pad is thinner than the ink at w=" + w,
				CardRenderer.wearStrokePad(w) >= CardRenderer.wearInkReach(w));
		}
	}

	/**
	 * Safe in the sense of "draws nothing" is not good enough — this is a reward,
	 * and it has to be visible on the two surfaces it actually ships on: the 90px
	 * album thumbnail and the reveal card at its smallest and largest. Swept over
	 * a range of band edges so a font-metric difference on someone else's machine
	 * cannot silently empty the card.
	 */
	@Test
	public void everyStageIsVisibleOnTheSurfacesThatShipIt()
	{
		int[][] sizes = {{THUMB_W, THUMB_H}, {70, 101}, {150, 217}, {195, 338}};
		for (int[] size : sizes)
		{
			int w = size[0];
			int h = size[1];
			Rectangle art = artShape(1, 0, 0, w, h);
			for (int top = h / 14 + 4 + Math.max(8, w / 11) - 4; top <= h / 5; top++)
			{
				for (int bottom = nameBandTop(0, h) - 3; bottom <= nameBandTop(0, h) + 3; bottom++)
				{
					Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h, top, bottom, art);
					List<Rectangle> lanes = CardRenderer.wearCorridors(0, 0, w, h, top, bottom, art);
					for (CardWear wear : CardWear.values())
					{
						if (wear == CardWear.NONE)
						{
							continue;
						}
						String where = w + "x" + h + " top=" + top + " bottom=" + bottom + " " + wear;
						Assert.assertFalse("no wear drawn at all on " + where,
							CardRenderer.wearSegments(w, wear, 12345, lanes, protect).isEmpty());
					}
				}
			}
		}
	}

	/**
	 * Pin the actual density on the album thumbnail. "Non-empty" would still pass
	 * if a refactor thinned three cracks down to one stub, and the difference
	 * between a badge you notice and one you do not is exactly this number.
	 */
	@Test
	public void theThumbnailDrawsEveryCrackItPromises()
	{
		Rectangle art = artShape(1, 0, 0, THUMB_W, THUMB_H);
		int top = topBandBottom(0, THUMB_W, THUMB_H);
		int bottom = nameBandTop(0, THUMB_H);
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H, top, bottom, art);
		List<Rectangle> lanes = CardRenderer.wearCorridors(0, 0, THUMB_W, THUMB_H, top, bottom, art);
		Assert.assertEquals("a wide sprite should leave both gutters and the band below it",
			3, lanes.size());
		for (CardWear wear : CardWear.values())
		{
			int drawn = CardRenderer.wearSegments(THUMB_W, wear, 12345, lanes, protect).size();
			Assert.assertEquals("wrong number of segments at " + wear,
				CardRenderer.crackCount(wear) * 4, drawn);
		}
	}

	/**
	 * A card under the first threshold must render exactly as it did before this
	 * feature existed. Not "almost nothing" — nothing.
	 */
	@Test
	public void aPristineCardDrawsNoWearAtAll()
	{
		Rectangle art = artShape(1, 0, 0, THUMB_W, THUMB_H);
		List<Rectangle> lanes = CardRenderer.wearCorridors(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H), art);
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H), art);
		Assert.assertEquals(0, CardRenderer.crackCount(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.wearAlpha(CardWear.NONE));
		for (int seed : SEEDS)
		{
			Assert.assertTrue("a pristine card drew wear",
				CardRenderer.wearSegments(THUMB_W, CardWear.NONE, seed, lanes, protect).isEmpty());
		}
		Assert.assertTrue("a null stage drew wear",
			CardRenderer.wearSegments(THUMB_W, null, 7, lanes, protect).isEmpty());
	}

	/**
	 * The pattern is seeded from the card NAME, never from the clock, so a worn
	 * card is one physical object: it does not shimmer frame to frame, it looks
	 * the same after a restart, and the album thumbnail matches the reveal card.
	 */
	@Test
	public void theSameCardCracksTheSameWayEveryTime()
	{
		Rectangle art = artShape(1, 0, 0, THUMB_W, THUMB_H);
		int top = topBandBottom(0, THUMB_W, THUMB_H);
		int bottom = nameBandTop(0, THUMB_H);
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H, top, bottom, art);
		List<Rectangle> lanes = CardRenderer.wearCorridors(0, 0, THUMB_W, THUMB_H, top, bottom, art);

		int seed = "Abyssal whip".hashCode();
		List<int[]> first = CardRenderer.wearSegments(THUMB_W, CardWear.CRACKED, seed, lanes, protect);
		List<int[]> again = CardRenderer.wearSegments(THUMB_W, CardWear.CRACKED, seed, lanes, protect);
		Assert.assertEquals(first.size(), again.size());
		for (int i = 0; i < first.size(); i++)
		{
			Assert.assertArrayEquals("crack " + i + " moved between draws", first.get(i), again.get(i));
		}

		List<int[]> other = CardRenderer.wearSegments(THUMB_W, CardWear.CRACKED,
			"Bandos chestplate".hashCode(), lanes, protect);
		boolean differs = other.size() != first.size();
		for (int i = 0; !differs && i < first.size(); i++)
		{
			differs = !java.util.Arrays.equals(first.get(i), other.get(i));
		}
		Assert.assertTrue("two different cards cracked identically", differs);
	}

	/**
	 * Wear only ever accumulates, so a card that reaches a heavier stage must
	 * never show LESS than it did before. This is the trap the stage-independent
	 * pad exists to avoid: a thicker vein at SHATTERED would need a wider
	 * corridor, and a card could visibly heal on its thousandth kill.
	 */
	@Test
	public void aHeavierStageNeverShowsLessThanALighterOne()
	{
		for (int w = 60; w <= 200; w += 5)
		{
			int h = w * 29 / 20;
			int top = topBandBottom(0, w, h);
			int bottom = nameBandTop(0, h);
			for (int style = 0; style < 4; style++)
			{
				Rectangle art = artShape(style, 0, 0, w, h);
				Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h, top, bottom, art);
				List<Rectangle> lanes = CardRenderer.wearCorridors(0, 0, w, h, top, bottom, art);
				for (int seed : SEEDS)
				{
					int hairline = CardRenderer.wearSegments(w, CardWear.HAIRLINE, seed, lanes, protect).size();
					int cracked = CardRenderer.wearSegments(w, CardWear.CRACKED, seed, lanes, protect).size();
					int shattered = CardRenderer.wearSegments(w, CardWear.SHATTERED, seed, lanes, protect).size();
					String where = "w=" + w + " style=" + style + " seed=" + seed;
					Assert.assertTrue("wear went backwards at CRACKED, " + where, cracked >= hairline);
					Assert.assertTrue("wear went backwards at SHATTERED, " + where, shattered >= cracked);
				}
			}
		}
	}

	/**
	 * A badge, not damage. The gold gets denser as the record grows but never
	 * turns opaque and never shatters the face into a spiderweb — the card has to
	 * keep reading as a thing you earned, not a thing about to break.
	 */
	@Test
	public void wearReadsAsABadgeAndNotAsDamage()
	{
		Assert.assertTrue(CardRenderer.crackCount(CardWear.HAIRLINE)
			< CardRenderer.crackCount(CardWear.CRACKED));
		Assert.assertTrue(CardRenderer.crackCount(CardWear.CRACKED)
			< CardRenderer.crackCount(CardWear.SHATTERED));
		Assert.assertTrue("the top stage shreds the card face",
			CardRenderer.crackCount(CardWear.SHATTERED) <= 6);

		Assert.assertTrue(CardRenderer.wearAlpha(CardWear.HAIRLINE)
			< CardRenderer.wearAlpha(CardWear.CRACKED));
		Assert.assertTrue(CardRenderer.wearAlpha(CardWear.CRACKED)
			< CardRenderer.wearAlpha(CardWear.SHATTERED));
		for (CardWear wear : CardWear.values())
		{
			Assert.assertTrue("wear paints opaque at " + wear, CardRenderer.wearAlpha(wear) < 255);
			Assert.assertTrue("negative alpha at " + wear, CardRenderer.wearAlpha(wear) >= 0);
		}
	}

	/**
	 * A corridor that passes the width filter must always yield a legal integer
	 * line. If it did not, wearCorridors would be handing wearSegments lanes it
	 * silently refuses to draw in, and the card would lose wear at some sizes for
	 * no visible reason.
	 */
	@Test
	public void everyCorridorWideEnoughToKeepIsWideEnoughToDrawIn()
	{
		for (int w = 40; w <= 400; w += 1)
		{
			int h = w * 29 / 20;
			int top = topBandBottom(0, w, h);
			int bottom = nameBandTop(0, h);
			for (int style = 0; style < 4; style++)
			{
				Rectangle art = artShape(style, 0, 0, w, h);
				for (Rectangle lane : CardRenderer.wearCorridors(0, 0, w, h, top, bottom, art))
				{
					Assert.assertNotNull("kept an undrawable corridor " + lane + " at w=" + w,
						CardRenderer.wearSafeBox(lane, CardRenderer.wearStrokePad(w)));
				}
			}
		}
	}

	/**
	 * Corridors are the free margin, so they must not overlap the protected rects
	 * in the first place. Rejection-testing every segment is the backstop; this is
	 * the property that makes "never obscures" true by construction.
	 */
	@Test
	public void corridorsNeverOverlapTheProtectedRegions()
	{
		for (int w = 40; w <= 260; w += 2)
		{
			int h = w * 29 / 20;
			int top = topBandBottom(0, w, h);
			int bottom = nameBandTop(0, h);
			for (int style = 0; style < 4; style++)
			{
				Rectangle art = artShape(style, 0, 0, w, h);
				Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h, top, bottom, art);
				for (Rectangle lane : CardRenderer.wearCorridors(0, 0, w, h, top, bottom, art))
				{
					for (Rectangle r : protect)
					{
						Assert.assertFalse("corridor " + lane + " runs through " + r + " at w=" + w,
							r != null && r.intersects(lane));
					}
				}
			}
		}
	}

	/** Nothing paints outside the card, so the rounded-corner clip is a safety net, not the plan. */
	@Test
	public void wearStaysInsideTheCard()
	{
		for (int w = 40; w <= 260; w += 2)
		{
			int h = w * 29 / 20;
			int x = 17;
			int y = 23;
			int top = topBandBottom(y, w, h);
			int bottom = nameBandTop(y, h);
			float reach = CardRenderer.wearInkReach(w);
			for (int style = 0; style < 4; style++)
			{
				Rectangle art = artShape(style, x, y, w, h);
				Rectangle[] protect = CardRenderer.wearProtect(x, y, w, h, top, bottom, art);
				List<Rectangle> lanes = CardRenderer.wearCorridors(x, y, w, h, top, bottom, art);
				for (CardWear wear : CardWear.values())
				{
					for (int seed : SEEDS)
					{
						for (int[] s : CardRenderer.wearSegments(w, wear, seed, lanes, protect))
						{
							Assert.assertTrue("wear escaped the card at w=" + w,
								Math.min(s[0], s[2]) - reach >= x
									&& Math.max(s[0], s[2]) + reach <= x + w
									&& Math.min(s[1], s[3]) - reach >= y
									&& Math.max(s[1], s[3]) + reach <= y + h);
						}
					}
				}
			}
		}
	}
}
