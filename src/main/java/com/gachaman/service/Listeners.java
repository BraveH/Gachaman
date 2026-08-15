package com.gachaman.service;

import java.util.*;
import java.util.function.*;
import lombok.extern.slf4j.*;

/**
 * The one listener fan-out loop, shared by every service that has listeners.
 *
 * <p>Sixteen sites used to spell this out by hand — copy the list, loop, try,
 * catch, warn — and every one of them had to get all three of the following
 * right independently. They are the reason this class exists, so none of them
 * may be "simplified" away:
 *
 * <ul>
 * <li><b>The defensive copy</b> is what lets a listener add or drop another one
 * from inside its own callback without a ConcurrentModificationException. It is
 * cheap insurance: these lists hold a handful of entries and the fan-out is not
 * on any hot loop.</li>
 * <li><b>The per-listener catch</b> is what stops one broken listener from
 * stranding the caller half-done. By the time a fan-out runs, the state write
 * and the GC award have usually already happened, so an exception escaping here
 * would lose every remaining notification AND the caller's return value with
 * nothing rolled back.</li>
 * <li><b>Catching Exception, not Throwable</b>, so an Error still propagates.</li>
 * </ul>
 *
 * <p>The label is the caller's own warning text, passed through verbatim rather
 * than derived, because it is the only thing in the log line that says WHICH
 * fan-out failed — this class is the logger for all of them. Labels must stay
 * free of SLF4J {@code {}} placeholders: the label is the message pattern, and
 * the exception is bound to the throwable overload, not to a placeholder.
 */
@Slf4j
final class Listeners {
	private Listeners() {
	}

	/**
	 * Hand {@code action} to every listener, in order, surviving throwers.
	 *
	 * <p>{@code Consumer<? super T>} rather than {@code Consumer<T>} so a
	 * handler written against a supertype (or a bare {@code Runnable::run}
	 * style reference) still binds without a cast at the call site.
	 */
	static <T> void fire(Collection<T> listeners, Consumer<? super T> action, String label) {
		for (T listener : new ArrayList<>(listeners)) {
			try {
				action.accept(listener);
			}
			catch (Exception e) {
				log.warn(label, e);
			}
		}
	}

	/**
	 * The same contract for a single OPTIONAL hook — the {@code @Setter} fields
	 * the plugin wires and the tests deliberately leave null.
	 *
	 * <p>Named differently rather than overloaded: a {@code List<T>} argument
	 * satisfies BOTH signatures (T binds to the list itself), so an overload
	 * here makes every collection call site ambiguous and the compiler refuses
	 * the whole package.
	 *
	 * <p>Separate from the collection overload rather than wrapping the hook in
	 * a singleton list: that would allocate on every call, and these fire on the
	 * kill and contract paths. The null check belongs here because every one of
	 * the five call sites had to remember it, and a forgotten one is an NPE on
	 * the hottest path in the plugin rather than a quiet no-op.
	 */
	static <T> void fireHook(T hook, Consumer<? super T> action, String label) {
		if (hook == null)
			return;
		try {
			action.accept(hook);
		}
		catch (Exception e) {
			log.warn(label, e);
		}
	}
}
