package com.gachaman.party;

import com.gachaman.service.AccountKey;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The sender's own presence: what the party page draws for one member.
 *
 * DISPLAY ONLY. Nothing here is ever an input to a roll — the seeded party
 * roll takes every value it needs from the propose/response handshake, and
 * feeding it a value from this message would let a late or dropped presence
 * packet desync the offers. Membership, slayer level and the seed candidate
 * are deliberately absent so the mistake is not even available.
 *
 * It is not an input to pooled PROGRESS either: {@link PartyKillsMessage}
 * remains the sole authority for advancing a shared contract's quota. The
 * killsDone here is rendered on one row and read by nothing else.
 *
 * Named Gacha* rather than Party*: RuneLite labels websocket subtypes by
 * SIMPLE class name and throws if two registered classes share one, so a
 * generic name would abort startUp the day another installed plugin picks
 * the same one.
 *
 * EXTENSION POINT: later party features add a FIELD here, never a second
 * message class. Gson drops unknown fields and leaves omitted ones at
 * null/0/false, so an added field is invisible to older clients and an
 * older client's omission reads as "not claimed".
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class GachaPresenceMessage extends PartyMemberMessage
{
	/**
	 * The sender's locked attack style (AttackStyle name). A NAME rather
	 * than an ordinal: an older client omits the field entirely and Gson
	 * leaves a String null ("unknown, ignore"), whereas an int would
	 * deserialize to 0 and fabricate MELEE.
	 */
	private String allowedStyle;
	private int combatLevel;
	/** The active contract's monster, or null when the sender has none. */
	private String activeTaskName;
	private int killsDone;
	private int killsRequired;
	private boolean tainted;
	/**
	 * The sender's {@link AccountKey}: a stable identity
	 * for the Patron's Mark, null from an older client or one not logged in.
	 * Self-reported and unauthenticated, exactly like the display name — the
	 * page draws it and the cosmetic ledger keys on it, and nothing else.
	 */
	private String accountKey;
	/**
	 * The sender has a board dealt but has signed nothing off it yet.
	 *
	 * Which makes them INELIGIBLE for a shared roll: a party roll is only for
	 * members with a clean slate, and undecided offers are not a clean slate.
	 * Broadcast so the page can say so before someone proposes and watches
	 * them get auto-excused for no visible reason.
	 *
	 * A boolean rather than the offer count: the count is a roll input's
	 * shape and this message must never look like one. Eligibility is the only
	 * question the page asks.
	 */
	private boolean undecidedOffers;
	/**
	 * The party proposal id of the sender's SHARED contract, or null when they
	 * are solo, idle, or on a contract that has carried to solo.
	 *
	 * Boxed on purpose. A proposal id is {@code nextLong()}, so zero is a legal
	 * value; a primitive would leave an older client's omission indistinguishable
	 * from a real id of 0 and could merge two unrelated members into one group.
	 * Null means "makes no claim", which is what an omission actually is.
	 *
	 * DISPLAY ONLY, like everything else here — it groups rows on a page. It is
	 * not how a shared contract is identified anywhere that matters; the pooled
	 * quota still moves on {@link PartyKillsMessage} alone.
	 */
	private Long partyContractId;
}
