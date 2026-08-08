package com.gachaman.party;

import com.gachaman.model.AttackStyle;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

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
	}

	@Test
	public void styleTravelsAsANameNotAnOrdinal()
	{
		GachaPresenceMessage magic = gson.fromJson(gson.toJson(
			new GachaPresenceMessage("MAGIC", 82, "Zulrah", 3, 25, false)),
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
		String json = gson.toJson(new GachaPresenceMessage("RANGED", 60, null, 0, 0, true));
		Assert.assertFalse("PartyMemberMessage.memberId is transient and stamped on receipt,"
			+ " so a sender-supplied id could never be trusted", json.contains("memberId"));
		Assert.assertTrue(json.contains("RANGED"));
	}

	@Test
	public void taintTravelsAsAFlag()
	{
		GachaPresenceMessage clean = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, null, 0, 0, false)), GachaPresenceMessage.class);
		Assert.assertFalse(clean.isTainted());
		GachaPresenceMessage dirty = gson.fromJson(gson.toJson(
			new GachaPresenceMessage(null, 3, null, 0, 0, true)), GachaPresenceMessage.class);
		Assert.assertTrue(dirty.isTainted());
	}
}
