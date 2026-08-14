package com.gachaman.overlay;

import java.awt.*;
import java.awt.geom.*;

/**
 * Draws the chrome of the unrolling contract scrolls used by the TASK_OFFERS
 * ceremony in {@link RevealOverlay}: the two wooden rollers (cylinders with
 * end caps) and the tinted parchment sheet stretched between them. Stateless
 * pure drawing - all animation timing and clipping live in the caller.
 */
final class ScrollPainter {
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

	private static final Color EDGE_SHADE = new Color(60, 42, 20, 90);
	private static final Color EDGE_CLEAR = new Color(60, 42, 20, 0);
	private static final Color VIGNETTE = new Color(120, 96, 60, 70);
	private static final Color VIGNETTE_CLEAR = new Color(120, 96, 60, 0);
	/** Wood fibre running the length of a roller. */
	private static final Color GRAIN = new Color(52, 34, 14, 55);
	/** Handling stain along the sheet's top and bottom edges. */
	private static final Color AGE = new Color(112, 84, 46, 46);
	private static final Color AGE_CLEAR = new Color(112, 84, 46, 0);
	/** Soft shadow: many faint passes rather than a few strong ones. */
	private static final int SHADOW_STEPS = 5;
	private static final int SHADOW_DROP = 5;
	private static final Color SHADOW_STEP = new Color(0, 0, 0, 11);

	private ScrollPainter() {
	}

	/** Center Y of the top roller at unroll progress u (0 = closed, 1 = open). */
	static int topRollerCy(Rectangle r, double u) {
		double closed = r.y + r.height / 2.0 - ROLLER_H / 2.0;
		double open = r.y + ROLLER_H / 2.0;
		return (int) Math.round(closed + (open - closed) * u);
	}

	/** Center Y of the bottom roller at unroll progress u (0 = closed, 1 = open). */
	static int bottomRollerCy(Rectangle r, double u) {
		double closed = r.y + r.height / 2.0 + ROLLER_H / 2.0;
		double open = r.y + r.height - ROLLER_H / 2.0;
		return (int) Math.round(closed + (open - closed) * u);
	}

	/**
	 * A horizontal wooden roller spanning the scroll bounds, centred on cy.
	 *
	 * <p>Nine-sliced rather than drawn: the cylinder's gradient runs purely
	 * vertically, so the middle is uniform along x and one narrow strip
	 * stretches to any scroll width exactly. Only the rounded ends and the end
	 * caps are fixed-width, and they blit unscaled.
	 *
	 * @param spin 0..1 phase of the grain, so a rolling scroll looks like it is
	 *             turning rather than sliding. The fibres stay procedural for
	 *             exactly that reason - they are the only part that moves.
	 */
	static void drawRoller(Graphics2D g, Rectangle r, int cy, Color tier, double spin) {
		int y = cy - ROLLER_H / 2;
		int bodyX = r.x + CAP_W - 2;
		int bodyW = r.width - (CAP_W - 2) * 2;
		String k = String.format("%06x", tier.getRGB() & 0xFFFFFF);
		int end = ROLLER_H / 2;
		ArtCache.blit(g, "roller-" + k + "-l", bodyX, y, end, ROLLER_H);
		ArtCache.blit(g, "roller-" + k + "-m", bodyX + end, y, bodyW - end * 2, ROLLER_H);
		ArtCache.blit(g, "roller-" + k + "-r", bodyX + bodyW - end, y, end, ROLLER_H);

		Shape clip = g.getClip();
		g.clip(new RoundRectangle2D.Float(bodyX, y, bodyW, ROLLER_H, ROLLER_H, ROLLER_H));
		g.setColor(GRAIN);
		for (int i = 0; i < 3; i++) {
			// floorMod, not %: the top roller spins backwards and Java's remainder
			// keeps the sign, which would push the grain off the clip entirely
			double phase = (((spin + i / 3.0) % 1.0) + 1.0) % 1.0;
			int gy = y + 2 + (int) Math.round(phase * (ROLLER_H - 4));
			g.drawLine(bodyX + 3, gy, bodyX + bodyW - 3, gy);
		}
		g.setClip(clip);

		ArtCache.blit(g, "roller-" + k + "-cap", r.x, y - 2, CAP_W, ROLLER_H + 4);
		ArtCache.blit(g, "roller-" + k + "-cap", r.x + r.width - CAP_W, y - 2, CAP_W, ROLLER_H + 4);
	}

	/**
	 * The parchment sheet at its full, final position: vertical paper
	 * gradient, aged vignette down both long edges, and tier-colored hairline
	 * borders. Callers clip this to the revealed window between the rollers.
	 */
	static void drawParchment(Graphics2D g, int x, int y, int w, int h,
		Color top, Color bottom, Color border) {
		g.setPaint(new GradientPaint(x, y, top, x, y + h, bottom));
		g.fillRect(x, y, w, h);

		// bow shading and paper fibre: both are pure fractions of the sheet, so
		// one stretched image (authored by com.gachaman.tools.ScrollArt)
		// reproduces them at any scroll size
		ArtCache.blit(g, "parchment-texture", x, y, w, h);

		// aged edges: the top and bottom of a sheet darken where it has been
		// handled, and it stops the paper reading as a flat swatch of colour
		int age = Math.min(18, h / 5);
		if (age > 2) {
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
	static void drawDropShadow(Graphics2D g, Rectangle r, double u) {
		int top = topRollerCy(r, u) - ROLLER_H / 2;
		int bot = bottomRollerCy(r, u) + ROLLER_H / 2;
		int h = bot - top;
		if (h <= 0) {
			return;
		}
		// Largest and faintest first, each smaller pass laid over the last, so the
		// alpha ACCUMULATES toward the middle and falls off at the rim.
		//
		// The previous version varied alpha per ring and floored it at 3, which
		// put a visible hard-edged step at the outermost ring and stacked to a
		// near-solid slab behind the scroll — it read as a dark panel the scroll
		// was mounted on rather than as light being blocked.
		for (int i = SHADOW_STEPS; i >= 1; i--) {
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
	static void drawEdgeShade(Graphics2D g, int x, int w, int edgeY, boolean fromTop) {
		if (fromTop) {
			g.setPaint(new GradientPaint(0, edgeY, EDGE_SHADE, 0, edgeY + EDGE_REACH, EDGE_CLEAR));
			g.fillRect(x, edgeY, w, EDGE_REACH);
		}
		else {
			g.setPaint(new GradientPaint(0, edgeY, EDGE_SHADE, 0, edgeY - EDGE_REACH, EDGE_CLEAR));
			g.fillRect(x, edgeY - EDGE_REACH, w, EDGE_REACH);
		}
	}

}
