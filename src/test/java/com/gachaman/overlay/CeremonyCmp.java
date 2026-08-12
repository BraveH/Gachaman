package com.gachaman.overlay;

import com.gachaman.Tuning;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Procedural ceremony vs the pre-rendered player, at exact frame times. */
public final class CeremonyCmp {
	/** Injected in the plugin; a comparison harness has no injector. */
	private static final CeremonyPlayer PLAYER = new CeremonyPlayer(new com.google.gson.Gson());

	public static void main(String[] a) throws Exception {
		File dir = new File(a[0]);
		dir.mkdirs();
		int W = 300;
		int H = 225;
		int CW = 900;
		int CH = 800;
		int n = 0;
		for (Tuning.Chest tier : Tuning.Chest.values()) {
			int frames = PLAYER.frames(tier);
			for (int f : new int[]{0, frames / 4, frames / 2, frames * 3 / 4, frames - 1}) {
				long el = f * 1000L / 20L;
				BufferedImage proc = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
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
				ImageIO.write(proc, "png", new File(dir, tier.name() + "_" + f + "_proc.png"));

				BufferedImage spr = new BufferedImage(CW, CH, BufferedImage.TYPE_INT_ARGB);
				g = spr.createGraphics();
				PLAYER.draw(g, CW / 2, CH / 2, W, H, tier, f, 1f);
				g.dispose();
				ImageIO.write(spr, "png", new File(dir, tier.name() + "_" + f + "_spr.png"));
				n++;
			}
		}
		System.out.println("compared " + n + " frame pairs");
	}

	private CeremonyCmp() {
	}
}
