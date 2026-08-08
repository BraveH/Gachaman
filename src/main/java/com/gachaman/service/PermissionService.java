package com.gachaman.service;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Variant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Computes the allowed item-id set from the loadout:
 *  - equipment card in slot S -> its exact item ids
 *  - SHINY equipment card    -> plus every lower-or-equal tier family member
 *  - HOLOGRAM in slot S      -> every item of that tier whose slot is S
 * All intersected with deeded slots. Fail-open: while the card DB is not
 * ready, nothing is ever blocked (an API hiccup must never punish a player).
 */
@Slf4j
@Singleton
public class PermissionService implements GachaStateService.Listener
{
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final net.runelite.api.Client client;
	private final com.gachaman.GachamanConfig config;

	private volatile Set<Integer> allowedItemIds = Collections.emptySet();
	private volatile Set<String> deededSlots = Collections.emptySet();

	@Inject
	public PermissionService(GachaStateService stateService, CardDatabase cardDatabase,
		net.runelite.api.Client client, com.gachaman.GachamanConfig config)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.client = client;
		this.config = config;
	}

	/** Re-derive permissions from the current state (config toggles etc.). */
	public void refresh()
	{
		GachaState state = stateService.get();
		if (state != null)
		{
			rebuild(state);
		}
	}

	public void start()
	{
		stateService.addListener(this);
		cardDatabase.onReady(() -> {
			GachaState state = stateService.get();
			if (state != null)
			{
				rebuild(state);
			}
		});
	}

	public void stop()
	{
		stateService.removeListener(this);
	}

	@Override
	public void onStateChanged(GachaState state)
	{
		rebuild(state);
	}

	/** Is equipping this item forbidden by Gachaman rules right now? */
	public boolean isForbidden(int itemId)
	{
		if (!cardDatabase.isReady() || stateService.get() == null)
		{
			return false; // fail-open
		}
		if (TutorialGate.onTutorial(client))
		{
			return false; // NO Gachaman locks on Tutorial Island — it force-equips items
		}
		CardDefinition card = cardDatabase.cardForItem(itemId);
		if (card == null)
		{
			return false; // not equipment we track
		}
		if (!deededSlots.contains(card.getSlot().name()))
		{
			return true; // slot itself locked
		}
		return !allowedItemIds.contains(itemId);
	}

	public boolean isSlotDeeded(GearSlot slot)
	{
		return deededSlots.contains(slot.name());
	}

	void rebuild(GachaState state)
	{
		if (!cardDatabase.isReady())
		{
			return;
		}
		if (!config.oneCardPerSlot())
		{
			rebuildOwnershipOnly(state);
			return;
		}
		Set<Integer> allowed = new HashSet<>();
		Map<String, OwnedCard> byUuid = new HashMap<>();
		for (OwnedCard card : state.getOwnedCards())
		{
			byUuid.put(card.getUuid(), card);
		}
		for (Map.Entry<String, String> entry : state.getLoadout().entrySet())
		{
			GearSlot slot;
			try
			{
				slot = GearSlot.valueOf(entry.getKey());
			}
			catch (IllegalArgumentException e)
			{
				continue;
			}
			OwnedCard owned = byUuid.get(entry.getValue());
			if (owned == null)
			{
				continue;
			}
			if (owned.isHologram())
			{
				// tier-wide permission scoped to this slot
				for (CardDefinition card : cardDatabase.all().values())
				{
					if (owned.getTierKey().equals(card.getTierKey()) && card.getSlot() == slot)
					{
						allowed.addAll(card.getItemIds());
					}
				}
				continue;
			}
			CardDefinition card = cardDatabase.card(owned.getCardId());
			if (card == null || card.getSlot() != slot)
			{
				continue;
			}
			allowed.addAll(card.getItemIds());
			if (owned.getVariant() == Variant.SHINY && card.getFamilyKey() != null)
			{
				for (CardDefinition member : cardDatabase.family(card.getFamilyKey()))
				{
					if (member.getTierRank() <= card.getTierRank())
					{
						allowed.addAll(member.getItemIds());
					}
				}
			}
		}
		this.allowedItemIds = allowed;
		this.deededSlots = new HashSet<>(state.getDeededSlots());
	}

	/**
	 * "One card per slot" OFF: owning a card is enough — every owned card's
	 * items are permitted with no loadout assignment. Shiny still unlocks the
	 * lower tiers of its family; a hologram unlocks its whole tier in every
	 * slot. Deed gating is unchanged.
	 */
	private void rebuildOwnershipOnly(GachaState state)
	{
		Set<Integer> allowed = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards())
		{
			if (owned.isHologram())
			{
				for (CardDefinition card : cardDatabase.all().values())
				{
					if (owned.getTierKey().equals(card.getTierKey()))
					{
						allowed.addAll(card.getItemIds());
					}
				}
				continue;
			}
			CardDefinition card = cardDatabase.card(owned.getCardId());
			if (card == null)
			{
				continue;
			}
			allowed.addAll(card.getItemIds());
			if (owned.getVariant() == Variant.SHINY && card.getFamilyKey() != null)
			{
				for (CardDefinition member : cardDatabase.family(card.getFamilyKey()))
				{
					if (member.getTierRank() <= card.getTierRank())
					{
						allowed.addAll(member.getItemIds());
					}
				}
			}
		}
		this.allowedItemIds = allowed;
		this.deededSlots = new HashSet<>(state.getDeededSlots());
	}
}
