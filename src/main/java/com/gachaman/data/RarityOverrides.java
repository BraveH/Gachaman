package com.gachaman.data;

import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public final class RarityOverrides
{
	private static final Map<String, Rarity> OVERRIDES = load();

	private static Map<String, Rarity> load()
	{
		try (InputStream in = RarityOverrides.class.getResourceAsStream(
			"/com/gachaman/data/rarity-overrides.json"))
		{
			if (in == null)
			{
				log.warn("rarity-overrides.json missing — every card falls back to the stat heuristic");
				return Collections.emptyMap();
			}
			Map<String, List<String>> byRarity = new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8),
				new TypeToken<Map<String, List<String>>>()
				{
				}.getType());
			Map<String, Rarity> index = new HashMap<>();
			for (Map.Entry<String, List<String>> entry : byRarity.entrySet())
			{
				Rarity rarity = Rarity.valueOf(entry.getKey());
				for (String name : entry.getValue())
				{
					index.put(name, rarity);
				}
			}
			return Collections.unmodifiableMap(index);
		}
		catch (Exception e)
		{
			log.error("Failed to load rarity-overrides.json", e);
			return Collections.emptyMap();
		}
	}

	private RarityOverrides()
	{
	}

	public static Rarity lookup(String cleanName)
	{
		return OVERRIDES.get(cleanName);
	}
}
