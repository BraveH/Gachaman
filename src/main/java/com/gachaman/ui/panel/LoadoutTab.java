package com.gachaman.ui.panel;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.service.ChestService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.LoadoutService;
import com.gachaman.service.PermissionService;
import com.gachaman.ui.CardImageService;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Loadout: the eleven gear slots arranged like the in-game equipment tab.
 * Deeded slots accept owned cards (holograms fit any slot); locked slots show
 * a padlock and can be unlocked with a pending Slot Deed.
 */
@Singleton
public class LoadoutTab extends JPanel
{
	private static final Color GOLD = new Color(230, 190, 80);
	private static final Color HOLO_BORDER = new Color(120, 220, 255);
	private static final Color LOCKED_FILL = new Color(30, 30, 30);
	private static final Color SLOT_FILL = new Color(40, 38, 34);
	private static final int SLOT_W = 64;
	private static final int SLOT_H = 56;

	/** (gridx, gridy) placement per slot, mirroring the equipment tab shape. */
	private static final Map<GearSlot, int[]> SLOT_GRID = new EnumMap<>(GearSlot.class);

	static
	{
		SLOT_GRID.put(GearSlot.HEAD, new int[]{1, 0});
		SLOT_GRID.put(GearSlot.CAPE, new int[]{0, 1});
		SLOT_GRID.put(GearSlot.AMULET, new int[]{1, 1});
		SLOT_GRID.put(GearSlot.AMMO, new int[]{2, 1});
		SLOT_GRID.put(GearSlot.WEAPON, new int[]{0, 2});
		SLOT_GRID.put(GearSlot.BODY, new int[]{1, 2});
		SLOT_GRID.put(GearSlot.SHIELD, new int[]{2, 2});
		SLOT_GRID.put(GearSlot.LEGS, new int[]{1, 3});
		SLOT_GRID.put(GearSlot.HANDS, new int[]{0, 4});
		SLOT_GRID.put(GearSlot.FEET, new int[]{1, 4});
		SLOT_GRID.put(GearSlot.RING, new int[]{2, 4});
	}

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;
	private final PermissionService permissionService;
	private final ChestService chestService;
	private final LoadoutService loadoutService;

	@Inject
	public LoadoutTab(GachaStateService stateService, CardDatabase cardDatabase,
		CardImageService cardImageService, PermissionService permissionService,
		ChestService chestService, LoadoutService loadoutService)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.cardImageService = cardImageService;
		this.permissionService = permissionService;
		this.chestService = chestService;
		this.loadoutService = loadoutService;
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
			add(GachamanPanel.centeredNote("Log in to manage your loadout."));
			revalidate();
			repaint();
			return;
		}

		if (state.getPendingDeeds() > 0)
		{
			JPanel banner = GachamanPanel.section(null);
			banner.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(GOLD, 1),
				new EmptyBorder(7, 7, 7, 7)));
			String plural = state.getPendingDeeds() == 1 ? "a Slot Deed"
				: state.getPendingDeeds() + " Slot Deeds";
			banner.add(GachamanPanel.line("You have " + plural + "!", GOLD,
				FontManager.getRunescapeBoldFont()));
			banner.add(GachamanPanel.wrapped("Click a locked slot to unlock it.", GOLD));
			add(banner);
			add(Box.createVerticalStrut(6));
		}

		JPanel gridSection = GachamanPanel.section("Loadout");
		JPanel grid = new JPanel(new GridBagLayout());
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		GridBagConstraints gbc = new GridBagConstraints();
		// 2px, not 3. A cell is SLOT_W plus its inset each side, and three columns
		// have to fit the 205px inside a section(): at 3 the grid wanted
		// 3 * (64 + 6) = 210 in a scroll pane with no horizontal bar, so the ring,
		// shield and ammo column simply lost its right edge. 3 * (64 + 4) = 204.
		gbc.insets = new Insets(2, 2, 2, 2);
		for (GearSlot slot : GearSlot.values())
		{
			int[] pos = SLOT_GRID.get(slot);
			gbc.gridx = pos[0];
			gbc.gridy = pos[1];
			grid.add(buildSlotComponent(state, slot), gbc);
		}
		gridSection.add(grid);
		gridSection.add(Box.createVerticalStrut(5));
		gridSection.add(GachamanPanel.wrapped(
			"Left-click a slot to assign a card. Right-click to unassign.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		add(gridSection);

		revalidate();
		repaint();
	}

	private SlotComponent buildSlotComponent(GachaState state, GearSlot slot)
	{
		boolean deeded = permissionService.isSlotDeeded(slot)
			|| state.getDeededSlots().contains(slot.name());
		OwnedCard assigned = loadoutService.assigned(slot);

		String name = null;
		Color border = null;
		BufferedImage sprite = null;
		if (assigned != null && cardDatabase.isReady())
		{
			if (assigned.isHologram())
			{
				HologramDefinition holo = cardDatabase.holograms().get(assigned.getTierKey());
				name = holo != null ? holo.getName() : "Hologram";
				border = HOLO_BORDER;
				if (holo != null)
				{
					sprite = cardImageService.hologramImage(holo, this::repaint);
				}
			}
			else
			{
				CardDefinition def = cardDatabase.card(assigned.getCardId());
				if (def != null)
				{
					name = def.getName();
					border = def.getRarity().getColor();
					sprite = cardImageService.cardImage(def, this::repaint);
				}
				else
				{
					name = "Card #" + assigned.getCardId();
					border = Rarity.COMMON.getColor();
				}
			}
		}

		SlotComponent component = new SlotComponent(slot, deeded, assigned != null,
			state.getPendingDeeds() > 0, name, border, sprite);
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				handleSlotPress(component, slot, e);
			}
		});
		return component;
	}

	private void handleSlotPress(SlotComponent component, GearSlot slot, MouseEvent e)
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return;
		}
		boolean deeded = permissionService.isSlotDeeded(slot)
			|| state.getDeededSlots().contains(slot.name());
		if (!deeded)
		{
			if (state.getPendingDeeds() > 0
				&& GachamanPanel.confirm(this, "Slot Deed",
					"Use a Slot Deed to unlock the " + slot.getDisplayName() + " slot?"))
			{
				chestService.claimDeed(slot);
			}
			return;
		}
		if (SwingUtilities.isRightMouseButton(e))
		{
			OwnedCard assigned = loadoutService.assigned(slot);
			if (assigned != null)
			{
				JPopupMenu menu = new JPopupMenu();
				JMenuItem unassign = new JMenuItem("Unassign " + loadoutService.displayName(assigned));
				unassign.addActionListener(a -> loadoutService.unassign(slot));
				menu.add(unassign);
				menu.show(component, e.getX(), e.getY());
			}
			return;
		}
		showAssignMenu(component, slot, e.getX(), e.getY());
	}

	/**
	 * Card picker: a popup hosting a search field + list. Unlike JMenuItems
	 * (which activate on the mouse RELEASE of the click that opened the menu,
	 * causing accidental picks), a list row only reacts to its own complete
	 * click — selection is always a second, deliberate action. Typing in the
	 * field filters live; Enter picks the top match; Esc closes.
	 */
	private void showAssignMenu(SlotComponent component, GearSlot slot, int x, int y)
	{
		List<OwnedCard> valid = loadoutService.validFor(slot);
		OwnedCard current = loadoutService.assigned(slot);

		JPopupMenu popup = new JPopupMenu();
		popup.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_HOVER_COLOR));
		JPanel content = new JPanel(new BorderLayout(0, 4));
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(6, 6, 6, 6));

		JTextField search = new JTextField();
		search.setBackground(ColorScheme.DARK_GRAY_COLOR);
		search.setForeground(Color.WHITE);
		search.setCaretColor(Color.WHITE);
		search.setBorder(new EmptyBorder(4, 6, 4, 6));
		search.setToolTipText("Type to filter");
		content.add(search, BorderLayout.NORTH);

		DefaultListModel<Object> model = new DefaultListModel<>();
		JList<Object> list = new JList<>(model);
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setSelectionBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		list.setSelectionForeground(Color.WHITE);
		list.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> jList,
				Object value, int index, boolean selected, boolean focused)
			{
				super.getListCellRendererComponent(jList, value, index, selected, focused);
				if (value instanceof OwnedCard)
				{
					OwnedCard owned = (OwnedCard) value;
					setText(loadoutService.displayName(owned));
					BufferedImage icon = iconFor(owned);
					setIcon(icon != null ? new ImageIcon(icon) : null);
				}
				else
				{
					setText(String.valueOf(value));
					setIcon(null);
					setForeground(new Color(240, 140, 130));
				}
				if (!selected)
				{
					setBackground(ColorScheme.DARKER_GRAY_COLOR);
					if (value instanceof OwnedCard)
					{
						setForeground(Color.WHITE);
					}
				}
				return this;
			}
		});

		Runnable refilter = () -> {
			String typed = search.getText() == null ? "" : search.getText().trim();
			String filter = typed.toLowerCase();
			model.clear();
			if (current != null)
			{
				model.addElement("Unassign " + loadoutService.displayName(current));
			}
			int matches = 0;
			for (OwnedCard owned : valid)
			{
				if (filter.isEmpty()
					|| loadoutService.displayName(owned).toLowerCase().contains(filter))
				{
					model.addElement(owned);
					matches++;
				}
			}
			// Counted, not model.isEmpty(). With a card already in the slot the
			// "Unassign" row is always present, so the model was never empty and
			// the slot that HAS a card was the one slot that could never explain
			// an empty list. And the reason matters: a filter that matches nothing
			// is a typo to fix, not a collection to go and earn.
			if (matches == 0)
			{
				model.addElement(filter.isEmpty()
					? "No eligible cards for " + slot.getDisplayName()
					: "No card here matches \"" + typed + "\"");
			}
		};
		refilter.run();
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refilter.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refilter.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refilter.run();
			}
		});

		Runnable pickSelected = () -> {
			Object value = list.getSelectedValue();
			if (value instanceof OwnedCard)
			{
				popup.setVisible(false);
				if (!loadoutService.assign(slot, ((OwnedCard) value).getUuid()))
				{
					GachamanPanel.info(this, "That card cannot go in the "
						+ slot.getDisplayName() + " slot.");
				}
			}
			else if (value instanceof String && ((String) value).startsWith("Unassign"))
			{
				popup.setVisible(false);
				loadoutService.unassign(slot);
			}
		};
		list.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0)
				{
					list.setSelectedIndex(index);
					pickSelected.run();
				}
			}
		});
		search.addActionListener(e -> {
			// Enter picks the first real match
			for (int i = 0; i < model.size(); i++)
			{
				if (model.get(i) instanceof OwnedCard)
				{
					list.setSelectedIndex(i);
					pickSelected.run();
					return;
				}
			}
		});

		JScrollPane scroll = new JScrollPane(list,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setPreferredSize(new Dimension(190,
			Math.min(320, Math.max(60, model.size() * 40))));
		GachamanPanel.styleScrollbar(scroll);
		content.add(scroll, BorderLayout.CENTER);

		popup.add(content);
		popup.show(component, x, y);
		SwingUtilities.invokeLater(search::requestFocusInWindow);
	}

	@Nullable
	private BufferedImage iconFor(OwnedCard owned)
	{
		if (!cardDatabase.isReady())
		{
			return null;
		}
		if (owned.isHologram())
		{
			HologramDefinition holo = cardDatabase.holograms().get(owned.getTierKey());
			return holo == null ? null : cardImageService.hologramImage(holo, null);
		}
		CardDefinition def = cardDatabase.card(owned.getCardId());
		return def == null ? null : cardImageService.cardImage(def, null);
	}

	// --- Slot component ---

	private static final class SlotComponent extends JComponent
	{
		private final GearSlot slot;
		private final boolean deeded;
		private final boolean assigned;
		private final boolean deedAvailable;
		@Nullable
		private final String cardName;
		@Nullable
		private final Color borderColor;
		@Nullable
		private final BufferedImage sprite;

		SlotComponent(GearSlot slot, boolean deeded, boolean assigned, boolean deedAvailable,
			@Nullable String cardName, @Nullable Color borderColor, @Nullable BufferedImage sprite)
		{
			this.slot = slot;
			this.deeded = deeded;
			this.assigned = assigned;
			this.deedAvailable = deedAvailable;
			this.cardName = cardName;
			this.borderColor = borderColor;
			this.sprite = sprite;
			setPreferredSize(new Dimension(SLOT_W, SLOT_H));
			setMinimumSize(new Dimension(SLOT_W, SLOT_H));
			if (!deeded)
			{
				setToolTipText(slot.getDisplayName() + " — locked"
					+ (deedAvailable ? " (click to use a Slot Deed)" : " (find a Slot Deed)"));
			}
			else if (assigned && cardName != null)
			{
				setToolTipText(slot.getDisplayName() + ": " + cardName);
			}
			else
			{
				setToolTipText(slot.getDisplayName() + " — empty (click to assign)");
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			g2.setColor(deeded ? SLOT_FILL : LOCKED_FILL);
			g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);

			Color outline = !deeded
				? (deedAvailable ? GOLD : ColorScheme.MEDIUM_GRAY_COLOR.darker())
				: assigned && borderColor != null ? borderColor : ColorScheme.MEDIUM_GRAY_COLOR;
			g2.setColor(outline);
			g2.setStroke(new BasicStroke(assigned ? 2f : 1.2f));
			g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

			if (!deeded)
			{
				drawPadlock(g2, w / 2, h / 2 - 6, deedAvailable ? GOLD : ColorScheme.MEDIUM_GRAY_COLOR);
				drawSlotName(g2, w, h, ColorScheme.MEDIUM_GRAY_COLOR);
			}
			else if (!assigned)
			{
				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString("+", (w - fm.stringWidth("+")) / 2, h / 2 + 2);
				drawSlotName(g2, w, h, ColorScheme.MEDIUM_GRAY_COLOR);
			}
			else
			{
				if (sprite != null)
				{
					int sw = Math.min(sprite.getWidth(), w - 8);
					int sh = Math.min(sprite.getHeight(), h - 20);
					g2.drawImage(sprite, (w - sw) / 2, 4, sw, sh, null);
				}
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(borderColor != null ? borderColor : Color.WHITE);
				String label = cardName == null ? slot.getDisplayName() : truncate(g2, cardName, w - 6);
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString(label, (w - fm.stringWidth(label)) / 2, h - 5);
			}
			g2.dispose();
		}

		private void drawSlotName(Graphics2D g2, int w, int h, Color color)
		{
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(color);
			String label = truncate(g2, slot.getDisplayName(), w - 6);
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(label, (w - fm.stringWidth(label)) / 2, h - 5);
		}

		private static void drawPadlock(Graphics2D g2, int cx, int cy, Color color)
		{
			g2.setColor(color);
			g2.setStroke(new BasicStroke(2f));
			// shackle
			g2.drawArc(cx - 6, cy - 8, 12, 12, 0, 180);
			// body
			g2.fillRoundRect(cx - 8, cy - 2, 16, 12, 3, 3);
			// keyhole
			g2.setColor(LOCKED_FILL);
			g2.fillOval(cx - 2, cy + 1, 4, 4);
			g2.drawLine(cx, cy + 4, cx, cy + 7);
		}

		private static String truncate(Graphics2D g2, String text, int maxWidth)
		{
			FontMetrics fm = g2.getFontMetrics();
			if (fm.stringWidth(text) <= maxWidth)
			{
				return text;
			}
			String drawn = text;
			while (drawn.length() > 2 && fm.stringWidth(drawn + "…") > maxWidth)
			{
				drawn = drawn.substring(0, drawn.length() - 1);
			}
			return drawn + "…";
		}
	}
}
