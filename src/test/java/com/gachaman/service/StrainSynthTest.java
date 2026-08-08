package com.gachaman.service;

import org.junit.Assert;
import org.junit.Test;

/**
 * The strain groan's synthesized samples, taken straight from the generator —
 * no AudioPlayer, no mixer, no WAV header parsing.
 */
public class StrainSynthTest
{
	private static final int SAMPLE_RATE = 22050;
	private static final int STEPS = 4;

	@Test
	public void strainPitchRisesWithStep()
	{
		// "Rising pitch" is half the feature and the audio API cannot shift a
		// cached clip, so each step has to be genuinely synthesized higher.
		// Counting upward zero crossings after the 50ms attack is enough: the
		// waveform shape is identical across steps, only its rate scales.
		int previous = 0;
		for (int step = 0; step < STEPS; step++)
		{
			int crossings = upwardCrossings(SoundService.synthStrain(step),
				(int) (0.05 * SAMPLE_RATE), (int) (0.25 * SAMPLE_RATE));
			Assert.assertTrue("step " + step + " must out-pitch step " + (step - 1)
				+ " (" + crossings + " vs " + previous + ")", crossings > previous);
			previous = crossings;
		}
	}

	@Test
	public void strainNeverClipsAndAlwaysDecays()
	{
		for (int step = 0; step < STEPS; step++)
		{
			double[] buf = SoundService.synthStrain(step);
			Assert.assertTrue("step " + step + " has samples", buf.length > 0);
			// Clipping would leave toWav's clamp doing the work, which distorts.
			double peak = 0;
			for (double sample : buf)
			{
				Assert.assertTrue("step " + step + " must not clip", Math.abs(sample) <= 1.0);
				peak = Math.max(peak, Math.abs(sample));
			}
			Assert.assertTrue("step " + step + " must make a sound", peak > 0.05);
			// The slow attack exists so the groan swells instead of clicking.
			Assert.assertEquals("step " + step + " must not click on", 0.0, buf[0], 1e-6);

			// Beats are at least 500ms apart, so the tail has to be gone by then
			// or two groans stack into mud.
			int tailFrom = buf.length - buf.length / 10;
			double tail = 0;
			for (int i = tailFrom; i < buf.length; i++)
			{
				tail += Math.abs(buf[i]);
			}
			tail /= (buf.length - tailFrom);
			Assert.assertTrue("step " + step + " tail " + tail + " must fade under peak " + peak,
				tail < peak * 0.15);
		}
	}

	private static int upwardCrossings(double[] buf, int from, int to)
	{
		int count = 0;
		for (int i = Math.max(1, from); i < Math.min(to, buf.length); i++)
		{
			if (buf[i - 1] <= 0 && buf[i] > 0)
			{
				count++;
			}
		}
		return count;
	}
}
