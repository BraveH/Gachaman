package com.gachaman.data;

import com.google.gson.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;

/**
 * Ranged gear that wears a metal prefix, which the metal ladder gets wrong twice over.
 *
 * <p>Cards are classified by longest name prefix, so "Rune arrow", "Adamant dart" and
 * "Rune crossbow" all land on the metal ladder and were gated on max(Attack, Defence).
 * That is wrong in both directions: a 40 Ranged / 1 Attack archer could not roll the
 * adamant arrows it shoots every day, while a 40 Attack meleer could roll rune arrows
 * it owns no bow for. AMMO is a deeded slot on a fresh save, so this is reachable early.
 *
 * <p>Re-reading the same ladder as a Ranged requirement fixes the skill but not the
 * numbers — that is only correct for darts and knives. Verified against the wiki:
 *
 * <ul>
 *   <li>Ammunition carries <b>no equip requirement at all</b>. A level-1 account can
 *       wear rune arrows. The number a player actually feels is the LAUNCHER's, so
 *       that is what is encoded here — a yew bow (40) for rune arrows, an adamant
 *       crossbow (46) for adamant bolts.
 *   <li>Crossbows barely touch the ladder: 1 / 26 / 31 / 36 / 46 / 61 / 64. Only
 *       bronze matches. Bolts inherit those same numbers from the crossbow that fires
 *       them.
 *   <li>Every javelin is ballista-exclusive at 65, whatever the metal says.
 *   <li>Dragon thrownaxe is 61, not 60 — the one divergence in an otherwise exact family.
 *   <li>Black exists only as a dart and a knife (10). There is no black arrow, black
 *       bolt, black javelin or black thrownaxe, and no white ranged item at all.
 * </ul>
 *
 * <p>Two more families land here for a different reason: their "metal" is not a metal at
 * all, it is a COLOUR that happens to spell one. "Black chinchompa" and "Black salamander"
 * prefix-match the black tier exactly as "Black platebody" does, so both were classified
 * rank 4 and — finding no ranged noun to match — gated on max(Attack, Defence) at the black
 * tier's 10. A chinchompa is 65 Ranged and a salamander is 70, so a level-1 account was
 * being offered both, and the loadout picker filed them under Melee for a ranger who could
 * not have equipped either. They exist at black and nowhere else, hence their single-entry rows.
 *
 * <p>Their siblings are untouched: "Chinchompa" has no prefix and "Red chinchompa" matches
 * no metal ("Red d'hide" is the only Red prefix), so both stay untiered and therefore
 * ungated — the same bucket every exotic weapon sits in. Gating the black one alone is not
 * an inconsistency but the house rule: partial knowledge gates partially, exactly as rune
 * arrows are gated at 40 while amethyst arrows are not gated at all.
 *
 * <p>Pure and static because both callers dereference Client, which is null in every
 * headless test — testing it through them would be vacuously green.
 */
public enum RangedMetal {
	ARROW,
	BOLTS,
	JAVELIN,
	CROSSBOW,
	DART,
	KNIFE,
	THROWNAXE,
	/** Ogre arrows — "Bronze brutal" through "Rune brutal", all comp-ogre-bow only. */
	BRUTAL,
	/** Not a metal — a live animal whose colour spells one. Black only. */
	CHINCHOMPA,
	/** Also not a metal. 70 Attack AND Magic AND Ranged in game; Ranged is the one that fits. */
	SALAMANDER;

	/**
	 * The nouns and the ladder, from ranged-metal.json. Which families EXIST is
	 * code — every one of them is a distinct rule with its own reasoning above —
	 * but the head nouns and the level per metal are plain data, and a wrong
	 * number there should be correctable without touching a class.
	 */
	private static class Table {
		Map<String, String> nouns = Collections.emptyMap();
		Map<String, Map<String, Integer>> families = Collections.emptyMap();
	}

	/**
	 * The table, as an injectable lookup. It lives beside the enum rather than
	 * inside it because it needs the CLIENT's Gson, which only exists once
	 * Guice has built something — an enum's static initializer runs far too
	 * early for that, and the Plugin Hub forbids brewing a fresh Gson to dodge
	 * the ordering problem.
	 */
	@Singleton
	public static final class Lookup {
		private final Table table;

		@Inject
		Lookup(Gson gson) {
			this.table = DataJson.load(gson, "ranged-metal", Table.class, new Table());
		}

		/**
		 * Which ranged family is this card, or null when it is ordinary melee metal gear.
		 *
		 * <p>Matches the last word only. Names reaching here are already run through
		 * CardDatabase.cleanName, which strips every trailing parenthetical — so the
		 * poisoned "Rune dart(p++)" arrives as "Rune dart" and needs no special case.
		 */
		@Nullable
		public RangedMetal of(String cardName) {
			if (cardName == null)
				return null;
			int lastSpace = cardName.lastIndexOf(' ');
			if (lastSpace < 0) {
				return null; // no prefix at all, so not metal-prefixed gear
			}
			String family = table.nouns.get(
				cardName.substring(lastSpace + 1).toLowerCase(Locale.ROOT));
			if (family == null)
				return null;
			try {
				return valueOf(family);
			}
			catch (IllegalArgumentException e) {
				// a noun pointing at a family this enum does not declare: bad data,
				// not a bad card. Withhold rather than guess.
				return null;
			}
		}

		/**
		 * Ranged level this item really needs. A metal with no item in this family
		 * (there is no black arrow) returns {@code fallback} — pass tiers.json's own
		 * number, so an unrecognised combination is gated exactly as it was before
		 * this class existed rather than silently opening at level 1.
		 */
		public int reqRangedLevel(RangedMetal metal, String tierKey, int fallback) {
			Map<String, Integer> reqs = table.families.get(metal.name());
			Integer req = tierKey == null || reqs == null ? null : reqs.get(tierKey);
			return req == null ? fallback : req;
		}
	}
}
