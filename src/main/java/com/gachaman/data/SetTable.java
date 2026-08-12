package com.gachaman.data;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;

@Slf4j
public class SetTable {
	public enum PerkType {
		KILL_GC_PERCENT, COMPLETION_GC_PERCENT, SIDEBET_GC_PERCENT
	}

	public enum PerkScope {
		GLOBAL, MONSTER_NAME_SET, CATEGORY_TAG
	}

	@Value
	public static class Perk {
		PerkType type;
		PerkScope scope;
		List<String> scopeValues;
		int magnitudePercent;
	}

	@Value
	public static class CardSet {
		String setKey;
		String name;
		List<String> cardNames;
		Perk perk;
		boolean boss;
	}

	private static class SetsFile {
		List<CardSet> sets;
	}

	@Getter
	private List<CardSet> sets = Collections.emptyList();

	public static SetTable load(Gson gson) {
		SetTable table = new SetTable();
		try (InputStream in = SetTable.class.getResourceAsStream("/com/gachaman/data/sets.json")) {
			SetsFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), SetsFile.class);
			table.sets = Collections.unmodifiableList(new ArrayList<>(file.sets));
		}
		catch (Exception e) {
			log.error("Failed to load sets.json", e);
		}
		return table;
	}

}
