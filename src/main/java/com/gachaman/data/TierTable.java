package com.gachaman.data;

import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** Curated tier ladders loaded from tiers.json; powers name-prefix classification. */
@Slf4j
public class TierTable
{
	@Value
	public static class Match
	{
		String tierKey;
		int rank;
		String familyKey;
	}

	private static class TierEntry
	{
		String tierKey;
		int rank;
		List<String> prefixes;
		/** Family names this tier's prefixes must NOT claim ("Black mask" is not metal). */
		List<String> excludeFamilies;
	}

	private static class Ladder
	{
		String ladderKey;
		List<TierEntry> tiers;
	}

	private static class HoloEntry
	{
		String tierKey;
		String name;
		String rarity;
		String representativeItemName;
	}

	private static class TiersFile
	{
		List<Ladder> ladders;
		List<HoloEntry> hologramTiers;
	}

	/** prefix (with trailing space) -> entry; longest prefix wins. */
	private final List<Map.Entry<String, Match>> prefixMatchers = new ArrayList<>();
	private final Map<String, Integer> rankByTier = new HashMap<>();
	private final Map<String, String> ladderByTier = new HashMap<>();
	/** tierKey -> lowercased families its prefixes must not classify. */
	private final Map<String, java.util.Set<String>> excludedFamiliesByTier = new HashMap<>();
	private final List<HologramDefinition> holograms = new ArrayList<>();

	public static TierTable load(Gson gson)
	{
		TierTable table = new TierTable();
		try (InputStream in = TierTable.class.getResourceAsStream("/com/gachaman/data/tiers.json"))
		{
			TiersFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TiersFile.class);
			for (Ladder ladder : file.ladders)
			{
				for (TierEntry tier : ladder.tiers)
				{
					table.rankByTier.put(tier.tierKey, tier.rank);
					table.ladderByTier.put(tier.tierKey, ladder.ladderKey);
					if (tier.excludeFamilies != null && !tier.excludeFamilies.isEmpty())
					{
						java.util.Set<String> excluded = new java.util.HashSet<>();
						for (String family : tier.excludeFamilies)
						{
							excluded.add(family.toLowerCase(java.util.Locale.ROOT));
						}
						table.excludedFamiliesByTier.put(tier.tierKey, excluded);
					}
					for (String prefix : tier.prefixes)
					{
						table.prefixMatchers.add(Map.entry(prefix + " ",
							new Match(tier.tierKey, tier.rank, null)));
					}
				}
			}
			// longest prefix first so "Black d'hide " beats "Black "
			table.prefixMatchers.sort((a, b) -> b.getKey().length() - a.getKey().length());
			if (file.hologramTiers != null)
			{
				for (HoloEntry h : file.hologramTiers)
				{
					table.holograms.add(new HologramDefinition(h.tierKey, h.name,
						Rarity.valueOf(h.rarity), h.representativeItemName));
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load tiers.json", e);
		}
		return table;
	}

	@Nullable
	public Match match(String cleanName)
	{
		for (Map.Entry<String, Match> entry : prefixMatchers)
		{
			if (cleanName.startsWith(entry.getKey()))
			{
				Match m = entry.getValue();
				String family = cleanName.substring(entry.getKey().length()).trim()
					.toLowerCase(java.util.Locale.ROOT);
				if (family.isEmpty())
				{
					return null;
				}
				// "Black mask" / "Black wizard hat" share the metal prefix but
				// are not metal gear — excluded families stay untiered
				java.util.Set<String> excluded = excludedFamiliesByTier.get(m.getTierKey());
				if (excluded != null && excluded.contains(family))
				{
					return null;
				}
				return new Match(m.getTierKey(), m.getRank(), family);
			}
		}
		return null;
	}

	public int rankOf(String tierKey)
	{
		return rankByTier.getOrDefault(tierKey, 0);
	}

	/** Ladder key ("metal"/"dhide"/"robes") for a tier, or null when unknown. */
	@Nullable
	public String ladderOf(String tierKey)
	{
		return ladderByTier.get(tierKey);
	}

	public List<HologramDefinition> getHolograms()
	{
		return holograms;
	}
}
