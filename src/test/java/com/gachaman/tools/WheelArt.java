package com.gachaman.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Authors the static chrome of the style roulette. Test scope: never shipped,
 * never counted, and it keeps the procedural source so the art can be redrawn.
 *
 * <p>Only the pieces that do NOT rotate with the wheel and do NOT depend on the
 * result live here. The wedges, separators, labels, pulse and glow stay in
 * RevealOverlay because they turn, tint or animate.
 */
public final class WheelArt {
	public static final int R = 190;
	public static final int FACE = 440;
	public static final int FACE_C = 220;
	private static final Color GOLD = new Color(230, 190, 80);
	private static final Color RIM_HI = new Color(214, 218, 228);
	private static final Color RIM_LO = new Color(96, 100, 112);

	private static Graphics2D aa(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		int cx = FACE_C;
		int cy = FACE_C;

		// 1. drop shadow under the whole wheel (drawn BEFORE the wedges)
		BufferedImage shadow = new BufferedImage(FACE, FACE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = aa(shadow);
		g.setColor(new Color(0, 0, 0, 80));
		g.fillOval(cx - R - 10, cy - R - 2, (R + 10) * 2, (R + 10) * 2);
		g.dispose();
		ImageIO.write(shadow, "png", new File(dir, "wheel-shadow.png"));

		// 2. sheen + metallic rim + bolts (drawn AFTER the separators)
		BufferedImage rim = new BufferedImage(FACE, FACE, BufferedImage.TYPE_INT_ARGB);
		g = aa(rim);
		Graphics2D gs = (Graphics2D) g.create();
		gs.setClip(new Ellipse2D.Float(cx - R, cy - R, R * 2, R * 2));
		for (int i = 4; i >= 1; i--) {
			float al = 0.030f * (5 - i);
			gs.setColor(new Color(255, 255, 255, (int) (al * 255)));
			int sw = (int) (R * (0.6 + i * 0.22));
			gs.fillOval(cx - (int) (R * 0.75), cy - (int) (R * 0.85), sw, (int) (sw * 0.8));
		}
		gs.dispose();
		g.setPaint(new GradientPaint(cx - R, cy - R, RIM_HI, cx + R, cy + R, RIM_LO));
		g.setStroke(new BasicStroke(11f));
		g.drawOval(cx - R - 5, cy - R - 5, (R + 5) * 2, (R + 5) * 2);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2f));
		g.drawOval(cx - R - 11, cy - R - 11, (R + 11) * 2, (R + 11) * 2);
		g.drawOval(cx - R + 1, cy - R + 1, (R - 1) * 2, (R - 1) * 2);
		for (int b = 0; b < 12; b++) {
			double ba = Math.toRadians(b * 30 + 15);
			int bx = cx + (int) (Math.cos(ba) * (R + 5));
			int by = cy - (int) (Math.sin(ba) * (R + 5));
			g.setColor(RIM_LO);
			g.fillOval(bx - 3, by - 3, 6, 6);
			g.setColor(RIM_HI);
			g.fillOval(bx - 2, by - 3, 3, 3);
		}
		g.dispose();
		ImageIO.write(rim, "png", new File(dir, "wheel-rim.png"));

		// 3. hub sigil - fixed 44px, independent of the wheel radius
		BufferedImage hub = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		g = aa(hub);
		int hx = 32;
		int hy = 30;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillOval(hx - 24, hy - 21, 48, 48);
		g.setPaint(new GradientPaint(hx - 22, hy - 22, new Color(52, 42, 22),
			hx + 22, hy + 22, new Color(24, 18, 8)));
		g.fillOval(hx - 22, hy - 22, 44, 44);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2.2f));
		g.drawOval(hx - 22, hy - 22, 44, 44);
		g.setColor(new Color(230, 190, 80, 204));
		g.setStroke(new BasicStroke(1.6f));
		int r9 = 9;
		g.drawOval(hx - r9, hy - r9, r9 * 2, r9 * 2);
		int d = r9 + r9 / 2;
		g.drawLine(hx, hy - d, hx + d, hy);
		g.drawLine(hx + d, hy, hx, hy + d);
		g.drawLine(hx, hy + d, hx - d, hy);
		g.drawLine(hx - d, hy, hx, hy - d);
		g.dispose();
		ImageIO.write(hub, "png", new File(dir, "wheel-hub.png"));

		// 4. pointer flap + pivot cap - a fixed 30px shape whatever the radius
		BufferedImage ptr = new BufferedImage(40, 48, BufferedImage.TYPE_INT_ARGB);
		g = aa(ptr);
		int px = 20;
		int py = 12;
		Path2D.Double flap = new Path2D.Double();
		flap.moveTo(px - 11, py - 4);
		flap.lineTo(px + 11, py - 4);
		flap.lineTo(px + 3, py + 18);
		flap.lineTo(px, py + 26);
		flap.lineTo(px - 3, py + 18);
		flap.closePath();
		g.setPaint(new GradientPaint(px - 10, py, new Color(250, 216, 110),
			px + 10, py + 26, new Color(150, 110, 30)));
		g.fill(flap);
		g.setColor(new Color(60, 44, 10));
		g.setStroke(new BasicStroke(1.6f));
		g.draw(flap);
		g.dispose();
		ImageIO.write(ptr, "png", new File(dir, "wheel-pointer.png"));
		System.out.println("wheel sprites written");
	}

	private WheelArt() {
	}

	/** The chrome as RevealOverlay drew it, for the comparison harness. */
	public static void drawChrome(Graphics2D g, int cx, int cy, int radius) {
		g.setColor(new Color(0, 0, 0, 80));
		g.fillOval(cx - radius - 10, cy - radius - 2, (radius + 10) * 2, (radius + 10) * 2);
		Graphics2D gs = (Graphics2D) g.create();
		gs.setClip(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
		for (int i = 4; i >= 1; i--) {
			float al = 0.030f * (5 - i);
			gs.setColor(new Color(255, 255, 255, (int) (al * 255)));
			int sw = (int) (radius * (0.6 + i * 0.22));
			gs.fillOval(cx - (int) (radius * 0.75), cy - (int) (radius * 0.85), sw, (int) (sw * 0.8));
		}
		gs.dispose();
		g.setPaint(new GradientPaint(cx - radius, cy - radius, RIM_HI, cx + radius, cy + radius, RIM_LO));
		g.setStroke(new BasicStroke(11f));
		g.drawOval(cx - radius - 5, cy - radius - 5, (radius + 5) * 2, (radius + 5) * 2);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2f));
		g.drawOval(cx - radius - 11, cy - radius - 11, (radius + 11) * 2, (radius + 11) * 2);
		g.drawOval(cx - radius + 1, cy - radius + 1, (radius - 1) * 2, (radius - 1) * 2);
		for (int b = 0; b < 12; b++) {
			double ba = Math.toRadians(b * 30 + 15);
			int bx = cx + (int) (Math.cos(ba) * (radius + 5));
			int by = cy - (int) (Math.sin(ba) * (radius + 5));
			g.setColor(RIM_LO);
			g.fillOval(bx - 3, by - 3, 6, 6);
			g.setColor(RIM_HI);
			g.fillOval(bx - 2, by - 3, 3, 3);
		}
		int hx = cx;
		int hy = cy;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillOval(hx - 24, hy - 21, 48, 48);
		g.setPaint(new GradientPaint(hx - 22, hy - 22, new Color(52, 42, 22),
			hx + 22, hy + 22, new Color(24, 18, 8)));
		g.fillOval(hx - 22, hy - 22, 44, 44);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2.2f));
		g.drawOval(hx - 22, hy - 22, 44, 44);
		g.setColor(new Color(230, 190, 80, 204));
		g.setStroke(new BasicStroke(1.6f));
		int r9 = 9;
		g.drawOval(hx - r9, hy - r9, r9 * 2, r9 * 2);
		int d = r9 + r9 / 2;
		g.drawLine(hx, hy - d, hx + d, hy);
		g.drawLine(hx + d, hy, hx, hy + d);
		g.drawLine(hx, hy + d, hx - d, hy);
		g.drawLine(hx - d, hy, hx, hy - d);
		int px = cx;
		int py = cy - radius - 16;
		Path2D.Double flap = new Path2D.Double();
		flap.moveTo(px - 11, py - 4);
		flap.lineTo(px + 11, py - 4);
		flap.lineTo(px + 3, py + 18);
		flap.lineTo(px, py + 26);
		flap.lineTo(px - 3, py + 18);
		flap.closePath();
		g.setPaint(new GradientPaint(px - 10, py, new Color(250, 216, 110),
			px + 10, py + 26, new Color(150, 110, 30)));
		g.fill(flap);
		g.setColor(new Color(60, 44, 10));
		g.setStroke(new BasicStroke(1.6f));
		g.draw(flap);
	}
}
