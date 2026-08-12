package com.gachaman.data;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;

@Slf4j
public class BossTable {
	@Value
	public static class Boss {
		String bossName;
		String chatName;
		List<Integer> kcMilestones;
		String setTag;
	}

	private static class BossesFile {
		List<Boss> bosses;
	}

	@Getter
	private List<Boss> bosses = Collections.emptyList();

	public static BossTable load(Gson gson) {
		BossTable table = new BossTable();
		try (InputStream in = BossTable.class.getResourceAsStream("/com/gachaman/data/bosses.json")) {
			BossesFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), BossesFile.class);
			table.bosses = Collections.unmodifiableList(new ArrayList<>(file.bosses));
		}
		catch (Exception e) {
			log.error("Failed to load bosses.json", e);
		}
		return table;
	}

}
