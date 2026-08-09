package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.PatronRecord;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Patron's Mark rules, in isolation. Every decision the feature makes
 * lives in these statics — PartyRollService.creditPatrons is reduced to
 * reading a roster and calling them, and PartyTab and PatronsTab only draw the
 * answer.
 *
 * The ledger is keyed on the ACCOUNT KEY with the display name demoted to a
 * label, so the theme running through this file is that identity and label are
 * separable: a rename must not fork a history, and a shared name must not merge
 * two strangers.
 *
 * What this does NOT prove: that two clients agree on a count. They never have
 * to. Each client keeps a private ledger of its own partners; there is no wire
 * message, no host authority and nothing to desync.
 */
public class PatronMarkTest
{
	/** A fixed clock. Real time in a test would make lastSharedAt unassertable. */
	private static final long NOW = 1_700_000_000_000L;

	private static final long DAY = 24L * 60 * 60 * 1000;

	/**
	 * A distinct, well-formed account key. Canonical casing, exactly 16 hex.
	 *
	 * Padded with 'a' rather than '0' so every key CONTAINS a letter: the
	 * case-folding assertions below upper-case a key and expect a different
	 * string back, and an all-digit key would make toUpperCase a no-op and
	 * those tests vacuously green. Only the suffix varies, so keys still sort
	 * by n and the tiebreak assertions stay readable.
	 */
	private static String key(int n)
	{
		String hex = Integer.toHexString(n);
		StringBuilder out = new StringBuilder(AccountKey.KEY_LENGTH);
		for (int i = hex.length(); i < AccountKey.KEY_LENGTH; i++)
		{
			out.append('a');
		}
		return out.append(hex).toString();
	}

	@Test
	public void theTestKeyHelperProducesRealKeys()
	{
		// this file's whole premise. A helper that quietly emitted something
		// normalize() rejects would make every assertion below test the junk
		// path instead of the one it names.
		for (int n : new int[]{0, 1, 9, 50, 255})
		{
			Assert.assertEquals("key(" + n + ") must be canonical",
				key(n), AccountKey.normalize(key(n)));
			Assert.assertNotEquals("key(" + n + ") must contain a hex letter, or the"
				+ " case-folding tests below prove nothing",
				key(n), key(n).toUpperCase(Locale.ROOT));
		}
		Assert.assertNotEquals(key(1), key(2));
	}

	/** One partner, so the common single-credit case reads as one line. */
	private static Map<String, String> one(String accountKey, String name)
	{
		Map<String, String> partners = new LinkedHashMap<>();
		partners.put(accountKey, name);
		return partners;
	}

	// --- A. identity is the key; the name is only a label ---

	@Test
	public void normalizeNameRejectsThePartyPlaceholder()
	{
		// PartyMember's CONSTRUCTOR DEFAULT, verified in the shipped client jar:
		// the literal "<unknown>" sits in its constant pool, and the only caller
		// of setDisplayName in the whole jar is the built-in Party plugin. With
		// that plugin off, EVERY member reads as this — storing it would put a
		// placeholder on the Patrons page as though it were somebody's name.
		Assert.assertNull("the placeholder must never be stored as a name",
			PatronMark.normalizeName("<unknown>"));
		Assert.assertNull(PatronMark.normalizeName(null));
		Assert.assertNull(PatronMark.normalizeName(""));
		Assert.assertNull(PatronMark.normalizeName("   "));
		// angle brackets would also be eaten by the sidebar's HTML renderer
		Assert.assertNull(PatronMark.normalizeName("<col=ff0000>Zezima"));
		Assert.assertNull(PatronMark.normalizeName("Zezima>"));
	}

	@Test
	public void normalizeNameTrimsAndBoundsTheName()
	{
		Assert.assertEquals("Zezima", PatronMark.normalizeName("  Zezima  "));
		// U+00A0 is Jagex's word separator. It reads as a space but is a
		// different character, so it is folded here rather than drawn raw.
		// Built by code point because in source the two characters are
		// indistinguishable and one editor pass would make this vacuous.
		Assert.assertEquals("Zezima Pk",
			PatronMark.normalizeName("Zezima" + (char) 0xA0 + "Pk"));
		Assert.assertEquals("a 12-character name is legal", "123456789012",
			PatronMark.normalizeName("123456789012"));
		Assert.assertNull("13 characters cannot be a real OSRS name",
			PatronMark.normalizeName("1234567890123"));
	}

	@Test
	public void aNameIsALabelNotAnIdentity()
	{
		// the whole reason the ledger moved off names: a partner who renames must
		// keep one history, and two partners who happen to share a name must keep
		// two. A name-keyed map got both of these backwards.
		Map<String, PatronRecord> ledger = PatronMark.credit(null,
			one(key(1), "Alpha"), 100, NOW);
		ledger = PatronMark.credit(ledger, one(key(1), "Renamed"), 100, NOW);

		Assert.assertEquals("a rename is one partner, not two", 1, ledger.size());
		Assert.assertEquals(2, PatronMark.countFor(ledger, key(1)));
		Assert.assertEquals("the label follows the latest completion",
			"Renamed", PatronMark.recordFor(ledger, key(1)).getName());

		// a completion credited while their name was unreadable keeps the label
		ledger = PatronMark.credit(ledger, one(key(1), "<unknown>"), 100, NOW);
		Assert.assertEquals(3, PatronMark.countFor(ledger, key(1)));
		Assert.assertEquals("an unreadable name must not blank a drawn row",
			"Renamed", PatronMark.recordFor(ledger, key(1)).getName());

		// and the converse: one name, two accounts, two rows
		Map<String, String> namesakes = new LinkedHashMap<>();
		namesakes.put(key(7), "Zezima");
		namesakes.put(key(8), "Zezima");
		Map<String, PatronRecord> two = PatronMark.credit(null, namesakes, 100, NOW);
		Assert.assertEquals("a shared name is not a shared identity", 2, two.size());
		Assert.assertEquals(1, PatronMark.countFor(two, key(7)));
		Assert.assertEquals(1, PatronMark.countFor(two, key(8)));
	}

	// --- B. crediting a finished contract ---

	@Test
	public void creditReturnsTheSameInstanceWhenNothingIsCreditable()
	{
		// this exact identity is what GachaStateService.mutate short-circuits on
		// (`next == state`), so a completion with no creditable partner pays for
		// no gzip + SHA-256 of the whole save at all
		Map<String, PatronRecord> current = new LinkedHashMap<>();
		current.put(key(1), new PatronRecord("Zezima", 4, NOW));
		Assert.assertSame(current, PatronMark.credit(current, null, 100, NOW));
		Assert.assertSame(current,
			PatronMark.credit(current, Collections.emptyMap(), 100, NOW));

		Map<String, String> junk = new LinkedHashMap<>();
		junk.put("not-a-key", "Zezima");
		junk.put("00112233445566", "Short");
		junk.put("00112233445566gg", "NotHex");
		Assert.assertSame("no usable key is the same as no partners",
			current, PatronMark.credit(current, junk, 100, NOW));
	}

	@Test
	public void creditReturnsTheSameInstanceWhenTheCapTurnsEveryoneAway()
	{
		// the subtle half of the identity contract: a full ledger whose partners
		// all have real histories accepts nobody, so NOTHING changed — handing
		// back an equal-but-distinct map would slip past mutate's `next ==
		// state` check and buy a gzip + SHA-256 of the whole save for no edit
		int cap = 3;
		Map<String, PatronRecord> full = new LinkedHashMap<>();
		for (int i = 0; i < cap; i++)
		{
			full.put(key(i), new PatronRecord("Partner" + i, 2, NOW));
		}
		Map<String, String> strangers = new LinkedHashMap<>();
		strangers.put(key(50), "Stranger");
		strangers.put(key(51), "Drifter");
		Assert.assertSame(full, PatronMark.credit(full, strangers, cap, NOW));
	}

	@Test
	public void oneAccountIsOneMarkHoweverManyClients()
	{
		// the same account dual-logged into the party arrives under two member
		// ids. The caller collapses them into one map entry, but two entries
		// differing only in case would still normalize to one partner and credit
		// them twice off a single contract — one mark per partner is the rule the
		// whole feature rests on, so it is enforced here too.
		Map<String, String> doubled = new LinkedHashMap<>();
		doubled.put(key(1), "Zezima");
		doubled.put(key(1).toUpperCase(Locale.ROOT), "Zezima");
		doubled.put("  " + key(1) + "  ", "Zezima");

		Map<String, PatronRecord> ledger = PatronMark.credit(null, doubled, 100, NOW);
		Assert.assertEquals(1, ledger.size());
		Assert.assertEquals("one contract is one mark", 1, PatronMark.countFor(ledger, key(1)));
		Assert.assertEquals("the stored key is canonical, whatever casing arrived",
			key(1), ledger.keySet().iterator().next());
	}

	@Test
	public void creditNeverMutatesTheMapItWasGiven()
	{
		// the map comes off the shared, immutable state object, and
		// creditPatrons compares the pre-mutate snapshot against the result —
		// an in-place write would corrupt both
		Map<String, PatronRecord> original = new LinkedHashMap<>();
		original.put(key(1), new PatronRecord("Zezima", 4, NOW));
		Map<String, PatronRecord> guarded = Collections.unmodifiableMap(original);

		Map<String, String> partners = new LinkedHashMap<>();
		partners.put(key(1), "Zezima");
		partners.put(key(2), "B0aty");
		Map<String, PatronRecord> next = PatronMark.credit(guarded, partners, 100, NOW);

		Assert.assertEquals("the caller's map is untouched", 1, guarded.size());
		Assert.assertEquals(4, PatronMark.countFor(guarded, key(1)));
		Assert.assertEquals(5, PatronMark.countFor(next, key(1)));
		Assert.assertEquals(1, PatronMark.countFor(next, key(2)));
	}

	@Test
	public void lastSharedAtIsStampedOnEveryCredit()
	{
		// the Patrons page draws "last <relative>", so a stamp written only on
		// the FIRST contract would show a years-old date for a daily partner
		Map<String, PatronRecord> ledger = PatronMark.credit(null,
			one(key(1), "Zezima"), 100, NOW);
		Assert.assertEquals(NOW, PatronMark.recordFor(ledger, key(1)).getLastSharedAt());

		ledger = PatronMark.credit(ledger, one(key(1), "Zezima"), 100, NOW + 5 * DAY);
		Assert.assertEquals("the stamp moves with the partnership",
			NOW + 5 * DAY, PatronMark.recordFor(ledger, key(1)).getLastSharedAt());
		Assert.assertEquals(2, PatronMark.countFor(ledger, key(1)));
	}

	@Test
	public void countForToleratesJunkAndNulls()
	{
		Assert.assertEquals(0, PatronMark.countFor(null, key(1)));
		Assert.assertEquals(0, PatronMark.countFor(Collections.emptyMap(), key(1)));
		Assert.assertEquals(0, PatronMark.countFor(
			Collections.singletonMap(key(1), new PatronRecord("Zezima", 3, NOW)), key(2)));
		Assert.assertEquals("an unknown account is not a lookup",
			0, PatronMark.countFor(
				Collections.singletonMap(key(1), new PatronRecord("Zezima", 3, NOW)), null));
		Assert.assertEquals(0, PatronMark.countFor(
			Collections.singletonMap(key(1), new PatronRecord("Zezima", 3, NOW)), "not-a-key"));

		// Gson can deserialize {"0000…01":null} out of a hand-edited save
		Map<String, PatronRecord> nulled = new HashMap<>();
		nulled.put(key(1), null);
		Assert.assertEquals(0, PatronMark.countFor(nulled, key(1)));
		Assert.assertNull(PatronMark.recordFor(nulled, key(1)));

		// as can a zero count, which is a row nobody has a history with
		Assert.assertEquals(0, PatronMark.countFor(
			Collections.singletonMap(key(1), new PatronRecord("Ghost", 0, NOW)), key(1)));
	}

	@Test
	public void aNonCanonicalStoredKeyDrawsNowhere()
	{
		// recordFor looks up with the NORMALIZED key, so an upper-cased key in a
		// hand-edited save would be listed by ranked() and found by nothing: the
		// Patrons page would name a top patron the party page could draw no pip
		// for. Both sides must agree, so it is junk to both.
		Map<String, PatronRecord> hand = new LinkedHashMap<>();
		hand.put(key(1).toUpperCase(Locale.ROOT), new PatronRecord("Forged", 99, NOW));
		hand.put(key(2), new PatronRecord("Real", 1, NOW));

		Assert.assertEquals(0, PatronMark.countFor(hand, key(1)));
		Assert.assertEquals(1, PatronMark.partnerCount(hand));
		Assert.assertEquals(key(2), PatronMark.topKey(hand));
		Assert.assertEquals(1, PatronMark.ranked(hand).size());
		Assert.assertEquals("Real", PatronMark.ranked(hand).get(0).getName());
	}

	// --- C. the bound, and who it may displace ---

	@Test
	public void aStrangerCannotDisplaceARealHistory()
	{
		int cap = 5;
		Map<String, PatronRecord> full = new LinkedHashMap<>();
		for (int i = 0; i < cap; i++)
		{
			full.put(key(i), new PatronRecord("Partner" + i, 2, NOW));
		}

		Map<String, PatronRecord> next = PatronMark.credit(full,
			one(key(50), "Stranger"), cap, NOW);
		Assert.assertEquals("the map stays bounded", cap, next.size());
		Assert.assertEquals("a two-contract history is never evicted",
			0, PatronMark.countFor(next, key(50)));
		Assert.assertEquals(2, PatronMark.countFor(next, key(0)));

		// with one genuine one-off present, that one — and only that one — goes
		Map<String, PatronRecord> withOneOff = new LinkedHashMap<>(full);
		withOneOff.remove(key(4));
		withOneOff.put(key(9), new PatronRecord("OneOff", 1, NOW));
		Map<String, PatronRecord> after = PatronMark.credit(withOneOff,
			one(key(50), "Stranger"), cap, NOW);
		Assert.assertEquals(cap, after.size());
		Assert.assertEquals(0, PatronMark.countFor(after, key(9)));
		Assert.assertEquals(1, PatronMark.countFor(after, key(50)));
		Assert.assertEquals(2, PatronMark.countFor(after, key(0)));
	}

	@Test
	public void amongOneOffsTheColdestGoesFirst()
	{
		// a bound has to drop SOMETHING; dropping the partner you shared with
		// longest ago is the only choice that does not throw away the newest
		// relationship the moment it starts
		int cap = 3;
		Map<String, PatronRecord> full = new LinkedHashMap<>();
		full.put(key(1), new PatronRecord("Recent", 1, NOW));
		full.put(key(2), new PatronRecord("Ancient", 1, NOW - 400 * DAY));
		full.put(key(3), new PatronRecord("Middling", 1, NOW - 30 * DAY));

		Map<String, PatronRecord> after = PatronMark.credit(full,
			one(key(50), "Newcomer"), cap, NOW);
		Assert.assertEquals(cap, after.size());
		Assert.assertEquals("the coldest one-off is the victim",
			0, PatronMark.countFor(after, key(2)));
		Assert.assertEquals(1, PatronMark.countFor(after, key(1)));
		Assert.assertEquals(1, PatronMark.countFor(after, key(3)));
		Assert.assertEquals(1, PatronMark.countFor(after, key(50)));
	}

	@Test
	public void evictionIsDeterministic()
	{
		// Gson hands the map back as a LinkedTreeMap in JSON order, so an
		// order-sensitive victim would differ between a fresh map and a reloaded
		// one and a partner would vanish on some logins but not others. Every
		// count and every stamp is identical here, so ONLY the tiebreak decides.
		int[] forward = {1, 2, 3};
		int[] backward = {3, 2, 1};

		Map<String, PatronRecord> a = new LinkedHashMap<>();
		for (int n : forward)
		{
			a.put(key(n), new PatronRecord("Partner" + n, 1, NOW));
		}
		Map<String, PatronRecord> b = new LinkedHashMap<>();
		for (int n : backward)
		{
			b.put(key(n), new PatronRecord("Partner" + n, 1, NOW));
		}

		Map<String, PatronRecord> afterA = PatronMark.credit(a,
			one(key(50), "Newcomer"), forward.length, NOW);
		Map<String, PatronRecord> afterB = PatronMark.credit(b,
			one(key(50), "Newcomer"), backward.length, NOW);

		for (int n : forward)
		{
			Assert.assertEquals("victim must not depend on insertion order: " + key(n),
				PatronMark.countFor(afterA, key(n)), PatronMark.countFor(afterB, key(n)));
		}
		Assert.assertEquals(forward.length, afterA.size());
	}

	@Test
	public void aCapOfZeroOrLessIsNoCapAtAll()
	{
		// defensive: a mis-tuned constant must not silently stop counting
		Map<String, String> partners = new LinkedHashMap<>();
		partners.put(key(1), "Alpha");
		partners.put(key(2), "Bravo");
		partners.put(key(3), "Charlie");
		Assert.assertEquals(3, PatronMark.credit(null, partners, 0, NOW).size());
		Assert.assertEquals(3, PatronMark.credit(null, partners, -1, NOW).size());
	}

	// --- D. who wears the mark ---

	@Test
	public void topKeyIsIndependentOfMapOrder()
	{
		Map<String, PatronRecord> seed = new LinkedHashMap<>();
		seed.put(key(1), new PatronRecord("Alpha", 3, NOW));
		seed.put(key(2), new PatronRecord("Bravo", 9, NOW));
		seed.put(key(3), new PatronRecord("Charlie", 4, NOW));

		Assert.assertEquals(key(2), PatronMark.topKey(new HashMap<>(seed)));
		Assert.assertEquals(key(2), PatronMark.topKey(new LinkedHashMap<>(seed)));
		Assert.assertEquals(key(2), PatronMark.topKey(new TreeMap<>(seed)));

		// a tie must resolve the same way from either insertion order
		Map<String, PatronRecord> tieForward = new LinkedHashMap<>();
		tieForward.put(key(1), new PatronRecord("Alpha", 7, NOW));
		tieForward.put(key(2), new PatronRecord("Bravo", 7, NOW));
		Map<String, PatronRecord> tieBackward = new LinkedHashMap<>();
		tieBackward.put(key(2), new PatronRecord("Bravo", 7, NOW));
		tieBackward.put(key(1), new PatronRecord("Alpha", 7, NOW));
		Assert.assertEquals(PatronMark.topKey(tieForward), PatronMark.topKey(tieBackward));

		// and two NAMELESS ties still resolve, because the key is the last word
		Map<String, PatronRecord> nameless = new LinkedHashMap<>();
		nameless.put(key(5), new PatronRecord(null, 2, NOW));
		nameless.put(key(4), new PatronRecord(null, 2, NOW));
		Assert.assertEquals(key(4), PatronMark.topKey(nameless));
	}

	@Test
	public void topKeyIgnoresJunkKeysAndZeroCounts()
	{
		Map<String, PatronRecord> ledger = new LinkedHashMap<>();
		ledger.put("<unknown>", new PatronRecord("Placeholder", 999, NOW));
		ledger.put("", new PatronRecord("Blank", 5, NOW));
		ledger.put(key(1), new PatronRecord("Ghost", 0, NOW));
		ledger.put(key(2), null);
		ledger.put(key(3), new PatronRecord("Zezima", 1, NOW));

		Assert.assertEquals("a key that is not a key must never become the mark,"
			+ " however high its count", key(3), PatronMark.topKey(ledger));
		Assert.assertEquals(1, PatronMark.partnerCount(ledger));
		Assert.assertEquals(1, PatronMark.totalMarks(ledger));
	}

	@Test
	public void topKeyOfNothingIsNothing()
	{
		// the day-one path: render no mark, not a blank row
		Assert.assertNull(PatronMark.topKey(null));
		Assert.assertNull(PatronMark.topKey(Collections.emptyMap()));
		Assert.assertNull(PatronMark.topKey(
			Collections.singletonMap(key(1), new PatronRecord("Ghost", 0, NOW))));
		Assert.assertEquals(0, PatronMark.partnerCount(null));
		Assert.assertEquals(0, PatronMark.totalMarks(null));
		Assert.assertTrue(PatronMark.ranked(null).isEmpty());
	}

	@Test
	public void rankedIsDescendingAndAgreesWithTopKey()
	{
		// the Patrons page draws ranked() in order and must NOT re-sort: its
		// first row and the outlined pip on the party page are the same claim
		// about the same person, and two sorts would eventually disagree
		Map<String, PatronRecord> ledger = new LinkedHashMap<>();
		ledger.put(key(1), new PatronRecord("Alpha", 3, NOW));
		ledger.put(key(2), new PatronRecord("Bravo", 9, NOW));
		ledger.put(key(3), new PatronRecord("Charlie", 4, NOW));
		ledger.put(key(4), new PatronRecord("Ghost", 0, NOW));

		List<PatronRecord> ranked = PatronMark.ranked(ledger);
		Assert.assertEquals("uncounted rows are not partners", 3, ranked.size());
		for (int i = 1; i < ranked.size(); i++)
		{
			Assert.assertTrue("descending at " + i,
				ranked.get(i - 1).getCount() >= ranked.get(i).getCount());
		}
		Assert.assertSame("row 0 IS the mark's owner",
			PatronMark.recordFor(ledger, PatronMark.topKey(ledger)), ranked.get(0));
		Assert.assertEquals(3, PatronMark.partnerCount(ledger));
		Assert.assertEquals("marks, not contracts: one shared contract with three"
			+ " partners is three", 16, PatronMark.totalMarks(ledger));
	}

	@Test
	public void rankedIsNotTheCallersToEdit()
	{
		// it is handed straight to a panel loop; an accidental sort there would
		// silently disagree with topKey rather than fail
		try
		{
			PatronMark.ranked(Collections.singletonMap(key(1),
				new PatronRecord("Zezima", 1, NOW))).clear();
			Assert.fail("ranked() must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// intended
		}
	}

	@Test
	public void displayNameFallsBackForAnUnnamedPartner()
	{
		// a partner credited while their client could not read their name is
		// still a real partner with a real count — the row draws either way
		Assert.assertEquals("An unnamed patron", PatronMark.displayName(null));
		Assert.assertEquals("An unnamed patron",
			PatronMark.displayName(new PatronRecord(null, 4, NOW)));
		Assert.assertEquals("a stored name that is not a name is not drawn raw",
			"An unnamed patron",
			PatronMark.displayName(new PatronRecord("<unknown>", 4, NOW)));
		Assert.assertEquals("Zezima",
			PatronMark.displayName(new PatronRecord("  Zezima  ", 4, NOW)));
	}

	// --- E. the tier ladder ---

	@Test
	public void tierLadderMatchesTheThresholds()
	{
		// driven off Tuning rather than hardcoded numbers, so retuning the
		// thresholds cannot silently invalidate this
		int[] tiers = Tuning.PATRON_TIERS;
		Assert.assertEquals("the ladder is ascending and has three rungs", 3, tiers.length);
		Assert.assertEquals(0, PatronMark.tierFor(0));
		for (int i = 0; i < tiers.length; i++)
		{
			Assert.assertEquals("one below threshold " + i, i, PatronMark.tierFor(tiers[i] - 1));
			Assert.assertEquals("at threshold " + i, i + 1, PatronMark.tierFor(tiers[i]));
		}
		Assert.assertEquals(tiers.length, PatronMark.tierFor(Integer.MAX_VALUE));
		for (int i = 1; i < tiers.length; i++)
		{
			Assert.assertTrue("thresholds must ascend", tiers[i] > tiers[i - 1]);
		}
	}

	@Test
	public void crossedTierFiresOnceAtEachThreshold()
	{
		// the chat line is the only feedback when the top patron is offline, so
		// a double-fire or a miss is directly user-visible
		for (int threshold : Tuning.PATRON_TIERS)
		{
			Assert.assertTrue("crossing " + threshold,
				PatronMark.crossedTier(threshold - 1, threshold));
			Assert.assertFalse("already past " + threshold,
				PatronMark.crossedTier(threshold, threshold + 1));
		}
		Assert.assertFalse(PatronMark.crossedTier(5, 9));
		Assert.assertFalse("standing still is not a crossing", PatronMark.crossedTier(10, 10));
	}

	@Test
	public void everyTierHasALabelAndTheLabelNeverThrows()
	{
		Assert.assertEquals("the label array must stay exactly one longer than"
			+ " PATRON_TIERS — index 0 is the untiered mark",
			Tuning.PATRON_TIERS.length + 1, PatronMark.labelCount());
		String previous = null;
		for (int count = 0; count <= 200; count++)
		{
			String label = PatronMark.tierLabel(count);
			Assert.assertNotNull("no label at " + count, label);
			Assert.assertFalse(label.isEmpty());
			if (previous != null && PatronMark.tierFor(count) == PatronMark.tierFor(count - 1))
			{
				Assert.assertEquals("the label may only change at a threshold",
					previous, label);
			}
			previous = label;
		}
	}

	// --- F. the whole path, end to end ---

	@Test
	public void aLongPartnershipClimbsTheLadderExactlyOnce()
	{
		// the property the feature exists for, and the one an id-keyed map could
		// never satisfy: a memberId is re-drawn from a fresh Random every login,
		// so tier II and III would be unreachable by construction
		Map<String, PatronRecord> ledger = null;
		int crossings = 0;
		for (int contract = 1; contract <= 100; contract++)
		{
			int before = PatronMark.countFor(ledger, key(1));
			// they rename halfway through: a name-keyed ledger forked here and
			// each half stalled below the next threshold
			String name = contract < 50 ? "Zezima" : "Zezima Pk";
			ledger = PatronMark.credit(ledger, one(key(1), name),
				Tuning.PATRON_MAX_PARTNERS, NOW + contract * DAY);
			int after = PatronMark.countFor(ledger, key(1));
			Assert.assertEquals(before + 1, after);
			if (PatronMark.crossedTier(before, after))
			{
				crossings++;
			}
		}
		Assert.assertEquals("one announcement per threshold, no more",
			Tuning.PATRON_TIERS.length, crossings);
		Assert.assertEquals("a rename must not fork the history", 1, ledger.size());
		Assert.assertEquals(key(1), PatronMark.topKey(ledger));
		Assert.assertEquals("Zezima Pk", PatronMark.displayName(PatronMark.recordFor(ledger, key(1))));
		Assert.assertEquals("Patron III",
			PatronMark.tierLabel(PatronMark.countFor(ledger, key(1))));
		Assert.assertEquals(NOW + 100 * DAY,
			PatronMark.recordFor(ledger, key(1)).getLastSharedAt());
	}
}
