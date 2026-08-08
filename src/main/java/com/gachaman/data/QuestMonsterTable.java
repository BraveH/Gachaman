package com.gachaman.data;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;

/**
 * Curated mapping of quests to the NPCs they require fighting. While such a
 * quest is IN_PROGRESS, attacking those NPCs is exempt from the combat lock.
 */
@Slf4j
public class QuestMonsterTable
{
	private static class Entry
	{
		String quest;
		List<String> npcNames;
	}

	private static class QuestsFile
	{
		List<Entry> quests;
	}

	/** lowercased npc name -> quests that unlock it while in progress. */
	private Map<String, List<Quest>> questsByNpc = Collections.emptyMap();

	public static QuestMonsterTable load(Gson gson)
	{
		QuestMonsterTable table = new QuestMonsterTable();
		try (InputStream in = QuestMonsterTable.class.getResourceAsStream(
			"/com/gachaman/data/quest-monsters.json"))
		{
			if (in == null)
			{
				log.warn("quest-monsters.json missing — quest combat exemptions disabled");
				return table;
			}
			QuestsFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), QuestsFile.class);
			Map<String, List<Quest>> index = new HashMap<>();
			for (Entry entry : file.quests)
			{
				Quest quest;
				try
				{
					quest = Quest.valueOf(entry.quest);
				}
				catch (IllegalArgumentException e)
				{
					log.warn("quest-monsters.json references unknown quest {}", entry.quest);
					continue;
				}
				for (String name : entry.npcNames)
				{
					index.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(quest);
				}
			}
			table.questsByNpc = Collections.unmodifiableMap(index);
		}
		catch (Exception e)
		{
			log.error("Failed to load quest-monsters.json", e);
		}
		return table;
	}

	public List<Quest> questsFor(String npcName)
	{
		return questsByNpc.getOrDefault(npcName.toLowerCase(Locale.ROOT), Collections.emptyList());
	}
}
