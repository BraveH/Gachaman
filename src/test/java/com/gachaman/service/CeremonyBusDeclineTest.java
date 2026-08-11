package com.gachaman.service;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * A renderer that declines a request must not lose it.
 *
 * <p>This is the contract the TASK_COMPLETE presentation depends on. That
 * ceremony no longer has a screen of its own — it is claimed as the generic
 * fanfare banner, and the renderer therefore DECLINES it whenever a banner is
 * already on screen rather than overwriting whichever celebration was
 * mid-flight.
 *
 * <p>That is only safe if a declined request stays queued and is re-presented
 * when the renderer next frees up. Nothing calls {@code setFallback}, so if the
 * queue instead dropped it — or stalled on it — a contract completion would
 * either vanish or block every ceremony behind it forever. Neither failure
 * raises anything; the player just stops seeing chests open.
 */
public class CeremonyBusDeclineTest
{
	/** Claims nothing while busy, everything otherwise — the overlay's shape. */
	private static class BusyRenderer implements CeremonyBus.Renderer
	{
		private final List<CeremonyBus.Type> presented = new ArrayList<>();
		private boolean busy;

		@Override
		public boolean present(CeremonyBus.Request request)
		{
			if (busy)
			{
				return false;
			}
			presented.add(request.getType());
			return true;
		}
	}

	@Test
	public void aDeclinedRequestIsRepresentedWhenTheRendererFreesUp()
	{
		CeremonyBus bus = new CeremonyBus();
		BusyRenderer renderer = new BusyRenderer();
		bus.addRenderer(renderer);

		renderer.busy = true;
		bus.submit(CeremonyBus.Type.TASK_COMPLETE, "summary");
		Assert.assertTrue("a busy renderer must not present", renderer.presented.isEmpty());

		renderer.busy = false;
		bus.drain();
		Assert.assertEquals("the declined request was dropped instead of re-presented",
			List.of(CeremonyBus.Type.TASK_COMPLETE), renderer.presented);
	}

	@Test
	public void aDeclinedRequestDoesNotBlockTheOnesBehindIt()
	{
		// the queue is FIFO and only drains while the head is claimable, so a
		// permanently-declined head would starve everything after it. Proven by
		// draining once free: both arrive, in order.
		CeremonyBus bus = new CeremonyBus();
		BusyRenderer renderer = new BusyRenderer();
		bus.addRenderer(renderer);

		renderer.busy = true;
		bus.submit(CeremonyBus.Type.TASK_COMPLETE, "summary");
		bus.submit(CeremonyBus.Type.STYLE_ROLL, "roll");

		renderer.busy = false;
		bus.drain(); // presents the head
		bus.drain(); // the overlay's own "I am free again" call
		Assert.assertEquals(List.of(CeremonyBus.Type.TASK_COMPLETE, CeremonyBus.Type.STYLE_ROLL),
			renderer.presented);
	}

	@Test
	public void tapsSeeEveryRequestEvenOneNobodyPresents()
	{
		// TimelineService records contract completions off a tap. If a tap only
		// fired for CLAIMED requests, declining while busy would silently drop
		// the completion out of the player's timeline.
		CeremonyBus bus = new CeremonyBus();
		BusyRenderer renderer = new BusyRenderer();
		bus.addRenderer(renderer);
		List<CeremonyBus.Type> tapped = new ArrayList<>();
		bus.addTap(request -> tapped.add(request.getType()));

		renderer.busy = true;
		bus.submit(CeremonyBus.Type.TASK_COMPLETE, "summary");

		Assert.assertEquals(List.of(CeremonyBus.Type.TASK_COMPLETE), tapped);
		Assert.assertTrue(renderer.presented.isEmpty());
	}
}
