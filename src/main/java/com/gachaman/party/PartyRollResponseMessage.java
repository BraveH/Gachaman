package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A member's stance on a proposed party roll: AGREE joins (with roll
 * context), DECLINE cancels the proposal party-wide, BUSY excuses a member
 * who already has an active contract.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollResponseMessage extends PartyMemberMessage
{
	public static final int AGREE = 0;
	public static final int DECLINE = 1;
	public static final int BUSY = 2;

	private long proposalId;
	private int response;
	private long seedCandidate;
	private boolean members;
	private int combatLevel;
	private int slayerLevel;
}
