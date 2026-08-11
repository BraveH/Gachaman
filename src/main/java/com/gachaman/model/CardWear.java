package com.gachaman.model;

import java.util.Locale;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Cosmetic wear on a card face, derived from the Service Record
 * ({@link OwnedCard#getKillsServed()}) and from nothing else.
 *
 * <p>No rule anywhere reads this: a worn card rolls, equips, completes sets,
 * burns at prestige and prices in the shop exactly like a pristine one. It is a
 * veteran's stripe, not a durability system, and it is drawn the way a played
 * trading card ages — worn print, creases, scratches, patina — rather than as
 * fracture, so it never reads as damage the player should fear or avoid.
 */
@Getter
@RequiredArgsConstructor
public enum CardWear {
	NONE(""),
	HAIRLINE("Hairline"),
	CRACKED("Cracked"),
	SHATTERED("Shattered, still holding");

	private final String displayName;

	/**
	 * A stage named in chat by the ::gachawear debug command, or null if the
	 * word is not one. Null rather than an exception or a NONE default: the
	 * caller's other reading of the argument is a raw kill count, and silently
	 * treating a typo as "no wear" would wipe a record the player meant to set.
	 */
	@Nullable
	public static CardWear parse(@Nullable String word) {
		if (word == null) {
			return null;
		}
		String needle = word.trim().toUpperCase(Locale.ROOT);
		for (CardWear wear : values()) {
			if (wear.name().equals(needle)) {
				return wear;
			}
		}
		return null;
	}
}
