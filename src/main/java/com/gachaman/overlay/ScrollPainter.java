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
	/**
	 * Horizontal inset of the parchment sheet from the scroll bounds.
	 *
	 * <p>Wide enough that the sheet clearly hangs BETWEEN the rollers rather than
	 * running out to their end caps. At 6 the paper reached almost to the knobs,
	 * so the rods read as trim glued along a card's edges instead of two rods
	 * with a sheet wound on them.
	 */
	static final int PARCH_INSET = 15;
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
	/** Wood fibre running the length of a roller. */
	private static final Color GRAIN = new Color(52, 34, 14, 55);
	/** Cross-section of a sheet bowed between two rods. */
	private static final Color BOW_SHADE = new Color(92, 68, 36, 70);
	private static final Color BOW_MID = new Color(120, 96, 60, 22);
	private static final Color BOW_LIGHT = new Color(255, 248, 226, 34);
	/**
	 * Paper fibre, as fractions down the sheet, each with its own inset and
	 * length as a fraction of the width. Fixed rather than random: a texture
	 * reseeded per frame crawls, and a scroll that shimmers reads as a fault.
	 *
	 * <p>Irregular on purpose. Evenly spaced full-width lines do not read as
	 * fibre in laid paper, they read as feint ruling — the page looked like a
	 * notebook rather than a document.
	 */
	private static final float[][] FIBRE_AT = {
		{0.11f, 0.06f, 0.62f},
		{0.26f, 0.31f, 0.55f},
		{0.38f, 0.04f, 0.34f},
		{0.57f, 0.22f, 0.71f},
		{0.69f, 0.09f, 0.41f},
		{0.84f, 0.36f, 0.52f},
	};
	private static final Color FIBRE = new Color(120, 96, 60, 11);
	/** Handling stain along the sheet's top and bottom edges. */
	private static final Color AGE = new Color(112, 84, 46, 46);
	private static final Color AGE_CLEAR = new Color(112, 84, 46, 0);
	/** Soft shadow: many faint passes rather than a few strong ones. */
	private static final int SHADOW_STEPS = 5;
	private static final int SHADOW_DROP = 5;
	private static final Color SHADOW_STEP = new Color(0, 0, 0, 11);

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
	 * A horizontal wooden roller spanning the scroll bounds, centered on cy.
	 *
	 * <p>Lit as an actual cylinder rather than a flat top-to-bottom ramp: a dark
	 * rim, a bright specular band held about a third of the way down, a mid
	 * body, a core shadow, and a weak bounce light along the bottom edge. That
	 * five-stop profile is what makes a rod read as round — a two-stop ramp
	 * reads as a bevelled bar however well it is coloured.
	 *
	 * @param spin 0..1 phase of the grain, so a rolling scroll looks like it is
	 *             turning rather than sliding
	 */
	static void drawRoller(Graphics2D g, Rectangle r, int cy, Color hi, Color lo, double spin)
	{
		int y = cy - ROLLER_H / 2;
		int bodyX = r.x + CAP_W - 2;
		int bodyW = r.width - (CAP_W - 2) * 2;

		java.awt.Shape clip = g.getClip();
		java.awt.geom.RoundRectangle2D body = new java.awt.geom.RoundRectangle2D.Float(
			bodyX, y, bodyW, ROLLER_H, ROLLER_H, ROLLER_H);

		Color rim = mix(lo, Color.BLACK, 0.35f);
		Color spec = mix(hi, Color.WHITE, 0.55f);
		Color core = mix(lo, Color.BLACK, 0.15f);
		Color bounce = mix(lo, hi, 0.45f);
		g.setPaint(new java.awt.LinearGradientPaint(
			new java.awt.geom.Point2D.Float(0, y),
			new java.awt.geom.Point2D.Float(0, y + ROLLER_H),
			new float[]{0f, 0.28f, 0.5f, 0.82f, 1f},
			new Color[]{rim, spec, hi, core, bounce}));
		g.fill(body);

		// grain: a few darker fibres running the length, their offset driven by
		// spin so the cylinder appears to rotate as the sheet pays out
		g.clip(body);
		g.setColor(GRAIN);
		for (int k = 0; k < 3; k++)
		{
			// floorMod, not %: the top roller spins backwards and Java's remainder
			// keeps the sign, which would push the grain off the clip entirely
			double phase = (((spin + k / 3.0) % 1.0) + 1.0) % 1.0;
			int gy = y + 2 + (int) Math.round(phase * (ROLLER_H - 4));
			g.drawLine(bodyX + 3, gy, bodyX + bodyW - 3, gy);
		}
		g.setClip(clip);

		// end caps as ellipses, not rounded rectangles: the cut end of a dowel is
		// a disc, and a radial highlight sells the turn better than a flat fill
		drawCap(g, r.x, y - 2, hi, lo);
		drawCap(g, r.x + r.width - CAP_W, y - 2, hi, lo);
	}

	/** One end-cap disc, lit from the upper left. */
	private static void drawCap(Graphics2D g, int x, int y, Color hi, Color lo)
	{
		int h = ROLLER_H + 4;
		g.setPaint(new java.awt.RadialGradientPaint(
			new java.awt.geom.Point2D.Float(x + CAP_W * 0.35f, y + h * 0.32f),
			Math.max(1f, h * 0.75f),
			new float[]{0f, 1f},
			new Color[]{mix(hi, Color.WHITE, 0.35f), mix(lo, Color.BLACK, 0.25f)}));
		g.fillOval(x, y, CAP_W, h);
		g.setColor(CAP_EDGE);
		g.drawOval(x, y, CAP_W - 1, h - 1);
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

		// A sheet held between two rods is not flat: it bows. Two soft bands of
		// shade a third of the way in, and a broad highlight up the middle, give
		// the page a cylinder's cross-section instead of a poster's.
		g.setPaint(new java.awt.LinearGradientPaint(
			new java.awt.geom.Point2D.Float(x, 0),
			new java.awt.geom.Point2D.Float(x + w, 0),
			new float[]{0f, 0.18f, 0.5f, 0.82f, 1f},
			new Color[]{BOW_SHADE, BOW_MID, BOW_LIGHT, BOW_MID, BOW_SHADE}));
		g.fillRect(x, y, w, h);

		// paper fibre: fixed offsets, never random — a texture reseeded per frame
		// crawls, and a scroll that shimmers reads as a rendering fault
		g.setColor(FIBRE);
		for (float[] fibre : FIBRE_AT)
		{
			int fy = y + (int) (h * fibre[0]);
			if (fy <= y || fy >= y + h - 1)
			{
				continue;
			}
			int fx = x + (int) (w * fibre[1]);
			int fw = (int) (w * fibre[2]);
			g.drawLine(fx, fy, Math.min(x + w - 3, fx + fw), fy);
		}

		// aged edges: the top and bottom of a sheet darken where it has been
		// handled, and it stops the paper reading as a flat swatch of colour
		int age = Math.min(18, h / 5);
		if (age > 2)
		{
			g.setPaint(new GradientPaint(0, y, AGE, 0, y + age, AGE_CLEAR));
			g.fillRect(x, y, w, age);
			g.setPaint(new GradientPaint(0, y + h, AGE, 0, y + h - age, AGE_CLEAR));
			g.fillRect(x, y + h - age, w, age);
		}

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
	 * A soft drop shadow under the whole scroll, so it sits above the scene
	 * rather than being printed on it. Drawn before anything else.
	 */
	static void drawDropShadow(Graphics2D g, Rectangle r, double u)
	{
		int top = topRollerCy(r, u) - ROLLER_H / 2;
		int bot = bottomRollerCy(r, u) + ROLLER_H / 2;
		int h = bot - top;
		if (h <= 0)
		{
			return;
		}
		// Largest and faintest first, each smaller pass laid over the last, so the
		// alpha ACCUMULATES toward the middle and falls off at the rim.
		//
		// The previous version varied alpha per ring and floored it at 3, which
		// put a visible hard-edged step at the outermost ring and stacked to a
		// near-solid slab behind the scroll — it read as a dark panel the scroll
		// was mounted on rather than as light being blocked.
		for (int i = SHADOW_STEPS; i >= 1; i--)
		{
			int grow = i * 3;
			g.setColor(SHADOW_STEP);
			g.fillRoundRect(r.x - grow, top - grow + SHADOW_DROP,
				r.width + grow * 2, h + grow * 2, 12 + grow, 12 + grow);
		}
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
