package com.gachaman.ui.loadout;

import com.gachaman.model.GachaState;
import com.gachaman.service.GachaStateService;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The loadout toggle drawn inside the worn-equipment page. Each frame it
 * looks up the equipment interface root ({@code InterfaceID.Wornitems.UNIVERSE},
 * group 387 child 0) and, when the page is visible, paints the stone button
 * in the empty space at the top-right of the panel, beside the head-gear
 * slot. {@link #getBounds()} is re-pointed
 * to that exact canvas rect every frame, so the existing click routing
 * (the RUNELITE_OVERLAY "Toggle" menu entry and
 * {@link LoadoutInputListener}'s hit test) keeps working unchanged. When
 * the equipment page is not visible nothing is drawn and the bounds
 * collapse to zero — the button only lives on the equipment page, though a
 * loadout board left open stays open and usable.
 */
@Singleton
public class LoadoutButtonOverlay extends Overlay
{
	public static final String TOGGLE_OPTION = "Toggle";
	public static final String OVERLAY_TARGET = "Gachaman loadout";

	static final int BUTTON_W = 36;
	static final int BUTTON_H = 36;

	/** Gap between the button and the equipment panel's top/right edges. */
	private static final int MARGIN = 6;

	private static final Color STONE_TOP = new Color(66, 58, 46, 245);
	private static final Color STONE_BOTTOM = new Color(38, 33, 26, 245);
	private static final Color STONE_PRESSED_TOP = new Color(30, 26, 20, 245);
	private static final Color STONE_PRESSED_BOTTOM = new Color(48, 42, 33, 245);
	private static final Color BEVEL_LIGHT = new Color(120, 108, 86, 160);
	private static final Color BEVEL_DARK = new Color(14, 12, 9, 200);
	private static final Color EDGE = new Color(90, 78, 58);
	private static final Color HOVER_WASH = new Color(255, 244, 200, 34);
	private static final Color CARD_A = new Color(58, 34, 92);
	private static final Color CARD_EDGE = new Color(212, 175, 55, 210);
	private static final Color BADGE_TEXT = new Color(40, 30, 8);
	private static final int DEED_R = 255;
	private static final int DEED_G = 200;
	private static final int DEED_B = 60;

	private static final BasicStroke STROKE_EDGE = new BasicStroke(1.2f);
	private static final BasicStroke STROKE_BEVEL = new BasicStroke(1f);
	private static final Font BADGE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);
	private static final String[] BADGE_COUNTS =
		{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

	// stone-body gradients in local (post-translate) coordinates
	private static final GradientPaint STONE_PAINT =
		new GradientPaint(0, 1, STONE_TOP, 0, BUTTON_H - 1, STONE_BOTTOM);
	private static final GradientPaint STONE_PAINT_PRESSED =
		new GradientPaint(0, 1, STONE_PRESSED_TOP, 0, BUTTON_H - 1, STONE_PRESSED_BOTTOM);

	private final Client client;
	private final GachaStateService stateService;
	private final LoadoutOverlay loadoutOverlay;

	/**
	 * The button's live canvas rect, owned by us. The overlay renderer resets
	 * a DYNAMIC overlay's bounds around every frame, so relying on the
	 * inherited bounds object breaks both hit-testing and (with clipping)
	 * rendering — we keep our own rect and hand out defensive copies.
	 */
	private final Rectangle buttonRect = new Rectangle();

	// --- self-diagnostics (read by the ::gachabutton debug command) ---
	private volatile long lastRenderMs;
	private volatile String lastExitReason = "never rendered";

	@Inject
	public LoadoutButtonOverlay(Client client, GachaStateService stateService,
		LoadoutOverlay loadoutOverlay)
	{
		this.client = client;
		this.stateService = stateService;
		this.loadoutOverlay = loadoutOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		// ALWAYS_ON_TOP: ABOVE_WIDGETS is clipped away over the side-panel
		// region (diagnostics proved the draw executed at correct coords with
		// zero visible pixels there); this layer renders unclipped everywhere
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_MED);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, TOGGLE_OPTION, OVERLAY_TARGET);
	}

	@Override
	public Rectangle getBounds()
	{
		// copy: the renderer mutates whatever this returns
		return new Rectangle(buttonRect);
	}

	/** One-line diagnostics for the ::gachabutton debug command. */
	public String diagnostics()
	{
		long age = lastRenderMs == 0 ? -1 : System.currentTimeMillis() - lastRenderMs;
		return (age < 0 ? "render NEVER called" : "last render " + age + "ms ago")
			+ " | " + lastExitReason + " | rect " + buttonRect.x + "," + buttonRect.y
			+ " " + buttonRect.width + "x" + buttonRect.height;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		lastRenderMs = System.currentTimeMillis();
		GachaState state = stateService.get();
		if (state == null)
		{
			lastExitReason = "state not loaded";
			buttonRect.setBounds(0, 0, 0, 0);
			return null;
		}

		// The equipment widgets can EXIST with garbage bounds outside the
		// actual game view (seen live: the button ghosted onto the welcome
		// screen). Only trust them when the panel root and the head slot BOTH
		// report real bounds that agree with each other.
		Widget rootWidget = client.getWidget(InterfaceID.Wornitems.UNIVERSE);
		Widget slotWidget = client.getWidget(InterfaceID.Wornitems.SLOT0);
		if (rootWidget == null || rootWidget.isHidden()
			|| slotWidget == null || slotWidget.isHidden())
		{
			lastExitReason = "equipment page not visible";
			buttonRect.setBounds(0, 0, 0, 0);
			return null;
		}
		Rectangle root = rootWidget.getBounds();
		Rectangle slot = slotWidget.getBounds();
		if (root == null || root.width < 100 || root.height < 100
			|| slot == null || slot.width <= 0 || slot.height <= 0
			|| !root.contains(slot.x + slot.width / 2, slot.y + slot.height / 2))
		{
			lastExitReason = "equipment widget bounds insane (root " + root
				+ ", slot " + slot + ")";
			buttonRect.setBounds(0, 0, 0, 0);
			return null;
		}

		// top-right corner inside the equipment panel, beside the head slot
		int x = root.x + root.width - BUTTON_W - MARGIN;
		int y = root.y + MARGIN;
		x = Math.max(2, Math.min(x, client.getCanvasWidth() - BUTTON_W - 2));
		y = Math.max(2, Math.min(y, client.getCanvasHeight() - BUTTON_H - 2));
		lastExitReason = "drawn at " + x + "," + y;
		buttonRect.setBounds(x, y, BUTTON_W, BUTTON_H);

		boolean pressed = loadoutOverlay.isOpen();
		Point mouse = client.getMouseCanvasPosition();
		boolean hovered = mouse != null
			&& mouse.getX() >= x && mouse.getX() < x + BUTTON_W
			&& mouse.getY() >= y && mouse.getY() < y + BUTTON_H;

		// draw at absolute canvas coordinates (the pattern every working
		// overlay in this plugin uses) — no reliance on renderer translation
		Graphics2D g = (Graphics2D) graphics.create();
		g.translate(x, y);
		drawButton(g, state, pressed, hovered);
		g.dispose();
		return null;
	}

	/** Draws the stone in local coordinates (graphics already translated). */
	private void drawButton(Graphics2D graphics, GachaState state, boolean pressed, boolean hovered)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// stone body with subtle bevel, native side-stone styling
		g.setPaint(pressed ? STONE_PAINT_PRESSED : STONE_PAINT);
		g.fillRoundRect(1, 1, BUTTON_W - 2, BUTTON_H - 2, 6, 6);
		g.setStroke(STROKE_BEVEL);
		g.setColor(pressed ? BEVEL_DARK : BEVEL_LIGHT);
		g.drawLine(3, 2, BUTTON_W - 4, 2);
		g.drawLine(2, 3, 2, BUTTON_H - 4);
		g.setColor(pressed ? BEVEL_LIGHT : BEVEL_DARK);
		g.drawLine(3, BUTTON_H - 3, BUTTON_W - 4, BUTTON_H - 3);
		g.drawLine(BUTTON_W - 3, 3, BUTTON_W - 3, BUTTON_H - 4);
		g.setColor(EDGE);
		g.setStroke(STROKE_EDGE);
		g.drawRoundRect(1, 1, BUTTON_W - 3, BUTTON_H - 3, 6, 6);

		// card-in-socket glyph, slightly tilted; sinks 1px when pressed
		int cx = BUTTON_W / 2;
		int cy = BUTTON_H / 2 + (pressed ? 1 : 0);
		g.rotate(Math.toRadians(-12), cx, cy);
		g.setColor(CARD_A);
		g.fillRoundRect(cx - 6, cy - 9, 12, 18, 3, 3);
		g.setColor(CARD_EDGE);
		g.setStroke(STROKE_EDGE);
		g.drawRoundRect(cx - 6, cy - 9, 12, 18, 3, 3);
		g.rotate(Math.toRadians(12), cx, cy);

		if (hovered && !pressed)
		{
			g.setColor(HOVER_WASH);
			g.fillRoundRect(1, 1, BUTTON_W - 2, BUTTON_H - 2, 6, 6);
		}

		// pulsing deed badge when a deed is waiting to be claimed
		if (state.getPendingDeeds() > 0)
		{
			float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 220.0));
			g.setColor(new Color(DEED_R, DEED_G, DEED_B, (int) (120 + 135 * pulse)));
			g.fillOval(BUTTON_W - 11, 0, 11, 11);
			g.setColor(BADGE_TEXT);
			g.setFont(BADGE_FONT);
			g.drawString(BADGE_COUNTS[Math.min(9, state.getPendingDeeds())], BUTTON_W - 8, 9);
		}

		g.dispose();
	}
}
