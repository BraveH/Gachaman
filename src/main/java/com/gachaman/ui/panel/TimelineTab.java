package com.gachaman.ui.panel;

import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.Rarity;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TimelineEvent;
import com.gachaman.service.GachaStateService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerDateModel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The fortune timeline: a chronological, color-coded audit of every roll,
 * pull, equip and event. A from/to window bounds the view and a scrubber
 * sweeps 0 -> from through 1 -> to, revealing history up to the scrub point.
 */
@Singleton
public class TimelineTab extends JPanel
{
	private static final int SLIDER_MAX = 1000;
	private static final SimpleDateFormat RANGE_FORMAT = new SimpleDateFormat("MMM d HH:mm");
	private static final SimpleDateFormat LINE_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss");

	private static final Color CHEST_ORANGE = new Color(226, 148, 62);
	private static final Color LUCK_GOLD = new Color(230, 190, 80);
	private static final Color EQUIP_BLUE = new Color(150, 190, 240);
	private static final Color CHARGE_PURPLE = new Color(178, 140, 235);
	private static final Color BAD_RED = new Color(229, 90, 80);
	private static final Color CLEANSE_GREEN = new Color(110, 200, 110);

	private final GachaStateService stateService;

	private final JSpinner fromSpinner = new JSpinner(new SpinnerDateModel());
	private final JSpinner toSpinner = new JSpinner(new SpinnerDateModel());
	private final JSlider scrubber = new JSlider(0, SLIDER_MAX, SLIDER_MAX);
	private final JLabel scrubLabel = new JLabel();
	private final JEditorPane list = new JEditorPane();

	/** Once the player edits the window, auto-follow stops until Now is pressed. */
	private boolean userTouchedRange;
	private boolean suppressSpinnerEvents;

	@Inject
	public TimelineTab(GachaStateService stateService)
	{
		this.stateService = stateService;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);

		fromSpinner.setEditor(new JSpinner.DateEditor(fromSpinner, "MMM d HH:mm"));
		toSpinner.setEditor(new JSpinner.DateEditor(toSpinner, "MMM d HH:mm"));
		GachamanPanel.styleSpinner(fromSpinner);
		GachamanPanel.styleSpinner(toSpinner);
		controls.add(spinnerRow("From", fromSpinner));
		controls.add(Box.createVerticalStrut(3));
		JButton nowButton = GachamanPanel.button("Now");
		nowButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
		nowButton.setToolTipText("Snap the window back to first event -> now and keep following");
		nowButton.addActionListener(e -> {
			userTouchedRange = false;
			scrubber.setValue(SLIDER_MAX);
			rebuild();
		});
		JPanel toRow = spinnerRow("To", toSpinner);
		toRow.add(Box.createHorizontalStrut(4));
		toRow.add(nowButton);
		controls.add(toRow);
		controls.add(Box.createVerticalStrut(4));

		scrubber.setOpaque(false);
		scrubber.setUI(new GachamanPanel.GameSliderUI(scrubber));
		scrubber.setAlignmentX(Component.LEFT_ALIGNMENT);
		scrubber.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		scrubber.setToolTipText("Scrub the window: 0 = from, 1 = to");
		controls.add(scrubber);
		scrubLabel.setFont(FontManager.getRunescapeSmallFont());
		scrubLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scrubLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(scrubLabel);
		add(controls, BorderLayout.NORTH);

		list.setEditable(false);
		list.setContentType("text/html");
		list.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		list.setFont(FontManager.getRunescapeSmallFont());
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setBorder(new EmptyBorder(6, 6, 6, 6));
		JScrollPane scroll = new JScrollPane(list,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GachamanPanel.styleScrollbar(scroll);
		add(scroll, BorderLayout.CENTER);

		fromSpinner.addChangeListener(e -> {
			if (!suppressSpinnerEvents)
			{
				userTouchedRange = true;
				refreshList();
			}
		});
		toSpinner.addChangeListener(e -> {
			if (!suppressSpinnerEvents)
			{
				userTouchedRange = true;
				refreshList();
			}
		});
		// Rebuilding on every ChangeEvent re-parsed the whole HTML document — around 90ms
		// at the 500-event cap, synchronously on the EDT — so dragging the thumb ran at
		// about 10fps with the list trailing the cursor. Defer the rebuild to the release
		// and keep the caption live, which is the part the player is actually reading
		// while they drag.
		scrubber.addChangeListener(e -> {
			if (scrubber.getValueIsAdjusting())
			{
				updateScrubLabel();
			}
			else
			{
				refreshList();
			}
		});
	}

	private static JPanel spinnerRow(String label, JSpinner spinner)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		JLabel text = new JLabel(label);
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setPreferredSize(new Dimension(34, 20));
		row.add(text);
		spinner.setFont(FontManager.getRunescapeSmallFont());
		spinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.add(spinner);
		return row;
	}

	void rebuild()
	{
		// entering the tab always starts at the END (scrub = 1): the newest
		// events show first and the player scrubs BACK through time. While
		// the tab is already visible, live state refreshes must not yank the
		// thumb out of the player's hand.
		if (!isShowing())
		{
			scrubber.setValue(SLIDER_MAX);
		}
		GachaState state = stateService.get();
		List<TimelineEvent> timeline = state == null ? null : state.getTimeline();
		if (state == null)
		{
			list.setText(htmlWrap("<font color='#909090'>Log in to view your fortune timeline.</font>"));
			scrubLabel.setText(" ");
			return;
		}
		if (!userTouchedRange)
		{
			long now = System.currentTimeMillis();
			long first = timeline != null && !timeline.isEmpty()
				? timeline.get(0).getAt() : now - 3_600_000L;
			suppressSpinnerEvents = true;
			fromSpinner.setValue(new Date(Math.min(first, now - 60_000L)));
			toSpinner.setValue(new Date(now));
			suppressSpinnerEvents = false;
		}
		refreshList();
	}

	/**
	 * {from, scrub} for the current spinner and scrubber positions.
	 *
	 * <p>Shared by the rebuild and the caption so a drag cannot show a timestamp
	 * computed from one window and a count from another.
	 */
	private long[] scrubWindow()
	{
		long from = ((Date) fromSpinner.getValue()).getTime();
		long to = ((Date) toSpinner.getValue()).getTime();
		if (to < from)
		{
			long swap = from;
			from = to;
			to = swap;
		}
		return new long[]{from,
			from + (long) ((to - from) * (scrubber.getValue() / (double) SLIDER_MAX))};
	}

	/**
	 * The caption alone, for the live part of a drag.
	 *
	 * <p>Counts with the same predicate {@link #refreshList} filters on, which is a
	 * bounded walk over at most TIMELINE_MAX_EVENTS and costs nothing next to
	 * re-parsing the document. Deliberately does not touch {@code list}: leaving the
	 * old rows standing for the length of a drag is the whole point.
	 */
	private void updateScrubLabel()
	{
		GachaState state = stateService.get();
		List<TimelineEvent> timeline = state == null ? null : state.getTimeline();
		long[] window = scrubWindow();
		long from = window[0];
		long scrub = window[1];

		int shown = 0;
		if (timeline != null)
		{
			for (TimelineEvent event : timeline)
			{
				if (event.getAt() >= from && event.getAt() <= scrub)
				{
					shown++;
				}
			}
		}
		scrubLabel.setText("Up to " + RANGE_FORMAT.format(new Date(scrub)) + "  (" + shown + " events)");
	}

	private void refreshList()
	{
		GachaState state = stateService.get();
		List<TimelineEvent> timeline = state == null ? List.of() : state.getTimeline();
		if (timeline == null)
		{
			timeline = List.of();
		}
		long[] window = scrubWindow();
		long from = window[0];
		long scrub = window[1];

		StringBuilder html = new StringBuilder();
		int shown = 0;
		for (TimelineEvent event : timeline)
		{
			if (event.getAt() < from || event.getAt() > scrub)
			{
				continue;
			}
			shown++;
			html.append("<font color='").append(hex(colorFor(event))).append("'>[")
				.append(LINE_FORMAT.format(new Date(event.getAt()))).append("] ")
				.append(escape(event.getText()))
				.append("</font><br/>");
		}
		if (shown == 0)
		{
			html.append("<font color='#909090'>No events in this window — fate has been quiet.</font>");
		}
		list.setText(htmlWrap(html.toString()));
		list.setCaretPosition(list.getDocument().getLength()); // newest visible
		scrubLabel.setText("Up to " + RANGE_FORMAT.format(new Date(scrub)) + "  (" + shown + " events)");
	}

	private static String htmlWrap(String body)
	{
		return "<html><body>" + body + "</body></html>";
	}

	private static Color colorFor(TimelineEvent event)
	{
		try
		{
			switch (event.getKind())
			{
				case TimelineEvent.KIND_STYLE:
					return event.getMeta() != null
						? AttackStyle.valueOf(event.getMeta()).getColor() : CHARGE_PURPLE;
				case TimelineEvent.KIND_CARD:
					return event.getMeta() != null
						? Rarity.valueOf(event.getMeta()).getColor() : CHEST_ORANGE;
				case TimelineEvent.KIND_ACCEPT:
				case TimelineEvent.KIND_COMPLETE:
					return event.getMeta() != null
						? TaskDifficulty.valueOf(event.getMeta()).getColor() : Color.WHITE;
				case TimelineEvent.KIND_CHEST:
					return CHEST_ORANGE;
				case TimelineEvent.KIND_EQUIP:
					return EQUIP_BLUE;
				case TimelineEvent.KIND_LUCK:
				case TimelineEvent.KIND_REROLL:
					return LUCK_GOLD;
				case TimelineEvent.KIND_CHARGE:
					return CHARGE_PURPLE;
				case TimelineEvent.KIND_CHARTER:
					// a chartered deed reads as the contract it becomes; a refund
					// carries no difficulty and falls back to the neutral gold
					return event.getMeta() != null
						? TaskDifficulty.valueOf(event.getMeta()).getColor() : LUCK_GOLD;
				case TimelineEvent.KIND_VIOLATION:
				case TimelineEvent.KIND_TAINT:
					return BAD_RED;
				case TimelineEvent.KIND_CLEANSE:
					return CLEANSE_GREEN;
				default:
					return ColorScheme.LIGHT_GRAY_COLOR;
			}
		}
		catch (IllegalArgumentException e)
		{
			return ColorScheme.LIGHT_GRAY_COLOR; // unknown meta from a future version
		}
	}

	private static String hex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
