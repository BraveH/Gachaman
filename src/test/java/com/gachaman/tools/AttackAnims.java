package com.gachaman.tools;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.gameval.*;

/**
 * Authors {@code attack-anims.json}: the animation ids StyleTracker classifies
 * attacks by.
 *
 * <p>The ids are pure data and belong in a resource, but writing them there by
 * hand would mean 100 magic numbers with nothing checking them. So they are
 * spelled here as {@link AnimationID} constants — where the compiler still
 * verifies every name against the live API — and this tool projects them into
 * the resource the plugin actually reads.
 *
 * <p>{@code AttackAnimResourceTest} asserts the shipped resource still matches
 * these lists, so a RuneLite rename cannot silently drop an animation and let a
 * teleport score as a melee attack against a magic contract.
 *
 * <p>Regenerate with {@code ./gradlew attackAnims}.
 */
public final class AttackAnims {
	/**
	 * Player animations that are unambiguously offensive spell casts. These
	 * judge MAGIC regardless of stance or mark — a melee-stance manual cast
	 * whose Cast click was missed (or whose mark expired) still animates one
	 * of these.
	 */
	public static final Set<Integer> OFFENSIVE_MAGIC = new LinkedHashSet<>(Set.of(
		AnimationID.HUMAN_CASTSTRIKE,
		AnimationID.HUMAN_CASTSTRIKE_STAFF,
		AnimationID.HUMAN_CASTWAVE,
		AnimationID.HUMAN_CASTWAVE_STAFF,
		AnimationID.HUMAN_CAST_SURGE,
		AnimationID.HUMAN_CAST_SURGE_FAST,
		AnimationID.HUMAN_CASTENTANGLE,
		AnimationID.HUMAN_CASTENTANGLE_STAFF,
		AnimationID.HUMAN_CASTIBANBLAST,
		AnimationID.HUMAN_CASTCRUMBLEUNDEAD,
		AnimationID.HUMAN_CASTCRUMBLEUNDEAD_STAFF,
		AnimationID.HUMAN_CASTING,
		AnimationID.ZAROS_CASTING,
		AnimationID.ZAROS_VERTICAL_CASTING,
		AnimationID.SLAYER_MAGICDART_CAST,
		AnimationID.HUMAN_SPELLCAST_GRASP,
		AnimationID.HUMAN_SPELLCAST_DEMONBANE,
		AnimationID.HUMAN_CASTCONFUSE,
		AnimationID.HUMAN_CASTWEAKEN,
		AnimationID.HUMAN_CASTCURSE,
		AnimationID.HUMAN_CASTENFEEBLE,
		AnimationID.HUMAN_CASTSTUN,
		AnimationID.HUMAN_CASTCONFUSE_STAFF,
		AnimationID.HUMAN_CASTWEAKEN_STAFF,
		AnimationID.HUMAN_CASTCURSE_STAFF,
		AnimationID.HUMAN_CASTENFEEBLE_STAFF,
		AnimationID.HUMAN_CASTSTUN_STAFF,
		AnimationID.HUMAN_CASTSTRIKE_WALKMERGE,
		AnimationID.HUMAN_CAST_SURGE_WALKMERGE,
		AnimationID.HUMAN_CASTCURSE_STAFF_WALKMERGE,
		AnimationID.HUMAN_CASTSTRIKE_STAFF_WALKMERGE,
		AnimationID.HUMAN_CASTCONFUSE_WALKMERGE,
		AnimationID.HUMAN_CASTCONFUSE_STAFF_WALKMERGE,
		AnimationID.HUMAN_CASTWEAKEN_WALKMERGE,
		AnimationID.HUMAN_CASTWEAKEN_STAFF_WALKMERGE,
		AnimationID.HUMAN_CASTCURSE_WALKMERGE,
		AnimationID.HUMAN_CASTWAVE_WALKMERGE,
		AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE,
		AnimationID.NIGHTMARE_STAFF_SPECIAL,
		AnimationID.TOA_SOT_CAST_B,
		AnimationID.POG_WARPED_SCEPTRE_ATTACK));

	/**
	 * Magic-XP-granting utility: alchemy, superheat, telegrab, teleports,
	 * enchants and the Arceuus/Lunar self-buffs. Their XP drop must never
	 * trigger a pardon — otherwise a missed forbidden melee swing could be
	 * erased by a quick alch.
	 */
	public static final Set<Integer> MAGIC_UTILITY = new LinkedHashSet<>(Set.of(
		AnimationID.HUMAN_CASTLOWLVLALCHEMY,
		AnimationID.HUMAN_CASTHIGHLVLALCHEMY,
		AnimationID.HUMAN_CASTLOWLVLALCHEMY_FIRE,
		AnimationID.HUMAN_CASTHIGHLVLALCHEMY_FIRE,
		AnimationID.HUMAN_CASTSUPERHEATITEM,
		AnimationID.HUMAN_CASTTELEGRAB,
		AnimationID.HUMAN_CASTTELEPORT,
		AnimationID.HUMAN_CASTTELEPORT_REVERSE,
		AnimationID.HUMAN_CAST2_TELEPORT,
		AnimationID.HUMAN_CAST_ENCHANTRING,
		AnimationID.HUMAN_CASTING_TELE_BLOCK,
		AnimationID.HUMAN_CASTING_TELE_BLOCK_STAFF,
		AnimationID.HUMAN_CASTING_TELE_BLOCK_STAFF_WALKMERGE,
		// the self-buffs pay Magic XP too, so they belong here as well: without
		// it, resurrecting a thrall one tick after a genuine forbidden sword
		// swing would hand out a pardon the swing never earned
		AnimationID.HUMAN_SPELLCAST_RESURRECT,
		AnimationID.HUMAN_SPELLCAST_SHADOWVEIL,
		AnimationID.HUMAN_CAST_VILEVIGOUR,
		AnimationID.HUMAN_CAST_OFFERING,
		AnimationID.HUMAN_CAST_SELFIMBUE,
		AnimationID.HUMAN_CASTHEAL,
		AnimationID.HUMAN_CASTCHARGEORB));

	/**
	 * Everything provably NOT an attack. Never judged — and crucially they do
	 * NOT consume a pending Cast mark (alching or eating while pathing to a
	 * marked target must not eat the mark and let the eventual cast be
	 * stance-judged as melee).
	 *
	 * <p>A superset of {@link #MAGIC_UTILITY}: every Magic-XP utility is also
	 * unjudgeable. Composed rather than restated so the two can never drift.
	 */
	public static final Set<Integer> NEVER_JUDGE = neverJudge();

	private static Set<Integer> neverJudge() {
		Set<Integer> all = new LinkedHashSet<>(MAGIC_UTILITY);
		all.addAll(Set.of(
			// the preparation poses pay no XP, so they are unjudgeable without
			// being pardon-worthy
			AnimationID.HUMAN_PREPARE_OFFERING,
			AnimationID.HUMAN_PREPARE_OFFERING_LOOP,
			AnimationID.HUMAN_EAT_BANANA,
			AnimationID.HUMAN_DRINK_RUM,
			AnimationID.HUMAN_EAT,
			AnimationID.HUMAN_PICKUPFLOOR,
			AnimationID.HUMAN_STAFF_BLOCK,
			AnimationID.HUMAN_STAFF_DEF,
			AnimationID.HUMAN_STAFFORB_BLOCK,
			AnimationID.HUMAN_STAFFORB_DEF,
			AnimationID.HUMAN_UNARMEDBLOCK,
			AnimationID.HUMAN_UNARMED_DEF,
			AnimationID.HUMAN_SHIELD_DEFENCE));
		return all;
	}

	private AttackAnims() {
	}

	public static Map<String, Set<Integer>> groups() {
		Map<String, Set<Integer>> groups = new LinkedHashMap<>();
		groups.put("offensiveMagic", OFFENSIVE_MAGIC);
		groups.put("neverJudge", NEVER_JUDGE);
		groups.put("magicUtility", MAGIC_UTILITY);
		return groups;
	}

	public static void main(String[] args) throws IOException {
		File out = new File("src/main/resources/com/gachaman/data/attack-anims.json");
		try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(groups(), w);
		}
		System.out.println("wrote " + out + " ("
			+ OFFENSIVE_MAGIC.size() + " offensive, "
			+ NEVER_JUDGE.size() + " never-judge, "
			+ MAGIC_UTILITY.size() + " magic-utility)");
	}
}
