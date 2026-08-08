package com.gachaman.model;

import lombok.Value;
import lombok.With;

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
	/**
	 * Service Record: kills this exact copy was assigned to the loadout for.
	 * Permanent and monotonic. Absent from saves written before the feature —
	 * an int deserializes to 0, which is the truth for them: no service was
	 * ever recorded. Field-level @With so only withKillsServed is generated.
	 */
	@With
	int killsServed;

	public boolean isHologram()
	{
		return variant == Variant.HOLOGRAM;
	}
}
