package com.gachaman.service;

import com.gachaman.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import org.junit.*;

/**
 * The three invariants sixteen hand-written fan-out loops used to each carry
 * their own copy of, now pinned in one place.
 *
 * <p>They matter because of WHERE a fan-out sits: by the time one runs, the
 * state write and the GC award have already happened. A listener that throws
 * must not be able to swallow the notifications queued behind it, and a
 * listener that registers or drops another one mid-callback (the panel does
 * exactly this when a tab is rebuilt) must not blow up the caller.
 */
public class ListenersTest
{
	@Test
	public void oneThrowingListenerDoesNotStopTheOthers()
	{
		List<String> seen = new ArrayList<>();
		List<Consumer<String>> listeners = new ArrayList<>();
		listeners.add(seen::add);
		listeners.add(name ->
		{
			seen.add("boom");
			throw new IllegalStateException("deliberate");
		});
		listeners.add(seen::add);

		Listeners.fire(listeners, l -> l.accept("event"), "test listener failed");

		Assert.assertEquals(Arrays.asList("event", "boom", "event"), seen);
	}

	/**
	 * A listener that throws on EVERY call still leaves the loop running to the
	 * end — the catch is per listener, not once around the whole fan-out.
	 */
	@Test
	public void everyListenerThrowingStillVisitsThemAll()
	{
		int[] visits = {0};
		List<Runnable> listeners = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			listeners.add(() ->
			{
				visits[0]++;
				throw new RuntimeException("deliberate");
			});
		}

		Listeners.fire(listeners, Runnable::run, "test listener failed");

		Assert.assertEquals(4, visits[0]);
	}

	/**
	 * The defensive copy: registering and unregistering from inside a callback
	 * is legal and must not throw ConcurrentModificationException. The snapshot
	 * is taken up front, so the listener added during the fan-out is NOT called
	 * by it (it will be by the next one) and the one removed still is.
	 */
	@Test
	public void aListenerMayAddAndRemoveListenersFromInsideItsOwnCallback()
	{
		List<String> seen = new ArrayList<>();
		List<Runnable> listeners = new ArrayList<>();
		Runnable late = () -> seen.add("late");
		Runnable doomed = () -> seen.add("doomed");
		listeners.add(() ->
		{
			seen.add("first");
			listeners.add(late);
			listeners.remove(doomed);
		});
		listeners.add(doomed);

		Listeners.fire(listeners, Runnable::run, "test listener failed");

		Assert.assertEquals(Arrays.asList("first", "doomed"), seen);
		Assert.assertEquals(Arrays.asList(listeners.get(0), late), listeners);
	}

	/** Listeners are visited in registration order, like every loop this replaced. */
	@Test
	public void listenersAreVisitedInOrder()
	{
		List<Integer> seen = new ArrayList<>();
		List<Integer> listeners = Arrays.asList(1, 2, 3, 4, 5);

		Listeners.fire(listeners, seen::add, "test listener failed");

		Assert.assertEquals(listeners, seen);
	}

	/**
	 * Exception is caught; Error is not. A StackOverflowError or an OOM means
	 * the client is already in trouble and must not be quietly logged as "a
	 * listener failed" while the fan-out carries on.
	 */
	@Test
	public void errorsPropagateInsteadOfBeingLogged()
	{
		List<Runnable> listeners = new ArrayList<>();
		listeners.add(() ->
		{
			throw new StackOverflowError("deliberate");
		});
		listeners.add(() -> Assert.fail("fan-out continued past an Error"));

		try
		{
			Listeners.fire(listeners, Runnable::run, "test listener failed");
			Assert.fail("Error should have escaped the fan-out");
		}
		catch (StackOverflowError expected)
		{
			Assert.assertEquals("deliberate", expected.getMessage());
		}
	}

	/** An empty collection is a no-op, not an NPE. */
	@Test
	public void emptyCollectionIsANoOp()
	{
		Listeners.fire(Collections.<Runnable>emptyList(),
			l -> Assert.fail("nothing to notify"), "test listener failed");
	}

	private static final String CALL = "Listeners.fire(";

	/**
	 * A failure mode the shared helper introduced that the sixteen hand-written
	 * loops could not have: the label IS the SLF4J message pattern, and the
	 * exception binds to {@code warn(String, Throwable)} rather than filling a
	 * placeholder. A label written as {@code "x failed for {}"} would therefore
	 * print a literal {@code {}} in the client log with nothing substituted.
	 *
	 * <p>Scans the real main-source tree rather than testing a behaviour, because
	 * the mistake is one a future call site makes, not one this class can catch.
	 */
	@Test
	public void noFanOutLabelCarriesAnSlf4jPlaceholder() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);

		List<String> offenders = new ArrayList<>();
		int checked = 0;
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : tree.filter(p -> p.toString().endsWith(".java"))
				.collect(Collectors.toList()))
			{
				String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				for (int at = src.indexOf(CALL); at >= 0; at = src.indexOf(CALL, at + 1))
				{
					String args = balanced(src, at + CALL.length() - 1);
					String label = args == null ? null : lastArgument(args);
					// a label built at runtime cannot be read here; only literals
					// are checkable, and every site today passes one
					if (label == null || !label.startsWith("\"") || !label.endsWith("\""))
					{
						continue;
					}
					checked++;
					if (label.contains("{}"))
					{
						offenders.add(root.relativize(file) + ": " + label);
					}
				}
			}
		}

		// a scan that matched nothing would pass forever, exactly as it would in
		// SourceHygieneTest — the call sites are what this test is about
		Assert.assertTrue("found no " + CALL + " call sites under " + root, checked > 0);
		Assert.assertEquals("SLF4J placeholder in a listener fan-out label — the label is"
			+ " the message pattern and nothing fills it, so {} would be printed literally",
			Collections.emptyList(), offenders);
	}

	/** Text between {@code src[openParen]} and its matching close, or null. */
	private static String balanced(String src, int openParen)
	{
		int depth = 0;
		for (int i = openParen; i < src.length(); i++)
		{
			char c = src.charAt(i);
			if (c == '"' || c == '\'')
			{
				i = endOfLiteral(src, i);
			}
			else if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && --depth == 0)
			{
				return src.substring(openParen + 1, i);
			}
		}
		return null;
	}

	/** Index of the closing quote of the literal starting at {@code start}. */
	private static int endOfLiteral(String src, int start)
	{
		char quote = src.charAt(start);
		for (int i = start + 1; i < src.length(); i++)
		{
			if (src.charAt(i) == '\\')
			{
				i++;
			}
			else if (src.charAt(i) == quote)
			{
				return i;
			}
		}
		return src.length() - 1;
	}

	/**
	 * The last argument of an argument list — split on the last TOP-LEVEL comma,
	 * so the commas inside the action lambda's own call do not count.
	 */
	private static String lastArgument(String args)
	{
		int depth = 0;
		int lastComma = -1;
		for (int i = 0; i < args.length(); i++)
		{
			char c = args.charAt(i);
			if (c == '"' || c == '\'')
			{
				i = endOfLiteral(args, i);
			}
			else if (c == '(' || c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}')
			{
				depth--;
			}
			else if (c == ',' && depth == 0)
			{
				lastComma = i;
			}
		}
		return lastComma < 0 ? null : args.substring(lastComma + 1).trim();
	}
}
