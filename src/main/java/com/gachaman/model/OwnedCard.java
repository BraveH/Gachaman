package com.gachaman.model;

import lombok.Value;

/**
 * One owned card instance. Equipment cards reference a card id (the base
 * canonical item id); hologram instances carry a tierKey and cardId -1.
 */
@Value
public class OwnedCard
{
	String uuid;
	int cardId;
	String tierKey; // non-null only for HOLOGRAM instances
	Variant variant;
	long acquiredAtMs;
	String provenance; // e.g. "chest:ORNATE", "shop:2026-W32", "kc:zulrah:50"

	public boolean isHologram()
	{
		return variant == Variant.HOLOGRAM;
	}
}
