package com.gachaman.ui.panel;

import com.gachaman.*;
import com.gachaman.service.*;
import javax.swing.*;
import org.junit.*;

/**
 * The two style-charge buttons are built by one shared helper now, and these
 * are the strings a player reads on them.
 *
 * <p>Collapsing the Compactor and Extender blocks into {@code chargeButton}
 * moved four player-visible pieces — the price label, the FREE-voucher label,
 * the explanation and the voucher sentence bolted onto its end — across a
 * method boundary, and re-split the long tooltip prose at a different point in
 * its concatenation. All of that is supposed to produce byte-identical text.
 * "Supposed to" is exactly the kind of claim worth a test, especially when the
 * only cost is a test file, which the token budget does not count.
 *
 * <p>The literals below are copied from the code as it stood BEFORE the helper
 * existed, not from the helper, or this would assert that the code equals
 * itself.
 */
public class ShopChargeButtonTest
{
	/**
	 * chargeButton touches no collaborator — it formats text, reads the two
	 * numbers it was handed and hangs a listener that only runs on a click — so
	 * an instance with nothing injected is enough to exercise it.
	 */
	private static ShopTab tab()
	{
		return new ShopTab(new GachaStateService(null), null, null, null, null, null,
			null, null);
	}

	@Test
	public void theCompactorButtonReadsAsItAlwaysHas()
	{
		// no voucher, enough GC: the priced label
		JButton priced = tab().chargeButton(true, "Compactor", Tuning.COMPACTOR_PRICE_GC, 0,
			Tuning.COMPACTOR_PRICE_GC, "This contract counts double toward the style cycle,"
				+ " and each kill counts double toward the contract itself (the skipped count"
				+ " pays no GC).");
		Assert.assertEquals("Compactor — 400 GC", priced.getText());
		Assert.assertEquals("This contract counts double toward the style cycle, and each"
			+ " kill counts double toward the contract itself (the skipped count pays no GC).",
			priced.getToolTipText());
		Assert.assertTrue("affordable at exactly the price", priced.isEnabled());
	}

	@Test
	public void aVoucherChangesTheLabelAndAppendsTheVoucherSentence()
	{
		// one voucher, no GC at all: still enabled, and the voucher sentence lands
		JButton free = tab().chargeButton(false, "Extender", Tuning.EXTENDER_PRICE_GC, 1, 0,
			"This contract counts only half toward the style cycle.");
		Assert.assertEquals("Extender — FREE voucher", free.getText());
		Assert.assertEquals("This contract counts only half toward the style cycle."
			+ " Uses your free voucher — no GC.", free.getToolTipText());
		Assert.assertTrue("a voucher pays for it whatever the balance", free.isEnabled());
	}

	@Test
	public void tooLittleGcAndNoVoucherDisablesIt()
	{
		JButton broke = tab().chargeButton(true, "Compactor", Tuning.COMPACTOR_PRICE_GC, 0,
			Tuning.COMPACTOR_PRICE_GC - 1, "irrelevant");
		Assert.assertFalse(broke.isEnabled());
	}
}
