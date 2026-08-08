package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.model.GachaState;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/** Awards one in-reveal reroll token per full +10 combat levels gained. */
@Slf4j
@Singleton
public class MilestoneService
{
	private final Client client;
	private final GachaStateService stateService;
	private final CeremonyBus ceremonyBus;

	@Inject
	public MilestoneService(Client client, GachaStateService stateService, CeremonyBus ceremonyBus)
	{
		this.client = client;
		this.stateService = stateService;
		this.ceremonyBus = ceremonyBus;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return;
		}
		int cb = combatLevel();
		int last = state.getLastTokenCombatLevel();
		if (cb >= last + Tuning.TOKEN_CB_INTERVAL)
		{
			int tokens = (cb - last) / Tuning.TOKEN_CB_INTERVAL;
			int newLast = last + tokens * Tuning.TOKEN_CB_INTERVAL;
			stateService.mutate(s -> s
				.withRerollTokens(s.getRerollTokens() + tokens)
				.withLastTokenCombatLevel(newLast));
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.MEDIUM, "Combat level " + cb + "!",
				"+" + tokens + " card reroll token" + (tokens > 1 ? "s" : ""), null));
		}
	}

	public int combatLevel()
	{
		return Experience.getCombatLevel(
			client.getRealSkillLevel(Skill.ATTACK),
			client.getRealSkillLevel(Skill.STRENGTH),
			client.getRealSkillLevel(Skill.DEFENCE),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.MAGIC),
			client.getRealSkillLevel(Skill.RANGED),
			client.getRealSkillLevel(Skill.PRAYER));
	}
}
