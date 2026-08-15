package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

import static com.gachaman.service.LoadoutFixture.*;

/**
 * PermissionService.itemIdsFor is the per-loadout-entry branch of rebuild,
 * lifted out so the unassign guard can ask rebuild's own question. The lift
 * has to be behaviour-preserving, and "I moved it carefully" is not evidence.
 *
 * <p>{@link #oracle} is the loop exactly as it read before the extraction,
 * kept verbatim on purpose: it is the only copy of the old behaviour that
 * still exists, and comparing rebuild against it over a generated album is
 * what makes the refactor checkable rather than merely plausible.
 */
public class PermissionItemIdsForTest
{
	private GachaStateService stateService;
	private LoadoutFixture.Cards cards;
	private GachamanConfig config;
	private PermissionService permissions;

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		cards = new LoadoutFixture.Cards();
		config = config(true);
		permissions = new PermissionService(stateService, cards, null, config);
		deedEverySlot(stateService);
	}

	/**
	 * The rebuild loop's per-entry branch, verbatim as it stood before
	 * itemIdsFor existed. Do not "tidy" this — its whole value is being an
	 * independent copy of the old code.
	 */
	private static Set<Integer> oracle(GachaState state, CardDatabase db)
	{
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
				for (CardDefinition card : db.all().values())
				{
					if (owned.getTierKey().equals(card.getTierKey()) && card.getSlot() == slot)
					{
						allowed.addAll(card.getItemIds());
					}
				}
				continue;
			}
			CardDefinition card = db.card(owned.getCardId());
			if (card == null || card.getSlot() != slot)
			{
				continue;
			}
			allowed.addAll(card.getItemIds());
			if (owned.getVariant() == Variant.SHINY && card.getFamilyKey() != null)
			{
				for (CardDefinition member : db.family(card.getFamilyKey()))
				{
					if (member.getTierRank() <= card.getTierRank())
					{
						allowed.addAll(member.getItemIds());
					}
				}
			}
		}
		return allowed;
	}

	/** Union of the extracted branch over every loadout entry. */
	private Set<Integer> viaExtraction(GachaState state)
	{
		Map<String, OwnedCard> byUuid = new HashMap<>();
		for (OwnedCard card : state.getOwnedCards())
		{
			byUuid.put(card.getUuid(), card);
		}
		Set<Integer> allowed = new HashSet<>();
		for (Map.Entry<String, String> entry : state.getLoadout().entrySet())
		{
			allowed.addAll(permissions.itemIdsFor(byUuid.get(entry.getValue()),
				GearSlot.valueOf(entry.getKey())));
		}
		return allowed;
	}

	/**
	 * Album generator: every slot holds a card, and which KIND of card rotates
	 * with the permutation index so that across the runs each slot has been
	 * plain, shiny and hologram, at several tiers.
	 */
	private List<OwnedCard> deal(int permutation)
	{
		List<OwnedCard> album = new ArrayList<>();
		GearSlot[] slots = GearSlot.values();
		for (int i = 0; i < slots.length; i++)
		{
			GearSlot slot = slots[i];
			String tierKey = TIERS.get((permutation + i) % TIERS.size());
			switch ((permutation + i) % 3)
			{
				case 0:
					album.add(plain(slot, tierKey));
					break;
				case 1:
					album.add(shiny(slot, tierKey));
					break;
				default:
					album.add(hologram(slot, tierKey));
					break;
			}
		}
		return album;
	}

	@Test
	public void theExtractedBranchReproducesTheOldRebuildLoopExactly()
	{
		// 33 permutations: 3 card kinds x 11 slot offsets, so every slot has
		// been every kind, and the tier walked past every rung of the ladder
		for (int permutation = 0; permutation < 33; permutation++)
		{
			List<OwnedCard> album = deal(permutation);
			stateService.mutate(s -> s.withOwnedCards(album));
			Map<String, String> loadout = new HashMap<>();
			for (int i = 0; i < GearSlot.values().length; i++)
			{
				loadout.put(GearSlot.values()[i].name(), album.get(i).getUuid());
			}
			stateService.mutate(s -> s.withLoadout(loadout));

			GachaState state = stateService.get();
			Set<Integer> expected = oracle(state, cards);
			Assert.assertFalse("permutation " + permutation + " permitted nothing, so it"
				+ " would have agreed with a broken extraction too", expected.isEmpty());
			Assert.assertEquals("permutation " + permutation,
				expected, viaExtraction(state));

			// ...and through the only door the rest of the plugin uses. Every
			// slot is deeded and TutorialGate reads false with a null Client,
			// so isForbidden here is exactly "not in the allowed set".
			permissions.refresh();
			for (int itemId : cards.everyItemId())
			{
				Assert.assertEquals("permutation " + permutation + ", item " + itemId,
					!expected.contains(itemId), permissions.isForbidden(itemId));
			}
		}
	}

	@Test
	public void aHologramGrantsItsTierOnlyInTheSlotItSitsIn()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		Set<Integer> weapon = permissions.itemIdsFor(holo, GearSlot.WEAPON);
		Assert.assertTrue(weapon.contains(itemId(GearSlot.WEAPON, "dragon")));
		Assert.assertTrue(weapon.contains(variantItemId(GearSlot.WEAPON, "dragon")));
		Assert.assertFalse("a weapon hologram permits no body armour",
			weapon.contains(itemId(GearSlot.BODY, "dragon")));
		Assert.assertFalse("nor a lower tier of its own slot",
			weapon.contains(itemId(GearSlot.WEAPON, "rune")));

		Assert.assertTrue("the same instance in BODY grants dragon body armour",
			permissions.itemIdsFor(holo, GearSlot.BODY)
				.contains(itemId(GearSlot.BODY, "dragon")));
	}

	@Test
	public void aShinyReachesDownItsFamilyAndNoFurther()
	{
		Set<Integer> ids = permissions.itemIdsFor(shiny(GearSlot.WEAPON, "rune"), GearSlot.WEAPON);
		for (String tierKey : TIERS)
		{
			boolean lowerOrEqual = TIERS.indexOf(tierKey) <= TIERS.indexOf("rune");
			Assert.assertEquals(tierKey, lowerOrEqual,
				ids.contains(itemId(GearSlot.WEAPON, tierKey)));
		}
		Assert.assertFalse("and never leaves the slot",
			ids.contains(itemId(GearSlot.BODY, "bronze")));
	}

	@Test
	public void aPlainCardGrantsOnlyItsOwnItemIds()
	{
		Set<Integer> ids = permissions.itemIdsFor(plain(GearSlot.WEAPON, "rune"), GearSlot.WEAPON);
		Assert.assertEquals(new HashSet<>(Arrays.asList(
			itemId(GearSlot.WEAPON, "rune"), variantItemId(GearSlot.WEAPON, "rune"))), ids);
	}

	/** A card in the wrong slot grants nothing, which is what makes the guard a set test. */
	@Test
	public void aCardInTheWrongSlotGrantsNothing()
	{
		Assert.assertTrue(permissions.itemIdsFor(plain(GearSlot.WEAPON, "rune"), GearSlot.BODY)
			.isEmpty());
		Assert.assertTrue(permissions.itemIdsFor(null, GearSlot.WEAPON).isEmpty());
		Assert.assertTrue(permissions.itemIdsFor(plain(GearSlot.WEAPON, "rune"), null).isEmpty());
	}

	/** Fail-open: an unbuilt card database must permit nothing rather than throw. */
	@Test
	public void anUnreadyDatabaseGrantsNothingAndDoesNotThrow()
	{
		CardDatabase notReady = new CardDatabase(null, null, null, null, null);
		PermissionService cold = new PermissionService(stateService, notReady, null, config);
		Assert.assertTrue(cold.itemIdsFor(plain(GearSlot.WEAPON, "rune"), GearSlot.WEAPON)
			.isEmpty());
	}
}
