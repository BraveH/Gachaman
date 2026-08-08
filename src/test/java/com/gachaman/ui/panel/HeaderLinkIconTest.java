package com.gachaman.ui.panel;

import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import org.junit.Assert;
import org.junit.Test;

/**
 * The header's Ko-fi and GitHub icons are DRAWN rather than shipped as PNGs, which
 * means a geometry edit can quietly produce an empty or invisible glyph â€” Swing
 * renders a blank icon perfectly happily and nothing fails. These assertions are
 * the smoke alarm for that: coverage proves something was actually painted, and the
 * hover difference proves the hover variant is not silently identical.
 *
 * Deliberately NOT pixel-exact. Pinning the artwork would make every nudge to the
 * tail or the ears a test edit, which trains the habit of updating the expectation
 * instead of looking at the icon.
 */
public class HeaderLinkIconTest
{
	private static BufferedImage image(ImageIcon icon)
	{
		return (BufferedImage) icon.getImage();
	}

	/** Fraction of pixels that are not fully transparent. */
	private static double coverage(BufferedImage image)
	{
		int painted = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) > 8)
				{
					painted++;
				}
			}
		}
		return painted / (double) (image.getWidth() * image.getHeight());
	}

	private static int differingPixels(BufferedImage a, BufferedImage b)
	{
		int differing = 0;
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					differing++;
				}
			}
		}
		return differing;
	}

	@Test
	public void bothIconsAreSquareAndTheSameSize()
	{
		// they sit side by side in the title row: a mismatch reads as misalignment
		BufferedImage github = image(GachamanPanel.githubIcon(false));
		BufferedImage kofi = image(GachamanPanel.kofiIcon(false));
		Assert.assertEquals(github.getWidth(), github.getHeight());
		Assert.assertEquals(kofi.getWidth(), kofi.getHeight());
		Assert.assertEquals(github.getWidth(), kofi.getWidth());
		Assert.assertTrue("too small to be legible in the sidebar", github.getWidth() >= 12);
	}

	@Test
	public void theGithubMarkIsActuallyDrawn()
	{
		double covered = coverage(image(GachamanPanel.githubIcon(false)));
		Assert.assertTrue("the mark rendered as (nearly) nothing: " + covered, covered > 0.3);
		Assert.assertTrue("the mark filled the whole tile, so no silhouette is readable: "
			+ covered, covered < 0.9);
	}

	@Test
	public void theKofiPlateIsOpaqueEdgeToEdge()
	{
		// a rounded plate, so the corners are the only transparent pixels
		double covered = coverage(image(GachamanPanel.kofiIcon(false)));
		Assert.assertTrue("the plate did not render: " + covered, covered > 0.85);
	}

	@Test
	public void hoverVariantsDifferFromTheirRestingState()
	{
		// hover is the ONLY affordance these labels have â€” no border, no fill, no
		// focus ring â€” so an identical hover icon makes them look unclickable
		Assert.assertTrue(differingPixels(
			image(GachamanPanel.githubIcon(false)),
			image(GachamanPanel.githubIcon(true))) > 20);
		Assert.assertTrue(differingPixels(
			image(GachamanPanel.kofiIcon(false)),
			image(GachamanPanel.kofiIcon(true))) > 20);
	}

	@Test
	public void repeatedCallsAreIdentical()
	{
		// drawn fresh per call rather than cached, so any accidental dependence on
		// time, randomness or shared mutable state would show up as a flicker
		Assert.assertEquals(0, differingPixels(
			image(GachamanPanel.githubIcon(false)), image(GachamanPanel.githubIcon(false))));
		Assert.assertEquals(0, differingPixels(
			image(GachamanPanel.kofiIcon(true)), image(GachamanPanel.kofiIcon(true))));
	}
}

