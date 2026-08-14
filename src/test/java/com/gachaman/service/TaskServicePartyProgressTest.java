package com.gachaman.service;

import com.gachaman.data.MonsterTable;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * The party-progress fan-out at the tail of {@link TaskService#onKill}.
 *
 * <p>The loop used to read the volatile state snapshot FOUR separate times per
 * listener — once for the state null test, once for the active-task null test,
 * once for isParty() and once more to build the argument. Three of those reads
 * guarded a fourth read that nothing tied to them, so the value the listener
 * actually received was never the value the guards had approved. It also meant
 * five registered listeners cost twenty state reads on the single hottest path
 * in the plugin.
 *
 * <p>These tests pin both halves of the fix: the fan-out makes exactly ONE
 * observation per listener (so the guard and the argument are the same object
 * by construction), and that observation still happens per ITERATION rather
 * than once for the whole loop — a listener that ends the contract must still
 * silence the listeners queued behind it.
 *
 * <p>Headless, real in-memory state. The client is null throughout, which is
 * safe only because every contract here uses perKillGc 0 — the award branch is
 * the only client read onKill can reach.
 */
public class TaskServicePartyProgressTest
{
	/**
	 * Counts the state reads made through the PUBLIC accessor. Mutations inside
	 * GachaStateService touch the field directly, so this counts callers only —
	 * which is exactly the quantity under test.
	 */
	private static final class CountingStateService extends GachaStateService
	{
		private int gets;

		CountingStateService()
		{
			super(silentStore());
		}

		@Override
		public GachaState get()
		{
			gets++;
			return super.get();
		}
	}

	/** A store that persists nothing and always loads fresh. */
	private static StateStore silentStore()
	{
		return new StateStore(null, null, null)
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
	}

	/** Records every party-progress callback, including a null one if it ever comes. */
	private static class Recorder implements TaskService.Listener
	{
		final List<ActiveTask> progress = new ArrayList<>();

		@Override
		public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
		{
		}

		@Override
		public void onPartyProgress(ActiveTask task)
		{
			progress.add(task);
		}
	}

	private CountingStateService stateService;
	private TaskService taskService;

	@Before
	public void setUp()
	{
		stateService = new CountingStateService();
		stateService.load(3);
		CreditSink creditSink = new CreditSink(stateService);
		ComplianceService complianceService =
			new ComplianceService(stateService, creditSink, null, null);
		CeremonyBus ceremonyBus = new CeremonyBus();
		StyleService styleService =
			new StyleService(stateService, complianceService, ceremonyBus, new GachaRng(1L));
		taskService = new TaskService(null, stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L),
			MonsterTable.load(new com.google.gson.Gson()),
			// null Client already means these tests never reach rollOffers()
			null, null);
	}

	/**
	 * Five kills short of the quota, so a single onKill reaches the fan-out
	 * without falling through into completeTask().
	 */
	private void seedContract(String partyLabel)
	{
		stateService.mutate(s -> s.withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(5)
			.killsDone(0)
			.perKillGc(0) // keeps the null client out of onKill
			.completionGc(1000)
			.acceptedAtMs(1L)
			.partyLabel(partyLabel)
			.build()));
	}

	private KillTracker.Kill goblinKill(int tick)
	{
		return new KillTracker.Kill("Goblin", 2, 1, tick, tick - 3, false, 3, false, null);
	}

	// --- A. what the listener receives ---

	@Test
	public void partyKillNotifiesWithTheFreshlyCreditedTask()
	{
		seedContract("Party of 2");
		Recorder recorder = new Recorder();
		taskService.addListener(recorder);

		taskService.onKill(goblinKill(10));

		Assert.assertEquals("exactly one party-progress callback per credited kill",
			1, recorder.progress.size());
		ActiveTask seen = recorder.progress.get(0);
		Assert.assertNotNull("the guard approved a task, so a task must arrive", seen);
		Assert.assertTrue("only party contracts fan out", seen.isParty());
		// the fan-out sits AFTER the kill-count mutate on purpose: the partner
		// clients are told the new total, never the pre-kill one
		Assert.assertEquals("the task must carry the kill just credited", 1, seen.getKillsDone());
	}

	@Test
	public void soloContractNeverFansOut()
	{
		seedContract(null);
		Recorder recorder = new Recorder();
		taskService.addListener(recorder);

		taskService.onKill(goblinKill(10));

		Assert.assertTrue("a solo contract has nobody to tell", recorder.progress.isEmpty());
	}

	@Test
	public void offTaskKillNeverFansOut()
	{
		seedContract("Party of 2");
		Recorder recorder = new Recorder();
		taskService.addListener(recorder);

		taskService.onKill(new KillTracker.Kill("Cow", 2, 1, 10, 7, false, 3, false, null));

		Assert.assertTrue("an off-contract kill credits nothing to share",
			recorder.progress.isEmpty());
	}

	// --- B. the re-read stays per-iteration ---

	@Test
	public void listenerThatEndsTheContractSilencesTheOnesBehindIt()
	{
		seedContract("Party of 2");
		Recorder first = new Recorder()
		{
			@Override
			public void onPartyProgress(ActiveTask task)
			{
				super.onPartyProgress(task);
				// there is no abandonTask() in the plugin, but a listener CAN end
				// the contract (completion clears it the same way), and the check
				// lives inside the loop precisely so the rest of the fan-out sees it
				stateService.mutate(s -> s.withActiveTask(null));
			}
		};
		Recorder second = new Recorder();
		taskService.addListener(first);
		taskService.addListener(second);

		taskService.onKill(goblinKill(10));

		Assert.assertEquals("the listener that ran first still gets told",
			1, first.progress.size());
		Assert.assertNotNull(first.progress.get(0));
		Assert.assertTrue("the contract was gone before the second listener's turn",
			second.progress.isEmpty());
	}

	@Test
	public void listenerThatConvertsTheContractToSoloSilencesTheOnesBehindIt()
	{
		seedContract("Party of 2");
		Recorder first = new Recorder()
		{
			@Override
			public void onPartyProgress(ActiveTask task)
			{
				super.onPartyProgress(task);
				// isParty() goes false without the task going null: the guard has to
				// re-test the flag, not merely the presence of a contract
				stateService.mutate(s -> s.withActiveTask(
					s.getActiveTask().withPartyConvertedToSolo(true)));
			}
		};
		Recorder second = new Recorder();
		taskService.addListener(first);
		taskService.addListener(second);

		taskService.onKill(goblinKill(10));

		Assert.assertEquals(1, first.progress.size());
		Assert.assertTrue("a carried contract is nobody else's business",
			second.progress.isEmpty());
	}

	// --- C. one observation per listener, not four ---

	@Test
	public void fanOutCostsExactlyOneStateReadPerListener()
	{
		// Measured as a DELTA between two otherwise identical kills, so the test
		// pins the per-listener cost of the fan-out without hardcoding the total
		// number of reads onKill makes — that total is free to move.
		int withOne = readsForOneKillWith(1);
		int withFour = readsForOneKillWith(4);

		Assert.assertEquals("three extra listeners may cost three extra state reads,"
			+ " not twelve", 3, withFour - withOne);
	}

	/** Fresh harness, n party-progress listeners, one credited kill; returns get() calls. */
	private int readsForOneKillWith(int listenerCount)
	{
		setUp(); // rebuild against a fresh counter so the delta is clean
		seedContract("Party of 2");
		for (int i = 0; i < listenerCount; i++)
		{
			// deliberately NOT a listener that reads state itself: the only reads
			// this test may attribute to the fan-out are the fan-out's own
			taskService.addListener(new Recorder());
		}
		int before = stateService.gets;
		taskService.onKill(goblinKill(10));
		return stateService.gets - before;
	}
}
