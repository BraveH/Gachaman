package com.gachaman.service;

import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import org.junit.*;

/**
 * The Toll's pure rules.
 *
 * <p>The headline invariant is stability: the pick is PERSISTED, not recomputed
 * from a seed, because the pool is the player's album and an album grows during
 * the week. {@link #pickIsStableAsTheAlbumGrows} is the test a naive port of
 * WeeklyShopService's zero-stored-state trick fails, and it is first on purpose.
 */
public class TollServiceTest
{
	private static final String WEEK = "2026-W33";
	private static final String NEXT_WEEK = "2026-W34";
	private static final ToIntFunction<String> NO_PENDING = uuid -> 0;

	/** Fixed seed: these tests pin behaviour, never a particular draw. */
	private static final long SEED = TollService.seedFor("profile-a", WEEK);

	private static OwnedCard card(String uuid, int cardId, int killsServed)
	{
		return new OwnedCard(uuid, cardId, null, Variant.NORMAL, 100L, "chest:GILDED", killsServed);
	}

	private static OwnedCard hologram(String uuid, String tierKey, int killsServed)
	{
		return new OwnedCard(uuid, -1, tierKey, Variant.HOLOGRAM, 100L, "chest:ORNATE", killsServed);
	}

	/** An album of six veteran cards — every one of them a legal Toll. */
	private static List<OwnedCard> veterans()
	{
		List<OwnedCard> cards = new ArrayList<>();
		for (int i = 0; i < 6; i++)
		{
			cards.add(card("vet-" + i, 1000 + i, 40 + i));
		}
		return cards;
	}

	private static ToIntFunction<String> pending(Map<String, Integer> banked)
	{
		return uuid -> banked.getOrDefault(uuid, 0);
	}

	// --- stability across a week (the reason this class stores its pick) ---

	@Test
	public void pickIsStableAsTheAlbumGrows()
	{
		List<OwnedCard> album = veterans();
		TollService.Pick first = TollService.resolve(WEEK, null, null, album, NO_PENDING, SEED);
		Assert.assertNotNull("a six-veteran album must offer a Toll", first.getUuid());
		Assert.assertTrue("the first pick of a week must be persisted", first.isRewrite());
		final String named = first.getUuid();

		// the week goes on: ten chests open, and three of those cards cross their
		// first kill of service. Both kinds of growth move the seeded index.
		for (int i = 0; i < 10; i++)
		{
			album.add(card("new-" + i, 2000 + i, 0));
		}
		Map<String, Integer> banked = new HashMap<>();
		for (int i = 0; i < 3; i++)
		{
			banked.put("new-" + i, 1);
		}

		TollService.Pick later = TollService.resolve(WEEK, WEEK, named, album, pending(banked), SEED);

		Assert.assertEquals("the named card must not change mid-week", named, later.getUuid());
		Assert.assertFalse("a pick that still stands must not be rewritten", later.isRewrite());
	}

	@Test
	public void aRecomputedPickWouldHaveDriftedOnTheGrownAlbum()
	{
		// The other half of the test above: it only proves something because the
		// grown album really does index differently under the very same seed. If
		// this ever stops holding, the stability test has quietly become vacuous.
		List<OwnedCard> album = veterans();
		String before = TollService.resolve(WEEK, null, null, album, NO_PENDING, SEED).getUuid();

		for (int i = 0; i < 10; i++)
		{
			album.add(card("new-" + i, 2000 + i, 1));
		}
		String recomputed = TollService.resolve(WEEK, null, null, album, NO_PENDING, SEED).getUuid();

		Assert.assertNotEquals("same seed, grown pool: the naive port re-picks", before, recomputed);
	}

	// --- the three re-pick triggers, and nothing else ---

	@Test
	public void rePicksWhenTheWeekTurns()
	{
		List<OwnedCard> album = veterans();
		TollService.Pick next = TollService.resolve(
			NEXT_WEEK, WEEK, "vet-0", album, NO_PENDING, TollService.seedFor("profile-a", NEXT_WEEK));

		Assert.assertNotNull(next.getUuid());
		Assert.assertTrue("a new week's pick must be persisted", next.isRewrite());
	}

	@Test
	public void rePicksWhenTheNamedCardIsNoLongerOwned()
	{
		List<OwnedCard> album = veterans();
		album.removeIf(c -> "vet-0".equals(c.getUuid()));

		TollService.Pick fresh = TollService.resolve(WEEK, WEEK, "vet-0", album, NO_PENDING, SEED);

		Assert.assertNotNull(fresh.getUuid());
		Assert.assertNotEquals("vet-0", fresh.getUuid());
		Assert.assertTrue(fresh.isRewrite());
	}

	@Test
	public void doesNotRePickOnceThisWeeksTollIsPaid()
	{
		// THE anti-re-arm rule. purchase() consumes the card and stores a null uuid
		// under the current week key. Check the null uuid AFTER the ownership test
		// instead of before and the spent card reads as "no longer owned", the Toll
		// re-picks, and the player pays it again and again inside one week.
		List<OwnedCard> album = veterans();

		TollService.Pick paid = TollService.resolve(WEEK, WEEK, null, album, NO_PENDING, SEED);

		Assert.assertNull("a paid week offers nothing, however full the album is", paid.getUuid());
		Assert.assertFalse("and writes nothing", paid.isRewrite());
	}

	@Test
	public void nothingElseRePicks()
	{
		// A live pick survives everything that is not "the week turned" or "the
		// card is gone": more cards, more service on other cards, more service on
		// the named card itself, and unflushed kills appearing anywhere.
		List<OwnedCard> album = veterans();
		album.add(card("late", 3000, 0));
		Map<String, Integer> banked = new HashMap<>();
		banked.put("late", 5);
		banked.put("vet-3", 12);

		TollService.Pick stands = TollService.resolve(
			WEEK, WEEK, "vet-3", album, pending(banked), SEED);

		Assert.assertEquals("vet-3", stands.getUuid());
		Assert.assertFalse(stands.isRewrite());
	}

	@Test
	public void anEmptyPoolWritesNothingSoTheWeekStaysOpen()
	{
		// Recording the week key with a null uuid here would be indistinguishable
		// from PAID and would suppress the Toll for the whole week — including for
		// the player whose first card earns its first kill on the Tuesday.
		List<OwnedCard> junkOnly = new ArrayList<>(Arrays.asList(
			card("j1", 10, 0), card("j2", 11, 0)));

		TollService.Pick none = TollService.resolve(WEEK, null, null, junkOnly, NO_PENDING, SEED);

		Assert.assertNull(none.getUuid());
		Assert.assertFalse("no eligible card must leave both fields untouched", none.isRewrite());

		// ...and the moment one of them earns a kill, the Toll arrives
		Map<String, Integer> banked = new HashMap<>();
		banked.put("j2", 1);
		TollService.Pick arrived = TollService.resolve(
			WEEK, null, null, junkOnly, pending(banked), SEED);
		Assert.assertEquals("j2", arrived.getUuid());
		Assert.assertTrue(arrived.isRewrite());
	}

	@Test
	public void handlesANullOrEmptyAlbum()
	{
		Assert.assertNull(TollService.resolve(WEEK, null, null, null, NO_PENDING, SEED).getUuid());
		Assert.assertNull(TollService.resolve(
			WEEK, null, null, Collections.emptyList(), NO_PENDING, SEED).getUuid());
	}

	// --- eligibility ---

	@Test
	public void eligibilityCountsUnflushedPendingKills()
	{
		// ServiceRecordService banks kills in memory until flush(), so a card that
		// earned its first kills this session still reads killsServed == 0. Without
		// the pending term the house would call it junk.
		OwnedCard freshlyBlooded = card("u1", 55, 0);

		Assert.assertFalse("no kills at all is junk", TollService.isEligible(freshlyBlooded, 0));
		Assert.assertTrue("one unflushed kill is a Service Record",
			TollService.isEligible(freshlyBlooded, 1));
		Assert.assertTrue("a written record needs no pending kills",
			TollService.isEligible(card("u2", 56, 3), 0));
	}

	@Test
	public void unflushedKillsCanCarryACardIntoThePool()
	{
		List<OwnedCard> album = new ArrayList<>(Arrays.asList(card("only", 77, 0)));
		Map<String, Integer> banked = new HashMap<>();
		banked.put("only", 2);

		List<OwnedCard> pool = TollService.eligible(album, pending(banked));

		Assert.assertEquals(1, pool.size());
		Assert.assertEquals("only", pool.get(0).getUuid());
	}

	@Test
	public void hologramsAreNeverNamed()
	{
		// a hologram represents a whole tier and is the rarest pull in the game;
		// the house does not ask for one, however much service it has seen
		OwnedCard holo = hologram("h1", "dragon", 900);
		Assert.assertFalse(TollService.isEligible(holo, 0));
		Assert.assertFalse(TollService.isEligible(holo, 50));

		List<OwnedCard> album = new ArrayList<>(Arrays.asList(holo, card("plain", 12, 4)));
		Assert.assertEquals(1, TollService.eligible(album, NO_PENDING).size());
		Assert.assertEquals("plain",
			TollService.resolve(WEEK, null, null, album, NO_PENDING, SEED).getUuid());

		// an album of nothing but holograms offers no Toll at all
		Assert.assertNull(TollService.resolve(WEEK, null, null,
			Collections.singletonList(holo), NO_PENDING, SEED).getUuid());
	}

	@Test
	public void aCardAssignedToALoadoutSlotIsStillEligible()
	{
		// The owner's decision: assignment does not enter the eligibility test at
		// all. The player unassigns it first — that refusal happens at purchase
		// time, not by shrinking the pool to whatever they are not wearing.
		OwnedCard worn = card("worn", 99, 120);
		List<OwnedCard> album = new ArrayList<>(Collections.singletonList(worn));
		Map<String, String> loadout = new HashMap<>();
		loadout.put(GearSlot.WEAPON.name(), "worn");

		Assert.assertEquals(1, TollService.eligible(album, NO_PENDING).size());
		Assert.assertEquals("worn",
			TollService.resolve(WEEK, null, null, album, NO_PENDING, SEED).getUuid());
		Assert.assertEquals("and the refusal is the separate concern",
			GearSlot.WEAPON, TollService.assignedSlot(loadout, "worn"));
	}

	@Test
	public void eligibleIsSortedByUuidForDeterminism()
	{
		// the album's own order follows acquisition; a seeded index into an
		// unsorted list would be reproducible only by accident
		List<OwnedCard> album = new ArrayList<>(Arrays.asList(
			card("ccc", 3, 1), card("aaa", 1, 1), card("bbb", 2, 1)));

		List<OwnedCard> pool = TollService.eligible(album, NO_PENDING);

		Assert.assertEquals(Arrays.asList("aaa", "bbb", "ccc"),
			Arrays.asList(pool.get(0).getUuid(), pool.get(1).getUuid(), pool.get(2).getUuid()));
	}

	// --- the assigned-slot refusal ---

	@Test
	public void assignedSlotFindsTheOccupiedSlotOnly()
	{
		Map<String, String> loadout = new HashMap<>();
		loadout.put(GearSlot.WEAPON.name(), "u1");
		loadout.put(GearSlot.BODY.name(), "u2");

		Assert.assertEquals(GearSlot.WEAPON, TollService.assignedSlot(loadout, "u1"));
		Assert.assertEquals(GearSlot.BODY, TollService.assignedSlot(loadout, "u2"));
		Assert.assertNull(TollService.assignedSlot(loadout, "u3"));
		Assert.assertNull(TollService.assignedSlot(null, "u1"));
		Assert.assertNull(TollService.assignedSlot(Collections.emptyMap(), "u1"));
	}

	@Test
	public void theRefusalNamesTheSlotAndIsNotTheUnequipMessage()
	{
		// One intent produces two refusals on two surfaces — unequip, then
		// unassign, then pay. Identical wording would read as one broken button,
		// so this pins the Toll's half of the pair.
		String message = String.format(TollService.ASSIGNED_REFUSAL, GearSlot.WEAPON.getDisplayName());

		Assert.assertEquals("That card is still in your Weapon slot", message);
		Assert.assertFalse("must not duplicate the unassign guard's message",
			message.toLowerCase(Locale.ROOT).contains("take the item off"));
	}

	// --- seeding ---

	@Test
	public void theFirstPickOfAWeekIsDeterministic()
	{
		List<OwnedCard> album = veterans();
		String a = TollService.resolve(WEEK, null, null, album, NO_PENDING,
			TollService.seedFor("profile-a", WEEK)).getUuid();
		String b = TollService.resolve(WEEK, null, null, album, NO_PENDING,
			TollService.seedFor("profile-a", WEEK)).getUuid();

		Assert.assertEquals("same profile, same week, same album -> same card", a, b);
	}

	@Test
	public void theSeedIsSaltedAwayFromTheWeeklyShopsSeed()
	{
		// both mix the same profile key and the same week key; without the salt the
		// two features would walk the same seeded sequence every week
		long shop = WeeklyShopService.splitmix64("profile-a".hashCode() * 31L + WEEK.hashCode());

		Assert.assertNotEquals(shop, TollService.seedFor("profile-a", WEEK));
	}

	@Test
	public void aNullProfileKeyStillSeeds()
	{
		// the profile key is null before login; the Toll must not throw there
		Assert.assertNotEquals(0L, TollService.seedFor(null, WEEK));
	}
}
