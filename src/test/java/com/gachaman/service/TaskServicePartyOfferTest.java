package com.gachaman.service;

import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * The orphaned-party-offer surface: what a click on a party offer does with no
 * vote session behind it, and that demoting them back to personal offers keeps
 * every contract term intact ("rolls cannot be undone").
 *
 * Headless with a null client: none of hasPendingOffers, hasPendingPartyOffers,
 * demotePartyOffers or acceptOffer/acceptInternal reads the client — only
 * rollOffers does, which is why the offers here are seeded through the state
 * service instead of rolled.
 */
public class TaskServicePartyOfferTest
{
	private GachaStateService stateService;
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
			null);
	}

	private static List<TaskOffer> fourOffers(boolean partyRoll)
	{
		List<TaskOffer> offers = new ArrayList<>(4);
		offers.add(new TaskOffer(TaskDifficulty.EASY, "Goblin", 2, 15, 7, 400,
			new ArrayList<>(), false, partyRoll));
		offers.add(new TaskOffer(TaskDifficulty.MEDIUM, "Hill Giant", 28, 40, 12, 1100,
			Arrays.asList(new SideBet(SideBet.Kind.BIG_HIT, 30, 0, true, false, 250)),
			false, partyRoll));
		offers.add(new TaskOffer(TaskDifficulty.HARD, "Blue dragon", 111, 25, 30, 3200,
			new ArrayList<>(), true, partyRoll));
		offers.add(new TaskOffer(TaskDifficulty.EASY, "Cow", 2, 20, 5, 300,
			new ArrayList<>(), false, partyRoll));
		return offers;
	}

	private void seedOffers(boolean partyRoll)
	{
		List<TaskOffer> offers = fourOffers(partyRoll);
		stateService.mutate(s -> s.withPendingOffers(offers));
	}

	/** The defect itself: with no vote session the click accepts nothing at all. */
	@Test
	public void orphanedPartyOfferClickAcceptsNothing()
	{
		seedOffers(true);
		final int[] voted = {-1};
		taskService.setPartyVoteHook(i -> voted[0] = i);

		Assert.assertTrue("acceptOffer reports success even though nothing is accepted",
			taskService.acceptOffer(0));

		Assert.assertNull("a party offer must never bind a contract by itself",
			stateService.get().getActiveTask());
		Assert.assertEquals("the offers stay pending, so the roll gate stays shut",
			4, stateService.get().getPendingOffers().size());
		Assert.assertEquals("the click was consumed as a vote", 0, voted[0]);
	}

	/** Rolls cannot be undone: demotion may only flip the party flag. */
	@Test
	public void demotePreservesEveryContractExactly()
	{
		List<TaskOffer> original = fourOffers(true);
		stateService.mutate(s -> s.withPendingOffers(original));

		taskService.demotePartyOffers();

		List<TaskOffer> after = stateService.get().getPendingOffers();
		Assert.assertEquals(original.size(), after.size());
		for (int i = 0; i < original.size(); i++)
		{
			TaskOffer before = original.get(i);
			TaskOffer now = after.get(i);
			Assert.assertEquals(before.getDifficulty(), now.getDifficulty());
			Assert.assertEquals(before.getMonsterName(), now.getMonsterName());
			Assert.assertEquals(before.getMonsterCombatLevel(), now.getMonsterCombatLevel());
			Assert.assertEquals(before.getKillsRequired(), now.getKillsRequired());
			Assert.assertEquals(before.getPerKillGc(), now.getPerKillGc());
			Assert.assertEquals(before.getCompletionGc(), now.getCompletionGc());
			Assert.assertEquals(before.isRedemption(), now.isRedemption());
			Assert.assertEquals(before.getSideBets(), now.getSideBets());
			Assert.assertFalse("only the party flag may change", now.isPartyRoll());
		}
	}

	/** After demotion the same scroll signs a plain solo contract. */
	@Test
	public void demotedOfferAcceptsAsPersonalContract()
	{
		seedOffers(true);
		taskService.demotePartyOffers();

		Assert.assertTrue(taskService.acceptOffer(1));

		Assert.assertNotNull(stateService.get().getActiveTask());
		Assert.assertEquals("Hill Giant", stateService.get().getActiveTask().getMonsterName());
		Assert.assertNull("a demoted offer must never bind a SHARED contract",
			stateService.get().getActiveTask().getPartyLabel());
		Assert.assertFalse(stateService.get().getActiveTask().isParty());
		Assert.assertTrue(stateService.get().getPendingOffers().isEmpty());
	}

	/**
	 * The load-time recovery sweep runs on every single login, so demotion has
	 * to be a no-op on anything that is not a live party set.
	 */
	@Test
	public void demoteIsIdempotentAndIgnoresPersonalOffers()
	{
		seedOffers(true);
		taskService.demotePartyOffers();
		List<TaskOffer> once = stateService.get().getPendingOffers();
		taskService.demotePartyOffers();
		Assert.assertEquals(once, stateService.get().getPendingOffers());

		List<TaskOffer> personal = fourOffers(false);
		stateService.mutate(s -> s.withPendingOffers(personal));
		taskService.demotePartyOffers();
		Assert.assertEquals(personal, stateService.get().getPendingOffers());

		stateService.mutate(s -> s.withPendingOffers(new ArrayList<>()));
		taskService.demotePartyOffers(); // must not throw on an empty board
		Assert.assertTrue(stateService.get().getPendingOffers().isEmpty());
	}

	/** The predicate the recovery sweep gates on, over every board state. */
	@Test
	public void hasPendingPartyOffersPredicate()
	{
		Assert.assertFalse("an untouched board has nothing to recover",
			taskService.hasPendingPartyOffers());

		seedOffers(true);
		Assert.assertTrue(taskService.hasPendingPartyOffers());

		taskService.demotePartyOffers();
		Assert.assertFalse("demotion is what clears the sweep's trigger",
			taskService.hasPendingPartyOffers());
		Assert.assertTrue("...without clearing the offers themselves",
			taskService.hasPendingOffers());

		seedOffers(false);
		Assert.assertFalse(taskService.hasPendingPartyOffers());

		stateService.mutate(s -> s.withPendingOffers(new ArrayList<>()));
		Assert.assertFalse(taskService.hasPendingPartyOffers());
	}
}
