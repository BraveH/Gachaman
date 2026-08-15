package com.gachaman.ui.panel;

import com.gachaman.service.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import net.runelite.client.callback.ClientThread;
import org.junit.*;

/**
 * The Chest Odds in-flight latch, against a client-thread body that throws.
 *
 * <p>ShopTab computes odds on the client thread because they read live skill
 * levels, and allows one flight at a time so a rebuild storm cannot queue a job per
 * rebuild. That flag is raised on the EDT before the job is posted and lowered by
 * the continuation the job posts back. If the job dies before it gets to post
 * anything, the flag is never lowered: every later request returns at the guard,
 * the panel is stuck on "Reading your levels…" for the rest of the session, and
 * nothing in the UI says why. The fix posts the continuation from a finally, so the
 * flag comes down whether the body returned or threw.
 *
 * <p>The scenario is reproduced honestly rather than simulated: chestService is
 * null, so the real {@code chestService.oddsFor(wanted)} call inside the real
 * client-thread body throws, exactly as a failure inside oddsFor would. Every other
 * collaborator is untouched on this path — the constructor only assigns fields, and
 * a throwing body never reaches the code that would use them.
 */
public class ShopOddsRequestTest
{
	/**
	 * Runs the job inline on the calling thread and counts the jobs handed over.
	 *
	 * <p>Overrides the Runnable overload deliberately: ClientThread also declares
	 * invokeLater(BooleanSupplier), and ShopTab's lambda has a void body, so it binds
	 * to this one. Overriding the other would silently observe nothing.
	 */
	private static final class InlineClientThread extends ClientThread
	{
		final AtomicInteger jobs = new AtomicInteger();

		@Override
		public void invokeLater(Runnable job)
		{
			jobs.incrementAndGet();
			job.run();
		}
	}

	@Test
	public void aThrowingOddsCallDoesNotLatchTheRequestFlagForever() throws Exception
	{
		InlineClientThread clientThread = new InlineClientThread();
		// a state service over a null store answers null from get(), which is the
		// logged-out case requestOdds already handles (it stamps -1). Every other
		// collaborator is null, chestService (the second argument) included — that
		// null IS the failure this test reproduces.
		ShopTab tab = new ShopTab(new GachaStateService(null), null, null, null, null, null,
			clientThread, null, null, null);

		Assert.assertTrue("the null chestService should have thrown inside the job",
			fireAndCatch(tab));
		drainEdt();
		Assert.assertEquals("the first request should have reached the client thread",
			1, clientThread.jobs.get());

		fireAndCatch(tab);
		drainEdt();
		// before the fix the continuation was posted only on success, so the flag was
		// still up here and this second request was swallowed at the guard
		Assert.assertEquals("a second request after a failed one must still be posted",
			2, clientThread.jobs.get());
	}

	@Test
	public void aFailedRequestDoesNotRebuildIntoAnotherRequest() throws Exception
	{
		InlineClientThread clientThread = new InlineClientThread();
		ShopTab tab = new ShopTab(new GachaStateService(null), null, null, null, null, null,
			clientThread, null, null, null);

		fireAndCatch(tab);
		drainEdt();

		// the failure path must clear the flag and stop. Rebuilding there would ask
		// buildOddsSection for a section with no snapshot, which requests again — a
		// tight loop between the client thread and the EDT. One job, not many, and the
		// panel is left exactly as it was rather than re-laid-out from a failure.
		Assert.assertEquals(1, clientThread.jobs.get());
		Assert.assertEquals("the failure path should not have rebuilt the panel",
			0, tab.getComponentCount());
	}

	/** Fires one request inline; returns whether the client-thread body threw. */
	private static boolean fireAndCatch(ShopTab tab)
	{
		try
		{
			tab.requestOdds();
			return false;
		}
		catch (NullPointerException expected)
		{
			// the throw is meant to keep travelling: RuneLite's own ClientThread logs
			// it, so the failure is recoverable without being silent
			return true;
		}
	}

	/** Blocks until everything already queued on the EDT has run. */
	private static void drainEdt() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}
}
