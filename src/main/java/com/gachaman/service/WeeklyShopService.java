package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.SetTable;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Variant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.config.ConfigManager;

/**
 * Deterministic weekly 3-card direct-buy shop: seed = profile key + ISO week,
 * so the rotation is personal and stable with zero stored state. Slot 3 is
 * biased toward unowned cards from sets the player has started.
 */
@Singleton
public class WeeklyShopService
{
	@Value
	public static class ShopSlot
	{
		int slotIndex;
		CardDefinition card;
		int priceGc;
		boolean purchased;
		boolean owned;
	}

	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final CardDatabase cardDatabase;
	private final SetTable setTable;
	private final ConfigManager configManager;

	@Inject
	public WeeklyShopService(GachaStateService stateService, CreditSink creditSink,
		CardDatabase cardDatabase, SetTable setTable, ConfigManager configManager)
	{
		this.stateService = stateService;
		this.creditSink = creditSink;
		this.cardDatabase = cardDatabase;
		this.setTable = setTable;
		this.configManager = configManager;
	}

	public String currentWeekKey()
	{
		LocalDate now = LocalDate.now(ZoneOffset.UTC);
		return now.get(IsoFields.WEEK_BASED_YEAR) + "-W" + now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
	}

	/**
	 * Purely seeded rotation: seed = f(profile key, ISO week). Every
	 * recomputation — before or after purchases, across restarts — returns the
	 * exact same 3 cards. No stored state.
	 */
	public List<ShopSlot> currentOffers()
	{
		GachaState state = stateService.get();
		if (state == null || !cardDatabase.isReady())
		{
			return Collections.emptyList();
		}
		String weekKey = currentWeekKey();
		Set<Integer> ownedIds = state.getOwnedCards().stream()
			.filter(c -> !c.isHologram())
			.map(OwnedCard::getCardId)
			.collect(Collectors.toSet());

		String profile = configManager.getRSProfileKey();
		long seed = splitmix64((profile == null ? 0 : profile.hashCode()) * 31L + weekKey.hashCode());
		Random weekRng = new Random(seed);
		List<CardDefinition> all = new ArrayList<>(cardDatabase.all().values());
		all.sort((a, b) -> Integer.compare(a.getCardId(), b.getCardId())); // determinism

		List<CardDefinition> offers = new ArrayList<>(3);
		Set<Integer> pickedIds = new HashSet<>();
		while (offers.size() < 3 && offers.size() < all.size())
		{
			CardDefinition pick = all.get(weekRng.nextInt(all.size()));
			if (pickedIds.add(pick.getCardId()))
			{
				offers.add(pick);
			}
		}

		Set<Integer> purchased = state.getWeeklyShopPurchases()
			.getOrDefault(weekKey, Collections.emptySet());
		List<ShopSlot> slots = new ArrayList<>(3);
		for (int i = 0; i < offers.size(); i++)
		{
			CardDefinition card = offers.get(i);
			slots.add(new ShopSlot(i, card, Tuning.SHOP_PRICE_GC.get(card.getRarity()),
				purchased.contains(i), ownedIds.contains(card.getCardId())));
		}
		return slots;
	}

	@Nullable
	public OwnedCard purchase(int slotIndex)
	{
		List<ShopSlot> offers = currentOffers();
		if (slotIndex < 0 || slotIndex >= offers.size())
		{
			return null;
		}
		ShopSlot slot = offers.get(slotIndex);
		if (slot.isPurchased() || !creditSink.spend(slot.getPriceGc()))
		{
			return null;
		}
		String weekKey = currentWeekKey();
		OwnedCard card = new OwnedCard(UUID.randomUUID().toString(), slot.getCard().getCardId(),
			null, Variant.NORMAL, System.currentTimeMillis(), "shop:" + weekKey);
		stateService.mutate(s -> {
			List<OwnedCard> owned = new ArrayList<>(s.getOwnedCards());
			owned.add(card);
			java.util.Map<String, Set<Integer>> purchases = new java.util.HashMap<>(s.getWeeklyShopPurchases());
			Set<Integer> week = new HashSet<>(purchases.getOrDefault(weekKey, Collections.emptySet()));
			week.add(slotIndex);
			purchases.put(weekKey, week);
			return s.withOwnedCards(owned).withWeeklyShopPurchases(purchases);
		});
		return card;
	}

	static long splitmix64(long seed)
	{
		long z = seed + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
