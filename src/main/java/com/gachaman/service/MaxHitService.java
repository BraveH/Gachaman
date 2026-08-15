package com.gachaman.service;

import com.gachaman.model.*;
import java.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.*;

/**
 * What this player could hit at the very best, right now, in one style.
 *
 * <p>Exists for the "land a hit of N+" side bet, which used to size itself off
 * combat level and so dealt bets a bronze-armed account could not physically
 * win. The maths is the standard OSRS one, transcribed from the same steps the
 * Plugin Hub's max-hit-calc uses: effective level from the skill, then
 * {@code floor(0.5 + effective * (strengthBonus + 64) / 640)}.
 *
 * <p>Deliberately the UNBOOSTED, unprayed, no-void figure. Every one of those
 * would raise the estimate, and an estimate that lands above what the player
 * can actually manage recreates the exact bug this replaced. Reading low costs
 * nothing — the bet asks for a fraction of this number, not the number itself.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MaxHitService {
	/**
	 * Standard-book combat spells by the Magic level that unlocks them, paired
	 * with their base max hit. The fire variant of each tier — the strongest of
	 * the four elements — because the player picks their own spell and this is
	 * a ceiling, not a prediction. Ascending, so the scan takes the last match.
	 */
	private static final int[] SPELL_LEVELS = {1, 13, 35, 59, 75, 95};
	private static final int[] SPELL_MAX_HITS = {2, 8, 12, 16, 20, 24};

	private final Client client;
	private final ItemManager itemManager;

	/** Base max hit for a spell the player's Magic level allows. */
	static int spellBaseMaxHit(int magicLevel) {
		int base = SPELL_MAX_HITS[0];
		for (int i = 0; i < SPELL_LEVELS.length; i++) {
			if (magicLevel >= SPELL_LEVELS[i]) {
				base = SPELL_MAX_HITS[i];
			}
		}
		return base;
	}

	/**
	 * The melee/ranged max-hit curve. Pure so it can be tested without a client:
	 * both styles share it, differing only in which level and which equipment
	 * strength bonus they read.
	 */
	static int gearedMaxHit(int level, int strengthBonus) {
		double effective = Math.max(1, level) + 8;
		return Math.max(1, (int) Math.floor(0.5 + effective * (strengthBonus + 64) / 640.0));
	}

	/** Magic ignores the curve entirely: spell base, scaled by damage percent. */
	static int magicMaxHit(int magicLevel, float magicDamagePercent) {
		return Math.max(1,
			(int) Math.floor(spellBaseMaxHit(magicLevel) * (1.0 + magicDamagePercent / 100.0)));
	}

	/**
	 * Best hit the player could land in the style named by the persisted state,
	 * or 0 when there is no style rolled yet or the name is unrecognised.
	 */
	public int estimateFor(String styleName) {
		if (styleName == null)
			return 0;
		try {
			return estimate(AttackStyle.valueOf(styleName));
		}
		catch (IllegalArgumentException e) {
			return 0; // a style name from a newer save than this build knows
		}
	}

	/** Best hit the player could land in this style, or 0 when it cannot be read. */
	public int estimate(AttackStyle style) {
		if (style == null)
			return 0;
		try {
			switch (style) {
				case RANGED:
					return gearedMaxHit(client.getRealSkillLevel(Skill.RANGED), rangedStrength());
				case MAGIC:
					return magicMaxHit(client.getRealSkillLevel(Skill.MAGIC), magicDamage());
				default:
					return gearedMaxHit(client.getRealSkillLevel(Skill.STRENGTH), meleeStrength());
			}
		}
		catch (Exception e) {
			return 0; // no container, no stats, not logged in — the bet falls to its floor
		}
	}

	private int meleeStrength() {
		int total = 0;
		for (ItemEquipmentStats stats : wornStats()) {
			total += stats.getStr();
		}
		return total;
	}

	private int rangedStrength() {
		int total = 0;
		for (ItemEquipmentStats stats : wornStats()) {
			total += stats.getRstr();
		}
		return total;
	}

	private float magicDamage() {
		float total = 0;
		for (ItemEquipmentStats stats : wornStats()) {
			total += stats.getMdmg();
		}
		return total;
	}

	private List<ItemEquipmentStats> wornStats() {
		List<ItemEquipmentStats> out = new ArrayList<>();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
			return out;
		for (Item item : worn.getItems()) {
			if (item == null || item.getId() <= 0)
				continue;
			ItemStats stats = itemManager.getItemStats(item.getId());
			if (stats != null && stats.getEquipment() != null) {
				out.add(stats.getEquipment());
			}
		}
		return out;
	}
}
