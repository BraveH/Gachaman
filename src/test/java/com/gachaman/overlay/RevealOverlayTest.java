package com.gachaman.overlay;

import java.util.List;
import com.gachaman.party.*;
import java.awt.*;
import java.awt.image.*;
import java.util.*;
import org.junit.*;

/**
 * Two defects in {@link RevealOverlay} that a live client would only ever show
 * you as a smear of overlapping text, or as a frame-rate mystery on a broken
 * jar. Both are pinned here because both are cheap to reproduce off-client:
 * {@code drawVoters} is a static function of a Graphics2D and a name list, and
 * the sprite cache is a plain static map.
 *
 * <p>Explicit {@code java.util.List} import on purpose — {@code java.awt.*} is
 * wildcarded below for Graphics2D and Color, and it exports a List of its own.
 */
public class RevealOverlayTest
{
	private static final int W = 400;
	private static final int H = 200;
	/** Left margin the voter block is drawn from, i.e. drawVoters' own {@code x}. */
	private static final int MARGIN = 12;
	/** Top the block starts at; the "Backed by" rule lands here. */
	private static final int TOP = 20;
	/**
	 * Column width. Narrow enough that the five names below need three rows,
	 * which is what gives the sweep a wrap to refuse in the first place.
	 */
	private static final int COLUMN = 150;
	/** Paper white. Every ink the block uses is dark, so "not this" means "inked". */
	private static final int BG = 0xFFFFFFFF;

	/**
	 * Five backers with no avatars. Dropping the faces is deliberate: it makes
	 * every chip's width a pure function of its name, so the layout the sweep
	 * reasons about has one input instead of two, and drawVoterFace's scratch
	 * image never enters the picture.
	 */
	private static List<PartyRollService.Voter> voters()
	{
		List<PartyRollService.Voter> out = new ArrayList<>();
		for (String name : new String[]{
			"Zezimaaaa", "Wooooox", "Framedddd", "Torvestaa", "Odablock"})
		{
			out.add(new PartyRollService.Voter(name, null, false));
		}
		return out;
	}

	/**
	 * The voter block rendered against {@code parchBot}, as a flat ARGB raster.
	 *
	 * <p>No rendering hints are set, by both renders equally — the comparison
	 * below only holds if the two rasters differ for exactly one reason.
	 */
	private static int[] render(int parchBot)
	{
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, W, H);
			RevealOverlay.drawVoters(g, voters(), MARGIN, TOP, COLUMN, parchBot);
		}
		finally
		{
			g.dispose();
		}
		return img.getRGB(0, 0, W, H, null, 0, W);
	}

	/**
	 * The "+N more" overflow label is appended AFTER the names on its row, never
	 * stamped on top of them.
	 *
	 * <p>Method: render the block once with the whole canvas to play with, then
	 * again at every parchment bottom from the very cramped to the roomy. A tight
	 * render draws a strict PREFIX of the roomy layout at identical coordinates —
	 * the wrap arithmetic depends only on the names, the font and the column, none
	 * of which parchBot touches, and the voter whose chip triggered the refused
	 * wrap is on the NEXT row in the roomy render. So every pixel the tight render
	 * inks and the roomy one does not is label ink, and nothing else.
	 *
	 * <p>The invariant is then one number against another: the label's leftmost
	 * pixel must sit clear of the rightmost pixel already on the label's own
	 * scanlines. The original code reset cx to the left margin at the wrap and
	 * then drew the label with it against the previous row's baseline, so the
	 * label started at the margin and the first name on that row disappeared
	 * underneath it — leftmost label pixel far to the LEFT of the row's ink.
	 */
	@Test
	public void theOverflowLabelNeverOverprintsTheNamesOnItsRow()
	{
		int[] roomy = render(H);
		int overflows = 0;
		for (int parchBot = TOP; parchBot <= H; parchBot++)
		{
			int[] tight = render(parchBot);
			int labelLeft = Integer.MAX_VALUE;
			int labelTop = Integer.MAX_VALUE;
			int labelBottom = Integer.MIN_VALUE;
			for (int py = 0; py < H; py++)
			{
				for (int px = 0; px < W; px++)
				{
					int at = py * W + px;
					if (tight[at] != BG && roomy[at] == BG)
					{
						labelLeft = Math.min(labelLeft, px);
						labelTop = Math.min(labelTop, py);
						labelBottom = Math.max(labelBottom, py);
					}
				}
			}
			if (labelLeft == Integer.MAX_VALUE)
			{
				// this height either fitted every name or refused the whole
				// block at the heading guard; either way there is no label
				continue;
			}
			overflows++;

			// the widest ink already on the label's scanlines, which on those
			// rows is exactly the chips that did fit
			int rowInkRight = -1;
			for (int py = labelTop; py <= labelBottom; py++)
			{
				for (int px = 0; px < W; px++)
				{
					if (roomy[py * W + px] != BG)
					{
						rowInkRight = Math.max(rowInkRight, px);
					}
				}
			}
			Assert.assertTrue(
				"parchBot=" + parchBot + ": '+N more' starts at x=" + labelLeft
					+ " but the names on that row run out to x=" + rowInkRight,
				labelLeft > rowInkRight);
		}
		// without this the sweep could pass by never producing a label at all
		Assert.assertTrue("the sweep never forced an overflow", overflows > 0);
	}

	/**
	 * A sprite that is not in the jar is looked up ONCE, not once per frame.
	 *
	 * <p>{@code computeIfAbsent} used to do the loading, and a HashMap does not
	 * record a null mapping — so a missing PNG stayed "not cached yet" forever and
	 * the roulette re-walked the classloader for it on every frame of the spin.
	 * The cached miss is the whole fix, so the assertion is on the map, not on
	 * anything drawn.
	 */
	@Test
	public void aMissingSpriteIsResolvedOnlyOnce()
	{
		String missing = "no-such-wheel-part";
		RevealOverlay.ART.remove(missing);
		blit(missing);
		Assert.assertTrue("a miss must be remembered, not retried every frame",
			RevealOverlay.ART.containsKey(missing));
		Assert.assertNull("a miss caches as an explicit null",
			RevealOverlay.ART.get(missing));
	}

	/**
	 * The sprite that ships still loads and still caches. Guards the other half
	 * of the same rewrite: a cache that remembers misses but has stopped
	 * remembering hits would pass the test above and blank the wheel.
	 */
	@Test
	public void aShippedSpriteStillLoadsAndCaches()
	{
		RevealOverlay.ART.remove("wheel-hub");
		blit("wheel-hub");
		Assert.assertNotNull("wheel-hub.png ships and must decode",
			RevealOverlay.ART.get("wheel-hub"));
	}

	/** One blitArt call against a throwaway surface; only the cache is of interest. */
	private static void blit(String name)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			RevealOverlay.blitArt(g, name, 8, 8, 1.0, 0, 0);
		}
		finally
		{
			g.dispose();
		}
	}
}
