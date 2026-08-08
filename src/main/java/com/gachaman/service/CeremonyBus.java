package com.gachaman.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Queue of ceremony/celebration requests emitted by game services and drained
 * by the overlay layer. Services never touch UI directly, so everything works
 * headless (requests fall back to chat lines when no renderer claims them).
 */
@Slf4j
@Singleton
public class CeremonyBus
{
	public enum Type
	{
		CHEST_OPEN,      // payload: ChestOpenResult
		THEMED_CHEST,    // payload: ChestOpenResult (boss KC reward)
		STYLE_ROLL,      // payload: StyleRollResult
		TASK_OFFERS,     // payload: List<TaskOffer>
		TASK_COMPLETE,   // payload: TaskCompletionSummary
		DEED_CHOICE,     // payload: DeedGrant
		FANFARE          // payload: Fanfare (generic icon+text celebration)
	}

	@Value
	public static class Request
	{
		Type type;
		Object payload;
	}

	@Value
	public static class Fanfare
	{
		public enum Size
		{
			SMALL, MEDIUM, LARGE
		}

		Size size;
		String title;
		String detail;
		@Nullable
		Integer iconItemId;
	}

	public interface Renderer
	{
		/** Return true when the request was claimed for animated presentation. */
		boolean present(Request request);
	}

	public interface FallbackHandler
	{
		/** Headless presentation (chat message) when no renderer claims it. */
		void presentFallback(Request request);
	}

	private final Deque<Request> queue = new ArrayDeque<>();
	private final List<Renderer> renderers = new ArrayList<>();
	/** Passive observers notified of every submit (audit/timeline). */
	private final List<java.util.function.Consumer<Request>> taps = new ArrayList<>();
	private FallbackHandler fallback;

	public synchronized void addTap(java.util.function.Consumer<Request> tap)
	{
		if (!taps.contains(tap))
		{
			taps.add(tap);
		}
	}

	public synchronized void removeTap(java.util.function.Consumer<Request> tap)
	{
		taps.remove(tap);
	}

	public synchronized void setFallback(FallbackHandler handler)
	{
		this.fallback = handler;
	}

	public synchronized void addRenderer(Renderer renderer)
	{
		renderers.add(renderer);
	}

	public synchronized void removeRenderer(Renderer renderer)
	{
		renderers.remove(renderer);
	}

	/** Drop everything still queued (plugin shutdown — renderer is being torn down). */
	public synchronized void clear()
	{
		queue.clear();
	}

	public void submit(Type type, Object payload)
	{
		// taps run OUTSIDE the bus monitor: they mutate game state, and holding
		// this lock across a state mutation would nest monitors across threads
		Request request = new Request(type, payload);
		List<java.util.function.Consumer<Request>> tapsCopy;
		synchronized (this)
		{
			tapsCopy = taps.isEmpty() ? List.of() : new ArrayList<>(taps);
		}
		for (java.util.function.Consumer<Request> tap : tapsCopy)
		{
			try
			{
				tap.accept(request);
			}
			catch (Exception e)
			{
				log.warn("ceremony tap failed", e);
			}
		}
		enqueue(type, payload);
	}

	private synchronized void enqueue(Type type, Object payload)
	{
		// Coalesce SMALL fanfare floods (early game fires many): once two SMALLs
		// are already waiting, further SMALLs merge into the last one instead of
		// stacking seconds of banners. MEDIUM/LARGE are rare and never coalesce.
		if (type == Type.FANFARE && payload instanceof Fanfare
			&& ((Fanfare) payload).getSize() == Fanfare.Size.SMALL)
		{
			Request lastSmall = null;
			int queuedSmall = 0;
			for (Request queued : queue)
			{
				if (queued.getType() == Type.FANFARE && queued.getPayload() instanceof Fanfare
					&& ((Fanfare) queued.getPayload()).getSize() == Fanfare.Size.SMALL)
				{
					queuedSmall++;
					lastSmall = queued;
				}
			}
			if (queuedSmall >= 2 && lastSmall != null)
			{
				Fanfare last = (Fanfare) lastSmall.getPayload();
				queue.remove(lastSmall);
				queue.add(new Request(Type.FANFARE, new Fanfare(Fanfare.Size.SMALL,
					last.getTitle(), "…and more", null)));
				drain();
				return;
			}
		}
		queue.add(new Request(type, payload));
		drain();
	}

	/** Called by the overlay layer when it becomes free to present the next request. */
	public synchronized void drain()
	{
		while (!queue.isEmpty())
		{
			Request request = queue.peek();
			boolean claimed = false;
			for (Renderer renderer : renderers)
			{
				try
				{
					if (renderer.present(request))
					{
						claimed = true;
						break;
					}
				}
				catch (Exception e)
				{
					log.warn("ceremony renderer failed", e);
				}
			}
			if (claimed)
			{
				queue.poll();
				return; // renderer presents one at a time; it calls drain() when done
			}
			if (fallback != null)
			{
				queue.poll();
				try
				{
					fallback.presentFallback(request);
				}
				catch (Exception e)
				{
					log.warn("ceremony fallback failed", e);
				}
			}
			else
			{
				return; // nothing can present yet; keep queued
			}
		}
	}
}
