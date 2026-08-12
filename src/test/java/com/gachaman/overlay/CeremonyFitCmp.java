package com.gachaman.overlay;

import com.gachaman.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/**
 * Checks the frames that had to be stored below their authored size to fit the
 * Plugin Hub's 1 MiB decoded-image limit.
 *
 * <p>Two different questions, and only one of them is about fidelity:
 *
 * <ol>
 *   <li><b>Geometry.</b> A downscaled frame is stretched back to its authored
 *       size at draw time. If the index's authored width/height were wrong the
 *       glow would land shifted or shrunk — a visible break, not a soft one.
 *       The painted bounding box must match the procedural one.
 *   <li><b>Fidelity.</b> Resolution really was traded away here, so the delta
 *       is NOT expected to be zero. What matters is that it stays low and sits
 *       in the soft halo rather than on the chest itself.
 * </ol>
 */
public final class CeremonyFitCmp {
	private static final CeremonyPlayer PLAYER = new CeremonyPlayer(new com.google.gson.Gson());

	/** The frames the generator reported as downscaled. */
	private static final Object[][] CASES = {
		{Tuning.Chest.GILDED, 72}, {Tuning.Chest.ORNATE, 36}, {Tuning.Chest.ORNATE, 37},
		{Tuning.Chest.ORNATE, 38}, {Tuning.Chest.ORNATE, 40}, {Tuning.Chest.ORNATE, 64},
		{Tuning.Chest.ORNATE, 65}, {Tuning.Chest.ORNATE, 66}, {Tuning.Chest.ORNATE, 68},
	};

	private static final int W = 300;
	private static final int H = 225;
	private static final int CW = 1100;
	private static final int CH = 1000;

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		System.out.printf("%-8s %5s  %-22s %-22s %7s %7s %7s%n",
			"tier", "frame", "procedural bounds", "sprite bounds", "meanD", "maxD", ">8/255");
		for (Object[] c : CASES) {
			Tuning.Chest tier = (Tuning.Chest) c[0];
			int f = (Integer) c[1];
			long el = f * 1000L / 20L;

			BufferedImage proc = canvas();
			Graphics2D g = proc.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			ChestPainter.draw(g, CW / 2, CH / 2, W, H, CeremonyArt.lidOpen(tier, el), tier,
				CeremonyArt.trim(tier), 0f, CeremonyArt.leak(tier, el), el);
			if (tier == Tuning.Chest.GILDED) {
				ChestPainter.drawPadlock(g, CW / 2, CH / 2 + H / 8, W, el, ChestStrain.giveMs(tier));
			}
			else if (tier == Tuning.Chest.ORNATE) {
				ChestPainter.drawChains(g, CW / 2, CH / 2, W, H, el, 1200, 1400);
			}
			g.dispose();

			BufferedImage spr = canvas();
			g = spr.createGraphics();
			PLAYER.draw(g, CW / 2, CH / 2, W, H, tier, f, 1f);
			g.dispose();

			int[] pb = bounds(proc);
			int[] sb = bounds(spr);
			long sum = 0;
			long n = 0;
			int max = 0;
			long loud = 0;
			for (int y = 0; y < CH; y++) {
				for (int x = 0; x < CW; x++) {
					int p = proc.getRGB(x, y);
					int s = spr.getRGB(x, y);
					if (p == 0 && s == 0) {
						continue;
					}
					int d = 0;
					for (int sh = 0; sh <= 24; sh += 8) {
						d = Math.max(d, Math.abs(((p >> sh) & 0xFF) - ((s >> sh) & 0xFF)));
					}
					sum += d;
					n++;
					max = Math.max(max, d);
					if (d > 8) {
						loud++;
					}
				}
			}
			System.out.printf("%-8s %5d  %-22s %-22s %7.2f %7d %6.2f%%%n",
				tier, f, box(pb), box(sb), n == 0 ? 0 : sum / (double) n, max,
				n == 0 ? 0 : 100.0 * loud / n);
			ImageIO.write(proc, "png", new File(dir, tier + "_" + f + "_proc.png"));
			ImageIO.write(spr, "png", new File(dir, tier + "_" + f + "_spr.png"));
		}
	}

	private static BufferedImage canvas() {
		return new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
	}

	private static String box(int[] b) {
		return b == null ? "(empty)"
			: String.format("%d,%d %dx%d", b[0], b[1], b[2] - b[0], b[3] - b[1]);
	}

	private static int[] bounds(BufferedImage img) {
		int minX = img.getWidth();
		int minY = img.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getRGB(x, y) >>> 24) > 2) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
	}

	private CeremonyFitCmp() {
	}
}
