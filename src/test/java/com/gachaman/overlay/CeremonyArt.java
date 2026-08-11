package com.gachaman.overlay;

import com.gachaman.Tuning;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import javax.imageio.ImageIO;

/**
 * Pre-renders the WHOLE chest-opening ceremony to a frame sequence per tier:
 * body, lid, chains, padlock, strain, seam leak, glow, motes and all.
 *
 * <p>It can be one sequence because the ceremony is a pure function of
 * (tier, elapsed): the chest shake and the camera shake are applied by the
 * caller as translations, innerGlow is 0 throughout the intro, and both lidOpen
 * and leak are derived from el. Every frame is cropped to its own alpha bounds
 * and its offset recorded, so the empty space around a whipping chain or a
 * blasted lid costs nothing.
 *
 * <p>Test scope: never shipped, never counted. This is the authoring source.
 */
public final class CeremonyArt {
	private static final int W = 300;
	private static final int H = 225;
	/** Generous working canvas; every frame is cropped out of it. */
	private static final int CW = 1100;
	private static final int CH = 1000;
	static final int FPS = 20;

	private static double clamp01(double v) {
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static double smoothstep(double t) {
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	/** Last millisecond worth rendering for a tier. */
	static int durationMs(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY: return 1600;
			case BATTERED: return 2150;
			case GILDED: return 4050;
			default: return 7100;
		}
	}

	static double lidOpen(Tuning.Chest tier, long el) {
		switch (tier) {
			case RUSTY: return el >= 900 ? smoothstep(clamp01((el - 900) / 350.0)) : 0;
			case BATTERED: return el >= 1400 ? smoothstep(clamp01((el - 1400) / 400.0)) : 0;
			case GILDED: return el >= 3200 ? smoothstep(clamp01((el - 3200) / 500.0)) : 0;
			default: return el >= 6400 ? smoothstep(clamp01((el - 6400) / 350.0)) * 1.35 : 0;
		}
	}

	static float leak(Tuning.Chest tier, long el) {
		if (tier != Tuning.Chest.ORNATE) {
			return 0f;
		}
		return el >= 4000 && el < 6400 ? (float) clamp01((el - 4000) / 2400.0) : 0f;
	}

	static Color trim(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY: return new Color(122, 82, 46);
			case BATTERED: return new Color(139, 98, 46);
			case GILDED: return new Color(198, 202, 212);
			default: return new Color(230, 190, 80);
		}
	}

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		StringBuilder idx = new StringBuilder("{\n  \"fps\": ").append(FPS).append(",\n  \"tiers\": {\n");
		long totalBytes = 0;
		long peakPixels = 0;
		for (Tuning.Chest tier : Tuning.Chest.values()) {
			String name = tier.name().toLowerCase();
			int step = 1000 / FPS;
			int n = 0;
			StringBuilder frames = new StringBuilder();
			long tierPixels = 0;
			for (long el = 0; el <= durationMs(tier); el += step) {
				BufferedImage img = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = img.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int cx = CW / 2;
				int cy = CH / 2;
				ChestPainter.draw(g, cx, cy, W, H, lidOpen(tier, el), tier, trim(tier), 0f,
					leak(tier, el), el);
				if (tier == Tuning.Chest.GILDED) {
					ChestPainter.drawPadlock(g, cx, cy + H / 8, W, el, ChestStrain.giveMs(tier));
				}
				else if (tier == Tuning.Chest.ORNATE) {
					ChestPainter.drawChains(g, cx, cy, W, H, el, 1200, 1400);
				}
				g.dispose();

				int[] bb = bounds(img);
				BufferedImage crop = img.getSubimage(bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]);
				File f = new File(dir, String.format("chest-%s-%03d.png", name, n));
				ImageIO.write(crop, "png", f);
				totalBytes += f.length();
				tierPixels += (long) crop.getWidth() * crop.getHeight();
				frames.append(n == 0 ? "" : ", ")
					.append("[").append(bb[0] - cx).append(",").append(bb[1] - cy).append("]");
				n++;
			}
			peakPixels = Math.max(peakPixels, tierPixels);
			idx.append("    \"").append(name).append("\": [").append(frames).append("]")
				.append(tier == Tuning.Chest.ORNATE ? "\n" : ",\n");
			System.out.printf("%-9s %3d frames%n", name, n);
		}
		idx.append("  }\n}\n");
		try (FileWriter w = new FileWriter(new File(dir, "chest-ceremony.json"))) {
			w.write(idx.toString());
		}
		System.out.printf("on disk: %.2f MB%n", totalBytes / 1048576.0);
		System.out.printf("largest tier decoded (ARGB): %.1f MB%n", peakPixels * 4 / 1048576.0);
	}

	/** Tight alpha bounds, or the whole canvas if the frame is empty. */
	private static int[] bounds(BufferedImage img) {
		int minX = img.getWidth();
		int minY = img.getHeight();
		int maxX = 0;
		int maxY = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getRGB(x, y) >>> 24) != 0) {
					if (x < minX) { minX = x; }
					if (y < minY) { minY = y; }
					if (x > maxX) { maxX = x; }
					if (y > maxY) { maxY = y; }
				}
			}
		}
		if (minX > maxX) {
			return new int[]{0, 0, 1, 1};
		}
		return new int[]{minX, minY, maxX + 1, maxY + 1};
	}

	private CeremonyArt() {
	}
}
