package com.gachaman.party;

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
}
