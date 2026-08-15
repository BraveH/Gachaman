package com.gachaman.tools;

import com.gachaman.model.*;
import com.gachaman.service.*;
import com.gachaman.service.WeaponTypeService.WeaponType;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.gameval.*;

/**
 * Authors {@code weapon-types.json}: the weapon CATEGORY taxonomy the Preferred
 * Weapon bonus is named from.
 *
 * <p>The dbrow ids are pure data and belong in a resource, but writing them
 * there by hand would mean three dozen four-digit magic numbers with nothing
 * checking them — and a mistyped one does not fail loudly. It resolves to no
 * type, so that category simply never pays the bonus, for everyone, forever,
 * with no error anywhere. So they are spelled here as
 * {@link DBTableID.CombatInterfaceWeaponCategory.Row} constants — where the
 * compiler still verifies every name against the live API — and this tool
 * projects them into the resource the plugin actually reads. Exactly the
 * arrangement {@link AttackAnims} uses for the animation ids.
 *
 * <p>{@code WeaponTypeResourceTest} asserts the shipped resource still matches
 * this declaration, and additionally that the declaration still covers EVERY
 * {@code Row} constant the API defines — so a RuneLite update that adds a
 * category fails the build with its name rather than quietly shipping a
 * category the wheel can never name.
 *
 * <p>Regenerate by running this class's {@code main} on the test classpath.
 * There is no {@code ./gradlew weaponTypes} task yet; see the note in
 * build.gradle's sibling tasks (attackAnims, iconArt, ceremonyArt).
 */
public final class WeaponTypes {
	private static final Set<AttackStyle> MELEE = EnumSet.of(AttackStyle.MELEE);
	private static final Set<AttackStyle> RANGED = EnumSet.of(AttackStyle.RANGED);
	private static final Set<AttackStyle> MAGIC = EnumSet.of(AttackStyle.MAGIC);
	/**
	 * The hybrid staves: named by the MELEE wheel and the MAGIC wheel both.
	 *
	 * <p>Deliberate, and it is what rescues magic from a two-item pool — without
	 * them magic could name only the powered staves and casting itself. Which
	 * mode such a staff is in is never resolved, and never has to be: the bonus
	 * pays only on a compliant kill, and a kill in the wrong style is tainted
	 * and pays zero before any multiplier is reached. So a magic player who bash
	 * their way through a kill with a bladed staff has already lost the whole
	 * award, not merely the 1.5x.
	 */
	private static final Set<AttackStyle> HYBRID_STAFF = EnumSet.of(AttackStyle.MELEE, AttackStyle.MAGIC);
	/** Never named by any wheel. */
	private static final Set<AttackStyle> NONE = EnumSet.noneOf(AttackStyle.class);

	private static final String NOTE = "Authored by com.gachaman.tools.WeaponTypes from"
		+ " DBTableID.CombatInterfaceWeaponCategory.Row constants. Do not hand-edit:"
		+ " WeaponTypeResourceTest pins this file to those constants, and the keys are"
		+ " persisted in player saves. dbrow -1 is the SPELL_CAST pseudo-type, which is"
		+ " com mode 4 (the autocast slot) and has no row of its own.";

	private WeaponTypes() {
	}

	private static WeaponType offerable(String key, String displayName, int dbrow,
		Set<AttackStyle> offerIn) {
		return new WeaponType(key, displayName, dbrow, offerIn, true, null);
	}

	private static WeaponType excluded(String key, String displayName, int dbrow, String reason) {
		return new WeaponType(key, displayName, dbrow, NONE, false, reason);
	}

	/**
	 * The taxonomy, in dbrow order.
	 *
	 * <p>Listed in dbrow order rather than grouped by pool so that this file
	 * lines up one-to-one with the {@code Row} class itself: when the API adds a
	 * category, a {@code javap} of {@code Row} diffs straight against this list
	 * and the newcomer is obvious. The pool each entry belongs to is the trailing
	 * argument, so nothing is lost by not grouping — the styles interleave.
	 *
	 * <p>The order is also the order the wheel's pools are built in, so a seeded
	 * roll is reproducible from the shipped file alone.
	 *
	 * <p>Every {@code Row} constant appears exactly once, offerable or not.
	 * Silence is not an option here: a category left out of the file is
	 * indistinguishable at runtime from one nobody has heard of, and the whole
	 * value of the exclusions is that somebody looked at each one and wrote down
	 * why.
	 */
	public static List<WeaponType> types() {
		List<WeaponType> types = new ArrayList<>();

		// "unarmed" is the key; the NAME is "No weapon equipped" and must stay
		// that way. The game reports this same category for every non-weapon
		// held item — a bucket of water, a lantern, a clue scroll — so telling
		// the player they are "unarmed" while they hold something would be a lie
		// about what is in their hands. It is a real, nameable category all the
		// same: fists are a legitimate way to fight, and the wheel may say so.
		types.add(offerable("unarmed", "No weapon equipped",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_UNARMED, MELEE));
		types.add(offerable("axe", "Axes",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_AXE, MELEE));
		types.add(offerable("blunt", "Blunt weapons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_BLUNT, MELEE));
		types.add(offerable("blunt_bludgeon", "Bludgeons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_BLUNT_BLUDGEON, MELEE));

		types.add(offerable("bow", "Bows",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_BOW, RANGED));

		types.add(offerable("claw", "Claws",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_CLAW, MELEE));

		types.add(offerable("crossbow", "Crossbows",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_CROSSBOW, RANGED));

		// tri-style: the same weapon fights with melee, ranged and magic, and the
		// varbit reports only the category — never which of the three stances is
		// live. Naming it would mean paying 1.5x on kills the preference cannot
		// actually verify
		types.add(excluded("flamer", "Salamanders",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_FLAMER,
			"tri-style: the varbit reports the category, never which of the three"
				+ " stances is in use"));

		types.add(offerable("grenade", "Chinchompas",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_GRENADE, RANGED));

		// the trap in the whole taxonomy: it reads as a ranged category and is
		// actually swung. A ranged week that named it would be paying a melee
		// bonus, and a player fighting to the letter of the offer would be
		// tainting every kill
		types.add(excluded("gun", "Guns",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_GUN,
			"reads as ranged but is fought as melee — a ranged wheel naming it would"
				+ " ask the player to break their own style lock"));

		types.add(offerable("hacksword", "Slash swords",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_HACKSWORD, MELEE));
		types.add(offerable("heavysword", "Two-handed swords",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_HEAVYSWORD, MELEE));
		types.add(excluded("heavysword_large", "Colossal blades",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_HEAVYSWORD_LARGE,
			"too narrow a category to hand a player for a whole style cycle"));
		types.add(offerable("pickaxe", "Pickaxes",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_PICKAXE, MELEE));
		types.add(offerable("polearm", "Polearms",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_POLEARM, MELEE));
		types.add(excluded("chargespear", "Charged spears",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_CHARGESPEAR,
			"the client special-cases this category's styles (see StyleTracker), and it"
				+ " is too narrow to hand a player for a whole style cycle"));
		types.add(offerable("polestaff", "Polestaves",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_POLESTAFF, MELEE));
		types.add(offerable("scythe", "Scythes",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_SCYTHE, MELEE));
		types.add(offerable("spear", "Spears",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_SPEAR, MELEE));
		types.add(excluded("banner", "Banners",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_BANNER,
			"a carried banner, not a category anyone trains a style in"));
		types.add(offerable("spiked", "Spiked weapons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_SPIKED, MELEE));
		types.add(offerable("stabsword", "Stab swords",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_STABSWORD, MELEE));

		// --- The hybrid staves: melee AND magic ---
		types.add(offerable("staff", "Staves",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_STAFF, HYBRID_STAFF));
		types.add(offerable("staff_bladed", "Bladed staves",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_STAFF_BLADED, HYBRID_STAFF));
		types.add(offerable("staff_spellblade", "Spellblade staves",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_STAFF_SPELLBLADE, HYBRID_STAFF));

		types.add(offerable("thrown", "Thrown weapons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_THROWN, RANGED));
		types.add(offerable("whip", "Whips",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_WHIP, MELEE));

		types.add(offerable("staff_selfpowering", "Powered staves",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_STAFF_SELFPOWERING, MAGIC));
		types.add(excluded("wand_selfpowering", "Powered wands",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_WAND_SELFPOWERING,
			"no live items in this category, so the preference could never be met"));

		// block mode deals no damage at all, so a cycle spent under this
		// preference is not merely weak, it is unplayable: the player would have
		// to choose between the bonus and killing anything
		types.add(excluded("bulwark", "Bulwarks",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_BULWARK,
			"block mode does no damage — a bulwark cycle is unplayable"));

		types.add(offerable("partisan", "Partisans",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_PARTISAN, MELEE));

		types.add(excluded("tribrid", "Tribrid weapons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_TRIBRID,
			"covers all three styles at once — naming it would hollow out the style lock"));
		types.add(excluded("egg", "Eggs",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_EGG,
			"not a category anyone fights a contract with"));
		types.add(excluded("sailing_cannon", "Sailing cannons",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_SAILING_CANNON,
			"a mounted cannon, not a wielded weapon"));
		types.add(excluded("multi_melee", "Multi-style melee",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_MULTI_MELEE,
			"no stable pool of items to name"));
		types.add(excluded("slashflail", "Slash flails",
			DBTableID.CombatInterfaceWeaponCategory.Row.COMBAT_INTERFACE_SLASHFLAIL,
			"one item, and quest locked"));

		// --- The pseudo-type ---
		// Not a weapon category and not a dbrow: com mode 4, the autocast slot,
		// which StyleTracker.resolve already reads. It is here so that MAGIC's
		// pool is not just the powered staves and the hybrids, and so that "does
		// this kill satisfy the preference" has exactly one answer from exactly
		// one place.
		types.add(offerable(WeaponTypeService.SPELL_CAST_KEY, "Cast spells",
			WeaponTypeService.NO_DBROW, MAGIC));

		return types;
	}

	public static void main(String[] args) throws IOException {
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("note", NOTE);
		file.put("types", types());

		File out = new File("src/main/resources/com/gachaman/data/weapon-types.json");
		try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(file, w);
		}

		int offerable = 0;
		for (WeaponType type : types()) {
			if (type.isOfferable()) {
				offerable++;
			}
		}
		System.out.println("wrote " + out + " (" + types().size() + " categories, "
			+ offerable + " offerable)");
	}
}
