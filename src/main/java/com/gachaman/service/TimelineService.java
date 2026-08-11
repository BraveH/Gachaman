package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GearSlot;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.TaskOffer;
import com.gachaman.model.TimelineEvent;
import com.gachaman.model.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The fortune timeline: records every roll, pull, equip and notable event as
 * a chronological audit line in state (capped). Feeds off a CeremonyBus tap,
 * the chest commit hooks, compliance events, and small hooks the plugin wires.
 */
@Slf4j
@Singleton
public class TimelineService implements ChestService.ChestListener, ComplianceService.Listener
{
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;

	@Inject
	public TimelineService(GachaStateService stateService, CardDatabase cardDatabase)
	{
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
	}

	// --- Core recording ---

	public void record(String kind, String text, @Nullable String meta)
	{
		recordAll(List.of(new TimelineEvent(System.currentTimeMillis(), kind, text, meta)));
	}

	public void recordAll(List<TimelineEvent> events)
	{
		if (events.isEmpty())
		{
			return;
		}
		stateService.mutate(s -> {
			List<TimelineEvent> timeline = s.getTimeline() == null
				? new ArrayList<>() : new ArrayList<>(s.getTimeline());
			timeline.addAll(events);
			while (timeline.size() > Tuning.TIMELINE_MAX_EVENTS)
			{
				timeline.remove(0);
			}
			return s.withTimeline(timeline);
		});
	}

	// --- CeremonyBus tap (style rolls, offers, completions, fanfares) ---

	public void onCeremony(CeremonyBus.Request request)
	{
		switch (request.getType())
		{
			case STYLE_ROLL:
			{
				if (!(request.getPayload() instanceof StyleService.StyleRollResult))
				{
					return;
				}
				StyleService.StyleRollResult roll = (StyleService.StyleRollResult) request.getPayload();
				AttackStyle rolled = roll.getRolled();
				String was = roll.getPrevious() == null ? ""
					: " (was " + roll.getPrevious().getDisplayName() + ")";
				record(TimelineEvent.KIND_STYLE,
					"Style rolled: " + rolled.getDisplayName() + was, rolled.name());
				return;
			}
			case TASK_OFFERS:
			{
				if (!(request.getPayload() instanceof List))
				{
					return;
				}
				StringBuilder sb = new StringBuilder("Contracts rolled: ");
				boolean first = true;
				for (Object o : (List<?>) request.getPayload())
				{
					if (!(o instanceof TaskOffer))
					{
						continue;
					}
					TaskOffer offer = (TaskOffer) o;
					if (!first)
					{
						sb.append(", ");
					}
					first = false;
					sb.append(offer.getMonsterName())
						.append(" (").append(offer.getDifficulty().getDisplayName()).append(')');
				}
				record(TimelineEvent.KIND_OFFERS, sb.toString(), null);
				return;
			}
			case TASK_COMPLETE:
			{
				if (!(request.getPayload() instanceof TaskService.TaskCompletionSummary))
				{
					return;
				}
				TaskService.TaskCompletionSummary sum =
					(TaskService.TaskCompletionSummary) request.getPayload();
				String monster = sum.getTask() == null ? "?" : sum.getTask().getMonsterName();
				String difficulty = sum.getTask() == null ? null : sum.getTask().getDifficulty().name();
				StringBuilder sb = new StringBuilder("Contract complete: ").append(monster)
					.append(" — +").append(sum.getCompletionGcAwarded()).append(" GC");
				if (sum.getFragmentsEarned() > 0)
				{
					sb.append(", +").append(sum.getFragmentsEarned()).append(" fragment")
						.append(sum.getFragmentsEarned() > 1 ? "s" : "");
				}
				if (sum.isFragmentDeedForged())
				{
					sb.append(" — DEED FORGED");
				}
				if (sum.getDeedMilestoneEarned() > 0)
				{
					sb.append(" — deed milestone ").append(sum.getDeedMilestoneEarned());
				}
				if (sum.isCycleTriggered())
				{
					sb.append(" — style cycle complete");
				}
				record(TimelineEvent.KIND_COMPLETE, sb.toString(), difficulty);
				return;
			}
			case FANFARE:
			{
				if (!(request.getPayload() instanceof CeremonyBus.Fanfare))
				{
					return;
				}
				CeremonyBus.Fanfare fanfare = (CeremonyBus.Fanfare) request.getPayload();
				String detail = fanfare.getDetail() == null || fanfare.getDetail().isEmpty()
					? "" : " — " + fanfare.getDetail();
				record(TimelineEvent.KIND_LUCK, fanfare.getTitle() + detail, null);
				return;
			}
			case CHEST_OPEN:
			case THEMED_CHEST:
			{
				// the purchase itself, logged the moment the ceremony starts
				// (cards land separately at commit, post-reroll)
				if (request.getPayload() instanceof ChestService.ChestOpenResult)
				{
					record(TimelineEvent.KIND_CHEST,
						chestHeader((ChestService.ChestOpenResult) request.getPayload()), null);
				}
				return;
			}
			default:
				// deed choices are recorded via their claim fanfare
		}
	}

	private static String chestHeader(ChestService.ChestOpenResult result)
	{
		StringBuilder sb = new StringBuilder();
		if (result.getThemedSetTag() != null)
		{
			sb.append("Boss chest opened: ").append(result.getThemedSetTag());
		}
		else if (result.getTargetSlot() != null)
		{
			sb.append("Slot chest opened: ").append(result.getTargetSlot());
		}
		else
		{
			sb.append("Chest opened: ").append(pretty(result.getPurchasedTier().name()));
		}
		if (result.getPricePaid() > 0)
		{
			sb.append(" (").append(result.getPricePaid()).append(" GC)");
		}
		if (result.isJackpotUpgraded())
		{
			sb.append(" — JACKPOT to ").append(pretty(result.getEffectiveTier().name()));
		}
		if (result.isPityBreak())
		{
			sb.append(" — PITY BREAK");
		}
		if (result.isStardustBlessed())
		{
			sb.append(" — stardust-blessed");
		}
		if (result.isDeedGranted())
		{
			sb.append(" — SLOT DEED rolled");
		}
		return sb.toString();
	}

	// --- Plugin-wired hooks ---

	public void onOfferAccepted(TaskOffer offer)
	{
		record(TimelineEvent.KIND_ACCEPT,
			"Contract accepted: " + offer.getKillsRequired() + "x " + offer.getMonsterName()
				+ " (" + offer.getDifficulty().getDisplayName() + ")"
				+ (offer.isPartyRoll() ? " — PARTY (shared)" : ""),
			offer.getDifficulty().name());
	}

	public void onCardAssigned(GearSlot slot, OwnedCard card)
	{
		record(TimelineEvent.KIND_EQUIP,
			"Equipped: " + cardName(card) + " -> " + slot.getDisplayName() + " slot", null);
	}

	public void onChargePurchased(boolean compactor, boolean voucher)
	{
		record(TimelineEvent.KIND_CHARGE,
			(compactor ? "Compactor" : "Extender") + " applied to the current contract"
				+ (voucher ? " (free voucher)" : ""), null);
	}

	// --- ChestService.ChestListener ---

	@Override
	public void onChestCommitted(ChestService.ChestOpenResult result, long dupeGc)
	{
		// the purchase header was logged at open time (ceremony tap); commit
		// logs the final cards — post-reroll, exactly what entered the album
		List<TimelineEvent> events = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (ChestService.RolledSlot slot : result.getSlots())
		{
			StringBuilder card = new StringBuilder("Card: ").append(slotName(slot))
				.append(" (").append(pretty(slot.getRarity().name())).append(')');
			if (slot.getVariant() == Variant.SHINY)
			{
				card.append(" — SHINY!");
			}
			else if (slot.getVariant() == Variant.HOLOGRAM)
			{
				card.append(" — HOLOGRAM!");
			}
			if (slot.isDuplicate())
			{
				Integer gc = Tuning.DUPLICATE_GC.get(slot.getRarity());
				card.append(" (dupe, +").append(gc == null ? 0 : gc).append(" GC)");
			}
			events.add(new TimelineEvent(now, TimelineEvent.KIND_CARD, card.toString(),
				slot.getRarity().name()));
		}
		recordAll(events);
	}

	@Override
	public void onDeedClaimed(GearSlot slot)
	{
		// the claim's LARGE fanfare already lands a LUCK entry via the bus tap
	}

	@Override
	public void onRerollSpent()
	{
		record(TimelineEvent.KIND_REROLL, "Reroll token spent — one card re-flipped", null);
	}

	// --- ComplianceService.Listener ---

	@Override
	public void onForbiddenAttack(AttackStyle used, AttackStyle allowed, long penaltyGc)
	{
		record(TimelineEvent.KIND_VIOLATION,
			"Forbidden " + used.getDisplayName() + " attack (" + allowed.getDisplayName()
				+ " allowed) — -" + penaltyGc + " GC", null);
	}

	@Override
	public void onForbiddenPardoned(int tick, long refundedGc)
	{
		record(TimelineEvent.KIND_CLEANSE,
			"Pardoned: that attack was Magic after all — +" + refundedGc + " GC refunded", null);
	}

	@Override
	public void onTaintAdded(int newTaint)
	{
		record(TimelineEvent.KIND_TAINT, "Tainted kill — taint x" + newTaint, null);
	}

	@Override
	public void onTaintCleared(int cleared, int remaining)
	{
		if (remaining == 0)
		{
			record(TimelineEvent.KIND_CLEANSE, "All taint cleansed", null);
		}
	}

	// --- Helpers ---

	private String cardName(OwnedCard card)
	{
		if (card.isHologram())
		{
			HologramDefinition holo = cardDatabase.holograms().get(card.getTierKey());
			return holo != null ? holo.getName() : "Hologram (" + card.getTierKey() + ")";
		}
		CardDefinition def = cardDatabase.card(card.getCardId());
		String name = def != null ? def.getName() : "Card #" + card.getCardId();
		return card.getVariant() == Variant.SHINY ? name + " (Shiny)" : name;
	}

	private String slotName(ChestService.RolledSlot slot)
	{
		if (slot.getHologramTier() != null)
		{
			HologramDefinition holo = cardDatabase.holograms().get(slot.getHologramTier());
			return holo != null ? holo.getName() : "Hologram (" + slot.getHologramTier() + ")";
		}
		CardDefinition def = cardDatabase.card(slot.getCardId());
		return def != null ? def.getName() : "Card #" + slot.getCardId();
	}

	/** RUSTY -> Rusty, LEGENDARY -> Legendary. */
	private static String pretty(String constant)
	{
		if (constant == null || constant.isEmpty())
		{
			return "?";
		}
		return constant.charAt(0) + constant.substring(1).toLowerCase(Locale.ROOT);
	}
}
