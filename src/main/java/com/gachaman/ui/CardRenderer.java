package com.gachaman.ui;

import java.util.List;
import com.gachaman.*;
import com.gachaman.model.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * Procedural card face rendering: rarity frames, foil sheen, shiny prismatic
 * cycling, hologram scanlines with chromatic ghosting, deterministic sparkles.
 * Pure drawing — animation state comes in via timeMs so callers control time.
 */
public final class CardRenderer {
	private static final Color CARD_BG_TOP = new Color(48, 42, 32);
	private static final Color CARD_BG_BOTTOM = new Color(28, 24, 18);
	private static final Color NAME_BAND = new Color(20, 17, 12, 230);
	private static final Color CARD_BACK_A = new Color(58, 34, 92);
	private static final Color CARD_BACK_B = new Color(32, 18, 52);
	private static final Color SERVICE_BG = new Color(18, 15, 10, 215);
	private static final Color SERVICE_EDGE = new Color(176, 141, 87, 210);
	private static final Color SERVICE_TEXT = new Color(226, 205, 158);
	/**
	 * Card stock. A printed card is ink over a pale board, so everywhere the print
	 * has worn through — the rim, the corners, the lit ridge of a crease, a
	 * shuffling scratch — what shows is this, slightly warm and never pure white.
	 * That one fact is what makes wear read as wear instead of as drawn-on damage.
	 */
	private static final Color WEAR_STOCK = new Color(216, 208, 192);
	private static final Color WEAR_SHADOW = new Color(10, 8, 5);
	/** Handling dirt: fingers, sleeves, and a decade in a box. */
	private static final Color WEAR_GRIME = new Color(26, 19, 10);
	/**
	 * Steps per crease. A crease is one polyline crossing the whole card, so this
	 * is how finely it bends rather than how many separate marks are drawn — and
	 * because the underlying meander is a smooth curve, MORE steps is smoother,
	 * not busier. Twelve is where the joints stop being visible at 150px.
	 */
	static final int WEAR_STEPS = 12;
	/** Segment kinds returned by {@link #wearSegments}, at index 4. */
	static final int KIND_CREASE = 0;
	static final int KIND_SCRATCH = 1;
	/** How far a scratch bows off its own straight line, as a fraction of half its length. */
	private static final double SCRATCH_BEND = 0.06;
	/**
	 * The bound on that bow once wearSeamPath's grit is included — its wave tops
	 * out at 1.0 and the grit adds another 0.11 — rounded up. Placement adds this
	 * to the plain sin/cos extent, because a scratch sized by trigonometry alone
	 * pokes its tip into the name band and loses the last of itself to the text
	 * check.
	 */
	private static final double WANDER = SCRATCH_BEND * 1.12;

	@Value
	@Builder
	public static class CardView {
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

	private CardRenderer() {
	}

	/**
	 * A child Graphics2D to draw one layer on, antialiased, optionally clipped —
	 * the preamble nine drawing methods here used to spell out for themselves.
	 *
	 * <p>The null-clip branch is the reason this takes a nullable Shape rather
	 * than always calling setClip: passing null STRAIGHT through would CLEAR the
	 * caller's clip (a RuneLite overlay's, or Swing's paint clip) rather than
	 * leave it alone, and the three top-level entry points rely on inheriting it.
	 *
	 * <p>Setting antialiasing here is a no-op for the clipped callers that used
	 * to omit it — Graphics.create() copies the parent's rendering hints, and
	 * every clipped child in this file descends from drawBack's or drawFace's
	 * graphics, on which antialiasing is already on.
	 */
	private static Graphics2D layer(Graphics2D g, Shape clip) {
		Graphics2D out = (Graphics2D) g.create();
		if (clip != null) {
			out.setClip(clip);
		}
		out.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return out;
	}

	/** Draw a face-down card back. */
	public static void drawBack(Graphics2D g, int x, int y, int w, int h, long timeMs) {
		Graphics2D g2 = layer(g, null);
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
		// the diamond's half-diagonal, one and a half radii. Four separate lines
		// rather than one drawPolygon on purpose: BasicStroke(2f) defaults to
		// CAP_SQUARE/JOIN_MITER, so a closed mitered path would differ from four
		// square-capped strokes by a pixel or two at every vertex.
		int d = r + r / 2;
		g2.drawLine(cx, cy - d, cx + d, cy);
		g2.drawLine(cx + d, cy, cx, cy + d);
		g2.drawLine(cx, cy + d, cx - d, cy);
		g2.drawLine(cx - d, cy, cx, cy - d);
		// slow sheen sweep so backs feel alive
		drawSheen(g2, shape, x, y, w, h, timeMs, 5200, new Color(255, 255, 255, 26));
		g2.dispose();
	}

	/** Draw a face-up card. */
	public static void drawFace(Graphics2D g, int x, int y, int w, int h, CardView view, long timeMs) {
		Graphics2D g2 = layer(g, null);
		RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, w / 7f, w / 7f);

		// body
		g2.setPaint(new GradientPaint(x, y, CARD_BG_TOP, x, y + h, CARD_BG_BOTTOM));
		g2.fill(shape);

		// art — cropped to its opaque bounds first: item sprites carry uneven
		// transparent padding, so centering the raw sprite off-centers the art.
		if (view.getArt() != null) {
			int artH = (int) (h * 0.52);
			int artY = y + (int) (h * 0.12);
			BufferedImage art = cropToOpaqueBounds(view.getArt());
			double scale = Math.min((double) (w - 16) / art.getWidth(), (double) artH / art.getHeight());
			int dw = Math.max(1, (int) (art.getWidth() * scale));
			int dh = Math.max(1, (int) (art.getHeight() * scale));
			// Nearest-neighbour so a scaled item sprite stays crisp instead of
			// turning to mush. Set on g2 itself rather than on a throwaway child:
			// g2 is drawFace's own private copy (created above, disposed at the
			// end), so the hint cannot escape to the caller, and KEY_INTERPOLATION
			// only affects drawImage and TexturePaint — neither of which anything
			// drawn after the art here uses.
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(art, x + (w - dw) / 2, artY + (artH - dh) / 2, dw, dh, null);
		}

		// Cracked Cards, first of three passes: handling dirt. Deliberately over
		// the art and under every piece of text — a veteran card's PICTURE is the
		// part that goes dull with age, while its printing has to stay readable at
		// 90px. Drawing it under the art instead would read as a dirty background
		// rather than as a dirty card.
		CardWear wear = Tuning.cardWear(view.getKillsServed());
		// seeded from the NAME, not from timeMs and not from a new CardView
		// field: the pattern is then frame-stable (no shimmer on a static
		// card), survives a restart, and is identical between the album
		// thumbnail and the reveal card — one physical object, not two.
		// Hoisted because all three wear passes below want the same number, and
		// String.hashCode is specified and pure, so one read cannot disagree
		// with another.
		int seed = view.getName().hashCode();
		if (wear != CardWear.NONE) {
			drawWearGrime(g2, shape, x, y, w, h, wear, seed);
		}

		// name band — the font SHRINKS to fit the name (ellipsis only as a
		// last resort at the minimum size)
		int bandH = Math.max(16, h / 6);
		g2.setColor(NAME_BAND);
		g2.fillRect(x + 2, y + h - bandH - h / 8, w - 4, bandH);
		g2.setColor(view.getRarity().getColor());
		drawFittedString(g2, view.getName(), x + w / 2, y + h - bandH / 2 - h / 8, w - 8,
			new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, w / 9)), 8);
		if (view.getSubtitle() != null) {
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
		if (service != null) {
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
		if (view.getVariant() == Variant.SHINY) {
			drawShiny(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getVariant() == Variant.HOLOGRAM) {
			drawHologram(g2, shape, x, y, w, h, timeMs);
		}
		else if (view.getRarity().atLeast(Rarity.EPIC)) {
			drawSheen(g2, shape, x, y, w, h, timeMs, 3000, new Color(255, 255, 255, 40));
		}

		// after the variant effects so the number is not lost under scanlines;
		// the border block below re-sets both colour and stroke, so nothing leaks
		if (service != null) {
			drawServiceBadge(g2, x, y, w, h, service);
		}

		// Second pass: creases and scratches. Drawn BEFORE the border so the
		// rarity frame always paints on top of them, and allowed to run edge to
		// edge straight ACROSS the art — a card creases through the picture, and
		// confining the fold to the margins is what made this read as scribble
		// rather than as damage. What it may never cover is TEXT: the service
		// pill, the rarity label and the name band. A segment that would cross one
		// is dropped, which leaves a clean gap, as if the band were a label stuck
		// on the card and the crease ran underneath it.
		int topBandBottom = Math.max(badgeBottom, y + h / 14 + 4 + labelSize);
		int nameBandTop = y + h - bandH - h / 8;
		if (wear != CardWear.NONE) {
			drawWearLines(g2, shape, w, wear, wearSegments(x, y, w, h, wear,
				seed, wearProtect(x, y, w, h, topBandBottom, nameBandTop)));
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

		// Third pass: the chewed edge, and the only thing drawn AFTER the border —
		// that is the whole point of it. A worn card's frame is interrupted where
		// the gilt has flaked off, so the nicks have to bite the border itself
		// rather than sit politely inside it.
		if (wear != CardWear.NONE) {
			drawWearEdge(g2, shape, x, y, w, h, wear, seed);
		}

		g2.dispose();
	}

	/** Rarity-colored glow behind a card (charge-up etc.); intensity 0..1. */
	public static void drawGlow(Graphics2D g, int x, int y, int w, int h, Color color, float intensity) {
		if (intensity <= 0)
			return;
		Graphics2D g2 = layer(g, null);
		int layers = 12;
		for (int i = layers; i >= 1; i--) {
			float t = (float) i / layers;
			int alpha = (int) (intensity * 90 * (1 - t) * (1 - t));
			if (alpha <= 0)
				continue;
			// alpha() clamps to 0..255, which subsumes the Math.min this used to do
			g2.setColor(alpha(color, alpha));
			int pad = (int) (t * Math.min(w, h) * 0.28f);
			g2.fill(new RoundRectangle2D.Float(x - pad, y - pad, w + pad * 2, h + pad * 2,
				w / 6f + pad, w / 6f + pad));
		}
		g2.dispose();
	}

	// --- effect internals ---

	private static void drawShiny(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs) {
		Graphics2D gs = layer(g2, clip);
		// two counter-drifting prismatic bands, swept diagonally
		gs.rotate(Math.toRadians(-25), x + w / 2.0, y + h / 2.0);
		for (int band = 0; band < 2; band++) {
			float phase = ((timeMs + band * 900) % 2600) / 2600f;
			int bx = x - w - h / 2 + (int) (phase * (w * 3 + h));
			Color c = prismaticColor(timeMs, band * 120);
			gs.setPaint(new GradientPaint(bx, y, alpha(c, 0), bx + w / 2f, y + h, alpha(c, 70)));
			gs.fillRect(bx, y - h / 2, w / 2, h * 2);
		}
		gs.rotate(Math.toRadians(25), x + w / 2.0, y + h / 2.0);
		// deterministic sparkles
		for (int i = 0; i < 7; i++) {
			float u = Paint.hash01(i * 733 + 1);
			float v = Paint.hash01(i * 733 + 2);
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

	private static void drawHologram(Graphics2D g2, Shape clip, int x, int y, int w, int h, long timeMs) {
		Graphics2D gs = layer(g2, clip);
		// cyan wash
		gs.setColor(new Color(90, 200, 255, 26));
		gs.fillRect(x, y, w, h);
		// scanlines drifting downward
		int offset = (int) ((timeMs / 90) % 6);
		gs.setColor(new Color(140, 230, 255, 34));
		for (int sy = y + offset; sy < y + h; sy += 6) {
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
		if (flicker > 0.9f) {
			int fy = y + (int) ((flicker - 0.9f) * 10 * h);
			gs.setColor(new Color(200, 245, 255, 60));
			gs.fillRect(x, fy, w, 4);
		}
		gs.dispose();
	}

	private static void drawSheen(Graphics2D g2, Shape clip, int x, int y, int w, int h,
		long timeMs, int periodMs, Color color) {
		// diagonal sweep: band travels corner-to-corner, tilted ~25 degrees
		Graphics2D gs = layer(g2, clip);
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
	//
	// Modelled on what a played trading card actually looks like, in the order a
	// grader would list it. Edge whitening first — the print layer wears through
	// at the rim and the pale stock core shows, worst at the corners, and it is
	// the single most recognisable "this card has been handled" cue there is. Then
	// creases: a card bends along a LINE, so a crease is near-straight and reads as
	// a shadow with a lit ridge beside it. Then surface scratches from sleeving
	// and shuffling, then general patina.
	//
	// Deliberately none of it is gold. An earlier pass drew kintsugi repair veins,
	// which is a nice idea and looks nothing like a worn card.
	//
	// Every card wears differently; every card in a bracket wears the same AMOUNT.
	// The counts and opacities below are functions of the stage alone, and the
	// per-card seed only ever picks where a crease enters, which way the scratches
	// lean, and where the fringe thins. So a SHATTERED card always reads as
	// SHATTERED at a glance — the weight of the damage is the stage — and no two
	// cards in the album carry it in the same places.

	/** Crease width. Scales with the card so a 90px thumb and a 150px reveal read alike. */
	static float wearStroke(int w) {
		return Math.max(1.6f, w / 55f);
	}

	/**
	 * How far the ink actually reaches from a line's centre line. The crease
	 * shadow is the widest of the passes and is drawn centred, so this is half its
	 * width — drawWearLines strokes with exactly (2 * this), which is what lets a
	 * test assert the painted footprint against the same number the renderer uses
	 * instead of against a copy of it that can drift.
	 */
	static float wearInkReach(int w) {
		return wearStroke(w) * 1.35f;
	}

	/**
	 * Everything the stage decides, as one table: five parallel switch statements
	 * over the same four-constant enum were 900-odd characters of case labels for
	 * twenty numbers, and reading a stage across them meant reading five methods.
	 * One row per {@link CardWear}, indexed by ordinal, so a row IS the recipe for
	 * a stage and the progression down a column is visible at a glance.
	 *
	 * <p>Rows are in enum declaration order: NONE, HAIRLINE, CRACKED, SHATTERED.
	 * NONE is ordinal 0 and its row is all zeroes, which is exactly what every
	 * switch's {@code default:} arm returned. Columns are the five accessors
	 * below, in this order:
	 *
	 * <pre>
	 *   creases  scratches  lineAlpha  grimeAlpha  edgeNicks
	 * </pre>
	 *
	 * <p>The one honest difference from the switches: a fifth constant added to
	 * CardWear would throw here where a switch would have quietly returned the
	 * default. WearTuningTest pins the row order and the row count against the
	 * enum, so that shows up as a red test rather than as a crash in a reveal.
	 */
	private static final int[][] WEAR_TUNING = {
		{0, 0, 0, 0, 0},
		{0, 6, 96, 34, 20},
		{1, 10, 150, 62, 38},
		{2, 16, 200, 92, 60}
	};

	/**
	 * Creases. A crease is the heaviest single thing that can happen to a card
	 * short of a tear, so these numbers are small on purpose: none at all on a
	 * lightly played card, one on a worn one, two on a wreck.
	 */
	static int creaseCount(CardWear wear) {
		return WEAR_TUNING[wear.ordinal()][0];
	}

	/**
	 * Surface scratches from sleeving and shuffling. Unlike creases these start
	 * immediately — a card with a hundred kills of service has been in and out of
	 * a loadout a hundred times, and fine scuffing is the first thing that shows.
	 */
	static int scratchCount(CardWear wear) {
		return WEAR_TUNING[wear.ordinal()][1];
	}

	/** Line opacity for creases and scratches. Strictly increasing, never opaque. */
	static int wearAlpha(CardWear wear) {
		return WEAR_TUNING[wear.ordinal()][2];
	}

	/**
	 * Handling-dirt opacity at the rim, where the vignette is darkest. Kept well
	 * under the line work: patina is the layer you notice last.
	 */
	static int grimeAlpha(CardWear wear) {
		return WEAR_TUNING[wear.ordinal()][3];
	}

	/**
	 * Patches of worn print along the border. High counts on purpose — edge
	 * whitening is a continuous frayed fringe, not a handful of chips, and the
	 * only way to get a fringe out of discrete marks is to use enough of them
	 * that they overlap.
	 */
	static int edgeNicks(CardWear wear) {
		return WEAR_TUNING[wear.ordinal()][4];
	}

	/**
	 * The two regions a line may never touch, and they are both TEXT: the top
	 * band (service pill plus rarity label) and the name band plus the subtitle
	 * strip under it. The art is pointedly NOT protected — a card creases
	 * through the picture, and keeping the fold out of the picture is exactly
	 * what made the old routing read as margin scribble.
	 *
	 * <p>Callers pass the band edges they themselves drew with, so this cannot
	 * drift out of step with drawFace the way a re-derived copy would.
	 */
	static Rectangle[] wearProtect(int x, int y, int w, int h, int topBandBottom,
		int nameBandTop) {
		return new Rectangle[]{
			new Rectangle(x, y, w, Math.max(1, topBandBottom - y)),
			new Rectangle(x, nameBandTop, w, Math.max(1, y + h - nameBandTop))
		};
	}

	/**
	 * The clear vertical window between the two text bands, as {lo, hi}. Derived
	 * from the protected rects the caller already built rather than from a second
	 * copy of drawFace's band arithmetic, so the two cannot disagree.
	 *
	 * <p>A rect touching the top of the card pushes {@code lo} down past it;
	 * anything else pulls {@code hi} up above it. That is exactly the shape of
	 * {@link #wearProtect} and does not care what order it returns them in.
	 * Degenerate input collapses to the whole card, and the per-segment
	 * {@link #blocked} check still keeps the guarantee — it just means a card
	 * whose bands eat the entire face draws no seams at all rather than seams in
	 * the wrong place.
	 */
	static int[] wearOpenBand(Rectangle[] protect, int y, int h) {
		int lo = y;
		int hi = y + h;
		if (protect != null) {
			for (Rectangle r : protect) {
				if (r == null)
					continue;
				if (r.y <= lo) {
					lo = Math.max(lo, r.y + r.height);
				}
				else {
					hi = Math.min(hi, r.y);
				}
			}
		}
		return hi - lo < 4 ? new int[]{y, y + h} : new int[]{lo, hi};
	}

	/**
	 * A point on the card's perimeter. {@code edge} is 0=top, 1=right, 2=bottom,
	 * 3=left and {@code t} in 0..1 slides along it, squeezed into the middle 70%
	 * so a seam never starts in a rounded corner where the clip would eat it.
	 *
	 * <p>The left and right edges slide along {@code band} — the clear strip
	 * between the two text bands — rather than the card's full height. A seam
	 * entering at the same height as the name band would be dropped in its
	 * entirety by the text check and paint nothing; entering through the open
	 * middle, it always crosses the face. Top and bottom entries deliberately do
	 * NOT get this treatment: losing their first few segments under the rarity
	 * label is the look, a break running beneath a stuck-on label.
	 *
	 * <p>Inset by {@code pad} rather than sitting exactly on the boundary: the
	 * seam is stroked round-capped, so an endpoint on the line would put half the
	 * relief outside the card, where the clip flattens it into a blunt stub
	 * instead of letting it taper into the frame.
	 */
	static double[] wearEdgePoint(int x, int y, int w, int h, int[] band, int edge, float t,
		float pad) {
		double u = 0.15 + 0.70 * t;
		double v = band[0] + u * (band[1] - band[0]);
		switch (edge & 3) {
			case 0:
				return new double[]{x + u * w, y + pad};
			case 1:
				return new double[]{x + w - pad, v};
			case 2:
				return new double[]{x + u * w, y + h - pad};
			default:
				return new double[]{x + pad, v};
		}
	}

	/**
	 * One seam as a polyline of {@link #WEAR_STEPS}+1 points, running from a to b
	 * with seeded jitter perpendicular to that line.
	 *
	 * <p>The offset is a MEANDER, not a random walk: two slow sine waves at a
	 * seeded frequency and phase, with a little grit on top. Sampling an
	 * independent random number per step is what draws a sawtooth, and a sawtooth
	 * is precisely the "horrible ascii" this rewrite exists to delete. A real
	 * break drifts, then turns; it does not alternate.
	 *
	 * <p>The whole offset is then tapered by {@code sin(pi*t)}, which is zero at
	 * both ends. That is the second half of the trick: the seam wanders freely
	 * across the middle of the card but arrives at each edge exactly where it was
	 * aimed, so it meets the frame cleanly instead of stopping in mid-air.
	 */
	static double[][] wearSeamPath(double ax, double ay, double bx, double by, int seed,
		double amp) {
		double dx = bx - ax;
		double dy = by - ay;
		double len = Math.hypot(dx, dy);
		double nx = len < 1e-6 ? 0 : -dy / len;
		double ny = len < 1e-6 ? 0 : dx / len;
		// one full lobe at the low end, so even the calmest seam bows rather than
		// running straight; the fast wave rides on it as a second, smaller kink
		double slow = 1.0 + Paint.hash01(seed + 1) * 1.4;
		double fast = 2.6 + Paint.hash01(seed + 2) * 2.4;
		double slowPhase = Paint.hash01(seed + 3) * Math.PI * 2;
		double fastPhase = Paint.hash01(seed + 4) * Math.PI * 2;
		double lean = Paint.hash01(seed + 5) < 0.5 ? -1 : 1;
		double[][] pts = new double[WEAR_STEPS + 1][2];
		for (int s = 0; s <= WEAR_STEPS; s++) {
			double t = (double) s / WEAR_STEPS;
			double wave = 0.66 * Math.sin(slow * Math.PI * t + slowPhase)
				+ 0.34 * Math.sin(fast * Math.PI * t + fastPhase);
			// grit is a fifth of the wave at most: enough to roughen the line,
			// never enough to become the line
			double grit = (Paint.hash01(seed + s * 31 + 7) - 0.5) * 0.22;
			double offset = lean * amp * Math.sin(Math.PI * t) * (wave + grit);
			pts[s][0] = ax + dx * t + nx * offset;
			pts[s][1] = ay + dy * t + ny * offset;
		}
		return pts;
	}

	/**
	 * Every line segment to be drawn, as {x1,y1,x2,y2,kind} with kind either
	 * {@link #KIND_CREASE} or {@link #KIND_SCRATCH}. Both kinds share one list so
	 * the text-clearance and stays-inside guarantees are proved once.
	 *
	 * <p>A CREASE is a fold, and a folded card creases from edge to edge in
	 * essentially a straight line — the amplitude here is a third of what a
	 * cracked-glass effect would use, because a crease that wanders is a tear.
	 * A SCRATCH is shorter, sits anywhere on the face, and leans with the rest of
	 * its card: cards get scuffed by sliding in and out of the same sleeve the
	 * same way, so a seeded dominant angle with a little spread per scratch reads
	 * as handling, where independent angles read as confetti.
	 *
	 * <p>Every card wears differently and every card in a bracket wears the same
	 * AMOUNT. The counts and opacities come only from {@code wear}; the seed only
	 * ever chooses where things land and which way they lean. That split is the
	 * whole contract: the stage is legible at a glance because two SHATTERED
	 * cards carry the same weight of damage, and the album does not look
	 * stamped because no two carry it in the same places.
	 *
	 * <p>Segments that would cross protected text are dropped rather than
	 * rerouted. Dropping leaves a gap in a line that carries on afterwards, which
	 * reads correctly — the name band is a printed label and the crease runs
	 * under it. Rerouting would bend the line around the band and put back the
	 * kink this rewrite exists to remove.
	 *
	 * <p>The first crease of every card is forced left-to-right. Top and bottom
	 * entries lose their first or last few segments to the two text bands, so
	 * without this a one-crease card could roll a top-to-bottom fold and show
	 * almost nothing; a horizontal crease always crosses the open middle.
	 *
	 * <p>Pure and deterministic in the seed, which is what lets a test prove the
	 * never-obscure guarantee by sweeping sizes and seeds rather than by eye.
	 */
	static List<int[]> wearSegments(int x, int y, int w, int h, CardWear wear,
		int seed, Rectangle[] protect) {
		List<int[]> out = new ArrayList<>();
		if (wear == null || w <= 0 || h <= 0)
			return out;
		float pad = wearInkReach(w);
		int[] band = wearOpenBand(protect, y, h);

		double creaseAmp = Math.min(w, h) * 0.035;
		for (int k = 0; k < creaseCount(wear); k++) {
			int cs = seed + k * 977;
			int entry;
			int exit;
			if (k == 0) {
				entry = 3;
				exit = 1;
			}
			else {
				entry = (int) (Paint.hash01(cs + 1) * 4) & 3;
				// +1.. so the exit can never land back on the entry edge, which
				// would give a fold that leaves and returns through the same side
				exit = (entry + 1 + (int) (Paint.hash01(cs + 2) * 3)) & 3;
			}
			double[] a = wearEdgePoint(x, y, w, h, band, entry, Paint.hash01(cs + 3), pad);
			double[] b = wearEdgePoint(x, y, w, h, band, exit, Paint.hash01(cs + 4), pad);
			emitSeam(out, clampPath(wearSeamPath(a[0], a[1], b[0], b[1], cs + 5, creaseAmp),
				x, y, w, h, pad), pad, protect, KIND_CREASE);
		}

		// one shuffle direction per card, shared by all of its scratches
		double lean = Paint.hash01(seed + 61) * Math.PI;
		for (int k = 0; k < scratchCount(wear); k++) {
			int ss = seed + 4099 + k * 613;
			double angle = lean + (Paint.hash01(ss + 1) - 0.5) * 0.7;
			// SQUARED, so most scratches are short and the occasional one is long.
			// A uniform draw gave every card several near-full-length lines, and
			// half a dozen long strokes at matched angles reads as straw laid on
			// the card rather than as a surface that has been rubbed.
			double r = Paint.hash01(ss + 2);
			double half = Math.min(w, h) * (0.07 + 0.30 * r * r) / 2;
			// How far the drawn scratch actually reaches from its centre on each
			// axis. WANDER covers the meander wearSeamPath adds perpendicular to
			// the line — small, but it is what a plain sin/cos bound misses, and
			// missing it puts the tip of a scratch under the name band.
			double rise = Math.abs(Math.sin(angle)) + WANDER;
			double run = Math.abs(Math.cos(angle)) + WANDER;
			// Length is chosen first and the centre is then placed only where the
			// whole scratch fits between the two text bands. Not for safety — the
			// per-segment check would drop any overhang anyway — but so the AMOUNT
			// of scuffing is the same for every card in the bracket. A scratch
			// half-eaten by the name band is a scratch this card silently did not
			// get, and the stage would stop reading at a glance.
			double roomY = (band[1] - band[0]) / 2.0 - pad;
			if (half * rise > roomY) {
				// an open band shorter than the scratch: shorten the scratch, since
				// the alternative is to draw it somewhere it will be thrown away
				half = Math.max(1, roomY / rise);
			}
			double loY = band[0] + pad + half * rise;
			double hiY = band[1] - pad - half * rise;
			double cy = loY + Paint.hash01(ss + 4) * Math.max(0, hiY - loY);
			double loX = x + pad + half * run;
			double hiX = x + w - pad - half * run;
			double cx = loX <= hiX
				? loX + Paint.hash01(ss + 3) * (hiX - loX)
				: x + w / 2.0;
			double hx = Math.cos(angle) * half;
			double hy = Math.sin(angle) * half;
			// barely any wander: a scratch is a straight drag, and the curve is
			// only here so it does not look ruled with a straightedge
			emitSeam(out, clampPath(wearSeamPath(cx - hx, cy - hy, cx + hx, cy + hy,
				ss + 5, half * SCRATCH_BEND), x, y, w, h, pad), pad, protect, KIND_SCRATCH);
		}
		return out;
	}

	/**
	 * Pull every point of a seam back inside the card by the ink reach. The
	 * tapered jitter already lands both ENDS exactly where they were aimed, but
	 * the bulge in the middle of a steep seam can swing past a side on a narrow
	 * card. Clamping per point keeps the polyline connected, and it means "wear
	 * never paints outside the card" is arithmetic rather than a job the
	 * rounded-rect clip is quietly doing for us.
	 *
	 * <p>The bounds are rounded OUTWARD to whole pixels — ceil the low edge, floor
	 * the high one — because emitSeam rounds each point to an int afterwards.
	 * Clamping at x+2.4 and then rounding 10.4 down to 10 would put the ink back
	 * over the edge by half a pixel; clamping at 11 cannot, since rounding never
	 * moves a value below its own floor.
	 */
	private static double[][] clampPath(double[][] path, int x, int y, int w, int h, float pad) {
		double loX = Math.ceil(x + pad);
		double hiX = Math.floor(x + w - pad);
		double loY = Math.ceil(y + pad);
		double hiY = Math.floor(y + h - pad);
		if (loX > hiX || loY > hiY) {
			// a card too small to hold even one padded pixel; nothing legal to draw
			return path;
		}
		for (double[] p : path) {
			p[0] = Math.max(loX, Math.min(hiX, p[0]));
			p[1] = Math.max(loY, Math.min(hiY, p[1]));
		}
		return path;
	}

	/**
	 * Round a polyline to integer segments, dropping any that would cross text.
	 *
	 * <p>Rounded FIRST, then tested. Testing the unrounded point and storing the
	 * rounded one lets the rounding move the ink up to half a pixel toward the
	 * band after the check has already passed, which is exactly enough to put the
	 * relief over the top of the rarity label on a small card.
	 */
	private static void emitSeam(List<int[]> out, double[][] path, float pad,
		Rectangle[] protect, int kind) {
		for (int s = 1; s < path.length; s++) {
			int ax = (int) Math.round(path[s - 1][0]);
			int ay = (int) Math.round(path[s - 1][1]);
			int bx = (int) Math.round(path[s][0]);
			int by = (int) Math.round(path[s][1]);
			if (!blocked(protect, ax, ay, bx, by, pad)) {
				out.add(new int[]{ax, ay, bx, by, kind});
			}
		}
	}

	/** True when a stroked segment, padded, would touch anything protected. */
	static boolean blocked(Rectangle[] protect, double ax, double ay,
		double bx, double by, float pad) {
		if (protect == null)
			return false;
		int x0 = (int) Math.floor(Math.min(ax, bx) - pad);
		int y0 = (int) Math.floor(Math.min(ay, by) - pad);
		int x1 = (int) Math.ceil(Math.max(ax, bx) + pad);
		int y1 = (int) Math.ceil(Math.max(ay, by) + pad);
		Rectangle box = new Rectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
		for (Rectangle r : protect) {
			if (r != null && r.intersects(box))
				return true;
		}
		return false;
	}

	/**
	 * Handling dirt: a vignette that darkens toward the rim, plus a few soft
	 * blotches. The vignette is transparent at its centre, which is where the
	 * sprite sits, so the art dulls at its extremities and stays legible in the
	 * middle without anything having to know where the art actually is.
	 */
	private static void drawWearGrime(Graphics2D g2, Shape clip, int x, int y, int w, int h,
		CardWear wear, int seed) {
		int alpha = grimeAlpha(wear);
		if (alpha <= 0 || w <= 0 || h <= 0)
			return;
		Graphics2D gg = layer(g2, clip);
		// centred a little above the middle, on the sprite rather than on the
		// card, so the clear window sits where the thing worth seeing is
		gg.setPaint(new RadialGradientPaint(
			new Point2D.Float(x + w / 2f, y + h * 0.42f),
			Math.max(w, h) * 0.72f,
			new float[]{0f, 0.50f, 1f},
			new Color[]{
				alpha(WEAR_GRIME, 0),
				alpha(WEAR_GRIME, alpha / 3),
				alpha(WEAR_GRIME, alpha)
			}));
		gg.fill(clip);
		// blotches: flat ovals stacked concentrically rather than one gradient
		// each. drawFace runs every frame of a reveal, and a per-blotch
		// RadialGradientPaint costs more than the softness is worth at this size.
		//
		// Six thin rings, not three fat ones. The same total opacity spread over
		// twice as many steps costs nothing extra and is the difference between
		// a soiled patch and a visible grey disc — with three rings the outermost
		// step lands at a fifth of full grime in one jump, and the eye reads that
		// jump as an outline.
		int blotches = Math.max(1, edgeNicks(wear) / 4);
		int rings = 6;
		for (int i = 0; i < blotches; i++) {
			int bs = seed + i * 613;
			int d = (int) (Math.min(w, h) * (0.34f + 0.40f * Paint.hash01(bs + 3)));
			int bx = x + (int) (Paint.hash01(bs + 1) * w) - d / 2;
			int by = y + (int) (Paint.hash01(bs + 2) * h) - d / 2;
			gg.setColor(alpha(WEAR_GRIME, Math.max(1, alpha / (rings * 2))));
			for (int ring = rings; ring >= 1; ring--) {
				int rd = Math.max(1, d * ring / rings);
				gg.fillOval(bx + (d - rd) / 2, by + (d - rd) / 2, rd, rd);
			}
		}
		gg.dispose();
	}

	/**
	 * Creases and scratches. Full passes over every segment of a kind rather than
	 * a full stack per segment: with per-segment stacks the shadow of a later
	 * segment paints over the lit ridge of an earlier one wherever two lines
	 * cross, and a crossing is exactly where the relief matters most.
	 *
	 * <p>A crease is drawn as three things, in the order a fold actually presents
	 * itself: a broad soft valley, a dark line in the bottom of it, and a pale
	 * ridge along ONE side. The ridge is the whole trick. It is the colour of the
	 * card stock because a fold cracks the printed ink and shows the board
	 * underneath, and it is off-centre because relief is a direction — light from
	 * somewhere, shadow on the other side. Centre it and the crease flattens back
	 * into a drawn-on line.
	 *
	 * <p>A scratch gets none of that: one hairline, thin and faint, in the same
	 * stock colour. A scuff is a shallow gouge with no depth to shade.
	 *
	 * <p>Every pass is inside {@link #wearInkReach} of the stored segment — the
	 * broad valley by half its width, the ridge by its offset plus half of its
	 * own — which is what {@link #blocked} reserves, so the paint that lands and
	 * the clearance the tests assert cannot drift apart.
	 */
	private static void drawWearLines(Graphics2D g2, Shape clip, int w, CardWear wear,
		List<int[]> segments) {
		if (segments.isEmpty())
			return;
		Graphics2D gw = layer(g2, clip);
		float line = wearStroke(w);
		int alpha = wearAlpha(wear);
		// Each pass filters the one shared list by kind as it walks it, rather
		// than being handed a pre-split copy. Splitting first meant building two
		// throwaway ArrayLists on every frame of every card, and the filter is a
		// single int compare inside a loop the pass already runs. The z-order is
		// unchanged and is the whole point of passing rather than stacking: all
		// crease valleys, then the crease shadow ridge, then the crease stock
		// ridge, then the two scratch ridges.
		strokePass(gw, segments, KIND_CREASE, roundStroke(wearInkReach(w) * 2f),
			alpha(WEAR_SHADOW, alpha / 4));
		// the dark side and the lit side sit either side of the fold line, half
		// a stroke each way. Stacking both on the centre is what flattened the
		// earlier pass into a drawn-on line: relief is two tones meeting, and
		// they cannot meet if they are on top of each other.
		ridgePass(gw, segments, KIND_CREASE, roundStroke(line * 0.9f),
			alpha(WEAR_SHADOW, alpha), line * -0.5f);
		ridgePass(gw, segments, KIND_CREASE, roundStroke(line * 0.7f),
			alpha(WEAR_STOCK, alpha), line * 0.5f);
		// A scratch is a groove, so it gets the same two-tone treatment as a crease
		// at a tenth of the scale: a dark side and a lit side half a hairline
		// apart. Sub-pixel at these widths, which is exactly right — antialiasing
		// resolves it into a soft channel in the surface, where a single bright
		// line sat on top of the card like a drawn stick.
		float fine = Math.max(1f, line * 0.35f);
		ridgePass(gw, segments, KIND_SCRATCH, roundStroke(fine),
			alpha(WEAR_SHADOW, alpha * 2 / 5), line * -0.45f);
		ridgePass(gw, segments, KIND_SCRATCH, roundStroke(fine),
			alpha(WEAR_STOCK, alpha * 2 / 5), line * 0.45f);
		gw.dispose();
	}

	/**
	 * Every stroke in the wear passes is round-capped and round-joined — a worn
	 * mark has no square ends — so the cap/join pair was spelled out seven times.
	 * Each caller still gets its own instance; BasicStroke is immutable, so that
	 * is a formality rather than a requirement.
	 */
	private static BasicStroke roundStroke(float width) {
		return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	}

	private static void strokePass(Graphics2D gw, List<int[]> segments, int kind,
		BasicStroke stroke, Color color) {
		gw.setStroke(stroke);
		gw.setColor(color);
		for (int[] seg : segments) {
			if (seg[4] == kind) {
				gw.drawLine(seg[0], seg[1], seg[2], seg[3]);
			}
		}
	}

	/**
	 * A pass over every segment of {@code kind}, shifted {@code off} pixels
	 * perpendicular to each. The normal comes from the segment's own direction,
	 * and the polyline is walked in a consistent order, so the shift stays on one
	 * side for the whole length of a crease instead of flipping about and
	 * crosshatching it.
	 *
	 * <p>Drawn through {@link Line2D.Double} rather than the int-coordinate
	 * drawLine. Rounding the shifted endpoint could push it a further half pixel
	 * out, and {@code off} plus half this stroke is already exactly
	 * {@link #wearInkReach} — the budget the text-clearance check reserves. There
	 * is no half pixel spare, so nothing may round.
	 */
	private static void ridgePass(Graphics2D gw, List<int[]> segments, int kind,
		BasicStroke stroke, Color color, float off) {
		gw.setStroke(stroke);
		gw.setColor(color);
		for (int[] seg : segments) {
			double dx = seg[2] - seg[0];
			double dy = seg[3] - seg[1];
			double len = Math.hypot(dx, dy);
			// the wrong kind and a zero-length segment are both "not this pass's
			// business", so they share the one skip
			if (seg[4] != kind || len < 1e-6)
				continue;
			double nx = -dy / len * off;
			double ny = dx / len * off;
			gw.draw(new Line2D.Double(seg[0] + nx, seg[1] + ny, seg[2] + nx, seg[3] + ny));
		}
	}

	/**
	 * Edge whitening — the single most recognisable thing about a played card,
	 * and the reason this pass runs AFTER the border instead of before it.
	 *
	 * <p>The rim of a card is where the ink goes first: it is what rubs on every
	 * other card in the deck, every sleeve, every box wall. What shows through is
	 * the pale core of the board, so the marks here are drawn in
	 * {@link #WEAR_STOCK} and they eat the gilt frame. An earlier pass drew dark
	 * bites instead, which is the mental image of "damage" but the opposite of
	 * what a worn card does — the edge gets LIGHTER, not darker.
	 *
	 * <p>Each mark is a dash lying ALONG the edge, not a dot on it. Dots read as
	 * punched holes; overlapping tangential dashes of varying length and
	 * thickness read as a continuous frayed fringe, which is why the counts in
	 * {@link #edgeNicks} are so high. Two strokes per dash — a wide faint one and
	 * a narrow bright one on the same line — feather it, so no mark has a hard
	 * edge of its own.
	 *
	 * <p>Corners are worst, because a corner takes the whole force of a drop on
	 * one point. Rather than a separate corner routine, every mark's length,
	 * thickness and opacity scale by how close it is to a corner, and each corner
	 * additionally gets a soft pale bloom centred exactly on its point — the clip
	 * keeps only the quarter of that bloom inside the card, which is precisely
	 * the shape of a rubbed-round corner.
	 *
	 * <p>Clipped to the card throughout. Marks straddle the boundary on purpose,
	 * so without the clip a card drawn next to another would fray its neighbour.
	 */
	private static void drawWearEdge(Graphics2D g2, Shape clip, int x, int y, int w, int h,
		CardWear wear, int seed) {
		int marks = edgeNicks(wear);
		if (marks == 0 || w <= 0 || h <= 0)
			return;
		Graphics2D ge = layer(g2, clip);
		int alpha = wearAlpha(wear);
		double perimeter = 2.0 * (w + h);
		// deliberately thin. An earlier pass used w/44 and the marks were so fat
		// they merged into an inner glow — a lit border, which is the opposite
		// read. The fringe has to be finer than the frame it is eating.
		float base = Math.max(1f, w / 70f);
		double cornerSpan = Math.min(w, h) * 0.30;
		for (int i = 0; i < marks; i++) {
			int ns = seed + i * 401;
			// walked round the perimeter in even steps with a jittered offset, so
			// the fringe spreads over all four sides instead of clumping on one
			double at = ((i + Paint.hash01(ns + 1) * 0.85) / marks) * perimeter;
			double px;
			double py;
			double tx;
			double ty;
			if (at < w) {
				px = x + at;
				py = y;
				tx = 1;
				ty = 0;
			}
			else if (at < w + h) {
				px = x + w;
				py = y + (at - w);
				tx = 0;
				ty = 1;
			}
			else if (at < 2 * w + h) {
				px = x + w - (at - w - h);
				py = y + h;
				tx = -1;
				ty = 0;
			}
			else {
				px = x;
				py = y + h - (at - 2 * w - h);
				tx = 0;
				ty = -1;
			}
			// The inward normal is the tangent turned a quarter turn, on every one
			// of the four sides — top (1,0) gives (0,1), right (0,1) gives (-1,0),
			// bottom (-1,0) gives (0,-1), left (0,-1) gives (1,0). It was written
			// out per branch, which is four more chances for a side to disagree
			// with its own tangent, and the walk goes clockwise for all four.
			double inx = -ty;
			double iny = tx;
			double corner = Math.min(Math.min(at, Math.abs(at - w)),
				Math.min(Math.abs(at - (w + h)),
					Math.min(Math.abs(at - (2 * w + h)), perimeter - at)));
			double boost = 1.0 + 1.6 * Math.max(0, 1 - corner / cornerSpan);
			float th = (float) (base * (0.55 + 0.65 * Paint.hash01(ns + 3)) * Math.min(1.5, boost));
			int a = (int) (alpha * (0.6 + 0.4 * Paint.hash01(ns + 5)));
			// Roughly a third of the fringe bites INWARD across the rim instead of
			// lying along it. A rim of pure tangential dashes reads as a drawn
			// outline however ragged the lengths are; the perpendicular ones are
			// what break the frame into teeth, and teeth are what a shelf-worn
			// edge actually has.
			boolean bite = Paint.hash01(ns + 6) < 0.34;
			double len;
			double dx;
			double dy;
			double depth;
			if (bite) {
				len = base * (1.6 + 3.4 * Paint.hash01(ns + 2)) * boost;
				dx = inx;
				dy = iny;
				// anchored just outside the rim so the bite starts in the frame and
				// runs in, rather than floating in the middle of the border
				depth = -base * 0.5;
			}
			else {
				len = base * (2.0 + 5.5 * Paint.hash01(ns + 2)) * boost;
				dx = tx;
				dy = ty;
				// a little in or a little out: the ones sitting proud of the rim get
				// halved by the clip, which is what stops the band looking ruled
				depth = base * (Paint.hash01(ns + 4) * 0.9 - 0.3);
			}
			// How far back along its own direction the mark starts. A tangential
			// dash is centred on its anchor, so it steps back half its length; a
			// bite STARTS at the rim and runs inward, so it steps back none. The
			// old spelling subtracted the half-length and then added it straight
			// back for bites, which is the same thing said twice.
			double back = bite ? 0 : len / 2;
			double ax = px + inx * depth - dx * back;
			double ay = py + iny * depth - dy * back;
			double bx = ax + dx * len;
			double by = ay + dy * len;
			// one line, drawn twice: a wide faint stroke feathered under a narrow
			// bright one, so no mark has a hard edge of its own. Graphics2D.draw
			// does not mutate the shape it is handed, so the two passes can share
			// the instance instead of allocating a second identical Line2D.
			Line2D mark = new Line2D.Double(ax, ay, bx, by);
			ge.setStroke(roundStroke(th * 1.9f));
			ge.setColor(alpha(WEAR_STOCK, a / 4));
			ge.draw(mark);
			ge.setStroke(roundStroke(th));
			ge.setColor(alpha(WEAR_STOCK, a));
			ge.draw(mark);
		}
		// The corner blooms, feathered outward-in so they have no rim of their own.
		// Kept small and faint: this is the last touch, a corner gone soft, not a
		// light source. Scaled by the stage through the mark count so a HAIRLINE
		// card gets a hint of it and a SHATTERED one gets a rounded-off corner.
		int d = Math.max(3, (int) (w * (0.035f + 0.045f * marks / 60f)));
		int steps = 5;
		for (int c = 0; c < 4; c++) {
			int cx = (c & 1) == 0 ? x : x + w;
			int cy = (c & 2) == 0 ? y : y + h;
			ge.setColor(alpha(WEAR_STOCK, Math.max(1, alpha / (steps * 6))));
			for (int s = steps; s >= 1; s--) {
				int rd = Math.max(2, d * s / steps);
				ge.fillOval(cx - rd / 2, cy - rd / 2, rd, rd);
			}
		}
		ge.dispose();
	}

	private static Color alpha(Color base, int a) {
		return new Color(base.getRed(), base.getGreen(), base.getBlue(),
			Math.max(0, Math.min(255, a)));
	}

	public static Color prismaticColor(long timeMs, int offsetDeg) {
		float hue = ((timeMs / 22) % 360 + offsetDeg) % 360 / 360f;
		return Color.getHSBColor(hue, 0.65f, 1f);
	}


	private static Font serviceFont(int w) {
		return new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, w / 13));
	}

	/** Left edge of the service badge pill. */
	static int serviceBadgeX(int x, int w) {
		return x + Math.max(3, w / 22);
	}

	/**
	 * Top edge of the service badge pill. Extracted from drawServiceBadge rather
	 * than duplicated because the wear pass measures the protected top band from
	 * it — two copies of this expression would drift and let a crack cross the
	 * pill.
	 */
	static int serviceBadgeY(int y, int h) {
		return y + Math.max(3, h / 26);
	}

	/** Horizontal padding inside the pill, one side. */
	static int serviceBadgePadX(int w) {
		return Math.max(3, w / 24);
	}

	/** Full width of the pill for a badge whose text measured badgeTextWidth. */
	static int serviceBadgeWidth(int w, int badgeTextWidth) {
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
	static int rarityLabelLeft(int x, int w, int badgeTextWidth) {
		if (badgeTextWidth < 0)
			return x + 4;
		return serviceBadgeX(x, w) + serviceBadgeWidth(w, badgeTextWidth) + Math.max(4, w / 20);
	}

	/**
	 * Compact service count: the corner has room for four characters, not six.
	 * Integer math only — no locale, no rounding drift, and only ASCII digits,
	 * '.', 'k' and 'm' come out, so there is no missing-glyph hazard.
	 */
	static String serviceText(int killsServed) {
		if (killsServed < 1000)
			return Integer.toString(killsServed);
		if (killsServed < 10000)
			return killsServed / 1000 + "." + killsServed % 1000 / 100 + "k";
		if (killsServed < 1000000)
			return killsServed / 1000 + "k";
		return killsServed / 1000000 + "m";
	}

	/** Top-left pill: a worn brass service tag. */
	private static void drawServiceBadge(Graphics2D g2, int x, int y, int w, int h, String text) {
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

	private static void drawCenteredString(Graphics2D g, String text, int cx, int cy, int maxWidth) {
		FontMetrics fm = g.getFontMetrics();
		String drawn = text;
		while (fm.stringWidth(drawn) > maxWidth && drawn.length() > 4) {
			drawn = drawn.substring(0, drawn.length() - 2);
		}
		if (!drawn.equals(text)) {
			drawn = drawn.substring(0, Math.max(1, drawn.length() - 1)) + "…";
		}
		g.drawString(drawn, cx - fm.stringWidth(drawn) / 2, cy + fm.getAscent() / 2 - 1);
	}

	/**
	 * Draw centered, shrinking the font (down to minSize) until the whole
	 * text fits; only at the floor does it fall back to ellipsizing.
	 */
	private static void drawFittedString(Graphics2D g, String text, int cx, int cy, int maxWidth,
		Font baseFont, int minSize) {
		Font font = baseFont;
		g.setFont(font);
		while (g.getFontMetrics().stringWidth(text) > maxWidth && font.getSize() > minSize) {
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
	private static BufferedImage cropToOpaqueBounds(BufferedImage image) {
		int minX = image.getWidth();
		int minY = image.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int py = 0; py < image.getHeight(); py++) {
			for (int px = 0; px < image.getWidth(); px++) {
				if ((image.getRGB(px, py) >>> 24) > 8) {
					minX = Math.min(minX, px);
					minY = Math.min(minY, py);
					maxX = Math.max(maxX, px);
					maxY = Math.max(maxY, py);
				}
			}
		}
		if (maxX < 0 || (minX == 0 && minY == 0
			&& maxX == image.getWidth() - 1 && maxY == image.getHeight() - 1)) {
			return image; // fully transparent or already tight
		}
		return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
