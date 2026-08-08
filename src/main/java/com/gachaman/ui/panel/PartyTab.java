package com.gachaman.ui.panel;

import com.gachaman.model.GachaState;
import com.gachaman.party.PartyPresenceService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.PatronMark;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

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
public class PartyTab extends JPanel
{
	/**
	 * Pre-realization fallback only: the 242px non-wrapped PluginPanel minus
	 * its 6px borders and a full stock 17px scrollbar — the NARROWEST the
	 * viewport can plausibly be, so nothing clips even before measuring.
	 */
	private static final int FALLBACK_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
		- 2 * PluginPanel.BORDER_OFFSET - PluginPanel.SCROLLBAR_WIDTH;

	/** Horizontal padding a GachamanPanel.section() adds (8px borders each side). */
	private static final int SECTION_PADDING = 16;
	/** Side of the drawn style chip. */
	private static final int SWATCH = 10;

	private static final Color MUTED = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color TAINT_RED = new Color(190, 60, 55);
	/** Side of the drawn Patron's Mark pip. */
	private static final int PIP = 9;
	/**
	 * The Patron's Mark by tier, index 0 unused (no pip is drawn below the
	 * first threshold). Bronze, silver, gold — the last is JournalTab's earned
	 * colour, so a maxed mark reads the same as every other earned thing.
	 */
	private static final Color[] PATRON_COLORS = {
		ColorScheme.LIGHT_GRAY_COLOR, new Color(170, 130, 90),
		new Color(200, 200, 210), new Color(230, 190, 80),
	};

	private final PartyPresenceService presenceService;
	private final GachaStateService stateService;
	private final com.gachaman.GachamanConfig config;

	/** Wrap width the current content was built for; -1 = never built. */
	private int builtWidth = -1;
	private boolean viewportHooked;

	@Inject
	public PartyTab(PartyPresenceService presenceService, GachaStateService stateService,
		com.gachaman.GachamanConfig config)
	{
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
	void rebuild()
	{
		int width = measuredWidth();
		builtWidth = width;
		int inner = width - SECTION_PADDING;
		removeAll();

		JPanel section = GachamanPanel.section("Party");
		if (!config.partyRollsEnabled())
		{
			section.add(textBlock("Party contracts are turned off in your Gachaman settings,"
				+ " so nothing is broadcast and nothing is shown here.", MUTED, inner));
		}
		else
		{
			List<PartyPresenceService.Row> rows = presenceService.getRows();
			if (rows.isEmpty())
			{
				section.add(textBlock("Join a RuneLite Party to see everyone's style, level"
					+ " and contract progress here.", MUTED, inner));
			}
			else
			{
				int onContract = 0;
				for (PartyPresenceService.Row row : rows)
				{
					if (row.getKillsRequired() > 0)
					{
						onContract++;
					}
				}
				section.add(GachamanPanel.smallLine(rows.size() + " members · " + onContract
					+ " on contract", ColorScheme.LIGHT_GRAY_COLOR));
				section.add(Box.createVerticalStrut(6));
				// resolved ONCE per rebuild, not per row: the ledger is one map
				// and the mark belongs to a single member of the party
				Patron patron = topPatron();
				for (PartyPresenceService.Row row : rows)
				{
					section.add(buildRow(row, inner, patron));
					section.add(Box.createVerticalStrut(6));
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

	private JComponent buildRow(PartyPresenceService.Row row, int w, @Nullable Patron patron)
	{
		boolean live = row.isHeard() && row.isLoggedIn();
		Color nameColor = live ? Color.WHITE : MUTED;
		Color styleColor = row.getStyle() == null ? MUTED : row.getStyle().getColor();
		Color barColor = row.getStyle() == null ? ColorScheme.BRAND_ORANGE : row.getStyle().getColor();

		JPanel outer = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(w, getPreferredSize().height);
			}
		};
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel header = new JPanel(new BorderLayout(6, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(w, getPreferredSize().height);
			}
		};
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(new Swatch(styleColor), BorderLayout.WEST);
		String label = row.getName() + (row.isSelf() ? " (you)" : "")
			+ (row.getCombatLevel() > 0 ? "  lvl " + row.getCombatLevel() : "");
		header.add(GachamanPanel.line(label, nameColor, FontManager.getRunescapeSmallFont()),
			BorderLayout.CENTER);
		header.add(badges(row, patron), BorderLayout.EAST);
		outer.add(header);

		if (row.getKillsRequired() > 0)
		{
			// a null task name alongside a real quota only reaches us from a
			// malformed or hostile client, and the row still has to lay out
			outer.add(GachamanPanel.smallLine(
				row.getTaskName() == null ? "A contract" : row.getTaskName(),
				live ? ColorScheme.LIGHT_GRAY_COLOR : MUTED));
			outer.add(Box.createVerticalStrut(2));
			GachamanPanel.MeterBar meter = new GachamanPanel.MeterBar(
				PartyPresenceService.progressFraction(row.getKillsDone(), row.getKillsRequired()),
				barColor, row.getKillsDone() + " / " + row.getKillsRequired());
			meter.setMaximumSize(new Dimension(w, 15));
			outer.add(meter);
		}
		else
		{
			// "- " marker only: the RuneScape TTFs have no bullet glyph
			outer.add(GachamanPanel.smallLine(row.isHeard() ? "- No contract" : "- No signal",
				MUTED));
		}
		return outer;
	}

	/**
	 * The row's badge strip: one small marker per party feature that decorates
	 * a member. Later party features add their marker HERE (and their bit as a
	 * field on GachaPresenceMessage) rather than adding a column, so every row
	 * keeps one layout however many features land.
	 */
	private static JPanel badges(PartyPresenceService.Row row, @Nullable Patron patron)
	{
		JPanel strip = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		strip.setOpaque(false);
		if (row.isTainted())
		{
			JLabel taint = GachamanPanel.line("*", TAINT_RED, FontManager.getRunescapeSmallFont());
			taint.setToolTipText("Tainted — their income is halved until they work it off.");
			strip.add(taint);
		}
		if (patron != null && patron.matches(row))
		{
			int tier = PatronMark.tierFor(patron.count);
			Pip pip = new Pip(PATRON_COLORS[Math.min(tier, PATRON_COLORS.length - 1)]);
			pip.setToolTipText(PatronMark.tierLabel(patron.count) + " — you have finished "
				+ patron.count + " shared contract" + (patron.count == 1 ? "" : "s")
				+ " with " + patron.name + ", more than with anyone else."
				+ " The mark is cosmetic: it pays nothing and unlocks nothing.");
			strip.add(pip);
		}
		return strip;
	}

	/**
	 * The Patron's Mark holder: the partner you have finished the most shared
	 * contracts with, and how many. Read once per rebuild.
	 */
	private static final class Patron
	{
		private final String name;
		private final int count;

		Patron(String name, int count)
		{
			this.name = name;
			this.count = count;
		}

		/**
		 * The match rule lives in PatronMark.sameName so it is unit-testable:
		 * both sides are normalized there, which is what stops the presence
		 * layer's "A party member" fallback row — or a member still reading as
		 * "&lt;unknown&gt;" — from wearing somebody else's mark.
		 */
		boolean matches(PartyPresenceService.Row row)
		{
			return PatronMark.sameName(row.getName(), name);
		}
	}

	/** The mark's owner as of now, or null when nobody has earned one yet. */
	@Nullable
	private Patron topPatron()
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return null; // not loaded yet: no mark rather than a wrong one
		}
		Map<String, Integer> counts = state.getPartnerContracts();
		String name = PatronMark.topPartner(counts);
		return name == null ? null : new Patron(name, PatronMark.countFor(counts, name));
	}

	// --- Layout plumbing ---

	/** The scroll viewport's ACTUAL extent width — the only trustworthy budget. */
	private int measuredWidth()
	{
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (ancestor instanceof JViewport)
		{
			int width = ((JViewport) ancestor).getExtentSize().width;
			if (width > 0)
			{
				return width;
			}
		}
		return FALLBACK_WIDTH;
	}

	@Override
	public void addNotify()
	{
		super.addNotify();
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (!viewportHooked && ancestor instanceof JViewport)
		{
			// the viewport narrows when the scrollbar appears (and would widen if
			// the LAF ever changed its width) — re-measure and rebuild. rebuild()
			// itself cannot carry the equal-width guard here (it must always
			// rebuild), so the guard sits in the listener or a resize loops.
			viewportHooked = true;
			ancestor.addComponentListener(new ComponentAdapter()
			{
				@Override
				public void componentResized(ComponentEvent e)
				{
					SwingUtilities.invokeLater(() -> {
						if (measuredWidth() != builtWidth)
						{
							rebuild();
						}
					});
				}
			});
		}
	}

	/** A drawn colour chip: the RuneScape TTFs have no block glyph to type. */
	private static final class Swatch extends JComponent
	{
		private final Color color;

		Swatch(Color color)
		{
			this.color = color;
			Dimension d = new Dimension(SWATCH, SWATCH);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(color);
			g.fillRect(0, Math.max(0, getHeight() / 2 - SWATCH / 2), SWATCH, SWATCH);
		}
	}

	/**
	 * The Patron's Mark, drawn rather than typed for the same reason as
	 * {@link Swatch}: the RuneScape TTFs carry no medal, crown, star or bullet
	 * glyph, and anything outside the plain ASCII markers renders as a tofu
	 * box. A diamond needs no glyph at all, and it cannot be confused with the
	 * taint badge's "*" sitting beside it.
	 */
	private static final class Pip extends JComponent
	{
		private final Color color;

		Pip(Color color)
		{
			this.color = color;
			Dimension d = new Dimension(PIP, PIP);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				int top = Math.max(0, getHeight() / 2 - PIP / 2);
				int mid = PIP / 2;
				int[] xs = {mid, PIP - 1, mid, 0};
				int[] ys = {top, top + mid, top + PIP - 1, top + mid};
				g2.setColor(color);
				g2.fillPolygon(xs, ys, 4);
				g2.setColor(color.darker());
				g2.drawPolygon(xs, ys, 4);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	/**
	 * Wrap-to-width body text WITHOUT the HTML renderer: Swing's CSS width is a
	 * preferred span, not a hard cap — stretched labels re-wrap wider than asked
	 * and then clip under the scrollbar. A JTextArea wraps at exactly the width
	 * it is given; sizing it up front makes its preferred height correct before
	 * the BoxLayout ever asks.
	 *
	 * A private twin of HelpTab's; sharing one copy means promoting HelpTab's
	 * out of private, which is a docs-owned file this round.
	 */
	private static JTextArea textBlock(String text, Color color, int width)
	{
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

	/**
	 * Hard cap on a section's width: the sidebar is fixed-width and the scroll
	 * pane never scrolls horizontally, so no child may push a section past the
	 * measured viewport width. A private twin of HelpTab's, for the same reason
	 * as {@link #textBlock}.
	 */
	private static final class WidthCap extends JPanel
	{
		private final int cap;

		WidthCap(JComponent inner, int cap)
		{
			super(new BorderLayout());
			this.cap = cap;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			add(inner, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			return new Dimension(Math.min(d.width, cap), d.height);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(cap, getPreferredSize().height);
		}
	}
}
