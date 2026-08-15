package com.gachaman.data;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

@Slf4j
public class MonsterTable {
	@Value
	public static class Monster {
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
		/**
		 * Melee cannot physically reach it, so a contract on it is UNWINNABLE for
		 * a player the wheel has locked to melee — not merely slow or resisted.
		 *
		 * <p>The bar for this flag is reach, never damage. A monster that resists
		 * melee, has high melee defence, or is simply a bad idea to melee does NOT
		 * belong here; a false positive silently deletes a monster from every
		 * melee player's contract pool, which is its own bug. Zalcano is the
		 * instructive near-miss: it is immune to conventional combat ENTIRELY, so
		 * ranged and magic fare no better and the flag would say something untrue.
		 * Ducks are the other one — they swim, but they also walk ashore, and a
		 * melee player can hit them there.
		 */
		boolean meleeUnreachable;
		/**
		 * Quests that must ALL be FINISHED before this monster can be reached or
		 * damaged — {@link Quest} constant names, never ordinals.
		 * Empty (the common case) means anyone may fight it.
		 *
		 * Area locks count: every Morytania monster carries PRIEST_IN_PERIL even
		 * though the quest has nothing to do with the monster itself. What does
		 * NOT belong here is anything a player can grind past — skill levels,
		 * items, diaries — nor a quest that merely needs STARTING, because a
		 * contract you can open by starting a quest is still completable.
		 *
		 * Normalised to a non-null immutable list by {@link #load}; never trust
		 * Gson to have populated it.
		 */
		List<String> quests;
	}

	private static class MonstersFile {
		List<Monster> monsters;
	}

	@Getter
	private List<Monster> monsters = Collections.emptyList();

	public static MonsterTable load(Gson gson) {
		MonsterTable table = new MonsterTable();
		try (InputStream in = MonsterTable.class.getResourceAsStream("/com/gachaman/data/monsters.json")) {
			MonstersFile file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MonstersFile.class);
			List<Monster> normalised = new ArrayList<>(file.monsters.size());
			for (Monster monster : file.monsters) {
				normalised.add(withNormalisedQuests(monster));
			}
			table.monsters = Collections.unmodifiableList(normalised);
		}
		catch (Exception e) {
			log.error("Failed to load monsters.json", e);
		}
		return table;
	}

	/**
	 * Rebuilds a monster with a non-null, immutable quest list. Gson writes the
	 * final fields directly, so a monster with no "quests" key arrives with a
	 * null there — every caller would otherwise need its own null check, and the
	 * one that forgot would be the one that decides whether a contract is
	 * fightable.
	 *
	 * Unrecognised quest names are KEPT, not dropped. A name Quest can't resolve
	 * is one no player can ever satisfy, so keeping it withholds the monster,
	 * while dropping it would hand out a contract on the strength of a typo. The
	 * dataset integrity test fails the build on such a name; this is what happens
	 * if one ever reaches a player anyway.
	 */
	private static Monster withNormalisedQuests(Monster monster) {
		List<String> quests = monster.getQuests();
		if (quests != null && quests.isEmpty())
			return monster;
		List<String> safe = quests == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(quests));
		return new Monster(monster.getName(), monster.getCombatLevel(), monster.getTags(),
			monster.isMembers(), monster.getSlayerLevel(), monster.isSlayerTaskOnly(),
			monster.isNoGuaranteedDrop(), monster.isMeleeUnreachable(), safe);
	}

}
