package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A participant's vote for one of the party roll's offers. The contract is
 * accepted (identically, on every client) only when EVERY participant's
 * latest vote lands on the same offer; re-voting is allowed until then.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollVoteMessage extends PartyMemberMessage
{
	private long proposalId;
	private int offerIndex;
}
