package com.gachaman.service;

import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.ContractRecord;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.gachaman.persist.StateStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * What a completed contract actually files in the Dossier.
 *
 * Headless, real in-memory state; the null client is never touched because
 * every contract here uses perKillGc 0 (the award branch is the only client
 * read in onKill). Harness cloned from TaskServicePartyStyleTest.
 */
public class TaskServiceDossierTest
{
	/** acceptedAtMs is set this far back, so the filed duration is checkable. */
	private static final long RUN_MS = 60_000L;

	private GachaStateService stateService;
	private ComplianceService complianceService;
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
		CreditSink creditSink = new CreditSink(stateService);
		complianceService = new ComplianceService(stateService, creditSink, null, null);
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
		Assert.assertEquals("harness must carry no taint", 0, stateService.get().getTaint());
		Assert.assertTrue("a fresh save files nothing", stateService.get().getContractLog().isEmpty());
	}

	private KillTracker.Kill goblinKill(int tick)
	{
		return new KillTracker.Kill("Goblin", 2, 1, tick, tick - 3, false, 3, false, null);
	}

	/**
	 * Defaults first, caller's tweaks last — a plain builder argument would let
	 * the shared defaults silently overwrite whatever the test asked for.
	 */
	private void seedContract(Consumer<ActiveTask.ActiveTaskBuilder> tweaks)
	{
		ActiveTask.ActiveTaskBuilder builder = ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(1) // one kill away, so a single onKill reaches completeTask()
			.killsDone(0)
			.perKillGc(0) // keeps the null client out of onKill
			.completionGc(1000)
			.acceptedAtMs(System.currentTimeMillis() - RUN_MS);
		tweaks.accept(builder);
		ActiveTask task = builder.build();
		stateService.mutate(s -> s.withActiveTask(task));
	}

	private void seedSoloContract()
	{
		seedContract(b ->
		{
		});
	}

	private List<ContractRecord> log()
	{
		return stateService.get().getContractLog();
	}

	private ContractRecord onlyRecord()
	{
		Assert.assertEquals("exactly one completion must fire", 1, completions.size());
		Assert.assertEquals("exactly one record must be filed", 1, log().size());
		return log().get(0);
	}

	// --- A. the record a completion files ---

	@Test
	public void completingAContractFilesOneRecord()
	{
		long before = System.currentTimeMillis();
		seedSoloContract();
		taskService.onKill(goblinKill(10));

		ContractRecord record = onlyRecord();
		Assert.assertEquals("Goblin", record.getMonsterName());
		Assert.assertEquals("EASY", record.getDifficulty());
		Assert.assertEquals(1, record.getKills());
		Assert.assertEquals("solo contracts carry no party label", null, record.getParty());
		Assert.assertFalse(record.isParty());
		Assert.assertFalse(record.isCarried());
		Assert.assertFalse(record.isRedemption());
		Assert.assertTrue("no forbidden attack was ever made", record.isClean());
		Assert.assertEquals(0, record.getTaintedKills());
		Assert.assertTrue("the filed timestamp is the completion, not the accept",
			record.getAt() >= before);
		Assert.assertTrue("duration must be measured from acceptedAtMs",
			record.getDurationMs() >= RUN_MS && record.getDurationMs() < RUN_MS + 60_000L);
	}

	@Test
	public void filedGcIsTheWholeHaulTheSummaryReports()
	{
		seedSoloContract();
		taskService.onKill(goblinKill(10));
		// no side bets and perKillGc 0, so the haul IS the completion award — the
		// Dossier and the PersonalBest must never disagree about what a job paid
		Assert.assertEquals(1000, completions.get(0).getCompletionGcAwarded());
		Assert.assertEquals(1000, onlyRecord().getGc());
	}

	@Test
	public void aPartyContractFilesItsLabelAndItsBoostedPay()
	{
		seedContract(b -> b
			.partyLabel("Party of 2")
			.partyStyles(Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE)));
		taskService.onKill(goblinKill(10));

		ContractRecord record = onlyRecord();
		Assert.assertEquals("Party of 2", record.getParty());
		Assert.assertTrue(record.isParty());
		Assert.assertFalse(record.isCarried());
		Assert.assertEquals("the co-op multiplier must reach the filed pay", 1600, record.getGc());
	}

	@Test
	public void aCarriedContractFilesTheCarryFlagAndNotTheParty()
	{
		// the party dissolved mid-contract: isParty() goes false but the label is
		// still the truth about who it was signed with
		seedContract(b -> b
			.partyLabel("Party of 2")
			.partyStyles(Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC))
			.partyConvertedToSolo(true));
		taskService.onKill(goblinKill(10));

		ContractRecord record = onlyRecord();
		Assert.assertTrue(record.isCarried());
		Assert.assertEquals("Party of 2", record.getParty());
		Assert.assertEquals("the carry multiplier, not the clash bonus", 800, record.getGc());
	}

	@Test
	public void aRedemptionContractIsFiledAsOne()
	{
		seedContract(b -> b.redemption(true));
		taskService.onKill(goblinKill(10));
		Assert.assertTrue(onlyRecord().isRedemption());
	}

	@Test
	public void contractsAccumulateNewestLast()
	{
		seedSoloContract();
		taskService.onKill(goblinKill(10));
		seedContract(b -> b.completionGc(2000));
		taskService.onKill(goblinKill(40));

		List<ContractRecord> log = log();
		Assert.assertEquals(2, log.size());
		Assert.assertEquals(1000, log.get(0).getGc());
		Assert.assertEquals("appended, so the newest is last in storage order",
			2000, log.get(1).getGc());
		Assert.assertTrue(log.get(1).getAt() >= log.get(0).getAt());
	}

	// --- B. the style is the one the contract was RUN under ---

	@Test
	public void theFiledStyleIsThePreRollStyle()
	{
		// cycleTarget 1 with progress 0 means this completion tips the style cycle
		// over: the reward re-roll is due. The record must still read MELEE — it
		// is filed inside the completion mutate, BEFORE advanceCycle runs.
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(1).withCycleProgress(0));
		seedSoloContract();
		taskService.onKill(goblinKill(10));

		Assert.assertTrue("the cycle must actually have tipped for this test to mean anything",
			completions.get(0).isCycleTriggered());
		Assert.assertEquals("MELEE", onlyRecord().getStyle());
	}

	@Test
	public void aContractFinishedBeforeTheFirstStyleRollFilesANullStyle()
	{
		// a fresh account has no allowed style yet; null is honest, and the tab
		// simply omits the segment rather than inventing one
		Assert.assertNull(stateService.get().getAllowedStyle());
		seedSoloContract();
		taskService.onKill(goblinKill(10));
		Assert.assertNull(onlyRecord().getStyle());
	}

	@Test
	public void filedStyleIsASnapshotNotALiveView()
	{
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(5).withCycleProgress(0));
		seedSoloContract();
		taskService.onKill(goblinKill(10));
		stateService.mutate(s -> s.withAllowedStyle("MAGIC"));
		Assert.assertEquals("a later re-roll must not rewrite history", "MELEE",
			log().get(0).getStyle());
	}

	// --- C. the clean verdict ---

	@Test
	public void anOutOfStyleKillFilesTheContractDirty()
	{
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(5).withCycleProgress(0));
		seedSoloContract();
		// the kill at tick 10 spans [7, 10], so this attack convicts it
		complianceService.onAttack(AttackStyle.MAGIC, 8);
		taskService.onKill(goblinKill(10));

		ContractRecord record = onlyRecord();
		Assert.assertEquals(1, record.getTaintedKills());
		Assert.assertFalse(record.isClean());
	}

	@Test
	public void oneDirtyKillTaintsTheWholeContractEvenIfTheLastIsClean()
	{
		// the counter LATCHES: "was the contract clean" is not "was the final kill
		// clean". This is the whole point of counting rather than flagging.
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(5).withCycleProgress(0));
		seedContract(b -> b.killsRequired(2));
		complianceService.onAttack(AttackStyle.MAGIC, 8);
		taskService.onKill(goblinKill(10)); // convicted by the tick-8 attack
		taskService.onKill(goblinKill(30)); // spans [27, 30] — nothing to convict it

		ContractRecord record = onlyRecord();
		Assert.assertEquals(1, record.getTaintedKills());
		Assert.assertFalse(record.isClean());
	}

	@Test
	public void aForbiddenAttackOutsideTheKillWindowDoesNotDirtyTheContract()
	{
		// the same death-tick bound the GC penalty already respects: an attack
		// before the engagement started never belonged to this kill
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(5).withCycleProgress(0));
		seedSoloContract();
		complianceService.onAttack(AttackStyle.MAGIC, 2); // kill 10 spans [7, 10]
		taskService.onKill(goblinKill(10));
		Assert.assertTrue(onlyRecord().isClean());
	}

	@Test
	public void theViolationCounterDoesNotLeakIntoTheNextContract()
	{
		stateService.mutate(s -> s.withAllowedStyle("MELEE").withCycleTarget(9).withCycleProgress(0));
		seedSoloContract();
		complianceService.onAttack(AttackStyle.MAGIC, 8);
		taskService.onKill(goblinKill(10));
		Assert.assertFalse(log().get(0).isClean());

		// a brand new contract starts its own count at zero — the counter lives on
		// the ActiveTask, which the completion mutate cleared
		seedSoloContract();
		taskService.onKill(goblinKill(60));
		Assert.assertEquals(2, log().size());
		Assert.assertTrue("a fresh contract is presumed clean", log().get(1).isClean());
	}
}
