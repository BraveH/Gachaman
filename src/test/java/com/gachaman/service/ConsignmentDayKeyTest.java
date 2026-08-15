package com.gachaman.service;

import java.time.*;
import java.util.*;
import org.junit.*;

/**
 * The once-per-day gate's clock, tested as a pure function.
 *
 * <p>The day key is the whole of the gate — there is no server and nothing
 * stored but the last key used — so the only two ways it can be wrong are
 * turning over at the wrong instant, or turning over at a different instant for
 * different players. One test each.
 */
public class ConsignmentDayKeyTest
{
	private TimeZone originalZone;

	@Before
	public void rememberZone()
	{
		originalZone = TimeZone.getDefault();
	}

	@After
	public void restoreZone()
	{
		TimeZone.setDefault(originalZone);
	}

	private static long at(String isoInstant)
	{
		return Instant.parse(isoInstant).toEpochMilli();
	}

	@Test
	public void theKeyTurnsOverAtUtcMidnightAndNotBefore()
	{
		String lateOnTheFifteenth = ConsignmentService.dayKeyAt(at("2026-08-15T23:59:59Z"));
		String earlyOnTheSixteenth = ConsignmentService.dayKeyAt(at("2026-08-16T00:00:01Z"));

		Assert.assertNotEquals("two seconds apart across UTC midnight is two different days",
			lateOnTheFifteenth, earlyOnTheSixteenth);
		Assert.assertEquals("the whole of one UTC day is one key",
			lateOnTheFifteenth, ConsignmentService.dayKeyAt(at("2026-08-15T00:00:00Z")));
		Assert.assertEquals(earlyOnTheSixteenth,
			ConsignmentService.dayKeyAt(at("2026-08-16T23:59:59Z")));
	}

	/**
	 * The guard against the obvious "simplification" — {@code LocalDate.now()}
	 * without a zone, which reads the JVM default. Two players hitting the same
	 * instant from Tokyo and Los Angeles are on opposite sides of their own local
	 * midnights here, and must still be on the same Consignment day.
	 */
	@Test
	public void oneInstantIsOneDayNoMatterWhatTheClientsClockIsSetTo()
	{
		// 05:00 the next morning in Tokyo, 13:00 the same afternoon in Los Angeles
		long instant = at("2026-08-15T20:00:00Z");

		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
		String tokyo = ConsignmentService.dayKeyAt(instant);

		TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
		String losAngeles = ConsignmentService.dayKeyAt(instant);

		Assert.assertEquals("the gate must not depend on the player's own clock",
			tokyo, losAngeles);
		Assert.assertEquals("...and both must be the UTC day", tokyo,
			ConsignmentService.dayKey(LocalDate.of(2026, 8, 15)));
	}

	/**
	 * Day-of-year rather than month/day, lifted verbatim from the Charter Office
	 * so two daily gates in one plugin cannot disagree about when a day turns
	 * over. Pinned because the shape is compared as a plain string against a
	 * persisted value — changing it would silently hand every existing save a
	 * free Consignment on the day of the change.
	 */
	@Test
	public void theKeyIsYearAndDayOfYear()
	{
		Assert.assertEquals("2026-D1", ConsignmentService.dayKey(LocalDate.of(2026, 1, 1)));
		Assert.assertEquals("2026-D227", ConsignmentService.dayKey(LocalDate.of(2026, 8, 15)));
		// leap years push the tail of the year along by one; nothing depends on
		// the number itself, only on two derived strings comparing equal
		Assert.assertEquals("2024-D366", ConsignmentService.dayKey(LocalDate.of(2024, 12, 31)));
		Assert.assertEquals("2025-D365", ConsignmentService.dayKey(LocalDate.of(2025, 12, 31)));
	}

	@Test
	public void anUnusedKeyIsNeverUsedToday()
	{
		String today = ConsignmentService.dayKey(LocalDate.of(2026, 8, 15));
		String yesterday = ConsignmentService.dayKey(LocalDate.of(2026, 8, 14));

		Assert.assertFalse("a save written before the feature existed has no key at all",
			ConsignmentService.usedOn(null, today));
		Assert.assertFalse(ConsignmentService.usedOn(yesterday, today));
		Assert.assertTrue(ConsignmentService.usedOn(today, today));
	}
}
