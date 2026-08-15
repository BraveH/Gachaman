package com.gachaman.service;

import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Queue of ceremony/celebration requests emitted by game services and drained
 * by the overlay layer. Services never touch UI directly, so everything works
 * headless (requests fall back to chat lines when no renderer claims them).
 */
@Slf4j
@Singleton
public class CeremonyBus {
	public enum Type {
		CHEST_OPEN,      // payload: ChestOpenResult
		THEMED_CHEST,    // payload: ChestOpenResult (boss KC reward)
		STYLE_ROLL,      // payload: StyleRollResult
		TASK_OFFERS,     // payload: List<TaskOffer>
		TASK_COMPLETE,   // payload: TaskCompletionSummary
		DEED_CHOICE,     // payload: DeedGrant
		/**
		 * payload: {@link ConsignmentService.Offer} — the Consignment, and the
		 * only request on this bus that carries an OBLIGATION rather than a
		 * reward.
		 *
		 * <p>Everything else queued here is something the player has already
		 * been given: dropping a fanfare costs a banner, dropping a chest reveal
		 * still commits the cards. This one is a question with a deadline on it,
		 * and whoever claims it has promised {@code ConsignmentService} that
		 * exactly one of {@code accept} / {@code decline} / {@code abandon} will
		 * follow. Claiming it and then merely forgetting it leaves the style roll
		 * owed until the next login drain settles it.
		 *
		 * <p>Which is why this type must never be handed to a
		 * {@link FallbackHandler}: a chat line cannot answer a binding choice.
		 * Nothing registers a fallback today (see CeremonyBusDeclineTest), so an
		 * unclaimed offer parks in the queue and re-presents when the renderer
		 * frees up — which is exactly what has to happen, because a contract
		 * completion queues TASK_COMPLETE and often DEED_CHOICE ahead of it.
		 */
		CONSIGNMENT,
		FANFARE          // payload: Fanfare (generic icon+text celebration)
	}

	@Value
	public static class Request {
		Type type;
		Object payload;
	}

	@Value
	public static class Fanfare {
		public enum Size {
			SMALL, MEDIUM, LARGE
		}

		Size size;
		String title;
		String detail;
		@Nullable
		Integer iconItemId;
	}

	public interface Renderer {
		/** Return true when the request was claimed for animated presentation. */
		boolean present(Request request);
	}

	public interface FallbackHandler {
		/** Headless presentation (chat message) when no renderer claims it. */
		void presentFallback(Request request);
	}

	private final Deque<Request> queue = new ArrayDeque<>();
	private final List<Renderer> renderers = new ArrayList<>();
	/** Passive observers notified of every submit (audit/timeline). */
	private final List<Consumer<Request>> taps = new ArrayList<>();
	private FallbackHandler fallback;

	public synchronized void addTap(Consumer<Request> tap) {
		if (!taps.contains(tap))
			taps.add(tap);
	}

	public synchronized void removeTap(Consumer<Request> tap) {
		taps.remove(tap);
	}

	public synchronized void setFallback(FallbackHandler handler) {
		this.fallback = handler;
	}

	public synchronized void addRenderer(Renderer renderer) {
		renderers.add(renderer);
	}

	public synchronized void removeRenderer(Renderer renderer) {
		renderers.remove(renderer);
	}

	/** Drop everything still queued (plugin shutdown — renderer is being torn down). */
	public synchronized void clear() {
		queue.clear();
	}

	public void submit(Type type, Object payload) {
		// taps run OUTSIDE the bus monitor: they mutate game state, and holding
		// this lock across a state mutation would nest monitors across threads
		Request request = new Request(type, payload);
		List<Consumer<Request>> tapsCopy;
		synchronized (this) {
			tapsCopy = taps.isEmpty() ? List.of() : new ArrayList<>(taps);
		}
		// the snapshot stays HERE rather than inside Listeners.fire: that helper
		// copies for iteration safety, but this copy also has to happen under the
		// bus monitor, which the helper knows nothing about
		Listeners.fire(tapsCopy, tap -> tap.accept(request), "ceremony tap failed");
		enqueue(type, payload);
	}

	private synchronized void enqueue(Type type, Object payload) {
		// Coalesce SMALL fanfare floods (early game fires many): once two SMALLs
		// are already waiting, further SMALLs merge into the last one instead of
		// stacking seconds of banners. MEDIUM/LARGE are rare and never coalesce.
		if (type == Type.FANFARE && payload instanceof Fanfare
			&& ((Fanfare) payload).getSize() == Fanfare.Size.SMALL) {
			Request lastSmall = null;
			int queuedSmall = 0;
			for (Request queued : queue) {
				if (queued.getType() == Type.FANFARE && queued.getPayload() instanceof Fanfare
					&& ((Fanfare) queued.getPayload()).getSize() == Fanfare.Size.SMALL) {
					queuedSmall++;
					lastSmall = queued;
				}
			}
			if (queuedSmall >= 2 && lastSmall != null) {
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
	public synchronized void drain() {
		while (!queue.isEmpty()) {
			Request request = queue.peek();
			boolean claimed = false;
			for (Renderer renderer : renderers) {
				try {
					if (renderer.present(request)) {
						claimed = true;
						break;
					}
				}
				catch (Exception e) {
					log.warn("ceremony renderer failed", e);
				}
			}
			if (claimed) {
				queue.poll();
				return; // renderer presents one at a time; it calls drain() when done
			}
			if (fallback != null) {
				queue.poll();
				try {
					fallback.presentFallback(request);
				}
				catch (Exception e) {
					log.warn("ceremony fallback failed", e);
				}
			}
			else {
				return; // nothing can present yet; keep queued
			}
		}
	}
}
