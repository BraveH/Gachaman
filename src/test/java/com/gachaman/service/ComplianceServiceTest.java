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
			}

			@Override
			public void onForbiddenPardoned(int tick, long refundedGc)
			{
				pardonRefunds.add(refundedGc);
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
}
