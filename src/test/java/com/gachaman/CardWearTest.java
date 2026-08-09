package com.gachaman;

import com.gachaman.model.CardWear;
import org.junit.Assert;
import org.junit.Test;

/**
 * The wear stage is a pure function of one number and of nothing else. That is
 * the whole safety argument for this feature: because no state, no clock and no
 * RNG feeds it, "a worn card behaves exactly like a pristine one" is provable
 * here rather than asserted in a design doc.
 */
public class CardWearTest
{
	/**
	 * Every threshold, from both sides. Off-by-one on a milestone is the bug a
	 * player would actually notice — they hit 100 kills, look at the card, and
	 * it has not changed.
	 */
	@Test
	public void everyBoundaryExactly()
	{
		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(99));
		Assert.assertEquals(CardWear.HAIRLINE, Tuning.cardWear(100));
		Assert.assertEquals(CardWear.HAIRLINE, Tuning.cardWear(399));
		Assert.assertEquals(CardWear.CRACKED, Tuning.cardWear(400));
		Assert.assertEquals(CardWear.CRACKED, Tuning.cardWear(999));
		Assert.assertEquals(CardWear.SHATTERED, Tuning.cardWear(1000));
	}

	/** A brand-new card, and a legacy save where killsServed backfilled to zero. */
	@Test
	public void aFreshCardIsPristine()
	{
		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(0));
		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(1));
	}

	/**
	 * killsServed is documented as monotonic, but a corrupted or hand-edited save
	 * must not throw or fall off the bottom of the switch. Negative means "no
	 * service", the same as zero.
	 */
	@Test
	public void nonsenseInputsDegradeToPristine()
	{
		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(-1));
		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(Integer.MIN_VALUE));
	}

	/** Past the top stage it saturates. There is no fifth, worse stage. */
	@Test
	public void theTopStageSaturates()
	{
		Assert.assertEquals(CardWear.SHATTERED, Tuning.cardWear(1001));
		Assert.assertEquals(CardWear.SHATTERED, Tuning.cardWear(1_000_000));
		Assert.assertEquals(CardWear.SHATTERED, Tuning.cardWear(Integer.MAX_VALUE));
	}

	/**
	 * Wear only ever accumulates. Because killsServed is permanent and monotonic,
	 * a card can never visibly heal — sweep the whole reachable ladder and prove
	 * the stage index never steps backwards.
	 */
	@Test
	public void wearNeverGoesBackwards()
	{
		int previous = -1;
		for (int kills = 0; kills <= 1200; kills++)
		{
			int stage = Tuning.cardWear(kills).ordinal();
			Assert.assertTrue("wear regressed at " + kills, stage >= previous);
			previous = stage;
		}
	}

	/**
	 * The thresholds are strictly ordered, so each stage is actually reachable.
	 * Collapsing two of them (say, both at 400) would silently delete a stage
	 * without failing anything else.
	 */
	@Test
	public void everyStageIsReachable()
	{
		Assert.assertTrue(Tuning.WEAR_HAIRLINE_KILLS < Tuning.WEAR_CRACKED_KILLS);
		Assert.assertTrue(Tuning.WEAR_CRACKED_KILLS < Tuning.WEAR_SHATTERED_KILLS);

		Assert.assertEquals(CardWear.HAIRLINE, Tuning.cardWear(Tuning.WEAR_HAIRLINE_KILLS));
		Assert.assertEquals(CardWear.CRACKED, Tuning.cardWear(Tuning.WEAR_CRACKED_KILLS));
		Assert.assertEquals(CardWear.SHATTERED, Tuning.cardWear(Tuning.WEAR_SHATTERED_KILLS));

		Assert.assertEquals(CardWear.NONE, Tuning.cardWear(Tuning.WEAR_HAIRLINE_KILLS - 1));
		Assert.assertEquals(CardWear.HAIRLINE, Tuning.cardWear(Tuning.WEAR_CRACKED_KILLS - 1));
		Assert.assertEquals(CardWear.CRACKED, Tuning.cardWear(Tuning.WEAR_SHATTERED_KILLS - 1));
	}

	/**
	 * The first threshold must stay at or above one. Wear is measured from the
	 * top band that CardRenderer reserves around the service pill, and the pill
	 * is only painted when killsServed &gt; 0 — a zero threshold would ask the
	 * crack router to steer around a badge that was never drawn.
	 */
	@Test
	public void theFirstThresholdImpliesAVisibleServicePill()
	{
		Assert.assertTrue("wear must not appear before the service pill does",
			Tuning.WEAR_HAIRLINE_KILLS >= 1);
	}

	/**
	 * The stage names are player-facing text and go through Java2D, which draws a
	 * tofu box for anything the sans-serif face has no glyph for.
	 */
	@Test
	public void displayNamesArePlainAscii()
	{
		Assert.assertEquals("", CardWear.NONE.getDisplayName());
		for (CardWear wear : CardWear.values())
		{
			Assert.assertTrue("non-ascii in " + wear,
				wear.getDisplayName().matches("[\\x20-\\x7E]*"));
		}
	}

	// --- the inverse, used only by ::gachawear ---

	/**
	 * The round trip, over every stage. wearKills lives in the same file as
	 * cardWear precisely so the two cannot drift, and this is the assertion that
	 * makes that guarantee real rather than a comment: retune a threshold and it
	 * still passes, add a stage and forget the inverse and it does not.
	 */
	@Test
	public void everyStageNamesItsOwnFloor()
	{
		for (CardWear wear : CardWear.values())
		{
			Assert.assertEquals("wearKills(" + wear + ") does not land on " + wear,
				wear, Tuning.cardWear(Tuning.wearKills(wear)));
		}
	}

	/**
	 * The floor is the FLOOR — one kill short of it must be a lower stage. A
	 * wearKills that returned the middle of a band would still pass the round
	 * trip above while making "::gachawear cracked" a lie about the number.
	 */
	@Test
	public void theFloorIsTheFirstKillOfItsStage()
	{
		Assert.assertEquals(0, Tuning.wearKills(CardWear.NONE));
		for (CardWear wear : CardWear.values())
		{
			if (wear != CardWear.NONE)
			{
				Assert.assertTrue(Tuning.cardWear(Tuning.wearKills(wear) - 1).ordinal()
					< wear.ordinal());
			}
		}
	}

	// --- CardWear.parse ---

	@Test
	public void parseTakesEveryStageNameInAnyCase()
	{
		for (CardWear wear : CardWear.values())
		{
			Assert.assertEquals(wear, CardWear.parse(wear.name()));
			Assert.assertEquals(wear, CardWear.parse(wear.name().toLowerCase(java.util.Locale.ROOT)));
			Assert.assertEquals(wear, CardWear.parse("  " + wear.name() + "  "));
		}
	}

	/**
	 * Null, not NONE. The caller's other reading of the argument is a raw kill
	 * count, so a stage default here would turn a typo into a wiped record.
	 */
	@Test
	public void parseRejectsAnythingThatIsNotAStage()
	{
		Assert.assertNull(CardWear.parse(null));
		Assert.assertNull(CardWear.parse(""));
		Assert.assertNull(CardWear.parse("   "));
		Assert.assertNull(CardWear.parse("shatter"));
		Assert.assertNull(CardWear.parse("shatteredd"));
		Assert.assertNull(CardWear.parse("100"));
		// the display name is prose, not an input token
		Assert.assertNull(CardWear.parse(CardWear.SHATTERED.getDisplayName()));
	}
}
