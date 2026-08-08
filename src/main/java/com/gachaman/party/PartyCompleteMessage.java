package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The sender's client completed the shared contract — a sync backstop so a
 * participant whose kill messages lagged still completes with the party.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyCompleteMessage extends PartyMemberMessage
{
	private long proposalId;
}
