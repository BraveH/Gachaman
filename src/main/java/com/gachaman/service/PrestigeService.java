package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.util.QuantityFormatter;

/**
 * Prestige/rebirth: sacrifice all Common+Uncommon NORMAL cards + GC for a
 * permanent rank. Perks are non-structural: +5% GC per rank, rank 2 lowers the
 * pity hard cap, rank 3 improves jackpot odds. Shiny/Hologram cards are exempt.
 */
@Singleton
public class PrestigeService
{
	@Value
	public static class PrestigePlan
	{
		boolean eligible;
		String requirementText;
		int cardsToBurn;
		long gcCost;
		int nextRank;
	}

	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CeremonyBus ceremonyBus;

	@Inject
	public PrestigeService(GachaStateService stateService, CardDatabase cardDatabase,
		CeremonyBus ceremonyBus)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.ceremonyBus = ceremonyBus;
	}

	private CreditSink.Modifier prestigeModifier;
	private CreditSink creditSink;

	public void start(CreditSink creditSink)
	{
		this.creditSink = creditSink;
		if (prestigeModifier == null)
		{
			prestigeModifier = context -> {
				GachaState state = stateService.get();
				return state == null ? 1.0
					: 1.0 + state.getPrestigeRank() * Tuning.PRESTIGE_GC_BONUS_PER_RANK;
			};
		}
		creditSink.registerModifier(prestigeModifier);
	}

	public void stop()
	{
		if (creditSink != null)
		{
			creditSink.unregisterModifier(prestigeModifier);
		}
	}

	public PrestigePlan plan()
	{
		GachaState state = stateService.get();
		if (state == null || !cardDatabase.isReady())
		{
			return new PrestigePlan(false, "State not loaded", 0, 0, 0);
		}
		boolean tasksMet = state.getTotalTasksCompleted() >= Tuning.PRESTIGE_TASKS_REQUIRED;
		long cheapCardsInDb = cardDatabase.all().values().stream()
			.filter(c -> !c.getRarity().atLeast(Rarity.RARE))
			.count();
		long cheapOwnedDistinct = state.getOwnedCards().stream()
			.filter(c -> !c.isHologram() && c.getVariant() == Variant.NORMAL)
			.map(OwnedCard::getCardId)
			.distinct()
			.filter(id -> {
				CardDefinition card = cardDatabase.card(id);
				return card != null && !card.getRarity().atLeast(Rarity.RARE);
			})
			.count();
		boolean collectionMet = cheapCardsInDb > 0
			&& cheapOwnedDistinct >= cheapCardsInDb * Tuning.PRESTIGE_COLLECTION_FRACTION;
		boolean eligible = (tasksMet || collectionMet)
			&& state.getGc() >= Tuning.PRESTIGE_GC_COST;

		int burnCount = (int) state.getOwnedCards().stream().filter(this::burnable).count();
		String req = "Requires " + QuantityFormatter.formatNumber(Tuning.PRESTIGE_TASKS_REQUIRED)
			+ " contracts (" + QuantityFormatter.formatNumber(state.getTotalTasksCompleted())
			+ ") or 90% C/U collection, plus "
			+ QuantityFormatter.formatNumber(Tuning.PRESTIGE_GC_COST) + " GC";
		return new PrestigePlan(eligible, req, burnCount, Tuning.PRESTIGE_GC_COST,
			state.getPrestigeRank() + 1);
	}

	private boolean burnable(OwnedCard card)
	{
		if (card.isHologram() || card.getVariant() != Variant.NORMAL)
		{
			return false;
		}
		CardDefinition def = cardDatabase.card(card.getCardId());
		return def != null && !def.getRarity().atLeast(Rarity.RARE);
	}

	/** Perform the rebirth. Returns the new rank, or -1 when not eligible. */
	public int prestige()
	{
		PrestigePlan plan = plan();
		if (!plan.isEligible())
		{
			return -1;
		}
		GachaState state = stateService.get();
		stateService.mutate(s -> {
			List<OwnedCard> kept = new ArrayList<>();
			List<String> burnedUuids = new ArrayList<>();
			for (OwnedCard card : s.getOwnedCards())
			{
				if (burnable(card))
				{
					burnedUuids.add(card.getUuid());
				}
				else
				{
					kept.add(card);
				}
			}
			// unassign burned cards from the loadout
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			loadout.values().removeIf(burnedUuids::contains);
			return s
				.withOwnedCards(kept)
				.withLoadout(loadout)
				.withGc(s.getGc() - Tuning.PRESTIGE_GC_COST)
				.withPrestigeRank(s.getPrestigeRank() + 1);
		});
		int newRank = state.getPrestigeRank() + 1;
		ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
			CeremonyBus.Fanfare.Size.LARGE, "REBIRTH — Prestige " + newRank,
			"+"+ (int) (newRank * Tuning.PRESTIGE_GC_BONUS_PER_RANK * 100) + "% GC forever", null));
		return newRank;
	}
}
