package com.gachaman.overlay;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.service.ChestService;
import com.gachaman.service.ComplianceService;
import com.gachaman.service.GachaStateService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * The at-a-glance Gachaman infobox: GC balance, reroll tokens, allowed style,
 * cycle progress, active task, taint warning, a thin pity progress bar under
 * the panel lines and a "STYLE CHANGED" chip for 60s after a re-roll.
 * Right-click offers "Open" entries for every affordable chest tier and every
 * queued themed chest; the plugin routes {@code OverlayMenuClicked} using the
 * static option/target constants below.
 */
@Singleton
public class GachaInfoboxOverlay extends OverlayPanel
{
	/** Right-click option shared by every chest entry. */
	public static final String OPTION_OPEN = "Open";
	public static final String TARGET_RUSTY_CHEST = "Rusty chest";
	public static final String TARGET_BATTERED_CHEST = "Battered chest";
	public static final String TARGET_GILDED_CHEST = "Gilded chest";
	public static final String TARGET_ORNATE_CHEST = "Ornate chest";
	/** Themed chest targets are {@code THEMED_TARGET_PREFIX + setTag}. */
	public static final String THEMED_TARGET_PREFIX = "Themed chest: ";

	/** A game tick is 600ms, so 5 ticks per 3 seconds. */
	private static final int TICKS_PER_3_SECONDS = 5;

	private static final Color GOLD = new Color(255, 205, 70);
	private static final Color WARN_RED = new Color(255, 80, 80);
	private static final Color WARN_ORANGE = new Color(255, 160, 60);
	private static final Color MUTED = new Color(170, 170, 170);
	private static final Color PITY_BAR_BG = new Color(60, 60, 60, 200);
	private static final Color PITY_BAR_FILL = new Color(178, 91, 226);

	private final Client client;
	private final GachaStateService stateService;
	private final ChestService chestService;
	private final ComplianceService complianceService;
	private final com.gachaman.GachamanConfig config;

	@Inject
	public GachaInfoboxOverlay(Client client, GachaStateService stateService,
		ChestService chestService, ComplianceService complianceService,
		com.gachaman.GachamanConfig config)
	{
		this.client = client;
		this.stateService = stateService;
		this.chestService = chestService;
		this.complianceService = complianceService;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	public static String chestTarget(Tuning.Chest tier)
	{
		switch (tier)
		{
			case RUSTY:
				return TARGET_RUSTY_CHEST;
			case BATTERED:
				return TARGET_BATTERED_CHEST;
			case GILDED:
				return TARGET_GILDED_CHEST;
			default:
				return TARGET_ORNATE_CHEST;
		}
	}

	public static String themedChestTarget(String setTag)
	{
		return THEMED_TARGET_PREFIX + setTag;
	}

	/** Inverse of {@link #chestTarget}; null when the target is not a chest tier. */
	@Nullable
	public static Tuning.Chest chestTierFromTarget(String target)
	{
		if (TARGET_RUSTY_CHEST.equals(target))
		{
			return Tuning.Chest.RUSTY;
		}
		if (TARGET_BATTERED_CHEST.equals(target))
		{
			return Tuning.Chest.BATTERED;
		}
		if (TARGET_GILDED_CHEST.equals(target))
		{
			return Tuning.Chest.GILDED;
		}
		if (TARGET_ORNATE_CHEST.equals(target))
		{
			return Tuning.Chest.ORNATE;
		}
		return null;
	}

	/** Inverse of {@link #themedChestTarget}; null when not a themed chest entry. */
	@Nullable
	public static String themedSetTagFromTarget(String target)
	{
		if (target != null && target.startsWith(THEMED_TARGET_PREFIX))
		{
			return target.substring(THEMED_TARGET_PREFIX.length());
		}
		return null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			getMenuEntries().clear();
			return null;
		}

		refreshMenuEntries(state);
		panelComponent.getChildren().clear();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Gachaman")
			.color(GOLD)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("GC")
			.right(QuantityFormatter.formatNumber(state.getGc()))
			.rightColor(GOLD)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Tokens")
			.right(Integer.toString(state.getRerollTokens()))
			.rightColor(state.getRerollTokens() > 0 ? Color.WHITE : MUTED)
			.build());

		AttackStyle style = null;
		if (state.getAllowedStyle() != null)
		{
			try
			{
				style = AttackStyle.valueOf(state.getAllowedStyle());
			}
			catch (IllegalArgumentException ignored)
			{
				style = null;
			}
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Style")
			.right(style == null ? "Unrolled" : style.getDisplayName())
			.rightColor(style == null ? MUTED : style.getColor())
			.build());

		if (state.getCycleTarget() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Cycle")
				.right(String.format("%.1f/%d", state.getCycleProgress(), state.getCycleTarget()))
				.rightColor(Color.WHITE)
				.build());
		}

		ActiveTask task = state.getActiveTask();
		if (task != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(task.getMonsterName())
				.leftColor(task.getDifficulty().getColor())
				.right(task.getKillsDone() + "/" + task.getKillsRequired())
				.rightColor(Color.WHITE)
				.build());
		}

		if (state.getTaint() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("TAINT x" + state.getTaint())
				.leftColor(WARN_RED)
				.right("(income halved)")
				.rightColor(WARN_RED)
				.build());
		}

		if (isStyleChangedWarningActive(state))
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("STYLE CHANGED")
				.leftColor(WARN_ORANGE)
				.right("switch gear!")
				.rightColor(WARN_ORANGE)
				.build());
		}

		// spacer line the pity bar draws over
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Pity")
			.leftColor(MUTED)
			.right(state.getOpensSinceEpic() + "/" + pityHardCap(state))
			.rightColor(MUTED)
			.build());

		Dimension size = super.render(graphics);
		if (size != null)
		{
			drawPityBar(graphics, state, size);
		}
		return size;
	}

	private static int pityHardCap(GachaState state)
	{
		return state.getPrestigeRank() >= 2 ? Tuning.PITY_HARD_CAP_PRESTIGE2 : Tuning.PITY_HARD_CAP;
	}

	/** Thin pity progress meter drawn under the panel lines. */
	private void drawPityBar(Graphics2D g, GachaState state, Dimension size)
	{
		int cap = pityHardCap(state);
		float frac = cap <= 0 ? 0 : Math.min(1f, state.getOpensSinceEpic() / (float) cap);
		int x = 6;
		int w = Math.max(10, size.width - 12);
		int y = size.height - 5;
		g.setColor(PITY_BAR_BG);
		g.fillRoundRect(x, y, w, 3, 3, 3);
		int fillW = (int) (w * frac);
		if (fillW > 0)
		{
			g.setColor(frac >= 0.85f ? GOLD : PITY_BAR_FILL);
			g.fillRoundRect(x, y, fillW, 3, 3, 3);
		}
	}

	private boolean isStyleChangedWarningActive(GachaState state)
	{
		int seconds = config.styleWarningSeconds();
		int changedTick = complianceService.getStyleChangedTick();
		if (changedTick >= 0)
		{
			int tick = client.getTickCount();
			if (tick >= changedTick && tick - changedTick <= seconds * TICKS_PER_3_SECONDS / 3)
			{
				return true;
			}
		}
		// wall-clock fallback (tick spaces can differ between trackers)
		long rolledAt = state.getStyleRolledAtMs();
		return rolledAt > 0 && System.currentTimeMillis() - rolledAt <= seconds * 1000L;
	}

	private void refreshMenuEntries(GachaState state)
	{
		getMenuEntries().clear();
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			if (tier == Tuning.Chest.RUSTY && !chestService.rustyAvailable())
			{
				continue; // rusted away — retired from the menu forever
			}
			if (chestService.canAfford(tier))
			{
				addMenuEntry(MenuAction.RUNELITE_OVERLAY, OPTION_OPEN, chestTarget(tier));
			}
		}
		List<String> themed = state.getQueuedThemedChests();
		if (themed != null)
		{
			for (String setTag : themed)
			{
				addMenuEntry(MenuAction.RUNELITE_OVERLAY, OPTION_OPEN, themedChestTarget(setTag));
			}
		}
	}
}
