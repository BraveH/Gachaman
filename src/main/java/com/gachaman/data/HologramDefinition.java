package com.gachaman.data;

import com.gachaman.model.Rarity;
import lombok.Value;

/** A hologram tier card: represents a whole tier, not a specific item. */
@Value
public class HologramDefinition
{
	String tierKey;
	String name; // e.g. "Dragon Hologram"
	Rarity rarity;
	String representativeItemName;
}
