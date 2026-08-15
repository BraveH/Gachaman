package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import java.util.*;
import java.util.stream.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * Detects set completion on collection changes and applies permanent perks as
 * a CreditSink modifier. Hologram cards count as every member card of their
 * tier for completion purposes (wildcard rule for tier sets).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SetPerkService implements GachaStateService.Listener {
	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final CardDatabase cardDatabase;
	private final SetTable setTable;
	private final CeremonyBus ceremonyBus;

	private final CreditSink.Modifier perkModifier = this::perkFactor;

	public void start() {
		stateService.addListener(this);
		creditSink.registerModifier(perkModifier);
	}

	public void stop() {
		stateService.removeListener(this);
		creditSink.unregisterModifier(perkModifier);
	}

	@Override
	public void onStateChanged(GachaState state) {
		if (!cardDatabase.isReady())
			return;
		Set<Integer> ownedIds = state.getOwnedCards().stream()
			.filter(c -> !c.isHologram())
			.map(OwnedCard::getCardId)
			.collect(Collectors.toSet());
		Set<String> ownedHoloTiers = state.getOwnedCards().stream()
			.filter(OwnedCard::isHologram)
			.map(OwnedCard::getTierKey)
			.collect(Collectors.toSet());

		// Everything found in ONE pass, banked in ONE mutate.
		//
		// Mutating inside the loop re-enters this method — mutate() notifies its
		// listeners synchronously and this class is one of them — and the nested pass
		// walks the WHOLE table again against the new state, banking any later set it
		// finds. The outer loop then reaches that same set still holding its stale
		// `state` snapshot, which does not know the set is done, and completes it a
		// second time: a second LARGE modal, and a second save (withCompletedSets
		// returns a new object even when the key is already in it, so mutate's
		// identity check does not catch it). It takes two sets finishing on one card,
		// which is precisely what a mega-set is built to do.
		Set<String> newKeys = new HashSet<>();
		List<CeremonyBus.Fanfare> fanfares = new ArrayList<>();
		for (SetTable.CardSet set : setTable.getSets()) {
			if (state.getCompletedSets().contains(set.getSetKey()))
				continue;
			List<CardDefinition> members = cardDatabase.setMembers(set.getSetKey());
			if (members.isEmpty() || members.size() < set.getCardNames().size()) {
				continue; // some names failed to resolve; never complete a partial mapping
			}
			boolean complete = members.stream().allMatch(m ->
				ownedIds.contains(m.getCardId())
					|| (m.getTierKey() != null && ownedHoloTiers.contains(m.getTierKey())));
			if (complete) {
				newKeys.add(set.getSetKey());
				fanfares.add(new CeremonyBus.Fanfare(
					CeremonyBus.Fanfare.Size.LARGE, "Set complete: " + set.getName(),
					perkDescription(set.getPerk()), members.get(0).getCardId()));
				log.debug("Set completed: {}", set.getSetKey());
			}
		}
		if (newKeys.isEmpty())
			return;
		// This one still re-enters, and is meant to: the nested pass sees every key
		// already banked and finds nothing, which is the fixed point we want.
		stateService.mutate(s -> {
			Set<String> done = new HashSet<>(s.getCompletedSets());
			done.addAll(newKeys);
			return s.withCompletedSets(done);
		});
		for (CeremonyBus.Fanfare fanfare : fanfares) {
			ceremonyBus.submit(CeremonyBus.Type.FANFARE, fanfare);
		}
	}

	double perkFactor(CreditSink.GcContext context) {
		GachaState state = stateService.get();
		if (state == null || state.getCompletedSets().isEmpty())
			return 1.0;
		double factor = 1.0;
		for (SetTable.CardSet set : setTable.getSets()) {
			if (!state.getCompletedSets().contains(set.getSetKey()))
				continue;
			SetTable.Perk perk = set.getPerk();
			if (perk == null || !typeMatches(perk.getType(), context.getSource()))
				continue;
			if (scopeMatches(perk, context)) {
				factor *= 1.0 + perk.getMagnitudePercent() / 100.0;
			}
		}
		return factor;
	}

	private static boolean typeMatches(SetTable.PerkType type, CreditSink.Source source) {
		switch (type) {
			case KILL_GC_PERCENT:
				return source == CreditSink.Source.KILL;
			case COMPLETION_GC_PERCENT:
				return source == CreditSink.Source.TASK_COMPLETION;
			case SIDEBET_GC_PERCENT:
				return source == CreditSink.Source.SIDE_BET;
			default:
				return false;
		}
	}

	private static boolean scopeMatches(SetTable.Perk perk, CreditSink.GcContext context) {
		switch (perk.getScope()) {
			case GLOBAL:
				return true;
			case MONSTER_NAME_SET:
				return context.getMonsterName() != null && perk.getScopeValues() != null
					&& perk.getScopeValues().stream()
						.anyMatch(n -> n.equalsIgnoreCase(context.getMonsterName()));
			case CATEGORY_TAG:
				return context.getMonsterTags() != null && perk.getScopeValues() != null
					&& perk.getScopeValues().stream().anyMatch(context.getMonsterTags()::contains);
			default:
				return false;
		}
	}

	public static String perkDescription(SetTable.Perk perk) {
		if (perk == null)
			return "";
		String what;
		switch (perk.getType()) {
			case KILL_GC_PERCENT:
				what = "kill GC";
				break;
			case COMPLETION_GC_PERCENT:
				what = "contract completion GC";
				break;
			default:
				what = "side bet GC";
		}
		String scope = perk.getScope() == SetTable.PerkScope.GLOBAL ? "" : " (scoped)";
		return "+" + perk.getMagnitudePercent() + "% " + what + scope + ", permanently";
	}
}
