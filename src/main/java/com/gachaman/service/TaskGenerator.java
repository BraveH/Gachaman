package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.stream.*;
import net.runelite.api.*;

/**
 * Pure task-offer generation: 4 difficulty offers scaled to combat level
 * (never impossible) and an optional Redemption 5th offer while tainted.
 * Fully deterministic for a given RNG seed and pool — party rolls rely on
 * this to produce identical offers on every participant's client.
 */
public final class TaskGenerator {
	private TaskGenerator() {
	}

	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		boolean membersWorld, boolean tainted, GachaRng rng) {
		return generateOffers(pool, playerCb, 99, membersWorld, tainted, rng);
	}

	/** Quest gating disabled — see the {@code completedQuests} overload. */
	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld, boolean tainted, GachaRng rng) {
		return generateOffers(pool, playerCb, playerSlayerLevel, membersWorld, null, tainted, 0, false, rng);
	}

	/** Quest-gated roll with no max-hit estimate: BIG_HIT falls to its floor. */
	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld,
		Set<String> completedQuests, boolean tainted, GachaRng rng) {
		return generateOffers(pool, playerCb, playerSlayerLevel, membersWorld,
			completedQuests, tainted, 0, false, rng);
	}

	/**
	 * @param completedQuests {@link Quest} names the player has
	 *                        FINISHED. Null disables quest gating entirely — the
	 *                        pre-quest-gate behaviour, which a mixed-version party
	 *                        falls back to so every client still deals one board.
	 *                        An EMPTY set is not the same thing: it means a player
	 *                        who has finished nothing, and gates accordingly.
	 * @param maxHit          the player's calculated max hit in their locked
	 *                        style, sizing the BIG_HIT side bet. 0 = unknown.
	 * @param anyoneLockedToMelee true when the local player — or, in a party, ANY
	 *                        agreed member — is locked to melee, which removes
	 *                        every melee-unreachable monster from the pool.
	 */
	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld,
		Set<String> completedQuests, boolean tainted,
		int maxHit, boolean anyoneLockedToMelee, GachaRng rng) {
		// slayer-task-only monsters are unfulfillable contracts (a Gachaman
		// task is not a slayer task); slayer-level-gated ones need the level;
		// quest-locked ones cannot be reached or damaged at all
		pool = pool.stream()
			.filter(m -> !m.isSlayerTaskOnly())
			.filter(m -> m.getSlayerLevel() <= playerSlayerLevel)
			.filter(m -> questsSatisfied(m, completedQuests))
			// A monster melee cannot reach is an UNWINNABLE contract for a player
			// the wheel has locked to melee, so it is gated exactly like the slayer
			// level and the quest locks above rather than merely discouraged.
			//
			// In a party this is ANY agreed member, not the local player: a shared
			// contract pools everyone's kills, so one melee member who cannot touch
			// the target is a member who cannot help and cannot build a combo. It
			// costs the party some variety, which is the trade the owner chose.
			.filter(m -> !anyoneLockedToMelee || !m.isMeleeUnreachable())
			.collect(Collectors.toList());
		List<TaskOffer> offers = new ArrayList<>(5);
		// no monster may appear on more than one offer in the same roll
		Set<String> usedMonsters = new HashSet<>();
		for (TaskDifficulty difficulty : TaskDifficulty.values()) {
			TaskOffer offer = generate(pool, playerCb, membersWorld, difficulty, false, usedMonsters, maxHit, rng);
			usedMonsters.add(offer.getMonsterName());
			offers.add(offer);
		}
		if (tainted) {
			TaskOffer redemption = generate(pool, playerCb, membersWorld, TaskDifficulty.MEDIUM,
				true, usedMonsters, maxHit, rng);
			offers.add(redemption);
		}
		return offers;
	}

	/**
	 * True when every quest this monster is locked behind has been finished.
	 *
	 * Order-independent by construction — a Set membership test, never a list
	 * comparison — because in a party this runs against an INTERSECTION built
	 * from several members' answers, and two clients that walked the roster in
	 * different orders must still agree monster for monster.
	 */
	static boolean questsSatisfied(MonsterTable.Monster monster,
		Set<String> completedQuests) {
		List<String> required = monster.getQuests();
		if (required == null || required.isEmpty())
			return true;
		// null = gating off; empty = a player who has finished nothing
		return completedQuests == null || completedQuests.containsAll(required);
	}

	static TaskOffer generate(List<MonsterTable.Monster> pool, int playerCb, boolean membersWorld,
		TaskDifficulty difficulty, boolean redemption,
		Set<String> excludeMonsters, int maxHit, GachaRng rng) {
		List<MonsterTable.Monster> eligible = eligibleMonsters(pool, playerCb, membersWorld, difficulty);
		List<MonsterTable.Monster> distinct = eligible.stream()
			.filter(m -> !excludeMonsters.contains(m.getName()))
			.collect(Collectors.toList());
		// only repeat a monster when the band genuinely has no alternative
		MonsterTable.Monster monster = rng.pick(distinct.isEmpty() ? eligible : distinct);

		int kills = rng.between(difficulty.getMinKills(), difficulty.getMaxKills());
		if (redemption)
			kills = (int) Math.ceil(kills * Tuning.REDEMPTION_KILL_MULT);
		int perKill = redemption ? 0 : Tuning.PER_KILL_GC.get(difficulty);
		int completion = Tuning.COMPLETION_GC.get(difficulty);

		List<SideBet> sideBets = redemption ? List.of() : rollSideBets(maxHit, completion, rng);
		return new TaskOffer(difficulty, monster.getName(), monster.getCombatLevel(),
			kills, perKill, completion, sideBets, redemption, false);
	}

	/**
	 * Monsters at or below the difficulty's CB cap. Always non-empty: when the
	 * cap excludes everything, the lowest-level monsters are used instead.
	 */
	static List<MonsterTable.Monster> eligibleMonsters(List<MonsterTable.Monster> pool, int playerCb,
		boolean membersWorld, TaskDifficulty difficulty) {
		int cap = (int) Math.max(2, Math.floor(playerCb * difficulty.getCbCapFraction()));
		List<MonsterTable.Monster> available = pool.stream()
			.filter(m -> membersWorld || !m.isMembers())
			.collect(Collectors.toList());
		List<MonsterTable.Monster> eligible = available.stream()
			.filter(m -> m.getCombatLevel() <= cap)
			.collect(Collectors.toList());
		if (!eligible.isEmpty()) {
			// bias toward the top of the band so harder difficulties feel harder
			int floor = (int) Math.floor(cap * (difficulty == TaskDifficulty.EASY ? 0.0 : 0.35));
			List<MonsterTable.Monster> banded = eligible.stream()
				.filter(m -> m.getCombatLevel() >= floor)
				.collect(Collectors.toList());
			return banded.isEmpty() ? eligible : banded;
		}
		// degenerate low-level case: hand back the weakest few monsters
		return available.stream()
			.sorted(Comparator.comparingInt(MonsterTable.Monster::getCombatLevel))
			.limit(8)
			.collect(Collectors.toList());
	}

	/**
	 * "Land a hit of N+", sized to the max hit this player could actually land
	 * in the style they are locked into — see {@link MaxHitService}.
	 *
	 * <p>Combat level was the wrong basis and produced impossible bets: the old
	 * {@code max(5, 5 + cb/8)} floored every player under combat 24 at 5, while
	 * a bronze-armed account around combat 12 maxes about 3. The bet could not
	 * be won at all in the band it was most likely to be dealt in.
	 *
	 * <p>Asks for {@value #BIG_HIT_FRACTION_PCT}% of that ceiling rather than
	 * the ceiling itself: a max hit is the rarest single roll on the damage
	 * curve, so a bet demanding it exactly would be technically possible and
	 * practically dead. The estimate is already conservative (no prayer, no
	 * boosts, no void), so the two margins compound in the player's favour.
	 *
	 * <p>Zero means the ceiling could not be read — logged out, no style rolled
	 * yet, an unreadable equipment container — and yields the floor.
	 */
	static final int BIG_HIT_FRACTION_PCT = 70;

	static int bigHitThreshold(int maxHit) {
		if (maxHit <= 0) {
			return 2; // unknown — a guess, and deliberately a low one
		}
		// never above what the player can physically hit: a floor that can
		// outrun the real ceiling is the same bug in smaller print
		return Math.max(1, Math.min(40, maxHit * BIG_HIT_FRACTION_PCT / 100));
	}

	static List<SideBet> rollSideBets(int maxHit, int completionGc, GachaRng rng) {
		int count = rng.chance(0.5) ? 2 : 1;
		List<SideBet> bets = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SideBet.Kind kind = SideBet.Kind.values()[rng.nextInt(SideBet.Kind.values().length)];
			int threshold = 0;
			int window = 0;
			switch (kind) {
				case BIG_HIT:
					threshold = bigHitThreshold(maxHit);
					break;
				case SPEED_KILLS:
					threshold = 3;
					window = 100;
					break;
				case DAMAGELESS_KILL:
				case CLUTCH_KILL:
					break;
			}
			double frac = Tuning.SIDEBET_MIN_PAYOUT_FRAC
				+ rng.nextDouble() * (Tuning.SIDEBET_MAX_PAYOUT_FRAC - Tuning.SIDEBET_MIN_PAYOUT_FRAC);
			int payout = (int) Math.round(completionGc * frac);
			// side bets are always visible — sealed bets were cut by design review
			bets.add(new SideBet(kind, threshold, window, false, false, payout));
		}
		return bets;
	}

}
