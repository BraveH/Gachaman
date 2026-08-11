package com.gachaman.tools;

import com.gachaman.Tuning;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * Procedural pseudo-3D treasure chest for the reveal ceremony. The chest is
 * drawn with fake perspective: a trapezoid front face with wood-plank strips,
 * side hints, a back rim, and a lid quad that rotates open around its back
 * hinge (vertical scale/shear approximates the 3D rotation; the inner face
 * fades in past ~60 degrees). Warm light spills from the opening, dust and
 * spark particles drift up. Pure function of its arguments - no state, no
 * timers; all animation comes in via {@code openT}, {@code leak} and
 * {@code timeMs} so callers control time. Single-threaded render use only
 * (shared scratch path).
 */
public final class ChestArt {
	private static final double OPEN_ANGLE_MAX = 1.95; // ~112 degrees

	// Body layout, as fractions of the chest box. Named because the chains have
	// to wrap the SILHOUETTE these produce, so the numbers now have two readers.
	private static final double LID_TOP_FRAC = 0.16;   // empty air above the lid
	private static final double LID_FRONT_FRAC = 0.30; // lid skirt height
	private static final double SLANT_FRAC = 0.045;    // front face taper
	private static final double SKIRT_WIDEN = 1.02;    // lid overhangs the body

	private static final Color WOOD_LIGHT = new Color(126, 84, 48);
	private static final Color WOOD_DARK = new Color(72, 46, 22);
	private static final Color WOOD_SEAM = new Color(46, 28, 12);
	private static final Color WOOD_SIDE = new Color(56, 35, 16);
	private static final Color INTERIOR = new Color(24, 14, 6);
	private static final Color INNER_LID = new Color(58, 36, 16);
	private static final Color GLOW_WARM = new Color(255, 214, 120);
	private static final Color GLOW_HOT = new Color(255, 240, 200);
	private static final Color METAL_SILVER_HI = new Color(212, 216, 226);
	private static final Color METAL_SILVER_LO = new Color(120, 124, 136);
	private static final Color METAL_GOLD_HI = new Color(244, 208, 96);
	private static final Color METAL_GOLD_LO = new Color(148, 108, 28);
	private static final Color METAL_WORN_HI = new Color(96, 92, 86);
	private static final Color METAL_WORN_LO = new Color(52, 48, 44);
	private static final Color METAL_RUST_HI = new Color(124, 78, 48);
	private static final Color METAL_RUST_LO = new Color(62, 40, 26);
	// Wide range on purpose: the chain sits on gold bands over lit wood, and a
	// mid-grey barstock with a mild highlight merges into all of it as one flat
	// mass. Dark body, hot specular, and the specular carries the scene's warmth.
	private static final Color CHAIN_LIGHT = new Color(136, 139, 150);
	private static final Color CHAIN_SHADE = new Color(20, 21, 27);
	private static final Color CHAIN_SPEC = new Color(246, 242, 228);
	private static final Color LOCK_GOLD = new Color(230, 190, 80);
	private static final Color LOCK_BODY_HI = new Color(112, 116, 130);
	private static final Color LOCK_BODY_LO = new Color(28, 30, 38);

	/** Key light for the chain barstock: up and to the left, screen space. */
	private static final double LIGHT_X = -0.52;
	private static final double LIGHT_Y = -0.85;

	private static final int LINK_FULL = 0;
	private static final int LINK_NEAR = 1;

	/** Where along its chain each padlock hangs; 0.30 puts the pair either side
	 * of the chest's own keyplate. */
	private static final double LOCK_T = 0.30;
	/** A lock gives, and only then does the chain it was holding let go. */
	private static final long LOCK_TO_WHIP_MS = 160;
	private static final long LOCK_BREAK_MS = 750;

	/** Shared scratch path; render happens on a single thread. */
	private static final Path2D.Double PATH = new Path2D.Double();

	private ChestArt() {
	}

	/**
	 * Draw the chest centered at (cx, cy) inside a w-by-h box.
	 *
	 * @param openT     0 = closed, 1 = fully open; values above 1 add blast
	 *                  lift (the lid keeps rising off the hinge). The lid ANGLE
	 *                  clamps at 1, so above-1 values only detach the lid from
	 *                  the hinge - reserve them for the stationary lid-blast
	 *                  beat and pass at most 1.0 while translating the chest,
	 *                  or the lid will visibly float off the moving body.
	 * @param tier      structural styling (band metal, trim richness)
	 * @param trim      trim color override (jackpot crossfades pass a mix)
	 * @param innerGlow extra interior glow 0..1 independent of the lid angle
	 * @param leak      seam light-leak intensity 0..1 (pre-blast tension)
	 */
	static void draw(Graphics2D g, int cx, int cy, int w, int h, double openT,
		Tuning.Chest tier, Color trim, float innerGlow, float leak, long timeMs) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int x = cx - w / 2;
		int top = cy - h / 2;
		int bottom = cy + h / 2;
		double a = clamp01(openT) * OPEN_ANGLE_MAX;
		int lift = (int) (Math.max(0, openT - 1.0) * h * 0.55);

		int lidFrontH = (int) (h * LID_FRONT_FRAC);
		int dTop = (int) (h * LID_TOP_FRAC);
		int frontTopY = top + lidFrontH + dTop;
		int inset = (int) (w * 0.07);
		int slant = (int) (w * SLANT_FRAC);
		int hingeY = top;

		float openGlow = (float) smoothstep(Math.min(1.0, a / 1.5));
		float glow = Math.max(openGlow, clamp01f(innerGlow));

		drawDropShadow(g2, cx, bottom, w, h);
		drawBackRim(g2, x, w, inset, hingeY, frontTopY);
		if (a > 0.10) {
			drawInterior(g2, x, w, inset, hingeY, frontTopY, glow);
		}
		drawBody(g2, x, frontTopY, bottom, w, slant, tier, trim);
		if (glow > 0.04f) {
			drawSpillGlow(g2, cx, frontTopY, w, h, glow);
			drawMotes(g2, cx, frontTopY, w, h, glow, timeMs);
		}
		drawLid(g2, x, w, inset, hingeY, dTop, lidFrontH, frontTopY, a, lift, tier, trim, glow);
		if (leak > 0.03f && a < 0.15) {
			// seam between the closed lid skirt and the body top edge
			drawSeamLeak(g2, x, w, frontTopY, leak, timeMs);
		}
		g2.dispose();
	}

	// --- pieces ---

	private static void drawDropShadow(Graphics2D g2, int cx, int bottom, int w, int h) {
		int sw = (int) (w * 1.20);
		int sh = Math.max(10, h / 6);
		g2.setColor(new Color(0, 0, 0, 60));
		g2.fillOval(cx - sw / 2, bottom - sh / 2, sw, sh);
		int sw2 = (int) (w * 1.02);
		int sh2 = Math.max(8, h / 8);
		g2.setColor(new Color(0, 0, 0, 90));
		g2.fillOval(cx - sw2 / 2, bottom - sh2 / 2, sw2, sh2);
	}

	private static void drawBackRim(Graphics2D g2, int x, int w, int inset, int hingeY, int frontTopY) {
		// thin dark silhouette of the box's back wall rising behind the opening
		int backH = (frontTopY - hingeY) / 2;
		g2.setColor(new Color(38, 24, 10));
		g2.fillRect(x + inset, frontTopY - backH - 2, w - inset * 2, backH + 2);
		g2.setColor(new Color(20, 12, 5));
		g2.drawLine(x + inset, frontTopY - backH - 2, x + w - inset, frontTopY - backH - 2);
	}

	private static void drawInterior(Graphics2D g2, int x, int w, int inset, int hingeY,
		int frontTopY, float glow) {
		PATH.reset();
		PATH.moveTo(x + 2, frontTopY);
		PATH.lineTo(x + w - 2, frontTopY);
		PATH.lineTo(x + w - inset, hingeY + 4);
		PATH.lineTo(x + inset, hingeY + 4);
		PATH.closePath();
		g2.setColor(INTERIOR);
		g2.fill(PATH);
		// warm pool inside, layered for a radial falloff
		int cxm = x + w / 2;
		int span = w - inset * 2;
		for (int i = 6; i >= 1; i--) {
			float t = i / 6f;
			int ew = (int) (span * (0.35 + 0.65 * (1 - t)));
			int eh = Math.max(4, (int) ((frontTopY - hingeY) * (1 - t) * 0.9));
			g2.setColor(withAlpha(GLOW_WARM, glow * 0.16f * (1 - t) + glow * 0.06f));
			g2.fillOval(cxm - ew / 2, frontTopY - eh - 2, ew, eh + 4);
		}
	}

	private static void drawBody(Graphics2D g2, int x, int frontTopY, int bottom, int w,
		int slant, Tuning.Chest tier, Color trim) {
		// side hints: dark slivers where the slanted front edges expose the sides
		PATH.reset();
		PATH.moveTo(x, frontTopY);
		PATH.lineTo(x + slant, bottom);
		PATH.lineTo(x, bottom);
		PATH.closePath();
		g2.setColor(WOOD_SIDE);
		g2.fill(PATH);
		PATH.reset();
		PATH.moveTo(x + w, frontTopY);
		PATH.lineTo(x + w - slant, bottom);
		PATH.lineTo(x + w, bottom);
		PATH.closePath();
		g2.fill(PATH);

		// front face trapezoid, clipped so plank strips stay inside
		PATH.reset();
		PATH.moveTo(x, frontTopY);
		PATH.lineTo(x + w, frontTopY);
		PATH.lineTo(x + w - slant, bottom);
		PATH.lineTo(x + slant, bottom);
		PATH.closePath();

		Graphics2D gp = (Graphics2D) g2.create();
		gp.setClip(PATH);
		boolean battered = worn(tier);
		float dimBody = tier == Tuning.Chest.RUSTY ? 0.70f : 0.82f;
		Color lightWood = battered ? dim(WOOD_LIGHT, dimBody) : WOOD_LIGHT;
		Color darkWood = battered ? dim(WOOD_DARK, dimBody) : WOOD_DARK;
		int planks = 4;
		int faceH = bottom - frontTopY;
		for (int p = 0; p < planks; p++) {
			int py = frontTopY + faceH * p / planks;
			int ph = faceH * (p + 1) / planks - faceH * p / planks;
			gp.setPaint(new GradientPaint(x, py, lightWood, x, py + ph, darkWood));
			gp.fillRect(x, py, w, ph + 1);
			// grain streaks (deterministic, subtle)
			gp.setColor(new Color(255, 235, 200, 16));
			int gy = py + ph / 3 + (p * 7) % Math.max(1, ph / 2);
			gp.drawLine(x + 6 + p * 11, gy, x + w / 2 + p * 9, gy);
			if (p > 0) {
				gp.setColor(WOOD_SEAM);
				gp.drawLine(x, py, x + w, py);
				gp.setColor(new Color(255, 220, 170, 24));
				gp.drawLine(x, py + 1, x + w, py + 1);
			}
		}
		// vertical metal bands following the trapezoid slant
		drawBand(gp, x, frontTopY, bottom, w, slant, 0.20f, tier);
		drawBand(gp, x, frontTopY, bottom, w, slant, 0.80f, tier);
		gp.dispose();

		// outline + tier trim
		g2.setColor(trim);
		g2.setStroke(new BasicStroke(tier == Tuning.Chest.ORNATE ? 3.2f : 2.4f));
		g2.draw(PATH);
		if (tier == Tuning.Chest.ORNATE) {
			g2.setColor(withAlpha(METAL_GOLD_HI, 0.55f));
			g2.setStroke(new BasicStroke(1.2f));
			g2.drawLine(x + 4, frontTopY + 3, x + w - 4, frontTopY + 3);
		}

		drawCornerCaps(g2, x, frontTopY, bottom, w, slant, tier);
		drawLockPlate(g2, x + w / 2, frontTopY, w, tier);
	}

	private static void drawBand(Graphics2D gp, int x, int frontTopY, int bottom, int w,
		int slant, float frac, Tuning.Chest tier) {
		int bandW = Math.max(6, w / 16);
		int xt = x + (int) (w * frac);
		int xb = x + slant + (int) ((w - slant * 2) * frac);
		PATH.reset();
		PATH.moveTo(xt - bandW / 2.0, frontTopY);
		PATH.lineTo(xt + bandW / 2.0, frontTopY);
		PATH.lineTo(xb + bandW / 2.0, bottom);
		PATH.lineTo(xb - bandW / 2.0, bottom);
		PATH.closePath();
		gp.setPaint(new GradientPaint(xt - bandW / 2f, frontTopY, bandHi(tier),
			xt + bandW / 2f, frontTopY, bandLo(tier)));
		gp.fill(PATH);
		gp.setColor(withAlpha(Color.BLACK, 0.35f));
		gp.setStroke(new BasicStroke(1f));
		gp.draw(PATH);
		// rivets
		gp.setColor(bandHi(tier));
		int faceH = bottom - frontTopY;
		for (int r = 0; r < 3; r++) {
			int ry = frontTopY + faceH * (r * 2 + 1) / 6;
			int rx = (int) lerp(xt, xb, (ry - frontTopY) / (double) faceH);
			gp.fillOval(rx - 2, ry - 2, 4, 4);
		}
		if (worn(tier)) {
			// worn notches chipped out of the band edge
			gp.setColor(WOOD_DARK);
			gp.fillRect(xt - bandW / 2 - 1, frontTopY + faceH / 4, 3, 5);
			gp.fillRect(xb + bandW / 2 - 2, bottom - faceH / 3, 3, 6);
		}
	}

	private static void drawCornerCaps(Graphics2D g2, int x, int frontTopY, int bottom, int w,
		int slant, Tuning.Chest tier) {
		int cap = Math.max(7, w / 14);
		g2.setPaint(new GradientPaint(x, frontTopY, bandHi(tier), x + cap, frontTopY + cap, bandLo(tier)));
		g2.fillRoundRect(x - 1, frontTopY - 1, cap, cap, 4, 4);
		g2.fillRoundRect(x + w - cap + 1, frontTopY - 1, cap, cap, 4, 4);
		g2.fillRoundRect(x + slant - 2, bottom - cap + 1, cap, cap, 4, 4);
		g2.fillRoundRect(x + w - slant - cap + 2, bottom - cap + 1, cap, cap, 4, 4);
		g2.setColor(withAlpha(Color.BLACK, 0.4f));
		g2.setStroke(new BasicStroke(1f));
		g2.drawRoundRect(x - 1, frontTopY - 1, cap, cap, 4, 4);
		g2.drawRoundRect(x + w - cap + 1, frontTopY - 1, cap, cap, 4, 4);
	}

	private static void drawLockPlate(Graphics2D g2, int cx, int frontTopY, int w, Tuning.Chest tier) {
		int pw = Math.max(14, w / 7);
		int ph = (int) (pw * 1.15);
		int px = cx - pw / 2;
		int py = frontTopY + 3;
		g2.setPaint(new GradientPaint(px, py, bandHi(tier), px, py + ph, bandLo(tier)));
		g2.fillRoundRect(px, py, pw, ph, 6, 6);
		g2.setColor(withAlpha(Color.BLACK, 0.45f));
		g2.setStroke(new BasicStroke(1.2f));
		g2.drawRoundRect(px, py, pw, ph, 6, 6);
		// keyhole
		g2.setColor(new Color(24, 16, 8));
		g2.fillOval(cx - 3, py + ph / 3 - 3, 6, 6);
		g2.fillRect(cx - 1, py + ph / 3, 3, ph / 3);
	}

	private static void drawLid(Graphics2D g2, int x, int w, int inset, int hingeY, int dTop,
		int lidFrontH, int frontTopY, double a, int lift, Tuning.Chest tier, Color trim, float glow) {
		double cosA = Math.cos(a);
		double sinA = Math.sin(a);
		double rise = (frontTopY - hingeY) * 1.6;

		int hy = hingeY - lift;
		double lipY = hy + dTop * cosA - rise * sinA;
		double lipHalf = (w * (1.0 - 0.10 * sinA)) / 2.0;
		double cxm = x + w / 2.0;

		// inner face: visible once past ~60 degrees, lit from the chest below
		float innerA = clamp01f((float) ((a - 1.0) / 0.5));
		if (innerA > 0.02f) {
			PATH.reset();
			PATH.moveTo(x + inset, hy);
			PATH.lineTo(x + w - inset, hy);
			PATH.lineTo(cxm + lipHalf, lipY);
			PATH.lineTo(cxm - lipHalf, lipY);
			PATH.closePath();
			Graphics2D gi = (Graphics2D) g2.create();
			gi.setClip(PATH);
			gi.setPaint(new GradientPaint((float) cxm, (float) lipY, dim(INNER_LID, 0.8f),
				(float) cxm, hy, mix(INNER_LID, GLOW_WARM, glow * 0.55f)));
			gi.fill(PATH);
			gi.setColor(withAlpha(GLOW_WARM, glow * 0.30f * innerA));
			gi.fillRect(x + inset, hy - 6, w - inset * 2, 12);
			gi.dispose();
			g2.setColor(withAlpha(trim, innerA));
			g2.setStroke(new BasicStroke(2.2f));
			g2.draw(PATH);
		}

		// outer skirt face: squashes vertically as the lid tips back
		if (cosA > 0.06) {
			double skirtTopY = hy + dTop * cosA;
			double skirtBotY = skirtTopY + lidFrontH * cosA;
			double topHalf = lipHalf;
			double botHalf = (w * SKIRT_WIDEN) / 2.0;
			PATH.reset();
			PATH.moveTo(cxm - topHalf, skirtTopY);
			PATH.lineTo(cxm + topHalf, skirtTopY);
			PATH.lineTo(cxm + botHalf, skirtBotY);
			PATH.lineTo(cxm - botHalf, skirtBotY);
			PATH.closePath();
			boolean battered = worn(tier);
			float hiDim = tier == Tuning.Chest.RUSTY ? 0.80f : 0.9f;
			float loDim = tier == Tuning.Chest.RUSTY ? 0.65f : 0.75f;
			Color hi = battered ? dim(WOOD_LIGHT, hiDim) : brighten(WOOD_LIGHT, 1.15f);
			Color lo = battered ? dim(WOOD_LIGHT, loDim) : WOOD_LIGHT;
			g2.setPaint(new GradientPaint((float) cxm, (float) skirtTopY, hi,
				(float) cxm, (float) skirtBotY, lo));
			g2.fill(PATH);

			Graphics2D gs = (Graphics2D) g2.create();
			gs.setClip(PATH);
			// plank seam across the skirt
			gs.setColor(WOOD_SEAM);
			int seamY = (int) lerp(skirtTopY, skirtBotY, 0.5);
			gs.drawLine((int) (cxm - botHalf), seamY, (int) (cxm + botHalf), seamY);
			// lid bands aligned with the body bands
			int bandW = Math.max(6, w / 16);
			gs.setPaint(new GradientPaint(0, (float) skirtTopY, bandHi(tier),
				0, (float) skirtBotY, bandLo(tier)));
			gs.fillRect((int) (cxm - w * 0.30) - bandW / 2, (int) skirtTopY - 2,
				bandW, (int) (skirtBotY - skirtTopY) + 4);
			gs.fillRect((int) (cxm + w * 0.30) - bandW / 2, (int) skirtTopY - 2,
				bandW, (int) (skirtBotY - skirtTopY) + 4);
			gs.dispose();

			g2.setColor(trim);
			g2.setStroke(new BasicStroke(tier == Tuning.Chest.ORNATE ? 3.0f : 2.2f));
			g2.draw(PATH);
			// arched top hint on the closed lid
			if (a < 0.35) {
				g2.setColor(withAlpha(brighten(WOOD_LIGHT, 1.25f), (float) (1.0 - a / 0.35) * 0.5f));
				g2.setStroke(new BasicStroke(1.4f));
				g2.drawLine((int) (cxm - topHalf + 6), (int) skirtTopY + 2,
					(int) (cxm + topHalf - 6), (int) skirtTopY + 2);
			}
		}
	}

	private static void drawSpillGlow(Graphics2D g2, int cx, int frontTopY, int w, int h, float glow) {
		// column of warm light rising out of the opening
		for (int i = 5; i >= 1; i--) {
			float t = i / 5f;
			int gw = (int) (w * (0.36 + 0.5 * (1 - t)));
			int gh = (int) (h * 0.7 * (1 - t * 0.35));
			g2.setColor(withAlpha(GLOW_WARM, glow * 0.10f * (1 - t) + glow * 0.03f));
			g2.fillOval(cx - gw / 2, frontTopY - gh, gw, gh + 8);
		}
		g2.setColor(withAlpha(GLOW_HOT, glow * 0.25f));
		g2.fillOval(cx - w / 4, frontTopY - 8, w / 2, 14);
	}

	private static void drawMotes(Graphics2D g2, int cx, int frontTopY, int w, int h,
		float glow, long timeMs) {
		for (int p = 0; p < 14; p++) {
			float h1 = hash01(p * 197 + 11);
			float h2 = hash01(p * 197 + 12);
			float h3 = hash01(p * 197 + 13);
			int cycle = 1500 + (int) (h1 * 1300);
			float ph = ((timeMs + (long) (h2 * 99991)) % cycle) / (float) cycle;
			int px = cx + (int) ((h3 - 0.5f) * w * 0.62) + (int) (Math.sin(timeMs * 0.0012 + p) * 5);
			int py = frontTopY - 4 - (int) (ph * h * 0.85);
			float alpha = (1 - ph) * glow * (0.35f + 0.5f * h2);
			if (alpha < 0.03f) {
				continue;
			}
			if ((p & 1) == 0) {
				g2.setColor(withAlpha(METAL_GOLD_HI, alpha));
				g2.fillRect(px, py, 2, 2);
			}
			else {
				g2.setColor(withAlpha(GLOW_HOT, alpha * 0.6f));
				g2.fillRect(px, py, 1, 2);
			}
		}
	}

	private static void drawSeamLeak(Graphics2D g2, int x, int w, int seamY, float leak, long timeMs) {
		float flicker = 0.85f + 0.15f * (float) Math.sin(timeMs * 0.02);
		float a = clamp01f(leak) * flicker;
		for (int i = 3; i >= 1; i--) {
			g2.setColor(withAlpha(GLOW_WARM, a * 0.14f * (4 - i)));
			g2.setStroke(new BasicStroke(2f + i * 3f));
			g2.drawLine(x + 6, seamY, x + w - 6, seamY);
		}
		g2.setColor(withAlpha(GLOW_HOT, a));
		g2.setStroke(new BasicStroke(2f));
		g2.drawLine(x + 6, seamY, x + w - 6, seamY);
		// stray rays escaping the seam
		g2.setStroke(new BasicStroke(1.4f));
		for (int r = 0; r < 5; r++) {
			float hr = hash01(r * 379 + 5);
			int rx = x + 10 + (int) (hr * (w - 20));
			int len = 6 + (int) (a * 22 * hash01(r * 379 + 6));
			g2.setColor(withAlpha(GLOW_HOT, a * 0.55f));
			g2.drawLine(rx, seamY - 2, rx + (int) ((hr - 0.5f) * 8), seamY - 2 - len);
		}
	}

	// --- ceremony props ---

	/**
	 * Gilded padlock hanging over the lock plate; pulses, then shatters into
	 * fragments at {@code shatterAtMs} (pure function of elapsed time).
	 */
	static void drawPadlock(Graphics2D g2, int cx, int cy, int chestW, long el, long shatterAtMs) {
		int size = chestW / 5;
		if (el >= shatterAtMs) {
			long t = el - shatterAtMs;
			if (t > 850) {
				return;
			}
			float u = t / 850f;
			// flash ring
			g2.setColor(withAlpha(GLOW_HOT, (1 - u) * 0.8f));
			g2.setStroke(new BasicStroke(2f + (1 - u) * 6f));
			int rr = (int) (size * (0.5 + u * 2.2));
			g2.drawOval(cx - rr, cy - rr, rr * 2, rr * 2);
			// fragments with fake gravity
			double ts = t / 1000.0;
			for (int p = 0; p < 14; p++) {
				float a1 = hash01(p * 131 + 7);
				float a2 = hash01(p * 131 + 8);
				double ang = a1 * Math.PI * 2;
				double speed = 70 + a2 * 190;
				int px = cx + (int) (Math.cos(ang) * speed * ts);
				int py = cy + (int) (Math.sin(ang) * speed * ts + 320 * ts * ts);
				int fs = 2 + (p % 3);
				g2.setColor(withAlpha((p & 1) == 0 ? METAL_GOLD_HI : METAL_SILVER_HI, 1 - u));
				Graphics2D gf = (Graphics2D) g2.create();
				gf.rotate(a1 * 6 + ts * 8, px, py);
				gf.fillRect(px - fs / 2, py - fs / 2, fs + 2, fs);
				gf.dispose();
			}
			return;
		}
		float pulse = 0.5f + 0.5f * (float) Math.sin(el * 0.008);
		// glow behind the lock
		for (int i = 4; i >= 1; i--) {
			g2.setColor(withAlpha(LOCK_GOLD, (0.10f + 0.10f * pulse) * (5 - i) / 5f));
			int pad = i * 5;
			g2.fillOval(cx - size / 2 - pad, cy - size / 2 - pad, size + pad * 2, size + pad * 2);
		}
		// shackle
		g2.setColor(METAL_SILVER_HI);
		g2.setStroke(new BasicStroke(5f));
		g2.drawArc(cx - size / 3, cy - size, size * 2 / 3, size * 2 / 3, 0, 180);
		g2.setColor(METAL_SILVER_LO);
		g2.setStroke(new BasicStroke(2f));
		g2.drawArc(cx - size / 3 + 2, cy - size + 2, size * 2 / 3 - 4, size * 2 / 3 - 4, 0, 180);
		// body
		g2.setPaint(new GradientPaint(cx - size / 2f, cy - size / 2f, METAL_GOLD_HI,
			cx + size / 2f, cy + size / 2f, METAL_GOLD_LO));
		g2.fillRoundRect(cx - size / 2, cy - size / 2, size, size, 8, 8);
		g2.setColor(withAlpha(Color.BLACK, 0.4f));
		g2.setStroke(new BasicStroke(1.4f));
		g2.drawRoundRect(cx - size / 2, cy - size / 2, size, size, 8, 8);
		g2.setColor(new Color(70, 52, 14));
		g2.fillOval(cx - 4, cy - 6, 8, 8);
		g2.fillRect(cx - 2, cy, 4, 8);
	}

	/**
	 * Two chains wrapped around the closed ornate chest, each held shut by its
	 * own padlock on the front. A lock bursts; the chain it was holding hangs
	 * slack for a beat and only then whips away (rotate + fling with gravity).
	 *
	 * <p>The OUTER chain goes first. Chain 1 is drawn second, so it lies over
	 * chain 0 where they cross, so it is the outer one, so its lock is the one
	 * that can be reached and the one that breaks first - you cannot unwrap the
	 * inner chain while the outer is still pinning it down. That is the kind of
	 * wrong that reads instantly even when nobody can say why.
	 *
	 * @param firstBreakMs when the outer chain's lock bursts
	 * @param breakGapMs   how long after that the inner one follows
	 */
	static void drawChains(Graphics2D g2, int cx, int cy, int w, int h, long el,
		long firstBreakMs, long breakGapMs) {
		int lockSize = Math.max(15, w / 9);
		long[] since = new long[2];
		double[][] at = new double[2][];
		for (int k = 0; k < 2; k++) {
			long breakAt = lockBreakMs(k, firstBreakMs, breakGapMs);
			since[k] = el - breakAt;
			// frozen at the break: the wreckage falls from where the lock WAS,
			// and does not keep riding a chain it is no longer attached to
			at[k] = chainPointAt(cx, cy, w, h, k == 0 ? 1 : -1, LOCK_T,
				Math.min(el, breakAt), k);
		}

		// the bow of each shackle, so the chain can run in FRONT of it
		for (int k = 0; k < 2; k++) {
			if (since[k] < 0) {
				drawLockBow(g2, at[k][0], at[k][1], lockSize, k == 0 ? 1 : -1, el, k);
			}
		}
		for (int k = 0; k < 2; k++) {
			int dir = k == 0 ? 1 : -1;
			long t = since[k] - LOCK_TO_WHIP_MS;
			if (t < 0) {
				// between the lock giving and the chain letting go, the tension
				// it was under comes out as slack - the chain sags and swings
				// wider for a beat before it goes
				drawChainDiagonal(g2, cx, cy, w, h, dir, 1f, 0, k, el,
					clamp01f(since[k] / (float) LOCK_TO_WHIP_MS) * 0.30f, true);
				continue;
			}
			if (t > 650) {
				continue;
			}
			float u = t / 650f;
			double ts = t / 1000.0;
			int off = (int) (ts * 320) * dir;
			int drop = (int) (460 * ts * ts);
			// a chain coming loose goes slack before it is clear of the box, so
			// the whip is a rigid throw plus a much larger travelling wave
			drawChainDiagonal(g2, cx + off, cy + drop, w, h, dir, 1f - u,
				(float) (u * 1.4 * dir), k, el, u, false);
		}
		// and the near leg and body back over the chain, closing the U on it
		for (int k = 0; k < 2; k++) {
			if (since[k] < 0) {
				drawLockFront(g2, at[k][0], at[k][1], lockSize, k == 0 ? 1 : -1, el, k);
			}
			else if (since[k] < LOCK_BREAK_MS) {
				drawLockBreak(g2, at[k][0], at[k][1], lockSize, since[k]);
			}
		}
	}

	/**
	 * When chain {@code k}'s padlock bursts. Chain 1 is drawn second, so it lies
	 * over chain 0 where they cross, so it is the OUTER one - and the outer lock
	 * is the one you can get at. Freeing the inner chain first would have it
	 * slide out from under a chain still pinning it down.
	 */
	static long lockBreakMs(int k, long firstBreakMs, long breakGapMs) {
		return firstBreakMs + (k == 1 ? 0 : breakGapMs);
	}

	/**
	 * The closed chest's outer silhouette - lid skirt plus body - as one shape.
	 * Used to clip the resting chains so their ends pass behind the box instead
	 * of hanging in the air beside it. Package-private so the test can pin it
	 * against {@link #chainEnds}: the layout fractions now have two readers, and
	 * a chain whose run leaves the silhouette is a chain the clip deletes.
	 */
	static Shape closedSilhouette(int cx, int cy, int w, int h) {
		double x = cx - w / 2.0;
		double top = cy - h / 2.0;
		double bottom = cy + h / 2.0;
		double skirtTop = top + h * LID_TOP_FRAC;
		double frontTop = skirtTop + h * LID_FRONT_FRAC;
		double slant = w * SLANT_FRAC;
		double botHalf = w * SKIRT_WIDEN / 2.0;
		Path2D.Double p = new Path2D.Double();
		p.moveTo(x, skirtTop);
		p.lineTo(x + w, skirtTop);
		p.lineTo(cx + botHalf, frontTop);
		p.lineTo(cx - botHalf, frontTop);
		p.closePath();
		p.moveTo(x, frontTop);
		p.lineTo(x + w, frontTop);
		p.lineTo(x + w - slant, bottom);
		p.lineTo(x + slant, bottom);
		p.closePath();
		return p;
	}

	/**
	 * Where chain {@code dir} enters and leaves the chest, before bow and sway.
	 *
	 * <p>Side to side, NOT corner to corner. A chain that ends on a corner could
	 * just as well be lying flat on the picture; one that runs off the left edge
	 * and off the right edge can only be continuing around the back. It still
	 * crosses the lid seam on the way, so it is still what holds the lid shut.
	 */
	static double[] chainEnds(int cx, int cy, int w, int h, int dir) {
		return new double[]{
			cx - dir * (w / 2.0),
			cy - h / 2.0 + h * (LID_TOP_FRAC + LID_FRONT_FRAC * 0.55),
			cx + dir * (w / 2.0),
			cy + h / 2.0 - h * 0.10};
	}

	/**
	 * How far the chain sits off its straight run at {@code c}, measured along
	 * the path normal. Three terms: the bow out over the front face, a slow
	 * swing, and a fast rattle at the ORNATE strain frequency so the chain
	 * visibly LAGS the box it is tied to instead of moving with it. All three
	 * are pinned to zero where the chain bites the corners.
	 *
	 * <p>Shared with the padlocks, which hang off the chain and so have to ride
	 * exactly this curve - a lock floating beside its own chain is the tell that
	 * gives the whole thing away.
	 */
	private static double chainNormal(double c, int w, int h, long el, int seed, float slack) {
		double env = Math.sin(c * Math.PI);
		double swing = (2.2 + 7.0 * slack) * Math.min(1.0, w / 300.0 + 0.4);
		return h * 0.055 * env
			+ swing * env * Math.sin(el * 0.0055 + c * 6.0 + seed * 2.0)
			+ 1.0 * env * Math.sin(el * 0.05 + c * 3.0 + seed);
	}

	/** Where chain {@code dir} actually is at {@code t}, sway and all. */
	private static double[] chainPointAt(int cx, int cy, int w, int h, int dir, double t,
		long el, int seed) {
		double[] e = chainEnds(cx, cy, w, h, dir);
		double dx = e[2] - e[0];
		double dy = e[3] - e[1];
		double len = Math.hypot(dx, dy);
		double n = chainNormal(clamp01(t), w, h, el, seed, 0f) * dir;
		return new double[]{e[0] + dx * t - dy / len * n, e[1] + dy * t + dx / len * n};
	}

	/**
	 * One chain wrapped diagonally around the chest, side to side.
	 *
	 * <p>What makes it read as a chain rather than a dotted line of beads: the
	 * link count comes from the path length divided by a 0.62 pitch, so every
	 * link's end bar lands INSIDE its neighbour's hole. A fixed count breaks
	 * that at every chest size but one. Then the flat links are drawn, the
	 * edge-on ones over them, and the flat links' NEAR arcs back over those - so
	 * each link genuinely threads behind one neighbour and in front of the next.
	 *
	 * <p>What makes it read as going AROUND the box rather than lying on top of
	 * the picture: the path runs past both sides of the silhouette and is
	 * clipped to it, so the ends disappear behind the chest; the last links
	 * before that edge foreshorten and drop into shadow as the band turns the
	 * corner; the run bows out over the front face and the whole chain twists
	 * along its length, so no two links present the same face.
	 *
	 * <p>And what makes it read as loose: it never sits still. A slow swing and
	 * a fast rattle ride the path normal, anchored to zero at both ends, and
	 * {@code slack} opens the swing right up while the chain is being flung.
	 */
	private static void drawChainDiagonal(Graphics2D g2, int cx, int cy, int w, int h,
		int dir, float alpha, float rot, int seed, long el, float slack, boolean wrapped) {
		float a = clamp01f(alpha);
		if (a <= 0.01f) {
			return;
		}
		Graphics2D gc = (Graphics2D) g2.create();
		gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if (wrapped) {
			gc.clip(closedSilhouette(cx, cy, w, h));
		}
		if (rot != 0) {
			gc.rotate(rot * (0.4 + 0.15 * seed), cx, cy);
		}

		double[] ends = chainEnds(cx, cy, w, h, dir);
		double x0 = ends[0];
		double y0 = ends[1];
		double x1 = ends[2];
		double y1 = ends[3];
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len = Math.hypot(dx, dy);
		// times dir, or the two chains bow in OPPOSITE directions - the left-hand
		// one sagging and the right-hand one arching - and their padlocks end up
		// hanging at visibly different heights
		double nx = -dy / len * dir;
		double ny = dx / len * dir;
		// run past both edges so the clip - not the last link - is what ends the
		// chain. A chain that stops exactly at the edge reads as painted on.
		// Constant in both states on purpose: the chain really is longer than
		// the face it crosses, so the flung one is the same length as the
		// wrapped one and the whip does not re-tessellate it on its first frame.
		double ext = 0.15;
		// floors matter: chestW is min(300, cw/3), so a narrow client asks for a
		// much smaller chain and an unfloored ring degenerates into a smear
		double linkLen = Math.max(11, w / 10.0);
		double bar = Math.max(2.2, w / 62.0);
		int links = Math.max(4,
			(int) Math.round(len * (1 + 2 * ext) / (linkLen * 0.62)));

		double[] px = new double[links + 1];
		double[] py = new double[links + 1];
		double[] ang = new double[links + 1];
		double[] halfLen = new double[links + 1];
		double[] halfWid = new double[links + 1];
		float[] shade = new float[links + 1];
		float[] linkA = new float[links + 1];
		// the length hidden behind the box has to come INTO view rather than
		// appear the instant the clip drops, so it fades up over the first
		// ~150ms of the whip - by which point the chain is already moving fast
		float freed = wrapped ? 0f : Math.min(1f, slack / 0.23f);

		for (int i = 0; i <= links; i++) {
			double t = -ext + (1 + 2 * ext) * i / links;
			double c = clamp01(t);
			double env = Math.sin(c * Math.PI);
			double n = chainNormal(c, w, h, el, seed, slack);
			px[i] = x0 + dx * t + nx * n;
			py[i] = y0 + dy * t + ny * n;
			// tangent of the OFFSET path, so links lie along the curve they sit
			// on rather than along the straight run underneath it. Differenced
			// rather than differentiated by hand: chainNormal has three terms and
			// two readers, and a stale derivative would tilt every link silently.
			double dn = (chainNormal(clamp01(t + 0.004), w, h, el, seed, slack)
				- chainNormal(clamp01(t - 0.004), w, h, el, seed, slack)) / 0.008;
			ang[i] = Math.atan2(dy + ny * dn, dx + nx * dn)
				+ (hash01(seed * 37 + i) - 0.5) * 0.10;

			// turning the corner: the band rolls away from the eye, so the last
			// links compress along their own axis and fall into shadow. Gentle
			// on both counts - crush the length and they stop being rings, crush
			// the light and the corner is a black lump instead of dark metal.
			// ...and released along with the rest of it: a chain in mid-air has
			// no corner left to turn, so the squash and the shadow let go as the
			// hidden length fades in
			double turn = (1 - smoothstep(Math.min(c, 1 - c) / 0.10)) * (1 - freed);
			// nearer the bottom is nearer the eye; the front face bows out too
			double s = (0.86 + 0.28 * c) * (1 + 0.10 * env);
			// the chain twists as it runs, so no two links show the same face
			double roll = c * 1.35 + seed * 0.9 + el * 0.0006;
			double open = Math.abs((i & 1) == 0 ? Math.cos(roll) : Math.sin(roll));
			halfLen[i] = (linkLen * s * (1 - 0.34 * turn) - bar) / 2;
			halfWid[i] = Math.max(bar * 0.85, linkLen * s * 0.30 * open - bar / 2);
			shade[i] = (float) (0.58 + 0.42 * (1 - turn));
			linkA[i] = t < 0 || t > 1 ? a * (wrapped ? 1f : freed) : a;
		}

		drawChainShadow(gc, px, py, bar, a);
		for (int pass = 0; pass < 3; pass++) {
			for (int i = 0; i <= links; i++) {
				boolean flat = (i & 1) == 0;
				// flat links, then the edge-on ones on top, then the flat links'
				// near arcs back over the top: that ordering IS the interlock
				if (pass == 0 ? !flat : (pass == 1) == flat) {
					continue;
				}
				if (linkA[i] <= 0.01f) {
					continue;
				}
				chainLink(gc, px[i], py[i], ang[i], halfLen[i], halfWid[i], bar, linkA[i],
					shade[i], pass == 2 ? LINK_NEAR : LINK_FULL);
			}
		}
		gc.dispose();
	}

	/**
	 * Diffuse shadow the chain throws onto the chest face. One soft stroke along
	 * the whole path rather than per-link, which is both cheaper and closer to
	 * how a shadow that far from its caster actually looks.
	 */
	private static void drawChainShadow(Graphics2D gc, double[] px, double[] py,
		double bar, float a) {
		PATH.reset();
		for (int i = 0; i < px.length; i++) {
			if (i == 0) {
				PATH.moveTo(px[i] + bar * 0.55, py[i] + bar * 0.8);
			}
			else {
				PATH.lineTo(px[i] + bar * 0.55, py[i] + bar * 0.8);
			}
		}
		for (int i = 2; i >= 1; i--) {
			gc.setColor(withAlpha(Color.BLACK, a * 0.24f));
			gc.setStroke(new BasicStroke((float) (bar * (1.1 + i * 0.8)),
				BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			gc.draw(PATH);
		}
	}

	/**
	 * One link, drawn in its own rotated frame. {@code halfLen}/{@code halfWid}
	 * are the ellipse the barstock centre follows, so the silhouette runs half a
	 * bar wider than that in every direction and the hole is what is left inside.
	 */
	private static void chainLink(Graphics2D gc, double lx, double ly, double ang,
		double halfLen, double halfWid, double bar, float a, float shade, int mode) {
		AffineTransform old = gc.getTransform();
		gc.translate(lx, ly);
		gc.rotate(ang);
		// the key light is fixed in screen space, so it has to be carried into
		// the link's frame - otherwise every link is lit from its own direction
		double llx = LIGHT_X * Math.cos(ang) + LIGHT_Y * Math.sin(ang);
		double lly = -LIGHT_X * Math.sin(ang) + LIGHT_Y * Math.cos(ang);
		double r = Math.max(halfLen, halfWid) * 0.95;
		Ellipse2D.Double ring = new Ellipse2D.Double(-halfLen, -halfWid,
			halfLen * 2, halfWid * 2);
		Shape body = mode == LINK_NEAR
			? new Arc2D.Double(-halfLen, -halfWid, halfLen * 2, halfWid * 2, 188, 164,
				Arc2D.OPEN)
			: ring;

		if (mode == LINK_FULL) {
			double hw = halfWid * 2 - bar;
			if (hw > 1) {
				// the hole is a window onto the planks, in the link's own shadow
				gc.setColor(withAlpha(Color.BLACK, a * 0.42f));
				gc.fill(new Ellipse2D.Double(-halfLen + bar * 0.5, -halfWid + bar * 0.5,
					halfLen * 2 - bar, hw));
			}
			// contour, so the metal sits ON the wood instead of floating over it.
			// Eased off with the light: a full-strength outline on an already
			// dark link at the far edge just paints a black lump.
			gc.setColor(withAlpha(Color.BLACK, a * 0.5f * (0.55f + 0.45f * shade)));
			gc.setStroke(new BasicStroke((float) bar + 1.1f, BasicStroke.CAP_ROUND,
				BasicStroke.JOIN_ROUND));
			gc.draw(ring);
		}

		barMetal(gc, body, llx, lly, r, bar, a, shade);
		gc.setTransform(old);
	}

	/**
	 * Round barstock, stroked along {@code body}: a gradient around the form
	 * from the key light, then a specular that dies before it reaches the shaded
	 * side. {@code llx,lly} is the light in the shape's own frame and {@code r}
	 * the radius the gradient spans. Shared by the chain links and the padlock
	 * shackles so both are visibly the same steel.
	 */
	private static void barMetal(Graphics2D gc, Shape body, double llx, double lly,
		double r, double bar, float a, float shade) {
		gc.setPaint(new GradientPaint(
			(float) (llx * r), (float) (lly * r), withAlpha(dim(CHAIN_LIGHT, shade), a),
			(float) (-llx * r), (float) (-lly * r), withAlpha(dim(CHAIN_SHADE, shade), a)));
		gc.setStroke(new BasicStroke((float) bar, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		gc.draw(body);

		AffineTransform old = gc.getTransform();
		gc.translate(llx * bar * 0.26, lly * bar * 0.26);
		gc.setPaint(new GradientPaint(
			(float) (llx * r), (float) (lly * r), withAlpha(dim(CHAIN_SPEC, shade), a * 0.85f),
			(float) (llx * r * 0.15), (float) (lly * r * 0.15), withAlpha(CHAIN_SPEC, 0f)));
		gc.setStroke(new BasicStroke((float) (bar * 0.40), BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND));
		gc.draw(body);
		gc.setTransform(old);
	}

	// --- the padlock on each chain ---
	//
	// Geometry is in units of `size`, in a frame whose origin is the point on
	// the chain the lock hangs from - which is also the middle of the shackle's
	// opening, so the chain runs THROUGH the U rather than past it. The bow and
	// the far leg are painted before the chain and the near leg and body after,
	// so the chain is genuinely inside the shackle and not merely behind a lock
	// parked on top of it.

	private static final double SHACKLE_BAR = 0.15;
	private static final double SHACKLE_R = 0.44;
	// The bow has to clear the chain running through it, and a chain link is
	// half as tall as this whole lock - so the U is deliberately tall. Sized
	// tight and the shackle vanishes behind the very chain it is holding.
	private static final double SHACKLE_TOP = -0.22;  // centre of the bow
	private static final double LOCK_BODY_TOP = 0.34;
	private static final double LOCK_BODY_H = 0.92;

	/** How far the lock has swung on its chain, in radians. */
	private static double lockTilt(long el, int seed) {
		return 0.07 * Math.sin(el * 0.0055 + seed * 2.0 + 0.9);
	}

	/** The bow of the shackle and its far leg: everything the chain runs over. */
	private static void drawLockBow(Graphics2D g2, double lx, double ly, int size,
		int dir, long el, int seed) {
		Graphics2D gl = (Graphics2D) g2.create();
		gl.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gl.translate(lx, ly);
		gl.rotate(lockTilt(el, seed));
		double bar = size * SHACKLE_BAR;
		double r = size * SHACKLE_R;
		double top = size * SHACKLE_TOP;
		// the far leg is the one on the side the chain climbs towards, because
		// higher on this chest means further away
		double legX = -dir * r;
		Path2D.Double bow = new Path2D.Double();
		bow.moveTo(legX, size * LOCK_BODY_TOP);
		bow.lineTo(legX, top);
		bow.append(new Arc2D.Double(-r, top - r, r * 2, r * 2, dir > 0 ? 180 : 0, dir > 0 ? -180 : 180,
			Arc2D.OPEN), true);
		gl.setColor(withAlpha(Color.BLACK, 0.5f));
		gl.setStroke(new BasicStroke((float) bar + 1.2f, BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND));
		gl.draw(bow);
		barMetal(gl, bow, LIGHT_X, LIGHT_Y, r, bar, 1f, 0.92f);
		gl.dispose();
	}

	/** The near leg and the body: everything that closes back over the chain. */
	private static void drawLockFront(Graphics2D g2, double lx, double ly, int size,
		int dir, long el, int seed) {
		Graphics2D gl = (Graphics2D) g2.create();
		gl.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gl.translate(lx, ly);
		gl.rotate(lockTilt(el, seed));
		lockNearLeg(gl, size, dir);
		lockBody(gl, size, 1f);
		gl.dispose();
	}

	private static void lockNearLeg(Graphics2D gl, int size, int dir) {
		double bar = size * SHACKLE_BAR;
		double legX = dir * size * SHACKLE_R;
		Path2D.Double leg = new Path2D.Double();
		leg.moveTo(legX, size * SHACKLE_TOP);
		leg.lineTo(legX, size * (LOCK_BODY_TOP + 0.1));
		gl.setColor(withAlpha(Color.BLACK, 0.5f));
		gl.setStroke(new BasicStroke((float) bar + 1.2f, BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND));
		gl.draw(leg);
		barMetal(gl, leg, LIGHT_X, LIGHT_Y, bar * 1.6, bar, 1f, 1f);
	}

	/**
	 * The lock body: an extruded side face behind a bevelled front plate, with a
	 * brass escutcheon around the keyhole. The extrusion is what stops it from
	 * reading as a rounded rectangle stuck to the wood.
	 */
	private static void lockBody(Graphics2D gl, int size, float a) {
		double bw = size;
		double bh = size * LOCK_BODY_H;
		double bx = -bw / 2;
		double by = size * LOCK_BODY_TOP;
		int arc = (int) (size * 0.34);
		double dz = size * 0.10;

		// contact shadow on the chest, then the extruded right/bottom faces
		gl.setColor(withAlpha(Color.BLACK, a * 0.42f));
		gl.fillRoundRect((int) (bx + dz * 1.5), (int) (by + dz * 1.7), (int) bw, (int) bh,
			arc, arc);
		gl.setPaint(new GradientPaint((float) bx, (float) by, brighten(LOCK_BODY_LO, 1.6f),
			(float) (bx + bw + dz), (float) (by + bh + dz), dim(LOCK_BODY_LO, 0.7f)));
		gl.fillRoundRect((int) (bx + dz), (int) (by + dz), (int) bw, (int) bh, arc, arc);

		// front plate
		gl.setPaint(new GradientPaint((float) bx, (float) by, LOCK_BODY_HI,
			(float) (bx + bw), (float) (by + bh), LOCK_BODY_LO));
		gl.fillRoundRect((int) bx, (int) by, (int) bw, (int) bh, arc, arc);
		// bevel: a lit band across the top and a bright edge on the top-left
		gl.setPaint(new GradientPaint(0f, (float) by, withAlpha(CHAIN_SPEC, a * 0.30f),
			0f, (float) (by + bh * 0.42), withAlpha(CHAIN_SPEC, 0f)));
		gl.fillRoundRect((int) bx + 1, (int) by + 1, (int) bw - 2, (int) (bh * 0.45), arc, arc);
		gl.setColor(withAlpha(CHAIN_SPEC, a * 0.55f));
		gl.setStroke(new BasicStroke(1.3f));
		gl.drawArc((int) bx + 1, (int) by + 1, arc, arc, 95, 80);
		gl.setColor(withAlpha(Color.BLACK, a * 0.55f));
		gl.drawRoundRect((int) bx, (int) by, (int) bw, (int) bh, arc, arc);

		// scuffs, so the face is a used object and not a swatch
		gl.setColor(withAlpha(CHAIN_SPEC, a * 0.10f));
		gl.setStroke(new BasicStroke(1f));
		gl.drawLine((int) (bx + bw * 0.18), (int) (by + bh * 0.30),
			(int) (bx + bw * 0.44), (int) (by + bh * 0.26));
		gl.drawLine((int) (bx + bw * 0.55), (int) (by + bh * 0.78),
			(int) (bx + bw * 0.84), (int) (by + bh * 0.72));

		// the two holes the shackle enters. Without them the legs simply stop at
		// the body edge and the whole lock reads as a sticker on the chest.
		double hx = size * SHACKLE_R;
		double hr2 = size * SHACKLE_BAR * 0.68;
		for (int s = -1; s <= 1; s += 2) {
			gl.setColor(withAlpha(new Color(9, 9, 12), a * 0.92f));
			gl.fillOval((int) (s * hx - hr2), (int) (by - hr2 * 0.6), (int) (hr2 * 2),
				(int) (hr2 * 1.5));
			gl.setColor(withAlpha(CHAIN_SPEC, a * 0.28f));
			gl.drawArc((int) (s * hx - hr2), (int) (by - hr2 * 0.6), (int) (hr2 * 2),
				(int) (hr2 * 1.5), 195, 150);
		}

		// brass escutcheon, recessed into the face, and the keyhole through it
		double kx = 0;
		double ky = by + bh * 0.54;
		double kr = size * 0.185;
		gl.setColor(withAlpha(Color.BLACK, a * 0.55f));
		gl.fillOval((int) (kx - kr - 1), (int) (ky - kr), (int) (kr * 2 + 2), (int) (kr * 2 + 2));
		gl.setPaint(new GradientPaint((float) (kx - kr), (float) (ky - kr), LOCK_GOLD,
			(float) (kx + kr), (float) (ky + kr), METAL_GOLD_LO));
		gl.fillOval((int) (kx - kr), (int) (ky - kr), (int) (kr * 2), (int) (kr * 2));
		gl.setColor(withAlpha(METAL_GOLD_HI, a * 0.5f));
		gl.setStroke(new BasicStroke(1f));
		gl.drawArc((int) (kx - kr), (int) (ky - kr), (int) (kr * 2), (int) (kr * 2), 70, 130);
		gl.setColor(withAlpha(new Color(12, 9, 5), a));
		double hr = kr * 0.44;
		gl.fillOval((int) (kx - hr), (int) (ky - hr * 1.3), (int) (hr * 2), (int) (hr * 2));
		Path2D.Double slot = new Path2D.Double();
		slot.moveTo(kx - hr * 0.75, ky);
		slot.lineTo(kx + hr * 0.75, ky);
		slot.lineTo(kx + hr * 0.35, ky + kr * 0.95);
		slot.lineTo(kx - hr * 0.35, ky + kr * 0.95);
		slot.closePath();
		gl.fill(slot);
	}

	/**
	 * The lock giving way: a flash, the shackle springing open at the bow and
	 * both halves tumbling off, the body dropping under it, and shrapnel. Pure
	 * function of {@code since} - the ms elapsed since it broke.
	 */
	private static void drawLockBreak(Graphics2D g2, double lx, double ly, int size, long since) {
		float u = clamp01f(since / (float) LOCK_BREAK_MS);
		double ts = since / 1000.0;
		float vis = (float) (1 - smoothstep(Math.max(0, (u - 0.6) / 0.4)));
		Graphics2D gl = (Graphics2D) g2.create();
		gl.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gl.translate(lx, ly);

		// a bloom, not a ring: struck steel throws light and grit, and a clean
		// expanding outline reads as a vector effect laid over the scene
		if (u < 0.30f) {
			float f = 1 - u / 0.30f;
			for (int i = 3; i >= 1; i--) {
				int rr = (int) (size * (0.30 + i * 0.42) * (1 + (1 - f) * 0.7));
				gl.setColor(withAlpha(i == 1 ? GLOW_HOT : GLOW_WARM, f * f * 0.30f));
				gl.fillOval(-rr, -rr, rr * 2, rr * 2);
			}
		}

		double bar = size * SHACKLE_BAR;
		double r = size * SHACKLE_R;
		double top = size * SHACKLE_TOP;
		for (int s = 0; s < 2; s++) {
			int sgn = s == 0 ? -1 : 1;
			AffineTransform old = gl.getTransform();
			gl.translate(sgn * 230 * ts, -150 * ts + 820 * ts * ts);
			gl.rotate(sgn * ts * 8.5);
			Path2D.Double half = new Path2D.Double();
			half.moveTo(sgn * r, size * LOCK_BODY_TOP);
			half.lineTo(sgn * r, top);
			half.append(new Arc2D.Double(-r, top - r, r * 2, r * 2, sgn > 0 ? 0 : 180,
				sgn > 0 ? 88 : -88, Arc2D.OPEN), true);
			barMetal(gl, half, LIGHT_X, LIGHT_Y, r, bar, vis, 1f);
			gl.setTransform(old);
		}

		AffineTransform old = gl.getTransform();
		gl.translate(28 * ts, 60 * ts + 900 * ts * ts);
		gl.rotate(ts * 2.4);
		lockBody(gl, size, vis);
		gl.setTransform(old);

		// grit, drawn as short trails along its own velocity rather than as dots
		gl.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int p = 0; p < 14; p++) {
			float a1 = hash01(p * 151 + 3);
			float a2 = hash01(p * 151 + 4);
			double ang = a1 * Math.PI * 2;
			double sp = 90 + a2 * 260;
			double vx = Math.cos(ang) * sp;
			double vy = Math.sin(ang) * sp + 760 * ts;
			double fx = vx * ts;
			double fy = Math.sin(ang) * sp * ts + 380 * ts * ts;
			double trail = Math.min(7, Math.hypot(vx, vy) * 0.016);
			double m = Math.max(1e-3, Math.hypot(vx, vy));
			gl.setColor(withAlpha((p & 1) == 0 ? CHAIN_SPEC : METAL_GOLD_HI,
				vis * (0.55f + 0.45f * a2)));
			gl.draw(new Line2D.Double(fx, fy,
				fx - vx / m * trail, fy - vy / m * trail));
		}
		gl.dispose();
	}

	// --- palette helpers ---

	private static Color bandHi(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return METAL_RUST_HI;
			case BATTERED:
				return METAL_WORN_HI;
			case GILDED:
				return METAL_SILVER_HI;
			default:
				return METAL_GOLD_HI;
		}
	}

	private static Color bandLo(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return METAL_RUST_LO;
			case BATTERED:
				return METAL_WORN_LO;
			case GILDED:
				return METAL_SILVER_LO;
			default:
				return METAL_GOLD_LO;
		}
	}

	/** Tiers with worn (dimmed) wood; Rusty is one visible step shabbier. */
	private static boolean worn(Tuning.Chest tier) {
		return tier == Tuning.Chest.BATTERED || tier == Tuning.Chest.RUSTY;
	}

	// --- math/color utilities (mirrors RevealOverlay's, kept private) ---

	private static double clamp01(double v) {
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static float clamp01f(float v) {
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static double smoothstep(double t) {
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static float hash01(int n) {
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static Color withAlpha(Color c, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	private static Color dim(Color c, float f) {
		return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f));
	}

	private static Color brighten(Color c, float f) {
		return new Color(Math.min(255, (int) (c.getRed() * f)),
			Math.min(255, (int) (c.getGreen() * f)),
			Math.min(255, (int) (c.getBlue() * f)));
	}

	private static Color mix(Color a, Color b, float t) {
		float u = clamp01f(t);
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * u),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * u),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * u));
	}

	public static final int ART_W = 300;
	public static final int ART_H = 225;
	public static final int PAD_X = 34;
	public static final int PAD_Y = 26;

	public static void drawStatic(Graphics2D g, int cx, int cy, int w, int h, Tuning.Chest tier,
		Color trim, boolean back) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int x = cx - w / 2;
		int top = cy - h / 2;
		int bottom = cy + h / 2;
		int frontTopY = top + (int) (h * LID_FRONT_FRAC) + (int) (h * LID_TOP_FRAC);
		int inset = (int) (w * 0.07);
		int slant = (int) (w * SLANT_FRAC);
		if (back) {
			drawDropShadow(g2, cx, bottom, w, h);
			drawBackRim(g2, x, w, inset, top, frontTopY);
		}
		else {
			drawBody(g2, x, frontTopY, bottom, w, slant, tier, trim);
		}
		g2.dispose();
	}

	public static void main(String[] args) throws Exception {
		java.io.File dir = new java.io.File(args[0]);
		dir.mkdirs();
		Color[] trims = {new Color(122, 82, 46), new Color(139, 98, 46),
			new Color(198, 202, 212), new Color(230, 190, 80)};
		for (int part = 0; part < 2; part++) {
			for (Tuning.Chest t : Tuning.Chest.values()) {
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
					ART_W + PAD_X * 2, ART_H + PAD_Y * 2,
					java.awt.image.BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				drawStatic(g, ART_W / 2 + PAD_X, ART_H / 2 + PAD_Y, ART_W, ART_H, t,
					trims[t.ordinal()], part == 0);
				g.dispose();
				javax.imageio.ImageIO.write(img, "png", new java.io.File(dir,
					(part == 0 ? "chest-back-" : "chest-body-") + t.name().toLowerCase() + ".png"));
			}
		}
		System.out.println("sprites written");
	}
}
