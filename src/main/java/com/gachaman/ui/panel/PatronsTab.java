package com.gachaman.ui.panel;

import com.gachaman.Tuning;
import com.gachaman.model.GachaState;
import com.gachaman.model.PatronRecord;
import com.gachaman.service.AccountKey;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.PatronMark;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The Patrons page: everyone you have finished a shared contract with, most
 * contracts first.
 *
 * <p>This is the Patron's Mark's HOME. The pip on the party page can only show
 * a partner who is standing in your party right now, which means the tally
 * itself was previously invisible the moment they logged off — you could hold
 * a hundred marks with someone and never see the number. Here the ledger is
 * the page.
 *
 * <p>STRICTLY COSMETIC, like the mark itself: no GC, no multiplier, no unlock.
 * The page states that outright rather than leaving a number that looks like a
 * currency sitting unexplained.
 *
 * <p>Every name here is SELF-REPORTED — it is the label that partner's own
 * client broadcast at the moment you finished together. The identity underneath
 * is their {@link AccountKey}, which is why a partner who
 * renames keeps one row instead of forking into two half-histories.
 */
@Singleton
public class PatronsTab extends JPanel
{
	private static final Color DETAIL_GRAY = new Color(138, 138, 138);
	private static final Color BODY_GRAY = new Color(200, 200, 200);
	private static final long DAY_MS = 86_400_000L;

	private final GachaStateService stateService;

	private final JPanel header = new JPanel();
	private final JEditorPane list = new JEditorPane();

	@Inject
	public PatronsTab(GachaStateService stateService)
	{
		this.stateService = stateService;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
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
		List<PatronRecord> patrons = state == null
			? Collections.emptyList() : PatronMark.ranked(state.getPatrons());

		header.add(buildTotals(patrons));
		// ONE clock reading for the whole page: sampling per row would let two
		// rows written in the same second render different ages
		list.setText(htmlWrap(buildRows(patrons, System.currentTimeMillis())));
		list.setCaretPosition(0);
		header.revalidate();
		header.repaint();
	}

	private static JPanel buildTotals(List<PatronRecord> patrons)
	{
		JPanel section = GachamanPanel.section(null);
		section.add(GachamanPanel.line(patrons.size() + (patrons.size() == 1 ? " patron" : " patrons"),
			Color.WHITE, FontManager.getRunescapeBoldFont()));
		section.add(Box.createVerticalStrut(4));

		int marks = 0;
		for (PatronRecord record : patrons)
		{
			marks += record.getCount();
		}
		// MARKS, never "shared contracts": one contract finished with three
		// partners earns three marks, and calling that three contracts would
		// contradict the Dossier's own shared count on the very next tab
		section.add(GachamanPanel.smallLine(QuantityFormatter.formatNumber(marks)
				+ (marks == 1 ? " mark earned" : " marks earned"),
			ColorScheme.BRAND_ORANGE));
		if (patrons.size() >= Tuning.PATRON_MAX_PARTNERS)
		{
			// wrapped, not smallLine: 65 characters is ~300px of small font in a
			// 214px header, and a non-wrapping label that wide widens the whole
			// tab and clips every row beside it
			section.add(GachamanPanel.wrapped("(the ledger keeps " + Tuning.PATRON_MAX_PARTNERS
				+ " partners; one-off strangers drop off first)", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		section.add(Box.createVerticalStrut(3));
		section.add(GachamanPanel.smallLine("Cosmetic only — marks pay nothing.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		return section;
	}

	/**
	 * The rows. Order is {@link PatronMark#ranked}'s and is NOT re-sorted here:
	 * that comparator is also what picks the party page's mark owner, so a
	 * second ordering in the UI could put a different name at the top of this
	 * page than the one wearing the outlined pip.
	 */
	private static String buildRows(List<PatronRecord> patrons, long now)
	{
		if (patrons.isEmpty())
		{
			return "<font color='#909090'>No shared contracts finished yet. Roll one with a"
				+ " party and whoever stands with you lands here.</font>";
		}
		StringBuilder html = new StringBuilder();
		for (int i = 0; i < patrons.size(); i++)
		{
			PatronRecord record = patrons.get(i);
			int count = record.getCount();
			// The name is another player's string off the party relay.
			// PatronMark.normalizeName already refuses angle brackets, but the
			// escape belongs at the renderer rather than resting on a validator
			// two classes away.
			html.append("<font color='").append(hex(PartyTab.patronColor(count))).append("'>")
				.append(GachamanPanel.escape(PatronMark.displayName(record))).append("</font>")
				.append("<font color='").append(hex(BODY_GRAY)).append("'>").append(GachamanPanel.DOT)
				.append(QuantityFormatter.formatNumber(count))
				.append(count == 1 ? " contract" : " contracts")
				// the list is already in mark order, so the first row IS the mark's
				// owner — the same rule PatronMark.topKey uses, not a second one
				.append(i == 0 && count > 0 ? GachamanPanel.DOT + "top" : "")
				.append("</font><br/>");

			html.append("<font color='").append(hex(DETAIL_GRAY)).append("'>")
				.append(GachamanPanel.escape(PatronMark.tierLabel(count)));
			String last = ago(record.getLastSharedAt(), now);
			if (!last.isEmpty())
			{
				// "shared", not "last": ago() answers "today" and "yesterday" as
				// well as "4d ago", and "last today" is not a sentence.
				html.append(GachamanPanel.DOT).append("shared ").append(last);
			}
			html.append("</font><br/><br/>");
		}
		return html.toString();
	}

	/**
	 * A compact age for a 200px column: "today", "4d ago", "3w ago", "2y ago".
	 *
	 * Relative rather than a date, because a patron's last contract can be a
	 * year old and a "MMM d HH:mm" stamp with no year would read as this year.
	 * Empty for an unrecorded timestamp, so the caller drops the segment
	 * instead of printing an age measured from 1970.
	 *
	 * A clock that has moved BACKWARDS (a resync, a timezone-naive edit, a save
	 * copied from another machine) reads as today rather than a negative age:
	 * "shared -3 days ago" is the only answer here that is certainly wrong.
	 */
	static String ago(long then, long now)
	{
		if (then <= 0)
		{
			return "";
		}
		long days = Math.max(0, now - then) / DAY_MS;
		if (days <= 0)
		{
			return "today";
		}
		if (days == 1)
		{
			return "yesterday";
		}
		if (days < 7)
		{
			return days + "d ago";
		}
		if (days < 30)
		{
			return (days / 7) + "w ago";
		}
		if (days < 365)
		{
			return (days / 30) + "mo ago";
		}
		return (days / 365) + "y ago";
	}

	private static String htmlWrap(String body)
	{
		return "<html><body>" + body + "</body></html>";
	}

	private static String hex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}
}
