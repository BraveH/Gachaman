package com.gachaman.data;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import lombok.extern.slf4j.*;

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
	private DataJson() {
	}

	/**
	 * @param gson the CLIENT's Gson, injected. Never build a fresh one — the
	 *             Plugin Hub forbids it, and the client's instance carries the
	 *             type adapters RuneLite's own serialization relies on.
	 */
	public static <T> T load(Gson gson, String name, Type type, T fallback) {
		try (InputStream in = DataJson.class.getResourceAsStream(
			"/com/gachaman/data/" + name + ".json")) {
			if (in == null) {
				log.warn("{}.json missing — falling back", name);
				return fallback;
			}
			T value = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
			return value == null ? fallback : value;
		}
		catch (Exception e) {
			log.error("Failed to load " + name + ".json", e);
			return fallback;
		}
	}
}
