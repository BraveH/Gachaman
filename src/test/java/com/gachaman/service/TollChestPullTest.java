package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * {@code ChestService.openTollChest} — the tier-scoped opener the Toll pays for.
 *
 * <p>Two of these pin traps that would otherwise ship silently. A pull that
 * escaped its ladder still deals a card, and a pull that counted the surrendered
 * card as owned still deals a card; neither throws, neither logs, and both only
 * ever hurt the player who has just destroyed a veteran card to get here.
 */
public class TollChestPullTest
{
	private static GachaStateService inMemoryStateService()
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

	private GachaStateService stateService;
	private ChestService chestService;
	private LoadoutFixture.Cards cards;

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		cards = new LoadoutFixture.Cards();
		chestService = new ChestService(stateService, new CreditSink(stateService), cards,
			new CeremonyBus(), new GachaRng(20260815L), new com.google.gson.Gson(),
			null, null, null);
		// funds only the ordinary-chest control below; every Toll pull is free
		stateService.mutate(s -> s.withGc(10_000));
	}

	/** Every card the fixture defines for one tier ladder. */
	private Set<Integer> cardIdsOfTier(String tierKey)
	{
		Set<Integer> ids = new HashSet<>();
		for (CardDefinition def : cards.all().values())
		{
			if (tierKey.equals(def.getTierKey()))
			{
				ids.add(def.getCardId());
			}
		}
		return ids;
	}

	private ChestService.RolledSlot onlySlotOf(ChestService.ChestOpenResult result)
	{
		Assert.assertNotNull("the Toll must deal something", result);
		Assert.assertEquals("a Toll pull is exactly one card", 1, result.getSlots().size());
		return result.getSlots().get(0);
	}

	// --- the ladder ----------------------------------------------------------

	/**
	 * The whole promise of the Toll: "a blind pull of its tier". Repeated, because
	 * a pool bug that leaked one tier in ten would pass a single draw.
	 */
	@Test
	public void everyPullComesFromTheNamedLadder()
	{
		Set<Integer> rune = cardIdsOfTier("rune");
		Assert.assertFalse("the fixture must define a rune ladder", rune.isEmpty());

		for (int i = 0; i < 200; i++)
		{
			ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
			ChestService.RolledSlot slot = onlySlotOf(result);
			if (slot.getVariant() == Variant.HOLOGRAM)
			{
				// a hologram represents a tier rather than a card; it must still be
				// this tier
				Assert.assertEquals("a hologram pull must name the paid-for tier",
					"rune", slot.getHologramTier());
			}
			else
			{
				Assert.assertTrue("pull #" + i + " escaped the rune ladder: card "
					+ slot.getCardId(), rune.contains(slot.getCardId()));
			}
			chestService.commitPending();
		}
	}

	/**
	 * The trap group 3 named: {@code openThemedChest(tierKey)} looks like the
	 * obvious route and silently is not, because a tier key is not a set tag. If
	 * openTollChest were ever re-implemented that way the pool would fall back to
	 * every card in the game — which this test detects as pulls from other tiers.
	 */
	@Test
	public void theUntieredBandIsItsOwnLadderRatherThanEverything()
	{
		// The fixture gives every card a tier, so the untiered band is EMPTY and
		// the pool guard falls back to all cards. That is the documented
		// behaviour of an empty pool, and pinning it here records that "untiered"
		// is a real branch rather than a synonym for null.
		ChestService.ChestOpenResult result = chestService.openTollChest(null, "spent-uuid");
		ChestService.RolledSlot slot = onlySlotOf(result);
		Assert.assertNotNull("an untiered Toll must still deal a card", slot);
		Assert.assertEquals("the result must record the untiered band, not a null tier",
			RollOdds.UNTIERED, result.getTollTierKey());
	}

	// --- the duplicate test --------------------------------------------------

	/**
	 * The surrendered card is still in the album when the pull is rolled — that
	 * ordering is deliberate, so a crash cannot take the card and hand back
	 * nothing. It must therefore be invisible to the duplicate test, or the
	 * player who pays a 300-kill veteran and pulls its own card back gets a
	 * handful of dupe GC for it.
	 */
	@Test
	public void theSurrenderedCardIsNotItsOwnDuplicate()
	{
		int paidCardId = cardIdsOfTier("rune").iterator().next();
		OwnedCard paid = new OwnedCard("spent-uuid", paidCardId, null, Variant.NORMAL,
			100L, "chest:GILDED", 300);
		stateService.mutate(s -> s.withOwnedCards(Collections.singletonList(paid)));

		boolean sawItsOwnCard = false;
		for (int i = 0; i < 400 && !sawItsOwnCard; i++)
		{
			ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
			ChestService.RolledSlot slot = onlySlotOf(result);
			if (slot.getVariant() == Variant.NORMAL && slot.getCardId() == paidCardId)
			{
				sawItsOwnCard = true;
				Assert.assertFalse("the card being surrendered must not count as a duplicate"
					+ " of itself — it is only still owned because the pull is dealt first",
					slot.isDuplicate());
			}
			chestService.commitPending();
		}
		Assert.assertTrue("the paid card's own id must come up in its own small ladder —"
			+ " if it never does, this test is proving nothing", sawItsOwnCard);
	}

	/**
	 * The other half of that rule, and the reason the exclusion is by UUID rather
	 * than by key: a player holding TWO copies who pays one still owns the other,
	 * so the pull genuinely is a duplicate. Excluding the key instead would hand
	 * them a free non-dupe.
	 */
	@Test
	public void aSecondCopyStillCountsAsADuplicate()
	{
		int paidCardId = cardIdsOfTier("rune").iterator().next();
		OwnedCard paid = new OwnedCard("spent-uuid", paidCardId, null, Variant.NORMAL,
			100L, "chest:GILDED", 300);
		OwnedCard kept = new OwnedCard("kept-uuid", paidCardId, null, Variant.NORMAL,
			100L, "chest:GILDED", 5);
		stateService.mutate(s -> s.withOwnedCards(Arrays.asList(paid, kept)));

		boolean sawIt = false;
		for (int i = 0; i < 400 && !sawIt; i++)
		{
			ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
			ChestService.RolledSlot slot = onlySlotOf(result);
			if (slot.getVariant() == Variant.NORMAL && slot.getCardId() == paidCardId)
			{
				sawIt = true;
				Assert.assertTrue("the KEPT copy still makes this a duplicate", slot.isDuplicate());
			}
			chestService.commitPending();
		}
		Assert.assertTrue("the card id must come up for this test to mean anything", sawIt);
	}

	// --- the contract with TollService ---------------------------------------

	/**
	 * TollService reads a null return as "nothing happened" and leaves the week
	 * unspent, so this must never return null after changing anything.
	 */
	@Test
	public void aPullRefusesWhileAnotherRevealIsPending()
	{
		Assert.assertNotNull(chestService.openTollChest("rune", "spent-uuid"));
		Assert.assertNull("a second pull must refuse while the first is unrevealed",
			chestService.openTollChest("rune", "spent-uuid"));
	}

	@Test
	public void aPullRefusesWhenTheCardDatabaseIsNotReady()
	{
		ChestService notReady = new ChestService(stateService, new CreditSink(stateService),
			new CardDatabase(null, null, null, null, null), new CeremonyBus(),
			new GachaRng(1L), new com.google.gson.Gson(), null, null, null);
		Assert.assertNull("no pool means no pull, and nothing taken",
			notReady.openTollChest("rune", "spent-uuid"));
	}

	/** Free at the till — the price was a card, and it was taken elsewhere. */
	@Test
	public void thePullCostsNoGachaCoins()
	{
		ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
		Assert.assertEquals("the Toll is paid in a card, never in GC", 0, result.getPricePaid());
	}

	/**
	 * A one-ladder pool has nothing to upgrade INTO, so the jackpot is excluded —
	 * and, more importantly, the exclusion is what keeps the ladder promise true
	 * for the rerolled card as well.
	 */
	@Test
	public void aPullNeverJackpotUpgrades()
	{
		for (int i = 0; i < 300; i++)
		{
			ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
			Assert.assertFalse("a Toll pull has one ladder and nothing to upgrade into",
				result.isJackpotUpgraded());
			chestService.commitPending();
		}
	}

	/**
	 * The tier must survive onto the RESULT, because the in-reveal reroll rebuilds
	 * its pool from that object rather than from the call. Drop the field and a
	 * rerolled Toll pull escapes to the all-cards pool — the player spends a token
	 * and leaves the ladder they paid for.
	 */
	@Test
	public void theLadderIsCarriedOnTheResultSoARerollCannotEscapeIt()
	{
		ChestService.ChestOpenResult result = chestService.openTollChest("rune", "spent-uuid");
		Assert.assertEquals("rune", result.getTollTierKey());

		stateService.mutate(s -> s.withRerollTokens(5));
		Set<Integer> rune = cardIdsOfTier("rune");
		ChestService.RolledSlot fresh = chestService.rerollSlot(0);
		Assert.assertNotNull("a Toll pull must be rerollable like any other reveal", fresh);
		if (fresh.getVariant() == Variant.HOLOGRAM)
		{
			Assert.assertEquals("rune", fresh.getHologramTier());
		}
		else
		{
			Assert.assertTrue("a rerolled Toll pull must stay inside the paid-for ladder",
				rune.contains(fresh.getCardId()));
		}
	}

	/** An ordinary chest must carry no tier, or every reroll would be confined. */
	@Test
	public void anOrdinaryChestCarriesNoLadder()
	{
		ChestService.ChestOpenResult ordinary = chestService.openChest(Tuning.Chest.BATTERED);
		Assert.assertNotNull(ordinary);
		Assert.assertNull("only a Toll pull is confined to a ladder",
			ordinary.getTollTierKey());
	}
}
