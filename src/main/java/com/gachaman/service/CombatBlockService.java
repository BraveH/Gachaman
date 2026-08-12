package com.gachaman.service;

import com.gachaman.model.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.chat.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.util.*;

/**
 * Combat gating: attacks are only permitted against the active task's
 * monster. With no task rolled, attacking is blocked entirely. Players are
 * never task targets, so PvP attacks are always blocked. Tutorial Island is
 * exempt. Same remove-plus-consume technique as the equipment block.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CombatBlockService {
	private final Client client;
	private final GachaStateService stateService;
	private final ChatMessageManager chatMessageManager;
	private final QuestExemptionService questExemptionService;

	private int lastWarnTick = -1;

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event) {
		MenuEntry entry = event.getMenuEntry();
		if (!isOffensiveEntry(entry.getOption(), entry.getType())) {
			return;
		}
		if (isBlocked(entry)) {
			client.getMenu().removeMenuEntry(entry);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (!isOffensiveEntry(event.getMenuOption(), event.getMenuAction())) {
			return;
		}
		if (isBlocked(event.getMenuEntry())) {
			event.consume();
			warn();
		}
	}

	private boolean isBlocked(MenuEntry entry) {
		GachaState state = stateService.get();
		if (state == null || TutorialGate.onTutorial(client)) {
			return false;
		}
		ActiveTask task = state.getActiveTask();
		NPC npc = entry.getNpc();
		if (npc == null) {
			// player (or unknown) target: never a task target
			return true;
		}
		String name = npc.getName();
		if (name != null && questExemptionService.isQuestTarget(Text.removeTags(name))) {
			return false; // an in-progress quest requires fighting this NPC
		}
		if (task == null) {
			return true; // no task rolled -> no combat at all
		}
		return name == null || !Text.removeTags(name).equalsIgnoreCase(task.getMonsterName());
	}

	private void warn() {
		int tick = client.getTickCount();
		if (tick == lastWarnTick) {
			return;
		}
		lastWarnTick = tick;
		GachaState state = stateService.get();
		String message = state != null && state.getActiveTask() != null
			? "You may only attack your contract target: "
				+ state.getActiveTask().getMonsterName() + "."
			: "You have no contract — roll one before entering combat.";
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> " + message)
			.build());
	}

	/**
	 * Attack options, plus manual spell casts on NPCs/players ("Cast <spell>
	 * -> <target>" arrives as a WIDGET_TARGET_ON_* action — casting High
	 * Alchemy on items is a different action type and stays untouched).
	 */
	static boolean isOffensiveEntry(String option, MenuAction action) {
		if ("Attack".equals(option)) {
			return true;
		}
		return option != null && option.startsWith("Cast")
			&& (action == MenuAction.WIDGET_TARGET_ON_NPC
			|| action == MenuAction.WIDGET_TARGET_ON_PLAYER);
	}
}
