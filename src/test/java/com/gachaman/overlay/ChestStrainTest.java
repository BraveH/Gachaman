package com.gachaman.overlay;

import com.gachaman.*;
import org.junit.*;

/**
 * The chest intro's three-stage strain schedule — pure timing math, no client,
 * no Graphics2D. Every case loops {@link Tuning.Chest#values()} rather than
 * naming tiers, so a fifth tier added later is held to the same invariants.
 */
public class ChestStrainTest
{
	/** Mirrors ChestStrain's private KICK_MS; kept private there because only the curve is public. */
	private static final long KICK_MS = 180;

	/**
	 * Closest two groans may ever land. Comfortably wider than both the 180ms
	 * kick and the 420ms groan clip, so neither the shudder nor the audio can
	 * stack on itself.
	 */
	private static final long MIN_BEAT_GAP_MS = 500;

	@Test
	public void totalsAreUnchanged()
	{
		// The pitch for this feature asked for a 2800ms top-tier intro. Applying
		// it would end Gilded's intro 400ms BEFORE its lid opens at el=3200, so
		// the ceremony would cut to the deal over a still-shut chest. These four
		// numbers are the ones the ceremony is tuned against; they do not move.
		Assert.assertEquals(1400, ChestStrain.totalMs(Tuning.Chest.RUSTY));
		Assert.assertEquals(2000, ChestStrain.totalMs(Tuning.Chest.BATTERED));
		Assert.assertEquals(4000, ChestStrain.totalMs(Tuning.Chest.GILDED));
		Assert.assertEquals(7000, ChestStrain.totalMs(Tuning.Chest.ORNATE));
	}

	@Test
	public void giveAlwaysLandsInsideTheIntro()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long shudder = ChestStrain.shudderMs(tier);
			long give = ChestStrain.giveMs(tier);
			Assert.assertTrue(tier + " shudders before it gives", shudder < give);
			Assert.assertTrue(tier + " gives before the intro ends", give < ChestStrain.totalMs(tier));
			Assert.assertTrue(tier + " leaves room for the stillness",
				give - shudder > ChestStrain.HELD_STILL_MS);
		}
	}

	@Test
	public void loadIsMonotoneAndClamped()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			double previous = -1;
			for (long el = -500; el <= ChestStrain.totalMs(tier) + 500; el += 10)
			{
				double load = ChestStrain.load(el, tier);
				Assert.assertTrue(tier + " load in range at " + el, load >= 0 && load <= 1);
				Assert.assertTrue(tier + " load never falls back at " + el, load >= previous);
				previous = load;
			}
			Assert.assertEquals(tier + " starts unloaded",
				0.0, ChestStrain.load(ChestStrain.shudderMs(tier), tier), 1e-9);
			Assert.assertEquals(tier + " is fully loaded when it gives",
				1.0, ChestStrain.load(ChestStrain.giveMs(tier), tier), 1e-9);
		}
	}

	@Test
	public void strainingBracketsExactly()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long shudder = ChestStrain.shudderMs(tier);
			long still = ChestStrain.giveMs(tier) - ChestStrain.HELD_STILL_MS;
			// Boundary exactness at shudderMs is why the draw code gates on
			// straining() and not on load() > 0: the load is exactly 0 here, so
			// a > 0 gate would silently drop the first frame of movement.
			Assert.assertFalse(tier + " is calm before the shudder", ChestStrain.straining(shudder - 1, tier));
			Assert.assertTrue(tier + " moves on the shudder", ChestStrain.straining(shudder, tier));
			Assert.assertTrue(tier + " still moves a frame before the hush",
				ChestStrain.straining(still - 1, tier));
			Assert.assertFalse(tier + " is hushed on the mark", ChestStrain.straining(still, tier));
			Assert.assertFalse(tier + " is hushed when it gives",
				ChestStrain.straining(ChestStrain.giveMs(tier), tier));
		}
	}

	@Test
	public void theLockGoesStillBeforeItGives()
	{
		// The payoff beat. Motion that merely continues reads as noise; motion
		// that stops dead reads as a lock about to lose.
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long give = ChestStrain.giveMs(tier);
			for (long el = give - ChestStrain.HELD_STILL_MS; el <= give; el++)
			{
				Assert.assertFalse(tier + " must not twitch at " + el, ChestStrain.straining(el, tier));
			}
		}
	}

	@Test
	public void rustyStaysMuted()
	{
		// The starter box's ceremony is deliberately cheap and undramatic. The
		// tell only works if the bottom of the ladder stays conspicuously quiet,
		// so Rusty gets no groans at all — its lack of a fight IS its tell.
		Assert.assertEquals(0, ChestStrain.beats(Tuning.Chest.RUSTY).length);
		Assert.assertEquals(300, ChestStrain.shudderMs(Tuning.Chest.RUSTY));
		Assert.assertEquals(900, ChestStrain.giveMs(Tuning.Chest.RUSTY));
		Assert.assertEquals(1400, ChestStrain.totalMs(Tuning.Chest.RUSTY));
	}

	@Test
	public void beatsAreOrderedAndInsideTheStrain()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long[] beats = ChestStrain.beats(tier);
			// The schedule was authored against a four-step pitch ladder — one
			// whole tone of metal-under-load per beat — and a fifth beat has no
			// step of its own: it clamps to the top of the ladder and repeats
			// the fourth. Four ASCENDING groans are the tell; a fifth that
			// merely repeats the last one is noise that says nothing new.
			Assert.assertTrue(tier + " has at most four beats", beats.length <= 4);
			for (int k = 0; k < beats.length; k++)
			{
				if (k > 0)
				{
					Assert.assertTrue(tier + " beats ascend", beats[k] > beats[k - 1]);
					Assert.assertTrue(tier + " beat " + k + " must not crowd its predecessor",
						beats[k] - beats[k - 1] >= MIN_BEAT_GAP_MS);
				}
				Assert.assertTrue(tier + " beat " + k + " waits for the shudder",
					beats[k] >= ChestStrain.shudderMs(tier));
				// A groan struck during the hush would be audio with no motion.
				Assert.assertTrue(tier + " beat " + k + " lands before the hush",
					beats[k] <= ChestStrain.giveMs(tier) - ChestStrain.HELD_STILL_MS);
			}
		}
	}

	@Test
	public void kickDecaysAndNeverExceedsOne()
	{
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long[] beats = ChestStrain.beats(tier);
			if (beats.length > 0)
			{
				Assert.assertEquals(tier + " is unkicked before its first beat",
					0.0, ChestStrain.kick(beats[0] - 1, tier), 1e-9);
			}
			for (long beat : beats)
			{
				Assert.assertEquals(tier + " kicks hardest on the beat",
					1.0, ChestStrain.kick(beat, tier), 1e-9);
				double previous = Double.MAX_VALUE;
				for (long d = 0; d < KICK_MS; d++)
				{
					double kick = ChestStrain.kick(beat + d, tier);
					Assert.assertTrue(tier + " kick decays at +" + d, kick < previous);
					previous = kick;
				}
				Assert.assertEquals(tier + " kick is spent at +" + KICK_MS,
					0.0, ChestStrain.kick(beat + KICK_MS, tier), 1e-9);
			}
			// The amplitude formulas add 3-4px per unit of kick, so an
			// out-of-range value would fling the chest off screen — and a kick
			// with no groan under it is a jolt from nowhere, so any nonzero
			// value has to sit inside some beat's window.
			for (long el = 0; el <= ChestStrain.totalMs(tier); el++)
			{
				double kick = ChestStrain.kick(el, tier);
				Assert.assertTrue(tier + " kick in range at " + el, kick >= 0 && kick <= 1);
				Assert.assertEquals(tier + " kick at " + el + " must follow a beat",
					inBeatWindow(beats, el), kick > 0);
			}
		}
	}

	private static boolean inBeatWindow(long[] beats, long el)
	{
		for (long beat : beats)
		{
			if (el >= beat && el < beat + KICK_MS)
			{
				return true;
			}
		}
		return false;
	}
}
