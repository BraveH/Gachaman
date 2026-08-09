package com.gachaman.ui.panel;

import com.gachaman.model.GachaState;
import com.gachaman.model.MonsterStats;
import com.gachaman.model.PersonalBest;
import com.gachaman.model.TaskDifficulty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import com.gachaman.service.GachaStateService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Journal: species codex, the Firsts Journal stamp page, per-difficulty
 * personal bests and a sortable per-monster stats table with a totals line.
 */
@Singleton
public class JournalTab extends JPanel
{
	private final GachaStateService stateService;
	private final com.gachaman.data.MonsterTable monsterTable;

	@Inject
	public JournalTab(GachaStateService stateService, com.gachaman.data.MonsterTable monsterTable)
	{
		this.stateService = stateService;
		this.monsterTable = monsterTable;
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
			add(GachamanPanel.centeredNote("Log in to view your journal."));
			revalidate();
			repaint();
			return;
		}

		add(buildCodexSection(state));
		add(Box.createVerticalStrut(6));
		add(buildFirstsSection(state));
		add(Box.createVerticalStrut(6));
		add(buildPbSection(state));
		add(Box.createVerticalStrut(6));
		add(buildStatsSection(state));

		revalidate();
		repaint();
	}

	private JPanel buildCodexSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Species Codex");
		int discovered = state.getSpeciesDiscovered() == null ? 0 : state.getSpeciesDiscovered().size();
		int total = monsterTable.getMonsters().size();
		section.add(GachamanPanel.line("Species discovered: " + discovered + " / " + total,
			Color.WHITE, FontManager.getRunescapeBoldFont()));
		section.add(Box.createVerticalStrut(4));
		int nextIndex = -1;
		for (int i = 0; i < com.gachaman.Tuning.BESTIARY_MILESTONES.length; i++)
		{
			if (com.gachaman.Tuning.BESTIARY_MILESTONES[i] > discovered)
			{
				nextIndex = i;
				break;
			}
		}
		if (nextIndex >= 0)
		{
			int next = com.gachaman.Tuning.BESTIARY_MILESTONES[nextIndex];
			section.add(new GachamanPanel.MeterBar((double) discovered / next,
				ColorScheme.BRAND_ORANGE, discovered + " / " + next));
			section.add(Box.createVerticalStrut(2));
			section.add(GachamanPanel.smallLine("Next: " + next + " species — +"
					+ QuantityFormatter.formatNumber(
						com.gachaman.Tuning.BESTIARY_MILESTONE_GC[nextIndex]) + " GC",
				ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			section.add(GachamanPanel.smallLine("All codex milestones complete!",
				ColorScheme.BRAND_ORANGE));
		}
		section.add(Box.createVerticalStrut(2));
		// "on-contract", not "on-task": this plugin has a real Slayer task concept
		// sitting one tab away (Double Docket), so "on-task" reads as the wrong one
		section.add(GachamanPanel.wrapped("First on-contract kill of a new species pays +"
				+ QuantityFormatter.formatNumber(com.gachaman.Tuning.DISCOVERY_GC) + " GC.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		if (discovered > 0)
		{
			section.add(Box.createVerticalStrut(5));
			section.add(GachamanPanel.smallLine("Discovered:", ColorScheme.BRAND_ORANGE));
			// persisted keys are lowercased — recover display names from the table
			Map<String, String> displayNames = new java.util.HashMap<>();
			for (com.gachaman.data.MonsterTable.Monster monster : monsterTable.getMonsters())
			{
				displayNames.put(monster.getName().toLowerCase(java.util.Locale.ROOT),
					monster.getName());
			}
			List<String> names = new ArrayList<>();
			for (String key : state.getSpeciesDiscovered())
			{
				names.add(displayNames.getOrDefault(key, capitalize(key)));
			}
			names.sort(String.CASE_INSENSITIVE_ORDER);
			for (String name : names)
			{
				section.add(GachamanPanel.smallLine("· " + name, ColorScheme.LIGHT_GRAY_COLOR));
			}
		}
		return section;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "?";
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private JPanel buildFirstsSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Firsts");
		java.util.Set<String> claimed = state.getFirstsClaimed() == null
			? java.util.Collections.emptySet() : state.getFirstsClaimed();
		com.gachaman.model.FirstStamp[] stamps = com.gachaman.model.FirstStamp.values();
		int earned = 0;
		for (com.gachaman.model.FirstStamp stamp : stamps)
		{
			if (claimed.contains(stamp.name()))
			{
				earned++;
			}
		}
		section.add(new GachamanPanel.MeterBar((double) earned / stamps.length,
			new Color(230, 190, 80), earned + " / " + stamps.length + " stamped"));
		section.add(Box.createVerticalStrut(4));
		for (com.gachaman.model.FirstStamp stamp : stamps)
		{
			boolean got = claimed.contains(stamp.name());
			Integer gc = com.gachaman.Tuning.FIRSTS_GC.get(stamp);
			// markers limited to glyphs the RuneScape TTFs actually cover
			String text = (got ? "* " : "- ") + stamp.getDisplayName()
				+ (got ? "" : "  (+" + QuantityFormatter.formatNumber(gc == null ? 0 : gc) + " GC)");
			JLabel line = GachamanPanel.smallLine(text,
				got ? new Color(230, 190, 80) : ColorScheme.MEDIUM_GRAY_COLOR);
			section.add(line);
			// explainer as a wrapped subtitle so the rule is readable in place
			section.add(GachamanPanel.wrapped(stamp.getExplainer(),
				got ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR.darker()));
			section.add(Box.createVerticalStrut(3));
		}
		return section;
	}

	private JPanel buildPbSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Personal Bests");
		boolean any = false;
		for (TaskDifficulty difficulty : TaskDifficulty.values())
		{
			PersonalBest pb = state.getPersonalBests().get(difficulty.name());
			if (pb == null || (pb.getFastestTaskMs() <= 0 && pb.getBiggestHaulGc() <= 0))
			{
				continue;
			}
			any = true;
			section.add(GachamanPanel.line(difficulty.getDisplayName(), difficulty.getColor(),
				FontManager.getRunescapeBoldFont()));
			// The record and the monster that set it go on separate lines. Appended,
			// "Biggest haul: 12,345 GC — Commander Zilyana" runs to 284px in a 205px
			// column, and with the horizontal scrollbar disabled the name — the half
			// the player cannot infer — was the half that got cut.
			if (pb.getFastestTaskMs() > 0)
			{
				section.add(GachamanPanel.smallLine(
					"Fastest: " + formatDuration(pb.getFastestTaskMs()),
					ColorScheme.LIGHT_GRAY_COLOR));
				if (pb.getFastestMonster() != null)
				{
					section.add(GachamanPanel.smallLine("    " + pb.getFastestMonster(),
						ColorScheme.MEDIUM_GRAY_COLOR));
				}
			}
			if (pb.getBiggestHaulGc() > 0)
			{
				section.add(GachamanPanel.smallLine(
					"Biggest haul: " + QuantityFormatter.formatNumber(pb.getBiggestHaulGc()) + " GC",
					ColorScheme.LIGHT_GRAY_COLOR));
				if (pb.getBiggestHaulMonster() != null)
				{
					section.add(GachamanPanel.smallLine("    " + pb.getBiggestHaulMonster(),
						ColorScheme.MEDIUM_GRAY_COLOR));
				}
			}
			section.add(Box.createVerticalStrut(4));
		}
		if (!any)
		{
			section.add(GachamanPanel.smallLine("No records yet — complete some contracts!",
				ColorScheme.MEDIUM_GRAY_COLOR));
		}
		return section;
	}

	private JPanel buildStatsSection(GachaState state)
	{
		JPanel section = GachamanPanel.section("Monster Stats");
		Map<String, MonsterStats> stats = state.getMonsterStats();
		if (stats.isEmpty())
		{
			section.add(GachamanPanel.smallLine("Nothing slain yet.", ColorScheme.MEDIUM_GRAY_COLOR));
			return section;
		}

		// "Done", not "Contracts": the column is 36px wide and the totals line
		// under the table spells the noun out in full anyway.
		String[] columns = {"Monster", "Kills", "GC", "Done"};
		List<Object[]> rows = new ArrayList<>();
		long totalKills = 0;
		long totalGc = 0;
		long totalTasks = 0;
		for (Map.Entry<String, MonsterStats> entry : stats.entrySet())
		{
			MonsterStats ms = entry.getValue();
			rows.add(new Object[]{entry.getKey(), ms.getKills(), ms.getGcEarned(),
				(long) ms.getTasksCompleted()});
			totalKills += ms.getKills();
			totalGc += ms.getGcEarned();
			totalTasks += ms.getTasksCompleted();
		}

		DefaultTableModel model = new DefaultTableModel(rows.toArray(new Object[0][]), columns)
		{
			@Override
			public boolean isCellEditable(int row, int column)
			{
				return false;
			}

			@Override
			public Class<?> getColumnClass(int column)
			{
				return column == 0 ? String.class : Long.class;
			}
		};

		JTable table = new JTable(model);
		table.setAutoCreateRowSorter(true);
		// Long.class picks up the default number renderer, which right-aligns but
		// prints "1234567" — the one place in the plugin where a GC figure had no
		// separators. Sorting still runs on the Long, not on this string.
		table.setDefaultRenderer(Long.class, new NumberCell());
		table.setFont(FontManager.getRunescapeSmallFont());
		table.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		table.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		table.setSelectionBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		table.setSelectionForeground(Color.WHITE);
		table.setGridColor(ColorScheme.DARK_GRAY_COLOR);
		table.setRowHeight(18);
		table.setShowVerticalLines(false);
		table.getTableHeader().setFont(FontManager.getRunescapeSmallFont());
		table.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		table.getTableHeader().setForeground(ColorScheme.BRAND_ORANGE);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(85);
		table.getColumnModel().getColumn(1).setPreferredWidth(38);
		table.getColumnModel().getColumn(2).setPreferredWidth(48);
		table.getColumnModel().getColumn(3).setPreferredWidth(36);
		table.getRowSorter().setSortKeys(Collections.singletonList(
			new RowSorter.SortKey(1, SortOrder.DESCENDING)));

		JPanel tableWrap = new JPanel(new BorderLayout());
		tableWrap.setOpaque(false);
		tableWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		tableWrap.add(table.getTableHeader(), BorderLayout.NORTH);
		tableWrap.add(table, BorderLayout.CENTER);
		section.add(tableWrap);
		section.add(Box.createVerticalStrut(4));

		// Two lines: all three totals on one ran to 238px in a 205px column.
		section.add(GachamanPanel.smallLine(
			"Totals: " + QuantityFormatter.formatNumber(totalKills)
				+ (totalKills == 1 ? " kill  ·  " : " kills  ·  ")
				+ QuantityFormatter.formatNumber(totalGc) + " GC",
			ColorScheme.BRAND_ORANGE));
		section.add(GachamanPanel.smallLine(
			"from " + QuantityFormatter.formatNumber(totalTasks)
				+ (totalTasks == 1 ? " contract" : " contracts"),
			ColorScheme.BRAND_ORANGE));
		return section;
	}

	/** Right-aligned, separator-formatted cell for the table's three Long columns. */
	private static final class NumberCell extends javax.swing.table.DefaultTableCellRenderer
	{
		NumberCell()
		{
			setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		}

		@Override
		protected void setValue(Object value)
		{
			setText(value instanceof Number
				? QuantityFormatter.formatNumber(((Number) value).longValue())
				: (value == null ? "" : value.toString()));
		}
	}

	private static String formatDuration(long ms)
	{
		long totalSeconds = ms / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		if (minutes >= 60)
		{
			return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}
}
