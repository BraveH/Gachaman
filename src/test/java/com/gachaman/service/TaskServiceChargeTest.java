package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import org.junit.*;

/**
 * Voucher-first purchaseCharge spend path and Compactor kill-count doubling
 * (headless, real in-memory state; the null client is never touched because
 * kill tests use perKillGc 0 contracts — the award branch is the only client
 * read in onKill).
 */
public class TaskServiceChargeTest
{
	private GachaStateService stateService;
	private CreditSink creditSink;
	private TaskService taskService;

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
		StyleService styleService = StyleFixture.styleService(stateService, complianceService, ceremonyBus,
			new GachaRng(1L));
		com.gachaman.data.MonsterTable monsterTable =
			com.gachaman.data.MonsterTable.load(new com.google.gson.Gson());
		taskService = new TaskService(null, stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L), monsterTable,
			// null Client already means these tests never reach rollOffers()
			null, null,
			// The three collaborators added with the Preferred Weapon and the
			// Consignment are unwired here: no contract in this file pays per kill,
			// so the award branch (the only reader of the weapon pair) is never
			// reached, and no completion here tips the style cycle.
			null, null, null);
	}

	private void seed(long gc, int compactors, int extenders, boolean withTask)
	{
		seed(gc, compactors, extenders, withTask, 8);
	}

	private void seed(long gc, int compactors, int extenders, boolean withTask, int perKillGc)
	{
		stateService.mutate(s -> {
			GachaState next = s.withGc(gc)
				.withFreeCompactors(compactors)
				.withFreeExtenders(extenders);
			if (withTask)
			{
				next = next.withActiveTask(ActiveTask.builder()
					.difficulty(TaskDifficulty.EASY)
					.monsterName("Goblin")
					.killsRequired(10)
					.killsDone(0)
					.perKillGc(perKillGc)
					.completionGc(250)
					.acceptedAtMs(1L)
					.build());
			}
			return next;
		});
	}

	private KillTracker.Kill goblinKill(int tick)
	{
		return new KillTracker.Kill("Goblin", 2, 1, tick, tick - 3, false, 3, false, null);
	}

	@Test
	public void assistedPenaltyStandsDownOnSharedPartyContracts()
	{
		ActiveTask solo = ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(10)
			.perKillGc(8)
			.completionGc(250)
			.acceptedAtMs(1L)
			.build();
		ActiveTask party = solo.toBuilder().partyLabel("Party of 3").build();
		Assert.assertTrue(TaskService.assistedPenaltyApplies(true, true, solo));
		Assert.assertFalse(TaskService.assistedPenaltyApplies(true, true, party));
		// carry clause converts the contract back to solo — the rule re-arms
		Assert.assertTrue(TaskService.assistedPenaltyApplies(true, true,
			party.withPartyConvertedToSolo(true)));
		Assert.assertFalse(TaskService.assistedPenaltyApplies(false, true, solo));
		Assert.assertFalse(TaskService.assistedPenaltyApplies(true, false, solo));
	}

	@Test
	public void voucherConsumedInsteadOfGc()
	{
		seed(1000, 1, 1, true);
		Assert.assertTrue(taskService.purchaseCharge(true));
		GachaState state = stateService.get();
		Assert.assertEquals(0, state.getFreeCompactors());
		Assert.assertEquals(1, state.getFreeExtenders());
		Assert.assertEquals("GC untouched when a voucher pays", 1000, state.getGc());
		Assert.assertEquals("COMPACTOR", state.getActiveTask().getAppliedCharge());
	}

	@Test
	public void secondChargeSameTaskBlocked()
	{
		seed(1000, 1, 1, true);
		Assert.assertTrue(taskService.purchaseCharge(true));
		Assert.assertFalse(taskService.purchaseCharge(true));
		Assert.assertFalse(taskService.purchaseCharge(false));
		GachaState state = stateService.get();
		Assert.assertEquals(0, state.getFreeCompactors());
		Assert.assertEquals(1, state.getFreeExtenders());
		Assert.assertEquals(1000, state.getGc());
	}

	@Test
	public void gcSpentWhenNoVoucher()
	{
		seed(1000, 0, 0, true);
		Assert.assertTrue(taskService.purchaseCharge(true));
		GachaState state = stateService.get();
		Assert.assertEquals(1000 - Tuning.COMPACTOR_PRICE_GC, state.getGc());
		Assert.assertEquals("COMPACTOR", state.getActiveTask().getAppliedCharge());
	}

	@Test
	public void voucherWorksAtZeroGc()
	{
		seed(0, 0, 1, true);
		Assert.assertTrue(taskService.purchaseCharge(false));
		GachaState state = stateService.get();
		Assert.assertEquals(0, state.getFreeExtenders());
		Assert.assertEquals(0, state.getGc());
		Assert.assertEquals("EXTENDER", state.getActiveTask().getAppliedCharge());
	}

	@Test
	public void wrongTypeVoucherNotUsed()
	{
		seed(0, 1, 0, true);
		Assert.assertFalse("extender unaffordable, compactor voucher must not cross over",
			taskService.purchaseCharge(false));
		GachaState state = stateService.get();
		Assert.assertEquals(1, state.getFreeCompactors());
		Assert.assertNull(state.getActiveTask().getAppliedCharge());
	}

	@Test
	public void noActiveTaskDoesNotConsumeVoucher()
	{
		seed(1000, 1, 1, false);
		Assert.assertFalse(taskService.purchaseCharge(true));
		GachaState state = stateService.get();
		Assert.assertEquals(1, state.getFreeCompactors());
		Assert.assertEquals(1, state.getFreeExtenders());
		Assert.assertEquals(1000, state.getGc());
	}

	@Test
	public void compactorDoublesKillCount()
	{
		seed(1000, 1, 0, true, 0);
		Assert.assertTrue(taskService.purchaseCharge(true));
		taskService.onKill(goblinKill(10));
		Assert.assertEquals("one kill must count as two", 2,
			stateService.get().getActiveTask().getKillsDone());
		taskService.onKill(goblinKill(20));
		Assert.assertEquals(4, stateService.get().getActiveTask().getKillsDone());
	}

	@Test
	public void plainKillCountsOne()
	{
		seed(1000, 0, 0, true, 0);
		taskService.onKill(goblinKill(10));
		Assert.assertEquals(1, stateService.get().getActiveTask().getKillsDone());
	}

	@Test
	public void compactorFinalKillClampsAndCompletes()
	{
		seed(1000, 1, 0, true, 0);
		Assert.assertTrue(taskService.purchaseCharge(true));
		// 9/10 done: the doubled kill must clamp to 10 (not 11) and complete
		stateService.mutate(s -> s.withActiveTask(s.getActiveTask().withKillsDone(9)));
		taskService.onKill(goblinKill(10));
		GachaState state = stateService.get();
		Assert.assertNull("task must complete", state.getActiveTask());
		Assert.assertEquals(1, state.getTotalTasksCompleted());
	}

	@Test
	public void lootOracleVerdict()
	{
		// loot received = server proof of full credit, overrides any suspicion
		Assert.assertFalse(KillTracker.finalAssisted(true, true, true, true));
		Assert.assertFalse(KillTracker.finalAssisted(true, false, true, true));
		// suspicion convicts regardless of the loot pipeline
		Assert.assertTrue(KillTracker.finalAssisted(false, true, false, true));
		Assert.assertTrue(KillTracker.finalAssisted(false, true, true, false));
		// absence convicts only when the pipeline is live AND a drop was guaranteed
		Assert.assertTrue(KillTracker.finalAssisted(false, false, true, true));
		Assert.assertFalse("dead pipeline must never convict",
			KillTracker.finalAssisted(false, false, false, true));
		Assert.assertFalse("no guaranteed drop proves nothing",
			KillTracker.finalAssisted(false, false, true, false));
	}

	@Test
	public void kcAdvanceRules()
	{
		// plain kill
		Assert.assertEquals(1, TaskService.kcAdvance(false, false, false).getIncrement());
		// compactor doubles
		Assert.assertEquals(2, TaskService.kcAdvance(true, false, false).getIncrement());
		// assisted normal kill: first banks a half, second redeems it
		TaskService.KcAdvance first = TaskService.kcAdvance(false, true, false);
		Assert.assertEquals(0, first.getIncrement());
		Assert.assertTrue(first.isHalfPending());
		TaskService.KcAdvance second = TaskService.kcAdvance(false, true, true);
		Assert.assertEquals(1, second.getIncrement());
		Assert.assertFalse(second.isHalfPending());
		// assisted compactor kill lands back on exactly 1, half untouched
		Assert.assertEquals(1, TaskService.kcAdvance(true, true, false).getIncrement());
		Assert.assertFalse(TaskService.kcAdvance(true, true, false).isHalfPending());
		Assert.assertEquals(1, TaskService.kcAdvance(true, true, true).getIncrement());
		Assert.assertTrue("pending half survives a compactor-assisted kill",
			TaskService.kcAdvance(true, true, true).isHalfPending());
		// unassisted kills never touch a banked half
		Assert.assertTrue(TaskService.kcAdvance(false, false, true).isHalfPending());
	}

	@Test
	public void compactorSkippedCountPaysNothing()
	{
		// perKillGc 0 keeps the client untouched; discovery pays a flat +25 on
		// the first-ever goblin, so any GC delta beyond that would mean the
		// skipped count was paid
		seed(0, 1, 0, true, 0);
		Assert.assertTrue(taskService.purchaseCharge(true));
		taskService.onKill(goblinKill(10));
		long afterFirst = stateService.get().getGc();
		taskService.onKill(goblinKill(20));
		Assert.assertEquals("second kill of a known species with perKill 0 must pay 0",
			afterFirst, stateService.get().getGc());
	}
}
