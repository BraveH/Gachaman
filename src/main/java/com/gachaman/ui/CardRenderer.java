package com.gachaman.ui;

import com.gachaman.Tuning;
import com.gachaman.model.CardWear;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;

/**
 * Procedural card face rendering: rarity frames, foil sheen, shiny prismatic
 * cycling, hologram scanlines with chromatic ghosting, deterministic sparkles.
 * Pure drawing — animation state comes in via timeMs so callers control time.
 */
public final class CardRenderer
{
	private static final Color CARD_BG_TOP = new Color(48, 42, 32);
	private static final Color CARD_BG_BOTTOM = new Color(28, 24, 18);
	private static final Color NAME_BAND = new Color(20, 17, 12, 230);
	private static final Color CARD_BACK_A = new Color(58, 34, 92);
	private static final Color CARD_BACK_B = new Color(32, 18, 52);
	private static final Color SERVICE_BG = new Color(18, 15, 10, 215);
	private static final Color SERVICE_EDGE = new Color(176, 141, 87, 210);
	private static final Color SERVICE_TEXT = new Color(226, 205, 158);
	/**
	 * Kintsugi, not fracture: the crack is filled with gold over a dark relief
	 * stroke, so a worn card reads as a mended veteran rather than as damage the
	 * player should fear. Grey fracture lines were rejected for exactly that.
	 */
	private static final Color WEAR_GOLD = new Color(226, 184, 96);
	private static final Color WEAR_SHADOW = new Color(18, 14, 8);
	/** Segments per crack. Each one bends, so more segments means more wander. */
	private static final int WEAR_SEGMENTS = 4;

	@Value
	@Builder
	public static class CardView
	{
		String name;
		Rarity rarity;
		Variant variant;
		@Nullable
		BufferedImage art;
		@Nullable
		String subtitle; // e.g. "Dragon tier — any slot" for holograms
		/**
		 * Service Record — kills this copy was assigned to the loadout for.
		 * 0 hides the badge, which is what @Builder leaves an unset int at, so
		 * every existing builder call site keeps rendering pixel-identically.
		 */
		int killsServed;
	}

	private CardRenderer()
	{
	}

	/** Draw a face-down card back. */
	public static void drawBack(Graphics2D g, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, w / 7f, w / 7f);
		g2.setPaint(new GradientPaint(x, y, CARD_BACK_A, x, y + h, CARD_BACK_B));
		g2.fill(shape);
		g2.setColor(new Color(212, 175, 55));
		g2.setStroke(new BasicStroke(2f));
		g2.draw(shape);
		// simple gacha sigil: diamond + circle
		int cx = x + w / 2;
		int cy = y + h / 2;
		int r = Math.min(w, h) / 5;
		g2.setColor(new Color(212, 175, 55, 150));
		g2.drawOval(cx - r, cy - r, r * 2, r * 2);
		g2.drawLine(cx, cy - r - r / 2, cx + r + r / 2, cy);
		g2.drawLine(cx + r + r / 2, cy, cx, cy + r + r / 2);
		g2.drawLine(cx, cy + r + r / 2, cx - r - r / 2, cy);
		g2.drawLine(cx - r - r / 2, cy, cx, cy - r - r / 2);
		// slow sheen sweep so backs feel alive
		drawSheen(g2, shape, x, y, w, h, timeMs, 5200, new Color(255, 255, 255, 26));
		g2.dispose();
	}

	/** Draw a face-up card. */
	public static void drawFace(Graphics2D g, int x, int y, int w, int h, CardView view, long timeMs)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, w / 7f, w / 7f);

		// body
		g2.setPaint(new GradientPaint(x, y, CARD_BG_TOP, x, y + h, CARD_BG_BOTTOM));
		g2.fill(shape);

		// art — cropped to its opaque bounds first: item sprites carry uneven
		// transparent padding, so centering the raw sprite off-centers the art.
		// artRect is hoisted because dw/dh exist only inside this block and the
		// wear pass below needs the ACTUAL drawn rect to route cracks around; an
		// edit that moved the art block below that pass would silently leave it
		// null and quietly break the never-obscure guarantee.
		Rectangle artRect = null;
		if (view.getArt() != null)
		{
			int artH = (int) (h * 0.52);
			int artY = y + (int) (h * 0.12);
			BufferedImage art = cropToOpaqueBounds(view.getArt());
			double scale = Math.min((double) (w - 16) / art.getWidth(), (double) artH / art.getHeight());
			int dw = Math.max(1, (int) (art.getWidth() * scale));
			int dh = Math.max(1, (int) (art.getHeight() * scale));
			Graphics2D ga = (Graphics2D) g2.create();
			ga.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			ga.drawImage(art, x + (w - dw) / 2, artY + (artH - dh) / 2, dw, dh, null);
			ga.dispose();
			artRect = new Rectangle(x + (w - dw) / 2, artY + (artH - dh) / 2, dw, dh);
		}

		// name band — the font SHRINKS to fit the name (ellipsis only as a
		// last resort at the minimum size)
		int bandH = Math.max(16, h / 6);
		g2.setColor(NAME_BAND);
		g2.fillRect(x + 2, y + h - bandH - h / 8, w - 4, bandH);
		g2.setColor(view.getRarity().getColor());
		drawFittedString(g2, view.getName(), x + w / 2, y + h - bandH / 2 - h / 8, w - 8,
			new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, w / 9)), 8);
		if (view.getSubtitle() != null)
		{
			g2.setColor(new Color(200, 200, 200));
			drawFittedString(g2, view.getSubtitle(), x + w / 2, y + h - h / 16, w - 8,
				new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, w / 12)), 7);
		}

		// The service badge shares the top band with the rarity label, so it is
		// measured FIRST and the label is then centred in the span the badge
		// leaves. Reserved on the LEFT only: mirroring the reservation to keep
		// the label centred on the card costs twice the width and chops
		// "LEGENDARY" to "LEGE…" on the 90px album thumbnail, which is the
		// surface this feature exists for.
		String service = view.getKillsServed() > 0 ? serviceText(view.getKillsServed()) : null;
		int badgeTextW = -1;
		int badgeBottom = y;
		if (service != null)
		{
			g2.setFont(serviceFont(w));
			badgeTextW = g2.getFontMetrics().stringWidth(service);
			badgeBottom = serviceBadgeY(y, h) + g2.getFontMetrics().getHeight();
		}
		int labelLeft = rarityLabelLeft(x, w, badgeTextW);
		int labelRight = x + w - 4;

		// rarity label
		int labelSize = Math.max(8, w / 11);
		g2.setColor(view.getRarity().getColor());
		g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, labelSize));
		drawCenteredString(g2, view.getRarity().getDisplayName().toUpperCase(),
			(labelLeft + labelRight) / 2, y + h / 14 + 4, labelRight - labelLeft);

		// variant effects
		if (view.getVariant() == Variant.SHINY)
		{
			drawShiny(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getVariant() == Variant.HOLOGRAM)
		{
			drawHologram(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getRarity().atLeast(Rarity.EPIC))
		{
			drawSheen(g2, shape, x, y, w, h, timeMs, 3000, new Color(255, 255, 255, 40));
		}

		// after the variant effects so the number is not lost under scanlines;
		// the border block below re-sets both colour and stroke, so nothing leaks
		if (service != null)
		{
			drawServiceBadge(g2, x, y, w, h, service);
		}

		// Cracked Cards: cosmetic wear earned from the Service Record, drawn
		// BEFORE the border so the rarity frame always paints on top of it, and
		// confined to the free margin around the art so it can never cover the
		// item icon, the card name or the rarity label. "Never obscures" is a
		// property of where the cracks are ALLOWED to run, not of an
		// art-direction hope; the protected rects are a second, redundant check.
		CardWear wear = Tuning.cardWear(view.getKillsServed());
		if (wear != CardWear.NONE)
		{
			// the two band edges are measured once and handed to both helpers,
			// so the corridors and the protected rects cannot disagree
			int topBandBottom = Math.max(badgeBottom, y + h / 14 + 4 + labelSize);
			int nameBandTop = y + h - bandH - h / 8;
			// seeded from the NAME, not from timeMs and not from a new CardView
			// field: the pattern is then frame-stable (no shimmer on a static
			// badge), survives a restart, and is identical between the album
			// thumbnail and the reveal card — one physical object, not two
			drawWear(g2, shape, w, wear, wearSegments(w, wear, view.getName().hashCode(),
				wearCorridors(x, y, w, h, topBandBottom, nameBandTop, artRect),
				wearProtect(x, y, w, h, topBandBottom, nameBandTop, artRect)));
		}

		// border (variant-tinted)
		Color border = view.getVariant() == Variant.SHINY
			? prismaticColor(timeMs, 0)
			: view.getVariant() == Variant.HOLOGRAM
			? new Color(120, 220, 255)
			: view.getRarity().getColor();
		g2.setColor(border);
		g2.setStroke(new BasicStroke(view.getRarity().atLeast(Rarity.RARE) ? 2.5f : 1.6f));
		g2.draw(shape);

		g2.dispose();
	}

	/** Rarity-colored glow behind a card (charge-up etc.); intensity 0..1. */
	public static void drawGlow(Graphics2D g, int x, int y, int w, int h, Color color, float intensity)
	{
		if (intensity <= 0)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int layers = 12;
		for (int i = layers; i >= 1; i--)
		{
			float t = (float) i / layers;
			int alpha = (int) (intensity * 90 * (1 - t) * (1 - t));
			if (alpha <= 0)
			{
				continue;
			}
			g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(255, alpha)));
			int pad = (int) (t * Math.min(w, h) * 0.28f);
			g2.fill(new RoundRectangle2D.Float(x - pad, y - pad, w + pad * 2, h + pad * 2,
				w / 6f + pad, w / 6f + pad));
		}
		g2.dispose();
	}

	// --- effect internals ---

	private static void drawShiny(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		// two counter-drifting prismatic bands, swept diagonally
		gs.rotate(Math.toRadians(-25), x + w / 2.0, y + h / 2.0);
		for (int band = 0; band < 2; band++)
		{
			float phase = ((timeMs + band * 900) % 2600) / 2600f;
			int bx = x - w - h / 2 + (int) (phase * (w * 3 + h));
			Color c = prismaticColor(timeMs, band * 120);
			gs.setPaint(new GradientPaint(bx, y, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
				bx + w / 2f, y + h, new Color(c.getRed(), c.getGreen(), c.getBlue(), 70)));
			gs.fillRect(bx, y - h / 2, w / 2, h * 2);
		}
		gs.rotate(Math.toRadians(25), x + w / 2.0, y + h / 2.0);
		// deterministic sparkles
		for (int i = 0; i < 7; i++)
		{
			float u = hash01(i * 733 + 1);
			float v = hash01(i * 733 + 2);
			float twinkle = (float) (0.5 + 0.5 * Math.sin(timeMs / 260.0 + i * 1.7));
			int alpha = (int) (200 * twinkle);
			gs.setColor(new Color(255, 255, 255, alpha));
			int sx = x + (int) (u * (w - 8)) + 4;
			int sy = y + (int) (v * (h - 8)) + 4;
			int size = 2 + (i % 2);
			gs.drawLine(sx - size, sy, sx + size, sy);
			gs.drawLine(sx, sy - size, sx, sy + size);
		}
		gs.dispose();
	}

	private static void drawHologram(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs)
	{
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		// cyan wash
		gs.setColor(new Color(90, 200, 255, 26));
		gs.fillRect(x, y, w, h);
		// scanlines drifting downward
		int offset = (int) ((timeMs / 90) % 6);
		gs.setColor(new Color(140, 230, 255, 34));
		for (int sy = y + offset; sy < y + h; sy += 6)
		{
			gs.drawLine(x, sy, x + w, sy);
		}
		// chromatic ghost edges
		Composite old = gs.getComposite();
		gs.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
		gs.setColor(new Color(255, 80, 160));
		gs.drawRoundRect(x - 1, y, w, h, w / 7, w / 7);
		gs.setColor(new Color(80, 255, 220));
		gs.drawRoundRect(x + 1, y, w, h, w / 7, w / 7);
		gs.setComposite(old);
		// occasional flicker band
		float flicker = (timeMs % 2400) / 2400f;
		if (flicker > 0.9f)
		{
			int fy = y + (int) ((flicker - 0.9f) * 10 * h);
			gs.setColor(new Color(200, 245, 255, 60));
			gs.fillRect(x, fy, w, 4);
		}
		gs.dispose();
	}

	private static void drawSheen(Graphics2D g2, Shape clip, int x, int y, int w, int h,
		long timeMs, int periodMs, Color color)
	{
		// diagonal sweep: band travels corner-to-corner, tilted ~25 degrees
		Graphics2D gs = (Graphics2D) g2.create();
		gs.setClip(clip);
		gs.rotate(Math.toRadians(-25), x + w / 2.0, y + h / 2.0);
		float phase = (timeMs % periodMs) / (float) periodMs;
		int travel = w * 3 + h;
		int sx = x - w - h / 2 + (int) (phase * travel);
		gs.setPaint(new GradientPaint(sx, y, new Color(255, 255, 255, 0),
			sx + w / 3f, y, color, true));
		gs.fillRect(sx, y - h / 2, w / 3, h * 2);
		gs.dispose();
	}

	// --- cosmetic wear (Cracked Cards) ---

	/** Gold vein width. Scales with the card so a 90px thumb and a 150px reveal read alike. */
	static float wearStroke(int w)
	{
		return Math.max(1f, w / 60f);
	}

	/**
	 * How far the ink actually reaches from a crack's centre line. The relief is
	 * the widest of the two passes and is drawn centred, so this is half its
	 * width — drawWear strokes with exactly (2 * this), which is what lets a test
	 * assert the painted footprint against the same number the renderer uses
	 * instead of a copy of it that can drift.
	 */
	static float wearInkReach(int w)
	{
		return wearStroke(w) / 2f + 0.8f;
	}

	/**
	 * Clearance a crack must keep from anything protected, and the inset it keeps
	 * from the walls of its own corridor. Always at least {@link #wearInkReach},
	 * with the surplus as visible breathing room so the gold reads as sitting
	 * beside the sprite rather than crowding it. Kept deliberately tight — the
	 * free margin on a 90px album thumbnail is only about nine pixels, and a
	 * generous pad would silently reject every crack and paint nothing at all.
	 *
	 * <p>Deliberately independent of the wear STAGE. If a heavier stage used a
	 * thicker vein it would need a wider corridor, and a card could then show
	 * hairline cracks but nothing at all once it reached SHATTERED.
	 */
	static float wearStrokePad(int w)
	{
		return wearStroke(w) / 2f + 2.5f;
	}

	/** Cracks drawn. Strictly increasing with the stage. */
	static int crackCount(CardWear wear)
	{
		switch (wear)
		{
			case HAIRLINE:
				return 1;
			case CRACKED:
				return 3;
			case SHATTERED:
				return 5;
			default:
				return 0;
		}
	}

	/** Gold opacity. Strictly increasing with the stage, never fully opaque. */
	static int wearAlpha(CardWear wear)
	{
		switch (wear)
		{
			case HAIRLINE:
				return 120;
			case CRACKED:
				return 180;
			case SHATTERED:
				return 230;
			default:
				return 0;
		}
	}

	/**
	 * The three regions a crack may never touch: the top band (service pill plus
	 * rarity label), the drawn art, and the name band plus the subtitle strip
	 * below it. The art entry is null for a card with no sprite; blocked() skips
	 * nulls.
	 *
	 * <p>Callers pass the band edges they themselves drew with, so this cannot
	 * drift out of step with drawFace the way a re-derived copy would.
	 */
	static Rectangle[] wearProtect(int x, int y, int w, int h, int topBandBottom,
		int nameBandTop, @Nullable Rectangle art)
	{
		return new Rectangle[]{
			new Rectangle(x, y, w, Math.max(1, topBandBottom - y)),
			art,
			new Rectangle(x, nameBandTop, w, Math.max(1, y + h - nameBandTop))
		};
	}

	/**
	 * The free margin a crack is allowed to occupy: the strip left of the art,
	 * the strip right of it, and the band between the art and the name band. A
	 * card with no sprite yields one lane covering the whole middle.
	 *
	 * <p>Routing cracks down these lanes, rather than radiating them from the
	 * edge toward the centre, is what makes "never covers the item icon" a
	 * property of the construction. On a 90px album thumbnail a wide sprite
	 * leaves roughly eight pixels of gutter and nine below the art, so a crack
	 * aimed at the centre would have to cross the sprite to be visible at all —
	 * there is no version of the radiating design that is both visible and safe.
	 *
	 * <p>A lane narrower than the stroke can hold is dropped rather than
	 * squeezed, so the failure direction is "no crack", never "a crack over the
	 * art".
	 */
	static List<Rectangle> wearCorridors(int x, int y, int w, int h, int topBandBottom,
		int nameBandTop, @Nullable Rectangle art)
	{
		List<Rectangle> out = new ArrayList<>();
		// ceil(2*pad)+1 is always at least 2*ceil(pad), which is exactly what
		// wearSafeBox needs to find a legal integer line, so a lane that survives
		// this filter always produces a crack rather than silently producing none
		int minSpan = (int) Math.ceil(wearStrokePad(w) * 2) + 1;
		int top = Math.max(y, topBandBottom);
		int bottom = Math.min(y + h, nameBandTop);
		if (bottom - top < minSpan || w <= 0 || h <= 0)
		{
			return out;
		}
		if (art == null || art.isEmpty())
		{
			out.add(new Rectangle(x, top, w, bottom - top));
			return out;
		}
		int artLeft = Math.min(x + w, Math.max(x, art.x));
		int artRight = Math.max(x, Math.min(x + w, art.x + art.width));
		int artBottom = Math.max(top, Math.min(bottom, art.y + art.height));
		if (artLeft - x >= minSpan)
		{
			out.add(new Rectangle(x, top, artLeft - x, bottom - top));
		}
		if (x + w - artRight >= minSpan)
		{
			out.add(new Rectangle(artRight, top, x + w - artRight, bottom - top));
		}
		if (bottom - artBottom >= minSpan)
		{
			out.add(new Rectangle(x, artBottom, w, bottom - artBottom));
		}
		return out;
	}

	/**
	 * The inclusive integer box {minX, minY, maxX, maxY} that a crack's stored
	 * endpoints must land in for the painted stroke to clear the walls of its own
	 * corridor by the full pad. Null when the corridor holds no legal integer
	 * line at all, in which case the caller skips it and draws nothing.
	 *
	 * <p>This exists because endpoints are stored as ints. Insetting the corridor
	 * by the pad in floating point and then truncating moves the endpoint back
	 * toward the wall by up to a pixel, which on the nine-pixel band under a wide
	 * sprite is enough to put the stroke back over the art. Rounding OUTWARD
	 * first — ceil the low edge, floor the high edge — makes the clearance exact
	 * at integer precision instead of nearly right.
	 */
	@Nullable
	static int[] wearSafeBox(Rectangle lane, float pad)
	{
		int minX = (int) Math.ceil(lane.x + pad);
		int minY = (int) Math.ceil(lane.y + pad);
		int maxX = (int) Math.floor(lane.x + lane.width - pad);
		int maxY = (int) Math.floor(lane.y + lane.height - pad);
		if (minX > maxX || minY > maxY)
		{
			return null;
		}
		return new int[]{minX, minY, maxX, maxY};
	}

	/**
	 * Every crack segment to be drawn, as {x1,y1,x2,y2}. Each crack runs the
	 * LENGTH of one corridor and wanders across its width, clamped to that
	 * corridor's safe box so the round caps stay inside.
	 *
	 * <p>The protected rects are still handed in and still tested per segment.
	 * That is redundant with the corridors by construction, and deliberately so:
	 * it is the backstop that keeps the guarantee true if a later edit changes a
	 * band's geometry without changing the corridor maths.
	 *
	 * <p>Pure and deterministic in the seed, which is what lets a test prove the
	 * never-obscure guarantee by sweeping sizes and seeds rather than by eye.
	 */
	static List<int[]> wearSegments(int w, @Nullable CardWear wear, int seed,
		@Nullable List<Rectangle> corridors, @Nullable Rectangle[] protect)
	{
		List<int[]> out = new ArrayList<>();
		int cracks = wear == null ? 0 : crackCount(wear);
		if (cracks == 0 || corridors == null || corridors.isEmpty())
		{
			return out;
		}
		float pad = wearStrokePad(w);
		for (int k = 0; k < cracks; k++)
		{
			Rectangle lane = corridors.get(k % corridors.size());
			int[] safe = wearSafeBox(lane, pad);
			if (safe == null)
			{
				continue;
			}
			int cs = seed + k * 977;
			// a tall lane is walked top to bottom, a wide one left to right, so a
			// crack always runs the long way and never bridges the corridor
			boolean vertical = lane.height >= lane.width;
			int acrossLo = vertical ? safe[0] : safe[1];
			int acrossHi = vertical ? safe[2] : safe[3];
			int alongLo = vertical ? safe[1] : safe[0];
			int alongHi = vertical ? safe[3] : safe[2];
			if (alongHi - alongLo < 3)
			{
				// shorter than this reads as a speck of dirt, not a crack
				continue;
			}
			double mid = (acrossLo + acrossHi) / 2.0;
			double room = (acrossHi - acrossLo) / 2.0;
			// a partial run of the lane, placed by the seed: two cracks sharing a
			// lane then overlap only sometimes, the way real ones do, instead of
			// stacking into one thick smear
			double lenFrac = 0.30 + 0.35 * hash01(cs + 1);
			double span = alongHi - alongLo;
			double a0 = alongLo + span * (1 - lenFrac) * hash01(cs + 2);
			double a1 = a0 + span * lenFrac;
			int a = clamp((int) a0, alongLo, alongHi);
			int c = clamp((int) (mid + (hash01(cs + 3) - 0.5) * 2 * room), acrossLo, acrossHi);
			int px = vertical ? c : a;
			int py = vertical ? a : c;
			for (int s = 1; s <= WEAR_SEGMENTS; s++)
			{
				int na = clamp((int) (a0 + (a1 - a0) * s / WEAR_SEGMENTS), alongLo, alongHi);
				int nc = clamp((int) (mid + (hash01(cs + s * 31 + 4) - 0.5) * 2 * room),
					acrossLo, acrossHi);
				int nx = vertical ? nc : na;
				int ny = vertical ? na : nc;
				if (!blocked(protect, px, py, nx, ny, pad))
				{
					out.add(new int[]{px, py, nx, ny});
				}
				px = nx;
				py = ny;
			}
		}
		return out;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return v < lo ? lo : Math.min(v, hi);
	}

	/** True when a stroked segment, padded, would touch anything protected. */
	static boolean blocked(@Nullable Rectangle[] protect, double ax, double ay,
		double bx, double by, float pad)
	{
		if (protect == null)
		{
			return false;
		}
		int x0 = (int) Math.floor(Math.min(ax, bx) - pad);
		int y0 = (int) Math.floor(Math.min(ay, by) - pad);
		int x1 = (int) Math.ceil(Math.max(ax, bx) + pad);
		int y1 = (int) Math.ceil(Math.max(ay, by) + pad);
		Rectangle box = new Rectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
		for (Rectangle r : protect)
		{
			if (r != null && r.intersects(box))
			{
				return true;
			}
		}
		return false;
	}

	private static void drawWear(Graphics2D g2, Shape clip, int w, CardWear wear, List<int[]> segments)
	{
		if (segments.isEmpty())
		{
			return;
		}
		Graphics2D gw = (Graphics2D) g2.create();
		gw.setClip(clip);
		float vein = wearStroke(w);
		int alpha = wearAlpha(wear);
		Color shadow = new Color(WEAR_SHADOW.getRed(), WEAR_SHADOW.getGreen(),
			WEAR_SHADOW.getBlue(), alpha);
		Color gold = new Color(WEAR_GOLD.getRed(), WEAR_GOLD.getGreen(),
			WEAR_GOLD.getBlue(), alpha);
		// The relief is CENTRED on the same line, not offset a pixel: an offset
		// would push the painted edge further from the stored endpoint than the
		// routing reserved, and the pad has no room to spare at 90px. Width comes
		// from wearInkReach so the promise and the paint cannot drift apart.
		BasicStroke relief = new BasicStroke(wearInkReach(w) * 2f,
			BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		BasicStroke fill = new BasicStroke(vein, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		for (int[] seg : segments)
		{
			// dark relief underneath, so the gold reads as sitting IN a groove
			gw.setStroke(relief);
			gw.setColor(shadow);
			gw.drawLine(seg[0], seg[1], seg[2], seg[3]);
			gw.setStroke(fill);
			gw.setColor(gold);
			gw.drawLine(seg[0], seg[1], seg[2], seg[3]);
		}
		gw.dispose();
	}

	public static Color prismaticColor(long timeMs, int offsetDeg)
	{
		float hue = ((timeMs / 22) % 360 + offsetDeg) % 360 / 360f;
		return Color.getHSBColor(hue, 0.65f, 1f);
	}

	private static float hash01(int n)
	{
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static Font serviceFont(int w)
	{
		return new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, w / 13));
	}

	/** Left edge of the service badge pill. */
	static int serviceBadgeX(int x, int w)
	{
		return x + Math.max(3, w / 22);
	}

	/**
	 * Top edge of the service badge pill. Extracted from drawServiceBadge rather
	 * than duplicated because the wear pass measures the protected top band from
	 * it — two copies of this expression would drift and let a crack cross the
	 * pill.
	 */
	static int serviceBadgeY(int y, int h)
	{
		return y + Math.max(3, h / 26);
	}

	/** Horizontal padding inside the pill, one side. */
	static int serviceBadgePadX(int w)
	{
		return Math.max(3, w / 24);
	}

	/** Full width of the pill for a badge whose text measured badgeTextWidth. */
	static int serviceBadgeWidth(int w, int badgeTextWidth)
	{
		return badgeTextWidth + serviceBadgePadX(w) * 2;
	}

	/**
	 * Leftmost x the centered rarity label may occupy. Pass badgeTextWidth &lt; 0
	 * for "no badge", which must return the plain inset so a card with no
	 * service record renders exactly as it did before this feature existed.
	 *
	 * <p>Derived from the SAME geometry drawServiceBadge draws with, so the
	 * clearance is guaranteed by construction rather than by measurement luck.
	 */
	static int rarityLabelLeft(int x, int w, int badgeTextWidth)
	{
		if (badgeTextWidth < 0)
		{
			return x + 4;
		}
		return serviceBadgeX(x, w) + serviceBadgeWidth(w, badgeTextWidth) + Math.max(4, w / 20);
	}

	/**
	 * Compact service count: the corner has room for four characters, not six.
	 * Integer math only — no locale, no rounding drift, and only ASCII digits,
	 * '.', 'k' and 'm' come out, so there is no missing-glyph hazard.
	 */
	static String serviceText(int killsServed)
	{
		if (killsServed < 1000)
		{
			return Integer.toString(killsServed);
		}
		if (killsServed < 10000)
		{
			return killsServed / 1000 + "." + killsServed % 1000 / 100 + "k";
		}
		if (killsServed < 1000000)
		{
			return killsServed / 1000 + "k";
		}
		return killsServed / 1000000 + "m";
	}

	/** Top-left pill: a worn brass service tag. */
	private static void drawServiceBadge(Graphics2D g2, int x, int y, int w, int h, String text)
	{
		g2.setFont(serviceFont(w));
		FontMetrics fm = g2.getFontMetrics();
		int padX = serviceBadgePadX(w);
		int bw = serviceBadgeWidth(w, fm.stringWidth(text));
		int bh = fm.getHeight();
		int bx = serviceBadgeX(x, w);
		int by = serviceBadgeY(y, h);
		g2.setColor(SERVICE_BG);
		g2.fillRoundRect(bx, by, bw, bh, bh, bh);
		g2.setColor(SERVICE_EDGE);
		g2.setStroke(new BasicStroke(1f));
		g2.drawRoundRect(bx, by, bw, bh, bh, bh);
		g2.setColor(SERVICE_TEXT);
		g2.drawString(text, bx + padX, by + fm.getAscent() - 1);
	}

	private static void drawCenteredString(Graphics2D g, String text, int cx, int cy, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		String drawn = text;
		while (fm.stringWidth(drawn) > maxWidth && drawn.length() > 4)
		{
			drawn = drawn.substring(0, drawn.length() - 2);
		}
		if (!drawn.equals(text))
		{
			drawn = drawn.substring(0, Math.max(1, drawn.length() - 1)) + "…";
		}
		g.drawString(drawn, cx - fm.stringWidth(drawn) / 2, cy + fm.getAscent() / 2 - 1);
	}

	/**
	 * Draw centered, shrinking the font (down to minSize) until the whole
	 * text fits; only at the floor does it fall back to ellipsizing.
	 */
	private static void drawFittedString(Graphics2D g, String text, int cx, int cy, int maxWidth,
		Font baseFont, int minSize)
	{
		Font font = baseFont;
		g.setFont(font);
		while (g.getFontMetrics().stringWidth(text) > maxWidth && font.getSize() > minSize)
		{
			font = font.deriveFont((float) (font.getSize() - 1));
			g.setFont(font);
		}
		drawCenteredString(g, text, cx, cy, maxWidth);
	}

	/**
	 * The smallest subimage containing every non-transparent pixel. Item
	 * sprites pad their content unevenly; cropping first makes centering
	 * center the visible art, not the padding.
	 */
	private static BufferedImage cropToOpaqueBounds(BufferedImage image)
	{
		int minX = image.getWidth();
		int minY = image.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int py = 0; py < image.getHeight(); py++)
		{
			for (int px = 0; px < image.getWidth(); px++)
			{
				if ((image.getRGB(px, py) >>> 24) > 8)
				{
					minX = Math.min(minX, px);
					minY = Math.min(minY, py);
					maxX = Math.max(maxX, px);
					maxY = Math.max(maxY, py);
				}
			}
		}
		if (maxX < 0 || (minX == 0 && minY == 0
			&& maxX == image.getWidth() - 1 && maxY == image.getHeight() - 1))
		{
			return image; // fully transparent or already tight
		}
		return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
