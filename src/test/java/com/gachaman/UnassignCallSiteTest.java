package com.gachaman;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;

/**
 * Every caller of {@code LoadoutService.unassign} must read its answer.
 *
 * <p>The guard's failure mode is not a crash, it is a DEAD CLICK: unassign
 * refuses, the caller drops the boolean on the floor, and the card sits there
 * with the player none the wiser. The in-game board is the one that would hurt
 * — before this change it discarded the result and had no feedback path at all
 * — so the regression worth pinning is "somebody added a call site and forgot".
 *
 * <p>A source scan rather than a behavioural test, deliberately. Driving the
 * real call sites means constructing a JPanel with six injected collaborators
 * and an Overlay that needs a Client, a SpriteManager and a ClientThread; a
 * scan states the invariant directly, catches call sites that do not exist
 * yet, and costs nothing — the Hub bot counts only src/main/java.
 */
public class UnassignCallSiteTest
{
	/**
	 * How a call site is allowed to consume the boolean: branch on the
	 * refusal, hand it upward, or capture it in a named local.
	 *
	 * <p>Deliberately narrow. A bare {@code if (} would match any call that
	 * merely SITS inside an unrelated conditional — which is exactly the shape
	 * the old overlay code had — and a bare {@code =} would match assignments
	 * that never look at what they stored. Both would pass the three real call
	 * sites and catch nothing, which is the worst thing a guard test can be.
	 */
	private static boolean readsTheResult(String line)
	{
		String trimmed = line.trim();
		String before = trimmed.substring(0, trimmed.indexOf(".unassign("));
		return before.contains("if (!") || before.contains("return ")
			|| before.contains("boolean ");
	}

	@Test
	public void everyUnassignCallSiteReadsTheRefusal() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);

		List<String> offenders = new ArrayList<>();
		Set<String> callers = new TreeSet<>();
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : tree.filter(p -> p.toString().endsWith(".java"))
				.collect(Collectors.toList()))
			{
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++)
				{
					if (!lines.get(i).contains(".unassign("))
					{
						continue;
					}
					callers.add(root.relativize(file).toString());
					if (!readsTheResult(lines.get(i)))
					{
						offenders.add(root.relativize(file) + ":" + (i + 1)
							+ "  " + lines.get(i).trim());
					}
				}
			}
		}

		Assert.assertEquals("a call site that ignores unassign()'s boolean is a click that"
			+ " refuses silently — branch on it and tell the player why",
			Collections.emptyList(), offenders);
		// a scan that found nothing would pass forever; the guard is only real
		// while somebody is actually calling the guarded method
		Assert.assertFalse("found no unassign() call sites at all under " + root,
			callers.isEmpty());
	}

	/**
	 * Both of the sidebar's unassign routes — the right-click menu item and
	 * the picker's "Unassign" row — go through the one helper that raises the
	 * dialog, so neither can be the one that swallows a refusal.
	 */
	@Test
	public void theSidebarRoutesBothOfItsUnassignsThroughTheExplainingHelper() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull(root);
		String source = new String(Files.readAllBytes(
			root.resolve("com/gachaman/ui/panel/LoadoutTab.java")), StandardCharsets.UTF_8);

		int routed = 0;
		for (int at = source.indexOf("unassignOrExplain("); at >= 0;
			at = source.indexOf("unassignOrExplain(", at + 1))
		{
			routed++;
		}
		// two call sites plus the declaration
		Assert.assertEquals("LoadoutTab must route both unassign routes through the helper",
			3, routed);
		Assert.assertTrue("the helper is what puts the refusal in front of the player",
			source.contains("GachamanPanel.info(this, \"That card is still unlocking"));
	}

	/** The board has no text of its own, so its refusal has to show on the board. */
	@Test
	public void theInGameBoardShowsSomethingWhenItIsRefused() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull(root);
		String source = new String(Files.readAllBytes(
			root.resolve("com/gachaman/ui/loadout/LoadoutOverlay.java")), StandardCharsets.UTF_8);

		Assert.assertTrue("the overlay must branch on the refusal",
			source.contains("if (!loadoutService.unassign("));
		Assert.assertTrue("...and mark the socket, or the click looks like a miss",
			source.contains("refusedSlot = slot;"));
	}
}
