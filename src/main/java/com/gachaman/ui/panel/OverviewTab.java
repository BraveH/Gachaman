package com.gachaman.ui.panel;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.SideBet;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.TaskService;
import java.awt.Color;
import java.awt.Component;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Overview: GC balances, style lock + cycle, the active task (with side bets
 * and abandon), taint warning, pity meter, and the Roll Tasks button.
 */
@Singleton
public class OverviewTab extends JPanel
{
	private static final Color TAINT_RED = new Color(190, 60, 55);
	private static final Color SIDEBET_DONE = new Color(110, 200, 110);

	private final GachaStateService stateService;
	private final TaskService taskService;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final com.gachaman.party.PartyRollService partyRollService;

	private BooleanSupplier inPartySupplier = () -> false;

	@Inject
	public OverviewTab(GachaStateService stateService, TaskService taskService,
		net.runelite.client.callback.ClientThread clientThread,
		com.gachaman.party.PartyRollService partyRollService)
	{
		this.stateService = stateService;
		this.taskService = taskService;
		this.clientThread = clientThread;
		this.partyRollService = partyRollService;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	void setInPartySupplier(BooleanSupplier supplier)
	{
		if (supplier != null)
		{
			this.inPartySupplier = supplier;
		}
	}

	void rebuild()
	{
		removeAll();
		GachaState state = stateService.get();
		if (state == null)
		{
			add(GachamanPanel.centeredNote("Log in to begin your Gachaman journey."));
			revalidate();
			repaint();
			return;
		}

		addSection(buildBalanceSection(state));
		addSection(buildStyleSection(state));
		addSection(buildTaskSection(state));
		if (!state.isFragmentDeedForged()
			&& state.getTotalTasksCompleted() < Tuning.FRAGMENT_WINDOW_TASKS)
		{
			addSection(buildFragmentSection(state));
		}
		if (state.getTaint() > 0)
		{
			addSection(buildTaintSection(state));
		}
		addSection(buildPitySection(state));

		revalidate();
		repaint();
	}

	private void addSection(JPanel section)
	{
		add(section);
		add(Box.createVerticalStrut(6));
	}

	private JPanel buildBalanceSection(GachaState state)
	{
		JPanel section = GachamanPanel.section(null);
		JLabel gc = new JLabel(QuantityFormatter.formatNumber(state.getGc()) + " GC");
		gc.setFont(FontManager.getRunescapeBoldFont().deriveFont(26f));
		gc.setForeground(ColorScheme.BRAND_ORANGE);
		gc.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(gc);
		section.add(Box.createVerticalStrut(4));
		section.add(GachamanPanel.smallLine(
			"Lifetime earned: " + QuantityFormatter.formatNumber(state.getLifetimeGcEarned()) + " GC",
			ColorScheme.LIGHT_GRAY_COLOR));
		section.add(GachamanPanel.smallLine(
			"Reroll tokens: " + state.getRerollTokens(), ColorScheme.LIGHT_GRAY_COLOR));
		if (state.getPrestigeRank() > 0)
		{
			section.add(GachamanPanel.smallLine(
				"Prestige rank " + state.getPrestigeRank() + " (+"
					+ (int) (state.getPrestigeRank() * Tuning.PRESTIGE_GC_BONUS_PER_RANK * 100) + "% GC)",
				new Color(230, 190, 80)));
		}
		return section;
	}

	private JPanel buildStyleSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Allowed Style");
		if (state.getAllowedStyle() == null)
		{
			section.add(GachamanPanel.smallLine("Not yet rolled — fate awaits.", ColorScheme.LIGHT_GRAY_COLOR));
			return section;
		}
		AttackStyle style;
		try
		{
			style = AttackStyle.valueOf(state.getAllowedStyle());
		}
		catch (IllegalArgumentException e)
		{
			section.add(GachamanPanel.smallLine("Unknown style", ColorScheme.LIGHT_GRAY_COLOR));
			return section;
		}
		JLabel styleLabel = GachamanPanel.line(style.getDisplayName(), style.getColor(),
			FontManager.getRunescapeBoldFont().deriveFont(16f));
		section.add(styleLabel);
		section.add(Box.createVerticalStrut(5));
		int target = Math.max(1, state.getCycleTarget());
		double progress = state.getCycleProgress();
		String barLabel = trimDouble(progress) + " / " + target + " tasks";
		section.add(new GachamanPanel.MeterBar(progress / target, style.getColor(), barLabel));
		section.add(Box.createVerticalStrut(3));
		section.add(GachamanPanel.smallLine("Style re-rolls when the cycle completes.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	private JPanel buildTaskSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Task");
		ActiveTask task = state.getActiveTask();
		if (task == null)
		{
			boolean offersWaiting = state.getPendingOffers() != null && !state.getPendingOffers().isEmpty();
			if (offersWaiting)
			{
				boolean partyVote = state.getPendingOffers().get(0).isPartyRoll();
				section.add(GachamanPanel.wrapped(partyVote
						? "Party contracts rolled — click one to VOTE (unanimity accepts)."
						: "Contracts rolled — view them and pick one.",
					ColorScheme.LIGHT_GRAY_COLOR));
				section.add(Box.createVerticalStrut(4));
				JButton view = GachamanPanel.button("View Rolled Tasks");
				view.addActionListener(e -> clientThread.invokeLater(taskService::presentOffers));
				section.add(view);
				if (partyVote && partyRollService.canCancelRoll())
				{
					section.add(Box.createVerticalStrut(3));
					JButton cancel = GachamanPanel.button("Cancel Party Roll");
					cancel.setToolTipText("Host only: abort this party roll for every member"
						+ " who joined it (before a contract is accepted).");
					cancel.addActionListener(e -> partyRollService.cancelRoll());
					section.add(cancel);
				}
				return section;
			}
			section.add(GachamanPanel.smallLine("No active task.", ColorScheme.LIGHT_GRAY_COLOR));
			section.add(Box.createVerticalStrut(4));
			JButton roll = GachamanPanel.button("Roll Tasks");
			roll.setEnabled(taskService.canRollOffers());
			roll.addActionListener(e -> clientThread.invokeLater(taskService::rollOffers));
			section.add(roll);
			if (inPartySupplier.getAsBoolean())
			{
				section.add(Box.createVerticalStrut(4));
				if (partyRollService.isProposalLive())
				{
					section.add(GachamanPanel.smallLine(
						"Party roll pending — " + partyRollService.agreedCount() + " agreed",
						new Color(230, 190, 80)));
					if (partyRollService.canForceStart())
					{
						section.add(Box.createVerticalStrut(3));
						JButton start = GachamanPanel.button(
							"Start Roll Now (" + partyRollService.agreedCount() + " agreed)");
						start.setToolTipText("Host only: start immediately with everyone who has"
							+ " agreed so far instead of waiting out the 60s window.");
						start.addActionListener(e -> partyRollService.forceStart());
						section.add(start);
						section.add(Box.createVerticalStrut(3));
						JButton cancel = GachamanPanel.button("Cancel Party Roll");
						cancel.setToolTipText("Host only: abort this party roll for every member"
							+ " who joined it.");
						cancel.addActionListener(e -> partyRollService.cancelRoll());
						section.add(cancel);
					}
				}
				else
				{
					JButton party = GachamanPanel.button("Propose Party Roll");
					party.setToolTipText("Task-less party members join with ::gachaparty;"
						+ " the roll starts with whoever agrees (minimum 2), identical offers"
						+ " appear for everyone, and a unanimous vote picks the shared contract.");
					party.setEnabled(taskService.canRollOffers());
					party.addActionListener(e -> partyRollService.propose());
					section.add(party);
				}
			}
			return section;
		}

		JLabel monster = GachamanPanel.line(task.getMonsterName() + "  (lvl " + task.getMonsterCombatLevel() + ")",
			Color.WHITE, FontManager.getRunescapeBoldFont());
		JPanel headerRow = new JPanel(new java.awt.BorderLayout(6, 0));
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		headerRow.add(monster, java.awt.BorderLayout.CENTER);
		JButton wiki = new JButton("Wiki");
		wiki.setFont(FontManager.getRunescapeSmallFont());
		wiki.setMargin(new java.awt.Insets(1, 6, 1, 6));
		wiki.setFocusPainted(false);
		wiki.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		wiki.setForeground(new Color(240, 200, 90));
		wiki.setToolTipText("Open the OSRS wiki page for " + task.getMonsterName());
		final String wikiUrl = "https://oldschool.runescape.wiki/w/"
			+ task.getMonsterName().replace(' ', '_');
		wiki.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(wikiUrl));
		headerRow.add(wiki, java.awt.BorderLayout.EAST);
		headerRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
			Math.max(monster.getPreferredSize().height, wiki.getPreferredSize().height)));
		section.add(headerRow);
		section.add(GachamanPanel.smallLine(task.getDifficulty().getDisplayName()
				+ (task.isRedemption() ? " — REDEMPTION" : "")
				+ (task.isDuo() ? " — SHARED: " + task.getDuoPartnerName() : ""),
			task.getDifficulty().getColor()));
		section.add(Box.createVerticalStrut(5));
		// shared contracts pool every participant's kills into the quota
		int pooled = task.getKillsDone() + (task.isDuo() ? task.getDuoPartnerKills() : 0);
		double killsDone = pooled + (task.isHalfKillPending() ? 0.5 : 0);
		double killFrac = task.getKillsRequired() <= 0 ? 0
			: killsDone / task.getKillsRequired();
		section.add(new GachamanPanel.MeterBar(killFrac, task.getDifficulty().getColor(),
			pooled + (task.isHalfKillPending() ? ".5" : "")
				+ " / " + task.getKillsRequired() + " kills"
				+ (task.isDuo() ? " (yours: " + task.getKillsDone() + ")" : "")));
		section.add(Box.createVerticalStrut(4));
		section.add(GachamanPanel.smallLine(
			task.getPerKillGc() + " GC / kill  •  " + task.getCompletionGc() + " GC completion",
			ColorScheme.LIGHT_GRAY_COLOR));
		if (task.getAppliedCharge() != null)
		{
			section.add(GachamanPanel.smallLine("Charge applied: " + prettyCharge(task.getAppliedCharge()),
				new Color(150, 190, 240)));
		}

		if (task.getSideBets() != null && !task.getSideBets().isEmpty())
		{
			section.add(Box.createVerticalStrut(5));
			section.add(GachamanPanel.smallLine("Side bets:", ColorScheme.BRAND_ORANGE));
			for (SideBet bet : task.getSideBets())
			{
				String text;
				if (bet.isSealed() && !bet.isCompleted())
				{
					text = "??? — " + bet.getPayoutGc() + " GC";
				}
				else
				{
					text = TaskService.describeSideBet(bet) + " — " + bet.getPayoutGc() + " GC";
				}
				if (bet.isCompleted())
				{
					text = "✓ " + text;
				}
				section.add(GachamanPanel.smallLine(text,
					bet.isCompleted() ? SIDEBET_DONE : ColorScheme.LIGHT_GRAY_COLOR));
			}
		}

		section.add(Box.createVerticalStrut(4));
		section.add(GachamanPanel.wrapped(
			"A contract is a contract — tasks cannot be abandoned.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	private JPanel buildTaintSection(GachaState state)
	{
		JPanel section = GachamanPanel.section(null);
		section.setBackground(new Color(56, 26, 24));
		JLabel title = GachamanPanel.line("TAINT ACTIVE (" + state.getTaint() + ")",
			TAINT_RED, FontManager.getRunescapeBoldFont());
		section.add(title);
		section.add(Box.createVerticalStrut(3));
		section.add(GachamanPanel.wrapped(
			"Forbidden gear or style has stained your record. All GC income is halved"
				+" until the taint is worked off with clean kills (or a Redemption task).",
			new Color(230, 170, 160)));
		return section;
	}

	private JPanel buildFragmentSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Deed Fragments");
		double frac = (double) state.getDeedFragments() / Tuning.FRAGMENTS_REQUIRED;
		section.add(new GachamanPanel.MeterBar(frac, new Color(230, 190, 80),
			state.getDeedFragments() + " / " + Tuning.FRAGMENTS_REQUIRED + " fragments"));
		section.add(Box.createVerticalStrut(3));
		int tasksLeft = Tuning.FRAGMENT_WINDOW_TASKS - state.getTotalTasksCompleted();
		section.add(GachamanPanel.wrapped(
			"Medium+ contracts drop fragments during your first "
				+ Tuning.FRAGMENT_WINDOW_TASKS + " tasks (" + tasksLeft + " left). Collect "
				+ Tuning.FRAGMENTS_REQUIRED + " to forge a bonus Slot Deed.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	private JPanel buildPitySection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Pity Meter");
		int cap = state.getPrestigeRank() >= 2 ? Tuning.PITY_HARD_CAP_PRESTIGE2 : Tuning.PITY_HARD_CAP;
		double frac = (double) state.getOpensSinceEpic() / cap;
		Color barColor = frac >= 0.8 ? new Color(226, 148, 62) : new Color(150, 120, 220);
		section.add(new GachamanPanel.MeterBar(frac, barColor,
			state.getOpensSinceEpic() + " / " + cap + " cards"));
		section.add(Box.createVerticalStrut(3));
		section.add(GachamanPanel.wrapped(
			"Cards revealed without an Epic+. At the cap, a Legendary is guaranteed.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	private static String prettyCharge(String charge)
	{
		if ("COMPACTOR".equals(charge))
		{
			return "Compactor";
		}
		if ("EXTENDER".equals(charge))
		{
			return "Extender";
		}
		return charge;
	}

	private static String trimDouble(double value)
	{
		if (value == Math.floor(value))
		{
			return String.valueOf((int) value);
		}
		return String.format("%.1f", value);
	}
}
