package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.CharterHold;
import com.gachaman.model.MonsterStats;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The Charter Office's rules, every one of them a pure static. Nothing here
 * touches Client, ConfigManager or the party layer — the service instance only
 * sequences these decisions and writes them in one mutate.
 */
public class CharterOfficeTest
{
	private static List<MonsterTable.Monster> monsters;

	@BeforeClass
	public static void load() throws Exception
	{
		try (InputStreamReader reader = new InputStreamReader(
			CharterOfficeTest.class.getResourceAsStream("/com/gachaman/data/monsters.json"),
			StandardCharsets.UTF_8))
		{
			MonstersShape shape = new Gson().fromJson(reader, MonstersShape.class);
			monsters = shape.monsters;
		}
		Assert.assertTrue("dataset should have 200+ monsters", monsters.size() >= 200);
	}

	private static class MonstersShape
	{
		List<MonsterTable.Monster> monsters;
	}

	private static MonsterTable.Monster monster(String name, int cb)
	{
		return new MonsterTable.Monster(name, cb, List.of("test"), false, 0, false, false,
			List.of());
	}

	private static MonsterTable.Monster monster(String name, int cb, boolean members,
		int slayerLevel, boolean slayerTaskOnly)
	{
		return new MonsterTable.Monster(name, cb, List.of("test"), members, slayerLevel,
			slayerTaskOnly, false, List.of());
	}

	private static MonsterTable.Monster questMonster(String name, int cb, String... quests)
	{
		return new MonsterTable.Monster(name, cb, List.of("test"), false, 0, false, false,
			List.of(quests));
	}

	private static Map<String, MonsterStats> journal(Object... nameThenKills)
	{
		Map<String, MonsterStats> stats = new HashMap<>();
		for (int i = 0; i < nameThenKills.length; i += 2)
		{
			stats.put((String) nameThenKills[i],
				new MonsterStats(((Number) nameThenKills[i + 1]).longValue(), 0, 0));
		}
		return stats;
	}

	private static TaskOffer offerFor(String name)
	{
		return new TaskOffer(TaskDifficulty.EASY, name, 10, 20, 8, 100, List.of(), false, false);
	}

	// --- Pricing --------------------------------------------------------------

	@Test
	public void priceStaysInsideTheAdvertisedBand()
	{
		for (int playerCb = 3; playerCb <= 126; playerCb++)
		{
			for (int npcCb = 0; npcCb <= 900; npcCb += 7)
			{
				int price = Tuning.charterPriceGc(playerCb, npcCb);
				Assert.assertTrue("under floor at " + playerCb + "/" + npcCb,
					price >= Tuning.CHARTER_PRICE_MIN_GC);
				Assert.assertTrue("over ceiling at " + playerCb + "/" + npcCb,
					price <= Tuning.CHARTER_PRICE_MAX_GC);
			}
		}
	}

	@Test
	public void priceIsQuotedInRoundNumbers()
	{
		for (int npcCb = 1; npcCb <= 200; npcCb++)
		{
			Assert.assertEquals(0, Tuning.charterPriceGc(100, npcCb) % Tuning.CHARTER_PRICE_STEP_GC);
		}
	}

	@Test
	public void priceNeverFallsAsTheTargetGetsHarder()
	{
		int previous = 0;
		for (int npcCb = 0; npcCb <= 300; npcCb++)
		{
			int price = Tuning.charterPriceGc(100, npcCb);
			Assert.assertTrue("price dipped at npcCb " + npcCb, price >= previous);
			previous = price;
		}
	}

	@Test
	public void priceScalesByRatioNotAbsoluteLevel()
	{
		// the same relative target costs the same to a low and a high level:
		// that is what stops a level-3 chicken being priced as a boss
		Assert.assertEquals(Tuning.charterPriceGc(40, 40), Tuning.charterPriceGc(120, 120));
		Assert.assertEquals(Tuning.charterPriceGc(50, 25), Tuning.charterPriceGc(100, 50));
	}

	@Test
	public void trivialTargetsCostTheFloorAndTopBandCostsTheCeiling()
	{
		Assert.assertEquals(Tuning.CHARTER_PRICE_MIN_GC, Tuning.charterPriceGc(100, 1));
		Assert.assertEquals(Tuning.CHARTER_PRICE_MIN_GC, Tuning.charterPriceGc(100, 45));
		Assert.assertEquals(Tuning.CHARTER_PRICE_MAX_GC, Tuning.charterPriceGc(100, 135));
		Assert.assertEquals(Tuning.CHARTER_PRICE_MAX_GC, Tuning.charterPriceGc(100, 5000));
	}

	@Test
	public void priceSurvivesDegenerateInputs()
	{
		Assert.assertEquals(Tuning.CHARTER_PRICE_MIN_GC, Tuning.charterPriceGc(0, 0));
		Assert.assertEquals(Tuning.CHARTER_PRICE_MIN_GC, Tuning.charterPriceGc(-5, -5));
		Assert.assertEquals(Tuning.CHARTER_PRICE_MAX_GC, Tuning.charterPriceGc(1, 400));
	}

	// --- Difficulty bands -----------------------------------------------------

	@Test
	public void difficultyPicksTheCheapestBandThatCoversTheTarget()
	{
		// cb 100 -> caps 45 / 75 / 105 / 135
		Assert.assertEquals(TaskDifficulty.EASY, TaskGenerator.charterDifficulty(100, 45));
		Assert.assertEquals(TaskDifficulty.MEDIUM, TaskGenerator.charterDifficulty(100, 46));
		Assert.assertEquals(TaskDifficulty.MEDIUM, TaskGenerator.charterDifficulty(100, 75));
		Assert.assertEquals(TaskDifficulty.HARD, TaskGenerator.charterDifficulty(100, 76));
		Assert.assertEquals(TaskDifficulty.INSANE, TaskGenerator.charterDifficulty(100, 135));
		Assert.assertNull(TaskGenerator.charterDifficulty(100, 136));
	}

	@Test
	public void difficultyBandsAscendSoTheFirstMatchIsTheCheapest()
	{
		// charterDifficulty returns the FIRST covering band; that is only the
		// cheapest one while the cap fractions ascend, so pin the ordering
		double previous = -1;
		for (TaskDifficulty difficulty : TaskDifficulty.values())
		{
			Assert.assertTrue("cbCapFraction must ascend across TaskDifficulty.values()",
				difficulty.getCbCapFraction() > previous);
			previous = difficulty.getCbCapFraction();
		}
	}

	@Test
	public void cbCapMatchesTheCeilingTheBoardActuallyOffersAt()
	{
		// the charter must not quote off a formula that has drifted from the roll
		for (int playerCb = 20; playerCb <= 126; playerCb += 3)
		{
			for (TaskDifficulty difficulty : TaskDifficulty.values())
			{
				int cap = TaskGenerator.cbCap(playerCb, difficulty);
				for (MonsterTable.Monster picked
					: TaskGenerator.eligibleMonsters(monsters, playerCb, true, difficulty))
				{
					Assert.assertTrue("eligibleMonsters admitted lvl " + picked.getCombatLevel()
							+ " above cap " + cap, picked.getCombatLevel() <= cap);
				}
			}
		}
	}

	@Test
	public void everyRealMonsterIsCoveredByABandAtSomeCombatLevel()
	{
		for (MonsterTable.Monster monster : monsters)
		{
			Assert.assertNotNull("no band ever covers " + monster.getName(),
				TaskGenerator.charterDifficulty(126, Math.min(monster.getCombatLevel(), 170)));
		}
	}

	// --- Eligibility gates ----------------------------------------------------

	@Test
	public void charterCannotBuyPastTheGatesTheBoardEnforces()
	{
		MonsterTable.Monster taskOnly = monster("Taskonly Boss", 90, true, 0, true);
		MonsterTable.Monster gated = monster("Highslayer Fiend", 90, true, 85, false);
		MonsterTable.Monster memberOnly = monster("P2P Beast", 90, true, 0, false);
		MonsterTable.Monster overCap = monster("Titan", 400, false, 0, false);

		Assert.assertFalse("slayer-task-only is unfulfillable",
			TaskGenerator.charterEligible(taskOnly, 126, 99, true));
		Assert.assertFalse("slayer level not met",
			TaskGenerator.charterEligible(gated, 126, 1, true));
		Assert.assertTrue(TaskGenerator.charterEligible(gated, 126, 85, true));
		Assert.assertFalse("members monster on a free world",
			TaskGenerator.charterEligible(memberOnly, 126, 99, false));
		Assert.assertTrue(TaskGenerator.charterEligible(memberOnly, 126, 99, true));
		Assert.assertFalse("above INSANE's ceiling",
			TaskGenerator.charterEligible(overCap, 126, 99, true));
		Assert.assertFalse(TaskGenerator.charterEligible(null, 126, 99, true));
	}

	@Test
	public void goldCanNeverBuyAContractTheBoardWouldNotOffer()
	{
		// the load-bearing invariant of the whole feature: a chartered target must
		// land in a band that would genuinely have rolled it, floor and cap
		int checked = 0;
		for (int playerCb = 20; playerCb <= 126; playerCb += 5)
		{
			for (boolean membersWorld : new boolean[]{true, false})
			{
				Map<TaskDifficulty, Set<String>> rollable = new HashMap<>();
				for (TaskDifficulty difficulty : TaskDifficulty.values())
				{
					Set<String> names = new HashSet<>();
					for (MonsterTable.Monster m
						: TaskGenerator.eligibleMonsters(monsters, playerCb, membersWorld, difficulty))
					{
						names.add(m.getName());
					}
					rollable.put(difficulty, names);
				}
				for (MonsterTable.Monster monster : monsters)
				{
					if (!TaskGenerator.charterEligible(monster, playerCb, 99, membersWorld))
					{
						continue;
					}
					TaskDifficulty band =
						TaskGenerator.charterDifficulty(playerCb, monster.getCombatLevel());
					Assert.assertTrue("cb " + playerCb + (membersWorld ? " p2p" : " f2p")
							+ ": " + monster.getName() + " is chartered at " + band
							+ " but the board would never roll it there",
						rollable.get(band).contains(monster.getName()));
					checked++;
				}
			}
		}
		// a gate that stopped admitting anything would pass vacuously forever
		Assert.assertTrue("invariant examined only " + checked + " targets", checked > 2000);
	}

	@Test
	public void targetsNeedTheKillThreshold()
	{
		List<MonsterTable.Monster> pool = List.of(monster("Familiar", 40), monster("Stranger", 40));
		Map<String, MonsterStats> stats = journal(
			"Familiar", Tuning.CHARTER_KILLS_REQUIRED,
			"Stranger", Tuning.CHARTER_KILLS_REQUIRED - 1);
		List<CharterService.Target> targets =
			CharterService.targets(stats, pool, 100, 99, true, Collections.emptySet());
		Assert.assertEquals(1, targets.size());
		Assert.assertEquals("Familiar", targets.get(0).getMonsterName());
		Assert.assertEquals(Tuning.CHARTER_KILLS_REQUIRED, targets.get(0).getKills());
	}

	@Test
	public void killsFoldCaseAndSumAcrossJournalSpellings()
	{
		// the journal is keyed by the raw NPC name as the kill arrived; a player
		// who really did the work must not be told they did not
		Map<String, MonsterStats> stats = journal("Giant rat", 13, "GIANT RAT", 12);
		Map<String, Long> folded = CharterService.killsByName(stats);
		Assert.assertEquals(25L, (long) folded.get("giant rat"));

		List<CharterService.Target> targets = CharterService.targets(stats,
			List.of(monster("Giant Rat", 3)), 100, 99, true, Collections.emptySet());
		Assert.assertEquals(1, targets.size());
		Assert.assertEquals("Giant Rat", targets.get(0).getMonsterName());
	}

	@Test
	public void targetsExcludeMonstersAlreadyOnTheBoard()
	{
		List<MonsterTable.Monster> pool = List.of(monster("Rolled", 40), monster("Free", 40));
		Map<String, MonsterStats> stats = journal("Rolled", 100, "Free", 100);
		Set<String> onBoard = new HashSet<>();
		onBoard.add("rolled"); // folded case must still exclude it
		List<CharterService.Target> targets =
			CharterService.targets(stats, pool, 100, 99, true, onBoard);
		Assert.assertEquals(1, targets.size());
		Assert.assertEquals("Free", targets.get(0).getMonsterName());
	}

	/**
	 * The journal is the only proof of familiarity the office reads, and it can
	 * hold kills from before a quest was ever a gate (an imported account, a
	 * dataset that gained the gate later, a monster whose free spawn is not the
	 * gated one). A deed is still a contract, so the gate is re-checked at the
	 * counter rather than trusted to the kill count.
	 */
	@Test
	public void aQuestLockedMonsterIsNotForSaleUntilTheQuestIsDone()
	{
		List<MonsterTable.Monster> pool = List.of(
			monster("Cow", 40),
			questMonster("Gargoyle", 111, "PRIEST_IN_PERIL"));
		Map<String, MonsterStats> stats = journal("Cow", 100, "Gargoyle", 100);
		Assert.assertEquals(List.of("Cow"),
			names(CharterService.targets(stats, pool, 126, 99, true, Set.of(), Set.of())));
		Assert.assertEquals(List.of("Gargoyle", "Cow"), names(CharterService.targets(stats, pool,
			126, 99, true, Set.of("PRIEST_IN_PERIL"), Set.of())));
		// the six-arg overload predates the gate and must stay pre-gate, or the
		// party layer's mixed-version fallback would mean two different things
		Assert.assertEquals(List.of("Gargoyle", "Cow"),
			names(CharterService.targets(stats, pool, 126, 99, true, Set.of())));
	}

	private static List<String> names(List<CharterService.Target> targets)
	{
		List<String> names = new java.util.ArrayList<>(targets.size());
		for (CharterService.Target target : targets)
		{
			names.add(target.getMonsterName());
		}
		return names;
	}

	@Test
	public void targetsSurviveNullJournalAndNullPool()
	{
		Assert.assertTrue(CharterService.targets(null, monsters, 100, 99, true, null).isEmpty());
		Assert.assertTrue(CharterService.targets(journal("X", 100), null, 100, 99, true, null).isEmpty());
		Assert.assertTrue(CharterService.killsByName(null).isEmpty());
	}

	@Test
	public void targetsAreSortedDearestFirstAndPricedConsistently()
	{
		List<MonsterTable.Monster> pool = new ArrayList<>();
		Map<String, MonsterStats> stats = new HashMap<>();
		for (int cb = 10; cb <= 120; cb += 10)
		{
			pool.add(monster("Mob" + cb, cb));
			stats.put("Mob" + cb, new MonsterStats(50, 0, 0));
		}
		List<CharterService.Target> targets =
			CharterService.targets(stats, pool, 100, 99, true, Collections.emptySet());
		Assert.assertFalse(targets.isEmpty());
		int previous = Integer.MAX_VALUE;
		for (CharterService.Target target : targets)
		{
			Assert.assertTrue("not sorted dearest first", target.getPriceGc() <= previous);
			previous = target.getPriceGc();
			Assert.assertEquals(Tuning.charterPriceGc(100, target.getCombatLevel()),
				target.getPriceGc());
			Assert.assertEquals(TaskGenerator.charterDifficulty(100, target.getCombatLevel()),
				target.getDifficulty());
		}
	}

	@Test
	public void everyOfferedTargetIsAlsoBuildable()
	{
		Map<String, MonsterStats> stats = new HashMap<>();
		for (MonsterTable.Monster monster : monsters)
		{
			stats.put(monster.getName(), new MonsterStats(50, 0, 0));
		}
		for (int playerCb = 20; playerCb <= 126; playerCb += 11)
		{
			for (CharterService.Target target
				: CharterService.targets(stats, monsters, playerCb, 99, true, Collections.emptySet()))
			{
				MonsterTable.Monster monster = monsters.stream()
					.filter(m -> m.getName().equals(target.getMonsterName()))
					.findFirst().orElseThrow(AssertionError::new);
				Assert.assertNotNull("offered but unbuildable: " + target.getMonsterName(),
					TaskGenerator.charterOffer(monster, playerCb, new GachaRng(1L)));
			}
		}
	}

	// --- The chartered offer --------------------------------------------------

	@Test
	public void charteredOfferMatchesTheBoardsRewardTables()
	{
		MonsterTable.Monster target = monster("Chartered", 80);
		TaskOffer offer = TaskGenerator.charterOffer(target, 100, new GachaRng(5L));
		Assert.assertNotNull(offer);
		Assert.assertEquals(TaskDifficulty.HARD, offer.getDifficulty());
		Assert.assertEquals("Chartered", offer.getMonsterName());
		Assert.assertEquals(80, offer.getMonsterCombatLevel());
		Assert.assertEquals((int) Tuning.PER_KILL_GC.get(TaskDifficulty.HARD), offer.getPerKillGc());
		Assert.assertEquals((int) Tuning.COMPLETION_GC.get(TaskDifficulty.HARD),
			offer.getCompletionGc());
		Assert.assertTrue(offer.getKillsRequired() >= TaskDifficulty.HARD.getMinKills());
		Assert.assertTrue(offer.getKillsRequired() <= TaskDifficulty.HARD.getMaxKills());
		Assert.assertFalse("a deed is never a redemption", offer.isRedemption());
		Assert.assertFalse("a deed binds one purse, never a party", offer.isPartyRoll());
		Assert.assertFalse("side bets ride along like any contract", offer.getSideBets().isEmpty());
	}

	@Test
	public void charteredOfferRefusesTargetsAboveEveryBand()
	{
		Assert.assertNull(TaskGenerator.charterOffer(monster("Titan", 400), 100, new GachaRng(1L)));
		Assert.assertNull(TaskGenerator.charterOffer(null, 100, new GachaRng(1L)));
	}

	@Test
	public void theQuoteShownIsTheContractReceived()
	{
		// same profile, same day, same target -> byte-identical contract, so
		// re-opening the panel can never re-roll a friendlier kill count
		long seed = CharterService.charterSeed("profileA", "2026-D220", "Chartered");
		TaskOffer first = TaskGenerator.charterOffer(monster("Chartered", 80), 100, new GachaRng(seed));
		TaskOffer second = TaskGenerator.charterOffer(monster("Chartered", 80), 100, new GachaRng(seed));
		Assert.assertEquals(first.getKillsRequired(), second.getKillsRequired());
		Assert.assertEquals(first.getSideBets().size(), second.getSideBets().size());
		for (int i = 0; i < first.getSideBets().size(); i++)
		{
			Assert.assertEquals(first.getSideBets().get(i).getPayoutGc(),
				second.getSideBets().get(i).getPayoutGc());
		}
	}

	@Test
	public void seedsSeparateProfilesDaysAndTargets()
	{
		long base = CharterService.charterSeed("profileA", "2026-D220", "Goblin");
		Assert.assertNotEquals(base, CharterService.charterSeed("profileB", "2026-D220", "Goblin"));
		Assert.assertNotEquals(base, CharterService.charterSeed("profileA", "2026-D221", "Goblin"));
		Assert.assertNotEquals(base, CharterService.charterSeed("profileA", "2026-D220", "Cow"));
		// a null profile key (pre-login) must not explode
		CharterService.charterSeed(null, "2026-D220", "Goblin");
	}

	// --- The daily lock -------------------------------------------------------

	@Test
	public void dayKeyIsDistinctForEveryDayOfEveryYear()
	{
		Set<String> seen = new HashSet<>();
		LocalDate date = LocalDate.of(2024, 1, 1); // leap year, so day 366 exists
		for (int i = 0; i < 366 * 3; i++)
		{
			Assert.assertTrue("collision on " + date, seen.add(CharterService.dayKey(date)));
			date = date.plusDays(1);
		}
		Assert.assertEquals("2024-D1", CharterService.dayKey(LocalDate.of(2024, 1, 1)));
		Assert.assertEquals("2024-D366", CharterService.dayKey(LocalDate.of(2024, 12, 31)));
	}

	@Test
	public void dayKeyIsStableAcrossTheSameDay()
	{
		Assert.assertEquals(CharterService.dayKey(LocalDate.of(2026, 8, 8)),
			CharterService.dayKey(LocalDate.of(2026, 8, 8)));
	}

	@Test
	public void theDailyLockOpensOnTheNextDayAndIsOpenForANewProfile()
	{
		String today = CharterService.dayKey(LocalDate.of(2026, 8, 8));
		String tomorrow = CharterService.dayKey(LocalDate.of(2026, 8, 9));
		Assert.assertTrue(CharterService.usedOn(today, today));
		Assert.assertFalse(CharterService.usedOn(today, tomorrow));
		// a save written before the field existed deserializes null: not used
		Assert.assertFalse(CharterService.usedOn(null, today));
	}

	// --- Escrow resolution ----------------------------------------------------

	private static CharterHold hold(long expiresAtMs)
	{
		return new CharterHold("Chartered", 1500, expiresAtMs);
	}

	@Test
	public void anUnheldEscrowResolvesToNothing()
	{
		Assert.assertEquals(CharterService.Resolution.NONE,
			CharterService.resolve(null, null, null, 1_000L));
	}

	@Test
	public void aDeedOnTheBoardAndInDateJustWaits()
	{
		Assert.assertEquals(CharterService.Resolution.WAITING, CharterService.resolve(
			hold(10_000L), null, List.of(offerFor("Rolled"), offerFor("Chartered")), 9_999L));
	}

	@Test
	public void signingTheCharteredContractSpendsTheEscrow()
	{
		ActiveTask signed = ActiveTask.builder().monsterName("Chartered").build();
		Assert.assertEquals(CharterService.Resolution.REDEEMED,
			CharterService.resolve(hold(10_000L), signed, new ArrayList<>(), 1L));
		// even past the deadline: the contract is binding, the money was spent
		Assert.assertEquals(CharterService.Resolution.REDEEMED,
			CharterService.resolve(hold(10_000L), signed, new ArrayList<>(), 999_999L));
	}

	@Test
	public void signingSomethingElseRefundsTheDeed()
	{
		ActiveTask other = ActiveTask.builder().monsterName("Rolled").build();
		Assert.assertEquals(CharterService.Resolution.ORPHANED,
			CharterService.resolve(hold(10_000L), other, new ArrayList<>(), 1L));
	}

	@Test
	public void losingTheBoardRefundsTheDeed()
	{
		// ::gachacleartask wipes the board without knowing about the escrow
		Assert.assertEquals(CharterService.Resolution.ORPHANED,
			CharterService.resolve(hold(10_000L), null, new ArrayList<>(), 1L));
		Assert.assertEquals(CharterService.Resolution.ORPHANED,
			CharterService.resolve(hold(10_000L), null, null, 1L));
		Assert.assertEquals(CharterService.Resolution.ORPHANED,
			CharterService.resolve(hold(10_000L), null, List.of(offerFor("Rolled")), 1L));
	}

	@Test
	public void sittingPastTheDeadlineRefundsTheDeed()
	{
		List<TaskOffer> board = List.of(offerFor("Rolled"), offerFor("Chartered"));
		Assert.assertEquals(CharterService.Resolution.EXPIRED,
			CharterService.resolve(hold(10_000L), null, board, 10_000L));
		Assert.assertEquals(CharterService.Resolution.EXPIRED,
			CharterService.resolve(hold(10_000L), null, board, 10_001L));
	}

	@Test
	public void resolutionToleratesNullOffersOnTheBoard()
	{
		List<TaskOffer> board = new ArrayList<>();
		board.add(null);
		board.add(offerFor("Chartered"));
		Assert.assertEquals(CharterService.Resolution.WAITING,
			CharterService.resolve(hold(10_000L), null, board, 1L));
	}

	@Test
	public void everyResolutionIsReachedFromAHeldDeed()
	{
		// a hold that no branch resolves would sit forever with the GC gone
		Set<CharterService.Resolution> reached = new HashSet<>();
		reached.add(CharterService.resolve(hold(10L), null, List.of(offerFor("Chartered")), 1L));
		reached.add(CharterService.resolve(hold(10L), null, List.of(offerFor("Chartered")), 99L));
		reached.add(CharterService.resolve(hold(10L), null, new ArrayList<>(), 1L));
		reached.add(CharterService.resolve(hold(10L),
			ActiveTask.builder().monsterName("Chartered").build(), null, 1L));
		Assert.assertEquals(4, reached.size());
	}

	// --- Board surgery --------------------------------------------------------

	@Test
	public void expiryTakesOnlyTheDeedOffTheBoardAndKeepsTheOrder()
	{
		List<TaskOffer> board = List.of(offerFor("A"), offerFor("B"), offerFor("C"),
			offerFor("D"), offerFor("Chartered"));
		List<TaskOffer> stripped = CharterService.stripCharter(board, "Chartered");
		Assert.assertEquals(4, stripped.size());
		Assert.assertEquals("A", stripped.get(0).getMonsterName());
		Assert.assertEquals("D", stripped.get(3).getMonsterName());
	}

	@Test
	public void strippingIsCaseInsensitiveAndNullSafe()
	{
		Assert.assertTrue(CharterService.stripCharter(null, "Chartered").isEmpty());
		Assert.assertTrue(CharterService.stripCharter(List.of(offerFor("CHARTERED")), "Chartered")
			.isEmpty());
	}

	// --- Countdown ------------------------------------------------------------

	@Test
	public void theCountdownStartsAtTheFullHoldAndFloorsAtZero()
	{
		long now = 1_000_000L;
		CharterHold fresh = new CharterHold("Chartered", 1500, now + Tuning.CHARTER_HOLD_MS);
		Assert.assertEquals(Tuning.CHARTER_HOLD_TICKS, CharterService.ticksRemaining(fresh, now));
		Assert.assertEquals(0, CharterService.ticksRemaining(fresh, now + Tuning.CHARTER_HOLD_MS));
		Assert.assertEquals(0, CharterService.ticksRemaining(fresh, now + Tuning.CHARTER_HOLD_MS + 1));
		Assert.assertEquals(0, CharterService.ticksRemaining(null, now));
		Assert.assertEquals(Tuning.CHARTER_HOLD_TICKS / 2,
			CharterService.ticksRemaining(fresh, now + Tuning.CHARTER_HOLD_MS / 2));
	}

	@Test
	public void theHoldIsExactlyTheAdvertisedNumberOfTicks()
	{
		Assert.assertEquals(500, Tuning.CHARTER_HOLD_TICKS);
		Assert.assertEquals(Tuning.CHARTER_HOLD_TICKS * 600L, Tuning.CHARTER_HOLD_MS);
	}
}
