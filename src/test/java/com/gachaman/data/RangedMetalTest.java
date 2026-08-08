package com.gachaman.data;

import com.gachaman.Tuning;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

/**
 * Metal-prefixed ranged gear: classified onto the metal ladder by name prefix, but
 * gated on Ranged and — for crossbows, bolts, javelins and the dragon thrownaxe — on
 * numbers the metal ladder does not carry.
 *
 * <p>ChestService.isReachable and LoadoutService.styleOf both dereference Client, which
 * is null in every headless test, so the decision itself lives in this pure enum and is
 * pinned here. Requirements are the LAUNCHER's: ammunition has no equip requirement at
 * all, so the number a player actually feels for rune arrows is the yew bow's 40.
 */
public class RangedMetalTest
{
	private TierTable table()
	{
		return TierTable.load(new Gson());
	}

	/** The reported symptom: these four all wear a metal prefix and are Ranged gear. */
	@Test
	public void metalPrefixedRangedGearIsRecognised()
	{
		Assert.assertEquals(RangedMetal.ARROW, RangedMetal.of("Rune arrow"));
		Assert.assertEquals(RangedMetal.DART, RangedMetal.of("Adamant dart"));
		Assert.assertEquals(RangedMetal.KNIFE, RangedMetal.of("Mithril knife"));
		Assert.assertEquals(RangedMetal.BOLTS, RangedMetal.of("Bronze bolts"));
		Assert.assertEquals(RangedMetal.CROSSBOW, RangedMetal.of("Rune crossbow"));
		Assert.assertEquals(RangedMetal.JAVELIN, RangedMetal.of("Adamant javelin"));
		Assert.assertEquals(RangedMetal.THROWNAXE, RangedMetal.of("Iron thrownaxe"));
	}

	/** Both spellings ship in-game — "Rune arrow" is singular, "Bronze bolts" is plural. */
	@Test
	public void bothSingularAndPluralHeadNounsMatch()
	{
		Assert.assertEquals(RangedMetal.ARROW, RangedMetal.of("Rune arrows"));
		Assert.assertEquals(RangedMetal.BOLTS, RangedMetal.of("Runite bolt"));
		Assert.assertEquals(RangedMetal.KNIFE, RangedMetal.of("Rune knives"));
		Assert.assertEquals(RangedMetal.DART, RangedMetal.of("Rune darts"));
		Assert.assertEquals(RangedMetal.JAVELIN, RangedMetal.of("Rune javelins"));
		Assert.assertEquals(RangedMetal.THROWNAXE, RangedMetal.of("Rune thrownaxes"));
		Assert.assertEquals(RangedMetal.CROSSBOW, RangedMetal.of("Rune crossbows"));
	}

	/**
	 * The load-bearing negative. Melee metal must stay melee — misfiring here would
	 * gate every platebody in the game on Ranged, which is far worse than the bug.
	 */
	@Test
	public void ordinaryMetalGearIsNotRanged()
	{
		String[] melee = {"Rune platebody", "Rune scimitar", "Dragon dagger", "Adamant full helm",
			"Bronze med helm", "Mithril kiteshield", "Rune 2h sword", "Black chainbody",
			"Steel battleaxe", "Granite maul", "Iron boots", "White platelegs"};
		for (String name : melee)
		{
			Assert.assertNull(name + " must not read as ranged gear", RangedMetal.of(name));
		}
	}

	/** Nulls, single words and empty strings reach here from card names — none may throw. */
	@Test
	public void degenerateNamesAreSafe()
	{
		Assert.assertNull(RangedMetal.of(null));
		Assert.assertNull(RangedMetal.of(""));
		Assert.assertNull(RangedMetal.of("Arrow"));   // no prefix at all
		Assert.assertNull(RangedMetal.of("Seercull"));
	}

	/**
	 * CardDatabase.cleanName strips every trailing parenthetical before a name reaches
	 * here, so the poisoned and enchanted variants arrive already normalised.
	 */
	@Test
	public void poisonedAndEnchantedVariantsAreAlreadyCleaned()
	{
		Assert.assertEquals(RangedMetal.DART, RangedMetal.of(CardDatabase.cleanName("Rune dart(p++)")));
		Assert.assertEquals(RangedMetal.KNIFE, RangedMetal.of(CardDatabase.cleanName("Adamant knife(p+)")));
		Assert.assertEquals(RangedMetal.BOLTS, RangedMetal.of(CardDatabase.cleanName("Runite bolts (e)")));
	}

	// --- the numbers, pinned against the wiki ---

	/** Darts and knives are the only families that ride the metal ladder exactly. */
	@Test
	public void dartsAndKnivesFollowTheMetalLadder()
	{
		TierTable t = table();
		String[] tiers = {"bronze", "iron", "steel", "black", "mithril", "adamant", "rune", "dragon"};
		for (String tier : tiers)
		{
			Assert.assertEquals(tier + " dart", t.reqLevelOf(tier),
				RangedMetal.DART.reqRangedLevel(tier, 99));
			Assert.assertEquals(tier + " knife", t.reqLevelOf(tier),
				RangedMetal.KNIFE.reqRangedLevel(tier, 99));
		}
	}

	/**
	 * Crossbows barely touch the ladder — only bronze matches. A rune crossbow is 61,
	 * not 40, and bolts inherit the number from the crossbow that fires them.
	 */
	@Test
	public void crossbowsAndBoltsDivergeFromTheLadder()
	{
		Assert.assertEquals(1, RangedMetal.CROSSBOW.reqRangedLevel("bronze", 99));
		Assert.assertEquals(26, RangedMetal.CROSSBOW.reqRangedLevel("iron", 99));
		Assert.assertEquals(31, RangedMetal.CROSSBOW.reqRangedLevel("steel", 99));
		Assert.assertEquals(36, RangedMetal.CROSSBOW.reqRangedLevel("mithril", 99));
		Assert.assertEquals(46, RangedMetal.CROSSBOW.reqRangedLevel("adamant", 99));
		Assert.assertEquals(61, RangedMetal.CROSSBOW.reqRangedLevel("rune", 99));
		Assert.assertEquals(64, RangedMetal.CROSSBOW.reqRangedLevel("dragon", 99));
		// bolts are gated by their launcher, so the two families read identically
		for (String tier : new String[]{"bronze", "iron", "steel", "mithril", "adamant", "rune", "dragon"})
		{
			Assert.assertEquals(tier + " bolts track the crossbow",
				RangedMetal.CROSSBOW.reqRangedLevel(tier, 99),
				RangedMetal.BOLTS.reqRangedLevel(tier, 99));
		}
	}

	/** Every javelin is ballista-exclusive at 65, whatever the metal says. */
	@Test
	public void everyJavelinIsBallistaGated()
	{
		for (String tier : new String[]{"bronze", "iron", "steel", "mithril", "adamant", "rune", "dragon"})
		{
			Assert.assertEquals(tier + " javelin", 65, RangedMetal.JAVELIN.reqRangedLevel(tier, 99));
		}
	}

	/** The one off-by-one in an otherwise exact family. */
	@Test
	public void dragonThrownaxeIsSixtyOne()
	{
		Assert.assertEquals(61, RangedMetal.THROWNAXE.reqRangedLevel("dragon", 99));
		Assert.assertEquals(60, RangedMetal.DART.reqRangedLevel("dragon", 99));
	}

	/**
	 * Black exists only as a dart and a knife; there is no black arrow, bolt, javelin or
	 * thrownaxe, and no white ranged item at all. A metal with no item in the family must
	 * fall back to the caller's number rather than silently opening at level 1.
	 */
	@Test
	public void absentMetalsFallBackInsteadOfOpeningUp()
	{
		Assert.assertEquals(10, RangedMetal.DART.reqRangedLevel("black", 99));
		Assert.assertEquals(10, RangedMetal.KNIFE.reqRangedLevel("black", 99));
		Assert.assertEquals(99, RangedMetal.ARROW.reqRangedLevel("black", 99));
		Assert.assertEquals(99, RangedMetal.BOLTS.reqRangedLevel("black", 99));
		Assert.assertEquals(99, RangedMetal.THROWNAXE.reqRangedLevel("black", 99));
		// white and granite are melee-only tiers, and an unknown/null tier must not open either
		Assert.assertEquals(99, RangedMetal.ARROW.reqRangedLevel("white", 99));
		Assert.assertEquals(99, RangedMetal.ARROW.reqRangedLevel("granite", 99));
		Assert.assertEquals(99, RangedMetal.ARROW.reqRangedLevel("no-such-tier", 99));
		Assert.assertEquals(99, RangedMetal.ARROW.reqRangedLevel(null, 99));
	}

	/** No ranged item may read as harder than the hardest thing in the game. */
	@Test
	public void everyRequirementIsALegalLevel()
	{
		for (RangedMetal family : RangedMetal.values())
		{
			for (String tier : new String[]{"bronze", "iron", "steel", "black", "mithril",
				"adamant", "rune", "dragon"})
			{
				int req = family.reqRangedLevel(tier, 1);
				Assert.assertTrue(family + "/" + tier + " = " + req, req >= 1 && req <= 99);
			}
		}
	}

	/** Within a family, a higher metal never costs less than a lower one. */
	@Test
	public void requirementsRiseWithTheMetal()
	{
		String[] ladder = {"bronze", "iron", "steel", "mithril", "adamant", "rune", "dragon"};
		for (RangedMetal family : RangedMetal.values())
		{
			for (int i = 1; i < ladder.length; i++)
			{
				Assert.assertTrue(family + ": " + ladder[i] + " must not be cheaper than " + ladder[i - 1],
					family.reqRangedLevel(ladder[i], 1) >= family.reqRangedLevel(ladder[i - 1], 1));
			}
		}
	}

	// --- the reach decision ChestService makes with these numbers ---

	/**
	 * The headline case. A 40 Ranged / 1 Attack / 1 Defence archer must reach adamant
	 * arrows (30) and rune arrows (40) — under the old max(Attack, Defence) read it
	 * reached neither, because its melee levels were 1.
	 */
	@Test
	public void archerReachesTheAmmoItActuallyShoots()
	{
		int h = Tuning.ROLL_LEVEL_HEADROOM;
		Assert.assertTrue("adamant arrows at 40 Ranged", Tuning.withinReach(40, 1,
			RangedMetal.ARROW.reqRangedLevel("adamant", 99), 1, h));
		Assert.assertTrue("rune arrows at 40 Ranged", Tuning.withinReach(40, 1,
			RangedMetal.ARROW.reqRangedLevel("rune", 99), 1, h));
		Assert.assertTrue("mithril knives at 40 Ranged", Tuning.withinReach(40, 1,
			RangedMetal.KNIFE.reqRangedLevel("mithril", 99), 1, h));
	}

	/** And the mirror: a 40 Attack meleer must stop rolling ammunition it owns no bow for. */
	@Test
	public void meleerNoLongerRollsAmmoItCannotFire()
	{
		int h = Tuning.ROLL_LEVEL_HEADROOM;
		Assert.assertFalse("rune arrows at Ranged 1", Tuning.withinReach(1, 40,
			RangedMetal.ARROW.reqRangedLevel("rune", 99), 1, h));
		Assert.assertFalse("rune crossbow at Ranged 1", Tuning.withinReach(1, 99,
			RangedMetal.CROSSBOW.reqRangedLevel("rune", 99), 1, h));
	}

	/**
	 * No ranged weapon or ammunition carries a Defence gate, so a 1 Defence pure must
	 * reach everything its Ranged level allows.
	 */
	@Test
	public void rangedGearNeverGatesOnDefence()
	{
		Assert.assertTrue("dragon crossbow at 64 Ranged / 1 Defence",
			Tuning.withinReach(64, 1, RangedMetal.CROSSBOW.reqRangedLevel("dragon", 99), 1, 0));
		Assert.assertTrue("rune javelin at 65 Ranged / 1 Defence",
			Tuning.withinReach(65, 1, RangedMetal.JAVELIN.reqRangedLevel("rune", 99), 1, 0));
	}

	/**
	 * The divergence that motivated encoding real numbers instead of re-reading the
	 * ladder as Ranged: at 45 Ranged the ladder would have handed over a rune crossbow.
	 */
	@Test
	public void ladderAsRangedWouldStillHaveBeenWrongForCrossbows()
	{
		TierTable t = table();
		Assert.assertTrue("the ladder says rune is 40", Tuning.withinReach(45, 1,
			t.reqLevelOf("rune"), 1, 0));
		Assert.assertFalse("but a rune crossbow is 61", Tuning.withinReach(45, 1,
			RangedMetal.CROSSBOW.reqRangedLevel("rune", 99), 1, 0));
	}

	/**
	 * "Runite bolts" is the one equipable item spelt the long way. "Runite" does not
	 * prefix-match "Rune" (4th char 'i' vs 'e'), so before it was added to the tier's
	 * prefixes the card landed untiered — no tier, no ladder, gated by nothing.
	 */
	@Test
	public void runiteBoltsAreTiered()
	{
		TierTable.Match match = table().match("Runite bolts");
		Assert.assertNotNull("Runite bolts must not be untiered", match);
		Assert.assertEquals("rune", match.getTierKey());
		Assert.assertEquals("bolts", match.getFamilyKey());
		Assert.assertEquals(61, RangedMetal.BOLTS.reqRangedLevel(match.getTierKey(), 99));
	}

	// --- the colour-prefixed impostors ---

	/**
	 * "Black chinchompa" and "Black salamander" are not black METAL — the prefix is a
	 * colour that happens to spell one. Both prefix-match the black tier exactly as a
	 * platebody does, so both were classified rank 4 and gated on max(Attack, Defence).
	 */
	@Test
	public void colourPrefixedImpostorsAreRecognisedAsRanged()
	{
		Assert.assertEquals(RangedMetal.CHINCHOMPA, RangedMetal.of("Black chinchompa"));
		Assert.assertEquals(RangedMetal.SALAMANDER, RangedMetal.of("Black salamander"));
	}

	/** The real wiki numbers: a chinchompa is 65 Ranged, a salamander 70. */
	@Test
	public void theImpostorsCarryTheirRealRequirement()
	{
		Assert.assertEquals(65, RangedMetal.CHINCHOMPA.reqRangedLevel("black", 99));
		Assert.assertEquals(70, RangedMetal.SALAMANDER.reqRangedLevel("black", 99));
	}

	/**
	 * They exist at black and nowhere else, so every other metal must fall back to the
	 * caller's number rather than inventing a mithril chinchompa at level 1.
	 */
	@Test
	public void theImpostorsExistAtBlackOnly()
	{
		for (String tier : new String[]{"bronze", "iron", "steel", "mithril", "adamant",
			"rune", "dragon", "white", "granite", "no-such-tier"})
		{
			Assert.assertEquals(tier + " chinchompa does not exist", 99,
				RangedMetal.CHINCHOMPA.reqRangedLevel(tier, 99));
			Assert.assertEquals(tier + " salamander does not exist", 99,
				RangedMetal.SALAMANDER.reqRangedLevel(tier, 99));
		}
		Assert.assertEquals(99, RangedMetal.CHINCHOMPA.reqRangedLevel(null, 99));
	}

	/**
	 * The defect itself, both halves. The black tier is rank 4 and reqLevel 10, so under
	 * the melee read a brand-new account cleared it outright — rank 4 &lt;= rank-for-level-1
	 * (2) + ROLL_TIER_HEADROOM (2) — and even with no headroom at all 10 Attack bought a
	 * weapon needing 65 Ranged. Read as Ranged, both refuse.
	 */
	@Test
	public void aFreshAccountNoLongerReachesABlackChinchompa()
	{
		TierTable.Match match = table().match("Black chinchompa");
		Assert.assertNotNull(match);
		Assert.assertEquals(4, match.getRank());
		// what the melee branch of ChestService.isReachable would have said
		Assert.assertTrue("the defect: rank 4 was inside a level-1 account's reach",
			match.getRank() <= Tuning.maxRankForLevel(1) + Tuning.ROLL_TIER_HEADROOM);
		Assert.assertTrue("and 10 Attack cleared it even with no headroom",
			match.getRank() <= Tuning.maxRankForLevel(10));

		int h = Tuning.ROLL_LEVEL_HEADROOM;
		int chin = RangedMetal.CHINCHOMPA.reqRangedLevel(match.getTierKey(), 99);
		Assert.assertFalse("1 Ranged must not reach a 65 Ranged weapon",
			Tuning.withinReach(1, 1, chin, 1, h));
		Assert.assertFalse("nor 99 Attack with no Ranged to speak of",
			Tuning.withinReach(1, 99, chin, 1, h));
		Assert.assertTrue("a 65 Ranged archer reaches it",
			Tuning.withinReach(65, 1, chin, 1, 0));
		Assert.assertTrue("and it stays aspirational inside the headroom band",
			Tuning.withinReach(56, 1, chin, 1, h));
	}

	/** Same story one tier of severity up: the salamander was 70, and was charging 10. */
	@Test
	public void aFreshAccountNoLongerReachesABlackSalamander()
	{
		int h = Tuning.ROLL_LEVEL_HEADROOM;
		int sala = RangedMetal.SALAMANDER.reqRangedLevel(
			table().match("Black salamander").getTierKey(), 99);
		Assert.assertEquals(70, sala);
		Assert.assertFalse(Tuning.withinReach(1, 1, sala, 1, h));
		Assert.assertTrue(Tuning.withinReach(70, 1, sala, 1, 0));
	}

	/**
	 * Deliberately still tiered. Excluding the family in tiers.json would have worked too,
	 * but tierKey is written into the card cache — flipping it to null would change every
	 * user's stored classification and force a rescan, where fixing the READ costs nothing.
	 */
	@Test
	public void theImpostorsStayOnTheBlackTierSoTheCacheIsUntouched()
	{
		TierTable t = table();
		Assert.assertEquals("black", t.match("Black chinchompa").getTierKey());
		Assert.assertEquals("chinchompa", t.match("Black chinchompa").getFamilyKey());
		Assert.assertEquals("black", t.match("Black salamander").getTierKey());
		Assert.assertEquals("salamander", t.match("Black salamander").getFamilyKey());
		// and being tiered is what routes them to the metal ladder in the first place,
		// which is the only branch that consults RangedMetal at all
		Assert.assertEquals("metal", t.ladderOf("black"));
	}

	/**
	 * The siblings are untiered and therefore ungated, like every other exotic weapon.
	 * "Chinchompa" has no prefix; "Red chinchompa" matches no metal, because "Red d'hide"
	 * is the only Red prefix and it needs the whole "Red d'hide " to match.
	 */
	@Test
	public void theUntieredSiblingsAreLeftAlone()
	{
		TierTable t = table();
		Assert.assertNull("Chinchompa carries no prefix", t.match("Chinchompa"));
		Assert.assertNull("Red is not a metal", t.match("Red chinchompa"));
		Assert.assertNull("nor for the salamanders", t.match("Red salamander"));
		Assert.assertNull(t.match("Orange salamander"));
		Assert.assertNull(t.match("Swamp lizard"));
	}

	/** And the load-bearing negative: real black metal must not follow them across. */
	@Test
	public void blackMetalGearIsUndisturbed()
	{
		Assert.assertNull(RangedMetal.of("Black platebody"));
		Assert.assertNull(RangedMetal.of("Black scimitar"));
		Assert.assertNull(RangedMetal.of("Black full helm"));
		Assert.assertEquals(RangedMetal.DART, RangedMetal.of("Black dart"));
		Assert.assertEquals(RangedMetal.KNIFE, RangedMetal.of("Black knife"));
		Assert.assertEquals(10, RangedMetal.DART.reqRangedLevel("black", 99));
	}

	/** Adding that prefix must not disturb anything the short spelling already claimed. */
	@Test
	public void theShortSpellingStillMatches()
	{
		TierTable t = table();
		Assert.assertEquals("rune", t.match("Rune platebody").getTierKey());
		Assert.assertEquals("rune", t.match("Rune arrow").getTierKey());
		Assert.assertEquals("rune", t.match("Rune crossbow").getTierKey());
		Assert.assertEquals(7, t.match("Rune platebody").getRank());
		Assert.assertEquals(7, t.match("Runite bolts").getRank());
	}
}
