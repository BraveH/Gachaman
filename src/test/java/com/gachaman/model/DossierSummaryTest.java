package com.gachaman.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** The totals header: a pure fold over the contract log. */
public class DossierSummaryTest
{
	private static ContractRecord record(long gc, int kills, long durationMs, int taintedKills,
		String party)
	{
		return new ContractRecord(1L, "Goblin", "EASY", kills, gc, durationMs, "MELEE",
			taintedKills, party, false, false);
	}

	@Test
	public void anEmptyLogTotalsToZeroWithoutDividingByZero()
	{
		for (List<ContractRecord> log : Arrays.asList(null, new ArrayList<ContractRecord>()))
		{
			DossierSummary summary = DossierSummary.of(log);
			Assert.assertEquals(0, summary.getContracts());
			Assert.assertEquals(0, summary.getTotalGc());
			Assert.assertEquals(0, summary.getBestGc());
			Assert.assertEquals(0, summary.averageGc());
			Assert.assertEquals(0, summary.averageDurationMs());
			Assert.assertEquals(0, summary.cleanPercent());
			Assert.assertEquals(0d, summary.cleanFraction(), 0.0001);
		}
	}

	@Test
	public void foldsEveryColumnInOnePass()
	{
		DossierSummary summary = DossierSummary.of(Arrays.asList(
			record(400, 15, 60_000, 0, null),
			record(1600, 30, 120_000, 2, "Party of 2"),
			record(1000, 25, 30_000, 0, "Party of 3")));
		Assert.assertEquals(3, summary.getContracts());
		Assert.assertEquals(2, summary.getCleanContracts());
		Assert.assertEquals(2, summary.getPartyContracts());
		Assert.assertEquals(70, summary.getTotalKills());
		Assert.assertEquals(3000, summary.getTotalGc());
		Assert.assertEquals(210_000, summary.getTotalDurationMs());
		Assert.assertEquals(1600, summary.getBestGc());
		Assert.assertEquals(1000, summary.averageGc());
		Assert.assertEquals(70_000, summary.averageDurationMs());
	}

	@Test
	public void aNullElementIsSkippedRatherThanThrowing()
	{
		// Gson writes null ARRAY elements regardless of serializeNulls, so a hole
		// genuinely survives the codec — the same skip partyStyles needs
		DossierSummary summary = DossierSummary.of(
			Arrays.asList(record(400, 15, 60_000, 0, null), null, record(600, 5, 0, 0, null)));
		Assert.assertEquals("the hole must not be counted as a contract", 2, summary.getContracts());
		Assert.assertEquals(1000, summary.getTotalGc());
	}

	@Test
	public void cleanPercentFloorsSoOneBlemishNeverReadsAsPerfect()
	{
		List<ContractRecord> log = new ArrayList<>();
		for (int i = 0; i < 199; i++)
		{
			log.add(record(1, 1, 0, 0, null));
		}
		log.add(record(1, 1, 0, 1, null));
		DossierSummary summary = DossierSummary.of(log);
		Assert.assertEquals(199, summary.getCleanContracts());
		Assert.assertEquals("199/200 rounds to 100 — it must floor to 99", 99, summary.cleanPercent());
	}

	@Test
	public void aFlawlessLogReadsAsOneHundred()
	{
		DossierSummary summary = DossierSummary.of(
			Arrays.asList(record(1, 1, 0, 0, null), record(1, 1, 0, 0, null)));
		Assert.assertEquals(100, summary.cleanPercent());
		Assert.assertEquals(1d, summary.cleanFraction(), 0.0001);
	}

	@Test
	public void aNegativeDurationCannotDragTheTotalBackwards()
	{
		// a save moved across a clock change can produce a negative elapsed time;
		// the header must not report less total time than a single contract took
		DossierSummary summary = DossierSummary.of(
			Arrays.asList(record(1, 1, 60_000, 0, null), record(1, 1, -5_000, 0, null)));
		Assert.assertEquals(60_000, summary.getTotalDurationMs());
	}

	@Test
	public void aSingleContractIsItsOwnBestAndAverage()
	{
		DossierSummary summary = DossierSummary.of(
			Collections.singletonList(record(1234, 15, 45_000, 0, null)));
		Assert.assertEquals(1234, summary.getBestGc());
		Assert.assertEquals(1234, summary.averageGc());
		Assert.assertEquals(45_000, summary.averageDurationMs());
	}
}
