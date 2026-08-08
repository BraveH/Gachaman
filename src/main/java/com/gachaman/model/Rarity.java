package com.gachaman.model;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Rarity
{
	COMMON("Common", new Color(176, 176, 176)),
	UNCOMMON("Uncommon", new Color(94, 204, 94)),
	RARE("Rare", new Color(86, 146, 255)),
	EPIC("Epic", new Color(178, 91, 226)),
	LEGENDARY("Legendary", new Color(255, 176, 46));

	private final String displayName;
	private final Color color;

	public boolean atLeast(Rarity other)
	{
		return ordinal() >= other.ordinal();
	}
}
