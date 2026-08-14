package com.gachaman.ui.panel;

import com.gachaman.*;
import org.junit.*;

/**
 * The Rusty tile's lifetime counter, and the sentinel it must never collide with.
 *
 * <p>ChestTile packs three meanings into one int: -1 means "this tier has no
 * lifetime cap", 0 means "retired", and a positive value prints as "N left". The
 * shop derives that number for Rusty by subtracting the persisted open count from
 * {@link Tuning#RUSTY_LIFETIME_CAP}, and the subtraction alone is not safe — a save
 * carrying more opens than the cap (an older build, a migration, a hand edit) lands
 * on exactly -1 and the retired chest comes back to life: ungreyed, unlabelled, and
 * clickable straight into a refusal that blames a busy reveal or a GC shortage.
 *
 * <p>So the invariant pinned here is not the arithmetic, it is the floor: this
 * method may never return a negative number, whatever the save says.
 */
public class ShopRustyCapTest
{
	@Test
	public void aFreshAccountHasTheWholeCapLeft()
	{
		Assert.assertEquals(Tuning.RUSTY_LIFETIME_CAP, ShopTab.rustyRemaining(0));
	}

	@Test
	public void eachOpenTakesOneOff()
	{
		Assert.assertEquals(Tuning.RUSTY_LIFETIME_CAP - 1, ShopTab.rustyRemaining(1));
		Assert.assertEquals(Tuning.RUSTY_LIFETIME_CAP - 2, ShopTab.rustyRemaining(2));
	}

	@Test
	public void spendingTheCapExactlyRetiresTheTile()
	{
		// 0 is "retired", which is what greys the tile and prints "Rusted away"
		Assert.assertEquals(0, ShopTab.rustyRemaining(Tuning.RUSTY_LIFETIME_CAP));
	}

	@Test
	public void aCountPastTheCapStaysRetiredInsteadOfReadingAsUncapped()
	{
		// the bug: CAP - (CAP + 1) is -1, which ChestTile reads as "no cap at all"
		Assert.assertEquals(0, ShopTab.rustyRemaining(Tuning.RUSTY_LIFETIME_CAP + 1));
		Assert.assertEquals(0, ShopTab.rustyRemaining(Tuning.RUSTY_LIFETIME_CAP + 2));
		Assert.assertEquals(0, ShopTab.rustyRemaining(99));
	}

	@Test
	public void theUncappedSentinelIsNeverReachableFromACount()
	{
		// -1 is reserved for the tiers that genuinely have no cap; the Rusty count
		// must never produce it, or the retired tile renders as an ordinary chest
		for (int opened = 0; opened < 500; opened++)
		{
			int remaining = ShopTab.rustyRemaining(opened);
			Assert.assertTrue("opened " + opened + " gave " + remaining, remaining >= 0);
			Assert.assertNotEquals("opened " + opened + " hit the uncapped sentinel",
				-1, remaining);
		}
	}
}
