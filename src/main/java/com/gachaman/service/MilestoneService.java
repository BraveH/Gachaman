package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.*;

/** Awards one in-reveal reroll token per full +10 combat levels gained. */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MilestoneService {
	private final Client client;
	private final GachaStateService stateService;
	private final CeremonyBus ceremonyBus;

	@Subscribe
	public void onStatChanged(StatChanged event) {
		GachaState state = stateService.get();
		if (state == null) {
			return;
		}
		int cb = combatLevel();
		int last = state.getLastTokenCombatLevel();
		if (cb >= last + Tuning.TOKEN_CB_INTERVAL) {
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

	public int combatLevel() {
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
