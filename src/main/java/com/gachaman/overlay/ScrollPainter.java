package com.gachaman.overlay;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Draws the chrome of the unrolling contract scrolls used by the TASK_OFFERS
 * ceremony in {@link RevealOverlay}: the two wooden rollers (cylinders with
 * end caps) and the tinted parchment sheet stretched between them. Stateless
 * pure drawing - all animation timing and clipping live in the caller.
 */
final class ScrollPainter
{
	/** Roller (cylinder) diameter in px. */
	static final int ROLLER_H = 18;
	/** Horizontal inset of the parchment sheet from the scroll bounds. */
	static final int PARCH_INSET = 6;
	/** Height of the vertically squashed "curl" band at each unrolling edge. */
	static final int CURL_BAND = 12;

	private static final int CAP_W = 7;
	private static final int EDGE_REACH = 8;
	private static final Color WOOD_LIGHT = new Color(168, 128, 76);
	private static final Color WOOD_DARK = new Color(70, 48, 24);
	private static final Color CAP_EDGE = new Color(34, 24, 12, 200);
	private static final Color SPECULAR = new Color(255, 255, 245, 60);
	private static final Color EDGE_SHADE = new Color(60, 42, 20, 90);
	private static final Color EDGE_CLEAR = new Color(60, 42, 20, 0);
	private static final Color VIGNETTE = new Color(120, 96, 60, 70);
	private static final Color VIGNETTE_CLEAR = new Color(120, 96, 60, 0);

	private ScrollPainter()
	{
	}

	/** Tier-tinted highlight color for a roller crown (precompute, not per-frame). */
	static Color rollerHi(Color tier)
	{
		return mix(WOOD_LIGHT, tier, 0.45f);
	}

	/** Tier-tinted shadow color for a roller underside (precompute, not per-frame). */
	static Color rollerLo(Color tier)
	{
		return mix(WOOD_DARK, tier, 0.30f);
	}

	/** Center Y of the top roller at unroll progress u (0 = closed, 1 = open). */
	static int topRollerCy(Rectangle r, double u)
	{
		double closed = r.y + r.height / 2.0 - ROLLER_H / 2.0;
		double open = r.y + ROLLER_H / 2.0;
		return (int) Math.round(closed + (open - closed) * u);
	}

	/** Center Y of the bottom roller at unroll progress u (0 = closed, 1 = open). */
	static int bottomRollerCy(Rectangle r, double u)
	{
		double closed = r.y + r.height / 2.0 + ROLLER_H / 2.0;
		double open = r.y + r.height - ROLLER_H / 2.0;
		return (int) Math.round(closed + (open - closed) * u);
	}

	/**
	 * A horizontal wooden roller spanning the scroll bounds, centered on cy:
	 * gradient-shaded cylinder body, specular crown strip, and slightly
	 * fatter end-cap discs closing off each end.
	 */
	static void drawRoller(Graphics2D g, Rectangle r, int cy, Color hi, Color lo)
	{
		int y = cy - ROLLER_H / 2;
		int bodyX = r.x + CAP_W - 2;
		int bodyW = r.width - (CAP_W - 2) * 2;
		g.setPaint(new GradientPaint(0, y, hi, 0, y + ROLLER_H, lo));
		g.fillRoundRect(bodyX, y, bodyW, ROLLER_H, ROLLER_H, ROLLER_H);
		g.setColor(SPECULAR);
		g.fillRoundRect(bodyX + 4, y + 3, bodyW - 8, 3, 3, 3);
		g.setPaint(new GradientPaint(0, y - 2, hi, 0, y + ROLLER_H + 2, lo));
		g.fillRoundRect(r.x, y - 2, CAP_W, ROLLER_H + 4, 6, 6);
		g.fillRoundRect(r.x + r.width - CAP_W, y - 2, CAP_W, ROLLER_H + 4, 6, 6);
		g.setColor(CAP_EDGE);
		g.drawRoundRect(r.x, y - 2, CAP_W - 1, ROLLER_H + 3, 6, 6);
		g.drawRoundRect(r.x + r.width - CAP_W, y - 2, CAP_W - 1, ROLLER_H + 3, 6, 6);
	}

	/**
	 * The parchment sheet at its full, final position: vertical paper
	 * gradient, aged vignette down both long edges, and tier-colored hairline
	 * borders. Callers clip this to the revealed window between the rollers.
	 */
	static void drawParchment(Graphics2D g, int x, int y, int w, int h,
		Color top, Color bottom, Color border)
	{
		g.setPaint(new GradientPaint(x, y, top, x, y + h, bottom));
		g.fillRect(x, y, w, h);
		int vig = Math.min(14, w / 6);
		g.setPaint(new GradientPaint(x, 0, VIGNETTE, x + vig, 0, VIGNETTE_CLEAR));
		g.fillRect(x, y, vig, h);
		g.setPaint(new GradientPaint(x + w, 0, VIGNETTE, x + w - vig, 0, VIGNETTE_CLEAR));
		g.fillRect(x + w - vig, y, vig, h);
		g.setColor(border);
		g.drawLine(x, y, x, y + h - 1);
		g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
	}

	/**
	 * Contact shading where the sheet disappears behind a roller: darkest at
	 * edgeY, fading over a few px {@code fromTop ? downward : upward}.
	 */
	static void drawEdgeShade(Graphics2D g, int x, int w, int edgeY, boolean fromTop)
	{
		if (fromTop)
		{
			g.setPaint(new GradientPaint(0, edgeY, EDGE_SHADE, 0, edgeY + EDGE_REACH, EDGE_CLEAR));
			g.fillRect(x, edgeY, w, EDGE_REACH);
		}
		else
		{
			g.setPaint(new GradientPaint(0, edgeY, EDGE_SHADE, 0, edgeY - EDGE_REACH, EDGE_CLEAR));
			g.fillRect(x, edgeY - EDGE_REACH, w, EDGE_REACH);
		}
	}

	private static Color mix(Color a, Color b, float t)
	{
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}
}
