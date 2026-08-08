package com.gachaman.service;

import com.gachaman.model.OwnedCard;
import com.gachaman.model.Variant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Service Record's pure rules. "Present for" means the owned card's uuid
 * was assigned to a loadout slot when the kill was credited — these tests pin
 * that reading, not any particular contract state.
 */
public class ServiceRecordServiceTest
{
	private static OwnedCard card(String uuid, int cardId, int killsServed)
	{
		return new OwnedCard(uuid, cardId, null, Variant.NORMAL, 100L, "chest:GILDED", killsServed);
	}

	// --- creditKill ---

	@Test
	public void creditKillCountsEachAssignedCardOnce()
	{
		Map<String, String> loadout = new LinkedHashMap<>();
		loadout.put("WEAPON", "u1");
		loadout.put("BODY", "u2");
		Map<String, Integer> tally = new HashMap<>();

		ServiceRecordService.creditKill(tally, loadout);
		ServiceRecordService.creditKill(tally, loadout);

		Assert.assertEquals(2, tally.size());
		Assert.assertEquals(Integer.valueOf(2), tally.get("u1"));
		Assert.assertEquals(Integer.valueOf(2), tally.get("u2"));
	}

	@Test
	public void creditKillCountsADuplicatedUuidOnce()
	{
		// a card that somehow occupied two slots still served ONE kill
		Map<String, String> loadout = new LinkedHashMap<>();
		loadout.put("WEAPON", "u1");
		loadout.put("SHIELD", "u1");
		Map<String, Integer> tally = new HashMap<>();

		ServiceRecordService.creditKill(tally, loadout);

		Assert.assertEquals(1, tally.size());
		Assert.assertEquals(Integer.valueOf(1), tally.get("u1"));
	}

	@Test
	public void creditKillIgnoresNullAndEmptyLoadout()
	{
		Map<String, Integer> tally = new HashMap<>();
		ServiceRecordService.creditKill(tally, null);
		ServiceRecordService.creditKill(tally, Collections.emptyMap());
		Assert.assertTrue(tally.isEmpty());
	}

	@Test
	public void creditKillSkipsNullSlotValues()
	{
		Map<String, String> loadout = new HashMap<>();
		loadout.put("WEAPON", null);
		loadout.put("BODY", "u2");
		Map<String, Integer> tally = new HashMap<>();

		ServiceRecordService.creditKill(tally, loadout);

		Assert.assertEquals(1, tally.size());
		Assert.assertEquals(Integer.valueOf(1), tally.get("u2"));
	}

	// --- applyTally ---

	@Test
	public void applyTallyCreditsOnlyMatchingCardsAndPreservesOrder()
	{
		List<OwnedCard> cards = Arrays.asList(card("u1", 11, 0), card("u2", 22, 0), card("u3", 33, 0));
		Map<String, Integer> tally = new HashMap<>();
		tally.put("u2", 5);

		List<OwnedCard> out = ServiceRecordService.applyTally(cards, tally);

		Assert.assertNotNull(out);
		Assert.assertEquals(3, out.size());
		Assert.assertEquals("u1", out.get(0).getUuid());
		Assert.assertEquals("u2", out.get(1).getUuid());
		Assert.assertEquals("u3", out.get(2).getUuid());
		Assert.assertEquals(0, out.get(0).getKillsServed());
		Assert.assertEquals(5, out.get(1).getKillsServed());
		Assert.assertEquals(0, out.get(2).getKillsServed());
		// every other field of the rewritten card survives untouched
		OwnedCard credited = out.get(1);
		Assert.assertEquals(22, credited.getCardId());
		Assert.assertNull(credited.getTierKey());
		Assert.assertEquals(Variant.NORMAL, credited.getVariant());
		Assert.assertEquals(100L, credited.getAcquiredAtMs());
		Assert.assertEquals("chest:GILDED", credited.getProvenance());
		// untouched cards are the very same instances, not equal copies
		Assert.assertSame(cards.get(0), out.get(0));
		Assert.assertSame(cards.get(2), out.get(2));
	}

	@Test
	public void applyTallyAccumulatesOnTopOfAnExistingRecord()
	{
		// the counter is monotonic — a flush ADDS, it never replaces
		List<OwnedCard> cards = Collections.singletonList(card("u1", 11, 400));
		Map<String, Integer> tally = new HashMap<>();
		tally.put("u1", 3);

		List<OwnedCard> out = ServiceRecordService.applyTally(cards, tally);

		Assert.assertNotNull(out);
		Assert.assertEquals(403, out.get(0).getKillsServed());
	}

	@Test
	public void applyTallyReturnsNullWhenNothingMatched()
	{
		// null hands mutate() an unchanged state, so a no-op flush costs no
		// gzip + SHA-256 encode at all
		List<OwnedCard> cards = Collections.singletonList(card("u1", 11, 0));
		Map<String, Integer> unowned = new HashMap<>();
		unowned.put("gone", 4);

		Assert.assertNull(ServiceRecordService.applyTally(cards, unowned));
		Assert.assertNull(ServiceRecordService.applyTally(cards, new HashMap<>()));
		Assert.assertNull(ServiceRecordService.applyTally(null, unowned));
		Assert.assertNull(ServiceRecordService.applyTally(Collections.emptyList(), unowned));
	}

	@Test
	public void applyTallyDropsUuidsNoLongerOwned()
	{
		// a card burned by prestige between the kill and the flush: its service
		// died with it, and the survivor still gets its own credit
		List<OwnedCard> cards = Collections.singletonList(card("alive", 11, 2));
		Map<String, Integer> tally = new HashMap<>();
		tally.put("alive", 1);
		tally.put("burned", 9);

		List<OwnedCard> out = ServiceRecordService.applyTally(cards, tally);

		Assert.assertNotNull(out);
		Assert.assertEquals(1, out.size());
		Assert.assertEquals(3, out.get(0).getKillsServed());
	}

	// --- bestByCardId ---

	@Test
	public void bestByCardIdTakesTheMaxAcrossCopies()
	{
		// the album cell is per card DEFINITION while the record is per copy:
		// the veteran copy is the one the player means
		List<OwnedCard> cards = Arrays.asList(
			new OwnedCard("u1", 1333, null, Variant.NORMAL, 1L, "chest:GILDED", 400),
			new OwnedCard("u2", 1333, null, Variant.SHINY, 2L, "chest:ORNATE", 0));

		Map<Integer, Integer> best = ServiceRecordService.bestByCardId(cards);

		Assert.assertEquals(1, best.size());
		Assert.assertEquals(Integer.valueOf(400), best.get(1333));
	}

	@Test
	public void bestByCardIdSkipsHologramsAndHandlesNull()
	{
		List<OwnedCard> cards = Arrays.asList(
			new OwnedCard("u1", 1333, null, Variant.NORMAL, 1L, "chest:GILDED", 7),
			new OwnedCard("u2", -1, "dragon", Variant.HOLOGRAM, 2L, "chest:ORNATE", 99));

		Map<Integer, Integer> best = ServiceRecordService.bestByCardId(cards);

		Assert.assertEquals(1, best.size());
		Assert.assertEquals(Integer.valueOf(7), best.get(1333));
		Assert.assertFalse("hologram cardId -1 must never enter the grid map", best.containsKey(-1));

		Assert.assertTrue(ServiceRecordService.bestByCardId(null).isEmpty());
		Assert.assertTrue(ServiceRecordService.bestByCardId(Collections.emptyList()).isEmpty());
	}
}
