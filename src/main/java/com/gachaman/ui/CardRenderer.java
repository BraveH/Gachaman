package com.gachaman.ui;

import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;

/**
 * Procedural card face rendering: rarity frames, foil sheen, shiny prismatic
 * cycling, hologram scanlines with chromatic ghosting, deterministic sparkles.
 * Pure drawing — animation state comes in via timeMs so callers control time.
 */
public final class CardRenderer
{
	private static final Color CARD_BG_TOP = new Color(48, 42, 32);
	private static final Color CARD_BG_BOTTOM = new Color(28, 24, 18);
	private static final Color NAME_BAND = new Color(20, 17, 12, 230);
	private static final Color CARD_BACK_A = new Color(58, 34, 92);
	private static final Color CARD_BACK_B = new Color(32, 18, 52);

	@Value
	@Builder
	public static class CardView
	{
		String name;
		Rarity rarity;
		Variant variant;
		@Nullable
		BufferedImage art;
		@Nullable
		String subtitle; // e.g. "Dragon tier — any slot" for holograms
	}

	private CardRenderer()
	{
	}

	/** Draw a face-down card back. */
	public static void drawBack(Graphics2D g, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, w / 7f, w / 7f);
		g2.setPaint(new GradientPaint(x, y, CARD_BACK_A, x, y + h, CARD_BACK_B));
		g2.fill(shape);
		g2.setColor(new Color(212, 175, 55));
		g2.setStroke(new BasicStroke(2f));
		g2.draw(shape);
		// simple gacha sigil: diamond + circle
		int cx = x + w / 2;
		int cy = y + h / 2;
		int r = Math.min(w, h) / 5;
		g2.setColor(new Color(212, 175, 55, 150));
		g2.drawOval(cx - r, cy - r, r * 2, r * 2);
		g2.drawLine(cx, cy - r - r / 2, cx + r + r / 2, cy);
		g2.drawLine(cx + r + r / 2, cy, cx, cy + r + r / 2);
		g2.drawLine(cx, cy + r + r / 2, cx - r - r / 2, cy);
		g2.drawLine(cx - r - r / 2, cy, cx, cy - r - r / 2);
		// slow sheen sweep so backs feel alive
		drawSheen(g2, shape, x, y, w, h, timeMs, 5200, new Color(255, 255, 255, 26));
		g2.dispose();
	}

	/** Draw a face-up card. */
	public static void drawFace(Graphics2D g, int x, int y, int w, int h, CardView view, long timeMs)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, w / 7f, w / 7f);

		// body
		g2.setPaint(new GradientPaint(x, y, CARD_BG_TOP, x, y + h, CARD_BG_BOTTOM));
		g2.fill(shape);

		// art — cropped to its opaque bounds first: item sprites carry uneven
		// transparent padding, so centering the raw sprite off-centers the art
		if (view.getArt() != null)
		{
			int artH = (int) (h * 0.52);
			int artY = y + (int) (h * 0.12);
			BufferedImage art = cropToOpaqueBounds(view.getArt());
			double scale = Math.min((double) (w - 16) / art.getWidth(), (double) artH / art.getHeight());
			int dw = Math.max(1, (int) (art.getWidth() * scale));
			int dh = Math.max(1, (int) (art.getHeight() * scale));
			Graphics2D ga = (Graphics2D) g2.create();
			ga.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			ga.drawImage(art, x + (w - dw) / 2, artY + (artH - dh) / 2, dw, dh, null);
			ga.dispose();
		}

		// name band — the font SHRINKS to fit the name (ellipsis only as a
		// last resort at the minimum size)
		int bandH = Math.max(16, h / 6);
		g2.setColor(NAME_BAND);
		g2.fillRect(x + 2, y + h - bandH - h / 8, w - 4, bandH);
		g2.setColor(view.getRarity().getColor());
		drawFittedString(g2, view.getName(), x + w / 2, y + h - bandH / 2 - h / 8, w - 8,
			new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, w / 9)), 8);
		if (view.getSubtitle() != null)
		{
			g2.setColor(new Color(200, 200, 200));
			drawFittedString(g2, view.getSubtitle(), x + w / 2, y + h - h / 16, w - 8,
				new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, w / 12)), 7);
		}

		// rarity label
		g2.setColor(view.getRarity().getColor());
		g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, w / 11)));
		drawCenteredString(g2, view.getRarity().getDisplayName().toUpperCase(), x + w / 2, y + h / 14 + 4, w - 8);

		// variant effects
		if (view.getVariant() == Variant.SHINY)
		{
			drawShiny(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getVariant() == Variant.HOLOGRAM)
		{
			drawHologram(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getRarity().atLeast(Rarity.EPIC))
		{
			drawSheen(g2, shape, x, y, w, h, timeMs, 3000, new Color(255, 255, 255, 40));
		}

		// border (variant-tinted)
		Color border = view.getVariant() == Variant.SHINY
			? prismaticColor(timeMs, 0)
			: view.getVariant() == Variant.HOLOGRAM
			? new Color(120, 220, 255)
			: view.getRarity().getColor();
		g2.setColor(border);
		g2.setStroke(new BasicStroke(view.getRarity().atLeast(Rarity.RARE) ? 2.5f : 1.6f));
		g2.draw(shape);

		g2.dispose();
	}

	/** Rarity-colored glow behind a card (charge-up etc.); intensity 0..1. */
	public static void drawGlow(Graphics2D g, int x, int y, int w, int h, Color color, float intensity)
	{
		if (intensity <= 0)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int layers = 12;
		for (int i = layers; i >= 1; i--)
		{
			float t = (float) i / layers;
			int alpha = (int) (intensity * 90 * (1 - t) * (1 - t));
			if (alpha <= 0)
			{
				continue;
			}
			g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(255, alpha)));
			int pad = (int) (t * Math.min(w, h) * 0.28f);
			g2.fill(new RoundRectangle2D.Float(x - pad, y - pad, w + pad * 2, h + pad * 2,
				w / 6f + pad, w / 6f + pad));
		}
		g2.dispose();
	}

	// --- effect internals ---

	private static void drawShiny(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		// two counter-drifting prismatic bands, swept diagonally
		gs.rotate(Math.toRadians(-25), x + w / 2.0, y + h / 2.0);
		for (int band = 0; band < 2; band++)
		{
			float phase = ((timeMs + band * 900) % 2600) / 2600f;
			int bx = x - w - h / 2 + (int) (phase * (w * 3 + h));
			Color c = prismaticColor(timeMs, band * 120);
			gs.setPaint(new GradientPaint(bx, y, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
				bx + w / 2f, y + h, new Color(c.getRed(), c.getGreen(), c.getBlue(), 70)));
			gs.fillRect(bx, y - h / 2, w / 2, h * 2);
		}
		gs.rotate(Math.toRadians(25), x + w / 2.0, y + h / 2.0);
		// deterministic sparkles
		for (int i = 0; i < 7; i++)
		{
			float u = hash01(i * 733 + 1);
			float v = hash01(i * 733 + 2);
			float twinkle = (float) (0.5 + 0.5 * Math.sin(timeMs / 260.0 + i * 1.7));
			int alpha = (int) (200 * twinkle);
			gs.setColor(new Color(255, 255, 255, alpha));
			int sx = x + (int) (u * (w - 8)) + 4;
			int sy = y + (int) (v * (h - 8)) + 4;
			int size = 2 + (i % 2);
			gs.drawLine(sx - size, sy, sx + size, sy);
			gs.drawLine(sx, sy - size, sx, sy + size);
		}
		gs.dispose();
	}

	private static void drawHologram(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		// cyan wash
		gs.setColor(new Color(90, 200, 255, 26));
		gs.fillRect(x, y, w, h);
		// scanlines drifting downward
		int offset = (int) ((timeMs / 90) % 6);
		gs.setColor(new Color(140, 230, 255, 34));
		for (int sy = y + offset; sy < y + h; sy += 6)
		{
			gs.drawLine(x, sy, x + w, sy);
		}
		// chromatic ghost edges
		Composite old = gs.getComposite();
		gs.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
		gs.setColor(new Color(255, 80, 160));
		gs.drawRoundRect(x - 1, y, w, h, w / 7, w / 7);
		gs.setColor(new Color(80, 255, 220));
		gs.drawRoundRect(x + 1, y, w, h, w / 7, w / 7);
		gs.setComposite(old);
		// occasional flicker band
		float flicker = (timeMs % 2400) / 2400f;
		if (flicker > 0.9f)
		{
			int fy = y + (int) ((flicker - 0.9f) * 10 * h);
			gs.setColor(new Color(200, 245, 255, 60));
			gs.fillRect(x, fy, w, 4);
		}
		gs.dispose();
	}

	private static void drawSheen(Graphics2D g2, Shape clip, int x, int y, int w, int h,
		long timeMs, int periodMs, Color color)
	{
		// diagonal sweep: band travels corner-to-corner, tilted ~25 degrees
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		gs.rotate(Math.toRadians(-25), x + w / 2.0, y + h / 2.0);
		float phase = (timeMs % periodMs) / (float) periodMs;
		int travel = w * 3 + h;
		int sx = x - w - h / 2 + (int) (phase * travel);
		gs.setPaint(new GradientPaint(sx, y, new Color(255, 255, 255, 0),
			sx + w / 3f, y, color, true));
		gs.fillRect(sx, y - h / 2, w / 3, h * 2);
		gs.dispose();
	}

	public static Color prismaticColor(long timeMs, int offsetDeg)
	{
		float hue = ((timeMs / 22) % 360 + offsetDeg) % 360 / 360f;
		return Color.getHSBColor(hue, 0.65f, 1f);
	}

	private static float hash01(int n)
	{
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static void drawCenteredString(Graphics2D g, String text, int cx, int cy, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		String drawn = text;
		while (fm.stringWidth(drawn) > maxWidth && drawn.length() > 4)
		{
			drawn = drawn.substring(0, drawn.length() - 2);
		}
		if (!drawn.equals(text))
		{
			drawn = drawn.substring(0, Math.max(1, drawn.length() - 1)) + "…";
		}
		g.drawString(drawn, cx - fm.stringWidth(drawn) / 2, cy + fm.getAscent() / 2 - 1);
	}

	/**
	 * Draw centered, shrinking the font (down to minSize) until the whole
	 * text fits; only at the floor does it fall back to ellipsizing.
	 */
	private static void drawFittedString(Graphics2D g, String text, int cx, int cy, int maxWidth,
		Font baseFont, int minSize)
	{
		Font font = baseFont;
		g.setFont(font);
		while (g.getFontMetrics().stringWidth(text) > maxWidth && font.getSize() > minSize)
		{
			font = font.deriveFont((float) (font.getSize() - 1));
			g.setFont(font);
		}
		drawCenteredString(g, text, cx, cy, maxWidth);
	}

	/**
	 * The smallest subimage containing every non-transparent pixel. Item
	 * sprites pad their content unevenly; cropping first makes centering
	 * center the visible art, not the padding.
	 */
	private static BufferedImage cropToOpaqueBounds(BufferedImage image)
	{
		int minX = image.getWidth();
		int minY = image.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int py = 0; py < image.getHeight(); py++)
		{
			for (int px = 0; px < image.getWidth(); px++)
			{
				if ((image.getRGB(px, py) >>> 24) > 8)
				{
					minX = Math.min(minX, px);
					minY = Math.min(minY, py);
					maxX = Math.max(maxX, px);
					maxY = Math.max(maxY, py);
				}
			}
		}
		if (maxX < 0 || (minX == 0 && minY == 0
			&& maxX == image.getWidth() - 1 && maxY == image.getHeight() - 1))
		{
			return image; // fully transparent or already tight
		}
		return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
