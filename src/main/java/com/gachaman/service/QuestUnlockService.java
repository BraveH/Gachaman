package com.gachaman.service;

import com.gachaman.data.*;
import java.util.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Which quest-locked monsters this account may actually be sent after.
 *
 * A quest lock is a HARD gate, like a Slayer level and unlike a combat level: a
 * contract on a monster behind an unfinished quest cannot be started at all, and
 * a Gachaman contract cannot be abandoned. So the pool is filtered before the
 * roll rather than the player being left holding it.
 *
 * Only quests that actually lock a monster in the table are ever read. That set
 * is a few dozen entries out of ~195 quests, and it decides both how many quest
 * scripts run here and how much a party roll puts on the wire.
 */
@Slf4j
@Singleton
public class QuestUnlockService {
	/**
	 * Quest state is a client script, not a field read. Recomputing the whole
	 * gating set on every panel repaint would be silly, and the only thing a
	 * stale answer can cost is a monster staying locked for another half minute
	 * after its quest is finished — it can never unlock one early, because the
	 * set only ever grows.
	 */
	private static final int CACHE_TTL_TICKS = 50;

	private final Client client;

	/** Quest names referenced by monsters.json, in a stable order. Resolved once. */
	private final List<Quest> gatingQuests;
	/** Names in gatingQuests, for the fast "is this even worth asking about" test. */
	private final Set<String> gatingNames;

	private Set<String> cached = Collections.emptySet();
	private int cachedAtTick = Integer.MIN_VALUE;

	@Inject
	public QuestUnlockService(Client client, MonsterTable monsterTable) {
		this.client = client;
		Set<String> names = new TreeSet<>();
		for (MonsterTable.Monster monster : monsterTable.getMonsters()) {
			if (monster.getQuests() != null) {
				names.addAll(monster.getQuests());
			}
		}
		List<Quest> resolved = new ArrayList<>(names.size());
		Set<String> resolvedNames = new LinkedHashSet<>();
		for (String name : names) {
			try {
				resolved.add(Quest.valueOf(name));
				resolvedNames.add(name);
			}
			catch (IllegalArgumentException e) {
				// Deliberately NOT added: a name Quest cannot resolve can never
				// enter the completed set, so every monster requiring it stays
				// locked forever. That is the safe direction, and the dataset
				// integrity test fails the build before it can ship.
				log.warn("monsters.json gates a monster on unknown quest {} — those monsters"
					+ " will never be offered", name);
			}
		}
		this.gatingQuests = Collections.unmodifiableList(resolved);
		this.gatingNames = Collections.unmodifiableSet(resolvedNames);
		log.debug("Quest gating active on {} quest(s)", resolved.size());
	}

	/**
	 * Client thread only. The gating quests this account has FINISHED.
	 *
	 * Never null: an empty set means "has finished none of them", which gates
	 * every quest-locked monster out. Null is reserved for "gating disabled" and
	 * this method does not produce it.
	 */
	public Set<String> completedQuests() {
		if (gatingQuests.isEmpty()) {
			return Collections.emptySet();
		}
		int tick = client.getTickCount();
		if (cachedAtTick != Integer.MIN_VALUE && tick - cachedAtTick < CACHE_TTL_TICKS
			&& tick >= cachedAtTick) {
			return cached;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			// Quest varbits are not populated yet, so every quest would read as
			// NOT_STARTED. Answer "none finished" — which locks quest monsters
			// rather than unlocking them — and do NOT cache it, so the first
			// call after login reads the real thing.
			return Collections.emptySet();
		}
		Set<String> finished = new HashSet<>();
		for (Quest quest : gatingQuests) {
			try {
				if (quest.getState(client) == QuestState.FINISHED) {
					finished.add(quest.name());
				}
			}
			catch (Exception e) {
				// A quest-state script hiccup is not evidence of completion.
				// Leaving it out withholds a contract; putting it in would hand
				// out one the player cannot start.
				log.debug("Quest state unavailable for {}", quest, e);
			}
		}
		cached = Collections.unmodifiableSet(finished);
		cachedAtTick = tick;
		return cached;
	}

	/**
	 * The same set as a sorted list, for the party wire.
	 *
	 * Sorted so two clients that transmit identical progress transmit identical
	 * JSON — the roll intersects these as sets and does not care about order,
	 * but a stable encoding makes a desync report readable instead of a diff of
	 * two shuffled arrays.
	 */
	public List<String> completedQuestsForWire() {
		return Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(completedQuests())));
	}

	/** Quest names that lock at least one monster in the table. */
	public Set<String> gatingQuestNames() {
		return gatingNames;
	}
}
