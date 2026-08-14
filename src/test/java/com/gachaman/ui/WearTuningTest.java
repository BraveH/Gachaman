package com.gachaman.ui;

import com.gachaman.model.*;
import org.junit.*;

/**
 * Pins CardRenderer's WEAR_TUNING table against the five switch statements it
 * replaced.
 *
 * <p>Every number here is a literal copied from the switches as they stood, not
 * a re-derivation of the table: the whole value of this test is that it fails if
 * a row is ever reordered or a column shifted, and a test that computed its
 * expectations from the same table would pass through exactly that mistake.
 *
 * <p>Two things the switches gave for free and the table does not, both covered
 * below. A switch fell through to {@code default: return 0} for any constant it
 * did not name, so a CardWear added later simply behaved like NONE; an
 * ordinal-indexed table throws instead. {@link #everyStageHasARow} is what turns
 * that from a crash mid-reveal into a red build, and it needs no maintenance —
 * it walks {@code values()}, so a new constant fails it the day it is added.
 */
public class WearTuningTest
{
	/**
	 * NONE is the row that used to be the {@code default:} arm of all five
	 * switches, so it is the one that proves the table did not shift by a row.
	 */
	@Test
	public void pristineDrawsNothing()
	{
		Assert.assertEquals(0, CardRenderer.creaseCount(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.scratchCount(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.wearAlpha(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.grimeAlpha(CardWear.NONE));
		Assert.assertEquals(0, CardRenderer.edgeNicks(CardWear.NONE));
	}

	/** HAIRLINE: scuffing has started, nothing has folded yet. */
	@Test
	public void hairlineNumbers()
	{
		Assert.assertEquals(0, CardRenderer.creaseCount(CardWear.HAIRLINE));
		Assert.assertEquals(6, CardRenderer.scratchCount(CardWear.HAIRLINE));
		Assert.assertEquals(96, CardRenderer.wearAlpha(CardWear.HAIRLINE));
		Assert.assertEquals(34, CardRenderer.grimeAlpha(CardWear.HAIRLINE));
		Assert.assertEquals(20, CardRenderer.edgeNicks(CardWear.HAIRLINE));
	}

	/** CRACKED: one fold. */
	@Test
	public void crackedNumbers()
	{
		Assert.assertEquals(1, CardRenderer.creaseCount(CardWear.CRACKED));
		Assert.assertEquals(10, CardRenderer.scratchCount(CardWear.CRACKED));
		Assert.assertEquals(150, CardRenderer.wearAlpha(CardWear.CRACKED));
		Assert.assertEquals(62, CardRenderer.grimeAlpha(CardWear.CRACKED));
		Assert.assertEquals(38, CardRenderer.edgeNicks(CardWear.CRACKED));
	}

	/** SHATTERED: two folds, and the heaviest of everything else. */
	@Test
	public void shatteredNumbers()
	{
		Assert.assertEquals(2, CardRenderer.creaseCount(CardWear.SHATTERED));
		Assert.assertEquals(16, CardRenderer.scratchCount(CardWear.SHATTERED));
		Assert.assertEquals(200, CardRenderer.wearAlpha(CardWear.SHATTERED));
		Assert.assertEquals(92, CardRenderer.grimeAlpha(CardWear.SHATTERED));
		Assert.assertEquals(60, CardRenderer.edgeNicks(CardWear.SHATTERED));
	}

	/**
	 * The invariant the table costs and the switches did not: one row per
	 * constant. A new CardWear with no row would throw
	 * ArrayIndexOutOfBoundsException out of every accessor, which is a crash in
	 * the middle of drawing a card rather than a wrong-looking card.
	 */
	@Test
	public void everyStageHasARow()
	{
		for (CardWear wear : CardWear.values())
		{
			String where = "no WEAR_TUNING row for " + wear;
			try
			{
				CardRenderer.creaseCount(wear);
				CardRenderer.scratchCount(wear);
				CardRenderer.wearAlpha(wear);
				CardRenderer.grimeAlpha(wear);
				CardRenderer.edgeNicks(wear);
			}
			catch (ArrayIndexOutOfBoundsException e)
			{
				Assert.fail(where);
			}
		}
	}

	/**
	 * The stage has to be legible at a glance, which means the damage can only
	 * ever get heavier as the Service Record grows. Creases are the one column
	 * allowed to sit still (HAIRLINE has none, by design — a scuffed card has not
	 * been folded), so it is tested non-decreasing while the rest are strictly
	 * increasing from HAIRLINE on.
	 */
	@Test
	public void everyColumnClimbsWithTheStage()
	{
		CardWear[] stages = CardWear.values();
		for (int i = 1; i < stages.length; i++)
		{
			CardWear prev = stages[i - 1];
			CardWear next = stages[i];
			String where = prev + " -> " + next;
			Assert.assertTrue("creases went backwards at " + where,
				CardRenderer.creaseCount(next) >= CardRenderer.creaseCount(prev));
			Assert.assertTrue("scratches went backwards at " + where,
				CardRenderer.scratchCount(next) > CardRenderer.scratchCount(prev));
			Assert.assertTrue("line alpha went backwards at " + where,
				CardRenderer.wearAlpha(next) > CardRenderer.wearAlpha(prev));
			Assert.assertTrue("grime went backwards at " + where,
				CardRenderer.grimeAlpha(next) > CardRenderer.grimeAlpha(prev));
			Assert.assertTrue("edge nicks went backwards at " + where,
				CardRenderer.edgeNicks(next) > CardRenderer.edgeNicks(prev));
		}
	}

	/** Never opaque: wear is a layer over the print, not a replacement for it. */
	@Test
	public void opacitiesStayUnderFull()
	{
		for (CardWear wear : CardWear.values())
		{
			Assert.assertTrue("line alpha is opaque at " + wear,
				CardRenderer.wearAlpha(wear) < 255);
			Assert.assertTrue("grime is opaque at " + wear,
				CardRenderer.grimeAlpha(wear) < 255);
			Assert.assertTrue("grime outweighs the line work at " + wear,
				CardRenderer.grimeAlpha(wear) <= CardRenderer.wearAlpha(wear));
		}
	}
}
