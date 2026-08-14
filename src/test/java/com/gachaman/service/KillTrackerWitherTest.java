package com.gachaman.service;

import org.junit.*;

/**
 * Pins the contract KillTracker.emit() now leans on lombok for.
 *
 * emit() used to spell the loot-oracle re-stamp out by hand:
 *
 *   assisted == draft.isAssistedByOther() ? draft : new Kill(...nine args...)
 *
 * and that hand-written form carried two guarantees the rest of the class
 * quietly depends on — (1) an unchanged verdict hands back the SAME instance,
 * so the common case allocates nothing on every kill, and (2) an overturned
 * verdict copies the other eight fields across untouched. Replacing it with
 * field-level {@code @With} moved both guarantees out of our source and into
 * generated code, where a lombok upgrade could change them without any diff
 * appearing in this repository. These assertions are the tripwire for that.
 */
public class KillTrackerWitherTest
{
	private static KillTracker.Kill draft(boolean assisted)
	{
		// deathLocation stays null: LocalPoint's constructor has moved between
		// RuneLite versions, and identity of the carried-over reference is what
		// this test cares about, which assertSame checks just as well on null.
		return new KillTracker.Kill("Goblin", 2, 17, 40, 33, true, 9, assisted, null);
	}

	@Test
	public void unchangedVerdictReturnsSameInstance()
	{
		KillTracker.Kill clean = draft(false);
		Assert.assertSame("clean verdict re-stamped clean must not reallocate",
			clean, clean.withAssistedByOther(false));

		KillTracker.Kill suspected = draft(true);
		Assert.assertSame("suspicion re-stamped suspicious must not reallocate",
			suspected, suspected.withAssistedByOther(true));
	}

	@Test
	public void overturnedVerdictCopiesEveryOtherField()
	{
		KillTracker.Kill suspected = draft(true);
		KillTracker.Kill exonerated = suspected.withAssistedByOther(false);

		Assert.assertNotSame(suspected, exonerated);
		Assert.assertTrue("the draft itself must be untouched", suspected.isAssistedByOther());
		Assert.assertFalse("the oracle's verdict must land", exonerated.isAssistedByOther());

		Assert.assertEquals("Goblin", exonerated.getNpcName());
		Assert.assertEquals(2, exonerated.getNpcCombatLevel());
		Assert.assertEquals(17, exonerated.getNpcIndex());
		Assert.assertEquals(40, exonerated.getTick());
		Assert.assertEquals(33, exonerated.getEngagementStartTick());
		Assert.assertTrue(exonerated.isTookDamageDuringEngagement());
		Assert.assertEquals(9, exonerated.getMaxHitDealt());
		Assert.assertSame(suspected.getDeathLocation(), exonerated.getDeathLocation());
	}
}
