package com.gachaman.ui.panel;

import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import org.junit.*;

/**
 * The header's Ko-fi and GitHub icons ship as PNGs authored by
 * {@code com.gachaman.tools.IconArt}. Shipping them rather than painting them
 * moves the failure mode: the glyph can no longer come out empty because of a
 * geometry edit, but it CAN be missing from the jar, regenerated at the wrong
 * size, or accidentally overwritten with a blank tile — and Swing renders a
 * missing or empty icon perfectly happily while nothing fails.
 *
 * <p>These assertions are the smoke alarm for that: the resource must resolve
 * off the classpath, coverage proves the tile actually carries a mark, and the
 * hover difference proves the two variants are not the same file twice.
 *
 * <p>Deliberately NOT pixel-exact. Pinning the artwork would make every nudge
 * to the tail or the ears a test edit, which trains the habit of updating the
 * expectation instead of looking at the icon.
 */
public class HeaderLinkIconTest
{
	private static BufferedImage icon(String name) throws IOException
	{
		try (InputStream in = HeaderLinkIconTest.class.getResourceAsStream(
			"/com/gachaman/ui/" + name + ".png"))
		{
			Assert.assertNotNull("icon missing from the classpath: " + name, in);
			return ImageIO.read(in);
		}
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
	public void bothIconsAreSquareAndTheSameSize() throws IOException
	{
		// they sit side by side in the title row: a mismatch reads as misalignment
		BufferedImage github = icon("link-github");
		BufferedImage kofi = icon("link-kofi");
		Assert.assertEquals(github.getWidth(), github.getHeight());
		Assert.assertEquals(kofi.getWidth(), kofi.getHeight());
		Assert.assertEquals(github.getWidth(), kofi.getWidth());
		Assert.assertTrue("too small to be legible in the sidebar", github.getWidth() >= 12);
	}

	@Test
	public void theGithubMarkIsActuallyDrawn() throws IOException
	{
		double covered = coverage(icon("link-github"));
		Assert.assertTrue("the mark rendered as (nearly) nothing: " + covered, covered > 0.3);
		Assert.assertTrue("the mark filled the whole tile, so no silhouette is readable: "
			+ covered, covered < 0.9);
	}

	@Test
	public void theKofiPlateIsOpaqueEdgeToEdge() throws IOException
	{
		// a rounded plate, so the corners are the only transparent pixels
		double covered = coverage(icon("link-kofi"));
		Assert.assertTrue("the plate did not render: " + covered, covered > 0.85);
	}

	@Test
	public void hoverVariantsDifferFromTheirRestingState() throws IOException
	{
		// hover is the ONLY affordance these labels have — no border, no fill, no
		// focus ring — so an identical hover icon makes them look unclickable
		Assert.assertTrue(differingPixels(icon("link-github"), icon("link-github-hover")) > 20);
		Assert.assertTrue(differingPixels(icon("link-kofi"), icon("link-kofi-hover")) > 20);
	}

	@Test
	public void theSidebarIconShipsAndCarriesAMark() throws IOException
	{
		// the one icon a player sees before opening anything; a missing resource
		// here is a blank button in the RuneLite sidebar
		BufferedImage sidebar = icon("panel-icon");
		Assert.assertEquals(sidebar.getWidth(), sidebar.getHeight());
		double covered = coverage(sidebar);
		Assert.assertTrue("the sidebar icon is blank: " + covered, covered > 0.3);
	}
}
