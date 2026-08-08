package com.gachaman.ui.panel;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.SetTable;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.SetPerkService;
import java.awt.Color;
import java.awt.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Card sets: per-set progress, perk description, gold highlight on completed
 * sets and silhouetted (darkened) names for the missing members.
 */
@Singleton
public class SetsTab extends JPanel
{
	private static final Color GOLD = new Color(230, 190, 80);
	private static final Color SILHOUETTE = new Color(88, 84, 74);
	private static final Color COMPLETE_BAR = new Color(120, 200, 120);

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final SetTable setTable;

	@Inject
	public SetsTab(GachaStateService stateService, CardDatabase cardDatabase, SetTable setTable)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.setTable = setTable;
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
			add(GachamanPanel.centeredNote("Log in to view your sets."));
			revalidate();
			repaint();
			return;
		}
		if (!cardDatabase.isReady() || setTable.getSets().isEmpty())
		{
			add(GachamanPanel.centeredNote("No sets available."));
			revalidate();
			repaint();
			return;
		}

		Set<Integer> ownedIds = new HashSet<>();
		Set<String> ownedHoloTiers = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards())
		{
			if (owned.isHologram())
			{
				ownedHoloTiers.add(owned.getTierKey());
			}
			else
			{
				ownedIds.add(owned.getCardId());
			}
		}

		for (SetTable.CardSet set : setTable.getSets())
		{
			add(buildSetRow(state, set, ownedIds, ownedHoloTiers));
			add(Box.createVerticalStrut(6));
		}
		revalidate();
		repaint();
	}

	private JPanel buildSetRow(GachaState state, SetTable.CardSet set,
		Set<Integer> ownedIds, Set<String> ownedHoloTiers)
	{
		List<CardDefinition> members = cardDatabase.setMembers(set.getSetKey());
		int ownedCount = 0;
		StringBuilder missing = new StringBuilder();
		for (CardDefinition member : members)
		{
			boolean owned = ownedIds.contains(member.getCardId())
				|| (member.getTierKey() != null && ownedHoloTiers.contains(member.getTierKey()));
			if (owned)
			{
				ownedCount++;
			}
			else
			{
				if (missing.length() > 0)
				{
					missing.append(", ");
				}
				missing.append(member.getName());
			}
		}
		boolean completed = state.getCompletedSets().contains(set.getSetKey())
			|| (!members.isEmpty() && ownedCount >= members.size()
				&& members.size() >= set.getCardNames().size());

		JPanel row = GachamanPanel.section(null);
		if (completed)
		{
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(GOLD, 1),
				new EmptyBorder(7, 7, 7, 7)));
		}

		// "*" not a star glyph: the RuneScape faces have no U+2605 and painted 44 tofu
		// boxes here, one per boss set. The marker itself has to stay — it is gated on
		// isBoss(), while GOLD and the gold border are gated on the orthogonal
		// `completed`, so nothing else on the row carries "this is a boss set".
		String titleText = set.getName() + (set.isBoss() ? " *" : "");
		JLabel title = GachamanPanel.line(titleText, completed ? GOLD : Color.WHITE,
			FontManager.getRunescapeBoldFont());
		row.add(title);
		row.add(Box.createVerticalStrut(4));

		int total = Math.max(members.size(), set.getCardNames().size());
		double fraction = total <= 0 ? 0 : (double) ownedCount / total;
		row.add(new GachamanPanel.MeterBar(fraction,
			completed ? COMPLETE_BAR : ColorScheme.BRAND_ORANGE,
			ownedCount + "/" + total));
		row.add(Box.createVerticalStrut(4));

		String perkText = SetPerkService.perkDescription(set.getPerk());
		if (!perkText.isEmpty())
		{
			row.add(GachamanPanel.wrapped(perkText,
				completed ? COMPLETE_BAR : ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (members.size() < set.getCardNames().size())
		{
			row.add(GachamanPanel.smallLine("(some members unresolved)", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		if (!completed && missing.length() > 0)
		{
			row.add(Box.createVerticalStrut(3));
			row.add(GachamanPanel.wrapped("Missing: " + missing, SILHOUETTE));
		}
		if (completed)
		{
			row.add(Box.createVerticalStrut(3));
			row.add(GachamanPanel.smallLine("COMPLETE", GOLD));
		}
		return row;
	}
}
