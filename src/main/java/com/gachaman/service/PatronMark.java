package com.gachaman.service;

import com.gachaman.Tuning;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 * Keyed by DISPLAY NAME, never by member id. A party memberId is drawn from a
 * fresh Random when PartyService is constructed and drawn AGAIN inside
 * changeParty(), so an id belongs to one process's one party session: an
 * id-keyed count would reset on every login, leaving the higher tiers
 * unreachable by construction, and would pile dead keys into a save blob that
 * is gzipped and hashed in full on every single mutate.
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

	private PatronMark()
	{
	}

	/**
	 * A partner name fit to persist and to draw, or null when there is nothing
	 * creditable.
	 *
	 * RuneLite already ran toJagexName and removeTags over anything that
	 * reached PartyMember.setDisplayName, so this only has to trim and refuse
	 * the placeholders. "&lt;unknown&gt;" is PartyMember's CONSTRUCTOR DEFAULT and
	 * is what every member reads as while the built-in Party plugin is off —
	 * crediting it would invent a shared top patron out of nothing, and the
	 * angle brackets would additionally be eaten by the sidebar's HTML
	 * renderer. The right degradation is a no-op, never a placeholder.
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

	/**
	 * Whether two names are the same partner. Both sides are normalized first,
	 * so a placeholder can never match: this is what stops the party page's
	 * own "A party member" fallback row, or a member still reading as
	 * "&lt;unknown&gt;", from wearing somebody else's mark.
	 */
	public static boolean sameName(@Nullable String a, @Nullable String b)
	{
		String left = normalizeName(a);
		String right = normalizeName(b);
		return left != null && right != null && left.equalsIgnoreCase(right);
	}

	/** Shared contracts finished with one partner; 0 for anyone uncounted. */
	public static int countFor(@Nullable Map<String, Integer> counts, @Nullable String name)
	{
		String normalized = normalizeName(name);
		if (counts == null || counts.isEmpty() || normalized == null)
		{
			return 0;
		}
		String key = existingKey(counts, normalized);
		return key == null ? 0 : value(counts.get(key));
	}

	/**
	 * One mark per distinct partner on a finished contract.
	 *
	 * Returns the CALLER'S OWN instance when nothing is creditable, so
	 * GachaStateService.mutate short-circuits on {@code next == state} and the
	 * completion skips the gzip + SHA-256 encode of the whole save entirely.
	 * Never writes into the map it was handed: the state object it came from
	 * is shared and immutable by contract.
	 */
	public static Map<String, Integer> credit(@Nullable Map<String, Integer> current,
		@Nullable Collection<String> partnerNames, int cap)
	{
		if (partnerNames == null || partnerNames.isEmpty())
		{
			return current;
		}
		// dedupe case-insensitively: one finished contract is one mark per
		// partner even when the same account sits in the party from two
		// clients under two member ids
		Map<String, String> distinct = new LinkedHashMap<>();
		for (String raw : partnerNames)
		{
			String name = normalizeName(raw);
			if (name != null)
			{
				distinct.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
			}
		}
		if (distinct.isEmpty())
		{
			return current;
		}
		Map<String, Integer> next = current == null
			? new LinkedHashMap<>() : new LinkedHashMap<>(current);
		boolean changed = false;
		for (String name : distinct.values())
		{
			String key = existingKey(next, name);
			if (key != null)
			{
				// keep the ORIGINAL key's casing so a partner whose client
				// cased the name differently keeps one row, not two
				next.put(key, value(next.get(key)) + 1);
				changed = true;
				continue;
			}
			if (cap > 0 && next.size() >= cap && !evictOneOff(next))
			{
				// at the cap with nobody to displace: drop the newcomer rather
				// than a partner you actually have a history with
				continue;
			}
			next.put(name, 1);
			changed = true;
		}
		// a full-cap contract where every partner was turned away changed
		// nothing, and handing back an equal-but-distinct map here would defeat
		// the identity short-circuit and buy a whole-save encode for no edit
		return changed ? next : current;
	}

	/**
	 * The partner who has finished the most contracts with you, or null when
	 * nobody has. Ties break by name so the answer cannot depend on map
	 * iteration order — Gson hands back a LinkedTreeMap in JSON order, so an
	 * order-sensitive winner would differ between a fresh map and a reloaded
	 * one and the displayed mark would move on its own.
	 */
	@Nullable
	public static String topPartner(@Nullable Map<String, Integer> counts)
	{
		if (counts == null || counts.isEmpty())
		{
			return null;
		}
		String best = null;
		int bestCount = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String name = normalizeName(entry.getKey());
			int count = value(entry.getValue());
			if (name == null || count <= 0)
			{
				continue; // a junk key from a hand-edited save never wins
			}
			if (count > bestCount || (count == bestCount && nameOrder(name, best) < 0))
			{
				best = name;
				bestCount = count;
			}
		}
		return best;
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

	/** Package-private for the test that pins the labels to the thresholds. */
	static int labelCount()
	{
		return TIER_LABELS.length;
	}

	// --- internals ---

	/** The key already holding this partner under any casing, or null. */
	@Nullable
	private static String existingKey(Map<String, Integer> counts, String name)
	{
		if (counts.containsKey(name))
		{
			return name;
		}
		for (String key : counts.keySet())
		{
			if (key != null && key.equalsIgnoreCase(name))
			{
				return key;
			}
		}
		return null;
	}

	/**
	 * Drop the lowest-count partner, but ONLY when that partner has a single
	 * contract, so the bound cannot be weaponised: a stranger can never push
	 * out a history of two or more. Ties break by name to keep eviction
	 * deterministic across a reload.
	 */
	private static boolean evictOneOff(Map<String, Integer> counts)
	{
		String victim = null;
		int lowest = Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			int count = value(entry.getValue());
			if (count < lowest || (count == lowest && nameOrder(entry.getKey(), victim) < 0))
			{
				lowest = count;
				victim = entry.getKey();
			}
		}
		if (victim == null || lowest > 1)
		{
			return false;
		}
		counts.remove(victim);
		return true;
	}

	/** Case-insensitive first, then case-sensitive, so no two names tie. */
	private static int nameOrder(String a, @Nullable String b)
	{
		if (b == null)
		{
			return -1;
		}
		int cmp = a.compareToIgnoreCase(b);
		return cmp != 0 ? cmp : a.compareTo(b);
	}

	/** Gson can deserialize {"Zezima":null} — treat a null count as zero. */
	private static int value(@Nullable Integer count)
	{
		return count == null ? 0 : count;
	}
}
