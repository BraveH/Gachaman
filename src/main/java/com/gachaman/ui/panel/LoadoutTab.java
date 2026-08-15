package com.gachaman.ui.panel;

import java.util.List;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import com.gachaman.ui.*;
import com.google.gson.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import net.runelite.client.ui.*;

/**
 * Loadout: the eleven gear slots arranged like the in-game equipment tab.
 * Deeded slots accept owned cards (holograms fit any slot); locked slots show
 * a padlock and can be unlocked with a pending Slot Deed.
 */
@Singleton
public class LoadoutTab extends JPanel {
	private static final Color GOLD = new Color(230, 190, 80);
	private static final Color HOLO_BORDER = new Color(120, 220, 255);
	private static final Color LOCKED_FILL = new Color(30, 30, 30);
	private static final Color SLOT_FILL = new Color(40, 38, 34);
	private static final int SLOT_W = 64;
	private static final int SLOT_H = 56;

	/**
	 * (gridx, gridy) placement per slot, mirroring the equipment tab shape —
	 * read from loadout-board.json, which is the SAME table the in-game
	 * LoadoutOverlay lays its sockets out from. Two hand-written copies of one
	 * arrangement is one edit away from a board that disagrees with itself about
	 * where the ring goes; see {@link BoardLayout}.
	 *
	 * <p>An instance field loaded in the constructor rather than the static block
	 * this replaces: the Gson is injected, so it does not exist until Guice has
	 * built this panel, and a data table must never load from a static
	 * initialiser.
	 */
	private final Map<String, BoardLayout.Socket> sockets;

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;
	private final PermissionService permissionService;
	private final ChestService chestService;
	private final LoadoutService loadoutService;

	@Inject
	public LoadoutTab(GachaStateService stateService, CardDatabase cardDatabase,
		CardImageService cardImageService, PermissionService permissionService,
		ChestService chestService, LoadoutService loadoutService, Gson gson) {
		this.sockets = BoardLayout.load(gson);
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

	void rebuild() {
		removeAll();
		GachaState state = stateService.get();
		if (state == null) {
			add(GachamanPanel.centeredNote("Log in to manage your loadout."));
			revalidate();
			repaint();
			return;
		}

		if (state.getPendingDeeds() > 0) {
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
		for (GearSlot slot : GearSlot.values()) {
			BoardLayout.Socket cell = sockets.get(slot.name());
			// a slot the resource does not name is left off the grid rather than
			// stacked at (0,0) on top of another one
			if (cell == null)
				continue;
			gbc.gridx = cell.getCol();
			gbc.gridy = cell.getRow();
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

	private SlotComponent buildSlotComponent(GachaState state, GearSlot slot) {
		boolean deeded = permissionService.isSlotDeeded(slot)
			|| state.getDeededSlots().contains(slot.name());
		OwnedCard assigned = loadoutService.assigned(slot);

		String name = null;
		Color border = null;
		BufferedImage sprite = null;
		if (assigned != null && cardDatabase.isReady()) {
			if (assigned.isHologram()) {
				HologramDefinition holo = cardDatabase.holograms().get(assigned.getTierKey());
				name = holo != null ? holo.getName() : "Hologram";
				border = HOLO_BORDER;
				if (holo != null) {
					sprite = cardImageService.hologramImage(holo, this::repaint);
				}
			}
			else {
				CardDefinition def = cardDatabase.card(assigned.getCardId());
				if (def != null) {
					name = def.getName();
					border = def.getRarity().getColor();
					sprite = cardImageService.cardImage(def, this::repaint);
				}
				else {
					name = "Card #" + assigned.getCardId();
					border = Rarity.COMMON.getColor();
				}
			}
		}

		SlotComponent component = new SlotComponent(slot, deeded, assigned != null,
			state.getPendingDeeds() > 0, name, border, sprite);
		component.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				handleSlotPress(component, slot, e);
			}
		});
		return component;
	}

	private void handleSlotPress(SlotComponent component, GearSlot slot, MouseEvent e) {
		GachaState state = stateService.get();
		if (state == null)
			return;
		boolean deeded = permissionService.isSlotDeeded(slot)
			|| state.getDeededSlots().contains(slot.name());
		if (!deeded) {
			if (state.getPendingDeeds() > 0
				&& GachamanPanel.confirm(this, "Slot Deed",
					"Use a Slot Deed to unlock the " + slot.getDisplayName() + " slot?")) {
				chestService.claimDeed(slot);
			}
			return;
		}
		if (SwingUtilities.isRightMouseButton(e)) {
			OwnedCard assigned = loadoutService.assigned(slot);
			if (assigned != null) {
				JPopupMenu menu = new JPopupMenu();
				JMenuItem unassign = new JMenuItem("Unassign " + loadoutService.displayName(assigned));
				unassign.addActionListener(a -> unassignOrExplain(slot));
				menu.add(unassign);
				menu.show(component, e.getX(), e.getY());
			}
			return;
		}
		showAssignMenu(component, slot, e.getX(), e.getY());
	}

	/**
	 * Clear a slot, and put the guard's refusal in front of the player when it
	 * says no. Both unassign routes on this page — the right-click menu item
	 * and the picker's Unassign row — come through here, so neither can be the
	 * one that silently swallows a refusal.
	 *
	 * <p>The chat line has already gone out from LoadoutService; this is the
	 * dialog on top of it, because a click made in the sidebar is answered in
	 * the sidebar. The assign refusal a few lines below sets that expectation.
	 *
	 * <p>No hop to the client thread, deliberately. The guard reads the worn
	 * container, but it is advisory rather than a boundary — the worst a stale
	 * read can do is refuse (or allow) a beat late — and both routes already
	 * mutate and checkpoint state from this thread, so a hop would be new
	 * machinery buying an answer nobody can act on faster than a game tick.
	 */
	private void unassignOrExplain(GearSlot slot) {
		if (!loadoutService.unassign(slot)) {
			GachamanPanel.info(this, "That card is still unlocking what you are wearing."
				+ " Take the item off first.");
		}
	}

	/**
	 * Card picker: a popup hosting a search field + list. Unlike JMenuItems
	 * (which activate on the mouse RELEASE of the click that opened the menu,
	 * causing accidental picks), a list row only reacts to its own complete
	 * click — selection is always a second, deliberate action. Typing in the
	 * field filters live; Enter picks the top match; Esc closes.
	 */
	private void showAssignMenu(SlotComponent component, GearSlot slot, int x, int y) {
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
		list.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> jList,
				Object value, int index, boolean selected, boolean focused) {
				super.getListCellRendererComponent(jList, value, index, selected, focused);
				if (value instanceof OwnedCard) {
					OwnedCard owned = (OwnedCard) value;
					setText(loadoutService.displayName(owned));
					BufferedImage icon = iconFor(owned);
					setIcon(icon != null ? new ImageIcon(icon) : null);
				}
				else {
					setText(String.valueOf(value));
					setIcon(null);
					setForeground(new Color(240, 140, 130));
				}
				if (!selected) {
					setBackground(ColorScheme.DARKER_GRAY_COLOR);
					if (value instanceof OwnedCard) {
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
			if (current != null) {
				model.addElement("Unassign " + loadoutService.displayName(current));
			}
			int matches = 0;
			for (OwnedCard owned : valid) {
				if (filter.isEmpty()
					|| loadoutService.displayName(owned).toLowerCase().contains(filter)) {
					model.addElement(owned);
					matches++;
				}
			}
			// Counted, not model.isEmpty(). With a card already in the slot the
			// "Unassign" row is always present, so the model was never empty and
			// the slot that HAS a card was the one slot that could never explain
			// an empty list. And the reason matters: a filter that matches nothing
			// is a typo to fix, not a collection to go and earn.
			if (matches == 0) {
				model.addElement(filter.isEmpty()
					? "No eligible cards for " + slot.getDisplayName()
					: "No card here matches \"" + typed + "\"");
			}
		};
		refilter.run();
		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refilter.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refilter.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refilter.run();
			}
		});

		Runnable pickSelected = () -> {
			Object value = list.getSelectedValue();
			if (value instanceof OwnedCard) {
				popup.setVisible(false);
				if (!loadoutService.assign(slot, ((OwnedCard) value).getUuid())) {
					GachamanPanel.info(this, "That card cannot go in the "
						+ slot.getDisplayName() + " slot.");
				}
			}
			else if (value instanceof String && ((String) value).startsWith("Unassign")) {
				popup.setVisible(false);
				unassignOrExplain(slot);
			}
		};
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0) {
					list.setSelectedIndex(index);
					pickSelected.run();
				}
			}
		});
		search.addActionListener(e -> {
			// Enter picks the first real match
			for (int i = 0; i < model.size(); i++) {
				if (model.get(i) instanceof OwnedCard) {
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
	private BufferedImage iconFor(OwnedCard owned) {
		if (!cardDatabase.isReady())
			return null;
		if (owned.isHologram()) {
			HologramDefinition holo = cardDatabase.holograms().get(owned.getTierKey());
			return holo == null ? null : cardImageService.hologramImage(holo, null);
		}
		CardDefinition def = cardDatabase.card(owned.getCardId());
		return def == null ? null : cardImageService.cardImage(def, null);
	}

	// --- Slot component ---

	private static final class SlotComponent extends JComponent {
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
			String cardName, Color borderColor, BufferedImage sprite) {
			this.slot = slot;
			this.deeded = deeded;
			this.assigned = assigned;
			this.deedAvailable = deedAvailable;
			this.cardName = cardName;
			this.borderColor = borderColor;
			this.sprite = sprite;
			setPreferredSize(new Dimension(SLOT_W, SLOT_H));
			setMinimumSize(new Dimension(SLOT_W, SLOT_H));
			// every arm opens with the slot name, so only the tail varies
			setToolTipText(slot.getDisplayName() + (!deeded
				? " — locked" + (deedAvailable ? " (click to use a Slot Deed)" : " (find a Slot Deed)")
				: assigned && cardName != null
					? ": " + cardName
					: " — empty (click to assign)"));
		}

		@Override
		protected void paintComponent(Graphics g) {
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

			if (!deeded) {
				drawPadlock(g2, w / 2, h / 2 - 6, deedAvailable ? GOLD : ColorScheme.MEDIUM_GRAY_COLOR);
				drawSlotName(g2, w, h, ColorScheme.MEDIUM_GRAY_COLOR);
			}
			else if (!assigned) {
				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
				centred(g2, "+", w, h / 2 + 2);
				drawSlotName(g2, w, h, ColorScheme.MEDIUM_GRAY_COLOR);
			}
			else {
				if (sprite != null) {
					int sw = Math.min(sprite.getWidth(), w - 8);
					int sh = Math.min(sprite.getHeight(), h - 20);
					g2.drawImage(sprite, (w - sw) / 2, 4, sw, sh, null);
				}
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(borderColor != null ? borderColor : Color.WHITE);
				centred(g2, cardName == null ? slot.getDisplayName() : truncate(g2, cardName, w - 6),
					w, h - 5);
			}
			g2.dispose();
		}

		private void drawSlotName(Graphics2D g2, int w, int h, Color color) {
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(color);
			centred(g2, truncate(g2, slot.getDisplayName(), w - 6), w, h - 5);
		}

		/**
		 * Draw text horizontally centred in a {@code w}-wide cell on the given
		 * baseline — the measure-then-draw pair every label in this component
		 * repeated verbatim, in the current font and colour so callers keep setting
		 * those themselves.
		 */
		private static void centred(Graphics2D g2, String text, int w, int baselineY) {
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(text, (w - fm.stringWidth(text)) / 2, baselineY);
		}

		private static void drawPadlock(Graphics2D g2, int cx, int cy, Color color) {
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

		private static String truncate(Graphics2D g2, String text, int maxWidth) {
			FontMetrics fm = g2.getFontMetrics();
			if (fm.stringWidth(text) <= maxWidth)
				return text;
			String drawn = text;
			while (drawn.length() > 2 && fm.stringWidth(drawn + "…") > maxWidth) {
				drawn = drawn.substring(0, drawn.length() - 1);
			}
			return drawn + "…";
		}
	}
}
