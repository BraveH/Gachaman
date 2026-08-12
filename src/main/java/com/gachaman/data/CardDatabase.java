package com.gachaman.data;

import com.gachaman.model.AttackStyle;
import com.gachaman.model.GearSlot;
import com.gachaman.model.Rarity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * The full card database, derived at runtime from the item cache so EVERY
 * equipable item in the game has a card. Built once per cache revision via a
 * chunked client-thread scan, then cached to disk.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CardDatabase {
	private static final int CHUNK_SIZE = 4000;
	private static final Pattern BARROWS_DEGRADE = Pattern.compile("\\s+(100|75|50|25|0)$");
	private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*\\([^)]*\\)$");

	private final Client client;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final Gson gson;
	private final RarityOverrides rarityOverrides;

	@Getter
	private volatile boolean ready;
	@Getter
	private volatile int scanProgressPercent;

	private volatile Map<Integer, CardDefinition> byCardId = Collections.emptyMap();
	private volatile Map<Integer, Integer> cardIdByItemId = Collections.emptyMap();
	private volatile Map<String, Integer> cardIdByName = Collections.emptyMap();
	private volatile Map<String, List<CardDefinition>> byFamily = Collections.emptyMap();
	private volatile Map<String, HologramDefinition> hologramsByTier = Collections.emptyMap();
	private volatile Map<String, List<CardDefinition>> bySetTagIndexed = Collections.emptyMap();

	private TierTable tierTable;
	private SetTable setTable;
	private final List<Runnable> readyCallbacks = new ArrayList<>();
	private List<ScannedItem> scanned;
	private int statsProbeAttempts;
	/** Known-equipable sentinel (Rune scimitar); stats loaded => scan may start. */
	private static final int PROBE_ITEM_ID = 1333;
	private static final int MAX_PROBE_ATTEMPTS = 600;
	/** Below this the item stats were clearly incomplete — do not poison the cache. */
	private static final int MIN_SANE_CARD_COUNT = 500;

	@Value
	private static class ScannedItem {
		int itemId;
		String rawName;
		int slotIndex;
		int power;
		boolean combatRelevant;
	}

	/** Zero-stat items that are genuinely combat equipment. */
	private static final Set<String> COMBAT_ALLOWLIST = new HashSet<>(Arrays.asList(
		"Chinchompa", "Red chinchompa", "Black chinchompa",
		"Training sword", "Training shield", "Training bow", "Training arrows"));

	public synchronized void onReady(Runnable callback) {
		if (ready) {
			callback.run();
		}
		else {
			readyCallbacks.add(callback);
		}
	}

	/** Kick off build (idempotent). Call after the client is logged in. */
	public void beginBuild(TierTable tiers, SetTable sets) {
		if (ready || scanned != null) {
			return;
		}
		this.tierTable = tiers;
		this.setTable = sets;

		List<CardDefinition> cached = loadCache();
		if (cached != null) {
			log.info("Gachaman card DB loaded from cache: {} cards", cached.size());
			index(cached);
			return;
		}

		scanned = new ArrayList<>(4096);
		clientThread.invokeLater(() -> scanChunk(0));
	}

	private void scanChunk(int startId) {
		if (startId == 0) {
			// item stats load asynchronously; scanning before they arrive would
			// classify everything as unequipable and poison the cache
			boolean statsReady;
			try {
				ItemStats probe = itemManager.getItemStats(PROBE_ITEM_ID);
				statsReady = probe != null && probe.isEquipable();
			}
			catch (Exception e) {
				statsReady = false;
			}
			if (!statsReady) {
				if (++statsProbeAttempts < MAX_PROBE_ATTEMPTS) {
					clientThread.invokeLater(() -> scanChunk(0));
				}
				else {
					log.warn("Gachaman: item stats never became available; card DB unavailable this session");
				}
				return;
			}
		}
		int count = client.getItemCount();
		int end = Math.min(startId + CHUNK_SIZE, count);
		for (int id = startId; id < end; id++) {
			try {
				ItemComposition comp = itemManager.getItemComposition(id);
				if (comp == null || comp.getNote() != -1 || comp.getPlaceholderTemplateId() != -1) {
					continue;
				}
				String name = comp.getName();
				if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name)) {
					continue;
				}
				ItemStats stats = itemManager.getItemStats(id);
				if (stats == null || !stats.isEquipable() || stats.getEquipment() == null) {
					continue;
				}
				int slotIndex = stats.getEquipment().getSlot();
				if (GearSlot.fromSlotIndex(slotIndex) == null) {
					continue;
				}
				int power = powerScore(stats);
				scanned.add(new ScannedItem(id, name, slotIndex, power, hasCombatStats(stats)));
			}
			catch (Exception e) {
				// individual bad entries must never kill the scan
			}
		}
		scanProgressPercent = count == 0 ? 0 : (int) (end * 100L / count);
		if (end < count) {
			clientThread.invokeLater(() -> scanChunk(end));
		}
		else {
			List<CardDefinition> cards = groupAndFinalize(scanned);
			scanned = null;
			if (cards.size() >= MIN_SANE_CARD_COUNT) {
				saveCache(cards);
			}
			else {
				log.warn("Gachaman card DB scan looks incomplete ({} cards) — using in-memory only, no cache", cards.size());
			}
			log.info("Gachaman card DB built: {} cards from {} items", cards.size(), count);
			index(cards);
		}
	}

	/**
	 * Any combat-relevant bonus at all: attack or defence in any style,
	 * melee/ranged strength, magic damage, or prayer. Pure cosmetics have
	 * none of these.
	 */
	private static boolean hasCombatStats(ItemStats stats) {
		ItemEquipmentStats e = stats.getEquipment();
		return e.getAstab() > 0 || e.getAslash() > 0 || e.getAcrush() > 0
			|| e.getAmagic() > 0 || e.getArange() > 0
			|| e.getDstab() > 0 || e.getDslash() > 0 || e.getDcrush() > 0
			|| e.getDmagic() > 0 || e.getDrange() > 0
			|| e.getStr() > 0 || e.getRstr() > 0 || e.getMdmg() > 0 || e.getPrayer() > 0;
	}

	private static int powerScore(ItemStats stats) {
		ItemEquipmentStats e = stats.getEquipment();
		int off = Math.max(Math.max(e.getAstab(), e.getAslash()),
			Math.max(Math.max(e.getAcrush(), e.getAmagic()), e.getArange()));
		int def = Math.max(Math.max(e.getDstab(), e.getDslash()),
			Math.max(Math.max(e.getDcrush(), e.getDmagic()), e.getDrange()));
		return Math.max(off, 0) + Math.max(def, 0) / 2 + Math.max(e.getStr(), 0) * 2;
	}

	private List<CardDefinition> groupAndFinalize(List<ScannedItem> items) {
		// group variants: (cleanName, slot) -> item ids
		Map<String, TreeSet<Integer>> groups = new TreeMap<>();
		Map<String, Integer> groupSlot = new HashMap<>();
		Map<String, Integer> groupPower = new HashMap<>();
		Map<String, Boolean> groupCombat = new HashMap<>();
		for (ScannedItem item : items) {
			String clean = cleanName(item.getRawName());
			if (clean.isEmpty()) {
				continue;
			}
			String key = clean + " " + item.getSlotIndex();
			groups.computeIfAbsent(key, k -> new TreeSet<>()).add(item.getItemId());
			groupSlot.put(key, item.getSlotIndex());
			groupPower.merge(key, item.getPower(), Math::max);
			groupCombat.merge(key, item.isCombatRelevant(), Boolean::logicalOr);
		}

		// first pass: create definitions with tier matches
		List<CardDefinition> cards = new ArrayList<>(groups.size());
		Map<String, Integer> familyMinRank = new HashMap<>();
		List<Object[]> pending = new ArrayList<>();
		for (Map.Entry<String, TreeSet<Integer>> entry : groups.entrySet()) {
			String clean = entry.getKey().substring(0, entry.getKey().lastIndexOf(' '));
			// cosmetic-only equipment gets no card: a card exists only when at
			// least one variant has combat stats (or the item is allowlisted).
			// Items with ANY positive stat stay — by user ruling, stat-bearing
			// novelty gear (camo sets, fish sacks...) keeps its cards.
			if (!groupCombat.getOrDefault(entry.getKey(), false)
				&& !COMBAT_ALLOWLIST.contains(clean)) {
				continue;
			}
			GearSlot slot = GearSlot.fromSlotIndex(groupSlot.get(entry.getKey()));
			TierTable.Match match = tierTable.match(clean);
			String familyKey = null;
			String tierKey = null;
			int rank = 0;
			if (match != null) {
				tierKey = match.getTierKey();
				rank = match.getRank();
				familyKey = match.getFamilyKey() + "/" + slot.name();
				familyMinRank.merge(familyKey, rank, Math::min);
			}
			pending.add(new Object[]{clean, slot, tierKey, rank, familyKey,
				entry.getValue(), groupPower.get(entry.getKey())});
		}

		// second pass: rarity + shiny eligibility need family context
		for (Object[] p : pending) {
			String clean = (String) p[0];
			GearSlot slot = (GearSlot) p[1];
			String tierKey = (String) p[2];
			int rank = (Integer) p[3];
			String familyKey = (String) p[4];
			@SuppressWarnings("unchecked")
			TreeSet<Integer> ids = (TreeSet<Integer>) p[5];
			int power = (Integer) p[6];

			Rarity rarity = rarityOverrides.lookup(clean);
			if (rarity == null) {
				rarity = tierKey != null ? rarityForRank(rank) : rarityForPower(power);
			}
			boolean shinyEligible = familyKey != null
				&& rank > familyMinRank.getOrDefault(familyKey, rank);

			cards.add(new CardDefinition(ids.first(), clean, slot, tierKey, rank,
				familyKey, rarity, Collections.unmodifiableSet(new HashSet<>(ids)), shinyEligible));
		}
		return cards;
	}

	static Rarity rarityForRank(int rank) {
		if (rank <= 2) {
			return Rarity.COMMON;
		}
		if (rank <= 5) {
			return Rarity.UNCOMMON;
		}
		if (rank <= 7) {
			return Rarity.RARE;
		}
		return Rarity.EPIC;
	}

	static Rarity rarityForPower(int power) {
		if (power < 10) {
			return Rarity.COMMON;
		}
		if (power < 35) {
			return Rarity.UNCOMMON;
		}
		if (power < 70) {
			return Rarity.RARE;
		}
		if (power < 110) {
			return Rarity.EPIC;
		}
		return Rarity.LEGENDARY;
	}

	static String cleanName(String raw) {
		String name = raw.trim();
		name = BARROWS_DEGRADE.matcher(name).replaceAll("");
		while (true) {
			String stripped = TRAILING_PAREN.matcher(name).replaceAll("");
			if (stripped.equals(name)) {
				break;
			}
			name = stripped;
		}
		return name.trim();
	}

	private synchronized void index(List<CardDefinition> cards) {
		Map<Integer, CardDefinition> byId = new HashMap<>();
		Map<Integer, Integer> byItem = new HashMap<>();
		Map<String, Integer> byName = new HashMap<>();
		Map<String, List<CardDefinition>> families = new HashMap<>();
		for (CardDefinition card : cards) {
			byId.put(card.getCardId(), card);
			byName.put(card.getName().toLowerCase(), card.getCardId());
			for (int itemId : card.getItemIds()) {
				byItem.put(itemId, card.getCardId());
			}
			if (card.getFamilyKey() != null) {
				families.computeIfAbsent(card.getFamilyKey(), k -> new ArrayList<>()).add(card);
			}
		}
		families.values().forEach(list -> list.sort(Comparator.comparingInt(CardDefinition::getTierRank)));

		// holograms: only tiers that exist on >= 2 distinct slots
		Map<String, Set<GearSlot>> slotsPerTier = new HashMap<>();
		for (CardDefinition card : cards) {
			if (card.getTierKey() != null) {
				slotsPerTier.computeIfAbsent(card.getTierKey(), k -> new HashSet<>()).add(card.getSlot());
			}
		}
		Map<String, HologramDefinition> holos = new HashMap<>();
		for (HologramDefinition holo : tierTable.getHolograms()) {
			if (slotsPerTier.getOrDefault(holo.getTierKey(), Collections.emptySet()).size() >= 2) {
				holos.put(holo.getTierKey(), holo);
			}
		}

		// set tag index: setKey -> member cards (matched by name)
		Map<String, List<CardDefinition>> setIndex = new HashMap<>();
		for (SetTable.CardSet set : setTable.getSets()) {
			List<CardDefinition> members = new ArrayList<>();
			List<String> unresolved = new ArrayList<>();
			for (String cardName : set.getCardNames()) {
				Integer cardId = byName.get(cardName.toLowerCase());
				if (cardId != null) {
					members.add(byId.get(cardId));
				}
				else {
					unresolved.add(cardName);
				}
			}
			if (!unresolved.isEmpty()) {
				// SetPerkService refuses to complete a set whose names don't all
				// resolve, so a typo or a renamed item bricks the set permanently.
				// Say so loudly — silently it looks like ordinary bad luck.
				log.warn("Gachaman: set '{}' names {} card(s) with no match in the item cache: {}",
					set.getSetKey(), unresolved.size(), String.join(", ", unresolved));
			}
			setIndex.put(set.getSetKey(), members);
		}

		byCardId = Collections.unmodifiableMap(byId);
		cardIdByItemId = Collections.unmodifiableMap(byItem);
		cardIdByName = Collections.unmodifiableMap(byName);
		byFamily = Collections.unmodifiableMap(families);
		hologramsByTier = Collections.unmodifiableMap(holos);
		bySetTagIndexed = Collections.unmodifiableMap(setIndex);
		ready = true;
		scanProgressPercent = 100;
		for (Runnable callback : readyCallbacks) {
			try {
				callback.run();
			}
			catch (Exception e) {
				log.warn("card DB ready callback failed", e);
			}
		}
		readyCallbacks.clear();
	}

	// --- Queries ---

	@Nullable
	public CardDefinition card(int cardId) {
		return byCardId.get(cardId);
	}

	@Nullable
	public CardDefinition cardForItem(int itemId) {
		Integer cardId = cardIdByItemId.get(itemId);
		return cardId == null ? null : byCardId.get(cardId);
	}

	@Nullable
	public CardDefinition cardByName(String name) {
		Integer cardId = cardIdByName.get(name.toLowerCase());
		return cardId == null ? null : byCardId.get(cardId);
	}

	/**
	 * Card ids of Common weapons whose dominant offensive bonus is this style.
	 *
	 * <p>Common is this database's own low-power band (see rarityForPower), so
	 * these are starter-grade by construction. The plugin holds no level
	 * requirements for untiered items — tiers.json ladders only metal, dhide and
	 * robes, and there is no tiered ranged or magic WEAPON in it at all — so
	 * "humble" is the closest honest stand-in for "equippable today", and the
	 * caller pairs it with the Rusty pool's reachability clamp.
	 *
	 * <p>CLIENT THREAD ONLY: stats are read live rather than stored on the card,
	 * because a style field on CardDefinition would have to bump the card-DB
	 * cache version and force every existing install into a full item rescan to
	 * answer a question that is asked once per account.
	 */
	public Set<Integer> weaponCardIdsForStyle(@Nullable AttackStyle style) {
		Set<Integer> ids = new HashSet<>();
		if (style == null) {
			return ids;
		}
		for (CardDefinition card : byCardId.values()) {
			if (card.getSlot() != GearSlot.WEAPON || card.getRarity() != Rarity.COMMON) {
				continue;
			}
			try {
				// cardId IS the lowest item id of the merged variant group, so it
				// is always a concrete item (same idiom as lowStatSuspects)
				ItemStats stats = itemManager.getItemStats(card.getCardId());
				if (stats == null || stats.getEquipment() == null) {
					continue;
				}
				ItemEquipmentStats e = stats.getEquipment();
				if (dominantStyle(e.getAstab(), e.getAslash(), e.getAcrush(),
					e.getArange(), e.getAmagic()) == style) {
					ids.add(card.getCardId());
				}
			}
			catch (Exception ex) {
				// one unreadable item must never cost the player their gift
			}
		}
		return ids;
	}

	/**
	 * Which style does this equipment actually attack with? A tie or no offence
	 * at all returns null: gear that is not unambiguously one style's weapon is
	 * not a promise worth making. Melee is the max of its three attack types
	 * because an item only ever swings with one of them.
	 */
	@Nullable
	static AttackStyle dominantStyle(int astab, int aslash, int acrush,
		int arange, int amagic) {
		int melee = Math.max(astab, Math.max(aslash, acrush));
		int best = Math.max(melee, Math.max(arange, amagic));
		if (best <= 0) {
			return null;
		}
		int winners = 0;
		if (melee == best) {
			winners++;
		}
		if (arange == best) {
			winners++;
		}
		if (amagic == best) {
			winners++;
		}
		if (winners != 1) {
			return null;
		}
		if (melee == best) {
			return AttackStyle.MELEE;
		}
		return arange == best
			? AttackStyle.RANGED
			: AttackStyle.MAGIC;
	}

	public Map<Integer, CardDefinition> all() {
		return byCardId;
	}

	public List<CardDefinition> family(String familyKey) {
		return byFamily.getOrDefault(familyKey, Collections.emptyList());
	}

	public Map<String, HologramDefinition> holograms() {
		return hologramsByTier;
	}

	public List<CardDefinition> setMembers(String setKey) {
		return bySetTagIndexed.getOrDefault(setKey, Collections.emptyList());
	}

	/**
	 * Audit helper (::gachacosmetics): card names whose canonical item's total
	 * positive bonuses are at or below the threshold — likely novelty gear
	 * that slipped the filter. Untiered cards only (tiered gear is legit by
	 * construction); allowlisted names skipped.
	 */
	public List<String> lowStatSuspects(int maxTotalBonus) {
		List<String> suspects = new ArrayList<>();
		for (CardDefinition card : byCardId.values()) {
			if (card.getTierKey() != null || COMBAT_ALLOWLIST.contains(card.getName())) {
				continue;
			}
			try {
				ItemStats stats = itemManager.getItemStats(card.getCardId());
				if (stats == null || stats.getEquipment() == null) {
					continue;
				}
				ItemEquipmentStats e = stats.getEquipment();
				int total = Math.max(0, e.getAstab()) + Math.max(0, e.getAslash())
					+ Math.max(0, e.getAcrush()) + Math.max(0, e.getAmagic())
					+ Math.max(0, e.getArange())
					+ Math.max(0, e.getDstab()) + Math.max(0, e.getDslash())
					+ Math.max(0, e.getDcrush()) + Math.max(0, e.getDmagic())
					+ Math.max(0, e.getDrange())
					+ Math.max(0, e.getStr()) + Math.max(0, e.getRstr())
					+ Math.max(0, e.getPrayer()) + (int) Math.max(0, e.getMdmg());
				if (total <= maxTotalBonus) {
					suspects.add(card.getName() + " (+" + total + ")");
				}
			}
			catch (Exception ex) {
				// skip unreadable entries
			}
		}
		Collections.sort(suspects, String.CASE_INSENSITIVE_ORDER);
		return suspects;
	}

	// --- Cache ---

	private File cacheFile() {
		// v5: "Runite" joins the rune tier's prefixes — "Runite bolts" is the one
		//     equipable item spelt the long way, and it was landing untiered
		//     (rarity by power, gated by nothing at all);
		// v4: rarity audit overrides + barrows degrade-suffix merge (the v3
		//     cache predated the "Ahrim's staff 100" name cleaning and held
		//     112 stale duplicate cards — the rebuild folds them away);
		// v3: black/white prefix family exclusions (Black mask etc. untiered);
		// v2: cosmetic-only equipment filtered out. Old caches must not load.
		return new File(new File(RuneLite.RUNELITE_DIR, "gachaman"),
			"carddb-v5-rev" + client.getRevision() + ".json.gz");
	}

	@Nullable
	private List<CardDefinition> loadCache() {
		File f = cacheFile();
		if (!f.exists()) {
			return null;
		}
		try (InputStreamReader r = new InputStreamReader(
			new GZIPInputStream(Files.newInputStream(f.toPath())), StandardCharsets.UTF_8)) {
			List<CardDefinition> cards = gson.fromJson(r,
				new TypeToken<List<CardDefinition>>() {
				}.getType());
			return cards == null || cards.isEmpty() ? null : cards;
		}
		catch (Exception e) {
			log.warn("Failed to read card DB cache", e);
			return null;
		}
	}

	private void saveCache(List<CardDefinition> cards) {
		File f = cacheFile();
		try {
			File dir = f.getParentFile();
			if (!dir.exists() && !dir.mkdirs()) {
				return;
			}
			try (Writer w = new OutputStreamWriter(
				new GZIPOutputStream(Files.newOutputStream(f.toPath())), StandardCharsets.UTF_8)) {
				gson.toJson(cards, w);
			}
		}
		catch (IOException e) {
			log.warn("Failed to write card DB cache", e);
		}
	}
}
