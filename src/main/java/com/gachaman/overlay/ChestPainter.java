package com.gachaman.overlay;

import com.gachaman.Tuning;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
final class ChestPainter
{
	private static final double OPEN_ANGLE_MAX = 1.95; // ~112 degrees

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
	private static final Color CHAIN_DARK = new Color(64, 64, 74);
	private static final Color CHAIN_LIGHT = new Color(148, 150, 162);
	private static final Color LOCK_GOLD = new Color(230, 190, 80);

	/** Shared scratch path; render happens on a single thread. */
	private static final Path2D.Double PATH = new Path2D.Double();

	private ChestPainter()
	{
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
		Tuning.Chest tier, Color trim, float innerGlow, float leak, long timeMs)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int x = cx - w / 2;
		int top = cy - h / 2;
		int bottom = cy + h / 2;
		double a = clamp01(openT) * OPEN_ANGLE_MAX;
		int lift = (int) (Math.max(0, openT - 1.0) * h * 0.55);

		int lidFrontH = (int) (h * 0.30);
		int dTop = (int) (h * 0.16);
		int frontTopY = top + lidFrontH + dTop;
		int inset = (int) (w * 0.07);
		int slant = (int) (w * 0.045);
		int hingeY = top;

		float openGlow = (float) smoothstep(Math.min(1.0, a / 1.5));
		float glow = Math.max(openGlow, clamp01f(innerGlow));

		drawDropShadow(g2, cx, bottom, w, h);
		drawBackRim(g2, x, w, inset, hingeY, frontTopY);
		if (a > 0.10)
		{
			drawInterior(g2, x, w, inset, hingeY, frontTopY, glow);
		}
		drawBody(g2, x, frontTopY, bottom, w, slant, tier, trim);
		if (glow > 0.04f)
		{
			drawSpillGlow(g2, cx, frontTopY, w, h, glow);
			drawMotes(g2, cx, frontTopY, w, h, glow, timeMs);
		}
		drawLid(g2, x, w, inset, hingeY, dTop, lidFrontH, frontTopY, a, lift, tier, trim, glow);
		if (leak > 0.03f && a < 0.15)
		{
			// seam between the closed lid skirt and the body top edge
			drawSeamLeak(g2, x, w, frontTopY, leak, timeMs);
		}
		g2.dispose();
	}

	// --- pieces ---

	private static void drawDropShadow(Graphics2D g2, int cx, int bottom, int w, int h)
	{
		int sw = (int) (w * 1.20);
		int sh = Math.max(10, h / 6);
		g2.setColor(new Color(0, 0, 0, 60));
		g2.fillOval(cx - sw / 2, bottom - sh / 2, sw, sh);
		int sw2 = (int) (w * 1.02);
		int sh2 = Math.max(8, h / 8);
		g2.setColor(new Color(0, 0, 0, 90));
		g2.fillOval(cx - sw2 / 2, bottom - sh2 / 2, sw2, sh2);
	}

	private static void drawBackRim(Graphics2D g2, int x, int w, int inset, int hingeY, int frontTopY)
	{
		// thin dark silhouette of the box's back wall rising behind the opening
		int backH = (frontTopY - hingeY) / 2;
		g2.setColor(new Color(38, 24, 10));
		g2.fillRect(x + inset, frontTopY - backH - 2, w - inset * 2, backH + 2);
		g2.setColor(new Color(20, 12, 5));
		g2.drawLine(x + inset, frontTopY - backH - 2, x + w - inset, frontTopY - backH - 2);
	}

	private static void drawInterior(Graphics2D g2, int x, int w, int inset, int hingeY,
		int frontTopY, float glow)
	{
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
		for (int i = 6; i >= 1; i--)
		{
			float t = i / 6f;
			int ew = (int) (span * (0.35 + 0.65 * (1 - t)));
			int eh = Math.max(4, (int) ((frontTopY - hingeY) * (1 - t) * 0.9));
			g2.setColor(withAlpha(GLOW_WARM, glow * 0.16f * (1 - t) + glow * 0.06f));
			g2.fillOval(cxm - ew / 2, frontTopY - eh - 2, ew, eh + 4);
		}
	}

	private static void drawBody(Graphics2D g2, int x, int frontTopY, int bottom, int w,
		int slant, Tuning.Chest tier, Color trim)
	{
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
		for (int p = 0; p < planks; p++)
		{
			int py = frontTopY + faceH * p / planks;
			int ph = faceH * (p + 1) / planks - faceH * p / planks;
			gp.setPaint(new GradientPaint(x, py, lightWood, x, py + ph, darkWood));
			gp.fillRect(x, py, w, ph + 1);
			// grain streaks (deterministic, subtle)
			gp.setColor(new Color(255, 235, 200, 16));
			int gy = py + ph / 3 + (p * 7) % Math.max(1, ph / 2);
			gp.drawLine(x + 6 + p * 11, gy, x + w / 2 + p * 9, gy);
			if (p > 0)
			{
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
		if (tier == Tuning.Chest.ORNATE)
		{
			g2.setColor(withAlpha(METAL_GOLD_HI, 0.55f));
			g2.setStroke(new BasicStroke(1.2f));
			g2.drawLine(x + 4, frontTopY + 3, x + w - 4, frontTopY + 3);
		}

		drawCornerCaps(g2, x, frontTopY, bottom, w, slant, tier);
		drawLockPlate(g2, x + w / 2, frontTopY, w, tier);
	}

	private static void drawBand(Graphics2D gp, int x, int frontTopY, int bottom, int w,
		int slant, float frac, Tuning.Chest tier)
	{
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
		for (int r = 0; r < 3; r++)
		{
			int ry = frontTopY + faceH * (r * 2 + 1) / 6;
			int rx = (int) lerp(xt, xb, (ry - frontTopY) / (double) faceH);
			gp.fillOval(rx - 2, ry - 2, 4, 4);
		}
		if (worn(tier))
		{
			// worn notches chipped out of the band edge
			gp.setColor(WOOD_DARK);
			gp.fillRect(xt - bandW / 2 - 1, frontTopY + faceH / 4, 3, 5);
			gp.fillRect(xb + bandW / 2 - 2, bottom - faceH / 3, 3, 6);
		}
	}

	private static void drawCornerCaps(Graphics2D g2, int x, int frontTopY, int bottom, int w,
		int slant, Tuning.Chest tier)
	{
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

	private static void drawLockPlate(Graphics2D g2, int cx, int frontTopY, int w, Tuning.Chest tier)
	{
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
		int lidFrontH, int frontTopY, double a, int lift, Tuning.Chest tier, Color trim, float glow)
	{
		double cosA = Math.cos(a);
		double sinA = Math.sin(a);
		double rise = (frontTopY - hingeY) * 1.6;

		int hy = hingeY - lift;
		double lipY = hy + dTop * cosA - rise * sinA;
		double lipHalf = (w * (1.0 - 0.10 * sinA)) / 2.0;
		double cxm = x + w / 2.0;

		// inner face: visible once past ~60 degrees, lit from the chest below
		float innerA = clamp01f((float) ((a - 1.0) / 0.5));
		if (innerA > 0.02f)
		{
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
		if (cosA > 0.06)
		{
			double skirtTopY = hy + dTop * cosA;
			double skirtBotY = skirtTopY + lidFrontH * cosA;
			double topHalf = lipHalf;
			double botHalf = (w * 1.02) / 2.0;
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
			if (a < 0.35)
			{
				g2.setColor(withAlpha(brighten(WOOD_LIGHT, 1.25f), (float) (1.0 - a / 0.35) * 0.5f));
				g2.setStroke(new BasicStroke(1.4f));
				g2.drawLine((int) (cxm - topHalf + 6), (int) skirtTopY + 2,
					(int) (cxm + topHalf - 6), (int) skirtTopY + 2);
			}
		}
	}

	private static void drawSpillGlow(Graphics2D g2, int cx, int frontTopY, int w, int h, float glow)
	{
		// column of warm light rising out of the opening
		for (int i = 5; i >= 1; i--)
		{
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
		float glow, long timeMs)
	{
		for (int p = 0; p < 14; p++)
		{
			float h1 = hash01(p * 197 + 11);
			float h2 = hash01(p * 197 + 12);
			float h3 = hash01(p * 197 + 13);
			int cycle = 1500 + (int) (h1 * 1300);
			float ph = ((timeMs + (long) (h2 * 99991)) % cycle) / (float) cycle;
			int px = cx + (int) ((h3 - 0.5f) * w * 0.62) + (int) (Math.sin(timeMs * 0.0012 + p) * 5);
			int py = frontTopY - 4 - (int) (ph * h * 0.85);
			float alpha = (1 - ph) * glow * (0.35f + 0.5f * h2);
			if (alpha < 0.03f)
			{
				continue;
			}
			if ((p & 1) == 0)
			{
				g2.setColor(withAlpha(METAL_GOLD_HI, alpha));
				g2.fillRect(px, py, 2, 2);
			}
			else
			{
				g2.setColor(withAlpha(GLOW_HOT, alpha * 0.6f));
				g2.fillRect(px, py, 1, 2);
			}
		}
	}

	private static void drawSeamLeak(Graphics2D g2, int x, int w, int seamY, float leak, long timeMs)
	{
		float flicker = 0.85f + 0.15f * (float) Math.sin(timeMs * 0.02);
		float a = clamp01f(leak) * flicker;
		for (int i = 3; i >= 1; i--)
		{
			g2.setColor(withAlpha(GLOW_WARM, a * 0.14f * (4 - i)));
			g2.setStroke(new BasicStroke(2f + i * 3f));
			g2.drawLine(x + 6, seamY, x + w - 6, seamY);
		}
		g2.setColor(withAlpha(GLOW_HOT, a));
		g2.setStroke(new BasicStroke(2f));
		g2.drawLine(x + 6, seamY, x + w - 6, seamY);
		// stray rays escaping the seam
		g2.setStroke(new BasicStroke(1.4f));
		for (int r = 0; r < 5; r++)
		{
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
	static void drawPadlock(Graphics2D g2, int cx, int cy, int chestW, long el, long shatterAtMs)
	{
		int size = chestW / 5;
		if (el >= shatterAtMs)
		{
			long t = el - shatterAtMs;
			if (t > 850)
			{
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
			for (int p = 0; p < 14; p++)
			{
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
		for (int i = 4; i >= 1; i--)
		{
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
	 * Two chains crossed over the closed ornate chest; each whips away
	 * (rotate + fling with gravity) when its start time passes.
	 */
	static void drawChains(Graphics2D g2, int cx, int cy, int w, int h, long el,
		long firstWhipMs, long whipGapMs)
	{
		for (int k = 0; k < 2; k++)
		{
			long start = firstWhipMs + k * whipGapMs;
			int dir = k == 0 ? 1 : -1;
			if (el < start)
			{
				drawChainDiagonal(g2, cx, cy, w, h, dir, 1f, 0, 0);
				continue;
			}
			long t = el - start;
			if (t > 650)
			{
				continue;
			}
			float u = t / 650f;
			double ts = t / 1000.0;
			int off = (int) (ts * 320) * dir;
			int drop = (int) (460 * ts * ts);
			drawChainDiagonal(g2, cx + off, cy + drop, w, h, dir, 1f - u, (float) (u * 1.4 * dir), k);
		}
	}

	private static void drawChainDiagonal(Graphics2D g2, int cx, int cy, int w, int h,
		int dir, float alpha, float rot, int seed)
	{
		Graphics2D gc = (Graphics2D) g2.create();
		if (rot != 0)
		{
			gc.rotate(rot * (0.4 + 0.15 * seed), cx, cy);
		}
		int x0 = cx - dir * (w / 2 + 8);
		int y0 = cy - h / 2 - 4;
		int x1 = cx + dir * (w / 2 + 8);
		int y1 = cy + h / 2 + 4;
		int links = 11;
		float a = clamp01f(alpha);
		for (int i = 0; i <= links; i++)
		{
			double t = i / (double) links;
			// slight sag toward the middle
			double sag = Math.sin(t * Math.PI) * h * 0.06;
			int lx = (int) lerp(x0, x1, t);
			int ly = (int) (lerp(y0, y1, t) + sag);
			Graphics2D gl = (Graphics2D) gc.create();
			double ang = Math.atan2(y1 - y0, x1 - x0) + ((i & 1) == 0 ? 0 : Math.PI / 2);
			gl.rotate(ang, lx, ly);
			gl.setColor(withAlpha(CHAIN_DARK, a));
			gl.fillOval(lx - 7, ly - 4, 14, 8);
			gl.setColor(withAlpha(CHAIN_LIGHT, a));
			gl.setStroke(new BasicStroke(2f));
			gl.drawOval(lx - 6, ly - 3, 12, 6);
			gl.dispose();
		}
		gc.dispose();
	}

	// --- palette helpers ---

	private static Color bandHi(Tuning.Chest tier)
	{
		switch (tier)
		{
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

	private static Color bandLo(Tuning.Chest tier)
	{
		switch (tier)
		{
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
	private static boolean worn(Tuning.Chest tier)
	{
		return tier == Tuning.Chest.BATTERED || tier == Tuning.Chest.RUSTY;
	}

	// --- math/color utilities (mirrors RevealOverlay's, kept private) ---

	private static double clamp01(double v)
	{
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static float clamp01f(float v)
	{
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static double smoothstep(double t)
	{
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double a, double b, double t)
	{
		return a + (b - a) * t;
	}

	private static float hash01(int n)
	{
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static Color withAlpha(Color c, float alpha)
	{
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	private static Color dim(Color c, float f)
	{
		return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f));
	}

	private static Color brighten(Color c, float f)
	{
		return new Color(Math.min(255, (int) (c.getRed() * f)),
			Math.min(255, (int) (c.getGreen() * f)),
			Math.min(255, (int) (c.getBlue() * f)));
	}

	private static Color mix(Color a, Color b, float t)
	{
		float u = clamp01f(t);
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * u),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * u),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * u));
	}
}
