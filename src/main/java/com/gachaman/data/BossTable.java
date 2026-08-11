package com.gachaman.data;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

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
