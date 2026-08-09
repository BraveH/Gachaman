package com.gachaman.overlay;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.gachaman.service.GachaStateService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Movable/dockable task progress panel: standard RuneLite OverlayPanel the
 * player can drag into any snap corner (alt-drag / overlay manage mode works
 * out of the box). Shows the active task monster, difficulty-colored progress
 * bar and a compact side-bet counter; when only rolled offers are pending it
 * collapses to a one-line reminder. Hidden entirely when there is nothing to
 * show. Steady-state frames build no new strings — labels are cached and only
 * rebuilt when the underlying counts change.
 */
@Singleton
public class TaskProgressOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(255, 205, 70);
	private static final Color MUTED = new Color(170, 170, 170);
	private static final Color BAR_BG = new Color(24, 24, 24, 210);
	private static final Color DONE_GREEN = new Color(120, 220, 120);
	private static final String TITLE = "Gachaman contract";
	private static final String OFFERS_WAITING = "Contracts rolled — view and pick one";
	private static final String NO_TASK = "No contract — roll one in the Gachaman panel";
	private static final String SIDE_BETS_LABEL = "Side bets";
	private static final String DOCKET_LABEL = "Double Docket";
	/** Constant expression, so the steady-state frame still allocates nothing. */
	private static final String DOCKET_VALUE = "x" + Tuning.DOUBLE_DOCKET_MULT;

	private final GachaStateService stateService;
	private final net.runelite.api.Client client;
	private final com.gachaman.service.TaskService taskService;
	private final com.gachaman.service.KillTracker killTracker;
	private final ProgressBarComponent progressBar = new ProgressBarComponent();
	private final ComboMeterComponent comboMeter = new ComboMeterComponent();

	// caches so unchanged frames allocate no new strings/colors
	private String monsterLine;
	private String monsterLineName;
	private TaskDifficulty monsterLineDifficulty;
	private String progressLabel;
	private int progressKills = -1;
	private int progressRequired = -1;
	private boolean progressHalf;
	private String[] sideBetLines;
	private String[] sideBetStatus;
	private boolean[] sideBetDoneFlags;
	private String sideBetTaskName;
	private int sideBetDone = -1;
	private int sideBetTotal = -1;
	/**
	 * Which CONTRACT the cached lines were built for.
	 *
	 * <p>The other three fields do not identify one: finish a Goblin contract with both
	 * side bets unclaimed and take a second Goblin contract that also carries two, and
	 * (done, total, monsterName) is identical across the boundary — so the HUD went on
	 * listing the first contract's objectives and payouts under the second one, and kept
	 * doing so until a real bet completed. The null gap between contracts does not clear
	 * it either; the {@code task == null} early return leaves every field standing.
	 */
	private long sideBetAcceptedAt = -1;
	private Color barColor;
	private TaskDifficulty barColorDifficulty;

	@Inject
	public TaskProgressOverlay(GachaStateService stateService, net.runelite.api.Client client,
		com.gachaman.service.TaskService taskService, com.gachaman.service.KillTracker killTracker)
	{
		this.stateService = stateService;
		this.client = client;
		this.taskService = taskService;
		this.killTracker = killTracker;
		setPosition(OverlayPosition.TOP_LEFT);
		progressBar.setMinimum(0);
		progressBar.setMaximum(100);
		progressBar.setBackgroundColor(BAR_BG);
		progressBar.setFontColor(Color.WHITE);
		progressBar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		GachaState state = stateService.get();
		if (state == null || com.gachaman.service.TutorialGate.onTutorial(client))
		{
			return null;
		}
		ActiveTask task = state.getActiveTask();
		List<TaskOffer> offers = state.getPendingOffers();

		panelComponent.getChildren().clear();

		if (task == null)
		{
			// combat is locked without a task — always tell the player what to do
			boolean offersWaiting = offers != null && !offers.isEmpty();
			panelComponent.getChildren().add(LineComponent.builder()
				.left(offersWaiting ? OFFERS_WAITING : NO_TASK)
				.leftColor(offersWaiting ? GOLD : MUTED)
				.build());
			return super.render(graphics);
		}

		TaskDifficulty difficulty = task.getDifficulty();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(TITLE)
			.color(GOLD)
			.build());

		if (monsterLine == null || difficulty != monsterLineDifficulty
			|| !task.getMonsterName().equals(monsterLineName))
		{
			monsterLineName = task.getMonsterName();
			monsterLineDifficulty = difficulty;
			monsterLine = monsterLineName + " (" + difficulty.getDisplayName() + ")";
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left(monsterLine)
			.leftColor(difficulty.getColor())
			.build());

		// shared (party) contracts pool everyone's kills into the quota
		int pooledKills = task.getKillsDone() + (task.isParty() ? task.getPartyOtherKills() : 0);
		if (pooledKills != progressKills || task.getKillsRequired() != progressRequired
			|| task.isHalfKillPending() != progressHalf)
		{
			progressKills = pooledKills;
			progressRequired = task.getKillsRequired();
			progressHalf = task.isHalfKillPending();
			progressLabel = progressKills + (progressHalf ? ".5" : "") + "/" + progressRequired;
		}
		if (difficulty != barColorDifficulty)
		{
			barColorDifficulty = difficulty;
			Color c = difficulty.getColor();
			barColor = new Color(c.getRed(), c.getGreen(), c.getBlue(), 200);
		}
		double pct = task.getKillsRequired() <= 0 ? 0
			: Math.min(100.0, (pooledKills + (task.isHalfKillPending() ? 0.5 : 0))
				* 100.0 / task.getKillsRequired());
		progressBar.setValue(pct);
		progressBar.setCenterLabel(progressLabel);
		progressBar.setForegroundColor(barColor);
		panelComponent.getChildren().add(progressBar);

		// rhythm combo meter (transient — lives in TaskService, KillTracker ticks)
		int nowTick = killTracker.currentTick();
		int stacks = taskService.comboStacksAt(nowTick);
		if (stacks >= 1)
		{
			comboMeter.set(stacks, taskService.comboWindowFraction(nowTick),
				taskService.comboIdleTicksRemaining(nowTick));
			panelComponent.getChildren().add(comboMeter);
		}

		// Latched-only here: the HUD is the terse view, so it states the bonus a
		// player HAS. The sidebar carries the always-visible "not your Slayer
		// task" case and the rule behind it.
		if (task.isSlayerAligned())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(DOCKET_LABEL)
				.leftColor(DONE_GREEN)
				.right(DOCKET_VALUE)
				.rightColor(GOLD)
				.build());
		}

		List<SideBet> sideBets = task.getSideBets();
		int total = sideBets == null ? 0 : sideBets.size();
		if (total > 0)
		{
			int done = 0;
			for (int i = 0; i < total; i++)
			{
				if (sideBets.get(i).isCompleted())
				{
					done++;
				}
			}
			// rebuild the per-bet lines only when the task or a completion changes
			if (sideBetLines == null || done != sideBetDone || total != sideBetTotal
				|| !task.getMonsterName().equals(sideBetTaskName)
				|| task.getAcceptedAtMs() != sideBetAcceptedAt)
			{
				sideBetDone = done;
				sideBetTotal = total;
				sideBetTaskName = task.getMonsterName();
				sideBetAcceptedAt = task.getAcceptedAtMs();
				sideBetLines = new String[total];
				sideBetStatus = new String[total];
				sideBetDoneFlags = new boolean[total];
				for (int i = 0; i < total; i++)
				{
					SideBet bet = sideBets.get(i);
					sideBetLines[i] = com.gachaman.service.TaskService.describeSideBet(bet);
					sideBetDoneFlags[i] = bet.isCompleted();
					// "*" not a check mark: the RuneScape faces have no U+2713. It only
					// looked right in testing because ComboMeterComponent leaves logical
					// SANS_SERIF on the shared Graphics2D while a combo is live — at rest
					// this line paints a tofu box.
					sideBetStatus[i] = bet.isCompleted()
						? "* +" + bet.getPayoutGc()
						: "+" + bet.getPayoutGc();
				}
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left(SIDE_BETS_LABEL)
				.leftColor(MUTED)
				.build());
			for (int i = 0; i < total; i++)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(sideBetLines[i])
					.leftColor(sideBetDoneFlags[i] ? DONE_GREEN : Color.WHITE)
					.right(sideBetStatus[i])
					.rightColor(sideBetDoneFlags[i] ? DONE_GREEN : GOLD)
					.build());
			}
		}

		return super.render(graphics);
	}

	/**
	 * Compact kill-tempo meter: bold xN counter, ten stack pips, and the idle
	 * countdown — a seconds label plus an arc draining over the full idle
	 * window, showing how long until the chain cancels. While the (shorter)
	 * growth window is still open the pips glow full; once it closes the pips
	 * dim to telegraph that the next kill maintains but does not build.
	 */
	private static final class ComboMeterComponent
		implements net.runelite.client.ui.overlay.components.LayoutableRenderableEntity
	{
		private static final int HEIGHT = 16;

		private final java.awt.Rectangle bounds = new java.awt.Rectangle();
		private java.awt.Point preferredLocation = new java.awt.Point();
		private Dimension preferredSize = new Dimension(150, HEIGHT);

		private int stacks;
		private double windowFraction;
		private int idleTicksRemaining;
		private String label = "";
		private int labelStacks = -1;
		private String secondsLabel = "";
		private int labelSeconds = -1;

		void set(int stacks, double windowFraction, int idleTicksRemaining)
		{
			this.stacks = stacks;
			this.windowFraction = windowFraction;
			this.idleTicksRemaining = idleTicksRemaining;
			if (stacks != labelStacks)
			{
				labelStacks = stacks;
				label = "x" + stacks;
			}
			// 1 game tick = 0.6s; round up so the label never reads 0s early
			int seconds = (int) Math.ceil(idleTicksRemaining * 0.6);
			if (seconds != labelSeconds)
			{
				labelSeconds = seconds;
				secondsLabel = seconds + "s";
			}
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
				java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			int x = preferredLocation.x;
			int y = preferredLocation.y;
			boolean held = windowFraction <= 0;

			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
			g.setColor(GOLD);
			g.drawString(label, x, y + 12);
			int textW = g.getFontMetrics().stringWidth(label);

			// stack pips
			int px = x + textW + 8;
			int py = y + 5;
			Color filled = held ? new Color(255, 205, 70, 140) : GOLD;
			Color empty = new Color(255, 205, 70, 60);
			for (int i = 0; i < 10; i++)
			{
				g.setColor(i < stacks ? filled : empty);
				g.fillOval(px + i * 7, py, 5, 5);
			}

			// idle countdown: arc + seconds until the chain cancels; the last
			// ~15s turn red as the combo is about to drop
			double idleFraction = Math.max(0, Math.min(1.0,
				idleTicksRemaining / (double) com.gachaman.Tuning.COMBO_IDLE_RESET_TICKS));
			boolean urgent = idleFraction <= 0.25;
			int ax = px + 10 * 7 + 6;
			g.setColor(urgent ? new Color(230, 90, 70, 160) : new Color(255, 205, 70, 90));
			g.setStroke(new java.awt.BasicStroke(2f));
			g.drawArc(ax, y + 2, 12, 12, 90, -(int) Math.round(360 * idleFraction));
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
			g.setColor(urgent ? new Color(230, 90, 70) : MUTED);
			int sx = ax + 12 + 5;
			g.drawString(secondsLabel, sx, y + 12);
			int secondsW = g.getFontMetrics().stringWidth(secondsLabel);

			int width = sx + secondsW + 2 - x;
			bounds.setBounds(x, y, width, HEIGHT);
			return new Dimension(width, HEIGHT);
		}

		@Override
		public java.awt.Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(java.awt.Point location)
		{
			this.preferredLocation = location;
		}

		@Override
		public void setPreferredSize(Dimension size)
		{
			if (size != null)
			{
				this.preferredSize = size;
			}
		}
	}
}
