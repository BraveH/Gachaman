package com.gachaman.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@With
@Builder(toBuilder = true)
public class ActiveTask
{
	TaskDifficulty difficulty;
	String monsterName;
	int monsterCombatLevel;
	int killsRequired;
	int killsDone;
	int perKillGc;
	int completionGc;
	List<SideBet> sideBets;
	boolean redemption;
	long acceptedAtMs;
	/** COMPACTOR / EXTENDER charge consumed by this task, or null. */
	String appliedCharge;
	/**
	 * Ironman assisted-kill carry: an assisted kill counts half, so the first
	 * one banks a pending half and the second completes the count.
	 */
	boolean halfKillPending;
	/**
	 * The Contract Dossier: kills landed on this contract while out of the
	 * allowed style. A COUNT rather than a "clean" boolean, because either
	 * polarity of a boolean lies about a contract signed before this field
	 * existed — "clean" would deserialize false and brand an innocent contract,
	 * "tainted" would deserialize false and vouch for one nobody watched. Zero
	 * is simply "no violations recorded", which is exactly true, and the
	 * counter re-arms on the very next out-of-style kill.
	 */
	int taintedKills;
	/**
	 * Double Docket: the target matched the live Slayer assignment at accept
	 * time, or at any kill since. A STICKY LATCH — once on it never turns off,
	 * so finishing the Slayer task partway through a contract cannot retract a
	 * bonus the player has already been told they have.
	 *
	 * No @SerializedName alias: this field has never been renamed, and Gson's
	 * missing-field default of false is exactly right for a contract signed
	 * before this feature existed — it pays base, and re-latches on its next
	 * kill if the assignment does in fact match.
	 */
	boolean slayerAligned;
	/**
	 * The Ante: GC escrowed out of the purse when this contract was signed, or 0
	 * for the ordinary no-wager contract. Completing returns it doubled; dying
	 * zeroes it here, which is what makes the loss final — the GC left the purse
	 * at accept time, so a forfeited stake is simply never given back.
	 *
	 * No @SerializedName alias: the field has never been renamed, and Gson's
	 * missing-field default of 0 is exactly right for a contract signed before
	 * this feature existed — nothing was staked, so nothing is returned or lost.
	 */
	int anteStake;

	// ---------------------------------------------------------------------
	// Shared party-contract fields (null/0 when solo).
	//
	// These four carry @SerializedName aliases because they were named duo*
	// until the party rename, and this class is serialized into GachaState.
	// Without the alias a player holding a shared contract at upgrade time
	// would silently load partyLabel == null, so isParty() would go false and
	// the contract would quietly lose BOTH the co-op multiplier and every
	// pooled kill — with no error to explain it. SCHEMA_VERSION deliberately
	// stays 1: bumping it makes these saves unloadable on downgraded clients,
	// which is a far worse failure than the one we are avoiding.
	// ---------------------------------------------------------------------
	@SerializedName(value = "partyLabel", alternate = {"duoPartnerName"})
	String partyLabel;
	@SerializedName(value = "partyAnchorId", alternate = {"duoPartnerMemberId"})
	long partyAnchorId;
	@SerializedName(value = "partyOtherKills", alternate = {"duoPartnerKills"})
	int partyOtherKills;
	/**
	 * EVERY participant's style as of signing (self included), or null when
	 * solo. The clash bonus is fixed at accept like the rest of the contract
	 * terms, so a style re-roll mid-contract cannot reprice it. Entries may be
	 * null: a participant on an older client sends no style.
	 */
	List<AttackStyle> partyStyles;
	@SerializedName(value = "partyConvertedToSolo", alternate = {"duoConvertedToSolo"})
	boolean partyConvertedToSolo; // carry clause applied
	/**
	 * The party roll's proposal id — null when solo, and null for a shared
	 * contract signed before this field existed.
	 *
	 * BOXED on purpose, and not merely to spot the legacy case: normalized()
	 * backfills null COLLECTIONS only, so a primitive would deserialize to 0,
	 * and 0 is a perfectly legal proposal id that a live party could be using.
	 *
	 * Persisted because it is the only thing that can reunite a restarted
	 * client with its own contract. Member ids cannot: RuneLite regenerates
	 * them every client session, so a returning player is a stranger to
	 * everyone (partyAnchorId, persisted alongside, is exactly that mistake and
	 * is dead weight for it). A proposal id is a 64-bit random known only to the
	 * clients that were on the roll, so quoting it is proof of membership
	 * without a handshake. See PartyRollService.resurrectPartyContract.
	 */
	Long partyProposalId;

	public boolean isParty()
	{
		return partyLabel != null && !partyConvertedToSolo;
	}
}
