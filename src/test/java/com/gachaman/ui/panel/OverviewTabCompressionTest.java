package com.gachaman.ui.panel;

import com.gachaman.*;
import com.gachaman.service.*;
import java.awt.*;
import javax.swing.*;
import org.junit.*;

/**
 * Facts the Overview panel used to state in code and now relies on silently.
 *
 * <p>Compressing that panel replaced two explicit structures with implicit
 * ones. A materialised {@code int[]} of Ante percentages became a bare
 * arithmetic progression, and a hand-built header row became a call to the
 * shared {@link GachamanPanel#row} helper. Both are exactly equivalent today.
 * Both would stop being equivalent if something OUTSIDE this file moved — a
 * Tuning constant in the first case, a helper in another class in the second —
 * and in neither case would the compiler notice.
 *
 * <p>So the equivalences are asserted rather than assumed. Nothing here tests
 * the compression itself; each test pins the external fact that makes the
 * compressed form correct.
 */
public class OverviewTabCompressionTest
{
	/**
	 * The Ante combo box lists ten-point stakes and the Arm listener turns the
	 * selected INDEX back into a percentage with {@code ANTE_MIN_PERCENT + 10 *
	 * index}. That inverse is only right while the band starts on the minimum,
	 * ends on the maximum, and divides evenly by ten — which the deleted
	 * {@code antePercentChoices()} array used to guarantee by construction.
	 *
	 * <p>A Tuning edit to an odd band (say 10..55) would leave the top stake
	 * unofferable, and a click would still arm a legal-looking wager, so this
	 * fails loudly instead.
	 */
	@Test
	public void everyAnteStopIsRecoverableFromItsComboIndexAlone()
	{
		Assert.assertTrue("the Ante band has to run upwards",
			Tuning.ANTE_MIN_PERCENT <= Tuning.ANTE_MAX_PERCENT);
		Assert.assertEquals("the band must divide evenly into ten-point stops, or the"
				+ " maximum stake is never offered",
			0, (Tuning.ANTE_MAX_PERCENT - Tuning.ANTE_MIN_PERCENT) % 10);

		// the same loop addAnteControls uses to fill the combo box
		int index = 0;
		int last = -1;
		for (int percent = Tuning.ANTE_MIN_PERCENT; percent <= Tuning.ANTE_MAX_PERCENT;
			percent += 10)
		{
			Assert.assertEquals("combo index " + index + " must map back to its own stop",
				percent, Tuning.ANTE_MIN_PERCENT + 10 * index);
			last = percent;
			index++;
		}
		Assert.assertEquals("the first item is the floor of the band",
			Tuning.ANTE_MIN_PERCENT, Tuning.ANTE_MIN_PERCENT + 10 * 0);
		Assert.assertEquals("the last item is the ceiling of the band",
			Tuning.ANTE_MAX_PERCENT, last);
		Assert.assertEquals("the item count is what the old int[] length was",
			(Tuning.ANTE_MAX_PERCENT - Tuning.ANTE_MIN_PERCENT) / 10 + 1, index);
		Assert.assertEquals("nothing selected (-1) has to clamp to the floor, not"
				+ " underflow below it",
			Tuning.ANTE_MIN_PERCENT, Tuning.ANTE_MIN_PERCENT + 10 * Math.max(0, -1));
	}

	/**
	 * The Contract header hands its name label and Wiki button to
	 * {@link GachamanPanel#row}. That is only safe while row() keeps building the
	 * panel the header used to build by hand: BorderLayout with a 6px horizontal
	 * gap (the header's width arithmetic subtracts exactly that), transparent so
	 * the section's own background shows through, left-aligned for the enclosing
	 * BoxLayout, and left-in-CENTER / right-in-EAST.
	 */
	@Test
	public void theSharedRowHelperStillBuildsWhatTheContractHeaderNeeds()
	{
		JLabel left = new JLabel("monster");
		JButton right = new JButton("Wiki");
		JPanel row = GachamanPanel.row(left, right);

		Assert.assertFalse("an opaque row would paint over the section background",
			row.isOpaque());
		Assert.assertEquals("BoxLayout centres anything that is not left-aligned",
			Component.LEFT_ALIGNMENT, row.getAlignmentX(), 0f);
		Assert.assertTrue("BorderLayout is what puts the button hard against the"
			+ " right edge", row.getLayout() instanceof BorderLayout);

		BorderLayout layout = (BorderLayout) row.getLayout();
		Assert.assertEquals("the header sizes its name cell as the column width less"
			+ " the button and THIS gap", 6, layout.getHgap());
		Assert.assertSame("the name takes the elastic cell",
			left, layout.getLayoutComponent(BorderLayout.CENTER));
		Assert.assertSame("the Wiki button keeps its natural width on the right",
			right, layout.getLayoutComponent(BorderLayout.EAST));
	}

	/**
	 * row() pins a maximum height of its own, which the Contract header overwrites
	 * on the very next line so the row ends up only as tall as its tallest child.
	 * This asserts the overwrite actually takes — if a future row() ever pinned
	 * the height some other way (a layout manager minimum, an overridden
	 * getMaximumSize) the later setMaximumSize would quietly stop winning and the
	 * header would gain empty space under it.
	 *
	 * <p>Deliberately says nothing about WHICH value row() pins. Asserting that
	 * would couple this test to a constant in another class that the header does
	 * not depend on and in fact discards, so an edit there would fail here as
	 * though OverviewTab had regressed.
	 */
	@Test
	public void anExplicitMaximumSizeStillOverridesTheOneRowPins()
	{
		JPanel row = GachamanPanel.row(new JLabel("monster"), new JButton("Wiki"));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 17));
		Assert.assertEquals("the header's ceiling has to be the one that sticks",
			17, row.getMaximumSize().height);
		Assert.assertEquals(Integer.MAX_VALUE, row.getMaximumSize().width);
	}

	/**
	 * The Quest-unlocked rows deliberately do NOT use row(): they put the NPC
	 * name in WEST so it holds its width and the quest name gives way first.
	 * Now that the Contract header DOES use row(), that difference is one edit
	 * away from being "tidied" into consistency — which would elide the NPC name
	 * the player is trying to match against the monster in front of them.
	 */
	@Test
	public void theQuestUnlockRowKeepsTheNpcNameOnTheInelasticSide()
	{
		QuestExemptionService.Unlock unlock =
			new QuestExemptionService.Unlock("Kurask", "Fremennik Trials");
		JPanel row = OverviewTab.unlockRow(unlock);

		BorderLayout layout = (BorderLayout) row.getLayout();
		JLabel npc = (JLabel) layout.getLayoutComponent(BorderLayout.WEST);
		JLabel quest = (JLabel) layout.getLayoutComponent(BorderLayout.CENTER);
		Assert.assertEquals("Kurask", npc.getText());
		Assert.assertEquals("Fremennik Trials", quest.getText());
		Assert.assertEquals("the quest name is the half that may be elided",
			SwingConstants.RIGHT, quest.getHorizontalAlignment());
		Assert.assertNull("nothing belongs in EAST here — that is row()'s shape",
			layout.getLayoutComponent(BorderLayout.EAST));
	}
}
