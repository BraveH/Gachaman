package com.gachaman.overlay;

import com.gachaman.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

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
	/**
	 * Decoded-size ceiling per frame, in bytes.
	 *
	 * <p>The Plugin Hub rejects any image whose DECODED size — width * height * 4,
	 * not the compressed file — passes 1 MiB. On disk these frames are 60-70 KB,
	 * so nothing about the file sizes hints at the problem. Four ORNATE frames
	 * were over the line and a fifth sat 3% under it, which is why the cap is set
	 * below the limit rather than at it.
	 *
	 * <p>What makes those frames large is not detail but REACH: the chain and the
	 * broken padlock fall away from the chest, so the alpha bounding box stretches
	 * to cover a thin diagonal of chain and one small padlock across a mostly
	 * empty rectangle. The cheap-looking alternative — clamping how far the debris
	 * may travel — would visibly cut the fall short, and packing the pieces into
	 * separate sub-images would cost player code the token budget cannot spare.
	 *
	 * <p>So resolution is what gets traded, worst case 0.82 linear on ORNATE 38.
	 * That frame's chain links and padlock were compared against the procedural
	 * source at 1:1 (CeremonyFitCmp) and are indistinguishable; the measurable
	 * delta sits on 1px trim edges and reaches the eye as nothing.
	 */
	private static final long MAX_DECODED = 900_000L;

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
		long peakDecoded = 0;
		int shrunk = 0;
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
				// authored size: what the frame is DRAWN at, whatever it is stored at
				int aw = crop.getWidth();
				int ah = crop.getHeight();
				BufferedImage out = crop;
				if ((long) aw * ah * 4 > MAX_DECODED) {
					double k = Math.sqrt(MAX_DECODED / (double) ((long) aw * ah * 4));
					int sw = Math.max(1, (int) Math.floor(aw * k));
					int sh = Math.max(1, (int) Math.floor(ah * k));
					out = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
					Graphics2D sg = out.createGraphics();
					sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					sg.setRenderingHint(RenderingHints.KEY_RENDERING,
						RenderingHints.VALUE_RENDER_QUALITY);
					sg.drawImage(crop, 0, 0, sw, sh, null);
					sg.dispose();
					shrunk++;
				}
				File f = new File(dir, String.format("chest-%s-%03d.png", name, n));
				ImageIO.write(out, "png", f);
				totalBytes += f.length();
				tierPixels += (long) out.getWidth() * out.getHeight();
				peakDecoded = Math.max(peakDecoded, (long) out.getWidth() * out.getHeight() * 4);
				frames.append(n == 0 ? "" : ", ")
					.append("[").append(bb[0] - cx).append(",").append(bb[1] - cy)
					.append(",").append(aw).append(",").append(ah).append("]");
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
		System.out.printf("largest SINGLE frame decoded: %,d bytes (Plugin Hub limit 1,048,576)%n",
			peakDecoded);
		System.out.printf("frames downscaled to fit: %d%n", shrunk);
		if (peakDecoded > 1048576L) {
			throw new IllegalStateException("a frame is still over the Plugin Hub image limit");
		}
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
