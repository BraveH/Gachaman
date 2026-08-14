package com.gachaman.ui.panel;

import java.lang.reflect.*;
import org.junit.*;

/**
 * The scan-progress poll timer crosses a thread boundary, and nothing about the
 * code says so at the point of use.
 *
 * <p>{@code scanTimer} is only ever WRITTEN on the EDT — {@code ensureScanTimer}
 * is reached from {@code onActivate} and from {@code refreshNow}, both Swing
 * callbacks. It is READ from the client thread as well: {@code GachamanPlugin}'s
 * {@code shutDown} calls {@code GachamanPanel.stop()}, which calls
 * {@code stopScanTimer()}. Without a happens-before edge between those two
 * threads the client thread is entitled to see the field's initial null and
 * quietly skip stopping a running 400ms poll.
 *
 * <p>The failure that causes is invisible — a stopped plugin whose timer keeps
 * ticking against a panel nobody is looking at — and it is self-healing on the
 * usual path, because {@code clientToolbar.removeNavigation} two lines later
 * drives {@code onDeactivate} on the EDT and stops it there. That combination
 * (silent, intermittent, usually masked) is exactly what makes the modifier
 * likely to be dropped by a later edit and never noticed, so it is asserted
 * here rather than trusted to the reader.
 *
 * <p>Asserted through reflection on the modifiers rather than by exercising the
 * race: a data race cannot be observed reliably from a test, and the modifier
 * IS the fix. The field's type is deliberately never named — {@code Timer} is
 * one of this repo's documented import collisions ({@code javax.swing} vs
 * {@code java.util}) and this test has no reason to take a position on it.
 */
public class ScanTimerPublicationTest
{
	@Test
	public void theScanTimerIsSafelyPublishedToTheClientThread() throws NoSuchFieldException
	{
		Field field = GachamanPanel.class.getDeclaredField("scanTimer");
		Assert.assertTrue(
			"GachamanPanel.scanTimer must stay volatile: GachamanPlugin.shutDown reads it "
				+ "from the client thread via stop() -> stopScanTimer(), while every write "
				+ "happens on the EDT",
			Modifier.isVolatile(field.getModifiers()));
	}
}
