package com.gachaman.ui.panel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Procedural 16x16 sidebar icon: a gacha card peeking out behind a chest.
 * No image resources needed.
 */
public final class PanelIcon
{
	private static final Color CARD_FILL = new Color(88, 52, 140);
	private static final Color CARD_TRIM = new Color(212, 175, 55);
	private static final Color CHEST_FILL = new Color(112, 82, 46);
	private static final Color CHEST_TRIM = new Color(230, 190, 80);

	private PanelIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
