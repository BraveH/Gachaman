package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

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
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PermissionService implements GachaStateService.Listener {
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final Client client;
	private final GachamanConfig config;

	private volatile Set<Integer> allowedItemIds = Collections.emptySet();
	private volatile Set<String> deededSlots = Collections.emptySet();

	/** Re-derive permissions from the current state (config toggles etc.). */
	public void refresh() {
		GachaState state = stateService.get();
		if (state != null) {
			rebuild(state);
		}
	}

	public void start() {
		stateService.addListener(this);
		cardDatabase.onReady(() -> {
			GachaState state = stateService.get();
			if (state != null) {
				rebuild(state);
			}
		});
	}

	public void stop() {
		stateService.removeListener(this);
	}

	@Override
	public void onStateChanged(GachaState state) {
		rebuild(state);
	}

	/** Is equipping this item forbidden by Gachaman rules right now? */
	public boolean isForbidden(int itemId) {
		if (!cardDatabase.isReady() || stateService.get() == null) {
			return false; // fail-open
		}
		if (TutorialGate.onTutorial(client)) {
			return false; // NO Gachaman locks on Tutorial Island — it force-equips items
		}
		CardDefinition card = cardDatabase.cardForItem(itemId);
		if (card == null) {
			return false; // not equipment we track
		}
		if (!deededSlots.contains(card.getSlot().name())) {
			return true; // slot itself locked
		}
		return !allowedItemIds.contains(itemId);
	}

	public boolean isSlotDeeded(GearSlot slot) {
		return deededSlots.contains(slot.name());
	}

	void rebuild(GachaState state) {
		if (!cardDatabase.isReady())
			return;
		if (!config.oneCardPerSlot()) {
			rebuildOwnershipOnly(state);
			return;
		}
		Set<Integer> allowed = new HashSet<>();
		Map<String, OwnedCard> byUuid = new HashMap<>();
		for (OwnedCard card : state.getOwnedCards()) {
			byUuid.put(card.getUuid(), card);
		}
		for (Map.Entry<String, String> entry : state.getLoadout().entrySet()) {
			GearSlot slot;
			try {
				slot = GearSlot.valueOf(entry.getKey());
			}
			catch (IllegalArgumentException e) {
				continue;
			}
			OwnedCard owned = byUuid.get(entry.getValue());
			if (owned == null)
				continue;
			allowed.addAll(itemIdsFor(owned, slot));
		}
		publish(allowed, state);
	}

	/**
	 * Swap in a freshly computed permission set. Both rebuild routes end here so
	 * the two volatiles are always written together and in the same order: a
	 * reader that saw a new allow-set against the previous deed set would, for one
	 * instant, be answering from a pair that never existed.
	 */
	private void publish(Set<Integer> allowed, GachaState state) {
		this.allowedItemIds = allowed;
		this.deededSlots = new HashSet<>(state.getDeededSlots());
	}

	/**
	 * Every item of a hologram's tier, optionally narrowed to one slot — {@code
	 * slot} null means the whole tier in every slot, which is what ownership-only
	 * permission grants.
	 */
	private void addTierIds(Set<Integer> ids, OwnedCard owned, GearSlot slot) {
		for (CardDefinition card : cardDatabase.all().values()) {
			if (owned.getTierKey().equals(card.getTierKey())
				&& (slot == null || card.getSlot() == slot)) {
				ids.addAll(card.getItemIds());
			}
		}
	}

	/**
	 * One equipment card's ids: its own, plus — for a SHINY — every lower-or-equal
	 * tier member of its family.
	 *
	 * <p>Shared by both permission routes, which is the point. The shiny ladder had
	 * this rule written out twice, and two copies of "which family members does a
	 * shiny unlock" is exactly the pair that drifts: the config toggle would then
	 * decide not just WHETHER the loadout gates gear but what a shiny is worth,
	 * and a player flipping it would watch their unlocks change for no stated
	 * reason.
	 */
	private void addCardIds(Set<Integer> ids, OwnedCard owned, CardDefinition card) {
		ids.addAll(card.getItemIds());
		if (owned.getVariant() == Variant.SHINY && card.getFamilyKey() != null) {
			for (CardDefinition member : cardDatabase.family(card.getFamilyKey())) {
				if (member.getTierRank() <= card.getTierRank()) {
					ids.addAll(member.getItemIds());
				}
			}
		}
	}

	/**
	 * The item ids ONE loadout entry grants: this card, in this slot, and
	 * nothing else. This is the per-entry branch {@link #rebuild} runs in its
	 * loop, lifted out verbatim so it has exactly one definition — the union
	 * over every entry is still the whole allowed set, so nothing about
	 * rebuild's answer changed.
	 *
	 * <p>It exists because {@link #isForbidden} is the WRONG question for the
	 * unassign guard, and wrong in the way that looks right: for every id in
	 * this set isForbidden answers false, precisely BECAUSE the card is still
	 * assigned. A guard built on it would refuse nothing, ever, and pass a
	 * casual read. A dragon hologram in WEAPON with a dragon scimitar worn is
	 * the case that makes the difference visible — isForbidden says fine,
	 * this says "that scimitar is yours only while the hologram stays put".
	 *
	 * <p>Empty when the card does not belong in this slot at all, which makes
	 * "would clearing this slot strand anything?" a plain set intersection.
	 */
	public Set<Integer> itemIdsFor(OwnedCard owned, GearSlot slot) {
		Set<Integer> ids = new HashSet<>();
		if (owned == null || slot == null || !cardDatabase.isReady()) {
			return ids; // fail-open, same as every other read here
		}
		if (owned.isHologram()) {
			// tier-wide permission scoped to this slot
			addTierIds(ids, owned, slot);
			return ids;
		}
		CardDefinition card = cardDatabase.card(owned.getCardId());
		if (card != null && card.getSlot() == slot) {
			addCardIds(ids, owned, card);
		}
		return ids;
	}

	/**
	 * "One card per slot" OFF: owning a card is enough — every owned card's
	 * items are permitted with no loadout assignment. Shiny still unlocks the
	 * lower tiers of its family; a hologram unlocks its whole tier in every
	 * slot. Deed gating is unchanged.
	 */
	private void rebuildOwnershipOnly(GachaState state) {
		Set<Integer> allowed = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (owned.isHologram()) {
				addTierIds(allowed, owned, null); // null slot: the whole tier, every slot
				continue;
			}
			CardDefinition card = cardDatabase.card(owned.getCardId());
			if (card != null) {
				addCardIds(allowed, owned, card);
			}
		}
		publish(allowed, state);
	}
}
