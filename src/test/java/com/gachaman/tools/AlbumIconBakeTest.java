package com.gachaman.tools;

import java.awt.*;
import java.awt.image.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;
import org.junit.*;

/**
 * Pins the album's two baked icons against the drawing they replaced.
 *
 * <p>AlbumTab used to carry a StardustIcon and a CheckboxIcon: two Icon classes
 * whose paintIcon bodies drew a fixed 13x13 picture from hardcoded colours on
 * every repaint. They are PNGs now, authored by {@link IconArt}, because the
 * Plugin Hub counts only src/main/java and a constant drawing is the cheapest
 * thing in the plugin to move out of it. The originals are copied verbatim into
 * this test — the old code is the specification, and keeping it here is free.
 *
 * <p>What the test asserts is the claim the swap rests on: rendering the shape
 * into a transparent layer and compositing that layer SRC_OVER produces the
 * same picture as drawing the shape straight onto the destination. It is not
 * bit-exact — ARGB is stored non-premultiplied, so an intermediate layer rounds
 * once more than a direct draw — hence the ±TOLERANCE band. When this was
 * written the worst case was 1, on eight pixels of a dark background.
 *
 * <p>Both sides render live, on whatever JDK runs the test, so the assertion is
 * about the two compositing routes rather than about one committed raster: a
 * different rasterizer moves both sides together. The committed PNGs get their
 * own, deliberately coarse check — that they exist, load, and are 13x13 — since
 * a missing or mis-sized resource is the failure a player would actually see.
 */
public class AlbumIconBakeTest
{
	private static final int SIZE = 13;
	private static final int PAD = 6;
	private static final int CANVAS = SIZE + 2 * PAD;
	/** Slack for the one extra rounding step a composited layer costs. */
	private static final int TOLERANCE = 2;

	private static final Color SPARKLE = new Color(190, 170, 255);
	private static final BasicStroke CHECK_STROKE =
		new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	// --- the AlbumTab paintIcon bodies, verbatim as they stood before the bake ---

	private static void paintStardust(Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int cx = x + SIZE / 2;
			int cy = y + SIZE / 2;
			g2.setColor(SPARKLE);
			g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.drawLine(cx, y + 2, cx, y + SIZE - 2);
			g2.drawLine(x + 2, cy, x + SIZE - 2, cy);
			g2.setStroke(new BasicStroke(1f));
			g2.drawLine(cx - 2, cy - 2, cx + 2, cy + 2);
			g2.drawLine(cx - 2, cy + 2, cx + 2, cy - 2);
			g2.setColor(Color.WHITE);
			g2.fillOval(cx - 1, cy - 1, 2, 2);
		}
		finally
		{
			g2.dispose();
		}
	}

	private static void paintCheckbox(boolean selected, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(x, y, SIZE, SIZE, 4, 4);
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 4, 4);
			if (selected)
			{
				g2.setColor(Color.WHITE);
				g2.setStroke(CHECK_STROKE);
				g2.drawLine(x + 3, y + 7, x + 5, y + 9);
				g2.drawLine(x + 5, y + 9, x + 10, y + 3);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	/**
	 * A canvas bigger than the icon on every side, so a stroke that bled outside
	 * the 13x13 box the baked PNG can hold would show up as a difference rather
	 * than being silently cropped by the comparison itself.
	 */
	private static BufferedImage canvas(Color background)
	{
		BufferedImage img = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		if (background != null)
		{
			g.setColor(background);
			g.fillRect(0, 0, CANVAS, CANVAS);
		}
		g.dispose();
		return img;
	}

	private static BufferedImage baked(BufferedImage icon, Color background)
	{
		BufferedImage img = canvas(background);
		Graphics2D g = img.createGraphics();
		g.drawImage(icon, PAD, PAD, null);
		g.dispose();
		return img;
	}

	private static void assertWithinTolerance(String what, BufferedImage a, BufferedImage b)
	{
		for (int y = 0; y < CANVAS; y++)
		{
			for (int x = 0; x < CANVAS; x++)
			{
				int pa = a.getRGB(x, y);
				int pb = b.getRGB(x, y);
				for (int shift = 0; shift < 32; shift += 8)
				{
					int delta = Math.abs(((pa >> shift) & 255) - ((pb >> shift) & 255));
					Assert.assertTrue(what + ": pixel (" + (x - PAD) + "," + (y - PAD)
							+ ") drifted from " + Integer.toHexString(pa) + " to "
							+ Integer.toHexString(pb),
						delta <= TOLERANCE);
				}
			}
		}
	}

	/**
	 * The three surfaces an icon can land on: the plugin panel it actually sits
	 * on, a bright one, and no background at all. A transparent destination is
	 * the case where a layered composite is exactly equal, so it also proves the
	 * geometry is identical and the tolerance is only absorbing rounding.
	 */
	private static Color[] backgrounds()
	{
		return new Color[]{ColorScheme.DARK_GRAY_COLOR, Color.WHITE, null};
	}

	@Test
	public void bakedStardustMatchesThePaintedSparkle()
	{
		for (Color background : backgrounds())
		{
			BufferedImage painted = canvas(background);
			Graphics2D g = painted.createGraphics();
			paintStardust(g, PAD, PAD);
			g.dispose();
			assertWithinTolerance("stardust on " + background, painted,
				baked(IconArt.stardustIcon(), background));
		}
	}

	@Test
	public void bakedCheckboxMatchesThePaintedBoxInBothStates()
	{
		for (boolean selected : new boolean[]{false, true})
		{
			for (Color background : backgrounds())
			{
				BufferedImage painted = canvas(background);
				Graphics2D g = painted.createGraphics();
				paintCheckbox(selected, g, PAD, PAD);
				g.dispose();
				assertWithinTolerance("checkbox[" + selected + "] on " + background, painted,
					baked(IconArt.checkboxIcon(selected), background));
			}
		}
	}

	/**
	 * The committed rasters themselves. AlbumTab loads these by name at
	 * construction, so a rename, a missing file or a resource excluded from the
	 * jar is a blank icon in the player's panel — and nothing else in the build
	 * would notice.
	 */
	@Test
	public void committedIconResourcesLoadAtTheExpectedSize()
	{
		for (String name : new String[]{"stardust", "checkbox-off", "checkbox-on"})
		{
			BufferedImage img = ImageUtil.loadImageResource(AlbumIconBakeTest.class,
				"/com/gachaman/ui/" + name + ".png");
			Assert.assertNotNull(name + ".png did not load", img);
			Assert.assertEquals(name + ".png width", SIZE, img.getWidth());
			Assert.assertEquals(name + ".png height", SIZE, img.getHeight());

			boolean anyVisible = false;
			for (int y = 0; y < img.getHeight() && !anyVisible; y++)
			{
				for (int x = 0; x < img.getWidth(); x++)
				{
					if ((img.getRGB(x, y) >>> 24) != 0)
					{
						anyVisible = true;
						break;
					}
				}
			}
			Assert.assertTrue(name + ".png is fully transparent", anyVisible);
		}
	}
}
