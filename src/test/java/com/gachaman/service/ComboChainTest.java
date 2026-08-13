package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import org.junit.*;

/**
 * Tick behaviour of the Rhythm Combo. The pure ladder maths lives in
 * {@link EarlyGameMathTest}; what is pinned here is when a kill banks and when
 * the chain dies, because that is where the chain used to freeze: growth was
 * gated on a 25s window measured from the previous KILL, so anything slower
 * than that sat on one stack forever while the meter still showed an active
 * chain. Only idling breaks a chain now.
 *
 * <p>The combo path touches no collaborator, so nulls are enough to build one.
 */
public class ComboChainTest
{
	private static final int IDLE = Tuning.COMBO_IDLE_RESET_TICKS;
	private static final int PER_STACK = Tuning.COMBO_KILLS_PER_STACK;

	private TaskService service()
	{
		return new TaskService(null, null, null, null, null, null, null, null, null);
	}

	/** Fight from `from` until `until`, landing an attack every other tick. */
	private void fight(TaskService s, int from, int until)
	{
		for (int t = from; t < until; t += 2)
		{
			s.onAttack(AttackStyle.MELEE, t);
		}
	}

	/** Land `kills` kills `gapTicks` apart, attacking throughout; returns the last tick. */
	private int grind(TaskService s, int kills, int gapTicks, int startTick)
	{
		int tick = startTick;
		for (int i = 0; i < kills; i++)
		{
			fight(s, tick + 1, tick + gapTicks);
			tick += gapTicks;
			s.advanceCombo(tick);
		}
		return tick;
	}

	@Test
	public void aStackCostsFiveKills()
	{
		TaskService s = service();
		int tick = 0;
		// four kills buy nothing at all
		for (int kill = 1; kill < PER_STACK; kill++)
		{
			tick += 10;
			Assert.assertEquals("kill " + kill, 0, s.advanceCombo(tick));
		}
		// the fifth lands the first stack; the four after it do not add another
		for (int kill = PER_STACK; kill < 2 * PER_STACK; kill++)
		{
			tick += 10;
			Assert.assertEquals("kill " + kill, 1, s.advanceCombo(tick));
		}
		// the tenth lands the second
		tick += 10;
		Assert.assertEquals(2, s.advanceCombo(tick));
	}

	@Test
	public void aKillSlowerThanTheOldWindowStillBanks()
	{
		TaskService s = service();
		// 45 ticks (27s) per kill — past the retired 42-tick growth window, so
		// this is exactly the case that used to be stuck forever
		int last = grind(s, PER_STACK, 45, 0);
		Assert.assertEquals(1, s.comboStacksAt(last));
	}

	@Test
	public void aTankyTargetReachesAFullChain()
	{
		TaskService s = service();
		// 90 ticks/kill — nearly a minute each, and still a full chain
		int last = grind(s, Tuning.COMBO_MAX_KILLS, 90, 0);
		Assert.assertEquals(Tuning.COMBO_MAX_STACKS, s.comboStacksAt(last));
		// and it holds there rather than banking credit it cannot spend
		last = grind(s, 20, 90, last);
		Assert.assertEquals(Tuning.COMBO_MAX_STACKS, s.comboStacksAt(last));
		Assert.assertEquals(0, s.comboProgressAt(last), 1e-9);
	}

	@Test
	public void idlingPastTheCutoffStartsTheChainOver()
	{
		TaskService s = service();
		int last = grind(s, 2 * PER_STACK, 10, 0);
		Assert.assertEquals(2, s.comboStacksAt(last));
		// no attacks at all across the cutoff
		Assert.assertEquals(0, s.advanceCombo(last + IDLE + 1));
	}

	@Test
	public void oneTickInsideTheCutoffKeepsTheChain()
	{
		TaskService s = service();
		grind(s, PER_STACK, 1, 0);
		// the next kill lands exactly on the cutoff, so the chain survives and
		// the banked kills carry — a sixth kill, not a new chain's first
		Assert.assertEquals(1, s.advanceCombo(PER_STACK + IDLE));
	}

	@Test
	public void attacksAfterTheCutoffCannotReviveADeadChain()
	{
		TaskService s = service();
		int last = grind(s, PER_STACK, 5, 0);
		Assert.assertEquals(1, s.comboStacksAt(last));
		// the chain is already dead by the time these land, so they must not
		// re-anchor it — the next kill starts a new chain rather than resuming
		fight(s, last + IDLE + 1, last + IDLE + 40);
		Assert.assertEquals(0, s.advanceCombo(last + IDLE + 41));
	}

	@Test
	public void anIdleChainReadsAsZeroBeforeTheNextKill()
	{
		TaskService s = service();
		int last = grind(s, PER_STACK, 1, 0);
		Assert.assertEquals(1, s.comboStacksAt(last + IDLE));
		Assert.assertEquals(0, s.comboStacksAt(last + IDLE + 1));
		Assert.assertEquals(0, s.comboProgressAt(last + IDLE + 1), 1e-9);
		// and the countdown drains to nothing rather than going negative
		Assert.assertEquals(0, s.comboIdleTicksRemaining(last + IDLE + 99));
	}

	@Test
	public void attackingHoldsTheCountdownOpen()
	{
		TaskService s = service();
		s.advanceCombo(0);
		fight(s, 1, IDLE);
		// last attack landed just under the cutoff, so the chain is still live
		// well past the point an unattended one would have expired
		Assert.assertEquals(0, s.comboStacksAt(IDLE + 10));
		Assert.assertTrue(s.comboIdleTicksRemaining(IDLE + 10) > 0);
	}

	@Test
	public void progressTracksTheClimbToTheNextStack()
	{
		TaskService s = service();
		Assert.assertEquals(0, s.comboProgressAt(0), 1e-9);
		int tick = 0;
		for (int banked = 1; banked < PER_STACK; banked++)
		{
			tick = banked * 10;
			s.advanceCombo(tick);
			Assert.assertEquals(banked / (double) PER_STACK, s.comboProgressAt(tick), 1e-9);
		}
		// the stack lands and the climb restarts from nothing
		tick = PER_STACK * 10;
		Assert.assertEquals(1, s.advanceCombo(tick));
		Assert.assertEquals(0, s.comboProgressAt(tick), 1e-9);
	}

	@Test
	public void noChainReadsZeroThroughout()
	{
		TaskService s = service();
		Assert.assertEquals(0, s.comboStacksAt(0));
		Assert.assertEquals(0, s.comboIdleTicksRemaining(0));
		Assert.assertEquals(0, s.comboProgressAt(0), 1e-9);
	}
}
