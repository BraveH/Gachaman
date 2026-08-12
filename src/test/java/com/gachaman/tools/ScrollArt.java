package com.gachaman.tools;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/**
 * Authors the tier-INDEPENDENT parchment texture: the cylinder bow across the
 * sheet and the laid-paper fibre. Both are pure fractions of the sheet's width
 * and height, so one image stretches to any scroll size exactly.
 *
 * <p>The base gradient and the hairline border stay procedural in ScrollPainter
 * because they carry the difficulty's colour, and the aged edges and vignette
 * stay because they clamp to a pixel count rather than a fraction.
 */
public final class ScrollArt {
	public static final int W = 240;
	public static final int H = 360;
	private static final Color BOW_SHADE = new Color(92, 68, 36, 70);
	private static final Color BOW_MID = new Color(120, 96, 60, 22);
	private static final Color BOW_LIGHT = new Color(255, 248, 226, 34);
	private static final Color FIBRE = new Color(120, 96, 60, 11);
	private static final float[][] FIBRE_AT = {
		{0.11f, 0.06f, 0.62f}, {0.26f, 0.31f, 0.55f}, {0.38f, 0.04f, 0.34f},
		{0.57f, 0.22f, 0.71f}, {0.69f, 0.09f, 0.41f}, {0.84f, 0.36f, 0.52f},
	};

	public static void main(String[] a) throws Exception {
		new File(a[0]).mkdirs();
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(W, 0),
			new float[]{0f, 0.18f, 0.5f, 0.82f, 1f},
			new Color[]{BOW_SHADE, BOW_MID, BOW_LIGHT, BOW_MID, BOW_SHADE}));
		g.fillRect(0, 0, W, H);
		g.setColor(FIBRE);
		for (float[] f : FIBRE_AT) {
			int fy = (int) (H * f[0]);
			int fx = (int) (W * f[1]);
			int fw = (int) (W * f[2]);
			g.drawLine(fx, fy, Math.min(W - 3, fx + fw), fy);
		}
		g.dispose();
		ImageIO.write(img, "png", new File(a[0] + "/parchment-texture.png"));
		System.out.println("parchment texture written " + W + "x" + H);
	}

	private ScrollArt() {
	}
}
