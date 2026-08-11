package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Host-only: aborts the party roll for every client that joined it — during
 * the answer-collection phase or the voting phase. An already-accepted
 * shared contract is binding (contracts cannot be abandoned) and is NOT
 * affected by this.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollCancelMessage extends PartyMemberMessage {
	private long proposalId;
}
