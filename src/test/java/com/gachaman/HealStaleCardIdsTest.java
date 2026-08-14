package com.gachaman;

import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import org.junit.*;

/**
 * The card-DB heal's remap-then-dedupe rule, in BOTH list orders.
 *
 * <p>A cache upgrade can merge two card groups into one, which leaves an owned
 * copy pointing at a card id that no longer exists. That copy is remapped
 * through the item index, and if the remap lands on a card the player already
 * owns in the same variant, the stale copy is dropped.
 *
 * <p>The regression these tests exist for: the drop used to depend on where
 * the stale copy sat in the list. Owned cards are stored in acquisition order
 * and the stale copy — minted under the older card DB — is almost always the
 * OLDER of the two, so in the common case NEITHER copy was dropped and the
 * album showed the duplicate twice. Every ordering assertion below is there to
 * keep that from coming back.
 */
public class HealStaleCardIdsTest
{
	private static final int MERGED = 4708; // the card that survived the merge
	private static final int STALE = 4712;  // an id the merge dissolved

	private static OwnedCard card(String uuid, int cardId, Variant variant, int killsServed)
	{
		return new OwnedCard(uuid, cardId, null, variant, 1L, "chest:GILDED", killsServed);
	}

	private static OwnedCard holo(String uuid, String tierKey)
	{
		return new OwnedCard(uuid, -1, tierKey, Variant.HOLOGRAM, 1L, "chest:ORNATE", 0);
	}

	/** A stand-in for the card DB: only the listed ids are stale. */
	private static IntUnaryOperator merges(int... staleToSurvivor)
	{
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < staleToSurvivor.length; i += 2)
		{
			map.put(staleToSurvivor[i], staleToSurvivor[i + 1]);
		}
		return id -> map.getOrDefault(id, -1);
	}

	// --- the ordering bug ---

	@Test
	public void staleCopyIsDroppedWhenItComesFirst()
	{
		// the common case: the stale copy was acquired first, so it leads
		List<OwnedCard> owned = Arrays.asList(
			card("stale", STALE, Variant.NORMAL, 0),
			card("genuine", MERGED, Variant.NORMAL, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(owned, merges(STALE, MERGED));

		Assert.assertNotNull(out);
		Assert.assertEquals("the remapped duplicate must not survive alongside its twin",
			1, out.size());
		Assert.assertEquals("genuine", out.get(0).getUuid());
		Assert.assertEquals(MERGED, out.get(0).getCardId());
	}

	@Test
	public void staleCopyIsDroppedWhenItComesSecond()
	{
		// the order that already worked — pinned so a fix cannot trade one for
		// the other
		List<OwnedCard> owned = Arrays.asList(
			card("genuine", MERGED, Variant.NORMAL, 0),
			card("stale", STALE, Variant.NORMAL, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(owned, merges(STALE, MERGED));

		Assert.assertNotNull(out);
		Assert.assertEquals(1, out.size());
		Assert.assertEquals("genuine", out.get(0).getUuid());
	}

	@Test
	public void twoStaleCopiesOfTheSameCardCollapseToOne()
	{
		// no healthy copy at all: one remapped survivor, not two
		List<OwnedCard> owned = Arrays.asList(
			card("a", STALE, Variant.NORMAL, 0),
			card("b", 4713, Variant.NORMAL, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(
			owned, merges(STALE, MERGED, 4713, MERGED));

		Assert.assertNotNull(out);
		Assert.assertEquals(1, out.size());
		Assert.assertEquals(MERGED, out.get(0).getCardId());
	}

	// --- what the drop must NOT touch ---

	@Test
	public void preExistingHealthyDuplicatesAreLeftAlone()
	{
		// two genuine copies of one card is a legal thing to own — the heal is
		// not a general dedupe pass and must never eat one of them
		List<OwnedCard> owned = Arrays.asList(
			card("dup1", MERGED, Variant.NORMAL, 0),
			card("dup2", MERGED, Variant.NORMAL, 0),
			card("stale", STALE, Variant.NORMAL, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(
			owned, merges(STALE, 5000)); // remaps somewhere else entirely

		Assert.assertNotNull(out);
		Assert.assertEquals(3, out.size());
		Assert.assertEquals("dup1", out.get(0).getUuid());
		Assert.assertEquals("dup2", out.get(1).getUuid());
		Assert.assertEquals(5000, out.get(2).getCardId());
	}

	@Test
	public void aDifferentVariantOfTheSameCardIsNotADuplicate()
	{
		// the key is (card, variant): a shiny and a normal are two collectibles
		List<OwnedCard> owned = Arrays.asList(
			card("stale", STALE, Variant.NORMAL, 0),
			card("shiny", MERGED, Variant.SHINY, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(owned, merges(STALE, MERGED));

		Assert.assertNotNull(out);
		Assert.assertEquals(2, out.size());
		Assert.assertEquals(MERGED, out.get(0).getCardId());
		Assert.assertEquals(Variant.NORMAL, out.get(0).getVariant());
		Assert.assertEquals(Variant.SHINY, out.get(1).getVariant());
	}

	@Test
	public void hologramsAreNeverRemappedOrDropped()
	{
		// holograms carry cardId -1 and key on their tier; two of a tier is the
		// player's business, not the heal's
		List<OwnedCard> owned = Arrays.asList(
			holo("h1", "dragon"),
			holo("h2", "dragon"),
			card("stale", STALE, Variant.NORMAL, 0));

		List<OwnedCard> out = GachamanPlugin.healCardIds(owned, merges(STALE, MERGED, -1, 99));

		Assert.assertNotNull(out);
		Assert.assertEquals(3, out.size());
		Assert.assertEquals(-1, out.get(0).getCardId());
		Assert.assertEquals(-1, out.get(1).getCardId());
		Assert.assertEquals("dragon", out.get(0).getTierKey());
	}

	// --- what the remap carries ---

	@Test
	public void remapKeepsTheServiceRecordAndEverySoftField()
	{
		List<OwnedCard> owned = Collections.singletonList(
			new OwnedCard("u1", STALE, null, Variant.SHINY, 12345L, "shop:2026-W32", 400));

		List<OwnedCard> out = GachamanPlugin.healCardIds(owned, merges(STALE, MERGED));

		Assert.assertNotNull(out);
		Assert.assertEquals(1, out.size());
		OwnedCard healed = out.get(0);
		Assert.assertEquals(MERGED, healed.getCardId());
		Assert.assertEquals("u1", healed.getUuid()); // loadouts key on the uuid
		Assert.assertEquals(400, healed.getKillsServed()); // the odometer never resets
		Assert.assertEquals(Variant.SHINY, healed.getVariant());
		Assert.assertEquals(12345L, healed.getAcquiredAtMs());
		Assert.assertEquals("shop:2026-W32", healed.getProvenance());
	}

	// --- the null contract ---

	@Test
	public void nullIsReturnedWhenThereIsNothingToHeal()
	{
		// null hands mutate() an unchanged state, so a clean load costs no
		// re-encode of the save at all
		List<OwnedCard> healthy = Arrays.asList(
			card("a", MERGED, Variant.NORMAL, 0),
			card("b", MERGED, Variant.NORMAL, 0));

		Assert.assertNull(GachamanPlugin.healCardIds(healthy, merges()));
		Assert.assertNull(GachamanPlugin.healCardIds(null, merges(STALE, MERGED)));
		Assert.assertNull(GachamanPlugin.healCardIds(
			Collections.emptyList(), merges(STALE, MERGED)));
	}

	@Test
	public void anUnrescuableStaleIdIsKeptAsIs()
	{
		// the id resolves to nothing at all: leave the copy alone rather than
		// silently deleting a card the player owns
		List<OwnedCard> owned = Collections.singletonList(card("u1", STALE, Variant.NORMAL, 0));

		Assert.assertNull(GachamanPlugin.healCardIds(owned, merges()));
	}
}
