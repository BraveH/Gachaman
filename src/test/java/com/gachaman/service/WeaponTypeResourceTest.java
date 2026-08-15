package com.gachaman.service;

import com.gachaman.model.*;
import com.gachaman.service.WeaponTypeService.WeaponType;
import com.gachaman.tools.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;
import net.runelite.api.gameval.*;
import org.junit.*;

/**
 * The shipped weapon-types.json must still be exactly what {@link WeaponTypes}
 * declares, and that declaration must still cover every weapon category the API
 * defines.
 *
 * <p>This is the seam that makes moving the dbrow ids into a resource safe. In
 * code they are {@code DBTableID.CombatInterfaceWeaponCategory.Row} constants
 * and the compiler catches a rename; as raw numbers in JSON nothing would — and
 * a wrong dbrow does not fail loudly, it just means that category silently never
 * pays the Preferred Weapon bonus, for everybody, with no error anywhere.
 *
 * <p>The second half is the one that keeps working after everybody has moved
 * on: {@link #everyApiCategoryIsAccountedFor} enumerates the {@code Row}
 * constants REFLECTIVELY, so the day a RuneLite bump adds a weapon category the
 * build fails and names it, instead of shipping a category the wheel can never
 * offer and the album can never explain. Reflection is forbidden in
 * {@code src/main/java} by the Plugin Hub rules — it is not forbidden here, and
 * tests are neither shipped nor counted against the token budget, so this is the
 * right place for it. Please do not "fix" it into a hardcoded list; a hardcoded
 * list is precisely the thing it exists to catch.
 *
 * <p>If the first test fails, run {@code com.gachaman.tools.WeaponTypes} and
 * commit the result.
 */
public class WeaponTypeResourceTest
{
	private static class ShippedFile
	{
		String note;
		List<WeaponType> types;
	}

	private static ShippedFile shipped() throws Exception
	{
		try (InputStream in = WeaponTypeResourceTest.class.getResourceAsStream(
			"/com/gachaman/data/weapon-types.json"))
		{
			Assert.assertNotNull("weapon-types.json is not on the classpath", in);
			return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				ShippedFile.class);
		}
	}

	/** Every {@code Row} constant the resolved runelite-api actually defines, by name. */
	private static Map<String, Integer> apiRows() throws Exception
	{
		Map<String, Integer> rows = new LinkedHashMap<>();
		for (Field field : DBTableID.CombatInterfaceWeaponCategory.Row.class.getFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
			{
				rows.put(field.getName(), field.getInt(null));
			}
		}
		Assert.assertFalse("no Row constants found — the API shape changed", rows.isEmpty());
		return rows;
	}

	@Test
	public void theResourceMatchesTheCompiledDbrowIds() throws Exception
	{
		// list equality, so ORDER is pinned too: the pools are built in resource
		// order and a seeded roll has to be reproducible from the shipped file
		Assert.assertEquals("stale weapon-types.json — run com.gachaman.tools.WeaponTypes",
			WeaponTypes.types(), shipped().types);
	}

	@Test
	public void theResourceExplainsItself() throws Exception
	{
		// JSON cannot carry a comment, so the file carries a note instead; a
		// hand-edit is the failure mode this whole test class exists for, and the
		// note is the only warning a reader who opens the file first ever gets
		Assert.assertNotNull("weapon-types.json lost its note key", shipped().note);
		Assert.assertTrue("the note must name the generator",
			shipped().note.contains("com.gachaman.tools.WeaponTypes"));
	}

	@Test
	public void everyShippedDbrowIsARealApiConstant() throws Exception
	{
		Collection<Integer> real = apiRows().values();
		for (WeaponType type : shipped().types)
		{
			if (type.getDbrow() == WeaponTypeService.NO_DBROW)
			{
				continue; // the pseudo-type; checked on its own below
			}
			Assert.assertTrue("dbrow " + type.getDbrow() + " (" + type.getKey()
				+ ") is not a CombatInterfaceWeaponCategory.Row constant",
				real.contains(type.getDbrow()));
		}
	}

	@Test
	public void everyApiCategoryIsAccountedFor() throws Exception
	{
		Map<Integer, WeaponType> byDbrow = new HashMap<>();
		for (WeaponType type : shipped().types)
		{
			byDbrow.put(type.getDbrow(), type);
		}
		List<String> missing = new ArrayList<>();
		for (Map.Entry<String, Integer> row : apiRows().entrySet())
		{
			WeaponType type = byDbrow.get(row.getValue());
			if (type == null)
			{
				missing.add(row.getKey() + " (" + row.getValue() + ")");
				continue;
			}
			// accounted for means one of two things, and never silence: either the
			// wheel may name it, or somebody decided it may not and said why
			if (type.isOfferable())
			{
				Assert.assertFalse(type.getKey() + " is offerable but belongs to no style pool",
					type.getOfferIn().isEmpty());
			}
			else
			{
				Assert.assertNotNull(type.getKey() + " is excluded with no reason given",
					type.getReason());
				Assert.assertTrue(type.getKey() + " is excluded but still in a style pool",
					type.getOfferIn().isEmpty());
			}
		}
		Assert.assertEquals("the API defines weapon categories weapon-types.json has never"
			+ " heard of — add them to com.gachaman.tools.WeaponTypes (offerable, or"
			+ " offerable:false with a reason) and regenerate",
			Collections.emptyList(), missing);
	}

	@Test
	public void noPlayerFacingNameSaysUnarmed() throws Exception
	{
		// the owner's rule, pinned where it cannot be argued with. The game reports
		// category 0 for every non-weapon held item, not just for bare fists, so
		// "unarmed" on screen is a claim about the player's hands that is usually
		// false. The KEY stays "unarmed" — it is persisted and internal — and the
		// guard belongs on the rendered string
		for (WeaponType type : shipped().types)
		{
			Assert.assertNotNull(type.getKey() + " has no displayName", type.getDisplayName());
			Assert.assertFalse("displayName \"" + type.getDisplayName() + "\" says unarmed;"
				+ " player-facing text must say \"no weapon equipped\"",
				type.getDisplayName().toLowerCase(Locale.ROOT).contains("narmed"));
		}
	}

	@Test
	public void keysAndDbrowsAreUniqueAndPersistSafe() throws Exception
	{
		// the key goes into GachaState.preferredWeaponType and therefore into save
		// files: a duplicate would make one of the two unreachable forever, and a
		// key with punctuation or case in it is one rename away from orphaning
		// every save that carries it
		Set<String> keys = new HashSet<>();
		Set<Integer> dbrows = new HashSet<>();
		for (WeaponType type : shipped().types)
		{
			Assert.assertTrue("key \"" + type.getKey() + "\" is not [a-z0-9_]",
				type.getKey().matches("[a-z0-9_]+"));
			Assert.assertTrue("duplicate key " + type.getKey(), keys.add(type.getKey()));
			if (type.getDbrow() != WeaponTypeService.NO_DBROW)
			{
				Assert.assertTrue("duplicate dbrow " + type.getDbrow(), dbrows.add(type.getDbrow()));
			}
		}
	}

	@Test
	public void spellCastIsTheOnlyPseudoType() throws Exception
	{
		// a second dbrow-less entry would be a category that can be rolled and can
		// never be satisfied by anything in hand — SPELL_CAST works only because
		// satisfies() special-cases exactly this one key
		List<String> pseudo = new ArrayList<>();
		for (WeaponType type : shipped().types)
		{
			if (type.getDbrow() == WeaponTypeService.NO_DBROW)
			{
				pseudo.add(type.getKey());
			}
		}
		Assert.assertEquals(Collections.singletonList(WeaponTypeService.SPELL_CAST_KEY), pseudo);
	}

	@Test
	public void everyStyleHasSomethingToOffer() throws Exception
	{
		// an empty pool is a style whose wheel can never name a weapon, which
		// would be a silent, permanent loss of the feature for whoever the wheel
		// locks there. Magic is the one this guards: without the hybrid staves it
		// is powered staves and casting, and nothing else
		Map<AttackStyle, Integer> counts = new EnumMap<>(AttackStyle.class);
		for (AttackStyle style : AttackStyle.values())
		{
			counts.put(style, 0);
		}
		for (WeaponType type : shipped().types)
		{
			if (!type.isOfferable())
			{
				continue;
			}
			for (AttackStyle style : type.getOfferIn())
			{
				counts.put(style, counts.get(style) + 1);
			}
		}
		for (AttackStyle style : AttackStyle.values())
		{
			Assert.assertTrue(style + " has an empty weapon pool", counts.get(style) >= 2);
		}
	}
}
