package com.gachaman.ui.panel;

import java.util.List;
import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.callback.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

// The same three static-import-on-demands OverviewTab already carries, for the
// same reason: this file names GachamanPanel 39 times, ColorScheme 16 and
// QuantityFormatter 10, and at 12-18 characters a qualifier that is pure budget.
// They export disjoint simple names (widget helpers and width constants,
// colours, formatters), none of which collides with anything ShopTab declares —
// and anything inherited from JPanel would win over all three anyway, since
// class members shadow imports. Comments keep the qualifier where it explains
// where a thing comes from; only the code drops it.
import static com.gachaman.ui.panel.GachamanPanel.*;
import static net.runelite.client.ui.ColorScheme.*;
import static net.runelite.client.util.QuantityFormatter.*;

/**
 * Shop: chest tiles (baked sprites, see ShopArt), style-charge purchases,
 * queued boss-themed chests and the weekly rotating card shop.
 */
@Singleton
public class ShopTab extends JPanel {
	private static final Color GOLD = new Color(230, 190, 80);

	/** The width every section must fit in; 221, derived in GachamanPanel. */
	private static final int CONTENT_WIDTH = VIEWPORT_WIDTH;

	/** Usable width inside a GachamanPanel.section(), which has 8px borders. */
	private static final int SECTION_INNER_WIDTH = SECTION_WIDTH;

	/** BorderLayout hgap used by GachamanPanel.row(). */
	private static final int ROW_GAP = 6;

	/**
	 * The pointer every clickable non-button in this tab wears.
	 *
	 * <p>Hoisted rather than called at each site because
	 * {@link Cursor#getPredefinedCursor} hands back one cached, immutable instance
	 * per type — so the three sites were already sharing this object, they were
	 * just each spelling out the lookup to get at it.
	 */
	private static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);

	private final GachaStateService stateService;
	private final ChestService chestService;
	private final WeeklyShopService weeklyShopService;
	private final CardDatabase cardDatabase;
	private final SetTable setTable;

	private final TaskService taskService;
	private final ClientThread clientThread;
	private final TimelineService timelineService;

	/**
	 * No CreditSink here on purpose: the shop never awards or spends GC itself.
	 * Every purchase on this tab goes through the service that owns the rule
	 * (ChestService, TaskService, WeeklyShopService) and those hold the sink, so
	 * a sink injected here would only be a second, unused route to the ledger.
	 */
	@Inject
	public ShopTab(GachaStateService stateService, ChestService chestService,
		WeeklyShopService weeklyShopService,
		CardDatabase cardDatabase, SetTable setTable, TaskService taskService,
		ClientThread clientThread,
		TimelineService timelineService) {
		this.timelineService = timelineService;
		this.stateService = stateService;
		this.chestService = chestService;
		this.weeklyShopService = weeklyShopService;
		this.cardDatabase = cardDatabase;
		this.setTable = setTable;
		this.taskService = taskService;
		this.clientThread = clientThread;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	void rebuild() {
		removeAll();
		GachaState state = stateService.get();
		if (state == null) {
			add(centeredNote("Log in to browse the shop."));
			revalidate();
			repaint();
			return;
		}

		// same big GC readout as the Overview tab — the shop is where it is spent
		addSection(balanceSection(state));
		addSection(buildChestSection(state));
		addSection(buildOddsSection(state));
		addSection(buildSlotChestSection(state));
		addSection(buildChargeSection(state));
		if (!state.getQueuedThemedChests().isEmpty()) {
			addSection(buildThemedSection(state));
		}
		addSection(buildWeeklySection(state));

		revalidate();
		repaint();
	}

	// GachamanPanel.WidthCap, reached unqualified through the static import at the
	// top. This tab's cap is the compile-time CONTENT_WIDTH rather than a measured
	// viewport, which is the only way its (deleted) private copy differed from the
	// shared class — so it passes the constant and the cap arithmetic is the same.
	private void addSection(JPanel section) {
		add(new WidthCap(section, CONTENT_WIDTH));
		add(Box.createVerticalStrut(6));
	}

	/**
	 * A single-line label whose text is pre-truncated with an ellipsis (via
	 * FontMetrics) so its preferred width can never exceed maxWidth. The
	 * tooltip always carries the full, untruncated detail.
	 */
	private static JLabel truncatedLine(String text, Color color, Font font, int maxWidth, String tooltip) {
		JLabel label = line(text, color, font);
		FontMetrics fm = label.getFontMetrics(font);
		if (fm.stringWidth(text) > maxWidth) {
			String ellipsis = "…";
			int budget = Math.max(0, maxWidth - fm.stringWidth(ellipsis));
			int end = text.length();
			while (end > 0 && fm.stringWidth(text.substring(0, end)) > budget) {
				end--;
			}
			// TRAILING whitespace only. addBand indents its tier rows with three
			// leading spaces to nest them under their band header, and trim() ate
			// them — so the one row long enough to need truncating was also the
			// one row that jumped back out to the margin.
			label.setText(text.substring(0, end).replaceAll("\\s+$", "") + ellipsis);
		}
		label.setToolTipText(tooltip);
		return label;
	}

	/**
	 * A word-wrapping paragraph, hard-sized to the section's real inner width.
	 *
	 * Not an HTML JLabel: Swing reads {@code width:Npx} in that CSS subset as a
	 * *preferred* span, not a cap, so a long unbroken run lays the label out
	 * wider than the column and the surrounding viewport — which has no
	 * horizontal scrollbar — clips the overflow at the right edge with no
	 * ellipsis to show for it. A JTextArea given an explicit size wraps against
	 * a real width, which is what GachamanPanel.textBlock already does at this
	 * same 205px. Every caller passes plain prose, so nothing here needs markup.
	 *
	 * <p>Which is why the body IS that shared helper now: it builds the identical
	 * area — same JTextArea flags, same font, same setSize-then-measure, same
	 * preferred and maximum pinned to (width, measured height). This wrapper adds
	 * the one thing it does not.
	 */
	private static JComponent wrappedText(String text, Color color) {
		JTextArea area = textBlock(text, color, SECTION_INNER_WIDTH);
		// the MINIMUM too, and deliberately not pushed down into textBlock: the
		// measured height is only correct for a 205px wrap, so a BoxLayout free to
		// shrink this area would re-wrap to more lines than the height it was given
		// and silently cut the last one off the bottom. HelpTab's and PartyTab's
		// callers of textBlock do not want that pin, so it stays here.
		area.setMinimumSize(area.getPreferredSize());
		return area;
	}

	/** A button that stretches to the full inner width of a section. */
	private static JButton fullWidthButton(String text) {
		JButton button = button(text);
		button.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, button.getPreferredSize().height));
		return button;
	}

	// --- Chests ---

	private JPanel buildChestSection(GachaState state) {
		JPanel section = section("Chests");
		if (chestService.getPending() != null) {
			section.add(smallLine("A reveal is in progress…", LIGHT_GRAY_COLOR));
			section.add(Box.createVerticalStrut(4));
		}
		for (Tuning.Chest tier : Tuning.Chest.values()) {
			long price = Tuning.CHEST_PRICE_GC.get(tier);
			boolean affordable = state.getGc() >= price;
			double fraction = price <= 0 ? 1 : Math.min(1.0, (double) state.getGc() / price);
			int remaining = tier == Tuning.Chest.RUSTY
				? rustyRemaining(chestService.rustyChestsOpened())
				: -1;
			section.add(new ChestTile(tier, affordable, fraction, remaining));
			section.add(Box.createVerticalStrut(5));
		}
		return section;
	}

	/**
	 * Lifetime Rusty opens left, floored at zero.
	 *
	 * <p>The floor is the whole point, because ChestTile's `remaining` overloads one
	 * int with three meanings: -1 is "this tier has no cap", 0 is "retired", and
	 * anything positive prints as "N left". A bare {@code CAP - opened} lets an
	 * opened count of CAP+1 collide with the uncapped sentinel, and the retired tile
	 * then renders as a perfectly ordinary chest: every test ChestTile makes is
	 * either {@code remaining == 0} (the greying, the "Rusted away" caption, the
	 * "rusted away" tooltip, the affordable flag the mouse listener reads) or
	 * {@code remaining > 0} (the "N left" suffix), and -1 answers no to both. The
	 * click that follows is refused by ChestService.rustyAvailable(), and the player
	 * is told the reveal is busy or their GC is short — neither of which is true.
	 *
	 * <p>That count is not hypothetical: it comes off the save through
	 * getChestsOpenedByTier().getOrDefault(...), so a state file written by an older
	 * build, migrated, or edited by hand can sit above a cap that openChest's own
	 * guard only ever enforced going forward. Clamping is a no-op for every count
	 * openChest can produce (0..CAP) and picks the already-correct retired rendering
	 * for the rest.
	 *
	 * <p>Package-private so the test can pin that -1 never comes back from here.
	 */
	static int rustyRemaining(int opened) {
		return Math.max(0, Tuning.RUSTY_LIFETIME_CAP - opened);
	}

	private void tryOpenChest(Tuning.Chest tier) {
		long price = Tuning.CHEST_PRICE_GC.get(tier);
		String name = chestName(tier);
		if (!confirm(this, "Open chest",
			"Open " + article(name) + " " + name + " for "
				+ formatNumber(price) + " GC?")) {
			return;
		}
		// chest rolls read live client skill levels — client thread only
		clientThread.invokeLater(() -> {
			if (chestService.openChest(tier) == null) {
				SwingUtilities.invokeLater(() ->
					info(this, "The chest cannot be opened right now"
						+ " (another reveal in progress, or not enough GC)."));
			}
		});
	}

	/**
	 * Display names in {@link Tuning.Chest} declaration order — RUSTY, BATTERED,
	 * GILDED, ORNATE — indexed by ordinal.
	 *
	 * <p>A table rather than the switch this used to be, and the trade is worth
	 * stating: the switch's {@code default:} arm silently answered "Ornate Chest"
	 * for a tier nobody had written a case for, where the table throws. That is
	 * the better failure — a new chest tier appearing under the wrong name is a
	 * bug that ships — but it is only safe because ShopTabTablesTest pins the
	 * length against Chest.values() and every entry against the name it replaced,
	 * so a fifth constant fails the build rather than the player's screen.
	 */
	static final String[] CHEST_NAMES = {
		"Rusty Chest", "Battered Chest", "Gilded Chest", "Ornate Chest"};

	static String chestName(Tuning.Chest tier) {
		return CHEST_NAMES[tier.ordinal()];
	}

	// --- Odds disclosure ---

	/** The two reach bands, as collapse targets. */
	private enum Band {
		WIELDABLE,
		HEADROOM
	}

	/**
	 * EDT-only. Which bands the player has opened.
	 *
	 * <p>Empty to start, which is what makes both bands collapsed by default. A
	 * field rather than a local because rebuild() runs on every state change and
	 * would otherwise slam a band shut again the moment a kill landed.
	 */
	private final EnumSet<Band> expandedBands = EnumSet.noneOf(Band.class);

	/** EDT-only. Which chest the odds panel is describing. */
	private Tuning.Chest oddsTier = Tuning.Chest.BATTERED;

	/**
	 * EDT-only. Last answer from the client thread. Null until the first one lands;
	 * the odds read live skill levels and so cannot be computed on the EDT.
	 */
	@javax.annotation.Nullable
	private ChestService.OddsDisclosure oddsSnapshot;

	/** EDT-only. One request in flight at a time, so a rebuild storm cannot pile up. */
	private boolean oddsRequested;

	/**
	 * EDT-only. The pity counter the live snapshot was asked for. Stamped from the EDT's
	 * own view of the state rather than from the answer, so the staleness test below
	 * compares like with like and cannot ping-pong with the client thread.
	 */
	private int oddsStamp = -1;

	private JPanel buildOddsSection(GachaState state) {
		JPanel section = section("Chest Odds");
		JComboBox<Tuning.Chest> picker =
			new JComboBox<>(Tuning.Chest.values());
		picker.setSelectedItem(oddsTier);
		picker.addActionListener(e -> {
			Tuning.Chest picked = (Tuning.Chest) picker.getSelectedItem();
			if (picked == null || picked == oddsTier) {
				return;
			}
			oddsTier = picked;
			oddsSnapshot = null;
			// deferred: rebuild() disposes this very combo, and doing that from inside
			// its own action listener runs while the popup is still closing
			SwingUtilities.invokeLater(this::rebuild);
		});
		// labelled through styleCombo, not by a renderer set beforehand: styleCombo
		// installs the only renderer the box has, and would overwrite it
		styleCombo(picker, ShopTab::chestName);
		picker.setAlignmentX(Component.LEFT_ALIGNMENT);
		picker.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, 24));
		section.add(picker);
		section.add(Box.createVerticalStrut(6));

		ChestService.OddsDisclosure odds = oddsSnapshot;
		// the pity counter moves the whole rarity curve, so a snapshot taken before an
		// open is stale by definition; levels move it too, hence the Refresh button
		if (odds == null || odds.getTier() != oddsTier
			|| oddsStamp != state.getOpensSinceEpic()) {
			requestOdds();
		}
		if (odds == null) {
			section.add(wrappedText("Reading your levels…", MEDIUM_GRAY_COLOR));
			return section;
		}

		for (Rarity rarity : Rarity.values()) {
			double fraction = odds.getRarityPercent()[rarity.ordinal()] / 100;
			section.add(oddsRow(rarity.getDisplayName(), pct(fraction), rarity.getColor(),
				rarity.getDisplayName() + " card: " + pct(fraction) + " per card", 0));
		}

		section.add(Box.createVerticalStrut(4));
		if (odds.isPityBreakNext()) {
			section.add(wrappedText("Your next open is a guaranteed Epic or better.",
				new Color(150, 190, 240)));
		}
		else if (odds.getPityBonusPercent() > 0) {
			// floored at 1 because the branch above already owns the zero case, so
			// the count here is always at least one — which the old sentence then
			// printed as the ungrammatical "within 1 more opens"
			int remaining = Math.max(1, odds.getPityHardCap() - odds.getOpensSinceEpic());
			section.add(wrappedText("Pity is already in those numbers: +"
				+ String.format(Locale.ROOT, "%.1f", odds.getPityBonusPercent())
				+ "% moved out of Common. A guaranteed Epic+ lands "
				+ (remaining == 1 ? "on your next open." : "within " + remaining + " more opens."),
				new Color(150, 190, 240)));
		}

		List<ChestService.TierOdds> wieldable = new ArrayList<>();
		List<ChestService.TierOdds> headroom = new ArrayList<>();
		for (ChestService.TierOdds row : odds.getRows()) {
			if (row.getTierKey().isEmpty()) {
				continue; // the untiered band is one nameless row; it prints as a total
			}
			(row.isWieldableNow() ? wieldable : headroom).add(row);
		}
		// a ladder can sit in both bands at once, so each side is told the other's
		// keys and can explain the overlap rather than look like it double-counted
		Set<String> wieldableKeys = new HashSet<>();
		for (ChestService.TierOdds row : wieldable) {
			wieldableKeys.add(row.getTierKey());
		}
		Set<String> headroomKeys = new HashSet<>();
		for (ChestService.TierOdds row : headroom) {
			headroomKeys.add(row.getTierKey());
		}
		// short titles: these share their line with a right-flush percentage, and
		// the label half is ellipsized against whatever the value leaves over —
		// the long form of each belongs in the tooltip, where it has room
		addBand(section, Band.WIELDABLE, "Wieldable now",
			"Gear you can equip at your levels today.",
			wieldable, odds.getWieldableTotal(), GOLD, headroomKeys);
		// "Not yet wieldable", not "Headroom": headroom is the house's word for the
		// slice it leans against, and to a reader it suggests spare capacity — the
		// opposite of the restriction it actually names. This pairs with the band
		// above it and says the condition outright. Not "Locked" either: the lean
		// never locks, and the footer two rows down promises exactly that.
		addBand(section, Band.HEADROOM, "Not yet wieldable",
			"Gear still out of reach — drawn less often, never locked out.",
			headroom, odds.getHeadroomTotal(), new Color(200, 140, 90), wieldableKeys);
		if (odds.getUntieredTotal() > 0) {
			section.add(Box.createVerticalStrut(5));
			section.add(oddsRow("No tier gate", pct(odds.getUntieredTotal()),
				LIGHT_GRAY_COLOR,
				"Gear with no tier ladder — never held back by the lean, but still subject"
					+ " to its own in-game requirements.", 0));
		}

		section.add(Box.createVerticalStrut(6));
		JButton refresh = fullWidthButton("Refresh");
		refresh.setToolTipText("Re-read your levels. The odds move every time you level up.");
		refresh.addActionListener(e -> {
			oddsSnapshot = null;
			rebuild();
		});
		section.add(refresh);
		section.add(Box.createVerticalStrut(4));
		section.add(wrappedText(
			"Real numbers for one ordinary card, computed by the roll's own code."
				+ " Gear you can wield today is drawn "
				+ String.format(Locale.ROOT, "%.2f", 1 / Tuning.HOUSE_LEAN_HEADROOM_WEIGHT)
				+ "x as often as gear still out of reach — the house leans, it never locks."
				+ " Not counted here: the jackpot tier upgrade, the hologram that replaces a"
				+ " card outright, and the pity guarantee on the first card. Slot Chests roll"
				+ " Gilded odds.",
			MEDIUM_GRAY_COLOR));
		return section;
	}

	/**
	 * One "Wieldable now / Not yet wieldable" group: a clickable header carrying
	 * the band total, and under it every tier in the band.
	 *
	 * <p>Every tier, not the first few with the rest folded into a "+N more tiers"
	 * line: that fold saved a little height and cost the reader the one thing this
	 * panel exists to give them, which is the actual ladder. The ladders are long,
	 * so the header collapses instead — closed to start, and the tiers are built
	 * either way and merely hidden, so opening one is a relayout rather than a
	 * rebuild and the scroll position survives the click.
	 *
	 * @param alsoInOtherBand tier keys that also appear in the opposite band, so a
	 *                        split ladder can say so instead of looking duplicated
	 */
	private void addBand(JPanel section, Band band, String title, String blurb,
		List<ChestService.TierOdds> rows, double total, Color color,
		Set<String> alsoInOtherBand) {
		if (rows.isEmpty()) {
			return;
		}
		JPanel tiers = new JPanel();
		tiers.setLayout(new BoxLayout(tiers, BoxLayout.Y_AXIS));
		tiers.setOpaque(false);
		tiers.setAlignmentX(Component.LEFT_ALIGNMENT);
		tiers.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, Integer.MAX_VALUE));
		for (ChestService.TierOdds row : rows) {
			tiers.add(oddsRow("   " + row.getDisplayName(), pct(row.getProbability()),
				LIGHT_GRAY_COLOR,
				rowTooltip(row, alsoInOtherBand.contains(row.getTierKey())), 0));
		}
		tiers.setVisible(expandedBands.contains(band));

		section.add(Box.createVerticalStrut(5));
		section.add(bandHeader(band, title, blurb, rows.size(), total, color, tiers));
		section.add(tiers);
	}

	/**
	 * The clickable line a band collapses to: a +/- marker, the band name, and the
	 * band total flush right.
	 *
	 * <p>ASCII +/- rather than a triangle glyph — the RuneScape faces have no
	 * caret, and a missing glyph draws as tofu. The marker is pinned to a fixed
	 * width so flipping it cannot shift the title's ellipsis budget and make the
	 * name change length as the band opens.
	 *
	 * <p>The line itself is an {@link #oddsRow}: a band header is that same
	 * label-plus-flush-right-value row, with a marker in front. So it is built as
	 * one — the marker's column is handed over as the row's `reserved` width and
	 * the marker goes into the WEST slot afterwards, which leaves the title
	 * measured against exactly the space it had when this method spelled the
	 * arithmetic out itself.
	 */
	private JPanel bandHeader(Band band, String title, String blurb, int tierCount,
		double total, Color color, JPanel tiers) {
		Font font = FontManager.getRunescapeSmallFont();
		JLabel marker = line(expandedBands.contains(band) ? "-" : "+", color, font);
		FontMetrics fm = marker.getFontMetrics(font);
		Dimension markerSize = new Dimension(
			Math.max(fm.stringWidth("+"), fm.stringWidth("-")),
			marker.getPreferredSize().height);
		marker.setPreferredSize(markerSize);
		marker.setMinimumSize(markerSize);
		marker.setMaximumSize(markerSize);

		// the count keeps a shut band honest about how much it is holding back
		String tip = blurb + " " + pct(total) + " per card, over "
			+ tierCount + (tierCount == 1 ? " tier." : " tiers.")
			+ " Click to open or close.";
		JPanel row = oddsRow(title, pct(total), color, tip, markerSize.width + ROW_GAP);
		row.setCursor(HAND);
		marker.setToolTipText(tip);
		row.add(marker, BorderLayout.WEST);

		MouseAdapter toggle = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				boolean open = !expandedBands.contains(band);
				if (open) {
					expandedBands.add(band);
				}
				else {
					expandedBands.remove(band);
				}
				marker.setText(open ? "-" : "+");
				tiers.setVisible(open);
				// the section, not the row: BoxLayout skips an invisible child, so the
				// height that has to be recomputed belongs to the container above
				Container parent = row.getParent();
				if (parent != null) {
					parent.revalidate();
					parent.repaint();
				}
			}
		};
		// on the labels as well as the row, and this is not belt-and-braces: giving a
		// component a tooltip registers it with ToolTipManager, which adds a mouse
		// listener, which makes the LABEL the event target. A listener on the row
		// alone never fires, because the label under the pointer swallows the press.
		//
		// The children rather than a named list of them: the row holds exactly the
		// title, the total and the marker, and Swing does not bubble mouse events, so
		// each still ends up with one listener and no component is missed.
		row.addMouseListener(toggle);
		for (Component part : row.getComponents()) {
			part.addMouseListener(toggle);
			part.setCursor(HAND);
		}
		return row;
	}

	/**
	 * Most names a row's tooltip spells out before it starts counting instead. A
	 * tooltip taller than the screen is its own kind of unreadable; the remainder
	 * is disclosed as a count rather than dropped in silence.
	 */
	private static final int MAX_TOOLTIP_CARDS = 24;

	/**
	 * The tooltip on one tier row.
	 *
	 * <p>Out-of-reach rows name the cards behind them. That is the whole point for
	 * a ladder that appears in both bands — "Hardleather" under Wieldable now AND
	 * under Not yet wieldable is correct (the Defence gate lands on the body only)
	 * but reads as a bug until the panel can say which pieces are the ones still
	 * out of reach.
	 *
	 * <p>HTML, so everything interpolated is escaped: card names come from the item
	 * cache, and {@link #pct} answers "&lt;0.1%" for a tier that rounds to zero —
	 * which Swing's HTML 3.2 parser reads as an unclosed tag and swallows the rest.
	 */
	static String rowTooltip(ChestService.TierOdds row, boolean splitAcrossBands) {
		StringBuilder html = new StringBuilder("<html>");
		html.append(escape(row.getDisplayName())).append(" - ")
			.append(escape(pct(row.getProbability()))).append(" per card");
		List<String> names = row.getCardNames();
		if (row.isWieldableNow() || names == null || names.isEmpty()) {
			return html.append("</html>").toString();
		}
		html.append("<br>");
		html.append(splitAcrossBands
			? "Some " + escape(row.getDisplayName())
				+ " gear is already within reach. Still out of reach:"
			: "Out of reach:");
		int shown = Math.min(names.size(), MAX_TOOLTIP_CARDS);
		for (int i = 0; i < shown; i++) {
			html.append("<br>&nbsp;&nbsp;").append(escape(names.get(i)));
		}
		if (names.size() > shown) {
			html.append("<br>&nbsp;&nbsp;+").append(names.size() - shown).append(" more");
		}
		return html.append("</html>").toString();
	}

	/**
	 * A label/value pair on one line, the value flush right. The label is ellipsized
	 * against whatever the value leaves over, so a long tier name can never widen the
	 * panel; the tooltip always carries the untruncated text.
	 *
	 * @param reserved width to hold back from the label's ellipsis budget for a
	 *                 component the caller will add itself, gap included. Band
	 *                 headers put their +/- marker in the WEST slot after this
	 *                 returns, and the title has to be measured as though it were
	 *                 already there — otherwise the name changes length the moment
	 *                 the marker appears. Plain rows pass 0.
	 */
	private static JPanel oddsRow(String label, String value, Color color, String tooltip,
		int reserved) {
		JPanel row = new JPanel(new BorderLayout(ROW_GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel right = line(value, color, FontManager.getRunescapeSmallFont());
		right.setToolTipText(tooltip);
		int budget = SECTION_INNER_WIDTH - reserved - right.getPreferredSize().width - ROW_GAP;
		JLabel left = truncatedLine(label, color, FontManager.getRunescapeSmallFont(),
			budget, tooltip);
		row.add(left, BorderLayout.CENTER);
		row.add(right, BorderLayout.EAST);
		int height = Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
		row.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, height));
		return row;
	}

	/**
	 * A row that rounds to 0.0% would read as impossible, which is the one claim the
	 * headroom band must never make, so anything non-zero floors at "&lt;0.1%".
	 *
	 * <p>Package-private for the test that pins that leading {@code <} — it is the
	 * reason addBand escapes this before putting it in an HTML tooltip.
	 */
	static String pct(double fraction) {
		double percent = fraction * 100;
		if (percent > 0 && percent < 0.05) {
			return "<0.1%";
		}
		return String.format(Locale.ROOT, "%.1f%%", percent);
	}

	/**
	 * Odds read live skill levels, so they must be computed on the client thread and
	 * handed back to the EDT. One flight at a time: rebuild() runs on every state
	 * change and would otherwise queue a client-thread job per rebuild.
	 *
	 * <p>Package-private for the test that pins the in-flight flag against a body
	 * that throws — see the try/finally below for why that case needs pinning.
	 */
	void requestOdds() {
		if (oddsRequested) {
			return;
		}
		oddsRequested = true;
		final Tuning.Chest wanted = oddsTier;
		GachaState current = stateService.get();
		final int stamp = current == null ? -1 : current.getOpensSinceEpic();
		clientThread.invokeLater(() -> {
			ChestService.OddsDisclosure snapshot = null;
			try {
				snapshot = chestService.oddsFor(wanted);
			}
			finally {
				// the flag is cleared by the continuation, so the continuation has to be
				// posted on EVERY exit from this body and not just the happy one. oddsFor
				// walks the whole card database and reads live skill levels; if it throws,
				// a continuation posted only on success leaves oddsRequested latched true
				// for the rest of the session, the guard at the top turns every later
				// request into a no-op, and the panel sits on "Reading your levels…"
				// forever with no way back short of a client restart. There is no catch
				// here on purpose: the throw still leaves this lambda and reaches
				// ClientThread's own handler, which logs it — the failure is made
				// recoverable, not hidden.
				final ChestService.OddsDisclosure landed = snapshot;
				SwingUtilities.invokeLater(() -> {
					oddsRequested = false;
					if (landed == null) {
						// oddsFor() is not @Nullable and has a single non-null return, so
						// null here means it threw. Deliberately neither retried nor
						// rebuilt: buildOddsSection re-requests whenever the snapshot is
						// null, so rebuilding on this path would bounce a failing call
						// between the client thread and the EDT in a tight loop forever.
						// Clearing the flag alone is enough — the panel keeps its
						// placeholder and the next ordinary rebuild retries exactly once.
						return;
					}
					if (wanted != oddsTier) {
						// the player switched chests mid-flight: this answer describes the
						// wrong one, so drop it and ask again rather than showing it
						requestOdds();
						return;
					}
					oddsSnapshot = landed;
					oddsStamp = stamp;
					rebuild();
				});
			}
		});
	}

	// --- Slot-targeted chests ---

	private GearSlot selectedSlotChest = GearSlot.WEAPON;

	private JPanel buildSlotChestSection(GachaState state) {
		JPanel section = section("Slot Chests");
		long price = Tuning.CHEST_PRICE_GC.get(Tuning.Chest.GILDED);
		JComboBox<GearSlot> picker =
			new JComboBox<>(GearSlot.values());
		picker.setSelectedItem(selectedSlotChest);
		picker.addActionListener(e ->
			selectedSlotChest = (GearSlot) picker.getSelectedItem());
		styleCombo(picker, GearSlot::getDisplayName);
		picker.setAlignmentX(Component.LEFT_ALIGNMENT);
		picker.setMaximumSize(new Dimension(SECTION_INNER_WIDTH, 24));
		section.add(picker);
		section.add(Box.createVerticalStrut(4));

		JButton open = fullWidthButton("Open — " + formatNumber(price) + " GC");
		open.setToolTipText("One card, rolled only from the chosen slot's pool (Gilded odds).");
		open.setEnabled(state.getGc() >= price && chestService.getPending() == null);
		open.addActionListener(e -> {
			GearSlot slot = (GearSlot) picker.getSelectedItem();
			String slotName = slot == null ? "" : slot.getDisplayName();
			if (slot != null && confirm(this, "Slot chest",
				"Open " + article(slotName) + " " + slotName + " chest for "
					+ formatNumber(price) + " GC?\nOne card, "
					+ slotName + " slot only.")) {
				clientThread.invokeLater(() -> chestService.openSlotChest(slot));
			}
		});
		section.add(open);
		section.add(Box.createVerticalStrut(3));
		section.add(wrappedText(
			"Gilded price, one card — but every roll is the slot you chose.",
			MEDIUM_GRAY_COLOR));
		return section;
	}

	// --- Style charges ---

	private JPanel buildChargeSection(GachaState state) {
		JPanel section = section("Style Charges");
		int freeComp = state.getFreeCompactors();
		int freeExt = state.getFreeExtenders();
		if (freeComp > 0 || freeExt > 0) {
			// Pluralised both ways. Stock starts at one of each and is spent one at
			// a time, so a lone "1 Compactor" is the ordinary reading of this line,
			// not the edge case.
			String banner = (freeComp + freeExt == 1 ? "Free voucher: " : "Free vouchers: ")
				+ (freeComp > 0 ? freeComp + (freeComp == 1 ? " Compactor" : " Compactors") : "")
				+ (freeComp > 0 && freeExt > 0 ? " · " : "")
				+ (freeExt > 0 ? freeExt + (freeExt == 1 ? " Extender" : " Extenders") : "");
			section.add(truncatedLine(banner, GOLD,
				FontManager.getRunescapeSmallFont(), SECTION_INNER_WIDTH, banner));
			section.add(Box.createVerticalStrut(3));
		}
		ActiveTask task = state.getActiveTask();
		if (task == null) {
			section.add(wrappedText(
				"Charges apply to your CURRENT contract — sign one first.",
				MEDIUM_GRAY_COLOR));
			return section;
		}
		if (task.getAppliedCharge() != null) {
			boolean compactor = "COMPACTOR".equals(task.getAppliedCharge());
			String applied = "Applied to this contract: " + (compactor ? "Compactor" : "Extender");
			section.add(truncatedLine(applied, new Color(150, 190, 240),
				FontManager.getRunescapeBoldFont(), SECTION_INNER_WIDTH, applied));
			section.add(Box.createVerticalStrut(3));
			section.add(wrappedText(
				"One charge per contract — available again once you sign a new one.",
				MEDIUM_GRAY_COLOR));
			return section;
		}
		section.add(chargeButton(true, "Compactor", Tuning.COMPACTOR_PRICE_GC, freeComp,
			state.getGc(),
			"This contract counts double toward the style cycle, and each"
				+ " kill counts double toward the contract itself (the skipped count pays no GC)."));
		section.add(Box.createVerticalStrut(4));
		section.add(chargeButton(false, "Extender", Tuning.EXTENDER_PRICE_GC, freeExt,
			state.getGc(),
			"This contract counts only half toward the style cycle."));
		section.add(Box.createVerticalStrut(4));
		section.add(wrappedText(
			"Compactor: this contract counts x2 toward the style cycle AND kills count x2"
				+ " toward the contract (skips pay no GC). Extender: it counts x0.5 toward"
				+ " the cycle.",
			MEDIUM_GRAY_COLOR));
		return section;
	}

	/**
	 * One style-charge purchase button. The two charges differ only in their name,
	 * their price, which voucher stock pays for them and the prose that explains
	 * what they do — everything else (the FREE-voucher label, the voucher sentence
	 * bolted onto the tooltip, the "affordable or voucher" enablement, the confirm
	 * and purchase the click runs) was the same five statements twice over.
	 *
	 * @param tip the charge's own explanation; the voucher sentence is appended
	 *            here so neither call site has to carry a copy of it
	 */
	JButton chargeButton(boolean compactor, String pretty, int price, int free,
		long gc, String tip) {
		JButton button = fullWidthButton(free > 0
			? pretty + " — FREE voucher"
			: pretty + " — " + formatNumber(price) + " GC");
		button.setToolTipText(tip + (free > 0 ? " Uses your free voucher — no GC." : ""));
		button.setEnabled(free > 0 || gc >= price);
		button.addActionListener(e -> buyCharge(compactor, pretty, price));
		return button;
	}

	private void buyCharge(boolean compactor, String pretty, int price) {
		GachaState state = stateService.get();
		boolean voucher = state != null
			&& (compactor ? state.getFreeCompactors() > 0 : state.getFreeExtenders() > 0);
		String cost = voucher ? "using your free voucher? (no GC)"
			: "for " + formatNumber(price) + " GC?";
		if (!confirm(this, "Buy " + pretty,
			"Apply a " + pretty + " to your current contract " + cost)) {
			return;
		}
		// client thread: serializes the purchase with kill/completion processing
		final boolean voucherUsed = voucher;
		clientThread.invokeLater(() -> {
			if (taskService.purchaseCharge(compactor)) {
				timelineService.onChargePurchased(compactor, voucherUsed);
			}
			else {
				SwingUtilities.invokeLater(() -> info(this,
					"Purchase failed — you need an active contract, no charge applied yet,"
						+ " and enough GC."));
			}
		});
	}

	// --- Themed chests ---

	private JPanel buildThemedSection(GachaState state) {
		JPanel section = section("Boss Chests");
		for (String tag : state.getQueuedThemedChests()) {
			JButton open = button("Open");
			open.addActionListener(e -> clientThread.invokeLater(() -> {
				if (chestService.openThemedChest(tag) == null) {
					SwingUtilities.invokeLater(() ->
						info(this, "The chest cannot be opened right now."));
				}
			}));
			String name = themedName(tag);
			int labelWidth = SECTION_INNER_WIDTH - open.getPreferredSize().width - ROW_GAP;
			JLabel label = truncatedLine(name, GOLD,
				FontManager.getRunescapeSmallFont(), labelWidth, name);
			section.add(row(label, open));
			section.add(Box.createVerticalStrut(3));
		}
		return section;
	}

	private String themedName(String tag) {
		for (SetTable.CardSet set : setTable.getSets()) {
			if (set.getSetKey().equals(tag)) {
				return set.getName() + " chest";
			}
		}
		if (tag == null || tag.isEmpty()) {
			return "Themed chest";
		}
		return Character.toUpperCase(tag.charAt(0)) + tag.substring(1) + " chest";
	}

	// --- Weekly shop ---

	private JPanel buildWeeklySection(GachaState state) {
		JPanel section = section("Weekly Shop");
		List<WeeklyShopService.ShopSlot> offers = weeklyShopService.currentOffers();
		if (offers.isEmpty()) {
			section.add(wrappedText("Stock arrives once the card database is ready.",
				MEDIUM_GRAY_COLOR));
			return section;
		}
		for (WeeklyShopService.ShopSlot slot : offers) {
			final int index = slot.getSlotIndex();
			final int price = slot.getPriceGc();
			final String cardName = slot.getCard().getName();
			String priceText = formatNumber(price) + " GC";
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
				if (!confirm(this, "Weekly shop",
					"Buy " + cardName + " for " + formatNumber(price) + " GC?")) {
					return;
				}
				if (weeklyShopService.purchase(index) == null) {
					info(this, "Purchase failed (already bought or not enough GC).");
				}
			});
			section.add(buy);
			section.add(Box.createVerticalStrut(5));
		}
		section.add(Box.createVerticalStrut(2));
		section.add(smallLine("Stock resets weekly.", MEDIUM_GRAY_COLOR));
		return section;
	}



	// --- Chest tile component ---

	private final class ChestTile extends JComponent {
		private final Tuning.Chest tier;
		private final boolean affordable;
		private final double fraction;
		/** Lifetime opens left for capped tiers; -1 = uncapped. 0 = retired. */
		private final int remaining;

		ChestTile(Tuning.Chest tier, boolean affordable, double fraction, int remaining) {
			this.tier = tier;
			this.affordable = affordable && remaining != 0;
			this.fraction = fraction;
			this.remaining = remaining;
			setPreferredSize(new Dimension(Math.min(120, SECTION_INNER_WIDTH), 62));
			setMaximumSize(new Dimension(SECTION_INNER_WIDTH, 62));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			if (remaining == 0) {
				setToolTipText(chestName(tier) + " — rusted away ("
					+ Tuning.RUSTY_LIFETIME_CAP + " of " + Tuning.RUSTY_LIFETIME_CAP + " opened)");
			}
			else {
				// pluralised rather than "card(s)": the tile's own face already says
				// "1 card" / "3 cards" properly, and the tooltip sat right under it
				int cards = Tuning.CHEST_CARDS.get(tier);
				setToolTipText(chestName(tier) + " — " + cards + (cards == 1 ? " card, " : " cards, ")
					+ formatNumber(Tuning.CHEST_PRICE_GC.get(tier)) + " GC"
					+ (remaining > 0 ? ", " + remaining + " left ever" : ""));
			}
			if (this.affordable) {
				setCursor(HAND);
			}
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (ChestTile.this.affordable && cardDatabase.isReady()) {
						tryOpenChest(ChestTile.this.tier);
					}
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			// tile background + border
			g2.setColor(DARKER_GRAY_COLOR);
			g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
			// the LIT trim, always: an unaffordable tile takes the grey instead, so
			// this branch never wants the dimmed colour the icon uses
			g2.setColor(affordable ? trimColor(tier) : MEDIUM_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1.2f));
			g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

			// the chest icon, baked. It was drawn from scratch here on every repaint
			// even though it only ever depends on the tier and whether the tile is
			// affordable — eight pictures in total, so it is eight PNGs now. See
			// ShopArt in test scope for the drawing itself and ShopIconBakeTest for
			// the pixel comparison against it.
			g2.drawImage(chestIcon(tier, affordable), ICON_X, ICON_Y, null);

			// texts
			int tx = 56;
			g2.setFont(FontManager.getRunescapeBoldFont());
			g2.setColor(affordable ? Color.WHITE : MEDIUM_GRAY_COLOR);
			g2.drawString(chestName(tier), tx, 22);
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(affordable ? GOLD : MEDIUM_GRAY_COLOR);
			if (remaining == 0) {
				g2.drawString("Rusted away", tx, 38);
			}
			else {
				int cards = Tuning.CHEST_CARDS.get(tier);
				g2.drawString(formatNumber(Tuning.CHEST_PRICE_GC.get(tier)) + " GC  ·  "
					+ cards + (cards == 1 ? " card" : " cards")
					+ (remaining > 0 ? "  ·  " + remaining + " left" : ""),
					tx, 38);
			}

			// affordability progress bar when locked
			if (!affordable && remaining != 0) {
				int barX = tx;
				int barW = Math.max(20, w - tx - 10);
				int barY = h - 15;
				g2.setColor(new Color(24, 24, 24));
				g2.fillRoundRect(barX, barY, barW, 6, 4, 4);
				int fill = (int) Math.round(barW * Math.max(0, Math.min(1, fraction)));
				if (fill > 0) {
					g2.setColor(new Color(226, 148, 62, 200));
					g2.fillRoundRect(barX, barY, Math.max(fill, 4), 6, 4, 4);
				}
			}
			g2.dispose();
		}
	}

	/**
	 * Tile trim per tier, in {@link Tuning.Chest} declaration order — see
	 * {@link #CHEST_NAMES} for why this is a table and not a switch.
	 *
	 * <p>The chest BODY colour used to live beside this one. It does not any more:
	 * the only thing that ever read it was the icon, which is baked, so the body
	 * palette moved out to ShopArt with the drawing it belongs to. Trim stays
	 * because the tile's own border still asks for it at paint time.
	 */
	static final Color[] CHEST_TRIMS = {
		new Color(154, 96, 52),
		new Color(146, 126, 96),
		new Color(230, 190, 80),
		new Color(255, 196, 60)};

	static Color trimColor(Tuning.Chest tier) {
		return CHEST_TRIMS[tier.ordinal()];
	}

	/**
	 * Where the baked icon's top-left corner sits inside the tile.
	 *
	 * <p>Not the icon box's own (8, 12): the sprite carries two pixels of margin
	 * on every side so the 1.5f outline and its antialiasing are not cropped, so
	 * it is blitted two pixels up and to the left of where the drawing starts.
	 * ShopArt.ORIGIN_X / ORIGIN_Y are these numbers, and ShopIconBakeTest pins
	 * them against each other — get them wrong and the chest sits two pixels off
	 * its old position, which nobody would notice by eye.
	 */
	static final int ICON_X = 6;
	static final int ICON_Y = 10;

	/**
	 * Baked chest icons, keyed tier-and-lit. Loaded once each and held forever:
	 * eight ~40x42 sprites is a few kilobytes, and the alternative is hitting the
	 * jar from inside paintComponent.
	 *
	 * <p>A plain HashMap with no synchronization is correct here because every
	 * reader is paintComponent, which Swing only ever calls on the EDT.
	 */
	private static final Map<String, Image> CHEST_ICONS = new HashMap<>();

	static Image chestIcon(Tuning.Chest tier, boolean lit) {
		// Locale.ROOT, not the default locale: a Turkish client lower-cases the I
		// in GILDED to a dotless i and would then ask the jar for a file that is
		// not in it.
		//
		// loadImageResource THROWS on a resource that is not there, and nothing
		// here catches it — which is a real difference from the drawing this
		// replaced, since a drawing cannot fail to be found. It is the right shape
		// anyway: the sprites are inside the jar or they are not, there is no
		// runtime condition in between, and ShopIconBakeTest loads all eight
		// through this very method, so a packaging slip fails the build instead of
		// reaching a paintComponent. (The ceremonies' ArtCache swallows failures
		// and skips the draw instead — it wants that, because it also runs against
		// art it can do without. A chest tile without its chest is not that.)
		return CHEST_ICONS.computeIfAbsent(
			tier.name().toLowerCase(Locale.ROOT) + (lit ? "-lit" : "-dim"),
			key -> ImageUtil.loadImageResource(ShopTab.class,
				"/com/gachaman/ui/chest-tile-" + key + ".png"));
	}
}
