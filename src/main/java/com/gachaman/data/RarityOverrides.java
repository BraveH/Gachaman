package com.gachaman.data;

import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Curated rarity overrides for iconic gear the stat heuristic misjudges: it
 * rates by melee-facing combat numbers, so prestige gear with tiny stats (skill
 * and max capes, 3rd age, gilded, raid uniques, prayer and mage gear, high-tier
 * ammo) crashes into Common while strength-heavy junk (kitchen weapons,
 * battlestaves, Bone mace) inflates into Legendary.
 *
 * <p>The 683 names themselves live in rarity-overrides.json rather than in a
 * static block here. They are pure data, and data in a resource can be audited
 * and corrected by anyone without touching code — the same shape the monster,
 * boss, set, tier and quest tables already use.
 */
@Slf4j
@Singleton
public final class RarityOverrides {
	private final Map<String, Rarity> overrides;

	@Inject
	RarityOverrides(Gson gson) {
		Map<String, List<String>> byRarity = DataJson.load(gson, "rarity-overrides",
			new TypeToken<Map<String, List<String>>>() {
			}.getType(), Collections.emptyMap());
		Map<String, Rarity> index = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : byRarity.entrySet()) {
			// A key that is not a Rarity is skipped, not thrown on. This runs in a
			// static initializer, so an exception here does not degrade the
			// heuristic — it fails the whole class and takes the plugin with it.
			// The other data files carry a "_comment" key by convention, and the
			// first person to add one here must not be able to do that.
			Rarity rarity;
			try {
				rarity = Rarity.valueOf(entry.getKey());
			}
			catch (IllegalArgumentException e) {
				log.warn("rarity-overrides.json: '{}' is not a rarity — skipped", entry.getKey());
				continue;
			}
			for (String name : entry.getValue()) {
				index.put(name, rarity);
			}
		}
		this.overrides = Collections.unmodifiableMap(index);
	}

	public Rarity lookup(String cleanName) {
		return overrides.get(cleanName);
	}
}
