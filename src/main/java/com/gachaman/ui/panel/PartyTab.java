package com.gachaman.ui.panel;

import java.util.List;
import static com.gachaman.ui.panel.GachamanPanel.measuredWidth;
import static com.gachaman.ui.panel.GachamanPanel.textBlock;
import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.party.*;
import com.gachaman.service.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.ui.*;

/**
 * The party page: one line per RuneLite Party member, showing the style they
 * rolled, their combat level and how far along their contract is.
 *
 * Every line is SELF-REPORTED — it is whatever that member's own client chose
 * to broadcast, taken on trust exactly like the rest of the party layer. The
 * tab renders {@link PartyPresenceService.Row}s and touches neither PartyService
 * nor Client itself; the service has already clipped and clamped every remote
 * value, so a hostile client cannot reach the layout through here.
 */
@Singleton
public class PartyTab extends JPanel {

	/** Horizontal padding a GachamanPanel.section() adds (8px borders each side). */
	private static final int SECTION_PADDING = 16;
	/** Side of the drawn style chip. */
	private static final int SWATCH = 10;

	private static final Color MUTED = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color TAINT_RED = new Color(190, 60, 55);
	/** Side of the drawn Patron's Mark pip. */
	private static final int PIP = 9;
	/**
	 * The Patron's Mark by tier. Index 0 is drawn too — a partner you have one
	 * contract with is below the first threshold but is still someone you have
	 * a history with, and that is exactly what the pip says. Bronze, silver,
	 * gold — the last is JournalTab's earned colour, so a maxed mark reads the
	 * same as every other earned thing.
	 */
	private static final Color[] PATRON_COLORS = {
		ColorScheme.LIGHT_GRAY_COLOR, new Color(170, 130, 90),
		new Color(200, 200, 210), new Color(230, 190, 80),
	};
	/**
	 * The outline on your TOP patron's pip. Every partner with a history now
	 * wears a pip, so without this the mark would stop having an owner; a
	 * near-white outline separates one pip from its neighbours at 9px in a way
	 * a tier colour cannot, since the top patron is often the top tier as well.
	 */
	private static final Color MARK_OWNER = new Color(245, 245, 245);

	/**
	 * The mark's colour for a count, clamped so a fourth entry in
	 * Tuning.PATRON_TIERS cannot throw. Shared with the Patrons page rather
	 * than copied: a pip beside a name and that name's row on the other tab
	 * describe the same mark, and two palettes would eventually disagree.
	 */
	static Color patronColor(int count) {
		return PATRON_COLORS[Math.min(PatronMark.tierFor(count), PATRON_COLORS.length - 1)];
	}

	private final PartyPresenceService presenceService;
	private final GachaStateService stateService;
	private final GachamanConfig config;

	/** Wrap width the current content was built for; -1 = never built. */
	private int builtWidth = -1;
	private boolean viewportHooked;

	/**
	 * Plugin-wired: the live party vote, or a supplier answering null when none
	 * is open. Nullable rather than a no-op default so an unwired panel shows no
	 * vote column at all instead of an empty one on every row.
	 */
	@Nullable
	private Supplier<PartyRollService.VoteView> voteViewSupplier;

	public void setVoteViewSupplier(
		@Nullable Supplier<PartyRollService.VoteView> supplier) {
		this.voteViewSupplier = supplier;
	}

	@Inject
	public PartyTab(PartyPresenceService presenceService, GachaStateService stateService,
		GachamanConfig config) {
		this.presenceService = presenceService;
		this.stateService = stateService;
		this.config = config;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	/**
	 * ALWAYS rebuilds. HelpTab short-circuits on an unchanged width because its
	 * content is static text; this page's content changes every time someone in
	 * the party kills something, so the same guard would freeze it after the
	 * first paint. The width comparison lives in the resize listener instead,
	 * which is also what stops a resize storm looping.
	 */
	void rebuild() {
		int width = measuredWidth(this);
		builtWidth = width;
		int inner = width - SECTION_PADDING;
		removeAll();

		JPanel section = GachamanPanel.section("Party");
		if (!config.partyRollsEnabled()) {
			section.add(textBlock("Party contracts are turned off in your Gachaman settings,"
				+ " so nothing is broadcast and nothing is shown here.", MUTED, inner));
		}
		else {
			List<PartyPresenceService.Row> rows = presenceService.getRows();
			if (rows.isEmpty()) {
				section.add(textBlock("Join a RuneLite Party to see everyone's style, level"
					+ " and contract progress here.", MUTED, inner));
			}
			else {
				// counted over ROWS, not over a group's members: a group is on
				// contract when its quota is, and saying so about a member whose
				// own line reports nothing would be inventing their state
				int onContract = 0;
				for (PartyPresenceService.Row row : rows) {
					if (row.getKillsRequired() > 0) {
						onContract++;
					}
				}
				List<PartyPresenceService.Group> groups = PartyPresenceService.group(rows);
				int shared = 0;
				for (PartyPresenceService.Group group : groups) {
					if (group.isShared()) {
						shared++;
					}
				}
				// Two lines rather than three facts on one: at double digits the
				// combined string ran past the 205px section and the tail was cut.
				// A party of one is reachable — buildRows always emits a self row.
				section.add(GachamanPanel.smallLine(rows.size()
						+ (rows.size() == 1 ? " member · " : " members · ")
						+ onContract + " on contract",
					ColorScheme.LIGHT_GRAY_COLOR));
				if (shared > 0) {
					section.add(GachamanPanel.smallLine(shared
							+ (shared == 1 ? " shared contract" : " shared contracts"),
						ColorScheme.LIGHT_GRAY_COLOR));
				}
				section.add(Box.createVerticalStrut(6));
				// resolved ONCE per rebuild, not per row: the ledger is one map,
				// read from one state snapshot, so no two pips on the page can
				// disagree about who the mark's owner is
				Marks marks = marks();
				// likewise once per rebuild: every row on the page must be reading
				// the same instant of the tally, or two names could show votes from
				// either side of an incoming ballot
				PartyRollService.VoteView votes =
					voteViewSupplier == null ? null : voteViewSupplier.get();
				for (PartyPresenceService.Group group : groups) {
					section.add(buildGroup(group, inner, marks, votes));
					section.add(Box.createVerticalStrut(8));
				}
				section.add(textBlock("Every line is self-reported by that member's own client"
					+ " and taken on trust.", MUTED, inner));
			}
		}

		add(new WidthCap(section, width));
		add(Box.createVerticalStrut(6));
		revalidate();
		repaint();
	}

	/**
	 * One block: every member sharing a contract, then the one pooled meter
	 * they are all filling.
	 *
	 * A shared contract is drawn ONCE, not once per member, because there is
	 * one quota — repeating "Goblin 12/20" under three names reads as three
	 * jobs of twenty and makes a party of four look like eighty kills of work.
	 * The names stack; the meter does not.
	 */
	private JComponent buildGroup(PartyPresenceService.Group group, int w, Marks marks,
		@Nullable PartyRollService.VoteView votes) {
		JPanel outer = new JPanel() {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(w, getPreferredSize().height);
			}
		};
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);

		List<PartyPresenceService.Row> members = group.getMembers();
		boolean live = false;
		for (int i = 0; i < members.size(); i++) {
			PartyPresenceService.Row row = members.get(i);
			if (i > 0) {
				outer.add(Box.createVerticalStrut(2));
			}
			outer.add(memberHeader(row, w, marks, votes));
			live |= row.isHeard() && row.isLoggedIn();
			if (!group.isOnContract()) {
				// a group with no quota is a single member by construction —
				// only a contract collapses rows together — but writing it
				// per-member means the page degrades to one line each rather
				// than silently dropping lines if that ever stops holding
				outer.add(statusLine(row));
			}
		}

		if (group.isOnContract()) {
			// a null task name alongside a real quota only reaches us from a
			// malformed or hostile client, and the block still has to lay out
			String quarry = group.getTaskName() == null ? "A contract" : group.getTaskName();
			outer.add(GachamanPanel.smallLine(group.isShared() ? "Shared · " + quarry : quarry,
				live ? ColorScheme.LIGHT_GRAY_COLOR : MUTED));
			outer.add(Box.createVerticalStrut(2));
			GachamanPanel.MeterBar meter = new GachamanPanel.MeterBar(
				PartyPresenceService.progressFraction(group.getKillsDone(), group.getKillsRequired()),
				barColor(group), group.getKillsDone() + " / " + group.getKillsRequired());
			meter.setMaximumSize(new Dimension(w, 15));
			outer.add(meter);
		}
		return outer;
	}

	/**
	 * A shared meter takes the brand colour, never a member's style colour: the
	 * members of one contract can be on three different styles — that is what
	 * the clash bonus pays for — so colouring the pooled bar after whichever
	 * row happened to be first would credit the quota to one of them.
	 */
	private static Color barColor(PartyPresenceService.Group group) {
		if (group.isShared()) {
			return ColorScheme.BRAND_ORANGE;
		}
		PartyPresenceService.Row only = group.getMembers().get(0);
		return only.getStyle() == null ? ColorScheme.BRAND_ORANGE : only.getStyle().getColor();
	}

	/** One member's line: style chip, name and level, their vote, then their badges. */
	private static JComponent memberHeader(PartyPresenceService.Row row, int w, Marks marks,
		@Nullable PartyRollService.VoteView votes) {
		boolean live = row.isHeard() && row.isLoggedIn();
		JPanel header = new JPanel(new BorderLayout(6, 0)) {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(w, getPreferredSize().height);
			}
		};
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		// style swatch and party face together on the left: the swatch says what
		// they swing, the face says who they are, and neither substitutes for the
		// other in a party where two members can share a style
		JPanel left = new JPanel(new BorderLayout(3, 0));
		left.setOpaque(false);
		left.add(new Swatch(row.getStyle() == null ? MUTED : row.getStyle().getColor()),
			BorderLayout.WEST);
		if (row.getAvatar() != null) {
			left.add(new Face(row.getAvatar(), live), BorderLayout.EAST);
		}
		header.add(left, BorderLayout.WEST);
		String label = row.getName() + (row.isSelf() ? " (you)" : "")
			+ (row.getCombatLevel() > 0 ? "  lvl " + row.getCombatLevel() : "")
			+ voteSuffix(row, votes);
		header.add(GachamanPanel.line(label, live ? Color.WHITE : MUTED,
			FontManager.getRunescapeSmallFont()), BorderLayout.CENTER);
		header.add(badges(row, marks), BorderLayout.EAST);
		return header;
	}

	/**
	 * How this member has voted, appended to their name while a vote is open.
	 *
	 * <p>Members who have NOT voted are named as such rather than left blank: a
	 * majority vote stalls on the people who have not answered, and a row that
	 * simply says nothing is indistinguishable from one the panel forgot. Empty
	 * outside a vote, so the line does not carry a dead column the rest of the
	 * time.
	 *
	 * <p>The contract is NAMED, not numbered. "vote 2" is an index into a board
	 * the reader may well have scrolled away from, and this column exists so a
	 * member can see what the party is converging on without reopening it.
	 */
	private static String voteSuffix(PartyPresenceService.Row row,
		@Nullable PartyRollService.VoteView votes) {
		if (votes == null) {
			return "";
		}
		String label = votes.getLabelByMember().get(row.getMemberId());
		return label == null ? "  · no vote" : "  · " + label;
	}

	/**
	 * What a member with no contract is doing. A dealt-but-unsigned board is
	 * called out by name because it is the one state that silently EXCLUDES
	 * someone from a party roll: without this the party proposes, that member
	 * is auto-excused, and nothing on the page ever said why.
	 */
	private static JLabel statusLine(PartyPresenceService.Row row) {
		if (!row.isHeard()) {
			// "- " marker only: the RuneScape TTFs have no bullet glyph
			return GachamanPanel.smallLine("- No signal", MUTED);
		}
		if (row.isUndecidedOffers()) {
			JLabel line = GachamanPanel.smallLine("- Undecided board", TAINT_RED);
			// your own row is on this page too, and third-person copy pointed at
			// yourself reads as a bug rather than as a description of you
			line.setToolTipText(row.isSelf()
				? "You have offers dealt and have signed none of them, so you cannot"
					+ " join a shared roll until you sign one or clear the board."
				: "They have offers dealt and have signed none of them, so they"
					+ " cannot join a shared roll until they sign one or clear the board.");
			return line;
		}
		return GachamanPanel.smallLine("- No contract", MUTED);
	}

	/**
	 * The row's badge strip: one small marker per party feature that decorates
	 * a member. Later party features add their marker HERE (and their bit as a
	 * field on GachaPresenceMessage) rather than adding a column, so every row
	 * keeps one layout however many features land.
	 */
	private static JPanel badges(PartyPresenceService.Row row, Marks marks) {
		JPanel strip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		strip.setOpaque(false);
		if (row.isTainted()) {
			JLabel taint = GachamanPanel.line("*", TAINT_RED, FontManager.getRunescapeSmallFont());
			taint.setToolTipText(row.isSelf()
				? "Tainted — your income is halved until you work it off."
				: "Tainted — their income is halved until they work it off.");
			strip.add(taint);
		}
		PatronRecord record = marks.recordFor(row);
		if (record != null) {
			int count = record.getCount();
			boolean top = marks.isTop(row);
			Pip pip = new Pip(patronColor(count), top);
			pip.setToolTipText(PatronMark.tierLabel(count) + " — you have finished "
				+ count + " shared contract" + (count == 1 ? "" : "s") + " with them"
				+ (top ? ", more than with anyone else." : ".")
				+ " The mark is cosmetic: it pays nothing and unlocks nothing.");
			strip.add(pip);
		}
		return strip;
	}

	/**
	 * The Patron's Mark ledger as the page needs it, read once per rebuild.
	 *
	 * Both halves come from ONE state snapshot on purpose: re-reading the state
	 * per row would let a shared contract complete mid-rebuild and hand two
	 * rows two different owners of a mark that only has one.
	 */
	private static final class Marks {
		@Nullable
		private final Map<String, PatronRecord> ledger;
		@Nullable
		private final String topKey;

		Marks(@Nullable Map<String, PatronRecord> ledger) {
			this.ledger = ledger;
			this.topKey = PatronMark.topKey(ledger);
		}

		/**
		 * Matched on ACCOUNT KEY, never on the name: the name a member
		 * broadcasts is a label they can change, and the presence layer's
		 * "A party member" fallback would otherwise let every unnamed row in
		 * the party wear the same person's mark.
		 */
		@Nullable
		PatronRecord recordFor(PartyPresenceService.Row row) {
			return PatronMark.recordFor(ledger, row.getAccountKey());
		}

		/** False whenever either side is unknown — see {@link AccountKey#same}. */
		boolean isTop(PartyPresenceService.Row row) {
			return AccountKey.same(row.getAccountKey(), topKey);
		}
	}

	/** The ledger as of now; empty when the save has not loaded yet. */
	private Marks marks() {
		GachaState state = stateService.get();
		return new Marks(state == null ? null : state.getPatrons());
	}

	// --- Layout plumbing ---

@Override
	public void addNotify() {
		super.addNotify();
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (!viewportHooked && ancestor instanceof JViewport) {
			// the viewport narrows when the scrollbar appears (and would widen if
			// the LAF ever changed its width) — re-measure and rebuild. rebuild()
			// itself cannot carry the equal-width guard here (it must always
			// rebuild), so the guard sits in the listener or a resize loops.
			viewportHooked = true;
			ancestor.addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(ComponentEvent e) {
					SwingUtilities.invokeLater(() -> {
						if (measuredWidth(PartyTab.this) != builtWidth) {
							rebuild();
						}
					});
				}
			});
		}
	}

	/** A drawn colour chip: the RuneScape TTFs have no block glyph to type. */
	private static final class Swatch extends JComponent {
		private final Color color;

		Swatch(Color color) {
			this.color = color;
			Dimension d = new Dimension(SWATCH, SWATCH);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g) {
			g.setColor(color);
			g.fillRect(0, Math.max(0, getHeight() / 2 - SWATCH / 2), SWATCH, SWATCH);
		}
	}

	/** Edge the party avatar is drawn at; matches the swatch's visual weight. */
	private static final int FACE = 14;

	/**
	 * A member's RuneLite party avatar.
	 *
	 * <p>Scaled on paint rather than pre-scaled once: the image arrives from
	 * RuneLite's party layer and can be replaced when a member changes it, so
	 * caching a resized copy here would pin the old face until the next rebuild.
	 * A member who is not currently heard is drawn faded, matching how their text
	 * greys out — the face should not be the one part of a stale row that still
	 * looks live.
	 */
	private static final class Face extends JComponent {
		private final BufferedImage image;
		private final boolean live;

		Face(BufferedImage image, boolean live) {
			this.image = image;
			this.live = live;
			Dimension d = new Dimension(FACE, FACE);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				if (!live) {
					g2.setComposite(AlphaComposite.getInstance(
						AlphaComposite.SRC_OVER, 0.45f));
				}
				int y = Math.max(0, getHeight() / 2 - FACE / 2);
				g2.drawImage(image, 0, y, FACE, FACE, null);
			}
			finally {
				g2.dispose();
			}
		}
	}

	/**
	 * The Patron's Mark, drawn rather than typed for the same reason as
	 * {@link Swatch}: the RuneScape TTFs carry no medal, crown, star or bullet
	 * glyph, and anything outside the plain ASCII markers renders as a tofu
	 * box. A diamond needs no glyph at all, and it cannot be confused with the
	 * taint badge's "*" sitting beside it.
	 */
	private static final class Pip extends JComponent {
		private final Color color;
		/** This is the mark's owner: outline it so one pip still stands out. */
		private final boolean owner;

		Pip(Color color, boolean owner) {
			this.color = color;
			this.owner = owner;
			Dimension d = new Dimension(PIP, PIP);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				int top = Math.max(0, getHeight() / 2 - PIP / 2);
				int mid = PIP / 2;
				int[] xs = {mid, PIP - 1, mid, 0};
				int[] ys = {top, top + mid, top + PIP - 1, top + mid};
				g2.setColor(color);
				g2.fillPolygon(xs, ys, 4);
				g2.setColor(owner ? MARK_OWNER : color.darker());
				g2.drawPolygon(xs, ys, 4);
			}
			finally {
				g2.dispose();
			}
		}
	}

/**
	 * Hard cap on a section's width: the sidebar is fixed-width and the scroll
	 * pane never scrolls horizontally, so no child may push a section past the
	 * measured viewport width. A private twin of HelpTab's, for the same reason
	 * as {@link #textBlock}.
	 */
	private static final class WidthCap extends JPanel {
		private final int cap;

		WidthCap(JComponent inner, int cap) {
			super(new BorderLayout());
			this.cap = cap;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			add(inner, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(Math.min(d.width, cap), d.height);
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(cap, getPreferredSize().height);
		}
	}
}
