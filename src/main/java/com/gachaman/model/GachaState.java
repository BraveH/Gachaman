package com.gachaman.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * The whole persisted Gachaman state for one RS profile. Immutable — every
 * mutation goes through GachaStateService which swaps the volatile snapshot.
 */
@Value
@With
@Builder(toBuilder = true)
public class GachaState {
	public static final int SCHEMA_VERSION = 1;

	// --- Collection ---
	List<OwnedCard> ownedCards;
	/** GearSlot name -> owned card uuid assigned to that loadout slot. */
	Map<String, String> loadout;
	/** GearSlot names unlocked by Deeds (WEAPON and BODY seeded). */
	Set<String> deededSlots;

	// --- Economy ---
	long gc;
	long lifetimeGcEarned;
	int taint;
	String armedCharge; // "COMPACTOR" | "EXTENDER" | null
	int rerollTokens;
	int combatLevelBaseline; // CB when profile first seen
	int lastTokenCombatLevel; // last CB bracket a token was awarded for

	// --- Style ---
	String allowedStyle; // AttackStyle name; null until first roll
	double cycleProgress;
	int cycleTarget;
	long styleRolledAtMs;

	// --- Tasks ---
	ActiveTask activeTask;
	List<TaskOffer> pendingOffers;
	Map<String, Integer> tasksCompletedByDifficulty;
	int totalTasksCompleted;
	int deedMilestonesClaimed;
	/** Deeds granted but not yet spent on a slot choice. */
	int pendingDeeds;

	// --- Pity / chests ---
	int opensSinceEpic;
	Map<String, Integer> chestsOpenedByTier;
	/** Gson-serialized pending ChestOpenResult (crash recovery for the deferred commit). */
	String pendingChestBlob;

	// --- Journal ---
	Map<String, MonsterStats> monsterStats;
	Map<String, PersonalBest> personalBests; // key: TaskDifficulty name

	// --- Meta ---
	Set<String> completedSets;
	/** weekKey ("2026-W32") -> set of purchased shop slot indexes. */
	Map<String, Set<Integer>> weeklyShopPurchases;
	/** "bossSetTag:milestone" claims, e.g. "zulrah:50". */
	Set<String> bossKcClaims;
	/** Queued free themed chests: setTag list, opened via the shop panel. */
	List<String> queuedThemedChests;

	// --- Early game ---
	/** One-time Firsts Journal stamps already claimed (FirstsService keys). */
	Set<String> firstsClaimed;
	/** Bestiary: species discovered via on-task kills (lowercased names). */
	Set<String> speciesDiscovered;
	/** Graduation: GearSlot name -> best tier rank ever worn in that slot. */
	Map<String, Integer> slotBestTierRank;
	/** Deed Fragments earned during the first-ten-tasks window. */
	int deedFragments;
	/** The one-per-account fragment deed has been forged. */
	boolean fragmentDeedForged;
	/** Stardust banked from shiny near-misses. */
	int stardust;
	/** Next chest opens stardust-blessed (double shiny attempts per card). */
	boolean stardustBlessArmed;
	/** Free style-charge vouchers (consumed before GC when buying). */
	int freeCompactors;
	int freeExtenders;
	/** One-shot onboarding voucher grant already applied. */
	boolean starterVouchersGranted;
	/**
	 * The free First Colours chest that rides behind an account's very first
	 * style roll has been earned but not yet dealt. Phrased as OWED, never as
	 * DONE: a save written before this field existed deserializes it as false,
	 * and false must mean "nothing owed". A "firstColoursDone" flag would
	 * deserialize as "not done" and retro-gift a free chest to every
	 * established account on its next login.
	 */
	boolean firstColoursChestOwed;
	/**
	 * The Tutorial Island clean-slate strip has been settled. Set on leaving the
	 * island (after stripping), and set immediately for any save that first
	 * loads already past the tutorial — installing the plugin later must never
	 * retroactively undress an established account.
	 */
	boolean tutorialStripDone;
	/**
	 * The strip was started but has not finished taking everything off — a logout
	 * or a full inventory interrupted it. Only a save that genuinely stepped off
	 * the island ever sets this, so resuming on the next login cannot undress an
	 * account that installed the plugin later.
	 */
	boolean tutorialStripPending;
	/** Fortune timeline: chronological audit of rolls/pulls/equips (capped). */
	List<TimelineEvent> timeline;
	/**
	 * The Patron's Mark: shared party contracts finished with each partner,
	 * keyed by ACCOUNT KEY (see AccountKey) with the display name demoted to a
	 * refreshable label inside the record. Not by member id — RuneLite draws a
	 * party memberId from a fresh Random on every process start AND again
	 * inside changeParty(), so an id-keyed tally would restart at zero every
	 * login and could never reach the higher tiers; and not by display name,
	 * which forks a partner's history in half the day they rename.
	 *
	 * Deliberately NOT the old {@code partnerContracts} field name. That one
	 * held a Map&lt;String,Integer&gt;, and reusing the name would hand Gson a
	 * bare number where it now expects an object — a parse failure that takes
	 * the WHOLE save with it, not just this field. A new name means Gson drops
	 * the old key as unknown and starts this ledger empty, which is what a
	 * pre-release rename should cost. Cosmetic only; see PatronMark.
	 */
	Map<String, PatronRecord> patrons;
	/**
	 * The Contract Dossier: one compact record per completed contract, oldest
	 * dropped past Tuning.DOSSIER_MAX_RECORDS. Capped separately from and far
	 * below the timeline because a record is a struct, not a line, and every
	 * mutate re-gzips and re-hashes the whole state.
	 */
	List<ContractRecord> contractLog;

	public static GachaState fresh(int combatLevel) {
		// weapon + body + ammo: melee AND ranged are trainable from the start
		Set<String> deeded = new HashSet<>();
		deeded.add(GearSlot.WEAPON.name());
		deeded.add(GearSlot.BODY.name());
		deeded.add(GearSlot.AMMO.name());
		return GachaState.builder()
			.ownedCards(new ArrayList<>())
			.loadout(new HashMap<>())
			.deededSlots(deeded)
			.gc(0)
			.lifetimeGcEarned(0)
			.taint(0)
			.armedCharge(null)
			.rerollTokens(0)
			.combatLevelBaseline(combatLevel)
			.lastTokenCombatLevel(combatLevel)
			.allowedStyle(null)
			.cycleProgress(0)
			.cycleTarget(0)
			.styleRolledAtMs(0)
			.activeTask(null)
			.pendingOffers(new ArrayList<>())
			.tasksCompletedByDifficulty(new HashMap<>())
			.totalTasksCompleted(0)
			.deedMilestonesClaimed(0)
			.pendingDeeds(0)
			.opensSinceEpic(0)
			.chestsOpenedByTier(new HashMap<>())
			.pendingChestBlob(null)
			.monsterStats(new HashMap<>())
			.personalBests(new HashMap<>())
			.completedSets(new HashSet<>())
			.weeklyShopPurchases(new HashMap<>())
			.bossKcClaims(new HashSet<>())
			.queuedThemedChests(new ArrayList<>())
			.firstsClaimed(new HashSet<>())
			.speciesDiscovered(new HashSet<>())
			.slotBestTierRank(new HashMap<>())
			.deedFragments(0)
			.fragmentDeedForged(false)
			.stardust(0)
			.stardustBlessArmed(false)
			.freeCompactors(0)
			.freeExtenders(0)
			.starterVouchersGranted(false)
			.firstColoursChestOwed(false)
			.tutorialStripDone(false)
			.tutorialStripPending(false)
			.timeline(new ArrayList<>())
			.patrons(new HashMap<>())
			.contractLog(new ArrayList<>())
			.build();
	}

	/**
	 * Saves written before a field existed deserialize it as null; returns a
	 * copy with every null collection replaced by an empty one so consumers
	 * never need per-site guards. Applied once at load.
	 */
	public GachaState normalized() {
		GachaStateBuilder b = toBuilder();
		if (ownedCards == null) {
			b.ownedCards(new ArrayList<>());
		}
		if (loadout == null) {
			b.loadout(new HashMap<>());
		}
		if (deededSlots == null) {
			b.deededSlots(new HashSet<>());
		}
		if (pendingOffers == null) {
			b.pendingOffers(new ArrayList<>());
		}
		if (tasksCompletedByDifficulty == null) {
			b.tasksCompletedByDifficulty(new HashMap<>());
		}
		if (chestsOpenedByTier == null) {
			b.chestsOpenedByTier(new HashMap<>());
		}
		if (monsterStats == null) {
			b.monsterStats(new HashMap<>());
		}
		if (personalBests == null) {
			b.personalBests(new HashMap<>());
		}
		if (completedSets == null) {
			b.completedSets(new HashSet<>());
		}
		if (weeklyShopPurchases == null) {
			b.weeklyShopPurchases(new HashMap<>());
		}
		if (bossKcClaims == null) {
			b.bossKcClaims(new HashSet<>());
		}
		if (queuedThemedChests == null) {
			b.queuedThemedChests(new ArrayList<>());
		}
		if (firstsClaimed == null) {
			b.firstsClaimed(new HashSet<>());
		}
		if (speciesDiscovered == null) {
			b.speciesDiscovered(new HashSet<>());
		}
		if (slotBestTierRank == null) {
			b.slotBestTierRank(new HashMap<>());
		}
		if (timeline == null) {
			b.timeline(new ArrayList<>());
		}
		if (patrons == null) {
			b.patrons(new HashMap<>());
		}
		if (contractLog == null) {
			b.contractLog(new ArrayList<>());
		}
		return b.build();
	}
}
