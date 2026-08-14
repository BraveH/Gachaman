package com.gachaman.tools;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

/**
 * Authors the Shop tab's chest-tile icons as PNGs — one per chest tier, in a
 * lit and a dimmed pass.
 *
 * <p>ShopTab.ChestTile used to paint this icon from scratch on every repaint.
 * It is a pure function of two things, the tier and whether the tile is
 * affordable, and neither of them is continuous: there are exactly eight
 * pictures it can ever produce. Re-deriving one of eight constants on every
 * paint cost the shipped plugin the drawing code, the two colour tables it read
 * and the desaturation helper it called — so the drawing moved here, into test
 * scope, where it stays reviewable and regenerable while the plugin ships eight
 * small PNGs instead. This is the same trade IconArt, ChestPainter, RollerArt
 * and CeremonyArt already make.
 *
 * <p><b>The drawing below is byte-for-byte the calls ShopTab made</b>, in the
 * same order, on a Graphics2D with the same antialiasing hint and the same
 * 1.5f stroke. That is what makes the swap invisible: antialiased shapes
 * composited SRC_OVER out of a transparent buffer land on the tile background
 * as {@code cov * src + (1 - cov) * dst}, which is what drawing them straight
 * onto that background already did — Porter-Duff OVER is associative, so
 * layering the icon's own overlaps first changes nothing. ShopIconBakeTest
 * pins that empirically rather than on my word for it.
 *
 * <p><b>One honest caveat, at UI scales above 1.</b> Everything above is about
 * an unscaled panel, where the measured difference is at most 2/255 on the
 * antialiased edges. On a scaled client the side panel hands ShopTab a
 * transformed Graphics2D, and a 44x42 raster is RESAMPLED there where vector
 * strokes would have been re-rendered at the higher resolution — so the chest
 * softens slightly while the tile's text, which still goes through drawString,
 * does not. That is a genuine change, not a rounding one. It is also the trade
 * this repo already ships for panel-icon.png, the link icons, the wheel chrome
 * and every ceremony frame, which is why it is taken here too rather than
 * quietly assumed away.
 *
 * <p>Deliberately depends on NOTHING but the JDK — not even Tuning, whose enum
 * order {@link #TIERS} mirrors (ShopIconBakeTest pins the two against each
 * other, so drift fails the build rather than mislabelling a chest). That keeps
 * this runnable straight from a JDK with no classpath at all:
 *
 * <pre>
 * javac -d /tmp/shopart src/test/java/com/gachaman/tools/ShopArt.java
 * java -cp /tmp/shopart com.gachaman.tools.ShopArt
 * </pre>
 *
 * <p>The output is committed; this is an authoring tool, not a build step.
 */
public final class ShopArt {
	private static final String RES = "src/main/resources/com/gachaman/ui/";

	/**
	 * Tier keys in Tuning.Chest declaration order. Lower case because they are
	 * also the file names, and because {@code name().toLowerCase()} is what
	 * ShopTab does to find them again.
	 */
	public static final String[] TIERS = {"rusty", "battered", "gilded", "ornate"};

	/** Chest body fill per tier. Was ShopTab.bodyColor(), which is now gone. */
	public static final Color[] BODY = {
		new Color(88, 60, 42),
		new Color(101, 84, 63),
		new Color(133, 105, 41),
		new Color(90, 56, 128)};

	/**
	 * Chest trim per tier. ShopTab keeps its own copy of this table because the
	 * tile BORDER still needs the lit trim colour at paint time; only the icon's
	 * use of it moved here.
	 */
	public static final Color[] TRIM = {
		new Color(154, 96, 52),
		new Color(146, 126, 96),
		new Color(230, 190, 80),
		new Color(255, 196, 60)};

	// The icon's box inside the tile, straight out of the old paintComponent.
	public static final int IX = 8;
	public static final int IY = 12;
	public static final int IW = 40;
	public static final int IH = 38;

	/**
	 * Where the sprite's top-left sits in tile coordinates, and how big it is.
	 *
	 * <p>Two pixels of margin on every side of the drawn extent: the 1.5f stroke
	 * hangs 0.75px outside the geometry and the antialiaser feathers about a
	 * pixel past that, so a canvas cropped to the nominal 40x38 box would shave
	 * the outline. The offsets are whole pixels on purpose — an integer
	 * translation reproduces the antialiasing coverage exactly, where a
	 * fractional one would resample it.
	 */
	public static final int ORIGIN_X = IX - 2;
	public static final int ORIGIN_Y = IY - 2;
	public static final int WIDTH = IW + 4;
	public static final int HEIGHT = IH + 4;

	private ShopArt() {
	}

	public static void main(String[] args) throws Exception {
		File dir = new File(args.length > 0 ? args[0] : RES);
		dir.mkdirs();
		for (int tier = 0; tier < TIERS.length; tier++) {
			for (int lit = 0; lit < 2; lit++) {
				ImageIO.write(chestIcon(tier, lit == 1), "png", new File(dir, name(tier, lit == 1)));
			}
		}
		System.out.println("wrote " + TIERS.length * 2 + " chest tile icons to " + dir);
	}

	/** The resource name ShopTab.chestIcon() rebuilds at load time. */
	public static String name(int tier, boolean lit) {
		return "chest-tile-" + TIERS[tier] + (lit ? "-lit" : "-dim") + ".png";
	}

	/** One baked icon, transparent everywhere the chest is not. */
	public static BufferedImage chestIcon(int tier, boolean lit) {
		BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = img.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		paint(g2, tier, lit, IX - ORIGIN_X, IY - ORIGIN_Y);
		g2.dispose();
		return img;
	}

	/**
	 * The chest itself, at (ix, iy) in whatever space the caller set up. Shared
	 * with the comparison test, which draws it at the tile coordinates the old
	 * ShopTab used so the two renderings can be diffed pixel for pixel.
	 *
	 * <p>A dimmed tile desaturates BOTH colours before drawing, which is exactly
	 * what ShopTab did — its dimming ran on {@code !affordable || remaining == 0},
	 * and a retired tile is never affordable (ChestTile's constructor ands the
	 * two together), so the second half of that test could never decide it.
	 */
	public static void paint(Graphics2D g2, int tier, boolean lit, int ix, int iy) {
		Color body = lit ? BODY[tier] : desaturate(BODY[tier]);
		Color trim = lit ? TRIM[tier] : desaturate(TRIM[tier]);
		int iw = IW;
		int ih = IH;
		g2.setColor(body);
		g2.fillRoundRect(ix, iy + ih / 3, iw, ih * 2 / 3, 6, 6);
		g2.fillArc(ix, iy, iw, ih * 2 / 3, 0, 180);
		g2.setColor(trim);
		g2.setStroke(new BasicStroke(1.5f));
		g2.drawRoundRect(ix, iy + ih / 3, iw, ih * 2 / 3, 6, 6);
		g2.drawArc(ix, iy, iw, ih * 2 / 3, 0, 180);
		g2.drawLine(ix, iy + ih / 3, ix + iw, iy + ih / 3);
		// clasp + keyhole
		g2.fillRect(ix + iw / 2 - 3, iy + ih / 3 - 3, 6, 9);
		g2.setColor(body.darker());
		g2.fillOval(ix + iw / 2 - 1, iy + ih / 3, 3, 4);
	}

	/** Was ShopTab.desaturate(): a luma-weighted pull toward grey, then darker(). */
	public static Color desaturate(Color color) {
		int gray = (int) (color.getRed() * 0.3 + color.getGreen() * 0.59 + color.getBlue() * 0.11);
		return new Color(
			(color.getRed() + gray * 2) / 3,
			(color.getGreen() + gray * 2) / 3,
			(color.getBlue() + gray * 2) / 3).darker();
	}
}
