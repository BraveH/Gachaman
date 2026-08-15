package com.gachaman.overlay;

import java.util.List;
import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import com.google.gson.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.junit.*;

/**
 * The roulette's preferred-weapon caption: the two lines that say what the wheel
 * named and what it is worth.
 *
 * <p>Both lines are load-bearing rather than decorative, and they fail
 * differently. The NAME can leak an internal key that lies about the game state
 * — the key for category 0 is "unarmed", which the game also reports for a
 * lantern, a bucket and a pet rock, so a player holding a fishing rod would be
 * told they are unarmed. The WORTH can quietly go stale: the bonus multiplies
 * the per-kill trickle only, so what it is actually worth depends on the ratio
 * between the kill rate and the completion bonus, and a printed number would go
 * wrong the first time either is retuned with nobody the wiser.
 *
 * <p>Everything here is a pure function of the shipped resource and
 * {@link Tuning}, so none of it needs a client.
 *
 * <p>Explicit {@code java.util.List} import: {@code java.awt.*} is wildcarded
 * below for the font metrics and exports a List of its own.
 */
public class WeaponCaptionTest
{
	/** The shape of the shipped resource, as far as this test cares. */
	private static class ShippedFile
	{
		List<WeaponTypeService.WeaponType> types;
	}

	/**
	 * The taxonomy as SHIPPED, read straight off the classpath.
	 *
	 * <p>Deliberately not via WeaponTypeService: the service's constructor is
	 * package-private to its own package, and going around it also means this
	 * test keeps working if the loading ever changes. What is being pinned is the
	 * file's contents against the caption, and nothing in between.
	 */
	private static List<WeaponTypeService.WeaponType> taxonomy() throws Exception
	{
		try (InputStream in = WeaponCaptionTest.class.getResourceAsStream(
			"/com/gachaman/data/weapon-types.json"))
		{
			Assert.assertNotNull("weapon-types.json is not on the classpath", in);
			ShippedFile file = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), ShippedFile.class);
			Assert.assertNotNull("weapon-types.json carries no types", file.types);
			Assert.assertFalse("weapon-types.json is empty", file.types.isEmpty());
			return file.types;
		}
	}

	/**
	 * Every category the wheel can name renders its display name, and NOT its key.
	 *
	 * <p>The taxonomy has a resource test of its own asserting that no display
	 * name contains "unarmed". This is the other end of the same rule: it does not
	 * matter what the resource says if the caption reaches past it for the key,
	 * and this method is the only place in the plugin where a weapon category
	 * becomes words on a player's screen. Walking the whole file rather than the
	 * one interesting entry is what makes it hold for entries added later.
	 */
	@Test
	public void everyShippedCategoryIsNamedByItsDisplayNameNeverItsKey() throws Exception
	{
		int checked = 0;
		for (WeaponTypeService.WeaponType type : taxonomy())
		{
			String line = RevealOverlay.weaponLine(type);
			checked++;
			Assert.assertTrue(type.getKey() + ": the caption must carry the display name '"
				+ type.getDisplayName() + "' — it says: " + line,
				line.contains(type.getDisplayName()));
			Assert.assertFalse(type.getKey() + ": the caption leaked the internal key —"
				+ " the game reports that category for every non-weapon held item, so it"
				+ " must read 'No weapon equipped'. It says: " + line,
				line.toLowerCase(Locale.ROOT).contains("narmed"));
		}
		Assert.assertTrue("walked no categories", checked > 20);
	}

	/** The category-0 entry specifically, since it is the one the rule exists for. */
	@Test
	public void categoryZeroReadsAsNoWeaponEquipped() throws Exception
	{
		WeaponTypeService.WeaponType unarmed = null;
		for (WeaponTypeService.WeaponType type : taxonomy())
		{
			if ("unarmed".equals(type.getKey()))
			{
				unarmed = type;
			}
		}
		Assert.assertNotNull("the taxonomy no longer carries the 'unarmed' key", unarmed);
		Assert.assertEquals("Preferred weapon: No weapon equipped",
			RevealOverlay.weaponLine(unarmed));
	}

	/**
	 * A cycle the wheel named nothing for reads as an absent bonus, never as a
	 * bonus the player let slip.
	 *
	 * <p>Null means the taxonomy resource failed to load — this build's
	 * shortcoming, not the player's — and the preference only ever adds to what
	 * they earn, so nothing was taken from them. Wording it as a loss would invent
	 * a punishment out of a missing file.
	 */
	@Test
	public void noNamedCategoryReadsAsNoBonusAvailable()
	{
		String worth = RevealOverlay.worthLine(null);
		Assert.assertTrue("it must say the bonus is unavailable, and say it plainly: " + worth,
			worth.contains("no bonus available"));
		String both = (RevealOverlay.weaponLine(null) + " " + worth).toLowerCase(Locale.ROOT);
		for (String blame : new String[]{"miss", "fail", "lost", "wasted", "unlucky", "should"})
		{
			Assert.assertFalse("the caption blames the player with '" + blame + "': " + both,
				both.contains(blame));
		}
		Assert.assertFalse("even with nothing named, the caption must not say 'unarmed'",
			both.contains("narmed"));
	}

	/**
	 * The worth is DERIVED from the constants that decide it.
	 *
	 * <p>Computed here the long way round — the whole contract's pay with the
	 * bonus against the whole contract's pay without it — so it agrees with the
	 * implementation only if both are reading the same three tuning values. A
	 * printed literal, or a formula that quietly dropped the completion bonus out
	 * of the denominator, fails this.
	 */
	@Test
	public void theWorthIsDerivedFromTuningRatherThanWrittenDown()
	{
		for (TaskDifficulty difficulty : TaskDifficulty.values())
		{
			double kills = (difficulty.getMinKills() + difficulty.getMaxKills()) / 2.0;
			double perKill = Tuning.PER_KILL_GC.get(difficulty);
			double completion = Tuning.COMPLETION_GC.get(difficulty);
			double plain = kills * perKill + completion;
			double bonused = kills * perKill * Tuning.WEAPON_BONUS_MULT + completion;
			Assert.assertEquals(difficulty.getDisplayName(),
				(int) Math.round(100 * (bonused - plain) / plain),
				RevealOverlay.weaponWorthPct(difficulty));
		}
	}

	/**
	 * The claim the caption is actually making: this is worth little on the bottom
	 * rung and a lot on the top one.
	 *
	 * <p>If that ever stops being true the caption stops being honest, and the
	 * player who chose a slower weapon on the strength of it has been misled. The
	 * ladder is monotone because kill count and per-kill rate both climb faster
	 * than the completion bonus does; pinning it means a retune that flattens the
	 * curve has to come back here and rewrite the sentence.
	 */
	@Test
	public void theBonusIsWorthLeastOnEasyAndMostOnInsane()
	{
		TaskDifficulty[] ladder = TaskDifficulty.values();
		for (int i = 1; i < ladder.length; i++)
		{
			Assert.assertTrue(ladder[i].getDisplayName() + " is not worth more than "
					+ ladder[i - 1].getDisplayName(),
				RevealOverlay.weaponWorthPct(ladder[i])
					> RevealOverlay.weaponWorthPct(ladder[i - 1]));
		}
		Assert.assertTrue("Easy is no longer a thin margin, so the caveat line lies",
			RevealOverlay.weaponWorthPct(TaskDifficulty.EASY) <= 10);
		Assert.assertTrue("Insane is no longer a large gain, so the caption oversells it",
			RevealOverlay.weaponWorthPct(TaskDifficulty.INSANE) >= 20);
	}

	/**
	 * The worth line names both ends of the ladder and says which half of the pay
	 * the multiplier touches.
	 *
	 * <p>"x1.5" on its own reads as half again as much money. It is half again as
	 * much of the per-kill trickle, and the completion bonus — the larger half of
	 * every contract — is untouched. The sentence has to carry that or the number
	 * is worse than no number.
	 */
	@Test
	public void theWorthLineNamesTheKillHalfAndBothEndsOfTheLadder()
	{
		String worth = RevealOverlay.worthLine(
			new WeaponTypeService.WeaponType("scimitar", "Scimitar", 3901,
				Collections.singleton(AttackStyle.MELEE), true, null));
		Assert.assertTrue("it must say the bonus is on kill GC: " + worth,
			worth.contains("kill GC"));
		Assert.assertTrue("it must name the multiplier: " + worth,
			worth.contains(String.valueOf(Tuning.WEAPON_BONUS_MULT)));
		for (TaskDifficulty end : new TaskDifficulty[]{TaskDifficulty.EASY, TaskDifficulty.INSANE})
		{
			Assert.assertTrue("it must name " + end.getDisplayName() + ": " + worth,
				worth.contains(end.getDisplayName()));
			Assert.assertTrue("it must carry " + end.getDisplayName() + "'s figure: " + worth,
				worth.contains("+" + RevealOverlay.weaponWorthPct(end) + "%"));
		}
	}

	/**
	 * No caption line is wider than the canvas it is centred on.
	 *
	 * <p>Centred text is not clipped anywhere in this ceremony, so a line too wide
	 * for the canvas runs off BOTH edges and loses its beginning and its end at
	 * once. The bound is the fixed-mode client's 765px canvas less a margin, which
	 * is the smallest screen any of this is drawn on.
	 */
	@Test
	public void everyCaptionLineFitsTheNarrowestClient() throws Exception
	{
		BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			int budget = 765 - 40;
			FontMetrics body = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD, 14));
			FontMetrics small = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			for (WeaponTypeService.WeaponType type : taxonomy())
			{
				String name = RevealOverlay.weaponLine(type);
				Assert.assertTrue("too wide for a 765px canvas: " + name,
					body.stringWidth(name) <= budget);
			}
			for (String line : new String[]{
				RevealOverlay.worthLine(null),
				RevealOverlay.worthLine(taxonomy().get(0))})
			{
				Assert.assertTrue("too wide for a 765px canvas: " + line,
					small.stringWidth(line) <= budget);
			}
		}
		finally
		{
			g.dispose();
		}
	}
}
