package com.gachaman.service;

import java.util.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.chat.*;
import net.runelite.client.eventbus.*;

/**
 * The hard click-block for forbidden equipment. Menu entries are removed on
 * every MenuEntryAdded (the menu rebuilds each frame — no caching), and
 * MenuOptionClicked.consume() is the backstop that actually guarantees the
 * block if any path re-adds an entry.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class EquipBlockService {
	private static final Set<String> EQUIP_OPTIONS = Set.of("Wield", "Wear", "Equip", "Hold");

	private final Client client;
	private final PermissionService permissionService;
	private final ChatMessageManager chatMessageManager;

	private int lastWarnTick = -1;

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event) {
		MenuEntry entry = event.getMenuEntry();
		if (!isEquipOption(entry.getOption())) {
			return;
		}
		int itemId = entry.getItemId();
		if (itemId > 0 && permissionService.isForbidden(itemId)) {
			client.getMenu().removeMenuEntry(entry);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (!isEquipOption(event.getMenuOption())) {
			return;
		}
		int itemId = event.getItemId();
		if (itemId > 0 && permissionService.isForbidden(itemId)) {
			event.consume();
			int tick = client.getTickCount();
			if (tick != lastWarnTick) {
				lastWarnTick = tick;
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage(
						"<col=b25be2>Gachaman:</col> That equipment is card-locked. Pull its card and equip it in your loadout first.")
					.build());
			}
		}
	}

	static boolean isEquipOption(String option) {
		return option != null && EQUIP_OPTIONS.contains(option);
	}
}
