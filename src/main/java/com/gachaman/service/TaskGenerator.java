package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure task-offer generation: 4 difficulty offers scaled to combat level
 * (never impossible) and an optional Redemption 5th offer while tainted.
 * Fully deterministic for a given RNG seed and pool — party rolls rely on
 * this to produce identical offers on every participant's client.
 */
public final class TaskGenerator
{
	private TaskGenerator()
	{
	}

	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		boolean membersWorld, boolean tainted, GachaRng rng)
	{
		return generateOffers(pool, playerCb, 99, membersWorld, tainted, rng);
	}

	/** Quest gating disabled — see the {@code completedQuests} overload. */
	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld, boolean tainted, GachaRng rng)
	{
		return generateOffers(pool, playerCb, playerSlayerLevel, membersWorld, null, tainted, rng);
	}

	/**
	 * @param completedQuests {@link net.runelite.api.Quest} names the player has
	 *                        FINISHED. Null disables quest gating entirely — the
	 *                        pre-quest-gate behaviour, which a mixed-version party
	 *                        falls back to so every client still deals one board.
	 *                        An EMPTY set is not the same thing: it means a player
	 *                        who has finished nothing, and gates accordingly.
	 */
	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld,
		@javax.annotation.Nullable java.util.Set<String> completedQuests, boolean tainted, GachaRng rng)
	{
		// slayer-task-only monsters are unfulfillable contracts (a Gachaman
		// task is not a slayer task); slayer-level-gated ones need the level;
		// quest-locked ones cannot be reached or damaged at all
		pool = pool.stream()
			.filter(m -> !m.isSlayerTaskOnly())
			.filter(m -> m.getSlayerLevel() <= playerSlayerLevel)
			.filter(m -> questsSatisfied(m, completedQuests))
			.collect(Collectors.toList());
		List<TaskOffer> offers = new ArrayList<>(5);
		// no monster may appear on more than one offer in the same roll
		java.util.Set<String> usedMonsters = new java.util.HashSet<>();
		for (TaskDifficulty difficulty : TaskDifficulty.values())
		{
			TaskOffer offer = generate(pool, playerCb, membersWorld, difficulty, false, usedMonsters, rng);
			usedMonsters.add(offer.getMonsterName());
			offers.add(offer);
		}
		if (tainted)
		{
			TaskOffer redemption = generate(pool, playerCb, membersWorld, TaskDifficulty.MEDIUM,
				true, usedMonsters, rng);
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
		@javax.annotation.Nullable java.util.Set<String> completedQuests)
	{
		List<String> required = monster.getQuests();
		if (required == null || required.isEmpty())
		{
			return true;
		}
		// null = gating off; empty = a player who has finished nothing
		return completedQuests == null || completedQuests.containsAll(required);
	}

	static TaskOffer generate(List<MonsterTable.Monster> pool, int playerCb, boolean membersWorld,
		TaskDifficulty difficulty, boolean redemption,
		java.util.Set<String> excludeMonsters, GachaRng rng)
	{
		List<MonsterTable.Monster> eligible = eligibleMonsters(pool, playerCb, membersWorld, difficulty);
		List<MonsterTable.Monster> distinct = eligible.stream()
			.filter(m -> !excludeMonsters.contains(m.getName()))
			.collect(Collectors.toList());
		// only repeat a monster when the band genuinely has no alternative
		MonsterTable.Monster monster = rng.pick(distinct.isEmpty() ? eligible : distinct);

		int kills = rng.between(difficulty.getMinKills(), difficulty.getMaxKills());
		if (redemption)
		{
			kills = (int) Math.ceil(kills * Tuning.REDEMPTION_KILL_MULT);
		}
		int perKill = redemption ? 0 : Tuning.PER_KILL_GC.get(difficulty);
		int completion = Tuning.COMPLETION_GC.get(difficulty);

		List<SideBet> sideBets = redemption ? List.of() : rollSideBets(playerCb, completion, rng);
		return new TaskOffer(difficulty, monster.getName(), monster.getCombatLevel(),
			kills, perKill, completion, sideBets, redemption, false);
	}

	/**
	 * Monsters at or below the difficulty's CB cap. Always non-empty: when the
	 * cap excludes everything, the lowest-level monsters are used instead.
	 */
	static List<MonsterTable.Monster> eligibleMonsters(List<MonsterTable.Monster> pool, int playerCb,
		boolean membersWorld, TaskDifficulty difficulty)
	{
		int cap = (int) Math.max(2, Math.floor(playerCb * difficulty.getCbCapFraction()));
		List<MonsterTable.Monster> available = pool.stream()
			.filter(m -> membersWorld || !m.isMembers())
			.collect(Collectors.toList());
		List<MonsterTable.Monster> eligible = available.stream()
			.filter(m -> m.getCombatLevel() <= cap)
			.collect(Collectors.toList());
		if (!eligible.isEmpty())
		{
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

	static List<SideBet> rollSideBets(int playerCb, int completionGc, GachaRng rng)
	{
		int count = rng.chance(0.5) ? 2 : 1;
		List<SideBet> bets = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			SideBet.Kind kind = SideBet.Kind.values()[rng.nextInt(SideBet.Kind.values().length)];
			int threshold = 0;
			int window = 0;
			switch (kind)
			{
				case BIG_HIT:
					threshold = Math.max(5, Math.min(40, 5 + playerCb / 8));
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

	// --- The Charter Office ---------------------------------------------------
	// A chartered contract is a BOUGHT board offer, so it must be indistinguishable
	// from a rolled one once it lands: same difficulty bands, same gates, same
	// reward tables. Everything below is appended rather than woven into the roll
	// path on purpose — party rolls replay generateOffers() from a shared seed, and
	// Random.nextInt() burns a variable number of draws for non-power-of-two
	// bounds, so a single extra call anywhere above would desync every client.
	// The charter always runs on its OWN GachaRng instance.

	/**
	 * The combat-level ceiling a difficulty will offer at this combat level —
	 * the same expression eligibleMonsters() bands by, exposed so the Charter
	 * Office can price and gate a target without rolling a board.
	 */
	public static int cbCap(int playerCb, TaskDifficulty difficulty)
	{
		return (int) Math.max(2, Math.floor(playerCb * difficulty.getCbCapFraction()));
	}

	/**
	 * The difficulty a chartered target lands at: the FIRST band whose ceiling
	 * covers it, so a target is always sold at the cheapest honest difficulty it
	 * qualifies for. Null means the target is above even INSANE's ceiling — the
	 * board would never offer it, so the Charter Office must not sell it either.
	 *
	 * Relies on cbCapFraction ascending across TaskDifficulty.values(); the test
	 * suite pins that ordering so a future band insert cannot quietly invert it.
	 */
	@javax.annotation.Nullable
	public static TaskDifficulty charterDifficulty(int playerCb, int npcCb)
	{
		for (TaskDifficulty difficulty : TaskDifficulty.values())
		{
			if (npcCb <= cbCap(playerCb, difficulty))
			{
				return difficulty;
			}
		}
		return null;
	}

	/** Quest gating disabled — see the {@code completedQuests} overload. */
	public static boolean charterEligible(MonsterTable.Monster monster, int playerCb,
		int playerSlayerLevel, boolean membersWorld)
	{
		return charterEligible(monster, playerCb, playerSlayerLevel, membersWorld, null);
	}

	/**
	 * Exactly the gates the board applies, and no others: slayer-task-only
	 * monsters are unfulfillable, slayer-level-gated ones need the level, members
	 * monsters need a members world, quest-locked ones need the quests, and
	 * nothing above INSANE's ceiling is offerable. Paying GC must never buy past
	 * a rule the roll enforces — and a deed is bought with a purse the player
	 * does not get back if the target turns out to be unreachable.
	 */
	public static boolean charterEligible(MonsterTable.Monster monster, int playerCb,
		int playerSlayerLevel, boolean membersWorld,
		@javax.annotation.Nullable java.util.Set<String> completedQuests)
	{
		return monster != null
			&& !monster.isSlayerTaskOnly()
			&& monster.getSlayerLevel() <= playerSlayerLevel
			&& (membersWorld || !monster.isMembers())
			&& questsSatisfied(monster, completedQuests)
			&& charterDifficulty(playerCb, monster.getCombatLevel()) != null;
	}

	/**
	 * Build the chartered contract. Draw order matches generate()'s (kills, then
	 * side bets) so a chartered offer is priced off the same tables a rolled one
	 * would be. Never a redemption, never a party roll: a deed is bought by one
	 * player, out of one purse, and binds only them.
	 */
	@javax.annotation.Nullable
	public static TaskOffer charterOffer(MonsterTable.Monster monster, int playerCb, GachaRng rng)
	{
		TaskDifficulty difficulty = monster == null
			? null : charterDifficulty(playerCb, monster.getCombatLevel());
		if (difficulty == null)
		{
			return null;
		}
		int kills = rng.between(difficulty.getMinKills(), difficulty.getMaxKills());
		int completion = Tuning.COMPLETION_GC.get(difficulty);
		List<SideBet> sideBets = rollSideBets(playerCb, completion, rng);
		return new TaskOffer(difficulty, monster.getName(), monster.getCombatLevel(), kills,
			Tuning.PER_KILL_GC.get(difficulty), completion, sideBets, false, false);
	}
}
