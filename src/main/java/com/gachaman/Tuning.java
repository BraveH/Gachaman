package com.gachaman;

import com.gachaman.model.FirstStamp;
import com.gachaman.model.Rarity;
import com.gachaman.model.TaskDifficulty;
import java.util.EnumMap;
import java.util.Map;

/**
 * All economy/RNG constants in one place. Values are deliberate tuning
 * decisions, not config — the gamemode is meant to be consistent.
 */
public final class Tuning
{
	private Tuning()
	{
	}

	// --- Task rewards (GC) ---
	public static final Map<TaskDifficulty, Integer> PER_KILL_GC = new EnumMap<>(Map.of(
		TaskDifficulty.EASY, 8,
		TaskDifficulty.MEDIUM, 16,
		TaskDifficulty.HARD, 32,
		TaskDifficulty.INSANE, 64));

	/**
	 * Per-kill GC scales with the combat-level DIFFERENCE between the slain
	 * NPC and the player (diff = npcCb - playerCb):
	 *  - up to 5 levels below you: the flat 1x base
	 *  - your level or above: starts at 1.1x and accelerates with the gap
	 *  - far beneath you: decays toward a floor (farming trivial mobs pays dust)
	 */
	public static final double KILL_DIFF_FLOOR = 0.1;
	public static final int KILL_DIFF_GRACE = 5;          // levels below you still worth 1x
	public static final double KILL_DIFF_UNDER_RATE = 0.02;  // decay per level beyond grace
	public static final double KILL_DIFF_EQUAL_BONUS = 0.1;  // matching your level pays 1.1x
	/**
	 * Punching up scales by the RATIO npcCb/playerCb, not the absolute gap:
	 * +5 levels at cb 3 is a monster ~2.7x your level (huge payout), while
	 * +5 at cb 70 is a near-peer (small bonus). Growth is linear+quadratic in
	 * (ratio - 1), capped.
	 */
	public static final double KILL_RATIO_LINEAR = 1.5;
	public static final double KILL_RATIO_QUAD = 0.75;
	public static final double KILL_DIFF_CAP = 5.0;

	/**
	 * Early-game compensation: kills are slower at low combat level, so kill
	 * GC gets a bonus that tapers linearly from +150% at cb 3 to nothing at
	 * cb 70+.
	 */
	public static final int LOWLEVEL_CEILING = 70;
	public static final double LOWLEVEL_MAX_BONUS = 1.5;

	public static double lowLevelMultiplier(int playerCb)
	{
		if (playerCb >= LOWLEVEL_CEILING)
		{
			return 1.0;
		}
		double fraction = (double) (LOWLEVEL_CEILING - playerCb) / (LOWLEVEL_CEILING - 3);
		return 1.0 + LOWLEVEL_MAX_BONUS * Math.min(1.0, Math.max(0.0, fraction));
	}

	/**
	 * Rhythm Combo: consecutive on-task kills within the window build stacks;
	 * each stack adds maxBonus/MAX_STACKS to the kill multiplier. The max
	 * bonus is +30% at low combat, fading linearly between the fade bounds to
	 * a permanent +10% floor — the combo never fully retires.
	 */
	public static final int COMBO_WINDOW_TICKS = 42;       // ~25 seconds
	public static final int COMBO_IDLE_RESET_TICKS = 100;  // ~60 seconds
	public static final int COMBO_MAX_STACKS = 10;
	public static final double COMBO_MAX_BONUS_LOW = 0.30;
	public static final double COMBO_MAX_BONUS_FLOOR = 0.10;
	public static final int COMBO_FADE_START_CB = 25;
	public static final int COMBO_FADE_END_CB = 45;

	public static double comboMaxBonus(int playerCb)
	{
		if (playerCb <= COMBO_FADE_START_CB)
		{
			return COMBO_MAX_BONUS_LOW;
		}
		if (playerCb >= COMBO_FADE_END_CB)
		{
			return COMBO_MAX_BONUS_FLOOR;
		}
		double fraction = (double) (playerCb - COMBO_FADE_START_CB)
			/ (COMBO_FADE_END_CB - COMBO_FADE_START_CB);
		return COMBO_MAX_BONUS_LOW - (COMBO_MAX_BONUS_LOW - COMBO_MAX_BONUS_FLOOR) * fraction;
	}

	public static double comboMultiplier(int stacks, int playerCb)
	{
		int capped = Math.max(0, Math.min(COMBO_MAX_STACKS, stacks));
		return 1.0 + comboMaxBonus(playerCb) * capped / COMBO_MAX_STACKS;
	}

	public static double killCbMultiplier(int playerCb, int npcCb)
	{
		int diff = npcCb - playerCb;
		if (diff >= 0)
		{
			double over = (double) npcCb / Math.max(1, playerCb) - 1.0;
			double mult = 1.0 + KILL_DIFF_EQUAL_BONUS
				+ over * KILL_RATIO_LINEAR
				+ over * over * KILL_RATIO_QUAD;
			return Math.min(KILL_DIFF_CAP, mult);
		}
		if (diff >= -KILL_DIFF_GRACE)
		{
			return 1.0;
		}
		return Math.max(KILL_DIFF_FLOOR, 1.0 + (diff + KILL_DIFF_GRACE) * KILL_DIFF_UNDER_RATE);
	}

	public static final Map<TaskDifficulty, Integer> COMPLETION_GC = new EnumMap<>(Map.of(
		TaskDifficulty.EASY, 250,
		TaskDifficulty.MEDIUM, 550,
		TaskDifficulty.HARD, 950,
		TaskDifficulty.INSANE, 1500));

	// --- Style cycle ---
	/** Style re-rolls after exactly this many completed tasks (charge-weighted). */
	public static final int CYCLE_TASKS = 5;
	public static final double COMPACTOR_WEIGHT = 2.0;
	public static final double EXTENDER_WEIGHT = 0.5;
	public static final int COMPACTOR_PRICE_GC = 400;
	public static final int EXTENDER_PRICE_GC = 250;

	/**
	 * Ironman honor rule: a kill another player damaged counts half — half a
	 * kill count (two assisted kills = one count; with a Compactor the doubled
	 * count halves back to 1) and half the kill GC.
	 */
	public static final double ASSISTED_KILL_MULT = 0.5;

	// --- Violations ---
	public static final int VIOLATION_ATTACK_PENALTY_MULT = 2; // x active task per-kill GC
	public static final int VIOLATION_ATTACK_PENALTY_NO_TASK = 25;
	public static final int VIOLATION_ATTACK_PENALTY_FLOOR = 10;
	public static final double TAINT_INCOME_MULT = 0.5;
	public static final double REDEMPTION_KILL_MULT = 1.4;

	// --- Chests ---
	public enum Chest
	{
		RUSTY, BATTERED, GILDED, ORNATE
	}

	public static final Map<Chest, Integer> CHEST_PRICE_GC = new EnumMap<>(Map.of(
		Chest.RUSTY, 150,
		Chest.BATTERED, 500,
		Chest.GILDED, 800,
		Chest.ORNATE, 1000));

	public static final Map<Chest, Integer> CHEST_CARDS = new EnumMap<>(Map.of(
		Chest.RUSTY, 1,
		Chest.BATTERED, 1,
		Chest.GILDED, 2,
		Chest.ORNATE, 3));

	/** Rarity odds per chest, percent, order C/U/R/E/L. Common absorbs renormalization. */
	public static final Map<Chest, double[]> CHEST_ODDS = new EnumMap<>(Map.of(
		Chest.RUSTY, new double[]{100, 0, 0, 0, 0},
		Chest.BATTERED, new double[]{62, 24, 9.5, 3.5, 1},
		Chest.GILDED, new double[]{55, 26, 12, 5, 2},
		Chest.ORNATE, new double[]{48, 26, 15, 7.5, 3.5}));

	/**
	 * The Rusty chest is the starter tier: buyable 3 times ever, COMMON cards
	 * only, pool clamped to unlocked slots + strictly wieldable gear, no
	 * jackpot/deed/pity, but a juiced shiny rate so the first sparkle lands in
	 * the opening session.
	 */
	public static final int RUSTY_LIFETIME_CAP = 3;
	public static final double RUSTY_SHINY_CHANCE = 1.0 / 16;

	// --- Variants ---
	public static final double SHINY_CHANCE = 1.0 / 64;
	public static final double HOLOGRAM_CHANCE = 1.0 / 256;

	// --- Roll proximity (rolls stay near what the player can actually wield) ---
	/** Skill level needed for tier rank index+1 (bronze..dragon-band). */
	public static final int[] TIER_RANK_LEVELS = {1, 1, 5, 10, 20, 30, 40, 60};
	/** Rolled tiers may exceed the player's wieldable rank by this much. */
	public static final int ROLL_TIER_HEADROOM = 2;

	public static int maxRankForLevel(int level)
	{
		int rank = 1;
		for (int i = 0; i < TIER_RANK_LEVELS.length; i++)
		{
			if (level >= TIER_RANK_LEVELS[i])
			{
				rank = i + 1;
			}
		}
		return rank;
	}

	// --- Pity ---
	public static final int PITY_SOFT_START = 12;
	public static final double PITY_BONUS_PER_OPEN = 2.0; // additive % points to Epic+ mass
	public static final int PITY_HARD_CAP = 30;
	public static final int PITY_HARD_CAP_PRESTIGE2 = 26;

	// --- Jackpot ---
	public static final double JACKPOT_CHANCE = 1.0 / 100;
	public static final double JACKPOT_CHANCE_PRESTIGE3 = 1.0 / 60;

	// --- Deeds ---
	public static final Map<Chest, Double> DEED_CHANCE = new EnumMap<>(Map.of(
		Chest.BATTERED, 1.0 / 25,
		Chest.GILDED, 1.0 / 18,
		Chest.ORNATE, 1.0 / 12));

	public static final int[] DEED_TASK_MILESTONES = {10, 25, 45, 70, 100, 140, 190, 250, 320};
	public static final int DEED_SATURATED_GC = 2000;

	/**
	 * Deed Fragments: during the first FIVE tasks, above-easy completions grant
	 * fragments (medium 1 / hard 2 / insane 3); ten fragments forge ONE bonus
	 * deed, ever. All-hard exactly forges; anything easier misses — difficulty
	 * selection is the point.
	 */
	public static final int FRAGMENT_WINDOW_TASKS = 5;
	public static final int FRAGMENTS_REQUIRED = 10;

	public static int fragmentsFor(TaskDifficulty difficulty)
	{
		switch (difficulty)
		{
			case MEDIUM:
				return 1;
			case HARD:
				return 2;
			case INSANE:
				return 3;
			default:
				return 0;
		}
	}

	// --- Duplicates (NORMAL variant only) ---
	public static final Map<Rarity, Integer> DUPLICATE_GC = new EnumMap<>(Map.of(
		Rarity.COMMON, 25,
		Rarity.UNCOMMON, 60,
		Rarity.RARE, 150,
		Rarity.EPIC, 400,
		Rarity.LEGENDARY, 1000));

	// --- Weekly shop ---
	public static final Map<Rarity, Integer> SHOP_PRICE_GC = new EnumMap<>(Map.of(
		Rarity.COMMON, 800,
		Rarity.UNCOMMON, 1500,
		Rarity.RARE, 4000,
		Rarity.EPIC, 9000,
		Rarity.LEGENDARY, 20000));

	// --- Reroll tokens ---
	public static final int TOKEN_CB_INTERVAL = 10;

	// --- Side bets ---
	public static final double SIDEBET_SEALED_CHANCE = 0.30;
	public static final double SIDEBET_MIN_PAYOUT_FRAC = 0.15;
	public static final double SIDEBET_MAX_PAYOUT_FRAC = 0.40;

	// --- Duo ---
	public static final double DUO_REWARD_MULT = 1.6;
	public static final double DUO_STYLE_CLASH_BONUS = 0.25;
	public static final double DUO_CARRY_MULT = 0.8;
	public static final int DUO_IDLE_TICKS = 1000;

	// --- Journal ---
	public static final int PB_RECORD_GC = 250;

	/** Fortune-timeline audit cap: oldest entries drop past this. */
	public static final int TIMELINE_MAX_EVENTS = 500;

	// --- Firsts Journal ---
	/**
	 * One-time stamp bounties. Sum = 495 GC — deliberately just short of one
	 * Battered chest, so chasing every first almost (not quite) funds it.
	 */
	public static final Map<FirstStamp, Integer> FIRSTS_GC = new EnumMap<>(Map.ofEntries(
		Map.entry(FirstStamp.FIRST_KILL, 15),
		Map.entry(FirstStamp.FIRST_TASK, 40),
		Map.entry(FirstStamp.FIRST_SIDE_BET, 30),
		Map.entry(FirstStamp.FIRST_CHEST, 25),
		Map.entry(FirstStamp.FIRST_ASSIGN, 20),
		Map.entry(FirstStamp.FIRST_DUPE, 10),
		Map.entry(FirstStamp.FIRST_UNCOMMON, 15),
		Map.entry(FirstStamp.FIRST_RARE, 35),
		Map.entry(FirstStamp.FIRST_EPIC, 60),
		Map.entry(FirstStamp.FIRST_SHINY, 75),
		Map.entry(FirstStamp.FIRST_RECORD, 30),
		Map.entry(FirstStamp.FIRST_TAINT_CLEARED, 30),
		Map.entry(FirstStamp.FIRST_CYCLE, 40),
		Map.entry(FirstStamp.FIRST_DEED, 50),
		Map.entry(FirstStamp.FIRST_REROLL_SPENT, 20)));

	// --- Bestiary ---
	/** First on-task kill of a new species pays this on top of normal kill GC. */
	public static final int DISCOVERY_GC = 25;
	public static final int[] BESTIARY_MILESTONES = {50, 100, 150};
	public static final int[] BESTIARY_MILESTONE_GC = {100, 150, 200};

	// --- Graduation ---
	/** Tier-up fanfares only fire while the NEW rank is at or below this. */
	public static final int GRADUATION_MAX_RANK = 4;
	public static final int GRADUATION_GC = 25;

	// --- Stardust ---
	/** Near-miss band: shiny roll landed within this multiple of the shiny chance. */
	public static final double STARDUST_NEAR_MISS_MULT = 4;
	/** Stardust needed to bless the next chest with double shiny attempts. */
	public static final int STARDUST_REQUIRED = 8;

	// --- Prestige ---
	public static final int PRESTIGE_TASKS_REQUIRED = 250;
	public static final double PRESTIGE_COLLECTION_FRACTION = 0.90;
	public static final int PRESTIGE_GC_COST = 25000;
	public static final double PRESTIGE_GC_BONUS_PER_RANK = 0.05;
}
