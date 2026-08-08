package com.gachaman.persist;

import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Variant;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class StateCodecTest
{
	private final StateCodec codec = new StateCodec(new Gson());

	@Test
	public void roundTripPreservesState()
	{
		GachaState state = GachaState.fresh(42);
		List<OwnedCard> cards = new ArrayList<>();
		cards.add(new OwnedCard("u1", 1333, null, Variant.NORMAL, 123L, "chest:BATTERED"));
		cards.add(new OwnedCard("u2", -1, "dragon", Variant.HOLOGRAM, 456L, "chest:ORNATE"));
		state = state.withOwnedCards(cards).withGc(12345).withTaint(3).withAllowedStyle("MAGIC")
			.withStardust(5).withStardustBlessArmed(true)
			.withFreeCompactors(1).withFreeExtenders(1).withStarterVouchersGranted(true)
			.withDeedFragments(7).withFragmentDeedForged(true);

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertEquals(12345, decoded.getGc());
		Assert.assertEquals(3, decoded.getTaint());
		Assert.assertEquals("MAGIC", decoded.getAllowedStyle());
		Assert.assertEquals(2, decoded.getOwnedCards().size());
		Assert.assertEquals("dragon", decoded.getOwnedCards().get(1).getTierKey());
		Assert.assertTrue(decoded.getOwnedCards().get(1).isHologram());
		Assert.assertEquals(3, decoded.getDeededSlots().size()); // weapon + body + ammo
		Assert.assertEquals(5, decoded.getStardust());
		Assert.assertTrue(decoded.isStardustBlessArmed());
		Assert.assertEquals(1, decoded.getFreeCompactors());
		Assert.assertEquals(1, decoded.getFreeExtenders());
		Assert.assertTrue(decoded.isStarterVouchersGranted());
		Assert.assertEquals(7, decoded.getDeedFragments());
		Assert.assertTrue(decoded.isFragmentDeedForged());
	}

	@Test
	public void legacyPayloadNormalizesNewFields()
	{
		// a pre-feature save: none of the early-game fields exist in the JSON
		GachaState legacy = new Gson().fromJson("{\"gc\":5,\"totalTasksCompleted\":2}", GachaState.class);
		Assert.assertNull(legacy.getFirstsClaimed());
		GachaState normalized = legacy.normalized();
		Assert.assertEquals(5, normalized.getGc());
		Assert.assertNotNull(normalized.getFirstsClaimed());
		Assert.assertNotNull(normalized.getSpeciesDiscovered());
		Assert.assertNotNull(normalized.getSlotBestTierRank());
		Assert.assertNotNull(normalized.getTimeline());
		Assert.assertNotNull(normalized.getOwnedCards());
		Assert.assertNotNull(normalized.getDeededSlots());
		Assert.assertNotNull(normalized.getQueuedThemedChests());
		Assert.assertEquals(0, normalized.getStardust());
		Assert.assertFalse(normalized.isStardustBlessArmed());
		Assert.assertEquals(0, normalized.getFreeCompactors());
		Assert.assertEquals(0, normalized.getFreeExtenders());
		Assert.assertFalse("false flag is the migration trigger", normalized.isStarterVouchersGranted());
		Assert.assertEquals(0, normalized.getDeedFragments());
		Assert.assertFalse(normalized.isFragmentDeedForged());
	}

	@Test
	public void tamperedPayloadRefusesToLoad()
	{
		GachaState state = GachaState.fresh(3);
		String blob = codec.encode(state);
		// flip a chunk of the base64 body
		char[] chars = blob.toCharArray();
		int mid = chars.length / 2;
		chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
		GachaState decoded = codec.decode(new String(chars));
		Assert.assertNull(decoded);
	}

	@Test
	public void sha256IsStable()
	{
		Assert.assertEquals(StateCodec.sha256("gachaman"), StateCodec.sha256("gachaman"));
		Assert.assertNotEquals(StateCodec.sha256("a"), StateCodec.sha256("b"));
	}

	@Test
	public void garbageReturnsNullNotThrow()
	{
		Assert.assertNull(codec.decode(null));
		Assert.assertNull(codec.decode(""));
		Assert.assertNull(codec.decode("not-base64!!"));
	}
}
