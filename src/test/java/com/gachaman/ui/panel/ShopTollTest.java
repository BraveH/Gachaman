package com.gachaman.ui.panel;

import com.gachaman.data.*;
import com.gachaman.service.*;
import com.google.gson.*;
import org.junit.*;

/**
 * The Toll's purchase row, in the two places where a wrong word would be a
 * wrong claim rather than a typo.
 *
 * <p>The row itself cannot be driven headlessly — it needs a live album, a
 * loadout map, a card database and a week key — so what is pinned here is every
 * decision the row makes that is NOT a Swing call: which sentence each of the
 * three Toll states gets, and how a tier ladder is named. Both have a failure
 * mode that ships silently: an untiered card printing "null" on the shop page,
 * or the two no-offer states collapsing into one sentence that tells a player
 * to wait a week for something that is actually arriving on its own.
 */
public class ShopTollTest
{
	/**
	 * tierLabel and tollPull read the tier table and nothing else — no state, no
	 * client, no chest service — so an instance with only that injected is
	 * enough. Same arrangement ShopChargeButtonTest already uses.
	 */
	private static ShopTab tab()
	{
		return new ShopTab(new GachaStateService(null), null, null, null, null, null,
			null, null, null, TierTable.load(new Gson()));
	}

	@Test
	public void anUntieredCardNeverPrintsARawNullOrAKey()
	{
		ShopTab tab = tab();
		// the untiered band is a real offer: the owner settled Toll eligibility as
		// "not a hologram and served at least one kill", which admits cards with no
		// tier ladder at all, and TierTable.displayNameOf would throw on the null
		Assert.assertEquals("No tier gate", tab.tierLabel(null));
		String pull = tab.tollPull(null);
		Assert.assertTrue("the untiered pull must say so: " + pull,
			pull.contains("untiered"));
		Assert.assertFalse("a null tier must never reach the player: " + pull,
			pull.toLowerCase(java.util.Locale.ROOT).contains("null"));
	}

	@Test
	public void aTieredCardNamesItsLadderInWords()
	{
		ShopTab tab = tab();
		String label = tab.tierLabel("rune");
		Assert.assertFalse("the raw key must never be shown: " + label, label.contains("_"));
		Assert.assertTrue("the ladder's own name belongs in the pull: " + tab.tollPull("rune"),
			tab.tollPull("rune").contains(label));
		Assert.assertTrue("the pull is one card and says so: " + tab.tollPull("rune"),
			tab.tollPull("rune").contains("one blind"));
	}

	/**
	 * The two no-offer states mean opposite things. "Paid" is a wait for the week
	 * to turn; "nothing eligible" ends the moment any assigned card is present for
	 * a kill, which can be this afternoon. One sentence for both would send half
	 * the players who read it away for six days.
	 */
	@Test
	public void theTwoNoOfferStatesDoNotShareASentence()
	{
		String paid = ShopTab.tollNoOffer(true);
		String nothing = ShopTab.tollNoOffer(false);
		Assert.assertNotEquals(paid, nothing);
		Assert.assertTrue("paid points at next week: " + paid, paid.contains("next week"));
		Assert.assertFalse("nothing-eligible must NOT point at next week: " + nothing,
			nothing.contains("next week"));
		Assert.assertTrue("it must name what actually ends the wait: " + nothing,
			nothing.contains("first kill"));
	}

	@Test
	public void aServiceRecordOfOneIsNotPluralised()
	{
		Assert.assertEquals("1 kill", ShopTab.killsServed(1));
		Assert.assertEquals("0 kills", ShopTab.killsServed(0));
		Assert.assertEquals("342 kills", ShopTab.killsServed(342));
	}
}
