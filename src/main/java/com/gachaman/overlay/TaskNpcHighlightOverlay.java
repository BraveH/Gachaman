package com.gachaman.overlay;

import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import java.awt.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;

/** Outlines NPCs matching the active task (config-toggled). */
@Singleton
public class TaskNpcHighlightOverlay extends Overlay {
	private final Client client;
	private final GachaStateService stateService;
	private final GachamanConfig config;

	@Inject
	public TaskNpcHighlightOverlay(Client client, GachaStateService stateService, GachamanConfig config) {
		this.client = client;
		this.stateService = stateService;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.highlightTaskNpc()) {
			return null;
		}
		GachaState state = stateService.get();
		ActiveTask task = state == null ? null : state.getActiveTask();
		if (task == null) {
			return null;
		}
		Color color = task.getDifficulty().getColor();
		Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 30);
		graphics.setStroke(new BasicStroke(2f));
		for (NPC npc : client.getTopLevelWorldView().npcs()) {
			if (npc == null) {
				continue;
			}
			String name = npc.getName();
			if (name == null || !Text.removeTags(name).equalsIgnoreCase(task.getMonsterName())
				|| npc.isDead()) {
				continue;
			}
			Shape hull = npc.getConvexHull();
			if (hull != null) {
				graphics.setColor(fill);
				graphics.fill(hull);
				graphics.setColor(color);
				graphics.draw(hull);
			}
		}
		return null;
	}
}
