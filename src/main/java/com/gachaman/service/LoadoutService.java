package com.gachaman.service;

import net.runelite.api.gameval.InventoryID;
import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.chat.*;

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
	private final Client client;
	private final GachamanConfig config;
	private final PermissionService permissionService;
	private final ChatMessageManager chatMessageManager;

	/** Optional hook fired after a successful assignment (Firsts + Timeline). */
	@Nullable
	@Setter
	private BiConsumer<GearSlot, OwnedCard> assignHook;

	/** Tick of the last refusal line, so a fumbled double-click says it once. */
	private int lastWarnTick = -1;


	/** Assign an owned card to a loadout slot. Returns false when invalid. */
	public boolean assign(GearSlot slot, String ownedCardUuid) {
		GachaState state = stateService.get();
		if (state == null || slot == null || ownedCardUuid == null)
			return false;
		OwnedCard owned = findByUuid(state, ownedCardUuid);
		if (owned == null)
			return false;
		if (!owned.isHologram()) {
			if (!cardDatabase.isReady())
				return false;
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def == null || def.getSlot() != slot)
				return false;
		}
		stateService.mutate(s -> {
			OwnedCard card = findByUuid(s, ownedCardUuid);
			if (card == null)
				return s;
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

	/**
	 * Clear a loadout slot.
	 *
	 * <p>REFUSES while an item this card unlocks is still WORN. Unassigning is
	 * the only way to lose a permission you are in the middle of exercising:
	 * nothing re-reads equipment on a loadout change, so the player would walk
	 * away still wearing gear the album no longer permits, and stay that way
	 * until some unrelated container change happened to force a rebuild. The
	 * plugin may not take the gear off for them — no automated actions, ever —
	 * so refusing and saying why is the only honest answer left.
	 *
	 * <p>Guarded only while {@code oneCardPerSlot} is ON, which is the same
	 * branch {@link PermissionService#rebuild} takes. With it OFF permission
	 * comes from OWNERSHIP alone (rebuildOwnershipOnly), the loadout grants
	 * nothing at all, and clearing a slot therefore takes nothing away — a
	 * guard there would be refusing a click that could not have hurt anyone.
	 *
	 * @return false ONLY when the guard refused and nothing changed. A clear
	 *     that happened, a slot that was already empty and a null slot all
	 *     return true: there is nothing there for a caller to explain.
	 */
	public boolean unassign(GearSlot slot) {
		if (slot == null)
			return true;
		if (stillWorn(slot, assigned(slot))) {
			refuseWornUnassign();
			return false;
		}
		stateService.mutate(s -> {
			if (!s.getLoadout().containsKey(slot.name()))
				return s;
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			loadout.remove(slot.name());
			return s.withLoadout(loadout);
		});
		// clearing a slot is a loadout change like any other, and a clear that
		// silently came back after a crash would be just as confusing as an
		// assignment that vanished
		stateService.checkpoint();
		return true;
	}

	/**
	 * Is something this card unlocks IN THIS SLOT on the player's back?
	 *
	 * <p>The id set comes from {@link PermissionService#itemIdsFor} — the very
	 * branch the rebuild loop runs per entry — and deliberately not from
	 * isForbidden, which answers false for every one of these ids exactly
	 * because the card is still assigned.
	 *
	 * <p>The worn container is scanned whole instead of being indexed by
	 * GearSlot. The card that named these ids already named the slot they live
	 * in, so an id match IS a slot match, and no GearSlot -> container index
	 * table has to exist or be kept correct.
	 */
	private boolean stillWorn(GearSlot slot, OwnedCard owned) {
		if (owned == null || !config.oneCardPerSlot())
			return false;
		Set<Integer> worn = wornItemIds();
		for (int itemId : permissionService.itemIdsFor(owned, slot)) {
			if (worn.contains(itemId))
				return true;
		}
		return false;
	}

	/**
	 * Item ids on the player's back right now, read the way GraduationService
	 * reads them: the WORN container, skipping the empty-slot sentinels.
	 *
	 * <p>Package-private so a headless test can hand the guard a synthetic set.
	 * RuneLite's Client is far too wide an interface to implement for one
	 * container read, and this repo's suite carries no mocking framework.
	 */
	Set<Integer> wornItemIds() {
		ItemContainer worn = client == null ? null : client.getItemContainer(InventoryID.WORN);
		if (worn == null) {
			return Collections.emptySet(); // logged out: fail open, like every permission read
		}
		Set<Integer> ids = new HashSet<>();
		for (Item item : worn.getItems()) {
			if (item != null && item.getId() > 0)
				ids.add(item.getId());
		}
		return ids;
	}

	/**
	 * The refusal line: CONSOLE, the Gachaman prefix, once per tick — the same
	 * shape EquipBlockService warns in when it blocks an equip. A player meets
	 * both messages in the same breath and they should read as one voice.
	 *
	 * <p>It lives HERE, not at each call site, on purpose. The in-game board
	 * has no text of its own and the chatbox search discards its result
	 * entirely, so a refusal wired per-caller is one forgotten call site away
	 * from a click that does nothing and says nothing. Putting it in the
	 * service means every caller — including ones this file has not met yet —
	 * gets the same sentence for free. The sidebar adds a dialog on top of it,
	 * because that is where a sidebar click is already answered.
	 */
	private void refuseWornUnassign() {
		if (chatMessageManager == null || client == null) {
			return; // headless (tests): the boolean return is the whole contract there
		}
		int tick = client.getTickCount();
		if (tick == lastWarnTick)
			return;
		lastWarnTick = tick;
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> That card is still unlocking"
				+ " what you are wearing. Take the item off first.")
			.build());
	}

	/** The owned card currently assigned to a slot, or null. */
	@Nullable
	public OwnedCard assigned(GearSlot slot) {
		GachaState state = stateService.get();
		if (state == null || slot == null)
			return null;
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
		if (state == null || slot == null || !cardDatabase.isReady())
			return result;
		Set<String> assignedUuids = new HashSet<>(state.getLoadout().values());
		String currentUuid = state.getLoadout().get(slot.name());
		Set<String> seen = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (owned.isHologram()) {
				if (owned.getUuid().equals(currentUuid)) {
					continue; // already here
				}
				if (!seen.add("H:" + owned.getTierKey()))
					continue;
				result.add(owned);
				continue;
			}
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def == null || def.getSlot() != slot)
				continue;
			if (assignedUuids.contains(owned.getUuid()))
				continue;
			if (!seen.add("C:" + owned.getCardId() + ":" + owned.getVariant()))
				continue;
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
	public boolean matchesStyle(OwnedCard owned, AttackStyle allowed) {
		if (allowed == null)
			return true;
		AttackStyle style = styleOf(owned);
		return style == null || style == allowed;
	}

	/** MELEE/RANGED/MAGIC by tier ladder; null = style-neutral or unknown. */
	@Nullable
	public AttackStyle styleOf(OwnedCard owned) {
		String tierKey = null;
		String name = null;
		if (owned.isHologram())
			tierKey = owned.getTierKey();
		else {
			CardDefinition def = cardDatabase.card(owned.getCardId());
			if (def != null) {
				tierKey = def.getTierKey();
				name = def.getName();
			}
		}
		if (tierKey == null)
			return null;
		String ladder = tierTable.ladderOf(tierKey);
		if (ladder == null)
			return null;
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
		if (owned == null)
			return "?";
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
			if (card.getUuid().equals(uuid))
				return card;
		}
		return null;
	}
}
