package com.gachaman.ui.panel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Procedural sidebar icon: a gacha card peeking out behind a chest.
 * No image resources needed.
 *
 * <p>The artwork is authored on a 16x16 grid — the size RuneLite's sidebar
 * actually draws — and {@link #create(int)} scales that one drawing up rather
 * than restating it at another size. The repository's icon.png is generated
 * from this same code, so the listing art and the in-client button cannot
 * drift apart the way they had.
 */
public final class PanelIcon
{
	/** The grid the artwork is authored on. */
	private static final int GRID = 16;

	private static final Color CARD_FILL = new Color(88, 52, 140);
	private static final Color CARD_TRIM = new Color(212, 175, 55);
	private static final Color CHEST_FILL = new Color(112, 82, 46);
	private static final Color CHEST_TRIM = new Color(230, 190, 80);

	private PanelIcon()
	{
	}

	/** The 16x16 sidebar button icon. */
	public static BufferedImage create()
	{
		return create(GRID);
	}

	/**
	 * The artwork centred on a canvas of the given size, aspect preserved.
	 *
	 * <p>For the Plugin Hub, whose icon is 48x72 while this drawing is square.
	 * Scaled to fit the shorter edge and centred rather than stretched to fill:
	 * a chest pulled to 1.5x its height is not the same mark, and the icon has to
	 * match the sidebar button it sits beside in the plugin list.
	 *
	 * @param width  canvas width in pixels; must be positive
	 * @param height canvas height in pixels; must be positive
	 */
	public static BufferedImage create(int width, int height)
	{
		if (width <= 0 || height <= 0)
		{
			throw new IllegalArgumentException(
				"icon canvas must be positive, was " + width + "x" + height);
		}
		int edge = Math.min(width, height);
		BufferedImage art = create(edge);
		if (width == height)
		{
			return art;
		}
		BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		try
		{
			g.drawImage(art, (width - edge) / 2, (height - edge) / 2, null);
		}
		finally
		{
			g.dispose();
		}
		return canvas;
	}

	/**
	 * The same artwork at an arbitrary square size.
	 *
	 * @param size edge length in pixels; must be positive
	 */
	public static BufferedImage create(int size)
	{
		if (size <= 0)
		{
			throw new IllegalArgumentException("icon size must be positive, was " + size);
		}
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (size != GRID)
			{
				// geometric accuracy, which is what an upscale wants. Deliberately NOT
				// set at 16x16: there the default (normalize) snaps the 1px outlines to
				// the pixel grid, and that crispness is the sidebar button as it ships.
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
					RenderingHints.VALUE_STROKE_PURE);
			}

			// one transform for the whole drawing, so every coordinate below stays
			// on the 16x16 grid the art was designed on
			double scale = size / (double) GRID;
			g.scale(scale, scale);
			// stroke in grid units too, or the outlines thin out as the icon grows
			g.setStroke(new BasicStroke(1f));

			// card, tilted feel via offset placement (behind the chest)
			g.setColor(CARD_FILL);
			g.fillRoundRect(1, 0, 8, 11, 3, 3);
			g.setColor(CARD_TRIM);
			g.drawRoundRect(1, 0, 8, 11, 3, 3);
			// tiny sigil on the card
			g.drawOval(3, 3, 4, 4);

			// chest in front
			g.setColor(CHEST_FILL);
			g.fillRoundRect(4, 6, 11, 9, 3, 3);
			g.setColor(CHEST_TRIM);
			g.drawRoundRect(4, 6, 11, 9, 3, 3);
			// lid seam
			g.drawLine(4, 9, 15, 9);
			// clasp
			g.fillRect(9, 8, 2, 3);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}
}
