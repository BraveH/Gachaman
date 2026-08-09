package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.PatronRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The Patron's Mark: a private, cosmetic tally of how many shared party
 * contracts each partner has finished alongside you.
 *
 * STRICTLY COSMETIC, deliberately. It pays no GC, feeds no CreditSink
 * modifier, multiplies nothing and gates nothing. A patron count that is
 * worth something makes farming a friend the correct play, and the mark stops
 * meaning what it says. Do not add an economic hook here.
 *
 * Keyed by ACCOUNT KEY — {@link AccountKey}, the hashed account hash the party
 * layer broadcasts — with the display name demoted to a label inside the
 * {@link PatronRecord}. Two identifiers were rejected first and it is worth
 * saying why. A party memberId is drawn from a fresh Random when PartyService
 * is constructed and drawn AGAIN inside changeParty(), so an id-keyed count
 * would reset on every login and the higher tiers would be unreachable by
 * construction. A display name survives a login but not a rename: a partner
 * who changes theirs forks into two rows, each with half a history, and
 * neither one is right. The account key survives both.
 *
 * The key is SELF-REPORTED and unauthenticated — it arrives over the party
 * relay from another player's client, exactly like the name it replaces. A
 * hostile client can claim any key it likes and inflate a count in a ledger
 * only its owner will ever see. That is the whole reason this stays cosmetic.
 *
 * Every rule lives here as a pure static so all of it is testable — the
 * service-side caller is reduced to reading a roster and calling these.
 */
public final class PatronMark
{
	/**
	 * OSRS display names are at most 12 characters. Anything longer did not
	 * come from a real name — the string arrives over the party relay from
	 * another player's client, so it is not trusted.
	 */
	private static final int MAX_NAME = 12;

	/** One longer than Tuning.PATRON_TIERS: index 0 is "counted, no tier yet". */
	private static final String[] TIER_LABELS =
		{"Patron", "Patron I", "Patron II", "Patron III"};

	/**
	 * Display order, and the SINGLE definition of it: most contracts first,
	 * then name, then key. Used by both {@link #ranked} and {@link #topKey} so
	 * the Patrons page's first row and the party page's top-patron mark can
	 * never disagree about who the mark belongs to.
	 *
	 * The key is the final tiebreak precisely because it is always present and
	 * always canonical. Without it two nameless one-contract partners would tie
	 * completely, and Gson hands the map back in JSON order, so the ordering
	 * would differ between a fresh ledger and a reloaded one and the page would
	 * reshuffle on its own.
	 */
	private static final Comparator<Map.Entry<String, PatronRecord>> DISPLAY_ORDER =
		Comparator.<Map.Entry<String, PatronRecord>>comparingInt(e -> -e.getValue().getCount())
			.thenComparing(e -> nameOf(e.getValue()), PatronMark::nameOrder)
			.thenComparing(Map.Entry::getKey);

	private PatronMark()
	{
		return;
	}

	/**
	 * A partner name fit to persist and to draw, or null when there is nothing
	 * drawable.
	 *
	 * RuneLite already ran toJagexName and removeTags over anything that
	 * reached PartyMember.setDisplayName, so this only has to trim and refuse
	 * the placeholders. "&lt;unknown&gt;" is PartyMember's CONSTRUCTOR DEFAULT and
	 * is what every member reads as while the built-in Party plugin is off —
	 * storing it would put a placeholder on the Patrons page as though it were
	 * somebody's name, and the angle brackets would additionally be eaten by
	 * the sidebar's HTML renderer.
	 *
	 * Unlike the old name-keyed ledger, a null here is no longer fatal: the
	 * account key still identifies the partner, so the contract is credited
	 * either way and only the label is missing.
	 */
	@Nullable
	public static String normalizeName(@Nullable String raw)
	{
		if (raw == null)
		{
			return null;
		}
		// U+00A0. Jagex names use it as the word separator, and it reads as a
		// normal space but would key separately — written as a code point
		// because a literal one in source is invisible and one editor pass
		// or encoding round-trip would silently eat it
		String name = raw.replace((char) 0xA0, ' ').trim();
		if (name.isEmpty() || name.length() > MAX_NAME
			|| name.indexOf('<') >= 0 || name.indexOf('>') >= 0)
		{
			return null;
		}
		return name;
	}

	/** The partner's record, or null when they are not in the ledger. */
	@Nullable
	public static PatronRecord recordFor(@Nullable Map<String, PatronRecord> ledger,
		@Nullable String accountKey)
	{
		String key = AccountKey.normalize(accountKey);
		if (ledger == null || ledger.isEmpty() || key == null)
		{
			return null;
		}
		PatronRecord record = ledger.get(key);
		return record == null || record.getCount() <= 0 ? null : record;
	}

	/** Shared contracts finished with one partner; 0 for anyone uncounted. */
	public static int countFor(@Nullable Map<String, PatronRecord> ledger,
		@Nullable String accountKey)
	{
		PatronRecord record = recordFor(ledger, accountKey);
		return record == null ? 0 : record.getCount();
	}

	/**
	 * Every counted partner in display order — what the Patrons page draws.
	 *
	 * Junk is dropped rather than rendered: a record with no count is not a
	 * partner, and a key that is not a key came from a hand-edited save.
	 */
	public static List<PatronRecord> ranked(@Nullable Map<String, PatronRecord> ledger)
	{
		List<Map.Entry<String, PatronRecord>> entries = sortedEntries(ledger);
		List<PatronRecord> out = new ArrayList<>(entries.size());
		for (Map.Entry<String, PatronRecord> entry : entries)
		{
			out.add(entry.getValue());
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * The account key of the partner who has finished the most contracts with
	 * you, or null when nobody has. Always {@link #ranked}'s first entry.
	 */
	@Nullable
	public static String topKey(@Nullable Map<String, PatronRecord> ledger)
	{
		List<Map.Entry<String, PatronRecord>> entries = sortedEntries(ledger);
		return entries.isEmpty() ? null : entries.get(0).getKey();
	}

	/** Distinct counted partners — the Patrons page's headline, and its gate. */
	public static int partnerCount(@Nullable Map<String, PatronRecord> ledger)
	{
		return sortedEntries(ledger).size();
	}

	/** Shared contracts across every partner. Not a contract count: a shared
	 * contract with three partners is three marks and reads as three here. */
	public static int totalMarks(@Nullable Map<String, PatronRecord> ledger)
	{
		int total = 0;
		for (Map.Entry<String, PatronRecord> entry : sortedEntries(ledger))
		{
			total += entry.getValue().getCount();
		}
		return total;
	}

	/**
	 * One mark per distinct partner on a finished contract.
	 *
	 * {@code partners} maps account key to the partner's display name at the
	 * moment of completion; a null or unusable name still credits the mark and
	 * simply leaves the previous label alone. Passing a Map is what makes the
	 * "one mark per partner" rule structural rather than a dedupe pass — the
	 * same account sitting in the party from two clients under two member ids
	 * collapses to one entry before this is ever called.
	 *
	 * Returns the CALLER'S OWN instance when nothing is creditable, so
	 * GachaStateService.mutate short-circuits on {@code next == state} and the
	 * completion skips the gzip + SHA-256 encode of the whole save entirely.
	 * Never writes into the map it was handed: the state object it came from
	 * is shared and immutable by contract.
	 */
	public static Map<String, PatronRecord> credit(@Nullable Map<String, PatronRecord> current,
		@Nullable Map<String, String> partners, int cap, long nowMs)
	{
		if (partners == null || partners.isEmpty())
		{
			return current;
		}
		Map<String, PatronRecord> next = current == null
			? new LinkedHashMap<>() : new LinkedHashMap<>(current);
		// two raw keys can normalize to ONE partner (they differ only in case),
		// and the loop below would then credit that partner twice off a single
		// contract. The caller normalizes before it builds the map, so this only
		// backstops a future one — but "one mark per partner" is the rule the
		// whole feature rests on, and a rule that holds by convention is not one
		Set<String> credited = new HashSet<>();
		boolean changed = false;
		for (Map.Entry<String, String> partner : partners.entrySet())
		{
			String key = AccountKey.normalize(partner.getKey());
			if (key == null || !credited.add(key))
			{
				continue; // not an identity, or an identity already counted
			}
			String name = normalizeName(partner.getValue());
			PatronRecord existing = next.get(key);
			if (existing != null && existing.getCount() > 0)
			{
				// keep the last name we could read rather than blanking a drawn
				// row because this one completion happened while they were
				// logged out of the roster
				next.put(key, new PatronRecord(name != null ? name : existing.getName(),
					existing.getCount() + 1, nowMs));
				changed = true;
				continue;
			}
			if (cap > 0 && next.size() >= cap && !evictOneOff(next))
			{
				// at the cap with nobody to displace: drop the newcomer rather
				// than a partner you actually have a history with
				continue;
			}
			next.put(key, new PatronRecord(name, 1, nowMs));
			changed = true;
		}
		// a full-cap contract where every partner was turned away changed
		// nothing, and handing back an equal-but-distinct map here would defeat
		// the identity short-circuit and buy a whole-save encode for no edit
		return changed ? next : current;
	}

	/** 0 = below the first threshold, up to Tuning.PATRON_TIERS.length. */
	public static int tierFor(int count)
	{
		int tier = 0;
		for (int threshold : Tuning.PATRON_TIERS)
		{
			if (count >= threshold)
			{
				tier++;
			}
		}
		return tier;
	}

	/** True only on the completion that pushes a partner up a tier. */
	public static boolean crossedTier(int before, int after)
	{
		return tierFor(after) > tierFor(before);
	}

	/** Clamped, so adding a fourth threshold to PATRON_TIERS cannot throw. */
	public static String tierLabel(int count)
	{
		return TIER_LABELS[Math.min(tierFor(count), TIER_LABELS.length - 1)];
	}

	/** What to draw for a partner whose client never told us their name. */
	public static String displayName(@Nullable PatronRecord record)
	{
		String name = record == null ? null : normalizeName(record.getName());
		return name == null ? "An unnamed patron" : name;
	}

	/** Package-private for the test that pins the labels to the thresholds. */
	static int labelCount()
	{
		return TIER_LABELS.length;
	}

	// --- internals ---

	/** Counted, key-valid entries in {@link #DISPLAY_ORDER}. */
	private static List<Map.Entry<String, PatronRecord>> sortedEntries(
		@Nullable Map<String, PatronRecord> ledger)
	{
		if (ledger == null || ledger.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Map.Entry<String, PatronRecord>> entries = new ArrayList<>(ledger.size());
		for (Map.Entry<String, PatronRecord> entry : ledger.entrySet())
		{
			// a null VALUE is reachable: Gson deserializes {"abc…":null} happily
			if (entry.getValue() == null || entry.getValue().getCount() <= 0)
			{
				continue;
			}
			// CANONICAL, not merely normalizable. recordFor looks the partner up
			// with the normalized key, so a stored "…AA" would be listed here and
			// found by nothing — the Patrons page would name a top patron the
			// party page could not draw a pip for. credit() only ever writes
			// canonical keys, so a non-canonical one came from a hand-edited save
			// and the honest reading of it is "not one of ours"
			if (!entry.getKey().equals(AccountKey.normalize(entry.getKey())))
			{
				continue;
			}
			entries.add(entry);
		}
		entries.sort(DISPLAY_ORDER);
		return entries;
	}

	/**
	 * Drop the least valuable partner, but ONLY when that partner has a single
	 * contract, so the bound cannot be weaponised: a stranger can never push
	 * out a history of two or more. Among one-offs the one you shared with
	 * longest ago goes first, and the key breaks any remaining tie so eviction
	 * is deterministic across a reload.
	 */
	private static boolean evictOneOff(Map<String, PatronRecord> ledger)
	{
		String victim = null;
		int lowest = Integer.MAX_VALUE;
		long oldest = Long.MAX_VALUE;
		for (Map.Entry<String, PatronRecord> entry : ledger.entrySet())
		{
			PatronRecord record = entry.getValue();
			// a junk row is the ideal victim: it occupies a slot and draws nothing
			int count = record == null ? 0 : record.getCount();
			long at = record == null ? 0 : record.getLastSharedAt();
			if (count < lowest
				|| (count == lowest && at < oldest)
				|| (count == lowest && at == oldest && keyOrder(entry.getKey(), victim) < 0))
			{
				lowest = count;
				oldest = at;
				victim = entry.getKey();
			}
		}
		if (victim == null || lowest > 1)
		{
			return false;
		}
		ledger.remove(victim);
		return true;
	}

	/** Nulls last, then case-insensitive, then case-sensitive, so no two tie. */
	private static int nameOrder(@Nullable String a, @Nullable String b)
	{
		if (a == null || b == null)
		{
			return a == b ? 0 : (a == null ? 1 : -1);
		}
		int cmp = a.compareToIgnoreCase(b);
		return cmp != 0 ? cmp : a.compareTo(b);
	}

	private static int keyOrder(String a, @Nullable String b)
	{
		return b == null ? -1 : a.compareTo(b);
	}

	@Nullable
	private static String nameOf(PatronRecord record)
	{
		return normalizeName(record.getName());
	}
}
