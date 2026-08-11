package com.gachaman.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Authors the scroll rollers as 9-slice pieces, one set per difficulty tint.
 *
 * <p>Exact rather than approximate: the roller body's gradient runs purely
 * vertically, so its middle is uniform along x and a narrow strip stretches to
 * any scroll width with no distortion. Only the two rounded ends and the end
 * caps are fixed-width, and those are blitted unscaled.
 *
 * <p>The three grain fibres are NOT baked - they are driven by spin, and that
 * is what makes the rods look like they are turning.
 */
public final class RollerArt {
	static final int H = 18;
	static final int CAP_W = 7;
	static final int END = H / 2;
	static final int MID = 8;
	private static final Color WOOD_LIGHT = new Color(168, 128, 76);
	private static final Color WOOD_DARK = new Color(70, 48, 24);
	private static final Color CAP_EDGE = new Color(34, 24, 12, 200);

	static final Color[] TINTS = {
		new Color(120, 200, 120), new Color(240, 200, 80), new Color(240, 130, 60),
		new Color(230, 60, 60), new Color(120, 20, 20),
	};

	static String key(Color tier) {
		return String.format("%06x", tier.getRGB() & 0xFFFFFF);
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		int w = 120;
		for (Color tier : TINTS) {
			Color hi = mix(WOOD_LIGHT, tier, 0.45f);
			Color lo = mix(WOOD_DARK, tier, 0.30f);
			BufferedImage body = new BufferedImage(w, H, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = body.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setPaint(new LinearGradientPaint(
				new Point2D.Float(0, 0), new Point2D.Float(0, H),
				new float[]{0f, 0.28f, 0.5f, 0.82f, 1f},
				new Color[]{mix(lo, Color.BLACK, 0.35f), mix(hi, Color.WHITE, 0.55f), hi,
					mix(lo, Color.BLACK, 0.15f), mix(lo, hi, 0.45f)}));
			g.fill(new RoundRectangle2D.Float(0, 0, w, H, H, H));
			g.dispose();
			String k = key(tier);
			ImageIO.write(body.getSubimage(0, 0, END, H), "png", new File(dir, "roller-" + k + "-l.png"));
			ImageIO.write(body.getSubimage(w / 2, 0, MID, H), "png", new File(dir, "roller-" + k + "-m.png"));
			ImageIO.write(body.getSubimage(w - END, 0, END, H), "png", new File(dir, "roller-" + k + "-r.png"));

			int ch = H + 4;
			BufferedImage cap = new BufferedImage(CAP_W, ch, BufferedImage.TYPE_INT_ARGB);
			g = cap.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setPaint(new RadialGradientPaint(
				new Point2D.Float(CAP_W * 0.35f, ch * 0.32f), Math.max(1f, ch * 0.75f),
				new float[]{0f, 1f},
				new Color[]{mix(hi, Color.WHITE, 0.35f), mix(lo, Color.BLACK, 0.25f)}));
			g.fillOval(0, 0, CAP_W, ch);
			g.setColor(CAP_EDGE);
			g.drawOval(0, 0, CAP_W - 1, ch - 1);
			g.dispose();
			ImageIO.write(cap, "png", new File(dir, "roller-" + k + "-cap.png"));
		}
		System.out.println("roller slices written for " + TINTS.length + " tints");
	}

	private RollerArt() {
	}
}
