package com.gachaman.ui;

import com.gachaman.model.CardWear;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	//
	// Creases and scratches cross the whole card face, ART INCLUDED — that is the
	// design, and the reason these tests no longer model a sprite rect at all. A
	// card creases through the picture. What wear may never sit on is TEXT.
	//
	// The other half of the design, and the harder one to keep by accident: every
	// card wears DIFFERENTLY and every card in a bracket wears the same AMOUNT.
	// Pattern is seeded, weight is not.

	private static final int[] SEEDS = {0, 1, -1, 7, 42, -913, 100003, Integer.MIN_VALUE,
		Integer.MAX_VALUE, "Abyssal whip".hashCode(), "Bandos chestplate".hashCode(),
		"Twisted bow".hashCode()};

	private static final CardWear[] WORN = {CardWear.HAIRLINE, CardWear.CRACKED,
		CardWear.SHATTERED};

	/** The band edges drawFace hands the wear pass, re-derived from its own arithmetic. */
	private static int topBandBottom(int y, int w, int h)
	{
		return y + h / 14 + 4 + Math.max(8, w / 11);
	}

	private static int nameBandTop(int y, int h)
	{
		return y + h - Math.max(16, h / 6) - h / 8;
	}

	private static List<int[]> ofKind(List<int[]> segments, int kind)
	{
		List<int[]> out = new ArrayList<>();
		for (int[] s : segments)
		{
			if (s[4] == kind)
			{
				out.add(s);
			}
		}
		return out;
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
		String[] names = {"the rarity label / service pill", "the card name"};
		for (int i = 0; i < protect.length; i++)
		{
			Rectangle r = protect[i];
			if (r == null)
			{
				continue;
			}
			boolean overlaps = x1 > r.getMinX() && x0 < r.getMaxX()
				&& y1 > r.getMinY() && y0 < r.getMaxY();
			Assert.assertFalse(where + ": wear covers " + names[i] + " " + r, overlaps);
		}
	}

	/**
	 * The promise this feature still makes: wear is art added to a card you
	 * already own, so it may never sit on top of the words you read the card BY.
	 * Swept across every size the plugin draws, both origins, all stages and a
	 * spread of name hashes, because a guarantee that holds only for the sizes
	 * someone happened to look at is not a guarantee.
	 *
	 * <p>Note what is NOT in the protected set any more: the item sprite. Keeping
	 * the damage out of the picture is exactly what forced the old renderer to
	 * route its cracks down the eight-pixel gutters beside the art, and that is
	 * what made them read as margin scribble instead of as wear.
	 */
	@Test
	public void wearNeverCoversTheNameOrTheRarityLabel()
	{
		for (int w = 40; w <= 260; w += 2)
		{
			int h = w * 29 / 20;
			int x = (w % 7) * 13;
			int y = (w % 5) * 9;
			Rectangle[] protect = CardRenderer.wearProtect(x, y, w, h,
				topBandBottom(y, w, h), nameBandTop(y, h));
			for (CardWear wear : CardWear.values())
			{
				for (int seed : SEEDS)
				{
					String where = "w=" + w + " " + wear + " seed=" + seed;
					for (int[] seg : CardRenderer.wearSegments(x, y, w, h, wear, seed, protect))
					{
						assertInkIsClear(where, seg, protect, CardRenderer.wearInkReach(w));
					}
				}
			}
		}
	}

	/**
	 * A crease is supposed to cross the sprite. This is the assertion that keeps
	 * the old design from creeping back in: measure the box the art actually
	 * occupies and require the fold to enter it. Without this, a well-meaning
	 * "let's keep the icon clean" edit would pass every other test in this file
	 * while undoing the entire point of the rewrite.
	 */
	@Test
	public void creasesRunStraightAcrossTheItemSprite()
	{
		int[][] sizes = {{THUMB_W, THUMB_H}, {150, 200}, {195, 338}};
		for (int[] size : sizes)
		{
			int w = size[0];
			int h = size[1];
			// the rect drawFace gives a typical wide sprite
			Rectangle art = new Rectangle(w * 13 / 100, h * 12 / 100, w * 74 / 100, h * 52 / 100);
			Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h,
				topBandBottom(0, w, h), nameBandTop(0, h));
			for (int seed : SEEDS)
			{
				boolean touched = false;
				for (int[] s : ofKind(CardRenderer.wearSegments(0, 0, w, h, CardWear.CRACKED,
					seed, protect), CardRenderer.KIND_CREASE))
				{
					touched |= art.intersectsLine(s[0], s[1], s[2], s[3]);
				}
				Assert.assertTrue("no crease reaches the art at " + w + "x" + h + " seed=" + seed,
					touched);
			}
		}
	}

	/**
	 * Safe in the sense of "draws nothing" is not good enough — this is a reward,
	 * and it has to be visible on the surfaces it actually ships on: the 90px
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
			for (int top = h / 14 + 4 + Math.max(8, w / 11) - 4; top <= h / 5; top++)
			{
				for (int bottom = nameBandTop(0, h) - 3; bottom <= nameBandTop(0, h) + 3; bottom++)
				{
					Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h, top, bottom);
					for (CardWear wear : WORN)
					{
						String where = w + "x" + h + " top=" + top + " bottom=" + bottom
							+ " " + wear;
						Assert.assertFalse("no wear drawn at all on " + where,
							CardRenderer.wearSegments(0, 0, w, h, wear, 12345, protect).isEmpty());
					}
				}
			}
		}
	}

	/**
	 * The rule that is easy to break without noticing: two cards at the same stage
	 * carry the same WEIGHT of damage, in different places. Scratches are pinned
	 * exactly — every scratch a bracket promises lands whole, which is why
	 * wearSegments confines them to the clear band rather than letting the name
	 * label quietly eat a card's third scratch.
	 *
	 * <p>Creases are pinned to a floor instead of an equality, because a crease
	 * entering through the top edge genuinely does run under the rarity label and
	 * lose its first step or two. That is the intended look; what is not
	 * acceptable is a crease losing most of itself, so two thirds is the bar.
	 */
	@Test
	public void everyCardInABracketCarriesTheSameAmountOfWear()
	{
		int steps = CardRenderer.WEAR_STEPS;
		for (int w = 60; w <= 220; w += 5)
		{
			int h = w * 29 / 20;
			Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h,
				topBandBottom(0, w, h), nameBandTop(0, h));
			for (CardWear wear : CardWear.values())
			{
				int scratchesDue = CardRenderer.scratchCount(wear) * steps;
				int creaseFloor = CardRenderer.creaseCount(wear) * steps * 2 / 3;
				for (int seed : SEEDS)
				{
					List<int[]> segs = CardRenderer.wearSegments(0, 0, w, h, wear, seed, protect);
					String where = "w=" + w + " " + wear + " seed=" + seed;
					Assert.assertEquals("this card got a different amount of scuffing, " + where,
						scratchesDue, ofKind(segs, CardRenderer.KIND_SCRATCH).size());
					Assert.assertTrue("this card lost most of a crease, " + where,
						ofKind(segs, CardRenderer.KIND_CREASE).size() >= creaseFloor);
				}
			}
		}
	}

	/**
	 * The other half of that rule: same amount, never the same picture. Every pair
	 * of seeds has to differ somewhere, or the album is a wall of stamped copies.
	 */
	@Test
	public void noTwoCardsWearInTheSamePlaces()
	{
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H));
		for (CardWear wear : WORN)
		{
			for (int i = 0; i < SEEDS.length; i++)
			{
				List<int[]> a = CardRenderer.wearSegments(0, 0, THUMB_W, THUMB_H, wear,
					SEEDS[i], protect);
				for (int j = i + 1; j < SEEDS.length; j++)
				{
					List<int[]> b = CardRenderer.wearSegments(0, 0, THUMB_W, THUMB_H, wear,
						SEEDS[j], protect);
					boolean differs = a.size() != b.size();
					for (int k = 0; !differs && k < a.size(); k++)
					{
						differs = !Arrays.equals(a.get(k), b.get(k));
					}
					Assert.assertTrue("seeds " + SEEDS[i] + " and " + SEEDS[j]
						+ " wear identically at " + wear, differs);
				}
			}
		}
	}

	/**
	 * The first crease of every card is forced left-to-right through the clear
	 * band between the two text strips, which is what makes the visibility test
	 * hold for EVERY seed rather than for the lucky ones. Pin it: a crease
	 * entering at the same height as the name band would be dropped whole, and a
	 * one-crease card would then show nothing folded at all.
	 */
	@Test
	public void theFirstCreaseAlwaysCrossesTheOpenMiddle()
	{
		for (int w = 40; w <= 260; w += 2)
		{
			int h = w * 29 / 20;
			Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h,
				topBandBottom(0, w, h), nameBandTop(0, h));
			int[] band = CardRenderer.wearOpenBand(protect, 0, h);
			Assert.assertTrue("the open band collapsed at w=" + w, band[1] - band[0] >= 4);
			for (int seed : SEEDS)
			{
				int left = Integer.MAX_VALUE;
				int right = Integer.MIN_VALUE;
				for (int[] s : ofKind(CardRenderer.wearSegments(0, 0, w, h, CardWear.CRACKED,
					seed, protect), CardRenderer.KIND_CREASE))
				{
					left = Math.min(left, Math.min(s[0], s[2]));
					right = Math.max(right, Math.max(s[0], s[2]));
				}
				Assert.assertTrue("the forced crease vanished at w=" + w + " seed=" + seed,
					right > left);
				Assert.assertTrue("the forced crease does not span the card at w=" + w
					+ " seed=" + seed + " (" + left + ".." + right + ")",
					right - left >= w * 3 / 4);
			}
		}
	}

	/**
	 * The open band is derived from the protected rects rather than from a second
	 * copy of drawFace's arithmetic, so it has to survive whatever shape those
	 * rects take — including the degenerate ones a hostile size could produce.
	 */
	@Test
	public void theOpenBandFallsBackRatherThanInverting()
	{
		Assert.assertArrayEquals(new int[]{0, 100}, CardRenderer.wearOpenBand(null, 0, 100));
		Assert.assertArrayEquals(new int[]{0, 100},
			CardRenderer.wearOpenBand(new Rectangle[]{null, null}, 0, 100));
		// bands that meet in the middle leave nothing: fall back to the whole card
		// and let the per-segment text check do the work
		Assert.assertArrayEquals(new int[]{0, 100}, CardRenderer.wearOpenBand(
			new Rectangle[]{new Rectangle(0, 0, 80, 50), new Rectangle(0, 50, 80, 50)}, 0, 100));
		// the normal case, and it must not care which order they arrive in
		Rectangle topBand = new Rectangle(0, 0, 80, 20);
		Rectangle nameBand = new Rectangle(0, 85, 80, 15);
		Assert.assertArrayEquals(new int[]{20, 85},
			CardRenderer.wearOpenBand(new Rectangle[]{topBand, nameBand}, 0, 100));
		Assert.assertArrayEquals(new int[]{20, 85},
			CardRenderer.wearOpenBand(new Rectangle[]{nameBand, topBand}, 0, 100));
	}

	/**
	 * Pin the actual density on the album thumbnail. "Non-empty" would still pass
	 * if a refactor thinned three creases down to one stub, and the difference
	 * between a badge you notice and one you do not is exactly this number.
	 *
	 * <p>A ceiling, not an equality: segments crossing the two text bands are
	 * dropped, and how many that is depends on which edges the seed picked.
	 */
	@Test
	public void theThumbnailDrawsMostOfEveryLineItPromises()
	{
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H));
		for (CardWear wear : CardWear.values())
		{
			int lines = CardRenderer.creaseCount(wear) + CardRenderer.scratchCount(wear);
			int ceiling = lines * CardRenderer.WEAR_STEPS;
			for (int seed : SEEDS)
			{
				int drawn = CardRenderer.wearSegments(0, 0, THUMB_W, THUMB_H, wear, seed,
					protect).size();
				Assert.assertTrue("more segments than there are steps at " + wear,
					drawn <= ceiling);
				// over half of every promised polyline actually lands: the bands
				// clip the ends of a crease, never the bulk of it
				Assert.assertTrue("only " + drawn + " of " + ceiling + " segments at "
					+ wear + " seed=" + seed, drawn >= ceiling / 2);
			}
		}
	}

	/**
	 * A card under the first threshold must render exactly as it did before this
	 * feature existed. Not "almost nothing" — nothing, across all four passes.
	 */
	@Test
	public void aPristineCardDrawsNoWearAtAll()
	{
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H));
		Assert.assertEquals(0, CardRenderer.creaseCount(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.scratchCount(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.wearAlpha(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.grimeAlpha(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.edgeNicks(CardWear.NONE));
		for (int seed : SEEDS)
		{
			Assert.assertTrue("a pristine card drew wear", CardRenderer.wearSegments(
				0, 0, THUMB_W, THUMB_H, CardWear.NONE, seed, protect).isEmpty());
		}
		Assert.assertTrue("a null stage drew wear", CardRenderer.wearSegments(
			0, 0, THUMB_W, THUMB_H, null, 7, protect).isEmpty());
	}

	/**
	 * The pattern is seeded from the card NAME, never from the clock, so a worn
	 * card is one physical object: it does not shimmer frame to frame, it looks
	 * the same after a restart, and the album thumbnail matches the reveal card.
	 */
	@Test
	public void theSameCardWearsTheSameWayEveryTime()
	{
		Rectangle[] protect = CardRenderer.wearProtect(0, 0, THUMB_W, THUMB_H,
			topBandBottom(0, THUMB_W, THUMB_H), nameBandTop(0, THUMB_H));
		int seed = "Abyssal whip".hashCode();
		List<int[]> first = CardRenderer.wearSegments(0, 0, THUMB_W, THUMB_H,
			CardWear.CRACKED, seed, protect);
		List<int[]> again = CardRenderer.wearSegments(0, 0, THUMB_W, THUMB_H,
			CardWear.CRACKED, seed, protect);
		Assert.assertEquals(first.size(), again.size());
		for (int i = 0; i < first.size(); i++)
		{
			Assert.assertArrayEquals("segment " + i + " moved between draws",
				first.get(i), again.get(i));
		}
	}

	/**
	 * Wear only ever accumulates, so a card that reaches a heavier stage must
	 * never show LESS than it did before. True by construction — line k's geometry
	 * depends on the seed and on k, never on the stage — and this is what pins
	 * that construction down.
	 *
	 * <p>Containment, not index equality: a heavier stage inserts its extra crease
	 * ahead of the scratches, so the shared lines sit at different offsets in the
	 * list even though every one of them is unmoved on the card.
	 */
	@Test
	public void aHeavierStageNeverShowsLessThanALighterOne()
	{
		for (int w = 60; w <= 200; w += 5)
		{
			int h = w * 29 / 20;
			Rectangle[] protect = CardRenderer.wearProtect(0, 0, w, h,
				topBandBottom(0, w, h), nameBandTop(0, h));
			for (int seed : SEEDS)
			{
				for (int i = 1; i < WORN.length; i++)
				{
					List<int[]> lighter = CardRenderer.wearSegments(0, 0, w, h, WORN[i - 1],
						seed, protect);
					List<int[]> heavier = CardRenderer.wearSegments(0, 0, w, h, WORN[i],
						seed, protect);
					String where = "w=" + w + " seed=" + seed + " " + WORN[i - 1]
						+ " -> " + WORN[i];
					Assert.assertTrue("wear went backwards at " + where,
						heavier.size() >= lighter.size());
					Set<String> after = new HashSet<>();
					for (int[] s : heavier)
					{
						after.add(Arrays.toString(s));
					}
					for (int[] s : lighter)
					{
						Assert.assertTrue("a line moved when the card aged, " + where + " "
							+ Arrays.toString(s), after.contains(Arrays.toString(s)));
					}
				}
			}
		}
	}

	/**
	 * A badge, not damage. Every layer gets denser as the record grows, but the
	 * lines never turn opaque, the grime never drowns the art, and the face never
	 * folds into origami — the card has to keep reading as a thing you earned, not
	 * a thing about to fall apart.
	 */
	@Test
	public void wearReadsAsABadgeAndNotAsDamage()
	{
		Assert.assertTrue("the top stage folds the card to pieces",
			CardRenderer.creaseCount(CardWear.SHATTERED) <= 3);
		Assert.assertTrue("scuffing should show before a fold does",
			CardRenderer.scratchCount(CardWear.HAIRLINE)
				> CardRenderer.creaseCount(CardWear.HAIRLINE));

		for (CardWear wear : CardWear.values())
		{
			Assert.assertTrue("wear paints opaque at " + wear, CardRenderer.wearAlpha(wear) < 255);
			Assert.assertTrue("negative alpha at " + wear, CardRenderer.wearAlpha(wear) >= 0);
			// grime is the layer you notice second: it must never out-shout the
			// line work. NONE is both-zero and so is skipped rather than
			// special-cased into a <=, which would let a future stage tie.
			Assert.assertTrue("grime drowns the art at " + wear,
				wear == CardWear.NONE
					|| CardRenderer.grimeAlpha(wear) < CardRenderer.wearAlpha(wear));
		}

		CardWear[] ladder = {CardWear.NONE, CardWear.HAIRLINE, CardWear.CRACKED,
			CardWear.SHATTERED};
		for (int i = 1; i < ladder.length; i++)
		{
			String step = ladder[i - 1] + " -> " + ladder[i];
			Assert.assertTrue("the lines faded at " + step,
				CardRenderer.wearAlpha(ladder[i]) > CardRenderer.wearAlpha(ladder[i - 1]));
			Assert.assertTrue("grime lifted at " + step,
				CardRenderer.grimeAlpha(ladder[i]) > CardRenderer.grimeAlpha(ladder[i - 1]));
			Assert.assertTrue("the edge healed at " + step,
				CardRenderer.edgeNicks(ladder[i]) > CardRenderer.edgeNicks(ladder[i - 1]));
			Assert.assertTrue("scratches buffed out at " + step,
				CardRenderer.scratchCount(ladder[i]) > CardRenderer.scratchCount(ladder[i - 1]));
			Assert.assertTrue("a crease unfolded at " + step,
				CardRenderer.creaseCount(ladder[i]) >= CardRenderer.creaseCount(ladder[i - 1]));
		}
	}

	/**
	 * A crease is one continuous line, not a scatter of marks. Consecutive
	 * segments must share an endpoint, because that is the difference between a
	 * fold and the hatching the old renderer drew. Gaps are legal only where the
	 * text check removed a segment.
	 */
	@Test
	public void aCreaseIsAContinuousLine()
	{
		// no protected rects, so nothing is dropped and the polyline is whole
		for (int w = 60; w <= 200; w += 5)
		{
			int h = w * 29 / 20;
			for (int seed : SEEDS)
			{
				List<int[]> creases = ofKind(CardRenderer.wearSegments(0, 0, w, h,
					CardWear.CRACKED, seed, null), CardRenderer.KIND_CREASE);
				Assert.assertEquals("a crease should be exactly one polyline",
					CardRenderer.WEAR_STEPS, creases.size());
				for (int i = 1; i < creases.size(); i++)
				{
					Assert.assertEquals("the crease broke between step " + (i - 1) + " and " + i,
						creases.get(i - 1)[2], creases.get(i)[0]);
					Assert.assertEquals("the crease broke between step " + (i - 1) + " and " + i,
						creases.get(i - 1)[3], creases.get(i)[1]);
				}
			}
		}
	}

	/**
	 * The jitter is tapered to zero at both ends, so a line arrives exactly where
	 * it was aimed and meets the frame cleanly. An untapered walk is what the old
	 * renderer drew and what read as scribble — this is the assertion that stops
	 * anyone quietly deleting the sin() term.
	 */
	@Test
	public void aLineMeetsBothEndsWhereItWasAimed()
	{
		for (int seed : SEEDS)
		{
			double[][] path = CardRenderer.wearSeamPath(10, 50, 190, 70, seed, 40);
			Assert.assertEquals(10, path[0][0], 1e-9);
			Assert.assertEquals(50, path[0][1], 1e-9);
			Assert.assertEquals(190, path[path.length - 1][0], 1e-9);
			Assert.assertEquals(70, path[path.length - 1][1], 1e-9);
			// and it genuinely wanders in between, or it is just a straight line
			double maxOff = 0;
			for (int s = 1; s < path.length - 1; s++)
			{
				double t = s / (double) (path.length - 1);
				maxOff = Math.max(maxOff, Math.abs(path[s][1] - (50 + 20 * t)));
			}
			Assert.assertTrue("the line is dead straight at seed=" + seed, maxOff > 1);
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
			Rectangle[] protect = CardRenderer.wearProtect(x, y, w, h,
				topBandBottom(y, w, h), nameBandTop(y, h));
			float reach = CardRenderer.wearInkReach(w);
			for (CardWear wear : CardWear.values())
			{
				for (int seed : SEEDS)
				{
					for (int[] s : CardRenderer.wearSegments(x, y, w, h, wear, seed, protect))
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

	/**
	 * wearInkReach is the budget every clearance test above measures against, so
	 * it has to cover the widest thing drawWearLines actually paints. Two passes
	 * compete for it: the crease's soft valley, stroked at exactly twice the reach
	 * and centred, and the lit ridge, offset a full stroke to one side and then
	 * half of its own width beyond that. Both land on the reach exactly — there is
	 * no slack here, which is why the ridge is drawn through Line2D rather than
	 * being rounded to whole pixels first.
	 */
	@Test
	public void theInkReachCoversEveryPassThatLands()
	{
		for (int w = 1; w <= 2000; w++)
		{
			float line = CardRenderer.wearStroke(w);
			float reach = CardRenderer.wearInkReach(w);
			Assert.assertTrue("the valley is wider than the reach at w=" + w, reach * 2f >= line);
			Assert.assertTrue("the ridge sits outside the reach at w=" + w,
				reach + 1e-4f >= line + line * 0.7f / 2f);
			Assert.assertTrue("the crease vanished at w=" + w, line >= 1.6f);
		}
	}
}
