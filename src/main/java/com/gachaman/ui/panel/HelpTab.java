package com.gachaman.ui.panel;

import java.util.List;
import static com.gachaman.ui.panel.GachamanPanel.measuredWidth;
import static com.gachaman.ui.panel.GachamanPanel.textBlock;
import com.gachaman.model.*;
import com.gachaman.ui.*;
import com.google.gson.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.ui.*;

/**
 * How-to-play reference for the gamemode. Content is entirely static text and
 * procedurally drawn illustrations (no assets, no network) — but its WRAP
 * WIDTH is not static: the real viewport width depends on which scrollbar
 * width the look-and-feel actually honors, and assuming it from constants has
 * produced both a right-side gap and scrollbar-covered text. The tab measures
 * the live viewport instead and rebuilds whenever that measurement changes.
 */
@Singleton
public class HelpTab extends JPanel {

	/** Horizontal padding a GachamanPanel.section() adds (8px borders each side). */
	private static final int SECTION_PADDING = 16;
	/** Icon column width inside an iconRow (glyph up to 16px + 6px gap). */
	private static final int ICON_COLUMN = 22;

	private static final Color MELEE = new Color(214, 72, 56);
	private static final Color RANGED = new Color(80, 175, 68);
	private static final Color MAGIC = new Color(72, 118, 214);
	private static final Color BODY = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color MUTED = ColorScheme.MEDIUM_GRAY_COLOR;

	private static final int CARD_W = 56;
	private static final int CARD_H = 80;

	/** Wrap width the current content was built for; -1 = never built. */
	private int builtWidth = -1;
	private boolean viewportHooked;

	private final Gson gson;

	@Inject
	public HelpTab(Gson gson) {
		this.gson = gson;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	/** Rebuilds only when the measured viewport width changed; no-op otherwise. */
	void rebuild() {
		int width = measuredWidth(this);
		if (width == builtWidth)
			return;
		builtWidth = width;
		removeAll();
		buildSections(width);
		revalidate();
		repaint();
	}

@Override
	public void addNotify() {
		super.addNotify();
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (!viewportHooked && ancestor instanceof JViewport) {
			// the viewport narrows when the scrollbar appears (and would widen
			// if the LAF ever changed its width) — re-measure and rebuild; the
			// equal-width check makes this settle in at most two passes
			viewportHooked = true;
			ancestor.addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(ComponentEvent e) {
					SwingUtilities.invokeLater(HelpTab.this::rebuild);
				}
			});
		}
	}

	private void addSection(JPanel section, int width) {
		add(new GachamanPanel.WidthCap(section, width));
		add(Box.createVerticalStrut(6));
	}

	// --- Sections ---

	/**
	 * Builds every section from help.json.
	 *
	 * <p>The copy is data, not code: it is ~11,000 words of prose that changes
	 * whenever the rules do, and prose wedged into string concatenations is
	 * prose nobody wants to edit. The resource carries the text and the ORDER;
	 * this method owns the rendering, and the illustrations stay in Java
	 * because they are drawn, not written.
	 */
	private void buildSections(int width) {
		for (Section section : document().sections) {
			JPanel panel = GachamanPanel.section(section.title);
			for (com.google.gson.JsonElement element : section.body) {
				if (element.isJsonPrimitive()) {
					paragraph(panel, width, element.getAsString());
					continue;
				}
				element(panel, width, element.getAsJsonObject());
			}
			addSection(panel, width);
		}
	}

	/**
	 * One non-paragraph element. The trailing gap is carried by the DATA, not
	 * assumed here: the original hand-written sections spaced these differently
	 * on purpose (the blocked-equipment glyph gets 6px, the padlock 4px), and a
	 * renderer that averaged them would quietly restyle the page.
	 */
	private void element(JPanel panel, int width, com.google.gson.JsonObject el) {
		if (el.has("note")) {
			panel.add(GachamanPanel.smallLine(el.get("note").getAsString(), MUTED));
		}
		else if (el.has("muted")) {
			panel.add(textBlock(el.get("muted").getAsString(), MUTED, width - SECTION_PADDING));
		}
		else if (el.has("icon")) {
			ImageIcon icon = "padlock".equals(el.get("icon").getAsString())
				? padlockIcon() : crossedCircleIcon();
			panel.add(iconRow(width, icon, textBlock(el.get("text").getAsString(),
				BODY, width - SECTION_PADDING - ICON_COLUMN)));
		}
		else {
			widget(panel, width, el);
		}
		if (el.has("gap")) {
			panel.add(Box.createVerticalStrut(el.get("gap").getAsInt()));
		}
	}

	private void widget(JPanel panel, int width, com.google.gson.JsonObject el) {
		switch (el.get("widget").getAsString()) {
			case "styleRow": {
				JPanel styles = flowRow(width);
				styles.add(styleLabel("Melee", MELEE));
				styles.add(styleLabel("Ranged", RANGED));
				styles.add(styleLabel("Magic", MAGIC));
				panel.add(styles);
				break;
			}
			case "rarityLadder":
				for (Rarity rarity : Rarity.values()) {
					panel.add(GachamanPanel.smallLine(rarity.getDisplayName(), rarity.getColor()));
					panel.add(Box.createVerticalStrut(1));
				}
				break;
			case "cardSamples": {
				JPanel cards = flowRow(width);
				cards.add(new JLabel(cardIcon(sample("Rune scimitar", Rarity.RARE, Variant.NORMAL, null))));
				cards.add(new JLabel(cardIcon(sample("Rune scimitar", Rarity.RARE, Variant.SHINY, null))));
				cards.add(new JLabel(cardIcon(sample("Dragon Hologram", Rarity.EPIC, Variant.HOLOGRAM,
					"Dragon tier"))));
				panel.add(cards);
				break;
			}
			case "pityMeter":
				panel.add(new GachamanPanel.MeterBar(12 / 30.0, ColorScheme.BRAND_ORANGE, "12 / 30"));
				break;
			case "commandList":
				for (com.google.gson.JsonElement command : el.getAsJsonArray("items")) {
					panel.add(GachamanPanel.smallLine(command.getAsString(), BODY));
					panel.add(Box.createVerticalStrut(1));
				}
				break;
			default:
				break;
		}
	}

	private static CardRenderer.CardView sample(String name, Rarity rarity, Variant variant,
		String subtitle) {
		return CardRenderer.CardView.builder()
			.name(name)
			.rarity(rarity)
			.variant(variant)
			.art(null)
			.subtitle(subtitle)
			.build();
	}

	// --- the document ---

	private static final class Doc {
		List<Section> sections;
	}

	private static final class Section {
		String title;
		List<com.google.gson.JsonElement> body;
	}

	private Doc document;

	private Doc document() {
		if (document == null) {
			Doc loaded = null;
			try (InputStream in = HelpTab.class.getResourceAsStream(
				"/com/gachaman/ui/help.json")) {
				if (in != null) {
					loaded = gson.fromJson(new InputStreamReader(
						in, StandardCharsets.UTF_8), Doc.class);
				}
			}
			catch (Exception e) {
				loaded = null;
			}
			if (loaded == null || loaded.sections == null) {
				loaded = new Doc();
				loaded.sections = Collections.emptyList();
			}
			document = loaded;
		}
		return document;
	}


	// --- Layout helpers ---

	private static void paragraph(JPanel section, int w, String text) {
		section.add(textBlock(text, BODY, w - SECTION_PADDING));
		section.add(Box.createVerticalStrut(4));
	}

/**
	 * A left-aligned flow row whose height never stretches in the BoxLayout.
	 *
	 * <p>Takes the SECTION-OUTER width, like {@link #paragraph}, and takes the
	 * padding off itself — both call sites were handing it the unpadded number
	 * while every other child of the same section subtracted first, so the cap
	 * it enforced was 16px wider than the column it sits in.
	 */
	private static JPanel flowRow(int outerWidth) {
		final int w = outerWidth - SECTION_PADDING;
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)) {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(w, getPreferredSize().height);
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/**
	 * A small glyph beside a wrapped text block, icon pinned to the top. Takes
	 * the SECTION-OUTER width and subtracts the padding itself, for the same
	 * reason {@link #flowRow} does.
	 */
	private static JPanel iconRow(int outerWidth, ImageIcon icon, JComponent text) {
		final int w = outerWidth - SECTION_PADDING;
		JPanel panel = new JPanel(new BorderLayout(6, 0)) {
			@Override
			public Dimension getMaximumSize() {
				return new Dimension(w, getPreferredSize().height);
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel iconLabel = new JLabel(icon);
		iconLabel.setVerticalAlignment(SwingConstants.TOP);
		panel.add(iconLabel, BorderLayout.WEST);
		panel.add(text, BorderLayout.CENTER);
		return panel;
	}

	private static JLabel styleLabel(String name, Color color) {
		JLabel label = new JLabel(name, squareIcon(color), SwingConstants.LEFT);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setIconTextGap(4);
		return label;
	}

	// --- Procedural illustrations ---

	/** A small filled swatch for the attack-style legend. */
	private static ImageIcon squareIcon(Color color) {
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 10, 10);
		g.setColor(color.darker());
		g.drawRect(0, 0, 9, 9);
		g.dispose();
		return new ImageIcon(image);
	}

	/** The 14px crossed-circle glyph, mirroring ForbiddenItemOverlay's icon. */
	private static ImageIcon crossedCircleIcon() {
		int size = 14;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillOval(0, 0, size - 1, size - 1);
		g.setColor(new Color(232, 60, 60));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(1, 1, size - 3, size - 3);
		int inset = 3;
		g.drawLine(inset, size - 1 - inset, size - 1 - inset, inset);
		g.dispose();
		return new ImageIcon(image);
	}

	/** A small padlock glyph for the locked gear slots. */
	private static ImageIcon padlockIcon() {
		int size = 16;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(230, 190, 80));
		g.setStroke(new BasicStroke(2f));
		g.drawArc(4, 1, 7, 10, 0, 180);
		g.setColor(new Color(146, 126, 96));
		g.fillRoundRect(2, 7, 12, 8, 3, 3);
		g.setColor(new Color(230, 190, 80));
		g.drawRoundRect(2, 7, 11, 7, 3, 3);
		g.setColor(new Color(46, 40, 32));
		g.fillOval(7, 9, 3, 3);
		g.dispose();
		return new ImageIcon(image);
	}

	/** A mini card face rendered through the shared CardRenderer. */
	private static ImageIcon cardIcon(CardRenderer.CardView view) {
		BufferedImage image = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		CardRenderer.drawFace(g, 1, 1, CARD_W - 2, CARD_H - 2, view, 300L);
		g.dispose();
		return new ImageIcon(image);
	}
}
