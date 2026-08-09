package com.gachaman.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A member's stance on a proposed party roll: AGREE joins (with roll context),
 * DECLINE sits this member out, BUSY excuses one who already has an active
 * contract.
 *
 * <p>DECLINE is per-member and does NOT cancel the proposal party-wide — this
 * javadoc used to say it did, and it never has. {@code evaluateProposal}
 * collects the AGREE stances and proceeds once two of them exist, which is why
 * the decline chat line promises "the others may still take a contract". Only
 * the host's {@code cancelRoll} ends a proposal for everyone.
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
	/** This member's locked attack style (AttackStyle name); null from a client that predates the clash bonus. */
	private String allowedStyle;
	/**
	 * Which roll rules this client implements; 0 from a client that predates
	 * Fighting Weight, which puts the whole party back on lowest-level sizing.
	 */
	private int rollProtocol;
	/**
	 * The {@link net.runelite.api.Quest} names this member has FINISHED, out of
	 * the ones that lock a monster in the table — see the propose message for
	 * why it is names and not a bitmask. The roll intersects every agreeing
	 * member's list, so a quest one member has not done withholds that monster
	 * from the whole party rather than handing three of them a contract the
	 * fourth cannot start.
	 */
	private java.util.List<String> completedQuests;
	/**
	 * The sender's {@link com.gachaman.service.AccountKey}; null from a client
	 * that predates it or one that is not logged in. Display and bookkeeping
	 * only — see the propose message for why it needs no protocol bump.
	 *
	 * MUST stay last — {@code @AllArgsConstructor} is positional.
	 */
	private String accountKey;
}
