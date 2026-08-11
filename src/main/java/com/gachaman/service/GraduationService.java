package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.model.GachaState;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Graduation Ceremony: the first time each gear slot's WORN tier rank climbs
 * (bronze to iron, iron to steel...) a fanfare fires with a small GC grant —
 * but only while the new rank stays within the early band. The first rank
 * ever recorded for a slot is a silent baseline, so equipping starter bronze
 * (or logging in on an endgame account) celebrates nothing.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GraduationService {
	private final Client client;
	private final ClientThread clientThread;
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CreditSink creditSink;
	private final CeremonyBus ceremonyBus;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() != InventoryID.WORN) {
			return;
		}
		process(event.getItemContainer());
	}

	/**
	 * Post-load catch-up: equipment worn at login may never fire a container
	 * change, so the plugin calls this once the card DB is ready to seed the
	 * baseline (silently, when a slot has no recorded rank yet).
	 */
	public void refresh() {
		clientThread.invokeLater(() -> process(client.getItemContainer(InventoryID.WORN)));
	}

	private void process(ItemContainer container) {
		GachaState state = stateService.get();
		if (container == null || state == null || state.getSlotBestTierRank() == null
			|| !cardDatabase.isReady()) {
			return;
		}
		Map<String, Integer> best = state.getSlotBestTierRank();
		Map<String, Integer> updates = new HashMap<>();
		// slot name -> the card that caused a CELEBRATED rank-up (not baselines)
		Map<String, CardDefinition> graduations = new HashMap<>();
		for (Item item : container.getItems()) {
			if (item == null || item.getId() <= 0) {
				continue;
			}
			CardDefinition def = cardDatabase.cardForItem(item.getId());
			if (def == null || def.getSlot() == null || def.getTierKey() == null
				|| def.getTierRank() <= 0) {
				continue; // untiered gear never graduates
			}
			String slotKey = def.getSlot().name();
			Integer prev = updates.containsKey(slotKey) ? updates.get(slotKey) : best.get(slotKey);
			if (prev == null) {
				updates.put(slotKey, def.getTierRank()); // silent baseline
				continue;
			}
			if (def.getTierRank() > prev) {
				updates.put(slotKey, def.getTierRank());
				if (def.getTierRank() <= Tuning.GRADUATION_MAX_RANK) {
					graduations.put(slotKey, def);
				}
			}
		}
		if (updates.isEmpty()) {
			return;
		}
		stateService.mutate(s -> {
			if (s.getSlotBestTierRank() == null) {
				return s;
			}
			Map<String, Integer> map = new HashMap<>(s.getSlotBestTierRank());
			for (Map.Entry<String, Integer> entry : updates.entrySet()) {
				Integer existing = map.get(entry.getKey());
				if (existing == null || entry.getValue() > existing) {
					map.put(entry.getKey(), entry.getValue());
				}
			}
			return s.withSlotBestTierRank(map);
		});
		for (Map.Entry<String, CardDefinition> entry : graduations.entrySet()) {
			CardDefinition def = entry.getValue();
			creditSink.award(Tuning.GRADUATION_GC,
				new CreditSink.GcContext(CreditSink.Source.GRADUATION, null, null));
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
				CeremonyBus.Fanfare.Size.MEDIUM, "Tier up: " + def.getName(),
				"Your " + def.getSlot().getDisplayName().toLowerCase()
					+ " gear graduates to " + def.getTierKey() + " — +" + Tuning.GRADUATION_GC + " GC",
				def.getCardId()));
		}
	}
}
