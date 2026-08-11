package com.gachaman.party;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The proposer fixes the FINAL participant set and starts the roll. This is
 * what keeps every client deterministic: with a "whoever agreed by the
 * deadline" rule, a last-second agree could be seen by some clients and not
 * others — so exactly one authority (the proposer) decides the list, and
 * every listed client rolls from it with the shared anchor seed.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollStartMessage extends PartyMemberMessage {
	private long proposalId;
	private List<Long> participantIds;
}
