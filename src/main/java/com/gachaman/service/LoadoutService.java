package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Loadout assignment rules, shared by the sidebar panel and the in-game
 * overlay. Equipment cards may only occupy their own gear slot; hologram
 * cards fit any slot but may occupy only ONE slot at a time (re-assigning a
 * hologram moves it).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LoadoutService {
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final TierTable tierTable;
	private final RangedMetal.Lookup rangedMetal;

	/** Optional hook fired after a successful assignment (Firsts + Timeline). */
	@Nullable
	@Setter
	private BiConsumer<GearSlot, OwnedCard> assignHook;


	/** Assign an owned card to a loadout slot. Returns false when invalid. */
	public boolean assign(GearSlot slot, String ownedCardUuid) {
		GachaState state = stateService.get();
		if (state == null || slot == null || ownedCardUuid == null) {
			return false;
		}
		OwnedCard owned = findByUuid(state, ownedCardUuid);
		if (owned == null) {
			return false;
		}
		if (!owned.isHologram()) {
			if (!cardDatabase.isReady()) {
				return false;
			}
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def == null || def.getSlot() != slot) {
				return false;
			}
		}
		stateService.mutate(s -> {
			OwnedCard card = findByUuid(s, ownedCardUuid);
			if (card == null) {
				return s;
			}
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			if (card.isHologram()) {
				// a hologram may occupy only one slot: assigning elsewhere moves it
				loadout.values().removeIf(ownedCardUuid::equals);
			}
			loadout.put(slot.name(), ownedCardUuid);
			return s.withLoadout(loadout);
		});
		// Straight to disk, not into the debounce. A loadout change is a
		// deliberate, fiddly decision the player just spent time on — swapping a
		// card between slots, re-equipping around a new pull — and it is the
		// single change they are most likely to notice missing. It is also rare
		// enough that an immediate write costs nothing measurable.
		stateService.checkpoint();
		Listeners.fireHook(assignHook, h -> h.accept(slot, owned), "assign hook failed");
		return true;
	}

	/** Clear a loadout slot. */
	public void unassign(GearSlot slot) {
		if (slot == null) {
			return;
		}
		stateService.mutate(s -> {
			if (!s.getLoadout().containsKey(slot.name())) {
				return s;
			}
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			loadout.remove(slot.name());
			return s.withLoadout(loadout);
		});
		// clearing a slot is a loadout change like any other, and a clear that
		// silently came back after a crash would be just as confusing as an
		// assignment that vanished
		stateService.checkpoint();
	}

	/** The owned card currently assigned to a slot, or null. */
	@Nullable
	public OwnedCard assigned(GearSlot slot) {
		GachaState state = stateService.get();
		if (state == null || slot == null) {
			return null;
		}
		String uuid = state.getLoadout().get(slot.name());
		return uuid == null ? null : findByUuid(state, uuid);
	}

	/**
	 * Owned cards that could be assigned to this slot right now: unassigned
	 * equipment cards whose card slot matches, plus every owned hologram
	 * (holograms are valid for every slot; assigning one that lives elsewhere
	 * moves it). Duplicate (cardId, variant) copies are collapsed to one.
	 */
	public List<OwnedCard> validFor(GearSlot slot) {
		GachaState state = stateService.get();
		List<OwnedCard> result = new ArrayList<>();
		if (state == null || slot == null || !cardDatabase.isReady()) {
			return result;
		}
		Set<String> assignedUuids = new HashSet<>(state.getLoadout().values());
		String currentUuid = state.getLoadout().get(slot.name());
		Set<String> seen = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (owned.isHologram()) {
				if (owned.getUuid().equals(currentUuid)) {
					continue; // already here
				}
				if (!seen.add("H:" + owned.getTierKey())) {
					continue;
				}
				result.add(owned);
				continue;
			}
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def == null || def.getSlot() != slot) {
				continue;
			}
			if (assignedUuids.contains(owned.getUuid())) {
				continue;
			}
			if (!seen.add("C:" + owned.getCardId() + ":" + owned.getVariant())) {
				continue;
			}
			result.add(owned);
		}
		// group 1: cards matching the rolled attack style (or style-neutral);
		// group 2: everything else — A-Z within both groups
		AttackStyle allowed = state.getAllowedStyle() == null
			? null : AttackStyle.valueOf(state.getAllowedStyle());
		result.sort(Comparator
			.comparing((OwnedCard owned) -> !matchesStyle(owned, allowed))
			.thenComparing(this::displayName, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	/** Does this card belong to the rolled style's gear family? Neutral gear always matches. */
	public boolean matchesStyle(OwnedCard owned, @Nullable AttackStyle allowed) {
		if (allowed == null) {
			return true;
		}
		AttackStyle style = styleOf(owned);
		return style == null || style == allowed;
	}

	/** MELEE/RANGED/MAGIC by tier ladder; null = style-neutral or unknown. */
	@Nullable
	public AttackStyle styleOf(OwnedCard owned) {
		String tierKey = null;
		String name = null;
		if (owned.isHologram()) {
			tierKey = owned.getTierKey();
		}
		else {
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def != null) {
				tierKey = def.getTierKey();
				name = def.getName();
			}
		}
		if (tierKey == null) {
			return null;
		}
		String ladder = tierTable.ladderOf(tierKey);
		if (ladder == null) {
			return null;
		}
		switch (ladder) {
			case "metal":
				// Rune arrows and adamant darts sit on the metal ladder but are Ranged gear.
				// Calling them melee sorted them into the non-matching half of a ranger's own
				// loadout picker. Holograms carry no item name, so they keep the melee default.
				return rangedMetal.of(name) != null
					? AttackStyle.RANGED
					: AttackStyle.MELEE;
			case "dhide":
				return AttackStyle.RANGED;
			case "robes":
				return AttackStyle.MAGIC;
			default:
				return null;
		}
	}

	/** Human-readable name for an owned card (works for holograms too). */
	public String displayName(OwnedCard owned) {
		if (owned == null) {
			return "?";
		}
		if (owned.isHologram()) {
			HologramDefinition holo = cardDatabase.holograms().get(owned.getTierKey());
			return holo != null ? holo.getName() : "Hologram (" + owned.getTierKey() + ")";
		}
		CardDefinition def = cardDatabase.card(owned.getCardId());
		String name = def != null ? def.getName() : "Card #" + owned.getCardId();
		return owned.getVariant() == Variant.SHINY ? name + " (Shiny)" : name;
	}

	@Nullable
	private static OwnedCard findByUuid(GachaState state, String uuid) {
		for (OwnedCard card : state.getOwnedCards()) {
			if (card.getUuid().equals(uuid)) {
				return card;
			}
		}
		return null;
	}
}
