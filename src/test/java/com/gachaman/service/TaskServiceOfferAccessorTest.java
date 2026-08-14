package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;

/**
 * Pins the invariants behind TaskService's shared board accessors — pending(),
 * offerAt(), installOffers() — and behind the removal of acceptInternal's
 * partyAnchorId parameter.
 *
 * These all began life as copy-pasted blocks inside the individual methods. The
 * folding is only safe while the answers below stay identical, and each of them
 * is the kind of thing a later "simplification" would quietly change:
 *
 * <ul>
 * <li>a null pendingOffers list is an EMPTY BOARD, never an NPE and never a
 *     throw out of a bounds test — a save written before the field existed
 *     deserialises exactly that way;</li>
 * <li>every signed contract carries partyAnchorId 0, because nothing writes it
 *     any more and the persisted field must keep encoding the same value;</li>
 * <li>installing a board always disarms the Ante and always tells the
 *     listeners, on the party path as much as on the personal one.</li>
 * </ul>
 *
 * Headless with a null Client: nothing exercised here reads the client (only
 * rollOffers does), so the offers are seeded through the state service.
 */
public class TaskServiceOfferAccessorTest
{
	private GachaStateService stateService;
	private CeremonyBus ceremonyBus;
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
		ceremonyBus = new CeremonyBus();
		StyleService styleService = new StyleService(stateService, complianceService, ceremonyBus,
			new GachaRng(1L));
		MonsterTable monsterTable = MonsterTable.load(new Gson());
		taskService = new TaskService(null, stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L), monsterTable, null, null);
	}

	private static List<TaskOffer> twoOffers(boolean partyRoll)
	{
		List<TaskOffer> offers = new ArrayList<>(2);
		offers.add(new TaskOffer(TaskDifficulty.EASY, "Goblin", 2, 15, 7, 400,
			new ArrayList<>(), false, partyRoll));
		offers.add(new TaskOffer(TaskDifficulty.MEDIUM, "Hill Giant", 28, 40, 12, 1100,
			new ArrayList<>(), false, partyRoll));
		return offers;
	}

	private List<TaskOffer> seedOffers(boolean partyRoll)
	{
		List<TaskOffer> offers = twoOffers(partyRoll);
		stateService.mutate(s -> s.withPendingOffers(offers));
		return offers;
	}

	/**
	 * A save from before pendingOffers existed loads the field as null. Every
	 * board query has to read that as "nothing on the board" — the old code said
	 * so with a hand-written null test at each site, the accessor says it once.
	 */
	@Test
	public void aNullPendingListIsAnEmptyBoard()
	{
		stateService.mutate(s -> s.withPendingOffers(null));

		Assert.assertFalse(taskService.hasPendingOffers());
		Assert.assertFalse(taskService.hasPendingPartyOffers());
		Assert.assertFalse("nothing to re-present", taskService.presentOffers());
		Assert.assertFalse("nothing to accept", taskService.acceptOffer(0));
		Assert.assertFalse("and a bogus index must not escape as a throw",
			taskService.acceptOffer(-1));
		Assert.assertNull(stateService.get().getActiveTask());
	}

	/** The accessor hands back the state's OWN list, so re-presenting is free. */
	@Test
	public void presentOffersSubmitsTheBoardItself()
	{
		List<TaskOffer> seeded = seedOffers(false);
		AtomicReference<Object> presented = new AtomicReference<>();
		ceremonyBus.addTap(request ->
		{
			if (request.getType() == CeremonyBus.Type.TASK_OFFERS)
			{
				presented.set(request.getPayload());
			}
		});

		Assert.assertTrue(taskService.presentOffers());

		Assert.assertSame("the ceremony must receive the live board, not a copy",
			seeded, presented.get());
	}

	/** Both accept paths refuse the same four ways, in the same order. */
	@Test
	public void acceptRefusesEveryOffBoardIndex()
	{
		Assert.assertFalse("empty board", taskService.acceptOffer(0));

		seedOffers(false);
		Assert.assertFalse("past the end", taskService.acceptOffer(2));
		Assert.assertFalse("past the end, party path",
			taskService.acceptPartyOffer(2, "Party of 2", null));

		Assert.assertTrue(taskService.acceptOffer(0));
		Assert.assertNotNull(stateService.get().getActiveTask());

		// a contract in force closes the board to both paths
		stateService.mutate(s -> s.withPendingOffers(twoOffers(false)));
		Assert.assertFalse("a contract is a contract", taskService.acceptOffer(1));
		Assert.assertFalse(taskService.acceptPartyOffer(1, "Party of 2", null));
		Assert.assertEquals("Goblin", stateService.get().getActiveTask().getMonsterName());
	}

	/**
	 * partyAnchorId is no longer a parameter of acceptInternal. It stays a
	 * persisted field (dropping it would change the save format), so what must
	 * hold is that the builder still writes the 0 every accept has always
	 * written — on the solo path and on the shared one alike.
	 */
	@Test
	public void everySignedContractCarriesAnchorIdZero()
	{
		seedOffers(false);
		Assert.assertTrue(taskService.acceptOffer(0));
		Assert.assertEquals("solo contract", 0L,
			stateService.get().getActiveTask().getPartyAnchorId());

		setUp(); // fresh state: a contract in force would block the second accept
		seedOffers(false);
		Assert.assertTrue(taskService.acceptPartyOffer(1, "Party of 3",
			Arrays.asList(AttackStyle.MELEE, AttackStyle.RANGED), false, 4242L));
		ActiveTask shared = stateService.get().getActiveTask();
		Assert.assertEquals("shared contract", 0L, shared.getPartyAnchorId());
		Assert.assertEquals("the id that actually survives a restart is untouched",
			Long.valueOf(4242L), shared.getPartyProposalId());
		Assert.assertEquals("Party of 3", shared.getPartyLabel());
		Assert.assertTrue(shared.isParty());
	}

	/**
	 * The personal roll and the party installer share one installer, so the
	 * party path must disarm the Ante and notify the listeners exactly as the
	 * personal path does. Arming survives neither: a stake is a decision about
	 * one specific board.
	 */
	@Test
	public void installingAPartyBoardDisarmsTheAnteAndNotifiesListeners()
	{
		AtomicReference<List<TaskOffer>> announced = new AtomicReference<>();
		taskService.addListener(new TaskService.Listener()
		{
			@Override
			public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
			{
			}

			@Override
			public void onOffersRolled(List<TaskOffer> offers)
			{
				announced.set(offers);
			}
		});
		AtomicInteger ceremonies = new AtomicInteger();
		ceremonyBus.addTap(request ->
		{
			if (request.getType() == CeremonyBus.Type.TASK_OFFERS)
			{
				ceremonies.incrementAndGet();
			}
		});
		taskService.armAnte(20);
		Assert.assertTrue(taskService.anteArmed());

		List<TaskOffer> offers = twoOffers(true);
		Assert.assertTrue(taskService.presentPartyOffers(offers));

		Assert.assertEquals("a new board is a new decision", 0,
			taskService.getArmedAntePercent());
		Assert.assertSame(offers, announced.get());
		Assert.assertEquals(1, ceremonies.get());
		Assert.assertEquals(offers, stateService.get().getPendingOffers());
		Assert.assertTrue(taskService.hasPendingPartyOffers());
	}
}
