package com.gachaman.overlay;

import com.gachaman.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/**
 * Every side effect RevealOverlay raises is raised from the client thread.
 *
 * <p>A source scan, on the {@link com.gachaman.ui.panel.PanelWordingScanTest}
 * precedent, and for the same reason: the sibling behavioural test
 * ({@code ConsignmentCeremonyTest.theAnswerIsOnlyEverGivenFromTheClientThread})
 * can only prove the hop for the one path it can drive. Driving a REROLL needs a
 * funded state, a live card database, a pending chest and a token to spend,
 * while the invariant is one line at each call site. Delete the hop from
 * {@code applyReroll} and every behavioural test in this package still passes —
 * which is precisely the gap this closes.
 *
 * <p>The reroll is the headline. {@code chestService.rerollSlot} rebuilds the
 * slot's pool through {@code poolFor} -> {@code isReachable}, which asks the
 * client for four real skill levels; raised from the AWT thread that delivered
 * the click, that is a read of game state racing the game thread writing it.
 *
 * <p>Scans also catch code that does not exist yet, which is the point: the next
 * person to add a service call to this overlay is who this is written for. Tests
 * cost no token budget.
 */
public class RevealHopScanTest
{
	/**
	 * Calls that must open a hop of their own, named in full rather than as a
	 * bare {@code .rerollSlot(} — a guard that fires on somebody else's correct
	 * code is a guard that gets deleted.
	 *
	 * <p>{@code runAction} is in this list because it IS the rest of them: the
	 * switch it carries commits chests, accepts contracts and answers
	 * Consignments, and it is private precisely so that the hop in
	 * {@code executeAction} is the only door into it. Pinning the door is
	 * stronger than pinning each arm, and it cannot drift as arms are added.
	 */
	private static final String[] MUST_HOP = {
		"chestService.rerollSlot(",
		"chestService.claimDeed(",
		"runAction(",
	};

	/**
	 * How far above the call the hop may open. Every one of them sits on the
	 * first line of its own lambda, so this is slack, not licence — a call that
	 * needed a wider window would be a call with logic ahead of it inside the
	 * lambda, and that logic is what the window is meant to make visible.
	 */
	private static final int WINDOW = 3;

	private static boolean isComment(String line)
	{
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	private static List<String> overlaySource() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);
		Path file = root.resolve("com").resolve("gachaman").resolve("overlay")
			.resolve("RevealOverlay.java");
		Assert.assertTrue("RevealOverlay.java is not at " + file, Files.isRegularFile(file));
		return Files.readAllLines(file, StandardCharsets.UTF_8);
	}

	@Test
	public void everyCallThatReachesTheClientIsMadeFromTheClientThread() throws IOException
	{
		List<String> lines = overlaySource();
		List<String> offenders = new ArrayList<>();
		int calls = 0;
		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			// the declaration of runAction is not a call to it
			if (isComment(line) || line.contains("private void runAction("))
			{
				continue;
			}
			boolean guarded = false;
			for (String call : MUST_HOP)
			{
				guarded |= line.contains(call);
			}
			if (!guarded)
			{
				continue;
			}
			calls++;
			boolean hopped = false;
			for (int back = i; back >= Math.max(0, i - WINDOW); back--)
			{
				hopped |= lines.get(back).contains("clientThread.invoke");
			}
			if (!hopped)
			{
				offenders.add("RevealOverlay.java:" + (i + 1) + "  " + line.trim());
			}
		}
		Assert.assertEquals("a ceremony side effect reaches live game state from the"
			+ " AWT thread the click arrived on", Collections.emptyList(), offenders);
		Assert.assertTrue("the guard is only real while these calls exist",
			calls >= MUST_HOP.length);
	}

	/**
	 * The teardown abandon is the deliberate exception, pinned so that
	 * "everything hops" cannot be tidied into being true.
	 *
	 * <p>{@code consignmentService.abandon()} clears the service's own live offer
	 * and logs. No game state, nothing submitted to the bus and so nothing
	 * drained — there is no Client beneath it. It runs in {@code reset()}, which
	 * shutDown calls on the AWT thread immediately before the bus is cleared, so
	 * hopping it would leave the last chance to release an unanswered offer
	 * queued against a plugin already being taken apart.
	 */
	@Test
	public void theTeardownAbandonIsDeliberatelyNotHopped() throws IOException
	{
		List<String> lines = overlaySource();
		int start = -1;
		for (int i = 0; i < lines.size(); i++)
		{
			if (lines.get(i).contains("public void reset()"))
			{
				start = i;
				break;
			}
		}
		Assert.assertTrue("reset() is gone from RevealOverlay", start >= 0);

		boolean abandoned = false;
		for (int i = start; i < lines.size(); i++)
		{
			// one tab and a brace: the end of the method, not of a block inside it
			if (i > start && lines.get(i).equals("\t}"))
			{
				break;
			}
			if (isComment(lines.get(i)))
			{
				continue;
			}
			Assert.assertFalse("RevealOverlay.java:" + (i + 1) + " the teardown abandon must"
					+ " stay synchronous; a deferred one never runs, see reset()",
				lines.get(i).contains("clientThread.invoke"));
			abandoned |= lines.get(i).contains("consignmentService.abandon()");
		}
		Assert.assertTrue("reset() no longer abandons the parked offer", abandoned);
	}
}
