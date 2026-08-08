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
	private static final Color DOCKET_GREEN = new Color(120, 220, 120);
	/** The Ante: warm, not festive — it marks money at risk, not a prize. */
	private static final Color ANTE_AMBER = new Color(214, 158, 74);
	/** The Charter Office: paperwork ink, cooler than the Ante's money-at-risk amber. */
	private static final Color CHARTER_INK = new Color(150, 190, 225);

	private final GachaStateService stateService;
	private final TaskService taskService;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final com.gachaman.party.PartyRollService partyRollService;
	private final com.gachaman.GachamanConfig config;
	private final com.gachaman.service.CharterService charterService;

	private BooleanSupplier inPartySupplier = () -> false;

	@Inject
	public OverviewTab(GachaStateService stateService, TaskService taskService,
		net.runelite.client.callback.ClientThread clientThread,
		com.gachaman.party.PartyRollService partyRollService,
		com.gachaman.GachamanConfig config,
		com.gachaman.service.CharterService charterService)
	{
		this.stateService = stateService;
		this.taskService = taskService;
		this.clientThread = clientThread;
		this.partyRollService = partyRollService;
		this.config = config;
		this.charterService = charterService;
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
						? "Party contracts rolled — click one to VOTE (a majority accepts)."
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
				addAnteControls(section, state, partyVote);
				addCharterControls(section, state, partyVote);
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
						+ " appear for everyone, and a majority vote picks the shared contract.");
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
				+ (task.isParty() ? " — SHARED: " + task.getPartyLabel() : ""),
			task.getDifficulty().getColor()));
		section.add(Box.createVerticalStrut(5));
		// shared contracts pool every participant's kills into the quota
		int pooled = task.getKillsDone() + (task.isParty() ? task.getPartyOtherKills() : 0);
		double killsDone = pooled + (task.isHalfKillPending() ? 0.5 : 0);
		double killFrac = task.getKillsRequired() <= 0 ? 0
			: killsDone / task.getKillsRequired();
		section.add(new GachamanPanel.MeterBar(killFrac, task.getDifficulty().getColor(),
			pooled + (task.isHalfKillPending() ? ".5" : "")
				+ " / " + task.getKillsRequired() + " kills"
				+ (task.isParty() ? " (yours: " + task.getKillsDone() + ")" : "")));
		section.add(Box.createVerticalStrut(4));
		section.add(GachamanPanel.smallLine(
			task.getPerKillGc() + " GC / kill  ·  " + task.getCompletionGc() + " GC completion",
			ColorScheme.LIGHT_GRAY_COLOR));
		if (task.getAppliedCharge() != null)
		{
			section.add(GachamanPanel.smallLine("Charge applied: " + prettyCharge(task.getAppliedCharge()),
				new Color(150, 190, 240)));
		}
		if (task.getAnteStake() > 0)
		{
			// Shown for as long as the stake exists, because it is the player's
			// own GC sitting outside the purse — the balance at the top of this
			// panel is short by exactly this much and needs the explanation.
			JLabel ante = GachamanPanel.smallLine(
				"Ante: " + QuantityFormatter.formatNumber(task.getAnteStake()) + " GC staked",
				ANTE_AMBER);
			ante.setToolTipText("This GC left your purse when you signed. Complete the contract"
				+ " and " + QuantityFormatter.formatNumber(
					(long) task.getAnteStake() * Tuning.ANTE_PAYOUT_MULT)
				+ " GC comes back; die and it is gone. Only your own stake is at risk —"
				+ " a party member dying does not cost you yours.");
			section.add(ante);
		}
		// Shown on EVERY contract, active or not. A bonus that only appears once
		// it is already earned is a bonus nobody goes looking for, and the "not
		// aligned" state is the only place the player can be told why they are
		// not getting it. The tooltip carries the exact when-is-it-fixed rule.
		JLabel docket = GachamanPanel.smallLine(
			task.isSlayerAligned()
				? "Double Docket: ACTIVE — x" + Tuning.DOUBLE_DOCKET_MULT + " completion"
				: "Double Docket: not your Slayer task",
			task.isSlayerAligned() ? DOCKET_GREEN : ColorScheme.LIGHT_GRAY_COLOR);
		docket.setToolTipText("Kill your Slayer assignment on contract and completion pays x"
			+ Tuning.DOUBLE_DOCKET_MULT + ". Checked when you accept AND on every kill, so"
			+ " picking up the matching task mid-contract still counts — and once it locks in it"
			+ " stays, even if you finish the Slayer task first. Contracts are never rolled to"
			+ " match your Slayer task; grouped assignments such as Metal dragons name no single"
			+ " monster and cannot be detected.");
		section.add(docket);

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
					text = "* " + text;
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

	/** The ten-point stops across the legal band, both ends included. */
	private static int[] antePercentChoices()
	{
		int count = (Tuning.ANTE_MAX_PERCENT - Tuning.ANTE_MIN_PERCENT) / 10 + 1;
		int[] choices = new int[count];
		for (int i = 0; i < count; i++)
		{
			choices[i] = Tuning.ANTE_MIN_PERCENT + i * 10;
		}
		return choices;
	}

	private static boolean hasInsaneOffer(GachaState state)
	{
		if (state.getPendingOffers() == null)
		{
			return false;
		}
		for (com.gachaman.model.TaskOffer offer : state.getPendingOffers())
		{
			if (TaskService.anteEligible(offer))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The Ante's arming controls, shown only while an INSANE contract is
	 * actually on the board — an option to wager on contracts that are not there
	 * is an invitation, and this is meant to be an answer to a question the
	 * player asked.
	 *
	 * Arming is the ONLY route by which GC is ever staked. Nothing here is
	 * preselected, the button is never the obvious next click, and it takes TWO
	 * confirmations: one naming the amount, one naming what losing means.
	 * Disarming takes none — backing out of a wager should never be defended.
	 */
	private void addAnteControls(JPanel section, GachaState state, boolean partyVote)
	{
		if (!config.anteEnabled() || !hasInsaneOffer(state))
		{
			return;
		}
		section.add(Box.createVerticalStrut(6));
		section.add(GachamanPanel.smallLine("The Ante", ColorScheme.BRAND_ORANGE));

		int armed = taskService.getArmedAntePercent();
		if (armed > 0)
		{
			section.add(GachamanPanel.wrapped("ARMED at " + armed + "% — "
				+ QuantityFormatter.formatNumber(taskService.previewAnteStake())
				+ " GC will be staked on the INSANE contract you "
				+ (partyVote
					? "vote for, and only if EVERY member agrees to their own stake."
					: "accept."),
				ANTE_AMBER));
			section.add(Box.createVerticalStrut(3));
			JButton disarm = GachamanPanel.button("Disarm the Ante");
			disarm.setToolTipText("Take the wager off. Nothing has been staked yet —"
				+ " GC only leaves your purse when a contract is actually signed.");
			disarm.addActionListener(e -> {
				taskService.armAnte(0);
				rebuild();
			});
			section.add(disarm);
			return;
		}

		if (taskService.previewAnteStake(Tuning.ANTE_MIN_PERCENT) <= 0)
		{
			section.add(GachamanPanel.wrapped("Your purse is under "
				+ QuantityFormatter.formatNumber(Tuning.ANTE_MIN_PURSE_GC)
				+ " GC, so no wager is offered.", ColorScheme.MEDIUM_GRAY_COLOR));
			return;
		}

		section.add(GachamanPanel.wrapped(
			"Optional. Stake part of your purse against an INSANE contract before you take"
				+ " it: complete the contract and the stake returns doubled, die and it is"
				+ " gone. Contracts cannot be abandoned.",
			ColorScheme.LIGHT_GRAY_COLOR));
		if (partyVote)
		{
			section.add(GachamanPanel.wrapped(
				"In a party it takes EVERY member. Each stakes from their own purse, and"
					+ " each loses only their own — one member declining means no Ante for"
					+ " anyone, but the contract goes ahead either way.",
				ColorScheme.LIGHT_GRAY_COLOR));
		}
		section.add(Box.createVerticalStrut(4));

		// The GC figure is baked into each item rather than tracked by a listener:
		// the amount is the whole decision, so it must be readable at the moment
		// of choosing without a second glance elsewhere.
		final int[] choices = antePercentChoices();
		javax.swing.JComboBox<String> percents = new javax.swing.JComboBox<>();
		for (int percent : choices)
		{
			percents.addItem(percent + "%  —  "
				+ QuantityFormatter.formatNumber(taskService.previewAnteStake(percent)) + " GC");
		}
		percents.setSelectedIndex(0);
		percents.setFont(FontManager.getRunescapeSmallFont());
		percents.setAlignmentX(Component.LEFT_ALIGNMENT);
		percents.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
			percents.getPreferredSize().height));
		section.add(percents);
		section.add(Box.createVerticalStrut(3));

		JButton arm = GachamanPanel.button("Arm the Ante");
		arm.setToolTipText("Arms only. The GC leaves your purse when you sign an INSANE"
			+ " contract, and is priced from your purse at that moment (capped at "
			+ QuantityFormatter.formatNumber(Tuning.ANTE_MAX_GC) + " GC).");
		arm.addActionListener(e -> {
			int percent = choices[Math.max(0, percents.getSelectedIndex())];
			int stake = taskService.previewAnteStake(percent);
			if (stake <= 0)
			{
				return;
			}
			String amount = QuantityFormatter.formatNumber(stake);
			if (!GachamanPanel.confirm(this, "Arm the Ante?",
				"Stake " + amount + " GC (" + percent + "% of your purse) on the next INSANE"
					+ " contract you take? The exact amount is fixed when you sign."))
			{
				return;
			}
			if (!GachamanPanel.confirm(this, "If you die, it is gone",
				"Dying while that contract is active loses the " + amount + " GC outright."
					+ " The contract itself is still binding and cannot be abandoned."
					+ " Arm the Ante?"))
			{
				return;
			}
			taskService.armAnte(percent);
			rebuild();
		});
		section.add(arm);
	}

	/**
	 * The Charter Office. Shown against a live personal board only, because what
	 * it sells is an EXTRA offer on that board — there is nothing to append to
	 * otherwise, and a party's board is not one player's to add to.
	 *
	 * The hold's state is always spelled out. GC that has left the purse and is
	 * coming back on a timer has to be visible, or the refund lands later as an
	 * unexplained balance change.
	 */
	private void addCharterControls(JPanel section, GachaState state, boolean partyVote)
	{
		com.gachaman.model.CharterHold hold = state.getCharterHold();
		if (hold != null)
		{
			section.add(Box.createVerticalStrut(6));
			section.add(GachamanPanel.smallLine("The Charter Office", ColorScheme.BRAND_ORANGE));
			section.add(GachamanPanel.wrapped("Deed held on <b>" + escape(hold.getMonsterName())
				+ "</b> — " + QuantityFormatter.formatNumber(hold.getPriceGc())
				+ " GC. Accept that contract off the board within "
				+ charterService.holdTicksRemaining() + " ticks, or the GC is refunded.",
				CHARTER_INK));
			return;
		}
		if (partyVote)
		{
			return;
		}
		if (charterService.usedToday())
		{
			section.add(Box.createVerticalStrut(6));
			section.add(GachamanPanel.smallLine("The Charter Office", ColorScheme.MEDIUM_GRAY_COLOR));
			section.add(GachamanPanel.wrapped(
				"Closed — one deed per day, and today's is spent.", ColorScheme.MEDIUM_GRAY_COLOR));
			return;
		}
		if (!charterService.canPurchase())
		{
			return;
		}
		java.util.List<com.gachaman.service.CharterService.Target> targets =
			charterService.eligibleTargets();

		section.add(Box.createVerticalStrut(6));
		section.add(GachamanPanel.smallLine("The Charter Office", ColorScheme.BRAND_ORANGE));
		if (targets.isEmpty())
		{
			section.add(GachamanPanel.wrapped("Name a target and the board will write the contract"
				+ " — but only for something you have killed at least "
				+ Tuning.CHARTER_KILLS_REQUIRED + " times. Nothing qualifies yet.",
				ColorScheme.MEDIUM_GRAY_COLOR));
			return;
		}
		section.add(GachamanPanel.wrapped("Buy one contract a day instead of waiting for it. The deed"
			+ " joins the board as an extra offer and is held for " + Tuning.CHARTER_HOLD_TICKS
			+ " ticks — sign it or the GC comes back.", ColorScheme.LIGHT_GRAY_COLOR));
		section.add(Box.createVerticalStrut(4));

		// price and difficulty ride on each row: the cost is the whole decision,
		// and it must be readable at the moment of choosing, not one click later
		javax.swing.JComboBox<String> picker = new javax.swing.JComboBox<>();
		for (com.gachaman.service.CharterService.Target target : targets)
		{
			picker.addItem(target.getMonsterName() + "  -  "
				+ QuantityFormatter.formatNumber(target.getPriceGc()) + " GC");
		}
		picker.setSelectedIndex(0);
		GachamanPanel.styleCombo(picker);
		picker.setAlignmentX(Component.LEFT_ALIGNMENT);
		picker.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
			picker.getPreferredSize().height));
		section.add(picker);
		section.add(Box.createVerticalStrut(3));

		JLabel detail = GachamanPanel.smallLine("", CHARTER_INK);
		section.add(detail);
		section.add(Box.createVerticalStrut(3));
		Runnable describe = () -> {
			com.gachaman.service.CharterService.Target target =
				targets.get(Math.max(0, Math.min(targets.size() - 1, picker.getSelectedIndex())));
			detail.setText(target.getDifficulty().getDisplayName() + " - lvl "
				+ target.getCombatLevel() + " - " + target.getKills() + " killed");
			detail.setForeground(target.getDifficulty().getColor());
		};
		describe.run();
		picker.addActionListener(e -> describe.run());

		JButton buy = GachamanPanel.button("Charter a Deed");
		buy.setToolTipText("One per day. The GC is held, not spent: it returns in full if the"
			+ " deed is still unsigned after " + Tuning.CHARTER_HOLD_TICKS + " ticks.");
		buy.addActionListener(e -> {
			com.gachaman.service.CharterService.Target target =
				targets.get(Math.max(0, Math.min(targets.size() - 1, picker.getSelectedIndex())));
			if (state.getGc() < target.getPriceGc())
			{
				GachamanPanel.info(this, "That deed costs "
					+ QuantityFormatter.formatNumber(target.getPriceGc())
					+ " GC and you have " + QuantityFormatter.formatNumber(state.getGc()) + ".");
				return;
			}
			if (!GachamanPanel.confirm(this, "Charter this deed?",
				"Pay " + QuantityFormatter.formatNumber(target.getPriceGc()) + " GC for a "
					+ target.getDifficulty().getDisplayName() + " contract on "
					+ target.getMonsterName() + "?\n\nIt joins the board as an extra offer."
					+ " Accepting it is binding like any other contract. This is your one deed"
					+ " for today either way."))
			{
				return;
			}
			final String name = target.getMonsterName();
			clientThread.invokeLater(() -> charterService.purchase(name));
		});
		section.add(buy);
	}

	/** Swing HTML labels interpret their text; monster names come from data. */
	private static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;");
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
