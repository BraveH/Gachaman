package com.gachaman;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import javax.inject.Singleton;
import javax.inject.Inject;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.overlay.*;
import com.gachaman.party.*;
import com.gachaman.service.*;
import com.gachaman.ui.loadout.*;
import com.gachaman.ui.panel.*;
import com.google.gson.*;
import com.google.inject.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.input.*;
import net.runelite.client.party.*;
import net.runelite.client.party.messages.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;

@Slf4j
@PluginDescriptor(
	name = "Gachaman",
	description = "RNG gamemode: attack styles rolled by fate, equipment gated behind collectible cards, chests, kill contracts and ceremonies. Removes menu entries on card-locked equipment (client-side only).",
	tags = {"gamemode", "gacha", "cards", "chest", "challenge"}
)
public class GachamanPlugin extends Plugin {
	// clients & managers
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
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
	@Inject
	private UpdateService updateService;

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
	private CeremonyBus ceremonyBus;
	@Inject
	private StyleTracker styleTracker;
	@Inject
	private KillTracker killTracker;
	@Inject
	private ComplianceService complianceService;
	@Inject
	private QuestExemptionService questExemptionService;
	@Inject
	private TaskService taskService;
	@Inject
	private StyleService styleService;
	@Inject
	private ChestService chestService;
	@Inject
	private MilestoneService milestoneService;
	@Inject
	private GraduationService graduationService;
	@Inject
	private FirstsService firstsService;
	@Inject
	private TimelineService timelineService;
	@Inject
	private BossKcService bossKcService;
	@Inject
	private SetPerkService setPerkService;
	@Inject
	private PermissionService permissionService;
	@Inject
	private EquipBlockService equipBlockService;
	@Inject
	private SafeModeService safeModeService;

	@Inject
	private PartyRollService partyRollService;
	@Inject
	private PartyPresenceService partyPresenceService;
	@Inject
	private CombatBlockService combatBlockService;
	@Inject
	private LoadoutService loadoutService;
	@Inject
	private ServiceRecordService serviceRecordService;
	@Inject
	private SlayerAlignment slayerAlignment;
	@Inject
	private IronmanGear ironmanGear;
	@Inject
	private ConsignmentService consignmentService;
	@Inject
	private TollService tollService;

	// overlays & UI
	@Inject
	private RevealOverlay revealOverlay;
	@Inject
	private RevealInputListener revealInputListener;
	@Inject
	private KillJuiceOverlay killJuiceOverlay;
	@Inject
	private ForbiddenItemOverlay forbiddenItemOverlay;
	@Inject
	private SlotLockOverlay slotLockOverlay;
	@Inject
	private TaskNpcHighlightOverlay taskNpcHighlightOverlay;
	@Inject
	private TaskProgressOverlay taskProgressOverlay;
	@Inject
	private LoadoutButtonOverlay loadoutButtonOverlay;
	@Inject
	private LoadoutTabButton loadoutTabButton;
	@Inject
	private LoadoutOverlay loadoutOverlay;
	@Inject
	private LoadoutInputListener loadoutInputListener;
	@Inject
	private GachamanPanel gachamanPanel;

	/**
	 * Every party packet type this plugin puts on the wire, in one place.
	 *
	 * <p>startUp registers the list and shutDown unregisters it, so the two can
	 * no longer drift — a message registered but never unregistered leaks a
	 * decoder across a disable/enable cycle, and one added to startUp alone was
	 * previously a silent one-line omission in a nine-line block.
	 */
	private static final List<Class<? extends PartyMemberMessage>> PARTY_MESSAGES = Arrays.asList(
		PartyRollProposeMessage.class, PartyRollResponseMessage.class,
		PartyRollStartMessage.class, PartyRollCancelMessage.class,
		PartyRollVoteMessage.class, PartyRollResolveMessage.class,
		PartyKillsMessage.class, PartyCompleteMessage.class, GachaPresenceMessage.class);

	private NavigationButton navButton;
	private boolean stateLoadPending;
	private boolean wasOnTutorial;

	/** Loud, immediate feedback the moment a forbidden-style attack lands. */
	private final ComplianceService.Listener complianceFeedback = new ComplianceService.Listener() {
		@Override
		public void onForbiddenAttack(AttackStyle used,
			AttackStyle allowed, long penaltyGc) {
			debugChat("<col=e83c3c>Forbidden " + used.getDisplayName()
				+ " attack!</col> Only " + allowed.getDisplayName()
				+ " is allowed. <col=e83c3c>-" + penaltyGc + " GC</col>");
			if (used == AttackStyle.MELEE
				&& allowed == AttackStyle.MAGIC
				&& autoRetaliateStaffBashLikely()) {
				debugChat("Tip: auto-retaliate swings your staff's melee bash between casts —"
					+ " set an autocast spell or turn auto-retaliate off.");
			}
		}

		@Override
		public void onForbiddenPardoned(int tick, long refundedGc) {
			debugChat("<col=6ec86e>Pardoned.</col> That attack was actually Magic —"
				+ (refundedGc > 0 ? " +" + refundedGc + " GC refunded." : " no penalty kept."));
		}

		@Override
		public void onTaintAdded(int newTaint) {
			debugChat("<col=e83c3c>Tainted kill — no reward.</col> Taint x" + newTaint
				+ ": all income halved until worked off.");
		}

		@Override
		public void onTaintCleared(int cleared, int remaining) {
			if (remaining == 0 && cleared > 1)
				debugChat("<col=6ec86e>All taint cleansed.</col>");
		}
	};

	/**
	 * Stable tap reference: a method reference is a NEW object each time, so
	 * addTap/removeTap must share this single instance to stay paired.
	 */
	private final Consumer<CeremonyBus.Request> timelineTap =
		request -> timelineService.onCeremony(request);

	/** Chat notice when the ironman assisted-kill penalty halves a kill's credit. */
	private final TaskService.Listener assistedKillFeedback = new TaskService.Listener() {
		@Override
		public void onKillFeedback(TaskService.KillFeedback feedback) {
			if (feedback.isAssistedHalfCredit()) {
				debugChat("<col=e8a33c>Assisted kill — half credit.</col> Another player"
					+ " damaged that monster; ironman kills count half kc and half GC.");
			}
		}


		@Override
		public void onTaskCompleted(TaskService.TaskCompletionSummary summary) {
			// never suppressible: a contract that paid ten times the usual
			// completion should say so, or it reads as a payout bug
			double mult = summary.getCompletionMilestoneMult();
			if (mult > 1.0) {
				debugChat("<col=ff9040>Milestone contract!</col> Your "
					+ ordinal(summary.getTaskNumber()) + " completion paid <col=ff9040>x"
					+ (mult == Math.rint(mult) ? String.valueOf((int) mult) : String.valueOf(mult))
					+ "</col> its usual reward.");
			}
		}

	};

	/**
	 * 1st, 2nd, 3rd, 4th... including the 11th/12th/13th exceptions.
	 *
	 * <p>The teens are folded in by mapping that whole band to the sentinel 0,
	 * which matches none of the 1/2/3 arms and so falls through to "th" — the
	 * same answer the explicit early return used to give. Negatives are safe
	 * for the same reason: n % 10 is then negative or zero and lands on "th".
	 *
	 * <p>Package-private, like chestArg below, purely so a test can pin the
	 * arithmetic — the milestone chat line it feeds cannot be reached without a
	 * live client, this can.
	 */
	static String ordinal(int n) {
		int m = n % 100 >= 11 && n % 100 <= 13 ? 0 : n % 10;
		return n + (m == 1 ? "st" : m == 2 ? "nd" : m == 3 ? "rd" : "th");
	}

	@Provides
	GachamanConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(GachamanConfig.class);
	}

	@Provides
	@Singleton
	TierTable provideTierTable(Gson gson) {
		return TierTable.load(gson);
	}

	@Provides
	@Singleton
	MonsterTable provideMonsterTable(Gson gson) {
		return MonsterTable.load(gson);
	}

	@Provides
	@Singleton
	BossTable provideBossTable(Gson gson) {
		return BossTable.load(gson);
	}

	@Provides
	@Singleton
	SetTable provideSetTable(Gson gson) {
		return SetTable.load(gson);
	}

	@Provides
	@Singleton
	QuestMonsterTable provideQuestMonsterTable(Gson gson) {
		return QuestMonsterTable.load(gson);
	}

	/**
	 * The overlays this plugin manages, in render-registration order.
	 *
	 * <p>A method rather than a field because these are {@code @Inject} fields:
	 * a field initialiser would run before Guice has populated them and capture
	 * a list of nulls. Both call sites run after injection.
	 *
	 * <p>loadoutButtonOverlay is deliberately absent — it is not managed, and
	 * adding it would draw a SECOND toggle button on the worn-equipment panel
	 * next to the live one LoadoutTabButton already plants there.
	 */
	private List<Overlay> overlays() {
		return Arrays.asList(revealOverlay, killJuiceOverlay, forbiddenItemOverlay,
			slotLockOverlay, taskNpcHighlightOverlay, taskProgressOverlay, loadoutOverlay);
	}

	/**
	 * Everything that subscribes to the RuneLite event bus, in registration
	 * order — startUp registers the list, shutDown unregisters the same one, so
	 * a service can never be attached without also being detached.
	 *
	 * <p>Same reason as {@link #overlays()} for it being a method: these are
	 * injected fields, still null at construction time.
	 */
	private List<Object> busSubscribers() {
		return Arrays.asList(styleTracker, killTracker, graduationService, milestoneService,
			bossKcService, safeModeService, equipBlockService, combatBlockService,
			loadoutTabButton, partyRollService, partyPresenceService);
	}

	@Override
	protected void startUp() {
		updateService.start();
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
		// the running tally: inked on the offer scrolls, and beside each name on
		// the party page, both off the one snapshot method
		revealOverlay.setPartyVoteSupplier(partyRollService::voteView);
		gachamanPanel.setVoteViewSupplier(partyRollService::voteView);
		permissionService.start();
		setPerkService.start();
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

		// The Consignment's offer screen. Without this the service has no
		// presenter and offerOrRoll() silently takes the ordinary roll every
		// time — no error, no log, just a feature that is never there. It is
		// wired here rather than injected into ConsignmentService because the
		// overlay already depends on the service (it reads the live offer to
		// draw it), and injecting the overlay back would close a Guice cycle.
		consignmentService.setPresenter(revealOverlay);

		// The Toll's pull. Same shape and the same reason: ChestService owns the
		// tier-scoped opener, and handing it over as a hook keeps TollService
		// free of a dependency on the chest — an unwired hook refuses the
		// purchase and takes nothing, which is what kept the tree green while
		// openTollChest was still unwritten.
		tollService.setDealer(chestService::openTollChest);

		// overlays
		for (Overlay overlay : overlays()) {
			overlayManager.add(overlay);
		}

		// input (modal reveal listener first so it wins while a ceremony is up)
		mouseManager.registerMouseListener(revealInputListener);
		mouseManager.registerMouseWheelListener(revealInputListener);
		keyManager.registerKeyListener(revealInputListener);
		mouseManager.registerMouseListener(loadoutInputListener);

		// event bus sub-services
		for (Object subscriber : busSubscribers()) {
			eventBus.register(subscriber);
		}
		clientThread.invokeLater(loadoutTabButton::create);

		// party messages
		for (var message : PARTY_MESSAGES) {
			wsClient.registerMessage(message);
		}

		// sidebar
		navButton = NavigationButton.builder()
			.tooltip("Gachaman")
			.icon(ImageUtil.loadImageResource(getClass(), "/com/gachaman/ui/panel-icon.png"))
			.priority(6)
			.panel(gachamanPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		gachamanPanel.start();
		// party UI hides entirely when the Party contracts setting is off
		gachamanPanel.setInPartySupplier(() -> partyService.isInParty() && config.partyRollsEnabled());

		if (client.getGameState() == GameState.LOGGED_IN)
			stateLoadPending = true;
		log.info("Gachaman started");
	}

	@Override
	protected void shutDown() {
		updateService.stop();
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
		revealOverlay.setPartyVoteSupplier(null);
		complianceService.removeListener(complianceFeedback);
		taskService.removeListener(serviceRecordService);
		taskService.removeListener(firstsService);
		complianceService.removeListener(firstsService);
		chestService.removeChestListener(firstsService);
		loadoutService.setAssignHook(null);
		ceremonyBus.removeTap(timelineTap);
		chestService.removeChestListener(timelineService);
		complianceService.removeListener(timelineService);

		gachamanPanel.stop();
		gachamanPanel.setInPartySupplier(() -> false); // null is ignored by the setter
		clientToolbar.removeNavigation(navButton);
		navButton = null;

		for (var message : PARTY_MESSAGES) {
			wsClient.unregisterMessage(message);
		}

		for (Object subscriber : busSubscribers()) {
			eventBus.unregister(subscriber);
		}
		clientThread.invokeLater(loadoutTabButton::remove);

		mouseManager.unregisterMouseListener(revealInputListener);
		mouseManager.unregisterMouseWheelListener(revealInputListener);
		keyManager.unregisterKeyListener(revealInputListener);
		mouseManager.unregisterMouseListener(loadoutInputListener);

		for (Overlay overlay : overlays()) {
			overlayManager.remove(overlay);
		}

		permissionService.stop();
		setPerkService.stop();
		log.info("Gachaman stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		// read once: the four tests below each asked the event for it again
		GameState gameState = event.getGameState();
		if (gameState == GameState.LOGGED_IN) {
			stateLoadPending = true;
			updateService.onLoggedIn();
		}
		else if (gameState == GameState.LOGIN_SCREEN) {
			// credit kills still waiting on the loot oracle BEFORE checkpointing,
			// so the tally commitAndCheckpoint() writes includes them
			killTracker.flushPending();
			commitAndCheckpoint();
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
		}
		if (gameState != GameState.LOGGED_IN) {
			// the board's hit test outlives the frames that drew it
			loadoutOverlay.setOpen(false);
		}
		if (gameState == GameState.LOGGING_IN
			|| gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR) {
			// off-world without a logout teardown. Ceremonies are only ever drawn
			// in game, so one left claimed here would sit invisible over the
			// welcome screen — exactly where "Click here to play" is the only
			// thing the user can click.
			revealOverlay.abortActiveCeremony();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		// a ceremony's phase clock only ticks while it is being drawn, so one
		// that stopped rendering can never time itself out — drop it here
		revealOverlay.pruneStaleModal();

		if (stateLoadPending && client.getGameState() == GameState.LOGGED_IN
			&& configManager.getRSProfileKey() != null) {
			// profile key must exist before load, or a fresh state could be
			// created under (and later saved to) the wrong profile
			stateLoadPending = false;
			if (!stateService.isLoaded())
				stateService.load(milestoneService.combatLevel());
			cardDatabase.beginBuild(tierTable, setTable);
			chestService.recoverPending(); // crash-interrupted reveal: auto-commit
			// A Consignment offer that was on screen when the client died left the
			// style roll OWED and unspent — deliberately, so the roll is never lost.
			// Take it now, as an ordinary wheel spin, since the offer it belonged to
			// is gone. This has to live here and not in StyleTracker's LOGGED_IN
			// branch: state is still null at LOGGED_IN (the profile key does not
			// exist yet, which is the whole reason this block is deferred to a later
			// GameTick), so a drain fired there would find nothing and no-op.
			consignmentService.drainOwedRoll(styleTracker.currentTick());
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
			wasOnTutorial = TutorialGate.onTutorial(client);
			if (!wasOnTutorial)
				beginJourneyIfFresh();
			return;
		}

		// Tutorial Island exit: the locks switch on. Nothing is taken off — the
		// island's own gear is card-granted in grantStarterCards, so what the
		// player walks ashore wearing is exactly what they may keep wearing
		if (wasOnTutorial && stateService.isLoaded()
			&& !TutorialGate.onTutorial(client)) {
			wasOnTutorial = false;
			beginJourneyIfFresh();
			cardDatabase.onReady(this::assignWornGear);
		}
	}

	/**
	 * Put the cards for what the player is ACTUALLY wearing into the loadout,
	 * once, as they step off the island.
	 *
	 * <p>Granting the island's cards is not enough on its own: under the
	 * default one-card-per-slot rule a card only permits its items once it is
	 * assigned to a slot, so a player who walks ashore swinging a bronze sword
	 * whose card sits unassigned in the album could take it off and never put
	 * it back — the exact trap the strip was there to avoid.
	 *
	 * <p>Overwrites whatever autoAssignStarter put there, deliberately: what is
	 * on their back beats what a default chose for them. Slots they have no
	 * deed for are skipped, since assigning into one permits nothing.
	 *
	 * <p>Needs no "already ran" flag of its own. wasOnTutorial is only ever set
	 * by a login that happens ON the island, and the exit branch clears it, so
	 * this fires once for the one transition a character can never repeat. The
	 * onReady queue is no second path either: it is drained and cleared once,
	 * and the DB latches ready for the rest of the session.
	 */
	private void assignWornGear() {
		var state = stateService.get();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (state == null || worn == null)
			return;
		// snapshot: assign() mutates through the service, so the loadout read
		// here goes stale — `assigned` is tracked by hand below. The owned-card
		// list does not, since assigning never mints or removes a card.
		Set<String> assigned = new HashSet<>(state.getLoadout().values());
		for (Item item : worn.getItems()) {
			if (item == null || item.getId() <= 0)
				continue;
			CardDefinition card = cardDatabase.cardForItem(item.getId());
			// no card = cosmetic or untracked; no deed = the slot permits nothing
			if (card == null || !permissionService.isSlotDeeded(card.getSlot()))
				continue;
			for (OwnedCard owned : state.getOwnedCards()) {
				if (!owned.isHologram() && owned.getCardId() == card.getCardId()
					&& !assigned.contains(owned.getUuid())
					&& loadoutService.assign(card.getSlot(), owned.getUuid())) {
					assigned.add(owned.getUuid());
					break;
				}
			}
		}
	}

	/**
	 * The opening style roll (and the free colours chest it arms). Contracts are
	 * NOT dealt here, or anywhere else automatically.
	 *
	 * <p>A roll cannot be cancelled — a dealt board must be decided — so dealing
	 * one the player did not ask for spends something of theirs on their behalf.
	 * It also made party contracts unreachable: undecided offers count as busy,
	 * so an auto-rolled board refused the player's own {@code propose()} AND
	 * auto-excused them from everyone else's proposal. Two members log in, both
	 * hold boards neither asked for, and no party can form until both clear one
	 * by hand. Rolling is one click on the Contract panel.
	 */
	private void beginJourneyIfFresh() {
		var state = stateService.get();
		if (state == null || state.getAllowedStyle() != null)
			return;
		styleService.roll(styleTracker.currentTick());
		// after the roulette, so the two ceremonies queue in the order the
		// player reads them: colours, then kit
		redeemFirstColoursChestIfOwed();
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
	private void redeemFirstColoursChestIfOwed() {
		var state = stateService.get();
		if (state == null || !state.isFirstColoursChestOwed() || state.getAllowedStyle() == null)
			return;
		AttackStyle style = AttackStyle.valueOf(state.getAllowedStyle());
		chestService.openFirstColoursChest(cardDatabase.weaponCardIdsForStyle(style));
	}

	/**
	 * Post-load baseline, idempotent: the default slots are deeded (weapon +
	 * body + ammo — pre-existing saves are migrated up), fresh accounts get
	 * the training cards, and empty default slots auto-assign starter gear so
	 * training equipment is usable out of the box.
	 */
	private void grantStarterCards() {
		var state = stateService.get();
		if (state == null)
			return;
		// migrate: ammo joined the default deeded slots
		if (!state.getDeededSlots().contains(GearSlot.AMMO.name())) {
			stateService.mutate(s -> {
				Set<String> deeded = new HashSet<>(s.getDeededSlots());
				deeded.add(GearSlot.WEAPON.name());
				deeded.add(GearSlot.BODY.name());
				deeded.add(GearSlot.AMMO.name());
				return s.withDeededSlots(deeded);
			});
		}

		// identity armour belongs to ONE account type — the game refuses to let
		// anyone else wear it, so grant only this account's set and take back
		// any foreign set an earlier build handed out
		int accountType = IronmanGear.accountType(client);
		revokeForeignIronmanCards(accountType);
		state = stateService.get();
		if (state == null)
			return;

		// every save owns the starter cards (idempotent: grants only what's
		// missing; names absent from the card DB are skipped with a log line).
		// The Tutorial Island set is in here because the island force-equips it:
		// walking ashore in gear no card unlocks would strand a fresh account in
		// items it could never put back on, so the island's own kit is granted
		// rather than taken away. Names are item names — a rename resolves to
		// null and is skipped, so the worst case is a missing card, not a crash
		List<String> starters = new ArrayList<>(Arrays.asList(
			"Training sword", "Training shield", "Training bow", "Training arrows",
			"Bronze axe", "Bronze pickaxe", "Bronze dagger", "Bronze sword",
			"Shortbow", "Wooden shield", "Bronze arrow"));
		starters.addAll(ironmanGear.cardNames(accountType));
		Set<Integer> ownedIds = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (!owned.isHologram())
				ownedIds.add(owned.getCardId());
		}
		List<OwnedCard> granted = new ArrayList<>();
		for (String name : starters) {
			CardDefinition card = cardDatabase.cardByName(name);
			if (card == null) {
				log.warn("Starter card not found in DB: {}", name);
				continue;
			}
			if (!ownedIds.contains(card.getCardId())) {
				granted.add(new OwnedCard(
					UUID.randomUUID().toString(), card.getCardId(), null,
					Variant.NORMAL, System.currentTimeMillis(), "starter", 0));
			}
		}
		if (!granted.isEmpty()) {
			stateService.mutate(s -> {
				List<OwnedCard> owned = new ArrayList<>(s.getOwnedCards());
				owned.addAll(granted);
				return s.withOwnedCards(owned);
			});
			chatPing("Starter equipment cards added to your album: " + granted.size());
		}

		// auto-assign starter gear into EMPTY default slots (never overwrites)
		autoAssignStarter(GearSlot.WEAPON, "Training sword");
		// an ironman's own platebody fills the starter body slot; a normal
		// account has no identity armour, so its body slot simply starts empty
		autoAssignStarter(GearSlot.BODY, ironmanGear.bodyCardName(accountType));
		autoAssignStarter(GearSlot.AMMO, "Training arrows");

		// one-shot voucher grant (fresh and pre-existing saves alike): a free
		// Compactor + Extender so the style-cycle levers get tried early
		var current = stateService.get();
		if (current != null && !current.isStarterVouchersGranted()) {
			stateService.mutate(s -> s.isStarterVouchersGranted() ? s
				: s.withFreeCompactors(s.getFreeCompactors() + 1)
					.withFreeExtenders(s.getFreeExtenders() + 1)
					.withStarterVouchersGranted(true));
			chatPing("A free Style Compactor and Style Extender voucher were added"
				+ " — apply one from the Shop tab during a contract.");
		}
	}

	/**
	 * Earlier builds granted all six ironman sets to every profile. Take back
	 * the ones this account can never wear, and free any loadout slot holding
	 * one. Only starter-granted copies are revoked — a card genuinely pulled
	 * from a chest carries a different provenance and is left alone.
	 */
	private void revokeForeignIronmanCards(int accountType) {
		var state = stateService.get();
		if (state == null)
			return;
		Set<String> mine = new HashSet<>(ironmanGear.cardNames(accountType));
		Set<Integer> foreignIds = new HashSet<>();
		for (String name : ironmanGear.allCardNames()) {
			if (mine.contains(name))
				continue;
			CardDefinition card = cardDatabase.cardByName(name);
			if (card != null)
				foreignIds.add(card.getCardId());
		}
		Set<String> revoked = new HashSet<>();
		for (OwnedCard owned : state.getOwnedCards()) {
			if (!owned.isHologram() && "starter".equals(owned.getProvenance())
				&& foreignIds.contains(owned.getCardId())) {
				revoked.add(owned.getUuid());
			}
		}
		if (revoked.isEmpty())
			return;
		stateService.mutate(s -> {
			List<OwnedCard> kept = new ArrayList<>();
			for (OwnedCard owned : s.getOwnedCards()) {
				if (!revoked.contains(owned.getUuid()))
					kept.add(owned);
			}
			Map<String, String> loadout = new HashMap<>(s.getLoadout());
			loadout.values().removeIf(revoked::contains);
			return s.withOwnedCards(kept).withLoadout(loadout);
		});
		log.debug("Gachaman: revoked {} ironman starter cards for account type {}",
			revoked.size(), accountType);
	}

	private void autoAssignStarter(GearSlot slot, String cardName) {
		var state = stateService.get();
		if (cardName == null || state == null || state.getLoadout().containsKey(slot.name()))
			return;
		CardDefinition card = cardDatabase.cardByName(cardName);
		if (card == null)
			return;
		Set<String> assigned = new HashSet<>(state.getLoadout().values());
		for (OwnedCard owned : state.getOwnedCards()) {
			if (!owned.isHologram() && owned.getCardId() == card.getCardId()
				&& !assigned.contains(owned.getUuid())) {
				loadoutService.assign(slot, owned.getUuid());
				return;
			}
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
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
		// ...and the Consignment keeps its own copy of the live offer, which
		// clear() cannot reach. Left alone, an offer raised on the previous
		// profile survives into this one as a stale `live` that the Overview
		// would render ("the house is making its offer now") for an account that
		// was never asked. Every other route out of an offer — accept, decline,
		// abort, reset — is covered from inside the service; this path is the one
		// that tears the queue down without going through any of them.
		consignmentService.abandon();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event) {
		// must run before ConfigManager's shutdown flush (priority ordering)
		commitAndCheckpoint();
	}

	/**
	 * Everything that has to reach disk before the session (or the client) can
	 * end, in the one order that is safe — shared verbatim by the logout path
	 * and the client-shutdown hook, which used to spell it out twice.
	 *
	 * <p>The order is the invariant, not an accident of how it was typed: both
	 * call sites ran these four in exactly this sequence before they were
	 * folded together, so do not reorder them here. The one reason recorded in
	 * the source is the flush: it must land BEFORE the checkpoint and under
	 * THIS profile's key, because the profile-changed handler discards without
	 * saving, so a tally written after the key moves credits the wrong account.
	 *
	 * <p>Not called from shutDown(), which needs the same four with three more
	 * steps interleaved (the renderer is detached first, and the ceremony bus
	 * is reset between the abort and the flush).
	 */
	private void commitAndCheckpoint() {
		revealOverlay.abortActiveCeremony();
		chestService.commitPending();
		serviceRecordService.flush();
		stateService.checkpoint();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!GachamanConfig.GROUP.equals(event.getGroup()))
			return;
		if ("oneCardPerSlot".equals(event.getKey())) {
			// re-derive permissions + show/hide every loadout surface
			clientThread.invokeLater(() -> {
				permissionService.refresh();
				if (config.oneCardPerSlot())
					loadoutTabButton.create();
				else {
					loadoutTabButton.remove();
					loadoutOverlay.setOpen(false);
				}
			});
			SwingUtilities.invokeLater(gachamanPanel::updateTabVisibility);
		}
	}

	// No OverlayMenuClicked handler on purpose. There used to be one, routing
	// LoadoutButtonOverlay's "Toggle" entry to loadoutOverlay.toggle(), but that
	// overlay is not in overlays() and so is never added to the OverlayManager:
	// an unmanaged Overlay never renders and never contributes a
	// RUNELITE_OVERLAY menu entry, so the handler could not fire. LoadoutTabButton
	// is the live replacement — a real widget child on the equipment interface
	// with its own native op — which is why nothing is missing on screen. The
	// loadoutButtonOverlay field stays: ::gachabutton prints its diagnostics.

	@Subscribe
	public void onCommandExecuted(CommandExecuted event) {
		String command = event.getCommand();
		// One read of the argument array, and one join of it, shared by every
		// branch below — four of them re-read getArguments() and two of them
		// re-joined it. Both are pure (a plain accessor on the event, and
		// String.join), so computing them up front for commands that never look
		// at them is unobservable.
		String[] args = event.getArguments();
		String npc = String.join(" ", args).trim(); // chat splits a name on spaces
		if ("gachaparty".equalsIgnoreCase(command)) {
			if (args.length > 0 && "no".equalsIgnoreCase(args[0]))
				partyRollService.decline();
			else if (args.length > 0 && "start".equalsIgnoreCase(args[0])) {
				partyRollService.forceStart(); // host only
			}
			else if (args.length > 0 && "cancel".equalsIgnoreCase(args[0])) {
				partyRollService.cancelRoll(); // host only
			}
			else {
				partyRollService.agree(); // proposes when no proposal is live
			}
			return;
		}
		// The quest-unlock escape hatch is NOT behind debugCommands: it exists
		// for a player who is locked out of a quest by a gap in the bundled
		// table, and telling them to first enable a developer setting is telling
		// them to stay stuck. Session-only, and the Overview tab shows every
		// override that is live so none is ever silently left on.
		if ("gachaunlock".equalsIgnoreCase(command)) {
			if (npc.isEmpty()) {
				debugChat("Usage: ::gachaunlock &lt;npc name&gt; — unblocks that NPC"
					+ " until you close the client. Please also report it on GitHub.");
			}
			else {
				questExemptionService.unlock(npc);
				debugChat("Unblocked \"" + npc + "\" for this session."
					+ " Undo with ::gacharelock " + npc);
				gachamanPanel.refresh();
			}
			return;
		}
		if ("gacharelock".equalsIgnoreCase(command)) {
			if (npc.isEmpty()) {
				int cleared = questExemptionService.relock(null);
				debugChat(cleared == 0
					? "No manual unlocks were active."
					: "Cleared " + cleared + " manual unlock" + (cleared == 1 ? "" : "s") + ".");
			}
			else {
				debugChat(questExemptionService.relock(npc) > 0
					? "Re-blocked \"" + npc + "\"."
					: "\"" + npc + "\" was not manually unlocked.");
			}
			// both arms ended with this; the panel's override list has to redraw
			// whether one name was re-blocked or the whole set was cleared
			gachamanPanel.refresh();
			return;
		}
		if (!config.debugCommands())
			return;
		// Locale.ROOT, not the default locale: in Turkish and Azeri "I" folds to
		// the dotless "ı", so a player who typed "::GachaGive" under a tr/az
		// client got "gachagıve" and fell through to the default arm. The same
		// trap runs the other way in chestArg below. setCardWear already folds
		// with Locale.ROOT — these two were the ones that missed it.
		switch (command.toLowerCase(Locale.ROOT)) {
			case "gachagive": {
				long amount = args.length > 0 ? Long.parseLong(args[0]) : 10000;
				stateService.mutate(s -> s.withGc(s.getGc() + amount));
				debugChat("+" + amount + " GC");
				break;
			}
			case "gachachest": {
				Tuning.Chest tier = chestArg(args);
				stateService.mutate(s -> s.withGc(s.getGc() + Tuning.CHEST_PRICE_GC.get(tier)));
				if (chestService.openChest(tier) == null)
					debugChat("Chest could not open (rusted away / busy / DB not ready).");
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
					.withPendingOffers(new ArrayList<>()));
				taskService.resetTransientCombat();
				serviceRecordService.flush(); // the wiped contract's kills were still served
				partyRollService.resetForDebug();
				debugChat("Active contract and rolled offers cleared.");
				break;
			case "gachacosmetics": {
				// audit: low-stat untiered cards still in the DB (novelty suspects)
				int threshold = args.length > 0 ? Integer.parseInt(args[0]) : 6;
				List<String> suspects = cardDatabase.lowStatSuspects(threshold);
				debugChat("Low-stat suspects (total bonus <= " + threshold + "): "
					+ suspects.size());
				debugList(suspects, 25, "");
				break;
			}
			case "gachabutton": {
				// loadout-button diagnostics: overlay state + raw widget state
				debugChat(loadoutButtonOverlay.diagnostics());
				Widget root = client.getWidget(InterfaceID.Wornitems.UNIVERSE);
				Widget head = client.getWidget(InterfaceID.Wornitems.SLOT0);
				debugChat("equip root: " + (root == null ? "null"
					: (root.isHidden() ? "hidden" : String.valueOf(root.getBounds())))
					+ " | head slot: " + (head == null ? "null"
					: (head.isHidden() ? "hidden" : String.valueOf(head.getBounds()))));
				break;
			}
			case "gachawear": {
				// stage a worn card face without grinding a thousand kills.
				// Wear is a pure function of killsServed and nothing reads it,
				// so this can only change how a face is painted.
				if (args.length == 0) {
					// no angle brackets in the usage line: chat runs it through
					// the RuneLite tag parser, which eats anything in them
					debugChat("Usage: ::gachawear none|hairline|cracked|shattered|N [name]"
						+ " — the name is a substring; leave it off to hit every card.");
					break;
				}
				CardWear stage = CardWear.parse(args[0]);
				int kills;
				if (stage != null)
					kills = Tuning.wearKills(stage);
				else {
					try {
						kills = Math.max(0, Integer.parseInt(args[0].trim()));
					}
					catch (NumberFormatException e) {
						debugChat("Not a wear stage or a kill count: " + args[0]);
						break;
					}
				}
				// the name filter is everything after the wear argument, rejoined
				// with the single spaces chat split it on
				setCardWear(kills, String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim());
				break;
			}
		}
	}

	/**
	 * ::gachachest's tier argument, defaulting to Battered.
	 *
	 * <p>Folded with Locale.ROOT because Tuning.Chest.GILDED is spelled with an
	 * "i": under a Turkish or Azeri default locale "gilded".toUpperCase() is
	 * "GİLDED" (dotted capital I), which matches no enum constant, so valueOf
	 * threw IllegalArgumentException and the command died. Package-private and
	 * static so the locale hazard can be pinned by a test — the switch above
	 * cannot be reached without a live client, this can.
	 */
	static Tuning.Chest chestArg(String[] args) {
		return args.length > 0
			? Tuning.Chest.valueOf(args[0].toUpperCase(Locale.ROOT))
			: Tuning.Chest.BATTERED;
	}

	/**
	 * Echo the head of a list to chat, then a "… and N more" tail.
	 *
	 * <p>{@code tailPrefix} is a parameter and not a constant on purpose: the
	 * two callers print that tail with different indents (::gachacosmetics flush
	 * left, ::gachawear indented two spaces to sit under its list), and folding
	 * them into one literal would silently reformat a line the player reads.
	 */
	private void debugList(List<String> lines, int limit, String tailPrefix) {
		int shown = Math.min(lines.size(), limit);
		for (int i = 0; i < shown; i++) {
			debugChat("  " + lines.get(i));
		}
		if (lines.size() > shown)
			debugChat(tailPrefix + "… and " + (lines.size() - shown) + " more");
	}

	/**
	 * ::gachawear. Selects here, against the list the player is looking at, and
	 * hands the service record an exact set of uuids — the album shows one cell
	 * per card DEFINITION but the record is per copy, so "Dragon scimitar" can
	 * legitimately mean several owned cards and all of them should move
	 * together. An empty filter means every card.
	 */
	private void setCardWear(int kills, String filter) {
		GachaState state = stateService.get();
		List<OwnedCard> owned = state == null ? null : state.getOwnedCards();
		if (owned == null || owned.isEmpty()) {
			debugChat("No cards owned yet.");
			return;
		}
		String needle = filter.toLowerCase(Locale.ROOT);
		Set<String> uuids = new LinkedHashSet<>();
		List<String> names = new ArrayList<>();
		for (OwnedCard card : owned) {
			String name = loadoutService.displayName(card);
			if (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)) {
				uuids.add(card.getUuid());
				names.add(name);
			}
		}
		if (uuids.isEmpty()) {
			debugChat("No owned card matches \"" + filter + "\".");
			return;
		}
		serviceRecordService.debugSetServed(uuids, kills);
		CardWear wear = Tuning.cardWear(kills);
		debugChat(uuids.size() + " card(s) set to " + kills + " kills of service — "
			+ (wear == CardWear.NONE ? "no wear" : wear.getDisplayName()) + ".");
		debugList(names, 5, "  ");
	}

	/**
	 * Informational chat (grants, summaries, milestones) — suppressible via the
	 * Chat notifications setting. Everything else goes straight to
	 * {@link #debugChat} and is never suppressed: enforcement feedback
	 * (penalties, refunds, blocked actions) that can be muted reads as a bug.
	 *
	 * <p>This method is the ONLY thing in the plugin that consults
	 * {@code config.chatPings()}. There used to be a second, byte-identical
	 * entry point beside debugChat — named "debugChatAlways" — whose whole job
	 * was to say "unsuppressible" at the call site; both queued the identical
	 * message, so the distinction was documentary rather than functional and
	 * one of the pair was pure budget. Route a suppressible line through here;
	 * route everything else straight through debugChat.
	 */
	private void chatPing(String message) {
		if (config.chatPings())
			debugChat(message);
	}

	/**
	 * Fires once, on the kill where the contract latches on to the Slayer bonus.
	 * chatPing rather than debugChat: this is a grant, not enforcement, so
	 * it honours the chat-notifications setting — the sidebar and the task
	 * overlay both state the bonus permanently, so muting chat cannot make it a
	 * surprise.
	 */
	private void announceDoubleDocket() {
		chatPing("<col=6ec86e>Double Docket!</col> This contract is also your Slayer task —"
			+ " completion pays x" + Tuning.DOUBLE_DOCKET_MULT + ". It stays locked in"
			+ " even if you finish the Slayer task first.");
	}

	private void debugChat(String message) {
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
	 *
	 * <p>A DROP does cost a uuid, and if that uuid was sitting in a loadout
	 * slot the slot reads empty afterwards (LoadoutService.assigned() returns
	 * null for a uuid nobody owns). That is inherent to the dedupe rule rather
	 * than new: the player keeps the genuine copy and can re-assign it, and the
	 * alternative — two indistinguishable copies of one card in the album — is
	 * the thing this method exists to prevent.
	 */
	private void healStaleCardIds() {
		if (!cardDatabase.isReady())
			return;
		stateService.mutate(s -> {
			List<OwnedCard> healed = healCardIds(s.getOwnedCards(), id -> {
				if (cardDatabase.card(id) != null) {
					return -1; // the id still names a card — nothing to heal
				}
				CardDefinition target = cardDatabase.cardForItem(id);
				return target == null ? -1 : target.getCardId();
			});
			return healed == null ? s : s.withOwnedCards(healed);
		});
	}

	/**
	 * The pure half of {@link #healStaleCardIds()}: remap, then dedupe. Returns
	 * the rewritten list, or null when nothing needed remapping and the caller
	 * should keep the state it already holds.
	 *
	 * <p>{@code remap} answers "which card id does this stale id become", or a
	 * negative number when the id is healthy or cannot be rescued. It MUST be a
	 * pure function of the card id: the list is walked twice and the two passes
	 * have to agree on which copies are stale. Both card-DB maps behind it are
	 * fully indexed before onReady fires, so it is.
	 *
	 * <p>The first pass is the fix for an order-dependent dedupe. The drop only
	 * ever fires on a copy that was remapped — that is what stops the rule
	 * eating a pre-existing pair of healthy duplicates — but with a single pass
	 * the guard also depended on WHERE the stale copy sat. Owned cards are
	 * stored in acquisition order and the stale copy is almost always the older
	 * one, so it claimed the key first and the genuine copy behind it, never
	 * having been remapped, sailed straight past the drop: two live copies of
	 * the same (card, variant), exactly what this method promises cannot
	 * happen. Reserving the unremapped keys up front makes the outcome the same
	 * in either order — the stale copy is the one that goes.
	 */
	static List<OwnedCard> healCardIds(List<OwnedCard> owned, IntUnaryOperator remap) {
		if (owned == null || owned.isEmpty())
			return null;
		Set<String> seen = new HashSet<>();
		for (OwnedCard card : owned) {
			if (card.isHologram() || remap.applyAsInt(card.getCardId()) < 0)
				seen.add(cardKey(card));
		}
		boolean changed = false;
		List<OwnedCard> healed = new ArrayList<>(owned.size());
		for (OwnedCard card : owned) {
			OwnedCard next = card;
			int fixed = card.isHologram() ? -1 : remap.applyAsInt(card.getCardId());
			if (fixed >= 0) {
				// carry the service record through the remap — a card-DB
				// heal must not reset the odometer
				next = new OwnedCard(card.getUuid(),
					fixed, card.getTierKey(), card.getVariant(),
					card.getAcquiredAtMs(), card.getProvenance(), card.getKillsServed());
				changed = true;
			}
			if (!seen.add(cardKey(next)) && next != card) {
				continue; // remap collided with an existing copy — drop the stale one
			}
			healed.add(next);
		}
		return changed ? healed : null;
	}

	/**
	 * The dedupe identity of an owned copy: holograms are one per tier and
	 * carry no card id, everything else is one copy per (card, variant). Kept
	 * private on purpose — the album and the loadout list build their own keys
	 * in their own formats, and none of the three are interchangeable.
	 */
	private static String cardKey(OwnedCard card) {
		return card.isHologram()
			? "holo:" + card.getTierKey() : card.getCardId() + ":" + card.getVariant();
	}

	/**
	 * Heuristic for the "why was my magic build fined for melee?" trap: a
	 * castable staff equipped with no autocast spell selected means
	 * auto-retaliate answers incoming hits with the staff's melee bash — a
	 * genuine melee attack the player never clicked.
	 */
	private boolean autoRetaliateStaffBashLikely() {
		try {
			int category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
			// 18 = staff, 21 = bladed staff — the categories with a Spell tab
			boolean castableStaff = category == 18 || category == 21;
			boolean noAutocast = client.getVarbitValue(VarbitID.AUTOCAST_SET) == 0;
			return castableStaff && noAutocast;
		}
		catch (Exception e) {
			return false;
		}
	}
}
