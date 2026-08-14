package com.gachaman.party;

import java.util.*;
import org.junit.*;

/**
 * Who is actually IN a party roll — and therefore who may be dealt a board,
 * who counts toward the start rule, and who is remembered as a partner.
 *
 * Three defects came out of that one question being answered three different
 * ways in three different places, so all three answers are pinned here:
 *
 * <ul>
 * <li><b>partOfRoll</b> — the start MESSAGE path turned away a client the
 * participant list omitted, but the host's own path did not, so a host who
 * declined its own proposal was dealt the full board it had just refused.</li>
 * <li><b>agreedAmong</b> — the Start Roll Now button counted every AGREE it
 * had ever heard while the rule behind the button counted only members still
 * on the party roster, so the button could promise a roll the rule refused and
 * then do nothing at all, with no chat line to say why.</li>
 * <li><b>rememberPartners</b> — joining a proposal resets the key cache, and
 * the host's account key rides on their propose message alone (a host never
 * answers its own proposal), so the Patron's Mark silently skipped the one
 * partner every joiner is guaranteed to have.</li>
 * </ul>
 *
 * All three are statics for the usual reason: the paths around them need a
 * Client, a ClientThread and a PartyService, and the rules themselves need
 * nothing.
 */
public class PartyRollRosterTest
{
	/** A valid 16-hex-character account key, as AccountKey.normalize demands. */
	private static final String KEY_HOST = "0123456789abcdef";
	private static final String KEY_MEMBER = "fedcba9876543210";

	private static PartyRollService.Stance stance(int response, String accountKey)
	{
		return new PartyRollService.Stance(response, 1L, true, 70, 55, "MELEE",
			PartyRollService.ROLL_PROTOCOL, Collections.emptyList(), accountKey);
	}

	private static Map<Long, PartyRollService.Stance> answers(long id,
		PartyRollService.Stance stance)
	{
		Map<Long, PartyRollService.Stance> map = new HashMap<>();
		map.put(id, stance);
		return map;
	}

	private static Set<Long> roster(long... ids)
	{
		Set<Long> set = new HashSet<>();
		for (long id : ids)
		{
			set.add(id);
		}
		return set;
	}

	// =====================================================================
	// partOfRoll — may this client deal itself the board?
	// =====================================================================

	@Test
	public void aListedMemberIsOnTheRoll()
	{
		Assert.assertTrue(PartyRollService.partOfRoll(Arrays.asList(1L, 2L, 3L), 2L));
	}

	@Test
	public void aHostWhoDeclinedItsOwnProposalIsNotOnTheRoll()
	{
		// evaluateProposal drops a DECLINE stance from the agreed list but still
		// recognises the client as the proposer, so it broadcast the start and
		// rolled locally — a full board, ceremony and all, for the one member who
		// had just said no, and four personal offers they then had to decide
		// because a roll cannot be handed back
		Assert.assertFalse(PartyRollService.partOfRoll(Arrays.asList(2L, 3L), 1L));
	}

	@Test
	public void anAbsentListAndAnAbsentMemberBothMeanNo()
	{
		Assert.assertFalse(PartyRollService.partOfRoll(null, 1L));
		Assert.assertFalse(PartyRollService.partOfRoll(Collections.emptyList(), 1L));
		// 0 is what safeMemberIdOrZero() returns when there is no local member;
		// it must never be read as membership of somebody else's roll
		Assert.assertFalse(PartyRollService.partOfRoll(Arrays.asList(1L, 2L), 0L));
	}

	@Test
	public void realSizedMemberIdsStillMatch()
	{
		// member ids come from nextLong(), far outside Long's boxing cache, so
		// this pins that membership is equals() and never reference identity —
		// the failure mode would be a party that silently deals nobody a board
		long id = 7_403_912_884_512_003_991L;
		Assert.assertTrue(PartyRollService.partOfRoll(Arrays.asList(id, 5L), id));
		Assert.assertFalse(PartyRollService.partOfRoll(Arrays.asList(id, 5L), id + 1));
	}

	// =====================================================================
	// agreedAmong — what the host's start button is allowed to promise
	// =====================================================================

	@Test
	public void onlyAgreementsAreCounted()
	{
		Map<Long, PartyRollService.Stance> stances = new HashMap<>();
		stances.put(1L, stance(PartyRollResponseMessage.AGREE, null));
		stances.put(2L, stance(PartyRollResponseMessage.DECLINE, null));
		stances.put(3L, stance(PartyRollResponseMessage.BUSY, null));

		Assert.assertEquals(1, PartyRollService.agreedAmong(stances, roster(1L, 2L, 3L)));
	}

	@Test
	public void anAgreeingMemberWhoLeftThePartyStopsCounting()
	{
		// the exact shape of the silent button: two agreements heard, one of them
		// from a partner who has since disconnected. evaluateProposal walks the
		// ROSTER, so it saw one; the button walked the stances, so it said two,
		// and pressing it hit neither the deadline nor the all-answered branch
		// and returned with no message and no state change
		Map<Long, PartyRollService.Stance> stances = new HashMap<>();
		stances.put(1L, stance(PartyRollResponseMessage.AGREE, null));
		stances.put(2L, stance(PartyRollResponseMessage.AGREE, null));

		Assert.assertEquals(2, PartyRollService.agreedAmong(stances, roster(1L, 2L)));
		Assert.assertEquals("the departed member's agreement cannot start a roll", 1,
			PartyRollService.agreedAmong(stances, roster(1L)));
		// and the number the button prints is now the number the rule tests
		Assert.assertTrue(PartyRollService.agreedAmong(stances, roster(1L)) < 2);
	}

	@Test
	public void anUnreadableRosterCountsNobody()
	{
		// rosterIds() answers with an empty set when PartyService throws, which is
		// also when evaluateProposal bails out entirely — so the button must not
		// keep offering a start that cannot happen
		Map<Long, PartyRollService.Stance> stances =
			answers(1L, stance(PartyRollResponseMessage.AGREE, null));
		Assert.assertEquals(0, PartyRollService.agreedAmong(stances, roster()));
	}

	@Test
	public void nothingHeardIsNobodyAgreed()
	{
		Assert.assertEquals(0, PartyRollService.agreedAmong(
			new HashMap<>(), roster(1L, 2L)));
	}

	// =====================================================================
	// rememberPartners — the key that survives into the Patron's Mark
	// =====================================================================

	@Test
	public void aJoinedProposalInheritsTheHostsKey()
	{
		// joinProposal resets the session, which wipes the key cache the propose
		// message had just filled. Nothing later re-sends it — Start carries
		// participant ids, Vote an offer index, Resolve a roster, and a host
		// never sends a Response to its own proposal — so without this the host
		// was dropped at creditPatrons' "no identity, no mark" guard, every time
		Map<Long, String> cache = new HashMap<>();
		PartyRollService.rememberPartners(cache,
			answers(9L, stance(PartyRollResponseMessage.AGREE, KEY_HOST)));

		Assert.assertEquals(KEY_HOST, cache.get(9L));
	}

	@Test
	public void everyHeardAnswerIsSeededNotJustTheHosts()
	{
		// answers that arrived while the card was still only an offer are wiped
		// by the same reset, and a member who has already answered will not
		// answer again either
		Map<Long, PartyRollService.Stance> heard = new HashMap<>();
		heard.put(9L, stance(PartyRollResponseMessage.AGREE, KEY_HOST));
		heard.put(4L, stance(PartyRollResponseMessage.AGREE, KEY_MEMBER));

		Map<Long, String> cache = new HashMap<>();
		PartyRollService.rememberPartners(cache, heard);

		Assert.assertEquals(2, cache.size());
		Assert.assertEquals(KEY_MEMBER, cache.get(4L));
	}

	@Test
	public void aClaimThatIsNotAKeyLeavesNoEntryAtAll()
	{
		// the value becomes a KEY in a persisted ledger, so a malformed claim
		// must leave the member unknown rather than store the claim
		Map<Long, String> cache = new HashMap<>();
		PartyRollService.rememberPartners(cache,
			answers(1L, stance(PartyRollResponseMessage.AGREE, null)));
		PartyRollService.rememberPartners(cache,
			answers(2L, stance(PartyRollResponseMessage.AGREE, "")));
		PartyRollService.rememberPartners(cache,
			answers(3L, stance(PartyRollResponseMessage.AGREE, "not-a-key")));
		PartyRollService.rememberPartners(cache,
			answers(4L, stance(PartyRollResponseMessage.AGREE, KEY_HOST + KEY_MEMBER)));

		Assert.assertTrue(cache.toString(), cache.isEmpty());
	}

	@Test
	public void anInheritedKeyIsStoredNormalised()
	{
		// the ledger keys on this string, so one account arriving in two cases
		// would otherwise earn two rows and never reach a tier
		Map<Long, String> cache = new HashMap<>();
		PartyRollService.rememberPartners(cache, answers(9L,
			stance(PartyRollResponseMessage.AGREE, "  " + KEY_HOST.toUpperCase(Locale.ROOT) + " ")));

		Assert.assertEquals(KEY_HOST, cache.get(9L));
	}

	@Test
	public void aLaterAnswerReplacesAnEarlierKeyForTheSameMember()
	{
		// the same shape rememberPartner has always had for single answers: the
		// most recent claim wins, so a member who logged in between messages is
		// not stuck as unknown
		Map<Long, String> cache = new HashMap<>();
		cache.put(9L, KEY_MEMBER);
		PartyRollService.rememberPartners(cache,
			answers(9L, stance(PartyRollResponseMessage.AGREE, KEY_HOST)));

		Assert.assertEquals(KEY_HOST, cache.get(9L));
	}
}
