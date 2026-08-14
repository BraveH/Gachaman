package com.gachaman.ui.panel;

import com.gachaman.*;
import com.gachaman.tools.*;
import java.awt.*;
import java.awt.image.*;
import org.junit.*;

/**
 * The Shop tab's chest icon is a baked PNG now, not a drawing. This is the test
 * that keeps it honest.
 *
 * <p>ChestTile used to paint the chest from scratch on every repaint even
 * though the picture only ever depended on the tier and on whether the tile was
 * affordable — eight possible outputs, re-derived forever. The drawing moved to
 * {@link ShopArt} in test scope, which authors the eight PNGs the plugin now
 * ships, and ShopTab blits one. Three things have to hold for a player not to
 * be able to tell, and each is checked below:
 *
 * <ol>
 * <li>every sprite is actually IN the jar under the name ShopTab rebuilds — a
 * missing or misnamed resource is the one failure mode of this refactor that
 * would reach a player, as a chestless tile, so the test loads them through
 * ShopTab's own accessor rather than by a path of its own;
 * <li>the sprite lands where the drawing used to, which is the blit origin
 * agreeing with the margin ShopArt padded the canvas with;
 * <li>the pixels match what the old code drew.
 * </ol>
 *
 * <p>On that last point the tolerance is honest rather than zero. Compositing
 * the chest into a transparent buffer, encoding it as 8-bit PNG and blending the
 * decoded result over the tile is Porter-Duff OVER either way — the operator is
 * associative, so layering the icon's own overlaps first is mathematically the
 * same answer — but the intermediate buffer quantises to 8 bits per channel
 * where the direct drawing kept going. Measured across four backgrounds and all
 * eight sprites, that costs at most 2/255 on at most ~40 of a tile's 7,440
 * pixels, every one of them on an antialiased edge. The bound is set at 2 so
 * that ordinary rounding passes while anything structural — a wrong colour, a
 * stale palette, an offset that slipped — fails loudly.
 */
public class ShopIconBakeTest
{
	/** The tile the icon is drawn into; RuneLite's DARKER_GRAY under it. */
	private static final int TILE_W = 120;
	private static final int TILE_H = 62;

	/** Per-channel slack, in 8-bit levels. See the class comment. */
	private static final int TOLERANCE = 2;

	@Test
	public void everySpriteIsPresentAndMatchesTheDrawingItReplaced()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			for (boolean lit : new boolean[]{true, false})
			{
				// through ShopTab's own loader: if the PNG is missing from
				// src/main/resources, or named differently from what the loader
				// builds, this is where it fails instead of on a player's screen
				Image sprite = ShopTab.chestIcon(tier, lit);
				Assert.assertNotNull(tier + " lit=" + lit + " has no baked sprite", sprite);

				BufferedImage drawn = tile();
				Graphics2D g = drawn.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				// exactly what ChestTile.paintComponent used to do, at the
				// coordinates it used to do it at
				ShopArt.paint(g, tier.ordinal(), lit, ShopArt.IX, ShopArt.IY);
				g.dispose();

				BufferedImage blitted = tile();
				Graphics2D g2 = blitted.createGraphics();
				g2.drawImage(sprite, ShopTab.ICON_X, ShopTab.ICON_Y, null);
				g2.dispose();

				int worst = maxChannelDelta(drawn, blitted);
				Assert.assertTrue(tier + " lit=" + lit + " differs from the drawing by "
					+ worst + " levels", worst <= TOLERANCE);
			}
		}
	}

	/**
	 * The blit origin and the canvas margin are two halves of one number. If
	 * ShopArt ever pads differently, ShopTab has to move by the same amount or
	 * the chest slides across the tile — a couple of pixels, which is exactly
	 * the size of mistake nobody catches by eye.
	 */
	@Test
	public void theBlitOriginMatchesTheCanvasTheSpritesWereAuthoredOn()
	{
		Assert.assertEquals(ShopArt.ORIGIN_X, ShopTab.ICON_X);
		Assert.assertEquals(ShopArt.ORIGIN_Y, ShopTab.ICON_Y);
	}

	/**
	 * ShopArt deliberately does not import Tuning — it is meant to run from a
	 * bare JDK with no classpath — so its tier order is a copy, and a copy needs
	 * pinning. Both the file names and the palettes are indexed by ordinal.
	 */
	@Test
	public void theAuthoringToolAgreesWithTheChestEnum()
	{
		Tuning.Chest[] tiers = Tuning.Chest.values();
		Assert.assertEquals(tiers.length, ShopArt.TIERS.length);
		Assert.assertEquals(tiers.length, ShopArt.BODY.length);
		Assert.assertEquals(tiers.length, ShopArt.TRIM.length);
		for (Tuning.Chest tier : tiers)
		{
			Assert.assertEquals(tier.name().toLowerCase(java.util.Locale.ROOT),
				ShopArt.TIERS[tier.ordinal()]);
			// the trim table is the one palette that lives in BOTH places: ShopArt
			// paints the icon with it, ShopTab still draws the tile border with it
			Assert.assertEquals("trim drifted for " + tier,
				ShopTab.trimColor(tier), ShopArt.TRIM[tier.ordinal()]);
		}
	}

	private static BufferedImage tile()
	{
		BufferedImage img = new BufferedImage(TILE_W, TILE_H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(30, 30, 30));
		g.fillRect(0, 0, TILE_W, TILE_H);
		g.dispose();
		return img;
	}

	/** Largest per-channel difference anywhere in the two tiles. */
	private static int maxChannelDelta(BufferedImage a, BufferedImage b)
	{
		int worst = 0;
		for (int y = 0; y < TILE_H; y++)
		{
			for (int x = 0; x < TILE_W; x++)
			{
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				for (int shift = 0; shift < 24; shift += 8)
				{
					worst = Math.max(worst, Math.abs(((p >> shift) & 255) - ((q >> shift) & 255)));
				}
			}
		}
		return worst;
	}
}
