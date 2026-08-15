package com.gachaman.service;

import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

/**
 * Who the house names: the style with the fewest owned weapon cards.
 *
 * <p>The tie-break gets first-class treatment here rather than a footnote,
 * because it is the common path and not the corner case. The pools come from
 * {@code CardDatabase.weaponCardIdsForStyle}, which returns COMMON weapons only,
 * so a young album sits at 0/0/0 and the earliest Consignments a player ever
 * sees are decided by the break alone.
 */
public class ConsignmentWorstDressedTest
{
	private static OwnedCard card(int cardId)
	{
		return new OwnedCard("u" + cardId + "-" + UUID.randomUUID(), cardId, null,
			Variant.NORMAL, 0L, "test", 0);
	}

	private static Map<AttackStyle, Set<Integer>> pools(int[] melee, int[] ranged, int[] magic)
	{
		Map<AttackStyle, Set<Integer>> out = new EnumMap<>(AttackStyle.class);
		out.put(AttackStyle.MELEE, ids(melee));
		out.put(AttackStyle.RANGED, ids(ranged));
		out.put(AttackStyle.MAGIC, ids(magic));
		return out;
	}

	private static Set<Integer> ids(int[] raw)
	{
		Set<Integer> out = new HashSet<>();
		for (int id : raw)
		{
			out.add(id);
		}
		return out;
	}

	@Test
	public void theFewestOwnedWeaponCardsWins()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2, 3}, new int[]{10, 11, 12}, new int[]{20, 21, 22});
		List<OwnedCard> album = Arrays.asList(
			card(1), card(2), card(3),   // melee: 3
			card(10),                    // ranged: 1
			card(20), card(21));         // magic: 2

		Assert.assertEquals(AttackStyle.RANGED,
			ConsignmentService.worstDressed(pools, album));
	}

	/**
	 * Ties go to the earliest declared style. Not a coin flip and not a seeded
	 * draw: the offer is recomputed for the panel as well as for the ceremony,
	 * and anything random would have to persist a seed to stop the two disagreeing
	 * — where a rule the player can learn costs nothing and never drifts.
	 */
	@Test
	public void anEmptyAlbumIsWorstDressedForMeleeByDeclarationOrder()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2}, new int[]{10, 11}, new int[]{20, 21});

		Assert.assertEquals("0/0/0 is the normal early-game state, not an edge case",
			AttackStyle.MELEE, ConsignmentService.worstDressed(pools, Collections.emptyList()));
	}

	@Test
	public void aTieBetweenTheLaterTwoGoesToRanged()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2}, new int[]{10, 11}, new int[]{20, 21});
		List<OwnedCard> album = Arrays.asList(card(1), card(10), card(20));

		// melee 1, ranged 1, magic 1 — a three-way tie
		Assert.assertEquals(AttackStyle.MELEE, ConsignmentService.worstDressed(pools, album));

		// give melee one more: ranged and magic tie at 1, ranged is declared first
		List<OwnedCard> richerMelee = new ArrayList<>(album);
		richerMelee.add(card(2));
		Assert.assertEquals(AttackStyle.RANGED,
			ConsignmentService.worstDressed(pools, richerMelee));
	}

	/**
	 * Copies of one card do not dress anybody twice. Counting copies would let a
	 * run of duplicate pulls quietly move a style out of last place without the
	 * player having gained a single new weapon.
	 */
	@Test
	public void duplicateCopiesOfOneCardCountOnce()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2, 3}, new int[]{10, 11, 12}, new int[]{20, 21, 22});
		List<OwnedCard> album = Arrays.asList(
			card(1), card(2),               // melee: 2 distinct
			card(10), card(10), card(10),   // ranged: 3 copies of ONE card
			card(20), card(21));            // magic: 2 distinct

		// counting copies would make ranged the best dressed at 3 and hand the
		// offer to melee; counting distinct cards puts ranged last at 1
		Assert.assertEquals(AttackStyle.RANGED,
			ConsignmentService.worstDressed(pools, album));
		Assert.assertEquals(1, ConsignmentService.ownedFrom(
			pools.get(AttackStyle.RANGED), album));
	}

	/**
	 * A hologram names a whole tier rather than a weapon. It genuinely does dress
	 * a slot, but this measure is "how many weapons has this style been given",
	 * and a tier card answers a different question — so it is skipped even when
	 * its card id would match.
	 */
	@Test
	public void hologramsAreNotWeaponCards()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2}, new int[]{10, 11}, new int[]{20, 21});
		List<OwnedCard> album = Arrays.asList(
			card(1),
			new OwnedCard("holo", 10, "rune", Variant.HOLOGRAM, 0L, "test", 0),
			card(20));

		Assert.assertEquals("the hologram must not count as ranged's weapon card",
			AttackStyle.RANGED, ConsignmentService.worstDressed(pools, album));
		Assert.assertEquals(0, ConsignmentService.ownedFrom(
			pools.get(AttackStyle.RANGED), album));
	}

	@Test
	public void theAnswerDoesNotDependOnTheOrderTheAlbumHappensToBeIn()
	{
		Map<AttackStyle, Set<Integer>> pools = pools(
			new int[]{1, 2, 3}, new int[]{10, 11, 12}, new int[]{20, 21, 22});
		List<OwnedCard> album = new ArrayList<>(Arrays.asList(
			card(1), card(2), card(10), card(20), card(21), card(22)));

		AttackStyle first = ConsignmentService.worstDressed(pools, album);
		Assert.assertEquals(AttackStyle.RANGED, first);
		for (int i = 0; i < 25; i++)
		{
			Collections.shuffle(album, new Random(i));
			Assert.assertEquals("the house must name the same style every time it is asked",
				first, ConsignmentService.worstDressed(pools, album));
		}
	}

	/**
	 * Never null, whatever it is handed. A null answer would send offerOrRoll
	 * down the "cannot offer" path, which is a safe outcome — but it would be
	 * silently safe, and a style is always nameable.
	 */
	@Test
	public void degenerateInputStillNamesAStyle()
	{
		Assert.assertEquals(AttackStyle.MELEE,
			ConsignmentService.worstDressed(new EnumMap<>(AttackStyle.class), null));
		Assert.assertEquals(AttackStyle.MELEE,
			ConsignmentService.worstDressed(null, Collections.emptyList()));
		Assert.assertEquals(0, ConsignmentService.ownedFrom(null, null));
		Assert.assertEquals(0, ConsignmentService.ownedFrom(Collections.emptySet(),
			Collections.singletonList(card(1))));
	}
}
