package com.gachaman.model;

import lombok.Value;
import lombok.With;

/**
 * The Charter Office's escrow: GC that has left the purse for a deed which has
 * not yet been signed. It exists so the money is never in limbo — the state on
 * disk always says either "the player owns this deed" or "the player is owed
 * this GC back", and CharterService resolves it on the very next tick after a
 * load. A crash between paying and receiving cannot eat the payment, because
 * the charge and this record are written in the same mutate.
 */
@Value
@With
public class CharterHold
{
	/** The chartered target, matched against the board and the active task by name. */
	String monsterName;
	/** Exactly what was taken from the purse, and exactly what comes back. */
	int priceGc;
	/**
	 * Wall-clock deadline. Not a tick count: ticking one down in state would
	 * mean a full re-serialize of the profile every 600ms.
	 */
	long expiresAtMs;
}
