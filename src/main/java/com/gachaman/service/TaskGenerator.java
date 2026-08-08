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

	public static List<TaskOffer> generateOffers(List<MonsterTable.Monster> pool, int playerCb,
		int playerSlayerLevel, boolean membersWorld, boolean tainted, GachaRng rng)
	{
		// slayer-task-only monsters are unfulfillable contracts (a Gachaman
		// task is not a slayer task); slayer-level-gated ones need the level
		pool = pool.stream()
			.filter(m -> !m.isSlayerTaskOnly())
			.filter(m -> m.getSlayerLevel() <= playerSlayerLevel)
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
}
