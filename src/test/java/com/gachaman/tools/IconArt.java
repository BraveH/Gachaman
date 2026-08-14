package com.gachaman.tools;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import net.runelite.client.ui.*;

/**
 * Authors the plugin's flat icons as PNGs: the sidebar mark, the Plugin Hub
 * listing art, and the GitHub / Ko-fi link icons in both their rest and hover
 * colours.
 *
 * <p>These were painted at runtime by PanelIcon and by two GachamanPanel
 * methods. Every one of them is a FIXED drawing — no animation, no tint that
 * varies with state, nothing a player action changes — so painting them on
 * every panel build was re-deriving a constant. The drawing code lives here
 * now, in test scope, which keeps the art reviewable and regenerable while
 * costing the shipped plugin nothing but six small PNGs.
 *
 * <p>Run with {@code ./gradlew iconArt}. The output is committed; this is an
 * authoring tool, not a build step.
 */
public final class IconArt {
	private static final int GRID = 16;
	private static final Color CARD_FILL = new Color(88, 52, 140);
	private static final Color CARD_TRIM = new Color(212, 175, 55);
	private static final Color CHEST_FILL = new Color(112, 82, 46);
	private static final Color CHEST_TRIM = new Color(230, 190, 80);
	private static final int LINK_ICON_SIZE = 18;
	/** The album header's stardust sparkle and "Owned only" checkbox. */
	private static final int ALBUM_ICON_SIZE = 13;
	private static final Color STARDUST = new Color(190, 170, 255);

	private static final String RES = "src/main/resources/com/gachaman/ui/";

	private IconArt() {
	}

	public static void main(String[] args) throws Exception {
		new File(RES).mkdirs();
		ImageIO.write(panelIcon(GRID), "png", new File(RES + "panel-icon.png"));
		// the Plugin Hub listing art is 48x72; same drawing, fitted and centred
		ImageIO.write(hubIcon(48, 72), "png", new File("icon.png"));
		ImageIO.write(githubIcon(false), "png", new File(RES + "link-github.png"));
		ImageIO.write(githubIcon(true), "png", new File(RES + "link-github-hover.png"));
		ImageIO.write(kofiIcon(false), "png", new File(RES + "link-kofi.png"));
		ImageIO.write(kofiIcon(true), "png", new File(RES + "link-kofi-hover.png"));
		ImageIO.write(stardustIcon(), "png", new File(RES + "stardust.png"));
		ImageIO.write(checkboxIcon(false), "png", new File(RES + "checkbox-off.png"));
		ImageIO.write(checkboxIcon(true), "png", new File(RES + "checkbox-on.png"));
		System.out.println("wrote 8 icons to " + RES + " and icon.png");
	}

	/**
	 * A 13x13 canvas with antialiasing on — the surface both album icons below
	 * were painting onto, once per repaint, straight into the panel's Graphics.
	 *
	 * <p>Compositing an antialiased shape into a transparent ARGB buffer and then
	 * drawing that buffer SRC_OVER gives cov*src + (1-cov)*dst, which is what
	 * drawing the shape straight onto the destination gives — the identity
	 * ChestArt and CeremonyArt already rely on. It was measured for these two
	 * rather than assumed, and it is not quite bit-exact: no pixel moved, and on
	 * a white or transparent destination the two routes agreed exactly, but over
	 * the dark panel eight pixels per icon came out ±1 on one channel, because
	 * ARGB is stored non-premultiplied and a layer rounds once more than a direct
	 * draw. AlbumIconBakeTest holds the old drawing code and pins that band.
	 */
	private static Graphics2D iconCanvas(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	/**
	 * The stardust counter's icon: a static 4-point sparkle in pale violet. No
	 * animation and no state — the "Blessed!" case recolours the LABEL, never
	 * this glyph — so the album was re-deriving a constant on every panel build.
	 */
	static BufferedImage stardustIcon() {
		int size = ALBUM_ICON_SIZE;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = iconCanvas(img);
		int cx = size / 2;
		int cy = size / 2;
		g.setColor(STARDUST);
		g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(cx, 2, cx, size - 2);
		g.drawLine(2, cy, size - 2, cy);
		g.setStroke(new BasicStroke(1f));
		g.drawLine(cx - 2, cy - 2, cx + 2, cy + 2);
		g.drawLine(cx - 2, cy + 2, cx + 2, cy - 2);
		g.setColor(Color.WHITE);
		g.fillOval(cx - 1, cy - 1, 2, 2);
		g.dispose();
		return img;
	}

	/**
	 * The album's "Owned only" checkbox in both states: a dark rounded square
	 * with a light border, plus a crisp white 2px check mark when selected. The
	 * stock look-and-feel's check mark was nearly invisible on the dark panel,
	 * which is why the plugin draws its own.
	 *
	 * <p>The border is drawn at SIZE-1 so the 1px stroke lands inside the icon's
	 * own 13x13 box; the default STROKE_NORMALIZE then snaps it to the pixel
	 * grid, so nothing bleeds outside the raster this bakes into.
	 */
	static BufferedImage checkboxIcon(boolean selected) {
		int size = ALBUM_ICON_SIZE;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = iconCanvas(img);
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fillRoundRect(0, 0, size, size, 4, 4);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.drawRoundRect(0, 0, size - 1, size - 1, 4, 4);
		if (selected) {
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(3, 7, 5, 9);
			g.drawLine(5, 9, 10, 3);
		}
		g.dispose();
		return img;
	}

	/** The artwork centred on a non-square canvas, aspect preserved. */
	private static BufferedImage hubIcon(int width, int height) {
		int edge = Math.min(width, height);
		BufferedImage art = panelIcon(edge);
		BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.drawImage(art, (width - edge) / 2, (height - edge) / 2, null);
		g.dispose();
		return canvas;
	}

	/** A gacha card peeking out behind a chest, authored on a 16x16 grid. */
	private static BufferedImage panelIcon(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if (size != GRID) {
			// geometric accuracy, which is what an upscale wants. Deliberately NOT
			// set at 16x16: there the default (normalize) snaps the 1px outlines to
			// the pixel grid, and that crispness is the sidebar button as it ships.
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		}
		double scale = size / (double) GRID;
		g.scale(scale, scale);
		g.setStroke(new BasicStroke(1f));

		g.setColor(CARD_FILL);
		g.fillRoundRect(1, 0, 8, 11, 3, 3);
		g.setColor(CARD_TRIM);
		g.drawRoundRect(1, 0, 8, 11, 3, 3);
		g.drawOval(3, 3, 4, 4);

		g.setColor(CHEST_FILL);
		g.fillRoundRect(4, 6, 11, 9, 3, 3);
		g.setColor(CHEST_TRIM);
		g.drawRoundRect(4, 6, 11, 9, 3, 3);
		g.drawLine(4, 9, 15, 9);
		g.fillRect(9, 8, 2, 3);
		g.dispose();
		return img;
	}

	/**
	 * The GitHub mark, drawn as a UNION of primitives rather than a traced bezier
	 * so the shape stays reviewable: ears, body, legs and tail as named parts.
	 */
	private static BufferedImage githubIcon(boolean hover) {
		BufferedImage image = new BufferedImage(LINK_ICON_SIZE, LINK_ICON_SIZE,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double s = LINK_ICON_SIZE / 18.0;
		g.scale(s, s);

		Area mark = new Area(new Ellipse2D.Float(1.7f, 4.2f, 14.6f, 10.2f));
		mark.add(new Area(triangle(3.1f, 6.8f, 3.5f, 1.6f, 7.6f, 4.6f)));
		mark.add(new Area(triangle(14.9f, 6.8f, 14.5f, 1.6f, 10.4f, 4.6f)));
		mark.add(new Area(new RoundRectangle2D.Float(5.5f, 11.6f, 2.4f, 5.6f, 1.8f, 1.8f)));
		mark.add(new Area(new RoundRectangle2D.Float(10.1f, 11.6f, 2.4f, 5.6f, 1.8f, 1.8f)));

		g.setColor(hover ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		g.fill(mark);
		g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new QuadCurve2D.Float(4.0f, 10.8f, 0.6f, 13.2f, 2.2f, 16.4f));
		g.dispose();
		return image;
	}

	/** The Ko-fi cup: a white mug and two steam wisps on the brand's coral plate. */
	private static BufferedImage kofiIcon(boolean hover) {
		BufferedImage image = new BufferedImage(LINK_ICON_SIZE, LINK_ICON_SIZE,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double s = LINK_ICON_SIZE / 18.0;
		g.scale(s, s);

		g.setColor(hover ? new Color(255, 110, 107) : new Color(214, 78, 75));
		g.fillRoundRect(0, 0, 18, 18, 6, 6);

		g.setColor(Color.WHITE);
		g.fillRoundRect(3, 8, 8, 7, 2, 2);
		g.setStroke(new BasicStroke(1.5f));
		g.drawArc(9, 8, 5, 5, 90, -180);
		g.fillRoundRect(2, 15, 11, 2, 1, 1);
		g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new QuadCurve2D.Float(5.2f, 6.6f, 6.8f, 4.8f, 5.2f, 2.8f));
		g.draw(new QuadCurve2D.Float(8.6f, 6.6f, 10.2f, 4.8f, 8.6f, 2.8f));
		g.dispose();
		return image;
	}

	private static Path2D.Float triangle(float x1, float y1, float x2, float y2,
		float x3, float y3) {
		Path2D.Float path = new Path2D.Float();
		path.moveTo(x1, y1);
		path.lineTo(x2, y2);
		path.lineTo(x3, y3);
		path.closePath();
		return path;
	}
}
