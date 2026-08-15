package com.gachaman.service;

import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Owner of the volatile state snapshot. Every mutation is a synchronized
 * pure-function application that persists the result and notifies listeners.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GachaStateService {
	public interface Listener {
		void onStateChanged(GachaState newState);
	}

	private final StateStore store;
	private final List<Listener> listeners = new ArrayList<>();

	private volatile GachaState state;
	@Getter
	private volatile boolean loaded;

	@Nullable
	public GachaState get() {
		return state;
	}


	/** Load for the current RS profile; creates fresh state when none exists. */
	public synchronized void load(int combatLevel) {
		GachaState s = store.load();
		if (s == null) {
			s = GachaState.fresh(combatLevel);
			log.info("Gachaman: fresh profile state created (cb {})", combatLevel);
		}
		else {
			// older saves: fields added since deserialize as null
			s = s.normalized();
		}
		state = s;
		loaded = true;
		notifyListeners(s);
	}

	public synchronized void unload() {
		if (loaded && state != null)
			store.save(state, true);
		state = null;
		loaded = false;
	}

	/**
	 * Drop the in-memory state WITHOUT saving. Used on RS profile switches:
	 * RuneScapeProfileChanged fires after the profile key has already moved,
	 * so saving here would write the old account's state under the new key.
	 */
	public synchronized void discard() {
		state = null;
		loaded = false;
	}

	/** Apply a pure mutation, persist, notify. No-op when not loaded. */
	public synchronized GachaState mutate(UnaryOperator<GachaState> fn) {
		if (!loaded || state == null)
			return null;
		GachaState next = fn.apply(state);
		if (next == null || next == state)
			return state;
		state = next;
		store.save(next);
		notifyListeners(next);
		return next;
	}

	/** Force a save of the current snapshot (logout/shutdown checkpoints). */
	public synchronized void checkpoint() {
		if (loaded && state != null)
			store.save(state, true);
	}

	public synchronized void addListener(Listener l) {
		listeners.add(l);
	}

	public synchronized void removeListener(Listener l) {
		listeners.remove(l);
	}

	private void notifyListeners(GachaState s) {
		Listeners.fire(listeners, l -> l.onStateChanged(s), "Gachaman state listener failed");
	}
}
