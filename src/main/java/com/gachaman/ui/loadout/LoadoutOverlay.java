package com.gachaman.ui.loadout;

import com.gachaman.GachamanConfig;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import com.gachaman.service.ChestService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.LoadoutService;
import com.gachaman.ui.CardImageService;
import com.gachaman.ui.CardRenderer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.SpriteID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The loadout board: an equipment-panel-shaped grid of the eleven GearSlot
 * sockets, drawn next to the inventory when toggled on. Styled to match the
 * native worn-equipment interface: each socket is the real equipment slot
 * tile sprite, empty deeded sockets show the native slot silhouette dimmed
 * with a gold add badge, undeeded sockets show the tile darkened under a
 * padlock (claimable when a Deed is pending), and assigned sockets show the
 * card's item sprite with a rarity/variant inner outline. Clicks arrive from
 * {@link LoadoutInputListener}.
 */
@Singleton
public class LoadoutOverlay extends Overlay
{
	static final int BOARD_W = 180;
	static final int BOARD_H = 260;
	private static final int SOCKET = 36;
	/** Horizontal gap kept between the board and the right canvas edge. */
	private static final int RIGHT_OFFSET = 260;
	private static final int BOTTOM_OFFSET = 30;

	// native equipment-panel chrome
	private static final Color PANEL_TOP = new Color(62, 53, 41);
	private static final Color PANEL_BOTTOM = new Color(51, 43, 33);
	private static final Color BORDER_OUTER = new Color(26, 22, 17);
	private static final Color BORDER_INNER = new Color(90, 78, 60);
	/** The orange OSRS interface title colour. */
	private static final Color TITLE_GOLD = new Color(255, 152, 31);
	private static final GradientPaint PANEL_GRADIENT =
		new GradientPaint(0, 0, PANEL_TOP, 0, BOARD_H, PANEL_BOTTOM);

	// procedural socket fallback, only until the tile sprite loads
	private static final Color SOCKET_BG = new Color(34, 30, 24);
	private static final Color SOCKET_BG_LOCKED = new Color(26, 23, 19);
	private static final Color SOCKET_EDGE = new Color(70, 62, 48);
	private static final Color HOVER_EDGE = new Color(255, 244, 200);
	private static final Color PLUS_COLOR = new Color(150, 140, 110);
	private static final Color LOCK_COLOR = new Color(110, 100, 80);
	private static final Color HOLOGRAM_EDGE = new Color(120, 220, 255);
	private static final Color DEED_RIBBON = new Color(255, 200, 60);
	private static final Color BADGE_PLUS = new Color(45, 33, 8);

	private static final BasicStroke STROKE_1 = new BasicStroke(1f);
	private static final BasicStroke STROKE_2 = new BasicStroke(2f);
	/** Empty-slot silhouettes are dimmed to ~55% alpha on the tile. */
	private static final AlphaComposite SILHOUETTE_COMPOSITE =
		AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f);
	/** Multiply factor applied to the tile under undeeded (locked) sockets. */
	private static final float LOCKED_TILE_FACTOR = 0.5f;
	/** Multiply factor applied to the tile under a hovered socket. */
	private static final float HOVER_TILE_FACTOR = 1.3f;

	private final Client client;
	private final ClientThread clientThread;
	private final GachaStateService stateService;
	private final LoadoutService loadoutService;
	private final ChestService chestService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;
	private final ChatboxCardSearch cardSearch;
	private final SpriteManager spriteManager;
	private final GachamanConfig config;

	private final Map<GearSlot, Rectangle> socketRects = new EnumMap<>(GearSlot.class);

	/** Raw sprites by sprite id; entries appear once SpriteManager has them. */
	private final Map<Integer, BufferedImage> spriteCache = new HashMap<>();
	/** Slot silhouettes by sprite id, pre-fit to the socket if oversized. */
	private final Map<Integer, BufferedImage> silhouetteCache = new HashMap<>();

	// tile variants baked once from the real EQUIPMENT_SLOT_TILE sprite
	@Nullable
	private BufferedImage tileBase;
	@Nullable
	private BufferedImage tileDark;
	@Nullable
	private BufferedImage tileHover;
	@Nullable
	private BufferedImage tileDarkHover;

	private final Font titleFont = FontManager.getRunescapeBoldFont();
	private final Font ribbonFont = FontManager.getRunescapeSmallFont();

	@Getter
	private volatile boolean open;
	@Nullable
	private volatile Point hoverCanvasPoint;

	@Inject
	public LoadoutOverlay(Client client, ClientThread clientThread, GachaStateService stateService,
		LoadoutService loadoutService, ChestService chestService, CardDatabase cardDatabase,
		CardImageService cardImageService, ChatboxCardSearch cardSearch, SpriteManager spriteManager,
		GachamanConfig config)
	{
		this.config = config;
		this.client = client;
		this.clientThread = clientThread;
		this.stateService = stateService;
		this.loadoutService = loadoutService;
		this.chestService = chestService;
		this.cardDatabase = cardDatabase;
		this.cardImageService = cardImageService;
		this.cardSearch = cardSearch;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		// same clipping issue as the button: must render over the side-panel region
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_MED);
		setMovable(true);

		// classic equipment tab arrangement
		int left = 20;
		int mid = (BOARD_W - SOCKET) / 2;
		int right = BOARD_W - SOCKET - 20;
		socketRects.put(GearSlot.HEAD, socket(mid, 28));
		socketRects.put(GearSlot.CAPE, socket(left, 72));
		socketRects.put(GearSlot.AMULET, socket(mid, 72));
		socketRects.put(GearSlot.AMMO, socket(right, 72));
		socketRects.put(GearSlot.WEAPON, socket(left, 116));
		socketRects.put(GearSlot.BODY, socket(mid, 116));
		socketRects.put(GearSlot.SHIELD, socket(right, 116));
		socketRects.put(GearSlot.LEGS, socket(mid, 160));
		socketRects.put(GearSlot.HANDS, socket(left, 204));
		socketRects.put(GearSlot.FEET, socket(mid, 204));
		socketRects.put(GearSlot.RING, socket(right, 204));
	}

	private static Rectangle socket(int x, int y)
	{
		return new Rectangle(x, y, SOCKET, SOCKET);
	}

	public void toggle()
	{
		open = !open;
	}

	public void setOpen(boolean open)
	{
		this.open = open;
	}

	/** Canvas-space hover position fed by the input listener (never consumed). */
	public void setHoverCanvasPoint(@Nullable Point point)
	{
		hoverCanvasPoint = point;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GachaState state = stateService.get();
		if (!open || state == null || !config.oneCardPerSlot())
		{
			open = false;
			return null;
		}
		// the board belongs to the equipment page: switching tabs closes it
		Widget equipment =
			client.getWidget(InterfaceID.Wornitems.UNIVERSE);
		if (equipment == null || equipment.isHidden())
		{
			open = false;
			return null;
		}

		// Anchor next to the inventory until the user drags us somewhere.
		if (getPreferredLocation() == null)
		{
			int ax = Math.max(0, client.getCanvasWidth() - BOARD_W - RIGHT_OFFSET);
			int ay = Math.max(0, client.getCanvasHeight() - BOARD_H - BOTTOM_OFFSET);
			Rectangle bounds = getBounds();
			if (bounds.x != ax || bounds.y != ay)
			{
				// graphics is already translated to the stale location; skip
				// one frame so the renderer picks the new anchor up.
				bounds.setLocation(ax, ay);
				return new Dimension(BOARD_W, BOARD_H);
			}
		}

		long now = System.currentTimeMillis();
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// panel: equipment-tab brown-grey with a subtle vertical gradient,
		// square corners, 2px double border (dark outer, highlight inner)
		g.setPaint(PANEL_GRADIENT);
		g.fillRect(1, 1, BOARD_W - 2, BOARD_H - 2);
		g.setStroke(STROKE_1);
		g.setColor(BORDER_OUTER);
		g.drawRect(0, 0, BOARD_W - 1, BOARD_H - 1);
		g.setColor(BORDER_INNER);
		g.drawRect(1, 1, BOARD_W - 3, BOARD_H - 3);

		g.setColor(TITLE_GOLD);
		g.setFont(titleFont);
		drawCentered(g, "Loadout", BOARD_W / 2, 20);

		GearSlot hovered = hoveredSlot();
		Map<String, OwnedCard> byUuid = ownedByUuid(state);
		for (GearSlot slot : GearSlot.values())
		{
			drawSocket(g, slot, state, byUuid, hovered == slot, now);
		}

		if (state.getPendingDeeds() > 0)
		{
			float pulse = (float) (0.5 + 0.5 * Math.sin(now / 240.0));
			g.setColor(new Color(DEED_RIBBON.getRed(), DEED_RIBBON.getGreen(), DEED_RIBBON.getBlue(),
				(int) (110 + 145 * pulse)));
			g.fillRoundRect(14, BOARD_H - 18, BOARD_W - 28, 14, 7, 7);
			g.setColor(BADGE_PLUS);
			g.setFont(ribbonFont);
			drawCentered(g, "DEED AVAILABLE", BOARD_W / 2, BOARD_H - 7);
		}

		g.dispose();
		return new Dimension(BOARD_W, BOARD_H);
	}

	private void drawSocket(Graphics2D g, GearSlot slot, GachaState state,
		Map<String, OwnedCard> byUuid, boolean hovered, long now)
	{
		Rectangle r = socketRects.get(slot);
		boolean deeded = state.getDeededSlots().contains(slot.name());
		OwnedCard assigned = assignedCard(state, byUuid, slot);
		boolean claimable = !deeded && state.getPendingDeeds() > 0;

		BufferedImage tile = tileImage(!deeded, hovered);
		if (tile != null)
		{
			g.drawImage(tile, r.x, r.y, null);
		}
		else
		{
			// procedural fallback until the tile sprite has loaded
			g.setColor(deeded ? SOCKET_BG : SOCKET_BG_LOCKED);
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(hovered ? HOVER_EDGE : SOCKET_EDGE);
			g.setStroke(STROKE_1);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		}

		if (assigned != null)
		{
			BufferedImage sprite = spriteFor(assigned);
			if (sprite != null)
			{
				g.drawImage(sprite,
					r.x + (r.width - sprite.getWidth()) / 2,
					r.y + (r.height - sprite.getHeight()) / 2, null);
			}
			// rarity/variant as a 1px inner outline on the tile
			g.setColor(borderColorFor(assigned, now));
			g.setStroke(STROKE_1);
			g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
		}
		else if (deeded)
		{
			BufferedImage silhouette = silhouetteImage(slot);
			if (silhouette != null)
			{
				Composite prev = g.getComposite();
				g.setComposite(SILHOUETTE_COMPOSITE);
				g.drawImage(silhouette,
					r.x + (r.width - silhouette.getWidth()) / 2,
					r.y + (r.height - silhouette.getHeight()) / 2, null);
				g.setComposite(prev);
			}
			else
			{
				// silhouette still loading: the old centred plus glyph
				g.setColor(PLUS_COLOR);
				g.setStroke(STROKE_2);
				int cx = r.x + r.width / 2;
				int cy = r.y + r.height / 2;
				g.drawLine(cx - 6, cy, cx + 6, cy);
				g.drawLine(cx, cy - 6, cx, cy + 6);
			}
			drawPlusBadge(g, r);
		}
		else
		{
			drawPadlock(g, r, claimable);
			if (claimable)
			{
				float pulse = (float) (0.5 + 0.5 * Math.sin(now / 240.0));
				g.setColor(new Color(DEED_RIBBON.getRed(), DEED_RIBBON.getGreen(),
					DEED_RIBBON.getBlue(), (int) (90 + 160 * pulse)));
				g.setStroke(STROKE_1);
				g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
			}
		}
	}

	/** Small gold add badge in the socket corner: assignment stays obvious. */
	private void drawPlusBadge(Graphics2D g, Rectangle r)
	{
		int size = 11;
		int bx = r.x + r.width - size - 2;
		int by = r.y + r.height - size - 2;
		g.setColor(DEED_RIBBON);
		g.fillOval(bx, by, size, size);
		g.setColor(BADGE_PLUS);
		g.setStroke(STROKE_1);
		int cx = bx + size / 2;
		int cy = by + size / 2;
		g.drawLine(cx - 2, cy, cx + 2, cy);
		g.drawLine(cx, cy - 2, cx, cy + 2);
	}

	private void drawPadlock(Graphics2D g, Rectangle r, boolean claimable)
	{
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		g.setColor(claimable ? DEED_RIBBON : LOCK_COLOR);
		g.setStroke(STROKE_2);
		// shackle
		g.drawArc(cx - 5, cy - 9, 10, 10, 0, 180);
		// body
		g.fillRoundRect(cx - 7, cy - 3, 14, 11, 3, 3);
	}

	// --- native sprites ---

	/**
	 * The equipment slot tile in the variant matching the socket state, or
	 * null until the sprite has loaded. All four variants are baked once.
	 */
	@Nullable
	private BufferedImage tileImage(boolean locked, boolean hovered)
	{
		if (tileBase == null)
		{
			BufferedImage raw = sprite(SpriteID.EQUIPMENT_SLOT_TILE);
			if (raw == null)
			{
				return null;
			}
			tileBase = scaled(raw, SOCKET, SOCKET);
			tileDark = tinted(tileBase, LOCKED_TILE_FACTOR);
			tileHover = tinted(tileBase, HOVER_TILE_FACTOR);
			tileDarkHover = tinted(tileDark, HOVER_TILE_FACTOR);
		}
		if (locked)
		{
			return hovered ? tileDarkHover : tileDark;
		}
		return hovered ? tileHover : tileBase;
	}

	/** The native slot silhouette, fit to the socket; null until loaded. */
	@Nullable
	private BufferedImage silhouetteImage(GearSlot slot)
	{
		int spriteId = silhouetteSpriteId(slot);
		BufferedImage prepared = silhouetteCache.get(spriteId);
		if (prepared != null)
		{
			return prepared;
		}
		BufferedImage raw = sprite(spriteId);
		if (raw == null)
		{
			return null;
		}
		if (raw.getWidth() > SOCKET || raw.getHeight() > SOCKET)
		{
			raw = scaled(raw, Math.min(raw.getWidth(), SOCKET), Math.min(raw.getHeight(), SOCKET));
		}
		silhouetteCache.put(spriteId, raw);
		return raw;
	}

	/** Raw sprite via SpriteManager; null (and retried) while still loading. */
	@Nullable
	private BufferedImage sprite(int spriteId)
	{
		BufferedImage img = spriteCache.get(spriteId);
		if (img == null)
		{
			img = spriteManager.getSprite(spriteId, 0);
			if (img != null)
			{
				spriteCache.put(spriteId, img);
			}
		}
		return img;
	}

	private static int silhouetteSpriteId(GearSlot slot)
	{
		switch (slot)
		{
			case HEAD:
				return SpriteID.EQUIPMENT_SLOT_HEAD;
			case CAPE:
				return SpriteID.EQUIPMENT_SLOT_CAPE;
			case AMULET:
				return SpriteID.EQUIPMENT_SLOT_NECK;
			case WEAPON:
				return SpriteID.EQUIPMENT_SLOT_WEAPON;
			case BODY:
				return SpriteID.EQUIPMENT_SLOT_TORSO;
			case SHIELD:
				return SpriteID.EQUIPMENT_SLOT_SHIELD;
			case LEGS:
				return SpriteID.EQUIPMENT_SLOT_LEGS;
			case HANDS:
				return SpriteID.EQUIPMENT_SLOT_HANDS;
			case FEET:
				return SpriteID.EQUIPMENT_SLOT_FEET;
			case RING:
				return SpriteID.EQUIPMENT_SLOT_RING;
			case AMMO:
			default:
				return SpriteID.EQUIPMENT_SLOT_AMMUNITION;
		}
	}

	/** Nearest-neighbour scale into a fresh ARGB image (bake-time only). */
	private static BufferedImage scaled(BufferedImage src, int w, int h)
	{
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	/** Multiplies RGB by factor (clamped), preserving alpha (bake-time only). */
	private static BufferedImage tinted(BufferedImage src, float factor)
	{
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
		for (int i = 0; i < pixels.length; i++)
		{
			int argb = pixels[i];
			int red = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
			int green = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
			int blue = Math.min(255, (int) ((argb & 0xFF) * factor));
			pixels[i] = (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
		}
		out.setRGB(0, 0, w, h, pixels, 0, w);
		return out;
	}

	@Nullable
	private BufferedImage spriteFor(OwnedCard owned)
	{
		Integer itemId = iconItemIdFor(owned);
		return itemId == null ? null : cardImageService.itemImage(itemId, null);
	}

	@Nullable
	private Integer iconItemIdFor(OwnedCard owned)
	{
		if (!cardDatabase.isReady())
		{
			return null;
		}
		if (owned.isHologram())
		{
			HologramDefinition holo = cardDatabase.holograms().get(owned.getTierKey());
			CardDefinition rep = holo == null ? null
				: cardDatabase.cardByName(holo.getRepresentativeItemName());
			return rep == null ? null : rep.getCardId();
		}
		CardDefinition card = cardDatabase.card(owned.getCardId());
		return card == null ? null : card.getCardId();
	}

	private Color borderColorFor(OwnedCard owned, long now)
	{
		if (owned.getVariant() == Variant.SHINY)
		{
			return CardRenderer.prismaticColor(now, 0);
		}
		if (owned.isHologram())
		{
			return HOLOGRAM_EDGE;
		}
		Rarity rarity = Rarity.COMMON;
		CardDefinition card = cardDatabase.isReady() ? cardDatabase.card(owned.getCardId()) : null;
		if (card != null)
		{
			rarity = card.getRarity();
		}
		return rarity.getColor();
	}

	// --- Click handling (called by LoadoutInputListener, AWT thread) ---

	/** True when the canvas point is inside the visible board. */
	public boolean containsCanvasPoint(Point canvasPoint)
	{
		if (!open || canvasPoint == null)
		{
			return false;
		}
		Rectangle bounds = getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0
			&& bounds.contains(canvasPoint);
	}

	/** Handle a left click at the given canvas point; hops to the client thread. */
	public void handleClick(Point canvasPoint)
	{
		if (!containsCanvasPoint(canvasPoint))
		{
			return;
		}
		Rectangle bounds = getBounds();
		final Point rel = new Point(canvasPoint.x - bounds.x, canvasPoint.y - bounds.y);
		clientThread.invokeLater(() -> handleClickOnClientThread(rel));
	}

	private void handleClickOnClientThread(Point rel)
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return;
		}
		GearSlot slot = slotAt(rel);
		if (slot == null)
		{
			return;
		}
		boolean deeded = state.getDeededSlots().contains(slot.name());
		if (!deeded)
		{
			if (state.getPendingDeeds() > 0)
			{
				chestService.claimDeed(slot);
			}
			return;
		}
		OwnedCard assigned = assignedCard(state, ownedByUuid(state), slot);
		if (assigned != null)
		{
			loadoutService.unassign(slot);
		}
		else
		{
			cardSearch.openFor(slot);
		}
	}

	@Nullable
	private GearSlot slotAt(Point rel)
	{
		for (Map.Entry<GearSlot, Rectangle> entry : socketRects.entrySet())
		{
			if (entry.getValue().contains(rel))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	@Nullable
	private GearSlot hoveredSlot()
	{
		Point canvas = hoverCanvasPoint;
		if (canvas == null || !containsCanvasPoint(canvas))
		{
			return null;
		}
		Rectangle bounds = getBounds();
		return slotAt(new Point(canvas.x - bounds.x, canvas.y - bounds.y));
	}

	// --- helpers ---

	private static Map<String, OwnedCard> ownedByUuid(GachaState state)
	{
		Map<String, OwnedCard> byUuid = new HashMap<>();
		if (state.getOwnedCards() != null)
		{
			for (OwnedCard card : state.getOwnedCards())
			{
				byUuid.put(card.getUuid(), card);
			}
		}
		return byUuid;
	}

	@Nullable
	private static OwnedCard assignedCard(GachaState state, Map<String, OwnedCard> byUuid, GearSlot slot)
	{
		String uuid = state.getLoadout() == null ? null : state.getLoadout().get(slot.name());
		return uuid == null ? null : byUuid.get(uuid);
	}

	private static void drawCentered(Graphics2D g, String text, int cx, int baselineY)
	{
		FontMetrics fm = g.getFontMetrics();
		g.drawString(text, cx - fm.stringWidth(text) / 2, baselineY);
	}
}
