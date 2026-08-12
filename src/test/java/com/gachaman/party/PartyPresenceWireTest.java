package com.gachaman.party;

import com.gachaman.model.*;
import com.google.gson.*;
import org.junit.*;

/**
 * The presence broadcast is the ONE channel every later party feature extends,
 * so both directions of version skew have to be safe: an older client's omitted
 * field must read as "not claimed" rather than fabricate a value, and a newer
 * client's extra field must not break the message for anyone.
 */
public class PartyPresenceWireTest
{
	private final Gson gson = new Gson();

	@Test
	public void olderClientPresenceFabricatesNothing()
	{
		GachaPresenceMessage msg = gson.fromJson("{\"combatLevel\":70}",
			GachaPresenceMessage.class);
		Assert.assertNull("an omitted style must not become MELEE", msg.getAllowedStyle());
		Assert.assertNull("no contract, not a phantom one", msg.getActiveTaskName());
		Assert.assertEquals(0, msg.getKillsRequired());
		Assert.assertEquals(0, msg.getKillsDone());
		Assert.assertFalse(msg.isTainted());
		Assert.assertEquals(70, msg.getCombatLevel());
		Assert.assertNull("an omitted key is an unknown account, not a blank one",
			msg.getAccountKey());
		Assert.assertFalse("an omitted flag reads as 'nothing pending', which is what"
			+ " a client that never had a board is", msg.isUndecidedOffers());
		Assert.assertNull("a BOXED id, so an omission cannot read as a real claim of 0",
			msg.getPartyContractId());
	}

	@Test
	public void styleTravelsAsANameNotAnOrdinal()
	{
		GachaPresenceMessage magic = gson.fromJson(gson.toJson(
			new GachaPresenceMessage("MAGIC", 82, "Zulrah", 3, 25, false, null, false, null)),
			GachaPresenceMessage.class);
		Assert.assertEquals("MAGIC", magic.getAllowedStyle());
		Assert.assertEquals(AttackStyle.MAGIC, PartyRollService.parseStyle(magic.getAllowedStyle()));
		Assert.assertEquals("Zulrah", magic.getActiveTaskName());
		Assert.assertEquals(3, magic.getKillsDone());
		Assert.assertEquals(25, magic.getKillsRequired());

		GachaPresenceMessage future = gson.fromJson("{\"allowedStyle\":\"SHADOW\"}",
			GachaPresenceMessage.class);
		Assert.assertNull("a fourth style must not crash an old client",
			PartyRollService.parseStyle(future.getAllowedStyle()));
	}

	@Test
	public void aNewerClientsExtraFieldIsIgnored()
	{
		// proves the documented badge seam (add a FIELD here, never a second
		// message class) is safe in both directions
		GachaPresenceMessage msg = gson.fromJson("{\"combatLevel\":3,\"patronMark\":true}",
			GachaPresenceMessage.class);
		Assert.assertEquals(3, msg.getCombatLevel());
	}

	@Test
	public void memberIdIsNotOnTheWire()
	{
		String json = gson.toJson(
			new GachaPresenceMessage("RANGED", 60, null, 0, 0, true, null, false, null));
		Assert.assertFalse("PartyMemberMessage.memberId is transient and stamped on receipt,"
			+ " so a sender-supplied id could never be trusted", json.contains("memberId"));
		Assert.assertTrue(json.contains("RANGED"));
	}

	@Test
	public void taintTravelsAsAFlag()
	{
		GachaPresenceMessage clean = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, null, 0, 0, false, null, false, null)),
			GachaPresenceMessage.class);
		Assert.assertFalse(clean.isTainted());
		GachaPresenceMessage dirty = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, null, 0, 0, true, null, false, null)),
			GachaPresenceMessage.class);
		Assert.assertTrue(dirty.isTainted());
	}

	@Test
	public void identityEligibilityAndContractRoundTripWithoutMovingTheOlderFields()
	{
		// @AllArgsConstructor is positional, so a field inserted rather than
		// APPENDED silently re-points every existing call site: the assertions
		// on the older fields are the only thing that would catch it
		GachaPresenceMessage msg = gson.fromJson(gson.toJson(
			new GachaPresenceMessage("MELEE", 99, "Goblin", 4, 12, true,
				"00112233445566aa", true, 0L)), GachaPresenceMessage.class);
		Assert.assertEquals("MELEE", msg.getAllowedStyle());
		Assert.assertEquals(99, msg.getCombatLevel());
		Assert.assertEquals("Goblin", msg.getActiveTaskName());
		Assert.assertEquals(4, msg.getKillsDone());
		Assert.assertEquals(12, msg.getKillsRequired());
		Assert.assertTrue(msg.isTainted());
		Assert.assertEquals("00112233445566aa", msg.getAccountKey());
		Assert.assertTrue(msg.isUndecidedOffers());
		Assert.assertEquals(Long.valueOf(0L), msg.getPartyContractId());
	}

	@Test
	public void aClaimedContractIdOfZeroSurvivesTheWireAsAClaim()
	{
		// the whole reason partyContractId is boxed: proposal ids come from
		// nextLong(), so 0 is as legal as any other, and a primitive would make
		// that member indistinguishable from one who claims nothing
		GachaPresenceMessage zero = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, "Goblin", 0, 12, false, null, false, 0L)),
			GachaPresenceMessage.class);
		Assert.assertNotNull(zero.getPartyContractId());
		Assert.assertEquals(0L, zero.getPartyContractId().longValue());

		GachaPresenceMessage silent = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, "Goblin", 0, 12, false, null, false, null)),
			GachaPresenceMessage.class);
		Assert.assertNull(silent.getPartyContractId());
	}
}
