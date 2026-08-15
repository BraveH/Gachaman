package com.gachaman;

import java.nio.charset.*;
import java.nio.file.*;
import org.junit.*;

/**
 * The three commissioned features each need a hook set from
 * {@link GachamanPlugin}, and every one of them fails SILENTLY when it is
 * missing. That is what makes them worth a test rather than a code review.
 *
 * <ul>
 *   <li>No {@code setPresenter} and {@code ConsignmentService.offerOrRoll} takes
 *       the ordinary roll every single time. No error, no log line, no chat
 *       notice — the Consignment simply never appears, and the only way to
 *       notice is to know it should have.</li>
 *   <li>No {@code setDealer} and {@code TollService.purchase} log-warns and
 *       returns null, which the shop panel renders as "a reveal may already be
 *       in progress" — a message that names the wrong cause forever.</li>
 *   <li>No {@code drainOwedRoll} and a style roll owed by a Consignment offer
 *       that died with the client is never taken. The cycle self-heals on the
 *       next contract, so even this one is invisible rather than broken.</li>
 *   <li>No {@code abandon} on the profile switch and a live offer leaks across
 *       accounts.</li>
 * </ul>
 *
 * <p>A source scan, following {@code UnassignCallSiteTest}'s precedent and for
 * the same reason: standing the real plugin up needs a Client, a ConfigManager,
 * an OverlayManager and the whole injector, while the invariant here is simply
 * "these four calls exist in this file". Scans cost nothing — the Hub bot counts
 * only src/main/java.
 */
public class FeatureWiringTest
{
	private static String plugin() throws Exception
	{
		return new String(Files.readAllBytes(
			Paths.get("src/main/java/com/gachaman/GachamanPlugin.java")), StandardCharsets.UTF_8);
	}

	/**
	 * Strips comments before matching, so that a call which exists only inside a
	 * javadoc block explaining that it OUGHT to exist cannot satisfy the test.
	 * That is a real failure mode here: every one of these hooks was described in
	 * prose in a handover note before it was written.
	 */
	private static String code() throws Exception
	{
		return plugin()
			.replaceAll("(?s)/\\*.*?\\*/", "")
			.replaceAll("//[^\n]*", "");
	}

	@Test
	public void theConsignmentHasAPresenter() throws Exception
	{
		Assert.assertTrue("GachamanPlugin must call consignmentService.setPresenter(revealOverlay) —"
				+ " without it the Consignment silently never appears",
			code().contains("consignmentService.setPresenter("));
	}

	@Test
	public void theTollHasAPullDealer() throws Exception
	{
		Assert.assertTrue("GachamanPlugin must call tollService.setDealer(...) —"
				+ " without it every Toll purchase refuses and blames a pending reveal",
			code().contains("tollService.setDealer("));
	}

	@Test
	public void theOwedStyleRollIsDrainedAtLogin() throws Exception
	{
		String code = code();
		Assert.assertTrue("GachamanPlugin must call consignmentService.drainOwedRoll(...)",
			code.contains("consignmentService.drainOwedRoll("));

		// It must sit in the state-load block, not the LOGGED_IN branch: state is
		// still null at LOGGED_IN, so a drain fired there no-ops forever. The
		// recoverPending() call is the anchor for that block.
		int drain = code.indexOf("consignmentService.drainOwedRoll(");
		int recover = code.indexOf("chestService.recoverPending()");
		Assert.assertTrue("recoverPending() should be present as the state-load anchor", recover > 0);
		Assert.assertTrue("drainOwedRoll must sit in the state-load block beside recoverPending(),"
				+ " where state actually exists — not in the LOGGED_IN branch",
			drain > recover && drain - recover < 900);
	}

	@Test
	public void aProfileSwitchAbandonsALiveConsignment() throws Exception
	{
		Assert.assertTrue("GachamanPlugin must call consignmentService.abandon() when the RS profile"
				+ " changes — ceremonyBus.clear() cannot reach the service's own live offer",
			code().contains("consignmentService.abandon()"));
	}

	/**
	 * The Toll's dealer must be ChestService's tier-scoped opener specifically.
	 * Routing it at {@code openThemedChest} would compile and would silently deal
	 * an ordinary chest, because {@code setMembers()} returns empty for a tier key
	 * and the pool falls back to every card in the game.
	 */
	@Test
	public void theTollDealerIsTheTierScopedOpener() throws Exception
	{
		Assert.assertTrue("the Toll must be dealt by ChestService.openTollChest",
			code().contains("openTollChest"));
		Assert.assertFalse("openThemedChest must never be the Toll's dealer — a tier key is not a"
				+ " set tag, so it would fall back to the all-cards pool",
			code().contains("tollService.setDealer(chestService::openThemedChest"));
	}
}
