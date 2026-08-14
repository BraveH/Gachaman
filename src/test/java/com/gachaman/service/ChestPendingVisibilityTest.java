package com.gachaman.service;

import java.lang.reflect.*;
import org.junit.*;

/**
 * Thread-visibility pins for ChestService's mutable reveal state.
 *
 * <p>These assert on modifiers rather than on behaviour, which is unusual for
 * this suite and needs justifying: a missing happens-before edge cannot be
 * pinned by running code. A test that spun up two threads and waited for a
 * stale read would pass on x86 whether or not the field is volatile (the
 * hardware rarely reorders these accesses) and would be flaky everywhere else.
 * The modifier IS the invariant, so the modifier is what gets asserted.
 *
 * <p>This is also the only reflection in the repository. It is confined to
 * src/test on purpose — CLAUDE.md forbids reflection in the plugin itself, and
 * src/test is neither shipped nor counted against the Plugin Hub token budget.
 */
public class ChestPendingVisibilityTest
{
	@Test
	public void pendingIsVolatileBecauseTheEdtReadsItWithoutTheLock() throws Exception
	{
		// ChestService writes `pending` only from synchronized methods, but it is
		// exposed through a plain Lombok @Getter and read off the Swing EDT by
		// ShopTab (lines 205 and 652) while the client thread writes it. Drop the
		// volatile and the EDT has no happens-before edge to those writes, so it
		// may cache a stale reference for the rest of the session — the shop tile
		// keeps insisting a reveal is in progress after the reveal has closed.
		Field pending = ChestService.class.getDeclaredField("pending");
		Assert.assertTrue(
			"ChestService.pending is read off the EDT through its @Getter while the "
				+ "client thread writes it under the lock; it must stay volatile or "
				+ "the EDT has no happens-before edge to those writes",
			Modifier.isVolatile(pending.getModifiers()));
	}

	@Test
	public void canRerollStaysSynchronisedSoTheRerollFlagNeedsNoVolatile()
		throws Exception
	{
		// The other half of the reasoning above, pinned so it cannot rot silently.
		// rerollUsedThisReveal is written under the lock and read ONLY here, so it
		// needs no volatile of its own — but that is true only while canReroll
		// keeps taking the lock. If someone drops `synchronized` from canReroll to
		// spare the render thread a monitor, the flag quietly acquires the exact
		// visibility hole this file exists to close, and this test says so.
		Method canReroll = ChestService.class.getDeclaredMethod("canReroll", int.class);
		Assert.assertTrue(
			"canReroll() must stay synchronized: it is the only reader of the "
				+ "non-volatile rerollUsedThisReveal field, so the lock is what "
				+ "makes that field's writes visible to it",
			Modifier.isSynchronized(canReroll.getModifiers()));
	}
}
