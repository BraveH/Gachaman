package com.gachaman.ui.panel;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.ServiceRecordService;
import com.gachaman.ui.CardImageService;
import com.gachaman.ui.CardRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The card album: every card in the database as a lazily rasterized thumbnail
 * grid, sorted rarity-ascending (Common first) then A-Z by default, with a
 * header button that flips the rarity direction. Unowned cards are darkened
 * silhouettes named "???". Thumbnails are built off the EDT in SwingWorker
 * batches (only visible cells are requested) and cached in a small LRU so a
 * multi-thousand-card database stays light. Item sprites load asynchronously,
 * so every raster snapshots a per-cell version and registers a load hook that
 * bumps the version, evicts that one cell and repaints — a raster built from
 * a not-yet-loaded sprite can never stick. The cached raster is static; a
 * live paint pass animates diagonal sheen, prismatic borders and scanlines
 * over visible shiny/hologram/EPIC+ cells, driven by a Swing timer that only
 * runs while such cells are actually on screen.
 */
@Slf4j
@Singleton
public class AlbumTab extends JPanel
{
	private static final int THUMB_W = 90;
	private static final int THUMB_H = 120;
	private static final int GAP = 6;
	/**
	 * Fixed time for the base raster. Chosen so CardRenderer's EPIC+ sheen
	 * band (period 3000ms, on-card while t%3000 is roughly 667..2000) sits
	 * fully off-card: the cached thumbnails carry no frozen effect band and
	 * the live paint pass owns all time-varying shimmer.
	 */
	private static final long STATIC_TIME_MS = 300L;
	private static final int CACHE_MAX = 300;
	private static final int BATCH_MAX = 24;
	private static final String UNKNOWN_NAME = "???";
	private static final Color PLACEHOLDER = new Color(38, 36, 32);
	/** Corner arc of the card face (matches CardRenderer's w/7 round rect). */
	private static final float CELL_ARC = THUMB_W / 7f;
	private static final int EFFECT_TICK_MS = 33;
	private static final int SHEEN_PERIOD_MS = 2600;
	private static final double SHEEN_ANGLE_RAD = Math.toRadians(-25);
	private static final Color SHEEN_CLEAR = new Color(255, 255, 255, 0);
	private static final Color SHEEN_WHITE = new Color(255, 255, 255, 46);
	private static final Color HOLO_SCAN = new Color(140, 230, 255, 34);
	private static final Color HOLO_EDGE = new Color(120, 220, 255);
	private static final BasicStroke BORDER_THICK = new BasicStroke(2.5f);
	private static final BasicStroke BORDER_THIN = new BasicStroke(1.6f);

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;

	private final JComboBox<String> slotFilter;
	private final JComboBox<String> rarityFilter;
	private final JComboBox<String> variantFilter;
	private final JCheckBox ownedOnlyBox = new JCheckBox("Owned only");
	private final JButton sortOrderButton = new JButton("Common first");
	private final JTextField searchField = new JTextField();
	private final JLabel collectedLabel = new JLabel();
	private final JLabel rarityCountsLabel = new JLabel();
	private final JLabel stardustLabel = new JLabel();
	private final JPanel holoPanel = new JPanel();
	private final GridPanel grid = new GridPanel();

	private List<CardDefinition> sorted = Collections.emptyList();
	private Map<Integer, CardDefinition> sortedSource;
	/** False = rarity ascending (Common first), true = Legendary first. */
	private boolean sortDescending;
	/** Direction {@link #sorted} was built with, to detect toggle flips. */
	private boolean sortedDescending;
	private Map<Integer, Variant> ownedVariantByCardId = Collections.emptyMap();
	/** Card id -> best Service Record among the owned copies of that card. */
	private Map<Integer, Integer> serviceByCardId = Collections.emptyMap();

	@Inject
	public AlbumTab(GachaStateService stateService, CardDatabase cardDatabase,
		CardImageService cardImageService)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.cardImageService = cardImageService;

		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		slotFilter = comboOf("All slots", slotNames());
		rarityFilter = comboOf("All rarities", rarityNames());
		variantFilter = comboOf("All variants", new String[]{"Normal", "Shiny"});

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);

		collectedLabel.setFont(FontManager.getRunescapeBoldFont());
		collectedLabel.setForeground(Color.WHITE);
		JPanel headerRow = new JPanel();
		headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.X_AXIS));
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerRow.add(collectedLabel);
		headerRow.add(Box.createHorizontalGlue());
		stardustLabel.setFont(FontManager.getRunescapeSmallFont());
		stardustLabel.setIcon(new StardustIcon());
		stardustLabel.setIconTextGap(4);
		headerRow.add(stardustLabel);
		north.add(headerRow);
		rarityCountsLabel.setFont(FontManager.getRunescapeSmallFont());
		rarityCountsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(rarityCountsLabel);
		north.add(Box.createVerticalStrut(5));

		JPanel filterGrid = new JPanel(new GridLayout(2, 2, 4, 4));
		filterGrid.setOpaque(false);
		filterGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
		filterGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
		filterGrid.add(slotFilter);
		filterGrid.add(rarityFilter);
		filterGrid.add(variantFilter);
		ownedOnlyBox.setOpaque(false);
		ownedOnlyBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ownedOnlyBox.setFont(FontManager.getRunescapeSmallFont());
		ownedOnlyBox.setIcon(new CheckboxIcon(false));
		ownedOnlyBox.setSelectedIcon(new CheckboxIcon(true));
		filterGrid.add(ownedOnlyBox);
		north.add(filterGrid);
		north.add(Box.createVerticalStrut(4));

		sortOrderButton.setFont(FontManager.getRunescapeSmallFont());
		sortOrderButton.setFocusable(false);
		sortOrderButton.setFocusPainted(false);
		sortOrderButton.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		sortOrderButton.setForeground(Color.WHITE);
		sortOrderButton.setToolTipText("Toggle rarity sort order");
		Dimension sortSize = new Dimension(94, 24);
		sortOrderButton.setPreferredSize(sortSize);
		sortOrderButton.setMinimumSize(sortSize);
		sortOrderButton.setMaximumSize(sortSize);

		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			javax.swing.BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new javax.swing.border.EmptyBorder(2, 5, 2, 5)));
		searchField.setToolTipText("Search by card name");
		JPanel searchRow = new JPanel();
		searchRow.setLayout(new BoxLayout(searchRow, BoxLayout.X_AXIS));
		searchRow.setOpaque(false);
		searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		searchRow.add(searchField);
		searchRow.add(Box.createHorizontalStrut(4));
		searchRow.add(sortOrderButton);
		north.add(searchRow);
		north.add(Box.createVerticalStrut(5));

		holoPanel.setLayout(new BoxLayout(holoPanel, BoxLayout.Y_AXIS));
		holoPanel.setOpaque(false);
		holoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(holoPanel);

		add(north, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(grid,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(THUMB_H / 3);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		GachamanPanel.styleScrollbar(scroll);
		add(scroll, BorderLayout.CENTER);

		slotFilter.addActionListener(e -> applyFilters());
		rarityFilter.addActionListener(e -> applyFilters());
		variantFilter.addActionListener(e -> applyFilters());
		ownedOnlyBox.addActionListener(e -> applyFilters());
		sortOrderButton.addActionListener(e -> {
			sortDescending = !sortDescending;
			sortOrderButton.setText(sortDescending ? "Legendary first" : "Common first");
			ensureSorted();
			applyFilters();
		});
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyFilters();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyFilters();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyFilters();
			}
		});
	}

	void rebuild()
	{
		GachaState state = stateService.get();
		ensureSorted();

		Map<Integer, Variant> ownedVariants = new HashMap<>();
		if (state != null)
		{
			for (OwnedCard owned : state.getOwnedCards())
			{
				if (owned.isHologram())
				{
					continue;
				}
				Variant existing = ownedVariants.get(owned.getCardId());
				if (existing == null || (existing == Variant.NORMAL && owned.getVariant() == Variant.SHINY))
				{
					ownedVariants.put(owned.getCardId(), owned.getVariant());
				}
			}
		}
		ownedVariantByCardId = ownedVariants;
		serviceByCardId = ServiceRecordService.bestByCardId(
			state == null ? null : state.getOwnedCards());

		updateHeader();
		rebuildHoloSection(state);
		applyFilters();
	}

	private void ensureSorted()
	{
		if (!cardDatabase.isReady())
		{
			sorted = Collections.emptyList();
			sortedSource = null;
			return;
		}
		Map<Integer, CardDefinition> source = cardDatabase.all();
		if (source == sortedSource && sortedDescending == sortDescending)
		{
			return;
		}
		final boolean descending = sortDescending;
		List<CardDefinition> cards = new ArrayList<>(source.values());
		// rarity ascending (Common first) by default, toggleable to
		// descending; name A-Z within a rarity either way
		cards.sort((a, b) -> {
			int cmp = Integer.compare(a.getRarity().ordinal(), b.getRarity().ordinal());
			if (cmp != 0)
			{
				return descending ? -cmp : cmp;
			}
			return a.getName().compareToIgnoreCase(b.getName());
		});
		sorted = cards;
		sortedSource = source;
		sortedDescending = descending;
	}

	private void updateHeader()
	{
		int total = sorted.size();
		int ownedCount = 0;
		Map<Rarity, Integer> ownedByRarity = new EnumMap<>(Rarity.class);
		Map<Rarity, Integer> totalByRarity = new EnumMap<>(Rarity.class);
		for (CardDefinition card : sorted)
		{
			totalByRarity.merge(card.getRarity(), 1, Integer::sum);
			if (ownedVariantByCardId.containsKey(card.getCardId()))
			{
				ownedCount++;
				ownedByRarity.merge(card.getRarity(), 1, Integer::sum);
			}
		}
		collectedLabel.setText("Collected: " + ownedCount + " / " + total);

		GachaState stardustState = stateService.get();
		int dust = stardustState == null ? 0 : stardustState.getStardust();
		boolean armed = stardustState != null && stardustState.isStardustBlessArmed();
		stardustLabel.setText(armed ? "Blessed!" : dust + "/" + com.gachaman.Tuning.STARDUST_REQUIRED);
		stardustLabel.setForeground(armed ? new Color(230, 190, 80) : new Color(190, 170, 255));
		stardustLabel.setToolTipText(armed
			? "Stardust blessing armed — the next chest rolls every card's shiny twice"
			: "Stardust " + dust + "/" + com.gachaman.Tuning.STARDUST_REQUIRED
				+ " — shiny near-misses bank stardust; a full bank blesses your next chest");

		StringBuilder html = new StringBuilder("<html>");
		boolean first = true;
		for (Rarity rarity : Rarity.values())
		{
			if (!first)
			{
				html.append("&nbsp;&nbsp;");
			}
			first = false;
			Color c = rarity.getColor();
			html.append("<span style='color:rgb(").append(c.getRed()).append(',')
				.append(c.getGreen()).append(',').append(c.getBlue()).append(")'>")
				.append(rarity.getDisplayName().charAt(0)).append(':')
				.append(ownedByRarity.getOrDefault(rarity, 0)).append('/')
				.append(totalByRarity.getOrDefault(rarity, 0)).append("</span>");
		}
		html.append("</html>");
		rarityCountsLabel.setText(html.toString());
	}

	private void rebuildHoloSection(@Nullable GachaState state)
	{
		holoPanel.removeAll();
		if (state == null || !cardDatabase.isReady())
		{
			return;
		}
		Set<String> seenTiers = new LinkedHashSet<>();
		List<OwnedCard> holos = new ArrayList<>();
		for (OwnedCard owned : state.getOwnedCards())
		{
			if (owned.isHologram() && seenTiers.add(owned.getTierKey()))
			{
				holos.add(owned);
			}
		}
		if (holos.isEmpty())
		{
			return;
		}
		JLabel title = new JLabel("Holograms");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(new Color(120, 220, 255));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		holoPanel.add(title);
		for (OwnedCard owned : holos)
		{
			HologramDefinition def = cardDatabase.holograms().get(owned.getTierKey());
			String name = def != null ? def.getName() : "Hologram (" + owned.getTierKey() + ")";
			GearSlot assigned = assignedSlotOf(state, owned);
			// holograms never enter the grid, so this label list is their only
			// album surface for the Service Record
			String text = name + " — tier " + owned.getTierKey()
				+ (assigned != null ? " — " + assigned.getDisplayName() : "")
				+ (owned.getKillsServed() > 0
					? " — present for " + QuantityFormatter.formatNumber(owned.getKillsServed())
						+ " kills"
					: "");
			JLabel label = new JLabel(text);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(def != null ? def.getRarity().getColor() : ColorScheme.LIGHT_GRAY_COLOR);
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			holoPanel.add(label);
		}
		holoPanel.add(Box.createVerticalStrut(4));
	}

	@Nullable
	private static GearSlot assignedSlotOf(GachaState state, OwnedCard owned)
	{
		for (Map.Entry<String, String> entry : state.getLoadout().entrySet())
		{
			if (owned.getUuid().equals(entry.getValue()))
			{
				try
				{
					return GearSlot.valueOf(entry.getKey());
				}
				catch (IllegalArgumentException e)
				{
					return null;
				}
			}
		}
		return null;
	}

	private void applyFilters()
	{
		GearSlot slotSel = slotFilter.getSelectedIndex() <= 0 ? null
			: GearSlot.values()[slotFilter.getSelectedIndex() - 1];
		Rarity raritySel = rarityFilter.getSelectedIndex() <= 0 ? null
			: Rarity.values()[rarityFilter.getSelectedIndex() - 1];
		Variant variantSel = variantFilter.getSelectedIndex() == 1 ? Variant.NORMAL
			: variantFilter.getSelectedIndex() == 2 ? Variant.SHINY : null;
		boolean ownedOnly = ownedOnlyBox.isSelected();
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();

		List<Entry> entries = new ArrayList<>();
		for (CardDefinition card : sorted)
		{
			if (slotSel != null && card.getSlot() != slotSel)
			{
				continue;
			}
			if (raritySel != null && card.getRarity() != raritySel)
			{
				continue;
			}
			Variant ownedVariant = ownedVariantByCardId.get(card.getCardId());
			boolean owned = ownedVariant != null;
			if (ownedOnly && !owned)
			{
				continue;
			}
			if (variantSel != null && (!owned || ownedVariant != variantSel))
			{
				continue;
			}
			if (!query.isEmpty() && !card.getName().toLowerCase().contains(query))
			{
				continue;
			}
			entries.add(new Entry(card, owned, owned ? ownedVariant : Variant.NORMAL,
				owned ? serviceByCardId.getOrDefault(card.getCardId(), 0) : 0));
		}
		grid.setEntries(entries);
		revalidate();
		repaint();
	}

	private static JComboBox<String> comboOf(String allLabel, String[] values)
	{
		String[] items = new String[values.length + 1];
		items[0] = allLabel;
		System.arraycopy(values, 0, items, 1, values.length);
		JComboBox<String> combo = new JComboBox<>(items);
		GachamanPanel.styleCombo(combo);
		return combo;
	}

	private static String[] slotNames()
	{
		GearSlot[] slots = GearSlot.values();
		String[] names = new String[slots.length];
		for (int i = 0; i < slots.length; i++)
		{
			names[i] = slots[i].getDisplayName();
		}
		return names;
	}

	private static String[] rarityNames()
	{
		Rarity[] rarities = Rarity.values();
		String[] names = new String[rarities.length];
		for (int i = 0; i < rarities.length; i++)
		{
			names[i] = rarities[i].getDisplayName();
		}
		return names;
	}

	// --- Grid entry ---

	private static final class Entry
	{
		private final CardDefinition card;
		private final boolean owned;
		private final Variant variant;
		private final int serviceKills;

		Entry(CardDefinition card, boolean owned, Variant variant, int serviceKills)
		{
			this.card = card;
			this.owned = owned;
			this.variant = variant;
			this.serviceKills = serviceKills;
		}
	}

	private static String keyOf(Entry entry)
	{
		// the Service Record is baked into the raster and the LRU is never
		// cleared on rebuild, so it must key the cache or thumbnails go stale
		return entry.card.getCardId() + (entry.owned ? ":o:" : ":u:") + entry.variant
			+ ":" + entry.serviceKills;
	}

	/** True for cells the live (non-rasterized) effect pass animates. */
	private static boolean hasLiveEffect(Entry entry)
	{
		return entry.owned
			&& (entry.variant == Variant.SHINY
			|| entry.variant == Variant.HOLOGRAM
			|| entry.card.getRarity().atLeast(Rarity.EPIC));
	}

	/** Cheap scan (item sprites are 36x32) for any non-transparent pixel. */
	private static boolean hasVisiblePixel(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Two-state checkbox icon: a dark rounded square with a light border,
	 * plus a crisp white 2px check mark when selected. The stock LAF check
	 * mark was nearly invisible on the dark panel.
	 */
	/** Static 4-point sparkle in pale violet — the stardust counter's icon. */
	private static final class StardustIcon implements Icon
	{
		private static final int SIZE = 13;
		private static final Color SPARKLE = new Color(190, 170, 255);

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int cx = x + SIZE / 2;
				int cy = y + SIZE / 2;
				g2.setColor(SPARKLE);
				g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.drawLine(cx, y + 2, cx, y + SIZE - 2);
				g2.drawLine(x + 2, cy, x + SIZE - 2, cy);
				g2.setStroke(new BasicStroke(1f));
				g2.drawLine(cx - 2, cy - 2, cx + 2, cy + 2);
				g2.drawLine(cx - 2, cy + 2, cx + 2, cy - 2);
				g2.setColor(Color.WHITE);
				g2.fillOval(cx - 1, cy - 1, 2, 2);
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}
	}

	private static final class CheckboxIcon implements Icon
	{
		private static final int SIZE = 13;
		private static final BasicStroke CHECK_STROKE =
			new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

		private final boolean selected;

		CheckboxIcon(boolean selected)
		{
			this.selected = selected;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fillRoundRect(x, y, SIZE, SIZE, 4, 4);
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 4, 4);
				if (selected)
				{
					g2.setColor(Color.WHITE);
					g2.setStroke(CHECK_STROKE);
					g2.drawLine(x + 3, y + 7, x + 5, y + 9);
					g2.drawLine(x + 5, y + 9, x + 10, y + 3);
				}
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}
	}

	// --- Grid panel ---

	private final class GridPanel extends JPanel implements Scrollable
	{
		private List<Entry> entries = Collections.emptyList();
		private final LinkedHashMap<String, BufferedImage> cache =
			new LinkedHashMap<String, BufferedImage>(64, 0.75f, true)
			{
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
				{
					return size() > CACHE_MAX;
				}
			};
		private boolean workerRunning;
		private final AtomicBoolean repaintScheduled = new AtomicBoolean();
		/**
		 * Per-cell raster version. A raster snapshots the version before
		 * fetching art; a sprite-load hook firing later bumps it, so the
		 * stale raster is dropped at publish time (or evicted from the
		 * cache if it already landed) and the cell re-rasters.
		 */
		private final Map<String, Integer> rasterVersion = new ConcurrentHashMap<>();
		/** Item ids whose sprite has finished loading (load hook fired). */
		private final Set<Integer> loadedItemIds = ConcurrentHashMap.newKeySet();
		/**
		 * Card id -> item id whose sprite actually drew visible pixels.
		 * Some odd item variants rasterize fully transparent; once a good
		 * sprite in the variant group is found it is remembered here.
		 */
		private final Map<Integer, Integer> resolvedArtIds = new ConcurrentHashMap<>();
		/**
		 * Drives the live shimmer pass; runs only while the album tab is
		 * showing AND at least one effect cell is inside the viewport.
		 */
		private final Timer effectTimer = new Timer(EFFECT_TICK_MS, e -> tickEffects());

		GridPanel()
		{
			setOpaque(false);
			setToolTipText("");
		}

		private void tickEffects()
		{
			if (!isShowing() || !hasVisibleEffectCells())
			{
				effectTimer.stop();
				return;
			}
			Rectangle vis = getVisibleRect();
			repaint(vis.x, vis.y, vis.width, vis.height);
		}

		private boolean hasVisibleEffectCells()
		{
			if (entries.isEmpty())
			{
				return false;
			}
			Rectangle vis = getVisibleRect();
			if (vis.isEmpty())
			{
				return false;
			}
			int c = cols();
			int rowH = THUMB_H + GAP;
			int firstRow = Math.max(0, (vis.y - GAP) / rowH);
			int lastRow = Math.max(firstRow, (vis.y + vis.height) / rowH);
			for (int row = firstRow; row <= lastRow; row++)
			{
				for (int col = 0; col < c; col++)
				{
					int index = row * c + col;
					if (index >= entries.size())
					{
						return false;
					}
					if (hasLiveEffect(entries.get(index)))
					{
						return true;
					}
				}
			}
			return false;
		}

		void setEntries(List<Entry> next)
		{
			entries = next == null ? Collections.emptyList() : next;
			revalidate();
			repaint();
		}

		private int cols()
		{
			int width = Math.max(getWidth(), THUMB_W);
			return Math.max(1, (width + GAP) / (THUMB_W + GAP));
		}

		@Override
		public Dimension getPreferredSize()
		{
			int c = cols();
			int rows = entries.isEmpty() ? 0 : (entries.size() + c - 1) / c;
			return new Dimension(THUMB_W, Math.max(rows * (THUMB_H + GAP) + GAP, 40));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				if (entries.isEmpty())
				{
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.setFont(FontManager.getRunescapeSmallFont());
					g2.drawString("No cards match the filters.", 8, 20);
					return;
				}
				long now = System.currentTimeMillis();
				Rectangle clip = g2.getClipBounds();
				if (clip == null)
				{
					clip = new Rectangle(0, 0, getWidth(), getHeight());
				}
				int c = cols();
				int cellW = getWidth() / c;
				int rowH = THUMB_H + GAP;
				int firstRow = Math.max(0, (clip.y - GAP) / rowH);
				int lastRow = Math.max(firstRow, (clip.y + clip.height) / rowH);
				List<Entry> missing = new ArrayList<>();
				for (int row = firstRow; row <= lastRow; row++)
				{
					for (int col = 0; col < c; col++)
					{
						int index = row * c + col;
						if (index >= entries.size())
						{
							break;
						}
						Entry entry = entries.get(index);
						int x = col * cellW + (cellW - THUMB_W) / 2;
						int y = GAP + row * rowH;
						BufferedImage thumb = cache.get(keyOf(entry));
						if (thumb != null)
						{
							g2.drawImage(thumb, x, y, null);
							if (hasLiveEffect(entry))
							{
								paintLiveEffect(g2, x, y, entry, now);
							}
						}
						else
						{
							g2.setColor(PLACEHOLDER);
							g2.fillRoundRect(x, y, THUMB_W, THUMB_H, 12, 12);
							if (missing.size() < BATCH_MAX)
							{
								missing.add(entry);
							}
						}
					}
				}
				if (!missing.isEmpty())
				{
					scheduleRaster(missing);
				}
				updateEffectTimer();
			}
			finally
			{
				g2.dispose();
			}
		}

		private void updateEffectTimer()
		{
			if (isShowing() && hasVisibleEffectCells())
			{
				if (!effectTimer.isRunning())
				{
					effectTimer.start();
				}
			}
			else if (effectTimer.isRunning())
			{
				effectTimer.stop();
			}
		}

		/**
		 * Live effect pass painted over the static base thumbnail: a diagonal
		 * sheen band sweeping corner-to-corner (shiny and EPIC+), a cycling
		 * prismatic border (shiny) and drifting scanlines (hologram). Cheap
		 * gradient/line fills clipped to the cell — never re-rasterization.
		 */
		private void paintLiveEffect(Graphics2D g, int x, int y, Entry entry, long now)
		{
			boolean shiny = entry.variant == Variant.SHINY;
			boolean holo = entry.variant == Variant.HOLOGRAM;
			RoundRectangle2D cell = new RoundRectangle2D.Float(
				x, y, THUMB_W, THUMB_H, CELL_ARC, CELL_ARC);

			if (shiny || entry.card.getRarity().atLeast(Rarity.EPIC))
			{
				Graphics2D gs = (Graphics2D) g.create();
				gs.setClip(cell);
				gs.rotate(SHEEN_ANGLE_RAD, x + THUMB_W / 2.0, y + THUMB_H / 2.0);
				float phase = (now % SHEEN_PERIOD_MS) / (float) SHEEN_PERIOD_MS;
				int span = THUMB_W + THUMB_H;
				int bandW = THUMB_W / 2;
				int bx = x - span + (int) (phase * span * 2);
				Color tint = shiny ? shinySheenColor(now) : SHEEN_WHITE;
				gs.setPaint(new GradientPaint(bx, 0, SHEEN_CLEAR, bx + bandW / 2f, 0, tint, true));
				gs.fillRect(bx, y - THUMB_H, bandW, THUMB_H * 3);
				gs.dispose();
			}

			if (holo)
			{
				Graphics2D gs = (Graphics2D) g.create();
				gs.setClip(cell);
				gs.setColor(HOLO_SCAN);
				int offset = (int) ((now / 90) % 6);
				for (int sy = y + offset; sy < y + THUMB_H; sy += 6)
				{
					gs.drawLine(x, sy, x + THUMB_W, sy);
				}
				gs.dispose();
			}

			if (shiny || holo)
			{
				Graphics2D gs = (Graphics2D) g.create();
				gs.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				gs.setColor(shiny ? CardRenderer.prismaticColor(now, 0) : HOLO_EDGE);
				gs.setStroke(entry.card.getRarity().atLeast(Rarity.RARE) ? BORDER_THICK : BORDER_THIN);
				gs.draw(cell);
				gs.dispose();
			}
		}

		private Color shinySheenColor(long now)
		{
			Color c = CardRenderer.prismaticColor(now, 60);
			return new Color(c.getRed(), c.getGreen(), c.getBlue(), 64);
		}

		private void scheduleRaster(List<Entry> batch)
		{
			if (workerRunning)
			{
				return;
			}
			workerRunning = true;
			final List<Entry> copy = new ArrayList<>(batch);
			new SwingWorker<Void, Object[]>()
			{
				@Override
				protected Void doInBackground()
				{
					for (Entry entry : copy)
					{
						try
						{
							String key = keyOf(entry);
							int version = versionOf(key);
							publish(new Object[]{key, rasterize(entry, key), version});
						}
						catch (Exception e)
						{
							log.debug("album thumbnail raster failed", e);
						}
					}
					return null;
				}

				@Override
				protected void process(List<Object[]> chunks)
				{
					for (Object[] chunk : chunks)
					{
						String key = (String) chunk[0];
						if (versionOf(key) == (Integer) chunk[2])
						{
							cache.put(key, (BufferedImage) chunk[1]);
						}
						// else: a sprite finished loading mid-raster; drop the
						// stale raster and let the repaint re-request the cell
					}
					scheduleRepaint();
				}

				@Override
				protected void done()
				{
					workerRunning = false;
					// repaint requests the next missing batch, if any
					scheduleRepaint();
				}
			}.execute();
		}

		private void scheduleRepaint()
		{
			if (!repaintScheduled.compareAndSet(false, true))
			{
				return;
			}
			SwingUtilities.invokeLater(() -> {
				repaintScheduled.set(false);
				repaint();
			});
		}

		private int versionOf(String key)
		{
			return rasterVersion.getOrDefault(key, 0);
		}

		/**
		 * Load hook attached to every art fetch. AsyncBufferedImage.onLoaded on
		 * an ALREADY-loaded image does not run synchronously — it re-queues via
		 * clientThread.invokeLater — so an in-fetch flag can never suppress it.
		 * The gate is the load-state TRANSITION instead: only the FIRST time we
		 * learn a sprite finished loading may a cell be invalidated. Repeat
		 * fires (every later fetch of the same loaded sprite) are no-ops, which
		 * is what makes the raster pipeline converge instead of evicting its
		 * own output forever.
		 */
		private Runnable artLoadHook(String key, int itemId)
		{
			return () ->
			{
				if (!loadedItemIds.add(itemId))
				{
					return; // already knew — the raster saw loaded pixels
				}
				rasterVersion.merge(key, 1, Integer::sum);
				SwingUtilities.invokeLater(() -> {
					cache.remove(key);
					scheduleRepaint();
				});
			};
		}

		@Nullable
		private BufferedImage fetchArt(String key, int itemId)
		{
			return cardImageService.itemImage(itemId, artLoadHook(key, itemId));
		}

		/**
		 * Card art is normally the card's lowest item id, but some odd item
		 * variants rasterize fully transparent. Walk the variant group in id
		 * order until a sprite shows visible pixels (a cheap 36x32 scan),
		 * remembering the winner. A sprite that is merely still loading is
		 * used as-is: its load hook re-rasters the cell once it arrives.
		 */
		@Nullable
		private BufferedImage resolveArt(Entry entry, String key)
		{
			int cardId = entry.card.getCardId();
			Integer resolved = resolvedArtIds.get(cardId);
			if (resolved != null)
			{
				return fetchArt(key, resolved);
			}
			Set<Integer> ids = entry.card.getItemIds();
			List<Integer> candidates = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
			if (!candidates.contains(cardId))
			{
				candidates.add(cardId);
			}
			Collections.sort(candidates);
			BufferedImage fallback = null;
			for (int itemId : candidates)
			{
				BufferedImage art = fetchArt(key, itemId);
				if (art == null)
				{
					continue;
				}
				if (hasVisiblePixel(art))
				{
					resolvedArtIds.put(cardId, itemId);
					return art;
				}
				if (!loadedItemIds.contains(itemId))
				{
					// still loading, so blank is inconclusive; draw with it
					// and let its load hook re-raster this cell
					fallback = art;
					break;
				}
				// loaded but fully transparent: try the next variant id
			}
			return fallback;
		}

		private BufferedImage rasterize(Entry entry, String key)
		{
			BufferedImage art = resolveArt(entry, key);
			BufferedImage img = new BufferedImage(THUMB_W, THUMB_H, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = img.createGraphics();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				// base raster is always the NORMAL face: time-varying variant
				// effects (shiny bands, holo scanlines, prismatic border) are
				// painted live in paintComponent instead of being baked in
				CardRenderer.CardView view = CardRenderer.CardView.builder()
					.name(entry.owned ? entry.card.getName() : UNKNOWN_NAME)
					.rarity(entry.card.getRarity())
					.variant(Variant.NORMAL)
					.art(art)
					.subtitle(entry.owned ? entry.card.getSlot().getDisplayName() : null)
					.killsServed(entry.serviceKills)
					.build();
				CardRenderer.drawFace(g2, 0, 0, THUMB_W, THUMB_H, view, STATIC_TIME_MS);
			}
			finally
			{
				g2.dispose();
			}
			if (!entry.owned)
			{
				RescaleOp darken = new RescaleOp(
					new float[]{0.16f, 0.16f, 0.20f, 1f}, new float[4], null);
				img = darken.filter(img, null);
			}
			return img;
		}

		@Override
		public String getToolTipText(MouseEvent event)
		{
			if (event == null || entries.isEmpty())
			{
				return null;
			}
			int c = cols();
			int cellW = Math.max(1, getWidth() / c);
			int rowH = THUMB_H + GAP;
			int col = event.getX() / cellW;
			int row = Math.max(0, (event.getY() - GAP) / rowH);
			int index = row * c + col;
			if (col >= c || index < 0 || index >= entries.size())
			{
				return null;
			}
			Entry entry = entries.get(index);
			if (!entry.owned)
			{
				return "Undiscovered card";
			}
			String variantText = entry.variant == Variant.SHINY ? " — Shiny" : "";
			// "present for", never "killed": the record counts kills the card was
			// ASSIGNED TO THE LOADOUT for, on-task or not, tainted or not
			String service = entry.serviceKills > 0
				? " — present for " + QuantityFormatter.formatNumber(entry.serviceKills) + " kills"
				: "";
			return entry.card.getName() + " — " + entry.card.getRarity().getDisplayName()
				+ variantText + " — " + entry.card.getSlot().getDisplayName() + service;
		}

		// --- Scrollable ---

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return THUMB_H / 3;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
