package com.gachaman.ui.panel;

import com.gachaman.Tuning;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.ContractRecord;
import com.gachaman.model.DossierSummary;
import com.gachaman.model.GachaState;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.service.GachaStateService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The Contract Dossier: every contract you have filed, newest first, under a
 * pinned totals header.
 *
 * <p>Distinct from the Timeline, which is a chronological audit of ALL fortune
 * events sharing one 500-entry budget that a busy chest session drains in an
 * afternoon; and from the Journal, which aggregates per MONSTER and keeps no
 * chronology. Only this page can answer "what did that job actually pay, and
 * did I run it clean" — and only this layout can pin the totals, because the
 * header lives in BorderLayout.NORTH outside the scroll viewport.
 */
@Singleton
public class DossierTab extends JPanel
{
	/**
	 * Year included: the log retains {@link Tuning#DOSSIER_MAX_RECORDS} contracts,
	 * which for a slow player spans well over a year, and a bare "Aug 9" on a
	 * record filed two years ago is a lie. Locale pinned to ENGLISH so the month
	 * abbreviation matches the rest of the panel's copy on a non-English JVM
	 * rather than appearing alone in the system language.
	 */
	private static final SimpleDateFormat LINE_FORMAT =
		new SimpleDateFormat("d MMM yy HH:mm", Locale.ENGLISH);

	private static final Color CLEAN_GREEN = new Color(110, 200, 110);
	private static final Color BAD_RED = new Color(229, 90, 80);
	private static final Color DETAIL_GRAY = new Color(138, 138, 138);
	private static final Color BODY_GRAY = new Color(200, 200, 200);
	private static final Color PARTY_BLUE = new Color(150, 190, 240);

	private final GachaStateService stateService;

	private final JPanel header = new JPanel();
	private final JEditorPane list = new JEditorPane();

	@Inject
	public DossierTab(GachaStateService stateService)
	{
		this.stateService = stateService;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
		header.setOpaque(false);
		add(header, BorderLayout.NORTH);

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
	}

	void rebuild()
	{
		header.removeAll();
		GachaState state = stateService.get();
		if (state == null)
		{
			list.setText(htmlWrap("<font color='#909090'>Log in to view your dossier.</font>"));
			header.revalidate();
			header.repaint();
			return;
		}

		List<ContractRecord> log = state.getContractLog();
		DossierSummary summary = DossierSummary.of(log);
		header.add(buildTotals(state, summary));

		list.setText(htmlWrap(buildRows(log)));
		// newest first, so the top of the document is the interesting end
		list.setCaretPosition(0);
		header.revalidate();
		header.repaint();
	}

	private static JPanel buildTotals(GachaState state, DossierSummary summary)
	{
		JPanel section = GachamanPanel.section(null);
		section.add(GachamanPanel.line("Contracts filed: " + summary.getContracts(),
			Color.WHITE, FontManager.getRunescapeBoldFont()));
		section.add(Box.createVerticalStrut(4));

		if (summary.getContracts() == 0)
		{
			section.add(GachamanPanel.smallLine("Nothing filed yet — go and sign one.",
				ColorScheme.MEDIUM_GRAY_COLOR));
			section.add(Box.createVerticalStrut(3));
			section.add(GachamanPanel.smallLine(
				"Lifetime earned: " + QuantityFormatter.formatNumber(state.getLifetimeGcEarned()) + " GC",
				ColorScheme.BRAND_ORANGE));
			return section;
		}

		section.add(new GachamanPanel.MeterBar(summary.cleanFraction(), CLEAN_GREEN,
			summary.getCleanContracts() + " / " + summary.getContracts() + " clean"));
		section.add(Box.createVerticalStrut(4));
		// Both GC figures on the pay line and both counts on the next: "best" is a
		// GC number and reading it off a line labelled "Kills:" made it look like a
		// kill count.
		section.add(GachamanPanel.smallLine(
			"Pay: " + QuantityFormatter.formatNumber(summary.getTotalGc()) + " GC  ·  avg "
				+ QuantityFormatter.formatNumber(summary.averageGc()) + " GC",
			ColorScheme.LIGHT_GRAY_COLOR));
		section.add(GachamanPanel.smallLine(
			"Best: " + QuantityFormatter.formatNumber(summary.getBestGc()) + " GC  ·  "
				+ QuantityFormatter.formatNumber(summary.getTotalKills()) + " kills",
			ColorScheme.LIGHT_GRAY_COLOR));
		section.add(GachamanPanel.smallLine(
			"Time: " + formatDuration(summary.getTotalDurationMs()) + "  ·  avg "
				+ formatDuration(summary.averageDurationMs()),
			ColorScheme.LIGHT_GRAY_COLOR));
		section.add(GachamanPanel.smallLine(
			"Clean rate: " + summary.cleanPercent() + "%  ·  shared " + summary.getPartyContracts(),
			ColorScheme.LIGHT_GRAY_COLOR));
		if (summary.getContracts() >= Tuning.DOSSIER_MAX_RECORDS)
		{
			// totals are a fold over the RETAINED window, so say so rather than
			// letting the header read as a lifetime figure it is not
			section.add(GachamanPanel.smallLine("(last " + Tuning.DOSSIER_MAX_RECORDS + " contracts)",
				ColorScheme.MEDIUM_GRAY_COLOR));
		}
		section.add(Box.createVerticalStrut(3));
		// the one true lifetime number, unbounded by the log's cap
		section.add(GachamanPanel.smallLine(
			"Lifetime earned: " + QuantityFormatter.formatNumber(state.getLifetimeGcEarned()) + " GC",
			ColorScheme.BRAND_ORANGE));
		return section;
	}

	private static String buildRows(@Nullable List<ContractRecord> log)
	{
		if (log == null || log.isEmpty())
		{
			return "<font color='#909090'>No contracts filed yet. Complete one and it lands here.</font>";
		}
		StringBuilder html = new StringBuilder();
		int shown = 0;
		// newest first: the log is appended in completion order, so walk it back
		for (int i = log.size() - 1; i >= 0; i--)
		{
			ContractRecord record = log.get(i);
			if (record == null)
			{
				continue; // Gson can hand back a null array element
			}
			shown++;
			html.append("<font color='").append(hex(difficultyColor(record.getDifficulty())))
				.append("'>").append(GachamanPanel.escape(nameOf(record.getMonsterName()))).append("</font>")
				.append("<font color='").append(hex(BODY_GRAY)).append("'>").append(GachamanPanel.DOT)
				.append(QuantityFormatter.formatNumber(record.getKills()))
				.append(record.getKills() == 1 ? " kill" : " kills").append(GachamanPanel.DOT)
				.append(QuantityFormatter.formatNumber(record.getGc())).append(" GC</font><br/>");

			html.append("<font color='").append(hex(DETAIL_GRAY)).append("'>")
				.append(LINE_FORMAT.format(new Date(record.getAt())))
				.append(GachamanPanel.DOT).append(formatDuration(record.getDurationMs()));
			Color styleColor = styleColor(record.getStyle());
			if (styleColor != null)
			{
				html.append(GachamanPanel.DOT).append("<font color='").append(hex(styleColor)).append("'>")
					.append(GachamanPanel.escape(styleName(record.getStyle()))).append("</font>");
			}
			if (record.isParty())
			{
				html.append(GachamanPanel.DOT).append("<font color='").append(hex(PARTY_BLUE)).append("'>")
					.append(GachamanPanel.escape(record.getParty())).append("</font>");
			}
			if (record.isCarried())
			{
				html.append(GachamanPanel.DOT).append("carried");
			}
			if (record.isRedemption())
			{
				html.append(GachamanPanel.DOT).append("redemption");
			}
			html.append(GachamanPanel.DOT).append("<font color='")
				.append(hex(record.isClean() ? CLEAN_GREEN : BAD_RED)).append("'>")
				.append(record.isClean() ? "clean"
					: record.getTaintedKills() + (record.getTaintedKills() == 1
						? " off-style kill" : " off-style kills"))
				.append("</font></font><br/><br/>");
		}
		if (shown == 0)
		{
			return "<font color='#909090'>No contracts filed yet. Complete one and it lands here.</font>";
		}
		return html.toString();
	}

	private static String nameOf(@Nullable String monsterName)
	{
		return monsterName == null || monsterName.isEmpty() ? "Unknown quarry" : monsterName;
	}

	/** Falls back to neutral for a difficulty name this build does not know. */
	private static Color difficultyColor(@Nullable String difficulty)
	{
		if (difficulty == null)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		try
		{
			return TaskDifficulty.valueOf(difficulty).getColor();
		}
		catch (IllegalArgumentException e)
		{
			return ColorScheme.LIGHT_GRAY_COLOR; // a record filed by a future version
		}
	}

	/** Null when there is no style to show, so the caller can omit the segment. */
	@Nullable
	private static Color styleColor(@Nullable String style)
	{
		if (style == null)
		{
			return null;
		}
		try
		{
			return AttackStyle.valueOf(style).getColor();
		}
		catch (IllegalArgumentException e)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
	}

	static String styleName(@Nullable String style)
	{
		if (style == null)
		{
			return "";
		}
		try
		{
			return AttackStyle.valueOf(style).getDisplayName();
		}
		catch (IllegalArgumentException e)
		{
			return style; // show the raw name rather than dropping the fact
		}
	}

	private static String htmlWrap(String body)
	{
		return "<html><body>" + body + "</body></html>";
	}

	private static String hex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	static String formatDuration(long ms)
	{
		long totalSeconds = Math.max(0, ms) / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		if (minutes >= 60)
		{
			return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}
}
