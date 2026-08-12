package com.gachaman.ui.loadout;

import net.runelite.api.SpriteID;
import net.runelite.api.gameval.InterfaceID;
import com.gachaman.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.*;
import net.runelite.client.eventbus.*;

/**
 * The loadout toggle as a REAL game widget: a child created on the worn-
 * equipment interface itself (the same technique core plugins use to add
 * buttons to game interfaces). It renders as part of the interface — no
 * overlay layers, no clipping, native left-click "Toggle" menu op — and
 * lives/dies with the equipment page automatically.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LoadoutTabButton {
	/** Worn-equipment interface group (387). */
	private static final int WORNITEMS_GROUP = InterfaceID.Wornitems.UNIVERSE >> 16;
	/** Icon: the Training sword item sprite (a starter card, fittingly). */
	private static final int ICON_ITEM_ID = 9703;
	private static final int SIZE = 32;
	private static final int MARGIN = 4;

	private final Client client;
	private final ClientThread clientThread;
	private final LoadoutOverlay loadoutOverlay;
	private final GachamanConfig config;

	private Widget tile;
	private Widget button;
	private Widget buttonParent;

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == WORNITEMS_GROUP) {
			// interface (re)built: any previous children are gone with it
			tile = null;
			button = null;
			buttonParent = null;
			clientThread.invokeLater(this::create);
		}
	}

	/** Idempotent; call on startUp too in case the interface is already loaded. */
	public void create() {
		if (!config.oneCardPerSlot()) {
			return; // loadout system disabled — no button on the equipment page
		}
		Widget parent = client.getWidget(InterfaceID.Wornitems.UNIVERSE);
		if (parent == null) {
			return;
		}
		if (button != null && buttonParent == parent) {
			return; // already attached to this incarnation of the interface
		}
		try {
			int tileSize = 36;
			int x = Math.max(0, parent.getWidth() - tileSize - MARGIN);
			int y = MARGIN;

			// the same beveled square the real equipment slots use
			Widget bg = parent.createChild(-1, WidgetType.GRAPHIC);
			bg.setSpriteId(SpriteID.EQUIPMENT_SLOT_TILE);
			bg.setOriginalWidth(tileSize);
			bg.setOriginalHeight(tileSize);
			bg.setOriginalX(x);
			bg.setOriginalY(y);
			bg.setName("<col=b25be2>Gachaman loadout</col>");
			bg.setHasListener(true);
			bg.setAction(0, "Toggle");
			bg.setOnOpListener((JavaScriptCallback) ev -> loadoutOverlay.toggle());
			bg.revalidate();

			Widget w = parent.createChild(-1, WidgetType.GRAPHIC);
			w.setItemId(ICON_ITEM_ID);
			w.setItemQuantity(10000);
			w.setItemQuantityMode(ItemQuantityMode.NEVER);
			w.setOriginalWidth(SIZE);
			w.setOriginalHeight(SIZE);
			w.setOriginalX(x + (tileSize - SIZE) / 2);
			w.setOriginalY(y + (tileSize - SIZE) / 2);
			w.setName("<col=b25be2>Gachaman loadout</col>");
			w.setHasListener(true);
			w.setAction(0, "Toggle");
			w.setOnOpListener((JavaScriptCallback) ev -> loadoutOverlay.toggle());
			w.revalidate();

			tile = bg;
			button = w;
			buttonParent = parent;
			log.debug("Gachaman loadout widget button attached to equipment page");
		}
		catch (Exception e) {
			log.warn("Failed to attach loadout button widget", e);
		}
	}

	/** shutDown cleanup (client thread): hide our children so nothing lingers. */
	public void remove() {
		for (Widget w : new Widget[]{button, tile}) {
			if (w != null) {
				try {
					w.setHidden(true);
				}
				catch (Exception e) {
					log.debug("loadout button widget already gone", e);
				}
			}
		}
		tile = null;
		button = null;
		buttonParent = null;
	}
}
