package com.gachaman.ui;

import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Card art from the client's own item sprites (offline, always available).
 * Sprites load asynchronously; callers can register a repaint hook.
 */
@Singleton
public class CardImageService
{
	private final ItemManager itemManager;
	private final CardDatabase cardDatabase;
	private final Map<Integer, BufferedImage> cache = new ConcurrentHashMap<>();

	@Inject
	public CardImageService(ItemManager itemManager, CardDatabase cardDatabase)
	{
		this.itemManager = itemManager;
		this.cardDatabase = cardDatabase;
	}

	/**
	 * Item sprite for a card (36x32 async). Returns immediately; onLoaded runs
	 * (possibly later, possibly never for already-loaded images) for repaints.
	 */
	@Nullable
	public BufferedImage itemImage(int itemId, @Nullable Runnable onLoaded)
	{
		BufferedImage cached = cache.get(itemId);
		if (cached != null)
		{
			// re-attach EVERY caller's hook: onLoaded fires immediately when
			// already loaded, so late callers never miss their repaint
			if (onLoaded != null && cached instanceof AsyncBufferedImage)
			{
				((AsyncBufferedImage) cached).onLoaded(onLoaded);
			}
			return cached;
		}
		AsyncBufferedImage async = itemManager.getImage(itemId);
		if (async == null)
		{
			return null;
		}
		cache.put(itemId, async);
		if (onLoaded != null)
		{
			async.onLoaded(onLoaded);
		}
		return async;
	}

	@Nullable
	public BufferedImage cardImage(CardDefinition card, @Nullable Runnable onLoaded)
	{
		return itemImage(card.getCardId(), onLoaded);
	}

	@Nullable
	public BufferedImage hologramImage(HologramDefinition holo, @Nullable Runnable onLoaded)
	{
		CardDefinition representative = cardDatabase.cardByName(holo.getRepresentativeItemName());
		return representative == null ? null : cardImage(representative, onLoaded);
	}
}
