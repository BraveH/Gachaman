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
public class PartyRollVoteMessage extends PartyMemberMessage {
	private long proposalId;
	private int offerIndex;
	/**
	 * The Ante: this member is willing to stake, personally, if the contract
	 * turns out to be one that can carry a wager. A STANDING willingness rather
	 * than a per-offer one — the vote picks the contract, this says only whether
	 * this player wants a stake on whatever the party settles on.
	 *
	 * Absent from an older client's payload, which Gson defaults to false: a
	 * client that has never heard of the Ante silently votes no, and unanimity
	 * then fails, so a mixed-version party simply never stakes. That is the
	 * correct failure — nobody's GC moves without their own client saying so.
	 */
	private boolean ante;
}
