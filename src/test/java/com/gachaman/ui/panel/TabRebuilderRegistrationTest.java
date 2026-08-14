package com.gachaman.ui.panel;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import com.gachaman.*;
import org.junit.*;

/**
 * Every {@code GachamanPanel.Tab} must have a rebuilder registered for it.
 *
 * <p>{@code rebuildIfDirty} used to be an eleven-arm switch, one arm per tab,
 * each arm the same statement with a different receiver. That shape is now an
 * {@code EnumMap<Tab, Runnable>} filled by {@code addCard} beside the card it
 * belongs to — smaller, and it cannot get a tab's rebuild call attached to the
 * wrong constant. What it does NOT do by itself is notice a tab nobody
 * registered: the old switch would silently fall through such a case and draw
 * nothing, and the map would hand back null, NPE inside the existing try, and
 * log a warning. Neither is a crash and neither is visible in review, so the
 * guarantee is pinned here instead.
 *
 * <p>Asserted against the SOURCE rather than a live panel because constructing
 * a GachamanPanel needs the whole Guice graph, a card database and an EDT —
 * none of which this invariant depends on. The registration is a lexical fact
 * about the constructor, so a lexical check is the honest one. Tests cost
 * nothing here: the Plugin Hub bot counts only {@code src/main/java}.
 */
public class TabRebuilderRegistrationTest
{
	@Test
	public void everyTabRegistersARebuilder() throws Exception
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);
		Path source = root.resolve("com/gachaman/ui/panel/GachamanPanel.java");
		Assert.assertTrue("missing " + source, Files.isRegularFile(source));
		String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

		// Tab is a private nested enum, so it cannot be named from here even in
		// the same package; reflection reads the constants without taking any
		// position on how the enum is written.
		Object[] tabs = Class.forName("com.gachaman.ui.panel.GachamanPanel$Tab")
			.getEnumConstants();
		Assert.assertNotNull("GachamanPanel.Tab is no longer an enum, or could not"
			+ " be loaded — this test cannot silently pass on that", tabs);
		Assert.assertTrue("GachamanPanel.Tab has no constants", tabs.length > 0);

		List<String> unregistered = new ArrayList<>();
		for (Object tab : tabs)
		{
			if (!text.contains("addCard(Tab." + tab + ","))
			{
				unregistered.add(tab.toString());
			}
		}
		Assert.assertEquals("every Tab needs an addCard(Tab.X, card, x::rebuild)"
			+ " call in the GachamanPanel constructor — without one the tab shows"
			+ " a card that never redraws and logs a warning instead",
			Collections.emptyList(), unregistered);

		// and no more registrations than there are tabs, so a stray or duplicated
		// addCard cannot hide here either
		int calls = 0;
		for (int at = text.indexOf("addCard(Tab."); at >= 0;
			at = text.indexOf("addCard(Tab.", at + 1))
		{
			calls++;
		}
		Assert.assertEquals("one addCard per Tab, no duplicates", tabs.length, calls);
	}
}
