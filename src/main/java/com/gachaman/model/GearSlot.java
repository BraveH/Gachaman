package com.gachaman.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The eleven equipment slots Gachaman gates. Index matches the equipment
 * slot index reported by ItemEquipmentStats.getSlot() / EquipmentInventorySlot.
 */
@Getter
@RequiredArgsConstructor
public enum GearSlot {
	HEAD(0, "Head"),
	CAPE(1, "Cape"),
	AMULET(2, "Amulet"),
	WEAPON(3, "Weapon"),
	BODY(4, "Body"),
	SHIELD(5, "Shield"),
	LEGS(7, "Legs"),
	HANDS(9, "Hands"),
	FEET(10, "Feet"),
	RING(12, "Ring"),
	AMMO(13, "Ammo");

	private final int slotIndex;
	private final String displayName;

	public static GearSlot fromSlotIndex(int index) {
		for (GearSlot s : values()) {
			if (s.slotIndex == index) {
				return s;
			}
		}
		return null;
	}
}
