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
		// One left: the contract's biggest moment used to be a surprise, because
		// the golden burst fires AFTER the final kill lands. Marking the targets
		// that can finish it turns the last stretch into a hunt instead — you see
		// the gold, you pick one, you go. Nothing mechanical changes: these are
		// the same NPCs, already outlined, in the same places.
		int remaining = task.getKillsRequired()
			- (task.getKillsDone() + (task.isParty() ? task.getPartyOtherKills() : 0));
		boolean lastOne = remaining == 1;
		Color color = lastOne ? finalTargetGold() : task.getDifficulty().getColor();
		Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), lastOne ? 60 : 30);
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

	/**
	 * Gold, pulsing about three times a second so it reads as urgency rather
	 * than decoration. Derived from wall-clock rather than a tick so it keeps
	 * breathing while the player stands still deciding which one to take.
	 */
	static Color finalTargetGold() {
		double phase = (Math.sin(System.currentTimeMillis() / 160.0) + 1) / 2;
		return new Color(255, 205, 70, (int) (170 + 85 * phase));
	}
}
