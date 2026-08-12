package com.gachaman.service;

import com.gachaman.data.*;
import com.google.gson.*;
import org.junit.*;

/**
 * The manual unlock escape hatch, which exists for exactly one situation: a
 * quest needs a monster the bundled table does not list, and the player cannot
 * proceed until someone ships a fix.
 *
 * <p>Its argument is free text a stuck player types into the chatbox while
 * annoyed, so the matching has to be forgiving about case — "::gachaunlock rat"
 * has to work, or the escape hatch fails the one person it was built for.
 *
 * <p>Constructed with a null Client on purpose: overriding is pure bookkeeping
 * and must never need the game to answer. If a change makes these calls touch
 * the client, this test NPEs, and that is the correct alarm — a bypass that
 * only works when the client is in a good mood is not a bypass.
 */
public class QuestUnlockOverrideTest
{
	private static QuestExemptionService service()
	{
		return new QuestExemptionService(null, QuestMonsterTable.load(new Gson()));
	}

	@Test
	public void unlockingIsCaseInsensitiveAndIdempotent()
	{
		QuestExemptionService service = service();
		Assert.assertTrue("first unlock should report as new", service.unlock("Rat"));
		Assert.assertFalse("the same NPC in another case is the same NPC",
			service.unlock("rat"));
		Assert.assertFalse(service.unlock("  RAT  ".trim()));
		Assert.assertEquals("relock must find it whatever case was typed",
			1, service.relock("rAt"));
		Assert.assertEquals("and it is gone", 0, service.relock("Rat"));
	}

	/** With no name, the command clears everything and reports how many. */
	@Test
	public void relockWithoutANameClearsEveryOverrideAndCountsThem()
	{
		QuestExemptionService service = service();
		Assert.assertEquals("nothing to clear yet", 0, service.relock(null));
		service.unlock("Rat");
		service.unlock("Imp");
		service.unlock("Giant rat");
		Assert.assertEquals(3, service.relock(null));
		Assert.assertEquals("clearing twice must not double-count", 0, service.relock(null));
	}

	/** Re-blocking something that was never unlocked is a no-op, not an error. */
	@Test
	public void relockingAnUntouchedNpcReportsNothing()
	{
		Assert.assertEquals(0, service().relock("Elvarg"));
	}
}
