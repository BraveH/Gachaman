package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.config.*;

/**
 * The Toll: a weekly chest priced in a CARD rather than in GC.
 *
 * <p>The house names one card out of the player's own album — never junk, never
 * a hologram — and the chest opens only for that card, dealing a blind pull of
 * the tier the card belongs to. Hand over a veteran rune scimitar, get an
 * unknown rune-tier card back.
 *
 * <h2>Why the pick is PERSISTED and not recomputed</h2>
 *
 * <p>{@link WeeklyShopService} recomputes its three offers from a seed on every
 * call and stores nothing at all. That is safe there for one reason only: its
 * pool is the immutable card database, so the same seed indexes the same list
 * forever.
 *
 * <p>The Toll's pool is the player's ALBUM, and an album GROWS during the week.
 * Every chest opened, every shop card bought, and every card that crosses its
 * first kill of service changes the length and the contents of the eligible
 * list — so the same seed would index a DIFFERENT card on Tuesday than it did
 * on Monday, and the Toll would silently rename itself mid-week under a player
 * who was saving up for it. A naive port of the weekly shop's zero-state trick
 * produces exactly that bug, which is what
 * {@code TollServiceTest.pickIsStableAsTheAlbumGrows} exists to catch.
 *
 * <p>So the pick lands in {@link GachaState#getTollWeekKey()} +
 * {@link GachaState#getTollCardUuid()} and is recomputed only when the week key
 * moves or the named uuid is no longer owned. The deterministic seed therefore
 * chooses only the FIRST pick of each week — which is what "stable across a
 * week" actually means. It is not decoration: it keeps the choice reproducible
 * (and so debuggable) without making it re-derivable.
 *
 * <h2>The three states those two fields encode</h2>
 *
 * <ul>
 * <li>week key does not match — re-pick. Fresh state has a null week key, so a
 * new profile lands here too.</li>
 * <li>week key matches, uuid null — PAID. Terminal for the rest of the week.
 * This is the state {@link #purchase()} writes, and it is why consuming the
 * card cannot re-arm the Toll: the "uuid no longer owned" re-pick trigger is
 * never reached, because the null uuid is checked first.</li>
 * <li>week key matches, uuid names an owned card — the live offer. If that uuid
 * is not owned (burned, spent elsewhere), re-pick.</li>
 * </ul>
 *
 * <p>Nothing else re-picks. In particular a re-pick that finds NO eligible card
 * deliberately writes nothing at all — see {@link #resolve}.
 *
 * <h2>A note on the shape of this file</h2>
 *
 * <p>It has been through a compression pass against the Plugin Hub's 200k token
 * ceiling, which counts this source with comments and blank lines stripped. No
 * behaviour moved and nothing player-visible changed: the savings are one-line
 * guards, {@code var} on locals whose type the initialiser already spells out,
 * annotations sharing the line they annotate, and values read once into a local
 * instead of re-derived. In particular the three-state encoding above, and the
 * order in which {@link #resolve} tests it, are exactly as they were — that
 * order is the anti-re-arm guard and no amount of budget buys it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TollService {
	/**
	 * The refusal the player sees when the Toll names a card they still have
	 * assigned to a loadout slot. Deliberately distinct wording from the unassign
	 * guard's "Take the item off first": one intent (pay the Toll with a card
	 * that is both slotted AND worn) produces two refusals on two surfaces —
	 * unequip, then unassign, then pay — and two identical messages would read as
	 * one button that is simply broken.
	 *
	 * <p>Load-bearing rather than cosmetic: {@link #purchase()} consumes the
	 * card, and consuming a card that is still in the loadout map would leave a
	 * dangling uuid behind in it.
	 */
	public static final String ASSIGNED_REFUSAL = "That card is still in your %s slot";

	/**
	 * Salt that keeps the Toll's draw from correlating with the weekly shop's.
	 * Both mix the same profile key and the same week key, so without a salt the
	 * two would walk the same seeded sequence every week. "Toll" in ASCII.
	 */
	private static final long SEED_SALT = 0x546F6C6CL;

	/**
	 * The seam ChestService fills: deal the blind pull, through the very machinery
	 * every other chest uses (roll -> publish pending -> persist the blob ->
	 * queue the ceremony), so the Toll inherits the deferred commit, the
	 * mid-reveal reroll and the crash recovery instead of reimplementing any of
	 * them badly.
	 *
	 * <p>Declared here as a settable hook — the same shape as
	 * {@link LoadoutService#setAssignHook} — rather than by injecting ChestService
	 * directly, for two reasons. ChestService currently has no tier-scoped opener
	 * and no public way to publish a rolled result ({@code pending},
	 * {@code deal()} and {@code persistPending()} are all private), so the method
	 * this calls has to be added there by whoever owns that file; and until it is,
	 * an unwired hook leaves this class compiling and every other feature green.
	 * An unwired hook refuses the purchase and takes nothing.
	 */
	public interface TollPullDealer {
		/**
		 * @param tierKey        the tier ladder the pull must draw from, or null for
		 *                       the untiered band ({@link RollOdds#UNTIERED}) — see
		 *                       {@link TollOffer#getTierKey()}
		 * @param spentCardUuid  the card being handed over. It is still owned at
		 *                       this instant (see the ordering note on
		 *                       {@link #purchase()}), so it MUST be excluded from
		 *                       the roll's duplicate test — drop it from
		 *                       {@code ownedKeys(state)} for this roll only.
		 *                       <p>Not a tail case: the pull is drawn from the
		 *                       surrendered card's OWN tier ladder, so its cardId is
		 *                       one of a small pool and comes up often. Without the
		 *                       exclusion, paying a 300-kill veteran and pulling its
		 *                       own cardId reads as a duplicate and pays a handful of
		 *                       GC instead of dealing the card back — the player has
		 *                       destroyed a veteran card for pocket change.
		 * @return the dealt open, or null when nothing could be dealt (a reveal
		 *         already in flight, database not ready). A null return MUST mean
		 *         nothing was taken.
		 */
		ChestService.ChestOpenResult dealTollPull(String tierKey, String spentCardUuid);
	}

	/** Everything the shop panel needs to render the offer, so it re-derives none of it. */
	@Value
	public static class TollOffer {
		/** The card the house is asking for. Never a hologram. */
		OwnedCard card;
		/** Album-style name, including the "(Shiny)" suffix when it applies. */
		String displayName;
		/**
		 * The tier ladder the pull will draw from — null when the named card is
		 * untiered (or its definition has gone missing), which draws the untiered
		 * band rather than nothing. Untiered cards are eligible because the owner
		 * settled the eligibility test as exactly {@code !hologram && served > 0},
		 * and narrowing it here would be re-deciding that.
		 */
		String tierKey;
		/** The loadout slot the card still occupies, or null when it is free to hand over. */
		GearSlot assignedSlot;
		/** Why the Toll cannot be paid right now; null when it can. */
		String refusal;
	}

	/** The outcome of {@link #resolve}: which uuid to offer, and whether it must be written. */
	@Value
	static class Pick {
		String uuid;
		boolean rewrite;
	}

	private final GachaStateService stateService;
	private final WeeklyShopService weeklyShop;
	private final ServiceRecordService serviceRecords;
	private final LoadoutService loadoutService;
	private final CardDatabase cardDatabase;
	private final ConfigManager configManager;

	@Setter private TollPullDealer dealer;

	/**
	 * Borrowed from the weekly shop rather than re-derived so there is ONE ISO
	 * week boundary in the plugin. Two transcriptions of the same calendar rule
	 * would eventually disagree at a year edge, and the two features would roll
	 * over on different days for the same player.
	 */
	public String currentWeekKey() { return weeklyShop.currentWeekKey(); }

	/**
	 * True once this week's Toll has been paid — terminal until the week turns.
	 *
	 * <p>Read together with {@link #currentOffer()} this is a THREE-way state, not
	 * a boolean, and a panel needs all three to write the right line:
	 *
	 * <table>
	 * <caption>What the pair means</caption>
	 * <tr><th>currentOffer()</th><th>paidThisWeek()</th><th>the player is told</th></tr>
	 * <tr><td>non-null</td><td>false</td><td>the live offer — render the card and the
	 * button, disabled with {@link TollOffer#getRefusal()} when that is non-null</td></tr>
	 * <tr><td>null</td><td>true</td><td>"paid" — comes back next week</td></tr>
	 * <tr><td>null</td><td>false</td><td>no card in the album has a Service Record
	 * yet, so the house has nothing to ask for. This one arrives on its own the
	 * moment a card earns its first kill; it is not an error and not a wait for
	 * the week to turn.</td></tr>
	 * </table>
	 *
	 * <p>(non-null + true cannot happen: a paid week offers nothing.) The third row
	 * is the reason this method exists at all — without it the panel would have to
	 * infer "nothing eligible" from a null offer, which is exactly the re-derivation
	 * {@link TollOffer} is shaped to spare it.
	 */
	public boolean paidThisWeek() {
		var state = stateService.get();
		return state != null && currentWeekKey().equals(state.getTollWeekKey()) && state.getTollCardUuid() == null;
	}

	/**
	 * This week's offer, or null when there is none (already paid, or the album
	 * holds nothing with a Service Record yet).
	 *
	 * <p>A read that can write: the first call of a new week persists the pick it
	 * just made, because a pick that lived only in memory would be re-drawn from a
	 * grown album after any restart — the exact drift this whole class exists to
	 * prevent. Every later call in the same week finds the stored uuid owned and
	 * writes nothing, so the panel may poll this freely.
	 *
	 * <p>Deliberately NOT synchronized, unlike {@link #purchase()}, for the reason
	 * ChestService gives for exposing {@code pending} through a volatile field
	 * rather than a synchronized getter: this is what the shop panel calls on every
	 * repaint, and putting the EDT behind the monitor a purchase holds while it
	 * rolls a chest, rewrites state and fans out to every listener is a UI stall
	 * bought for nothing. It also removes the only lock-ordering hazard this class
	 * could have had — a state listener firing under GachaStateService's monitor
	 * can reach this method, and if it blocked here while purchase() held this
	 * monitor and waited for that one, the two would deadlock.
	 *
	 * <p>The atomicity given up is nil: the state snapshot is read ONCE into a
	 * local and GachaState is deeply immutable, so there is no compound read to
	 * tear. Two callers racing on the very first call of a week can both re-pick,
	 * and the loser's write is overwritten by an identical one — the seed is
	 * deterministic and the album has not moved between them.
	 */
	public TollOffer currentOffer() {
		var state = stateService.get();
		if (state == null || !cardDatabase.isReady()) { return null; }
		final String weekKey = currentWeekKey();
		// the album is read ONCE out of the snapshot and handed to both the pick and
		// the lookup below, which is what the "read out of the snapshot" note used to
		// say twice: the mutate in between touches only the two Toll fields, so the
		// card list under it is still the one the pick was made from
		var owned = state.getOwnedCards();
		var pick = resolve(weekKey, state.getTollWeekKey(), state.getTollCardUuid(), owned, serviceRecords::pendingFor,
			seedFor(configManager.getRSProfileKey(), weekKey));
		// one read of the picked uuid, which also gives the mutate below the
		// effectively-final capture it needs without a second local for it
		var uuid = pick.getUuid();
		if (uuid == null) { return null; }
		if (pick.isRewrite()) { stateService.mutate(s -> s.withTollWeekKey(weekKey).withTollCardUuid(uuid)); }
		var card = findByUuid(owned, uuid);
		if (card == null) { return null; }
		var def = cardDatabase.card(card.getCardId());
		// uuid rather than card.getUuid(): findByUuid matched on it, so they are the
		// same string and the second call was a re-derivation of a value in hand
		var slot = assignedSlot(state.getLoadout(), uuid);
		return new TollOffer(card, loadoutService.displayName(card), def == null ? null : def.getTierKey(),
			slot, slot == null ? null : String.format(ASSIGNED_REFUSAL, slot.getDisplayName()));
	}

	/**
	 * Pay the Toll: hand over the named card, take a blind pull of its tier.
	 * Returns null and takes NOTHING when there is no offer, when the card is
	 * still assigned, or when the pull could not be dealt.
	 *
	 * <p>Order is deliberate — deal FIRST, consume SECOND. The two writes cannot
	 * be folded into one (the blob write lives inside ChestService), so one of
	 * them has to be exposed to a crash, and this order picks the direction that
	 * favours the player: a client that dies in the gap finds the paid-for pull in
	 * the pending blob and commits it on the next login, while the card was never
	 * taken and the Toll is still live. The reverse order would take the card and
	 * hand back nothing. Consuming the card and marking the week paid ARE one
	 * mutate, so no crash can leave the card gone with the Toll still armed.
	 *
	 * <p>Synchronized because it reads the offer and then acts on it; two clicks
	 * landing together must not both pay. This is the ONLY method that takes this
	 * monitor — see the note on {@link #currentOffer()} — so nothing else can be
	 * blocked behind a purchase, and the locks it then takes (ChestService's, then
	 * GachaStateService's) are only ever acquired in that order: neither of those
	 * services knows the Toll exists, so no path runs the sequence backwards.
	 */
	public synchronized ChestService.ChestOpenResult purchase() {
		var offer = currentOffer();
		if (offer == null || offer.getRefusal() != null) { return null; }
		// one read of the hook field, so a setDealer landing mid-purchase cannot
		// leave this method half-wired to two different dealers
		var pull = dealer;
		if (pull == null) { log.warn("Toll purchase attempted with no pull dealer wired; nothing taken"); return null; }
		final String uuid = offer.getCard().getUuid();
		var result = pull.dealTollPull(offer.getTierKey(), uuid);
		if (result == null) { return null; }
		final String weekKey = currentWeekKey();
		stateService.mutate(s -> {
			var owned = new ArrayList<>(s.getOwnedCards());
			owned.removeIf(c -> uuid.equals(c.getUuid()));
			// the null uuid under the CURRENT week key is what marks the Toll paid;
			// the week key is rewritten alongside it so a purchase that somehow
			// straddles midnight on Sunday cannot mark the wrong week
			return s.withOwnedCards(owned).withTollWeekKey(weekKey).withTollCardUuid(null);
		});
		// a card surrendered and a chest earned is precisely the kind of event a
		// player would resent losing to a client that dies before the 1s debounce
		stateService.checkpoint();
		return result;
	}

	// --- pure rules ---

	/**
	 * Which uuid the Toll should name, and whether the caller must persist it.
	 *
	 * <p>The order of the two same-week checks is the whole anti-re-arm rule: a
	 * null uuid means PAID and returns before the ownership test, so consuming the
	 * card cannot be mistaken for "the named card is gone, pick another" and hand
	 * out a second free Toll in the same week.
	 *
	 * <p>An empty eligible pool writes NOTHING and offers nothing. Recording the
	 * week key with a null uuid would be indistinguishable from PAID and would
	 * suppress the Toll for the rest of the week — including for a player whose
	 * first card earns its first kill on the Tuesday. Leaving the stale fields
	 * untouched costs one retry per call and is always self-correcting.
	 */
	static Pick resolve(String weekKey, String storedWeekKey, String storedUuid,
		List<OwnedCard> owned, ToIntFunction<String> pendingFor, long seed) {
		if (weekKey.equals(storedWeekKey)) {
			if (storedUuid == null) { return new Pick(null, false); }
			if (findByUuid(owned, storedUuid) != null) { return new Pick(storedUuid, false); }
		}
		var pool = eligible(owned, pendingFor);
		if (pool.isEmpty()) { return new Pick(null, false); }
		return new Pick(pool.get(new Random(seed).nextInt(pool.size())).getUuid(), true);
	}

	/**
	 * Every card the Toll may name, in a stable order.
	 *
	 * <p>Sorted by uuid for the same reason the weekly shop sorts by cardId: the
	 * album's own list order follows acquisition and a seeded index into an
	 * unsorted list would be reproducible only by accident.
	 *
	 * <p>Assignment to a loadout slot is deliberately NOT part of this test. An
	 * assigned card is fully eligible — the player unassigns it first — and
	 * filtering assigned cards out here would quietly shrink the pool to whatever
	 * the player happens not to be wearing.
	 */
	static List<OwnedCard> eligible(List<OwnedCard> owned, ToIntFunction<String> pendingFor) {
		if (owned == null) { return new ArrayList<>(); }
		// the filter-then-sort the loop did by hand. sorted() on an ordered stream is
		// stable, exactly as List.sort was, so equal uuids keep album order — and the
		// sort still happens AFTER the filter, which is what keeps the seeded index
		// pointing at the same card the loop form would have handed back
		return owned.stream().filter(c -> isEligible(c, pendingFor.applyAsInt(c.getUuid())))
			.sorted(Comparator.comparing(OwnedCard::getUuid)).collect(Collectors.toList());
	}

	/**
	 * A card with a Service Record on it, and not a hologram. Holograms represent
	 * a whole tier and are the rarest pull in the game; the house does not ask for
	 * one.
	 *
	 * <p>{@code pendingKills} is {@link ServiceRecordService#pendingFor} — the
	 * kills banked since the last flush. Without it a card that earned its very
	 * first kills this session would still read as junk, because the record does
	 * not reach {@code killsServed} until a contract completes or the client shuts
	 * down cleanly.
	 *
	 * <p>That tally is transient, and the consequence is deliberate: a hard crash
	 * loses it, so a pick made on pending kills alone can survive into a week where
	 * the named card reads {@code killsServed == 0}. The Toll does NOT re-pick on
	 * that — the only re-pick triggers are the week turning and the card being
	 * gone. A pick that re-rolled because a number moved underneath it would be
	 * the very instability this class is built to prevent, and the player is owed
	 * the card they were shown.
	 */
	static boolean isEligible(OwnedCard card, int pendingKills) { return !card.isHologram() && (card.getKillsServed() + pendingKills) > 0; }

	/**
	 * Personal and week-stable, mixed the same way {@link WeeklyShopService} mixes
	 * its own seed — same splitmix64, one shared implementation — but salted so
	 * the two features cannot draw in lockstep.
	 */
	static long seedFor(String profileKey, String weekKey) {
		return WeeklyShopService.splitmix64((profileKey == null ? 0 : profileKey.hashCode()) * 31L + weekKey.hashCode() + SEED_SALT);
	}

	/**
	 * The loadout slot holding this uuid, or null. Walks the enum rather than the
	 * map so the answer is deterministic; only holograms can occupy two slots at
	 * once and they are never named by the Toll.
	 */
	static GearSlot assignedSlot(Map<String, String> loadout, String uuid) {
		if (loadout == null) { return null; }
		for (GearSlot slot : GearSlot.values()) { if (uuid.equals(loadout.get(slot.name()))) { return slot; } }
		return null;
	}

	/**
	 * The named card out of an album that may be null. Null-tolerant on both the
	 * list and its contents, unlike {@code LoadoutService}'s same-named private
	 * helper, which dereferences the card first — the two cannot be merged without
	 * changing one of the two null contracts, so they stay apart deliberately.
	 */
	private static OwnedCard findByUuid(List<OwnedCard> owned, String uuid) {
		if (owned == null) { return null; }
		for (OwnedCard card : owned) { if (uuid.equals(card.getUuid())) { return card; } }
		return null;
	}
}
