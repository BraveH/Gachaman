package com.gachaman.service;

import com.gachaman.data.QuestMonsterTable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Quest combat exemptions: an NPC required by an IN_PROGRESS quest may be
 * attacked (and fought with any style) regardless of the task/style locks.
 * Kills stay economy-neutral — this service only answers "is this NPC an
 * active quest target?".
 *
 * Sources: the bundled quest->monster table (authoritative, uses the game's
 * own quest state), plus a best-effort reflective peek at Quest Helper's
 * current step when that plugin is running (bonus coverage; degrades to
 * nothing silently if its internals change).
 */
@Slf4j
@Singleton
public class QuestExemptionService
{
	private final Client client;
	private final QuestMonsterTable questMonsterTable;
	private final PluginManager pluginManager;

	/** Per-tick memo: quest-state scripts are not free. */
	private final Map<String, Boolean> tickMemo = new HashMap<>();
	private int memoTick = -1;

	// Quest Helper reflection handles, resolved once, null on failure
	private Object questHelperPlugin;
	private Method getSelectedQuest;
	private Method getCurrentStep;
	private boolean questHelperResolved;

	@Inject
	public QuestExemptionService(Client client, QuestMonsterTable questMonsterTable,
		PluginManager pluginManager)
	{
		this.client = client;
		this.questMonsterTable = questMonsterTable;
		this.pluginManager = pluginManager;
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
		for (Quest quest : questMonsterTable.questsFor(lowerName))
		{
			try
			{
				if (quest.getState(client) == QuestState.IN_PROGRESS)
				{
					return true;
				}
			}
			catch (Exception e)
			{
				// quest state script hiccup: fail open for THIS check? No —
				// fail closed here; the block only relaxes on positive proof
			}
		}
		return questHelperTargets(lowerName);
	}

	// --- Quest Helper bonus source (best-effort, fully defensive) ---

	private boolean questHelperTargets(String lowerName)
	{
		try
		{
			resolveQuestHelper();
			if (questHelperPlugin == null)
			{
				return false;
			}
			Object selectedQuest = getSelectedQuest.invoke(questHelperPlugin);
			if (selectedQuest == null)
			{
				return false;
			}
			if (getCurrentStep == null)
			{
				getCurrentStep = selectedQuest.getClass().getMethod("getCurrentStep");
			}
			Object step = getCurrentStep.invoke(selectedQuest);
			if (step == null)
			{
				return false;
			}
			return stepMentionsNpc(step, lowerName, 0);
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/** Walk a QuestStep (and its active substep) looking for a matching NPC. */
	private boolean stepMentionsNpc(Object step, String lowerName, int depth) throws Exception
	{
		if (step == null || depth > 4)
		{
			return false;
		}
		// NpcStep exposes getNpcs() (List<NPC>) on current Quest Helper builds
		try
		{
			Method getNpcs = step.getClass().getMethod("getNpcs");
			Object npcs = getNpcs.invoke(step);
			if (npcs instanceof Collection)
			{
				for (Object npc : (Collection<?>) npcs)
				{
					try
					{
						Method getName = npc.getClass().getMethod("getName");
						Object name = getName.invoke(npc);
						if (name != null && lowerName.equalsIgnoreCase(
							net.runelite.client.util.Text.removeTags(name.toString())))
						{
							return true;
						}
					}
					catch (Exception ignored)
					{
						// not an NPC-shaped object; keep scanning
					}
				}
			}
		}
		catch (NoSuchMethodException ignored)
		{
			// not an NpcStep
		}
		// conditional/owner steps wrap an active sub-step
		try
		{
			Method getActiveStep = step.getClass().getMethod("getActiveStep");
			Object active = getActiveStep.invoke(step);
			if (active != null && active != step)
			{
				return stepMentionsNpc(active, lowerName, depth + 1);
			}
		}
		catch (NoSuchMethodException ignored)
		{
			// leaf step
		}
		return false;
	}

	private void resolveQuestHelper()
	{
		if (questHelperResolved)
		{
			return;
		}
		questHelperResolved = true;
		try
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (!plugin.getClass().getSimpleName().equals("QuestHelperPlugin")
					|| !pluginManager.isPluginEnabled(plugin))
				{
					continue;
				}
				Method selected = plugin.getClass().getMethod("getSelectedQuest");
				Object quest = selected.invoke(plugin);
				Method current = null;
				if (quest != null)
				{
					current = quest.getClass().getMethod("getCurrentStep");
				}
				else
				{
					// resolve lazily later; still record the plugin + method
					current = null;
				}
				questHelperPlugin = plugin;
				getSelectedQuest = selected;
				getCurrentStep = current;
				log.debug("Quest Helper detected — bonus quest-target source active");
				return;
			}
		}
		catch (Exception e)
		{
			questHelperPlugin = null;
			log.debug("Quest Helper not integrable", e);
		}
	}
}
