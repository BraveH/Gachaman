package com.gachaman.data;

import com.gachaman.model.GearSlot;
import com.gachaman.model.Rarity;
import java.util.Set;
import lombok.Value;

/**
 * One equipment card, derived at runtime from the item cache. cardId is the
 * lowest item id in the merged variant group and is stable across builds.
 */
@Value
public class CardDefinition
{
	int cardId;
	String name;       // cleaned canonical name, e.g. "Rune scimitar"
	GearSlot slot;
	String tierKey;    // null when untiered
	int tierRank;      // 0 when untiered
	String familyKey;  // e.g. "scimitar"; null when untiered
	Rarity rarity;
	Set<Integer> itemIds; // every concrete item id this card permits
	boolean shinyEligible;
}
