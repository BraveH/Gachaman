package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * The Consignment: a free Gilded chest whose price is your next style roll.
 *
 * <p>The house does not spin the wheel — it names the style, and it names the
 * one your album is worst dressed for. Take the deal and you get the crate and
 * the style the house picked; refuse it and the wheel spins as normal. Either
 * answer costs the roll, because the roll was the price of being asked.
 *
 * <h2>Why the roll is DEFERRED rather than undone</h2>
 *
 * This cannot be a ceremony that runs after {@link StyleService#roll}. Read that
 * class's comment: the roll commits immediately and the roulette is purely
 * cosmetic, so by the time a wheel is on screen the style is already in state.
 * An offer made there would be pricing something the player has already been
 * sold — "your next style roll" would name a roll that already happened, and
 * accepting could only overwrite it, which is a second roll, not a price.
 *
 * <p>So the roll is held back. When the cycle comes due the caller hands the
 * roll to {@link #offerOrRoll}, which arms {@link GachaState#isStyleRollOwed()}
 * and puts the offer to the player instead. The roll is taken on resolution —
 * with the house's style on accept, on the ordinary wheel on decline. This is
 * the {@code firstColoursChestOwed} idiom from StyleService: a flag that says
 * something is OWED, never that something is DONE, so a save written before the
 * field existed deserializes false and false means "nothing owed".
 *
 * <p><b>Losing the flag is safe, and that is by design.</b> {@code advanceCycle}
 * never resets {@code cycleProgress} — only {@code roll()} does — so a deferred
 * roll that gets dropped (crash, kill -9, a profile switch mid-offer) leaves the
 * state cycle-OVERDUE, and the very next {@code advanceCycle} returns true
 * again. The wheel is re-owed automatically. Every failure path in this file
 * leans on that: nothing here has to be transactional with StyleService, because
 * the worst case is one contract's delay, not a lost roll.
 *
 * <h2>What spends the day key</h2>
 *
 * At most one Consignment per UTC day, tracked by
 * {@link GachaState#getConsignmentDayKey()}. The rule is that an offer the
 * player never got to answer must not be charged:
 *
 * <table>
 *   <tr><td>accepted</td><td>SPENT — roll taken now, house's style</td></tr>
 *   <tr><td>declined (Escape)</td><td>SPENT — roll taken now, ordinary wheel</td></tr>
 *   <tr><td>aborted by safe mode (incoming damage)</td><td>UNSPENT, still owed</td></tr>
 *   <tr><td>logged out mid-offer</td><td>UNSPENT, still owed</td></tr>
 * </table>
 *
 * <p>Decline SPENDS deliberately, and unlike {@code DEED_CHOICE} this offer must
 * NOT re-queue on decline. A deed that re-queues costs nothing — the deed is
 * still owed either way. A Consignment that re-queues is re-offerable at will:
 * Escape, finish another contract, get asked again, and the once-per-day gate is
 * worth nothing. Being asked is the thing that is rationed, not being crated.
 *
 * <h2>A note on the shape of this file</h2>
 *
 * <p>It has been through a compression pass against the Plugin Hub's 200k token
 * ceiling, which counts this source with comments and blank lines stripped.
 * Nothing player-visible changed and no behaviour moved: the savings are all
 * one-line guards, {@code var} on locals whose type the initialiser already
 * names, Lombok standing in for hand-written accessors, and single-caller
 * helpers folded into their caller with their javadoc carried along. Where a
 * fold is not obvious the comment at the site says what was folded and why.
 * Comments cost nothing here, so none were shortened to pay for any of it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ConsignmentService {
	/** The deal as it is put to the player. Immutable; safe to hand to the EDT. */
	@Value
	public static class Offer {
		/** The style the house names — the one the album is worst dressed for. */
		AttackStyle style;
		/** How few weapon cards that style has; the reason it was picked. */
		int ownedWeaponCards;
		/** Always GILDED. Carried rather than assumed so the crate art can read it. */
		Tuning.Chest chestTier;
	}

	/**
	 * Whoever puts the offer on screen. One presenter at a time (the overlay).
	 *
	 * <p><b>The contract, in full, because the presenter lives in another file:</b>
	 * returning true means "I am showing this AND I will eventually call exactly
	 * one of {@link #accept}, {@link #decline} or {@link #abandon}". Returning
	 * false means "I cannot show this right now" — the roll is then taken on the
	 * ordinary wheel immediately and the day key is NOT spent, because an offer
	 * that never reached the player is not an offer.
	 *
	 * <p>The obligation to call {@link #abandon} on teardown sits with the
	 * presenter, not with safe mode. {@code SafeModeService.abortIfModal} only
	 * fires while the overlay reports itself modal, so a ceremony torn down by a
	 * logout, a plugin shutdown or a profile switch never reaches it. A presenter
	 * that drops an offer without calling anything is not a disaster — the owed
	 * flag is still on disk and the login drain settles it — but it does cost the
	 * player a wheel they were mid-way through answering.
	 */
	public interface Presenter {
		/** Return true when the offer was claimed for presentation. */
		boolean present(Offer offer);
	}

	private final GachaStateService stateService;
	private final StyleService styleService;
	private final ChestService chestService;
	private final CardDatabase cardDatabase;
	private final CeremonyBus ceremonyBus;

	/**
	 * Lombok writes {@code setPresenter} rather than this file: same public
	 * signature, same assignment, and the hand-written body was pure budget.
	 */
	@Setter private volatile Presenter presenter;
	/**
	 * The offer currently in front of the player, or null.
	 *
	 * <p>Deliberately NOT persisted, unlike the owed flag. A live offer is a
	 * question being asked, and a question does not survive the client that was
	 * asking it: the state that has to outlive a crash is "a roll is owed", which
	 * the flag carries, and the login drain settles that by rolling rather than by
	 * re-asking. Re-asking on login would be an offer at an arbitrary time, which
	 * is exactly what the once-per-day gate exists to prevent.
	 *
	 * <p>Volatile because the panel may read it off the EDT while the client
	 * thread writes it. Every reader takes one read into a local — Offer is a
	 * deeply immutable value type, so seeing the reference is seeing the whole
	 * thing.
	 */
	private volatile Offer live;
	/**
	 * Guards the claim of {@link #live} and NOTHING else — never a state mutation
	 * and never a presenter call.
	 *
	 * <p>Reading the offer and clearing it is a check-then-act, and both halves
	 * have to be one step or a double-click deals two crates. Making the whole
	 * resolution synchronized would have done it too, but that would hold this
	 * monitor across {@code stateService.mutate}, which notifies every state
	 * listener from inside its own monitor — the exact nested-monitor-across-
	 * threads shape CeremonyBus.submit documents avoiding. A lock this small
	 * cannot participate in a cycle.
	 *
	 * <p>{@link #pendingOffer()} deliberately reads {@link #live} WITHOUT taking
	 * this lock, and that is not an oversight. A single read has nothing to make
	 * atomic — the volatile is the whole of what it needs — and keeping the
	 * panel's read lock-free is what guarantees the EDT can never end up waiting
	 * on the client thread to finish resolving an offer.
	 */
	private final Object offerLock = new Object();

	/** Take the live offer, if there is one, leaving nothing behind for a second answer. */
	private Offer claim() {
		synchronized (offerLock) { Offer c = live; live = null; return c; }
	}

	// --- Pure rules -----------------------------------------------------------

	/**
	 * The day key, shaped exactly like the weekly shop's week key and derived the
	 * same way: UTC, from the client's own clock, with no server and nothing
	 * stored but the last one used. Comparing two derived strings is what makes
	 * "once a day" work offline, across restarts, and across world hops.
	 *
	 * <p>Lifted verbatim from the removed Charter Office, which is the point: two
	 * daily gates in one plugin that disagreed about when a day turns over would
	 * be a bug nobody could reproduce without waiting for midnight.
	 */
	public static String dayKey(LocalDate date) { return date.getYear() + "-D" + date.getDayOfYear(); }

	/**
	 * The UTC day key for an instant. The UTC pin lives HERE rather than at the
	 * call site so there is exactly one place that can get the zone wrong: a
	 * player's local midnight is not the gate, and two players on the same world
	 * in different timezones roll over together.
	 */
	public static String dayKeyAt(long epochMs) { return dayKey(LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)); }

	/** A Consignment was already put to the player on this day key. */
	public static boolean usedOn(String lastDayKey, String today) { return lastDayKey != null && lastDayKey.equals(today); }

	/**
	 * How many DISTINCT weapon cards in this pool the album holds.
	 *
	 * <p>Distinct by card id, not by copy: a second Rune scimitar card does not
	 * make anybody better dressed, and counting copies would let a run of dupes
	 * quietly move a style out of last place. Holograms are skipped because they
	 * carry card id -1 and a tier key instead — they genuinely do dress a slot,
	 * but they name a whole tier rather than a weapon, and this measure is
	 * "how many weapons has this style been given", not "what can you equip".
	 */
	public static int ownedFrom(Set<Integer> pool, List<OwnedCard> owned) {
		if (pool == null || pool.isEmpty() || owned == null) { return 0; }
		// distinct() over the boxed ids is the HashSet this used to build by hand:
		// same equals/hashCode, same count, and the set no longer needs a name
		return (int) owned.stream().filter(c -> c != null && !c.isHologram() && pool.contains(c.getCardId()))
			.map(OwnedCard::getCardId).distinct().count();
	}

	/**
	 * The worst-dressed style: fewest owned weapon cards, ties broken by
	 * declaration order (MELEE, then RANGED, then MAGIC).
	 *
	 * <p><b>The tie-break is the main path, not a corner case.</b> The pools come
	 * from {@code weaponCardIdsForStyle}, which returns COMMON weapons only, so a
	 * young album sits at 0/0/0 and every early Consignment is decided by the
	 * break alone. That makes "first declared wins" a rule the player can learn
	 * and predict, which a coin flip could never be — and a seeded flip would be
	 * worse still, since the seed would have to be persisted to survive the
	 * offer being re-computed for the panel.
	 *
	 * <p>Nothing excludes the style the player is already locked into. The wheel
	 * has always been allowed to land on the same style twice in a row, and the
	 * house naming a style it happens to already be is the same kind of luck.
	 */
	public static AttackStyle worstDressed(Map<AttackStyle, Set<Integer>> pools, List<OwnedCard> owned) {
		AttackStyle worst = null;
		int fewest = Integer.MAX_VALUE;
		for (AttackStyle style : AttackStyle.values()) {
			int count = ownedFrom(pools == null ? null : pools.get(style), owned);
			// strictly-less-than is what makes the tie-break declaration order:
			// a later style has to actually beat the incumbent to displace it
			if (count < fewest) { fewest = count; worst = style; }
		}
		return worst;
	}

	// --- Live state -----------------------------------------------------------

	public String currentDayKey() { return dayKeyAt(System.currentTimeMillis()); }

	/** The Consignment has already been put to this player today. */
	public boolean offeredToday() {
		var state = stateService.get();
		return state != null && usedOn(state.getConsignmentDayKey(), currentDayKey());
	}

	/** The offer currently in front of the player, or null. */
	public Offer pendingOffer() { return live; }

	// --- The offer ------------------------------------------------------------

	/**
	 * A style roll has come due. Either put the Consignment to the player —
	 * deferring the roll until they answer — or take the roll right now.
	 *
	 * <p>This is the ONLY entry point for a due roll, and it always leaves a roll
	 * either taken or owed. Callers replace a bare {@code styleService.roll(tick)}
	 * with this and read nothing back but the boolean.
	 *
	 * <p><b>Not the debug command.</b> {@code ::gachastyle} must keep calling
	 * {@link StyleService#roll} directly. Routing it through here for consistency
	 * would let a developer spend a real player's once-per-day Consignment from a
	 * chat box, and would make the offer appear at an arbitrary time, which is the
	 * one thing the design forbids.
	 *
	 * @return true when the roll was DEFERRED and an offer is now live.
	 */
	public boolean offerOrRoll(int tick) {
		var state = stateService.get();
		// not loaded: there is no cycle to advance and nothing to roll
		if (state == null) { return false; }
		if (!canOffer(state)) { rollNow(tick); return false; }
		// One scan of the card database for all three styles, reused for both the
		// pick and the count it is justified by. weaponCardIdsForStyle walks every
		// card and reads item stats live, so asking it a fourth time to re-count
		// the style it just named would double the cost of the whole offer.
		//
		// This scan was a private weaponPools() helper with one caller, which is
		// pure budget, so it was folded in here and brought its javadoc with it:
		// CLIENT THREAD ONLY, inherited from weaponCardIdsForStyle — it reads item
		// stats live rather than off the card. The only caller is the offer, which
		// is raised from the contract-completion path, already on that thread.
		Map<AttackStyle, Set<Integer>> pools = new EnumMap<>(AttackStyle.class);
		for (AttackStyle style : AttackStyle.values()) { pools.put(style, cardDatabase.weaponCardIdsForStyle(style)); }
		// one read of the album, shared by the pick and the count that justifies it
		var owned = state.getOwnedCards();
		var named = worstDressed(pools, owned);
		var offer = new Offer(named, ownedFrom(pools.get(named), owned), Tuning.Chest.GILDED);

		// Armed BEFORE the offer goes on screen, never after. The flag is the only
		// record that a roll is outstanding, and the window it closes is the whole
		// point of the feature: a client that dies with the offer up must find the
		// roll still owed, not silently swallowed. Ordering it the other way would
		// make the crash cost a wheel instead of a question.
		stateService.mutate(s -> s.withStyleRollOwed(true));
		synchronized (offerLock) { live = offer; }

		// the presenter's true/false is read straight out of the call rather than
		// through a `claimed` flag; a throw lands in the catch and falls through to
		// the unclaimed path exactly as the flag's initial false used to
		var p = presenter;
		if (p != null) {
			try { if (p.present(offer)) { return true; } }
			catch (Exception e) { log.warn("Consignment presenter failed", e); }
		}
		// A presenter is allowed to resolve inline (a headless one does exactly
		// that) and then report false because it never put pixels up. It has
		// already taken the roll, so taking another here would hand out a free
		// re-roll — claiming the offer back is what tells the two cases apart:
		// getting nothing means somebody already answered.
		if (claim() == null) { return false; }
		// Nobody could show it, so nobody was asked: the day key stays unspent and
		// the wheel spins as it would have without this feature at all.
		rollNow(tick);
		return false;
	}

	/**
	 * Every way the offer can be off the table. Each term is a way the deal would
	 * be dishonest rather than merely unavailable:
	 *
	 * <ul>
	 * <li>no style yet — the first-ever roll. An account with no album cannot be
	 *     "worst dressed" for anything, and the free First Colours chest already
	 *     rides that roll; a second free crate on top of it would make the opening
	 *     minute the richest in the game. {@code allowedStyle == null} is the same
	 *     {@code firstEver} discriminator StyleService uses.</li>
	 * <li>a roll is already owed — an offer is live, or one was dropped and the
	 *     login drain has not run yet. Either way, one question at a time.</li>
	 * <li>the day key is spent — at most one per UTC day.</li>
	 * <li>the card database is not ready — worst-dressed cannot be computed from
	 *     an empty pool, and the crate could not be rolled either.</li>
	 * <li>a chest reveal is in flight, in memory or serialized by a client that
	 *     died mid-ceremony — the Gilded crate could not be dealt on accept, and
	 *     offering something undeliverable is worse than not offering.</li>
	 * </ul>
	 */
	private boolean canOffer(GachaState state) {
		return state.getAllowedStyle() != null && !state.isStyleRollOwed()
			&& !usedOn(state.getConsignmentDayKey(), currentDayKey())
			&& cardDatabase.isReady() && chestService.getPending() == null
			&& state.getPendingChestBlob() == null;
	}

	// --- Resolution -----------------------------------------------------------

	/**
	 * The half of {@link #accept} and {@link #decline} that is the same on both
	 * sides of the deal: refuse the answer if there is nothing owed, and spend the
	 * day key in ONE write. False means there was nothing to answer.
	 *
	 * <p>The offer is claimed by the CALLER and handed in already claimed, which
	 * keeps the original order: the claim happens before the state read, so a
	 * claim that then fails the owed test still drops the offer and a second click
	 * arriving behind it finds nothing rather than re-running the answer. It also
	 * lets accept read the price off the offer's own tier rather than assuming
	 * GILDED — the tier is carried on the Offer precisely so nothing assumes it.
	 *
	 * <p>{@code price} is the funding accept needs and decline does not; decline
	 * passes zero, and {@code withGc} on an unchanged value returns the same
	 * instance, so the decline write is byte-for-byte the two-field write it was
	 * before this method existed.
	 *
	 * <p>The write order is the crash contract, and it is deliberately the same
	 * side of the trade {@code openFirstColoursChest} picked. That method's
	 * javadoc says two writes leave a window that either re-gifts the chest or
	 * eats it, and it folded them into one to never re-gift. This path cannot
	 * fold — the crate's blob is written inside ChestService's own mutate, which
	 * is not ours to join — so it takes the same side by ordering: the day key is
	 * spent FIRST, and a crash in the window costs a crate rather than handing out
	 * a second free Gilded.
	 *
	 * <p>The owed flag clears in that same first write, before the roll is taken.
	 * A crash there leaves the state cycle-overdue with nothing owed, and the next
	 * completed contract re-fires the roll — one contract late, never lost. Clearing
	 * it after the roll would be the worse order: a crash between the two would
	 * leave a roll owed that had already happened, and the login drain would roll
	 * a second time.
	 */
	private boolean settle(Offer offer, long price) {
		var state = stateService.get();
		if (offer == null || state == null || !state.isStyleRollOwed()) { return false; }
		final String today = currentDayKey();
		stateService.mutate(s -> s.withConsignmentDayKey(today).withStyleRollOwed(false).withGc(s.getGc() + price));
		return true;
	}

	/**
	 * The player takes the deal: the house's style, and the Gilded crate free.
	 *
	 * <p>The crate is funded in the same write that spends the key (see
	 * {@link #settle}), then bought back out of the purse by the ordinary purchase
	 * path below. The round trip is exactly GC-neutral and it is what keeps the
	 * crate honest: pity, the lifetime tier counts and the pending-blob crash
	 * contract all come free, where a second kind of "free chest" inside
	 * ChestService would have to re-implement every one of them. lifetimeGcEarned
	 * is untouched on purpose — this is not income, it is the price of a thing
	 * being handed straight back.
	 *
	 * <p>The price is read off the claimed offer's own tier before the settle,
	 * which is a pure constant-map lookup with no side effect: moving it ahead of
	 * the owed test changes nothing but saves carrying the offer back out again.
	 */
	public boolean accept(int tick) {
		var offer = claim();
		final long price = offer == null ? 0 : Tuning.CHEST_PRICE_GC.get(offer.getChestTier());
		if (!settle(offer, price)) { return false; }

		// Roll first, crate second, so the two ceremonies queue in the order the
		// player reads them: the house names the style, then the box arrives.
		styleService.roll(tick, offer.getStyle());
		if (chestService.openChest(offer.getChestTier()) == null) {
			// Undeliverable after all (the reveal or the database moved under us).
			// The funding stays in the purse: nothing is being invented as a
			// consolation prize, we are simply declining to claw back GC the player
			// can now see, for a crate we failed to hand over.
			//
			// Submitted straight to the bus rather than through a fanfare() helper:
			// there was exactly one call, so the helper cost more than it saved.
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(CeremonyBus.Fanfare.Size.MEDIUM,
				"The consignment could not be crated", "Its price is in your purse instead: " + price + " GC.", null));
		}
		// The free crate, the named style and the spent day key all land in one
		// moment, and it is a moment the player would be furious to replay.
		stateService.checkpoint();
		log.debug("Consignment accepted: {} named, {} crate", offer.getStyle(), offer.getChestTier());
		return true;
	}

	/**
	 * The player refuses (Escape). The wheel spins as normal — and the day key is
	 * spent anyway, because being asked was the thing that was rationed. See the
	 * class comment for why this must not re-queue the way a deed choice does.
	 */
	public boolean decline(int tick) {
		if (!settle(claim(), 0)) { return false; }
		styleService.roll(tick);
		// A resolution, exactly like accept: the key is spent and the wheel has
		// turned. A player who declines and crashes must not be asked again today.
		stateService.checkpoint();
		return true;
	}

	/**
	 * The offer went away without an answer — safe mode aborted it on incoming
	 * damage, or the client is being torn down.
	 *
	 * <p>Mutates nothing at all, and that is the whole feature: the day key stays
	 * unspent because the player never answered, and the owed flag stays armed
	 * because the roll has not been taken. A pure read, so no checkpoint — there
	 * is nothing new on disk to flush.
	 */
	public void abandon() {
		if (claim() == null) { return; }
		log.debug("Consignment abandoned unanswered; day key unspent, roll still owed");
	}

	/**
	 * Login drain: settle a roll left owed by a client that died mid-offer.
	 *
	 * <p>Without this the wheel would sit owed until the player's NEXT contract
	 * completed, which on a bad night is hours of playing a style the game already
	 * decided to change. The cycle self-heals — see the class comment — but it
	 * heals slowly, and the player cannot see why.
	 *
	 * <p>It takes the ordinary wheel and never re-offers. The Consignment is only
	 * ever put up in the moment a roll comes due; a login is an arbitrary time,
	 * and an offer at an arbitrary time is what the once-per-day gate exists to
	 * stop. The day key is untouched, so a player who crashed mid-offer can still
	 * be asked properly the next time the cycle turns over today.
	 */
	public void drainOwedRoll(int tick) {
		var state = stateService.get();
		if (state == null || !state.isStyleRollOwed()) { return; }
		claim();
		rollNow(tick);
		stateService.checkpoint();
		log.debug("Drained a style roll left owed by an unanswered Consignment");
	}

	/**
	 * Take the roll on the ordinary wheel, making sure nothing is left owed.
	 *
	 * <p>The flag clears BEFORE the roll for the same reason accept clears it
	 * early: a crash between the two must leave the state cycle-overdue with
	 * nothing owed (one contract's delay) rather than owed-and-already-rolled (a
	 * free second wheel from the next login drain).
	 */
	private void rollNow(int tick) {
		var state = stateService.get();
		if (state != null && state.isStyleRollOwed()) { stateService.mutate(s -> s.withStyleRollOwed(false)); }
		styleService.roll(tick);
	}
}
