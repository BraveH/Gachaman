package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.With;
import lombok.extern.slf4j.Slf4j;

/**
 * Chest purchasing and roll math: rarity odds with pity, jackpot upgrades,
 * shiny/hologram variants, deed rolls, duplicate conversion. Outcomes are
 * rolled up-front and committed only when the reveal closes (deferred commit),
 * so in-reveal rerolls and aborted ceremonies are safe.
 */
@Slf4j
@Singleton
public class ChestService
{
	@Value
	@With
	public static class RolledSlot
	{
		Rarity rarity;
		int cardId;             // -1 for hologram slots
		@Nullable
		String hologramTier;    // non-null for hologram slots
		Variant variant;
		boolean duplicate;      // NORMAL dupe -> converts to GC at commit
		boolean pityLocked;     // the guaranteed pity slot; reroll-locked
		boolean nearMiss;       // shiny roll landed just outside the band -> stardust
	}

	@Value
	public static class ChestOpenResult
	{
		Tuning.Chest purchasedTier;
		Tuning.Chest effectiveTier;
		boolean jackpotUpgraded;
		boolean pityBreak;
		boolean deedGranted;
		@Nullable
		String themedSetTag;
		/** GearSlot name for slot-targeted chests, else null. */
		@Nullable
		String targetSlot;
		List<RolledSlot> slots;
		long pricePaid;
		/** Every card in this open rolled shiny twice (8 stardust consumed). */
		boolean stardustBlessed;
	}

	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final CardDatabase cardDatabase;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;
	private final com.google.gson.Gson gson;
	@Nullable
	private final net.runelite.api.Client client;
	@Nullable
	private final com.gachaman.data.TierTable tierTable;

	/** Commit-time hooks (Firsts Journal stamps chest events through this). */
	public interface ChestListener
	{
		void onChestCommitted(ChestOpenResult result, long dupeGc);

		void onDeedClaimed(com.gachaman.model.GearSlot slot);

		void onRerollSpent();
	}

	private final List<ChestListener> chestListeners = new ArrayList<>();

	/** The single pending (uncommitted) open, if any. */
	@Nullable
	private ChestOpenResult pending;
	private boolean rerollUsedThisReveal;

	public void addChestListener(ChestListener listener)
	{
		if (!chestListeners.contains(listener))
		{
			chestListeners.add(listener);
		}
	}

	public void removeChestListener(ChestListener listener)
	{
		chestListeners.remove(listener);
	}

	@Inject
	public ChestService(GachaStateService stateService, CreditSink creditSink,
		CardDatabase cardDatabase, CeremonyBus ceremonyBus, GachaRng rng, com.google.gson.Gson gson,
		net.runelite.api.Client client, com.gachaman.data.TierTable tierTable)
	{
		this.stateService = stateService;
		this.creditSink = creditSink;
		this.cardDatabase = cardDatabase;
		this.ceremonyBus = ceremonyBus;
		this.rng = rng;
		this.gson = gson;
		this.client = client;
		this.tierTable = tierTable;
	}

	/**
	 * Crash recovery: a client that died mid-reveal left the paid-for outcome
	 * serialized in state. Auto-commit it on the next load so the purchase is
	 * never lost.
	 */
	public synchronized void recoverPending()
	{
		var state = stateService.get();
		if (pending != null || state == null || state.getPendingChestBlob() == null)
		{
			return;
		}
		try
		{
			pending = gson.fromJson(state.getPendingChestBlob(), ChestOpenResult.class);
		}
		catch (Exception e)
		{
			log.warn("Failed to recover pending chest; discarding", e);
			stateService.mutate(s -> s.withPendingChestBlob(null));
			return;
		}
		if (pending != null)
		{
			log.info("Recovering interrupted chest open ({})", pending.getEffectiveTier());
			commitPending();
		}
	}

	private void persistPending()
	{
		String blob = pending == null ? null : gson.toJson(pending);
		stateService.mutate(s -> s.withPendingChestBlob(blob));
	}

	// --- Opening ---

	public boolean canAfford(Tuning.Chest tier)
	{
		GachaState state = stateService.get();
		return state != null && state.getGc() >= Tuning.CHEST_PRICE_GC.get(tier);
	}

	/** Lifetime Rusty opens so far (the starter tier retires after the cap). */
	public int rustyChestsOpened()
	{
		GachaState state = stateService.get();
		if (state == null || state.getChestsOpenedByTier() == null)
		{
			return 0;
		}
		return state.getChestsOpenedByTier().getOrDefault(Tuning.Chest.RUSTY.name(), 0);
	}

	public boolean rustyAvailable()
	{
		return rustyChestsOpened() < Tuning.RUSTY_LIFETIME_CAP;
	}

	/** Buy and roll a chest; queues the ceremony. Null when unaffordable/busy/DB not ready. */
	@Nullable
	public synchronized ChestOpenResult openChest(Tuning.Chest tier)
	{
		if (pending != null || !cardDatabase.isReady())
		{
			return null;
		}
		if (tier == Tuning.Chest.RUSTY && !rustyAvailable())
		{
			return null;
		}
		long price = Tuning.CHEST_PRICE_GC.get(tier);
		if (!creditSink.spend(price))
		{
			return null;
		}
		ChestOpenResult result = roll(tier, null, null, price);
		pending = result;
		rerollUsedThisReveal = false;
		persistPending();
		ceremonyBus.submit(CeremonyBus.Type.CHEST_OPEN, result);
		return result;
	}

	/**
	 * Slot-targeted chest: Gilded price, ONE card, rolled only from the chosen
	 * gear slot's pool (Gilded odds; pity applies; no jackpot upgrade).
	 */
	@Nullable
	public synchronized ChestOpenResult openSlotChest(com.gachaman.model.GearSlot slot)
	{
		if (pending != null || !cardDatabase.isReady() || slot == null)
		{
			return null;
		}
		long price = Tuning.CHEST_PRICE_GC.get(Tuning.Chest.GILDED);
		if (!creditSink.spend(price))
		{
			return null;
		}
		ChestOpenResult result = roll(Tuning.Chest.GILDED, null, slot, price);
		pending = result;
		rerollUsedThisReveal = false;
		persistPending();
		ceremonyBus.submit(CeremonyBus.Type.CHEST_OPEN, result);
		return result;
	}

	/** Open a queued boss-themed chest (free). */
	@Nullable
	public synchronized ChestOpenResult openThemedChest(String setTag)
	{
		GachaState state = stateService.get();
		if (pending != null || state == null || !cardDatabase.isReady()
			|| !state.getQueuedThemedChests().contains(setTag))
		{
			return null;
		}
		stateService.mutate(s -> {
			List<String> queued = new ArrayList<>(s.getQueuedThemedChests());
			queued.remove(setTag);
			return s.withQueuedThemedChests(queued);
		});
		ChestOpenResult result = roll(Tuning.Chest.GILDED, setTag, null, 0);
		pending = result;
		rerollUsedThisReveal = false;
		persistPending();
		ceremonyBus.submit(CeremonyBus.Type.THEMED_CHEST, result);
		return result;
	}

	ChestOpenResult roll(Tuning.Chest tier, @Nullable String themedSetTag,
		@Nullable com.gachaman.model.GearSlot targetSlot, long price)
	{
		GachaState state = stateService.get();
		int prestige = state == null ? 0 : state.getPrestigeRank();
		int opensSinceEpic = state == null ? 0 : state.getOpensSinceEpic();
		boolean rusty = tier == Tuning.Chest.RUSTY;

		// stardust blessing: consumed by the next chest that can actually roll
		// shiny — themed chests roll no variants, so they pass the blessing
		// through untouched. The flag clears at roll time (persisted
		// immediately) so a crash cannot re-arm.
		boolean blessed = themedSetTag == null && state != null && state.isStardustBlessArmed();
		if (blessed)
		{
			stateService.mutate(s -> s.withStardustBlessArmed(false));
		}

		// jackpot upgrade (regular untargeted chests only; the starter tier
		// never upgrades — it must stay the humblest box in the shop)
		Tuning.Chest effective = tier;
		boolean jackpot = false;
		if (themedSetTag == null && targetSlot == null && !rusty)
		{
			double jackpotChance = prestige >= 3 ? Tuning.JACKPOT_CHANCE_PRESTIGE3 : Tuning.JACKPOT_CHANCE;
			if (rng.chance(jackpotChance))
			{
				jackpot = true;
				if (tier == Tuning.Chest.BATTERED)
				{
					effective = Tuning.Chest.GILDED;
				}
				else if (tier == Tuning.Chest.GILDED)
				{
					effective = Tuning.Chest.ORNATE;
				}
			}
		}
		int cardCount = targetSlot != null ? 1 : Tuning.CHEST_CARDS.get(effective);
		if (jackpot && tier == Tuning.Chest.ORNATE)
		{
			cardCount++; // ornate jackpot: 4th card
		}

		// pity (themed chests are free rewards and sit outside pity; Rusty can
		// never pay Epic+ so it neither benefits from nor advances pity)
		boolean pityEligible = themedSetTag == null && !rusty;
		int hardCap = prestige >= 2 ? Tuning.PITY_HARD_CAP_PRESTIGE2 : Tuning.PITY_HARD_CAP;
		boolean pityBreak = pityEligible && opensSinceEpic + 1 >= hardCap;
		double pityBonus = pityEligible
			? Math.max(0, opensSinceEpic - Tuning.PITY_SOFT_START) * Tuning.PITY_BONUS_PER_OPEN
			: 0;

		List<CardDefinition> pool;
		if (targetSlot != null)
		{
			pool = cardDatabase.all().values().stream()
				.filter(c -> c.getSlot() == targetSlot)
				.collect(Collectors.toList());
		}
		else if (themedSetTag != null)
		{
			pool = cardDatabase.setMembers(themedSetTag);
		}
		else if (rusty)
		{
			pool = rustyPool(state);
		}
		else
		{
			pool = new ArrayList<>(cardDatabase.all().values());
		}
		if (pool.isEmpty())
		{
			// crash-guard only — a themed tag landing here means bosses.json
			// references a set that sets.json does not define (integrity-tested)
			log.warn("empty chest pool (themedSetTag={}, targetSlot={}) — falling back to all cards",
				themedSetTag, targetSlot);
			pool = new ArrayList<>(cardDatabase.all().values());
		}

		// themed chests roll no variants at all (unchanged); Rusty rolls no
		// holograms (too grand for the starter box) but shiny at a juiced rate
		boolean hologramsAllowed = themedSetTag == null && !rusty;
		double shinyChance = themedSetTag != null ? 0
			: (rusty ? Tuning.RUSTY_SHINY_CHANCE : Tuning.SHINY_CHANCE);
		int shinyAttempts = blessed ? 2 : 1;
		Set<String> ownedKeys = ownedKeys(state);
		List<RolledSlot> slots = new ArrayList<>(cardCount);
		for (int i = 0; i < cardCount; i++)
		{
			if (i == 0 && pityBreak)
			{
				RolledSlot slot = rollSlot(pool, Rarity.LEGENDARY, hologramsAllowed,
					shinyChance, shinyAttempts, ownedKeys);
				slots.add(slot.withPityLocked(true));
				continue;
			}
			Rarity rarity = rollRarity(Tuning.CHEST_ODDS.get(effective), pityBonus);
			slots.add(rollSlot(pool, rarity, hologramsAllowed,
				shinyChance, shinyAttempts, ownedKeys));
		}

		boolean deed = false;
		if (themedSetTag == null && rng.chance(Tuning.DEED_CHANCE.getOrDefault(tier, 0.0)))
		{
			deed = true;
		}

		return new ChestOpenResult(tier, effective, jackpot, pityBreak, deed, themedSetTag,
			targetSlot == null ? null : targetSlot.name(), slots, price, blessed);
	}

	Rarity rollRarity(double[] odds, double pityBonusPercent)
	{
		// shift pity bonus into EPIC+LEGENDARY mass, taken from COMMON
		double[] adjusted = odds.clone();
		if (pityBonusPercent > 0)
		{
			double shift = Math.min(adjusted[0] - 1, pityBonusPercent);
			adjusted[0] -= shift;
			adjusted[3] += shift * 0.7;
			adjusted[4] += shift * 0.3;
		}
		double total = 0;
		for (double odd : adjusted)
		{
			total += odd;
		}
		double roll = rng.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < adjusted.length; i++)
		{
			cumulative += adjusted[i];
			if (roll < cumulative)
			{
				return Rarity.values()[i];
			}
		}
		return Rarity.COMMON;
	}

	RolledSlot rollSlot(List<CardDefinition> pool, Rarity rarity, boolean hologramsAllowed,
		double shinyChance, int shinyAttempts, Set<String> ownedKeys)
	{
		// hologram replaces the card entirely
		if (hologramsAllowed && !cardDatabase.holograms().isEmpty() && rng.chance(Tuning.HOLOGRAM_CHANCE))
		{
			HologramDefinition holo = pickHologram();
			boolean dupe = ownedKeys.contains("H:" + holo.getTierKey());
			return new RolledSlot(holo.getRarity(), -1, holo.getTierKey(), Variant.HOLOGRAM, dupe, false, false);
		}
		CardDefinition card = pickCardOfRarity(pool, rarity);
		Variant variant = Variant.NORMAL;
		boolean nearMiss = false;
		if (shinyChance > 0 && card.isShinyEligible())
		{
			// the raw roll is captured (not chance()) so the near-miss band is
			// observable; draw count is unchanged except for blessed retries
			double band = shinyChance * Tuning.STARDUST_NEAR_MISS_MULT;
			for (int attempt = 0; attempt < Math.max(1, shinyAttempts); attempt++)
			{
				double r = rng.nextDouble();
				if (r < shinyChance)
				{
					variant = Variant.SHINY;
					nearMiss = false;
					break;
				}
				if (r < band)
				{
					nearMiss = true;
				}
			}
		}
		boolean duplicate = variant == Variant.NORMAL && ownedKeys.contains("C:" + card.getCardId());
		return new RolledSlot(card.getRarity(), card.getCardId(), null, variant, duplicate, false, nearMiss);
	}

	CardDefinition pickCardOfRarity(List<CardDefinition> pool, Rarity rarity)
	{
		// Epic+ rolls may land anywhere; below that, stay near what the
		// player's levels can actually wield (rank headroom of 2).
		boolean proximityGated = !rarity.atLeast(Rarity.EPIC);
		for (int r = rarity.ordinal(); r >= 0; r--)
		{
			final Rarity target = Rarity.values()[r];
			List<CardDefinition> candidates = pool.stream()
				.filter(c -> c.getRarity() == target)
				.filter(c -> !proximityGated || isReachable(c))
				.collect(Collectors.toList());
			if (!candidates.isEmpty())
			{
				return rng.pick(candidates);
			}
		}
		// gate excluded everything of every rarity — fall back unfiltered
		for (int r = rarity.ordinal(); r >= 0; r--)
		{
			final Rarity target = Rarity.values()[r];
			List<CardDefinition> candidates = pool.stream()
				.filter(c -> c.getRarity() == target)
				.collect(Collectors.toList());
			if (!candidates.isEmpty())
			{
				return rng.pick(candidates);
			}
		}
		return rng.pick(pool);
	}

	/**
	 * The Rusty starter pool: only slots the player has unlocked, only gear
	 * strictly wieldable today (no headroom). Shared by roll() and rerollSlot()
	 * so the two can never drift.
	 */
	private List<CardDefinition> rustyPool(@Nullable GachaState state)
	{
		Set<String> deeded = state == null || state.getDeededSlots() == null
			? Set.of() : state.getDeededSlots();
		return cardDatabase.all().values().stream()
			.filter(c -> c.getSlot() != null && deeded.contains(c.getSlot().name()))
			.filter(c -> isReachable(c, 0))
			.collect(Collectors.toList());
	}

	/** Is this card's tier within reach of the player's levels (+headroom)? */
	boolean isReachable(CardDefinition card)
	{
		return isReachable(card, Tuning.ROLL_TIER_HEADROOM);
	}

	boolean isReachable(CardDefinition card, int headroom)
	{
		if (card.getTierKey() == null || client == null || tierTable == null)
		{
			return true; // untiered gear (or headless tests) is never proximity-gated
		}
		String ladder = tierTable.ladderOf(card.getTierKey());
		if (ladder == null)
		{
			return true;
		}
		int level;
		switch (ladder)
		{
			case "metal":
				level = Math.max(client.getRealSkillLevel(net.runelite.api.Skill.ATTACK),
					client.getRealSkillLevel(net.runelite.api.Skill.DEFENCE));
				break;
			case "dhide":
				level = client.getRealSkillLevel(net.runelite.api.Skill.RANGED);
				break;
			case "robes":
				level = client.getRealSkillLevel(net.runelite.api.Skill.MAGIC);
				break;
			default:
				return true;
		}
		return card.getTierRank() <= Tuning.maxRankForLevel(level) + headroom;
	}

	HologramDefinition pickHologram()
	{
		List<HologramDefinition> holos = new ArrayList<>(cardDatabase.holograms().values());
		int maxRank = 1;
		for (HologramDefinition holo : holos)
		{
			maxRank = Math.max(maxRank, rankOf(holo));
		}
		List<Double> weights = new ArrayList<>(holos.size());
		double total = 0;
		for (HologramDefinition holo : holos)
		{
			double weight = Math.pow(maxRank - rankOf(holo) + 1, 2);
			weights.add(weight);
			total += weight;
		}
		double roll = rng.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < holos.size(); i++)
		{
			cumulative += weights.get(i);
			if (roll < cumulative)
			{
				return holos.get(i);
			}
		}
		return holos.get(holos.size() - 1);
	}

	private int rankOf(HologramDefinition holo)
	{
		return holo.getRarity().ordinal() * 2 + 1; // proxy: rarity encodes rank band
	}

	// --- In-reveal reroll ---

	public synchronized boolean canReroll(int slotIndex)
	{
		GachaState state = stateService.get();
		return pending != null && !rerollUsedThisReveal
			&& state != null && state.getRerollTokens() > 0
			&& slotIndex >= 0 && slotIndex < pending.getSlots().size()
			&& !pending.getSlots().get(slotIndex).isPityLocked();
	}

	/** Spend a reroll token to re-flip one slot; returns the new slot or null. */
	@Nullable
	public synchronized RolledSlot rerollSlot(int slotIndex)
	{
		if (!canReroll(slotIndex))
		{
			return null;
		}
		GachaState state = stateService.get();
		stateService.mutate(s -> s.withRerollTokens(s.getRerollTokens() - 1));
		rerollUsedThisReveal = true;

		boolean rusty = pending.getPurchasedTier() == Tuning.Chest.RUSTY;
		List<CardDefinition> pool;
		if (pending.getTargetSlot() != null)
		{
			com.gachaman.model.GearSlot slot = com.gachaman.model.GearSlot.valueOf(pending.getTargetSlot());
			pool = cardDatabase.all().values().stream()
				.filter(c -> c.getSlot() == slot)
				.collect(Collectors.toList());
		}
		else if (pending.getThemedSetTag() != null)
		{
			pool = cardDatabase.setMembers(pending.getThemedSetTag());
		}
		else if (rusty)
		{
			pool = rustyPool(state);
			if (pool.isEmpty())
			{
				pool = new ArrayList<>(cardDatabase.all().values());
			}
		}
		else
		{
			pool = new ArrayList<>(cardDatabase.all().values());
		}
		boolean hologramsAllowed = pending.getThemedSetTag() == null && !rusty;
		double shinyChance = pending.getThemedSetTag() != null ? 0
			: (rusty ? Tuning.RUSTY_SHINY_CHANCE : Tuning.SHINY_CHANCE);
		int shinyAttempts = pending.isStardustBlessed() ? 2 : 1;
		Rarity rarity = rollRarity(Tuning.CHEST_ODDS.get(pending.getEffectiveTier()), 0);
		RolledSlot fresh = rollSlot(pool, rarity, hologramsAllowed, shinyChance, shinyAttempts,
			ownedKeys(state));

		List<RolledSlot> slots = new ArrayList<>(pending.getSlots());
		slots.set(slotIndex, fresh);
		pending = new ChestOpenResult(pending.getPurchasedTier(), pending.getEffectiveTier(),
			pending.isJackpotUpgraded(), pending.isPityBreak(), pending.isDeedGranted(),
			pending.getThemedSetTag(), pending.getTargetSlot(), slots, pending.getPricePaid(),
			pending.isStardustBlessed());
		persistPending();
		for (ChestListener listener : new ArrayList<>(chestListeners))
		{
			try
			{
				listener.onRerollSpent();
			}
			catch (Exception e)
			{
				log.warn("chest listener failed", e);
			}
		}
		return fresh;
	}

	// --- Commit (reveal closed or aborted) ---

	@Nullable
	public ChestOpenResult getPending()
	{
		return pending;
	}

	/** Apply the pending open to state. Returns GC gained from duplicates. */
	public synchronized long commitPending()
	{
		if (pending == null)
		{
			return 0;
		}
		ChestOpenResult result = pending;
		pending = null;

		long dupeGc = 0;
		List<OwnedCard> newCards = new ArrayList<>();
		for (RolledSlot slot : result.getSlots())
		{
			if (slot.isDuplicate())
			{
				dupeGc += Tuning.DUPLICATE_GC.get(slot.getRarity());
				continue;
			}
			String provenance = (result.getThemedSetTag() == null ? "chest:" : "kc-chest:")
				+ result.getEffectiveTier();
			newCards.add(new OwnedCard(UUID.randomUUID().toString(),
				slot.getCardId(), slot.getHologramTier(), slot.getVariant(),
				System.currentTimeMillis(), provenance));
		}

		final long dupeGcFinal = dupeGc;
		GachaState beforeCommit = stateService.get();
		stateService.mutate(s -> {
			List<OwnedCard> owned = new ArrayList<>(s.getOwnedCards());
			owned.addAll(newCards);
			java.util.Map<String, Integer> byTier = new java.util.HashMap<>(s.getChestsOpenedByTier());
			byTier.merge(result.getPurchasedTier().name(), 1, Integer::sum);
			GachaState next = s.withOwnedCards(owned).withChestsOpenedByTier(byTier)
				.withPendingChestBlob(null);
			if (result.getThemedSetTag() == null
				&& result.getPurchasedTier() != Tuning.Chest.RUSTY)
			{
				// pity counts CARDS since the last Epic+ card, in reveal order
				int counter = s.getOpensSinceEpic();
				for (RolledSlot slot : result.getSlots())
				{
					counter = slot.getRarity().atLeast(Rarity.EPIC) ? 0 : counter + 1;
				}
				next = next.withOpensSinceEpic(counter);
			}
			// stardust: near-misses bank at commit (rerolls replaced their slot,
			// so this can never double-count); 8 banked arms the next chest
			int nearMisses = 0;
			for (RolledSlot slot : result.getSlots())
			{
				if (slot.isNearMiss())
				{
					nearMisses++;
				}
			}
			if (nearMisses > 0)
			{
				int dust = s.getStardust() + nearMisses;
				boolean armed = s.isStardustBlessArmed();
				if (!armed && dust >= Tuning.STARDUST_REQUIRED)
				{
					dust -= Tuning.STARDUST_REQUIRED;
					armed = true;
				}
				next = next.withStardust(dust).withStardustBlessArmed(armed);
			}
			if (result.isDeedGranted())
			{
				boolean saturated = s.getDeededSlots().size() >= com.gachaman.model.GearSlot.values().length;
				if (!saturated)
				{
					next = next.withPendingDeeds(s.getPendingDeeds() + 1);
				}
			}
			return next;
		});

		GachaState afterCommit = stateService.get();
		if (afterCommit != null && afterCommit.isStardustBlessArmed()
			&& (beforeCommit == null || !beforeCommit.isStardustBlessArmed()))
		{
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.SMALL, "Stardust blessing!",
				Tuning.STARDUST_REQUIRED + " stardust consumed — your next chest rolls shiny twice per card.",
				null));
		}

		if (dupeGc > 0)
		{
			creditSink.award(dupeGcFinal, new CreditSink.GcContext(CreditSink.Source.DUPLICATE, null, null));
		}
		if (result.isDeedGranted())
		{
			GachaState state = stateService.get();
			if (state != null && state.getDeededSlots().size() >= com.gachaman.model.GearSlot.values().length)
			{
				creditSink.award(Tuning.DEED_SATURATED_GC,
					new CreditSink.GcContext(CreditSink.Source.DEED_SATURATED, null, null));
			}
			else
			{
				ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
			}
		}
		for (ChestListener listener : new ArrayList<>(chestListeners))
		{
			try
			{
				listener.onChestCommitted(result, dupeGc);
			}
			catch (Exception e)
			{
				log.warn("chest listener failed", e);
			}
		}
		return dupeGc;
	}

	// --- Deed choice ---

	public boolean claimDeed(com.gachaman.model.GearSlot slot)
	{
		GachaState state = stateService.get();
		if (state == null || state.getPendingDeeds() <= 0
			|| state.getDeededSlots().contains(slot.name()))
		{
			return false;
		}
		stateService.mutate(s -> {
			Set<String> deeded = new HashSet<>(s.getDeededSlots());
			deeded.add(slot.name());
			return s.withDeededSlots(deeded).withPendingDeeds(s.getPendingDeeds() - 1);
		});
		ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
			CeremonyBus.Fanfare.Size.LARGE, "Slot Deed: " + slot.getDisplayName(),
			"The " + slot.getDisplayName() + " slot is now unlocked!", null));
		for (ChestListener listener : new ArrayList<>(chestListeners))
		{
			try
			{
				listener.onDeedClaimed(slot);
			}
			catch (Exception e)
			{
				log.warn("chest listener failed", e);
			}
		}
		return true;
	}

	/** Milestone deed from task completion (already counted in TaskService). */
	public void grantMilestoneDeed()
	{
		stateService.mutate(s -> s.withPendingDeeds(s.getPendingDeeds() + 1));
	}

	// --- Helpers ---

	static Set<String> ownedKeys(@Nullable GachaState state)
	{
		Set<String> keys = new HashSet<>();
		if (state == null)
		{
			return keys;
		}
		for (OwnedCard card : state.getOwnedCards())
		{
			if (card.isHologram())
			{
				keys.add("H:" + card.getTierKey());
			}
			else if (card.getVariant() == Variant.NORMAL)
			{
				keys.add("C:" + card.getCardId());
			}
		}
		return keys;
	}
}
