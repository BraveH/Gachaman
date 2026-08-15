package com.gachaman;

import com.gachaman.model.*;
import java.util.*;

/**
 * All economy/RNG constants in one place. Values are deliberate tuning
 * decisions, not config — the gamemode is meant to be consistent.
 */
public final class Tuning {
	private Tuning() {
	}

	/**
	 * An enum-keyed table built POSITIONALLY from {@code values()}: the Nth
	 * value belongs to the Nth constant, and a null value means that constant
	 * is deliberately absent from the table (a Rusty chest rolls no deeds).
	 *
	 * <p>Spelling every key out cost about twenty characters apiece, and the
	 * Plugin Hub's token budget is the binding constraint on this plugin — see
	 * CLAUDE.md. The declaration order IS the mapping. That is safe here and
	 * only here: these enums are closed sets the gamemode defines itself, and
	 * reordering one is already a save-breaking change (the persisted state
	 * stores their NAMES, so a reorder is invisible to Gson but would silently
	 * re-point every table below). Do not use this for anything whose order is
	 * outside this repo's control.
	 *
	 * <p>Too FEW values throws ArrayIndexOutOfBounds at class-init, which is the
	 * right moment and needs no hand-written check to say so. Too many is caught
	 * by TuningTableTest, which is free — tests are not counted.
	 */
	@SafeVarargs
	private static <K extends Enum<K>, V> Map<K, V> table(K[] keys, V... values) {
		Map<K, V> map = new EnumMap<>(keys[0].getDeclaringClass());
		for (int i = 0; i < values.length; i++) {
			if (values[i] != null) {
				map.put(keys[i], values[i]);
			}
		}
		return map;
	}

	// --- Task rewards (GC) ---
	/**
	 * Kill income is deliberately a trickle next to {@link #COMPLETION_GC}:
	 * kill COUNT already scales 20 -> 100 across the ladder, so a steep per-kill
	 * ladder on top of that compounds into the runaway this replaced. Finishing
	 * the contract is what pays.
	 */
	public static final Map<TaskDifficulty, Integer> PER_KILL_GC =
		table(TaskDifficulty.values(), 4, 8, 16, 28);

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
	/**
	 * Was 5.0, then 2.5. A 5x ceiling on punching up was the other half of the
	 * runaway: against the retired low-level bonus it compounded to 12x on the
	 * base before the combo was even counted.
	 *
	 * <p>The drop from 2.5 is a literal no-op for every SOLO kill and always
	 * was — the clamp first bites at ratio 1.693, but a contract sizes its
	 * monster to at most INSANE's 1.35 fraction of the player's combat level,
	 * which tops out at 1.7169. The one live path to the clamp is a PARTY
	 * CARRY: the award reads the LOCAL player's combat level while a party
	 * contract sizes its monster to the party average, so a cb-30 member
	 * carried in a cb-90 party can fight ratio 4.0 and clamp. Left at 2.5 that
	 * path pays the weapon bonus on top of a 4x, on the account least able to
	 * have earned it. See KillDiffCapTest, which pins the no-op claim over
	 * every pair the generator can actually produce.
	 */
	public static final double KILL_DIFF_CAP = 1.75;

	/*
	 * There is deliberately no low-level compensation multiplier here any more.
	 * It paid up to +150% at combat 3 on the theory that low-level kills are
	 * slower — but the contract generator already caps monsters to a fraction
	 * of the player's combat level, so time-to-kill is roughly FLAT across the
	 * whole range (~90s at combat 12 against ~85s at combat 70: a low-level
	 * player kills a 25 HP monster slowly, a high-level one kills a 150 HP
	 * monster fast, and it comes out even). Paying 2.5x for a slowdown the
	 * content scaling already absorbs made the early game the richest part of
	 * the gamemode, which is backwards.
	 */

	/**
	 * Rhythm Combo: every five compliant on-task kills earn a stack, and each
	 * stack multiplies the contract's base per-kill GC by a further quarter —
	 * x1.25 at one stack up to x2.5 at six, so a maxed chain is 30 kills deep.
	 * How long a kill takes is deliberately not a condition; only going idle
	 * breaks the chain, so a slow tanky target still builds a full combo as
	 * long as the player keeps attacking.
	 *
	 * <p>Combat level does not enter into it. The bonus is a flat ladder so a
	 * player can read their own pip count and know exactly what the next kill
	 * pays, which a level-faded percentage could never tell them.
	 *
	 * <p>Every base in {@link #PER_KILL_GC} is a multiple of 4, so a quarter
	 * step always lands on a whole number of GC and the ladder needs no
	 * rounding of its own — EXCEPT when {@link #WEAPON_BONUS_MULT} is live,
	 * which is a deliberate, owner-chosen exception; see that constant.
	 */
	public static final int COMBO_IDLE_RESET_TICKS = 50;   // ~30 seconds
	public static final int COMBO_MAX_STACKS = 6;
	public static final int COMBO_KILLS_PER_STACK = 5;
	public static final double COMBO_STACK_STEP = 0.25;

	/** Kills a chain is worth holding on to; past this the ladder is capped. */
	public static final int COMBO_MAX_KILLS = COMBO_MAX_STACKS * COMBO_KILLS_PER_STACK;

	public static int comboStacks(int chainKills) {
		return Math.min(COMBO_MAX_STACKS, Math.max(0, chainKills) / COMBO_KILLS_PER_STACK);
	}

	public static double comboMultiplier(int stacks) {
		return 1.0 + COMBO_STACK_STEP * Math.max(0, Math.min(COMBO_MAX_STACKS, stacks));
	}

	/**
	 * The Preferred Weapon: the wheel names a weapon CATEGORY alongside the
	 * style, and a compliant kill landed with that category in hand pays this
	 * much more. Multiplicative on the whole per-kill award, deliberately —
	 * folded in additively it would be worth only +31% at the attainable
	 * ceiling, and a bonus the interface calls "1.5x" while the player measures
	 * +31% is a lie. The runaway argument that forced the combat-level bonus
	 * and the combo to ADD does not transfer: those two are the same kind of
	 * term scaling the same base for the same reason, while the weapon is a
	 * different axis — HOW you fight, not who you fight or how steadily — and
	 * it is self-limiting because the named category usually costs DPS.
	 *
	 * <p>Per-kill only, never on completion. The category is sampled at the
	 * killing blow (see StyleTracker's cached judgement), so the bonus is
	 * verifiable against the kill that earned it; a completion bonus would be
	 * decided by whatever happened to be equipped at the end and could be had
	 * by swapping in the preferred weapon for the final kill alone.
	 *
	 * <p>1.5 breaks the whole-GC ladder above, knowingly: it needs base x mult
	 * to stay a multiple of 4, and with the smallest base at 4 that admits only
	 * WHOLE multipliers — 1.25, 1.5 and 1.75 all go lumpy, and only 2.0 and 3.0
	 * do not. The owner chose 1.5 with that named. EASY at an even match
	 * therefore reads 6, 8, 9, 11, 12, 14, 15 up the combo ladder rather than
	 * stepping evenly, and WeaponBonusTest pins exactly that so it stays a
	 * decision rather than becoming a bug report. Any level gap at all already
	 * makes the total fractional, so this is visible only on an even match.
	 */
	public static final double WEAPON_BONUS_MULT = 1.5;

	public static double killCbMultiplier(int playerCb, int npcCb) {
		int diff = npcCb - playerCb;
		if (diff >= 0) {
			double over = (double) npcCb / Math.max(1, playerCb) - 1.0;
			double mult = 1.0 + KILL_DIFF_EQUAL_BONUS
				+ over * KILL_RATIO_LINEAR
				+ over * over * KILL_RATIO_QUAD;
			return Math.min(KILL_DIFF_CAP, mult);
		}
		if (diff >= -KILL_DIFF_GRACE)
			return 1.0;
		return Math.max(KILL_DIFF_FLOOR, 1.0 + (diff + KILL_DIFF_GRACE) * KILL_DIFF_UNDER_RATE);
	}

	public static final Map<TaskDifficulty, Integer> COMPLETION_GC =
		table(TaskDifficulty.values(), 400, 900, 1900, 3600);

	/**
	 * Milestone completions, in the shape Slayer points use: every Nth finished
	 * contract pays a multiple of its completion reward. HIGHEST matching tier
	 * wins — they do not stack, so the 100th contract pays x10 and not
	 * 1.5 + 2.5 + 5 + 10.
	 *
	 * <p>Descending so the scan takes the first match. Kept as a pair of arrays
	 * rather than a map because order IS the rule here.
	 *
	 * <p>Tuned for a ~30% lift on completion income averaged over 100
	 * contracts, not for the raw size of the peaks: across any 100 contracts
	 * 80 pay flat, 10 pay x1.5, 8 pay x2.5, and one each pay x5 and x10. The
	 * peaks are meant to be an event; the average is meant to stay modest, or
	 * the milestone ladder quietly becomes the economy.
	 */
	public static final int[] COMPLETION_MILESTONES = {250, 100, 50, 10, 5};
	public static final double[] COMPLETION_MILESTONE_MULT = {15.0, 10.0, 5.0, 2.5, 1.5};

	/**
	 * Reward multiple for the Nth completed contract, 1.0 when N is not a
	 * milestone. N is the count INCLUDING this completion, so the tenth
	 * contract a player ever finishes is the one that pays x2.5.
	 */
	public static double completionMilestoneMult(int taskNumber) {
		if (taskNumber <= 0)
			return 1.0;
		for (int i = 0; i < COMPLETION_MILESTONES.length; i++) {
			if (taskNumber % COMPLETION_MILESTONES[i] == 0)
				return COMPLETION_MILESTONE_MULT[i];
		}
		return 1.0;
	}

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
	public enum Chest {
		RUSTY, BATTERED, GILDED, ORNATE
	}

	public static final Map<Chest, Integer> CHEST_PRICE_GC =
		table(Chest.values(), 150, 500, 800, 1000);

	public static final Map<Chest, Integer> CHEST_CARDS =
		table(Chest.values(), 1, 1, 2, 3);

	/** Rarity odds per chest, percent, order C/U/R/E/L. Common absorbs renormalization. */
	public static final Map<Chest, double[]> CHEST_ODDS = table(Chest.values(),
		new double[]{100, 0, 0, 0, 0}, new double[]{62, 24, 9.5, 3.5, 1},
		new double[]{55, 26, 12, 5, 2}, new double[]{48, 26, 15, 7.5, 3.5});

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
	 *
	 * <p>Was 0.35, which drew usable gear only ~3x as often as gear the player
	 * could not yet touch. At 0.15 it is nearly 7x: with chests now costing a
	 * real fraction of income, a pull the account cannot equip reads as a wasted
	 * chest rather than as aspirational. The headroom band is still alive — that
	 * is the point of it — just clearly the minority outcome.
	 */
	public static final double HOUSE_LEAN_HEADROOM_WEIGHT = 0.15;

	public static int maxRankForLevel(int level) {
		int rank = 1;
		for (int i = 0; i < TIER_RANK_LEVELS.length; i++) {
			if (level >= TIER_RANK_LEVELS[i]) {
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
		int reqPrimary, int reqDefence, int headroom) {
		return primaryLevel + headroom >= reqPrimary && defenceLevel + headroom >= reqDefence;
	}

	// --- Pity ---
	public static final int PITY_SOFT_START = 12;
	public static final double PITY_BONUS_PER_OPEN = 2.0; // additive % points to Epic+ mass
	public static final int PITY_HARD_CAP = 30;

	// --- Jackpot ---
	public static final double JACKPOT_CHANCE = 1.0 / 100;

	// --- Deeds ---
	public static final Map<Chest, Double> DEED_CHANCE =
		table(Chest.values(), null, 1.0 / 25, 1.0 / 18, 1.0 / 12);

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

	public static int fragmentsFor(TaskDifficulty difficulty) {
		switch (difficulty) {
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
	public static final Map<Rarity, Integer> DUPLICATE_GC =
		table(Rarity.values(), 25, 60, 150, 400, 1000);

	// --- Weekly shop ---
	public static final Map<Rarity, Integer> SHOP_PRICE_GC =
		table(Rarity.values(), 800, 1500, 4000, 9000, 20000);

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
	public static CardWear cardWear(int killsServed) {
		if (killsServed >= WEAR_SHATTERED_KILLS)
			return CardWear.SHATTERED;
		if (killsServed >= WEAR_CRACKED_KILLS)
			return CardWear.CRACKED;
		if (killsServed >= WEAR_HAIRLINE_KILLS)
			return CardWear.HAIRLINE;
		return CardWear.NONE;
	}

	/**
	 * Fewest kills of service that earns a stage — the inverse of
	 * {@link #cardWear(int)}, and deliberately in the same file so the two can
	 * never drift apart in separate edits. Only the ::gachawear debug command
	 * uses it; nothing in normal play sets a service record, it is only ever
	 * counted up one kill at a time.
	 */
	public static int wearKills(CardWear wear) {
		switch (wear) {
			case SHATTERED:
				return WEAR_SHATTERED_KILLS;
			case CRACKED:
				return WEAR_CRACKED_KILLS;
			case HAIRLINE:
				return WEAR_HAIRLINE_KILLS;
			default:
				return 0;
		}
	}

	/**
	 * What share of a contract's pay is the PER-KILL half, as a fraction of the
	 * two halves together.
	 *
	 * <p>Here because two files were deriving it independently and agreeing only
	 * by luck. {@link #WEAPON_BONUS_MULT} multiplies kill GC and nothing else, so
	 * this single number is the whole of what the Preferred Weapon is worth — and
	 * both the Overview panel (which prints it against the contract in hand) and
	 * the reveal ceremony's weapon caption (which prints it for a difficulty's
	 * base rates) have to answer with it. Two correct derivations in two files is
	 * not a shared answer; it is two answers that happen to match until one of
	 * them is edited.
	 *
	 * <p>Callers keep their own final step, which is the honest division of
	 * labour: the panel wants the fraction twice (once as a percentage of the
	 * contract, once scaled by {@code WEAPON_BONUS_MULT - 1} into a break-even
	 * slack), while the caption wants only the second. What must not differ — and
	 * now cannot — is the ratio itself.
	 *
	 * <p>Doubles rather than the panel's three ints because the caption's kill
	 * count is a midpoint and lands on x.5 for an odd band. The negative clamp
	 * stays: a completion bonus below zero is not a thing the generator produces,
	 * but a hand-edited or migrated save can hold one, and treating it as zero is
	 * what the panel already did.
	 *
	 * @param killGc      base per-kill GC times the quota — never the combo, the
	 *                    level gap or the party pool, none of which is a term of
	 *                    the contract
	 * @param completionGc the completion bonus, likewise at its base rate
	 * @return the kill share in 0..1, and 0 for a contract that pays nothing at
	 *         all rather than a NaN from 0/0
	 */
	public static double killShare(double killGc, double completionGc) {
		double total = killGc + Math.max(0, completionGc);
		return total <= 0 ? 0 : killGc / total;
	}
}
