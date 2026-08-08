package com.gachaman.model;

import com.gachaman.Tuning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Dossier's capped-append rule and its clean verdict. Pure — no client, no
 * state service, no Swing.
 */
public class ContractRecordTest
{
	private static ContractRecord record(long at, int taintedKills)
	{
		return new ContractRecord(at, "Goblin", "EASY", 15, 400, 60_000, "MELEE",
			taintedKills, null, false, false);
	}

	// --- A. the cap ---

	@Test
	public void appendingToNullStartsTheLog()
	{
		// a save written before the Dossier existed reaches this with a null list
		// (normalized() only runs at LOAD, and a mutate lambda can see a state
		// object that never went through it)
		List<ContractRecord> log = ContractRecord.appendCapped(null, record(1L, 0), 200);
		Assert.assertEquals(1, log.size());
		Assert.assertEquals(1L, log.get(0).getAt());
	}

	@Test
	public void appendsInCompletionOrder()
	{
		List<ContractRecord> log = null;
		for (int i = 1; i <= 3; i++)
		{
			log = ContractRecord.appendCapped(log, record(i, 0), 200);
		}
		Assert.assertEquals(Arrays.asList(1L, 2L, 3L),
			Arrays.asList(log.get(0).getAt(), log.get(1).getAt(), log.get(2).getAt()));
	}

	@Test
	public void oldestDropsPastTheCap()
	{
		List<ContractRecord> log = null;
		for (int i = 1; i <= Tuning.DOSSIER_MAX_RECORDS + 25; i++)
		{
			log = ContractRecord.appendCapped(log, record(i, 0), Tuning.DOSSIER_MAX_RECORDS);
		}
		Assert.assertEquals(Tuning.DOSSIER_MAX_RECORDS, log.size());
		Assert.assertEquals("the 25 oldest are gone, not the 25 newest",
			26L, log.get(0).getAt());
		Assert.assertEquals(Tuning.DOSSIER_MAX_RECORDS + 25L, log.get(log.size() - 1).getAt());
	}

	@Test
	public void anOversizeLogConvergesRatherThanSittingOverTheCap()
	{
		// a save written while the cap was higher: trimming exactly one per append
		// would leave it permanently over, so the trim loops
		List<ContractRecord> oversize = new ArrayList<>();
		for (int i = 1; i <= 500; i++)
		{
			oversize.add(record(i, 0));
		}
		List<ContractRecord> log = ContractRecord.appendCapped(oversize, record(501, 0), 200);
		Assert.assertEquals(200, log.size());
		Assert.assertEquals(501L, log.get(log.size() - 1).getAt());
	}

	@Test
	public void theInputListIsNeverMutated()
	{
		// GachaState is immutable and the decoded list may be shared or read-only
		List<ContractRecord> original = Collections.singletonList(record(1L, 0));
		List<ContractRecord> log = ContractRecord.appendCapped(original, record(2L, 0), 200);
		Assert.assertEquals(1, original.size());
		Assert.assertEquals(2, log.size());
	}

	@Test
	public void aCapOfOneKeepsOnlyTheNewest()
	{
		List<ContractRecord> log = ContractRecord.appendCapped(
			Arrays.asList(record(1L, 0), record(2L, 0)), record(3L, 0), 1);
		Assert.assertEquals(1, log.size());
		Assert.assertEquals(3L, log.get(0).getAt());
	}

	@Test
	public void aNonsenseCapYieldsAnEmptyLogRatherThanAnUnboundedOne()
	{
		// nothing in the plugin passes 0, but an unbounded log is the one outcome
		// that must be impossible — it grows the gzip+SHA256 done on every mutate
		Assert.assertTrue(ContractRecord.appendCapped(null, record(1L, 0), 0).isEmpty());
		Assert.assertTrue(ContractRecord.appendCapped(null, record(1L, 0), -5).isEmpty());
	}

	// --- B. the verdict ---

	@Test
	public void zeroViolationsReadsClean()
	{
		Assert.assertTrue(record(1L, 0).isClean());
	}

	@Test
	public void anyViolationReadsDirty()
	{
		Assert.assertFalse(record(1L, 1).isClean());
		Assert.assertFalse(record(1L, 9).isClean());
	}

	@Test
	public void aNegativeCountStillReadsClean()
	{
		// nothing writes a negative, but "clean" must be the tolerant side: a
		// corrupt count must not brand a contract the plugin never convicted
		Assert.assertTrue(record(1L, -1).isClean());
	}

	@Test
	public void soloAndPartyAreDistinguishedByTheLabel()
	{
		Assert.assertFalse(record(1L, 0).isParty());
		Assert.assertTrue(new ContractRecord(1L, "Goblin", "EASY", 15, 400, 60_000, "MELEE",
			0, "Party of 3", false, false).isParty());
	}
}
