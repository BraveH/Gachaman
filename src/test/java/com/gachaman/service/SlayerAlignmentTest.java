package com.gachaman.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The Double Docket matching rule. Everything here is pure static — the
 * Client/ConfigManager half of {@link SlayerAlignment} is deliberately a single
 * thin method so that this suite can cover the whole decision.
 */
public class SlayerAlignmentTest
{
	@Test
	public void normalizeStripsCaseAndPunctuationAndCollapsesSpaces()
	{
		assertEquals("fire giant", SlayerAlignment.normalize("Fire Giant"));
		assertEquals("kalphite worker", SlayerAlignment.normalize("  Kalphite   Worker  "));
		assertEquals("mounted terrorbird gnome", SlayerAlignment.normalize("Mounted terrorbird-gnome"));
		assertEquals("", SlayerAlignment.normalize(null));
		assertEquals("", SlayerAlignment.normalize("   "));
	}

	@Test
	public void noSlayerTaskMeansNoBonus()
	{
		// The whole feature must fail CLOSED: absent or blank inputs never pay.
		assertFalse(SlayerAlignment.matches("Fire giant", null));
		assertFalse(SlayerAlignment.matches("Fire giant", ""));
		assertFalse(SlayerAlignment.matches("Fire giant", "   "));
		assertFalse(SlayerAlignment.matches(null, "Fire giants"));
		assertFalse(SlayerAlignment.matches("", "Fire giants"));
		assertFalse(SlayerAlignment.matches(null, null));
	}

	@Test
	public void plainPluralAssignmentMatchesSingularContract()
	{
		assertTrue(SlayerAlignment.matches("Fire giant", "Fire giants"));
		assertTrue(SlayerAlignment.matches("Hill giant", "Hill giants"));
		assertTrue(SlayerAlignment.matches("Goblin", "Goblins"));
		assertTrue(SlayerAlignment.matches("Blue dragon", "Blue dragons"));
	}

	@Test
	public void irregularPluralAssignmentsMatch()
	{
		assertTrue(SlayerAlignment.matches("Jelly", "Jellies"));
		assertTrue(SlayerAlignment.matches("Elf warrior", "Elves"));
		assertTrue(SlayerAlignment.matches("Zombie", "Zombies"));
	}

	@Test
	public void alreadySingularAssignmentStillMatches()
	{
		// "Nechryael" has no distinct plural, so the untouched name must remain a
		// candidate or the whole assignment silently stops paying.
		assertTrue(SlayerAlignment.matches("Nechryael", "Nechryael"));
		assertTrue(SlayerAlignment.matches("Aberrant spectre", "Aberrant spectre"));
	}

	@Test
	public void broaderAssignmentCoversSpecificContract()
	{
		assertTrue(SlayerAlignment.matches("Kalphite Worker", "Kalphites"));
		assertTrue(SlayerAlignment.matches("Dust devil", "Dust devils"));
		assertTrue(SlayerAlignment.matches("Greater demon", "Greater demons"));
	}

	@Test
	public void prefixMatchRequiresAWordBoundary()
	{
		// The trailing space in the prefix test is load-bearing: without it these
		// would all pay a bonus for an unrelated monster.
		assertFalse(SlayerAlignment.matches("Ratcatcher", "Rats"));
		assertFalse(SlayerAlignment.matches("Hobgoblin", "Goblins"));
		assertFalse(SlayerAlignment.matches("Bandit", "Bandos"));
	}

	@Test
	public void prefixMatchIsOneDirectionalOnly()
	{
		// A NARROWER assignment does not cover a broader contract: being sent to
		// kill steel dragons is not being sent to kill every dragon.
		assertFalse(SlayerAlignment.matches("Dragon", "Steel dragons"));
		assertFalse(SlayerAlignment.matches("Demon", "Greater demons"));
	}

	@Test
	public void differentSpeciesNeverMatch()
	{
		assertFalse(SlayerAlignment.matches("Fire giant", "Blue dragons"));
		assertFalse(SlayerAlignment.matches("Goblin", "Abyssal demons"));
		assertFalse(SlayerAlignment.matches("Cow", "Chickens"));
	}

	@Test
	public void setValuedAssignmentsAreAKnownWithheldBonus()
	{
		// Documented gap, asserted so it is a CHOICE and not an accident: these
		// category names map to their members through the Slayer plugin's
		// package-private Task enum, which is unreachable from here. Failing
		// closed (no bonus) is the correct direction.
		assertFalse(SlayerAlignment.matches("Steel dragon", "Metal dragons"));
		assertFalse(SlayerAlignment.matches("Guard", "Bandits"));
	}

	@Test
	public void singularCandidatesAlwaysIncludeTheOriginal()
	{
		assertTrue(SlayerAlignment.singularCandidates("nechryael").contains("nechryael"));
		assertTrue(SlayerAlignment.singularCandidates("fire giants").contains("fire giant"));
		assertTrue(SlayerAlignment.singularCandidates("jellies").contains("jelly"));
		assertTrue(SlayerAlignment.singularCandidates("elves").contains("elf"));
		// Only the LAST word is singularised, so a plural head word survives.
		assertTrue(SlayerAlignment.singularCandidates("black demons").contains("black demon"));
		assertFalse(SlayerAlignment.singularCandidates("black demons").contains("black demons "));
	}
}
