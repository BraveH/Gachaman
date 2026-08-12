package com.gachaman.tools;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/** Procedural bow+fibre vs the stretched sprite, at several sheet sizes. */
public final class ScrollCmp {
	private static final Color BOW_SHADE = new Color(92, 68, 36, 70);
	private static final Color BOW_MID = new Color(120, 96, 60, 22);
	private static final Color BOW_LIGHT = new Color(255, 248, 226, 34);
	private static final Color FIBRE = new Color(120, 96, 60, 11);
	private static final float[][] FIBRE_AT = {
		{0.11f, 0.06f, 0.62f}, {0.26f, 0.31f, 0.55f}, {0.38f, 0.04f, 0.34f},
		{0.57f, 0.22f, 0.71f}, {0.69f, 0.09f, 0.41f}, {0.84f, 0.36f, 0.52f},
	};

	public static void main(String[] a) throws Exception {
		int[][] sizes = {{240, 360}, {180, 300}, {300, 420}};
		for (int[] s : sizes) {
			int w = s[0];
			int h = s[1];
			BufferedImage p = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = p.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(w, 0),
				new float[]{0f, 0.18f, 0.5f, 0.82f, 1f},
				new Color[]{BOW_SHADE, BOW_MID, BOW_LIGHT, BOW_MID, BOW_SHADE}));
			g.fillRect(0, 0, w, h);
			g.setColor(FIBRE);
			for (float[] f : FIBRE_AT) {
				int fy = (int) (h * f[0]);
				int fx = (int) (w * f[1]);
				int fw = (int) (w * f[2]);
				g.drawLine(fx, fy, Math.min(w - 3, fx + fw), fy);
			}
			g.dispose();
			ImageIO.write(p, "png", new File(a[0] + "/parch_proc_" + w + ".png"));

			BufferedImage q = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			g = q.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			try (InputStream in = ScrollCmp.class.getResourceAsStream(
				"/com/gachaman/art/parchment-texture.png")) {
				Image tex = ImageIO.read(in);
				g.drawImage(tex, 0, 0, w, h, null);
			}
			g.dispose();
			ImageIO.write(q, "png", new File(a[0] + "/parch_spr_" + w + ".png"));
		}
		System.out.println("rendered 3 sizes");
	}

	private ScrollCmp() {
	}
}
