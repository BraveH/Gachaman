package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * Chest purchasing and roll math: rarity odds with pity, jackpot upgrades,
 * shiny/hologram variants, deed rolls, duplicate conversion. Outcomes are
 * rolled up-front and committed only when the reveal closes (deferred commit),
 * so in-reveal rerolls and aborted ceremonies are safe.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ChestService {
	@Value
	@With
	public static class RolledSlot {
		Rarity rarity;
		int cardId;             // -1 for hologram slots
		@Nullable
		String hologramTier;    // non-null for hologram slots
		Variant variant;
		boolean duplicate;      // NORMAL dupe -> converts to GC at commit
		boolean pityLocked;     // the guaranteed pity slot; reroll-locked
		boolean nearMiss;       // shiny roll landed just outside the band -> stardust
	}

	/**
	 * {@code @With} is here for rerollSlot, which changes exactly one field
	 * (the slot list) and would otherwise have to hand-copy all ten — a list that
	 * silently rots the next time a field is added. The generated withSlots()
	 * expands to the very constructor call it replaces, and Gson binds by field,
	 * so the persisted blob shape is untouched.
	 */
	@Value
	@With
	public static class ChestOpenResult {
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
	public static class RollBucket {
		List<CardDefinition> cards;
		/** True when the proximity gate ran, which is exactly when the lean applies. */
		boolean leaned;
	}

	/** One disclosure row: a tier ladder in one reach band, with its real odds. */
	@Value
	public static class TierOdds {
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
	public static class OddsDisclosure {
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
	private final RangedMetal.Lookup rangedMetal;
	@Nullable
	private final Client client;
	@Nullable
	private final TierTable tierTable;

	/** Commit-time hooks (Firsts Journal stamps chest events through this). */
	public interface ChestListener {
		void onChestCommitted(ChestOpenResult result, long dupeGc);

		void onDeedClaimed(GearSlot slot);

		void onRerollSpent();
	}

	private final List<ChestListener> chestListeners = new ArrayList<>();

	/**
	 * The single pending (uncommitted) open, if any.
	 *
	 * <p>Volatile because the writers and some of the readers are different
	 * threads. Every write happens under this object's monitor — recoverPending,
	 * openFirstColoursChest, rerollSlot and commitPending are all synchronized, and
	 * the one remaining write lives in deal(), which is deliberately NOT
	 * synchronized because its only callers (openChest, openSlotChest,
	 * openThemedChest) are, and already hold this monitor when they call it. Grep
	 * for `pending =` and you will land in deal() rather than in the openers; that
	 * is the reason, not a lock that went missing. The Lombok getter takes
	 * no lock, and the Swing EDT reads through it (ShopTab:205 and :652) while the
	 * client thread is the one doing the writing. With no lock and no volatile
	 * there is no happens-before edge to the EDT at all, so it is free to keep
	 * observing a stale reference indefinitely: a shop tile stuck on "a reveal is
	 * in progress" after the reveal has already closed, or an Open button that
	 * stays disabled. Publishing the reference safely is the whole fix — the
	 * object behind it is a deeply immutable Lombok value type, so a reader that
	 * sees the reference necessarily sees a fully built result.
	 *
	 * <p>Deliberately volatile rather than a synchronized getter: the getter would
	 * put the EDT behind the same monitor commitPending() holds while it rewrites
	 * state, awards GC and fans out to every listener, which is a UI stall bought
	 * for nothing. Every reader does a single read into a local and never a
	 * compound check, so a plain volatile read is all the atomicity they need.
	 *
	 * <p>rerollUsedThisReveal below needs no such treatment: its only reader is
	 * canReroll(), which is itself synchronized, so every read of it already
	 * takes the lock that its writes are made under.
	 */
	@Nullable
	@Getter
	private volatile ChestOpenResult pending;
	private boolean rerollUsedThisReveal;

	public void addChestListener(ChestListener listener) {
		if (!chestListeners.contains(listener)) {
			chestListeners.add(listener);
		}
	}

	public void removeChestListener(ChestListener listener) {
		chestListeners.remove(listener);
	}

	/**
	 * Crash recovery: a client that died mid-reveal left the paid-for outcome
	 * serialized in state. Auto-commit it on the next load so the purchase is
	 * never lost.
	 */
	public synchronized void recoverPending() {
		var state = stateService.get();
		if (pending != null || state == null || state.getPendingChestBlob() == null) {
			return;
		}
		try {
			pending = gson.fromJson(state.getPendingChestBlob(), ChestOpenResult.class);
		}
		catch (Exception e) {
			log.warn("Failed to recover pending chest; discarding", e);
			stateService.mutate(s -> s.withPendingChestBlob(null));
			return;
		}
		if (pending != null) {
			log.info("Recovering interrupted chest open ({})", pending.getEffectiveTier());
			commitPending();
		}
	}

	private void persistPending() {
		String blob = pending == null ? null : gson.toJson(pending);
		stateService.mutate(s -> s.withPendingChestBlob(blob));
	}

	// --- Opening ---

	public boolean canAfford(Tuning.Chest tier) {
		GachaState state = stateService.get();
		return state != null && state.getGc() >= Tuning.CHEST_PRICE_GC.get(tier);
	}

	/** Lifetime Rusty opens so far (the starter tier retires after the cap). */
	public int rustyChestsOpened() {
		GachaState state = stateService.get();
		if (state == null || state.getChestsOpenedByTier() == null) {
			return 0;
		}
		return state.getChestsOpenedByTier().getOrDefault(Tuning.Chest.RUSTY.name(), 0);
	}

	public boolean rustyAvailable() {
		return rustyChestsOpened() < Tuning.RUSTY_LIFETIME_CAP;
	}

	/** Buy and roll a chest; queues the ceremony. Null when unaffordable/busy/DB not ready. */
	@Nullable
	public synchronized ChestOpenResult openChest(Tuning.Chest tier) {
		if (pending != null || !cardDatabase.isReady()) {
			return null;
		}
		if (tier == Tuning.Chest.RUSTY && !rustyAvailable()) {
			return null;
		}
		long price = Tuning.CHEST_PRICE_GC.get(tier);
		if (!creditSink.spend(price)) {
			return null;
		}
		return deal(roll(tier, null, null, price, null), CeremonyBus.Type.CHEST_OPEN);
	}

	/**
	 * The tail every PURCHASED open shares: publish the outcome, arm a fresh
	 * reroll, get the blob on disk, and only then hand the result to the ceremony
	 * queue. That order is the crash contract — a client that dies during the
	 * ceremony must find the paid-for outcome in state, so persistPending() has to
	 * run before the reveal can possibly start.
	 *
	 * <p>Called only from synchronized openers, so it inherits their monitor (see
	 * the note on `pending`). openFirstColoursChest deliberately does NOT route
	 * through here: it folds the blob write and the owed-flag clear into ONE
	 * mutate, for the crash-window reason its own javadoc gives.
	 */
	private ChestOpenResult deal(ChestOpenResult result, CeremonyBus.Type type) {
		pending = result;
		rerollUsedThisReveal = false;
		persistPending();
		ceremonyBus.submit(type, result);
		return result;
	}

	/**
	 * Slot-targeted chest: Gilded price, ONE card, rolled only from the chosen
	 * gear slot's pool (Gilded odds; pity applies; no jackpot upgrade).
	 */
	@Nullable
	public synchronized ChestOpenResult openSlotChest(GearSlot slot) {
		if (pending != null || !cardDatabase.isReady() || slot == null) {
			return null;
		}
		long price = Tuning.CHEST_PRICE_GC.get(Tuning.Chest.GILDED);
		if (!creditSink.spend(price)) {
			return null;
		}
		return deal(roll(Tuning.Chest.GILDED, null, slot, price, null),
			CeremonyBus.Type.CHEST_OPEN);
	}

	/** Open a queued boss-themed chest (free). */
	@Nullable
	public synchronized ChestOpenResult openThemedChest(String setTag) {
		GachaState state = stateService.get();
		if (pending != null || state == null || !cardDatabase.isReady()
			|| !state.getQueuedThemedChests().contains(setTag)) {
			return null;
		}
		stateService.mutate(s -> {
			List<String> queued = new ArrayList<>(s.getQueuedThemedChests());
			queued.remove(setTag);
			return s.withQueuedThemedChests(queued);
		});
		return deal(roll(Tuning.Chest.GILDED, setTag, null, 0, null),
			CeremonyBus.Type.THEMED_CHEST);
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
	static boolean firstColoursDue(@Nullable GachaState state, boolean revealPending, boolean dbReady) {
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
	public synchronized ChestOpenResult openFirstColoursChest(@Nullable Set<Integer> preferredCardIds) {
		GachaState state = stateService.get();
		if (!firstColoursDue(state, pending != null, cardDatabase.isReady())) {
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

	/**
	 * {@code require} steers the FIRST card only, and only as a preference. A
	 * chest that promised the player something usable must keep that promise in
	 * the card they see first, while the rest of the box stays honest gacha.
	 * Every caller that has nothing to steer passes an explicit null; the
	 * convenience overload that used to hide it was pure budget.
	 */
	ChestOpenResult roll(Tuning.Chest tier, @Nullable String themedSetTag,
		@Nullable GearSlot targetSlot, long price,
		@Nullable Predicate<CardDefinition> require) {
		GachaState state = stateService.get();
		int opensSinceEpic = state == null ? 0 : state.getOpensSinceEpic();
		boolean rusty = tier == Tuning.Chest.RUSTY;

		// stardust blessing: consumed by the next chest that can actually roll
		// shiny — themed chests roll no variants, so they pass the blessing
		// through untouched. The flag clears at roll time (persisted
		// immediately) so a crash cannot re-arm.
		boolean blessed = themedSetTag == null && state != null && state.isStardustBlessArmed();
		if (blessed) {
			stateService.mutate(s -> s.withStardustBlessArmed(false));
		}

		// jackpot upgrade (regular untargeted chests only; the starter tier
		// never upgrades — it must stay the humblest box in the shop).
		//
		// rng.chance is the LAST && term deliberately: && short-circuits, so a
		// themed, slot-targeted or Rusty chest still consumes no RNG here, exactly
		// as the nested ifs this replaces did. Move it earlier and every fixed-seed
		// test shifts.
		boolean jackpot = themedSetTag == null && targetSlot == null && !rusty
			&& rng.chance(Tuning.JACKPOT_CHANCE);
		// Chest is declared RUSTY, BATTERED, GILDED, ORNATE, so ordinal + 1 IS the
		// one-tier promotion: BATTERED -> GILDED, GILDED -> ORNATE, the only two
		// steps the ladder has. ORNATE is excluded because it is the top of the shop
		// — its jackpot pays a fourth card below instead — and RUSTY can never get
		// here at all because !rusty gates the roll. That is a real dependency on
		// the enum's declaration order, which ChestJackpotLadderTest pins.
		Tuning.Chest effective = jackpot && tier != Tuning.Chest.ORNATE
			? Tuning.Chest.values()[tier.ordinal() + 1]
			: tier;
		int cardCount = targetSlot != null ? 1 : Tuning.CHEST_CARDS.get(effective);
		if (jackpot && tier == Tuning.Chest.ORNATE) {
			cardCount++; // ornate jackpot: 4th card
		}

		// pity (themed chests are free rewards and sit outside pity; Rusty can
		// never pay Epic+ so it neither benefits from nor advances pity)
		boolean pityEligible = themedSetTag == null && !rusty;
		boolean pityBreak = pityEligible && opensSinceEpic + 1 >= Tuning.PITY_HARD_CAP;
		double pityBonus = pityEligible
			? Math.max(0, opensSinceEpic - Tuning.PITY_SOFT_START) * Tuning.PITY_BONUS_PER_OPEN
			: 0;

		List<CardDefinition> pool = poolFor(targetSlot == null ? null : targetSlot.name(),
			themedSetTag, rusty, state);

		// themed chests roll no variants at all (unchanged); Rusty rolls no
		// holograms (too grand for the starter box) but shiny at a juiced rate
		boolean hologramsAllowed = themedSetTag == null && !rusty;
		double shinyChance = themedSetTag != null ? 0
			: (rusty ? Tuning.RUSTY_SHINY_CHANCE : Tuning.SHINY_CHANCE);
		int shinyAttempts = blessed ? 2 : 1;
		Set<String> ownedKeys = ownedKeys(state);
		List<RolledSlot> slots = new ArrayList<>(cardCount);
		for (int i = 0; i < cardCount; i++) {
			// The ternary on `rarity` is load-bearing, not cosmetic: it
			// short-circuits, so the guaranteed pity card still consumes NO
			// rollRarity draw. Turn it into an unconditional call whose result is
			// then discarded and every fixed-seed test in the suite moves.
			boolean locked = i == 0 && pityBreak;
			Rarity rarity = locked
				? Rarity.LEGENDARY
				: rollRarity(Tuning.CHEST_ODDS.get(effective), pityBonus);
			RolledSlot slot = rollSlot(pool, rarity, hologramsAllowed, shinyChance,
				shinyAttempts, ownedKeys, i == 0 ? require : null);
			slots.add(locked ? slot.withPityLocked(true) : slot);
		}

		// themed chests never grant deeds; the null check stays FIRST so it still
		// gates whether the deed roll is drawn at all
		boolean deed = themedSetTag == null
			&& rng.chance(Tuning.DEED_CHANCE.getOrDefault(tier, 0.0));

		return new ChestOpenResult(tier, effective, jackpot, pityBreak, deed, themedSetTag,
			targetSlot == null ? null : targetSlot.name(), slots, price, blessed);
	}

	Rarity rollRarity(double[] odds, double pityBonusPercent) {
		// shift pity bonus into EPIC+LEGENDARY mass, taken from COMMON. Shared with
		// the odds disclosure so the panel cannot quote a different pity curve than
		// the one the roll runs; the cumulative walk below stays here on purpose,
		// because pickHologram's near-identical walk falls back to the LAST element
		// while this one falls back to index 0 (EarlyGameMathTest.rustyRollsCommonOnly
		// depends on exactly that).
		double[] adjusted = RollOdds.adjustOdds(odds, pityBonusPercent);
		double total = 0;
		for (double odd : adjusted) {
			total += odd;
		}
		double roll = rng.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < adjusted.length; i++) {
			cumulative += adjusted[i];
			if (roll < cumulative) {
				return Rarity.values()[i];
			}
		}
		return Rarity.COMMON;
	}

	RolledSlot rollSlot(List<CardDefinition> pool, Rarity rarity, boolean hologramsAllowed,
		double shinyChance, int shinyAttempts, Set<String> ownedKeys) {
		return rollSlot(pool, rarity, hologramsAllowed, shinyChance, shinyAttempts, ownedKeys, null);
	}

	RolledSlot rollSlot(List<CardDefinition> pool, Rarity rarity, boolean hologramsAllowed,
		double shinyChance, int shinyAttempts, Set<String> ownedKeys,
		@Nullable Predicate<CardDefinition> require) {
		// hologram replaces the card entirely
		if (hologramsAllowed && !cardDatabase.holograms().isEmpty() && rng.chance(Tuning.HOLOGRAM_CHANCE)) {
			HologramDefinition holo = pickHologram();
			boolean dupe = ownedKeys.contains("H:" + holo.getTierKey());
			return new RolledSlot(holo.getRarity(), -1, holo.getTierKey(), Variant.HOLOGRAM, dupe, false, false);
		}
		CardDefinition card = pickCardOfRarity(pool, rarity, require);
		Variant variant = Variant.NORMAL;
		boolean nearMiss = false;
		if (shinyChance > 0 && card.isShinyEligible()) {
			// the raw roll is captured (not chance()) so the near-miss band is
			// observable; draw count is unchanged except for blessed retries
			double band = shinyChance * Tuning.STARDUST_NEAR_MISS_MULT;
			for (int attempt = 0; attempt < Math.max(1, shinyAttempts); attempt++) {
				double r = rng.nextDouble();
				if (r < shinyChance) {
					variant = Variant.SHINY;
					nearMiss = false;
					break;
				}
				if (r < band) {
					nearMiss = true;
				}
			}
		}
		boolean duplicate = variant == Variant.NORMAL && ownedKeys.contains("C:" + card.getCardId());
		return new RolledSlot(card.getRarity(), card.getCardId(), null, variant, duplicate, false, nearMiss);
	}

	/**
	 * {@code require} narrows the candidate list BEFORE the draw instead of
	 * rejecting after it, so a constrained pick still costs exactly one
	 * rng.pick. With require == null the candidate lists — and therefore every
	 * nextInt bound — are identical to the unconstrained build, so no existing
	 * seed moves.
	 */
	CardDefinition pickCardOfRarity(List<CardDefinition> pool, Rarity rarity,
		@Nullable Predicate<CardDefinition> require) {
		RollBucket bucket = bucketFor(pool, rarity, require);
		return bucket.isLeaned() ? pickLeaned(bucket.getCards()) : rng.pick(bucket.getCards());
	}

	/**
	 * The exact candidate list a roll of this rarity would draw from — everything
	 * pickCardOfRarity used to do except the draw itself. Split out because it
	 * consumes NO RNG, which is what lets oddsFor() quote the roll's own numbers
	 * instead of a parallel transcription that would drift.
	 *
	 * <p>Two passes rather than two near-identical loops. Pass 0 is the real draw:
	 * Epic+ may land anywhere, below that stay near what the player's levels can
	 * actually wield (see isReachable for the headroom). Pass 1 is the fallback for
	 * when the gate excluded every card of every rarity, and drops BOTH the gate
	 * and the preference — dropping the preference is free rather than a change,
	 * because constrained(list, null) hands back the very same list instance, so
	 * pass 1 builds exactly the list the old unconstrained loop built and no
	 * rng.pick bound moves.
	 *
	 * <p>Each pass walks r DOWN the Rarity declaration order: a rarity with no
	 * candidates falls back to the next one below it, never above.
	 */
	RollBucket bucketFor(List<CardDefinition> pool, Rarity rarity,
		@Nullable Predicate<CardDefinition> require) {
		for (int pass = 0; pass < 2; pass++) {
			final boolean gate = pass == 0 && !rarity.atLeast(Rarity.EPIC);
			for (int r = rarity.ordinal(); r >= 0; r--) {
				final Rarity target = Rarity.values()[r];
				List<CardDefinition> candidates = constrained(pool.stream()
					.filter(c -> c.getRarity() == target)
					.filter(c -> !gate || isReachable(c, true))
					.collect(Collectors.toList()), pass == 0 ? require : null);
				if (!candidates.isEmpty()) {
					// `gate` doubles as the leaned flag on purpose: the house lean
					// applies exactly when the proximity gate ran, so one variable
					// cannot let the two drift apart.
					return new RollBucket(candidates, gate);
				}
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
	private CardDefinition pickLeaned(List<CardDefinition> candidates) {
		double[] weights = new double[candidates.size()];
		double total = 0;
		int wieldable = 0;
		for (int i = 0; i < candidates.size(); i++) {
			boolean now = isReachable(candidates.get(i), false);
			if (now) {
				wieldable++;
			}
			// total is accumulated from the very values the walk below reads, so the
			// two can never disagree in the last bit and drop off the end
			weights[i] = RollOdds.leanWeight(now);
			total += weights[i];
		}
		if (wieldable == 0 || wieldable == candidates.size()) {
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
		@Nullable Predicate<CardDefinition> require) {
		if (require == null || candidates.isEmpty()) {
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
	private List<CardDefinition> rustyPool(@Nullable GachaState state) {
		Set<String> deeded = state == null || state.getDeededSlots() == null
			? Set.of() : state.getDeededSlots();
		return cardDatabase.all().values().stream()
			.filter(c -> c.getSlot() != null && deeded.contains(c.getSlot().name()))
			.filter(c -> isReachable(c, false))
			.collect(Collectors.toList());
	}

	/**
	 * The card pool a chest of this shape draws from, plus the empty-pool fallback.
	 * Shared by roll() and rerollSlot() so a reroll can never draw from a different
	 * pool than the open it is re-flipping.
	 *
	 * <p>Keyed on the gear slot's NAME rather than the enum because that is how
	 * ChestOpenResult carries it (it has to survive Gson), and GearSlot.name() /
	 * valueOf() round-trip exactly, so the filtered pool is unchanged.
	 *
	 * <p>An empty pool has two causes, only one of which is a defect: bosses.json
	 * naming a set sets.json does not define (a dataset bug, integrity-tested) —
	 * and, perfectly legitimately, a Rusty pool on an account whose deeded slots
	 * hold nothing wieldable yet. Both fall back to the whole card list so a chest
	 * always deals, and both log, because the first case is worth shouting about
	 * and the second is rare enough that the noise is cheap. Note this makes the
	 * reroll path log where it used to fall back silently, and turns a reroll of an
	 * empty themed/slot-targeted pool from an rng.pick crash into the same fallback
	 * the open itself would have taken.
	 */
	private List<CardDefinition> poolFor(@Nullable String targetSlot, @Nullable String themedSetTag,
		boolean rusty, @Nullable GachaState state) {
		List<CardDefinition> pool;
		if (targetSlot != null) {
			GearSlot slot = GearSlot.valueOf(targetSlot);
			pool = cardDatabase.all().values().stream()
				.filter(c -> c.getSlot() == slot)
				.collect(Collectors.toList());
		}
		else if (themedSetTag != null) {
			pool = cardDatabase.setMembers(themedSetTag);
		}
		else if (rusty) {
			pool = rustyPool(state);
		}
		else {
			pool = new ArrayList<>(cardDatabase.all().values());
		}
		if (pool.isEmpty()) {
			log.warn("empty chest pool (themedSetTag={}, targetSlot={}) — falling back to all cards",
				themedSetTag, targetSlot);
			pool = new ArrayList<>(cardDatabase.all().values());
		}
		return pool;
	}

	/**
	 * Is this card's tier within reach of the player's levels? With
	 * {@code allowHeadroom} it also admits the aspirational band just above them.
	 *
	 * <p>Headroom is a flag rather than a number because the two branches below
	 * measure it in different units — metal in tier ranks, dhide/robes in skill
	 * levels. Callers pass it explicitly (the one-argument convenience overload was
	 * deleted as pure budget); true is the roll's proximity gate, false is "can
	 * wield today", which is what the lean and the Rusty pool ask for.
	 */
	boolean isReachable(CardDefinition card, boolean allowHeadroom) {
		if (card.getTierKey() == null || client == null || tierTable == null) {
			return true; // untiered gear (or headless tests) is never proximity-gated
		}
		String ladder = tierTable.ladderOf(card.getTierKey());
		if (ladder == null) {
			return true;
		}
		switch (ladder) {
			case "metal":
				RangedMetal ranged = rangedMetal.of(card.getName());
				if (ranged != null) {
					// Arrows, bolts, javelins, crossbows, darts, knives and thrownaxes wear a
					// metal prefix but are Ranged gear, and mostly not on the ladder's numbers
					// (a rune crossbow is 61, not 40). Measured in levels like dhide/robes, so
					// the level headroom; no ranged weapon or ammunition carries a Defence gate.
					return Tuning.withinReach(
						client.getRealSkillLevel(Skill.RANGED),
						client.getRealSkillLevel(Skill.DEFENCE),
						rangedMetal.reqRangedLevel(ranged, card.getTierKey(),
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
	private boolean ladderWithinReach(CardDefinition card, int primaryLevel, boolean allowHeadroom) {
		int reqDefence = card.getSlot() == GearSlot.BODY
			? tierTable.reqDefenceOf(card.getTierKey())
			: 1;
		return Tuning.withinReach(primaryLevel,
			client.getRealSkillLevel(Skill.DEFENCE),
			tierTable.reqLevelOf(card.getTierKey()), reqDefence,
			allowHeadroom ? Tuning.ROLL_LEVEL_HEADROOM : 0);
	}

	HologramDefinition pickHologram() {
		List<HologramDefinition> holos = new ArrayList<>(cardDatabase.holograms().values());
		int maxRank = 1;
		for (HologramDefinition holo : holos) {
			maxRank = Math.max(maxRank, rankOf(holo));
		}
		double[] weights = new double[holos.size()];
		double total = 0;
		for (int i = 0; i < holos.size(); i++) {
			weights[i] = Math.pow(maxRank - rankOf(holos.get(i)) + 1, 2);
			total += weights[i];
		}
		// the same cumulative walk this used to open-code, including the landing
		// spot when a rounding crumb pushes the roll past the total: weightedIndex
		// returns the LAST index, which is what holos.get(holos.size() - 1) did.
		// One rng.nextDouble(), in the same position, so no seeded test moves.
		return holos.get(RollOdds.weightedIndex(rng.nextDouble() * total, weights));
	}

	private int rankOf(HologramDefinition holo) {
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
	public OddsDisclosure oddsFor(Tuning.Chest tier) {
		GachaState state = stateService.get();
		// these five mirror roll() exactly — keep them adjacent so a reviewer can
		// diff the two blocks by eye
		boolean rusty = tier == Tuning.Chest.RUSTY;
		int opensSinceEpic = state == null ? 0 : state.getOpensSinceEpic();
		int hardCap = Tuning.PITY_HARD_CAP;
		double pityBonus = rusty
			? 0
			: Math.max(0, opensSinceEpic - Tuning.PITY_SOFT_START) * Tuning.PITY_BONUS_PER_OPEN;
		boolean pityBreakNext = !rusty && opensSinceEpic + 1 >= hardCap;

		List<CardDefinition> pool = rusty
			? rustyPool(state)
			: new ArrayList<>(cardDatabase.all().values());
		if (pool.isEmpty()) {
			pool = new ArrayList<>(cardDatabase.all().values());
		}

		// one pass, not five: the rarity buckets walk down and revisit cards, and
		// cardId is the key of cardDatabase.all() so it is unique per definition
		Map<Integer, Boolean> wieldableByCardId = new HashMap<>();
		for (CardDefinition card : pool) {
			wieldableByCardId.put(card.getCardId(), isReachable(card, false));
		}

		double[] adjusted = RollOdds.adjustOdds(Tuning.CHEST_ODDS.get(tier), pityBonus);
		double[] rarityShare = RollOdds.normalize(adjusted);
		Map<RollOdds.TierBand, Double> totals = new LinkedHashMap<>();
		// names per row, for the tooltip that has to name the pieces a split tier
		// leaves out of reach. Sorted and de-duplicated: a card can be revisited
		// across rarity buckets, and an arbitrary order would reshuffle every rebuild.
		Map<RollOdds.TierBand, SortedSet<String>> namesByBand = new HashMap<>();
		for (Rarity rarity : Rarity.values()) {
			double share = rarityShare[rarity.ordinal()];
			if (share <= 0) {
				continue;
			}
			RollBucket bucket = bucketFor(pool, rarity, null);
			List<CardDefinition> cards = bucket.getCards();
			boolean[] flags = new boolean[cards.size()];
			// one pass fills flags[] and files the name, because the name only ever
			// needs its OWN index's flag — nothing here reads a later one
			for (int i = 0; i < cards.size(); i++) {
				CardDefinition card = cards.get(i);
				flags[i] = wieldableByCardId.getOrDefault(card.getCardId(), true);
				namesByBand
					.computeIfAbsent(RollOdds.bandOf(card, flags[i]),
						k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
					.add(card.getName());
			}
			for (Map.Entry<RollOdds.TierBand, Double> entry
				: RollOdds.tierShares(cards, flags, bucket.isLeaned()).entrySet()) {
				totals.merge(entry.getKey(), entry.getValue() * share, Double::sum);
			}
		}

		List<TierOdds> rows = new ArrayList<>(totals.size());
		double wieldableTotal = 0;
		double headroomTotal = 0;
		double untieredTotal = 0;
		for (Map.Entry<RollOdds.TierBand, Double> entry : totals.entrySet()) {
			double probability = entry.getValue();
			if (probability <= 0) {
				continue;
			}
			RollOdds.TierBand band = entry.getKey();
			boolean untiered = RollOdds.UNTIERED.equals(band.getTierKey());
			if (untiered) {
				untieredTotal += probability;
			}
			else if (band.isWieldableNow()) {
				wieldableTotal += probability;
			}
			else {
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
		for (int i = 0; i < rarityShare.length; i++) {
			rarityPercent[i] = rarityShare[i] * 100;
		}
		return new OddsDisclosure(tier, rarityPercent, rows, wieldableTotal, headroomTotal,
			untieredTotal, pityBonus, opensSinceEpic, hardCap, pityBreakNext);
	}

	// --- In-reveal reroll ---

	public synchronized boolean canReroll(int slotIndex) {
		GachaState state = stateService.get();
		return pending != null && !rerollUsedThisReveal
			&& state != null && state.getRerollTokens() > 0
			&& slotIndex >= 0 && slotIndex < pending.getSlots().size()
			&& !pending.getSlots().get(slotIndex).isPityLocked();
	}

	/** Spend a reroll token to re-flip one slot; returns the new slot or null. */
	@Nullable
	public synchronized RolledSlot rerollSlot(int slotIndex) {
		if (!canReroll(slotIndex)) {
			return null;
		}
		GachaState state = stateService.get();
		stateService.mutate(s -> s.withRerollTokens(s.getRerollTokens() - 1));
		rerollUsedThisReveal = true;

		boolean rusty = pending.getPurchasedTier() == Tuning.Chest.RUSTY;
		List<CardDefinition> pool = poolFor(pending.getTargetSlot(), pending.getThemedSetTag(),
			rusty, state);
		boolean hologramsAllowed = pending.getThemedSetTag() == null && !rusty;
		double shinyChance = pending.getThemedSetTag() != null ? 0
			: (rusty ? Tuning.RUSTY_SHINY_CHANCE : Tuning.SHINY_CHANCE);
		int shinyAttempts = pending.isStardustBlessed() ? 2 : 1;
		Rarity rarity = rollRarity(Tuning.CHEST_ODDS.get(pending.getEffectiveTier()), 0);
		RolledSlot fresh = rollSlot(pool, rarity, hologramsAllowed, shinyChance, shinyAttempts,
			ownedKeys(state));

		List<RolledSlot> slots = new ArrayList<>(pending.getSlots());
		slots.set(slotIndex, fresh);
		// withSlots() is the generated copy of every OTHER field verbatim; the list
		// is freshly built so it is never == and a real copy always happens
		pending = pending.withSlots(slots);
		persistPending();
		notifyListeners(ChestListener::onRerollSpent);
		return fresh;
	}

	/**
	 * Fan one event out to every listener — the three call sites (reroll spent,
	 * chest committed, deed claimed) had this loop copied out verbatim.
	 *
	 * <p>The defensive copy is what lets a listener add or drop another one from
	 * inside its own callback without a ConcurrentModificationException, and the
	 * per-listener catch is what stops a single broken listener from stranding a
	 * chest half-committed: by the time this runs the state write and the GC award
	 * have already happened, so an exception escaping here would lose the
	 * notification AND the return value with nothing rolled back. Both now live in
	 * {@link Listeners#fire} — this wrapper stays because the three call sites
	 * would otherwise each have to repeat the collection and the warning text.
	 */
	private void notifyListeners(Consumer<ChestListener> event) {
		Listeners.fire(chestListeners, event, "chest listener failed");
	}

	// --- Commit (reveal closed or aborted) ---

	/** Apply the pending open to state. Returns GC gained from duplicates. */
	public synchronized long commitPending() {
		if (pending == null) {
			return 0;
		}
		ChestOpenResult result = pending;
		pending = null;

		long dupeGc = 0;
		List<OwnedCard> newCards = new ArrayList<>();
		for (RolledSlot slot : result.getSlots()) {
			if (slot.isDuplicate()) {
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
			// One walk, two tallies. Pity counts CARDS since the last Epic+ card, in
			// reveal order; stardust counts near-misses, which bank at commit
			// (rerolls replaced their slot, so this can never double-count) and arm
			// the next chest once 8 are held. The pity counter is computed even for
			// chests that sit outside pity and then thrown away — both reads are
			// pure, so the wasted arithmetic simply buys one less pass over the list.
			int counter = s.getOpensSinceEpic();
			int nearMisses = 0;
			for (RolledSlot slot : result.getSlots()) {
				counter = slot.getRarity().atLeast(Rarity.EPIC) ? 0 : counter + 1;
				if (slot.isNearMiss()) {
					nearMisses++;
				}
			}
			GachaState next = s.withOwnedCards(owned).withChestsOpenedByTier(byTier)
				.withPendingChestBlob(null);
			if (result.getThemedSetTag() == null
				&& result.getPurchasedTier() != Tuning.Chest.RUSTY) {
				next = next.withOpensSinceEpic(counter);
			}
			if (nearMisses > 0) {
				int dust = s.getStardust() + nearMisses;
				boolean armed = s.isStardustBlessArmed();
				if (!armed && dust >= Tuning.STARDUST_REQUIRED) {
					dust -= Tuning.STARDUST_REQUIRED;
					armed = true;
				}
				next = next.withStardust(dust).withStardustBlessArmed(armed);
			}
			if (result.isDeedGranted()) {
				boolean saturated = s.getDeededSlots().size() >= GearSlot.values().length;
				if (!saturated) {
					next = next.withPendingDeeds(s.getPendingDeeds() + 1);
				}
			}
			return next;
		});

		GachaState afterCommit = stateService.get();
		if (afterCommit != null && afterCommit.isStardustBlessArmed()
			&& (beforeCommit == null || !beforeCommit.isStardustBlessArmed())) {
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.SMALL, "Stardust blessing!",
				Tuning.STARDUST_REQUIRED + " stardust consumed — your next chest rolls shiny twice per card.",
				null));
		}

		if (dupeGc > 0) {
			creditSink.award(dupeGcFinal, new CreditSink.GcContext(CreditSink.Source.DUPLICATE, null, null));
		}
		if (result.isDeedGranted()) {
			GachaState state = stateService.get();
			if (state != null && state.getDeededSlots().size() >= GearSlot.values().length) {
				creditSink.award(Tuning.DEED_SATURATED_GC,
					new CreditSink.GcContext(CreditSink.Source.DEED_SATURATED, null, null));
			}
			else {
				ceremonyBus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
			}
		}
		notifyListeners(l -> l.onChestCommitted(result, dupeGcFinal));
		return dupeGc;
	}

	// --- Deed choice ---

	public boolean claimDeed(GearSlot slot) {
		GachaState state = stateService.get();
		if (state == null || state.getPendingDeeds() <= 0
			|| state.getDeededSlots().contains(slot.name())) {
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
		notifyListeners(l -> l.onDeedClaimed(slot));
		return true;
	}

	/** Milestone deed from task completion (already counted in TaskService). */
	public void grantMilestoneDeed() {
		stateService.mutate(s -> s.withPendingDeeds(s.getPendingDeeds() + 1));
	}

	// --- Helpers ---

	static Set<String> ownedKeys(@Nullable GachaState state) {
		Set<String> keys = new HashSet<>();
		if (state == null) {
			return keys;
		}
		for (OwnedCard card : state.getOwnedCards()) {
			if (card.isHologram()) {
				keys.add("H:" + card.getTierKey());
			}
			else if (card.getVariant() == Variant.NORMAL) {
				keys.add("C:" + card.getCardId());
			}
		}
		return keys;
	}
}
