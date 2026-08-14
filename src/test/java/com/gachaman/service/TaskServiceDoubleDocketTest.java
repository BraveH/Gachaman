package com.gachaman.service;

import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * Double Docket: what a contract actually PAYS when its target is also the
 * player's live Slayer assignment, and how that composes with the rest of the
 * completion multiplier chain.
 *
 * Headless, real in-memory state. The Slayer read is a hook rather than an
 * injected ConfigManager precisely so it can be driven from here; the null
 * client is never touched because every contract uses perKillGc 0 (the award
 * branch is the only client read in onKill). The exact GC figures assume the
 * harness registers NO CreditSink modifiers and carries no taint unless a test
 * seeds some — both asserted in setUp.
 */
public class TaskServiceDoubleDocketTest
{
	private GachaStateService stateService;
	private TaskService taskService;
	private final List<TaskService.TaskCompletionSummary> completions = new ArrayList<>();
	/** What the Slayer layer would report; mutable so a test can end the task. */
	private String slayerTarget;
	private int latchAnnouncements;

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
		ComplianceService complianceService = new ComplianceService(stateService, creditSink, null, null);
		CeremonyBus ceremonyBus = new CeremonyBus();
		StyleService styleService = new StyleService(stateService, complianceService, ceremonyBus,
			new GachaRng(1L));
		com.gachaman.data.MonsterTable monsterTable =
			com.gachaman.data.MonsterTable.load(new com.google.gson.Gson());
		taskService = new TaskService(null, stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L), monsterTable,
			// null Client already means these tests never reach rollOffers()
			null, null);
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

	/** Wire the Slayer layer in, reading whatever {@link #slayerTarget} holds now. */
	private void wireSlayer(String target)
	{
		slayerTarget = target;
		taskService.setSlayerTargetHook(() -> slayerTarget);
		taskService.setSlayerLatchHook(() -> latchAnnouncements++);
	}

	private KillTracker.Kill goblinKill(int tick)
	{
		return new KillTracker.Kill("Goblin", 2, 1, tick, tick - 3, false, 3, false, null);
	}

	private void seedContract(String partyLabel, List<AttackStyle> styles, boolean convertedToSolo,
		boolean slayerAligned, int killsRequired)
	{
		stateService.mutate(s -> s.withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(killsRequired)
			.killsDone(0)
			.perKillGc(0) // keeps the null client out of onKill
			.completionGc(1000)
			.acceptedAtMs(1L)
			.partyLabel(partyLabel)
			.partyStyles(styles)
			.partyConvertedToSolo(convertedToSolo)
			.slayerAligned(slayerAligned)
			.build()));
	}

	/** One kill away from done, so a single onKill reaches completeTask(). */
	private void seedSolo(boolean slayerAligned)
	{
		seedContract(null, null, false, slayerAligned, 1);
	}

	private long completionAward()
	{
		taskService.onKill(goblinKill(10));
		Assert.assertEquals("exactly one completion must fire", 1, completions.size());
		return completions.get(0).getCompletionGcAwarded();
	}

	// --- A. no Slayer layer at all ---

	@Test
	public void unwiredHookPaysBase()
	{
		// every other test suite in this project leaves the hook null; none of
		// them may start paying a bonus because this feature exists
		seedSolo(false);
		Assert.assertEquals(1000, completionAward());
	}

	@Test
	public void throwingHookPaysBaseAndDoesNotEscape()
	{
		// a broken Slayer read must cost the player a bonus, never a completion
		taskService.setSlayerTargetHook(() ->
		{
			throw new IllegalStateException("no rs profile");
		});
		seedSolo(false);
		Assert.assertEquals(1000, completionAward());
	}

	// --- B. what it pays ---

	@Test
	public void alignedSoloContractPaysTwentyPercentMore()
	{
		wireSlayer("Goblins");
		seedSolo(false);
		Assert.assertEquals(1200, completionAward());
		Assert.assertEquals("the player is told once, on the kill that latches",
			1, latchAnnouncements);
	}

	@Test
	public void unalignedContractNeverLatches()
	{
		wireSlayer("Fire giants");
		seedContract(null, null, false, false, 2); // 2 kills: survives to be inspected
		taskService.onKill(goblinKill(10));
		Assert.assertFalse("a different assignment must not latch",
			stateService.get().getActiveTask().isSlayerAligned());
		Assert.assertEquals(0, latchAnnouncements);
		taskService.onKill(goblinKill(20));
		Assert.assertEquals(1000, completions.get(0).getCompletionGcAwarded());
	}

	// --- C. the latch ---

	@Test
	public void latchTurnsOnMidContract()
	{
		// signed with no assignment, then the player goes and gets the matching
		// one: the bonus applies, because the roll is never biased toward Slayer
		// and this is the only way alignment can be sought out deliberately
		wireSlayer(null);
		seedContract(null, null, false, false, 2);
		taskService.onKill(goblinKill(10));
		Assert.assertFalse(stateService.get().getActiveTask().isSlayerAligned());
		slayerTarget = "Goblins";
		Assert.assertEquals(1200, completionAward());
		Assert.assertEquals(1, latchAnnouncements);
	}

	@Test
	public void latchStaysOnAfterTheSlayerTaskEnds()
	{
		// finishing the Slayer assignment mid-contract must not retract a bonus
		// the sidebar has already promised
		wireSlayer("Goblins");
		seedContract(null, null, false, false, 2);
		taskService.onKill(goblinKill(10));
		Assert.assertTrue(stateService.get().getActiveTask().isSlayerAligned());
		slayerTarget = null; // assignment handed in
		Assert.assertEquals(1200, completionAward());
		Assert.assertEquals("no second announcement once latched", 1, latchAnnouncements);
	}

	@Test
	public void legacyInFlightContractDefaultsToNoBonus()
	{
		// a contract signed before this field existed deserializes slayerAligned
		// false; with no matching assignment it simply pays base
		wireSlayer("Fire giants");
		seedSolo(false);
		Assert.assertEquals(1000, completionAward());
	}

	@Test
	public void acceptingAnOfferLatchesImmediately()
	{
		// the accept-time check, so the sidebar can promise the bonus from the
		// first kill rather than only after one has landed
		wireSlayer("Goblins");
		stateService.mutate(s -> s.withPendingOffers(Collections.singletonList(
			new TaskOffer(TaskDifficulty.EASY, "Goblin", 2, 1, 0, 1000, null, false, false))));
		Assert.assertTrue(taskService.acceptOffer(0));
		Assert.assertTrue(stateService.get().getActiveTask().isSlayerAligned());
		Assert.assertEquals("accepting is not a kill, so nothing is announced yet",
			0, latchAnnouncements);
		Assert.assertEquals(1200, completionAward());
	}

	// --- D. composition with the rest of the chain ---

	@Test
	public void partyAndDocketComposeMultiplicatively()
	{
		// 1.6 co-op + 0.25 flat clash = 1.85, then x1.2
		wireSlayer("Goblins");
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC), false, false, 1);
		Assert.assertEquals(2220, completionAward());
	}

	@Test
	public void monoStylePartyAndDocketCompose()
	{
		wireSlayer("Goblins");
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MELEE), false, false, 1);
		Assert.assertEquals(1920, completionAward());
	}

	@Test
	public void carryClauseAndDocketCompose()
	{
		// the carry clause replaces the party bonus but not the docket
		wireSlayer("Goblins");
		seedContract("Party of 2", Arrays.asList(AttackStyle.MELEE, AttackStyle.MAGIC), true, false, 1);
		Assert.assertEquals(960, completionAward());
	}

	@Test
	public void taintStillHalvesADocketedAward()
	{
		// the docket is a completion MULTIPLIER, so it is priced before the sink
		// halves a tainted player's income: 1000 x1.2 = 1200, halved = 600. A
		// docket must never be a way to shrug off a debt.
		wireSlayer("Goblins");
		stateService.mutate(s -> s.withTaint(5));
		seedSolo(false);
		Assert.assertEquals(600, completionAward());
		Assert.assertTrue("the kill works one taint off, so the debt must still stand",
			stateService.get().getTaint() > 0);
	}
}
