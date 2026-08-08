package com.gachaman.service;

import com.gachaman.Tuning;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;

/**
 * The Patron's Mark rules, in isolation. Every decision the feature makes
 * lives in these statics — PartyRollService.creditPatrons is reduced to
 * reading a roster and calling them, and PartyTab only draws the answer.
 *
 * What this does NOT prove: that two clients agree on a count. They never have
 * to. Each client keeps a private ledger of its own partners; there is no wire
 * message, no host authority and nothing to desync.
 */
public class PatronMarkTest
{
	// --- A. what counts as a name ---

	@Test
	public void normalizeNameRejectsThePartyPlaceholder()
	{
		// PartyMember's CONSTRUCTOR DEFAULT, verified in the shipped client jar:
		// the literal "<unknown>" sits in its constant pool, and the only caller
		// of setDisplayName in the whole jar is the built-in Party plugin. With
		// that plugin off, EVERY member reads as this — crediting it would
		// invent one shared top patron out of nothing.
		Assert.assertNull("the placeholder must never be credited",
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
		// U+00A0 is Jagex's word separator. It reads as a space but keys
		// separately, so one client's "Zezima Pk" and another's would become
		// two partners. Built by code point because in source the two
		// characters are indistinguishable and one editor pass would make
		// this assertion vacuous.
		Assert.assertEquals("Zezima Pk",
			PatronMark.normalizeName("Zezima" + (char) 0xA0 + "Pk"));
		Assert.assertTrue(PatronMark.sameName("Zezima" + (char) 0xA0 + "Pk", "Zezima Pk"));
		Assert.assertEquals("a 12-character name is legal", "123456789012",
			PatronMark.normalizeName("123456789012"));
		Assert.assertNull("13 characters cannot be a real OSRS name",
			PatronMark.normalizeName("1234567890123"));
	}

	@Test
	public void sameNameNeverMatchesAPlaceholderAgainstItself()
	{
		Assert.assertTrue(PatronMark.sameName("Zezima", "zezima"));
		Assert.assertTrue(PatronMark.sameName(" Zezima ", "Zezima"));
		Assert.assertFalse(PatronMark.sameName("Zezima", "B0aty"));
		// the row-match rule the panel uses: two members BOTH reading as the
		// placeholder must not resolve to "the same partner" and share a mark
		Assert.assertFalse("<unknown> is not a partner, even against itself",
			PatronMark.sameName("<unknown>", "<unknown>"));
		// the presence layer's own fallback for an unnamed row is 14 chars, so
		// it is rejected on length and can never wear someone else's mark
		Assert.assertFalse(PatronMark.sameName("A party member", "A party member"));
		Assert.assertFalse(PatronMark.sameName(null, null));
	}

	// --- B. crediting a finished contract ---

	@Test
	public void creditReturnsTheSameInstanceWhenNothingIsCreditable()
	{
		// this exact identity is what GachaStateService.mutate short-circuits on
		// (`next == state`), so a completion with no creditable partner pays for
		// no gzip + SHA-256 of the whole save at all
		Map<String, Integer> current = new LinkedHashMap<>();
		current.put("Zezima", 4);
		Assert.assertSame(current, PatronMark.credit(current, null, 100));
		Assert.assertSame(current, PatronMark.credit(current, Collections.emptyList(), 100));
		Assert.assertSame("every name unusable is the same as no names",
			current, PatronMark.credit(current, Arrays.asList("<unknown>", "  ", null), 100));
	}

	@Test
	public void creditReturnsTheSameInstanceWhenTheCapTurnsEveryoneAway()
	{
		// the subtle half of the identity contract: a full map whose partners
		// all have real histories accepts nobody, so NOTHING changed — handing
		// back an equal-but-distinct map would slip past mutate's `next ==
		// state` check and buy a gzip + SHA-256 of the whole save for no edit
		int cap = 3;
		Map<String, Integer> full = new LinkedHashMap<>();
		for (int i = 0; i < cap; i++)
		{
			full.put("Partner" + i, 2);
		}
		Assert.assertSame(full, PatronMark.credit(full,
			Arrays.asList("Stranger", "Drifter"), cap));
	}

	@Test
	public void creditMergesNamesCaseInsensitivelyAndKeepsTheFirstCasing()
	{
		Map<String, Integer> counts = PatronMark.credit(null,
			Collections.singletonList("Zezima"), 100);
		counts = PatronMark.credit(counts, Collections.singletonList("zezima"), 100);

		Assert.assertEquals("one partner is one row however their client cased it",
			1, counts.size());
		Assert.assertEquals("Zezima", counts.keySet().iterator().next());
		Assert.assertEquals(2, PatronMark.countFor(counts, "ZEZIMA"));
	}

	@Test
	public void oneContractIsOneMarkPerPartner()
	{
		// a party can hold two member ids sharing one display name (the same
		// account from two clients); the alternative reading lets a player
		// inflate a friend's count by dual-logging
		Map<String, Integer> counts = PatronMark.credit(null,
			Arrays.asList("Zezima", "zezima", " Zezima "), 100);
		Assert.assertEquals(1, counts.size());
		Assert.assertEquals(1, PatronMark.countFor(counts, "Zezima"));
	}

	@Test
	public void creditNeverMutatesTheMapItWasGiven()
	{
		// the map comes off the shared, immutable state object, and
		// creditPatrons compares the pre-mutate snapshot against the result —
		// an in-place write would corrupt both
		Map<String, Integer> original = new LinkedHashMap<>();
		original.put("Zezima", 4);
		Map<String, Integer> guarded = Collections.unmodifiableMap(original);

		Map<String, Integer> next = PatronMark.credit(guarded,
			Arrays.asList("Zezima", "B0aty"), 100);

		Assert.assertEquals("the caller's map is untouched", 1, guarded.size());
		Assert.assertEquals(4, PatronMark.countFor(guarded, "Zezima"));
		Assert.assertEquals(5, PatronMark.countFor(next, "Zezima"));
		Assert.assertEquals(1, PatronMark.countFor(next, "B0aty"));
	}

	@Test
	public void countForToleratesJunkAndNulls()
	{
		Assert.assertEquals(0, PatronMark.countFor(null, "Zezima"));
		Assert.assertEquals(0, PatronMark.countFor(Collections.emptyMap(), "Zezima"));
		Assert.assertEquals(0, PatronMark.countFor(
			Collections.singletonMap("Zezima", 3), "<unknown>"));
		// Gson can deserialize {"Zezima":null} out of a hand-edited save
		Map<String, Integer> nulled = new HashMap<>();
		nulled.put("Zezima", null);
		Assert.assertEquals(0, PatronMark.countFor(nulled, "Zezima"));
	}

	// --- C. the bound, and who it may displace ---

	@Test
	public void aStrangerCannotDisplaceARealHistory()
	{
		int cap = 5;
		Map<String, Integer> full = new LinkedHashMap<>();
		for (int i = 0; i < cap; i++)
		{
			full.put("Partner" + i, 2);
		}

		Map<String, Integer> next = PatronMark.credit(full,
			Collections.singletonList("Stranger"), cap);
		Assert.assertEquals("the map stays bounded", cap, next.size());
		Assert.assertEquals("a two-contract history is never evicted",
			0, PatronMark.countFor(next, "Stranger"));
		Assert.assertEquals(2, PatronMark.countFor(next, "Partner0"));

		// with one genuine one-off present, that one — and only that one — goes
		Map<String, Integer> withOneOff = new LinkedHashMap<>(full);
		withOneOff.remove("Partner4");
		withOneOff.put("OneOff", 1);
		Map<String, Integer> after = PatronMark.credit(withOneOff,
			Collections.singletonList("Stranger"), cap);
		Assert.assertEquals(cap, after.size());
		Assert.assertEquals(0, PatronMark.countFor(after, "OneOff"));
		Assert.assertEquals(1, PatronMark.countFor(after, "Stranger"));
		Assert.assertEquals(2, PatronMark.countFor(after, "Partner0"));
	}

	@Test
	public void evictionIsDeterministic()
	{
		// Gson hands the map back as a LinkedTreeMap in JSON order, so an
		// order-sensitive victim would differ between a fresh map and a reloaded
		// one and a partner would vanish on some logins but not others
		String[] forward = {"Alpha", "Bravo", "Charlie"};
		String[] backward = {"Charlie", "Bravo", "Alpha"};

		Map<String, Integer> a = new LinkedHashMap<>();
		for (String name : forward)
		{
			a.put(name, 1);
		}
		Map<String, Integer> b = new LinkedHashMap<>();
		for (String name : backward)
		{
			b.put(name, 1);
		}

		Map<String, Integer> afterA = PatronMark.credit(a,
			Collections.singletonList("Newcomer"), forward.length);
		Map<String, Integer> afterB = PatronMark.credit(b,
			Collections.singletonList("Newcomer"), backward.length);

		for (String name : forward)
		{
			Assert.assertEquals("victim must not depend on insertion order: " + name,
				PatronMark.countFor(afterA, name), PatronMark.countFor(afterB, name));
		}
	}

	@Test
	public void aCapOfZeroOrLessIsNoCapAtAll()
	{
		// defensive: a mis-tuned constant must not silently stop counting
		Map<String, Integer> counts = PatronMark.credit(null,
			Arrays.asList("Alpha", "Bravo", "Charlie"), 0);
		Assert.assertEquals(3, counts.size());
	}

	// --- D. who wears the mark ---

	@Test
	public void topPartnerIsIndependentOfMapOrder()
	{
		Map<String, Integer> seed = new LinkedHashMap<>();
		seed.put("Alpha", 3);
		seed.put("Bravo", 9);
		seed.put("Charlie", 4);

		Assert.assertEquals("Bravo", PatronMark.topPartner(new HashMap<>(seed)));
		Assert.assertEquals("Bravo", PatronMark.topPartner(new LinkedHashMap<>(seed)));
		Assert.assertEquals("Bravo", PatronMark.topPartner(new TreeMap<>(seed)));

		// a tie must resolve the same way from either insertion order
		Map<String, Integer> tieForward = new LinkedHashMap<>();
		tieForward.put("Alpha", 7);
		tieForward.put("Bravo", 7);
		Map<String, Integer> tieBackward = new LinkedHashMap<>();
		tieBackward.put("Bravo", 7);
		tieBackward.put("Alpha", 7);
		Assert.assertEquals(PatronMark.topPartner(tieForward),
			PatronMark.topPartner(tieBackward));
	}

	@Test
	public void topPartnerIgnoresJunkKeysAndZeroCounts()
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		counts.put("<unknown>", 999);
		counts.put("", 5);
		counts.put("Ghost", 0);
		counts.put("Nulled", null);
		counts.put("Zezima", 1);

		Assert.assertEquals("a placeholder must never become the displayed mark,"
			+ " however high its count", "Zezima", PatronMark.topPartner(counts));
	}

	@Test
	public void topPartnerOfNothingIsNothing()
	{
		// the legacy-save path and the day-one path: render no mark, not a blank row
		Assert.assertNull(PatronMark.topPartner(null));
		Assert.assertNull(PatronMark.topPartner(Collections.emptyMap()));
		Assert.assertNull(PatronMark.topPartner(Collections.singletonMap("Zezima", 0)));
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
		Map<String, Integer> counts = null;
		int crossings = 0;
		for (int contract = 1; contract <= 100; contract++)
		{
			int before = PatronMark.countFor(counts, "Zezima");
			counts = PatronMark.credit(counts, Collections.singletonList("Zezima"),
				Tuning.PATRON_MAX_PARTNERS);
			int after = PatronMark.countFor(counts, "Zezima");
			Assert.assertEquals(before + 1, after);
			if (PatronMark.crossedTier(before, after))
			{
				crossings++;
			}
		}
		Assert.assertEquals("one announcement per threshold, no more",
			Tuning.PATRON_TIERS.length, crossings);
		Assert.assertEquals("Zezima", PatronMark.topPartner(counts));
		Assert.assertEquals("Patron III", PatronMark.tierLabel(PatronMark.countFor(counts, "Zezima")));
	}
}
