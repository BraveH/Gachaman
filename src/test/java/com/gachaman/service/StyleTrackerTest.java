package com.gachaman.service;

import com.gachaman.model.AttackStyle;
import org.junit.Assert;
import org.junit.Test;

public class StyleTrackerTest
{
	@Test
	public void styleNamesMapCorrectly()
	{
		Assert.assertEquals(AttackStyle.MELEE, StyleTracker.mapStyleName("Accurate"));
		Assert.assertEquals(AttackStyle.MELEE, StyleTracker.mapStyleName("Aggressive"));
		Assert.assertEquals(AttackStyle.MELEE, StyleTracker.mapStyleName("Defensive"));
		Assert.assertEquals(AttackStyle.MELEE, StyleTracker.mapStyleName("Controlled"));
		Assert.assertEquals(AttackStyle.RANGED, StyleTracker.mapStyleName("Ranging"));
		Assert.assertEquals(AttackStyle.RANGED, StyleTracker.mapStyleName("Longrange"));
		Assert.assertEquals(AttackStyle.MAGIC, StyleTracker.mapStyleName("Casting"));
		Assert.assertEquals(AttackStyle.MAGIC, StyleTracker.mapStyleName("Defensive Casting"));
		Assert.assertNull(StyleTracker.mapStyleName("Unknown"));
	}

	@Test
	public void autocastModeIsAlwaysMagic()
	{
		// com mode 4 = autocast slot regardless of weapon category
		Assert.assertEquals(AttackStyle.MAGIC,
			StyleTracker.resolve(1, 4, false, (c, s) -> "Accurate"));
		Assert.assertEquals(AttackStyle.MAGIC,
			StyleTracker.resolve(24, 4, true, (c, s) -> null));
	}

	@Test
	public void specialCasedCategoriesFallBackToMelee()
	{
		Assert.assertEquals(AttackStyle.MELEE,
			StyleTracker.resolve(22, 0, false, (c, s) -> null));
		Assert.assertEquals(AttackStyle.MELEE,
			StyleTracker.resolve(30, 1, false, (c, s) -> null));
	}

	@Test
	public void unresolvableIsNullNeverGuessed()
	{
		Assert.assertNull(StyleTracker.resolve(5, 1, false, (c, s) -> null));
		Assert.assertNull(StyleTracker.resolve(5, 1, false, (c, s) -> "Garbage"));
	}

	@Test
	public void normalResolutionUsesLookup()
	{
		Assert.assertEquals(AttackStyle.RANGED,
			StyleTracker.resolve(3, 1, false, (c, s) -> "Ranging"));
		Assert.assertEquals(AttackStyle.MAGIC,
			StyleTracker.resolve(18, 3, false, (c, s) -> "Defensive Casting"));
	}

	@Test
	public void poweredStavesResolveMagicInEveryComMode()
	{
		// powered staves (trident, sanguinesti...) fight from comMode 0/1/3,
		// never the autocast slot; style 0 of such a category is "Casting".
		// Without the style-0 probe, comMode 3 here would resolve MELEE.
		StyleTracker.StyleNameLookup poweredStave = (c, s) -> s == 0 ? "Casting" : "Defensive";
		Assert.assertEquals(AttackStyle.MAGIC, StyleTracker.resolve(24, 0, false, poweredStave));
		Assert.assertEquals(AttackStyle.MAGIC, StyleTracker.resolve(24, 1, false, poweredStave));
		Assert.assertEquals(AttackStyle.MAGIC, StyleTracker.resolve(24, 3, false, poweredStave));
		// a melee weapon's style 0 is not "Casting" — probe must not touch it
		Assert.assertEquals(AttackStyle.MELEE,
			StyleTracker.resolve(6, 1, false, (c, s) -> "Aggressive"));
	}

	@Test
	public void onlyUnconfirmedStanceVerdictsArePardonable()
	{
		// the happy path: stance said melee, magic xp landed 3 ticks later,
		// nothing confirmed the melee verdict
		Assert.assertTrue(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 103, -1, -100));
		Assert.assertTrue(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.RANGED, false, 100, 103, -1, -100));
		// MARK/ANIM verdicts are already magic; XP verdicts are ground truth
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.MARK,
			AttackStyle.MELEE, false, 100, 103, -1, -100));
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.ANIM,
			AttackStyle.MELEE, false, 100, 103, -1, -100));
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.XP,
			AttackStyle.MELEE, false, 100, 103, -1, -100));
		// a magic verdict needs no pardon
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MAGIC, false, 100, 103, -1, -100));
	}

	@Test
	public void pardonBlockedByWindowConfirmationAndUtilityCasts()
	{
		// outside the pardon window
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 106, -1, -100));
		// already pardoned once
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, true, 100, 103, -1, -100));
		// same-tick melee xp confirms a genuine melee hit (auto-retaliate
		// staff bash that landed) — never pardoned
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 103, 100, -100));
		// magic xp right after an alch/teleport proves nothing about combat
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 103, -1, 103));
		Assert.assertFalse(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 103, -1, 102));
		// melee xp from BEFORE the verdict does not confirm it
		Assert.assertTrue(StyleTracker.shouldPardon(StyleTracker.JudgementSource.STANCE,
			AttackStyle.MELEE, false, 100, 103, 99, -100));
	}
}
