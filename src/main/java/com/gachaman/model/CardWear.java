package com.gachaman.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Cosmetic wear on a card face, derived from the Service Record
 * ({@link OwnedCard#getKillsServed()}) and from nothing else.
 *
 * <p>No rule anywhere reads this: a worn card rolls, equips, completes sets,
 * burns at prestige and prices in the shop exactly like a pristine one. It is a
 * veteran's stripe, not a durability system, and it is drawn as gold-filled
 * kintsugi repair rather than grey fracture so it never reads as damage the
 * player should fear or avoid.
 */
@Getter
@RequiredArgsConstructor
public enum CardWear
{
	NONE(""),
	HAIRLINE("Hairline"),
	CRACKED("Cracked"),
	SHATTERED("Shattered, still holding");

	private final String displayName;
}
