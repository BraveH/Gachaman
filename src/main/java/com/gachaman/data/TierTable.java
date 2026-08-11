package com.gachaman.data;

import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** Curated tier ladders loaded from tiers.json; powers name-prefix classification. */
@Slf4j
public class TierTable {
	@Value
	public static class Match {
		String tierKey;
		int rank;
		String familyKey;
	}

	private static class TierEntry {
		String tierKey;
		int rank;
		List<String> prefixes;
		/** Family names this tier's prefixes must NOT claim ("Black mask" is not metal). */
		List<String> excludeFamilies;
		/**
		 * Real OSRS requirements: the ladder's primary skill (Ranged/Magic/melee) and
		 * Defence. Boxed on purpose — a tier authored without them must read null and
		 * fail closed, not deserialize to 0 and become wieldable at level 1 by everyone.
		 */
		Integer reqLevel;
		Integer reqDefence;
	}

	private static class Ladder {
		String ladderKey;
		List<TierEntry> tiers;
	}

	private static class HoloEntry {
		String tierKey;
		String name;
		String rarity;
		String representativeItemName;
	}

	private static class TiersFile {
		List<Ladder> ladders;
		List<HoloEntry> hologramTiers;
	}

	/** A tier with no authored requirement is out of reach until 99 — fail closed. */
	private static final int UNKNOWN_REQ = 99;

	/** prefix (with trailing space) -> entry; longest prefix wins. */
	private final List<Map.Entry<String, Match>> prefixMatchers = new ArrayList<>();
	private final Map<String, Integer> rankByTier = new HashMap<>();
	private final Map<String, String> ladderByTier = new HashMap<>();
	private final Map<String, Integer> reqLevelByTier = new HashMap<>();
	private final Map<String, Integer> reqDefenceByTier = new HashMap<>();
	/**
	 * tierKey -> human label, harvested from the hologram names rather than authored a
	 * second time, so a tier renamed in tiers.json cannot end up reading one way in the
	 * collection and another way in the odds disclosure.
	 */
	private final Map<String, String> displayNameByTier = new HashMap<>();
	/** tierKey -> lowercased families its prefixes must not classify. */
	private final Map<String, Set<String>> excludedFamiliesByTier = new HashMap<>();
	@Getter
	private final List<HologramDefinition> holograms = new ArrayList<>();

	public static TierTable load(Gson gson) {
		TierTable table = new TierTable();
		try (InputStream in = TierTable.class.getResourceAsStream("/com/gachaman/data/tiers.json")) {
			TiersFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TiersFile.class);
			for (Ladder ladder : file.ladders) {
				for (TierEntry tier : ladder.tiers) {
					table.rankByTier.put(tier.tierKey, tier.rank);
					table.ladderByTier.put(tier.tierKey, ladder.ladderKey);
					if (tier.reqLevel == null || tier.reqDefence == null) {
						// left absent, the tier falls back to UNKNOWN_REQ and drops out of
						// every proximity-gated roll — loud, because ChestService's
						// unfiltered fallback would otherwise mask it as "gate stopped working"
						log.warn("tier {} has no reqLevel/reqDefence in tiers.json; failing closed", tier.tierKey);
					}
					else {
						table.reqLevelByTier.put(tier.tierKey, tier.reqLevel);
						table.reqDefenceByTier.put(tier.tierKey, tier.reqDefence);
					}
					if (tier.excludeFamilies != null && !tier.excludeFamilies.isEmpty()) {
						Set<String> excluded = new HashSet<>();
						for (String family : tier.excludeFamilies) {
							excluded.add(family.toLowerCase(Locale.ROOT));
						}
						table.excludedFamiliesByTier.put(tier.tierKey, excluded);
					}
					for (String prefix : tier.prefixes) {
						table.prefixMatchers.add(Map.entry(prefix + " ",
							new Match(tier.tierKey, tier.rank, null)));
					}
				}
			}
			// longest prefix first so "Black d'hide " beats "Black "
			table.prefixMatchers.sort((a, b) -> b.getKey().length() - a.getKey().length());
			if (file.hologramTiers != null) {
				for (HoloEntry h : file.hologramTiers) {
					table.holograms.add(new HologramDefinition(h.tierKey, h.name,
						Rarity.valueOf(h.rarity), h.representativeItemName));
					// "Dragon Hologram" is the collection's label for the set; the tier itself
					// is just "Dragon", which is what a per-tier odds row wants to say
					String display = h.name.endsWith(" Hologram")
						? h.name.substring(0, h.name.length() - " Hologram".length())
						: h.name;
					table.displayNameByTier.put(h.tierKey, display);
				}
			}
		}
		catch (Exception e) {
			log.error("Failed to load tiers.json", e);
		}
		return table;
	}

	@Nullable
	public Match match(String cleanName) {
		for (Map.Entry<String, Match> entry : prefixMatchers) {
			if (cleanName.startsWith(entry.getKey())) {
				Match m = entry.getValue();
				String family = cleanName.substring(entry.getKey().length()).trim()
					.toLowerCase(Locale.ROOT);
				if (family.isEmpty()) {
					return null;
				}
				// "Black mask" / "Black wizard hat" share the metal prefix but
				// are not metal gear — excluded families stay untiered
				Set<String> excluded = excludedFamiliesByTier.get(m.getTierKey());
				if (excluded != null && excluded.contains(family)) {
					return null;
				}
				return new Match(m.getTierKey(), m.getRank(), family);
			}
		}
		return null;
	}

	public int rankOf(String tierKey) {
		return rankByTier.getOrDefault(tierKey, 0);
	}

	/** Ladder key ("metal"/"dhide"/"robes") for a tier, or null when unknown. */
	@Nullable
	public String ladderOf(String tierKey) {
		return ladderByTier.get(tierKey);
	}

	/**
	 * Level in the ladder's primary skill this tier really needs. Consulted for the
	 * dhide and robes ladders, whose ranks are power ordinals rather than levels; the
	 * metal ladder is still read rank-wise off Tuning.TIER_RANK_LEVELS, which happens
	 * to transcribe it exactly. The metal values here are documentation.
	 */
	public int reqLevelOf(String tierKey) {
		return reqLevelByTier.getOrDefault(tierKey, UNKNOWN_REQ);
	}

	/** Defence this tier's body piece really needs. See reqLevelOf. */
	public int reqDefenceOf(String tierKey) {
		return reqDefenceByTier.getOrDefault(tierKey, UNKNOWN_REQ);
	}

	/**
	 * Human label for a tier row. Falls back to the tierKey read as words rather than
	 * to the raw key, so a tier authored without a hologram still shows up as
	 * "Frog Leather" in the odds panel instead of leaking "frog_leather" into the UI.
	 */
	public String displayNameOf(String tierKey) {
		String known = displayNameByTier.get(tierKey);
		if (known != null) {
			return known;
		}
		StringBuilder out = new StringBuilder();
		for (String word : tierKey.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.length() == 0 ? tierKey : out.toString();
	}

}
