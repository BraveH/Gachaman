package com.gachaman.service;

import com.gachaman.Tuning;
import com.gachaman.data.MonsterTable;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.CharterHold;
import com.gachaman.model.GachaState;
import com.gachaman.model.MonsterStats;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.gachaman.model.TimelineEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;

/**
 * The Charter Office: buy one specific contract a day instead of waiting for
 * the board to offer it.
 *
 * <p>Three rules do the heavy lifting. A target must already be FAMILIAR — 25
 * banked kills, read straight off the journal, so nothing new is counted per
 * kill. A deed may never buy past a gate the roll enforces, so the same combat,
 * slayer and members filters apply. And the GC is held in escrow, not spent:
 * the deed sits on the board as an extra offer for 500 ticks, and if it is not
 * signed in that time the money comes back.
 *
 * <p>The escrow's whole lifecycle lives HERE, in one place, driven off the game
 * tick. Nothing else clears the hold. That is deliberate: the alternative was
 * threading a refund through the contract-signing path, and signing a contract
 * is the most delicate mutate in the plugin. Instead the state on disk always
 * reads as either "this deed is owned" or "this GC is owed", and the first tick
 * after any load settles it — so a crash cannot eat the payment.
 */
@Slf4j
@Singleton
public class CharterService
{
	/** One purchasable deed as the panel sees it: target, cost, and what it becomes. */
	@Value
	public static class Target
	{
		String monsterName;
		int combatLevel;
		long kills;
		int priceGc;
		TaskDifficulty difficulty;
	}

	/** What an open escrow has turned into as of now. */
	public enum Resolution
	{
		/** Nothing is held. */
		NONE,
		/** The deed is still on the board and still in date. */
		WAITING,
		/** The chartered contract was signed — the escrow was spent, not lost. */
		REDEEMED,
		/** The deed timed out unsigned: refund, and take it back off the board. */
		EXPIRED,
		/** The board it sat on is gone and it was never signed: refund. */
		ORPHANED
	}

	private final GachaStateService stateService;
	private final MonsterTable monsterTable;
	private final QuestUnlockService questUnlockService;
	private final TaskService taskService;
	private final CeremonyBus ceremonyBus;
	private final TimelineService timelineService;
	private final ChatMessageManager chatMessageManager;
	private final ConfigManager configManager;
	private final Client client;

	/** How often the panel is nudged while a hold runs, so its countdown moves. */
	private static final int PANEL_NUDGE_TICKS = 25;
	private int heldTicks;

	/**
	 * Client-derived scalars, refreshed on the game tick and read from the Swing
	 * thread. The panel rebuilds on the EDT and must not touch Client, so the
	 * numbers are cached here rather than fetched at paint time — at worst one
	 * tick stale, and every one of them is re-verified inside the purchase mutate.
	 */
	private volatile int playerCb = 3;
	private volatile int slayerLevel = 1;
	private volatile boolean membersWorld;
	/**
	 * Snapshot of the finished gating quests, refreshed alongside the other
	 * scalars. Starts EMPTY rather than null: until the client thread has read
	 * the real thing, the office offers nothing quest-locked instead of
	 * offering everything.
	 */
	private volatile Set<String> completedQuests = Collections.emptySet();

	@Nullable
	private Runnable refreshHook;
	@Nullable
	private BooleanSupplier partyBusyHook;

	@Inject
	public CharterService(GachaStateService stateService, MonsterTable monsterTable,
		QuestUnlockService questUnlockService, TaskService taskService, CeremonyBus ceremonyBus,
		TimelineService timelineService, ChatMessageManager chatMessageManager,
		ConfigManager configManager, Client client)
	{
		this.stateService = stateService;
		this.monsterTable = monsterTable;
		this.questUnlockService = questUnlockService;
		this.taskService = taskService;
		this.ceremonyBus = ceremonyBus;
		this.timelineService = timelineService;
		this.chatMessageManager = chatMessageManager;
		this.configManager = configManager;
		this.client = client;
	}

	public void setRefreshHook(@Nullable Runnable hook)
	{
		this.refreshHook = hook;
	}

	/** The party layer's "a roll is live" answer, wired by the plugin. */
	public void setPartyBusyHook(@Nullable BooleanSupplier hook)
	{
		this.partyBusyHook = hook;
	}

	// --- Pure rules -----------------------------------------------------------

	/**
	 * The day key, shaped exactly like the weekly shop's week key and derived the
	 * same way: UTC, from the client's own clock, with no server and nothing
	 * stored but the last one used. Comparing two derived strings is what makes
	 * "once a day" work offline, across restarts, and across world hops.
	 */
	public static String dayKey(LocalDate date)
	{
		return date.getYear() + "-D" + date.getDayOfYear();
	}

	/**
	 * The chartered contract's seed. Fixed by profile, day and target, so the
	 * quote the player is shown before paying is the contract they actually
	 * receive, and re-opening the panel cannot re-roll a friendlier kill count.
	 */
	public static long charterSeed(@Nullable String profileKey, String dayKey, String monsterName)
	{
		long mixed = (profileKey == null ? 0 : profileKey.hashCode()) * 31L + dayKey.hashCode();
		return WeeklyShopService.splitmix64(mixed * 31L + monsterName.hashCode());
	}

	/** A deed was already chartered on this day key. */
	public static boolean usedOn(@Nullable String lastDayKey, String today)
	{
		return lastDayKey != null && lastDayKey.equals(today);
	}

	/**
	 * Banked kills per species, folded case-insensitively. The journal is keyed
	 * by the raw NPC name as the kill arrived, which is not guaranteed to match
	 * the dataset's spelling byte for byte, so variants are summed rather than
	 * looked up — a player who has genuinely killed something 25 times must not
	 * be told they have not.
	 */
	public static Map<String, Long> killsByName(@Nullable Map<String, MonsterStats> monsterStats)
	{
		Map<String, Long> folded = new HashMap<>();
		if (monsterStats == null)
		{
			return folded;
		}
		for (Map.Entry<String, MonsterStats> entry : monsterStats.entrySet())
		{
			if (entry.getKey() == null || entry.getValue() == null)
			{
				continue;
			}
			folded.merge(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getKills(), Long::sum);
		}
		return folded;
	}

	/**
	 * Everything the player may charter right now, dearest first (the interesting
	 * end of the list). A target must be familiar enough, must pass every gate the
	 * board itself applies, and must not already be on the board — a duplicate
	 * would put the same monster on two offers, which no rolled board ever does,
	 * and would make "was the deed signed?" ambiguous at resolution time.
	 */
	/** Quest gating disabled — see the {@code completedQuests} overload. */
	public static List<Target> targets(@Nullable Map<String, MonsterStats> monsterStats,
		List<MonsterTable.Monster> pool, int playerCb, int playerSlayerLevel, boolean membersWorld,
		Set<String> excludeNames)
	{
		return targets(monsterStats, pool, playerCb, playerSlayerLevel, membersWorld, null, excludeNames);
	}

	public static List<Target> targets(@Nullable Map<String, MonsterStats> monsterStats,
		List<MonsterTable.Monster> pool, int playerCb, int playerSlayerLevel, boolean membersWorld,
		@Nullable Set<String> completedQuests, Set<String> excludeNames)
	{
		Map<String, Long> kills = killsByName(monsterStats);
		Set<String> excluded = new HashSet<>();
		for (String name : excludeNames == null ? Collections.<String>emptySet() : excludeNames)
		{
			if (name != null)
			{
				excluded.add(name.toLowerCase(Locale.ROOT));
			}
		}
		List<Target> targets = new ArrayList<>();
		for (MonsterTable.Monster monster : pool == null ? Collections.<MonsterTable.Monster>emptyList() : pool)
		{
			String folded = monster.getName().toLowerCase(Locale.ROOT);
			if (excluded.contains(folded)
				|| kills.getOrDefault(folded, 0L) < Tuning.CHARTER_KILLS_REQUIRED
				|| !TaskGenerator.charterEligible(monster, playerCb, playerSlayerLevel, membersWorld,
					completedQuests))
			{
				continue;
			}
			targets.add(new Target(monster.getName(), monster.getCombatLevel(),
				kills.getOrDefault(folded, 0L),
				Tuning.charterPriceGc(playerCb, monster.getCombatLevel()),
				TaskGenerator.charterDifficulty(playerCb, monster.getCombatLevel())));
		}
		targets.sort(Comparator.comparingInt(Target::getPriceGc).reversed()
			.thenComparing(Target::getMonsterName));
		return targets;
	}

	/**
	 * What an open escrow has become. Signing the chartered contract spends it;
	 * signing anything else, or losing the board it sat on (a debug wipe, a
	 * cleared task), refunds it; sitting past the deadline refunds it too.
	 */
	public static Resolution resolve(@Nullable CharterHold hold, @Nullable ActiveTask task,
		@Nullable List<TaskOffer> pending, long nowMs)
	{
		if (hold == null)
		{
			return Resolution.NONE;
		}
		if (task != null)
		{
			return hold.getMonsterName().equalsIgnoreCase(task.getMonsterName())
				? Resolution.REDEEMED : Resolution.ORPHANED;
		}
		boolean onBoard = false;
		if (pending != null)
		{
			for (TaskOffer offer : pending)
			{
				onBoard |= offer != null && hold.getMonsterName().equalsIgnoreCase(offer.getMonsterName());
			}
		}
		if (!onBoard)
		{
			return Resolution.ORPHANED;
		}
		return nowMs >= hold.getExpiresAtMs() ? Resolution.EXPIRED : Resolution.WAITING;
	}

	/** The board without the chartered offer on it. Order of the rest is preserved. */
	public static List<TaskOffer> stripCharter(@Nullable List<TaskOffer> pending, String monsterName)
	{
		List<TaskOffer> kept = new ArrayList<>();
		if (pending == null)
		{
			return kept;
		}
		for (TaskOffer offer : pending)
		{
			if (offer != null && !monsterName.equalsIgnoreCase(offer.getMonsterName()))
			{
				kept.add(offer);
			}
		}
		return kept;
	}

	/** Ticks left on a hold, floored at zero, for the panel's countdown. */
	public static int ticksRemaining(@Nullable CharterHold hold, long nowMs)
	{
		if (hold == null)
		{
			return 0;
		}
		long ms = hold.getExpiresAtMs() - nowMs;
		return ms <= 0 ? 0 : (int) Math.min(Tuning.CHARTER_HOLD_TICKS, ms / 600L);
	}

	// --- Live state -----------------------------------------------------------

	public String currentDayKey()
	{
		return dayKey(LocalDate.now(ZoneOffset.UTC));
	}

	public boolean usedToday()
	{
		GachaState state = stateService.get();
		return state != null && usedOn(state.getCharterDayKey(), currentDayKey());
	}

	@Nullable
	public CharterHold hold()
	{
		GachaState state = stateService.get();
		return state == null ? null : state.getCharterHold();
	}

	public int holdTicksRemaining()
	{
		return ticksRemaining(hold(), System.currentTimeMillis());
	}

	public int getPlayerCombatLevel()
	{
		return playerCb;
	}

	/**
	 * The Charter Office is open only against a live PERSONAL board. It sells an
	 * extra offer, so there must be offers to add to; it binds one purse, so a
	 * party roll in any stage closes the counter.
	 */
	public boolean canPurchase()
	{
		GachaState state = stateService.get();
		if (state == null || monsterTable.getMonsters().isEmpty())
		{
			return false;
		}
		if (state.getActiveTask() != null || state.getCharterHold() != null || usedToday())
		{
			return false;
		}
		List<TaskOffer> pending = state.getPendingOffers();
		if (pending == null || pending.isEmpty() || pending.get(0).isPartyRoll())
		{
			return false;
		}
		return !partyBusy();
	}

	private boolean partyBusy()
	{
		if (partyBusyHook == null)
		{
			return false;
		}
		try
		{
			return partyBusyHook.getAsBoolean();
		}
		catch (Exception e)
		{
			log.warn("party busy hook failed", e);
			return true; // an unanswerable party question is a closed counter
		}
	}

	/** Monsters already on the board — a deed may not duplicate one. */
	private Set<String> boardNames(@Nullable GachaState state)
	{
		Set<String> names = new HashSet<>();
		if (state != null && state.getPendingOffers() != null)
		{
			for (TaskOffer offer : state.getPendingOffers())
			{
				if (offer != null)
				{
					names.add(offer.getMonsterName());
				}
			}
		}
		return names;
	}

	public List<Target> eligibleTargets()
	{
		GachaState state = stateService.get();
		if (state == null)
		{
			return Collections.emptyList();
		}
		return targets(state.getMonsterStats(), monsterTable.getMonsters(), playerCb, slayerLevel,
			membersWorld, completedQuests, boardNames(state));
	}

	// --- Purchase -------------------------------------------------------------

	/**
	 * Charter a contract. The charge, the extra offer, the escrow record and the
	 * day lock are written in ONE mutate off the state actually being persisted —
	 * every guard is re-checked in there, so a second click, a contract signed a
	 * frame earlier or a purchase made in another window cannot slip through the
	 * gap between reading and writing.
	 */
	public boolean purchase(String monsterName)
	{
		if (monsterName == null || !canPurchase())
		{
			return false;
		}
		Target target = null;
		for (Target candidate : eligibleTargets())
		{
			if (candidate.getMonsterName().equals(monsterName))
			{
				target = candidate;
				break;
			}
		}
		MonsterTable.Monster monster = findMonster(monsterName);
		if (target == null || monster == null)
		{
			return false;
		}
		String dayKey = currentDayKey();
		// its own RNG instance, never the roll's: party rolls replay the shared
		// generator draw for draw, and one extra call there desyncs every client
		TaskOffer offer = TaskGenerator.charterOffer(monster, playerCb,
			new GachaRng(charterSeed(configManager.getRSProfileKey(), dayKey, monsterName)));
		if (offer == null)
		{
			return false;
		}
		final int price = target.getPriceGc();
		final long expiresAt = System.currentTimeMillis() + Tuning.CHARTER_HOLD_MS;
		final boolean[] bought = {false};
		stateService.mutate(s -> {
			List<TaskOffer> pending = s.getPendingOffers();
			if (s.getActiveTask() != null || s.getCharterHold() != null
				|| usedOn(s.getCharterDayKey(), dayKey)
				|| pending == null || pending.isEmpty() || pending.get(0).isPartyRoll()
				|| s.getGc() < price)
			{
				return s;
			}
			for (TaskOffer existing : pending)
			{
				if (existing != null && existing.getMonsterName().equalsIgnoreCase(monsterName))
				{
					return s; // never two offers for one monster
				}
			}
			List<TaskOffer> board = new ArrayList<>(pending);
			board.add(offer); // strictly APPENDED — no existing offer's index moves
			bought[0] = true;
			return s.withGc(s.getGc() - price)
				.withPendingOffers(board)
				.withCharterHold(new CharterHold(monsterName, price, expiresAt))
				.withCharterDayKey(dayKey);
		});
		if (!bought[0])
		{
			// every guard was clear a moment ago, so getting here means something
			// moved underneath the click — say so rather than swallowing it
			chat("The Charter Office could not write that deed. Check your GC and that the"
				+ " board is still yours.");
			refresh();
			return false;
		}
		chat("Deed chartered: " + offer.getKillsRequired() + "x " + monsterName + " ("
			+ offer.getDifficulty().getDisplayName() + ") for " + price
			+ " GC. It sits on the board for " + Tuning.CHARTER_HOLD_TICKS
			+ " ticks — accept it or the GC comes back.");
		timelineService.record(TimelineEvent.KIND_CHARTER,
			"Deed chartered: " + monsterName + " (" + offer.getDifficulty().getDisplayName()
				+ ") — -" + price + " GC", offer.getDifficulty().name());
		GachaState after = stateService.get();
		if (after != null && after.getPendingOffers() != null)
		{
			// the same ceremony the board already uses; a new Type here would sit
			// unhandled in the modal queue and block every ceremony behind it
			ceremonyBus.submit(CeremonyBus.Type.TASK_OFFERS, after.getPendingOffers());
		}
		refresh();
		return true;
	}

	@Nullable
	private MonsterTable.Monster findMonster(String monsterName)
	{
		for (MonsterTable.Monster monster : monsterTable.getMonsters())
		{
			if (monster.getName().equals(monsterName))
			{
				return monster;
			}
		}
		return null;
	}

	// --- Tick -----------------------------------------------------------------

	/**
	 * Refresh the cached client scalars and settle any open escrow. Cheap on the
	 * overwhelming majority of ticks: with no hold outstanding it reads three
	 * client values and returns without touching state.
	 */
	public void tick()
	{
		refreshScalars();
		GachaState state = stateService.get();
		if (state == null || state.getCharterHold() == null)
		{
			heldTicks = 0;
			return;
		}
		if (resolve(state.getCharterHold(), state.getActiveTask(), state.getPendingOffers(),
			System.currentTimeMillis()) == Resolution.WAITING)
		{
			// the panel repaints on state changes, and a running hold changes
			// nothing in state — nudge it occasionally so the countdown it draws
			// is not visibly frozen. No mutate, so nothing is re-serialized.
			if (++heldTicks % PANEL_NUDGE_TICKS == 0)
			{
				refresh();
			}
			return;
		}
		heldTicks = 0;
		final int[] refunded = {0};
		final boolean[] expired = {false};
		stateService.mutate(s -> {
			CharterHold held = s.getCharterHold();
			if (held == null)
			{
				return s;
			}
			// re-resolved against the state being written, not the snapshot above
			switch (resolve(held, s.getActiveTask(), s.getPendingOffers(), System.currentTimeMillis()))
			{
				case REDEEMED:
					return s.withCharterHold(null);
				case EXPIRED:
					refunded[0] = held.getPriceGc();
					expired[0] = true;
					return s.withCharterHold(null)
						.withGc(s.getGc() + held.getPriceGc())
						.withPendingOffers(stripCharter(s.getPendingOffers(), held.getMonsterName()));
				case ORPHANED:
					refunded[0] = held.getPriceGc();
					return s.withCharterHold(null).withGc(s.getGc() + held.getPriceGc());
				default:
					return s;
			}
		});
		if (refunded[0] > 0)
		{
			// never suppressible: GC moving on its own reads as a bug otherwise
			chat(expired[0]
				? "The chartered deed expired unsigned — " + refunded[0] + " GC refunded."
				: "The chartered deed is gone — " + refunded[0] + " GC refunded.");
			timelineService.record(TimelineEvent.KIND_CHARTER,
				"Deed refunded: +" + refunded[0] + " GC", null);
			refresh();
		}
	}

	/** Client reads happen here, on the client thread, and nowhere else. */
	public void refreshScalars()
	{
		if (client == null)
		{
			return;
		}
		try
		{
			playerCb = taskService.playerCombatLevel();
			slayerLevel = client.getRealSkillLevel(Skill.SLAYER);
			membersWorld = taskService.localIsMembers();
			completedQuests = questUnlockService.completedQuests();
		}
		catch (Exception e)
		{
			log.debug("charter scalars unavailable", e);
		}
	}

	// --- Helpers --------------------------------------------------------------

	private void refresh()
	{
		if (refreshHook == null)
		{
			return;
		}
		try
		{
			refreshHook.run();
		}
		catch (Exception e)
		{
			log.warn("charter refresh hook failed", e);
		}
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> " + message)
			.build());
	}
}
