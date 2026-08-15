package com.gachaman.overlay;

import com.gachaman.model.*;
import com.gachaman.service.*;
import java.awt.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.overlay.*;

/**
 * Crosses out the equipment-tab slots no Deed has opened yet, so the lock is
 * visible where the player actually looks for it rather than only in the
 * loadout board.
 *
 * <p>Drawn over EMPTY slots too, which is why this cannot be a
 * {@link net.runelite.client.ui.overlay.WidgetItemOverlay} like the forbidden-item
 * marker: that renders per item, and an undeeded slot is usually bare.
 */
@Singleton
public class SlotLockOverlay extends Overlay {
	/**
	 * Slot widget per {@link GearSlot}, in declaration order. The ids are NOT
	 * contiguous — the equipment interface skips 6, 8 and 11 — so this is a
	 * table rather than arithmetic on the slot index.
	 */
	private static final int[] SLOT_WIDGETS = {
		InterfaceID.Wornitems.SLOT0,   // HEAD
		InterfaceID.Wornitems.SLOT1,   // CAPE
		InterfaceID.Wornitems.SLOT2,   // AMULET
		InterfaceID.Wornitems.SLOT3,   // WEAPON
		InterfaceID.Wornitems.SLOT4,   // BODY
		InterfaceID.Wornitems.SLOT5,   // SHIELD
		InterfaceID.Wornitems.SLOT7,   // LEGS
		InterfaceID.Wornitems.SLOT9,   // HANDS
		InterfaceID.Wornitems.SLOT10,  // FEET
		InterfaceID.Wornitems.SLOT12,  // RING
		InterfaceID.Wornitems.SLOT13,  // AMMO
	};

	private static final Color SCRIM = new Color(0, 0, 0, 120);
	private static final Color CROSS = new Color(200, 60, 50, 210);
	private static final BasicStroke CROSS_STROKE = new BasicStroke(2f);
	private static final int INSET = 4;

	private final Client client;
	private final PermissionService permissionService;
	private final GachaStateService stateService;

	@Inject
	public SlotLockOverlay(Client client, PermissionService permissionService,
		GachaStateService stateService) {
		this.client = client;
		this.permissionService = permissionService;
		this.stateService = stateService;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g) {
		// Fail open, exactly as the permission check does: an unloaded save has
		// an EMPTY deeded set, which would read as "every slot is locked" and
		// flash eleven crosses across the tab on every login.
		if (stateService.get() == null)
			return null;
		// The equipment widgets can EXIST with garbage bounds outside the game
		// view, so trust them only when the panel root and the head slot agree —
		// the same guard the loadout button uses, for the same ghosting reason.
		Widget root = client.getWidget(InterfaceID.Wornitems.UNIVERSE);
		if (root == null || root.isHidden())
			return null;
		Rectangle rootBounds = root.getBounds();
		if (rootBounds == null || rootBounds.width < 100 || rootBounds.height < 100)
			return null;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (GearSlot slot : GearSlot.values()) {
			if (permissionService.isSlotDeeded(slot))
				continue;
			Widget widget = client.getWidget(SLOT_WIDGETS[slot.ordinal()]);
			if (widget == null || widget.isHidden())
				continue;
			Rectangle b = widget.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0
				|| !rootBounds.contains(b.x + b.width / 2, b.y + b.height / 2)) {
				continue; // stale or off-panel bounds — draw nothing rather than ghost
			}
			g.setColor(SCRIM);
			g.fillRect(b.x, b.y, b.width, b.height);
			g.setColor(CROSS);
			g.setStroke(CROSS_STROKE);
			int x1 = b.x + INSET;
			int y1 = b.y + INSET;
			int x2 = b.x + b.width - INSET;
			int y2 = b.y + b.height - INSET;
			g.drawLine(x1, y1, x2, y2);
			g.drawLine(x1, y2, x2, y1);
		}
		return null;
	}
}
