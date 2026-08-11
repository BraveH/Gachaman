package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.data.RangedMetal;
import com.gachaman.data.TierTable;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;

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

	/**
	 * The exact candidate list a roll of this rarity would draw from, plus whether the
	 * house lean applies to it. Shared by pickCardOfRarity and oddsFor so the
	 * disclosure can never drift from the roll.
	 */
	@Value
	public static class RollBucket
	{
		List<CardDefinition> cards;
		/** True when the proximity gate ran, which is exactly when the lean applies. */
		boolean leaned;
	}

	/** One disclosure row: a tier ladder in one reach band, with its real odds. */
	@Value
	public static class TierOdds
	{
		/** tierKey, or RollOdds.UNTIERED. */
		String tierKey;
		String displayName;
		boolean wieldableNow;
		/** Probability 0..1 for one ordinary card out of this chest. */
		double probability;
		/**
		 * The cards counted in this row, sorted and de-duplicated.
		 *
		 * <p>Carried because a tier ladder legitimately appears in BOTH bands — the
		 * Defence gate lands on the body only, and metal-prefixed ranged gear is
		 * measured against Ranged — so "Hardleather" showing up twice reads as a bug
		 * unless the panel can say which pieces are the ones still out of reach.
		 */
		List<String> cardNames;
	}

	/** Everything the Chest Odds panel prints, computed from the roll's own code. */
	@Value
	public static class OddsDisclosure
	{
		Tuning.Chest tier;
		/** Post-pity rarity split, percent, order C/U/R/E/L. */
		double[] rarityPercent;
		/** All non-zero rows, probability-descending. */
		List<TierOdds> rows;
		double wieldableTotal;
		double headroomTotal;
		double untieredTotal;
		double pityBonusPercent;
		int opensSinceEpic;
		int pityHardCap;
		boolean pityBreakNext;
	}

	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final CardDatabase cardDatabase;
	private final CeremonyBus ceremonyBus;
	private final GachaRng rng;
	private final com.google.gson.Gson gson;
	@Nullable
	private final Client client;
	@Nullable
	private final TierTable tierTable;

	/** Commit-time hooks (Firsts Journal stamps chest events through this). */
	public interface ChestListener
	{
		void onChestCommitted(ChestOpenResult result, long dupeGc);

		void onDeedClaimed(GearSlot slot);

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
		Client client, TierTable tierTable)
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
	public synchronized ChestOpenResult openSlotChest(GearSlot slot)
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

	/**
	 * Is the free First Colours chest due to be dealt right now?
	 *
	 * <p>Every term is a way the gift could go wrong. The owed flag on its own
	 * would re-deal the chest on every login until a reveal happened to close; an
	 * unfinished reveal — live in memory OR still serialized by a client that
	 * died mid-ceremony — has to be committed first, because writing this chest's
	 * blob over it would silently destroy one the player paid for; and rolling
	 * before the card database is ready would draw from an empty pool.
	 */
	static boolean firstColoursDue(@Nullable GachaState state, boolean revealPending, boolean dbReady)
	{
		return state != null
			&& state.isFirstColoursChestOwed()
			&& !revealPending
			&& state.getPendingChestBlob() == null
			&& dbReady;
	}

	/**
	 * The free chest that rides behind an account's very first style roll.
	 * Deliberately NOT gated on rustyAvailable(): the lifetime cap limits what
	 * the shop will sell, and withholding a gift because the player already
	 * bought their three would punish exactly the wrong account.
	 *
	 * <p>{@code preferredCardIds} steers the first card only, and only when the
	 * rarity band it lands in actually holds one of them — see constrained(). A
	 * preference, never a guarantee, so a sparse card database still deals.
	 *
	 * <p>The owed flag clears in the SAME mutate that persists the blob rather
	 * than via persistPending(): two writes leave a crash window that either
	 * re-gifts the chest or eats it, and the blob alone is enough for
	 * recoverPending() to finish the job.
	 */
	@Nullable
	public synchronized ChestOpenResult openFirstColoursChest(@Nullable Set<Integer> preferredCardIds)
	{
		GachaState state = stateService.get();
		if (!firstColoursDue(state, pending != null, cardDatabase.isReady()))
		{
			return null;
		}
		Predicate<CardDefinition> require =
			preferredCardIds == null || preferredCardIds.isEmpty()
				? null
				: c -> preferredCardIds.contains(c.getCardId());
		ChestOpenResult result = roll(Tuning.Chest.RUSTY, null, null, 0, require);
		pending = result;
		rerollUsedThisReveal = false;
		final String blob = gson.toJson(result);
		stateService.mutate(s -> s.withPendingChestBlob(blob).withFirstColoursChestOwed(false));
		ceremonyBus.submit(CeremonyBus.Type.CHEST_OPEN, result);
		log.debug("First Colours chest dealt ({} preferred cards)",
			preferredCardIds == null ? 0 : preferredCardIds.size());
		return result;
	}

	ChestOpenResult roll(Tuning.Chest tier, @Nullable String themedSetTag,
		@Nullable GearSlot targetSlot, long price)
	{
		return roll(tier, themedSetTag, targetSlot, price, null);
	}

	/**
	 * {@code require} steers the FIRST card only, and only as a preference. A
	 * chest that promised the player something usable must keep that promise in
	 * the card they see first, while the rest of the box stays honest gacha.
	 */
	ChestOpenResult roll(Tuning.Chest tier, @Nullable String themedSetTag,
		@Nullable GearSlot targetSlot, long price,
		@Nullable Predicate<CardDefinition> require)
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
			final Predicate<CardDefinition> steer = i == 0 ? require : null;
			if (i == 0 && pityBreak)
			{
				RolledSlot slot = rollSlot(pool, Rarity.LEGENDARY, hologramsAllowed,
					shinyChance, shinyAttempts, ownedKeys, steer);
				slots.add(slot.withPityLocked(true));
				continue;
			}
			Rarity rarity = rollRarity(Tuning.CHEST_ODDS.get(effective), pityBonus);
			slots.add(rollSlot(pool, rarity, hologramsAllowed,
				shinyChance, shinyAttempts, ownedKeys, steer));
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
		// shift pity bonus into EPIC+LEGENDARY mass, taken from COMMON. Shared with
		// the odds disclosure so the panel cannot quote a different pity curve than
		// the one the roll runs; the cumulative walk below stays here on purpose,
		// because pickHologram's near-identical walk falls back to the LAST element
		// while this one falls back to index 0 (EarlyGameMathTest.rustyRollsCommonOnly
		// depends on exactly that).
		double[] adjusted = RollOdds.adjustOdds(odds, pityBonusPercent);
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
		return rollSlot(pool, rarity, hologramsAllowed, shinyChance, shinyAttempts, ownedKeys, null);
	}

	RolledSlot rollSlot(List<CardDefinition> pool, Rarity rarity, boolean hologramsAllowed,
		double shinyChance, int shinyAttempts, Set<String> ownedKeys,
		@Nullable Predicate<CardDefinition> require)
	{
		// hologram replaces the card entirely
		if (hologramsAllowed && !cardDatabase.holograms().isEmpty() && rng.chance(Tuning.HOLOGRAM_CHANCE))
		{
			HologramDefinition holo = pickHologram();
			boolean dupe = ownedKeys.contains("H:" + holo.getTierKey());
			return new RolledSlot(holo.getRarity(), -1, holo.getTierKey(), Variant.HOLOGRAM, dupe, false, false);
		}
		CardDefinition card = pickCardOfRarity(pool, rarity, require);
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
		return pickCardOfRarity(pool, rarity, null);
	}

	/**
	 * {@code require} narrows the candidate list BEFORE the draw instead of
	 * rejecting after it, so a constrained pick still costs exactly one
	 * rng.pick. With require == null the candidate lists — and therefore every
	 * nextInt bound — are identical to the unconstrained build, so no existing
	 * seed moves.
	 */
	CardDefinition pickCardOfRarity(List<CardDefinition> pool, Rarity rarity,
		@Nullable Predicate<CardDefinition> require)
	{
		RollBucket bucket = bucketFor(pool, rarity, require);
		return bucket.isLeaned() ? pickLeaned(bucket.getCards()) : rng.pick(bucket.getCards());
	}

	RollBucket bucketFor(List<CardDefinition> pool, Rarity rarity)
	{
		return bucketFor(pool, rarity, null);
	}

	/**
	 * The exact candidate list a roll of this rarity would draw from — everything
	 * pickCardOfRarity used to do except the draw itself. Split out because it
	 * consumes NO RNG, which is what lets oddsFor() quote the roll's own numbers
	 * instead of a parallel transcription that would drift.
	 */
	RollBucket bucketFor(List<CardDefinition> pool, Rarity rarity,
		@Nullable Predicate<CardDefinition> require)
	{
		// Epic+ rolls may land anywhere; below that, stay near what the
		// player's levels can actually wield (see isReachable for the headroom).
		boolean proximityGated = !rarity.atLeast(Rarity.EPIC);
		for (int r = rarity.ordinal(); r >= 0; r--)
		{
			final Rarity target = Rarity.values()[r];
			List<CardDefinition> candidates = constrained(pool.stream()
				.filter(c -> c.getRarity() == target)
				.filter(c -> !proximityGated || isReachable(c))
				.collect(Collectors.toList()), require);
			if (!candidates.isEmpty())
			{
				return new RollBucket(candidates, proximityGated);
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
				return new RollBucket(candidates, false);
			}
		}
		return new RollBucket(pool, false);
	}

	/**
	 * The house lean: inside a proximity-gated bucket, gear the player can wield
	 * today outweighs gear that only got in through the headroom. Weighted rather
	 * than filtered so the headroom band stays reachable — it is deliberate
	 * aspirational slack, not an accident, and leanWeight() is asserted never to
	 * return zero.
	 *
	 * <p>When every candidate sits in the same band the weight vector is flat, and a
	 * flat weight vector IS a uniform draw, so this takes rng.pick() instead: same
	 * distribution, same single next() call. That branch is what keeps every
	 * fixed-seed test byte-identical, because isReachable is uniformly true when
	 * client is null — which is every headless test.
	 */
	private CardDefinition pickLeaned(List<CardDefinition> candidates)
	{
		double[] weights = new double[candidates.size()];
		double total = 0;
		int wieldable = 0;
		for (int i = 0; i < candidates.size(); i++)
		{
			boolean now = isReachable(candidates.get(i), false);
			if (now)
			{
				wieldable++;
			}
			// total is accumulated from the very values the walk below reads, so the
			// two can never disagree in the last bit and drop off the end
			weights[i] = RollOdds.leanWeight(now);
			total += weights[i];
		}
		if (wieldable == 0 || wieldable == candidates.size())
		{
			return rng.pick(candidates);
		}
		return candidates.get(RollOdds.weightedIndex(rng.nextDouble() * total, weights));
	}

	/**
	 * The constraint is a PREFERENCE, not a filter: narrowing to nothing hands
	 * back the full list, so a chest can never fail to deal a card. Returning the
	 * very same list instance on the null path is the point — every rng.pick
	 * bound must stay bit-identical to the unconstrained build.
	 */
	static List<CardDefinition> constrained(List<CardDefinition> candidates,
		@Nullable Predicate<CardDefinition> require)
	{
		if (require == null || candidates.isEmpty())
		{
			return candidates;
		}
		List<CardDefinition> narrowed = candidates.stream()
			.filter(require)
			.collect(Collectors.toList());
		return narrowed.isEmpty() ? candidates : narrowed;
	}

	/**
	 * The Rusty starter pool: only slots the player has unlocked, and tiered gear
	 * only up to what is wieldable today (no headroom). Untiered gear is not
	 * level-filtered at all — isReachable exempts it by design — so this is a
	 * clamp on the tier ladders, not a hard wieldability guarantee. Shared by
	 * roll() and rerollSlot() so the two can never drift.
	 */
	private List<CardDefinition> rustyPool(@Nullable GachaState state)
	{
		Set<String> deeded = state == null || state.getDeededSlots() == null
			? Set.of() : state.getDeededSlots();
		return cardDatabase.all().values().stream()
			.filter(c -> c.getSlot() != null && deeded.contains(c.getSlot().name()))
			.filter(c -> isReachable(c, false))
			.collect(Collectors.toList());
	}

	/** Is this card's tier within reach of the player's levels (+headroom)? */
	boolean isReachable(CardDefinition card)
	{
		return isReachable(card, true);
	}

	/**
	 * Headroom is a flag rather than a number because the two branches below measure
	 * it in different units — metal in tier ranks, dhide/robes in skill levels.
	 */
	boolean isReachable(CardDefinition card, boolean allowHeadroom)
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
		switch (ladder)
		{
			case "metal":
				RangedMetal ranged = RangedMetal.of(card.getName());
				if (ranged != null)
				{
					// Arrows, bolts, javelins, crossbows, darts, knives and thrownaxes wear a
					// metal prefix but are Ranged gear, and mostly not on the ladder's numbers
					// (a rune crossbow is 61, not 40). Measured in levels like dhide/robes, so
					// the level headroom; no ranged weapon or ammunition carries a Defence gate.
					return Tuning.withinReach(
						client.getRealSkillLevel(Skill.RANGED),
						client.getRealSkillLevel(Skill.DEFENCE),
						ranged.reqRangedLevel(card.getTierKey(),
							tierTable.reqLevelOf(card.getTierKey())),
						1,
						allowHeadroom ? Tuning.ROLL_LEVEL_HEADROOM : 0);
				}
				// Melee metal is left rank-wise on purpose: TIER_RANK_LEVELS transcribes the
				// metal ladder exactly, so this is already correct. The max() is load-bearing
				// too — metal weapons gate on Attack and metal armour on Defence, never both,
				// so a second Defence term here would lock an Attack pure out of rune weapons
				// it can wield today.
				int metalLevel = Math.max(client.getRealSkillLevel(Skill.ATTACK),
					client.getRealSkillLevel(Skill.DEFENCE));
				return card.getTierRank() <= Tuning.maxRankForLevel(metalLevel)
					+ (allowHeadroom ? Tuning.ROLL_TIER_HEADROOM : 0);
			case "dhide":
				return ladderWithinReach(card,
					client.getRealSkillLevel(Skill.RANGED), allowHeadroom);
			case "robes":
				return ladderWithinReach(card,
					client.getRealSkillLevel(Skill.MAGIC), allowHeadroom);
			default:
				return true;
		}
	}

	/**
	 * Dhide/robes ranks are power ordinals on their own ladder, not levels, so these
	 * two read explicit requirements out of tiers.json rather than borrowing metal's
	 * rank table (which understated them by 15-40 levels). Defence is applied to BODY
	 * only: the body is the piece that carries a Defence gate on every tier of both
	 * ladders, while d'hide chaps and vambraces carry none and would otherwise be
	 * over-gated for a low-Defence ranger.
	 */
	private boolean ladderWithinReach(CardDefinition card, int primaryLevel, boolean allowHeadroom)
	{
		int reqDefence = card.getSlot() == GearSlot.BODY
			? tierTable.reqDefenceOf(card.getTierKey())
			: 1;
		return Tuning.withinReach(primaryLevel,
			client.getRealSkillLevel(Skill.DEFENCE),
			tierTable.reqLevelOf(card.getTierKey()), reqDefence,
			allowHeadroom ? Tuning.ROLL_LEVEL_HEADROOM : 0);
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

	// --- Odds disclosure ---

	/**
	 * The true post-lean odds for ONE ORDINARY card out of this chest, derived from
	 * the same bucketFor()/adjustOdds()/leanWeight() the roll uses, so the panel
	 * cannot drift from reality.
	 *
	 * <p>Scoped to one ordinary card on purpose: the jackpot tier upgrade, the
	 * hologram that replaces a card outright and the pity hard-cap Legendary are
	 * NAMED in the panel rather than blended in, so every number shown is one a
	 * player can actually check against their own opens.
	 *
	 * <p>Reads live skill levels through isReachable — CLIENT THREAD ONLY.
	 * Deliberately NOT synchronized, unlike openChest/rerollSlot: both they and this
	 * run on the client thread and so cannot interleave, and this touches neither
	 * `pending` nor state, so the lock would buy nothing but contention with a reveal
	 * in flight.
	 */
	public OddsDisclosure oddsFor(Tuning.Chest tier)
	{
		GachaState state = stateService.get();
		// these five mirror roll() exactly — keep them adjacent so a reviewer can
		// diff the two blocks by eye
		boolean rusty = tier == Tuning.Chest.RUSTY;
		int prestige = state == null ? 0 : state.getPrestigeRank();
		int opensSinceEpic = state == null ? 0 : state.getOpensSinceEpic();
		int hardCap = prestige >= 2 ? Tuning.PITY_HARD_CAP_PRESTIGE2 : Tuning.PITY_HARD_CAP;
		double pityBonus = rusty
			? 0
			: Math.max(0, opensSinceEpic - Tuning.PITY_SOFT_START) * Tuning.PITY_BONUS_PER_OPEN;
		boolean pityBreakNext = !rusty && opensSinceEpic + 1 >= hardCap;

		List<CardDefinition> pool = rusty
			? rustyPool(state)
			: new ArrayList<>(cardDatabase.all().values());
		if (pool.isEmpty())
		{
			pool = new ArrayList<>(cardDatabase.all().values());
		}

		// one pass, not five: the rarity buckets walk down and revisit cards, and
		// cardId is the key of cardDatabase.all() so it is unique per definition
		Map<Integer, Boolean> wieldableByCardId = new HashMap<>();
		for (CardDefinition card : pool)
		{
			wieldableByCardId.put(card.getCardId(), isReachable(card, false));
		}

		double[] adjusted = RollOdds.adjustOdds(Tuning.CHEST_ODDS.get(tier), pityBonus);
		double[] rarityShare = RollOdds.normalize(adjusted);
		Map<RollOdds.TierBand, Double> totals = new LinkedHashMap<>();
		// names per row, for the tooltip that has to name the pieces a split tier
		// leaves out of reach. Sorted and de-duplicated: a card can be revisited
		// across rarity buckets, and an arbitrary order would reshuffle every rebuild.
		Map<RollOdds.TierBand, SortedSet<String>> namesByBand = new HashMap<>();
		for (Rarity rarity : Rarity.values())
		{
			double share = rarityShare[rarity.ordinal()];
			if (share <= 0)
			{
				continue;
			}
			RollBucket bucket = bucketFor(pool, rarity);
			List<CardDefinition> cards = bucket.getCards();
			boolean[] flags = new boolean[cards.size()];
			for (int i = 0; i < cards.size(); i++)
			{
				flags[i] = wieldableByCardId.getOrDefault(cards.get(i).getCardId(), true);
			}
			for (int i = 0; i < cards.size(); i++)
			{
				namesByBand
					.computeIfAbsent(RollOdds.bandOf(cards.get(i), flags[i]),
						k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
					.add(cards.get(i).getName());
			}
			for (Map.Entry<RollOdds.TierBand, Double> entry
				: RollOdds.tierShares(cards, flags, bucket.isLeaned()).entrySet())
			{
				totals.merge(entry.getKey(), entry.getValue() * share, Double::sum);
			}
		}

		List<TierOdds> rows = new ArrayList<>(totals.size());
		double wieldableTotal = 0;
		double headroomTotal = 0;
		double untieredTotal = 0;
		for (Map.Entry<RollOdds.TierBand, Double> entry : totals.entrySet())
		{
			double probability = entry.getValue();
			if (probability <= 0)
			{
				continue;
			}
			RollOdds.TierBand band = entry.getKey();
			boolean untiered = RollOdds.UNTIERED.equals(band.getTierKey());
			if (untiered)
			{
				untieredTotal += probability;
			}
			else if (band.isWieldableNow())
			{
				wieldableTotal += probability;
			}
			else
			{
				headroomTotal += probability;
			}
			String display = untiered || tierTable == null
				? band.getTierKey()
				: tierTable.displayNameOf(band.getTierKey());
			SortedSet<String> names = namesByBand.get(band);
			rows.add(new TierOdds(band.getTierKey(), display, band.isWieldableNow(), probability,
				names == null ? Collections.emptyList() : new ArrayList<>(names)));
		}
		rows.sort(Comparator.comparingDouble(TierOdds::getProbability).reversed());

		double[] rarityPercent = new double[rarityShare.length];
		for (int i = 0; i < rarityShare.length; i++)
		{
			rarityPercent[i] = rarityShare[i] * 100;
		}
		return new OddsDisclosure(tier, rarityPercent, rows, wieldableTotal, headroomTotal,
			untieredTotal, pityBonus, opensSinceEpic, hardCap, pityBreakNext);
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
			GearSlot slot = GearSlot.valueOf(pending.getTargetSlot());
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
				System.currentTimeMillis(), provenance, 0));
		}

		final long dupeGcFinal = dupeGc;
		GachaState beforeCommit = stateService.get();
		stateService.mutate(s -> {
			List<OwnedCard> owned = new ArrayList<>(s.getOwnedCards());
			owned.addAll(newCards);
			Map<String, Integer> byTier = new HashMap<>(s.getChestsOpenedByTier());
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
				boolean saturated = s.getDeededSlots().size() >= GearSlot.values().length;
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
			if (state != null && state.getDeededSlots().size() >= GearSlot.values().length)
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

	public boolean claimDeed(GearSlot slot)
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
