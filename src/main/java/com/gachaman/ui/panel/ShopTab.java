package com.gachaman.ui.panel;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.SetTable;
import com.gachaman.model.GachaState;
import com.gachaman.service.ChestService;
import com.gachaman.service.CreditSink;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.PrestigeService;
import com.gachaman.service.WeeklyShopService;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/**
 * Shop: chest tiles (procedurally drawn), style-charge purchases, queued
 * boss-themed chests, the weekly rotating card shop and the prestige altar.
 */
@Singleton
public class ShopTab extends JPanel
{
	private static final Color GOLD = new Color(230, 190, 80);

	/**
	 * The width every section must fit in. GachamanPanel is a non-wrapped
	 * PluginPanel (PANEL_WIDTH + SCROLLBAR_WIDTH = 242px) with a 6px
	 * EmptyBorder on each side; each tab sits in a JScrollPane whose CUSTOM
	 * scrollbar is 9px (GameScrollBarUI, not the stock 17px), leaving
	 * 242 - 12 - 9 = 221.
	 */
	private static final int CONTENT_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
		- 2 * PluginPanel.BORDER_OFFSET - 9;

	/** Usable width inside a GachamanPanel.section(), which has 8px borders. */
	private static final int SECTION_INNER_WIDTH = CONTENT_WIDTH - 16;

	/** BorderLayout hgap used by GachamanPanel.row(). */
	private static final int ROW_GAP = 6;

	private final GachaStateService stateService;
	private final ChestService chestService;
	private final CreditSink creditSink;
	private final WeeklyShopService weeklyShopService;
	private final PrestigeService prestigeService;
	private final CardDatabase cardDatabase;
	private final SetTable setTable;

	private final com.gachaman.service.TaskService taskService;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final com.gachaman.service.TimelineService timelineService;

	@Inject
	public ShopTab(GachaStateService stateService, ChestService chestService, CreditSink creditSink,
		WeeklyShopService weeklyShopService, PrestigeService prestigeService,
		CardDatabase cardDatabase, SetTable setTable, com.gachaman.service.TaskService taskService,
		net.runelite.client.callback.ClientThread clientThread,
		com.gachaman.service.TimelineService timelineService)
	{
		this.timelineService = timelineService;
		this.stateService = stateService;
		this.chestService = chestService;
		this.creditSink = creditSink;
		this.weeklyShopService = weeklyShopService;
		this.prestigeService = prestigeService;
		this.cardDatabase = cardDatabase;
		this.setTable = setTable;
		this.taskService = taskService;
		this.clientThread = clientThread;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	void rebuild()
	{
		removeAll();
		GachaState state = stateService.get();
		if (state == null)
		{
			add(GachamanPanel.centeredNote("Log in to browse the shop."));
			revalidate();
			repaint();
			return;
		}

		addSection(buildBalanceSection(state));
		addSection(buildChestSection(state));
		addSection(buildSlotChestSection(state));
		addSection(buildChargeSection(state));
		if (!state.getQueuedThemedChests().isEmpty())
		{
			addSection(buildThemedSection(state));
		}
		addSection(buildWeeklySection(state));
		addSection(buildPrestigeSection());

		revalidate();
		repaint();
	}

	private void addSection(JPanel section)
	{
		add(new WidthCap(section));
		add(Box.createVerticalStrut(6));
	}

	/**
	 * Hard cap on a section's width. RuneLite side panels are fixed-width and
	 * the tab's scroll pane never scrolls horizontally, so a child whose
	 * preferred width exceeds the viewport would simply be clipped at the
	 * right edge. Capping both the preferred and maximum width here means no
	 * child can ever push a section past CONTENT_WIDTH.
	 */
	private static final class WidthCap extends JPanel
	{
		WidthCap(JComponent inner)
		{
			super(new BorderLayout());
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			add(inner, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			return new Dimension(Math.min(d.width, CONTENT_WIDTH), d.height);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(CONTENT_WIDTH, getPreferredSize().height);
		}
	}

	/**
	 * A single-line label whose text is pre-truncated with an ellipsis (via
	 * FontMetrics) so its preferred width can never exceed maxWidth. The
	 * tooltip always carries the full, untruncated detail.
	 */
	private static JLabel truncatedLine(String text, Color color, Font font, int maxWidth, String tooltip)
	{
		JLabel label = GachamanPanel.line(text, color, font);
		FontMetrics fm = label.getFontMetrics(font);
		if (fm.stringWidth(text) > maxWidth)
		{
			String ellipsis = "…";
			int budget = Math.max(0, maxWidth - fm.stringWidth(ellipsis));
			int end = text.length();
			while (end > 0 && fm.stringWidth(text.substring(0, end)) > budget)
			{
				end--;
			}
			label.setText(text.substring(0, end).trim() + ellipsis);
		}
		label.setToolTipText(tooltip);
		return label;
	}

	/** A word-wrapping label sized to the section's real inner width. */
	private static JLabel wrappedText(String text, Color color)
	{
		JLabel label = new JLabel("<html><body style='width:" + (SECTION_INNER_WIDTH - 10)
			+ "px'>" + text + "</body></html>");
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** A button that stretches to the full inner width of a section. */
	private static JButton fullWidthButton(String text)
	{
		JButton button = GachamanPanel.button(text);
		button.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, button.getPreferredSize().height));
		return button;
	}

	/** Same big GC readout as the Overview tab — the shop is where it is spent. */
	private static JPanel buildBalanceSection(GachaState state)
	{
		JPanel section = GachamanPanel.section(null);
		JLabel gc = new JLabel(QuantityFormatter.formatNumber(state.getGc()) + " GC");
		gc.setFont(FontManager.getRunescapeBoldFont().deriveFont(26f));
		gc.setForeground(ColorScheme.BRAND_ORANGE);
		gc.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(gc);
		return section;
	}

	// --- Chests ---

	private JPanel buildChestSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Chests");
		if (chestService.getPending() != null)
		{
			section.add(GachamanPanel.smallLine("A reveal is in progress…", ColorScheme.LIGHT_GRAY_COLOR));
			section.add(Box.createVerticalStrut(4));
		}
		for (Tuning.Chest tier : Tuning.Chest.values())
		{
			long price = Tuning.CHEST_PRICE_GC.get(tier);
			boolean affordable = state.getGc() >= price;
			double fraction = price <= 0 ? 1 : Math.min(1.0, (double) state.getGc() / price);
			int remaining = tier == Tuning.Chest.RUSTY
				? Tuning.RUSTY_LIFETIME_CAP - chestService.rustyChestsOpened()
				: -1;
			section.add(new ChestTile(tier, affordable, fraction, remaining));
			section.add(Box.createVerticalStrut(5));
		}
		return section;
	}

	private void tryOpenChest(Tuning.Chest tier)
	{
		long price = Tuning.CHEST_PRICE_GC.get(tier);
		if (!GachamanPanel.confirm(this, "Open chest",
			"Open a " + chestName(tier) + " for " + QuantityFormatter.formatNumber(price) + " GC?"))
		{
			return;
		}
		// chest rolls read live client skill levels — client thread only
		clientThread.invokeLater(() -> {
			if (chestService.openChest(tier) == null)
			{
				javax.swing.SwingUtilities.invokeLater(() ->
					GachamanPanel.info(this, "The chest cannot be opened right now"
						+ " (another reveal in progress, or not enough GC)."));
			}
		});
	}

	private static String chestName(Tuning.Chest tier)
	{
		switch (tier)
		{
			case RUSTY:
				return "Rusty Chest";
			case BATTERED:
				return "Battered Chest";
			case GILDED:
				return "Gilded Chest";
			default:
				return "Ornate Chest";
		}
	}

	// --- Slot-targeted chests ---

	private com.gachaman.model.GearSlot selectedSlotChest = com.gachaman.model.GearSlot.WEAPON;

	private JPanel buildSlotChestSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Slot Chests");
		long price = Tuning.CHEST_PRICE_GC.get(Tuning.Chest.GILDED);
		javax.swing.JComboBox<com.gachaman.model.GearSlot> picker =
			new javax.swing.JComboBox<>(com.gachaman.model.GearSlot.values());
		picker.setSelectedItem(selectedSlotChest);
		picker.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
				Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof com.gachaman.model.GearSlot)
				{
					setText(((com.gachaman.model.GearSlot) value).getDisplayName());
				}
				return this;
			}
		});
		picker.addActionListener(e ->
			selectedSlotChest = (com.gachaman.model.GearSlot) picker.getSelectedItem());
		GachamanPanel.styleCombo(picker);
		picker.setAlignmentX(Component.LEFT_ALIGNMENT);
		picker.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, 24));
		section.add(picker);
		section.add(Box.createVerticalStrut(4));

		JButton open = fullWidthButton("Open — " + QuantityFormatter.formatNumber(price) + " GC");
		open.setToolTipText("One card, rolled only from the chosen slot's pool (Gilded odds).");
		open.setEnabled(state.getGc() >= price && chestService.getPending() == null);
		open.addActionListener(e -> {
			com.gachaman.model.GearSlot slot = (com.gachaman.model.GearSlot) picker.getSelectedItem();
			if (slot != null && GachamanPanel.confirm(this, "Slot chest",
				"Open a " + slot.getDisplayName() + " chest for "
					+ QuantityFormatter.formatNumber(price) + " GC?\nOne card, "
					+ slot.getDisplayName() + " slot only."))
			{
				clientThread.invokeLater(() -> chestService.openSlotChest(slot));
			}
		});
		section.add(open);
		section.add(Box.createVerticalStrut(3));
		section.add(wrappedText(
			"Gilded price, one card — but every roll is the slot you chose.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	// --- Style charges ---

	private JPanel buildChargeSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Style Charges");
		int freeComp = state.getFreeCompactors();
		int freeExt = state.getFreeExtenders();
		if (freeComp > 0 || freeExt > 0)
		{
			String banner = "Free vouchers: "
				+ (freeComp > 0 ? freeComp + " Compactor" : "")
				+ (freeComp > 0 && freeExt > 0 ? " • " : "")
				+ (freeExt > 0 ? freeExt + " Extender" : "");
			section.add(truncatedLine(banner, GOLD,
				FontManager.getRunescapeSmallFont(), SECTION_INNER_WIDTH, banner));
			section.add(Box.createVerticalStrut(3));
		}
		com.gachaman.model.ActiveTask task = state.getActiveTask();
		if (task == null)
		{
			section.add(wrappedText(
				"Charges apply to your CURRENT task — assign a task first.",
				ColorScheme.MEDIUM_GRAY_COLOR));
			return section;
		}
		if (task.getAppliedCharge() != null)
		{
			boolean compactor = "COMPACTOR".equals(task.getAppliedCharge());
			String applied = "Applied to this task: " + (compactor ? "Compactor" : "Extender");
			section.add(truncatedLine(applied, new Color(150, 190, 240),
				FontManager.getRunescapeBoldFont(), SECTION_INNER_WIDTH, applied));
			section.add(Box.createVerticalStrut(3));
			section.add(wrappedText(
				"One charge per task — available again once a new task is assigned.",
				ColorScheme.MEDIUM_GRAY_COLOR));
			return section;
		}
		JButton compactor = fullWidthButton(freeComp > 0
			? "Compactor — FREE voucher"
			: "Compactor — " + Tuning.COMPACTOR_PRICE_GC + " GC");
		compactor.setToolTipText("This task counts double toward the style cycle, and each kill"
			+ " counts double toward the contract (the skipped count pays no GC)."
			+ (freeComp > 0 ? " Uses your free voucher — no GC." : ""));
		compactor.setEnabled(freeComp > 0 || state.getGc() >= Tuning.COMPACTOR_PRICE_GC);
		compactor.addActionListener(e -> buyCharge(true, "Compactor", Tuning.COMPACTOR_PRICE_GC));
		section.add(compactor);
		section.add(Box.createVerticalStrut(4));
		JButton extender = fullWidthButton(freeExt > 0
			? "Extender — FREE voucher"
			: "Extender — " + Tuning.EXTENDER_PRICE_GC + " GC");
		extender.setToolTipText("This task counts only half toward the style cycle."
			+ (freeExt > 0 ? " Uses your free voucher — no GC." : ""));
		extender.setEnabled(freeExt > 0 || state.getGc() >= Tuning.EXTENDER_PRICE_GC);
		extender.addActionListener(e -> buyCharge(false, "Extender", Tuning.EXTENDER_PRICE_GC));
		section.add(extender);
		section.add(Box.createVerticalStrut(4));
		section.add(wrappedText(
			"Compactor: task counts x2 toward the style cycle AND kills count x2 toward"
				+ " the contract (skips pay no GC). Extender: task counts x0.5 toward the cycle.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	private void buyCharge(boolean compactor, String pretty, int price)
	{
		GachaState state = stateService.get();
		boolean voucher = state != null
			&& (compactor ? state.getFreeCompactors() > 0 : state.getFreeExtenders() > 0);
		String cost = voucher ? "using your free voucher? (no GC)" : "for " + price + " GC?";
		if (!GachamanPanel.confirm(this, "Buy " + pretty,
			"Apply a " + pretty + " to your current task " + cost))
		{
			return;
		}
		// client thread: serializes the purchase with kill/completion processing
		final boolean voucherUsed = voucher;
		clientThread.invokeLater(() -> {
			if (taskService.purchaseCharge(compactor))
			{
				timelineService.onChargePurchased(compactor, voucherUsed);
			}
			else
			{
				javax.swing.SwingUtilities.invokeLater(() -> GachamanPanel.info(this,
					"Purchase failed — you need an active task, no charge applied yet, and enough GC."));
			}
		});
	}

	// --- Themed chests ---

	private JPanel buildThemedSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Boss Chests");
		for (String tag : state.getQueuedThemedChests())
		{
			JButton open = GachamanPanel.button("Open");
			open.addActionListener(e -> clientThread.invokeLater(() -> {
				if (chestService.openThemedChest(tag) == null)
				{
					javax.swing.SwingUtilities.invokeLater(() ->
						GachamanPanel.info(this, "The chest cannot be opened right now."));
				}
			}));
			String name = themedName(tag);
			int labelWidth = SECTION_INNER_WIDTH - open.getPreferredSize().width - ROW_GAP;
			JLabel label = truncatedLine(name, GOLD,
				FontManager.getRunescapeSmallFont(), labelWidth, name);
			section.add(GachamanPanel.row(label, open));
			section.add(Box.createVerticalStrut(3));
		}
		return section;
	}

	private String themedName(String tag)
	{
		for (SetTable.CardSet set : setTable.getSets())
		{
			if (set.getSetKey().equals(tag))
			{
				return set.getName() + " chest";
			}
		}
		if (tag == null || tag.isEmpty())
		{
			return "Themed chest";
		}
		return Character.toUpperCase(tag.charAt(0)) + tag.substring(1) + " chest";
	}

	// --- Weekly shop ---

	private JPanel buildWeeklySection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Weekly Shop");
		List<WeeklyShopService.ShopSlot> offers = weeklyShopService.currentOffers();
		if (offers.isEmpty())
		{
			section.add(wrappedText("Stock arrives once the card database is ready.",
				ColorScheme.MEDIUM_GRAY_COLOR));
			return section;
		}
		for (WeeklyShopService.ShopSlot slot : offers)
		{
			final int index = slot.getSlotIndex();
			final int price = slot.getPriceGc();
			final String cardName = slot.getCard().getName();
			String priceText = QuantityFormatter.formatNumber(price) + " GC";
			// Name on its own line, ellipsized so it can never widen the panel;
			// the tooltip always shows the full name and price.
			section.add(truncatedLine(cardName + (slot.isOwned() ? " (owned)" : ""),
				slot.getCard().getRarity().getColor(), FontManager.getRunescapeSmallFont(),
				SECTION_INNER_WIDTH, cardName + " — " + priceText));
			section.add(Box.createVerticalStrut(2));
			JButton buy = fullWidthButton(slot.isPurchased() ? "Bought" : "Buy — " + priceText);
			buy.setToolTipText(cardName + " — " + priceText);
			buy.setEnabled(!slot.isPurchased() && state.getGc() >= price);
			buy.addActionListener(e -> {
				if (!GachamanPanel.confirm(this, "Weekly shop",
					"Buy " + cardName + " for " + QuantityFormatter.formatNumber(price) + " GC?"))
				{
					return;
				}
				if (weeklyShopService.purchase(index) == null)
				{
					GachamanPanel.info(this, "Purchase failed (already bought or not enough GC).");
				}
			});
			section.add(buy);
			section.add(Box.createVerticalStrut(5));
		}
		section.add(Box.createVerticalStrut(2));
		section.add(GachamanPanel.smallLine("Stock resets weekly.", ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	// --- Prestige ---

	private JPanel buildPrestigeSection()
	{
		JPanel section = GachamanPanel.section("Prestige");
		PrestigeService.PrestigePlan plan = prestigeService.plan();
		section.add(wrappedText(plan.getRequirementText(), ColorScheme.LIGHT_GRAY_COLOR));
		section.add(Box.createVerticalStrut(3));
		String burns = "Burns " + plan.getCardsToBurn() + " Common/Uncommon cards";
		section.add(truncatedLine(burns, ColorScheme.LIGHT_GRAY_COLOR,
			FontManager.getRunescapeSmallFont(), SECTION_INNER_WIDTH, burns));
		if (plan.getNextRank() > 0)
		{
			section.add(GachamanPanel.smallLine("Next rank: " + plan.getNextRank(), GOLD));
		}
		section.add(Box.createVerticalStrut(5));
		JButton prestige = fullWidthButton("PRESTIGE");
		prestige.setForeground(GOLD);
		prestige.setEnabled(plan.isEligible());
		prestige.addActionListener(e -> doPrestige());
		section.add(prestige);
		return section;
	}

	private void doPrestige()
	{
		PrestigeService.PrestigePlan plan = prestigeService.plan();
		if (!plan.isEligible())
		{
			return;
		}
		if (!GachamanPanel.confirm(this, "Prestige",
			"Rebirth to Prestige " + plan.getNextRank() + "?\n\nThis will BURN "
				+ plan.getCardsToBurn() + " Common/Uncommon cards and cost "
				+ QuantityFormatter.formatNumber(plan.getGcCost()) + " GC."))
		{
			return;
		}
		if (!GachamanPanel.confirm(this, "Prestige — final warning",
			"Are you absolutely sure? The " + plan.getCardsToBurn()
				+ " burned cards are gone forever.\nShiny and Hologram cards are kept."))
		{
			return;
		}
		if (prestigeService.prestige() < 0)
		{
			GachamanPanel.info(this, "Prestige failed — requirements no longer met.");
		}
	}

	// --- Chest tile component ---

	private final class ChestTile extends JComponent
	{
		private final Tuning.Chest tier;
		private final boolean affordable;
		private final double fraction;
		/** Lifetime opens left for capped tiers; -1 = uncapped. 0 = retired. */
		private final int remaining;

		ChestTile(Tuning.Chest tier, boolean affordable, double fraction, int remaining)
		{
			this.tier = tier;
			this.affordable = affordable && remaining != 0;
			this.fraction = fraction;
			this.remaining = remaining;
			setPreferredSize(new Dimension(Math.min(120, SECTION_INNER_WIDTH), 62));
			setMaximumSize(new Dimension(SECTION_INNER_WIDTH, 62));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			if (remaining == 0)
			{
				setToolTipText(chestName(tier) + " — rusted away ("
					+ Tuning.RUSTY_LIFETIME_CAP + " of " + Tuning.RUSTY_LIFETIME_CAP + " opened)");
			}
			else
			{
				setToolTipText(chestName(tier) + " — " + Tuning.CHEST_CARDS.get(tier) + " card(s), "
					+ QuantityFormatter.formatNumber(Tuning.CHEST_PRICE_GC.get(tier)) + " GC"
					+ (remaining > 0 ? ", " + remaining + " left ever" : ""));
			}
			if (this.affordable)
			{
				setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (ChestTile.this.affordable && cardDatabase.isReady())
					{
						tryOpenChest(ChestTile.this.tier);
					}
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			Color body = bodyColor(tier);
			Color trim = trimColor(tier);
			if (!affordable || remaining == 0)
			{
				body = desaturate(body);
				trim = desaturate(trim);
			}

			// tile background + border
			g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
			g2.setColor(affordable ? trim : ColorScheme.MEDIUM_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1.2f));
			g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

			// procedural chest icon
			int ix = 8;
			int iy = 12;
			int iw = 40;
			int ih = 38;
			g2.setColor(body);
			g2.fillRoundRect(ix, iy + ih / 3, iw, ih * 2 / 3, 6, 6);
			g2.fillArc(ix, iy, iw, ih * 2 / 3, 0, 180);
			g2.setColor(trim);
			g2.setStroke(new BasicStroke(1.5f));
			g2.drawRoundRect(ix, iy + ih / 3, iw, ih * 2 / 3, 6, 6);
			g2.drawArc(ix, iy, iw, ih * 2 / 3, 0, 180);
			g2.drawLine(ix, iy + ih / 3, ix + iw, iy + ih / 3);
			// clasp + keyhole
			g2.fillRect(ix + iw / 2 - 3, iy + ih / 3 - 3, 6, 9);
			g2.setColor(body.darker());
			g2.fillOval(ix + iw / 2 - 1, iy + ih / 3, 3, 4);

			// texts
			int tx = ix + iw + 8;
			g2.setFont(FontManager.getRunescapeBoldFont());
			g2.setColor(affordable ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
			g2.drawString(chestName(tier), tx, 22);
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(affordable ? GOLD : ColorScheme.MEDIUM_GRAY_COLOR);
			if (remaining == 0)
			{
				g2.drawString("Rusted away", tx, 38);
			}
			else
			{
				g2.drawString(QuantityFormatter.formatNumber(Tuning.CHEST_PRICE_GC.get(tier)) + " GC  •  "
					+ Tuning.CHEST_CARDS.get(tier) + (Tuning.CHEST_CARDS.get(tier) == 1 ? " card" : " cards")
					+ (remaining > 0 ? "  •  " + remaining + " left" : ""),
					tx, 38);
			}

			// affordability progress bar when locked
			if (!affordable && remaining != 0)
			{
				int barX = tx;
				int barW = Math.max(20, w - tx - 10);
				int barY = h - 15;
				g2.setColor(new Color(24, 24, 24));
				g2.fillRoundRect(barX, barY, barW, 6, 4, 4);
				int fill = (int) Math.round(barW * Math.max(0, Math.min(1, fraction)));
				if (fill > 0)
				{
					g2.setColor(new Color(226, 148, 62, 200));
					g2.fillRoundRect(barX, barY, Math.max(fill, 4), 6, 4, 4);
				}
			}
			g2.dispose();
		}
	}

	private static Color bodyColor(Tuning.Chest tier)
	{
		switch (tier)
		{
			case RUSTY:
				return new Color(88, 60, 42);
			case BATTERED:
				return new Color(101, 84, 63);
			case GILDED:
				return new Color(133, 105, 41);
			default:
				return new Color(90, 56, 128);
		}
	}

	private static Color trimColor(Tuning.Chest tier)
	{
		switch (tier)
		{
			case RUSTY:
				return new Color(154, 96, 52);
			case BATTERED:
				return new Color(146, 126, 96);
			case GILDED:
				return new Color(230, 190, 80);
			default:
				return new Color(255, 196, 60);
		}
	}

	private static Color desaturate(Color color)
	{
		int gray = (int) (color.getRed() * 0.3 + color.getGreen() * 0.59 + color.getBlue() * 0.11);
		return new Color(
			(color.getRed() + gray * 2) / 3,
			(color.getGreen() + gray * 2) / 3,
			(color.getBlue() + gray * 2) / 3).darker();
	}
}
