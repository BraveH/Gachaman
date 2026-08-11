package com.gachaman.ui.loadout;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import com.gachaman.service.LoadoutService;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;

/**
 * Chatbox picker for assigning an owned card to a loadout slot. Mirrors
 * RuneLite's own ChatboxItemSearch icon-grid recipe, but searches the
 * player's valid cards for one GearSlot instead of the item database.
 * Open via {@link #openFor(GearSlot)}; typing refilters; ESC closes.
 */
@Slf4j
@Singleton
public class ChatboxCardSearch extends ChatboxTextInput {
	private static final int ICON_WIDTH = 36;
	private static final int ICON_HEIGHT = 32;
	private static final int PADDING = 6;
	private static final int MAX_RESULTS = 24;
	private static final int FONT_SIZE = 16;
	private static final int HOVERED_OPACITY = 128;

	@RequiredArgsConstructor
	private static final class Result {
		final String uuid;
		final String name;
		final int iconItemId;
		final Rarity rarity;
	}

	private final ChatboxPanelManager chatboxPanelManager;
	private final LoadoutService loadoutService;
	private final CardDatabase cardDatabase;

	private final List<Result> results = new ArrayList<>();
	@Nullable
	private GearSlot slot;

	@Inject
	public ChatboxCardSearch(ChatboxPanelManager chatboxPanelManager, ClientThread clientThread,
		LoadoutService loadoutService, CardDatabase cardDatabase) {
		super(chatboxPanelManager, clientThread);
		this.chatboxPanelManager = chatboxPanelManager;
		this.loadoutService = loadoutService;
		this.cardDatabase = cardDatabase;
		lines(1);
		onChanged(searchText -> clientThread.invokeLater(() -> {
			filterResults();
			update();
		}));
	}

	/** Open the picker for one loadout slot. Safe to call from any thread. */
	public void openFor(GearSlot gearSlot) {
		clientThread.invokeLater(() -> {
			// close any live input first (incl. a previous run of this search)
			// so key/mouse listeners unregister cleanly before we rebuild
			if (chatboxPanelManager.getCurrentInput() != null) {
				chatboxPanelManager.close();
			}
			this.slot = gearSlot;
			prompt(gearSlot.getDisplayName() + " slot — choose a card");
			value("");
			onClose(() -> {
				if (this.slot == gearSlot) {
					this.slot = null;
				}
			});
			filterResults();
			build(); // opens via chatboxPanelManager.openInput(this)
		});
	}

	@Override
	protected void update() {
		Widget container = chatboxPanelManager.getContainerWidget();
		if (container == null) {
			return;
		}
		container.deleteAllChildren();

		Widget promptWidget = container.createChild(-1, WidgetType.TEXT);
		promptWidget.setText(getPrompt());
		promptWidget.setTextColor(0x800000);
		promptWidget.setFontId(getFontID());
		promptWidget.setOriginalX(0);
		promptWidget.setOriginalY(5);
		promptWidget.setOriginalWidth(container.getWidth());
		promptWidget.setOriginalHeight(FONT_SIZE);
		promptWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.revalidate();

		buildEdit(0, 5 + FONT_SIZE, container.getWidth(), FONT_SIZE);

		int x = PADDING;
		int y = PADDING * 3;
		for (Result result : results) {
			final Result r = result;
			final Widget item = container.createChild(-1, WidgetType.GRAPHIC);
			item.setItemId(r.iconItemId);
			item.setItemQuantity(10000);
			item.setItemQuantityMode(ItemQuantityMode.NEVER);
			item.setOriginalX(x);
			item.setOriginalY(y + FONT_SIZE * 2);
			item.setOriginalWidth(ICON_WIDTH);
			item.setOriginalHeight(ICON_HEIGHT);
			item.setBorderType(1);
			item.setName(colTag(r.rarity) + r.name + "</col>");
			item.setHasListener(true);
			item.setAction(0, "Select");
			item.setOnMouseOverListener((JavaScriptCallback) ev -> item.setOpacity(HOVERED_OPACITY));
			item.setOnMouseLeaveListener((JavaScriptCallback) ev -> item.setOpacity(0));
			item.setOnOpListener((JavaScriptCallback) ev -> select(r));
			item.revalidate();

			x += ICON_WIDTH + PADDING;
			if (x + ICON_WIDTH >= container.getWidth()) {
				y += ICON_HEIGHT + PADDING;
				x = PADDING;
			}
		}
	}

	@Override
	public void keyPressed(KeyEvent ev) {
		if (ev.getKeyCode() == KeyEvent.VK_ENTER) {
			ev.consume();
			if (!results.isEmpty()) {
				final Result first = results.get(0);
				clientThread.invokeLater(() -> select(first));
			}
			return;
		}
		super.keyPressed(ev);
	}

	/** Client thread. */
	private void select(Result result) {
		GearSlot target = slot;
		if (target != null) {
			loadoutService.assign(target, result.uuid);
		}
		chatboxPanelManager.close();
	}

	private void filterResults() {
		results.clear();
		GearSlot target = slot;
		if (target == null || !cardDatabase.isReady()) {
			return;
		}
		String filter = getValue() == null ? "" : getValue().toLowerCase(Locale.ROOT).trim();
		List<OwnedCard> valid;
		try {
			valid = loadoutService.validFor(target);
		}
		catch (Exception e) {
			log.warn("validFor({}) failed", target, e);
			return;
		}
		if (valid == null) {
			return;
		}
		Set<String> seen = new HashSet<>();
		List<Result> matched = new ArrayList<>();
		for (OwnedCard owned : valid) {
			Result r = toResult(owned);
			if (r == null || !r.name.toLowerCase(Locale.ROOT).contains(filter)) {
				continue;
			}
			if (seen.add(r.name)) {
				matched.add(r);
			}
		}
		matched.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
		for (Result r : matched) {
			if (results.size() >= MAX_RESULTS) {
				break;
			}
			results.add(r);
		}
	}

	@Nullable
	private Result toResult(OwnedCard owned) {
		if (owned.isHologram()) {
			HologramDefinition holo = cardDatabase.holograms().get(owned.getTierKey());
			if (holo == null) {
				return null;
			}
			CardDefinition rep = cardDatabase.cardByName(holo.getRepresentativeItemName());
			if (rep == null) {
				return null;
			}
			return new Result(owned.getUuid(), holo.getName(), rep.getCardId(), holo.getRarity());
		}
		CardDefinition card = cardDatabase.card(owned.getCardId());
		if (card == null) {
			return null;
		}
		String name = owned.getVariant() == Variant.SHINY ? card.getName() + " (Shiny)" : card.getName();
		return new Result(owned.getUuid(), name, card.getCardId(), card.getRarity());
	}

	private static String colTag(Rarity rarity) {
		int rgb = rarity.getColor().getRGB() & 0xFFFFFF;
		return "<col=" + String.format("%06x", rgb) + ">";
	}
}
