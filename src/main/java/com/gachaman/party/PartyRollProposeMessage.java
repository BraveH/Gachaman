package com.gachaman.party;

import com.gachaman.service.AccountKey;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.api.Quest;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member proposes a shared task roll. Every task-less member must
 * agree before the roll executes; members with an active contract auto-report
 * busy. The proposer's roll context (seed candidate, membership, levels)
 * rides along — the seed of the LOWEST member id among the agreeing
 * participants is the one every client rolls with, so all screens see
 * identical offers.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PartyRollProposeMessage extends PartyMemberMessage
{
	private long proposalId;
	private long seedCandidate;
	private boolean members;
	private int combatLevel;
	private int slayerLevel;
	/**
	 * The proposer's locked attack style (AttackStyle name), for the style
	 * clash bonus. A NAME rather than an ordinal: an older client omits the
	 * field entirely and Gson leaves a String null ("unknown, ignore"),
	 * whereas an int would deserialize to 0 and fabricate MELEE.
	 */
	private String allowedStyle;
	/**
	 * Which roll RULES this client implements. Absent from an older client, and
	 * Gson leaves an int 0 — which is exactly the "predates Fighting Weight"
	 * answer, so the whole party falls back to the old lowest-combat-level
	 * sizing rather than rolling two different boards from one shared seed.
	 */
	private int rollProtocol;
	/**
	 * The HOST's sizing rule for this roll ({@link PartySizing} name) — the
	 * only client whose setting counts, which is why this rides on the propose
	 * message and has no counterpart on the response.
	 *
	 * A NAME rather than an ordinal, for the same reason as {@link #allowedStyle}:
	 * an older client omits the field, Gson leaves a String null, and
	 * {@link PartySizing#fromWire} reads that as "unknown, use the default".
	 *
	 */
	private String sizingMode;
	/**
	 * The {@link Quest} names this member has FINISHED, out of
	 * the ones that lock a monster in the table. Unlike {@link #sizingMode},
	 * every member's answer counts: the roll intersects them, so the board only
	 * offers monsters the WHOLE party can reach.
	 *
	 * Names of what is FINISHED, rather than a bitmask or a list of what is not:
	 * both alternatives fail in the wrong direction. A bitmask silently means
	 * something else if two clients disagree about the bit order, and a
	 * not-finished list read by a client that has never heard of a quest would
	 * leave that quest unlisted and its monsters offerable. With this encoding
	 * an unrecognised or missing name simply fails the {@code containsAll} test,
	 * so the party is offered LESS, never something it cannot fight.
	 */
	private List<String> completedQuests;
	/**
	 * The sender's {@link AccountKey} — a stable identity
	 * for the Patron's Mark, null from a client that predates it or from one
	 * that is not logged in.
	 *
	 * Carries NO roll protocol bump, and that is the point: identity is not an
	 * input to the pool, the seed or the sizing, so a party where only some
	 * clients send it still deals one board from one seed. The only thing an
	 * absent key costs is a mark nobody else can see anyway.
	 *
	 * MUST stay the last field: {@code @AllArgsConstructor} is positional.
	 */
	private String accountKey;
}
