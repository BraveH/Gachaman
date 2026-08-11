package com.gachaman.data;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads a data table out of {@code /com/gachaman/data/}.
 *
 * <p>Every table the gamemode reads — monsters, bosses, sets, tiers, rarity
 * overrides, quest gates, animation classifications, gear ladders — is data
 * rather than logic, so it lives in a resource that anyone can audit and
 * correct without touching code.
 *
 * <p>Never throws. A missing or malformed resource logs and yields the
 * caller's fallback: a table that fails to load must degrade to the
 * pre-table behaviour, not take the plugin down at class-init time.
 */
@Slf4j
public final class DataJson {
	private static final Gson GSON = new Gson();

	private DataJson() {
	}

	public static <T> T load(String name, Type type, T fallback) {
		try (InputStream in = DataJson.class.getResourceAsStream(
			"/com/gachaman/data/" + name + ".json")) {
			if (in == null) {
				log.warn("{}.json missing — falling back", name);
				return fallback;
			}
			T value = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
			return value == null ? fallback : value;
		}
		catch (Exception e) {
			log.error("Failed to load " + name + ".json", e);
			return fallback;
		}
	}
}
