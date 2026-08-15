package com.gachaman.ui.panel;

import com.gachaman.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;

/**
 * Three rules about the sidebar's new text that no behavioural test can reach,
 * because each of them is about a mistake that compiles, runs, and looks right
 * until a player reads it.
 *
 * <p>A source scan rather than a rendering test, on the
 * {@link UnassignCallSiteTest} precedent: driving these lines means a live
 * album, a card database, a week key and a Swing hierarchy, while the invariant
 * itself is one line of text each. Scans also catch code that does not exist
 * yet, which is the point — the next person to add a weapon line to this
 * package is the one these are written for. Tests cost no token budget.
 */
public class PanelWordingScanTest
{
	private static List<Path> panelSources() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);
		Path panel = root.resolve("com").resolve("gachaman").resolve("ui");
		Assert.assertTrue("no ui package under " + root, Files.isDirectory(panel));
		try (Stream<Path> tree = Files.walk(panel))
		{
			List<Path> files = tree.filter(p -> p.toString().endsWith(".java"))
				.collect(Collectors.toList());
			// an empty walk is the classic silent green
			Assert.assertFalse("scanned no sources under " + panel, files.isEmpty());
			return files;
		}
	}

	/**
	 * Comments are exempt and MUST be, because the reason "unarmed" is banned
	 * from player-facing text can only be written down using the word: the key
	 * for category 0 really is "unarmed", and the javadoc that warns the next
	 * reader off it would otherwise fail this very test.
	 */
	private static boolean isComment(String line)
	{
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	/**
	 * The owner's rule: category 0 is reported for every non-weapon item a player
	 * might be holding, so calling it "unarmed" would be a claim about their
	 * hands the varbit cannot support. Its display name is "No weapon equipped",
	 * and a resource test already stops the JSON from saying otherwise — this is
	 * the same guard on the side that renders it.
	 */
	@Test
	public void noPanelStringEverSaysUnarmed() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		for (Path file : panelSources())
		{
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String line = lines.get(i);
				if (!isComment(line)
					&& line.toLowerCase(Locale.ROOT).contains("narmed"))
				{
					offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.trim());
				}
			}
		}
		Assert.assertEquals("player-facing text must say \"no weapon equipped\","
			+ " never \"unarmed\"", Collections.emptyList(), offenders);
	}

	/**
	 * The preferred weapon is persisted as a KEY and rendered as a NAME, and the
	 * two are deliberately different strings. Every read of the raw field must go
	 * straight into {@code WeaponTypeService.displayName}, which is also what
	 * makes a key this build no longer knows render as nothing rather than as
	 * itself.
	 */
	@Test
	public void thePersistedWeaponKeyIsOnlyEverResolvedToAName() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		int reads = 0;
		for (Path file : panelSources())
		{
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				String line = lines.get(i);
				if (isComment(line) || !line.contains("getPreferredWeaponType()"))
				{
					continue;
				}
				reads++;
				if (!line.contains("displayName("))
				{
					offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.trim());
				}
			}
		}
		Assert.assertEquals("a raw weapon-type key must never reach the screen",
			Collections.emptyList(), offenders);
		Assert.assertTrue("the guard is only real while somebody reads the field", reads > 0);
	}

	/**
	 * Paying the Toll rolls a chest, and rolling a chest reads live skill levels
	 * — client thread only. A Swing listener calling it directly would work on
	 * every machine right up until it did not, which is the failure this package
	 * already routes every other purchase around.
	 */
	@Test
	public void theTollIsOnlyEverPaidFromTheClientThread() throws IOException
	{
		List<String> offenders = new ArrayList<>();
		int calls = 0;
		for (Path file : panelSources())
		{
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				// tollService.purchase() by name, not a bare ".purchase()": the
				// weekly shop's purchase(index) is called straight from the EDT on
				// purpose, and the next zero-arg purchase somebody adds under ui/
				// might legitimately be too. A guard that fails on somebody else's
				// correct code is a guard that gets deleted.
				if (isComment(lines.get(i)) || !lines.get(i).contains("tollService.purchase()"))
				{
					continue;
				}
				calls++;
				boolean hopped = false;
				// the hop opens the lambda a line or two above the call
				for (int back = i; back >= Math.max(0, i - 4); back--)
				{
					hopped |= lines.get(back).contains("clientThread.invokeLater");
				}
				if (!hopped)
				{
					offenders.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).trim());
				}
			}
		}
		Assert.assertEquals("a chest roll off the client thread reads game state from the EDT",
			Collections.emptyList(), offenders);
		Assert.assertTrue("the guard is only real while somebody pays the Toll", calls > 0);
	}
}
