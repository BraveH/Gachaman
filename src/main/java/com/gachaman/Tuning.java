package com.gachaman;

import com.gachaman.model.CardWear;
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
	/**
	 * Skill level needed for tier rank index+1 (bronze..dragon-band). METAL ONLY:
	 * this transcribes the melee ladder rank-for-rank, so it models metal exactly and
	 * nothing else. Dhide and robes rank by power on their own ladder (robes has no
	 * rank 3/5/7 at all), so they read real requirements out of tiers.json instead.
	 */
	public static final int[] TIER_RANK_LEVELS = {1, 1, 5, 10, 20, 30, 40, 60};
	/** Rolled metal tiers may exceed the player's wieldable rank by this much. */
	public static final int ROLL_TIER_HEADROOM = 2;
	/**
	 * The same aspirational slack for dhide/robes, denominated in skill levels because
	 * a rank step on those ladders is worth anywhere from 0 to 30 levels.
	 */
	public static final int ROLL_LEVEL_HEADROOM = 10;
	/**
	 * The house lean: inside a proximity-gated roll, gear that only got in through the
	 * headroom is drawn at this fraction of the weight of gear the player can wield
	 * today. Strictly between 0 and 1 on purpose — at 0 the headroom band would be dead
	 * (it is deliberate aspirational slack, see ROLL_TIER_HEADROOM), and at 1 there is
	 * no lean at all. Safe to retune with immediate feedback: the Shop tab's Chest Odds
	 * panel prints the real resulting percentages, derived from this same constant.
	 */
	public static final double HOUSE_LEAN_HEADROOM_WEIGHT = 0.35;

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

	/**
	 * Can a tier needing reqPrimary/reqDefence be rolled at these levels? Pure and
	 * static because its only caller, ChestService.isReachable, short-circuits to true
	 * whenever Client is null — which is every headless test, so testing it there
	 * would be vacuously green.
	 */
	public static boolean withinReach(int primaryLevel, int defenceLevel,
		int reqPrimary, int reqDefence, int headroom)
	{
		return primaryLevel + headroom >= reqPrimary && defenceLevel + headroom >= reqDefence;
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

	// --- The Charter Office ---
	/**
	 * Kills banked on a species before its contract may be chartered. Read off
	 * the journal's existing per-monster tally, so nothing new is counted.
	 */
	public static final int CHARTER_KILLS_REQUIRED = 25;
	public static final int CHARTER_PRICE_MIN_GC = 800;
	public static final int CHARTER_PRICE_MAX_GC = 2500;
	/** A deed is quoted in round numbers — never 1637 GC. */
	public static final int CHARTER_PRICE_STEP_GC = 10;
	/** How long an unaccepted deed sits on the board before the GC comes back. */
	public static final int CHARTER_HOLD_TICKS = 500;
	/**
	 * The hold deadline is stored as WALL CLOCK, not as a tick countdown: a
	 * persisted counter would need a mutate every single tick, and every mutate
	 * re-gzips and re-hashes the entire state. 500 ticks at 600ms each.
	 */
	public static final long CHARTER_HOLD_MS = CHARTER_HOLD_TICKS * 600L;

	/**
	 * A deed costs what the target is worth relative to the buyer: the target's
	 * combat level as a fraction of the player's, clamped to the same band the
	 * contract board already uses (EASY's cap fraction at the bottom, INSANE's at
	 * the top), interpolated across the price range and rounded off.
	 *
	 * Scaling by RATIO rather than by absolute level is what keeps the price
	 * honest at both ends of the game: a level-3 chicken is never worth INSANE
	 * money to anyone, and a target the player can only just legally be offered
	 * costs full price whether they are combat 40 or combat 126.
	 */
	public static int charterPriceGc(int playerCb, int npcCb)
	{
		double lo = TaskDifficulty.EASY.getCbCapFraction();
		double hi = TaskDifficulty.INSANE.getCbCapFraction();
		double ratio = Math.max(0, npcCb) / (double) Math.max(1, playerCb);
		double t = (Math.max(lo, Math.min(hi, ratio)) - lo) / (hi - lo);
		double raw = CHARTER_PRICE_MIN_GC + t * (CHARTER_PRICE_MAX_GC - CHARTER_PRICE_MIN_GC);
		long stepped = Math.round(raw / CHARTER_PRICE_STEP_GC) * (long) CHARTER_PRICE_STEP_GC;
		return (int) Math.max(CHARTER_PRICE_MIN_GC, Math.min(CHARTER_PRICE_MAX_GC, stepped));
	}

	// --- Reroll tokens ---
	public static final int TOKEN_CB_INTERVAL = 10;

	// --- Side bets ---
	public static final double SIDEBET_SEALED_CHANCE = 0.30;
	public static final double SIDEBET_MIN_PAYOUT_FRAC = 0.15;
	public static final double SIDEBET_MAX_PAYOUT_FRAC = 0.40;

	// --- Shared party contracts ---
	public static final double PARTY_REWARD_MULT = 1.6;
	/**
	 * Paid ONCE when the party covers 2+ distinct rolled styles — flat, never
	 * scaled by how many distinct styles there are. A party of five all running
	 * ranged pays 1.60x; any party covering two OR three styles pays 1.85x.
	 */
	public static final double PARTY_STYLE_CLASH_BONUS = 0.25;
	public static final double PARTY_CARRY_MULT = 0.8;
	public static final int PARTY_IDLE_TICKS = 1000;
	/**
	 * Grace given to a shared contract resurrected from disk before the watchdog
	 * will believe a one-member party. ~1 minute: RuneLite rejoins the previous
	 * party asynchronously at startup, so the roster is very often still empty at
	 * the moment the state loads, and reading that as "the party is gone" would
	 * convert a live contract before anyone had a chance to appear.
	 *
	 * Only the EMPTY-party branch waits on this. A resurrected contract that is in
	 * a populated party but never hears from anyone is still settled by
	 * PARTY_IDLE_TICKS on the usual terms — this is the narrower case where
	 * waiting ten minutes for a party of one would be theatre, and where a relog
	 * would otherwise hand a dead contract a fresh window every time.
	 */
	public static final int PARTY_RESYNC_TICKS = 100;
	/**
	 * Grace given to a partner who has DROPPED OUT of the party roster before the
	 * carry clause writes them off. ~3 minutes, which is a RuneLite relaunch plus a
	 * login plus the asynchronous party rejoin, with room to spare.
	 *
	 * Zero here — the old behaviour — makes the resume feature inert for a party of
	 * two, which is the common case: closing your client removes you from the roster
	 * at once, so your partner's very next sweep would convert the shared contract to
	 * solo about fifteen seconds into your restart, and you would come back to
	 * resurrect a contract nobody is on any more. The grace exists so the two sides
	 * of a restart can still find each other.
	 *
	 * Strictly a DELAY: it never causes a conversion that would not otherwise have
	 * happened, and never changes what one pays. The cost of being wrong is a few
	 * minutes of party-rate pay for a party that had genuinely left, which is far
	 * cheaper than silently voiding a live contract every time somebody crashes.
	 */
	public static final int PARTY_DEPART_GRACE_TICKS = 300;
	/**
	 * Sanity bounds on a TRANSMITTED combat level, applied per level before the
	 * party's fighting weight is averaged. Under the old lowest-level rule a
	 * broken or hostile value could only ever make contracts EASIER, so it was
	 * self-limiting; an average lets one inflated number drag the whole party
	 * onto a monster nobody can kill, and a party contract cannot be abandoned.
	 * 3 and 126 are the real floor and ceiling of the combat-level formula.
	 */
	public static final int COMBAT_LEVEL_MIN = 3;
	public static final int COMBAT_LEVEL_MAX = 126;
	/**
	 * Presence heartbeat, ~12s. Presence is re-sent whenever any field of it
	 * changes; the heartbeat exists only so a client that joined late, or that
	 * missed a message, converges without anyone doing anything.
	 */
	public static final int PARTY_PRESENCE_HEARTBEAT_TICKS = 20;
	/**
	 * Five missed heartbeats (~60s) before a member's line is treated as no
	 * signal. Deliberately forgiving: a row flickering between "on contract"
	 * and "no signal" reads as a bug, and presence is only ever cosmetic.
	 */
	public static final int PARTY_PRESENCE_STALE_TICKS = 100;
	/**
	 * The Patron's Mark tier thresholds: shared contracts finished with one
	 * partner. STRICTLY COSMETIC and deliberately so — the mark pays no GC,
	 * feeds no CreditSink modifier, multiplies nothing and gates nothing,
	 * because the moment a patron count is worth something the correct play
	 * becomes farming a friend for it. Do not hang an economic hook here.
	 * Ascending; PatronMark's label array stays exactly one longer than this.
	 */
	public static final int[] PATRON_TIERS = {10, 25, 100};
	/**
	 * Distinct partners the mark will remember. The key space is supplied by
	 * OTHER players' clients and every mutate re-encodes the whole save, so it
	 * is bounded. At the cap a newcomer only displaces another one-contract
	 * stranger — a real history is never dropped.
	 */
	public static final int PATRON_MAX_PARTNERS = 100;

	// --- Double Docket ---
	/**
	 * Completion bonus when the contract target is also the player's live Slayer
	 * assignment. Deliberately small and MULTIPLICATIVE: it stacks onto the
	 * party/clash/carry chain rather than replacing any of it, and it lands
	 * before the taint halving like every other completion modifier, so a
	 * tainted player still only gets 1.2x of their reduced payout.
	 *
	 * Offer generation is NOT weighted toward the Slayer assignment. Biasing the
	 * roll would add RNG draws inside the seeded party path and desync every
	 * client in the party, so alignment stays a happy accident the game pays for.
	 */
	public static final double DOUBLE_DOCKET_MULT = 1.2;

	// --- The Ante ---
	/**
	 * A voluntary side wager on the hardest contracts only: stake a slice of the
	 * purse BEFORE signing, finish and it comes back doubled, die and it is gone.
	 *
	 * The percent band is the player's choice inside these bounds; the absolute
	 * cap keeps one contract from becoming an economy-sized swing for a rich
	 * account. The purse floor is the other half of that guard — under it the
	 * minimum stake is not a meaningful risk, only a tax on being broke, so the
	 * wager is not offered at all.
	 */
	public static final int ANTE_MIN_PERCENT = 10;
	public static final int ANTE_MAX_PERCENT = 50;
	public static final int ANTE_MAX_GC = 5000;
	public static final int ANTE_MIN_PURSE_GC = 250;
	/**
	 * A won Ante puts this multiple of the stake back in hand: the principal
	 * returns raw out of escrow and the remaining (MULT - 1) is paid as income,
	 * so only the PROFIT is exposed to perks and to the taint halving.
	 */
	public static final int ANTE_PAYOUT_MULT = 2;

	// --- Journal ---
	public static final int PB_RECORD_GC = 250;

	/** Fortune-timeline audit cap: oldest entries drop past this. */
	public static final int TIMELINE_MAX_EVENTS = 500;

	/**
	 * Contract dossier cap: the oldest record drops past this. Deliberately well
	 * under TIMELINE_MAX_EVENTS — a record is a fat struct rather than a short
	 * line, and StateCodec gzips plus SHA-256s the ENTIRE state synchronously on
	 * every mutate, so the encoded size is a cost paid on every kill.
	 */
	public static final int DOSSIER_MAX_RECORDS = 200;

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

	// --- Card wear (cosmetic) ---
	/**
	 * Service Record kills a card must have carried to earn each wear stage.
	 * Purely a badge: nothing in the plugin branches on the returned CardWear,
	 * so moving these numbers can never change a roll, a payout, a set, a
	 * prestige burn or a requirement — only how the face is painted.
	 *
	 * <p>The lowest threshold must stay at or above 1. Wear implies a visible
	 * service count, and CardRenderer measures the wear-free top band from the
	 * service pill it has already drawn; a zero threshold would ask it to
	 * protect a pill that was never painted.
	 */
	public static final int WEAR_HAIRLINE_KILLS = 100;
	public static final int WEAR_CRACKED_KILLS = 400;
	public static final int WEAR_SHATTERED_KILLS = 1000;

	/**
	 * The whole rule. Saturates at the top stage and floors at NONE, so a
	 * corrupt or absent record paints nothing rather than inventing history.
	 */
	public static CardWear cardWear(int killsServed)
	{
		if (killsServed >= WEAR_SHATTERED_KILLS)
		{
			return CardWear.SHATTERED;
		}
		if (killsServed >= WEAR_CRACKED_KILLS)
		{
			return CardWear.CRACKED;
		}
		if (killsServed >= WEAR_HAIRLINE_KILLS)
		{
			return CardWear.HAIRLINE;
		}
		return CardWear.NONE;
	}
}
