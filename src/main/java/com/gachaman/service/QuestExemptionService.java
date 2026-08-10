package com.gachaman.service;

import com.gachaman.data.QuestMonsterTable;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
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
public class QuestExemptionService
{
	private final Client client;
	private final QuestMonsterTable questMonsterTable;

	/** Per-tick memo: quest-state scripts are not free. */
	private final Map<String, Boolean> tickMemo = new HashMap<>();
	private int memoTick = -1;

	@Inject
	public QuestExemptionService(Client client, QuestMonsterTable questMonsterTable)
	{
		this.client = client;
		this.questMonsterTable = questMonsterTable;
	}

	/** Client thread only. True when the NPC is a target of an in-progress quest. */
	public boolean isQuestTarget(String npcName)
	{
		if (npcName == null)
		{
			return false;
		}
		int tick = client.getTickCount();
		if (tick != memoTick)
		{
			memoTick = tick;
			tickMemo.clear();
		}
		return tickMemo.computeIfAbsent(npcName.toLowerCase(), this::compute);
	}

	private boolean compute(String lowerName)
	{
		for (QuestMonsterTable.Gate gate : questMonsterTable.gatesFor(lowerName))
		{
			try
			{
				if (gate.getQuest().getState(client) != QuestState.IN_PROGRESS)
				{
					continue;
				}
				if (!gate.hasWindow())
				{
					return true;
				}
				// the same variable the quest itself tracks its progress in, read
				// through the ordinary client API - this is how Quest Helper knows
				// which step you are on, and it needs no privileged access
				int value = gate.isVarbit()
					? client.getVarbitValue(gate.getVarId())
					: client.getVarpValue(gate.getVarId());
				if (gate.contains(value))
				{
					return true;
				}
			}
			catch (Exception e)
			{
				// a quest-state script hiccup fails CLOSED: the combat block
				// only ever relaxes on positive proof that the quest is running
			}
		}
		return false;
	}
}
