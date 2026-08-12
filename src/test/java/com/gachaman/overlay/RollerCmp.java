package com.gachaman.overlay;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/** Procedural roller vs the 9-sliced one, across tints and scroll widths. */
public final class RollerCmp {
	private static final Color WOOD_LIGHT = new Color(168, 128, 76);
	private static final Color WOOD_DARK = new Color(70, 48, 24);
	private static final Color CAP_EDGE = new Color(34, 24, 12, 200);
	private static final Color GRAIN = new Color(52, 34, 14, 55);

	private static Color mix(Color a, Color b, float t) {
		return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		int n = 0;
		for (Color tier : RollerArt.TINTS) {
			for (int width : new int[]{200, 260, 320}) {
				Rectangle r = new Rectangle(20, 20, width, 120);
				int cy = 40;
				BufferedImage proc = new BufferedImage(width + 60, 90, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = proc.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				old(g, r, cy, mix(WOOD_LIGHT, tier, 0.45f), mix(WOOD_DARK, tier, 0.30f), 0.3);
				g.dispose();
				ImageIO.write(proc, "png", new File(dir, RollerArt.key(tier) + "_" + width + "_proc.png"));

				BufferedImage spr = new BufferedImage(width + 60, 90, BufferedImage.TYPE_INT_ARGB);
				g = spr.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				ScrollPainter.drawRoller(g, r, cy, tier, 0.3);
				g.dispose();
				ImageIO.write(spr, "png", new File(dir, RollerArt.key(tier) + "_" + width + "_spr.png"));
				n++;
			}
		}
		System.out.println("compared " + n + " rollers");
	}

	/** The roller exactly as ScrollPainter drew it before the 9-slice. */
	private static void old(Graphics2D g, Rectangle r, int cy, Color hi, Color lo, double spin) {
		int H = 18;
		int CAP_W = 7;
		int y = cy - H / 2;
		int bodyX = r.x + CAP_W - 2;
		int bodyW = r.width - (CAP_W - 2) * 2;
		java.awt.Shape clip = g.getClip();
		RoundRectangle2D body = new RoundRectangle2D.Float(bodyX, y, bodyW, H, H, H);
		g.setPaint(new LinearGradientPaint(new Point2D.Float(0, y), new Point2D.Float(0, y + H),
			new float[]{0f, 0.28f, 0.5f, 0.82f, 1f},
			new Color[]{mix(lo, Color.BLACK, 0.35f), mix(hi, Color.WHITE, 0.55f), hi,
				mix(lo, Color.BLACK, 0.15f), mix(lo, hi, 0.45f)}));
		g.fill(body);
		g.clip(body);
		g.setColor(GRAIN);
		for (int k = 0; k < 3; k++) {
			double phase = (((spin + k / 3.0) % 1.0) + 1.0) % 1.0;
			int gy = y + 2 + (int) Math.round(phase * (H - 4));
			g.drawLine(bodyX + 3, gy, bodyX + bodyW - 3, gy);
		}
		g.setClip(clip);
		cap(g, r.x, y - 2, hi, lo);
		cap(g, r.x + r.width - CAP_W, y - 2, hi, lo);
	}

	private static void cap(Graphics2D g, int x, int y, Color hi, Color lo) {
		int h = 22;
		g.setPaint(new java.awt.RadialGradientPaint(new Point2D.Float(x + 7 * 0.35f, y + h * 0.32f),
			Math.max(1f, h * 0.75f), new float[]{0f, 1f},
			new Color[]{mix(hi, Color.WHITE, 0.35f), mix(lo, Color.BLACK, 0.25f)}));
		g.fillOval(x, y, 7, h);
		g.setColor(CAP_EDGE);
		g.drawOval(x, y, 6, h - 1);
	}

	private RollerCmp() {
	}
}
