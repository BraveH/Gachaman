package com.gachaman.service;

import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.gachaman.persist.StateStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The party style clash bonus: PARTY_REWARD_MULT plus PARTY_STYLE_CLASH_BONUS per
 * DISTINCT style beyond the first, priced from the accept-time snapshot.
 *
 * Headless, real in-memory state; the null client is never touched because
 * every contract here uses perKillGc 0 (the award branch is the only client
 * read in onKill). The exact GC figures assume the harness registers NO
 * CreditSink modifiers and carries no taint — both asserted in setUp.
 */
public class TaskServicePartyStyleTest
{
	private GachaStateService stateService;
	private CreditSink creditSink;
	private TaskService taskService;
	private final List<TaskService.TaskCompletionSummary> completions = new ArrayList<>();

	private static GachaStateService inMemoryStateService()
	{
		StateStore store = new StateStore(null, null, null)
		{
			@Override
			public void save(GachaState state)
			{
			}

			@Override
			public void save(GachaState state, boolean flushDiskNow)
			{
			}

			@Override
			public GachaState load()
			{
				return null; // forces a fresh in-memory state
			}
		};
		GachaStateService service = new GachaStateService(store);
		service.load(3);
		return service;
	}

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		creditSink = new CreditSink(stateService);
		ComplianceService complianceService = new ComplianceService(stateService, creditSink, null, null);
		CeremonyBus ceremonyBus = new CeremonyBus();
		StyleService styleService = new StyleService(stateService, complianceService, ceremonyBus,
			new GachaRng(1L));
		com.gachaman.data.MonsterTable monsterTable =
			com.gachaman.data.MonsterTable.load(new com.google.gson.Gson());
		taskService = new TaskService(null, stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L), monsterTable,
			// null Client already means these tests never reach rollOffers()
			null);
		taskService.addListener(new TaskService.Listener()
		{
			@Override
			public void onKillFeedback(TaskService.KillFeedback feedback)
			{
			}

			@Override
			public void onSideBetHit(SideBet bet, String monsterName)
			{
			}

			@Override
			public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
			{
				completions.add(summary);
			}

			@Override
			public void onOffersRolled(List<TaskOffer> offers)
			{
			}

			@Override
			public void onPartyProgress(ActiveTask task)
			{
			}
		});
		// the exact payout figures below only hold with an unmodified sink
		Assert.assertEquals("harness must carry no taint", 0, stateService.get().getTaint());
	}

	private KillTracker.Kill goblinKill(int tick)
	{
		return new KillTracker.Kill("Goblin", 2, 1, tick, tick - 3, false, 3, false, null);
	}

	/** One kill away from done, so a single onKill reaches completeTask(). */
	private void seedContract(String partyLabel, List<AttackStyle> styles, boolean convertedToSolo)
	{
		stateService.mutate(s -> s.withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(1)
			.killsDone(0)
			.perKillGc(0) // keeps the null client out of onKill
			.completionGc(1000)
			.acceptedAtMs(1L)
			.partyLabel(partyLabel)
			.partyStyles(styles)
			.partyConvertedToSolo(convertedToSolo)
			.build()));
	}

	private long completionAward()
	{
		taskService.onKill(goblinKill(10));
		Assert.assertEquals("exactly one completion must fire", 1, completions.size());
		return completions.get(0).getCompletionGcAwarded();
	}

	// --- A. the pure rule ---

	@Test
	public void distinctStylesCountsBeyondTheFirst()
	{
		Assert.assertEquals("legacy save / solo contract", 0, TaskService.distinctStyles(null));
		Assert.assertEquals(0, TaskService.distinctStyles(new ArrayList<>()));
		Assert.assertEquals(1, TaskService.distinctStyles(
			Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE)));
		Assert.assertEquals("the two-member case the constant was authored for", 2,
			TaskService.distinctStyles(Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC)));
		Assert.assertEquals(3, TaskService.distinctStyles(
			Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC, AttackStyle.RANGED)));
		Assert.assertEquals("a trio running two styles is not three-way diversity", 2,
			TaskService.distinctStyles(
				Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE, AttackStyle.MAGIC)));
		Assert.assertEquals("an absent style contributes nothing and must not throw", 2,
			TaskService.distinctStyles(Arrays.asList(AttackStyle.MELEE, null, AttackStyle.MAGIC)));
		Assert.assertEquals("a large mono party earns no clash bonus", 1,
			TaskService.distinctStyles(Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE,
				AttackStyle.MELEE, AttackStyle.MELEE)));
	}

	// --- B. what it pays ---

	@Test
	public void monoStylePartyPaysPlainCoopBonus()
	{
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE), false);
		Assert.assertEquals(1600, completionAward());
	}

	@Test
	public void mixedStylePairPaysTheClashBonus()
	{
		// the original regression: this paid 1600 while the style field had no writer
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC), false);
		Assert.assertEquals(1850, completionAward());
	}

	@Test
	public void threeWayDiversityPaysTheSameFlatBonusAsTwo()
	{
		// The clash bonus is FLAT, not per extra style: covering all three styles
		// pays exactly what covering two pays. This pinned 2100 while the bonus
		// scaled — the user's ruling is a flat 25%, so a scaling curve is the bug.
		seedContract("Party of 3",
			Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC, AttackStyle.RANGED), false);
		Assert.assertEquals(1850, completionAward());
	}

	@Test
	public void trioSharingAStylePaysOneClashStep()
	{
		seedContract("Party of 3",
			Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE, AttackStyle.MAGIC), false);
		Assert.assertEquals(1850, completionAward());
	}

	@Test
	public void legacyInFlightContractPaysCoopBonusWithoutThrowing()
	{
		// a shared contract signed before the snapshot existed: null list
		seedContract("Party of 2", null, false);
		Assert.assertEquals(1600, completionAward());
	}

	@Test
	public void carryClauseBeatsTheClashBonus()
	{
		// converted to solo: the carry multiplier wins outright and the clash
		// bonus must not leak into it
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC), true);
		Assert.assertEquals(800, completionAward());
	}

	@Test
	public void soloContractIsUntouched()
	{
		seedContract(null, null, false);
		Assert.assertEquals(1000, completionAward());
	}

	// --- D. the snapshot, not the live style ---

	@Test
	public void payoutFollowsTheSnapshotNotTheLiveStyle()
	{
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC), false);
		// the local style moves mid-contract (the ::gachastyle path) to match
		// the partner's; a live read would collapse the clash and pay 1600.
		// cycleTarget is set so advanceCycle cannot fire a second style roll.
		stateService.mutate(s -> s.withAllowedStyle("MAGIC").withCycleTarget(5).withCycleProgress(0));
		Assert.assertEquals(1850, completionAward());
	}
}
