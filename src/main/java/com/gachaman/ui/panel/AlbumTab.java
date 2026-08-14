package com.gachaman.ui.panel;

import javax.swing.Timer;
import java.util.List;
import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import com.gachaman.ui.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.annotation.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

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
public class AlbumTab extends JPanel {
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
	/** Wiki badge in each owned card's top-right corner. */
	private static final int WIKI_BADGE = 13;
	private static final Color WIKI_BADGE_BG = new Color(0, 0, 0, 165);
	private static final Color WIKI_BADGE_FG = new Color(240, 200, 90);
	private static final Font WIKI_BADGE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);

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
	private final JPanel holoPanel = box(BoxLayout.Y_AXIS);
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
		CardImageService cardImageService) {
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.cardImageService = cardImageService;

		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		// Arrays.stream over values() keeps enum DECLARATION order, which is the
		// order applyFilters depends on when it maps selectedIndex-1 back through
		// GearSlot.values() / Rarity.values(). This replaced two named helpers
		// holding byte-identical index loops; denser to read, but the Plugin Hub
		// token budget is the binding constraint and the helpers bought nothing.
		slotFilter = comboOf("All slots",
			Arrays.stream(GearSlot.values()).map(GearSlot::getDisplayName).toArray(String[]::new));
		rarityFilter = comboOf("All rarities",
			Arrays.stream(Rarity.values()).map(Rarity::getDisplayName).toArray(String[]::new));
		variantFilter = comboOf("All variants", new String[]{"Normal", "Shiny"});

		JPanel north = box(BoxLayout.Y_AXIS);

		collectedLabel.setFont(FontManager.getRunescapeBoldFont());
		collectedLabel.setForeground(Color.WHITE);
		JPanel headerRow = box(BoxLayout.X_AXIS);
		headerRow.add(collectedLabel);
		headerRow.add(Box.createHorizontalGlue());
		stardustLabel.setFont(FontManager.getRunescapeSmallFont());
		stardustLabel.setIcon(icon("stardust"));
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
		ownedOnlyBox.setIcon(icon("checkbox-off"));
		ownedOnlyBox.setSelectedIcon(icon("checkbox-on"));
		filterGrid.add(ownedOnlyBox);
		north.add(filterGrid);
		north.add(Box.createVerticalStrut(4));

		sortOrderButton.setFont(FontManager.getRunescapeSmallFont());
		sortOrderButton.setFocusable(false);
		sortOrderButton.setFocusPainted(false);
		sortOrderButton.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		sortOrderButton.setForeground(Color.WHITE);
		sortOrderButton.setToolTipText("Toggle rarity sort order");
		// Sized to the LONGER of the two captions it toggles between, measured
		// rather than guessed: the button is pinned to a fixed width so the
		// search field beside it can take the rest, and the hardcoded 94 was
		// narrower than "Legendary first" plus the look-and-feel's own insets —
		// so the button ellipsised the moment you pressed it.
		Insets buttonInsets = sortOrderButton.getInsets();
		FontMetrics sortMetrics =
			sortOrderButton.getFontMetrics(FontManager.getRunescapeSmallFont());
		int sortWidth = Math.max(sortMetrics.stringWidth("Common first"),
			sortMetrics.stringWidth("Legendary first"))
			+ buttonInsets.left + buttonInsets.right + 8;
		Dimension sortSize = new Dimension(sortWidth, 24);
		sortOrderButton.setPreferredSize(sortSize);
		sortOrderButton.setMinimumSize(sortSize);
		sortOrderButton.setMaximumSize(sortSize);

		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(2, 5, 2, 5)));
		searchField.setToolTipText("Search by card name");
		JPanel searchRow = box(BoxLayout.X_AXIS);
		searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		searchRow.add(searchField);
		searchRow.add(Box.createHorizontalStrut(4));
		searchRow.add(sortOrderButton);
		north.add(searchRow);
		north.add(Box.createVerticalStrut(5));

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
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				applyFilters();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				applyFilters();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				applyFilters();
			}
		});
	}

	void rebuild() {
		GachaState state = stateService.get();
		ensureSorted();

		Map<Integer, Variant> ownedVariants = new HashMap<>();
		if (state != null) {
			for (OwnedCard owned : state.getOwnedCards()) {
				if (owned.isHologram()) {
					continue;
				}
				Variant existing = ownedVariants.get(owned.getCardId());
				if (existing == null || (existing == Variant.NORMAL && owned.getVariant() == Variant.SHINY)) {
					ownedVariants.put(owned.getCardId(), owned.getVariant());
				}
			}
		}
		ownedVariantByCardId = ownedVariants;
		serviceByCardId = ServiceRecordService.bestByCardId(
			state == null ? null : state.getOwnedCards());

		updateHeader(state);
		rebuildHoloSection(state);
		applyFilters();
	}

	private void ensureSorted() {
		if (!cardDatabase.isReady()) {
			sorted = Collections.emptyList();
			sortedSource = null;
			return;
		}
		Map<Integer, CardDefinition> source = cardDatabase.all();
		if (source == sortedSource && sortedDescending == sortDescending) {
			return;
		}
		final boolean descending = sortDescending;
		List<CardDefinition> cards = new ArrayList<>(source.values());
		// rarity ascending (Common first) by default, toggleable to
		// descending; name A-Z within a rarity either way
		cards.sort((a, b) -> {
			int cmp = Integer.compare(a.getRarity().ordinal(), b.getRarity().ordinal());
			if (cmp != 0) {
				return descending ? -cmp : cmp;
			}
			return a.getName().compareToIgnoreCase(b.getName());
		});
		sorted = cards;
		sortedSource = source;
		sortedDescending = descending;
	}

	/**
	 * Takes the state rather than re-reading it: the only caller is rebuild(),
	 * which already holds the same snapshot and hands it to rebuildHoloSection
	 * two lines later. Nothing between the two runs off the EDT or mutates
	 * state, so this is the same object either way — and the asymmetry of one
	 * sibling taking the state while the other silently re-fetched it is gone.
	 */
	private void updateHeader(@Nullable GachaState state) {
		int total = sorted.size();
		int ownedCount = 0;
		Map<Rarity, Integer> ownedByRarity = new EnumMap<>(Rarity.class);
		Map<Rarity, Integer> totalByRarity = new EnumMap<>(Rarity.class);
		for (CardDefinition card : sorted) {
			totalByRarity.merge(card.getRarity(), 1, Integer::sum);
			if (ownedVariantByCardId.containsKey(card.getCardId())) {
				ownedCount++;
				ownedByRarity.merge(card.getRarity(), 1, Integer::sum);
			}
		}
		collectedLabel.setText("Collected: " + QuantityFormatter.formatNumber(ownedCount)
			+ " / " + QuantityFormatter.formatNumber(total));

		int dust = state == null ? 0 : state.getStardust();
		boolean armed = state != null && state.isStardustBlessArmed();
		stardustLabel.setText(armed ? "Blessed!" : dust + "/" + Tuning.STARDUST_REQUIRED);
		stardustLabel.setForeground(armed ? new Color(230, 190, 80) : new Color(190, 170, 255));
		stardustLabel.setToolTipText(armed
			? "Stardust blessing armed — the next chest rolls every card's shiny twice"
			: "Stardust " + dust + "/" + Tuning.STARDUST_REQUIRED
				+ " — shiny near-misses bank stardust; a full bank blesses your next chest");

		StringBuilder html = new StringBuilder("<html>");
		boolean first = true;
		for (Rarity rarity : Rarity.values()) {
			if (!first) {
				html.append("&nbsp;&nbsp;");
			}
			first = false;
			// #rrggbb and rgb(r,g,b) parse to the same Color in Swing's CSS, and
			// GachamanPanel.hex is what Dossier, Patrons and Timeline already use
			html.append("<span style='color:").append(GachamanPanel.hex(rarity.getColor()))
				.append("'>")
				.append(rarity.getDisplayName().charAt(0)).append(':')
				.append(ownedByRarity.getOrDefault(rarity, 0)).append('/')
				.append(totalByRarity.getOrDefault(rarity, 0)).append("</span>");
		}
		html.append("</html>");
		rarityCountsLabel.setText(html.toString());
	}

	private void rebuildHoloSection(@Nullable GachaState state) {
		holoPanel.removeAll();
		if (state == null || !cardDatabase.isReady()) {
			return;
		}
		Set<String> seenTiers = new LinkedHashSet<>();
		List<OwnedCard> holos = new ArrayList<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (owned.isHologram() && seenTiers.add(owned.getTierKey())) {
				holos.add(owned);
			}
		}
		if (holos.isEmpty()) {
			return;
		}
		JLabel title = new JLabel("Holograms");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(new Color(120, 220, 255));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		holoPanel.add(title);
		for (OwnedCard owned : holos) {
			HologramDefinition def = cardDatabase.holograms().get(owned.getTierKey());
			String name = def != null ? def.getName() : "Hologram (" + owned.getTierKey() + ")";
			GearSlot assigned = assignedSlotOf(state, owned);
			int served = owned.getKillsServed();
			// Holograms never enter the grid, so this list is their only album
			// surface for the Service Record — and on one line the whole row ran
			// to ~345px in a 230px column, so the Service Record, the last thing
			// on it, was always the thing that got cut. Name, then the facts
			// indented under it; the tooltip carries the lot.
			String full = name + " — tier " + owned.getTierKey()
				+ (assigned != null ? " — " + assigned.getDisplayName() : "")
				+ (served > 0 ? " — " + servedText(served) : "");
			JLabel label = GachamanPanel.line(name,
				def != null ? def.getRarity().getColor() : ColorScheme.LIGHT_GRAY_COLOR,
				FontManager.getRunescapeSmallFont());
			label.setToolTipText(full);
			holoPanel.add(label);

			JLabel detail = GachamanPanel.smallLine("    tier " + owned.getTierKey()
					+ (assigned != null ? "  ·  " + assigned.getDisplayName() : ""),
				ColorScheme.MEDIUM_GRAY_COLOR);
			detail.setToolTipText(full);
			holoPanel.add(detail);

			if (served > 0) {
				JLabel service = GachamanPanel.smallLine("    " + servedText(served),
					ColorScheme.MEDIUM_GRAY_COLOR);
				service.setToolTipText(full);
				holoPanel.add(service);
			}
		}
		holoPanel.add(Box.createVerticalStrut(4));
	}

	@Nullable
	private static GearSlot assignedSlotOf(GachaState state, OwnedCard owned) {
		for (Map.Entry<String, String> entry : state.getLoadout().entrySet()) {
			if (owned.getUuid().equals(entry.getValue())) {
				try {
					return GearSlot.valueOf(entry.getKey());
				}
				catch (IllegalArgumentException e) {
					return null;
				}
			}
		}
		return null;
	}

	private void applyFilters() {
		GearSlot slotSel = slotFilter.getSelectedIndex() <= 0 ? null
			: GearSlot.values()[slotFilter.getSelectedIndex() - 1];
		Rarity raritySel = rarityFilter.getSelectedIndex() <= 0 ? null
			: Rarity.values()[rarityFilter.getSelectedIndex() - 1];
		// same values()[index - 1] idiom as the two filters above: the combo is
		// ["All variants", "Normal", "Shiny"] and Variant is [NORMAL, SHINY,
		// HOLOGRAM], so index 3 (HOLOGRAM) is unreachable — the combo has three
		// items — and index 0 or -1 falls through to "no variant filter"
		Variant variantSel = variantFilter.getSelectedIndex() <= 0 ? null
			: Variant.values()[variantFilter.getSelectedIndex() - 1];
		boolean ownedOnly = ownedOnlyBox.isSelected();
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();

		List<Entry> entries = new ArrayList<>();
		for (CardDefinition card : sorted) {
			if (slotSel != null && card.getSlot() != slotSel) {
				continue;
			}
			if (raritySel != null && card.getRarity() != raritySel) {
				continue;
			}
			Variant ownedVariant = ownedVariantByCardId.get(card.getCardId());
			boolean owned = ownedVariant != null;
			if (ownedOnly && !owned) {
				continue;
			}
			if (variantSel != null && (!owned || ownedVariant != variantSel)) {
				continue;
			}
			if (!query.isEmpty() && !card.getName().toLowerCase().contains(query)) {
				continue;
			}
			entries.add(new Entry(card, owned, owned ? ownedVariant : Variant.NORMAL,
				owned ? serviceByCardId.getOrDefault(card.getCardId(), 0) : 0));
		}
		grid.setEntries(entries);
		revalidate();
		repaint();
	}

	private static JComboBox<String> comboOf(String allLabel, String[] values) {
		String[] items = new String[values.length + 1];
		items[0] = allLabel;
		System.arraycopy(values, 0, items, 1, values.length);
		JComboBox<String> combo = new JComboBox<>(items);
		GachamanPanel.styleCombo(combo);
		return combo;
	}

	/**
	 * The transparent left-aligned BoxLayout panel this file assembled by hand
	 * four times over. LEFT_ALIGNMENT is set unconditionally: every one of these
	 * panels either sits in a BoxLayout parent that honours it, or — in the case
	 * of the north container — is added with BorderLayout.NORTH, which ignores
	 * alignmentX outright, so the one extra call is inert rather than a change.
	 */
	private static JPanel box(int axis) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, axis));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/**
	 * The Service Record phrase, shared by the hologram tooltip, the hologram
	 * detail line and the grid tooltip. ServiceRecordService's javadoc pins the
	 * wording — "present for N kills", never "killed N", because the record
	 * counts kills the card was ASSIGNED TO THE LOADOUT for, on-task or not —
	 * so one place to say it is also one place to keep saying it correctly.
	 */
	private static String servedText(int kills) {
		return "present for " + QuantityFormatter.formatNumber(kills)
			+ (kills == 1 ? " kill" : " kills");
	}

	// --- Grid entry ---

	@RequiredArgsConstructor
	private static final class Entry {
		private final CardDefinition card;
		private final boolean owned;
		private final Variant variant;
		private final int serviceKills;
	}

	private static String keyOf(Entry entry) {
		// the Service Record is baked into the raster and the LRU is never
		// cleared on rebuild, so it must key the cache or thumbnails go stale.
		// The variant deliberately does NOT: rasterize() hardcodes the NORMAL
		// face and the variant effects are painted live, so keying on it only
		// re-rasterized an identical image when a card upgraded to shiny. The
		// ":o:"/":u:" separator keeps ids and kill counts unambiguous.
		return entry.card.getCardId() + (entry.owned ? ":o:" : ":u:") + entry.serviceKills;
	}

	/** True for cells the live (non-rasterized) effect pass animates. */
	private static boolean hasLiveEffect(Entry entry) {
		return entry.owned
			&& (entry.variant == Variant.SHINY
			|| entry.variant == Variant.HOLOGRAM
			|| entry.card.getRarity().atLeast(Rarity.EPIC));
	}

	/** Cheap scan (item sprites are 36x32) for any non-transparent pixel. */
	private static boolean hasVisiblePixel(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if ((img.getRGB(x, y) >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A flat 13x13 icon authored by com.gachaman.tools.IconArt, in test scope.
	 *
	 * <p>The stardust sparkle and the two "Owned only" checkbox states used to be
	 * hand-painted Icon classes right here. Every one of them is a FIXED drawing
	 * — no animation, no tint that varies with state (the "Blessed!" case
	 * recolours the LABEL, not the sparkle) — so painting them on each panel
	 * build was re-deriving a constant, exactly the case IconArt already exists
	 * for. AlbumIconBakeTest keeps the old paintIcon bodies and renders both ways
	 * onto the same backgrounds: no pixel moved, and the only drift was ±1 on one
	 * channel of eight pixels over the dark panel — the extra rounding step a
	 * composited layer costs, since ARGB is stored non-premultiplied.
	 *
	 * <p>GachamanPanel has a character-identical loader but keeps it private;
	 * duplicating four lines here is deliberate, so this change stays inside one
	 * file. Collapsing the two into one package-private helper is a later pass.
	 */
	private static ImageIcon icon(String name) {
		return new ImageIcon(ImageUtil.loadImageResource(
			AlbumTab.class, "/com/gachaman/ui/" + name + ".png"));
	}

	// --- Grid panel ---

	private final class GridPanel extends JPanel implements Scrollable {
		private List<Entry> entries = Collections.emptyList();
		private final LinkedHashMap<String, BufferedImage> cache =
			new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
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

		GridPanel() {
			setOpaque(false);
			setToolTipText("");
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					Entry entry = entryAt(e.getX(), e.getY());
					// owned only: an undiscovered card shows "???" and has no
					// name to look up, so its badge is never drawn either
					if (entry != null && entry.owned && onWikiBadge(e.getX(), e.getY())) {
						LinkBrowser.browse("https://oldschool.runescape.wiki/w/"
							+ entry.card.getName().replace(' ', '_'));
					}
				}
			});
			addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					Entry entry = entryAt(e.getX(), e.getY());
					boolean onBadge = entry != null && entry.owned
						&& onWikiBadge(e.getX(), e.getY());
					setCursor(Cursor.getPredefinedCursor(
						onBadge ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
				}
			});
		}

		private void tickEffects() {
			if (!isShowing() || !hasVisibleEffectCells()) {
				effectTimer.stop();
				return;
			}
			Rectangle vis = getVisibleRect();
			repaint(vis.x, vis.y, vis.width, vis.height);
		}

		private boolean hasVisibleEffectCells() {
			Rectangle vis = getVisibleRect();
			if (entries.isEmpty() || vis.isEmpty()) {
				return false;
			}
			int c = cols();
			int rowH = THUMB_H + GAP;
			int firstRow = Math.max(0, (vis.y - GAP) / rowH);
			// This was a nested row/col walk, but index = row * c + col rises by
			// exactly one every step, so the visible cells are one contiguous run
			// of the flat list. Math.min against entries.size() is the old inner
			// "past the last entry, give up" bail; keeping Math.max(firstRow, ...)
			// preserves the old lastRow clamp for a negative vis.y, which cannot
			// happen through a JViewport but costs nothing to honour.
			int last = Math.min(entries.size(),
				(Math.max(firstRow, (vis.y + vis.height) / rowH) + 1) * c);
			for (int i = firstRow * c; i < last; i++) {
				if (hasLiveEffect(entries.get(i))) {
					return true;
				}
			}
			return false;
		}

		void setEntries(List<Entry> next) {
			entries = next == null ? Collections.emptyList() : next;
			revalidate();
			repaint();
		}

		private int cols() {
			int width = Math.max(getWidth(), THUMB_W);
			return Math.max(1, (width + GAP) / (THUMB_W + GAP));
		}

		@Override
		public Dimension getPreferredSize() {
			int c = cols();
			int rows = entries.isEmpty() ? 0 : (entries.size() + c - 1) / c;
			return new Dimension(THUMB_W, Math.max(rows * (THUMB_H + GAP) + GAP, 40));
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				if (entries.isEmpty()) {
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.setFont(FontManager.getRunescapeSmallFont());
					g2.drawString("No cards match the filters.", 8, 20);
					return;
				}
				long now = System.currentTimeMillis();
				Rectangle clip = g2.getClipBounds();
				if (clip == null) {
					clip = new Rectangle(0, 0, getWidth(), getHeight());
				}
				int c = cols();
				int cellW = getWidth() / c;
				int rowH = THUMB_H + GAP;
				int firstRow = Math.max(0, (clip.y - GAP) / rowH);
				int lastRow = Math.max(firstRow, (clip.y + clip.height) / rowH);
				List<Entry> missing = new ArrayList<>();
				for (int row = firstRow; row <= lastRow; row++) {
					for (int col = 0; col < c; col++) {
						int index = row * c + col;
						if (index >= entries.size()) {
							break;
						}
						Entry entry = entries.get(index);
						int x = col * cellW + (cellW - THUMB_W) / 2;
						int y = GAP + row * rowH;
						BufferedImage thumb = cache.get(keyOf(entry));
						if (thumb != null) {
							g2.drawImage(thumb, x, y, null);
							if (hasLiveEffect(entry)) {
								paintLiveEffect(g2, x, y, entry, now);
							}
							if (entry.owned) {
								paintWikiBadge(g2, x, y);
							}
						}
						else {
							g2.setColor(PLACEHOLDER);
							g2.fillRoundRect(x, y, THUMB_W, THUMB_H, 12, 12);
							if (missing.size() < BATCH_MAX) {
								missing.add(entry);
							}
						}
					}
				}
				if (!missing.isEmpty()) {
					scheduleRaster(missing);
				}
				updateEffectTimer();
			}
			finally {
				g2.dispose();
			}
		}

		private void updateEffectTimer() {
			if (isShowing() && hasVisibleEffectCells()) {
				if (!effectTimer.isRunning()) {
					effectTimer.start();
				}
			}
			else if (effectTimer.isRunning()) {
				effectTimer.stop();
			}
		}

		/**
		 * Live effect pass painted over the static base thumbnail: a diagonal
		 * sheen band sweeping corner-to-corner (shiny and EPIC+), a cycling
		 * prismatic border (shiny) and drifting scanlines (hologram). Cheap
		 * gradient/line fills clipped to the cell — never re-rasterization.
		 */
		private void paintLiveEffect(Graphics2D g, int x, int y, Entry entry, long now) {
			boolean shiny = entry.variant == Variant.SHINY;
			boolean holo = entry.variant == Variant.HOLOGRAM;
			RoundRectangle2D cell = new RoundRectangle2D.Float(
				x, y, THUMB_W, THUMB_H, CELL_ARC, CELL_ARC);

			if (shiny || entry.card.getRarity().atLeast(Rarity.EPIC)) {
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

			if (holo) {
				Graphics2D gs = (Graphics2D) g.create();
				gs.setClip(cell);
				gs.setColor(HOLO_SCAN);
				int offset = (int) ((now / 90) % 6);
				for (int sy = y + offset; sy < y + THUMB_H; sy += 6) {
					gs.drawLine(x, sy, x + THUMB_W, sy);
				}
				gs.dispose();
			}

			if (shiny || holo) {
				Graphics2D gs = (Graphics2D) g.create();
				gs.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				gs.setColor(shiny ? CardRenderer.prismaticColor(now, 0) : HOLO_EDGE);
				gs.setStroke(entry.card.getRarity().atLeast(Rarity.RARE) ? BORDER_THICK : BORDER_THIN);
				gs.draw(cell);
				gs.dispose();
			}
		}

		private Color shinySheenColor(long now) {
			Color c = CardRenderer.prismaticColor(now, 60);
			return new Color(c.getRed(), c.getGreen(), c.getBlue(), 64);
		}

		private void scheduleRaster(List<Entry> batch) {
			if (workerRunning) {
				return;
			}
			workerRunning = true;
			final List<Entry> copy = new ArrayList<>(batch);
			new SwingWorker<Void, Object[]>() {
				@Override
				protected Void doInBackground() {
					for (Entry entry : copy) {
						try {
							String key = keyOf(entry);
							int version = versionOf(key);
							publish(new Object[]{key, rasterize(entry, key), version});
						}
						catch (Exception e) {
							log.debug("album thumbnail raster failed", e);
						}
					}
					return null;
				}

				@Override
				protected void process(List<Object[]> chunks) {
					for (Object[] chunk : chunks) {
						String key = (String) chunk[0];
						if (versionOf(key) == (Integer) chunk[2]) {
							cache.put(key, (BufferedImage) chunk[1]);
						}
						// else: a sprite finished loading mid-raster; drop the
						// stale raster and let the repaint re-request the cell
					}
					scheduleRepaint();
				}

				@Override
				protected void done() {
					workerRunning = false;
					// repaint requests the next missing batch, if any
					scheduleRepaint();
				}
			}.execute();
		}

		private void scheduleRepaint() {
			if (!repaintScheduled.compareAndSet(false, true)) {
				return;
			}
			SwingUtilities.invokeLater(() -> {
				repaintScheduled.set(false);
				repaint();
			});
		}

		private int versionOf(String key) {
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
		private Runnable artLoadHook(String key, int itemId) {
			return () -> {
				if (!loadedItemIds.add(itemId)) {
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
		private BufferedImage fetchArt(String key, int itemId) {
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
		private BufferedImage resolveArt(Entry entry, String key) {
			int cardId = entry.card.getCardId();
			Integer resolved = resolvedArtIds.get(cardId);
			if (resolved != null) {
				return fetchArt(key, resolved);
			}
			Set<Integer> ids = entry.card.getItemIds();
			List<Integer> candidates = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
			if (!candidates.contains(cardId)) {
				candidates.add(cardId);
			}
			Collections.sort(candidates);
			BufferedImage fallback = null;
			for (int itemId : candidates) {
				BufferedImage art = fetchArt(key, itemId);
				if (art == null) {
					continue;
				}
				if (hasVisiblePixel(art)) {
					resolvedArtIds.put(cardId, itemId);
					return art;
				}
				if (!loadedItemIds.contains(itemId)) {
					// still loading, so blank is inconclusive; draw with it
					// and let its load hook re-raster this cell
					fallback = art;
					break;
				}
				// loaded but fully transparent: try the next variant id
			}
			return fallback;
		}

		private BufferedImage rasterize(Entry entry, String key) {
			BufferedImage art = resolveArt(entry, key);
			BufferedImage img = new BufferedImage(THUMB_W, THUMB_H, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = img.createGraphics();
			try {
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
			finally {
				g2.dispose();
			}
			if (!entry.owned) {
				RescaleOp darken = new RescaleOp(
					new float[]{0.16f, 0.16f, 0.20f, 1f}, new float[4], null);
				img = darken.filter(img, null);
			}
			return img;
		}

		/**
		 * The "w" badge in a card's top-right corner, drawn only on cards the
		 * player owns — an undiscovered card has no name to look up yet.
		 */
		private void paintWikiBadge(Graphics2D g2, int x, int y) {
			int bx = x + THUMB_W - WIKI_BADGE - 3;
			int by = y + 3;
			g2.setColor(WIKI_BADGE_BG);
			g2.fillOval(bx, by, WIKI_BADGE, WIKI_BADGE);
			g2.setColor(WIKI_BADGE_FG);
			g2.setFont(WIKI_BADGE_FONT);
			FontMetrics fm = g2.getFontMetrics();
			String glyph = "w";
			g2.drawString(glyph, bx + (WIKI_BADGE - fm.stringWidth(glyph)) / 2,
				by + (WIKI_BADGE + fm.getAscent()) / 2 - 1);
		}

		/** Cell the point falls in, or null when it is past the last card. */
		private Entry entryAt(int px, int py) {
			if (entries.isEmpty()) {
				return null;
			}
			int c = cols();
			int cellW = Math.max(1, getWidth() / c);
			int col = px / cellW;
			int row = Math.max(0, (py - GAP) / (THUMB_H + GAP));
			int index = row * c + col;
			if (col >= c || index < 0 || index >= entries.size()) {
				return null;
			}
			return entries.get(index);
		}

		/** True when the point is inside that cell's wiki badge. */
		private boolean onWikiBadge(int px, int py) {
			int c = cols();
			int cellW = Math.max(1, getWidth() / c);
			int rowH = THUMB_H + GAP;
			// back to the cell's own origin, the same way paintComponent lays it out
			int x = (px / cellW) * cellW + (cellW - THUMB_W) / 2;
			int y = GAP + Math.max(0, (py - GAP) / rowH) * rowH;
			int bx = x + THUMB_W - WIKI_BADGE - 3;
			int by = y + 3;
			return px >= bx && px <= bx + WIKI_BADGE && py >= by && py <= by + WIKI_BADGE;
		}

		@Override
		public String getToolTipText(MouseEvent event) {
			if (event == null || entries.isEmpty()) {
				return null;
			}
			Entry entry = entryAt(event.getX(), event.getY());
			if (entry == null) {
				return null;
			}
			if (entry.owned && onWikiBadge(event.getX(), event.getY())) {
				return "Open the OSRS wiki page for " + entry.card.getName();
			}
			if (!entry.owned) {
				return "Undiscovered card";
			}
			String variantText = entry.variant == Variant.SHINY ? " — Shiny" : "";
			// "present for", never "killed": the record counts kills the card was
			// ASSIGNED TO THE LOADOUT for, on-task or not, tainted or not
			String service = entry.serviceKills > 0 ? " — " + servedText(entry.serviceKills) : "";
			return entry.card.getName() + " — " + entry.card.getRarity().getDisplayName()
				+ variantText + " — " + entry.card.getSlot().getDisplayName() + service;
		}

		// --- Scrollable ---

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return THUMB_H / 3;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
