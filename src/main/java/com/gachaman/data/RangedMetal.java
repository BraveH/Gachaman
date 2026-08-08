package com.gachaman.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

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
 * not have equipped either. They exist at black and nowhere else, hence {@link #onlyAt}.
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
public enum RangedMetal
{
	/** Free to wear; gated by the bow that fires it. */
	ARROW(reqs(1, 1, 5, RangedMetal.NONE, 20, 30, 40, 60)),
	/** Free to wear; gated by the crossbow that fires it. */
	BOLTS(reqs(1, 26, 31, RangedMetal.NONE, 36, 46, 61, 64)),
	/** Free to wear; ballista-exclusive, so the metal never matters. */
	JAVELIN(reqs(65, 65, 65, RangedMetal.NONE, 65, 65, 65, 65)),
	CROSSBOW(reqs(1, 26, 31, RangedMetal.NONE, 36, 46, 61, 64)),
	DART(reqs(1, 1, 5, 10, 20, 30, 40, 60)),
	KNIFE(reqs(1, 1, 5, 10, 20, 30, 40, 60)),
	THROWNAXE(reqs(1, 1, 5, RangedMetal.NONE, 20, 30, 40, 61)),
	/**
	 * Ogre arrows — "Bronze brutal", "Rune brutal" and the five between. Flat 30 whatever
	 * the metal, like the javelins: every one of them is fired from the comp ogre bow and
	 * from nothing else, so the metal never enters into it.
	 */
	BRUTAL(reqs(30, 30, 30, 30, 30, 30, 30, 30)),
	/** Not a metal — a live animal whose colour spells one. Black only, at 65. */
	CHINCHOMPA(onlyAt("black", 65)),
	/**
	 * Also not a metal. 70 Attack AND 70 Magic AND 70 Ranged in game; only the Ranged
	 * number is expressible here, which is the right one to pick — it is the style the
	 * weapon is carried for, and any of the three is an enormous improvement on the 10
	 * the black tier was charging.
	 */
	SALAMANDER(onlyAt("black", 70));

	/**
	 * Marker for a metal that has no item in this family, e.g. the black arrow.
	 *
	 * <p>Written {@code RangedMetal.NONE} above rather than plain {@code NONE}: a field
	 * cannot be declared before an enum's constants, and a SIMPLE-name reference to a
	 * field declared later in the same class is an illegal forward reference even when
	 * the field is a compile-time constant (JLS 8.3.3). Qualifying it is exempt.
	 */
	private static final int NONE = 0;

	/**
	 * Head nouns, both spellings where the game uses one of each ("Bronze bolts" is
	 * plural, "Rune arrow" is singular). Listed rather than stemmed: no stemmer turns
	 * "knives" into "knife".
	 */
	private static final Map<String, RangedMetal> BY_NOUN;

	static
	{
		Map<String, RangedMetal> nouns = new HashMap<>();
		nouns.put("arrow", ARROW);
		nouns.put("arrows", ARROW);
		nouns.put("bolt", BOLTS);
		nouns.put("bolts", BOLTS);
		nouns.put("javelin", JAVELIN);
		nouns.put("javelins", JAVELIN);
		nouns.put("crossbow", CROSSBOW);
		nouns.put("crossbows", CROSSBOW);
		nouns.put("dart", DART);
		nouns.put("darts", DART);
		nouns.put("knife", KNIFE);
		nouns.put("knives", KNIFE);
		nouns.put("thrownaxe", THROWNAXE);
		nouns.put("thrownaxes", THROWNAXE);
		// the ogre arrows are named for the adjective alone — the item really is
		// called "Rune brutal", with no head noun to stem
		nouns.put("brutal", BRUTAL);
		// the game ships these two singular only; the plurals are here for the same
		// reason as the rest of the table, so a future rename cannot silently un-gate them
		nouns.put("chinchompa", CHINCHOMPA);
		nouns.put("chinchompas", CHINCHOMPA);
		nouns.put("salamander", SALAMANDER);
		nouns.put("salamanders", SALAMANDER);
		BY_NOUN = Collections.unmodifiableMap(nouns);
	}

	private final Map<String, Integer> reqByTier;

	RangedMetal(Map<String, Integer> reqByTier)
	{
		this.reqByTier = reqByTier;
	}

	private static Map<String, Integer> reqs(int bronze, int iron, int steel, int black,
		int mithril, int adamant, int rune, int dragon)
	{
		Map<String, Integer> req = new HashMap<>();
		req.put("bronze", bronze);
		req.put("iron", iron);
		req.put("steel", steel);
		if (black != NONE)
		{
			req.put("black", black);
		}
		req.put("mithril", mithril);
		req.put("adamant", adamant);
		req.put("rune", rune);
		req.put("dragon", dragon);
		return Collections.unmodifiableMap(req);
	}

	/**
	 * A family that exists at exactly ONE tier, for the colour-prefixed impostors.
	 *
	 * <p>Kept separate from {@link #reqs} rather than passing NONE seven times: reqs()
	 * only honours NONE in the black column, so it cannot express this shape, and
	 * widening it would mean spelling {@code RangedMetal.NONE} seven times per constant
	 * (the simple name is an illegal forward reference — see {@link #NONE}). Every other
	 * tier falls through to the caller's fallback, which is what should happen: there is
	 * no mithril chinchompa to have an opinion about.
	 */
	private static Map<String, Integer> onlyAt(String tierKey, int req)
	{
		return Collections.singletonMap(tierKey, req);
	}

	/**
	 * Which ranged family is this card, or null when it is ordinary melee metal gear.
	 *
	 * <p>Matches the last word only. Names reaching here are already run through
	 * CardDatabase.cleanName, which strips every trailing parenthetical — so the
	 * poisoned "Rune dart(p++)" arrives as "Rune dart" and needs no special case.
	 */
	@Nullable
	public static RangedMetal of(@Nullable String cardName)
	{
		if (cardName == null)
		{
			return null;
		}
		int lastSpace = cardName.lastIndexOf(' ');
		if (lastSpace < 0)
		{
			return null; // no prefix at all, so not metal-prefixed gear
		}
		return BY_NOUN.get(cardName.substring(lastSpace + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * Ranged level this item really needs. A metal with no item in this family (there is
	 * no black arrow) returns {@code fallback} — pass tiers.json's own number, so an
	 * unrecognised combination is gated exactly as it was before this class existed
	 * rather than silently opening at level 1.
	 */
	public int reqRangedLevel(@Nullable String tierKey, int fallback)
	{
		Integer req = tierKey == null ? null : reqByTier.get(tierKey);
		return req == null ? fallback : req;
	}
}
