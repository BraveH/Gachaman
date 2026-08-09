package com.gachaman.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * One partner in the Patron's Mark ledger: how many shared contracts you have
 * finished together, what they were called the last time you did, and when.
 *
 * <p>The MAP KEY is the partner's account key, not this name. Identity and
 * label are split on purpose: a key survives a rename, a display name does not,
 * and a ledger keyed on the name would quietly fork one partner into two the
 * day they changed it. The name is carried here only so the Patrons page has
 * something to draw for somebody who is not currently in your party — it is
 * refreshed on every shared completion and is never compared against.
 *
 * <p>Both the key and the name arrive over the party relay from another
 * player's client, so both are self-reported and neither is authenticated.
 * That is acceptable because this whole ledger is cosmetic; see
 * {@link com.gachaman.service.PatronMark}.
 */
@Value
public class PatronRecord
{
	/**
	 * Last display name seen for this partner. Nullable because a partner
	 * credited by a client that could not read their name at the time is still
	 * a real partner — the count is the fact, the label is a convenience.
	 */
	@Nullable
	String name;
	int count;
	/** Epoch ms of the most recent shared completion; 0 when unrecorded. */
	long lastSharedAt;
}
