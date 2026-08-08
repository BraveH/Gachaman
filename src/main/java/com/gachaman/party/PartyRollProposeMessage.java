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
}
