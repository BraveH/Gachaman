package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.persist.StateStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Conviction + pardon round-trip (headless, real in-memory state; the null
 * client skips the tutorial gate and quest-exemption branches).
 */
public class ComplianceServiceTest
{
	private GachaStateService stateService;
	private ComplianceService complianceService;
	private final List<Long> pardonRefunds = new ArrayList<>();
	/** (cleared, remaining) pairs — a pardon must NEVER land in here. */
	private final List<int[]> taintCleared = new ArrayList<>();
	/** (lifted, remaining) pairs from the pardon rollback. */
	private final List<int[]> taintRolledBack = new ArrayList<>();

	private int taint()
	{
		return stateService.get().getTaint();
	}

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
		complianceService.addListener(new ComplianceService.Listener()
		{
			@Override
			public void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc)
			{
			}

			@Override
			public void onTaintAdded(int newTaint)
			{
			}

			@Override
			public void onTaintCleared(int cleared, int remaining)
			{
				taintCleared.add(new int[]{cleared, remaining});
			}

			@Override
			public void onForbiddenPardoned(int tick, long refundedGc)
			{
				pardonRefunds.add(refundedGc);
			}

			@Override
			public void onTaintRolledBack(int lifted, int remaining)
			{
				taintRolledBack.add(new int[]{lifted, remaining});
			}
		});
		stateService.mutate(s -> s.withGc(1000).withAllowedStyle(AttackStyle.MAGIC.name()));
	}

	@Test
	public void pardonRefundsPenaltyAndClearsTaintWindow()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		Assert.assertEquals(1000 - Tuning.VIOLATION_ATTACK_PENALTY_NO_TASK,
			stateService.get().getGc());
		Assert.assertTrue(complianceService.forbiddenAttackBetween(95, 105));
		Assert.assertEquals(100, complianceService.getLastForbiddenAttackTick());

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(1000, stateService.get().getGc());
		Assert.assertFalse(complianceService.forbiddenAttackBetween(95, 105));
		Assert.assertEquals(-1, complianceService.getLastForbiddenAttackTick());
		Assert.assertEquals(100, complianceService.getLastCompliantAttackTick());
		Assert.assertEquals(List.of((long) Tuning.VIOLATION_ATTACK_PENALTY_NO_TASK), pardonRefunds);
	}

	@Test
	public void pardonOnlyLiftsTheMatchingConviction()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.onAttack(AttackStyle.MELEE, 104);
		complianceService.onAttackPardoned(100);
		Assert.assertFalse(complianceService.forbiddenAttackBetween(100, 103));
		Assert.assertTrue(complianceService.forbiddenAttackBetween(104, 104));
		Assert.assertEquals(104, complianceService.getLastForbiddenAttackTick());
	}

	@Test
	public void pardonOfACompliantOrUnknownTickIsANoOp()
	{
		complianceService.onAttack(AttackStyle.MAGIC, 100); // compliant
		long gcBefore = stateService.get().getGc();
		complianceService.onAttackPardoned(100);
		complianceService.onAttackPardoned(999);
		Assert.assertEquals(gcBefore, stateService.get().getGc());
		Assert.assertTrue(pardonRefunds.isEmpty());
	}

	/**
	 * The defect: the pardon refunded the GC but left the taint standing, so the
	 * player kept paying the halved-income tax for an attack the plugin had just
	 * declared legal.
	 */
	@Test
	public void pardonRollsBackTheTaintItCaused()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		Assert.assertEquals(100, complianceService.convictingAttackTick(95, 100));
		complianceService.addTaint(100);
		Assert.assertEquals(1, taint());

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(0, taint());
		Assert.assertEquals(1, taintRolledBack.size());
		Assert.assertArrayEquals(new int[]{1, 0}, taintRolledBack.get(0));
		Assert.assertEquals(1000, stateService.get().getGc());
	}

	/**
	 * A pardon must not masquerade as a cleanse: onTaintCleared is what claims
	 * the once-ever FIRST_TAINT_CLEARED stamp and pays its bounty, and nothing
	 * here was worked off.
	 */
	@Test
	public void pardonNeverFiresTaintCleared()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		complianceService.onAttackPardoned(100);
		Assert.assertTrue(taintCleared.isEmpty());
		Assert.assertEquals(1, taintRolledBack.size());
	}

	@Test
	public void pardonDoesNotClearUnrelatedTaint()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		complianceService.onAttack(AttackStyle.MELEE, 200);
		complianceService.addTaint(200);
		Assert.assertEquals(2, taint());

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(1, taint());
		Assert.assertEquals(200, complianceService.convictingAttackTick(200, 200));
	}

	/** Ledger drift: the point is already gone, so the pardon must lift nothing. */
	@Test
	public void pardonAfterTheTaintWasAlreadyWorkedOff()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		complianceService.workOffTaint();
		Assert.assertEquals(0, taint());
		Assert.assertEquals(1, taintCleared.size());

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(0, taint());
		Assert.assertTrue(taintRolledBack.isEmpty());
		Assert.assertEquals(1, taintCleared.size());
	}

	@Test
	public void workOffTaintConsumesOldestConvictionFirst()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		complianceService.onAttack(AttackStyle.MELEE, 200);
		complianceService.addTaint(200);
		complianceService.workOffTaint();
		Assert.assertEquals(1, taint());

		complianceService.onAttackPardoned(100); // its point was already worked off
		Assert.assertEquals(1, taint());
		complianceService.onAttackPardoned(200);
		Assert.assertEquals(0, taint());
		complianceService.onAttackPardoned(200); // gone from the deque — no-op
		Assert.assertEquals(0, taint());
	}

	@Test
	public void clearAllTaintZeroesTheLedger()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		complianceService.clearAllTaint();
		Assert.assertEquals(0, taint());

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(0, taint());
		Assert.assertTrue(taintRolledBack.isEmpty());
	}

	/**
	 * The winning ordering, which already worked: the pardon beats the deferred
	 * kill, so the kill is judged compliant and no taint is ever added.
	 */
	@Test
	public void pardonBeforeCreditStillPreventsTaintEntirely()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.onAttackPardoned(100);
		Assert.assertEquals(-1, complianceService.convictingAttackTick(90, 105));
		Assert.assertFalse(complianceService.forbiddenAttackBetween(95, 105));
		Assert.assertEquals(0, taint());
	}

	/**
	 * Two convictions inside ONE kill window: the taint is pinned to the oldest,
	 * but StyleTracker can only ever pardon the newest verdict. Lifting nothing
	 * is the correct conservative answer — the surviving conviction still earns
	 * the taint.
	 */
	@Test
	public void pardoningTheNewerOfTwoConvictionsInOneWindowLiftsNothing()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.onAttack(AttackStyle.MELEE, 103);
		complianceService.addTaint(complianceService.convictingAttackTick(95, 105));
		Assert.assertEquals(1, taint());

		complianceService.onAttackPardoned(103);
		Assert.assertEquals(1, taint());
		Assert.assertTrue(taintRolledBack.isEmpty());
		Assert.assertEquals(100, complianceService.convictingAttackTick(95, 105));
	}

	/**
	 * The ledger is in-memory while taint is persisted, so a reload can leave a
	 * conviction scored against a counter that no longer holds its point. The
	 * rollback is clamped to what is actually standing, never to the ledger.
	 */
	@Test
	public void staleLedgerAgainstAReloadedCounterIsANoOp()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.addTaint(100);
		stateService.mutate(s -> s.withTaint(0)); // counter reloaded out from under it

		complianceService.onAttackPardoned(100);
		Assert.assertEquals(0, taint());
		Assert.assertTrue(taintRolledBack.isEmpty());
	}

	@Test
	public void convictingAttackTickAgreesWithForbiddenAttackBetween()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.onAttack(AttackStyle.MELEE, 107);
		int[][] windows = {{95, 105}, {100, 100}, {101, 106}, {107, 110}, {0, 99}, {108, 200}};
		for (int[] window : windows)
		{
			Assert.assertEquals("window " + window[0] + ".." + window[1],
				complianceService.forbiddenAttackBetween(window[0], window[1]),
				complianceService.convictingAttackTick(window[0], window[1]) >= 0);
		}
	}

	@Test
	public void resetTransientDropsConvictionsWithTheProfile()
	{
		complianceService.onAttack(AttackStyle.MELEE, 100);
		complianceService.resetTransient();
		Assert.assertEquals(-1, complianceService.convictingAttackTick(95, 105));
		Assert.assertEquals(-1, complianceService.getLastForbiddenAttackTick());
		Assert.assertEquals(-1, complianceService.getLastCompliantAttackTick());
	}
}
