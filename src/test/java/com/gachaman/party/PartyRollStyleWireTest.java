package com.gachaman.party;

import com.gachaman.model.*;
import com.google.gson.*;
import org.junit.*;

/**
 * The allowed style rides the party handshake so the clash bonus can be
 * priced at accept. Mixed-version parties are the whole reason the field is a
 * nullable NAME: an older client omits it and Gson leaves null, where an int
 * ordinal would deserialize to 0 and silently fabricate MELEE.
 */
public class PartyRollStyleWireTest
{
	private final Gson gson = new Gson();

	@Test
	public void proposeFromAnOlderClientCarriesNoStyle()
	{
		PartyRollProposeMessage msg = gson.fromJson(
			"{\"proposalId\":7,\"seedCandidate\":3,\"members\":true,"
				+ "\"combatLevel\":50,\"slayerLevel\":12}", PartyRollProposeMessage.class);
		Assert.assertNull("an omitted style must not become MELEE", msg.getAllowedStyle());
		Assert.assertEquals(50, msg.getCombatLevel());
	}

	@Test
	public void responseFromAnOlderClientCarriesNoStyle()
	{
		PartyRollResponseMessage msg = gson.fromJson(
			"{\"proposalId\":7,\"response\":0,\"seedCandidate\":3,\"members\":false,"
				+ "\"combatLevel\":50,\"slayerLevel\":12}", PartyRollResponseMessage.class);
		Assert.assertNull(msg.getAllowedStyle());
		Assert.assertEquals(PartyRollResponseMessage.AGREE, msg.getResponse());
	}

	@Test
	public void styleRoundTripsOnBothMessages()
	{
		PartyRollProposeMessage propose = gson.fromJson(gson.toJson(
			new PartyRollProposeMessage(7L, 3L, true, 50, 12, "MAGIC",
				PartyRollService.ROLL_PROTOCOL, PartySizing.FIGHTING_WEIGHT.name(),
				java.util.List.of(), null)),
			PartyRollProposeMessage.class);
		Assert.assertEquals("MAGIC", propose.getAllowedStyle());

		PartyRollResponseMessage response = gson.fromJson(gson.toJson(
			new PartyRollResponseMessage(7L, PartyRollResponseMessage.AGREE, 3L, true, 50, 12,
				"RANGED", PartyRollService.ROLL_PROTOCOL, java.util.List.of(), null)),
			PartyRollResponseMessage.class);
		Assert.assertEquals("RANGED", response.getAllowedStyle());
	}

	@Test
	public void parseStyleRejectsGarbageInsteadOfThrowing()
	{
		Assert.assertEquals(AttackStyle.MAGIC, PartyRollService.parseStyle("MAGIC"));
		Assert.assertNull("a member who has not rolled yet", PartyRollService.parseStyle(null));
		Assert.assertNull("a style name this build does not know",
			PartyRollService.parseStyle("DRAGONFIRE"));
		Assert.assertNull(PartyRollService.parseStyle(""));
	}
}
