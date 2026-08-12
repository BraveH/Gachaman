package com.gachaman.overlay;

import java.awt.*;
import org.junit.*;

/**
 * The ornate chest's chains are clipped to the chest's own silhouette - that
 * clip is what makes their ends pass BEHIND the box instead of hanging in the
 * air beside it. Which means the silhouette and the chain path are now two
 * readers of the same layout fractions, and nothing in the painter would notice
 * if they drifted apart: a run that leaves the silhouette is not a bug you see
 * as a misplaced chain, it is a bug you see as no chain at all.
 *
 * <p>Swept over every chest size that ships. chestW is min(300, cw/3) and
 * chestH is three quarters of that, so the range below covers a very narrow
 * client through to the clamp.
 */
public class ChestPainterChainTest
{
	private static final int[] DIRS = {1, -1};

	/** Chain position at t along the straight run, before bow and sway. */
	private static double[] at(double[] ends, double t)
	{
		return new double[]{ends[0] + (ends[2] - ends[0]) * t, ends[1] + (ends[3] - ends[1]) * t};
	}

	/**
	 * Every part of the run the player is meant to see lands on the chest. The
	 * ends are sampled just inside t=0 and t=1 rather than exactly on them,
	 * because those points sit on the silhouette's own boundary where
	 * {@code contains} is entitled to answer either way.
	 */
	@Test
	public void theWholeVisibleRunOfEveryChainLandsOnTheChest()
	{
		for (int w = 60; w <= 300; w += 4)
		{
			int h = w * 3 / 4;
			Shape box = ChestPainter.closedSilhouette(0, 0, w, h);
			for (int dir : DIRS)
			{
				double[] ends = ChestPainter.chainEnds(0, 0, w, h, dir);
				for (double t = 0.02; t <= 0.98; t += 0.02)
				{
					double[] p = at(ends, t);
					Assert.assertTrue("chain " + dir + " leaves the chest at t=" + t
						+ " on a " + w + "x" + h + " chest", box.contains(p[0], p[1]));
				}
			}
		}
	}

	/**
	 * And the length past those ends does NOT, or the chain would simply stop at
	 * the edge with a visible last link - which is the thing that reads as an X
	 * painted on the picture rather than as a band going around a box. The
	 * painter runs 15% past each end and leans on the clip to cut it.
	 */
	@Test
	public void theLengthPastEachEndIsHiddenBehindTheChest()
	{
		for (int w = 60; w <= 300; w += 4)
		{
			int h = w * 3 / 4;
			Shape box = ChestPainter.closedSilhouette(0, 0, w, h);
			for (int dir : DIRS)
			{
				double[] ends = ChestPainter.chainEnds(0, 0, w, h, dir);
				for (double t : new double[]{-0.15, -0.08, 1.08, 1.15})
				{
					double[] p = at(ends, t);
					Assert.assertFalse("the run past t=" + t + " is still on the chest at "
						+ w + "x" + h, box.contains(p[0], p[1]));
				}
			}
		}
	}

	/**
	 * A chain that never reaches the lid is not holding anything shut. Both
	 * chains have to start inside the lid skirt and finish on the body, so the
	 * lid seam is crossed on the way and the whip-away has something to release.
	 */
	@Test
	public void everyChainCrossesTheLidSeam()
	{
		for (int w = 60; w <= 300; w += 4)
		{
			int h = w * 3 / 4;
			double skirtTop = -h / 2.0 + h * 0.16;
			double seam = skirtTop + h * 0.30;
			double bottom = h / 2.0;
			for (int dir : DIRS)
			{
				double[] ends = ChestPainter.chainEnds(0, 0, w, h, dir);
				Assert.assertTrue("chain " + dir + " starts above the lid at " + w,
					ends[1] > skirtTop);
				Assert.assertTrue("chain " + dir + " starts below the lid seam at " + w,
					ends[1] < seam);
				Assert.assertTrue("chain " + dir + " ends above the lid seam at " + w,
					ends[3] > seam);
				Assert.assertTrue("chain " + dir + " ends below the chest at " + w,
					ends[3] < bottom);
			}
		}
	}

	/**
	 * The OUTER chain comes off first. Chain 1 is painted second, so it lies
	 * over chain 0 where they cross, so it is the outer one and the only one
	 * whose padlock can actually be reached. Releasing the inner chain first
	 * has it slide out from under a chain still pinning it down - wrong in a way
	 * everybody sees and nobody can name, which is exactly the kind of thing
	 * that gets quietly reintroduced by a loop that runs k = 0 then 1.
	 */
	@Test
	public void theOuterChainIsUnlockedFirst()
	{
		long outer = ChestPainter.lockBreakMs(1, 1200, 1400);
		long inner = ChestPainter.lockBreakMs(0, 1200, 1400);
		Assert.assertEquals("the first lock should break at firstBreakMs", 1200, outer);
		Assert.assertEquals("the second should follow one gap later", 2600, inner);
		Assert.assertTrue("the inner chain came off before the outer one", outer < inner);
	}

	/** The two chains cross, or there is no X and nothing to whip off in turn. */
	@Test
	public void theTwoChainsCross()
	{
		for (int w = 60; w <= 300; w += 4)
		{
			int h = w * 3 / 4;
			double[] a = ChestPainter.chainEnds(0, 0, w, h, 1);
			double[] b = ChestPainter.chainEnds(0, 0, w, h, -1);
			Assert.assertTrue("the chains run parallel at " + w,
				java.awt.geom.Line2D.linesIntersect(a[0], a[1], a[2], a[3],
					b[0], b[1], b[2], b[3]));
		}
	}
}
