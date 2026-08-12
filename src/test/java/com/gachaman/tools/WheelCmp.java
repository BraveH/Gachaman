package com.gachaman.tools;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/** Renders the wheel chrome procedurally and from sprites, to prove the anchors. */
public final class WheelCmp {
	public static void main(String[] a) throws Exception {
		int radius = Integer.parseInt(a[1]);
		int cw = 560;
		int ch = 620;
		int cx = cw / 2;
		int cy = ch / 2 + 10;

		BufferedImage proc = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = proc.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		WheelArt.drawChrome(g, cx, cy, radius);
		g.dispose();
		ImageIO.write(proc, "png", new File(a[0] + "/wheel_proc.png"));

		BufferedImage spr = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
		g = spr.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double s = radius / (double) WheelArt.R;
		blit(g, "wheel-shadow", cx, cy, s, 220, 220);
		blit(g, "wheel-rim", cx, cy, s, 220, 220);
		blit(g, "wheel-hub", cx, cy, 1.0, 32, 30);
		int pivotY = cy - radius - 16;
		blit(g, "wheel-pointer", cx, pivotY, 1.0, 20, 12);
		g.dispose();
		ImageIO.write(spr, "png", new File(a[0] + "/wheel_spr.png"));
		System.out.println("rendered at radius " + radius);
	}

	private static void blit(Graphics2D g, String n, int cx, int cy, double s, int ax, int ay)
		throws Exception {
		try (InputStream in = WheelCmp.class.getResourceAsStream("/com/gachaman/art/" + n + ".png")) {
			Image im = ImageIO.read(in);
			g.drawImage(im, cx - (int) Math.round(ax * s), cy - (int) Math.round(ay * s),
				(int) Math.round(im.getWidth(null) * s), (int) Math.round(im.getHeight(null) * s), null);
		}
	}

	private WheelCmp() {
	}
}
