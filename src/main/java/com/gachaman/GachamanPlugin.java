package com.gachaman;

import com.gachaman.data.BossTable;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.MonsterTable;
import com.gachaman.data.SetTable;
import com.gachaman.data.TierTable;
import com.gachaman.overlay.ForbiddenItemOverlay;
import com.gachaman.overlay.GachaInfoboxOverlay;
import com.gachaman.overlay.KillJuiceOverlay;
import com.gachaman.overlay.RevealInputListener;
import com.gachaman.overlay.RevealOverlay;
import com.gachaman.party.GachaPresenceMessage;
import com.gachaman.party.PartyCompleteMessage;
import com.gachaman.party.PartyKillsMessage;
import com.gachaman.party.PartyPresenceService;
import com.gachaman.party.PartyRollProposeMessage;
import com.gachaman.party.PartyRollResponseMessage;
import com.gachaman.party.PartyRollService;
import com.gachaman.party.PartyRollVoteMessage;
import com.gachaman.service.BossKcService;
import com.gachaman.service.CeremonyBus;
import com.gachaman.service.ChestService;
import com.gachaman.service.ComplianceService;
import com.gachaman.service.CreditSink;
import com.gachaman.service.EquipBlockService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.KillTracker;
import com.gachaman.service.MilestoneService;
import com.gachaman.service.PermissionService;
import com.gachaman.service.PrestigeService;
import com.gachaman.service.SafeModeService;
import com.gachaman.service.SetPerkService;
import com.gachaman.service.SoundService;
import com.gachaman.service.StyleService;
import com.gachaman.service.StyleTracker;
import com.gachaman.service.TaskService;
import com.gachaman.ui.loadout.LoadoutButtonOverlay;
import com.gachaman.ui.loadout.LoadoutInputListener;
import com.gachaman.ui.loadout.LoadoutOverlay;
import com.gachaman.ui.panel.GachamanPanel;
import com.gachaman.ui.panel.PanelIcon;
import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Gachaman",
	description = "RNG gamemode: attack styles rolled by fate, equipment gated behind collectible cards, chests, kill tasks and ceremonies. Removes menu entries on card-locked equipment (client-side only).",
	tags = {"gamemode", "gacha", "cards", "chest", "challenge"}
)
public class GachamanPlugin extends Plugin
{
	// clients & managers
	@Inject
	private Client client;
	@Inject
	private net.runelite.client.callback.ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private EventBus eventBus;
	@Inject
	private WSClient wsClient;
	@Inject
	private PartyService partyService;
	@Inject
	private ChatMessageManager chatMessageManager;
	@Inject
	private ConfigManager configManager;
	@Inject
	private GachamanConfig config;

	// data
	@Inject
	private CardDatabase cardDatabase;
	@Inject
	private TierTable tierTable;
	@Inject
	private SetTable setTable;

	// services
	@Inject
	private GachaStateService stateService;
	@Inject
	private CreditSink creditSink;
	@Inject
	private CeremonyBus ceremonyBus;
	@Inject
	private StyleTracker styleTracker;
	@Inject
	private KillTracker killTracker;
	@Inject
	private ComplianceService complianceService;
	@Inject
	private TaskService taskService;
	@Inject
	private StyleService styleService;
	@Inject
	private ChestService chestService;
	@Inject
	private MilestoneService milestoneService;
	@Inject
	private com.gachaman.service.GraduationService graduationService;
	@Inject
	private com.gachaman.service.FirstsService firstsService;
	@Inject
	private com.gachaman.service.TimelineService timelineService;
	@Inject
	private com.gachaman.service.CharterService charterService;
	@Inject
	private BossKcService bossKcService;
	@Inject
	private SetPerkService setPerkService;
	@Inject
	private PrestigeService prestigeService;
	@Inject
	private PermissionService permissionService;
	@Inject
	private EquipBlockService equipBlockService;
	@Inject
	private SafeModeService safeModeService;

	@Inject
	private com.gachaman.service.UnequipService unequipService;
	@Inject
	private SoundService soundService;
	@Inject
	private PartyRollService partyRollService;
	@Inject
	private PartyPresenceService partyPresenceService;
	@Inject
	private com.gachaman.service.CombatBlockService combatBlockService;
	@Inject
	private com.gachaman.service.LoadoutService loadoutService;
	@Inject
	private com.gachaman.service.ServiceRecordService serviceRecordService;
	@Inject
	private com.gachaman.service.SlayerAlignment slayerAlignment;

	// overlays & UI
	@Inject
	private RevealOverlay revealOverlay;
	@Inject
	private RevealInputListener revealInputListener;
	@Inject
	private KillJuiceOverlay killJuiceOverlay;
	@Inject
	private GachaInfoboxOverlay gachaInfoboxOverlay;
	@Inject
	private ForbiddenItemOverlay forbiddenItemOverlay;
	@Inject
	private com.gachaman.overlay.TaskNpcHighlightOverlay taskNpcHighlightOverlay;
	@Inject
	private com.gachaman.overlay.TaskProgressOverlay taskProgressOverlay;
	@Inject
	private LoadoutButtonOverlay loadoutButtonOverlay;
	@Inject
	private com.gachaman.ui.loadout.LoadoutTabButton loadoutTabButton;
	@Inject
	private LoadoutOverlay loadoutOverlay;
	@Inject
	private LoadoutInputListener loadoutInputListener;
	@Inject
	private GachamanPanel gachamanPanel;

	private NavigationButton navButton;
	private boolean stateLoadPending;
	private boolean wasOnTutorial;

	/** Loud, immediate feedback the moment a forbidden-style attack lands. */
	private final ComplianceService.Listener complianceFeedback = new ComplianceService.Listener()
	{
		@Override
		public void onForbiddenAttack(com.gachaman.model.AttackStyle used,
			com.gachaman.model.AttackStyle allowed, long penaltyGc)
		{
			soundService.playShatter();
			debugChatAlways("<col=e83c3c>Forbidden " + used.getDisplayName()
				+ " attack!</col> Only " + allowed.getDisplayName()
				+ " is allowed. <col=e83c3c>-" + penaltyGc + " GC</col>");
			if (used == com.gachaman.model.AttackStyle.MELEE
				&& allowed == com.gachaman.model.AttackStyle.MAGIC
				&& autoRetaliateStaffBashLikely())
			{
				debugChatAlways("Tip: auto-retaliate swings your staff's melee bash between casts —"
					+ " set an autocast spell or turn auto-retaliate off.");
			}
		}

		@Override
		public void onForbiddenPardoned(int tick, long refundedGc)
		{
			debugChatAlways("<col=6ec86e>Pardoned.</col> That attack was actually Magic —"
				+ (refundedGc > 0 ? " +" + refundedGc + " GC refunded." : " no penalty kept."));
		}

		@Override
		public void onTaintAdded(int newTaint)
		{
			debugChatAlways("<col=e83c3c>Tainted kill — no reward.</col> Taint x" + newTaint
				+ ": all income halved until worked off.");
		}

		@Override
		public void onTaintCleared(int cleared, int remaining)
		{
			if (remaining == 0 && cleared > 1)
			{
				debugChatAlways("<col=6ec86e>All taint cleansed.</col>");
			}
		}
	};

	/**
	 * Stable tap reference: a method reference is a NEW object each time, so
	 * addTap/removeTap must share this single instance to stay paired.
	 */
	private final java.util.function.Consumer<CeremonyBus.Request> timelineTap =
		request -> timelineService.onCeremony(request);

	/** Chat notice when the ironman assisted-kill penalty halves a kill's credit. */
	private final TaskService.Listener assistedKillFeedback = new TaskService.Listener()
	{
		@Override
		public void onKillFeedback(TaskService.KillFeedback feedback)
		{
			if (feedback.isAssistedHalfCredit())
			{
				debugChatAlways("<col=e8a33c>Assisted kill — half credit.</col> Another player"
					+ " damaged that monster; ironman kills count half kc and half GC.");
			}
		}

		@Override
		public void onSideBetHit(com.gachaman.model.SideBet bet, String monsterName)
		{
		}

		@Override
		public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
		{
		}

		@Override
		public void onOffersRolled(java.util.List<com.gachaman.model.TaskOffer> offers)
		{
		}

		@Override
		public void onPartyProgress(com.gachaman.model.ActiveTask task)
		{
		}
	};

	@Provides
	GachamanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GachamanConfig.class);
	}

	@Provides
	@Singleton
	TierTable provideTierTable(Gson gson)
	{
		return TierTable.load(gson);
	}

	@Provides
	@Singleton
	MonsterTable provideMonsterTable(Gson gson)
	{
		return MonsterTable.load(gson);
	}

	@Provides
	@Singleton
	BossTable provideBossTable(Gson gson)
	{
		return BossTable.load(gson);
	}

	@Provides
	@Singleton
	SetTable provideSetTable(Gson gson)
	{
		return SetTable.load(gson);
	}

	@Provides
	@Singleton
	com.gachaman.data.QuestMonsterTable provideQuestMonsterTable(Gson gson)
	{
		return com.gachaman.data.QuestMonsterTable.load(gson);
	}

	@Override
	protected void startUp()
	{
		// service listener graph
		styleTracker.addListener(complianceService);
		styleTracker.addListener(taskService); // any attack keeps the combo chain alive
		// before taskService: the contract's final kill is then tallied before
		// completeTask fires the flush. Out of order it is merely deferred to
		// the next flush, never lost — but this way the last kill lands too.
		killTracker.addListener(serviceRecordService);
		killTracker.addListener(taskService);
		complianceService.addListener(taskService); // rhythm combo breaks on violations
		taskService.addListener(killJuiceOverlay);
		taskService.addListener(partyRollService);
		taskService.addListener(assistedKillFeedback);
		taskService.setOfferAcceptedHook(timelineService::onOfferAccepted);
		taskService.setPartyVoteHook(partyRollService::voteLocal);
		taskService.setSlayerTargetHook(slayerAlignment::liveTarget);
		taskService.setSlayerLatchHook(this::announceDoubleDocket);
		partyRollService.setRefreshHook(gachamanPanel::refresh);
		partyPresenceService.setRefreshHook(gachamanPanel::refresh);
		// scoped: a dead vote closes the offer scrolls it invalidated, nothing else
		partyRollService.setCeremonyAbortHook(
			() -> revealOverlay.abortActiveCeremony(CeremonyBus.Type.TASK_OFFERS));
		// The Charter Office sells a personal, binding contract onto a personal
		// board, so it stands down for the whole of any party roll
		charterService.setPartyBusyHook(partyRollService::isPartyRollLive);
		charterService.setRefreshHook(gachamanPanel::refresh);
		permissionService.start();
		setPerkService.start();
		prestigeService.start(creditSink);
		complianceService.addListener(complianceFeedback);

		taskService.addListener(serviceRecordService); // flush the tally at contract completion

		// Firsts Journal: stamps ride the existing listener graphs
		taskService.addListener(firstsService);
		complianceService.addListener(firstsService);
		chestService.addChestListener(firstsService);
		loadoutService.setAssignHook((slot, card) -> {
			firstsService.onCardAssigned(card);
			timelineService.onCardAssigned(slot, card);
		});

		// Fortune timeline: audits ceremonies, chest commits and violations
		ceremonyBus.addTap(timelineTap);
		chestService.addChestListener(timelineService);
		complianceService.addListener(timelineService);

		// ceremonies
		ceremonyBus.addRenderer(revealOverlay);
		soundService.setEnabled(config.soundsEnabled());
		soundService.setVolume(config.soundVolume());

		// overlays
		overlayManager.add(revealOverlay);
		overlayManager.add(killJuiceOverlay);
		overlayManager.add(gachaInfoboxOverlay);
		overlayManager.add(forbiddenItemOverlay);
		overlayManager.add(taskNpcHighlightOverlay);
		overlayManager.add(taskProgressOverlay);
		overlayManager.add(loadoutOverlay);

		// input (modal reveal listener first so it wins while a ceremony is up)
		mouseManager.registerMouseListener(revealInputListener);
		mouseManager.registerMouseWheelListener(revealInputListener);
		keyManager.registerKeyListener(revealInputListener);
		mouseManager.registerMouseListener(loadoutInputListener);

		// event bus sub-services
		eventBus.register(styleTracker);
		eventBus.register(killTracker);
		eventBus.register(graduationService);
		eventBus.register(milestoneService);
		eventBus.register(bossKcService);
		eventBus.register(safeModeService);
		eventBus.register(equipBlockService);
		eventBus.register(combatBlockService);
		eventBus.register(loadoutTabButton);
		eventBus.register(partyRollService);
		eventBus.register(partyPresenceService);
		clientThreadInvokeCreateButton();

		// party messages
		wsClient.registerMessage(PartyRollProposeMessage.class);
		wsClient.registerMessage(PartyRollResponseMessage.class);
		wsClient.registerMessage(com.gachaman.party.PartyRollStartMessage.class);
		wsClient.registerMessage(com.gachaman.party.PartyRollCancelMessage.class);
		wsClient.registerMessage(PartyRollVoteMessage.class);
		wsClient.registerMessage(com.gachaman.party.PartyRollResolveMessage.class);
		wsClient.registerMessage(PartyKillsMessage.class);
		wsClient.registerMessage(PartyCompleteMessage.class);
		wsClient.registerMessage(GachaPresenceMessage.class);

		// sidebar
		navButton = NavigationButton.builder()
			.tooltip("Gachaman")
			.icon(PanelIcon.create())
			.priority(6)
			.panel(gachamanPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		gachamanPanel.start();
		// party UI hides entirely when the Party contracts setting is off
		gachamanPanel.setInPartySupplier(() -> partyService.isInParty() && config.partyRollsEnabled());

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			stateLoadPending = true;
		}
		log.info("Gachaman started");
	}

	@Override
	protected void shutDown()
	{
		// renderer FIRST: commit-time ceremony submissions must only queue (and
		// then be cleared), never be claimed into the overlay being torn down
		ceremonyBus.removeRenderer(revealOverlay);
		killTracker.flushPending(); // kills still waiting on the loot oracle
		revealOverlay.abortActiveCeremony();
		revealOverlay.reset(); // drop any claimed-but-unshown fanfare too
		chestService.commitPending(); // idempotent: catches opens queued but never presented
		ceremonyBus.clear();
		// the kills flushPending just emitted are tallied but unwritten, and the
		// listener is still attached above, so they reached the tally
		serviceRecordService.flush();
		stateService.checkpoint();
		stateService.unload();

		styleTracker.removeListener(complianceService);
		styleTracker.removeListener(taskService);
		killTracker.removeListener(serviceRecordService);
		killTracker.removeListener(taskService);
		complianceService.removeListener(taskService);
		taskService.removeListener(killJuiceOverlay);
		taskService.removeListener(partyRollService);
		taskService.removeListener(assistedKillFeedback);
		taskService.setOfferAcceptedHook(null);
		taskService.setPartyVoteHook(null);
		taskService.setSlayerTargetHook(null);
		taskService.setSlayerLatchHook(null);
		partyRollService.setRefreshHook(null);
		partyPresenceService.setRefreshHook(null);
		partyPresenceService.reset();
		partyRollService.setCeremonyAbortHook(null);
		charterService.setPartyBusyHook(null);
		charterService.setRefreshHook(null);
		complianceService.removeListener(complianceFeedback);
		taskService.removeListener(serviceRecordService);
		taskService.removeListener(firstsService);
		complianceService.removeListener(firstsService);
		chestService.removeChestListener(firstsService);
		loadoutService.setAssignHook(null);
		ceremonyBus.removeTap(timelineTap);
		chestService.removeChestListener(timelineService);
		complianceService.removeListener(timelineService);
		prestigeService.stop();

		gachamanPanel.stop();
		gachamanPanel.setInPartySupplier(() -> false); // null is ignored by the setter
		clientToolbar.removeNavigation(navButton);
		navButton = null;

		wsClient.unregisterMessage(PartyRollProposeMessage.class);
		wsClient.unregisterMessage(PartyRollResponseMessage.class);
		wsClient.unregisterMessage(com.gachaman.party.PartyRollStartMessage.class);
		wsClient.unregisterMessage(com.gachaman.party.PartyRollCancelMessage.class);
		wsClient.unregisterMessage(PartyRollVoteMessage.class);
		wsClient.unregisterMessage(com.gachaman.party.PartyRollResolveMessage.class);
		wsClient.unregisterMessage(PartyKillsMessage.class);
		wsClient.unregisterMessage(PartyCompleteMessage.class);
		wsClient.unregisterMessage(GachaPresenceMessage.class);

		eventBus.unregister(styleTracker);
		eventBus.unregister(killTracker);
		eventBus.unregister(graduationService);
		eventBus.unregister(milestoneService);
		eventBus.unregister(bossKcService);
		eventBus.unregister(safeModeService);
		eventBus.unregister(equipBlockService);
		eventBus.unregister(combatBlockService);
		eventBus.unregister(loadoutTabButton);
		eventBus.unregister(partyRollService);
		eventBus.unregister(partyPresenceService);
		clientThreadInvokeRemoveButton();

		mouseManager.unregisterMouseListener(revealInputListener);
		mouseManager.unregisterMouseWheelListener(revealInputListener);
		keyManager.unregisterKeyListener(revealInputListener);
		mouseManager.unregisterMouseListener(loadoutInputListener);

		overlayManager.remove(revealOverlay);
		overlayManager.remove(killJuiceOverlay);
		overlayManager.remove(gachaInfoboxOverlay);
		overlayManager.remove(forbiddenItemOverlay);
		overlayManager.remove(taskNpcHighlightOverlay);
		overlayManager.remove(taskProgressOverlay);
		overlayManager.remove(loadoutOverlay);

		permissionService.stop();
		setPerkService.stop();
		log.info("Gachaman stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			stateLoadPending = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// credit kills still waiting on the loot oracle BEFORE checkpointing
			killTracker.flushPending();
			revealOverlay.abortActiveCeremony();
			chestService.commitPending();
			// must land BEFORE the checkpoint, under THIS profile's key: the
			// profile-changed handler discards without saving
			serviceRecordService.flush();
			stateService.checkpoint();
			// transient combat must not survive a logout (stale combos would
			// leak across characters and defeat the idle reset). Convictions go
			// with them — pending kills were just flushed above, so nothing is
			// left to judge, and a conviction outliving its profile could pardon
			// away the next character's taint.
			taskService.resetTransientCombat();
			complianceService.resetTransient();
			// the presence line we last broadcast describes a character that just
			// logged out — force a re-announce rather than let the heartbeat carry
			// the old one into the next login
			partyPresenceService.reset();
			// a half-finished strip must not resume against the next character
			unequipService.cancel();
		}
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			// the board's hit test outlives the frames that drew it
			loadoutOverlay.setOpen(false);
		}
		if (event.getGameState() == GameState.LOGGING_IN
			|| event.getGameState() == GameState.LOGIN_SCREEN_AUTHENTICATOR)
		{
			// off-world without a logout teardown. Ceremonies are only ever drawn
			// in game, so one left claimed here would sit invisible over the
			// welcome screen — exactly where "Click here to play" is the only
			// thing the user can click.
			revealOverlay.abortActiveCeremony();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// a ceremony's phase clock only ticks while it is being drawn, so one
		// that stopped rendering can never time itself out — drop it here
		revealOverlay.pruneStaleModal();

		if (stateLoadPending && client.getGameState() == GameState.LOGGED_IN
			&& configManager.getRSProfileKey() != null)
		{
			// profile key must exist before load, or a fresh state could be
			// created under (and later saved to) the wrong profile
			stateLoadPending = false;
			if (!stateService.isLoaded())
			{
				stateService.load(milestoneService.combatLevel());
			}
			cardDatabase.beginBuild(tierTable, setTable);
			chestService.recoverPending(); // crash-interrupted reveal: auto-commit
			// crash-interrupted party session: the vote and the shared-contract
			// session died with the old process, but the party-flagged offers and
			// the contract itself came back off disk. Orphaned offers are demoted
			// (every click would route into a vote nobody counts) and an orphaned
			// contract is resurrected, so it can resync, complete or convert like
			// any other. Guarded internally, so a hop with a live session is
			// untouched.
			partyRollService.recoverPartySession();
			cardDatabase.onReady(this::healStaleCardIds);
			cardDatabase.onReady(this::grantStarterCards);
			// the gift needs the DB before it can know what the rolled style
			// actually swings; a save whose roll was armed in an earlier session
			// that died mid-ceremony is redeemed from here rather than below
			cardDatabase.onReady(this::redeemFirstColoursChestIfOwed);
			cardDatabase.onReady(graduationService::refresh); // baseline worn gear
			// ceremonies parked while logged out present now
			ceremonyBus.drain();
			wasOnTutorial = com.gachaman.service.TutorialGate.onTutorial(client);
			if (!wasOnTutorial)
			{
				// already ashore: settle the strip without performing it, so
				// installing the plugin later never undresses an existing account
				stateService.mutate(s -> s.isTutorialStripDone() ? s : s.withTutorialStripDone(true));
				// ...unless this save DID step off the island and the strip was cut
				// short by that logout; finish the job it started
				if (stateService.get() != null && stateService.get().isTutorialStripPending())
				{
					unequipService.arm();
				}
				beginJourneyIfFresh();
			}
			return;
		}

		// Tutorial Island exit: the locks switch on — strip the tutorial's gear
		// (no card unlocks it yet), then style roll, then tasks
		if (wasOnTutorial && stateService.isLoaded()
			&& !com.gachaman.service.TutorialGate.onTutorial(client))
		{
			wasOnTutorial = false;
			var state = stateService.get();
			if (state != null && !state.isTutorialStripDone())
			{
				stateService.mutate(s -> s.withTutorialStripDone(true).withTutorialStripPending(true));
				unequipService.arm();
				// never suppressible: gear vanishing without a reason reads as item loss
				debugChatAlways("Welcome to Gachaman. Your tutorial gear is being removed —"
					+ " equipment is locked behind cards from here on.");
			}
			beginJourneyIfFresh();
		}

		if (unequipService.isArmed() && !unequipService.tick() && unequipService.isStripComplete())
		{
			// everything is off; stop resuming this on every future login
			stateService.mutate(s -> s.isTutorialStripPending() ? s.withTutorialStripPending(false) : s);
		}

		// The Charter Office: refresh the scalars the panel reads off the EDT, and
		// settle any open deed escrow. Deliberately below the load block's return,
		// so the first tick that sees a hold runs against a fully restored board.
		charterService.tick();
	}

	/** First style roll + first task offers, in that order (ceremonies queue sequentially). */
	private void beginJourneyIfFresh()
	{
		var state = stateService.get();
		if (state == null)
		{
			return;
		}
		if (state.getAllowedStyle() == null)
		{
			styleService.roll(styleTracker.currentTick());
			// between the roulette and the task offers, so the three ceremonies
			// queue in the order the player reads them: colours, kit, work
			redeemFirstColoursChestIfOwed();
		}
		if (state.getActiveTask() == null
			&& (state.getPendingOffers() == null || state.getPendingOffers().isEmpty()))
		{
			taskService.rollOffers();
		}
	}

	/**
	 * Deal the free chest the opening style roll armed, now that the card
	 * database can say what that style actually swings.
	 *
	 * <p>Idempotence rides on the persisted owed flag, not on "have we been here
	 * before": a client that died between the roll and the reveal still gets its
	 * gift on the next login, and an account that already collected one can never
	 * be handed a second. A no-op when the DB is not ready yet — the onReady hook
	 * registered at load time covers that case.
	 */
	private void redeemFirstColoursChestIfOwed()
	{
		var state = stateService.get();
		if (state == null || !state.isFirstColoursChestOwed() || state.getAllowedStyle() == null)
		{
			return;
		}
		com.gachaman.model.AttackStyle style =
			com.gachaman.model.AttackStyle.valueOf(state.getAllowedStyle());
		chestService.openFirstColoursChest(cardDatabase.weaponCardIdsForStyle(style));
	}

	/**
	 * Post-load baseline, idempotent: the default slots are deeded (weapon +
	 * body + ammo — pre-existing saves are migrated up), fresh accounts get
	 * the training cards, and empty default slots auto-assign starter gear so
	 * training equipment is usable out of the box.
	 */
	private void grantStarterCards()
	{
		var state = stateService.get();
		if (state == null)
		{
			return;
		}
		// migrate: ammo joined the default deeded slots
		if (!state.getDeededSlots().contains(com.gachaman.model.GearSlot.AMMO.name()))
		{
			stateService.mutate(s -> {
				java.util.Set<String> deeded = new java.util.HashSet<>(s.getDeededSlots());
				deeded.add(com.gachaman.model.GearSlot.WEAPON.name());
				deeded.add(com.gachaman.model.GearSlot.BODY.name());
				deeded.add(com.gachaman.model.GearSlot.AMMO.name());
				return s.withDeededSlots(deeded);
			});
		}

		// identity armour belongs to ONE account type — the game refuses to let
		// anyone else wear it, so grant only this account's set and take back
		// any foreign set an earlier build handed out
		int accountType = com.gachaman.service.IronmanGear.accountType(client);
		revokeForeignIronmanCards(accountType);
		state = stateService.get();
		if (state == null)
		{
			return;
		}

		// every save owns the starter cards (idempotent: grants only what's
		// missing; names absent from the card DB are skipped with a log line)
		java.util.List<String> starters = new java.util.ArrayList<>(java.util.Arrays.asList(
			"Training sword", "Training shield", "Training bow", "Training arrows"));
		starters.addAll(com.gachaman.service.IronmanGear.cardNames(accountType));
		java.util.Set<Integer> ownedIds = new java.util.HashSet<>();
		for (com.gachaman.model.OwnedCard owned : state.getOwnedCards())
		{
			if (!owned.isHologram())
			{
				ownedIds.add(owned.getCardId());
			}
		}
		java.util.List<com.gachaman.model.OwnedCard> granted = new java.util.ArrayList<>();
		for (String name : starters)
		{
			com.gachaman.data.CardDefinition card = cardDatabase.cardByName(name);
			if (card == null)
			{
				log.warn("Starter card not found in DB: {}", name);
				continue;
			}
			if (!ownedIds.contains(card.getCardId()))
			{
				granted.add(new com.gachaman.model.OwnedCard(
					java.util.UUID.randomUUID().toString(), card.getCardId(), null,
					com.gachaman.model.Variant.NORMAL, System.currentTimeMillis(), "starter", 0));
			}
		}
		if (!granted.isEmpty())
		{
			stateService.mutate(s -> {
				java.util.List<com.gachaman.model.OwnedCard> owned = new java.util.ArrayList<>(s.getOwnedCards());
				owned.addAll(granted);
				return s.withOwnedCards(owned);
			});
			chatPing("Starter equipment cards added to your album: " + granted.size());
		}

		// auto-assign starter gear into EMPTY default slots (never overwrites)
		autoAssignStarter(com.gachaman.model.GearSlot.WEAPON, "Training sword");
		// an ironman's own platebody fills the starter body slot; a normal
		// account has no identity armour, so its body slot simply starts empty
		autoAssignStarter(com.gachaman.model.GearSlot.BODY,
			com.gachaman.service.IronmanGear.bodyCardName(accountType));
		autoAssignStarter(com.gachaman.model.GearSlot.AMMO, "Training arrows");

		// one-shot voucher grant (fresh and pre-existing saves alike): a free
		// Compactor + Extender so the style-cycle levers get tried early
		var current = stateService.get();
		if (current != null && !current.isStarterVouchersGranted())
		{
			stateService.mutate(s -> s.isStarterVouchersGranted() ? s
				: s.withFreeCompactors(s.getFreeCompactors() + 1)
					.withFreeExtenders(s.getFreeExtenders() + 1)
					.withStarterVouchersGranted(true));
			chatPing("A free Style Compactor and Style Extender voucher were added"
				+ " — apply one from the Shop tab during a task.");
		}
	}

	/**
	 * Earlier builds granted all six ironman sets to every profile. Take back
	 * the ones this account can never wear, and free any loadout slot holding
	 * one. Only starter-granted copies are revoked — a card genuinely pulled
	 * from a chest carries a different provenance and is left alone.
	 */
	private void revokeForeignIronmanCards(int accountType)
	{
		var state = stateService.get();
		if (state == null)
		{
			return;
		}
		java.util.Set<String> mine =
			new java.util.HashSet<>(com.gachaman.service.IronmanGear.cardNames(accountType));
		java.util.Set<Integer> foreignIds = new java.util.HashSet<>();
		for (String name : com.gachaman.service.IronmanGear.allCardNames())
		{
			if (mine.contains(name))
			{
				continue;
			}
			com.gachaman.data.CardDefinition card = cardDatabase.cardByName(name);
			if (card != null)
			{
				foreignIds.add(card.getCardId());
			}
		}
		java.util.Set<String> revoked = new java.util.HashSet<>();
		for (com.gachaman.model.OwnedCard owned : state.getOwnedCards())
		{
			if (!owned.isHologram() && "starter".equals(owned.getProvenance())
				&& foreignIds.contains(owned.getCardId()))
			{
				revoked.add(owned.getUuid());
			}
		}
		if (revoked.isEmpty())
		{
			return;
		}
		stateService.mutate(s -> {
			java.util.List<com.gachaman.model.OwnedCard> kept = new java.util.ArrayList<>();
			for (com.gachaman.model.OwnedCard owned : s.getOwnedCards())
			{
				if (!revoked.contains(owned.getUuid()))
				{
					kept.add(owned);
				}
			}
			java.util.Map<String, String> loadout = new java.util.HashMap<>(s.getLoadout());
			loadout.values().removeIf(revoked::contains);
			return s.withOwnedCards(kept).withLoadout(loadout);
		});
		log.debug("Gachaman: revoked {} ironman starter cards for account type {}",
			revoked.size(), accountType);
	}

	private void autoAssignStarter(com.gachaman.model.GearSlot slot, String cardName)
	{
		var state = stateService.get();
		if (cardName == null || state == null || state.getLoadout().containsKey(slot.name()))
		{
			return;
		}
		com.gachaman.data.CardDefinition card = cardDatabase.cardByName(cardName);
		if (card == null)
		{
			return;
		}
		java.util.Set<String> assigned = new java.util.HashSet<>(state.getLoadout().values());
		for (com.gachaman.model.OwnedCard owned : state.getOwnedCards())
		{
			if (!owned.isHologram() && owned.getCardId() == card.getCardId()
				&& !assigned.contains(owned.getUuid()))
			{
				loadoutService.assign(slot, owned.getUuid());
				return;
			}
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// This event fires AFTER the profile key has switched: saving here
		// would write the previous account's state under the new key. The
		// LOGIN_SCREEN handler already checkpointed under the correct key —
		// discard without saving and reload for the new profile.
		stateService.discard();
		stateLoadPending = true;
		taskService.resetTransientCombat();
		// dropped, not flushed: the profile key already moved, so writing the
		// tally would credit the NEW account's cards with the old account's
		// kills. The LOGIN_SCREEN handler above already flushed under the
		// correct key.
		serviceRecordService.drop();
		// the conviction ledger is scored against the OLD profile's taint counter
		complianceService.resetTransient();
		// the vote session is CLIENT-scoped while the offers it votes on are
		// per-profile: left running, a vote resolving after the switch would sign
		// a shared contract into an account that never rolled it, and its live
		// flags would block the new profile's orphan recovery below
		partyRollService.resetForDebug();
		// same reason as the vote session above: the last presence we sent named
		// the PREVIOUS profile's style, level and contract
		partyPresenceService.reset();
		// parked ceremonies from the previous account must never replay into
		// this one (their rewards are already persisted per-profile)
		ceremonyBus.clear();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// must run before ConfigManager's shutdown flush (priority ordering)
		revealOverlay.abortActiveCeremony();
		chestService.commitPending();
		serviceRecordService.flush();
		stateService.checkpoint();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GachamanConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		soundService.setEnabled(config.soundsEnabled());
		soundService.setVolume(config.soundVolume());
		if ("oneCardPerSlot".equals(event.getKey()))
		{
			// re-derive permissions + show/hide every loadout surface
			clientThread.invokeLater(() -> {
				permissionService.refresh();
				if (config.oneCardPerSlot())
				{
					loadoutTabButton.create();
				}
				else
				{
					loadoutTabButton.remove();
					loadoutOverlay.setOpen(false);
				}
			});
			javax.swing.SwingUtilities.invokeLater(gachamanPanel::updateLoadoutTabVisibility);
		}
	}

	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (event.getEntry().getMenuAction() != MenuAction.RUNELITE_OVERLAY)
		{
			return;
		}
		if (event.getOverlay() == loadoutButtonOverlay
			&& LoadoutButtonOverlay.TOGGLE_OPTION.equals(event.getEntry().getOption()))
		{
			loadoutOverlay.toggle();
			return;
		}
		if (event.getOverlay() == gachaInfoboxOverlay
			&& GachaInfoboxOverlay.OPTION_OPEN.equals(event.getEntry().getOption()))
		{
			String target = event.getEntry().getTarget();
			Tuning.Chest tier = GachaInfoboxOverlay.chestTierFromTarget(target);
			if (tier != null)
			{
				chestService.openChest(tier);
				return;
			}
			String tag = GachaInfoboxOverlay.themedSetTagFromTarget(target);
			if (tag != null)
			{
				chestService.openThemedChest(tag);
			}
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		String command = event.getCommand();
		if ("gachaparty".equalsIgnoreCase(command))
		{
			String[] partyArgs = event.getArguments();
			if (partyArgs.length > 0 && "no".equalsIgnoreCase(partyArgs[0]))
			{
				partyRollService.decline();
			}
			else if (partyArgs.length > 0 && "start".equalsIgnoreCase(partyArgs[0]))
			{
				partyRollService.forceStart(); // host only
			}
			else if (partyArgs.length > 0 && "cancel".equalsIgnoreCase(partyArgs[0]))
			{
				partyRollService.cancelRoll(); // host only
			}
			else
			{
				partyRollService.agree(); // proposes when no proposal is live
			}
			return;
		}
		if (!config.debugCommands())
		{
			return;
		}
		String[] args = event.getArguments();
		switch (command.toLowerCase())
		{
			case "gachagive":
			{
				long amount = args.length > 0 ? Long.parseLong(args[0]) : 10000;
				stateService.mutate(s -> s.withGc(s.getGc() + amount));
				debugChat("+" + amount + " GC");
				break;
			}
			case "gachachest":
			{
				Tuning.Chest tier = args.length > 0
					? Tuning.Chest.valueOf(args[0].toUpperCase())
					: Tuning.Chest.BATTERED;
				stateService.mutate(s -> s.withGc(s.getGc() + Tuning.CHEST_PRICE_GC.get(tier)));
				if (chestService.openChest(tier) == null)
				{
					debugChat("Chest could not open (rusted away / busy / DB not ready).");
				}
				break;
			}
			case "gachatask":
				taskService.rollOffers();
				break;
			case "gachastyle":
				styleService.roll(styleTracker.currentTick());
				break;
			case "gachatoken":
				stateService.mutate(s -> s.withRerollTokens(s.getRerollTokens() + 1));
				debugChat("+1 reroll token");
				break;
			case "gachacleartaint":
				complianceService.clearAllTaint();
				debugChat("Taint cleared.");
				break;
			case "gachacleartask":
				// testing cheat: wipe the active contract + rolled offers so a
				// fresh (party) roll is immediately possible on this account
				stateService.mutate(s -> s
					.withActiveTask(null)
					.withPendingOffers(new java.util.ArrayList<>()));
				taskService.resetTransientCombat();
				serviceRecordService.flush(); // the wiped contract's kills were still served
				partyRollService.resetForDebug();
				debugChat("Active task and rolled offers cleared.");
				break;
			case "gachacosmetics":
			{
				// audit: low-stat untiered cards still in the DB (novelty suspects)
				int threshold = args.length > 0 ? Integer.parseInt(args[0]) : 6;
				java.util.List<String> suspects = cardDatabase.lowStatSuspects(threshold);
				debugChat("Low-stat suspects (total bonus <= " + threshold + "): "
					+ suspects.size());
				int shown = 0;
				for (String suspect : suspects)
				{
					if (++shown > 25)
					{
						debugChat("… and " + (suspects.size() - 25) + " more");
						break;
					}
					debugChat("  " + suspect);
				}
				break;
			}
			case "gachabutton":
			{
				// loadout-button diagnostics: overlay state + raw widget state
				debugChat(loadoutButtonOverlay.diagnostics());
				net.runelite.api.widgets.Widget root =
					client.getWidget(net.runelite.api.gameval.InterfaceID.Wornitems.UNIVERSE);
				net.runelite.api.widgets.Widget head =
					client.getWidget(net.runelite.api.gameval.InterfaceID.Wornitems.SLOT0);
				debugChat("equip root: " + (root == null ? "null"
					: (root.isHidden() ? "hidden" : String.valueOf(root.getBounds())))
					+ " | head slot: " + (head == null ? "null"
					: (head.isHidden() ? "hidden" : String.valueOf(head.getBounds()))));
				break;
			}
			default:
				break;
		}
	}

	private void clientThreadInvokeCreateButton()
	{
		clientThread.invokeLater(loadoutTabButton::create);
	}

	private void clientThreadInvokeRemoveButton()
	{
		clientThread.invokeLater(loadoutTabButton::remove);
	}

	private void debugChat(String message)
	{
		debugChatAlways(message);
	}

	/**
	 * Informational chat (grants, summaries, milestones) — suppressible via the
	 * Chat notifications setting. Enforcement feedback (penalties, refunds,
	 * blocked actions) uses {@link #debugChatAlways} and is never suppressed:
	 * a silent penalty reads as a bug.
	 */
	private void chatPing(String message)
	{
		if (config.chatPings())
		{
			debugChatAlways(message);
		}
	}

	/**
	 * Fires once, on the kill where the contract latches on to the Slayer bonus.
	 * chatPing rather than debugChatAlways: this is a grant, not enforcement, so
	 * it honours the chat-notifications setting — the sidebar and the task
	 * overlay both state the bonus permanently, so muting chat cannot make it a
	 * surprise.
	 */
	private void announceDoubleDocket()
	{
		chatPing("<col=6ec86e>Double Docket!</col> This contract is also your Slayer task —"
			+ " completion pays x" + Tuning.DOUBLE_DOCKET_MULT + ". It stays locked in"
			+ " even if you finish the Slayer task first.");
	}

	private void debugChatAlways(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("<col=b25be2>Gachaman:</col> " + message)
			.build());
	}

	/**
	 * Card-DB cache upgrades can MERGE card groups (the v4 rebuild folded the
	 * stale "Ahrim's staff 100"-style degrade duplicates into their base
	 * cards). An owned card whose id no longer exists is remapped through the
	 * item index — a card id IS the group's lowest item id, which still
	 * resolves to the surviving merged card. If that would duplicate an
	 * already-owned (card, variant) pair, the stale copy is dropped instead
	 * (exactly what the dupe rule would have done without the cache bug).
	 * Loadout assignments key on card uuid, so remapping preserves them.
	 */
	private void healStaleCardIds()
	{
		if (!cardDatabase.isReady())
		{
			return;
		}
		stateService.mutate(s -> {
			if (s.getOwnedCards() == null || s.getOwnedCards().isEmpty())
			{
				return s;
			}
			boolean changed = false;
			java.util.Set<String> seen = new java.util.HashSet<>();
			java.util.List<com.gachaman.model.OwnedCard> healed =
				new java.util.ArrayList<>(s.getOwnedCards().size());
			for (com.gachaman.model.OwnedCard card : s.getOwnedCards())
			{
				com.gachaman.model.OwnedCard next = card;
				if (!card.isHologram() && cardDatabase.card(card.getCardId()) == null)
				{
					com.gachaman.data.CardDefinition target =
						cardDatabase.cardForItem(card.getCardId());
					if (target != null)
					{
						// carry the service record through the remap — a card-DB
						// heal must not reset the odometer
						next = new com.gachaman.model.OwnedCard(card.getUuid(),
							target.getCardId(), card.getTierKey(), card.getVariant(),
							card.getAcquiredAtMs(), card.getProvenance(), card.getKillsServed());
						changed = true;
					}
				}
				String key = next.isHologram()
					? "holo:" + next.getTierKey() : next.getCardId() + ":" + next.getVariant();
				if (!seen.add(key) && next != card)
				{
					continue; // remap collided with an existing copy — drop the stale one
				}
				healed.add(next);
			}
			return changed ? s.withOwnedCards(healed) : s;
		});
	}

	/**
	 * Heuristic for the "why was my magic build fined for melee?" trap: a
	 * castable staff equipped with no autocast spell selected means
	 * auto-retaliate answers incoming hits with the staff's melee bash — a
	 * genuine melee attack the player never clicked.
	 */
	private boolean autoRetaliateStaffBashLikely()
	{
		try
		{
			int category = client.getVarbitValue(
				net.runelite.api.gameval.VarbitID.COMBAT_WEAPON_CATEGORY);
			// 18 = staff, 21 = bladed staff — the categories with a Spell tab
			boolean castableStaff = category == 18 || category == 21;
			boolean noAutocast = client.getVarbitValue(
				net.runelite.api.gameval.VarbitID.AUTOCAST_SET) == 0;
			return castableStaff && noAutocast;
		}
		catch (Exception e)
		{
			return false;
		}
	}
}
