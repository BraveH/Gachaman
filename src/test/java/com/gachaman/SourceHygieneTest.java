package com.gachaman;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;

/**
 * Byte-level guards on the main source tree itself.
 *
 * <p>The one this exists for: a raw NUL (0x00) written directly into a string
 * literal instead of the {@code \0} escape. Both compile to the identical
 * character, so nothing at runtime can tell them apart — but the raw byte makes
 * {@code file} classify the source as "data", and makes grep and ripgrep treat
 * it as BINARY and skip it entirely unless invoked with {@code -a}. One such
 * byte in {@code OverviewTab.java} silently hid ~900 lines of live UI from every
 * repo-wide search, which very nearly got the party-roll proposal cards
 * (proposalGroups, PendingProposal, joinProposal, declineProposal) recommended
 * for deletion as dead code.
 *
 * <p>That failure mode is invisible to the compiler, invisible to the Plugin Hub
 * bot, and invisible in a diff. A test is the only thing that can see it, and
 * tests cost nothing here — the bot counts only src/main/java.
 */
public class SourceHygieneTest
{
	/**
	 * The main source root, found by walking up from the test working directory.
	 *
	 * <p>Gradle runs tests with the project directory as CWD, but a run launched
	 * from elsewhere (an IDE pointed at a module, a nested invocation) can start
	 * a level or two down, so this climbs rather than assuming. Returns null when
	 * it cannot be found, which callers MUST treat as a failure and never as a
	 * pass — a source scan that walks nothing is the classic silent green.
	 */
	public static Path sourceRoot()
	{
		Path dir = Paths.get("").toAbsolutePath();
		for (int up = 0; dir != null && up < 5; up++)
		{
			Path candidate = dir.resolve("src").resolve("main").resolve("java");
			if (Files.isDirectory(candidate))
			{
				return candidate;
			}
			dir = dir.getParent();
		}
		return null;
	}

	@Test
	public void noSourceFileCarriesARawNulByte() throws IOException
	{
		Path root = sourceRoot();
		Assert.assertNotNull("could not locate src/main/java from "
			+ Paths.get("").toAbsolutePath(), root);

		List<String> offenders = new ArrayList<>();
		int scanned = 0;
		try (Stream<Path> tree = Files.walk(root))
		{
			for (Path file : tree.filter(p -> p.toString().endsWith(".java"))
				.collect(Collectors.toList()))
			{
				scanned++;
				for (byte b : Files.readAllBytes(file))
				{
					if (b == 0)
					{
						offenders.add(root.relativize(file).toString());
						break;
					}
				}
			}
		}

		// the scan finding nothing is only good news if it actually looked at
		// something — an empty walk would otherwise pass forever
		Assert.assertTrue("scanned no .java files under " + root, scanned > 0);
		Assert.assertEquals("raw NUL byte in source — write it as the escape \\0"
			+ " instead, or grep/ripgrep will skip these files as binary",
			Collections.emptyList(), offenders);
	}
}
