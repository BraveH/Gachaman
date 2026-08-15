package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;

/**
 * Everything the loadout tests need to run headless: a synthetic card
 * database, an in-memory state service, and a config whose only interesting
 * answer is {@code oneCardPerSlot}.
 *
 * <p>It exists because the guard under test spans three collaborators. The
 * real CardDatabase is built by a chunked scan of the live item cache, so a
 * test cannot have one; RuneLite's Client is an interface far too wide to
 * implement for a single container read, and this repo's suite carries no
 * mocking framework by design. What is left is a small honest fake plus one
 * overridable seam in LoadoutService, which is enough to state every rule.
 */
final class LoadoutFixture
{
	/** Ranks 1..6, low to high — the shiny rule reads "<= my rank". */
	static final List<String> TIERS =
		Arrays.asList("bronze", "iron", "steel", "adamant", "rune", "dragon");

	private LoadoutFixture()
	{
	}

	/**
	 * A card database of eleven slots x six tiers, one family per slot.
	 *
	 * <p>One family per slot mirrors the real builder, which keys families as
	 * "scimitar/WEAPON" — a shiny can only ever reach down its own slot's
	 * ladder, and the guard depends on that: an id match is a slot match.
	 *
	 * <p>Every card carries TWO item ids (its canonical id and that id plus
	 * {@link #VARIANT_STEP}) because real cards merge item variants — kitted,
	 * charged, ornamented. A guard that only ever compared canonical ids would
	 * pass a one-id-per-card fake and still let a player keep an ornament kit
	 * on their back.
	 */
	static final class Cards extends CardDatabase
	{
		/** Distance from a card's canonical item id to its second variant id. */
		static final int VARIANT_STEP = 10000;

		private static final Map<GearSlot, String> WORD = new EnumMap<>(GearSlot.class);

		static
		{
			WORD.put(GearSlot.HEAD, "full helm");
			WORD.put(GearSlot.CAPE, "cape");
			WORD.put(GearSlot.AMULET, "amulet");
			WORD.put(GearSlot.WEAPON, "scimitar");
			WORD.put(GearSlot.BODY, "platebody");
			WORD.put(GearSlot.SHIELD, "kiteshield");
			WORD.put(GearSlot.LEGS, "platelegs");
			WORD.put(GearSlot.HANDS, "gauntlets");
			WORD.put(GearSlot.FEET, "boots");
			WORD.put(GearSlot.RING, "ring");
			WORD.put(GearSlot.AMMO, "arrows");
		}

		private final Map<Integer, CardDefinition> cards = new LinkedHashMap<>();
		private final Map<Integer, CardDefinition> cardByItem = new HashMap<>();
		private final Map<String, List<CardDefinition>> families = new HashMap<>();

		Cards()
		{
			super(null, null, null, null, null);
			for (GearSlot slot : GearSlot.values())
			{
				for (int rank = 1; rank <= TIERS.size(); rank++)
				{
					String tierKey = TIERS.get(rank - 1);
					int cardId = cardId(slot, tierKey);
					Set<Integer> itemIds = new LinkedHashSet<>(
						Arrays.asList(cardId, cardId + VARIANT_STEP));
					CardDefinition def = new CardDefinition(cardId,
						name(tierKey, slot), slot, tierKey, rank, familyKey(slot),
						Rarity.values()[Math.min(rank - 1, Rarity.values().length - 1)],
						itemIds, rank > 1);
					cards.put(cardId, def);
					for (int itemId : itemIds)
					{
						cardByItem.put(itemId, def);
					}
					families.computeIfAbsent(familyKey(slot), k -> new ArrayList<>()).add(def);
				}
			}
		}

		@Override
		public boolean isReady()
		{
			return true;
		}

		@Override
		public CardDefinition card(int cardId)
		{
			return cards.get(cardId);
		}

		@Override
		public CardDefinition cardForItem(int itemId)
		{
			return cardByItem.get(itemId);
		}

		@Override
		public Map<Integer, CardDefinition> all()
		{
			return cards;
		}

		@Override
		public List<CardDefinition> family(String familyKey)
		{
			return families.getOrDefault(familyKey, Collections.emptyList());
		}

		/** Every item id in the database, canonical and variant alike. */
		Set<Integer> everyItemId()
		{
			return cardByItem.keySet();
		}

		static String familyKey(GearSlot slot)
		{
			return WORD.get(slot) + "/" + slot.name();
		}

		static String name(String tierKey, GearSlot slot)
		{
			return Character.toUpperCase(tierKey.charAt(0)) + tierKey.substring(1)
				+ " " + WORD.get(slot);
		}
	}

	/**
	 * Card id for a slot/tier pair. Also its canonical ITEM id: the real
	 * builder uses the lowest merged item id as the card id, so a card's own
	 * number being wearable is true there too.
	 */
	static int cardId(GearSlot slot, String tierKey)
	{
		return (slot.ordinal() + 1) * 100 + TIERS.indexOf(tierKey) + 1;
	}

	/** The canonical worn item id of a slot/tier pair. Reads better at call sites. */
	static int itemId(GearSlot slot, String tierKey)
	{
		return cardId(slot, tierKey);
	}

	/** The second, merged-variant item id of a slot/tier pair. */
	static int variantItemId(GearSlot slot, String tierKey)
	{
		return cardId(slot, tierKey) + Cards.VARIANT_STEP;
	}

	static OwnedCard plain(GearSlot slot, String tierKey)
	{
		return owned("plain:" + slot + ":" + tierKey, cardId(slot, tierKey), null, Variant.NORMAL);
	}

	static OwnedCard shiny(GearSlot slot, String tierKey)
	{
		return owned("shiny:" + slot + ":" + tierKey, cardId(slot, tierKey), null, Variant.SHINY);
	}

	/**
	 * A hologram instance. The slot is only part of the uuid — a hologram
	 * carries no card id and grants by TIER — but distinct uuids let a
	 * generated album park one in every slot at once without two entries
	 * silently being the same physical card.
	 */
	static OwnedCard hologram(GearSlot slot, String tierKey)
	{
		return owned("holo:" + slot + ":" + tierKey, -1, tierKey, Variant.HOLOGRAM);
	}

	private static OwnedCard owned(String uuid, int cardId, String tierKey, Variant variant)
	{
		return new OwnedCard(uuid, cardId, tierKey, variant, 0L, "test", 0);
	}

	/**
	 * State backed by a store that never touches disk and never returns a save,
	 * so every test starts from a fresh profile. Same shape the task-service
	 * tests use.
	 */
	static GachaStateService inMemoryStateService()
	{
		StateStore store = new StateStore(null, null, null)
		{
			@Override
			public void save(GachaState state)
			{
			}

			@Override
			public void save(GachaState state, boolean flushDiskNow)
			{
			}

			@Override
			public GachaState load()
			{
				return null;
			}
		};
		GachaStateService service = new GachaStateService(store);
		service.load(3);
		return service;
	}

	/** Every slot deeded, so deed gating can never be what a test is measuring. */
	static void deedEverySlot(GachaStateService stateService)
	{
		Set<String> all = new HashSet<>();
		for (GearSlot slot : GearSlot.values())
		{
			all.add(slot.name());
		}
		stateService.mutate(s -> s.withDeededSlots(all));
	}

	static void giveCards(GachaStateService stateService, OwnedCard... cards)
	{
		List<OwnedCard> owned = new ArrayList<>(Arrays.asList(cards));
		stateService.mutate(s -> s.withOwnedCards(owned));
	}

	static void putInLoadout(GachaStateService stateService, GearSlot slot, OwnedCard card)
	{
		stateService.mutate(s ->
		{
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			loadout.put(slot.name(), card.getUuid());
			return s.withLoadout(loadout);
		});
	}

	static GachamanConfig config(boolean oneCardPerSlot)
	{
		return new GachamanConfig()
		{
			@Override
			public boolean oneCardPerSlot()
			{
				return oneCardPerSlot;
			}
		};
	}

	/**
	 * A LoadoutService wired for a headless test: a null Client and a null
	 * ChatMessageManager (so the refusal line is skipped and the boolean
	 * return is the whole contract), with the worn-equipment read replaced by
	 * a fixed set.
	 */
	static LoadoutService service(GachaStateService stateService, CardDatabase cards,
		PermissionService permissions, GachamanConfig config, Set<Integer> worn)
	{
		return new LoadoutService(stateService, cards, null, null, null, config, permissions, null)
		{
			@Override
			Set<Integer> wornItemIds()
			{
				return worn;
			}
		};
	}
}
