package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * The Ante end to end: escrow at accept, doubled return at completion, total
 * loss on death, and the guarantee that none of it can interfere with the
 * contract itself.
 *
 * Headless, real in-memory state; the null client is never touched because
 * every contract here uses perKillGc 0 (the award branch is the only client
 * read in onKill). The exact GC figures assume the harness registers NO
 * CreditSink modifiers and carries no taint — both asserted in setUp.
 */
public class TaskServiceAnteTest
{
	private static final int PURSE = 2000;
	/**
	 * Everything the ONE kill in these tests pays that is not the Ante and not
	 * the completion award. A fresh harness has an empty bestiary, so that kill
	 * is a first-of-species discovery. Named rather than folded into the
	 * numbers so an Ante regression cannot hide behind an unexplained constant.
	 */
	private static final int KILL_EXTRAS = Tuning.DISCOVERY_GC;

	private GachaStateService stateService;
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
		setPurse(PURSE);
	}

	private void setPurse(long gc)
	{
		stateService.mutate(s -> s.withGc(gc));
	}

	private long gc()
	{
		return stateService.get().getGc();
	}

	private ActiveTask task()
	{
		return stateService.get().getActiveTask();
	}

	/** One kill short of done, perKillGc 0, so one onKill reaches completeTask. */
	private void board(TaskDifficulty difficulty)
	{
		TaskOffer offer = new TaskOffer(difficulty, "Goblin", 2, 1, 0, 1000, null, false, false);
		stateService.mutate(s -> s.withPendingOffers(Arrays.asList(offer)));
	}

	private KillTracker.Kill goblinKill()
	{
		return new KillTracker.Kill("Goblin", 2, 1, 10, 7, false, 3, false, null);
	}

	// --- A. the escrow ---

	@Test
	public void armingAloneStakesNothing()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		Assert.assertEquals("GC must not move until a contract is signed", PURSE, gc());
		Assert.assertTrue(taskService.anteArmed());
		Assert.assertEquals(1000, taskService.previewAnteStake());
	}

	@Test
	public void acceptingAnInsaneContractEscrowsTheStake()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		Assert.assertTrue(taskService.acceptOffer(0));
		Assert.assertEquals("the stake left the purse at signing", PURSE - 1000, gc());
		Assert.assertEquals(1000, task().getAnteStake());
		Assert.assertEquals(1000, taskService.getActiveAnteStake());
	}

	@Test
	public void theArmingIsSpentByTheContractItArmed()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		Assert.assertFalse("a stake must never carry silently to the next contract",
			taskService.anteArmed());
		Assert.assertEquals(0, taskService.getArmedAntePercent());
	}

	@Test
	public void anUnarmedPlayerStakesNothing()
	{
		board(TaskDifficulty.INSANE);
		Assert.assertTrue(taskService.acceptOffer(0));
		Assert.assertEquals(PURSE, gc());
		Assert.assertEquals(0, task().getAnteStake());
	}

	@Test
	public void onlyInsaneContractsTakeTheStake()
	{
		board(TaskDifficulty.HARD);
		taskService.armAnte(50);
		Assert.assertTrue("the contract is signed regardless", taskService.acceptOffer(0));
		Assert.assertEquals("an armed player must not be charged on an ineligible contract",
			PURSE, gc());
		Assert.assertEquals(0, task().getAnteStake());
	}

	@Test
	public void aPurseUnderTheFloorSignsTheContractWithoutAWager()
	{
		setPurse(Tuning.ANTE_MIN_PURSE_GC - 1);
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		Assert.assertTrue("the Ante must never block or cancel a contract",
			taskService.acceptOffer(0));
		Assert.assertNotNull(task());
		Assert.assertEquals(Tuning.ANTE_MIN_PURSE_GC - 1, gc());
		Assert.assertEquals(0, task().getAnteStake());
	}

	@Test
	public void aFreshBoardDisarmsAnyStandingArming()
	{
		taskService.armAnte(30);
		// the party layer installing a new offer set is the same decision point
		// as a personal re-roll: a new board, so a new answer
		Assert.assertTrue(taskService.presentPartyOffers(Arrays.asList(
			new TaskOffer(TaskDifficulty.INSANE, "Goblin", 2, 1, 0, 1000, null, false, true))));
		Assert.assertFalse(taskService.anteArmed());
	}

	// --- B. winning ---

	@Test
	public void completingReturnsTheStakeDoubled()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		long afterAccept = gc();
		taskService.onKill(goblinKill());

		Assert.assertEquals("exactly one completion must fire", 1, completions.size());
		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals("principal out of escrow plus an equal profit",
			afterAccept + completion + 2L * 1000 + KILL_EXTRAS, gc());
		Assert.assertNull(stateService.get().getActiveTask());
	}

	@Test
	public void aWonAnteIsNetNeutralOnTheStakeItself()
	{
		// the whole promise, stated as a balance: stake 1000, finish, be 1000 up
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		taskService.onKill(goblinKill());
		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals(PURSE + completion + 1000 + KILL_EXTRAS, gc());
	}

	@Test
	public void theStakePrincipalIsNotCountedAsEarnings()
	{
		// it is the player's own GC coming back out of escrow, not income; only
		// the profit is (and lifetime earnings drives prestige progress)
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		long earnedBefore = stateService.get().getLifetimeGcEarned();
		taskService.onKill(goblinKill());
		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals(earnedBefore + completion + 1000 + KILL_EXTRAS,
			stateService.get().getLifetimeGcEarned());
	}

	@Test
	public void anUnstakedContractPaysExactlyWhatItAlwaysDid()
	{
		board(TaskDifficulty.INSANE);
		taskService.acceptOffer(0);
		taskService.onKill(goblinKill());
		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals(1000, completion);
		Assert.assertEquals(PURSE + completion + KILL_EXTRAS, gc());
	}

	// --- C. losing ---

	@Test
	public void dyingForfeitsTheStake()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		long afterAccept = gc();

		taskService.onLocalPlayerDeath();
		Assert.assertEquals("the GC already left the purse; death only stops the return",
			afterAccept, gc());
		Assert.assertEquals(0, task().getAnteStake());
		Assert.assertNotNull("the contract itself survives the player", task());
	}

	@Test
	public void aForfeitedStakeIsNotReturnedOnCompletion()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		long afterAccept = gc();
		taskService.onLocalPlayerDeath();
		taskService.onKill(goblinKill());

		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals("finishing after a death pays the contract and nothing more",
			afterAccept + completion + KILL_EXTRAS, gc());
	}

	@Test
	public void dyingTwiceCannotChargeTheStakeTwice()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptOffer(0);
		long afterAccept = gc();
		taskService.onLocalPlayerDeath();
		taskService.onLocalPlayerDeath();
		taskService.onLocalPlayerDeath();
		Assert.assertEquals(afterAccept, gc());
		Assert.assertEquals(0, task().getAnteStake());
	}

	@Test
	public void dyingWithNothingStakedChangesNothing()
	{
		board(TaskDifficulty.INSANE);
		taskService.acceptOffer(0);
		taskService.onLocalPlayerDeath();
		Assert.assertEquals(PURSE, gc());
		Assert.assertNotNull(task());
	}

	@Test
	public void dyingWithNoContractAtAllIsHarmless()
	{
		// the death hook fires on every death, contract or not
		taskService.onLocalPlayerDeath();
		Assert.assertEquals(PURSE, gc());
		Assert.assertNull(task());
	}

	// --- D. the party path: personal stakes, personal loss ---

	@Test
	public void aPartyContractStakesOnlyWithThisClientsConsent()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		// the three-arg form is the no-verdict path: the party did not agree
		Assert.assertTrue(taskService.acceptPartyOffer(0, "Party of 3", null));
		Assert.assertEquals("no unanimity means no wager, but the contract stands",
			PURSE, gc());
		Assert.assertEquals(0, task().getAnteStake());
		Assert.assertTrue(task().isParty());
	}

	@Test
	public void aConsentedPartyContractEscrowsThisPlayersOwnStake()
	{
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		Assert.assertTrue(taskService.acceptPartyOffer(0, "Party of 3", null, true));
		Assert.assertEquals(PURSE - 1000, gc());
		Assert.assertEquals("the stake is priced off THIS purse, not the party's",
			1000, task().getAnteStake());
	}

	@Test
	public void aPartyVerdictCannotStakeAnUnarmedPlayer()
	{
		// belt and braces: the host's verdict says the party agreed, but a client
		// that armed nothing has consented to nothing
		board(TaskDifficulty.INSANE);
		Assert.assertTrue(taskService.acceptPartyOffer(0, "Party of 3", null, true));
		Assert.assertEquals(PURSE, gc());
		Assert.assertEquals(0, task().getAnteStake());
	}

	@Test
	public void theCarryClauseDoesNotReleaseTheStake()
	{
		// the partner leaves; the contract converts to solo and still pays out.
		// Refunding here would make "partner logs out" the cheap way out of a bet.
		board(TaskDifficulty.INSANE);
		taskService.armAnte(50);
		taskService.acceptPartyOffer(0, "Party of 2", null, true);
		taskService.convertPartyToSolo();
		Assert.assertEquals(1000, task().getAnteStake());

		taskService.onKill(goblinKill());
		long completion = completions.get(0).getCompletionGcAwarded();
		Assert.assertEquals(PURSE - 1000 + completion + 2000 + KILL_EXTRAS, gc());
	}
}
