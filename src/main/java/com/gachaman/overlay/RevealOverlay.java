package com.gachaman.overlay;

import com.gachaman.ui.Paint;
import java.awt.Point;
import java.util.List;
// Paint.withAlpha and Paint.hash01 are the two most-called helpers in this file
// — fifty call sites between them — and the qualifier was two tokens on every
// one of them. Static-imported they read the same and cost nothing; the plain
// import above stays because java.awt.Paint is still in scope through
// java.awt.*, and the day someone names that type here it must resolve to ours.
import static com.gachaman.ui.Paint.*;
import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.party.*;
import com.gachaman.service.*;
import com.gachaman.ui.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.function.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.ui.overlay.*;

/**
 * The ceremony renderer: presents one {@link CeremonyBus.Request} at a time on
 * a full-canvas modal overlay driven by a wall-clock phase machine advanced
 * inside {@code render()} (no timer threads). Fanfares are non-modal and draw
 * as a top strip while gameplay continues.
 */
@Slf4j
@Singleton
public class RevealOverlay extends Overlay
	implements CeremonyBus.Renderer, ConsignmentService.Presenter {
	// One declaration per RUN of constants rather than one per constant.
	//
	// `private static final int` is four tokens of the Plugin Hub's 200k budget
	// every time it is typed, and this file types it eighty-one times. Comma-
	// continuing a run that already shares a type and a purpose costs nothing a
	// reader has to hold in their head — the names, the values, the order and
	// every comment below are byte-for-byte what they were — and gives the
	// budget back the repetition was spending. Constants carrying their own
	// javadoc keep their own declaration, so the doc still binds to the one
	// thing it describes.

	// --- deferred side effects (executed OUTSIDE the state lock; see notes) ---
	private static final int ACT_NONE = 0, ACT_DRAIN = 1, ACT_COMMIT_DRAIN = 2,
		ACT_ABORT_COMMIT = 3, ACT_ACCEPT_DRAIN = 4,
		// The three ways a Consignment can end. All three are deferred like every
		// other side effect here: each one mutates state and queues further
		// ceremonies, and doing that under this lock would nest the ceremony monitor
		// inside it in the one direction the lock order forbids.
		ACT_CONSIGN_ACCEPT = 5, ACT_CONSIGN_DECLINE = 6, ACT_CONSIGN_ABANDON = 7,

		// --- chest phases ---
		PH_CHEST_INTRO = 0, PH_CHEST_UPGRADE = 1, PH_CHEST_DEAL = 2,
		PH_CHEST_REVEAL = 3, PH_CHEST_WAIT = 4,
		// --- style roll phases ---
		PH_SPIN = 0, PH_SPIN_RESULT = 1,
		// --- offer phases ---
		PH_OFFERS_UNROLL = 0, PH_OFFERS_SETTLED = 1, PH_OFFERS_ACCEPTED = 2,
		// --- deed phases ---
		PH_DEED_CHOOSE = 0, PH_DEED_BURST = 1;
	/**
	 * The Consignment has one beat and NO CLOCK, deliberately.
	 *
	 * <p>Every other ceremony here advances itself out of its last phase on wall
	 * time; {@link #advanceModalLocked} has no CONSIGNMENT case at all, so the
	 * offer waits indefinitely for an answer. A timed-out offer would be a
	 * question answered by whoever walked away from the keyboard, and the answer
	 * it would give — decline — spends the day key and takes the roll.
	 *
	 * <p>The one thing that ends it unanswered is {@link #pruneStaleModal}: 30
	 * seconds with no frames painted and it is abandoned, which spends nothing
	 * and leaves the roll owed. That is the same row of ConsignmentService's spend
	 * table as a safe-mode abort or a logout, and it is the only outcome an
	 * unattended client is allowed to reach.
	 */
	private static final int PH_CONSIGN_OFFER = 0;

	/**
	 * Strength and falloff of the white blow-out at the moment each chest gives,
	 * indexed by {@link Tuning.Chest#ordinal()} (RUSTY, BATTERED, GILDED,
	 * ORNATE). A better box does not merely open — it goes off, and these two
	 * rows are the whole difference between a creak and a detonation.
	 */
	private static final double[] FLASH_PEAK = {0.30, 0.55, 0.5, 0.85},
		FLASH_TAU = {200.0, 220.0, 260.0, 300.0};

	// STARDUST used to sit in the middle of this run of durations; it is a Color
	// and now lives with the other colours, which is the only reason the run is
	// unbroken enough to share one declaration.
	private static final long UPGRADE_MS = 1700, DEAL_STAGGER_MS = 160,
		DEAL_FLIGHT_MS = 520, DEAL_CHEST_DROP_MS = 300, DEAL_SETTLE_MS = 200,
		FLIP_MS = 220, FIZZLE_MS = 900, MASS_FLIP_STAGGER_MS = 60;
	/** Advance presses within this window of entering the reveal are ignored
	 *  so skip-spam from the intro can never mass-flip the cards face-up. */
	private static final long REVEAL_GRACE_MS = 350;
	private static final long REROLL_FLIPBACK_MS = 300, REROLL_TOTAL_MS = 950,
		SHOCKWAVE_MS = 1600, PITY_GLOW_MS = 2600, SPIN_MS = 4500;
	/**
	 * The very first roulette an account ever sees runs long. It is the moment
	 * the whole gamemode is decided and it happens exactly once, so it gets to
	 * breathe; every roll after it is a re-roll and would only be padding.
	 */
	private static final long FIRST_SPIN_MS = 7500;
	private static final long OFFER_UNROLL_MS = 450, OFFER_UNROLL_STAGGER_MS = 120,
		OFFER_BURN_MS = 900, DEED_BURST_MS = 1150;
	private static final float HOVER_CHARGE_SEC = 0.8f;


	/**
	 * How long after the last painted frame the modal still counts as on screen.
	 * Generous enough to survive an unfocused client throttled to ~1 fps.
	 */
	private static final long MODAL_PAINT_STALE_MS = 1500;

	/** Unpainted for this long and the ceremony is abandoned outright. */
	private static final long MODAL_ABANDON_MS = 30_000;

	private static final Font FONT_HUGE = new Font(Font.SANS_SERIF, Font.BOLD, 30),
		FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 22),
		FONT_NAME = new Font(Font.SERIF, Font.BOLD, 17),
		FONT_BODY = new Font(Font.SANS_SERIF, Font.BOLD, 14),
		FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	private static final Color
		DIM = new Color(0, 0, 0, 140), // 55% of 255
		GOLD = new Color(230, 190, 80),
		STARDUST = new Color(190, 170, 255),
		// The warm off-white every closing hint line is written in — "Click
		// anywhere to collect", "Click to continue", "Click an answer". It was
		// spelled out at all four of them.
		HINT = new Color(235, 225, 200);

	/** The roulette caption's ink — the same warm off-white the first-roll line uses. */
	private static final Color CAP_INK = new Color(215, 200, 165);
	private static final Color CAP_DIM = new Color(186, 174, 148);
	/**
	 * The third caption line, and the honest one.
	 *
	 * <p>The percentages above it are the whole of what the bonus is worth, and on
	 * EASY that number is small because kill GC is a small slice of an easy
	 * contract: the completion bonus is what pays. So a preferred category the
	 * player has to fight SLOWER with is a live way to earn less than having no
	 * preference at all — the multiplier is on the pay per kill, never on the pay
	 * per hour. Nothing in the plugin can measure a player's DPS, and it should
	 * not pretend to; naming the shape of the trade is what lets them measure it
	 * themselves.
	 *
	 * <p>Dropped only when the band above the wheel is already carrying the
	 * first-roll subtitle, which is the {@code drawSideBets} rule — a block that
	 * cannot be given room says nothing rather than writing over its neighbour.
	 * That is the FIRST-EVER roll and no other: an account reaches it once, owns
	 * no contract yet, and is the one player for whom a warning about trading pay
	 * per kill against pay per hour has nothing to attach to.
	 */
	private static final String WORTH_CAVEAT =
		"On Easy that margin is thin: a slower weapon can cost more than it pays.";

	private static final Color REDEMPTION_RED = new Color(120, 20, 20);
	/** Warm dark the tier is pulled toward for rules and outlines. */
	private static final Color PARCH_EDGE_DARK = new Color(104, 82, 52);
	/** Side margin left bare either side of the difficulty heading. */
	private static final int BAND_INSET = 14;
	/** Extra px between letters of the heading; capitals need air to read as set type. */
	private static final int HEAD_TRACKING = 2;
	/** The light on the far wall of a pressed impression. */
	private static final Color PARCH_EMBOSS = new Color(255, 250, 232);
	/**
	 * Bounds of the two-figure party mark, as drawn by
	 * {@link #drawPartySilhouette}: the near figure spans x-1..x+8 and the far
	 * one reaches x+15, over 15px of height. Named so the heading can reserve
	 * room for it instead of the two guessing at each other's extent.
	 */
	private static final int PARTY_GLYPH_W = 17, PARTY_GLYPH_H = 15;
	private static final Color PARCH_TOP = new Color(236, 222, 186),
		PARCH_BOTTOM = new Color(213, 192, 151), PARCH_INK = new Color(58, 44, 26),
		PARCH_INK_SOFT = new Color(104, 86, 58), PARCH_REWARD = new Color(128, 94, 20);
	/**
	 * Side-bet ink: a deep ledger green, distinct from the gold the guaranteed
	 * reward is written in.
	 *
	 * <p>Both used to be {@link #PARCH_REWARD}, which said the same thing about
	 * two different promises — the GC line is what the contract pays, a side bet
	 * is what it MIGHT pay. A second colour separates certain money from
	 * conditional money, and green on warm paper is legible where more gold on
	 * gold was not.
	 */
	private static final Color PARCH_BET = new Color(66, 92, 52);
	private static final Color PARCH_EDGE_SOFT = new Color(146, 120, 80, 153);
	/** The Ante's ink: dark red on parchment, the colour of a debt, not a prize. */
	private static final Color PARCH_ANTE = new Color(132, 44, 34);

	private static final Color EMBER_HOT = new Color(255, 176, 60),
		EMBER_RED = new Color(220, 80, 30), RIM_SILVER_HI = new Color(214, 218, 228),
		RIM_SILVER_LO = new Color(96, 100, 112);

	/** {col,row} in the equipment-panel arrangement, indexed by GearSlot.ordinal(). */
	private static final int[][] DEED_GRID = {
		{1, 0}, {0, 1}, {1, 1},  // HEAD, CAPE, AMULET
		{0, 2}, {1, 2}, {2, 2},  // WEAPON, BODY, SHIELD
		{1, 3}, {0, 4}, {1, 4},  // LEGS, HANDS, FEET
		{2, 4}, {2, 1},          // RING, AMMO
	};

	private final Client client;
	/**
	 * The hop every side effect this overlay raises is executed behind.
	 *
	 * <p>Input arrives on the AWT thread — {@link RevealInputListener} is called
	 * by RuneLite's MouseManager/KeyManager — while {@code render()} is called on
	 * the client thread. The side effects those two paths raise are the SAME
	 * code, and it reads live game state: a reroll re-rolls the slot's pool,
	 * which asks {@code ChestService.isReachable} for the player's real skill
	 * levels; answering a Consignment stamps {@code client.getTickCount()} and
	 * opens a chest off the same pool; accepting a contract reads the Slayer varp
	 * through the alignment hook; and every one of them ends in a bus drain whose
	 * {@code present()} reads {@code client.getGameState()}. Reading any of that
	 * from AWT is a data race against the game thread writing it.
	 *
	 * <p>{@link ClientThread#invoke} rather than {@code invokeLater} on purpose.
	 * The render path already runs on the client thread, and invoke() runs inline
	 * there, so that path stays byte-for-byte what it was — no commit or drain is
	 * pushed across a tick boundary by a fix aimed at the other caller. Only the
	 * AWT path actually hops.
	 */
	private final ClientThread clientThread;
	private final CeremonyBus ceremonyBus;
	private final ChestService chestService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;
	private final ChatMessageManager chatMessageManager;
	private final CeremonyPlayer ceremonyPlayer;
	/**
	 * Injected rather than handed over with {@code setPresenter}, exactly as
	 * {@link ChestService} and {@link TaskService} are: the answer to a ceremony
	 * goes straight back to the service that asked the question. The plugin still
	 * has to call {@code consignmentService.setPresenter(revealOverlay)} so the
	 * service can find its way HERE, but nothing about drawing or answering an
	 * offer depends on that call having happened.
	 *
	 * <p>No Guice cycle: ConsignmentService takes GachaStateService, StyleService,
	 * ChestService, CardDatabase and CeremonyBus, and none of those reaches an
	 * overlay.
	 */
	private final ConsignmentService consignmentService;

	/**
	 * Guards all ceremony state. LOCK ORDER: CeremonyBus (and other services)
	 * are always acquired BEFORE this lock, never while holding it — so all
	 * bus/service side effects are collected under the lock and executed after
	 * release (the ACT_* constants).
	 */
	private final Object lock = new Object();

	private CeremonyBus.Type active;
	private int phase;
	private long phaseAt, startedAt;
	/** Timestamp of the last frame in which the modal was actually drawn; 0 = never. */
	private long paintedAt;

	// pointer (canvas space, fed by RevealInputListener)
	private volatile int pointerX = -1, pointerY = -1;
	private volatile boolean pointerValid;

	// chest state
	private ChestService.ChestOpenResult opened;
	private boolean chestThemed;
	private List<ChestService.RolledSlot> cards;
	private long[] flipAt = new long[0], rerollAt = new long[0];
	private boolean[] flipFxFired = new boolean[0];
	private float[] hoverCharge = new float[0];
	private CardRenderer.CardView[] cardViews = new CardRenderer.CardView[0];
	/**
	 * Service Records frozen at chest-open time, card id -> best owned copy.
	 * Snapshotted the way snapshotCanReroll() is, so cardViewFor() never reaches
	 * into a service from inside the render lock, and so a rerolled slot reads
	 * the same records the rest of the ceremony did.
	 */
	private Map<Integer, Integer> serviceSnapshot = Collections.emptyMap();
	private long lastHoverMs, pityFlipMs, shockAt;
	private int shockwaveSeed, shockCx, shockCy;
	private Color shockwaveColor = Color.WHITE;

	// style roll
	private StyleService.StyleRollResult styleResult;
	private double wheelThetaEnd;
	/**
	 * The two caption lines naming the preferred weapon and what it is worth,
	 * built once when the ceremony starts.
	 *
	 * <p>Precomputed for the reason {@link OfferScrollArt} is: the roulette runs
	 * at frame rate for four and a half seconds, and neither line can change
	 * while it does — the category was decided in the same state write as the
	 * style, and the percentages are pure functions of {@link Tuning}.
	 */
	private String weaponLine, worthLine;

	// task offers
	private List<TaskOffer> offers;
	private OfferScrollArt[] offerArt = new OfferScrollArt[0];
	private int acceptedIndex = -1;

	// task complete

	// deed choice
	private GearSlot chosenDeedSlot;

	// consignment: a binding choice, not a presentation. Held only for the frames
	// it is on screen — the authoritative copy is ConsignmentService's own `live`,
	// and answering goes back through that service rather than through this field.
	private ConsignmentService.Offer consignOffer;

	// fanfare (non-modal, independent of the modal slot)
	private CeremonyBus.Fanfare fanfare;
	private long fanfareAt;

	// preallocated scratch (zero allocation on the hot paths)
	private final Rectangle rect = new Rectangle(), rect2 = new Rectangle();
	private final Rectangle[] deedRects = new Rectangle[GearSlot.values().length];
	private final boolean[] canReroll = new boolean[8];

	@Inject
	public RevealOverlay(Client client, ClientThread clientThread, CeremonyBus ceremonyBus,
		ChestService chestService,
		TaskService taskService, GachaStateService stateService, CardDatabase cardDatabase,
		CardImageService cardImageService, ChatMessageManager chatMessageManager,
		CeremonyPlayer ceremonyPlayer, ConsignmentService consignmentService) {
		this.client = client;
		this.clientThread = clientThread;
		this.ceremonyPlayer = ceremonyPlayer;
		this.consignmentService = consignmentService;
		this.ceremonyBus = ceremonyBus;
		this.chestService = chestService;
		this.taskService = taskService;
		this.stateService = stateService;
		this.cardDatabase = cardDatabase;
		this.cardImageService = cardImageService;
		this.chatMessageManager = chatMessageManager;
		for (int i = 0; i < deedRects.length; i++) {
			deedRects[i] = new Rectangle();
		}
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	// --- CeremonyBus.Renderer ---

	@Override
	public boolean present(CeremonyBus.Request request) {
		if (request == null)
			return false;
		// only claim while actually in game: a ceremony claimed at the login
		// screen never renders (ABOVE_WIDGETS) yet its modal input listener
		// would eat every login-screen click. Declined requests stay parked in
		// the CeremonyBus queue and re-present after login.
		if (client.getGameState() != GameState.LOGGED_IN)
			return false;
		long now = System.currentTimeMillis();
		// Read once. Every arm below asked the request for its payload twice — to
		// type-test it and again to cast it — and TASK_OFFERS three times.
		CeremonyBus.Type type = request.getType();
		Object load = request.getPayload();
		synchronized (lock) {
			if (type == CeremonyBus.Type.FANFARE) {
				if (fanfare != null || active != null
					|| !(load instanceof CeremonyBus.Fanfare)) {
					return false;
				}
				fanfare = (CeremonyBus.Fanfare) load;
				fanfareAt = 0; // clock starts on the first frame actually painted
				return true;
			}
			if (active != null)
				return false;
			switch (type) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (!(load instanceof ChestService.ChestOpenResult))
						return false;
					beginChest((ChestService.ChestOpenResult) load,
						type == CeremonyBus.Type.THEMED_CHEST, now);
					active = type;
					return true;
				case STYLE_ROLL:
					if (!(load instanceof StyleService.StyleRollResult))
						return false;
					beginStyleRoll((StyleService.StyleRollResult) load, now);
					active = CeremonyBus.Type.STYLE_ROLL;
					return true;
				case TASK_OFFERS:
					if (!(load instanceof List) || ((List<?>) load).isEmpty())
						return false;
					beginOffers(castOffers((List<?>) load), now);
					active = CeremonyBus.Type.TASK_OFFERS;
					return true;
				case TASK_COMPLETE: {
					// Presented as the generic fanfare banner rather than a screen of
					// its own. Declined while a banner is already up — the request
					// stays queued and the overlay drains it the moment that banner
					// clears, which is the same contract the FANFARE case above
					// honours. Overwriting `fanfare` instead would silently eat
					// whichever celebration was mid-flight.
					if (fanfare != null
						|| !(load instanceof TaskService.TaskCompletionSummary)) {
						return false;
					}
					TaskService.TaskCompletionSummary done =
						(TaskService.TaskCompletionSummary) load;
					fanfare = new CeremonyBus.Fanfare(CeremonyBus.Fanfare.Size.MEDIUM,
						"Contract complete",
						done.getCompletionGcAwarded() + " GC", null);
					fanfareAt = 0;
					return true;
				}
				case DEED_CHOICE:
					// payload is the milestone number (or 0); the value is not needed
					phase = PH_DEED_CHOOSE;
					phaseAt = now;
					startedAt = now;
					chosenDeedSlot = null;
					active = CeremonyBus.Type.DEED_CHOICE;
					return true;
				case CONSIGNMENT:
					// The deed choice's twin, and claimed the same way — but note
					// what is NOT checked here: whether the offer is still live in
					// ConsignmentService. A request this renderer refuses stays at
					// the head of the bus queue with nothing behind it able to
					// drain, so refusing an offer that went stale while it was
					// parked would starve every ceremony after it. It is claimed
					// and drawn either way, and a stale one simply answers nothing
					// when clicked — which is precisely how applyDeedClaim already
					// handles a deed that was spent elsewhere while its screen was
					// up.
					if (!(load instanceof ConsignmentService.Offer))
						return false;
					phase = PH_CONSIGN_OFFER;
					phaseAt = now;
					startedAt = now;
					consignOffer = (ConsignmentService.Offer) load;
					active = CeremonyBus.Type.CONSIGNMENT;
					return true;
				default:
					return false;
			}
		}
	}

	// --- ConsignmentService.Presenter ---

	/**
	 * Claim the offer by QUEUEING it, never by asking whether a modal is up.
	 *
	 * <p>An overload of the renderer's {@code present} above rather than a
	 * differently-named method, because both interfaces name it that and the
	 * argument types tell them apart.
	 *
	 * <p>The distinction is the whole reason this is one line rather than a
	 * condition. {@link ConsignmentService#offerOrRoll} is reached from a
	 * completed contract, and by then TASK_COMPLETE has already been submitted
	 * (plus DEED_CHOICE on a milestone — and every entry in
	 * {@code DEED_TASK_MILESTONES} is a multiple of {@code CYCLE_TASKS}, so a due
	 * roll and a deed choice coincide by construction, not by accident). A
	 * presenter that answered "can I show this right now?" would therefore answer
	 * NO on essentially every call, the service would take the ordinary roll, and
	 * the Consignment would never once appear. Enqueueing claims it: the bus holds
	 * it behind the ceremonies already up and hands it over the moment they clear.
	 *
	 * <p>Returning true is the promise that exactly one of accept / decline /
	 * abandon will follow. It is kept from four places, and between them they
	 * cover every way the offer can leave the screen: a click, Escape,
	 * {@link #abortActiveCeremony} (safe mode, logout, a party abort) and
	 * {@link #reset} (plugin teardown, which also covers an offer still parked in
	 * the queue).
	 */
	@Override
	public boolean present(ConsignmentService.Offer offer) {
		if (offer == null)
			return false;
		ceremonyBus.submit(CeremonyBus.Type.CONSIGNMENT, offer);
		return true;
	}

	private static List<TaskOffer> castOffers(List<?> raw) {
		List<TaskOffer> out = new ArrayList<>(raw.size());
		for (Object o : raw) {
			if (o instanceof TaskOffer) {
				out.add((TaskOffer) o);
			}
		}
		return out;
	}

	private void beginChest(ChestService.ChestOpenResult result, boolean themed, long now) {
		opened = result;
		chestThemed = themed;
		cards = new ArrayList<>(result.getSlots());
		int n = cards.size();
		flipAt = new long[n];
		flipFxFired = new boolean[n];
		rerollAt = new long[n];
		hoverCharge = new float[n];
		cardViews = new CardRenderer.CardView[n];
		// taken BEFORE the cards are committed, so a card the player has never
		// held reads 0 and shows neither a service count nor wear — the reveal
		// can only ever report history that already existed
		GachaState wearState = stateService.get();
		serviceSnapshot = ServiceRecordService.bestByCardId(
			wearState == null ? null : wearState.getOwnedCards());
		phase = PH_CHEST_INTRO;
		phaseAt = now;
		startedAt = now;
		pityFlipMs = 0;
		shockAt = 0;
		lastHoverMs = now;
	}

	private void beginStyleRoll(StyleService.StyleRollResult result, long now) {
		styleResult = result;
		weaponLine = weaponLine(result.getWeaponType());
		worthLine = worthLine(result.getWeaponType());
		phase = PH_SPIN;
		phaseAt = now;
		startedAt = now;
		int idx = result.getRolled().ordinal();
		double jitter = (hash01((int) now * 31 + idx) - 0.5f) * 80.0;
		double landing = 90 - (idx * 120 + 60) + jitter;
		landing = ((landing % 360) + 360) % 360;
		wheelThetaEnd = 5 * 360 + landing;
	}

	private void beginOffers(List<TaskOffer> list, long now) {
		offers = list;
		int n = list.size();
		// all colors and text lines are precomputed here so the per-frame
		// scroll drawing allocates nothing beyond the unavoidable Paints
		offerArt = new OfferScrollArt[n];
		for (int i = 0; i < n; i++) {
			offerArt[i] = new OfferScrollArt(list.get(i));
		}
		phase = PH_OFFERS_UNROLL;
		phaseAt = now;
		startedAt = now;
		acceptedIndex = -1;
	}

	// --- input surface (called by RevealInputListener / SafeModeService) ---

	public boolean isModalActive() {
		synchronized (lock) {
			return active != null;
		}
	}

	/**
	 * True only while a modal is claimed AND is genuinely on screen. Input is
	 * consumed on this, never on {@link #isModalActive()}: a ceremony that holds
	 * the modal slot without being painted would swallow every click and keypress
	 * with nothing on screen to explain why, and its own phase clock — which only
	 * ticks from {@link #render} — could never time it out. That is an
	 * unrecoverable input trap; not consuming what we do not draw makes it
	 * impossible.
	 */
	public boolean isModalInteractive() {
		synchronized (lock) {
			return active != null
				&& paintedAt != 0
				&& System.currentTimeMillis() - paintedAt < MODAL_PAINT_STALE_MS;
		}
	}

	/**
	 * Abandons a modal that has stopped being painted (or was never painted at
	 * all). Called once per game tick: with no frames there is no phase clock, so
	 * such a ceremony would otherwise hold the slot forever and block every
	 * ceremony queued behind it. A pending chest is still committed.
	 */
	public void pruneStaleModal() {
		boolean stale;
		synchronized (lock) {
			long last = paintedAt != 0 ? paintedAt : startedAt;
			stale = active != null && System.currentTimeMillis() - last > MODAL_ABANDON_MS;
			if (stale) {
				log.debug("Gachaman: abandoning unpainted {} ceremony", active);
			}
		}
		if (stale) {
			abortActiveCeremony();
		}
	}

	public void setPointer(Point p) {
		if (p == null) {
			pointerValid = false;
			return;
		}
		pointerX = p.x;
		pointerY = p.y;
		pointerValid = true;
	}

	public void handleClick(Point p) {
		if (p == null)
			return;
		long now = System.currentTimeMillis();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		snapshotCanReroll();

		// no action a click can raise carries an argument (only the offer-accept
		// path does, and that is raised from stagedAdvance/advanceModalLocked),
		// so the arg is the literal -1 rather than a local nothing ever writes
		int action = ACT_NONE;
		int rerollIndex = -1;
		GearSlot deedClicked = null;

		synchronized (lock) {
			if (active == null)
				return;
			switch (active) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (phase == PH_CHEST_WAIT) {
						action = closeLocked(ACT_COMMIT_DRAIN);
					}
					else if (phase == PH_CHEST_REVEAL) {
						int n = cards.size();
						for (int i = 0; i < n; i++) {
							slotRect(i, n, cw, ch, rect);
							if (!rect.contains(p.x, p.y))
								continue;
							if (flipAt[i] == 0 && rerollAt[i] == 0) {
								flipAt[i] = now;
							}
							else if (isFaceUpSteady(i, now) && canReroll[i]
								&& rerollButtonHit(rect, p.x, p.y)) {
								rerollIndex = i;
							}
							break;
						}
					}
					break;
				case STYLE_ROLL:
					if (phase == PH_SPIN_RESULT) {
						action = closeLocked(ACT_DRAIN);
					}
					break;
				case TASK_OFFERS:
					if (phase == PH_OFFERS_SETTLED) {
						int n = offers.size();
						for (int i = 0; i < n; i++) {
							offerRect(i, n, cw, ch, rect);
							if (rect.contains(p.x, p.y)) {
								acceptedIndex = i;
								phase = PH_OFFERS_ACCEPTED;
								phaseAt = now;
								break;
							}
						}
					}
					break;
				case DEED_CHOICE:
					if (phase == PH_DEED_CHOOSE) {
						layoutDeedRects(cw, ch);
						GearSlot[] slots = GearSlot.values();
						for (int i = 0; i < slots.length; i++) {
							if (deedRects[i].contains(p.x, p.y) && !isSlotDeeded(slots[i])) {
								deedClicked = slots[i];
								break;
							}
						}
					}
					break;
				case CONSIGNMENT:
					if (phase == PH_CONSIGN_OFFER) {
						// The modal is released HERE, inside the lock, before the
						// answer is sent — which is what lets the roulette the
						// answer causes claim the screen on the very next submit,
						// with the crate parking behind it. That is the one place
						// this ceremony does not mirror the deed choice: a deed's
						// consequence is a burst drawn over the screen it was
						// claimed on, while a Consignment's consequence is two
						// ceremonies queued behind it. There is nothing left to
						// show here once the answer is given.
						for (int i = 0; i < 2; i++) {
							consignRect(i, cw, ch, rect);
							if (!rect.contains(p.x, p.y))
								continue;
							int answer = i == 0 ? ACT_CONSIGN_ACCEPT : ACT_CONSIGN_DECLINE;
							action = closeLocked(answer);
							break;
						}
					}
					break;
				default:
					break;
			}
		}

		// All three hop to the client thread, and all three go through the same
		// FIFO queue when they do, so this order survives the crossing.
		if (rerollIndex >= 0) {
			applyReroll(rerollIndex);
		}
		if (deedClicked != null) {
			applyDeedClaim(deedClicked);
		}
		executeAction(action, -1);
	}

	/** Esc: staged skip, then close. */
	public void handleEscape() {
		stagedAdvance(true);
	}

	/** Space: advance / skip the current beat. */
	public void handleAdvance() {
		stagedAdvance(false);
	}

	private void stagedAdvance(boolean escape) {
		long now = System.currentTimeMillis();
		int action = ACT_NONE;
		int actionArg = -1;
		boolean handled = false;
		synchronized (lock) {
			if (active == null)
				return;
			switch (active) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (phase == PH_CHEST_INTRO || phase == PH_CHEST_UPGRADE || phase == PH_CHEST_DEAL) {
						phase = PH_CHEST_REVEAL;
						phaseAt = now;
						lastHoverMs = now;
						handled = true;
					}
					else if (phase == PH_CHEST_REVEAL) {
						// grace window: a skip-spammed press that just landed us
						// here must never instantly flip the cards face-up
						if (now - phaseAt < REVEAL_GRACE_MS) {
							handled = true;
							break;
						}
						boolean any = false;
						int stagger = 0;
						for (int i = 0; i < flipAt.length; i++) {
							if (flipAt[i] == 0 && rerollAt[i] == 0) {
								flipAt[i] = now + stagger * MASS_FLIP_STAGGER_MS;
								stagger++;
								any = true;
							}
						}
						if (any) {
							handled = true;
						}
						else if (escape) {
							action = closeLocked(ACT_COMMIT_DRAIN);
							handled = true;
						}
					}
					else if (phase == PH_CHEST_WAIT) {
						action = closeLocked(ACT_COMMIT_DRAIN);
						handled = true;
					}
					break;
				case STYLE_ROLL:
					if (phase == PH_SPIN) {
						phase = PH_SPIN_RESULT;
						phaseAt = now;
					}
					else {
						action = closeLocked(ACT_DRAIN);
					}
					handled = true;
					break;
				case TASK_OFFERS:
					if (phase == PH_OFFERS_UNROLL) {
						phase = PH_OFFERS_SETTLED;
						phaseAt = now;
						handled = true;
					}
					else if (phase == PH_OFFERS_SETTLED && escape) {
						// dismiss without accepting; offers stay pending in state
						action = closeLocked(ACT_DRAIN);
						handled = true;
					}
					else if (phase == PH_OFFERS_ACCEPTED) {
						// the contract is already chosen — skip the burn, but
						// still ACCEPT it; a plain dismissal would drop the pick
						int idx = acceptedIndex;
						if (finishModalLocked()) {
							action = idx >= 0 ? ACT_ACCEPT_DRAIN : ACT_DRAIN;
							actionArg = idx;
						}
						handled = true;
					}
					break;
				case DEED_CHOICE:
					if (phase == PH_DEED_CHOOSE && escape) {
						// deed stays pending; the ceremony re-queues on next grant
						action = closeLocked(ACT_DRAIN);
						handled = true;
					}
					else if (phase == PH_DEED_BURST) {
						// the slot is already unlocked; this is just the flourish
						action = closeLocked(ACT_DRAIN);
						handled = true;
					}
					break;
				case CONSIGNMENT:
					// Escape REFUSES the deal, and refusing is an answer: the wheel
					// spins as it normally would and today's Consignment is spent.
					//
					// This is the one place the offer deliberately parts company
					// with DEED_CHOICE above, which re-queues on Escape and costs
					// the player nothing to dismiss. A deed can afford that because
					// the deed is still owed either way — dismissing it postpones a
					// gift. An offer that re-queued would be re-offerable at will:
					// press Escape, finish another contract, be asked again, and
					// the once-per-day gate is worth precisely nothing. What is
					// rationed here is BEING ASKED, not being crated, so the
					// question has to be spent by answering it either way.
					//
					// Space is deliberately not an answer. A binding choice must be
					// aimed at, and the skip key is muscle memory from four other
					// ceremonies where it costs nothing.
					if (escape) {
						action = closeLocked(ACT_CONSIGN_DECLINE);
					}
					handled = true;
					break;
				default:
					break;
			}
			if (!handled && escape) {
				// Escape is the universal exit. Any beat without a dismissal of
				// its own must still release the input this overlay is consuming
				// — a modal that cannot be closed is an input trap, and the user
				// has no way to know which of them they are stuck in.
				//
				// A Consignment closed through here would be the one request on
				// the bus that this overlay promised to answer and then simply
				// dropped, leaving the offer live and the roll owed until the next
				// login drain. The case above already claims every escape, so this
				// is unreachable today — it is spelled out anyway so that "closing
				// an offer always answers it" survives an edit to that switch.
				boolean chest = active == CeremonyBus.Type.CHEST_OPEN
					|| active == CeremonyBus.Type.THEMED_CHEST;
				boolean consign = active == CeremonyBus.Type.CONSIGNMENT;
				if (finishModalLocked()) {
					action = chest ? ACT_COMMIT_DRAIN
						: (consign ? ACT_CONSIGN_DECLINE : ACT_DRAIN);
				}
			}
		}
		executeAction(action, actionArg);
	}

	/**
	 * Force-close the active modal ceremony (safe-mode / shutdown). A pending
	 * chest is STILL committed, with a one-line chat summary.
	 */
	/**
	 * Plugin-wired: the live party vote picture, or null when no vote is open.
	 *
	 * <p>A supplier rather than an injected PartyRollService, matching the hooks
	 * that service already takes from the plugin. The overlay asks while it
	 * paints and never holds the answer, so a vote that resolves mid-ceremony is
	 * reflected on the very next frame instead of going stale on the parchment.
	 */
	@Nullable
	private Supplier<PartyRollService.VoteView> partyVoteSupplier;

	public void setPartyVoteSupplier(
		Supplier<PartyRollService.VoteView> supplier) {
		this.partyVoteSupplier = supplier;
	}

	/** The vote snapshot for the frame being painted; see the offer render loop. */
	@Nullable
	private PartyRollService.VoteView frameVotes;

	public void abortActiveCeremony() {
		abortActiveCeremony(null);
	}

	/**
	 * Force-close the active modal ceremony only when it is of the given type
	 * (null aborts whatever is up). The party layer aborts the offer scrolls
	 * when a vote dies under them, and it must not take an unrelated chest
	 * reveal down with them — that ceremony's meaning did not change, and the
	 * player did not ask to skip it.
	 */
	public void abortActiveCeremony(CeremonyBus.Type only) {
		int action;
		synchronized (lock) {
			if (active == null || (only != null && active != only))
				return;
			boolean chest = active == CeremonyBus.Type.CHEST_OPEN
				|| active == CeremonyBus.Type.THEMED_CHEST;
			// An offer taken off the screen by something other than the player —
			// incoming damage, a world hop, the plugin going down — is ABANDONED,
			// which spends nothing and leaves the roll owed. That is deliberately
			// not the same as a refusal: the player never answered, and being asked
			// is the thing the day key rations. Aborting is the only route to this
			// row of ConsignmentService's spend table, so it must not be quietly
			// turned into a decline for tidiness.
			boolean consign = active == CeremonyBus.Type.CONSIGNMENT;
			finishModalLocked();
			action = chest ? ACT_ABORT_COMMIT : (consign ? ACT_CONSIGN_ABANDON : ACT_DRAIN);
		}
		// The card count rides across the hop as the action's argument, read HERE
		// rather than inside it, and this is the one place that ordering matters.
		//
		// Reading it here is safe on any thread: getPending() touches nothing but
		// ChestService's own field, holding this plugin's own result object, with
		// no Client beneath it — the very case that does not need the hop.
		//
		// Reading it THERE would be wrong, and only on the path that matters most.
		// shutDown() runs on the AWT thread (PluginManager.stopPlugin asserts it),
		// and it calls this and then calls chestService.commitPending() itself two
		// lines later. So by the time a hopped job runs, the cards are already
		// committed and the pending slot is empty — and the notice would tell a
		// player who just watched four cards go by that zero were saved. Taken at
		// raise time it is the count that was on screen when the reveal was
		// interrupted, which is true whichever commit gets there first: this one,
		// or shutDown's own.
		executeAction(action, action == ACT_ABORT_COMMIT ? pendingCardCount() : -1);
	}

	/** Cards in the uncommitted open, or 0 when there is none. Reads no game state. */
	private int pendingCardCount() {
		ChestService.ChestOpenResult pending = chestService.getPending();
		return pending == null ? 0 : pending.getSlots().size();
	}

	/**
	 * Full teardown for plugin shutdown: clears the modal slot AND any
	 * claimed-but-unshown fanfare, so nothing stale survives into the next
	 * startUp of this @Singleton.
	 */
	public void reset() {
		synchronized (lock) {
			finishModalLocked();
			fanfare = null;
			fanfareAt = 0;
		}
		// Unconditional, and outside the lock like every other service call here.
		// It is a no-op when nothing is live, and it covers the case no other
		// teardown path can see: an offer still PARKED in the bus queue, never
		// presented, about to be dropped by the clear() that follows this in
		// shutDown. Abandoning is right for both — nobody answered.
		//
		// The ONE service call in this file that does not hop to the client thread,
		// and deliberately. abandon() clears the service's own `live` field and
		// logs; it reads no game state, submits nothing to the bus, and so drains
		// nothing — there is no Client anywhere beneath it. Hopping it would buy
		// nothing and cost something real: this runs in shutDown, immediately
		// before the bus is cleared, and a deferred abandon would be a job left
		// queued against a plugin that is already being taken apart.
		consignmentService.abandon();
	}

	/**
	 * Clear the modal slot and hand back the follow-up action to run once the
	 * lock is released — or ACT_NONE when there was nothing to close.
	 *
	 * <p>Eleven call sites wrote that ternary out by hand. The pairing is not
	 * decoration: the action must be raised if and only if THIS call was the one
	 * that took the ceremony down, or a race between a click and the phase clock
	 * would drain the bus twice and commit a chest that is already committed.
	 * Keeping the test and its consequence in one place is what stops the two
	 * drifting apart. The {@code Locked} suffix carries the same warning the rest
	 * of this file's does — callers hold {@link #lock}.
	 */
	private int closeLocked(int action) {
		return finishModalLocked() ? action : ACT_NONE;
	}

	/** Clears the modal slot; true when something was active. Callers execute the follow-up action. */
	private boolean finishModalLocked() {
		if (active == null)
			return false;
		active = null;
		paintedAt = 0;
		opened = null;
		cards = null;
		styleResult = null;
		offers = null;
		chosenDeedSlot = null;
		consignOffer = null;
		acceptedIndex = -1;
		shockAt = 0;
		pityFlipMs = 0;
		return true;
	}

	/**
	 * Raise a deferred side effect, on the client thread.
	 *
	 * <p>The ACT_NONE early return is not just tidiness. {@code render()} calls
	 * this every single frame with nothing to do, and a lambda capturing two ints
	 * allocates; returning first keeps the render path allocation-free, which the
	 * rest of this file goes to some trouble to be.
	 *
	 * <p>A BLOCK lambda deliberately: ClientThread overloads invoke() on Runnable
	 * and on BooleanSupplier, and a void-bodied block can only be the first — the
	 * same trap OverviewTab documents at its own hop.
	 */
	private void executeAction(int action, int arg) {
		if (action == ACT_NONE)
			return;
		clientThread.invoke(() -> {
			runAction(action, arg);
		});
	}

	private void runAction(int action, int arg) {
		switch (action) {
			case ACT_DRAIN:
				ceremonyBus.drain();
				break;
			case ACT_COMMIT_DRAIN:
				chestService.commitPending();
				ceremonyBus.drain();
				break;
			case ACT_ABORT_COMMIT: {
				// `arg` is the card count, snapshotted by the raiser — see
				// abortActiveCeremony for why it cannot be read here.
				//
				// The duplicate GC still is, and stays behind the hop, because unlike
				// the count it is not knowable until the commit runs. In the one race
				// where another thread committed first this returns 0 and the suffix
				// is dropped, which understates the payout rather than misstating it:
				// the GC itself was awarded by whichever commit won, and the player's
				// purse is right either way.
				long dupes = chestService.commitPending();
				String msg = "Gachaman: reveal interrupted - " + arg
					+ " card(s) committed to your collection"
					+ (dupes > 0 ? " (+" + dupes + " GC from duplicates)." : ".");
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.GAMEMESSAGE)
					.runeLiteFormattedMessage(msg)
					.value(msg)
					.build());
				ceremonyBus.drain();
				break;
			}
			case ACT_ACCEPT_DRAIN:
				taskService.acceptOffer(arg);
				ceremonyBus.drain();
				break;
			// The three Consignment answers. Each is given the CURRENT tick rather
			// than the tick the offer was raised on: the number's only use is the
			// window on the "your style changed, switch your gear" chip, which is
			// owed from the moment the style actually changes — and that is now,
			// not whenever the question was first put.
			//
			// A false return is ignored on purpose, exactly as applyDeedClaim
			// ignores a deed that turned out to be already spent. It means the
			// offer was resolved by something else while this screen was up (a
			// login drain settling a roll left owed by an earlier crash is the only
			// live route), and the modal has already been released, so the right
			// response is the one that has just happened: nothing.
			case ACT_CONSIGN_ACCEPT:
				consignmentService.accept(client.getTickCount());
				ceremonyBus.drain();
				break;
			case ACT_CONSIGN_DECLINE:
				consignmentService.decline(client.getTickCount());
				ceremonyBus.drain();
				break;
			case ACT_CONSIGN_ABANDON:
				consignmentService.abandon();
				ceremonyBus.drain();
				break;
			default:
				break;
		}
	}

	/**
	 * Spend a reroll token on one slot. This is THE call that made the race real:
	 * {@code rerollSlot} rebuilds the slot's pool through {@code poolFor} ->
	 * {@code isReachable}, which asks the client for four real skill levels.
	 *
	 * <p>The continuation moved inside the hop rather than staying on the caller's
	 * thread, because it consumes the return value. {@code invoke} is void and
	 * asynchronous off the client thread, so reading {@code fresh} on the next
	 * line would read a slot that had not been rolled yet — a worse bug than the
	 * one being fixed. Nothing in here touches Swing (this is a canvas overlay),
	 * and the fields it writes are the lock's, which {@code render()} reads under
	 * the same lock, so there is nothing to hop back for.
	 *
	 * <p>The flip-back is stamped HERE and no longer takes the click's timestamp.
	 * A click stamp would already be up to a tick old by the time the hop lands,
	 * and {@code REROLL_FLIPBACK_MS} is 300ms — the card would skip the flip and
	 * pop straight to its replacement. The animation belongs to the moment the
	 * reroll actually happened, which is this one.
	 */
	private void applyReroll(int index) {
		clientThread.invoke(() -> {
			ChestService.RolledSlot fresh = chestService.rerollSlot(index);
			if (fresh == null)
				return;
			long now = System.currentTimeMillis();
			synchronized (lock) {
				if (active != CeremonyBus.Type.CHEST_OPEN && active != CeremonyBus.Type.THEMED_CHEST)
					return;
				if (index < cards.size()) {
					// keep the OLD card view while the card flips back over; the
					// fresh slot's view is built when the shimmer re-reveals it
					cards.set(index, fresh);
					rerollAt[index] = now;
				}
			}
		});
	}

	/**
	 * Claim the pending deed for a slot. {@code claimDeed} itself only reads and
	 * writes GachaState, but it ends in a {@code ceremonyBus.submit} whose drain
	 * presents the next request, and presenting reads {@code getGameState()}; the
	 * dismissal branch below drains the bus for the same reason.
	 *
	 * <p>Like the reroll, the continuation had to come inside the hop: it branches
	 * on the returned boolean, and that answer does not exist until the body runs.
	 * The burst is stamped inside for the reason the flip-back is — a stale click
	 * timestamp would eat the front of a 1150ms animation.
	 */
	private void applyDeedClaim(GearSlot slot) {
		clientThread.invoke(() -> {
			boolean ok = chestService.claimDeed(slot);
			int action = ACT_NONE;
			synchronized (lock) {
				if (active != CeremonyBus.Type.DEED_CHOICE)
					return;
				if (ok) {
					chosenDeedSlot = slot;
					phase = PH_DEED_BURST;
					phaseAt = System.currentTimeMillis();
				}
				else {
					// no pending deed after all (already spent elsewhere) - just dismiss
					action = closeLocked(ACT_DRAIN);
				}
			}
			// already on the client thread; executeAction's invoke() runs it inline
			executeAction(action, -1);
		});
	}

	// --- render ---

	@Override
	public Dimension render(Graphics2D g) {
		long now = System.currentTimeMillis();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
			return null;
		snapshotCanReroll();

		int action = ACT_NONE;
		int actionArg = -1;
		boolean fanfareEnded = false;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		synchronized (lock) {
			if (active != null) {
				int[] out = advanceModalLocked(now);
				action = out[0];
				actionArg = out[1];
			}
			if (active != null) {
				// proof of life for isModalInteractive()/pruneStaleModal(): from
				// here on the modal is provably on screen
				paintedAt = now;
				g.setColor(DIM);
				g.fillRect(0, 0, cw, ch);
				switch (active) {
					case CHEST_OPEN:
					case THEMED_CHEST:
						drawChestCeremony(g, cw, ch, now);
						break;
					case STYLE_ROLL:
						drawStyleRoll(g, cw, ch, now);
						break;
					case TASK_OFFERS:
						drawOffers(g, cw, ch, now);
						break;
					case DEED_CHOICE:
						drawDeedChoice(g, cw, ch, now);
						break;
					case CONSIGNMENT:
						drawConsignment(g, cw, ch);
						break;
					default:
						break;
				}
			}
			if (fanfare != null) {
				if (fanfareAt == 0) {
					// claimed while not rendering (e.g. at logout): the clock
					// starts on the first frame actually painted
					fanfareAt = now;
				}
				long el = now - fanfareAt;
				if (el >= fanfareDurationMs(fanfare.getSize())) {
					fanfare = null;
					fanfareEnded = true;
				}
				else {
					drawFanfare(g, cw, ch, now, el);
				}
			}
		}

		executeAction(action, actionArg);
		if (fanfareEnded) {
			ceremonyBus.drain();
		}
		return null;
	}

	/** Wall-clock phase advancement; returns {action, arg} to run outside the lock. */
	private int[] advanceModalLocked(long now) {
		long el = now - phaseAt;
		switch (active) {
			case CHEST_OPEN:
			case THEMED_CHEST: {
				switch (phase) {
					case PH_CHEST_INTRO:
						if (el >= ChestStrain.totalMs(opened.getPurchasedTier())) {
							phase = opened.isJackpotUpgraded() ? PH_CHEST_UPGRADE : PH_CHEST_DEAL;
							phaseAt = now;
						}
						break;
					case PH_CHEST_UPGRADE:
						if (el >= UPGRADE_MS) {
							phase = PH_CHEST_DEAL;
							phaseAt = now;
						}
						break;
					case PH_CHEST_DEAL:
						if (el >= dealTotalMs(cards.size())) {
							phase = PH_CHEST_REVEAL;
							phaseAt = now;
							lastHoverMs = now;
						}
						break;
					case PH_CHEST_REVEAL: {
						updateHoverCharges(now);
						for (int i = 0; i < rerollAt.length; i++) {
							if (rerollAt[i] > 0 && now - rerollAt[i] >= REROLL_TOTAL_MS) {
								rerollAt[i] = 0;
								cardViews[i] = null;
								flipAt[i] = now;
								flipFxFired[i] = false;
							}
						}
						// face effects fire exactly when a flip COMPLETES,
						// for clicked, mass-skipped and rerolled cards alike
						for (int i = 0; i < flipAt.length; i++) {
							if (!flipFxFired[i] && flipAt[i] > 0 && rerollAt[i] == 0
								&& now - flipAt[i] >= FLIP_MS) {
								flipFxFired[i] = true;
								slotRect(i, cards.size(), client.getCanvasWidth(),
									client.getCanvasHeight(), rect);
								onFlipEffectsLocked(i, now, rect);
							}
						}
						boolean allDone = true;
						for (int i = 0; i < flipAt.length; i++) {
							if (flipAt[i] == 0 || rerollAt[i] > 0
								|| now - flipAt[i] < FLIP_MS + 350) {
								allDone = false;
								break;
							}
						}
						if (allDone) {
							phase = PH_CHEST_WAIT;
							phaseAt = now;
						}
						break;
					}
					default:
						break;
				}
				break;
			}
			case STYLE_ROLL:
				// Nothing to integrate: the wheel's on-screen angle is solved
				// independently in drawStyleRoll from wheelThetaEnd and the phase
				// clock, so all the advance step owes the roulette is the moment
				// the spin is over.
				if (phase == PH_SPIN && el >= spinMs()) {
					phase = PH_SPIN_RESULT;
					phaseAt = now;
				}
				break;
			case TASK_OFFERS:
				// likewise the scrolls: each one's unroll progress is a pure
				// function of el, computed where it is drawn
				if (phase == PH_OFFERS_UNROLL && el >= unrollTotalMs()) {
					phase = PH_OFFERS_SETTLED;
					phaseAt = now;
				}
				else if (phase == PH_OFFERS_ACCEPTED && el >= OFFER_BURN_MS) {
					int idx = acceptedIndex;
					finishModalLocked();
					return new int[]{ACT_ACCEPT_DRAIN, idx};
				}
				break;
			case DEED_CHOICE:
				if (phase == PH_DEED_BURST && el >= DEED_BURST_MS) {
					finishModalLocked();
					return new int[]{ACT_DRAIN, -1};
				}
				break;
			default:
				break;
		}
		return new int[]{ACT_NONE, -1};
	}


	/** Fired at flip COMPLETION (the face is fully visible at this instant). */
	private void onFlipEffectsLocked(int i, long now, Rectangle cardRect) {
		ChestService.RolledSlot slot = cards.get(i);
		if (i == 0 && opened.isPityBreak()) {
			pityFlipMs = now;
		}
		boolean shock = slot.getRarity() == Rarity.LEGENDARY
			|| slot.getVariant() == Variant.SHINY
			|| slot.getVariant() == Variant.HOLOGRAM;
		if (shock) {
			shockAt = now;
			shockwaveSeed = i * 7919 + (int) (now & 0xFFFF);
			shockwaveColor = slot.getRarity().getColor();
			shockCx = cardRect.x + cardRect.width / 2;
			shockCy = cardRect.y + cardRect.height / 2;
		}
		// A near miss deliberately gets NOTHING here: its cue is the quiet
		// stardust fizzle drawn on the card itself, and anything fired from this
		// method would read as the shiny fanfare the player did not win.
	}

	private void updateHoverCharges(long now) {
		float dt = Math.min(0.05f, (now - lastHoverMs) / 1000f);
		lastHoverMs = now;
		int n = cards.size();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		for (int i = 0; i < n; i++) {
			slotRect(i, n, cw, ch, rect);
			boolean hovered = pointerValid && flipAt[i] == 0 && rerollAt[i] == 0
				&& rect.contains(pointerX, pointerY);
			if (hovered) {
				hoverCharge[i] = Math.min(1f, hoverCharge[i] + dt / HOVER_CHARGE_SEC);
			}
			else {
				hoverCharge[i] = Math.max(0f, hoverCharge[i] - dt / 0.4f);
			}
		}
	}

	private void snapshotCanReroll() {
		ChestService.ChestOpenResult pending = chestService.getPending();
		int n = pending == null ? 0 : Math.min(canReroll.length, pending.getSlots().size());
		for (int i = 0; i < canReroll.length; i++) {
			canReroll[i] = i < n && chestService.canReroll(i);
		}
	}

	// =====================================================================
	// CHEST CEREMONY
	// =====================================================================

	private static long dealTotalMs(int n) {
		return DEAL_CHEST_DROP_MS + (n - 1) * DEAL_STAGGER_MS + DEAL_FLIGHT_MS + DEAL_SETTLE_MS + 150;
	}


	private void drawChestCeremony(Graphics2D g, int cw, int ch, long now) {
		long el = now - phaseAt;
		// Before the deal the header may only name the tier that was PAID for:
		// announcing the upgraded tier over an un-upgraded chest spoils the very
		// ceremony that exists to reveal it. This is the same guard the
		// (JACKPOT!) suffix below already uses.
		boolean dealt = phase >= PH_CHEST_DEAL;
		Tuning.Chest shownTier = dealt
			? opened.getEffectiveTier() : opened.getPurchasedTier();
		String tag = opened.getThemedSetTag();
		String title = chestThemed
			? "THEMED CHEST" + (tag == null ? "" : " - " + tag.toUpperCase())
			: shownTier.name() + " CHEST";
		if (opened.isJackpotUpgraded() && dealt) {
			title = title + "  (JACKPOT!)";
		}
		Color titleColor = !chestThemed && shownTier == Tuning.Chest.RUSTY
			? new Color(176, 156, 128) : GOLD;
		centre(g, title, cw / 2, 46, FONT_TITLE, titleColor);

		if (phase == PH_CHEST_INTRO) {
			drawChestIntro(g, cw, ch, el);
			return;
		}
		if (phase == PH_CHEST_UPGRADE) {
			drawChestUpgrade(g, cw, ch, el);
			return;
		}
		if (phase == PH_CHEST_DEAL) {
			drawChestDeal(g, cw, ch, el, now);
			return;
		}
		drawChestReveal(g, cw, ch, now);
	}

	private void drawChestIntro(Graphics2D g, int cw, int ch, long el) {
		Tuning.Chest tier = opened.getPurchasedTier();
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;
		int cy = ch / 2 + 20;

		// Only the motion is computed here. The lid angle and the seam leak used
		// to be solved alongside it and handed to a hand-drawn chest; both are
		// baked into the pre-rendered frames now, so the values were being
		// computed and thrown away — deleting them is not a change to what the
		// lid does, it is deleting a second opinion nobody asked for.
		double shakeX = 0;
		double shakeY = 0;
		double camX = 0;
		double camY = 0;
		// the instant the lock loses; everything after the strain is anchored to it
		long give = ChestStrain.giveMs(tier);

		if (tier == Tuning.Chest.RUSTY) {
			// one feeble wobble, a creak, and the lid gives up — no drama
			if (el >= 300 && el < 800) {
				shakeX = Math.sin(el * 0.07) * 2;
			}
		}
		else if (tier == Tuning.Chest.BATTERED) {
			if (ChestStrain.straining(el, tier)) {
				shakeX = Math.sin(el * 0.09)
					* (2 + 5 * ChestStrain.load(el, tier) + 3 * ChestStrain.kick(el, tier));
			}
		}
		else if (tier == Tuning.Chest.GILDED) {
			if (ChestStrain.straining(el, tier)) {
				double amp = 3 + 5 * ChestStrain.load(el, tier) + 4 * ChestStrain.kick(el, tier);
				shakeX = Math.sin(el * 0.12) * amp;
				shakeY = Math.cos(el * 0.10) * amp * 0.4;
			}
		}
		else {
			// ornate: a padlock bursts and the chain it held whips off - the
			// outer chain at 1200, the inner at 2600 - then the lid seam leaks
			// light with mounting intensity, then the lid blasts open with a
			// decaying 2-3px camera shake. Only that last camera shake is still
			// drawn from here; the chains and the seam are in the frames.
			if (ChestStrain.straining(el, tier)) {
				double load = ChestStrain.load(el, tier);
				double amp = 1.5 + 6.5 * load + 4 * ChestStrain.kick(el, tier);
				shakeX = Math.sin(el * (0.05 + 0.06 * load)) * amp;
			}
			if (el >= give) {
				double mag = 3.0 * Math.exp(-(el - give) / 260.0);
				camX = Math.sin(el * 0.19) * mag;
				camY = Math.cos(el * 0.23) * mag * 0.7;
			}
		}

		// The white blow-out at the give, which every tier does with the same
		// curve and only its own strength and falloff: peak * exp(-since/tau).
		// The four thresholds it used to be written against ARE ChestStrain's
		// give times, so it reads them from there rather than keeping a second
		// copy that could drift out of step with the strain schedule.
		float flash = el < give ? 0f : (float) Math.max(0,
			FLASH_PEAK[tier.ordinal()] * Math.exp(-(el - give) / FLASH_TAU[tier.ordinal()]));

		Graphics2D gc = copy(g);
		gc.translate(camX, camY);
		int dx = cx + (int) shakeX;
		int dy = cy + (int) shakeY;
		// one pre-rendered frame carries the whole beat - body, lid, chains,
		// padlock, strain, seam leak, glow and motes. The shakes stay here as
		// translations, which is exactly why the ceremony bakes at all.
		ceremonyPlayer.draw(gc, dx, dy, chestW, chestH, tier,
			ceremonyPlayer.frameAt(tier, el), 1f);
		gc.dispose();

		if (flash > 0.02f) {
			g.setColor(new Color(255, 255, 240, (int) (flash * 255)));
			g.fillRect(0, 0, cw, ch);
		}
		if (opened.isStardustBlessed()) {
			centre(g, "Stardust-blessed", cw / 2, cy - chestH / 2 - 24, FONT_BODY,
				withAlpha(STARDUST, 0.75f + 0.25f * (float) Math.sin(el * 0.005)));
		}
		centre(g, "Space to skip", cw / 2, ch - 30, FONT_SMALL,
			new Color(200, 200, 200, 160), false);
	}

	private void drawChestUpgrade(Graphics2D g, int cw, int ch, long el) {
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;
		int cy = ch / 2 + 20;
		// violent shake building to the flash, then calming as the tint lands
		double envelope = Math.sin(Math.PI * clamp(el / (double) UPGRADE_MS));
		double amp = 7 + 7 * envelope;
		int dx = cx + (int) (Math.sin(el * 0.18) * amp);
		int dy = cy + (int) (Math.cos(el * 0.15) * amp * 0.5);

		// the jackpot reveal crossfades the two tiers' closed frames, which is a
		// truer upgrade than crossfading the trim colour alone ever was
		float mix = (float) clamp((el - 700) / 500.0);
		ceremonyPlayer.draw(g, dx, dy, chestW, chestH, opened.getPurchasedTier(), 0, 1f);
		if (mix > 0.01f) {
			ceremonyPlayer.draw(g, dx, dy, chestW, chestH, opened.getEffectiveTier(), 0, mix);
		}

		if (el >= 750 && el < 1250) {
			float flash = (float) Math.max(0, 0.7 * Math.exp(-(el - 750) / 200.0));
			g.setColor(new Color(255, 255, 255, (int) (flash * 255)));
			g.fillRect(0, 0, cw, ch);
		}
		float pulse = 0.75f + 0.25f * (float) Math.sin(el * 0.02);
		centre(g, "JACKPOT!", cw / 2, cy - chestH, FONT_HUGE,
			withAlpha(GOLD, pulse));
		centre(g, "Upgraded to " + opened.getEffectiveTier().name(),
			cw / 2, cy - chestH + 30, FONT_BODY, Color.WHITE);
	}

	private void drawChestDeal(Graphics2D g, int cw, int ch, long el, long now) {
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;

		// the open chest eases down toward the bottom edge, then fades out
		// once the last card has left it
		double drop = smoothstep(clamp(el / (double) DEAL_CHEST_DROP_MS));
		int chestCy = (int) lerp(ch / 2.0 + 20, ch - chestH / 2.0 - 24, drop);
		long total = dealTotalMs(cards.size());
		float chestAlpha = (float) clamp((total - el) / 320.0);
		if (chestAlpha > 0.02f) {
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, chestAlpha));
			// openT must be exactly 1.0 here: values above 1 add blast lift,
			// which detaches the lid from the hinge while the chest slides
			// (the lid angle is clamped at 1.0 either way)
			ceremonyPlayer.draw(g, cx, chestCy, chestW, chestH, opened.getEffectiveTier(),
				ceremonyPlayer.lastFrame(opened.getEffectiveTier()), 1f);
			g.setComposite(old);
		}

		// cards launch FACE-DOWN out of the chest opening along an eased arc,
		// staggered, then settle into the row with a small bounce
		int mouthX = cx;
		int mouthY = chestCy - chestH / 2 - 6;
		int n = cards.size();
		for (int i = 0; i < n; i++) {
			long t = el - DEAL_CHEST_DROP_MS - i * DEAL_STAGGER_MS;
			if (t < 0)
				continue;
			slotRect(i, n, cw, ch, rect);
			if (t < DEAL_FLIGHT_MS) {
				double u = easeOutCubic(t / (double) DEAL_FLIGHT_MS);
				double scale = 0.35 + 0.65 * u;
				int sw = (int) (rect.width * scale);
				int sh = (int) (rect.height * scale);
				double ex = rect.x + rect.width / 2.0;
				double ey = rect.y + rect.height / 2.0;
				// quadratic arc: control point well above both endpoints
				double ctrlX = (mouthX + ex) / 2.0;
				double ctrlY = Math.min(mouthY, ey) - rect.height * 0.9;
				double omu = 1.0 - u;
				double px = omu * omu * mouthX + 2 * omu * u * ctrlX + u * u * ex;
				double py = omu * omu * mouthY + 2 * omu * u * ctrlY + u * u * ey;
				double rot = (1.0 - u) * (i % 2 == 0 ? -0.30 : 0.30);
				Graphics2D g2 = copy(g);
				g2.rotate(rot, px, py);
				CardRenderer.drawBack(g2, (int) (px - sw / 2.0), (int) (py - sh / 2.0), sw, sh, now);
				g2.dispose();
			}
			else {
				// landed: settle bounce (dip down + tiny squash, decaying)
				long st = t - DEAL_FLIGHT_MS;
				int dy = 0;
				double squash = 1.0;
				if (st < DEAL_SETTLE_MS) {
					double v = st / (double) DEAL_SETTLE_MS;
					dy = (int) (5 * Math.sin(Math.PI * v) * (1.0 - v * 0.4));
					squash = 1.0 - 0.05 * Math.sin(Math.PI * v);
				}
				int sh = (int) (rect.height * squash);
				CardRenderer.drawBack(g, rect.x,
					rect.y + dy + (rect.height - sh), rect.width, sh, now);
			}
		}
	}

	private void drawChestReveal(Graphics2D g, int cw, int ch, long now) {
		int n = cards.size();
		for (int i = 0; i < n; i++) {
			slotRect(i, n, cw, ch, rect);
			drawRevealSlot(g, i, rect, now);
		}

		if (shockAt > 0 && now >= shockAt
			&& now - shockAt < SHOCKWAVE_MS) {
			drawShockwave(g, cw, ch, now - shockAt);
		}
		if (pityFlipMs > 0 && now - pityFlipMs < PITY_GLOW_MS) {
			drawPityEdgeGlow(g, cw, ch, now - pityFlipMs);
		}

		String hint = phase == PH_CHEST_WAIT
			? "Click anywhere to collect"
			: "Click cards to reveal - Esc to skip";
		centre(g, hint, cw / 2, ch - 30, FONT_BODY, HINT);
	}

	private void drawRevealSlot(Graphics2D g, int i, Rectangle r, long now) {
		ChestService.RolledSlot slot = cards.get(i);
		Color trueColor = slot.getRarity().getColor();

		if (rerollAt[i] > 0) {
			long t = now - rerollAt[i];
			if (t < REROLL_FLIPBACK_MS) {
				// face flips back over (cosine ease-in-out): starts face-first,
				// so the positive half of the cosine is the FACE
				drawScaledX(g, r, Math.cos(Math.PI * t / (double) REROLL_FLIPBACK_MS),
					true, i, now);
			}
			else {
				// face-down shimmer while the replacement is prepared
				CardRenderer.drawBack(g, r.x, r.y, r.width, r.height, now);
				g.setColor(CardRenderer.prismaticColor(now, i * 60));
				g.setStroke(new BasicStroke(2.5f));
				g.drawRoundRect(r.x, r.y, r.width, r.height, r.width / 7, r.width / 7);
			}
			return;
		}

		if (flipAt[i] == 0) {
			// face-down; hover charge-up glow toward the TRUE rarity color
			if (hoverCharge[i] > 0.01f) {
				CardRenderer.drawGlow(g, r.x, r.y, r.width, r.height, trueColor, hoverCharge[i]);
			}
			CardRenderer.drawBack(g, r.x, r.y, r.width, r.height, now);
			if (opened.isStardustBlessed()) {
				// subtle blessed shimmer on unrevealed backs
				float pulse = 0.18f + 0.14f * (float) Math.sin(now * 0.004);
				g.setColor(withAlpha(STARDUST, pulse));
				g.setStroke(new BasicStroke(1.6f));
				g.drawRoundRect(r.x, r.y, r.width, r.height, r.width / 7, r.width / 7);
			}
			return;
		}

		long t = now - flipAt[i];
		if (t < 0) {
			CardRenderer.drawBack(g, r.x, r.y, r.width, r.height, now);
			return;
		}
		if (t < FLIP_MS) {
			// horizontal scale 1 -> 0 (back), swap, 0 -> 1 (face); the cosine
			// gives ease-in-out and the face NEVER paints in the first half
			drawScaledX(g, r, Math.cos(Math.PI * t / (double) FLIP_MS), false, i, now);
			return;
		}

		// steady face-up
		CardRenderer.drawGlow(g, r.x, r.y, r.width, r.height, trueColor, 0.35f);
		CardRenderer.drawFace(g, r.x, r.y, r.width, r.height, cardViewFor(i), now);

		if (slot.isDuplicate()) {
			Integer gc = Tuning.DUPLICATE_GC.get(slot.getRarity());
			drawChip(g, r.x + r.width - 8, r.y - 6, "+" + (gc == null ? 0 : gc) + " GC");
		}
		if (slot.isNearMiss()) {
			drawChip(g, r.x + r.width - 8, r.y + r.height + 6, "+1 Stardust");
			long ft = t - FLIP_MS;
			if (ft >= 0 && ft < FIZZLE_MS) {
				drawStardustFizzle(g, r, ft);
			}
		}
		if (i == 0 && opened.isPityBreak()) {
			centre(g, "PITY BREAK", r.x + r.width / 2, r.y - 12, FONT_BODY, GOLD);
		}
		if (canReroll.length > i && canReroll[i] && phase == PH_CHEST_REVEAL) {
			drawRerollToken(g, r, now);
		}
	}

	/**
	 * Draw one card side horizontally squashed (flip animation).
	 *
	 * <p>{@code v} is the raw signed cosine of the flip: its magnitude is the
	 * horizontal scale, and its sign says which side is facing the viewer. Both
	 * callers differ only in WHICH side that is — a reveal turns back-first, a
	 * re-roll turns face-first — so they hand over the cosine untouched and name
	 * that difference with {@code faceWhenPositive}. At exactly zero the card is
	 * edge-on and the early return below draws nothing, whichever side "wins".
	 */
	private void drawScaledX(Graphics2D g, Rectangle r, double v, boolean faceWhenPositive,
		int i, long now) {
		double scaleX = Math.abs(v);
		if (scaleX < 0.04)
			return;
		boolean face = (v > 0) == faceWhenPositive;
		Graphics2D g2 = copy(g);
		double cx = r.x + r.width / 2.0;
		g2.translate(cx, 0);
		g2.scale(scaleX, 1);
		g2.translate(-cx, 0);
		if (face) {
			CardRenderer.drawFace(g2, r.x, r.y, r.width, r.height, cardViewFor(i), now);
		}
		else {
			CardRenderer.drawBack(g2, r.x, r.y, r.width, r.height, now);
		}
		g2.dispose();
	}

	private CardRenderer.CardView cardViewFor(int i) {
		CardRenderer.CardView view = cardViews[i];
		if (view != null)
			return view;
		ChestService.RolledSlot slot = cards.get(i);
		String name;
		String subtitle = null;
		BufferedImage art = null;
		if (slot.getHologramTier() != null) {
			HologramDefinition holo = cardDatabase.holograms().get(slot.getHologramTier());
			String tierLabel = capitalize(slot.getHologramTier());
			name = holo != null ? holo.getName() : tierLabel + " Hologram";
			subtitle = tierLabel + " tier - one slot, any item";
			if (holo != null) {
				art = cardImageService.hologramImage(holo, null);
			}
		}
		else {
			CardDefinition card = cardDatabase.card(slot.getCardId());
			name = card != null ? card.getName() : "Card #" + slot.getCardId();
			if (card != null) {
				art = cardImageService.cardImage(card, null);
			}
		}
		// The record of the copies already owned, which is what the wear badge
		// and the service pill both report: "this card of yours has carried N
		// kills", not a claim about the copy being revealed (it has carried
		// none). A first-ever pull reads 0 and stays pristine. Holograms are
		// filed per tier rather than per card id, so they sit outside
		// bestByCardId and simply read 0 here.
		int served = slot.getHologramTier() != null
			? 0 : serviceSnapshot.getOrDefault(slot.getCardId(), 0);
		view = CardRenderer.CardView.builder()
			.name(name)
			.rarity(slot.getRarity())
			.variant(slot.getVariant())
			.art(art)
			.subtitle(subtitle)
			.killsServed(served)
			.build();
		cardViews[i] = view;
		return view;
	}

	private void slotRect(int i, int n, int cw, int ch, Rectangle out) {
		int gap = 24;
		int cardW = Math.min(150, Math.max(70, (cw - 80 - (n - 1) * gap) / Math.max(1, n)));
		int cardH = cardW * 29 / 20;
		int totalW = n * cardW + (n - 1) * gap;
		int x0 = (cw - totalW) / 2;
		int y = (ch - cardH) / 2 + 10;
		out.setBounds(x0 + i * (cardW + gap), y, cardW, cardH);
	}

	private static boolean rerollButtonHit(Rectangle cardRect, int px, int py) {
		int bx = cardRect.x + cardRect.width - 16;
		int by = cardRect.y + cardRect.height - 16;
		int dx = px - bx;
		int dy = py - by;
		return dx * dx + dy * dy <= 15 * 15;
	}

	private void drawRerollToken(Graphics2D g, Rectangle r, long now) {
		int bx = r.x + r.width - 16;
		int by = r.y + r.height - 16;
		g.setColor(new Color(30, 26, 16, 230));
		g.fillOval(bx - 13, by - 13, 26, 26);
		float pulse = 0.7f + 0.3f * (float) Math.sin(now * 0.006);
		g.setColor(withAlpha(GOLD, pulse));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(bx - 13, by - 13, 26, 26);
		// circular re-roll arrow
		g.drawArc(bx - 7, by - 7, 14, 14, 30, 280);
		g.drawLine(bx + 6, by - 4, bx + 9, by - 8);
		g.drawLine(bx + 6, by - 4, bx + 2, by - 6);
	}

	private boolean isFaceUpSteady(int i, long now) {
		return flipAt[i] > 0 && rerollAt[i] == 0 && now - flipAt[i] >= FLIP_MS;
	}

	private void drawChip(Graphics2D g, int rightX, int y, String text) {
		FontMetrics fm = metrics(g, FONT_SMALL);
		int w = fm.stringWidth(text) + 12;
		int h = fm.getHeight() + 4;
		int x = rightX - w;
		g.setColor(new Color(20, 16, 8, 230));
		g.fillRoundRect(x, y, w, h, 8, 8);
		g.setColor(GOLD);
		g.drawRoundRect(x, y, w, h, 8, 8);
		g.drawString(text, x + 6, y + h - 6);
	}

	/**
	 * The shiny near-miss: a handful of sparkles drift up off the card and
	 * fizzle out — a promise glimpsed, not kept. Runs once per flip for
	 * {@link #FIZZLE_MS} after the face lands.
	 */
	private void drawStardustFizzle(Graphics2D g, Rectangle r, long t) {
		float u = (float) clamp(t / (double) FIZZLE_MS);
		Graphics2D g2 = copy(g);
		// brief center glint that shrinks instead of shockwaving
		if (t < 150) {
			float gu = 1f - t / 150f;
			int gr = (int) (10 * gu);
			g2.setColor(withAlpha(Color.WHITE, 0.6f * gu));
			g2.setStroke(new BasicStroke(1.5f));
			int gcx = r.x + r.width / 2;
			int gcy = r.y + r.height / 3;
			g2.drawLine(gcx - gr, gcy, gcx + gr, gcy);
			g2.drawLine(gcx, gcy - gr, gcx, gcy + gr);
		}
		g2.setStroke(new BasicStroke(1.2f));
		for (int p = 0; p < 12; p++) {
			float h1 = hash01(p * 131 + 7);
			float h2 = hash01(p * 131 + 8);
			float h3 = hash01(p * 131 + 9);
			float pu = (float) clamp((t - h3 * 250) / (double) (FIZZLE_MS - 250));
			if (pu <= 0 || pu >= 1)
				continue;
			double rise = easeOutCubic(pu) * (18 + h2 * 26);
			int px = r.x + (int) (h1 * r.width);
			int py = r.y + (int) (h2 * r.height * 0.5) - (int) rise;
			float alpha = pu > 0.66f ? (1f - pu) * 3f : 1f;
			g2.setColor(withAlpha(STARDUST, 0.75f * alpha));
			int s = 2 + (int) (h3 * 2);
			g2.drawLine(px - s, py, px + s, py);
			g2.drawLine(px, py - s, px, py + s);
		}
		g2.dispose();
	}

	private void drawShockwave(Graphics2D g, int cw, int ch, long t) {
		float u = (float) clamp(t / (double) SHOCKWAVE_MS);
		double maxR = Math.hypot(cw, ch) * 0.55;
		double r = smoothstep(u) * maxR;
		// one number, not two: the ring's alpha, the stroke weights and the spark
		// size all fade on exactly (1 - u), which was spelled out beside its own
		// named copy three times over
		float fade = (1 - u);

		Graphics2D g2 = copy(g);
		g2.setColor(withAlpha(shockwaveColor, fade * 0.85f));
		g2.setStroke(new BasicStroke(2f + fade * 18f));
		g2.drawOval(shockCx - (int) r, shockCy - (int) r, (int) (r * 2), (int) (r * 2));
		double r2 = smoothstep(clamp(u * 1.35)) * maxR * 0.7;
		g2.setColor(withAlpha(Color.WHITE, fade * 0.45f));
		g2.setStroke(new BasicStroke(1.5f + fade * 8f));
		g2.drawOval(shockCx - (int) r2, shockCy - (int) r2, (int) (r2 * 2), (int) (r2 * 2));

		// Vignette breath. Deliberately NOT drawEdgeBands: this one is a flat
		// colour and its side bands stop short of the corners, so the corners are
		// inked once. Running it through the gradient helper would blend them
		// twice and ring the flash with four dark blocks.
		float vig = (float) Math.sin(Math.PI * u) * 0.35f;
		g2.setColor(new Color(0, 0, 0, (int) (vig * 255)));
		int band = Math.max(30, ch / 7);
		g2.fillRect(0, 0, cw, band);
		g2.fillRect(0, ch - band, cw, band);
		g2.fillRect(0, band, band, ch - band * 2);
		g2.fillRect(cw - band, band, band, ch - band * 2);

		// 24 deterministic particles with fake gravity (positions are pure
		// functions of elapsed time + seed; nothing allocated per frame)
		double ts = t / 1000.0;
		for (int p = 0; p < 24; p++) {
			float h1 = hash01(shockwaveSeed + p * 3);
			float h2 = hash01(shockwaveSeed + p * 3 + 1);
			float h3 = hash01(shockwaveSeed + p * 3 + 2);
			double ang = h1 * Math.PI * 2;
			double speed = (90 + h2 * 320);
			int px = shockCx + (int) (Math.cos(ang) * speed * ts);
			int py = shockCy + (int) (Math.sin(ang) * speed * ts + 340 * ts * ts);
			// hash01 and fade are both non-negative, so this never falls below
			// the base 2 and every particle always has a body to draw
			int size = 2 + (int) (h3 * 3 * fade);
			g2.setColor(withAlpha((p & 1) == 0 ? shockwaveColor : Color.WHITE, fade));
			g2.fillRect(px, py, size, size);
		}
		g2.dispose();
	}

	private void drawPityEdgeGlow(Graphics2D g, int cw, int ch, long t) {
		float ramp = (float) clamp(t / 250.0);
		float decay = (float) clamp(1.0 - (t - 250) / (double) (PITY_GLOW_MS - 250));
		float a = ramp * decay * 0.55f;
		if (a <= 0.02f)
			return;
		drawEdgeBands(g, cw, ch, Math.max(26, ch / 9),
			withAlpha(GOLD, a), withAlpha(GOLD, 0f));
	}

	/**
	 * The four-sided gradient vignette: every edge fades from {@code outer} at
	 * the border to {@code inner} {@code band} px inwards.
	 *
	 * <p>The left and right bands run the full height rather than stopping at the
	 * top and bottom ones, so the corners take two passes and come out heavier
	 * than the edges. That double blend is the point — it is what rounds the
	 * frame instead of leaving four butted rectangles — and it is why the pity
	 * glow and the offer backdrop must share one implementation rather than two
	 * that could drift apart in the order they lay the bands down.
	 */
	private static void drawEdgeBands(Graphics2D g, int cw, int ch, int band,
		Color outer, Color inner) {
		g.setPaint(new GradientPaint(0, 0, outer, 0, band, inner));
		g.fillRect(0, 0, cw, band);
		g.setPaint(new GradientPaint(0, ch, outer, 0, ch - band, inner));
		g.fillRect(0, ch - band, cw, band);
		g.setPaint(new GradientPaint(0, 0, outer, band, 0, inner));
		g.fillRect(0, 0, band, ch);
		g.setPaint(new GradientPaint(cw, 0, outer, cw - band, 0, inner));
		g.fillRect(cw - band, 0, band, ch);
	}

	// =====================================================================
	// STYLE ROLL ROULETTE
	// =====================================================================

	/**
	 * Null-guarded because finishModalLocked clears styleResult while a repaint
	 * may still be in flight, and a spin length of zero would divide the eased
	 * curve by nothing. The wheel's landing angle is solved backwards from the
	 * result, so stretching the duration changes only how long it takes to get
	 * there — never where it stops.
	 */
	private long spinMs() {
		return styleResult != null && styleResult.getPrevious() == null ? FIRST_SPIN_MS : SPIN_MS;
	}


	/** Radius the wheel chrome was authored at; sprites scale from here. */
	private static final int WHEEL_ART_R = 190;

	/**
	 * A wheel sprite drawn about an anchor point, scaled from the radius it was
	 * authored at. Only the placement maths lives here — the decode and the
	 * cached-miss sentinel are {@link ArtCache}'s, shared with the scrolls.
	 */
	static void blitArt(Graphics2D g, String name, int cx, int cy, double scale,
		int anchorX, int anchorY) {
		Image art = ArtCache.get(name);
		if (art == null)
			return;
		g.drawImage(art,
			cx - (int) Math.round(anchorX * scale), cy - (int) Math.round(anchorY * scale),
			(int) Math.round(art.getWidth(null) * scale),
			(int) Math.round(art.getHeight(null) * scale), null);
	}

	private void drawStyleRoll(Graphics2D g, int cw, int ch, long now) {
		long el = now - phaseAt;
		boolean result = phase == PH_SPIN_RESULT;
		// honest backward-solved deceleration - unchanged
		double t = result ? 1.0 : clamp(el / (double) spinMs());
		double theta = wheelThetaEnd * (1 - Math.pow(1 - t, 3));
		long rt = result ? el : 0;

		int radius = Math.min(Math.min(cw, ch) / 3, 190);
		int cx = cw / 2;
		int cy = ch / 2 + 10;

		centre(g, "STYLE ROULETTE", cx, cy - radius - 64, FONT_TITLE, GOLD);
		boolean firstEver = styleResult.getPrevious() == null;
		if (firstEver) {
			// the longer first spin is only worth the seconds if the player is told
			// what it is deciding; every roll after this one is merely a re-roll
			centre(g, "Your first colours - and a chest to match",
				cx, cy - radius - 44, FONT_SMALL, CAP_INK, false);
		}

		double art = radius / (double) WHEEL_ART_R;
		blitArt(g, "wheel-shadow", cx, cy, art, 220, 220);

		AttackStyle[] styles = AttackStyle.values();
		AttackStyle rolled = styleResult.getRolled();
		float desat = result ? (float) clamp(rt / 600.0) : 0f;
		for (int i = 0; i < styles.length; i++) {
			Color base = styles[i].getColor();
			Color fill = styles[i] == rolled ? base : mixColor(base, desaturate(base), desat);
			g.setColor(fill);
			g.fillArc(cx - radius, cy - radius, radius * 2, radius * 2,
				(int) Math.round(i * 120 + theta), 120);
		}

		// winning wedge pulses outward 3 times with a glow ring
		if (result && rt < 1800) {
			int pulseIdx = (int) (rt / 600);
			if (pulseIdx < 3) {
				float pw = (float) Math.sin(Math.PI * ((rt % 600) / 600.0));
				int pr = (int) (radius * (1 + 0.06f * pw));
				g.setColor(rolled.getColor());
				g.fillArc(cx - pr, cy - pr, pr * 2, pr * 2,
					(int) Math.round(rolled.ordinal() * 120 + theta), 120);
				int ringR = (int) (radius * (1.04f + 0.12f * pw));
				g.setColor(withAlpha(rolled.getColor(), pw * 0.65f));
				g.setStroke(new BasicStroke(3f + pw * 4f));
				g.drawOval(cx - ringR, cy - ringR, ringR * 2, ringR * 2);
			}
		}
		if (result) {
			float bloom = 0.35f + 0.20f * (float) Math.sin(rt * 0.006);
			CardRenderer.drawGlow(g, cx - radius, cy - radius, radius * 2, radius * 2,
				rolled.getColor(), bloom * 0.6f);
		}

		// wedge separators with a bright edge
		for (int i = 0; i < 3; i++) {
			double a = Math.toRadians(i * 120 + theta);
			int ex = cx + (int) (Math.cos(a) * radius);
			int ey = cy - (int) (Math.sin(a) * radius);
			g.setColor(new Color(20, 16, 10));
			g.setStroke(new BasicStroke(4f));
			g.drawLine(cx, cy, ex, ey);
			g.setColor(new Color(255, 240, 200, 60));
			g.setStroke(new BasicStroke(1.2f));
			g.drawLine(cx, cy, ex, ey);
		}

		// sheen, metallic rim and the twelve bolts: none of it turns with the
		// wheel and none of it depends on the result, so it is one PNG
		blitArt(g, "wheel-rim", cx, cy, art, 220, 220);

		// wedge labels
		g.setFont(FONT_BODY);
		for (int i = 0; i < styles.length; i++) {
			double mid = Math.toRadians(i * 120 + 60 + theta);
			int lx = cx + (int) (Math.cos(mid) * radius * 0.62);
			int ly = cy - (int) (Math.sin(mid) * radius * 0.62);
			Color lc = (result && styles[i] != rolled)
				? withAlpha(Color.WHITE, 1f - desat * 0.55f) : Color.WHITE;
			centre(g, styles[i].getDisplayName().toUpperCase(), lx, ly, FONT_BODY,
				lc);
		}

		blitArt(g, "wheel-hub", cx, cy, 1.0, 32, 30);
		drawWheelPointer(g, cx, cy, radius, theta);

		if (result) {
			// after the chrome, never before it: the wheel's drop shadow is a
			// sprite anchored well outside the radius it is scaled from, and it
			// would wash over the lower caption lines if they were laid down first
			drawWeaponCaption(g, cx, cy - radius, firstEver);
			drawStyleResultBanner(g, cw, ch, cx, cy, radius, rt, rolled);
		}
	}


	/**
	 * Gold pointer flap on a pivot above the wheel; it kicks each time a wedge
	 * boundary sweeps past and eases back. The flap is a fixed 30px shape at any
	 * radius, so it is a sprite rotated about the pivot - the pivot shadow and
	 * cap stay drawn here because they must NOT turn with it.
	 */
	private void drawWheelPointer(Graphics2D g, int cx, int cy, int radius, double theta) {
		double since = (((theta - 90) % 120) + 120) % 120;
		double kick = -0.55 * Math.exp(-since / 9.0);
		int pivotY = cy - radius - 16;
		g.setColor(new Color(0, 0, 0, 110));
		g.fillOval(cx - 9, pivotY + 3, 18, 10);
		Graphics2D g2 = copy(g);
		g2.rotate(kick, cx, pivotY);
		blitArt(g2, "wheel-pointer", cx, pivotY, 1.0, 20, 12);
		g2.dispose();
		g.setPaint(new GradientPaint(cx - 6, pivotY - 6, RIM_SILVER_HI, cx + 6, pivotY + 6, RIM_SILVER_LO));
		g.fillOval(cx - 6, pivotY - 6, 12, 12);
		g.setColor(new Color(40, 36, 30));
		g.setStroke(new BasicStroke(1.2f));
		g.drawOval(cx - 6, pivotY - 6, 12, 12);
	}


	/** Result banner sliding in on a dark ribbon tinted with the style color. */
	private void drawStyleResultBanner(Graphics2D g, int cw, int ch, int cx, int cy, int radius,
		long rt, AttackStyle rolled) {
		double slide = easeOutCubic(clamp(rt / 380.0));
		int bw = Math.min(540, cw - 30);
		int bh = 48;
		int bx = (cw - bw) / 2 + (int) ((1.0 - slide) * (cw / 2.0 + bw));
		int by = Math.min(cy + radius + 26, ch - bh - 44);

		g.setColor(new Color(0, 0, 0, 70));
		g.fillRect(bx + 4, by + 5, bw, bh);
		g.setPaint(new GradientPaint(bx, by, new Color(26, 20, 12, 245),
			bx, by + bh, new Color(14, 10, 6, 245)));
		g.fillRect(bx, by, bw, bh);
		Color ink = rolled.getColor();
		g.setColor(ink);
		g.fillRect(bx, by, bw, 3);
		g.fillRect(bx, by + bh - 3, bw, 3);
		// ribbon tails
		g.setColor(withAlpha(ink, 0.65f));
		g.fillRect(bx - 10, by + 6, 10, bh - 12);
		g.fillRect(bx + bw, by + 6, 10, bh - 12);

		// pluralised: a Compactor can halve the remaining cycle down to one, and
		// this banner is the ceremony's headline — "re-roll in 1 tasks" on the
		// biggest text the plugin draws
		int cycleTarget = styleResult.getCycleTarget();
		String line = rolled.getDisplayName().toUpperCase() + " ALLOWED - re-roll in "
			+ cycleTarget + (cycleTarget == 1 ? " contract" : " contracts");
		FontMetrics fm = metrics(g, FONT_TITLE);
		Font lineFont = fm.stringWidth(line) > bw - 24 ? FONT_BODY : FONT_TITLE;
		int mx = bx + bw / 2;
		centre(g, line, mx, by + bh / 2, lineFont, ink);

		AttackStyle was = styleResult.getPrevious();
		if (was != null && was != rolled) {
			centre(g, "(was " + was.getDisplayName() + ")",
				mx, by + bh + 14, FONT_SMALL, new Color(200, 200, 200), false);
		}
		centre(g, "Click to continue", cx, ch - 30, FONT_BODY, HINT);
	}

	/**
	 * The preferred weapon, named by its DISPLAY name and never by its key.
	 *
	 * <p>The key for category 0 is "unarmed"; its display name is "No weapon
	 * equipped". The game reports that same category for every non-weapon held
	 * item — a lantern, a bucket, a pet rock — so "unarmed" on screen would be a
	 * claim about the player's hands that the varbit never made. This is the one
	 * place in the plugin where that rule becomes visible, which is why
	 * WeaponCaptionTest walks the entire shipped taxonomy through this method
	 * rather than trusting the resource test alone.
	 */
	static String weaponLine(WeaponTypeService.WeaponType type) {
		return "Preferred weapon: " + (type == null ? "none this cycle" : type.getDisplayName());
	}

	/**
	 * What the preference is currently worth, in the only terms that let a player
	 * judge it: a share of everything a contract pays, at both ends of the ladder.
	 *
	 * <p>The multiplier alone would be a half-truth. "x1.5" sounds like half again
	 * as much money and is nothing of the kind — it multiplies the per-kill
	 * trickle only, never the completion bonus, and on an EASY contract the
	 * trickle is a small fraction of the pay. Naming both ends is what makes the
	 * shape of the thing legible: it is a rounding error on the bottom rung and a
	 * fifth of the whole contract on the top one, and the player is about to
	 * choose which rung to sign.
	 *
	 * <p>A null type is "no bonus available", NEVER a bonus the player failed to
	 * earn. The wheel names a category from the style's own pool and can only come
	 * back empty if the taxonomy resource did not load, which is this build's
	 * shortcoming and not the player's — and the preference is additive to their
	 * fortunes, so its absence costs them nothing that was ever theirs.
	 */
	static String worthLine(WeaponTypeService.WeaponType type) {
		if (type == null)
			return "no bonus available - the wheel named no category it could offer";
		return "x" + Tuning.WEAPON_BONUS_MULT + " on kill GC - about +"
			+ weaponWorthPct(TaskDifficulty.EASY) + "% of an "
			+ TaskDifficulty.EASY.getDisplayName() + " contract, +"
			+ weaponWorthPct(TaskDifficulty.INSANE) + "% of an "
			+ TaskDifficulty.INSANE.getDisplayName() + " one";
	}

	/**
	 * The bonus as a percentage of a whole contract's pay at this difficulty,
	 * derived from {@link Tuning} rather than written down.
	 *
	 * <p>Read off the constants that actually decide it — the mid kill count, the
	 * per-kill rate and the completion bonus — so that retuning any of the three
	 * moves this number with them. A printed literal would go quietly wrong the
	 * first time PER_KILL_GC changed, and a wrong number here is worse than none:
	 * the whole point of the line is that the player can trust it enough to plan
	 * against it.
	 *
	 * <p>It says "about" for two reasons that pull in opposite directions and are
	 * both real. The Rhythm Combo and the punching-up multiplier scale the KILL
	 * half and nothing else, so a maxed chain roughly doubles this share; a
	 * completion milestone scales the OTHER half by up to 15x and shrinks it to
	 * near nothing. The base-rate figure sits between them, and it is the only one
	 * that is true of every contract of that difficulty rather than of some.
	 *
	 * <p>It also assumes the named category is in hand for every on-task kill,
	 * which is exactly what a player acting on this line would do. The bonus is
	 * sampled at the killing blow, so kills landed with anything else simply pay
	 * the ordinary rate — this is the ceiling, and it is reachable.
	 */
	static int weaponWorthPct(TaskDifficulty difficulty) {
		double kills = (difficulty.getMinKills() + difficulty.getMaxKills()) / 2.0;
		double killGc = kills * Tuning.PER_KILL_GC.get(difficulty);
		return (int) Math.round(100 * (Tuning.WEAPON_BONUS_MULT - 1) * killGc
			/ (killGc + Tuning.COMPLETION_GC.get(difficulty)));
	}

	/**
	 * The caption under the roulette title: what the wheel named alongside the
	 * style, and what it is worth.
	 *
	 * <p>Laid out UPWARD from the top of the wheel, because the band it lives in
	 * is a fixed 64px by construction — the title is always drawn at
	 * {@code cy - radius - 64} — and anchoring to the wheel is what keeps the last
	 * line clear of it at every canvas size and radius.
	 *
	 * <p>The three offsets are measured, not guessed, and the band has no slack to
	 * spare. Against the sans-serif metrics these fonts actually resolve to
	 * (FONT_SMALL ascent 12 descent 3, FONT_BODY 15 and 4, FONT_TITLE 23 and 5)
	 * and {@link #centre}'s own {@code cy + ascent/2 - 2} baseline, the
	 * three-line block spans 53 of the 55px between the title's last inked row and
	 * the wheel, with each line's box abutting the next rather than overlapping
	 * it. Widening either step pushes the top line into "STYLE ROULETTE"; the
	 * title has no descenders, which is the only reason 55 rather than 50.
	 *
	 * <p>Drawn only once the wheel has stopped. Naming the category during the
	 * spin would name the STYLE with it: the category is rolled from the rolled
	 * style's own pool, so a crossbow on screen is a ranged wheel that has not
	 * landed yet.
	 *
	 * @param crowded the first-roll subtitle already occupies the middle of the
	 *                band, so there is room for two lines instead of three
	 */
	private void drawWeaponCaption(Graphics2D g, int cx, int wheelTop, boolean crowded) {
		int y = wheelTop - 11;
		if (!crowded && styleResult.getWeaponType() != null) {
			centre(g, WORTH_CAVEAT, cx, y, FONT_SMALL, CAP_DIM);
			y -= 15;
		}
		centre(g, worthLine, cx, y, FONT_SMALL, CAP_INK);
		centre(g, weaponLine, cx, y - 17, FONT_BODY, GOLD);
	}

	private static Color desaturate(Color c) {
		int grey = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
		return new Color((c.getRed() + grey * 2) / 3, (c.getGreen() + grey * 2) / 3,
			(c.getBlue() + grey * 2) / 3);
	}

	// =====================================================================
	// TASK OFFERS
	// =====================================================================

	private void offerRect(int i, int n, int cw, int ch, Rectangle out) {
		int gap = 18;
		int cardW = Math.min(195, Math.max(120, (cw - 60 - (n - 1) * gap) / Math.max(1, n)));
		int cardH = Math.min(ch - 140, cardW * 3 / 2 + 46);
		int totalW = n * cardW + (n - 1) * gap;
		int x0 = (cw - totalW) / 2;
		int y = (ch - cardH) / 2 + 8;
		out.setBounds(x0 + i * (cardW + gap), y, cardW, cardH);
	}

	private long unrollTotalMs() {
		return (offers.size() - 1) * OFFER_UNROLL_STAGGER_MS + OFFER_UNROLL_MS + 150;
	}

	private void drawOffers(Graphics2D g, int cw, int ch, long now) {
		long el = phase == PH_OFFERS_UNROLL ? now - phaseAt : Long.MAX_VALUE / 4;
		int n = offers.size();

		drawOffersBackdrop(g, cw, ch);
		String hint = phase == PH_OFFERS_UNROLL
			? "Esc/Space to skip"
			: (phase == PH_OFFERS_SETTLED ? "Click a contract to accept - Esc to decide later" : "");
		if (!hint.isEmpty()) {
			centre(g, hint, cw / 2, ch - 26, FONT_BODY, HINT);
		}

		boolean burnPhase = phase == PH_OFFERS_ACCEPTED;
		float burnT = burnPhase
			? (float) clamp((now - phaseAt) / (double) OFFER_BURN_MS) : 0f;

		// once per FRAME, not once per scroll: the snapshot allocates, and asking
		// four times a frame would also let two scrolls disagree about the tally
		// if a ballot landed between them
		frameVotes = partyVoteSupplier == null ? null : partyVoteSupplier.get();

		for (int i = 0; i < n; i++) {
			offerRect(i, n, cw, ch, rect);
			Rectangle r = rect;

			// scrolls unroll in place, staggered left to right
			double u = 1.0;

			if (phase == PH_OFFERS_UNROLL) {
				long t = el - i * OFFER_UNROLL_STAGGER_MS;
				u = t <= 0 ? 0 : easeOutBack(t / (double) OFFER_UNROLL_MS);
			}

			boolean accepted = burnPhase && i == acceptedIndex;
			boolean burning = burnPhase && i != acceptedIndex;
			boolean hovered = phase == PH_OFFERS_SETTLED && pointerValid
				&& r.contains(pointerX, pointerY);

			// hovered (or accepted) scroll lifts 6px with a stronger shadow
			int lift = (hovered || accepted) ? 6 : 0;
			rect2.setBounds(r.x, r.y - lift, r.width, r.height);

			// shadow tracks only the occupied extent (rollers + revealed sheet)
			int topEdge = ScrollPainter.topRollerCy(rect2, u) - ScrollPainter.ROLLER_H / 2;
			int botEdge = ScrollPainter.bottomRollerCy(rect2, u) + ScrollPainter.ROLLER_H / 2;
			rect2.setBounds(r.x, topEdge, r.width, botEdge - topEdge);
			drawSoftShadow(g, rect2, lift > 0 ? 0.55f : 0.35f, lift);
			rect2.setBounds(r.x, r.y - lift, r.width, r.height);

			if (burning) {
				drawBurningOffer(g, i, rect2, now, burnT);
				continue;
			}
			drawOfferScroll(g, i, rect2, u);
			if (accepted) {
				drawAcceptedStamp(g, rect2, now - phaseAt);
			}
		}
	}

	/** Dark vignette backdrop plus the gold-trimmed ceremony header. */
	private void drawOffersBackdrop(Graphics2D g, int cw, int ch) {
		drawEdgeBands(g, cw, ch, Math.max(70, ch / 4),
			new Color(0, 0, 0, 185), new Color(0, 0, 0, 0));

		int hw = Math.min(430, cw - 60);
		int hh = 46;
		int hx = (cw - hw) / 2;
		int hy = 16;
		g.setPaint(new GradientPaint(hx, hy, new Color(34, 26, 12, 240),
			hx, hy + hh, new Color(18, 13, 6, 240)));
		g.fillRoundRect(hx, hy, hw, hh, 10, 10);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(hx, hy, hw, hh, 10, 10);
		g.setColor(withAlpha(GOLD, 0.55f));
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(hx + 4, hy + 4, hw - 8, hh - 8, 7, 7);
		centre(g, "CHOOSE YOUR CONTRACT", cw / 2, hy + hh / 2, FONT_TITLE, GOLD);
		// flanking diamond accents
		g.setColor(GOLD);
		int dyc = hy + hh / 2;
		for (int s = -1; s <= 1; s += 2) {
			int dxc = cw / 2 + s * (hw / 2 - 22);
			g.fillPolygon(new int[]{dxc, dxc + 5, dxc, dxc - 5},
				new int[]{dyc - 5, dyc, dyc + 5, dyc}, 4);
		}
	}

	/** Layered soft drop shadow under a contract card. */
	private static void drawSoftShadow(Graphics2D g, Rectangle r, float strength, int lift) {
		for (int i = 3; i >= 1; i--) {
			float a = strength * 0.16f * (4 - i);
			g.setColor(new Color(0, 0, 0, (int) (a * 255)));
			int spread = i * 3;
			g.fillRoundRect(r.x - spread / 2, r.y + 5 + lift + i * 2,
				r.width + spread, r.height, 16, 16);
		}
	}

	/**
	 * One contract scroll at unroll progress u (0 = closed, rollers together
	 * in the middle; 1 = fully open). The parchment between the rollers is
	 * revealed purely by clipping - every line of contract data is already
	 * inked at its final position from the first visible pixel. While
	 * unrolling, a ~12px band at each leading edge is vertically squashed to
	 * fake the curl of parchment still coming off the roller.
	 */
	private void drawOfferScroll(Graphics2D g, int i, Rectangle r, double u) {
		int topCy = ScrollPainter.topRollerCy(r, u);
		int botCy = ScrollPainter.bottomRollerCy(r, u);
		int revTop = topCy + ScrollPainter.ROLLER_H / 2;
		int revBot = botCy - ScrollPainter.ROLLER_H / 2;
		int pX = r.x + ScrollPainter.PARCH_INSET;
		int pW = r.width - ScrollPainter.PARCH_INSET * 2;

		// under everything: the scroll should sit ABOVE the scene, and nothing
		// says "in front of" like something casting onto what is behind it
		ScrollPainter.drawDropShadow(g, r, u);

		if (revBot > revTop) {
			if (u >= 1.0) {
				Graphics2D g2 = copy(g);
				g2.clipRect(r.x, revTop, r.width, revBot - revTop);
				drawScrollBody(g2, i, r);
				g2.dispose();
			}
			else {
				int band = Math.min(ScrollPainter.CURL_BAND, (revBot - revTop) / 2);
				double squash = 0.70 + 0.30 * u;
				Graphics2D g2 = copy(g);
				g2.clipRect(r.x, revTop + band, r.width, revBot - revTop - band * 2);
				drawScrollBody(g2, i, r);
				g2.dispose();
				if (band > 0) {
					// leading-edge curl: the band nearest each roller shows the
					// same inked content, vertically squashed toward the roller
					drawCurl(g, i, r, revTop, revTop, band, squash);
					drawCurl(g, i, r, revBot - band, revBot, band, squash);
				}
			}
			// contact shading where the sheet meets each roller
			ScrollPainter.drawEdgeShade(g, pX, pW, revTop, true);
			ScrollPainter.drawEdgeShade(g, pX, pW, revBot, false);
		}

		OfferScrollArt art = offerArt[i];
		// the rods counter-rotate as the sheet pays out: the top one winds up,
		// the bottom one down, which is what makes the paper look like it is
		// coming OFF them rather than the two simply drifting apart
		ScrollPainter.drawRoller(g, r, topCy, art.tier, -u * 1.6);
		ScrollPainter.drawRoller(g, r, botCy, art.tier, u * 1.6);
	}

	/**
	 * One leading-edge curl: the band of sheet nearest a roller, showing the same
	 * inked content squashed vertically toward it.
	 *
	 * <p>The top and bottom curls were written out identically, seven lines each,
	 * and differ only in where the clip sits and which edge the squash pivots
	 * about — {@code clipY} and {@code pivot} are the same number at the top and
	 * a band apart at the bottom, which is the whole of it.
	 */
	private void drawCurl(Graphics2D g, int i, Rectangle r, int clipY, int pivot,
		int band, double squash) {
		Graphics2D g2 = copy(g);
		g2.clipRect(r.x, clipY, r.width, band);
		g2.translate(0, pivot);
		g2.scale(1, squash);
		g2.translate(0, -pivot);
		drawScrollBody(g2, i, r);
		g2.dispose();
	}

	/**
	 * The fully-inked parchment sheet at its final layout; callers clip (and
	 * optionally squash) this to the window revealed between the rollers.
	 */
	private void drawScrollBody(Graphics2D g, int i, Rectangle r) {
		TaskOffer offer = offers.get(i);
		OfferScrollArt art = offerArt[i];
		int pX = r.x + ScrollPainter.PARCH_INSET;
		int pW = r.width - ScrollPainter.PARCH_INSET * 2;
		int parchTop = r.y + ScrollPainter.ROLLER_H;
		int parchH = r.height - ScrollPainter.ROLLER_H * 2;
		int pBot = parchTop + parchH;

		ScrollPainter.drawParchment(g, pX, parchTop, pW, parchH,
			art.parchTop, art.parchBottom, art.edge);

		// Difficulty band across the head of the sheet.
		//
		// A dusty wash with INK lettering, not a saturated fill with white text:
		// a solid colour bar under white type is poster furniture, and it was the
		// single loudest thing on the page — the parchment could be perfect and
		// the scroll would still read as a cartoon while that sat on top of it.
		// The tier still colours the band, just at the strength of a stamp rather
		// than a highlighter.
		// The difficulty, set as a heading ON the paper rather than a plate over it.
		//
		// No fill at all. A filled rectangle reads as an applied element however
		// faint it is made — softening the colour only produced a translucent
		// rectangle, still with four hard edges the paper does not have. What a
		// title looks like on a real sheet is letterspaced capitals pressed into
		// the fibre, flanked by rules that fade out before they reach the margin.
		int ribH = 22;
		int ribY = parchTop + 5;
		FontMetrics hfm = metrics(g, FONT_BODY);
		int labelW = spacedWidth(hfm, art.label, HEAD_TRACKING);
		int cxHead = pX + pW / 2;
		int baseHead = ribY + ribH / 2 + hfm.getAscent() / 2 - 2;
		int ruleY = baseHead - hfm.getAscent() / 2 - 1;

		// NOT shared with the right-hand rule below, though the two look like
		// mirror images. Each `> 6` guard is SIGNED, and the sign is what refuses
		// to draw when a long heading on a narrow scroll leaves the type ends
		// crossed past the margins — which at the minimum card width (pW 90, and
		// "REDEMPTION" alone measuring ~110) is the ordinary case, not an edge
		// one. A helper taking the two ends can only test |type - margin|, and
		// would answer that by drawing an inverted rule back through the heading.
		int outerL = pX + BAND_INSET;
		int innerL = cxHead - labelW / 2 - 9;
		if (innerL - outerL > 6) {
			g.setPaint(new GradientPaint(outerL, 0, withAlpha(art.edge, 0f),
				innerL, 0, withAlpha(art.edge, 0.75f)));
			g.fillRect(outerL, ruleY, innerL - outerL, 1);
		}
		// the party mark occupies the end of the heading line, so the right rule
		// stops short of it. It used to run underneath the glyph — a hairline
		// through the middle of the one mark that changes what a click does.
		int marginR = pX + pW - BAND_INSET;
		int glyphX = marginR - PARTY_GLYPH_W;
		int outerR = offer.isPartyRoll() ? glyphX - 7 : marginR;
		int innerR = cxHead + labelW / 2 + 9;
		if (outerR - innerR > 6) {
			g.setPaint(new GradientPaint(innerR, 0, withAlpha(art.edge, 0.75f),
				outerR, 0, withAlpha(art.edge, 0f)));
			g.fillRect(innerR, ruleY, outerR - innerR, 1);
		}

		// debossed: a pale ghost one pixel BELOW the ink is the light catching the
		// far wall of an impression, which is what makes type look stamped in
		drawSpaced(g, art.label, cxHead - labelW / 2, baseHead + 1, HEAD_TRACKING,
			withAlpha(PARCH_EMBOSS, 0.55f));
		drawSpaced(g, art.label, cxHead - labelW / 2, baseHead, HEAD_TRACKING, art.headInk);

		if (offer.isPartyRoll()) {
			// shared party contract: clicking VOTES rather than accepts. Centred on
			// the heading's own band rather than offset from its top, so it sits
			// level with the type instead of riding a couple of pixels high.
			drawPartySilhouette(g, glyphX + 1,
				ribY + (ribH - PARTY_GLYPH_H) / 2, art.edge);
		}

		int fieldX = pX + 8;
		int fieldW = pW - 16;

		// Contract data, dark ink. A running cursor rather than four fixed
		// offsets: a long quarry name ("Fremennik warband berserker") now takes
		// the two lines it needs and pushes the rest down, instead of being cut
		// off mid-word. Nothing on a contract should end in an ellipsis — the one
		// thing a player must be able to read is what they are agreeing to kill.
		int cbY = ribY + ribH + 20;
		cbY = drawWrappedCentre(g, offer.getMonsterName(), cxHead, cbY,
			FONT_NAME, PARCH_INK, fieldW, pBot, 26);
		cbY = drawWrappedCentre(g, art.killsLine, cxHead, cbY,
			FONT_BODY, PARCH_INK_SOFT, fieldW, pBot, 22);
		cbY = drawWrappedCentre(g, art.rewardLine, cxHead, cbY,
			FONT_SMALL, PARCH_REWARD, fieldW, pBot, 20);
		cbY = drawWrappedCentre(g, art.cbLine, cxHead, cbY,
			FONT_SMALL, PARCH_INK_SOFT, fieldW, pBot, 0);

		// --- footer stack ---
		// Every block below takes the TOP of its box and returns the next free
		// top. Three conventions used to meet here — a centre for the ink lines,
		// a baseline for the side-bet list, a top for the voter faces — and since
		// the side-bet rule is drawn ABOVE its own y, the blocks laid out through
		// one another. One convention, and each block owns its own leading.
		int footerY = cbY + 12;

		// Who has backed this contract, by face and name. Shown to members who
		// have NOT voted yet as much as to those who have: the bar is a majority,
		// so the balance is the thing being decided, and hiding it until you
		// commit would make the deciding vote the only blind one. Names rather
		// than a count, because on a board of four the useful question is not how
		// close the vote is but who you would be siding with.
		if (offer.isPartyRoll() && frameVotes != null && i < frameVotes.getVoters().size()) {
			footerY = drawVoters(g, frameVotes.getVoters().get(i), fieldX, footerY,
				fieldW, pBot);
		}

		// The Ante, inked on the contract itself: an armed wager must be legible
		// on the very thing the player is about to click, not only in a panel
		// they may have scrolled away from.
		if (TaskService.anteEligible(offer) && taskService.anteArmed()) {
			int stake = taskService.previewAnteStake();
			if (stake > 0 && footerY + 16 <= pBot - 6) {
				drawInkLine(g, "ANTE ARMED — " + stake + " GC", cxHead,
					footerY + 8, FONT_SMALL, PARCH_ANTE, fieldW);
				footerY += 18;
			}
		}

		if (art.betConds.length > 0) {
			drawSideBets(g, art.betConds, art.betRewards, fieldX, footerY, fieldW, pBot);
		}
	}

	/**
	 * A hairline and a small caption introducing a footer block.
	 *
	 * @return the top of the block's first content row
	 */
	private static int drawFooterHeading(Graphics2D g, String label, int x, int y, int w) {
		FontMetrics fm = metrics(g, FONT_SMALL);
		g.setColor(PARCH_EDGE_SOFT);
		g.drawLine(x, y, x + w, y);
		g.setColor(PARCH_INK_SOFT);
		g.drawString(label, x, y + 3 + fm.getAscent());
		return y + 5 + fm.getHeight();
	}

	/**
	 * The side-bet list, under its own rule. Returns the next free top.
	 *
	 * <p>Condition in bet ink, payout in reward gold. Running them together in
	 * one colour made the number the hardest thing on the line to find, which is
	 * backwards — the condition is what you read once, the payout is what you
	 * compare between bets.
	 */
	private static int drawSideBets(Graphics2D g, String[] conds, String[] rewards,
		int x, int y, int w, int pBot) {
		FontMetrics fm = metrics(g, FONT_SMALL);
		// the line box, the baseline within it, and the last row the sheet will
		// take — asked for thirteen times between them before this
		int lh = fm.getHeight();
		int asc = fm.getAscent();
		int floor = pBot - 6;
		if (y + lh * 2 + 6 > floor) {
			return y; // too short to say anything useful; say nothing
		}
		y = drawFooterHeading(g, "Side bets", x, y, w);
		int space = fm.stringWidth(" ");
		for (int i = 0; i < conds.length; i++) {
			String reward = i < rewards.length ? rewards[i] : null;
			int rewardW = reward == null ? 0 : fm.stringWidth(reward);
			// wrapped, not clipped: a side bet cut to "A kill without taking dam…"
			// has lost the one part that matters, which is the condition
			List<String> parts = wrapText(fm, conds[i], w);
			for (int p = 0; p < parts.size(); p++) {
				if (y + lh > floor)
					return y;
				String part = parts.get(p);
				g.setColor(PARCH_BET);
				g.drawString(part, x, y + asc);
				if (p == parts.size() - 1 && reward != null) {
					int at = fm.stringWidth(part) + space;
					if (at + rewardW <= w) {
						g.setColor(PARCH_REWARD);
						g.drawString(reward, x + at, y + asc);
						reward = null;
					}
				}
				y += lh;
			}
			if (reward != null) {
				// no room on the condition's last line: give the payout its own,
				// still in gold, rather than squeezing it past the margin
				if (y + lh > floor)
					return y;
				g.setColor(PARCH_REWARD);
				g.drawString(reward, x, y + asc);
				y += lh;
			}
		}
		return y;
	}

	/** Party avatars are square on the wire; this is the edge they are drawn at. */
	private static final int FACE = 12;


	/**
	 * The voters backing one contract: avatar then name, one per line.
	 *
	 * <p>Clamped against the bottom of the parchment like every other block here,
	 * so a short scroll drops lines rather than writing over its own border. A
	 * member with no avatar still gets their name — the face is the decoration,
	 * the name is the information.
	 *
	 * @return the next free y, so the caller's footer keeps stacking
	 */
	static int drawVoters(Graphics2D g,
		List<PartyRollService.Voter> voters,
		int x, int y, int maxWidth, int pBot) {
		if (voters == null || voters.isEmpty())
			return y;
		FontMetrics fm = metrics(g, FONT_SMALL);
		int lh = fm.getHeight();
		int rowH = Math.max(FACE, lh) + 3;
		// the roll, the last row the sheet will take, and the baseline offset
		// inside a row: each was being re-derived two and three times below
		int n = voters.size();
		int floor = pBot - 6;
		int half = (rowH + fm.getAscent()) / 2 - 2;
		// heading plus one row, or the block says nothing and takes no space
		if (y + 5 + lh + rowH > floor)
			return y;
		y = drawFooterHeading(g, "Backed by", x, y, maxWidth);

		// A wrapping flow, not a column. A party of five turned a stack of names
		// into most of the scroll and pushed the side bets off the bottom; read
		// as a sentence, the same five fit on two lines and the block stays a
		// footnote rather than becoming the body of the contract.
		int cx = x;
		for (int v = 0; v < n; v++) {
			PartyRollService.Voter voter = voters.get(v);
			boolean last = v == n - 1;
			String name = voter.getName() + (last ? "" : ",");
			int faceW = voter.getAvatar() != null ? FACE + 3 : 0;
			int chipW = faceW + fm.stringWidth(name);

			if (cx > x && cx + chipW > x + maxWidth) {
				// The out-of-room test belongs HERE, before the wrap, not after
				// it. It used to sit below this block and draw the label with
				// the already-reset cx (== x) against the previous row's
				// baseline (y - rowH), which stamped "+N more" straight over the
				// first name on the last drawn row. Nothing else moves y inside
				// this loop and the guard above proves the first row always
				// fits, so the refusal was only ever reachable on the iteration
				// that had just wrapped — i.e. always with a clobbered cx. Same
				// arithmetic, one row earlier: y + rowH * 2 is the bottom of the
				// row the wrap was about to open.
				if (y + rowH * 2 > floor) {
					// no room for another line: say how many went unnamed rather
					// than trailing off, since a truncated list of allies is a
					// misleading one.
					//
					// The label is appended at the pen, so it can run past the
					// right edge of the column — the wrap fired precisely
					// because the remaining width was too narrow for the next
					// chip, and "+N more" is not much shorter than a name.
					// Overhang is the deliberate choice: wrapText already lets
					// an over-long word run wide for the same reason, and the
					// only alternative — clamping back toward the margin — is
					// exactly the overprint this fixed. A count you cannot tell
					// apart from an ally's name is worse than one a few pixels
					// wide.
					g.setColor(PARCH_INK_SOFT);
					g.drawString("+" + (n - v) + " more", cx, y + half);
					return y + rowH + 2;
				}
				cx = x;
				y += rowH;
			}
			if (voter.getAvatar() != null) {
				drawVoterFace(g, voter.getAvatar(), cx, y + (rowH - FACE) / 2);
			}
			g.setFont(FONT_SMALL);
			g.setColor(voter.isSelf() ? PARCH_REWARD : PARCH_INK);
			g.drawString(name, cx + faceW, y + half);
			cx += chipW + VOTER_GAP;
		}
		return y + rowH + 2;
	}

	/** Space between one voter chip and the next on the same line. */
	private static final int VOTER_GAP = 7;

	/** Parchment-toned wash over a party avatar, so a blue UI face belongs on paper. */
	private static final Color FACE_WASH = new Color(122, 84, 44, 96);

	/**
	 * One voter's avatar, toned to the page.
	 *
	 * <p>RuneLite's default avatar is a cool grey-blue UI glyph; dropped raw onto
	 * warm parchment it reads as a screenshot pasted onto a letter. Drawn into a
	 * scratch image, washed with a sepia tint through SRC_ATOP so only the avatar's
	 * own pixels take the colour, then framed with the same hairline the rules use.
	 */
	private static void drawVoterFace(Graphics2D g, BufferedImage src,
		int x, int y) {
		BufferedImage tinted = new BufferedImage(
			FACE, FACE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tinted.createGraphics();
		try {
			tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			tg.drawImage(src, 0, 0, FACE, FACE, null);
			tg.setComposite(AlphaComposite.SrcAtop);
			tg.setColor(FACE_WASH);
			tg.fillRect(0, 0, FACE, FACE);
		}
		finally {
			tg.dispose();
		}
		g.drawImage(tinted, x, y, null);
		g.setColor(PARCH_EDGE_SOFT);
		g.drawRect(x, y, FACE - 1, FACE - 1);
	}

	/**
	 * Break {@code text} onto as many lines as it needs to fit {@code maxWidth}.
	 *
	 * <p>Breaks on spaces. A single word longer than the column is left whole and
	 * allowed to overhang rather than being chopped mid-word — an item name split
	 * across a line boundary is harder to read than one that runs slightly wide,
	 * and this is a contract, not a newspaper column.
	 */
	private static List<String> wrapText(FontMetrics fm, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty())
			return lines;
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() == 0) {
				line.append(word);
				continue;
			}
			if (fm.stringWidth(line + " " + word) <= maxWidth) {
				line.append(' ').append(word);
			}
			else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
	}

	/**
	 * A centred block of ink, wrapped to the column.
	 *
	 * @param y      centre of the FIRST line
	 * @param gapAfter extra px to leave below the block
	 * @return the centre y for the next block
	 */
	private static int drawWrappedCentre(Graphics2D g, String text, int cx, int y, Font font,
		Color color, int maxWidth, int pBot, int gapAfter) {
		FontMetrics fm = metrics(g, font);
		List<String> lines = wrapText(fm, text, maxWidth);
		for (String line : lines) {
			if (y > pBot - 8)
				return y;
			g.setColor(color);
			g.drawString(line, cx - fm.stringWidth(line) / 2, y + fm.getAscent() / 2 - 2);
			y += fm.getHeight();
		}
		// the gap is claimed whether or not any line fitted: an empty block that
		// silently gave its leading back would shift the whole footer stack up
		return y + gapAfter;
	}

	/** Width of {@code text} once {@code tracking} px are added between letters. */
	private static int spacedWidth(FontMetrics fm, String text, int tracking) {
		if (text.isEmpty())
			return 0;
		return fm.stringWidth(text) + tracking * (text.length() - 1);
	}

	/** Draw {@code text} letter by letter with extra tracking, from a left baseline. */
	private static void drawSpaced(Graphics2D g, String text, int x, int baseline,
		int tracking, Color color) {
		FontMetrics fm = g.getFontMetrics();
		g.setColor(color);
		int cx = x;
		for (int i = 0; i < text.length(); i++) {
			String ch = text.substring(i, i + 1);
			g.drawString(ch, cx, baseline);
			cx += fm.stringWidth(ch) + tracking;
		}
	}

	/** A single centered, clipped ink line (no drop shadow on parchment). */
	private static void drawInkLine(Graphics2D g, String text, int cx, int cy, Font font,
		Color color, int maxWidth) {
		FontMetrics fm = metrics(g, font);
		String clipped = clipText(fm, text, maxWidth);
		g.setColor(color);
		g.drawString(clipped, cx - fm.stringWidth(clipped) / 2, cy + fm.getAscent() / 2 - 2);
	}

	/**
	 * Per-offer scroll styling and text, precomputed at ceremony start so
	 * the per-frame drawing performs no color mixing or string building.
	 */
	private static final class OfferScrollArt {
		/** headInk is the tier-tinted ink for the difficulty heading; edge is the
		 *  muted rule/outline colour every hairline on this scroll is drawn in. */
		final Color tier, parchTop, parchBottom, headInk, edge;
		final String label, killsLine, rewardLine, cbLine;
		/**
		 * Side bets kept as two parallel arrays rather than one joined string.
		 *
		 * <p>The payout is drawn in a different ink from the condition, and the
		 * only robust way to know where one ends and the other begins is to never
		 * have merged them: splitting on the last " +" works until a condition
		 * describes something with a plus in it.
		 */
		final String[] betConds, betRewards;

		OfferScrollArt(TaskOffer offer) {
			tier = offer.isRedemption() ? REDEMPTION_RED : offer.getDifficulty().getColor();
			// A HINT of tier in the paper, not a wash of it. The comment here used
			// to claim "strongly desaturated" while mixing a quarter of a fully
			// saturated UI colour into the sheet, which stopped the parchment
			// reading as paper at all and made the whole scroll look cartoonish.
			// The tier's identity is carried by the banner and the rollers, which
			// are meant to be colourful; the page only needs to agree with them.
			Color muted = desaturate(tier, 0.6f);
			parchTop = mixColor(PARCH_TOP, muted, 0.10f);
			parchBottom = mixColor(PARCH_BOTTOM, muted, 0.10f);
			// The heading is now type, not a plate, so the tier lives in the INK.
			// Dark enough to be ink and not paint: pulled most of the way to the
			// body colour, keeping just enough hue that INSANE reads red-black and
			// EASY reads green-black at a glance.
			headInk = mixColor(PARCH_INK, desaturate(tier, 0.15f), 0.42f);
			// every rule and outline on the page: a warm dark with a memory of the
			// tier in it, never the raw UI colour — a saturated hairline around a
			// sheet of paper is the outline of a sticker
			edge = mixColor(desaturate(tier, 0.5f), PARCH_EDGE_DARK, 0.62f);
			label = offer.isRedemption()
				? "REDEMPTION" : offer.getDifficulty().getDisplayName().toUpperCase();
			killsLine = "Kills: " + offer.getKillsRequired();
			rewardLine = offer.isRedemption()
				? "Clears all taint"
				: offer.getCompletionGc() + " GC  (+" + offer.getPerKillGc() + "/kill)";
			cbLine = "cb " + offer.getMonsterCombatLevel();
			List<SideBet> bets = offer.getSideBets();
			int n = bets == null ? 0 : bets.size();
			betConds = new String[n];
			betRewards = new String[n];
			for (int b = 0; b < n; b++) {
				SideBet bet = bets.get(b);
				betConds[b] = TaskService.describeSideBet(bet);
				betRewards[b] = "+" + bet.getPayoutGc();
			}
		}
	}

	/**
	 * The two-figure mark that says this contract is voted on, not accepted.
	 *
	 * <p>Inked in the scroll's own rule colour rather than the cool grey-blue it
	 * used to be: a UI-blue glyph on warm parchment reads as a foreign widget
	 * pasted over the page, and against the band it was the least legible thing
	 * on the scroll despite being the one mark that changes what a click does.
	 */
	private void drawPartySilhouette(Graphics2D g, int x, int y, Color ink) {
		g.setColor(ink);
		g.fillOval(x, y, 7, 7);
		g.fillRoundRect(x - 1, y + 7, 9, 8, 4, 4);
		g.setColor(withAlpha(ink, 0.55f));
		g.fillOval(x + 8, y + 2, 6, 6);
		g.fillRoundRect(x + 7, y + 8, 8, 7, 4, 4);
	}

	/**
	 * ACCEPTED stamp: slams in from above (scale 2.4 -> 1, accelerating) while
	 * rotating to rest, then a thud scale-punch on impact.
	 */
	private void drawAcceptedStamp(Graphics2D g, Rectangle r, long t) {
		final long slamMs = 180;
		final long punchMs = 170;
		double scale;
		double rot;
		float a;
		if (t < slamMs) {
			double u = easeInCubic(t / (double) slamMs);
			scale = 2.4 - 1.4 * u;
			rot = Math.toRadians(-40 + 24 * u);
			a = (float) (0.25 + 0.75 * u);
		}
		else if (t < slamMs + punchMs) {
			double v = (t - slamMs) / (double) punchMs;
			scale = 1.0 - 0.07 * Math.sin(Math.PI * v);
			rot = Math.toRadians(-16);
			a = 1f;
		}
		else {
			scale = 1.0;
			rot = Math.toRadians(-16);
			a = 1f;
		}

		double cx = r.x + r.width / 2.0;
		double cy = r.y + r.height / 2.0;
		Graphics2D g2 = copy(g);
		g2.rotate(rot, cx, cy);
		g2.translate(cx, cy);
		g2.scale(scale, scale);
		g2.translate(-cx, -cy);
		FontMetrics fm = metrics(g2, FONT_TITLE);
		String text = "ACCEPTED";
		// The stamp's three rectangles were written out longhand three times over,
		// each re-deriving the same four metrics. They are one box: the outer
		// border filled and stroked, and the inner hairline inset 4px on every
		// side — which is what (tx-6, ty-asc-2, tw+12, th+4) always was.
		int tw = fm.stringWidth(text);
		int asc = fm.getAscent();
		int tx = (int) cx - tw / 2;
		int ty = (int) cy + asc / 2;
		int bx = tx - 10;
		int by = ty - asc - 6;
		int bw = tw + 20;
		int bh = fm.getHeight() + 12;
		Color inkGreen = new Color(24, 104, 34);
		g2.setColor(withAlpha(inkGreen, a * 0.28f));
		g2.fillRoundRect(bx, by, bw, bh, 8, 8);
		g2.setColor(withAlpha(inkGreen, a));
		g2.setStroke(new BasicStroke(3f));
		g2.drawRoundRect(bx, by, bw, bh, 8, 8);
		g2.setStroke(new BasicStroke(1.2f));
		g2.drawRoundRect(bx + 4, by + 4, bw - 8, bh - 8, 6, 6);
		g2.drawString(text, tx, ty);
		g2.dispose();

		// impact dust ring on the thud
		if (t >= slamMs && t < slamMs + 260) {
			float v = (t - slamMs) / 260f;
			int rr = (int) (r.width * (0.30 + 0.35 * easeOutCubic(v)));
			g.setColor(withAlpha(new Color(60, 50, 30), (1 - v) * 0.4f));
			g.setStroke(new BasicStroke(2f + (1 - v) * 3f));
			g.drawOval((int) cx - rr, (int) cy - rr / 2, rr * 2, rr);
		}
	}

	/**
	 * Losing contracts burn away with an upward ember dissolve: the burn front
	 * climbs the scroll, embers rise off it, ash darkens the edge.
	 */
	private void drawBurningOffer(Graphics2D g, int i, Rectangle r, long now, float burnT) {
		double front = easeInCubic(burnT * 1.06);
		int visibleH = (int) (r.height * (1.0 - front));
		if (visibleH <= 2)
			return;
		int frontY = r.y + visibleH;

		Graphics2D g2 = copy(g);
		g2.setClip(r.x - 8, r.y - 10, r.width + 16, visibleH + 10);
		drawOfferScroll(g2, i, r, 1.0);
		// charring just above the burn front
		g2.setPaint(new GradientPaint(r.x, frontY - 18, new Color(30, 16, 8, 0),
			r.x, frontY, new Color(30, 16, 8, 210)));
		g2.fillRect(r.x, frontY - 18, r.width, 18);
		g2.dispose();

		// glowing ragged burn edge
		for (int k = 0; k < r.width; k += 5) {
			float hk = hash01(i * 811 + k * 7);
			int tick = 2 + (int) (hk * 5);
			g.setColor(withAlpha(hk > 0.5f ? EMBER_HOT : EMBER_RED, 0.85f));
			g.fillRect(r.x + k, frontY - tick / 2, 4, tick);
		}

		// embers rising off the front (deterministic, fake buoyancy)
		double ts = (now - phaseAt) / 1000.0;
		for (int p = 0; p < 12; p++) {
			float h1 = hash01(i * 977 + p * 3);
			float h2 = hash01(i * 977 + p * 3 + 1);
			double rise = (40 + h2 * 130) * ts;
			int px = r.x + (int) (h1 * r.width) + (int) (Math.sin(ts * 5 + p) * 6);
			int py = frontY - (int) rise;
			if (py < r.y - 40)
				continue;
			float a = (1 - burnT) * (0.4f + 0.6f * h2);
			g.setColor(withAlpha((p & 1) == 0 ? EMBER_HOT : EMBER_RED, a));
			int sz = 2 + (p % 2);
			g.fillRect(px, py, sz, sz);
		}
	}

	// =====================================================================
	// TASK COMPLETE
	// =====================================================================


	// =====================================================================
	// DEED CHOICE
	// =====================================================================

	private void layoutDeedRects(int cw, int ch) {
		int boxW = Math.min(120, (cw - 120) / 3);
		int boxH = 58;
		int gapX = 16;
		int gapY = 14;
		int totalW = boxW * 3 + gapX * 2;
		int totalH = boxH * 5 + gapY * 4;
		int x0 = (cw - totalW) / 2;
		int y0 = (ch - totalH) / 2 + 16;
		GearSlot[] slots = GearSlot.values();
		for (int i = 0; i < slots.length; i++) {
			int col = DEED_GRID[i][0];
			int row = DEED_GRID[i][1];
			deedRects[i].setBounds(x0 + col * (boxW + gapX), y0 + row * (boxH + gapY), boxW, boxH);
		}
	}

	private boolean isSlotDeeded(GearSlot slot) {
		GachaState state = stateService.get();
		return state != null && state.getDeededSlots().contains(slot.name());
	}

	private void drawDeedChoice(Graphics2D g, int cw, int ch, long now) {
		layoutDeedRects(cw, ch);
		centre(g, "SLOT DEED EARNED", cw / 2, 46, FONT_TITLE, GOLD);
		centre(g, "Choose a locked slot to unlock forever", cw / 2, 70, FONT_BODY,
			Color.WHITE);
		GachaState state = stateService.get();
		int pending = state == null ? 0 : state.getPendingDeeds();
		if (pending > 1) {
			centre(g, "Deeds available: " + pending, cw / 2, 90, FONT_SMALL,
				new Color(200, 200, 200), false);
		}

		GearSlot[] slots = GearSlot.values();
		for (int i = 0; i < slots.length; i++) {
			Rectangle r = deedRects[i];
			boolean deeded = isSlotDeeded(slots[i]);
			boolean chosen = phase == PH_DEED_BURST && slots[i] == chosenDeedSlot;
			boolean hovered = phase == PH_DEED_CHOOSE && !deeded && pointerValid
				&& r.contains(pointerX, pointerY);

			// Every box is the same rounded plate at the same geometry; a slot
			// that is won (or being won right now) differs only in its three
			// colours and a heavier border. `lit` and `hovered` are mutually
			// exclusive by construction — hovered demands PH_DEED_CHOOSE and
			// !deeded — so the border weight is simply "either of them".
			boolean lit = deeded || chosen;
			plate(g, r, lit ? new Color(66, 52, 18, 235)
					: hovered ? new Color(46, 46, 54, 240) : new Color(30, 30, 36, 235),
				lit ? GOLD : hovered ? new Color(200, 200, 210) : new Color(90, 90, 100),
				lit || hovered);
			int mx = r.x + r.width / 2;
			if (lit) {
				// won: the name alone, centred, with nothing left to unlock
				centre(g, slots[i].getDisplayName(), mx, r.y + r.height / 2 + 4,
					FONT_BODY, GOLD);
			}
			else {
				// locked: the padlock takes the middle and the name sits under it
				centre(g, slots[i].getDisplayName(), mx, r.y + r.height - 10, FONT_SMALL,
					hovered ? Color.WHITE : new Color(150, 150, 160), false);
				drawMiniPadlock(g, mx, r.y + r.height / 2 - 8,
					hovered ? new Color(220, 220, 230) : new Color(120, 120, 130));
			}

			if (chosen) {
				drawDeedBurst(g, r, now - phaseAt);
			}
		}
	}

	private void drawMiniPadlock(Graphics2D g, int cx, int cy, Color color) {
		g.setColor(color);
		g.setStroke(new BasicStroke(2f));
		g.drawArc(cx - 5, cy - 9, 10, 10, 0, 180);
		g.fillRoundRect(cx - 7, cy - 3, 14, 11, 3, 3);
	}

	private void drawDeedBurst(Graphics2D g, Rectangle r, long t) {
		float u = (float) clamp(t / (double) DEED_BURST_MS);
		float fade = 1 - u;
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		// rays
		Graphics2D g2 = copy(g);
		g2.setStroke(new BasicStroke(3f));
		for (int k = 0; k < 12; k++) {
			double ang = k * Math.PI / 6 + u * 0.9;
			double len = 20 + smoothstep(u) * 110;
			g2.setColor(withAlpha(GOLD, fade * 0.9f));
			g2.drawLine(cx + (int) (Math.cos(ang) * 12), cy + (int) (Math.sin(ang) * 12),
				cx + (int) (Math.cos(ang) * len), cy + (int) (Math.sin(ang) * len));
		}
		// expanding ring + sparks
		double ringR = smoothstep(u) * 90;
		g2.setColor(withAlpha(Color.WHITE, fade * 0.8f));
		g2.setStroke(new BasicStroke(2f + fade * 8f));
		g2.drawOval(cx - (int) ringR, cy - (int) ringR, (int) ringR * 2, (int) ringR * 2);
		double ts = t / 1000.0;
		for (int p = 0; p < 18; p++) {
			float h1 = hash01(p * 71 + 5);
			float h2 = hash01(p * 71 + 6);
			double ang = h1 * Math.PI * 2;
			double speed = 50 + h2 * 190;
			int px = cx + (int) (Math.cos(ang) * speed * ts);
			int py = cy + (int) (Math.sin(ang) * speed * ts + 260 * ts * ts);
			g2.setColor(withAlpha(GOLD, fade));
			g2.fillRect(px, py, 3, 3);
		}
		g2.dispose();
	}

	// =====================================================================
	// THE CONSIGNMENT (a binding choice)
	// =====================================================================

	/**
	 * The two answers, as plates to aim at.
	 *
	 * <p>Identical geometry on purpose: a binding choice must not have a default,
	 * and the house's side of it is exactly the one that would profit from being
	 * the bigger target.
	 *
	 * <p>Static and package-private because it is pure, and because the invariant
	 * worth pinning off-client is a layout one — two plates that overlapped, or
	 * one that ran off the canvas, would take an answer the player never gave.
	 */
	static void consignRect(int i, int cw, int ch, Rectangle out) {
		int bw = Math.min(230, (cw - 90) / 2);
		int gap = 26;
		int x0 = (cw - (bw * 2 + gap)) / 2;
		// centred on a tall canvas, lifted off the bottom edge on a short one, so
		// the crate above keeps its room either way
		out.setBounds(x0 + i * (bw + gap), Math.min(ch - 116, ch / 2 + 118), bw, 52);
	}

	/**
	 * One answer: a title, and the consequence spelled out under it.
	 *
	 * <p>The second line is not decoration. The two answers differ in what they
	 * cost as much as in what they pay, and "TAKE THE DEAL" against "SPIN THE
	 * WHEEL" asks the player to remember which of them hands their style to the
	 * house — at the one moment they are being asked to agree to it.
	 */
	private static void drawChoicePlate(Graphics2D g, Rectangle r, String title, String detail,
		boolean hovered, Color accent) {
		plate(g, r, hovered ? new Color(46, 42, 30, 245) : new Color(28, 26, 20, 235),
			hovered ? accent : withAlpha(accent, 0.6f), hovered);
		int mx = r.x + r.width / 2;
		centre(g, title, mx, r.y + 19, FONT_BODY, accent);
		centre(g, detail, mx, r.y + 37, FONT_SMALL, CAP_DIM, false);
	}

	/**
	 * The rounded plate every aimable target in this file is built on: a slot in
	 * the deed grid and an answer in the Consignment.
	 *
	 * <p>The two screens were drawing the identical five calls — same 10px
	 * corner, same 2.5/1.6 border weights, same fill-then-stroke order — and
	 * differed only in which colours they chose and what they wrote on top. That
	 * is not two plates, it is one plate asked twice, and a target the player has
	 * to aim at is exactly the thing that must not drift between the screen that
	 * unlocks a slot and the screen that signs away a style roll.
	 *
	 * <p>{@code heavy} is passed rather than derived: the deed grid thickens the
	 * border for a slot that is lit OR hovered, the Consignment only for a hover,
	 * and no expression over the arguments here can tell those apart.
	 */
	private static void plate(Graphics2D g, Rectangle r, Color fill, Color edge,
		boolean heavy) {
		g.setColor(fill);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
		g.setColor(edge);
		g.setStroke(new BasicStroke(heavy ? 2.5f : 1.6f));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
	}

	/**
	 * The offer. No clock, no animation, no phase to skip past — see
	 * {@link #PH_CONSIGN_OFFER}. Everything on it is either the deal or the price
	 * of the deal.
	 */
	private void drawConsignment(Graphics2D g, int cw, int ch) {
		ConsignmentService.Offer offer = consignOffer;
		if (offer == null)
			return;
		Tuning.Chest tier = offer.getChestTier();
		// every line, the crate and both answer plates hang off the same axis
		int mx = cw / 2;
		centre(g, "THE CONSIGNMENT", mx, 46, FONT_TITLE, GOLD);
		centre(g, "A free " + tier.name() + " crate, on the house",
			mx, 72, FONT_BODY, Color.WHITE);
		// What the crate is worth, from the same two tables the shop charges from
		// and the roller deals from — so the offer cannot flatter itself, and a
		// retune of either moves this line with it.
		centre(g, "normally " + Tuning.CHEST_PRICE_GC.get(tier) + " GC - "
			+ Tuning.CHEST_CARDS.get(tier) + " cards", mx, 92, FONT_SMALL, CAP_INK, false);

		// the button row owns the vertical anchor; everything above stacks off it
		consignRect(0, cw, ch, rect);
		int btnY = rect.y;
		int headY = btnY - 52;

		int crateW = Math.min(220, cw / 3);
		int crateH = crateW * 3 / 4;
		int crateCy = headY - 30 - crateH / 2;
		// the crate, closed, in its own tier's ceremony art. Skipped rather than
		// squeezed when the canvas cannot hold it under the heading: the words are
		// the offer, the picture is only the box it comes in.
		if (crateCy - crateH / 2 > 104) {
			ceremonyPlayer.draw(g, mx, crateCy, crateW, crateH, tier, 0, 1f);
		}

		AttackStyle named = offer.getStyle();
		centre(g, "Its price is your next style roll", mx, headY - 24,
			FONT_SMALL, CAP_DIM);
		centre(g, "THE HOUSE NAMES " + named.getDisplayName().toUpperCase(),
			mx, headY, FONT_TITLE, named.getColor());
		// why THIS style, in the terms the house picked it by. A deal that names a
		// style without saying how it chose reads as rigged; saying it out loud
		// makes it a rule the player can see coming next time.
		int owned = offer.getOwnedWeaponCards();
		centre(g, owned == 0
			? "the one style your album holds no weapon cards for"
			: "the style your album is worst dressed for - " + owned + " weapon card"
				+ (owned == 1 ? "" : "s"),
			mx, btnY - 26, FONT_SMALL, CAP_INK);

		for (int i = 0; i < 2; i++) {
			consignRect(i, cw, ch, rect);
			drawChoicePlate(g, rect, i == 0 ? "TAKE THE DEAL" : "SPIN THE WHEEL",
				i == 0 ? "the crate, and the house's style" : "the wheel decides, as always",
				pointerValid && rect.contains(pointerX, pointerY),
				i == 0 ? GOLD : new Color(200, 200, 210));
		}
		// Said before the click, not after it. Refusing costs the day too, and a
		// player who learned that by pressing Escape and being asked no more today
		// would be right to call it a trick.
		centre(g, "Either answer takes the roll and spends today's offer.",
			mx, ch - 48, FONT_SMALL, CAP_DIM);
		centre(g, "Click an answer - Esc refuses the deal", mx, ch - 30,
			FONT_BODY, HINT);
	}

	// =====================================================================
	// FANFARE (non-modal)
	// =====================================================================

	private static long fanfareDurationMs(CeremonyBus.Fanfare.Size size) {
		switch (size) {
			case SMALL:
				return 2000;
			case MEDIUM:
				return 3200;
			default:
				return 4400;
		}
	}

	private void drawFanfare(Graphics2D g, int cw, int ch, long now, long el) {
		CeremonyBus.Fanfare fan = fanfare;
		CeremonyBus.Fanfare.Size size = fan.getSize();
		long total = fanfareDurationMs(size);
		// The banner's own copy, read once. Both lines were being fetched and
		// null-tested twice over — once to measure the ribbon, once to ink it —
		// and "has a detail line" was spelled out at both ends of the method.
		String title = fan.getTitle() == null ? "" : fan.getTitle();
		String detail = fan.getDetail();
		boolean detailed = detail != null && !detail.isEmpty();

		if (size == CeremonyBus.Fanfare.Size.MEDIUM) {
			drawConfetti(g, cw, ch, el);
		}
		else if (size == CeremonyBus.Fanfare.Size.LARGE) {
			drawFireworks(g, cw, ch, el);
		}

		// ribbon banner slides in at the top
		double in = smoothstep(clamp(el / 250.0));
		double out = smoothstep(clamp((total - el) / 250.0));
		double slide = Math.min(in, out);
		int bannerH = detailed ? 56 : 40;
		int y = (int) lerp(-bannerH - 8, 14, slide);

		FontMetrics fmT = metrics(g, FONT_BODY);
		int titleW = fmT.stringWidth(title);
		g.setFont(FONT_SMALL);
		int detailW = detail == null ? 0 : g.getFontMetrics().stringWidth(detail);
		BufferedImage icon = fan.getIconItemId() != null
			? cardImageService.itemImage(fan.getIconItemId(), null) : null;
		int iconW = icon != null ? 40 : 0;
		int w = Math.max(titleW, detailW) + 40 + iconW;
		int x = (cw - w) / 2;

		g.setPaint(new GradientPaint(x, y, new Color(40, 32, 14, 235),
			x, y + bannerH, new Color(24, 18, 8, 235)));
		g.fillRoundRect(x, y, w, bannerH, 12, 12);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(x, y, w, bannerH, 12, 12);
		// ribbon tails
		g.setColor(new Color(120, 90, 20, 220));
		g.fillRect(x - 12, y + 8, 12, bannerH - 16);
		g.fillRect(x + w, y + 8, 12, bannerH - 16);

		int textX = x + 20 + iconW;
		if (icon != null) {
			g.drawImage(icon, x + 10, y + (bannerH - 32) / 2, 32, 32, null);
		}
		g.setFont(FONT_BODY);
		g.setColor(GOLD);
		g.drawString(title, textX, y + 22);
		if (detailed) {
			g.setFont(FONT_SMALL);
			g.setColor(Color.WHITE);
			g.drawString(detail, textX, y + 40);
		}
	}

	private void drawConfetti(Graphics2D g, int cw, int ch, long el) {
		AffineTransform saved = g.getTransform();
		double ts = el / 1000.0;
		for (int p = 0; p < 36; p++) {
			float h1 = hash01(p * 91 + 1);
			float h2 = hash01(p * 91 + 2);
			float h3 = hash01(p * 91 + 3);
			int x = (int) (h1 * cw);
			double speed = 110 + h2 * 190;
			int y = (int) ((h3 * ch + speed * ts) % (ch + 40)) - 20;
			double rot = ts * (2 + h2 * 5) + h1 * 6;
			g.setColor(Color.getHSBColor(h1, 0.75f, 1f));
			g.rotate(rot, x, y);
			g.fillRect(x - 2, y - 5, 4, 10);
			g.setTransform(saved);
		}
	}

	private void drawFireworks(Graphics2D g, int cw, int ch, long el) {
		long[] launches = {250, 1050, 1850};
		double[] xFracs = {0.2, 0.8, 0.5};
		for (int m = 0; m < launches.length; m++) {
			long t = el - launches[m];
			if (t < 0)
				continue;
			int lx = (int) (cw * xFracs[m]);
			int burstY = (int) (ch * (0.30 + 0.06 * m));
			long riseMs = 480;
			if (t < riseMs) {
				// mortar streak
				double u = smoothstep(t / (double) riseMs);
				int y = (int) lerp(ch, burstY, u);
				g.setColor(withAlpha(Color.WHITE, 0.9f));
				g.fillRect(lx - 1, y, 3, 10);
				g.setColor(withAlpha(GOLD, 0.5f));
				g.fillRect(lx - 1, y + 10, 3, 18);
				continue;
			}
			long bt = t - riseMs;
			if (bt > 1000)
				continue;
			float u = bt / 1000f;
			double ts = bt / 1000.0;
			for (int p = 0; p < 26; p++) {
				float h1 = hash01(m * 811 + p * 3);
				float h2 = hash01(m * 811 + p * 3 + 1);
				double ang = h1 * Math.PI * 2;
				double speed = 60 + h2 * 220;
				int px = lx + (int) (Math.cos(ang) * speed * ts);
				int py = burstY + (int) (Math.sin(ang) * speed * ts + 190 * ts * ts);
				g.setColor(withAlpha((p % 3 == 0) ? Color.WHITE : GOLD, 1 - u));
				g.fillRect(px, py, 3, 3);
			}
		}
	}

	// =====================================================================
	// utilities
	// =====================================================================

	/**
	 * A throwaway Graphics2D to transform, clip or restyle without disturbing
	 * the caller's.
	 *
	 * <p>Thirteen ceremonies take one, and {@code (Graphics2D) g.create()} was
	 * spelled out at every one of them — the cast included, because
	 * {@link Graphics#create()} is declared to return the superclass. One name
	 * for it says the same thing and carries the cast once.
	 */
	/**
	 * Set the font and hand back its metrics.
	 *
	 * <p>Nothing in this file ever wants one without the other — every block of
	 * text here is measured before it is placed — and the two lines were written
	 * out together eleven times over. One name for the pair also makes it
	 * impossible to measure against a font other than the one about to be drawn
	 * with, which is the bug the split version invites.
	 */
	private static FontMetrics metrics(Graphics2D g, Font f) {
		g.setFont(f);
		return g.getFontMetrics();
	}

	private static Graphics2D copy(Graphics2D g) {
		return (Graphics2D) g.create();
	}

	private static double clamp(double v) {
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static double smoothstep(double t) {
		t = clamp(t);
		return t * t * (3 - 2 * t);
	}

	private static double easeOutCubic(double t) {
		t = clamp(t);
		double u = 1 - t;
		return 1 - u * u * u;
	}

	/**
	 * Ease-out with a small overshoot, for the unroll.
	 *
	 * <p>A cubic ease glides to a halt, which is how a menu slides, not how a
	 * sheet of paper behaves: it pays out, overruns, and is pulled taut. The
	 * overshoot is deliberately gentle — 1.1 rather than the usual 1.70158 —
	 * because the rollers travel only a few dozen pixels and a full back-ease at
	 * that distance reads as a bounce rather than tension.
	 */
	private static double easeOutBack(double t) {
		t = clamp(t);
		double u = t - 1;
		final double c = 1.1;
		return 1 + (c + 1) * u * u * u + c * u * u;
	}

	private static double easeInCubic(double t) {
		t = clamp(t);
		return t * t * t;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}



	private static Color mixColor(Color a, Color b, float t) {
		float u = Math.max(0f, Math.min(1f, t));
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * u),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * u),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * u));
	}

	/**
	 * Pull a colour toward its own brightness, keeping the hue but dropping the
	 * chroma. Used before tinting paper: mixing a UI-saturated colour into a
	 * parchment base carries the saturation across with it, and a little of a
	 * very saturated colour still looks like paint rather than staining.
	 *
	 * @param amount 0 = untouched, 1 = fully grey
	 */
	private static Color desaturate(Color c, float amount) {
		int lum = (int) Math.round(0.299 * c.getRed() + 0.587 * c.getGreen()
			+ 0.114 * c.getBlue());
		return mixColor(c, new Color(lum, lum, lum), amount);
	}

	private static String capitalize(String s) {
		if (s == null || s.isEmpty())
			return "";
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String clipText(FontMetrics fm, String text, int maxWidth) {
		if (fm.stringWidth(text) <= maxWidth)
			return text;
		String drawn = text;
		while (fm.stringWidth(drawn) > maxWidth && drawn.length() > 3) {
			drawn = drawn.substring(0, drawn.length() - 2);
		}
		return drawn + "...";
	}

	/**
	 * One line of text centred on (cx, cy), with the drop shadow every ceremony
	 * headline carries.
	 *
	 * <p>Shadowed is the overwhelming majority — twenty-six of the thirty-three
	 * call sites — so it is the overload rather than a flag repeated at each of
	 * them. The seven that pass {@code false} are the quiet secondary lines that
	 * sit on their own dark plate and gain nothing from a shadow.
	 */
	private static void centre(Graphics2D g, String text, int cx, int cy, Font font,
		Color color) {
		centre(g, text, cx, cy, font, color, true);
	}

	private static void centre(Graphics2D g, String text, int cx, int cy, Font font,
		Color color, boolean shadow) {
		FontMetrics fm = metrics(g, font);
		int x = cx - fm.stringWidth(text) / 2;
		int y = cy + fm.getAscent() / 2 - 2;
		if (shadow) {
			g.setColor(new Color(0, 0, 0, 200));
			g.drawString(text, x + 2, y + 2);
		}
		g.setColor(color);
		g.drawString(text, x, y);
	}
}
