package com.gachaman.service;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;

/**
 * The clean-slate strip: everything Tutorial Island hands you is gear no card
 * has unlocked yet, so stepping off the island takes it all off. Without this a
 * fresh account starts the gamemode wearing items it could never re-equip.
 *
 * <p>The op is discovered, not hardcoded: for each occupied equipment slot the
 * worn-items widget's own action list is searched for "Remove" and that op
 * index is invoked, so a client-side renumbering degrades to doing nothing
 * rather than firing the wrong action.
 *
 * <p>One item per tick, with a bounded budget. Removing gear needs free
 * inventory space, so a full inventory stops the attempt instead of retrying
 * against a server that keeps refusing.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class UnequipService {
	/**
	 * Worn-items component per equipment slot index. The gaps (6, 8, 11) are
	 * indices the equipment container does not use.
	 */
	private static final int[] SLOT_COMPONENTS = {
		InterfaceID.Wornitems.SLOT0,  // head
		InterfaceID.Wornitems.SLOT1,  // cape
		InterfaceID.Wornitems.SLOT2,  // amulet
		InterfaceID.Wornitems.SLOT3,  // weapon
		InterfaceID.Wornitems.SLOT4,  // body
		InterfaceID.Wornitems.SLOT5,  // shield
		-1,
		InterfaceID.Wornitems.SLOT7,  // legs
		-1,
		InterfaceID.Wornitems.SLOT9,  // hands
		InterfaceID.Wornitems.SLOT10, // feet
		-1,
		InterfaceID.Wornitems.SLOT12, // ring
		InterfaceID.Wornitems.SLOT13, // ammo
	};

	private static final int INVENTORY_SIZE = 28;

	/** Generous enough for a full 11 slots plus retries, short enough to give up. */
	private static final int MAX_TICKS = 40;

	private final Client client;

	private int ticksLeft;
	@Getter
	private boolean stripComplete;

	/** Begin stripping on the following ticks. */
	public void arm() {
		ticksLeft = MAX_TICKS;
		stripComplete = false;
	}

	public boolean isArmed() {
		return ticksLeft > 0;
	}


	public void cancel() {
		ticksLeft = 0;
	}

	/**
	 * Removes at most one equipped item. Must be called from the client thread
	 * (a {@code GameTick} subscriber qualifies).
	 *
	 * @return true while items remain to strip
	 */
	public boolean tick() {
		if (ticksLeft <= 0) {
			return false;
		}
		ticksLeft--;

		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null) {
			ticksLeft = 0;
			return false;
		}
		if (freeInventorySlots() <= 0) {
			log.debug("Gachaman: tutorial strip stopped — inventory full");
			ticksLeft = 0;
			return false;
		}

		for (int slot = 0; slot < SLOT_COMPONENTS.length; slot++) {
			int component = SLOT_COMPONENTS[slot];
			if (component < 0) {
				continue;
			}
			Item item = worn.getItem(slot);
			if (item == null || item.getId() <= 0) {
				continue;
			}
			if (invokeRemove(component, item.getId())) {
				return true;
			}
		}

		// nothing equipped (or nothing removable) — done
		ticksLeft = 0;
		stripComplete = true;
		return false;
	}

	/** Count of empty inventory slots, or 0 when the container is unavailable. */
	private int freeInventorySlots() {
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null) {
			return 0;
		}
		int used = 0;
		for (Item item : inventory.getItems()) {
			if (item != null && item.getId() > 0) {
				used++;
			}
		}
		return INVENTORY_SIZE - used;
	}

	/** Fires the widget's own "Remove" op, if it has one. */
	private boolean invokeRemove(int component, int itemId) {
		Widget widget = client.getWidget(component);
		if (widget == null) {
			return false;
		}
		if (invokeRemoveOn(widget, itemId)) {
			return true;
		}
		// some layouts put the item (and its ops) on a child of the slot
		Widget[] children = widget.getChildren();
		if (children != null) {
			for (Widget child : children) {
				if (child != null && invokeRemoveOn(child, itemId)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean invokeRemoveOn(Widget widget, int itemId) {
		String[] actions = widget.getActions();
		if (actions == null) {
			return false;
		}
		for (int i = 0; i < actions.length; i++) {
			if (!"Remove".equalsIgnoreCase(actions[i])) {
				continue;
			}
			// CC_OP identifiers are 1-based over the action list
			client.menuAction(widget.getIndex(), widget.getId(), MenuAction.CC_OP,
				i + 1, itemId, actions[i], "");
			log.debug("Gachaman: tutorial strip removing item {}", itemId);
			return true;
		}
		return false;
	}
}
