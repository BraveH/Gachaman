package com.gachaman.ui.panel;

import com.gachaman.GachamanConfig;
import com.gachaman.data.CardDatabase;
import com.gachaman.model.ContractRecord;
import com.gachaman.model.GachaState;
import com.gachaman.party.PartyRollService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.PatronMark;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * The Gachaman sidebar: a tab strip over a CardLayout hosting the tabs.
 * While the card database is still scanning the item cache, every tab shows a
 * centered scan-progress message polled by a Swing timer (only while visible).
 * refresh() is EDT-safe and coalesced; only the selected tab is rebuilt, the
 * rest are marked dirty and rebuilt lazily on selection.
 */
@Slf4j
@Singleton
public class GachamanPanel extends PluginPanel implements GachaStateService.Listener {
	private enum Tab {
		OVERVIEW("Overview"),
		SHOP("Shop"),
		ALBUM("Album"),
		SETS("Sets"),
		JOURNAL("Journal"),
		TIMELINE("Timeline"),
		DOSSIER("Dossier"),
		PARTY("Party"),
		PATRONS("Patrons"),
		LOADOUT("Loadout"),
		HELP("Help");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final String CARD_LOADING = "LOADING";
	private static final int SCAN_POLL_MS = 400;
	private static final String GITHUB_URL = "https://github.com/BraveH/Gachaman";
	private static final String KOFI_URL = "https://ko-fi.com/amrothabet";
	private static final int LINK_ICON_SIZE = 18;

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final OverviewTab overviewTab;
	private final ShopTab shopTab;
	private final AlbumTab albumTab;
	private final SetsTab setsTab;
	private final JournalTab journalTab;
	private final TimelineTab timelineTab;
	private final DossierTab dossierTab;
	private final PartyTab partyTab;
	private final PatronsTab patronsTab;
	private final LoadoutTab loadoutTab;
	private final HelpTab helpTab;

	private final CardLayout contentLayout = new CardLayout();
	private final JPanel content = new JPanel(contentLayout);
	private final JLabel scanLabel = new JLabel("", SwingConstants.CENTER);
	private final Map<Tab, JButton> tabButtons = new EnumMap<>(Tab.class);
	private final EnumSet<Tab> dirty = EnumSet.allOf(Tab.class);
	private final AtomicBoolean refreshQueued = new AtomicBoolean();

	private Tab selected = Tab.OVERVIEW;
	private Timer scanTimer;
	private volatile boolean started;
	private volatile boolean active;

	private final GachamanConfig config;
	private JPanel tabRow;

	@Inject
	public GachamanPanel(
		GachaStateService stateService,
		CardDatabase cardDatabase,
		OverviewTab overviewTab,
		ShopTab shopTab,
		AlbumTab albumTab,
		SetsTab setsTab,
		JournalTab journalTab,
		TimelineTab timelineTab,
		DossierTab dossierTab,
		PartyTab partyTab,
		PatronsTab patronsTab,
		LoadoutTab loadoutTab,
		HelpTab helpTab,
		GachamanConfig config) {
		super(false);
		this.config = config;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.overviewTab = overviewTab;
		this.shopTab = shopTab;
		this.albumTab = albumTab;
		this.setsTab = setsTab;
		this.journalTab = journalTab;
		this.timelineTab = timelineTab;
		this.dossierTab = dossierTab;
		this.partyTab = partyTab;
		this.patronsTab = patronsTab;
		this.loadoutTab = loadoutTab;
		this.helpTab = helpTab;

		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setOpaque(false);
		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		JLabel titleLabel = new JLabel("Gachaman");
		titleLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		titleLabel.setForeground(Color.WHITE);
		titleRow.add(titleLabel, BorderLayout.CENTER);
		JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		links.setOpaque(false);
		links.add(linkIcon(icon("link-github"), icon("link-github-hover"), GITHUB_URL,
			"Gachaman on GitHub — source, issues and releases"));
		links.add(linkIcon(icon("link-kofi"), icon("link-kofi-hover"), KOFI_URL,
			"Support Gachaman on Ko-fi"));
		titleRow.add(links, BorderLayout.EAST);
		header.add(titleRow, BorderLayout.NORTH);

		tabRow = new JPanel(new GridLayout(0, 3, 3, 3));
		tabRow.setOpaque(false);
		for (Tab tab : Tab.values()) {
			JButton button = new JButton(tab.label);
			button.setFont(FontManager.getRunescapeSmallFont());
			button.setFocusPainted(false);
			button.setMargin(new Insets(2, 2, 2, 2));
			button.addActionListener(e -> selectTab(tab));
			tabButtons.put(tab, button);
			tabRow.add(button);
		}
		header.add(tabRow, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		updateTabVisibility();

		content.setOpaque(false);
		JPanel loading = new JPanel(new GridBagLayout());
		loading.setOpaque(false);
		scanLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scanLabel.setFont(FontManager.getRunescapeSmallFont());
		loading.add(scanLabel, new GridBagConstraints());
		content.add(loading, CARD_LOADING);
		content.add(wrapScroll(overviewTab), Tab.OVERVIEW.name());
		content.add(wrapScroll(shopTab), Tab.SHOP.name());
		content.add(albumTab, Tab.ALBUM.name()); // album manages its own scroll
		content.add(wrapScroll(setsTab), Tab.SETS.name());
		content.add(wrapScroll(journalTab), Tab.JOURNAL.name());
		content.add(timelineTab, Tab.TIMELINE.name()); // timeline manages its own scroll
		content.add(dossierTab, Tab.DOSSIER.name()); // dossier pins its totals outside the scroll
		content.add(wrapScroll(partyTab), Tab.PARTY.name());
		content.add(patronsTab, Tab.PATRONS.name()); // patrons pins its totals outside the scroll
		content.add(wrapScroll(loadoutTab), Tab.LOADOUT.name());
		content.add(wrapScroll(helpTab), Tab.HELP.name());
		add(content, BorderLayout.CENTER);

		updateTabButtonStyles();
		updateScanLabel();
		contentLayout.show(content, CARD_LOADING);
	}

	// --- Lifecycle ---

	public void start() {
		started = true;
		stateService.addListener(this);
		cardDatabase.onReady(this::refresh);
		refresh();
	}

	public void stop() {
		started = false;
		stateService.removeListener(this);
		stopScanTimer();
	}

	@Override
	public void onActivate() {
		active = true;
		ensureScanTimer();
		refresh();
	}

	@Override
	public void onDeactivate() {
		active = false;
		stopScanTimer();
	}

	/** Wired later by the plugin: whether the player is in a party (party offers). */
	public void setInPartySupplier(BooleanSupplier supplier) {
		overviewTab.setInPartySupplier(supplier);
	}

	/**
	 * Wired later by the plugin: the live party vote, or null when none is open.
	 *
	 * <p>Pushed in rather than injected, for the same reason as the supplier
	 * above — the panel is built by Guice and the party layer takes its hooks
	 * from the plugin, so a constructor dependency here would be the one edge
	 * that points backwards.
	 */
	public void setVoteViewSupplier(
		Supplier<PartyRollService.VoteView> supplier) {
		partyTab.setVoteViewSupplier(supplier);
	}

	// --- State listener ---

	@Override
	public void onStateChanged(GachaState newState) {
		refresh();
	}

	/** EDT-safe, coalesced refresh. Cheap to call from any thread, any rate. */
	public void refresh() {
		if (!refreshQueued.compareAndSet(false, true)) {
			return;
		}
		SwingUtilities.invokeLater(() -> {
			refreshQueued.set(false);
			refreshNow();
		});
	}

	private void refreshNow() {
		if (!started) {
			return;
		}
		dirty.addAll(EnumSet.allOf(Tab.class));
		// on every refresh, not just on a config change: the first filed contract
		// and the first shared one are STATE changes, and a tab the player just
		// earned should appear on the refresh that earned it
		updateTabVisibility();
		if (!cardDatabase.isReady()) {
			updateScanLabel();
			contentLayout.show(content, CARD_LOADING);
			ensureScanTimer();
			return;
		}
		stopScanTimer();
		if (active || isShowing()) {
			rebuildIfDirty(selected);
		}
		contentLayout.show(content, selected.name());
	}

	// --- Tabs ---

	private void selectTab(Tab tab) {
		selected = tab;
		updateTabButtonStyles();
		if (!cardDatabase.isReady()) {
			contentLayout.show(content, CARD_LOADING);
			return;
		}
		rebuildIfDirty(tab);
		contentLayout.show(content, tab.name());
	}

	private void rebuildIfDirty(Tab tab) {
		if (!dirty.remove(tab)) {
			return;
		}
		try {
			switch (tab) {
				case OVERVIEW:
					overviewTab.rebuild();
					break;
				case SHOP:
					shopTab.rebuild();
					break;
				case ALBUM:
					albumTab.rebuild();
					break;
				case SETS:
					setsTab.rebuild();
					break;
				case JOURNAL:
					journalTab.rebuild();
					break;
				case TIMELINE:
					timelineTab.rebuild();
					break;
				case DOSSIER:
					dossierTab.rebuild();
					break;
				case PARTY:
					partyTab.rebuild();
					break;
				case PATRONS:
					patronsTab.rebuild();
					break;
				case LOADOUT:
					loadoutTab.rebuild();
					break;
				case HELP:
					helpTab.rebuild();
					break;
			}
		}
		catch (Exception e) {
			log.warn("Gachaman tab rebuild failed: {}", tab, e);
		}
	}

	/**
	 * Rebuild the tab strip from the enum, keeping only the tabs that currently
	 * have something behind them.
	 *
	 * The WHOLE strip is rebuilt rather than one button being spliced back in at
	 * its ordinal. An index-based re-insert is only correct while at most one tab
	 * is hideable and it is the last one before Help — with three hideable tabs
	 * an ordinal is no longer a valid tabRow index (two hidden tabs below it push
	 * every later ordinal past the button count) and the strip would either throw
	 * or silently reorder itself. Removing and re-adding in enum order is O(tabs),
	 * runs only on a refresh, and cannot get the order wrong by construction.
	 *
	 * Safe to call before the constructor finishes wiring: tabRow is the guard.
	 */
	public void updateTabVisibility() {
		if (tabRow == null) {
			return;
		}
		GachaState state = stateService.get();
		tabRow.removeAll();
		boolean selectedSurvives = false;
		for (Tab tab : Tab.values()) {
			JButton button = tabButtons.get(tab);
			if (button == null || !isTabVisible(tab, state)) {
				continue;
			}
			tabRow.add(button);
			selectedSurvives |= tab == selected;
		}
		tabRow.revalidate();
		tabRow.repaint();
		if (!selectedSurvives) {
			// the page the player was reading just went away — land them somewhere
			// that always exists rather than on a card with no button to leave it
			selectTab(Tab.OVERVIEW);
		}
	}

	/**
	 * Whether a tab is worth a button right now.
	 *
	 * Empty pages are HIDDEN rather than shown empty: the strip is a 3-column
	 * grid in a 200px sidebar, so every button that explains nothing costs a row
	 * of the ones that do. Overview is the fallback in
	 * {@link #updateTabVisibility} and so must never be hidden here.
	 */
	private boolean isTabVisible(Tab tab, @Nullable GachaState state) {
		switch (tab) {
			case DOSSIER:
				return hasContracts(state);
			case PATRONS:
				return PatronMark.partnerCount(state == null ? null : state.getPatrons()) > 0;
			case LOADOUT:
				return config.oneCardPerSlot();
			default:
				return true;
		}
	}

	/** True once one contract has been filed — the Dossier's whole content. */
	private static boolean hasContracts(@Nullable GachaState state) {
		List<ContractRecord> log = state == null ? null : state.getContractLog();
		if (log == null) {
			return false;
		}
		for (ContractRecord record : log) {
			if (record != null) {
				return true; // Gson can hand back a null array element
			}
		}
		return false;
	}

	private void updateTabButtonStyles() {
		for (Map.Entry<Tab, JButton> entry : tabButtons.entrySet()) {
			boolean isSelected = entry.getKey() == selected;
			JButton button = entry.getValue();
			button.setBackground(isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
			button.setForeground(isSelected ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	// --- Header links ---

	/**
	 * A flat icon that opens a URL.
	 *
	 * A JLabel rather than a JButton on purpose: a button brings a border, a fill
	 * and a focus ring, and three of those stacked immediately above the tab strip
	 * read as two competing rows of controls. The icon IS the control, so hover is
	 * the only affordance it needs — hence the swapped bright variant and the hand
	 * cursor.
	 *
	 * LinkBrowser rather than Desktop.browse(): it is RuneLite's own opener, so it
	 * respects the client's link-confirmation setting and degrades to a
	 * copy-to-clipboard prompt on a machine with no usable browser handler,
	 * instead of throwing onto the EDT.
	 */
	/** A flat panel icon, authored by com.gachaman.tools.IconArt. */
	private static ImageIcon icon(String name) {
		return new ImageIcon(ImageUtil.loadImageResource(
			GachamanPanel.class, "/com/gachaman/ui/" + name + ".png"));
	}

	private static JLabel linkIcon(ImageIcon normal, ImageIcon hover, String url, String tooltip) {
		JLabel label = new JLabel(normal);
		label.setToolTipText(tooltip);
		label.setCursor(new Cursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				LinkBrowser.browse(url);
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				label.setIcon(hover);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				label.setIcon(normal);
			}
		});
		return label;
	}




	// --- Scan progress polling ---

	private void updateScanLabel() {
		scanLabel.setText("Scanning Gielinor's armoury… " + cardDatabase.getScanProgressPercent() + "%");
	}

	private void ensureScanTimer() {
		if (!active || cardDatabase.isReady()) {
			return;
		}
		if (scanTimer == null) {
			scanTimer = new Timer(SCAN_POLL_MS, e -> {
				if (cardDatabase.isReady()) {
					stopScanTimer();
					refresh();
				}
				else {
					updateScanLabel();
				}
			});
			scanTimer.setRepeats(true);
		}
		if (!scanTimer.isRunning()) {
			scanTimer.start();
		}
	}

	private void stopScanTimer() {
		if (scanTimer != null && scanTimer.isRunning()) {
			scanTimer.stop();
		}
	}

	private static JScrollPane wrapScroll(JComponent inner) {
		JScrollPane scroll = new JScrollPane(inner,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		styleScrollbar(scroll);
		return scroll;
	}

	/** Slim, dark, game-styled scrollbar (stone thumb, no arrow buttons). */
	public static void styleScrollbar(JScrollPane scroll) {
		JScrollBar bar = scroll.getVerticalScrollBar();
		bar.setUI(new GameScrollBarUI());
		bar.setPreferredSize(new Dimension(9, 0));
		bar.setOpaque(false);
	}

	private static final class GameScrollBarUI extends BasicScrollBarUI {
		private static final Color TRACK = new Color(28, 27, 25);
		private static final Color THUMB = new Color(82, 72, 58);
		private static final Color THUMB_EDGE_LIGHT = new Color(115, 102, 82);
		private static final Color THUMB_EDGE_DARK = new Color(46, 40, 32);

		@Override
		protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
			g.setColor(TRACK);
			g.fillRect(r.x, r.y, r.width, r.height);
		}

		@Override
		protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
			if (r.isEmpty() || !scrollbar.isEnabled()) {
				return;
			}
			g.setColor(THUMB);
			g.fillRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
			g.setColor(THUMB_EDGE_LIGHT);
			g.drawLine(r.x + 1, r.y + 1, r.x + r.width - 2, r.y + 1);
			g.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + r.height - 2);
			g.setColor(THUMB_EDGE_DARK);
			g.drawLine(r.x + 1, r.y + r.height - 2, r.x + r.width - 2, r.y + r.height - 2);
			g.drawLine(r.x + r.width - 2, r.y + 1, r.x + r.width - 2, r.y + r.height - 2);
		}

		@Override
		protected JButton createDecreaseButton(int orientation) {
			return zeroButton();
		}

		@Override
		protected JButton createIncreaseButton(int orientation) {
			return zeroButton();
		}

		private static JButton zeroButton() {
			JButton button = new JButton();
			Dimension zero = new Dimension(0, 0);
			button.setPreferredSize(zero);
			button.setMinimumSize(zero);
			button.setMaximumSize(zero);
			return button;
		}
	}

	// --- Shared widget helpers used by the tabs ---

	static JPanel section(@Nullable String title) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (title != null) {
			JLabel label = new JLabel(title);
			label.setForeground(ColorScheme.BRAND_ORANGE);
			label.setFont(FontManager.getRunescapeBoldFont());
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			panel.add(label);
			panel.add(Box.createVerticalStrut(6));
		}
		return panel;
	}

	static JLabel line(String text, Color color, Font font) {
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(font);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	static JLabel smallLine(String text, Color color) {
		return line(text, color, FontManager.getRunescapeSmallFont());
	}

	/**
	 * The viewport a scroll-wrapped tab actually gets.
	 *
	 * <p>{@link PluginPanel#getPreferredSize()} returns 242 for the super(false)
	 * form this panel uses; take off the 6px panel border each side and the 9px
	 * GameScrollBarUI and 221 is left. The scroll panes never scroll sideways,
	 * so a child that wants more than this is not scrolled to — it is clipped.
	 */
	static final int VIEWPORT_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
		- 2 * PluginPanel.BORDER_OFFSET - 9;

	/**
	 * The usable column inside a {@link #section}, whose padding is 8px a side.
	 * The gold-bordered variants swap that padding for a 1px line plus 7px,
	 * which is the same 8px, so one number covers every caller.
	 */
	static final int SECTION_WIDTH = VIEWPORT_WIDTH - 16;

	/**
	 * A width-constrained, word-wrapping HTML block sized for a direct child of
	 * {@link #section}.
	 *
	 * <p>The text is inserted raw — {@code <b>} in a caller's string is meant to
	 * render. Callers interpolating anything the player or the item cache
	 * supplies must run it through {@link #escape} first.
	 *
	 * <p>Not an HTML JLabel with {@code style='width:205px'}, which is what this
	 * used to be: Swing reads that width as a PREFERRED span, not a cap, so a
	 * line that measures wider simply laid out wider than the column and the
	 * viewport — which has no horizontal scrollbar — sheared the overflow off at
	 * the right edge with no ellipsis. A JEditorPane wraps against the width it
	 * is actually given, and pinning that width makes the column real.
	 */
	static JComponent wrapped(String text, Color color) {
		return wrapped(text, color, FontManager.getRunescapeSmallFont());
	}

	/**
	 * {@link #wrapped} in a caller-chosen font. Same column — the only reason to
	 * pick the font is a HEADING that has to wrap, and the bold face at ~7px/char
	 * runs out of column a third sooner than the small one does.
	 */
	static JComponent wrapped(String text, Color color, Font font) {
		JEditorPane pane = new JEditorPane();
		pane.setContentType("text/html");
		pane.setEditable(false);
		pane.setFocusable(false);
		pane.setOpaque(false);
		pane.setBorder(null);
		pane.setMargin(new Insets(0, 0, 0, 0));
		// without this the pane renders in the HTML default face and colour and
		// ignores setFont/setForeground entirely — the panel would go serif black
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setFont(font);
		pane.setForeground(color);
		// no CSS width: the wrap comes from the size pinned below, which unlike the
		// CSS hint is a width the layout cannot talk the pane out of
		pane.setText("<html><body>" + text + "</body></html>");
		pane.setAlignmentX(Component.LEFT_ALIGNMENT);
		// size first, then ask: the line count only exists once there is a width
		// to wrap against, and the height is only right for that same width
		pane.setSize(SECTION_WIDTH, Short.MAX_VALUE);
		Dimension fixed = new Dimension(SECTION_WIDTH, pane.getPreferredSize().height);
		pane.setPreferredSize(fixed);
		pane.setMinimumSize(fixed);
		pane.setMaximumSize(fixed);
		return pane;
	}

	/**
	 * The between-facts separator for the HTML panes (Dossier, Patrons,
	 * Timeline), matching the literal {@code "  ·  "} the plain-JLabel panels
	 * use. Non-breaking because HTML collapses a run of spaces to one, so the
	 * literal rendered a third of the intended gap and the segments ran together.
	 */
	static final String DOT = "&nbsp;&nbsp;·&nbsp;&nbsp;";

	/**
	 * Neutralise HTML in a string bound for a Swing HTML label. All three of
	 * {@code & < >} — a lone {@code >} is legal text in HTML5 but Swing's parser
	 * is HTML 3.2 and will happily close a tag it thinks it is inside.
	 */
	static String escape(@Nullable String text) {
		return text == null ? ""
			: text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * "a" or "an" for a noun phrase, so generated sentences do not read "a Easy
	 * contract" or "a Ornate Chest". Vowel letter, not vowel sound: every noun
	 * this is used on is a difficulty or a chest name, and none of them is a
	 * "u"-as-in-you or a silent-h word where the two rules disagree.
	 */
	static String article(@Nullable String noun) {
		if (noun == null || noun.isEmpty()) {
			return "a";
		}
		return "AEIOUaeiou".indexOf(noun.charAt(0)) >= 0 ? "an" : "a";
	}

	static JComponent centeredNote(String text) {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		panel.add(label, new GridBagConstraints());
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	static boolean confirm(Component parent, String titleText, String message) {
		JOptionPane pane = new JOptionPane(dialogBody(message),
			JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
		JDialog dialog = pane.createDialog(parent, titleText);
		sizeForTargetScreen(dialog, parent);
		dialog.setVisible(true);
		dialog.dispose();
		return Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
	}

	static void info(Component parent, String message) {
		JOptionPane pane = new JOptionPane(dialogBody(message), JOptionPane.INFORMATION_MESSAGE);
		JDialog dialog = pane.createDialog(parent, "Gachaman");
		sizeForTargetScreen(dialog, parent);
		dialog.setVisible(true);
		dialog.dispose();
	}

	/** Fixed-width HTML body so the dialog's preferred size is stable on any monitor. */
	private static JComponent dialogBody(String message) {
		String escaped = escape(message).replace("\n", "<br>");
		return new JLabel("<html><body style='width:230px'>" + escaped + "</body></html>");
	}

	/**
	 * Mixed-DPI multi-monitor fix: createDialog() packs the dialog with the
	 * PRIMARY monitor's graphics configuration; when the client sits on a
	 * monitor with a different scale factor the dialog opens clipped — message
	 * truncated, Yes/No buttons entirely off the bottom. Move the window onto
	 * the target screen FIRST, then re-pack so layout runs with that monitor's
	 * real metrics, and pin the packed size as the minimum.
	 */
	private static void sizeForTargetScreen(JDialog dialog, Component parent) {
		dialog.setLocationRelativeTo(parent);
		dialog.pack();
		dialog.setMinimumSize(dialog.getSize());
		dialog.setLocationRelativeTo(parent);
	}

	/** A thin horizontal meter with an optional centered label. */
	static final class MeterBar extends JComponent {
		private static final Color TRACK = new Color(28, 28, 28);

		private final double fraction;
		private final Color barColor;
		@Nullable
		private final String label;

		MeterBar(double fraction, Color barColor, @Nullable String label) {
			this.fraction = Math.max(0, Math.min(1, fraction));
			this.barColor = barColor;
			this.label = label;
			setPreferredSize(new Dimension(100, 15));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 15));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(TRACK);
			g2.fillRoundRect(0, 0, w, h, 6, 6);
			int fill = (int) Math.round(w * fraction);
			if (fill > 0) {
				g2.setColor(barColor);
				g2.fillRoundRect(0, 0, Math.max(fill, 6), h, 6, 6);
			}
			if (label != null) {
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(Color.WHITE);
				int tw = g2.getFontMetrics().stringWidth(label);
				g2.drawString(label, (w - tw) / 2, h / 2 + g2.getFontMetrics().getAscent() / 2 - 1);
			}
			g2.dispose();
		}
	}

	/** A left-aligned row wrapper so BorderLayout rows behave in a BoxLayout. */
	static JPanel row(JComponent left, @Nullable JComponent right) {
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		panel.add(left, BorderLayout.CENTER);
		if (right != null) {
			panel.add(right, BorderLayout.EAST);
		}
		return panel;
	}

	static JButton button(String text) {
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		button.setForeground(Color.WHITE);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 8, 3, 8)));
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		return button;
	}

	/** Dark, RuneLite-styled combo box: colors, font, renderer and arrow. */
	static void styleCombo(JComboBox<?> combo) {
		styleCombo(combo, value -> value == null ? "" : String.valueOf(value));
	}

	/**
	 * As {@link #styleCombo(JComboBox)}, but rendering each entry
	 * through {@code labeller} instead of its {@code toString()}.
	 *
	 * A combo box has exactly one renderer, and this method installs it — so a
	 * caller that sets its own renderer first and then styles the box loses it
	 * silently, and the list falls back to raw enum names ("ORNATE" rather than
	 * "Ornate Chest"). Passing the label function in is the only ordering that
	 * cannot be got wrong.
	 */
	static <T> void styleCombo(JComboBox<T> combo,
		Function<T, String> labeller) {
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setForeground(Color.WHITE);
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setFocusable(false);
		combo.setUI(new BasicComboBoxUI() {
			@Override
			protected JButton createArrowButton() {
				BasicArrowButton arrow = new BasicArrowButton(
					SwingConstants.SOUTH, ColorScheme.DARKER_GRAY_COLOR.darker(),
					ColorScheme.DARKER_GRAY_COLOR.darker(), ColorScheme.LIGHT_GRAY_COLOR,
					ColorScheme.DARKER_GRAY_COLOR.darker());
				arrow.setBorder(BorderFactory.createEmptyBorder());
				return arrow;
			}
		});
		combo.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		DefaultListCellRenderer renderer = new DefaultListCellRenderer() {
			@Override
			@SuppressWarnings("unchecked")
			public Component getListCellRendererComponent(JList<?> list, Object value,
				int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				setFont(FontManager.getRunescapeSmallFont());
				setBackground(isSelected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				setForeground(isSelected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				setBorder(new EmptyBorder(2, 5, 2, 5));
				// Swing renders a null entry while the popup sizes itself and
				// whenever the model is empty; the labeller never sees one.
				setText(value == null ? "" : labeller.apply((T) value));
				return this;
			}
		};
		combo.setRenderer(renderer);
	}

	/** Dark, RuneLite-styled spinner (date/number editors alike). */
	static void styleSpinner(JComponent spinner) {
		if (!(spinner instanceof JSpinner)) {
			return;
		}
		JSpinner s = (JSpinner) spinner;
		s.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		s.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JComponent editor = s.getEditor();
		if (editor instanceof JSpinner.DefaultEditor) {
			JFormattedTextField field =
				((JSpinner.DefaultEditor) editor).getTextField();
			field.setFont(FontManager.getRunescapeSmallFont());
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setForeground(Color.WHITE);
			field.setCaretColor(Color.WHITE);
			field.setBorder(new EmptyBorder(2, 5, 2, 5));
		}
		for (Component child : s.getComponents()) {
			if (child instanceof JButton) {
				child.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			}
		}
	}

	/** Dark game-styled slider: stone thumb on a slim dark track. */
	static final class GameSliderUI extends BasicSliderUI {
		private static final Color TRACK = new Color(28, 27, 25);
		private static final Color THUMB = new Color(82, 72, 58);
		private static final Color THUMB_EDGE = new Color(115, 102, 82);

		GameSliderUI(JSlider slider) {
			super(slider);
		}

		@Override
		public void paintTrack(Graphics g) {
			int cy = trackRect.y + trackRect.height / 2;
			g.setColor(TRACK);
			g.fillRoundRect(trackRect.x, cy - 3, trackRect.width, 6, 6, 6);
			g.setColor(ColorScheme.BRAND_ORANGE);
			int fill = thumbRect.x + thumbRect.width / 2 - trackRect.x;
			if (fill > 0) {
				g.fillRoundRect(trackRect.x, cy - 3, Math.min(fill, trackRect.width), 6, 6, 6);
			}
		}

		@Override
		public void paintThumb(Graphics g) {
			g.setColor(THUMB);
			g.fillRect(thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
			g.setColor(THUMB_EDGE);
			g.drawRect(thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 5, thumbRect.height - 5);
		}

		@Override
		public void paintFocus(Graphics g) {
			// no focus ring
		}
	}

	/**
	 * Pre-realization fallback only: the 242px non-wrapped PluginPanel minus
	 * its 6px borders and a full stock 17px scrollbar — the NARROWEST the
	 * viewport can plausibly be, so nothing clips even before measuring.
	 */
	static final int FALLBACK_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
		- 2 * PluginPanel.BORDER_OFFSET - PluginPanel.SCROLLBAR_WIDTH;

	static String htmlWrap(String body) {
		return "<html><body>" + body + "</body></html>";
	}

	static String hex(Color color) {
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	/**
	 * Wrap-to-width body text WITHOUT the HTML renderer: Swing's CSS width is
	 * a preferred span, not a hard cap — stretched labels re-wrap wider than
	 * asked and then clip under the scrollbar. A JTextArea wraps at exactly
	 * the width it is given; sizing it up front makes its preferred height
	 * correct before the BoxLayout ever asks.
	 */
	static JTextArea textBlock(String text, Color color, int width) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setBorder(null);
		area.setForeground(color);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		area.setSize(width, Short.MAX_VALUE);
		Dimension pref = area.getPreferredSize();
		area.setPreferredSize(new Dimension(width, pref.height));
		area.setMaximumSize(new Dimension(width, pref.height));
		return area;
	}

	/** The scroll viewport's ACTUAL extent width — the only trustworthy budget. */
	static int measuredWidth(JComponent panel) {
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, panel);
		if (ancestor instanceof JViewport) {
			int width = ((JViewport) ancestor).getExtentSize().width;
			if (width > 0) {
				return width;
			}
		}
		return FALLBACK_WIDTH;
	}
}
