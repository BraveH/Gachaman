package com.gachaman.service;

import com.gachaman.model.*;
import com.gachaman.service.WeaponTypeService.WeaponType;
import com.google.gson.*;
import java.util.*;
import org.junit.*;

/**
 * The Preferred Weapon lookup, and above all the property the whole design rests
 * on: an unrecognised category earns NOTHING.
 *
 * <p>There is no default arm anywhere in the chain, no array index, no clamp
 * into range and no remap of an unknown id onto a similar one — so a player
 * holding a category this build has never heard of simply does not get the
 * multiplier that kill. Every path that could turn a miss into a payout is
 * pinned here.
 *
 * <p>{@code WeaponTypeService} dereferences Client to read the category table,
 * and Client is null in every headless test, so the decisions themselves live in
 * pure package-private overloads that take the table as an argument — the same
 * seam {@code StyleTracker.resolve} uses, for the same reason.
 */
public class WeaponTypeServiceTest
{
	/**
	 * The table is injected in the plugin (the Plugin Hub forbids a fresh Gson
	 * in shipped code); a test builds its own because there is no injector here.
	 */
	private static WeaponTypeService service(long seed)
	{
		return new WeaponTypeService(null, new Gson(), new GachaRng(seed));
	}

	private static final WeaponTypeService SERVICE = service(0xC0FFEEL);

	/**
	 * A SYNTHETIC category-int to dbrow table. Deliberately scrambled — it maps
	 * category 0 to the LAST of the original block of rows and counts backwards.
	 *
	 * <p>It is scrambled on purpose so that nothing in this file can be mistaken
	 * for a table the plugin could hardcode. The live mapping is the client's own
	 * (DB table 78) and must always be read from it: the legacy WeaponType int
	 * table that other plugins copy around is stale from 22 onward, and building
	 * anything on a fixed int-to-category assumption is the exact bug the dbrow
	 * indirection exists to avoid.
	 *
	 * <p>What matters here is only its SHAPE: exactly the ints 0..30 are mapped,
	 * so 31..63 and everything negative are genuinely unknown to it.
	 */
	private static final Map<Integer, Integer> FIXTURE = fixture();

	private static Map<Integer, Integer> fixture()
	{
		List<WeaponType> withRows = new ArrayList<>();
		for (WeaponType type : SERVICE.all())
		{
			if (type.getDbrow() != WeaponTypeService.NO_DBROW)
			{
				withRows.add(type);
			}
		}
		Map<Integer, Integer> map = new HashMap<>();
		for (int category = 0; category <= 30; category++)
		{
			map.put(category, withRows.get(30 - category).getDbrow());
		}
		return Collections.unmodifiableMap(map);
	}

	/** The fixture's category int for a key, or -1 when it maps to none. */
	private static int categoryOf(String key)
	{
		WeaponType type = SERVICE.byKey(key);
		Assert.assertNotNull("no such weapon type: " + key, type);
		for (Map.Entry<Integer, Integer> entry : FIXTURE.entrySet())
		{
			if (entry.getValue() == type.getDbrow())
			{
				return entry.getKey();
			}
		}
		return -1;
	}

	@Test
	public void theResourceLoads()
	{
		// every other test in this file would pass vacuously against an empty
		// taxonomy, so prove there is one before asserting anything about it
		Assert.assertFalse("weapon-types.json did not load", SERVICE.all().isEmpty());
		Assert.assertNotNull(SERVICE.byKey("whip"));
		Assert.assertNotNull(SERVICE.byKey(WeaponTypeService.SPELL_CAST_KEY));
	}

	@Test
	public void aKnownCategoryInHandDoesPay()
	{
		// the positive control: without it, a service that answered "no" to
		// everything would pass every other test in this class
		int whip = categoryOf("whip");
		Assert.assertNotEquals(-1, whip);
		Assert.assertEquals("whip", SERVICE.byCategory(FIXTURE, whip).getKey());
		Assert.assertTrue(SERVICE.satisfies(FIXTURE, "whip", whip, 0));
	}

	@Test
	public void anUnknownCategoryPaysNothing()
	{
		// the varbit is 6 bits, so 0..63 is the whole legal range; the live table
		// fills only the low end of it and everything above is a category this
		// build has never heard of
		for (int category = 31; category <= 63; category++)
		{
			assertNothingPaid(category);
		}
	}

	@Test
	public void anImpossibleCategoryPaysNothing()
	{
		// negatives cannot come out of a varbit at all, so reaching one means the
		// read itself failed — which must still be worth nothing rather than
		// indexing off the front of something
		for (int category = -1; category >= -8; category--)
		{
			assertNothingPaid(category);
		}
		assertNothingPaid(Integer.MIN_VALUE);
		assertNothingPaid(Integer.MAX_VALUE);
		assertNothingPaid(64);
		assertNothingPaid(255);
	}

	private void assertNothingPaid(int category)
	{
		Assert.assertNull("category " + category + " resolved to a type",
			SERVICE.byCategory(FIXTURE, category));
		for (String key : new String[] {"whip", "unarmed", "bow", "staff_selfpowering"})
		{
			Assert.assertFalse("category " + category + " paid the " + key + " bonus",
				SERVICE.satisfies(FIXTURE, key, category, 0));
			// and not through the autocast door either: only SPELL_CAST may pass
			// on com mode alone
			Assert.assertFalse("category " + category + " paid the " + key
				+ " bonus while autocasting", SERVICE.satisfies(FIXTURE, key, category, 4));
		}
	}

	@Test
	public void anUnreadableCategoryTablePaysNothing()
	{
		// the client has not loaded the table yet (or the read failed and was not
		// cached): every weapon is unknown, and unknown is worth nothing. Never a
		// free bonus, never a penalty
		Map<Integer, Integer> empty = Collections.emptyMap();
		for (int category = 0; category <= 63; category++)
		{
			Assert.assertNull(SERVICE.byCategory(empty, category));
			Assert.assertFalse(SERVICE.satisfies(empty, "whip", category, 0));
		}
		// ...except the pseudo-type, which needs no table at all
		Assert.assertTrue(SERVICE.satisfies(empty, WeaponTypeService.SPELL_CAST_KEY, 0, 4));
	}

	@Test
	public void aPartialCategoryTableStillPaysForWhatItDoesResolve()
	{
		// the read came back with only some of the rows readable. Everything the
		// partial table DOES name still pays — refusing the lot would turn a
		// partial outage into a total one, and a total one is silent (the service
		// never caches a partial map, so this heals by itself if it was transient)
		Map<Integer, Integer> partial = new HashMap<>();
		int whip = categoryOf("whip");
		partial.put(whip, SERVICE.byKey("whip").getDbrow());

		Assert.assertTrue(SERVICE.satisfies(partial, "whip", whip, 0));
		Assert.assertFalse("a category missing from the partial table must pay nothing",
			SERVICE.satisfies(partial, "bow", categoryOf("bow"), 0));
		Assert.assertNull(SERVICE.byCategory(partial, categoryOf("bow")));
	}

	@Test
	public void aCategoryMappedToADbrowThisBuildDoesNotKnowPaysNothing()
	{
		// the table resolved the varbit to a row the taxonomy has no entry for —
		// a category the API gained and weapon-types.json has not caught up with.
		// The chain must break here rather than fall through to a neighbour
		Map<Integer, Integer> future = new HashMap<>(FIXTURE);
		future.put(31, 999_999);
		Assert.assertNull(SERVICE.byCategory(future, 31));
		for (String key : new String[] {"whip", "unarmed", "bow"})
		{
			Assert.assertFalse(SERVICE.satisfies(future, key, 31, 0));
		}
	}

	@Test
	public void noPreferenceIsNoBonusAndNeverAnError()
	{
		int whip = categoryOf("whip");
		Assert.assertFalse("a null preference must be worth nothing",
			SERVICE.satisfies(FIXTURE, null, whip, 0));
		Assert.assertNull(SERVICE.byKey(null));
		Assert.assertNull(SERVICE.displayName(null));
	}

	@Test
	public void aPreferenceThisBuildNoLongerKnowsPaysNothing()
	{
		// preferredWeaponType is a String in a save file and the taxonomy is
		// regenerated from a moving API. A save carrying a key that no longer
		// resolves must land on "no bonus" — not on an exception, and not on some
		// neighbouring category
		// "blaster" is in this list on purpose: the owner's exclusion list named
		// it, and runelite-api 1.12.35 has no COMBAT_INTERFACE_BLASTER constant to
		// spell it with, so it was left out rather than invented. If the API ever
		// adds one, everyApiCategoryIsAccountedFor will demand an entry for it and
		// this assertion will fail — that is the pair working, not a broken test.
		// Move "blaster" out of this list then; do not delete the assertion.
		int whip = categoryOf("whip");
		for (String stale : new String[] {"moon_hammer", "", "WHIP", "whip ", "blaster"})
		{
			Assert.assertNull("unexpectedly resolvable: " + stale, SERVICE.byKey(stale));
			Assert.assertNull(SERVICE.displayName(stale));
			Assert.assertFalse(stale + " paid a bonus", SERVICE.satisfies(FIXTURE, stale, whip, 0));
			Assert.assertFalse(stale + " paid a bonus while autocasting",
				SERVICE.satisfies(FIXTURE, stale, whip, 4));
		}
	}

	@Test
	public void spellCastMatchesTheAutocastSlotAndNothingElse()
	{
		// com mode 4 is the autocast slot, the same signal StyleTracker.resolve
		// judges MAGIC on. It is the whole definition of the pseudo-type: what is
		// in hand is irrelevant, which is why it can be named at all
		for (int category : new int[] {0, 1, categoryOf("whip"), categoryOf("staff_selfpowering"), 31, 63, -1})
		{
			Assert.assertTrue("spell_cast must match com mode 4 (category " + category + ")",
				SERVICE.satisfies(FIXTURE, WeaponTypeService.SPELL_CAST_KEY, category, 4));
			for (int comMode = -1; comMode <= 10; comMode++)
			{
				if (comMode == 4)
				{
					continue;
				}
				Assert.assertFalse("spell_cast matched com mode " + comMode,
					SERVICE.satisfies(FIXTURE, WeaponTypeService.SPELL_CAST_KEY, category, comMode));
			}
		}
	}

	@Test
	public void aPoweredStaffIsNotSpellCastAndSpellCastIsNotAPoweredStaff()
	{
		// powered staves fight from com mode 0/1/3, never the autocast slot, which
		// is exactly why they are their own category. The two must not stand in
		// for each other in either direction, or naming one would silently pay for
		// the other
		int powered = categoryOf("staff_selfpowering");
		Assert.assertFalse(SERVICE.satisfies(FIXTURE, WeaponTypeService.SPELL_CAST_KEY, powered, 0));
		Assert.assertTrue(SERVICE.satisfies(FIXTURE, "staff_selfpowering", powered, 0));
		Assert.assertFalse(SERVICE.satisfies(FIXTURE, "staff_selfpowering", categoryOf("whip"), 4));
	}

	@Test
	public void aNamedStaffStillPaysWhileAutocasting()
	{
		// deliberate: the category in hand is what the preference names, and a
		// hybrid staff reads as its category whether it is being swung or cast
		// with. The style lock is what makes this safe — a magic player who swings
		// it has tainted the kill and lost the whole award, not merely the 1.5x
		for (String key : new String[] {"staff", "staff_bladed", "staff_spellblade"})
		{
			int category = categoryOf(key);
			Assert.assertTrue(key + " should pay while autocasting",
				SERVICE.satisfies(FIXTURE, key, category, 4));
			Assert.assertTrue(key + " should pay while swinging",
				SERVICE.satisfies(FIXTURE, key, category, 0));
		}
	}

	@Test
	public void hybridStavesAreMeleeAndMagicButNeverRanged()
	{
		// this is what rescues magic from a two-item pool, so it is worth pinning
		// rather than leaving to the resource
		for (String key : new String[] {"staff", "staff_bladed", "staff_spellblade"})
		{
			WeaponType type = SERVICE.byKey(key);
			Assert.assertNotNull(key + " is missing from the taxonomy", type);
			Assert.assertTrue(key + " must be offerable", type.isOfferable());
			Assert.assertTrue(key + " must be in the melee pool",
				SERVICE.pool(AttackStyle.MELEE).contains(type));
			Assert.assertTrue(key + " must be in the magic pool",
				SERVICE.pool(AttackStyle.MAGIC).contains(type));
			Assert.assertFalse(key + " must never be in the ranged pool",
				SERVICE.pool(AttackStyle.RANGED).contains(type));
		}
	}

	@Test
	public void excludedCategoriesNeverComeOutOfTheWheel()
	{
		// 10,000 rolls per style. An excluded category reaching a player is not a
		// cosmetic bug: bulwark cannot deal damage, gun reads ranged and is fought
		// as melee, and a salamander cannot be verified at all — each one is a
		// whole style cycle the player cannot honour
		Set<String> excluded = new HashSet<>();
		for (WeaponType type : SERVICE.all())
		{
			if (!type.isOfferable())
			{
				excluded.add(type.getKey());
			}
		}
		Assert.assertFalse("no categories are excluded — the taxonomy is not loaded",
			excluded.isEmpty());

		for (AttackStyle style : AttackStyle.values())
		{
			WeaponTypeService rolling = service(20260815L + style.ordinal());
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < 10_000; i++)
			{
				WeaponType rolled = rolling.roll(style);
				Assert.assertNotNull(style + " rolled nothing", rolled);
				Assert.assertFalse(style + " rolled the excluded category " + rolled.getKey(),
					excluded.contains(rolled.getKey()));
				Assert.assertTrue(style + " rolled " + rolled.getKey() + ", which it may not name",
					rolled.getOfferIn().contains(style));
				seen.add(rolled.getKey());
			}
			// and the pool is genuinely drawn from: a roll that always returned the
			// same entry would satisfy everything above
			Set<String> pool = new HashSet<>();
			for (WeaponType type : SERVICE.pool(style))
			{
				pool.add(type.getKey());
			}
			Assert.assertEquals(style + " never rolled part of its own pool", pool, seen);
		}
	}

	@Test
	public void theRollIsDrivenOnlyByTheInjectedRng()
	{
		// the wheel's weapon roll must not draw from the party's seeded RNG — an
		// extra draw taken from that stream by one client and not another deals
		// two different boards. The RNG is constructor-injected precisely so the
		// seeded instance has no route in; this pins that the roll consumes that
		// instance and nothing else, so the same seed replays exactly
		WeaponTypeService a = service(4242L);
		WeaponTypeService b = service(4242L);
		for (int i = 0; i < 200; i++)
		{
			AttackStyle style = AttackStyle.values()[i % AttackStyle.values().length];
			Assert.assertEquals(a.roll(style).getKey(), b.roll(style).getKey());
		}
	}

	@Test
	public void unarmedIsNamedForWhatThePlayerCanSee()
	{
		// category 0 is reported for every non-weapon held item, not just for bare
		// fists, so the player-facing name may never say "unarmed" — see
		// WeaponTypeResourceTest for the guard across the whole file
		Assert.assertEquals("No weapon equipped", SERVICE.displayName("unarmed"));
		Assert.assertTrue("unarmed belongs to the melee pool",
			SERVICE.pool(AttackStyle.MELEE).contains(SERVICE.byKey("unarmed")));
	}
}
