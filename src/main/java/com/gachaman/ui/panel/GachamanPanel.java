package com.gachaman.ui.panel;

import com.gachaman.data.CardDatabase;
import com.gachaman.model.GachaState;
import com.gachaman.service.GachaStateService;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
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
public class GachamanPanel extends PluginPanel implements GachaStateService.Listener
{
	private enum Tab
	{
		OVERVIEW("Overview"),
		SHOP("Shop"),
		ALBUM("Album"),
		SETS("Sets"),
		JOURNAL("Journal"),
		TIMELINE("Timeline"),
		// Dossier and Party must stay BEFORE LOADOUT: updateLoadoutTabVisibility()
		// re-inserts the Loadout button at Tab.LOADOUT.ordinal(), which is only a
		// valid tabRow index because EVERY tab of lower ordinal is unconditionally
		// visible. Loadout is the only hideable tab, so any future tab is safe here
		// and only here — inserting one AFTER Loadout would push Loadout's ordinal
		// past the button count while Loadout is hidden and throw on re-insert.
		DOSSIER("Dossier"),
		PARTY("Party"),
		LOADOUT("Loadout"),
		HELP("Help");

		private final String label;

		Tab(String label)
		{
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
	private final LoadoutTab loadoutTab;
	private final HelpTab helpTab;

	private final CardLayout contentLayout = new CardLayout();
	private final JPanel content = new JPanel(contentLayout);
	private final JLabel scanLabel = new JLabel("", SwingConstants.CENTER);
	private final Map<Tab, JButton> tabButtons = new EnumMap<>(Tab.class);
	private final EnumSet<Tab> dirty = EnumSet.allOf(Tab.class);
	private final AtomicBoolean refreshQueued = new AtomicBoolean();

	private Tab selected = Tab.OVERVIEW;
	private javax.swing.Timer scanTimer;
	private volatile boolean started;
	private volatile boolean active;

	private final com.gachaman.GachamanConfig config;
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
		LoadoutTab loadoutTab,
		HelpTab helpTab,
		com.gachaman.GachamanConfig config)
	{
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
		links.add(linkIcon(githubIcon(false), githubIcon(true), GITHUB_URL,
			"Gachaman on GitHub — source, issues and releases"));
		links.add(linkIcon(kofiIcon(false), kofiIcon(true), KOFI_URL,
			"Support Gachaman on Ko-fi"));
		titleRow.add(links, BorderLayout.EAST);
		header.add(titleRow, BorderLayout.NORTH);

		tabRow = new JPanel(new GridLayout(0, 3, 3, 3));
		tabRow.setOpaque(false);
		for (Tab tab : Tab.values())
		{
			JButton button = new JButton(tab.label);
			button.setFont(FontManager.getRunescapeSmallFont());
			button.setFocusPainted(false);
			button.setMargin(new java.awt.Insets(2, 2, 2, 2));
			button.addActionListener(e -> selectTab(tab));
			tabButtons.put(tab, button);
			tabRow.add(button);
		}
		header.add(tabRow, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);
		updateLoadoutTabVisibility();

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
		content.add(wrapScroll(loadoutTab), Tab.LOADOUT.name());
		content.add(wrapScroll(helpTab), Tab.HELP.name());
		add(content, BorderLayout.CENTER);

		updateTabButtonStyles();
		updateScanLabel();
		contentLayout.show(content, CARD_LOADING);
	}

	// --- Lifecycle ---

	public void start()
	{
		started = true;
		stateService.addListener(this);
		cardDatabase.onReady(this::refresh);
		refresh();
	}

	public void stop()
	{
		started = false;
		stateService.removeListener(this);
		stopScanTimer();
	}

	@Override
	public void onActivate()
	{
		active = true;
		ensureScanTimer();
		refresh();
	}

	@Override
	public void onDeactivate()
	{
		active = false;
		stopScanTimer();
	}

	/** Wired later by the plugin: whether the player is in a party (party offers). */
	public void setInPartySupplier(BooleanSupplier supplier)
	{
		overviewTab.setInPartySupplier(supplier);
	}

	// --- State listener ---

	@Override
	public void onStateChanged(GachaState newState)
	{
		refresh();
	}

	/** EDT-safe, coalesced refresh. Cheap to call from any thread, any rate. */
	public void refresh()
	{
		if (!refreshQueued.compareAndSet(false, true))
		{
			return;
		}
		SwingUtilities.invokeLater(() -> {
			refreshQueued.set(false);
			refreshNow();
		});
	}

	private void refreshNow()
	{
		if (!started)
		{
			return;
		}
		dirty.addAll(EnumSet.allOf(Tab.class));
		if (!cardDatabase.isReady())
		{
			updateScanLabel();
			contentLayout.show(content, CARD_LOADING);
			ensureScanTimer();
			return;
		}
		stopScanTimer();
		if (active || isShowing())
		{
			rebuildIfDirty(selected);
		}
		contentLayout.show(content, selected.name());
	}

	// --- Tabs ---

	private void selectTab(Tab tab)
	{
		selected = tab;
		updateTabButtonStyles();
		if (!cardDatabase.isReady())
		{
			contentLayout.show(content, CARD_LOADING);
			return;
		}
		rebuildIfDirty(tab);
		contentLayout.show(content, tab.name());
	}

	private void rebuildIfDirty(Tab tab)
	{
		if (!dirty.remove(tab))
		{
			return;
		}
		try
		{
			switch (tab)
			{
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
				case LOADOUT:
					loadoutTab.rebuild();
					break;
				case HELP:
					helpTab.rebuild();
					break;
			}
		}
		catch (Exception e)
		{
			log.warn("Gachaman tab rebuild failed: {}", tab, e);
		}
	}

	/** Show/hide the Loadout tab per the "one card per slot" setting. */
	public void updateLoadoutTabVisibility()
	{
		JButton loadoutButton = tabButtons.get(Tab.LOADOUT);
		if (loadoutButton == null || tabRow == null)
		{
			return;
		}
		boolean shouldShow = config.oneCardPerSlot();
		boolean showing = loadoutButton.getParent() == tabRow;
		if (shouldShow && !showing)
		{
			// Re-insert at the enum position (every tab before LOADOUT is
			// always visible) so Loadout does not end up after Help.
			tabRow.add(loadoutButton, Tab.LOADOUT.ordinal());
		}
		else if (!shouldShow && showing)
		{
			tabRow.remove(loadoutButton);
			if (selected == Tab.LOADOUT)
			{
				selectTab(Tab.OVERVIEW);
			}
		}
		tabRow.revalidate();
		tabRow.repaint();
	}

	private void updateTabButtonStyles()
	{
		for (Map.Entry<Tab, JButton> entry : tabButtons.entrySet())
		{
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
	private static JLabel linkIcon(ImageIcon normal, ImageIcon hover, String url, String tooltip)
	{
		JLabel label = new JLabel(normal);
		label.setToolTipText(tooltip);
		label.setCursor(new Cursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				LinkBrowser.browse(url);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setIcon(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(normal);
			}
		});
		return label;
	}

	/**
	 * The GitHub mark, drawn as a UNION of primitives rather than a traced bezier.
	 *
	 * A hand-transcribed path would be a long opaque string that nobody can review
	 * and that silently renders as a blob if one control point is wrong; ears, body,
	 * legs and tail as named shapes stay legible and scale cleanly with
	 * {@link #LINK_ICON_SIZE}. Drawn rather than shipped as a PNG to match the rest
	 * of this plugin's icons (see HelpTab's padlock and crossed circle).
	 */
	static ImageIcon githubIcon(boolean hover)
	{
		BufferedImage image = new BufferedImage(LINK_ICON_SIZE, LINK_ICON_SIZE,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double s = LINK_ICON_SIZE / 18.0; // authored at 18px, scaled from there
		g.scale(s, s);

		// The body sits HIGH and is deliberately shallower than it is wide: the legs
		// and the tail are what separate the mark from a generic cat face, and a
		// body deep enough to be circular swallows both of them.
		Area mark = new Area(new Ellipse2D.Float(1.7f, 4.2f, 14.6f, 10.2f));
		mark.add(new Area(triangle(3.1f, 6.8f, 3.5f, 1.6f, 7.6f, 4.6f)));   // left ear
		mark.add(new Area(triangle(14.9f, 6.8f, 14.5f, 1.6f, 10.4f, 4.6f))); // right ear
		mark.add(new Area(new java.awt.geom.RoundRectangle2D.Float(
			5.5f, 11.6f, 2.4f, 5.6f, 1.8f, 1.8f)));                         // left leg
		mark.add(new Area(new java.awt.geom.RoundRectangle2D.Float(
			10.1f, 11.6f, 2.4f, 5.6f, 1.8f, 1.8f)));                        // right leg

		g.setColor(hover ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		g.fill(mark);
		// The tail starts high on the flank and swings WIDE before dropping, so it
		// reads as a tail rather than as a third leg next to the other two.
		g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new QuadCurve2D.Float(4.0f, 10.8f, 0.6f, 13.2f, 2.2f, 16.4f));
		g.dispose();
		return new ImageIcon(image);
	}

	/** The Ko-fi cup: a white mug and two steam wisps on the brand's coral plate. */
	static ImageIcon kofiIcon(boolean hover)
	{
		BufferedImage image = new BufferedImage(LINK_ICON_SIZE, LINK_ICON_SIZE,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double s = LINK_ICON_SIZE / 18.0;
		g.scale(s, s);

		g.setColor(hover ? new Color(255, 110, 107) : new Color(214, 78, 75));
		g.fillRoundRect(0, 0, 18, 18, 6, 6);

		g.setColor(Color.WHITE);
		g.fillRoundRect(3, 8, 8, 7, 2, 2);                                  // mug body
		g.setStroke(new BasicStroke(1.5f));
		g.drawArc(9, 8, 5, 5, 90, -180);                                    // handle
		g.fillRoundRect(2, 15, 11, 2, 1, 1);                                // saucer
		g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new QuadCurve2D.Float(5.2f, 6.6f, 6.8f, 4.8f, 5.2f, 2.8f));  // steam
		g.draw(new QuadCurve2D.Float(8.6f, 6.6f, 10.2f, 4.8f, 8.6f, 2.8f));
		g.dispose();
		return new ImageIcon(image);
	}

	private static Path2D.Float triangle(float x1, float y1, float x2, float y2,
		float x3, float y3)
	{
		Path2D.Float path = new Path2D.Float();
		path.moveTo(x1, y1);
		path.lineTo(x2, y2);
		path.lineTo(x3, y3);
		path.closePath();
		return path;
	}

	// --- Scan progress polling ---

	private void updateScanLabel()
	{
		scanLabel.setText("Scanning Gielinor's armoury… " + cardDatabase.getScanProgressPercent() + "%");
	}

	private void ensureScanTimer()
	{
		if (!active || cardDatabase.isReady())
		{
			return;
		}
		if (scanTimer == null)
		{
			scanTimer = new javax.swing.Timer(SCAN_POLL_MS, e -> {
				if (cardDatabase.isReady())
				{
					stopScanTimer();
					refresh();
				}
				else
				{
					updateScanLabel();
				}
			});
			scanTimer.setRepeats(true);
		}
		if (!scanTimer.isRunning())
		{
			scanTimer.start();
		}
	}

	private void stopScanTimer()
	{
		if (scanTimer != null && scanTimer.isRunning())
		{
			scanTimer.stop();
		}
	}

	private static JScrollPane wrapScroll(JComponent inner)
	{
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
	public static void styleScrollbar(JScrollPane scroll)
	{
		javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
		bar.setUI(new GameScrollBarUI());
		bar.setPreferredSize(new java.awt.Dimension(9, 0));
		bar.setOpaque(false);
	}

	private static final class GameScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI
	{
		private static final Color TRACK = new Color(28, 27, 25);
		private static final Color THUMB = new Color(82, 72, 58);
		private static final Color THUMB_EDGE_LIGHT = new Color(115, 102, 82);
		private static final Color THUMB_EDGE_DARK = new Color(46, 40, 32);

		@Override
		protected void paintTrack(java.awt.Graphics g, JComponent c, java.awt.Rectangle r)
		{
			g.setColor(TRACK);
			g.fillRect(r.x, r.y, r.width, r.height);
		}

		@Override
		protected void paintThumb(java.awt.Graphics g, JComponent c, java.awt.Rectangle r)
		{
			if (r.isEmpty() || !scrollbar.isEnabled())
			{
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
		protected javax.swing.JButton createDecreaseButton(int orientation)
		{
			return zeroButton();
		}

		@Override
		protected javax.swing.JButton createIncreaseButton(int orientation)
		{
			return zeroButton();
		}

		private static javax.swing.JButton zeroButton()
		{
			javax.swing.JButton button = new javax.swing.JButton();
			java.awt.Dimension zero = new java.awt.Dimension(0, 0);
			button.setPreferredSize(zero);
			button.setMinimumSize(zero);
			button.setMaximumSize(zero);
			return button;
		}
	}

	// --- Shared widget helpers used by the tabs ---

	static JPanel section(@Nullable String title)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (title != null)
		{
			JLabel label = new JLabel(title);
			label.setForeground(ColorScheme.BRAND_ORANGE);
			label.setFont(FontManager.getRunescapeBoldFont());
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			panel.add(label);
			panel.add(Box.createVerticalStrut(6));
		}
		return panel;
	}

	static JLabel line(String text, Color color, Font font)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(font);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	static JLabel smallLine(String text, Color color)
	{
		return line(text, color, FontManager.getRunescapeSmallFont());
	}

	/** A width-constrained, word-wrapping HTML label. */
	static JLabel wrapped(String text, Color color)
	{
		JLabel label = new JLabel("<html><body style='width:170px'>" + text + "</body></html>");
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	static JComponent centeredNote(String text)
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		panel.add(label, new GridBagConstraints());
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	static boolean confirm(Component parent, String titleText, String message)
	{
		JOptionPane pane = new JOptionPane(dialogBody(message),
			JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
		JDialog dialog = pane.createDialog(parent, titleText);
		sizeForTargetScreen(dialog, parent);
		dialog.setVisible(true);
		dialog.dispose();
		return Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
	}

	static void info(Component parent, String message)
	{
		JOptionPane pane = new JOptionPane(dialogBody(message), JOptionPane.INFORMATION_MESSAGE);
		JDialog dialog = pane.createDialog(parent, "Gachaman");
		sizeForTargetScreen(dialog, parent);
		dialog.setVisible(true);
		dialog.dispose();
	}

	/** Fixed-width HTML body so the dialog's preferred size is stable on any monitor. */
	private static JComponent dialogBody(String message)
	{
		String escaped = message.replace("&", "&amp;").replace("<", "&lt;")
			.replace("\n", "<br>");
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
	private static void sizeForTargetScreen(JDialog dialog, Component parent)
	{
		dialog.setLocationRelativeTo(parent);
		dialog.pack();
		dialog.setMinimumSize(dialog.getSize());
		dialog.setLocationRelativeTo(parent);
	}

	/** A thin horizontal meter with an optional centered label. */
	static final class MeterBar extends JComponent
	{
		private static final Color TRACK = new Color(28, 28, 28);

		private final double fraction;
		private final Color barColor;
		@Nullable
		private final String label;

		MeterBar(double fraction, Color barColor, @Nullable String label)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
			this.barColor = barColor;
			this.label = label;
			setPreferredSize(new Dimension(100, 15));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 15));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(TRACK);
			g2.fillRoundRect(0, 0, w, h, 6, 6);
			int fill = (int) Math.round(w * fraction);
			if (fill > 0)
			{
				g2.setColor(barColor);
				g2.fillRoundRect(0, 0, Math.max(fill, 6), h, 6, 6);
			}
			if (label != null)
			{
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(Color.WHITE);
				int tw = g2.getFontMetrics().stringWidth(label);
				g2.drawString(label, (w - tw) / 2, h / 2 + g2.getFontMetrics().getAscent() / 2 - 1);
			}
			g2.dispose();
		}
	}

	/** A left-aligned row wrapper so BorderLayout rows behave in a BoxLayout. */
	static JPanel row(JComponent left, @Nullable JComponent right)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		panel.add(left, BorderLayout.CENTER);
		if (right != null)
		{
			panel.add(right, BorderLayout.EAST);
		}
		return panel;
	}

	static JButton button(String text)
	{
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
	static void styleCombo(javax.swing.JComboBox<?> combo)
	{
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setForeground(Color.WHITE);
		combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		combo.setFocusable(false);
		combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				javax.swing.plaf.basic.BasicArrowButton arrow = new javax.swing.plaf.basic.BasicArrowButton(
					SwingConstants.SOUTH, ColorScheme.DARKER_GRAY_COLOR.darker(),
					ColorScheme.DARKER_GRAY_COLOR.darker(), ColorScheme.LIGHT_GRAY_COLOR,
					ColorScheme.DARKER_GRAY_COLOR.darker());
				arrow.setBorder(BorderFactory.createEmptyBorder());
				return arrow;
			}
		});
		combo.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		javax.swing.DefaultListCellRenderer renderer = new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
				int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				setFont(FontManager.getRunescapeSmallFont());
				setBackground(isSelected ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
				setForeground(isSelected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				setBorder(new EmptyBorder(2, 5, 2, 5));
				return this;
			}
		};
		combo.setRenderer(renderer);
	}

	/** Dark, RuneLite-styled spinner (date/number editors alike). */
	static void styleSpinner(JComponent spinner)
	{
		if (!(spinner instanceof javax.swing.JSpinner))
		{
			return;
		}
		javax.swing.JSpinner s = (javax.swing.JSpinner) spinner;
		s.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		s.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JComponent editor = s.getEditor();
		if (editor instanceof javax.swing.JSpinner.DefaultEditor)
		{
			javax.swing.JFormattedTextField field =
				((javax.swing.JSpinner.DefaultEditor) editor).getTextField();
			field.setFont(FontManager.getRunescapeSmallFont());
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setForeground(Color.WHITE);
			field.setCaretColor(Color.WHITE);
			field.setBorder(new EmptyBorder(2, 5, 2, 5));
		}
		for (Component child : s.getComponents())
		{
			if (child instanceof JButton)
			{
				child.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			}
		}
	}

	/** Dark game-styled slider: stone thumb on a slim dark track. */
	static final class GameSliderUI extends javax.swing.plaf.basic.BasicSliderUI
	{
		private static final Color TRACK = new Color(28, 27, 25);
		private static final Color THUMB = new Color(82, 72, 58);
		private static final Color THUMB_EDGE = new Color(115, 102, 82);

		GameSliderUI(javax.swing.JSlider slider)
		{
			super(slider);
		}

		@Override
		public void paintTrack(Graphics g)
		{
			int cy = trackRect.y + trackRect.height / 2;
			g.setColor(TRACK);
			g.fillRoundRect(trackRect.x, cy - 3, trackRect.width, 6, 6, 6);
			g.setColor(ColorScheme.BRAND_ORANGE);
			int fill = thumbRect.x + thumbRect.width / 2 - trackRect.x;
			if (fill > 0)
			{
				g.fillRoundRect(trackRect.x, cy - 3, Math.min(fill, trackRect.width), 6, 6, 6);
			}
		}

		@Override
		public void paintThumb(Graphics g)
		{
			g.setColor(THUMB);
			g.fillRect(thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
			g.setColor(THUMB_EDGE);
			g.drawRect(thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 5, thumbRect.height - 5);
		}

		@Override
		public void paintFocus(Graphics g)
		{
			// no focus ring
		}
	}
}
