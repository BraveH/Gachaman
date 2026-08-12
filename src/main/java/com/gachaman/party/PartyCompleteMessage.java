package com.gachaman.party;

import lombok.*;
import net.runelite.client.party.messages.*;

/**
 * The sender's client completed the shared contract — a sync backstop so a
 * participant whose kill messages lagged still completes with the party.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyCompleteMessage extends PartyMemberMessage {
	private long proposalId;
}
