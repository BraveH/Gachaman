package com.gachaman.party;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The host settles the vote. Voting is by MAJORITY, not unanimity, and the
 * losing cases (a plurality at the deadline, or a tie broken by a draw) have
 * outcomes that cannot be derived identically on every client — the voter set
 * at the instant the timer fires differs per client, so two clients left to
 * decide alone could sign two different contracts.
 *
 * So exactly one authority decides, the same way {@link PartyRollStartMessage}
 * fixes the participant list: the host tallies, picks, and broadcasts THIS,
 * and every other client only applies it.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollResolveMessage extends PartyMemberMessage {
	/** A strict majority of the party picked this one. Binds the whole party. */
	public static final int MODE_MAJORITY = 0;
	/** No majority, but one contract led on votes. Binds the voters only. */
	public static final int MODE_PLURALITY = 1;
	/** No majority and the lead was tied, so it was drawn. Binds the voters only. */
	public static final int MODE_TIEBREAK = 2;

	private long proposalId;
	private int offerIndex;
	/** The FINAL contract roster — the whole party, or just the voters. */
	private List<Long> memberIds;
	private int mode;
	/**
	 * The Ante verdict: every member of that final roster consented, and the
	 * settled contract is one that can carry a wager.
	 *
	 * Host-decided for the same reason the contract is: unanimity is measured
	 * over a roster only the host knows, and a client that tallied it locally
	 * could stake against a roster the host had already narrowed. Each client
	 * still re-checks its OWN recorded consent before any GC moves, so a bad or
	 * spoofed true can bind nobody who did not personally say yes.
	 */
	private boolean ante;
}
