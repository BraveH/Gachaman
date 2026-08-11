package com.gachaman.service;

import com.gachaman.data.QuestMonsterTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

/**
 * Quest combat exemptions: an NPC required by an IN_PROGRESS quest may be
 * attacked (and fought with any style) regardless of the task/style locks.
 * Kills stay economy-neutral — this service only answers "is this NPC an
 * active quest target?".
 *
 * <p>One source, and it is the game's own: the bundled quest-&gt;monster table
 * cross-checked against {@link Quest#getState}. This used to have a second,
 * best-effort source that reflected into Quest Helper's selected step for bonus
 * coverage. Reflection is not permitted in Plugin Hub plugins, and there is no
 * supported way for one plugin to read another's state, so that source is gone
 * rather than reworked. The table is the contract now: an NPC that should be
 * exempt has to be IN it.
 */
@Singleton
public class QuestExemptionService {
	private final Client client;
	private final QuestMonsterTable questMonsterTable;

	/** Per-tick memo: quest-state scripts are not free. */
	private final Map<String, Boolean> tickMemo = new HashMap<>();
	private int memoTick = -1;

	/**
	 * Manually unlocked NPCs, lowercased. The escape hatch for the case this
	 * whole table cannot avoid: a quest genuinely needs a monster the curated
	 * data does not list, and the player is locked out of their own quest with
	 * no way forward until the table is fixed and shipped.
	 *
	 * <p>Deliberately NOT persisted. It survives a hop, not a client restart —
	 * a bypass that outlives the bug it worked around is a bypass nobody
	 * remembers turning on. Client thread only, like everything else here.
	 */
	private final Set<String> manualUnlocks = new LinkedHashSet<>();

	/** One reason the player may currently attack something off-task. */
	@Getter
	@RequiredArgsConstructor
	public static final class Unlock {
		private final String npcName;
		/** The quest that opened it, or null when it is a manual override. */
		private final String questName;

		public boolean isManual() {
			return questName == null;
		}
	}

	@Inject
	public QuestExemptionService(Client client, QuestMonsterTable questMonsterTable) {
		this.client = client;
		this.questMonsterTable = questMonsterTable;
	}

	/** Client thread only. True when the NPC is a target of an in-progress quest. */
	public boolean isQuestTarget(String npcName) {
		if (npcName == null) {
			return false;
		}
		int tick = client.getTickCount();
		if (tick != memoTick) {
			memoTick = tick;
			tickMemo.clear();
		}
		return tickMemo.computeIfAbsent(npcName.toLowerCase(), this::compute);
	}

	// --- manual overrides ---

	/** True when this newly unlocked it; false when it was already unlocked. */
	public boolean unlock(String npcName) {
		tickMemo.clear();
		return manualUnlocks.add(npcName.toLowerCase(Locale.ROOT));
	}

	/** Drops one override, or every one of them when {@code npcName} is null. */
	public int relock(String npcName) {
		tickMemo.clear();
		if (npcName == null) {
			int had = manualUnlocks.size();
			manualUnlocks.clear();
			return had;
		}
		return manualUnlocks.remove(npcName.toLowerCase(Locale.ROOT)) ? 1 : 0;
	}

	/**
	 * Client thread only. Every NPC the player may currently attack regardless
	 * of their contract, and why. Walks the whole table rather than answering
	 * one name, so quest state is resolved once per quest and not once per NPC.
	 */
	public List<Unlock> currentUnlocks() {
		List<Unlock> out = new ArrayList<>();
		for (String name : manualUnlocks) {
			out.add(new Unlock(name, null));
		}
		Map<Quest, Boolean> inProgress = new HashMap<>();
		for (QuestMonsterTable.Gate gate : questMonsterTable.allGates()) {
			try {
				if (!inProgress.computeIfAbsent(gate.getQuest(),
					q -> q.getState(client) == QuestState.IN_PROGRESS)) {
					continue;
				}
				if (gate.hasWindow() && !gate.contains(readVar(gate))) {
					continue;
				}
				out.add(new Unlock(gate.getNpcName(), gate.getQuest().getName()));
			}
			catch (Exception e) {
				// one quest's state script misbehaving must not empty the list
			}
		}
		return out;
	}

	private int readVar(QuestMonsterTable.Gate gate) {
		// the same variable the quest itself tracks its progress in, read through
		// the ordinary client API - this is how Quest Helper knows which step you
		// are on, and it needs no privileged access
		return gate.isVarbit()
			? client.getVarbitValue(gate.getVarId())
			: client.getVarpValue(gate.getVarId());
	}

	private boolean compute(String lowerName) {
		if (manualUnlocks.contains(lowerName)) {
			return true;
		}
		for (QuestMonsterTable.Gate gate : questMonsterTable.gatesFor(lowerName)) {
			try {
				if (gate.getQuest().getState(client) != QuestState.IN_PROGRESS) {
					continue;
				}
				if (!gate.hasWindow()) {
					return true;
				}
				if (gate.contains(readVar(gate))) {
					return true;
				}
			}
			catch (Exception e) {
				// a quest-state script hiccup fails CLOSED: the combat block
				// only ever relaxes on positive proof that the quest is running
			}
		}
		return false;
	}
}
