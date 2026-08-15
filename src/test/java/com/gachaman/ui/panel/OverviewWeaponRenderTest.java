package com.gachaman.ui.panel;

import com.gachaman.model.*;
import com.gachaman.service.*;
import java.util.*;
import javax.swing.*;
import javax.swing.text.*;
import org.junit.*;

/**
 * The Preferred Weapon block as a player actually reads it, built against the
 * REAL shipped taxonomy and the real Tuning tables.
 *
 * <p>Every other test of this block checks a number in isolation. This one
 * assembles the lines, which is the only way to catch the two mistakes that
 * would survive all of them: the persisted key reaching the screen instead of
 * the display name, and the honesty sentence quietly losing its figures.
 *
 * <p>Headless-safe — nothing here realises a window, and the panel's own
 * helpers only measure text, which AWT does without a display.
 */
public class OverviewWeaponRenderTest
{
	/**
	 * Only the taxonomy is wired: addWeaponLines reads that and the state it is
	 * handed, and touches no other collaborator. The same all-nulls arrangement
	 * ShopChargeButtonTest uses on the other tab.
	 */
	private static OverviewTab tab()
	{
		return new OverviewTab(null, null, null, null, null, null,
			WeaponTypeFixture.taxonomy(), null);
	}

	private static GachaState state(String weaponKey, ActiveTask task)
	{
		return GachaState.fresh(50).withAllowedStyle(AttackStyle.MELEE.name())
			.withPreferredWeaponType(weaponKey).withActiveTask(task);
	}

	/** An ordinary Easy contract: 20 kills at 4 GC, 400 GC to finish. */
	private static ActiveTask easyContract()
	{
		return ActiveTask.builder().difficulty(TaskDifficulty.EASY).monsterName("Goblin")
			.killsRequired(20).perKillGc(4).completionGc(400).build();
	}

	/** Every word the block put on screen, labels and wrapped paragraphs alike. */
	private static String rendered(GachaState state)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		tab().addWeaponLines(section, state, AttackStyle.MELEE);
		StringBuilder text = new StringBuilder();
		for (java.awt.Component child : section.getComponents())
		{
			if (child instanceof JLabel)
			{
				text.append(((JLabel) child).getText()).append('\n');
			}
			else if (child instanceof JEditorPane)
			{
				Document doc = ((JEditorPane) child).getDocument();
				try
				{
					text.append(doc.getText(0, doc.getLength())).append('\n');
				}
				catch (BadLocationException e)
				{
					throw new AssertionError(e);
				}
			}
		}
		return text.toString();
	}

	/**
	 * The owner's rule, end to end. Category 0 is reported for every non-weapon
	 * item a player might be holding, so its name is "No weapon equipped" — while
	 * the key it is persisted under, and the word this line must never contain,
	 * is "unarmed".
	 */
	@Test
	public void categoryZeroReadsAsNoWeaponEquippedAndNeverAsItsKey()
	{
		String shown = rendered(state("unarmed", easyContract()));
		Assert.assertTrue("the display name must be on screen: " + shown,
			shown.contains("No weapon equipped"));
		Assert.assertFalse("the persisted key must never reach the player: " + shown,
			shown.toLowerCase(Locale.ROOT).contains("narmed"));
	}

	/**
	 * The honesty sentence, with the numbers a player can check by hand: 20 kills
	 * at 4 GC is 80 GC of kill income against 400 GC of completion, so kill GC is
	 * a sixth of the 480 GC contract and the x1.5 buys about 8% of slack.
	 */
	@Test
	public void anEasyContractPrintsItsRealShareAndBreakEven()
	{
		String shown = rendered(state("axe", easyContract()));
		Assert.assertTrue("the category is named: " + shown, shown.contains("Axes"));
		Assert.assertTrue("the share of the contract is stated: " + shown,
			shown.contains("17%"));
		Assert.assertTrue("the contract's own total is stated: " + shown,
			shown.contains("480 GC"));
		Assert.assertTrue("the break-even is stated: " + shown, shown.contains("8% slower"));
		Assert.assertTrue("and what it means is stated: " + shown,
			shown.contains("pay cut"));
	}

	/**
	 * The same weapon on an Insane contract is worth nearly three times as much
	 * slack, which is the entire reason the panel prints a number instead of the
	 * multiplier. 100 kills at 28 GC against 3,600 GC to finish.
	 */
	@Test
	public void anInsaneContractPrintsAFarLargerBreakEven()
	{
		String shown = rendered(state("axe", ActiveTask.builder()
			.difficulty(TaskDifficulty.INSANE).monsterName("Abyssal demon")
			.killsRequired(100).perKillGc(28).completionGc(3600).build()));
		Assert.assertTrue("the share of the contract is stated: " + shown,
			shown.contains("44%"));
		Assert.assertTrue("the break-even is stated: " + shown, shown.contains("22% slower"));
	}

	/**
	 * A preference saved under a key this build no longer carries resolves to
	 * nothing, and nothing is what must be shown — not the key, and not an error.
	 */
	@Test
	public void aKeyThisBuildNoLongerKnowsReadsAsNoBonusRatherThanAsItself()
	{
		String shown = rendered(state("halberd_of_a_later_patch", easyContract()));
		Assert.assertTrue("no bonus, said plainly: " + shown,
			shown.contains("no bonus available"));
		Assert.assertFalse("the unknown key must not be echoed: " + shown,
			shown.contains("halberd_of_a_later_patch"));
	}

	@Test
	public void aRedemptionContractSaysWhyTheBonusIsWorthNothing()
	{
		String shown = rendered(state("axe", ActiveTask.builder()
			.difficulty(TaskDifficulty.HARD).monsterName("Anything").killsRequired(20)
			.perKillGc(0).completionGc(1900).redemption(true).build()));
		Assert.assertTrue("the category is still named: " + shown, shown.contains("Axes"));
		Assert.assertTrue("and the reason it pays nothing: " + shown,
			shown.contains("no bonus available"));
		Assert.assertFalse("a break-even here would be a division by an income of zero: "
			+ shown, shown.contains("slower"));
	}

	@Test
	public void noPreferenceAtAllIsNotDressedAsAFailure()
	{
		String shown = rendered(state(null, easyContract()));
		Assert.assertTrue(shown, shown.contains("no bonus available"));
		Assert.assertFalse("nothing here is the player's fault: " + shown,
			shown.toLowerCase(Locale.ROOT).contains("error"));
	}
}
