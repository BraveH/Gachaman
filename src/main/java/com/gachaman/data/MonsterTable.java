package com.gachaman.data;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MonsterTable
{
	@Value
	public static class Monster
	{
		String name;
		int combatLevel;
		List<String> tags;
		boolean members;
		/** Slayer level required to harm it at all (0 = none). */
		int slayerLevel;
		/** Only attackable while on a matching SLAYER task — never rollable. */
		boolean slayerTaskOnly;
		/**
		 * No 100% drop (no bones/ashes/ether): absence of loot after a kill is
		 * NOT evidence the server denied kill credit for these.
		 */
		boolean noGuaranteedDrop;
	}

	private static class MonstersFile
	{
		List<Monster> monsters;
	}

	private List<Monster> monsters = Collections.emptyList();

	public static MonsterTable load(Gson gson)
	{
		MonsterTable table = new MonsterTable();
		try (InputStream in = MonsterTable.class.getResourceAsStream("/com/gachaman/data/monsters.json"))
		{
			MonstersFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MonstersFile.class);
			table.monsters = Collections.unmodifiableList(new ArrayList<>(file.monsters));
		}
		catch (Exception e)
		{
			log.error("Failed to load monsters.json", e);
		}
		return table;
	}

	public List<Monster> getMonsters()
	{
		return monsters;
	}
}
