package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member proposes a shared task roll. Every task-less member must
 * agree before the roll executes; members with an active contract auto-report
 * busy. The proposer's roll context (seed candidate, membership, levels)
 * rides along — the seed of the LOWEST member id among the agreeing
 * participants is the one every client rolls with, so all screens see
 * identical offers.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollProposeMessage extends PartyMemberMessage
{
	private long proposalId;
	private long seedCandidate;
	private boolean members;
	private int combatLevel;
	private int slayerLevel;
	/**
	 * The proposer's locked attack style (AttackStyle name), for the style
	 * clash bonus. A NAME rather than an ordinal: an older client omits the
	 * field entirely and Gson leaves a String null ("unknown, ignore"),
	 * whereas an int would deserialize to 0 and fabricate MELEE.
	 */
	private String allowedStyle;
	/**
	 * Which roll RULES this client implements. Absent from an older client, and
	 * Gson leaves an int 0 — which is exactly the "predates Fighting Weight"
	 * answer, so the whole party falls back to the old lowest-combat-level
	 * sizing rather than rolling two different boards from one shared seed.
	 *
	 * MUST stay the last field: {@code @AllArgsConstructor} is positional, so
	 * inserting above this silently reorders every existing call site.
	 */
	private int rollProtocol;
}
