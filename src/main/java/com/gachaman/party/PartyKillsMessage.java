package com.gachaman.party;

import lombok.*;
import net.runelite.client.party.messages.*;

/** The sender's own kill count on the shared party contract. */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyKillsMessage extends PartyMemberMessage {
	private long proposalId;
	private int kills;
}
