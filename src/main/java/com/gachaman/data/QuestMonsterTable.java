package com.gachaman.data;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Curated mapping of quests to the NPCs they require fighting. While such a
 * quest is IN_PROGRESS, attacking those NPCs is exempt from the combat lock.
 *
 * <p>An entry may narrow that to a window of the quest by naming the game
 * variable the quest tracks its progress in, plus a min/max on its value. This
 * matters because most of these NPCs are ordinary monsters the gamemode
 * otherwise gates: start Witch's Potion and, without a window, every rat in the
 * game is free for the rest of the run.
 *
 * <p>The window is OPTIONAL and an entry without one behaves exactly as it did
 * before windows existed - exempt for the whole quest. That asymmetry is
 * deliberate: too loose costs a little economy leak, too tight blocks a player
 * out of a quest they cannot then finish. Anything unverified is left open.
 *
 * <p>A quest may appear on several entries, each with its own window and NPC
 * list, which is how a quest that needs different monsters at different stages
 * is expressed.
 */
@Slf4j
public class QuestMonsterTable {
	private static class Entry {
		String quest;
		List<String> npcNames;
		Integer varp;
		Integer varbit;
		Integer min;
		Integer max;
	}

	private static class QuestsFile {
		List<Entry> quests;
	}

	/**
	 * One reason an NPC may be attacked: {@code quest} must be IN_PROGRESS and,
	 * when {@link #hasWindow()}, the tracked variable must be inside [min, max].
	 */
	@RequiredArgsConstructor
	public static final class Gate {
		/** The NPC as written in the file, for display; the index keys on its
		 *  lowercase form. One Gate per (npc, entry) so it can name itself. */
		@Getter
		private final String npcName;
		@Getter
		private final Quest quest;
		/** Id in the VarPlayerID or VarbitID space, or -1 for no window. */
		@Getter
		private final int varId;
		/** True to read it with getVarbitValue, false with getVarpValue. */
		@Getter
		private final boolean varbit;
		private final int min;
		private final int max;

		public boolean hasWindow() {
			return varId >= 0;
		}

		/** Pure so it is testable without a Client; the caller does the read. */
		public boolean contains(int value) {
			return value >= min && value <= max;
		}
	}

	/** lowercased npc name -> every reason it may be attacked. */
	private Map<String, List<Gate>> gatesByNpc = Collections.emptyMap();
	/** Same gates, flat and in file order, for enumeration by the panel. */
	private List<Gate> allGates = Collections.emptyList();

	public static QuestMonsterTable load(Gson gson) {
		QuestMonsterTable table = new QuestMonsterTable();
		try (InputStream in = QuestMonsterTable.class.getResourceAsStream(
			"/com/gachaman/data/quest-monsters.json")) {
			if (in == null) {
				log.warn("quest-monsters.json missing — quest combat exemptions disabled");
				return table;
			}
			QuestsFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), QuestsFile.class);
			Map<String, List<Gate>> index = new HashMap<>();
			List<Gate> all = new ArrayList<>();
			for (Entry entry : file.quests) {
				Quest quest;
				try {
					quest = Quest.valueOf(entry.quest);
				}
				catch (IllegalArgumentException e) {
					log.warn("quest-monsters.json references unknown quest {}", entry.quest);
					continue;
				}
				for (String name : entry.npcNames) {
					Gate gate = gateOf(entry, quest, name);
					index.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(gate);
					all.add(gate);
				}
			}
			table.gatesByNpc = Collections.unmodifiableMap(index);
			table.allGates = Collections.unmodifiableList(all);
		}
		catch (Exception e) {
			log.error("Failed to load quest-monsters.json", e);
		}
		return table;
	}

	/**
	 * A malformed window drops to no window rather than to no exemption: the
	 * failure that locks a player out of a quest is the one worth avoiding, and
	 * the build catches malformed entries anyway (DatasetIntegrityTest).
	 */
	private static Gate gateOf(Entry entry, Quest quest, String npcName) {
		boolean hasRange = entry.min != null || entry.max != null;
		if (entry.varp != null && entry.varbit != null) {
			log.warn("{} names both a varp and a varbit — ignoring the window", entry.quest);
			return new Gate(npcName, quest, -1, false, 0, 0);
		}
		Integer id = entry.varp != null ? entry.varp : entry.varbit;
		if (id == null || !hasRange) {
			if (id != null || hasRange) {
				log.warn("{} has half a window (id={}, min={}, max={}) — ignoring it",
					entry.quest, id, entry.min, entry.max);
			}
			return new Gate(npcName, quest, -1, false, 0, 0);
		}
		int min = entry.min != null ? entry.min : Integer.MIN_VALUE;
		int max = entry.max != null ? entry.max : Integer.MAX_VALUE;
		if (min > max) {
			log.warn("{} has min {} above max {} — ignoring the window", entry.quest, min, max);
			return new Gate(npcName, quest, -1, false, 0, 0);
		}
		return new Gate(npcName, quest, id, entry.varbit != null, min, max);
	}

	public List<Gate> gatesFor(String npcName) {
		return gatesByNpc.getOrDefault(npcName.toLowerCase(Locale.ROOT), Collections.emptyList());
	}

	/** Every gate in the table, for callers that have to enumerate rather than ask. */
	public List<Gate> allGates() {
		return allGates;
	}
}
