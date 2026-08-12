package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;

/**
 * The Firsts Journal: listens across the task/chest/compliance layers and
 * claims each {@link FirstStamp} exactly once, paying a small bounty with a
 * fanfare whose detail line teaches the rule that was just touched.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class FirstsService implements TaskService.Listener, ComplianceService.Listener,
	ChestService.ChestListener {
	private final GachaStateService stateService;
	private final CreditSink creditSink;
	private final CeremonyBus ceremonyBus;
	@Nullable
	private final Client client;

	/** Claim-once core. Returns true when newly claimed. */
	public synchronized boolean claim(FirstStamp stamp) {
		GachaState state = stateService.get();
		if (state == null || alreadyClaimed(state.getFirstsClaimed(), stamp)) {
			return false;
		}
		// stamp + bounty land in ONE mutate: an unload/profile switch can
		// never mark the once-ever stamp claimed while swallowing its GC
		CreditSink.AwardResult result = creditSink.awardWith(Tuning.FIRSTS_GC.get(stamp),
			new CreditSink.GcContext(CreditSink.Source.FIRST, null, null),
			s -> {
				Set<String> next = s.getFirstsClaimed() == null
					? new HashSet<>() : new HashSet<>(s.getFirstsClaimed());
				return next.add(stamp.name()) ? s.withFirstsClaimed(next) : s;
			});
		if (result.getState() == null || result.getState().getFirstsClaimed() == null
			|| !result.getState().getFirstsClaimed().contains(stamp.name())) {
			return false;
		}
		ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
			CeremonyBus.Fanfare.Size.SMALL, "First: " + stamp.getDisplayName(),
			stamp.getExplainer() + "  +" + result.getAmount() + " GC", null));
		return true;
	}

	static boolean alreadyClaimed(@Nullable Set<String> claimed, FirstStamp stamp) {
		return claimed != null && claimed.contains(stamp.name());
	}

	/**
	 * Pure mapper for chest commits: which stamps do these revealed slots
	 * touch? EPIC matches Epic-or-better (the pity-reset rule its explainer
	 * teaches); holograms count for rarity and dupes but never shiny.
	 */
	static List<FirstStamp> stampsForSlots(List<ChestService.RolledSlot> slots) {
		List<FirstStamp> stamps = new ArrayList<>();
		for (ChestService.RolledSlot slot : slots) {
			if (slot.getRarity() == Rarity.UNCOMMON && !stamps.contains(FirstStamp.FIRST_UNCOMMON)) {
				stamps.add(FirstStamp.FIRST_UNCOMMON);
			}
			if (slot.getRarity() == Rarity.RARE && !stamps.contains(FirstStamp.FIRST_RARE)) {
				stamps.add(FirstStamp.FIRST_RARE);
			}
			if (slot.getRarity().atLeast(Rarity.EPIC) && !stamps.contains(FirstStamp.FIRST_EPIC)) {
				stamps.add(FirstStamp.FIRST_EPIC);
			}
			if (slot.getVariant() == Variant.SHINY && !stamps.contains(FirstStamp.FIRST_SHINY)) {
				stamps.add(FirstStamp.FIRST_SHINY);
			}
			if (slot.isDuplicate() && !stamps.contains(FirstStamp.FIRST_DUPE)) {
				stamps.add(FirstStamp.FIRST_DUPE);
			}
		}
		return stamps;
	}

	// --- TaskService.Listener ---

	@Override
	public void onKillFeedback(TaskService.KillFeedback feedback) {
		if (client != null && TutorialGate.onTutorial(client)) {
			return;
		}
		claim(FirstStamp.FIRST_KILL);
	}

	@Override
	public void onSideBetHit(SideBet bet, String monsterName) {
		claim(FirstStamp.FIRST_SIDE_BET);
	}

	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary) {
		claim(FirstStamp.FIRST_TASK);
		if (summary.isNewFastestPb() || summary.isNewHaulPb()) {
			claim(FirstStamp.FIRST_RECORD);
		}
		if (summary.isCycleTriggered()) {
			claim(FirstStamp.FIRST_CYCLE);
		}
	}


	// --- ComplianceService.Listener ---

	@Override
	public void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc) {
	}


	@Override
	public void onTaintCleared(int cleared, int remaining) {
		claim(FirstStamp.FIRST_TAINT_CLEARED);
	}

	// --- ChestService.ChestListener ---

	@Override
	public void onChestCommitted(ChestService.ChestOpenResult result, long dupeGc) {
		claim(FirstStamp.FIRST_CHEST);
		for (FirstStamp stamp : stampsForSlots(result.getSlots())) {
			claim(stamp);
		}
	}

	@Override
	public void onDeedClaimed(GearSlot slot) {
		claim(FirstStamp.FIRST_DEED);
	}

	@Override
	public void onRerollSpent() {
		claim(FirstStamp.FIRST_REROLL_SPENT);
	}

	// --- Loadout hook (wired by the plugin) ---

	public void onCardAssigned(OwnedCard card) {
		// starter gear is auto-assigned on fresh load — never stamp without
		// an actual player action
		if (!"starter".equals(card.getProvenance())) {
			claim(FirstStamp.FIRST_ASSIGN);
		}
	}
}
