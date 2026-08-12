package com.gachaman.overlay;

import com.gachaman.Tuning;
import com.gachaman.data.CardDatabase;
import com.gachaman.data.CardDefinition;
import com.gachaman.data.HologramDefinition;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.model.GearSlot;
import com.gachaman.model.Rarity;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskOffer;
import com.gachaman.model.Variant;
import com.gachaman.party.PartyRollService;
import com.gachaman.service.CeremonyBus;
import com.gachaman.service.ChestService;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.ServiceRecordService;
import com.gachaman.service.StyleService;
import com.gachaman.service.TaskService;
import com.gachaman.ui.CardImageService;
import com.gachaman.ui.CardRenderer;
import com.gachaman.ui.Paint;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.io.InputStream;
import java.util.HashMap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The ceremony renderer: presents one {@link CeremonyBus.Request} at a time on
 * a full-canvas modal overlay driven by a wall-clock phase machine advanced
 * inside {@code render()} (no timer threads). Fanfares are non-modal and draw
 * as a top strip while gameplay continues.
 */
@Slf4j
@Singleton
public class RevealOverlay extends Overlay implements CeremonyBus.Renderer {
	// --- deferred side effects (executed OUTSIDE the state lock; see notes) ---
	private static final int ACT_NONE = 0;
	private static final int ACT_DRAIN = 1;
	private static final int ACT_COMMIT_DRAIN = 2;
	private static final int ACT_ABORT_COMMIT = 3;
	private static final int ACT_ACCEPT_DRAIN = 4;

	// --- chest phases ---
	private static final int PH_CHEST_INTRO = 0;
	private static final int PH_CHEST_UPGRADE = 1;
	private static final int PH_CHEST_DEAL = 2;
	private static final int PH_CHEST_REVEAL = 3;
	private static final int PH_CHEST_WAIT = 4;
	// --- style roll phases ---
	private static final int PH_SPIN = 0;
	private static final int PH_SPIN_RESULT = 1;
	// --- offer phases ---
	private static final int PH_OFFERS_UNROLL = 0;
	private static final int PH_OFFERS_SETTLED = 1;
	private static final int PH_OFFERS_ACCEPTED = 2;
	// --- deed phases ---
	private static final int PH_DEED_CHOOSE = 0;
	private static final int PH_DEED_BURST = 1;

	private static final long UPGRADE_MS = 1700;
	private static final long DEAL_STAGGER_MS = 160;
	private static final long DEAL_FLIGHT_MS = 520;
	private static final long DEAL_CHEST_DROP_MS = 300;
	private static final long DEAL_SETTLE_MS = 200;
	private static final long FLIP_MS = 220;
	private static final long STARDUST_FIZZLE_MS = 900;
	private static final Color STARDUST = new Color(190, 170, 255);
	private static final long MASS_FLIP_STAGGER_MS = 60;
	/** Advance presses within this window of entering the reveal are ignored
	 *  so skip-spam from the intro can never mass-flip the cards face-up. */
	private static final long REVEAL_GRACE_MS = 350;
	private static final long REROLL_FLIPBACK_MS = 300;
	private static final long REROLL_TOTAL_MS = 950;
	private static final long SHOCKWAVE_MS = 1600;
	private static final long PITY_GLOW_MS = 2600;
	private static final long SPIN_MS = 4500;
	/**
	 * The very first roulette an account ever sees runs long. It is the moment
	 * the whole gamemode is decided and it happens exactly once, so it gets to
	 * breathe; every roll after it is a re-roll and would only be padding.
	 */
	private static final long FIRST_SPIN_MS = 7500;
	private static final long OFFER_UNROLL_MS = 450;
	private static final long OFFER_UNROLL_STAGGER_MS = 120;
	private static final long OFFER_BURN_MS = 900;
	private static final long DEED_BURST_MS = 1150;
	private static final float HOVER_CHARGE_SEC = 0.8f;

	/**
	 * Base fireOnce bit for the strain groans, one per beat. Bits 0-4 and 9 are
	 * already spoken for by the other intro cues, so the strain is parked well
	 * clear of them; four beats occupy 16-19 and firedSounds is an int.
	 */
	private static final int SOUND_BIT_STRAIN = 16;

	/**
	 * How long after the last painted frame the modal still counts as on screen.
	 * Generous enough to survive an unfocused client throttled to ~1 fps.
	 */
	private static final long MODAL_PAINT_STALE_MS = 1500;

	/** Unpainted for this long and the ceremony is abandoned outright. */
	private static final long MODAL_ABANDON_MS = 30_000;

	private static final Font FONT_HUGE = new Font(Font.SANS_SERIF, Font.BOLD, 30);
	private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 22);
	private static final Font FONT_NAME = new Font(Font.SERIF, Font.BOLD, 17);
	private static final Font FONT_BODY = new Font(Font.SANS_SERIF, Font.BOLD, 14);
	private static final Font FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	private static final Color DIM = new Color(0, 0, 0, 140); // 55% of 255
	private static final Color GOLD = new Color(230, 190, 80);
	private static final Color PANEL_BG = new Color(24, 20, 14, 235);
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
	private static final int PARTY_GLYPH_W = 17;
	private static final int PARTY_GLYPH_H = 15;
	private static final Color PARCH_TOP = new Color(236, 222, 186);
	private static final Color PARCH_BOTTOM = new Color(213, 192, 151);
	private static final Color PARCH_EDGE = new Color(146, 120, 80);
	private static final Color PARCH_INK = new Color(58, 44, 26);
	private static final Color PARCH_INK_SOFT = new Color(104, 86, 58);
	private static final Color PARCH_REWARD = new Color(128, 94, 20);
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
	private static final Color BANNER_UNDERLINE = new Color(0, 0, 0, 77);
	private static final Color EMBER_HOT = new Color(255, 176, 60);
	private static final Color EMBER_RED = new Color(220, 80, 30);
	private static final Color RIM_SILVER_HI = new Color(214, 218, 228);
	private static final Color RIM_SILVER_LO = new Color(96, 100, 112);

	/** {col,row} in the equipment-panel arrangement, indexed by GearSlot.ordinal(). */
	private static final int[][] DEED_GRID = {
		{1, 0}, // HEAD
		{0, 1}, // CAPE
		{1, 1}, // AMULET
		{0, 2}, // WEAPON
		{1, 2}, // BODY
		{2, 2}, // SHIELD
		{1, 3}, // LEGS
		{0, 4}, // HANDS
		{1, 4}, // FEET
		{2, 4}, // RING
		{2, 1}, // AMMO
	};

	private final Client client;
	private final CeremonyBus ceremonyBus;
	private final ChestService chestService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final CardDatabase cardDatabase;
	private final CardImageService cardImageService;
	private final ChatMessageManager chatMessageManager;
	private final CeremonyPlayer ceremonyPlayer;

	/**
	 * Guards all ceremony state. LOCK ORDER: CeremonyBus (and other services)
	 * are always acquired BEFORE this lock, never while holding it — so all
	 * bus/service side effects are collected under the lock and executed after
	 * release (the ACT_* constants).
	 */
	private final Object lock = new Object();

	private CeremonyBus.Type activeType;
	private int phase;
	private long phaseStartMs;
	private long ceremonyStartMs;
	private int firedSounds;
	/** Timestamp of the last frame in which the modal was actually drawn; 0 = never. */
	private long lastModalPaintMs;

	// pointer (canvas space, fed by RevealInputListener)
	private volatile int pointerX = -1;
	private volatile int pointerY = -1;
	private volatile boolean pointerValid;

	// chest state
	private ChestService.ChestOpenResult chestResult;
	private boolean chestThemed;
	private List<ChestService.RolledSlot> chestSlots;
	private long[] flipAtMs = new long[0];
	private boolean[] flipFxFired = new boolean[0];
	private long[] rerollAtMs = new long[0];
	private float[] hoverCharge = new float[0];
	private CardRenderer.CardView[] cardViews = new CardRenderer.CardView[0];
	/**
	 * Service Records frozen at chest-open time, card id -> best owned copy.
	 * Snapshotted the way snapshotCanReroll() is, so cardViewFor() never reaches
	 * into a service from inside the render lock, and so a rerolled slot reads
	 * the same records the rest of the ceremony did.
	 */
	private Map<Integer, Integer> serviceSnapshot = Collections.emptyMap();
	private long lastHoverMs;
	private long pityFlipMs;
	private long shockwaveStartMs;
	private int shockwaveSeed;
	private int shockCx;
	private int shockCy;
	private Color shockwaveColor = Color.WHITE;
	private int dealSoundIndex = -1;

	// style roll
	private StyleService.StyleRollResult styleResult;
	private double wheelThetaEnd;
	private long wheelTickCount;

	// task offers
	private List<TaskOffer> offers;
	private OfferScrollArt[] offerArt = new OfferScrollArt[0];
	private int acceptedIndex = -1;
	private int offerWhooshCount;

	// task complete

	// deed choice
	private GearSlot chosenDeedSlot;

	// fanfare (non-modal, independent of the modal slot)
	private CeremonyBus.Fanfare fanfare;
	private long fanfareStartMs;
	private int fanfareSounds;

	// preallocated scratch (zero allocation on the hot paths)
	private final Rectangle rectScratch = new Rectangle();
	private final Rectangle rectScratch2 = new Rectangle();
	private final Rectangle[] deedRects = new Rectangle[GearSlot.values().length];
	private final boolean[] canRerollScratch = new boolean[8];

	@Inject
	public RevealOverlay(Client client, CeremonyBus ceremonyBus, ChestService chestService,
		TaskService taskService, GachaStateService stateService, CardDatabase cardDatabase,
		CardImageService cardImageService, ChatMessageManager chatMessageManager,
		CeremonyPlayer ceremonyPlayer) {
		this.client = client;
		this.ceremonyPlayer = ceremonyPlayer;
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
		if (request == null) {
			return false;
		}
		// only claim while actually in game: a ceremony claimed at the login
		// screen never renders (ABOVE_WIDGETS) yet its modal input listener
		// would eat every login-screen click. Declined requests stay parked in
		// the CeremonyBus queue and re-present after login.
		if (client.getGameState() != GameState.LOGGED_IN) {
			return false;
		}
		long now = System.currentTimeMillis();
		synchronized (lock) {
			if (request.getType() == CeremonyBus.Type.FANFARE) {
				if (fanfare != null || activeType != null
					|| !(request.getPayload() instanceof CeremonyBus.Fanfare)) {
					return false;
				}
				fanfare = (CeremonyBus.Fanfare) request.getPayload();
				fanfareStartMs = 0; // clock starts on the first frame actually painted
				fanfareSounds = 0;
				return true;
			}
			if (activeType != null) {
				return false;
			}
			switch (request.getType()) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (!(request.getPayload() instanceof ChestService.ChestOpenResult)) {
						return false;
					}
					beginChest((ChestService.ChestOpenResult) request.getPayload(),
						request.getType() == CeremonyBus.Type.THEMED_CHEST, now);
					activeType = request.getType();
					return true;
				case STYLE_ROLL:
					if (!(request.getPayload() instanceof StyleService.StyleRollResult)) {
						return false;
					}
					beginStyleRoll((StyleService.StyleRollResult) request.getPayload(), now);
					activeType = CeremonyBus.Type.STYLE_ROLL;
					return true;
				case TASK_OFFERS:
					if (!(request.getPayload() instanceof List) || ((List<?>) request.getPayload()).isEmpty()) {
						return false;
					}
					beginOffers(castOffers((List<?>) request.getPayload()), now);
					activeType = CeremonyBus.Type.TASK_OFFERS;
					return true;
				case TASK_COMPLETE: {
					// Presented as the generic fanfare banner rather than a screen of
					// its own. Declined while a banner is already up — the request
					// stays queued and the overlay drains it the moment that banner
					// clears, which is the same contract the FANFARE case above
					// honours. Overwriting `fanfare` instead would silently eat
					// whichever celebration was mid-flight.
					if (fanfare != null
						|| !(request.getPayload() instanceof TaskService.TaskCompletionSummary)) {
						return false;
					}
					TaskService.TaskCompletionSummary done =
						(TaskService.TaskCompletionSummary) request.getPayload();
					fanfare = new CeremonyBus.Fanfare(CeremonyBus.Fanfare.Size.MEDIUM,
						"Contract complete",
						done.getCompletionGcAwarded() + " GC", null);
					fanfareStartMs = 0;
					fanfareSounds = 0;
					return true;
				}
				case DEED_CHOICE:
					// payload is the milestone number (or 0); the value is not needed
					phase = PH_DEED_CHOOSE;
					phaseStartMs = now;
					ceremonyStartMs = now;
					firedSounds = 0;
					chosenDeedSlot = null;
					activeType = CeremonyBus.Type.DEED_CHOICE;
					return true;
				default:
					return false;
			}
		}
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
		chestResult = result;
		chestThemed = themed;
		chestSlots = new ArrayList<>(result.getSlots());
		int n = chestSlots.size();
		flipAtMs = new long[n];
		flipFxFired = new boolean[n];
		rerollAtMs = new long[n];
		hoverCharge = new float[n];
		cardViews = new CardRenderer.CardView[n];
		// taken BEFORE the cards are committed, so a card the player has never
		// held reads 0 and shows neither a service count nor wear — the reveal
		// can only ever report history that already existed
		GachaState wearState = stateService.get();
		serviceSnapshot = ServiceRecordService.bestByCardId(
			wearState == null ? null : wearState.getOwnedCards());
		phase = PH_CHEST_INTRO;
		phaseStartMs = now;
		ceremonyStartMs = now;
		firedSounds = 0;
		dealSoundIndex = -1;
		pityFlipMs = 0;
		shockwaveStartMs = 0;
		lastHoverMs = now;
	}

	private void beginStyleRoll(StyleService.StyleRollResult result, long now) {
		styleResult = result;
		phase = PH_SPIN;
		phaseStartMs = now;
		ceremonyStartMs = now;
		firedSounds = 0;
		wheelTickCount = 0;
		int idx = result.getRolled().ordinal();
		double jitter = (Paint.hash01((int) now * 31 + idx) - 0.5f) * 80.0;
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
		phaseStartMs = now;
		ceremonyStartMs = now;
		firedSounds = 0;
		acceptedIndex = -1;
		offerWhooshCount = 0;
	}

	// --- input surface (called by RevealInputListener / SafeModeService) ---

	public boolean isModalActive() {
		synchronized (lock) {
			return activeType != null;
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
			return activeType != null
				&& lastModalPaintMs != 0
				&& System.currentTimeMillis() - lastModalPaintMs < MODAL_PAINT_STALE_MS;
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
			long last = lastModalPaintMs != 0 ? lastModalPaintMs : ceremonyStartMs;
			stale = activeType != null && System.currentTimeMillis() - last > MODAL_ABANDON_MS;
			if (stale) {
				log.debug("Gachaman: abandoning unpainted {} ceremony", activeType);
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
		if (p == null) {
			return;
		}
		long now = System.currentTimeMillis();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		snapshotCanReroll();

		int action = ACT_NONE;
		int actionArg = -1;
		int rerollIndex = -1;
		GearSlot deedClicked = null;

		synchronized (lock) {
			if (activeType == null) {
				return;
			}
			switch (activeType) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (phase == PH_CHEST_WAIT) {
						action = finishModalLocked() ? ACT_COMMIT_DRAIN : ACT_NONE;
					}
					else if (phase == PH_CHEST_REVEAL) {
						int n = chestSlots.size();
						for (int i = 0; i < n; i++) {
							slotRect(i, n, cw, ch, rectScratch);
							if (!rectScratch.contains(p.x, p.y)) {
								continue;
							}
							if (flipAtMs[i] == 0 && rerollAtMs[i] == 0) {
								flipAtMs[i] = now;
							}
							else if (isFaceUpSteady(i, now) && canRerollScratch[i]
								&& rerollButtonHit(rectScratch, p.x, p.y)) {
								rerollIndex = i;
							}
							break;
						}
					}
					break;
				case STYLE_ROLL:
					if (phase == PH_SPIN_RESULT) {
						action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
					}
					break;
				case TASK_OFFERS:
					if (phase == PH_OFFERS_SETTLED) {
						int n = offers.size();
						for (int i = 0; i < n; i++) {
							offerRect(i, n, cw, ch, rectScratch);
							if (rectScratch.contains(p.x, p.y)) {
								acceptedIndex = i;
								phase = PH_OFFERS_ACCEPTED;
								phaseStartMs = now;
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
				default:
					break;
			}
		}

		if (rerollIndex >= 0) {
			applyReroll(rerollIndex, now);
		}
		if (deedClicked != null) {
			applyDeedClaim(deedClicked, now);
		}
		executeAction(action, actionArg);
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
			if (activeType == null) {
				return;
			}
			switch (activeType) {
				case CHEST_OPEN:
				case THEMED_CHEST:
					if (phase == PH_CHEST_INTRO || phase == PH_CHEST_UPGRADE || phase == PH_CHEST_DEAL) {
						phase = PH_CHEST_REVEAL;
						phaseStartMs = now;
						lastHoverMs = now;
						handled = true;
					}
					else if (phase == PH_CHEST_REVEAL) {
						// grace window: a skip-spammed press that just landed us
						// here must never instantly flip the cards face-up
						if (now - phaseStartMs < REVEAL_GRACE_MS) {
							handled = true;
							break;
						}
						boolean any = false;
						int stagger = 0;
						for (int i = 0; i < flipAtMs.length; i++) {
							if (flipAtMs[i] == 0 && rerollAtMs[i] == 0) {
								flipAtMs[i] = now + stagger * MASS_FLIP_STAGGER_MS;
								stagger++;
								any = true;
							}
						}
						if (any) {
							handled = true;
						}
						else if (escape) {
							action = finishModalLocked() ? ACT_COMMIT_DRAIN : ACT_NONE;
							handled = true;
						}
					}
					else if (phase == PH_CHEST_WAIT) {
						action = finishModalLocked() ? ACT_COMMIT_DRAIN : ACT_NONE;
						handled = true;
					}
					break;
				case STYLE_ROLL:
					if (phase == PH_SPIN) {
						phase = PH_SPIN_RESULT;
						phaseStartMs = now;
					}
					else {
						action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
					}
					handled = true;
					break;
				case TASK_OFFERS:
					if (phase == PH_OFFERS_UNROLL) {
						phase = PH_OFFERS_SETTLED;
						phaseStartMs = now;
						handled = true;
					}
					else if (phase == PH_OFFERS_SETTLED && escape) {
						// dismiss without accepting; offers stay pending in state
						action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
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
						action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
						handled = true;
					}
					else if (phase == PH_DEED_BURST) {
						// the slot is already unlocked; this is just the flourish
						action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
						handled = true;
					}
					break;
				default:
					break;
			}
			if (!handled && escape) {
				// Escape is the universal exit. Any beat without a dismissal of
				// its own must still release the input this overlay is consuming
				// — a modal that cannot be closed is an input trap, and the user
				// has no way to know which of them they are stuck in.
				boolean chest = activeType == CeremonyBus.Type.CHEST_OPEN
					|| activeType == CeremonyBus.Type.THEMED_CHEST;
				if (finishModalLocked()) {
					action = chest ? ACT_COMMIT_DRAIN : ACT_DRAIN;
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
		@Nullable Supplier<PartyRollService.VoteView> supplier) {
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
	public void abortActiveCeremony(@Nullable CeremonyBus.Type only) {
		int action;
		synchronized (lock) {
			if (activeType == null || (only != null && activeType != only)) {
				return;
			}
			boolean chest = activeType == CeremonyBus.Type.CHEST_OPEN
				|| activeType == CeremonyBus.Type.THEMED_CHEST;
			finishModalLocked();
			action = chest ? ACT_ABORT_COMMIT : ACT_DRAIN;
		}
		executeAction(action, -1);
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
			fanfareStartMs = 0;
		}
	}

	/** Clears the modal slot; true when something was active. Callers execute the follow-up action. */
	private boolean finishModalLocked() {
		if (activeType == null) {
			return false;
		}
		activeType = null;
		lastModalPaintMs = 0;
		chestResult = null;
		chestSlots = null;
		styleResult = null;
		offers = null;
		chosenDeedSlot = null;
		acceptedIndex = -1;
		shockwaveStartMs = 0;
		pityFlipMs = 0;
		return true;
	}

	private void executeAction(int action, int arg) {
		switch (action) {
			case ACT_DRAIN:
				ceremonyBus.drain();
				break;
			case ACT_COMMIT_DRAIN:
				chestService.commitPending();
				ceremonyBus.drain();
				break;
			case ACT_ABORT_COMMIT: {
				ChestService.ChestOpenResult pending = chestService.getPending();
				int cards = pending == null ? 0 : pending.getSlots().size();
				long dupes = chestService.commitPending();
				String msg = "Gachaman: reveal interrupted - " + cards
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
			default:
				break;
		}
	}

	private void applyReroll(int index, long now) {
		ChestService.RolledSlot fresh = chestService.rerollSlot(index);
		if (fresh == null) {
			return;
		}
		synchronized (lock) {
			if (activeType != CeremonyBus.Type.CHEST_OPEN && activeType != CeremonyBus.Type.THEMED_CHEST) {
				return;
			}
			if (index < chestSlots.size()) {
				// keep the OLD card view while the card flips back over; the
				// fresh slot's view is built when the shimmer re-reveals it
				chestSlots.set(index, fresh);
				rerollAtMs[index] = now;
			}
		}
	}

	private void applyDeedClaim(GearSlot slot, long now) {
		boolean ok = chestService.claimDeed(slot);
		int action = ACT_NONE;
		synchronized (lock) {
			if (activeType != CeremonyBus.Type.DEED_CHOICE) {
				return;
			}
			if (ok) {
				chosenDeedSlot = slot;
				phase = PH_DEED_BURST;
				phaseStartMs = now;
			}
			else {
				// no pending deed after all (already spent elsewhere) - just dismiss
				action = finishModalLocked() ? ACT_DRAIN : ACT_NONE;
			}
		}
		executeAction(action, -1);
	}

	// --- render ---

	@Override
	public Dimension render(Graphics2D g) {
		long now = System.currentTimeMillis();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0) {
			return null;
		}
		snapshotCanReroll();

		int action = ACT_NONE;
		int actionArg = -1;
		boolean fanfareEnded = false;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		synchronized (lock) {
			if (activeType != null) {
				int[] out = advanceModalLocked(now);
				action = out[0];
				actionArg = out[1];
			}
			if (activeType != null) {
				// proof of life for isModalInteractive()/pruneStaleModal(): from
				// here on the modal is provably on screen
				lastModalPaintMs = now;
				g.setColor(DIM);
				g.fillRect(0, 0, cw, ch);
				switch (activeType) {
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
					default:
						break;
				}
			}
			if (fanfare != null) {
				if (fanfareStartMs == 0) {
					// claimed while not rendering (e.g. at logout): the clock
					// starts on the first frame actually painted
					fanfareStartMs = now;
				}
				long el = now - fanfareStartMs;
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
		long el = now - phaseStartMs;
		switch (activeType) {
			case CHEST_OPEN:
			case THEMED_CHEST: {
				switch (phase) {
					case PH_CHEST_INTRO:
						if (el >= ChestStrain.totalMs(chestResult.getPurchasedTier())) {
							phase = chestResult.isJackpotUpgraded() ? PH_CHEST_UPGRADE : PH_CHEST_DEAL;
							phaseStartMs = now;
						}
						break;
					case PH_CHEST_UPGRADE:
						if (el >= 850 && fireOnce(9)) {
						}
						if (el >= UPGRADE_MS) {
							phase = PH_CHEST_DEAL;
							phaseStartMs = now;
						}
						break;
					case PH_CHEST_DEAL:
						while (dealSoundIndex + 1 < chestSlots.size()
							&& el >= DEAL_CHEST_DROP_MS + (dealSoundIndex + 1) * DEAL_STAGGER_MS) {
							dealSoundIndex++;
						}
						if (el >= dealTotalMs(chestSlots.size())) {
							phase = PH_CHEST_REVEAL;
							phaseStartMs = now;
							lastHoverMs = now;
						}
						break;
					case PH_CHEST_REVEAL: {
						updateHoverCharges(now);
						for (int i = 0; i < rerollAtMs.length; i++) {
							if (rerollAtMs[i] > 0 && now - rerollAtMs[i] >= REROLL_TOTAL_MS) {
								rerollAtMs[i] = 0;
								cardViews[i] = null;
								flipAtMs[i] = now;
								flipFxFired[i] = false;
							}
						}
						// face effects fire exactly when a flip COMPLETES,
						// for clicked, mass-skipped and rerolled cards alike
						for (int i = 0; i < flipAtMs.length; i++) {
							if (!flipFxFired[i] && flipAtMs[i] > 0 && rerollAtMs[i] == 0
								&& now - flipAtMs[i] >= FLIP_MS) {
								flipFxFired[i] = true;
								slotRect(i, chestSlots.size(), client.getCanvasWidth(),
									client.getCanvasHeight(), rectScratch);
								onFlipEffectsLocked(i, now, rectScratch);
							}
						}
						boolean allDone = true;
						for (int i = 0; i < flipAtMs.length; i++) {
							if (flipAtMs[i] == 0 || rerollAtMs[i] > 0
								|| now - flipAtMs[i] < FLIP_MS + 350) {
								allDone = false;
								break;
							}
						}
						if (allDone) {
							phase = PH_CHEST_WAIT;
							phaseStartMs = now;
						}
						break;
					}
					default:
						break;
				}
				break;
			}
			case STYLE_ROLL:
				if (phase == PH_SPIN) {
					long spin = spinMs();
					double t = clamp01(el / (double) spin);
					double theta = wheelThetaEnd * (1 - Math.pow(1 - t, 3));
					long count = (long) (theta / 120.0);
					if (count > wheelTickCount) {
						wheelTickCount = count;
					}
					if (el >= spin) {
						phase = PH_SPIN_RESULT;
						phaseStartMs = now;
					}
				}
				break;
			case TASK_OFFERS:
				if (phase == PH_OFFERS_UNROLL) {
					// one soft paper whoosh as each scroll starts to unroll
					int started = (int) Math.min(offers.size(), el / OFFER_UNROLL_STAGGER_MS + 1);
					if (started > offerWhooshCount) {
						offerWhooshCount = started;
					}
					if (el >= unrollTotalMs()) {
						phase = PH_OFFERS_SETTLED;
						phaseStartMs = now;
					}
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


	private boolean fireOnce(int bit) {
		int mask = 1 << bit;
		if ((firedSounds & mask) != 0) {
			return false;
		}
		firedSounds |= mask;
		return true;
	}

	/** Fired at flip COMPLETION (the face is fully visible at this instant). */
	private void onFlipEffectsLocked(int i, long now, Rectangle cardRect) {
		ChestService.RolledSlot slot = chestSlots.get(i);
		if (slot.isDuplicate()) {
		}
		if (i == 0 && chestResult.isPityBreak()) {
			pityFlipMs = now;
		}
		boolean shock = slot.getRarity() == Rarity.LEGENDARY
			|| slot.getVariant() == Variant.SHINY
			|| slot.getVariant() == Variant.HOLOGRAM;
		if (shock) {
			shockwaveStartMs = now;
			shockwaveSeed = i * 7919 + (int) (now & 0xFFFF);
			shockwaveColor = slot.getRarity().getColor();
			shockCx = cardRect.x + cardRect.width / 2;
			shockCy = cardRect.y + cardRect.height / 2;
		}
		if (slot.isNearMiss()) {
			// soft fizzle cue — deliberately NOT the shiny fanfare
		}
	}

	private void updateHoverCharges(long now) {
		float dt = Math.min(0.05f, (now - lastHoverMs) / 1000f);
		lastHoverMs = now;
		int n = chestSlots.size();
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		for (int i = 0; i < n; i++) {
			slotRect(i, n, cw, ch, rectScratch);
			boolean hovered = pointerValid && flipAtMs[i] == 0 && rerollAtMs[i] == 0
				&& rectScratch.contains(pointerX, pointerY);
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
		int n = pending == null ? 0 : Math.min(canRerollScratch.length, pending.getSlots().size());
		for (int i = 0; i < canRerollScratch.length; i++) {
			canRerollScratch[i] = i < n && chestService.canReroll(i);
		}
	}

	// =====================================================================
	// CHEST CEREMONY
	// =====================================================================

	private static long dealTotalMs(int n) {
		return DEAL_CHEST_DROP_MS + (n - 1) * DEAL_STAGGER_MS + DEAL_FLIGHT_MS + DEAL_SETTLE_MS + 150;
	}


	private void drawChestCeremony(Graphics2D g, int cw, int ch, long now) {
		long el = now - phaseStartMs;
		// Before the deal the header may only name the tier that was PAID for:
		// announcing the upgraded tier over an un-upgraded chest spoils the very
		// ceremony that exists to reveal it. This is the same guard the
		// (JACKPOT!) suffix below already uses.
		Tuning.Chest shownTier = phase >= PH_CHEST_DEAL
			? chestResult.getEffectiveTier() : chestResult.getPurchasedTier();
		String title = chestThemed
			? "THEMED CHEST" + (chestResult.getThemedSetTag() == null
				? "" : " - " + chestResult.getThemedSetTag().toUpperCase())
			: shownTier.name() + " CHEST";
		if (chestResult.isJackpotUpgraded() && (phase >= PH_CHEST_DEAL)) {
			title = title + "  (JACKPOT!)";
		}
		Color titleColor = !chestThemed && shownTier == Tuning.Chest.RUSTY
			? new Color(176, 156, 128) : GOLD;
		drawCenteredText(g, title, cw / 2, 46, FONT_TITLE, titleColor, true);

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
		Tuning.Chest tier = chestResult.getPurchasedTier();
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;
		int cy = ch / 2 + 20;

		double shakeX = 0;
		double shakeY = 0;
		double lidOpen = 0;
		float flash = 0;
		float leak = 0;
		double camX = 0;
		double camY = 0;

		if (tier == Tuning.Chest.RUSTY) {
			// one feeble wobble, a creak, and the lid gives up — no drama
			if (el >= 300 && el < 800) {
				shakeX = Math.sin(el * 0.07) * 2;
			}
			if (el >= 900) {
				lidOpen = smoothstep(clamp01((el - 900) / 350.0));
				flash = (float) Math.max(0, 0.30 * Math.exp(-(el - 900) / 200.0));
			}
		}
		else if (tier == Tuning.Chest.BATTERED) {
			if (ChestStrain.straining(el, tier)) {
				shakeX = Math.sin(el * 0.09)
					* (2 + 5 * ChestStrain.load(el, tier) + 3 * ChestStrain.kick(el, tier));
			}
			if (el >= 1400) {
				lidOpen = smoothstep(clamp01((el - 1400) / 400.0));
				flash = (float) Math.max(0, 0.55 * Math.exp(-(el - 1400) / 220.0));
			}
		}
		else if (tier == Tuning.Chest.GILDED) {
			if (ChestStrain.straining(el, tier)) {
				double amp = 3 + 5 * ChestStrain.load(el, tier) + 4 * ChestStrain.kick(el, tier);
				shakeX = Math.sin(el * 0.12) * amp;
				shakeY = Math.cos(el * 0.10) * amp * 0.4;
			}
			if (el >= 3200) {
				lidOpen = smoothstep(clamp01((el - 3200) / 500.0));
			}
			if (el >= 2800) {
				flash = (float) Math.max(0, 0.5 * Math.exp(-(el - 2800) / 260.0));
			}
		}
		else {
			// ornate: a padlock bursts and the chain it held whips off - the
			// outer chain at 1200, the inner at 2600 - then the lid seam leaks
			// light with mounting intensity, then the lid blasts open with a
			// decaying 2-3px camera shake
			if (ChestStrain.straining(el, tier)) {
				double load = ChestStrain.load(el, tier);
				double amp = 1.5 + 6.5 * load + 4 * ChestStrain.kick(el, tier);
				shakeX = Math.sin(el * (0.05 + 0.06 * load)) * amp;
			}
			// the upper bound is load-bearing: without it the seam keeps
			// glowing through the lid-blast frames and swallows the payoff
			if (el >= 4000 && el < 6400) {
				leak = (float) clamp01((el - 4000) / 2400.0);
			}
			if (el >= 6400) {
				lidOpen = smoothstep(clamp01((el - 6400) / 350.0)) * 1.35;
				flash = (float) Math.max(0, 0.85 * Math.exp(-(el - 6400) / 300.0));
				double mag = 3.0 * Math.exp(-(el - 6400) / 260.0);
				camX = Math.sin(el * 0.19) * mag;
				camY = Math.cos(el * 0.23) * mag * 0.7;
			}
		}

		Graphics2D gc = (Graphics2D) g.create();
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
		if (chestResult.isStardustBlessed()) {
			drawCenteredText(g, "Stardust-blessed", cw / 2, cy - chestH / 2 - 24, FONT_BODY,
				Paint.withAlpha(STARDUST, 0.75f + 0.25f * (float) Math.sin(el * 0.005)), true);
		}
		drawCenteredText(g, "Space to skip", cw / 2, ch - 30, FONT_SMALL,
			new Color(200, 200, 200, 160), false);
	}

	private void drawChestUpgrade(Graphics2D g, int cw, int ch, long el) {
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;
		int cy = ch / 2 + 20;
		// violent shake building to the flash, then calming as the tint lands
		double envelope = Math.sin(Math.PI * clamp01(el / (double) UPGRADE_MS));
		double amp = 7 + 7 * envelope;
		int dx = cx + (int) (Math.sin(el * 0.18) * amp);
		int dy = cy + (int) (Math.cos(el * 0.15) * amp * 0.5);

		// the jackpot reveal crossfades the two tiers' closed frames, which is a
		// truer upgrade than crossfading the trim colour alone ever was
		float mix = (float) clamp01((el - 700) / 500.0);
		ceremonyPlayer.draw(g, dx, dy, chestW, chestH, chestResult.getPurchasedTier(), 0, 1f);
		if (mix > 0.01f) {
			ceremonyPlayer.draw(g, dx, dy, chestW, chestH, chestResult.getEffectiveTier(), 0, mix);
		}

		if (el >= 750 && el < 1250) {
			float flash = (float) Math.max(0, 0.7 * Math.exp(-(el - 750) / 200.0));
			g.setColor(new Color(255, 255, 255, (int) (flash * 255)));
			g.fillRect(0, 0, cw, ch);
		}
		float pulse = 0.75f + 0.25f * (float) Math.sin(el * 0.02);
		drawCenteredText(g, "JACKPOT!", cw / 2, cy - chestH, FONT_HUGE,
			Paint.withAlpha(GOLD, pulse), true);
		drawCenteredText(g, "Upgraded to " + chestResult.getEffectiveTier().name(),
			cw / 2, cy - chestH + 30, FONT_BODY, Color.WHITE, true);
	}

	private void drawChestDeal(Graphics2D g, int cw, int ch, long el, long now) {
		int chestW = Math.min(300, cw / 3);
		int chestH = chestW * 3 / 4;
		int cx = cw / 2;

		// the open chest eases down toward the bottom edge, then fades out
		// once the last card has left it
		double drop = smoothstep(clamp01(el / (double) DEAL_CHEST_DROP_MS));
		int chestCy = (int) lerp(ch / 2.0 + 20, ch - chestH / 2.0 - 24, drop);
		long total = dealTotalMs(chestSlots.size());
		float chestAlpha = (float) clamp01((total - el) / 320.0);
		if (chestAlpha > 0.02f) {
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, chestAlpha));
			// openT must be exactly 1.0 here: values above 1 add blast lift,
			// which detaches the lid from the hinge while the chest slides
			// (the lid angle is clamped at 1.0 either way)
			ceremonyPlayer.draw(g, cx, chestCy, chestW, chestH, chestResult.getEffectiveTier(),
				ceremonyPlayer.lastFrame(chestResult.getEffectiveTier()), 1f);
			g.setComposite(old);
		}

		// cards launch FACE-DOWN out of the chest opening along an eased arc,
		// staggered, then settle into the row with a small bounce
		int mouthX = cx;
		int mouthY = chestCy - chestH / 2 - 6;
		int n = chestSlots.size();
		for (int i = 0; i < n; i++) {
			long t = el - DEAL_CHEST_DROP_MS - i * DEAL_STAGGER_MS;
			if (t < 0) {
				continue;
			}
			slotRect(i, n, cw, ch, rectScratch);
			if (t < DEAL_FLIGHT_MS) {
				double u = easeOutCubic(t / (double) DEAL_FLIGHT_MS);
				double scale = 0.35 + 0.65 * u;
				int sw = (int) (rectScratch.width * scale);
				int sh = (int) (rectScratch.height * scale);
				double ex = rectScratch.x + rectScratch.width / 2.0;
				double ey = rectScratch.y + rectScratch.height / 2.0;
				// quadratic arc: control point well above both endpoints
				double ctrlX = (mouthX + ex) / 2.0;
				double ctrlY = Math.min(mouthY, ey) - rectScratch.height * 0.9;
				double omu = 1.0 - u;
				double px = omu * omu * mouthX + 2 * omu * u * ctrlX + u * u * ex;
				double py = omu * omu * mouthY + 2 * omu * u * ctrlY + u * u * ey;
				double rot = (1.0 - u) * (i % 2 == 0 ? -0.30 : 0.30);
				Graphics2D g2 = (Graphics2D) g.create();
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
				int sh = (int) (rectScratch.height * squash);
				CardRenderer.drawBack(g, rectScratch.x,
					rectScratch.y + dy + (rectScratch.height - sh), rectScratch.width, sh, now);
			}
		}
	}

	private void drawChestReveal(Graphics2D g, int cw, int ch, long now) {
		int n = chestSlots.size();
		for (int i = 0; i < n; i++) {
			slotRect(i, n, cw, ch, rectScratch);
			drawRevealSlot(g, i, rectScratch, now);
		}

		if (shockwaveStartMs > 0 && now >= shockwaveStartMs
			&& now - shockwaveStartMs < SHOCKWAVE_MS) {
			drawShockwave(g, cw, ch, now - shockwaveStartMs);
		}
		if (pityFlipMs > 0 && now - pityFlipMs < PITY_GLOW_MS) {
			drawPityEdgeGlow(g, cw, ch, now - pityFlipMs);
		}

		String hint = phase == PH_CHEST_WAIT
			? "Click anywhere to collect"
			: "Click cards to reveal - Esc to skip";
		drawCenteredText(g, hint, cw / 2, ch - 30, FONT_BODY, new Color(235, 225, 200), true);
	}

	private void drawRevealSlot(Graphics2D g, int i, Rectangle r, long now) {
		ChestService.RolledSlot slot = chestSlots.get(i);
		Color trueColor = slot.getRarity().getColor();

		if (rerollAtMs[i] > 0) {
			long t = now - rerollAtMs[i];
			if (t < REROLL_FLIPBACK_MS) {
				// face flips back over (cosine ease-in-out)
				double s = Math.cos(Math.PI * t / (double) REROLL_FLIPBACK_MS);
				if (s > 0) {
					drawScaledX(g, r, s, true, i, now);
				}
				else {
					drawScaledX(g, r, -s, false, i, now);
				}
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

		if (flipAtMs[i] == 0) {
			// face-down; hover charge-up glow toward the TRUE rarity color
			if (hoverCharge[i] > 0.01f) {
				CardRenderer.drawGlow(g, r.x, r.y, r.width, r.height, trueColor, hoverCharge[i]);
			}
			CardRenderer.drawBack(g, r.x, r.y, r.width, r.height, now);
			if (chestResult.isStardustBlessed()) {
				// subtle blessed shimmer on unrevealed backs
				float pulse = 0.18f + 0.14f * (float) Math.sin(now * 0.004);
				g.setColor(Paint.withAlpha(STARDUST, pulse));
				g.setStroke(new BasicStroke(1.6f));
				g.drawRoundRect(r.x, r.y, r.width, r.height, r.width / 7, r.width / 7);
			}
			return;
		}

		long t = now - flipAtMs[i];
		if (t < 0) {
			CardRenderer.drawBack(g, r.x, r.y, r.width, r.height, now);
			return;
		}
		if (t < FLIP_MS) {
			// horizontal scale 1 -> 0 (back), swap, 0 -> 1 (face); the cosine
			// gives ease-in-out and the face NEVER paints in the first half
			double s = Math.cos(Math.PI * t / (double) FLIP_MS);
			if (s > 0) {
				drawScaledX(g, r, s, false, i, now);
			}
			else {
				drawScaledX(g, r, -s, true, i, now);
			}
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
			if (ft >= 0 && ft < STARDUST_FIZZLE_MS) {
				drawStardustFizzle(g, r, ft);
			}
		}
		if (i == 0 && chestResult.isPityBreak()) {
			drawCenteredText(g, "PITY BREAK", r.x + r.width / 2, r.y - 12, FONT_BODY, GOLD, true);
		}
		if (canRerollScratch.length > i && canRerollScratch[i] && phase == PH_CHEST_REVEAL) {
			drawRerollToken(g, r, now);
		}
	}

	/** Draw one card side horizontally squashed (flip animation). */
	private void drawScaledX(Graphics2D g, Rectangle r, double scaleX, boolean face, int i, long now) {
		if (scaleX < 0.04) {
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
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
		if (view != null) {
			return view;
		}
		ChestService.RolledSlot slot = chestSlots.get(i);
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
		g.setColor(Paint.withAlpha(GOLD, pulse));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(bx - 13, by - 13, 26, 26);
		// circular re-roll arrow
		g.drawArc(bx - 7, by - 7, 14, 14, 30, 280);
		g.drawLine(bx + 6, by - 4, bx + 9, by - 8);
		g.drawLine(bx + 6, by - 4, bx + 2, by - 6);
	}

	private boolean isFaceUpSteady(int i, long now) {
		return flipAtMs[i] > 0 && rerollAtMs[i] == 0 && now - flipAtMs[i] >= FLIP_MS;
	}

	private void drawChip(Graphics2D g, int rightX, int y, String text) {
		g.setFont(FONT_SMALL);
		FontMetrics fm = g.getFontMetrics();
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
	 * {@link #STARDUST_FIZZLE_MS} after the face lands.
	 */
	private void drawStardustFizzle(Graphics2D g, Rectangle r, long t) {
		float u = (float) clamp01(t / (double) STARDUST_FIZZLE_MS);
		Graphics2D g2 = (Graphics2D) g.create();
		// brief center glint that shrinks instead of shockwaving
		if (t < 150) {
			float gu = 1f - t / 150f;
			int gr = (int) (10 * gu);
			g2.setColor(Paint.withAlpha(Color.WHITE, 0.6f * gu));
			g2.setStroke(new BasicStroke(1.5f));
			int gcx = r.x + r.width / 2;
			int gcy = r.y + r.height / 3;
			g2.drawLine(gcx - gr, gcy, gcx + gr, gcy);
			g2.drawLine(gcx, gcy - gr, gcx, gcy + gr);
		}
		g2.setStroke(new BasicStroke(1.2f));
		for (int p = 0; p < 12; p++) {
			float h1 = Paint.hash01(p * 131 + 7);
			float h2 = Paint.hash01(p * 131 + 8);
			float h3 = Paint.hash01(p * 131 + 9);
			float pu = (float) clamp01((t - h3 * 250) / (double) (STARDUST_FIZZLE_MS - 250));
			if (pu <= 0 || pu >= 1) {
				continue;
			}
			double rise = easeOutCubic(pu) * (18 + h2 * 26);
			int px = r.x + (int) (h1 * r.width);
			int py = r.y + (int) (h2 * r.height * 0.5) - (int) rise;
			float alpha = pu > 0.66f ? (1f - pu) * 3f : 1f;
			g2.setColor(Paint.withAlpha(STARDUST, 0.75f * alpha));
			int s = 2 + (int) (h3 * 2);
			g2.drawLine(px - s, py, px + s, py);
			g2.drawLine(px, py - s, px, py + s);
		}
		g2.dispose();
	}

	private void drawShockwave(Graphics2D g, int cw, int ch, long t) {
		float u = (float) clamp01(t / (double) SHOCKWAVE_MS);
		double maxR = Math.hypot(cw, ch) * 0.55;
		double r = smoothstep(u) * maxR;
		float ringAlpha = (1 - u);

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setColor(Paint.withAlpha(shockwaveColor, ringAlpha * 0.85f));
		g2.setStroke(new BasicStroke(2f + (1 - u) * 18f));
		g2.drawOval(shockCx - (int) r, shockCy - (int) r, (int) (r * 2), (int) (r * 2));
		double r2 = smoothstep(clamp01(u * 1.35)) * maxR * 0.7;
		g2.setColor(Paint.withAlpha(Color.WHITE, ringAlpha * 0.45f));
		g2.setStroke(new BasicStroke(1.5f + (1 - u) * 8f));
		g2.drawOval(shockCx - (int) r2, shockCy - (int) r2, (int) (r2 * 2), (int) (r2 * 2));

		// vignette breath
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
			float h1 = Paint.hash01(shockwaveSeed + p * 3);
			float h2 = Paint.hash01(shockwaveSeed + p * 3 + 1);
			float h3 = Paint.hash01(shockwaveSeed + p * 3 + 2);
			double ang = h1 * Math.PI * 2;
			double speed = (90 + h2 * 320);
			int px = shockCx + (int) (Math.cos(ang) * speed * ts);
			int py = shockCy + (int) (Math.sin(ang) * speed * ts + 340 * ts * ts);
			int size = 2 + (int) (h3 * 3 * (1 - u));
			if (size <= 0) {
				continue;
			}
			g2.setColor(Paint.withAlpha((p & 1) == 0 ? shockwaveColor : Color.WHITE, ringAlpha));
			g2.fillRect(px, py, size, size);
		}
		g2.dispose();
	}

	private void drawPityEdgeGlow(Graphics2D g, int cw, int ch, long t) {
		float ramp = (float) clamp01(t / 250.0);
		float decay = (float) clamp01(1.0 - (t - 250) / (double) (PITY_GLOW_MS - 250));
		float a = ramp * decay * 0.55f;
		if (a <= 0.02f) {
			return;
		}
		int band = Math.max(26, ch / 9);
		Color inner = Paint.withAlpha(GOLD, 0f);
		Color outer = Paint.withAlpha(GOLD, a);
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
	private static final Map<String, Image> ART = new HashMap<>();

	private static void blitArt(Graphics2D g, String name, int cx, int cy, double scale,
		int anchorX, int anchorY) {
		Image art = ART.computeIfAbsent(name, n -> {
			try (InputStream in = RevealOverlay.class.getResourceAsStream(
				"/com/gachaman/art/" + n + ".png")) {
				return in == null ? null : ImageIO.read(in);
			}
			catch (Exception e) {
				return null;
			}
		});
		if (art == null) {
			return;
		}
		g.drawImage(art,
			cx - (int) Math.round(anchorX * scale), cy - (int) Math.round(anchorY * scale),
			(int) Math.round(art.getWidth(null) * scale),
			(int) Math.round(art.getHeight(null) * scale), null);
	}

	private void drawStyleRoll(Graphics2D g, int cw, int ch, long now) {
		long el = now - phaseStartMs;
		boolean result = phase == PH_SPIN_RESULT;
		// honest backward-solved deceleration - unchanged
		double t = result ? 1.0 : clamp01(el / (double) spinMs());
		double theta = wheelThetaEnd * (1 - Math.pow(1 - t, 3));
		long rt = result ? el : 0;

		int radius = Math.min(Math.min(cw, ch) / 3, 190);
		int cx = cw / 2;
		int cy = ch / 2 + 10;

		drawCenteredText(g, "STYLE ROULETTE", cx, cy - radius - 64, FONT_TITLE, GOLD, true);
		if (styleResult.getPrevious() == null) {
			// the longer first spin is only worth the seconds if the player is told
			// what it is deciding; every roll after this one is merely a re-roll
			drawCenteredText(g, "Your first colours - and a chest to match",
				cx, cy - radius - 44, FONT_SMALL, new Color(215, 200, 165), false);
		}

		blitArt(g, "wheel-shadow", cx, cy, radius / (double) WHEEL_ART_R, 220, 220);

		AttackStyle[] styles = AttackStyle.values();
		AttackStyle rolled = styleResult.getRolled();
		float desat = result ? (float) clamp01(rt / 600.0) : 0f;
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
				g.setColor(Paint.withAlpha(rolled.getColor(), pw * 0.65f));
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
		blitArt(g, "wheel-rim", cx, cy, radius / (double) WHEEL_ART_R, 220, 220);

		// wedge labels
		g.setFont(FONT_BODY);
		for (int i = 0; i < styles.length; i++) {
			double mid = Math.toRadians(i * 120 + 60 + theta);
			int lx = cx + (int) (Math.cos(mid) * radius * 0.62);
			int ly = cy - (int) (Math.sin(mid) * radius * 0.62);
			Color lc = (result && styles[i] != rolled)
				? Paint.withAlpha(Color.WHITE, 1f - desat * 0.55f) : Color.WHITE;
			drawCenteredText(g, styles[i].getDisplayName().toUpperCase(), lx, ly, FONT_BODY,
				lc, true);
		}

		blitArt(g, "wheel-hub", cx, cy, 1.0, 32, 30);
		drawWheelPointer(g, cx, cy, radius, theta);

		if (result) {
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
		Graphics2D g2 = (Graphics2D) g.create();
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
		double slide = easeOutCubic(clamp01(rt / 380.0));
		int bw = Math.min(540, cw - 30);
		int bh = 48;
		int bx = (cw - bw) / 2 + (int) ((1.0 - slide) * (cw / 2.0 + bw));
		int by = Math.min(cy + radius + 26, ch - bh - 44);

		g.setColor(new Color(0, 0, 0, 70));
		g.fillRect(bx + 4, by + 5, bw, bh);
		g.setPaint(new GradientPaint(bx, by, new Color(26, 20, 12, 245),
			bx, by + bh, new Color(14, 10, 6, 245)));
		g.fillRect(bx, by, bw, bh);
		g.setColor(rolled.getColor());
		g.fillRect(bx, by, bw, 3);
		g.fillRect(bx, by + bh - 3, bw, 3);
		// ribbon tails
		g.setColor(Paint.withAlpha(rolled.getColor(), 0.65f));
		g.fillRect(bx - 10, by + 6, 10, bh - 12);
		g.fillRect(bx + bw, by + 6, 10, bh - 12);

		// pluralised: a Compactor can halve the remaining cycle down to one, and
		// this banner is the ceremony's headline — "re-roll in 1 tasks" on the
		// biggest text the plugin draws
		int cycleTarget = styleResult.getCycleTarget();
		String line = rolled.getDisplayName().toUpperCase() + " ALLOWED - re-roll in "
			+ cycleTarget + (cycleTarget == 1 ? " contract" : " contracts");
		g.setFont(FONT_TITLE);
		FontMetrics fm = g.getFontMetrics();
		Font lineFont = fm.stringWidth(line) > bw - 24 ? FONT_BODY : FONT_TITLE;
		drawCenteredText(g, line, bx + bw / 2, by + bh / 2, lineFont, rolled.getColor(), true);

		if (styleResult.getPrevious() != null && styleResult.getPrevious() != rolled) {
			drawCenteredText(g, "(was " + styleResult.getPrevious().getDisplayName() + ")",
				bx + bw / 2, by + bh + 14, FONT_SMALL, new Color(200, 200, 200), false);
		}
		drawCenteredText(g, "Click to continue", cx, ch - 30, FONT_BODY,
			new Color(235, 225, 200), true);
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
		long el = phase == PH_OFFERS_UNROLL ? now - phaseStartMs : Long.MAX_VALUE / 4;
		int n = offers.size();

		drawOffersBackdrop(g, cw, ch);
		String hint = phase == PH_OFFERS_UNROLL
			? "Esc/Space to skip"
			: (phase == PH_OFFERS_SETTLED ? "Click a contract to accept - Esc to decide later" : "");
		if (!hint.isEmpty()) {
			drawCenteredText(g, hint, cw / 2, ch - 26, FONT_BODY, new Color(235, 225, 200), true);
		}

		float burnT = phase == PH_OFFERS_ACCEPTED
			? (float) clamp01((now - phaseStartMs) / (double) OFFER_BURN_MS) : 0f;

		// once per FRAME, not once per scroll: the snapshot allocates, and asking
		// four times a frame would also let two scrolls disagree about the tally
		// if a ballot landed between them
		frameVotes = partyVoteSupplier == null ? null : partyVoteSupplier.get();

		for (int i = 0; i < n; i++) {
			offerRect(i, n, cw, ch, rectScratch);
			Rectangle r = rectScratch;

			// scrolls unroll in place, staggered left to right
			double u = 1.0;

			if (phase == PH_OFFERS_UNROLL) {
				long t = el - i * OFFER_UNROLL_STAGGER_MS;
				u = t <= 0 ? 0 : easeOutBack(t / (double) OFFER_UNROLL_MS);
			}

			boolean accepted = phase == PH_OFFERS_ACCEPTED && i == acceptedIndex;
			boolean burning = phase == PH_OFFERS_ACCEPTED && i != acceptedIndex;
			boolean hovered = phase == PH_OFFERS_SETTLED && pointerValid
				&& r.contains(pointerX, pointerY);

			// hovered (or accepted) scroll lifts 6px with a stronger shadow
			int lift = (hovered || accepted) ? 6 : 0;
			rectScratch2.setBounds(r.x, r.y - lift, r.width, r.height);

			// shadow tracks only the occupied extent (rollers + revealed sheet)
			int topEdge = ScrollPainter.topRollerCy(rectScratch2, u) - ScrollPainter.ROLLER_H / 2;
			int botEdge = ScrollPainter.bottomRollerCy(rectScratch2, u) + ScrollPainter.ROLLER_H / 2;
			rectScratch2.setBounds(r.x, topEdge, r.width, botEdge - topEdge);
			drawSoftShadow(g, rectScratch2, lift > 0 ? 0.55f : 0.35f, lift);
			rectScratch2.setBounds(r.x, r.y - lift, r.width, r.height);

			if (burning) {
				drawBurningOffer(g, i, rectScratch2, now, burnT);
				continue;
			}
			drawOfferScroll(g, i, rectScratch2, u);
			if (accepted) {
				drawAcceptedStamp(g, rectScratch2, now - phaseStartMs);
			}
		}
	}

	/** Dark vignette backdrop plus the gold-trimmed ceremony header. */
	private void drawOffersBackdrop(Graphics2D g, int cw, int ch) {
		int band = Math.max(70, ch / 4);
		Color edge = new Color(0, 0, 0, 185);
		Color mid = new Color(0, 0, 0, 0);
		g.setPaint(new GradientPaint(0, 0, edge, 0, band, mid));
		g.fillRect(0, 0, cw, band);
		g.setPaint(new GradientPaint(0, ch, edge, 0, ch - band, mid));
		g.fillRect(0, ch - band, cw, band);
		g.setPaint(new GradientPaint(0, 0, edge, band, 0, mid));
		g.fillRect(0, 0, band, ch);
		g.setPaint(new GradientPaint(cw, 0, edge, cw - band, 0, mid));
		g.fillRect(cw - band, 0, band, ch);

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
		g.setColor(Paint.withAlpha(GOLD, 0.55f));
		g.setStroke(new BasicStroke(1f));
		g.drawRoundRect(hx + 4, hy + 4, hw - 8, hh - 8, 7, 7);
		drawCenteredText(g, "CHOOSE YOUR CONTRACT", cw / 2, hy + hh / 2, FONT_TITLE, GOLD, true);
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
		int parchX = r.x + ScrollPainter.PARCH_INSET;
		int parchW = r.width - ScrollPainter.PARCH_INSET * 2;

		// under everything: the scroll should sit ABOVE the scene, and nothing
		// says "in front of" like something casting onto what is behind it
		ScrollPainter.drawDropShadow(g, r, u);

		if (revBot > revTop) {
			if (u >= 1.0) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.clipRect(r.x, revTop, r.width, revBot - revTop);
				drawScrollBody(g2, i, r);
				g2.dispose();
			}
			else {
				int band = Math.min(ScrollPainter.CURL_BAND, (revBot - revTop) / 2);
				double squash = 0.70 + 0.30 * u;
				Graphics2D g2 = (Graphics2D) g.create();
				g2.clipRect(r.x, revTop + band, r.width, revBot - revTop - band * 2);
				drawScrollBody(g2, i, r);
				g2.dispose();
				if (band > 0) {
					// leading-edge curl: the band nearest each roller shows the
					// same inked content, vertically squashed toward the roller
					g2 = (Graphics2D) g.create();
					g2.clipRect(r.x, revTop, r.width, band);
					g2.translate(0, revTop);
					g2.scale(1, squash);
					g2.translate(0, -revTop);
					drawScrollBody(g2, i, r);
					g2.dispose();

					g2 = (Graphics2D) g.create();
					g2.clipRect(r.x, revBot - band, r.width, band);
					g2.translate(0, revBot);
					g2.scale(1, squash);
					g2.translate(0, -revBot);
					drawScrollBody(g2, i, r);
					g2.dispose();
				}
			}
			// contact shading where the sheet meets each roller
			ScrollPainter.drawEdgeShade(g, parchX, parchW, revTop, true);
			ScrollPainter.drawEdgeShade(g, parchX, parchW, revBot, false);
		}

		OfferScrollArt art = offerArt[i];
		// the rods counter-rotate as the sheet pays out: the top one winds up,
		// the bottom one down, which is what makes the paper look like it is
		// coming OFF them rather than the two simply drifting apart
		ScrollPainter.drawRoller(g, r, topCy, art.tier, -u * 1.6);
		ScrollPainter.drawRoller(g, r, botCy, art.tier, u * 1.6);
	}

	/**
	 * The fully-inked parchment sheet at its final layout; callers clip (and
	 * optionally squash) this to the window revealed between the rollers.
	 */
	private void drawScrollBody(Graphics2D g, int i, Rectangle r) {
		TaskOffer offer = offers.get(i);
		OfferScrollArt art = offerArt[i];
		int parchX = r.x + ScrollPainter.PARCH_INSET;
		int parchW = r.width - ScrollPainter.PARCH_INSET * 2;
		int parchTop = r.y + ScrollPainter.ROLLER_H;
		int parchH = r.height - ScrollPainter.ROLLER_H * 2;
		int parchBot = parchTop + parchH;

		ScrollPainter.drawParchment(g, parchX, parchTop, parchW, parchH,
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
		g.setFont(FONT_BODY);
		FontMetrics hfm = g.getFontMetrics();
		int labelW = spacedWidth(hfm, art.label, HEAD_TRACKING);
		int cxHead = parchX + parchW / 2;
		int baseHead = ribY + ribH / 2 + hfm.getAscent() / 2 - 2;
		int ruleY = baseHead - hfm.getAscent() / 2 - 1;

		int outerL = parchX + BAND_INSET;
		int innerL = cxHead - labelW / 2 - 9;
		if (innerL - outerL > 6) {
			g.setPaint(new GradientPaint(outerL, 0, Paint.withAlpha(art.edge, 0f),
				innerL, 0, Paint.withAlpha(art.edge, 0.75f)));
			g.fillRect(outerL, ruleY, innerL - outerL, 1);
		}
		// the party mark occupies the end of the heading line, so the right rule
		// stops short of it. It used to run underneath the glyph — a hairline
		// through the middle of the one mark that changes what a click does.
		int marginR = parchX + parchW - BAND_INSET;
		int glyphX = marginR - PARTY_GLYPH_W;
		int outerR = offer.isPartyRoll() ? glyphX - 7 : marginR;
		int innerR = cxHead + labelW / 2 + 9;
		if (outerR - innerR > 6) {
			g.setPaint(new GradientPaint(innerR, 0, Paint.withAlpha(art.edge, 0.75f),
				outerR, 0, Paint.withAlpha(art.edge, 0f)));
			g.fillRect(innerR, ruleY, outerR - innerR, 1);
		}

		// debossed: a pale ghost one pixel BELOW the ink is the light catching the
		// far wall of an impression, which is what makes type look stamped in
		drawSpaced(g, art.label, cxHead - labelW / 2, baseHead + 1, HEAD_TRACKING,
			Paint.withAlpha(PARCH_EMBOSS, 0.55f));
		drawSpaced(g, art.label, cxHead - labelW / 2, baseHead, HEAD_TRACKING, art.headInk);

		if (offer.isPartyRoll()) {
			// shared party contract: clicking VOTES rather than accepts. Centred on
			// the heading's own band rather than offset from its top, so it sits
			// level with the type instead of riding a couple of pixels high.
			drawPartySilhouette(g, glyphX + 1,
				ribY + (ribH - PARTY_GLYPH_H) / 2, art.edge);
		}

		int fieldX = parchX + 8;
		int fieldW = parchW - 16;

		// Contract data, dark ink. A running cursor rather than four fixed
		// offsets: a long quarry name ("Fremennik warband berserker") now takes
		// the two lines it needs and pushes the rest down, instead of being cut
		// off mid-word. Nothing on a contract should end in an ellipsis — the one
		// thing a player must be able to read is what they are agreeing to kill.
		int cbY = ribY + ribH + 20;
		cbY = drawWrappedCentre(g, offer.getMonsterName(), parchX + parchW / 2, cbY,
			FONT_NAME, PARCH_INK, fieldW, parchBot, 26);
		cbY = drawWrappedCentre(g, art.killsLine, parchX + parchW / 2, cbY,
			FONT_BODY, PARCH_INK_SOFT, fieldW, parchBot, 22);
		cbY = drawWrappedCentre(g, art.rewardLine, parchX + parchW / 2, cbY,
			FONT_SMALL, PARCH_REWARD, fieldW, parchBot, 20);
		cbY = drawWrappedCentre(g, art.cbLine, parchX + parchW / 2, cbY,
			FONT_SMALL, PARCH_INK_SOFT, fieldW, parchBot, 0);

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
				fieldW, parchBot);
		}

		// The Ante, inked on the contract itself: an armed wager must be legible
		// on the very thing the player is about to click, not only in a panel
		// they may have scrolled away from.
		if (TaskService.anteEligible(offer) && taskService.anteArmed()) {
			int stake = taskService.previewAnteStake();
			if (stake > 0 && footerY + 16 <= parchBot - 6) {
				drawInkLine(g, "ANTE ARMED — " + stake + " GC", parchX + parchW / 2,
					footerY + 8, FONT_SMALL, PARCH_ANTE, fieldW);
				footerY += 18;
			}
		}

		if (art.betConds.length > 0) {
			drawSideBets(g, art.betConds, art.betRewards, fieldX, footerY, fieldW, parchBot);
		}
	}

	/**
	 * A hairline and a small caption introducing a footer block.
	 *
	 * @return the top of the block's first content row
	 */
	private static int drawFooterHeading(Graphics2D g, String label, int x, int y, int w) {
		g.setFont(FONT_SMALL);
		FontMetrics fm = g.getFontMetrics();
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
		int x, int y, int w, int parchBot) {
		g.setFont(FONT_SMALL);
		FontMetrics fm = g.getFontMetrics();
		if (y + fm.getHeight() * 2 + 6 > parchBot - 6) {
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
				if (y + fm.getHeight() > parchBot - 6) {
					return y;
				}
				String part = parts.get(p);
				g.setColor(PARCH_BET);
				g.drawString(part, x, y + fm.getAscent());
				if (p == parts.size() - 1 && reward != null) {
					int at = fm.stringWidth(part) + space;
					if (at + rewardW <= w) {
						g.setColor(PARCH_REWARD);
						g.drawString(reward, x + at, y + fm.getAscent());
						reward = null;
					}
				}
				y += fm.getHeight();
			}
			if (reward != null) {
				// no room on the condition's last line: give the payout its own,
				// still in gold, rather than squeezing it past the margin
				if (y + fm.getHeight() > parchBot - 6) {
					return y;
				}
				g.setColor(PARCH_REWARD);
				g.drawString(reward, x, y + fm.getAscent());
				y += fm.getHeight();
			}
		}
		return y;
	}

	/** Party avatars are square on the wire; this is the edge they are drawn at. */
	private static final int VOTER_FACE = 12;


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
	private static int drawVoters(Graphics2D g,
		List<PartyRollService.Voter> voters,
		int x, int y, int maxWidth, int parchBot) {
		if (voters == null || voters.isEmpty()) {
			return y;
		}
		g.setFont(FONT_SMALL);
		FontMetrics fm = g.getFontMetrics();
		int rowH = Math.max(VOTER_FACE, fm.getHeight()) + 3;
		// heading plus one row, or the block says nothing and takes no space
		if (y + 5 + fm.getHeight() + rowH > parchBot - 6) {
			return y;
		}
		y = drawFooterHeading(g, "Backed by", x, y, maxWidth);

		// A wrapping flow, not a column. A party of five turned a stack of names
		// into most of the scroll and pushed the side bets off the bottom; read
		// as a sentence, the same five fit on two lines and the block stays a
		// footnote rather than becoming the body of the contract.
		int cx = x;
		for (int v = 0; v < voters.size(); v++) {
			PartyRollService.Voter voter = voters.get(v);
			boolean last = v == voters.size() - 1;
			String name = voter.getName() + (last ? "" : ",");
			int faceW = voter.getAvatar() != null ? VOTER_FACE + 3 : 0;
			int chipW = faceW + fm.stringWidth(name);

			if (cx > x && cx + chipW > x + maxWidth) {
				cx = x;
				y += rowH;
			}
			if (y + rowH > parchBot - 6) {
				// no room for another line: say how many went unnamed rather than
				// trailing off, since a truncated list of allies is a misleading one
				g.setColor(PARCH_INK_SOFT);
				g.drawString("+" + (voters.size() - v) + " more", cx,
					y - rowH + (rowH + fm.getAscent()) / 2 - 2);
				return y + 2;
			}
			int baseline = y + (rowH + fm.getAscent()) / 2 - 2;
			if (voter.getAvatar() != null) {
				drawVoterFace(g, voter.getAvatar(), cx, y + (rowH - VOTER_FACE) / 2);
			}
			g.setFont(FONT_SMALL);
			g.setColor(voter.isSelf() ? PARCH_REWARD : PARCH_INK);
			g.drawString(name, cx + faceW, baseline);
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
			VOTER_FACE, VOTER_FACE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D tg = tinted.createGraphics();
		try {
			tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			tg.drawImage(src, 0, 0, VOTER_FACE, VOTER_FACE, null);
			tg.setComposite(AlphaComposite.SrcAtop);
			tg.setColor(FACE_WASH);
			tg.fillRect(0, 0, VOTER_FACE, VOTER_FACE);
		}
		finally {
			tg.dispose();
		}
		g.drawImage(tinted, x, y, null);
		g.setColor(PARCH_EDGE_SOFT);
		g.drawRect(x, y, VOTER_FACE - 1, VOTER_FACE - 1);
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
		if (text == null || text.isEmpty()) {
			return lines;
		}
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
		Color color, int maxWidth, int parchBot, int gapAfter) {
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = wrapText(fm, text, maxWidth);
		for (String line : lines) {
			if (y > parchBot - 8) {
				return y;
			}
			g.setColor(color);
			g.drawString(line, cx - fm.stringWidth(line) / 2, y + fm.getAscent() / 2 - 2);
			y += fm.getHeight();
		}
		return y + gapAfter - (lines.isEmpty() ? 0 : 0);
	}

	/** Width of {@code text} once {@code tracking} px are added between letters. */
	private static int spacedWidth(FontMetrics fm, String text, int tracking) {
		if (text.isEmpty()) {
			return 0;
		}
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
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		String clipped = clipText(fm, text, maxWidth);
		g.setColor(color);
		g.drawString(clipped, cx - fm.stringWidth(clipped) / 2, cy + fm.getAscent() / 2 - 2);
	}

	/**
	 * Per-offer scroll styling and text, precomputed at ceremony start so
	 * the per-frame drawing performs no color mixing or string building.
	 */
	private static final class OfferScrollArt {
		final Color tier;
		final Color parchTop;
		final Color parchBottom;
		/** Tier-tinted ink for the difficulty heading. */
		final Color headInk;
		/** Muted rule/outline colour for this scroll. */
		final Color edge;
		final String label;
		final String killsLine;
		final String rewardLine;
		final String cbLine;
		/**
		 * Side bets kept as two parallel arrays rather than one joined string.
		 *
		 * <p>The payout is drawn in a different ink from the condition, and the
		 * only robust way to know where one ends and the other begins is to never
		 * have merged them: splitting on the last " +" works until a condition
		 * describes something with a plus in it.
		 */
		final String[] betConds;
		final String[] betRewards;

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
		g.setColor(Paint.withAlpha(ink, 0.55f));
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
		Graphics2D g2 = (Graphics2D) g.create();
		g2.rotate(rot, cx, cy);
		g2.translate(cx, cy);
		g2.scale(scale, scale);
		g2.translate(-cx, -cy);
		g2.setFont(FONT_TITLE);
		FontMetrics fm = g2.getFontMetrics();
		String text = "ACCEPTED";
		int tx = (int) cx - fm.stringWidth(text) / 2;
		int ty = (int) cy + fm.getAscent() / 2;
		Color inkGreen = new Color(24, 104, 34);
		g2.setColor(Paint.withAlpha(inkGreen, a * 0.28f));
		g2.fillRoundRect(tx - 10, ty - fm.getAscent() - 6, fm.stringWidth(text) + 20,
			fm.getHeight() + 12, 8, 8);
		g2.setColor(Paint.withAlpha(inkGreen, a));
		g2.setStroke(new BasicStroke(3f));
		g2.drawRoundRect(tx - 10, ty - fm.getAscent() - 6, fm.stringWidth(text) + 20,
			fm.getHeight() + 12, 8, 8);
		g2.setStroke(new BasicStroke(1.2f));
		g2.drawRoundRect(tx - 6, ty - fm.getAscent() - 2, fm.stringWidth(text) + 12,
			fm.getHeight() + 4, 6, 6);
		g2.drawString(text, tx, ty);
		g2.dispose();

		// impact dust ring on the thud
		if (t >= slamMs && t < slamMs + 260) {
			float v = (t - slamMs) / 260f;
			int rr = (int) (r.width * (0.30 + 0.35 * easeOutCubic(v)));
			g.setColor(Paint.withAlpha(new Color(60, 50, 30), (1 - v) * 0.4f));
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
		if (visibleH <= 2) {
			return;
		}
		int frontY = r.y + visibleH;

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setClip(r.x - 8, r.y - 10, r.width + 16, visibleH + 10);
		drawOfferScroll(g2, i, r, 1.0);
		// charring just above the burn front
		g2.setPaint(new GradientPaint(r.x, frontY - 18, new Color(30, 16, 8, 0),
			r.x, frontY, new Color(30, 16, 8, 210)));
		g2.fillRect(r.x, frontY - 18, r.width, 18);
		g2.dispose();

		// glowing ragged burn edge
		for (int k = 0; k < r.width; k += 5) {
			float hk = Paint.hash01(i * 811 + k * 7);
			int tick = 2 + (int) (hk * 5);
			g.setColor(Paint.withAlpha(hk > 0.5f ? EMBER_HOT : EMBER_RED, 0.85f));
			g.fillRect(r.x + k, frontY - tick / 2, 4, tick);
		}

		// embers rising off the front (deterministic, fake buoyancy)
		double ts = (now - phaseStartMs) / 1000.0;
		for (int p = 0; p < 12; p++) {
			float h1 = Paint.hash01(i * 977 + p * 3);
			float h2 = Paint.hash01(i * 977 + p * 3 + 1);
			double rise = (40 + h2 * 130) * ts;
			int px = r.x + (int) (h1 * r.width) + (int) (Math.sin(ts * 5 + p) * 6);
			int py = frontY - (int) rise;
			if (py < r.y - 40) {
				continue;
			}
			float a = (1 - burnT) * (0.4f + 0.6f * h2);
			g.setColor(Paint.withAlpha((p & 1) == 0 ? EMBER_HOT : EMBER_RED, a));
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
		drawCenteredText(g, "SLOT DEED EARNED", cw / 2, 46, FONT_TITLE, GOLD, true);
		drawCenteredText(g, "Choose a locked slot to unlock forever", cw / 2, 70, FONT_BODY,
			Color.WHITE, true);
		GachaState state = stateService.get();
		int pending = state == null ? 0 : state.getPendingDeeds();
		if (pending > 1) {
			drawCenteredText(g, "Deeds available: " + pending, cw / 2, 90, FONT_SMALL,
				new Color(200, 200, 200), false);
		}

		GearSlot[] slots = GearSlot.values();
		for (int i = 0; i < slots.length; i++) {
			Rectangle r = deedRects[i];
			boolean deeded = isSlotDeeded(slots[i]);
			boolean chosen = phase == PH_DEED_BURST && slots[i] == chosenDeedSlot;
			boolean hovered = phase == PH_DEED_CHOOSE && !deeded && pointerValid
				&& r.contains(pointerX, pointerY);

			if (deeded || chosen) {
				g.setColor(new Color(66, 52, 18, 235));
				g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
				g.setColor(GOLD);
				g.setStroke(new BasicStroke(2.5f));
				g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
				drawCenteredText(g, slots[i].getDisplayName(), r.x + r.width / 2,
					r.y + r.height / 2 + 4, FONT_BODY, GOLD, true);
			}
			else {
				g.setColor(hovered ? new Color(46, 46, 54, 240) : new Color(30, 30, 36, 235));
				g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
				g.setColor(hovered ? new Color(200, 200, 210) : new Color(90, 90, 100));
				g.setStroke(new BasicStroke(hovered ? 2.5f : 1.6f));
				g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
				drawCenteredText(g, slots[i].getDisplayName(), r.x + r.width / 2,
					r.y + r.height - 10, FONT_SMALL,
					hovered ? Color.WHITE : new Color(150, 150, 160), false);
				drawMiniPadlock(g, r.x + r.width / 2, r.y + r.height / 2 - 8,
					hovered ? new Color(220, 220, 230) : new Color(120, 120, 130));
			}

			if (chosen) {
				drawDeedBurst(g, r, now - phaseStartMs);
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
		float u = (float) clamp01(t / (double) DEED_BURST_MS);
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		// rays
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setStroke(new BasicStroke(3f));
		for (int k = 0; k < 12; k++) {
			double ang = k * Math.PI / 6 + u * 0.9;
			double len = 20 + smoothstep(u) * 110;
			g2.setColor(Paint.withAlpha(GOLD, (1 - u) * 0.9f));
			g2.drawLine(cx + (int) (Math.cos(ang) * 12), cy + (int) (Math.sin(ang) * 12),
				cx + (int) (Math.cos(ang) * len), cy + (int) (Math.sin(ang) * len));
		}
		// expanding ring + sparks
		double ringR = smoothstep(u) * 90;
		g2.setColor(Paint.withAlpha(Color.WHITE, (1 - u) * 0.8f));
		g2.setStroke(new BasicStroke(2f + (1 - u) * 8f));
		g2.drawOval(cx - (int) ringR, cy - (int) ringR, (int) ringR * 2, (int) ringR * 2);
		double ts = t / 1000.0;
		for (int p = 0; p < 18; p++) {
			float h1 = Paint.hash01(p * 71 + 5);
			float h2 = Paint.hash01(p * 71 + 6);
			double ang = h1 * Math.PI * 2;
			double speed = 50 + h2 * 190;
			int px = cx + (int) (Math.cos(ang) * speed * ts);
			int py = cy + (int) (Math.sin(ang) * speed * ts + 260 * ts * ts);
			g2.setColor(Paint.withAlpha(GOLD, 1 - u));
			g2.fillRect(px, py, 3, 3);
		}
		g2.dispose();
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
		long total = fanfareDurationMs(fan.getSize());

		if (fanfareSounds == 0) {
			fanfareSounds = 1;
			switch (fan.getSize()) {
				case SMALL:
					break;
				case MEDIUM:
					break;
				default:
					break;
			}
		}

		if (fan.getSize() == CeremonyBus.Fanfare.Size.MEDIUM) {
			drawConfetti(g, cw, ch, el);
		}
		else if (fan.getSize() == CeremonyBus.Fanfare.Size.LARGE) {
			drawFireworks(g, cw, ch, el);
		}

		// ribbon banner slides in at the top
		double in = smoothstep(clamp01(el / 250.0));
		double out = smoothstep(clamp01((total - el) / 250.0));
		double slide = Math.min(in, out);
		int bannerH = fan.getDetail() != null && !fan.getDetail().isEmpty() ? 56 : 40;
		int y = (int) lerp(-bannerH - 8, 14, slide);

		g.setFont(FONT_BODY);
		FontMetrics fmT = g.getFontMetrics();
		int titleW = fmT.stringWidth(fan.getTitle() == null ? "" : fan.getTitle());
		g.setFont(FONT_SMALL);
		int detailW = fan.getDetail() == null ? 0 : g.getFontMetrics().stringWidth(fan.getDetail());
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
		g.drawString(fan.getTitle() == null ? "" : fan.getTitle(), textX, y + 22);
		if (fan.getDetail() != null && !fan.getDetail().isEmpty()) {
			g.setFont(FONT_SMALL);
			g.setColor(Color.WHITE);
			g.drawString(fan.getDetail(), textX, y + 40);
		}
	}

	private void drawConfetti(Graphics2D g, int cw, int ch, long el) {
		AffineTransform saved = g.getTransform();
		double ts = el / 1000.0;
		for (int p = 0; p < 36; p++) {
			float h1 = Paint.hash01(p * 91 + 1);
			float h2 = Paint.hash01(p * 91 + 2);
			float h3 = Paint.hash01(p * 91 + 3);
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
			if (t < 0) {
				continue;
			}
			int lx = (int) (cw * xFracs[m]);
			int burstY = (int) (ch * (0.30 + 0.06 * m));
			long riseMs = 480;
			if (t < riseMs) {
				// mortar streak
				double u = smoothstep(t / (double) riseMs);
				int y = (int) lerp(ch, burstY, u);
				g.setColor(Paint.withAlpha(Color.WHITE, 0.9f));
				g.fillRect(lx - 1, y, 3, 10);
				g.setColor(Paint.withAlpha(GOLD, 0.5f));
				g.fillRect(lx - 1, y + 10, 3, 18);
				continue;
			}
			long bt = t - riseMs;
			if (bt > 1000) {
				continue;
			}
			if (bt < 60 && fireFanfareBurst(m)) {
			}
			float u = bt / 1000f;
			double ts = bt / 1000.0;
			for (int p = 0; p < 26; p++) {
				float h1 = Paint.hash01(m * 811 + p * 3);
				float h2 = Paint.hash01(m * 811 + p * 3 + 1);
				double ang = h1 * Math.PI * 2;
				double speed = 60 + h2 * 220;
				int px = lx + (int) (Math.cos(ang) * speed * ts);
				int py = burstY + (int) (Math.sin(ang) * speed * ts + 190 * ts * ts);
				g.setColor(Paint.withAlpha((p % 3 == 0) ? Color.WHITE : GOLD, 1 - u));
				g.fillRect(px, py, 3, 3);
			}
		}
	}

	private boolean fireFanfareBurst(int m) {
		int mask = 1 << (m + 1);
		if ((fanfareSounds & mask) != 0) {
			return false;
		}
		fanfareSounds |= mask;
		return true;
	}

	// =====================================================================
	// utilities
	// =====================================================================

	private static double clamp01(double v) {
		return v <= 0 ? 0 : (v >= 1 ? 1 : v);
	}

	private static double smoothstep(double t) {
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	private static double easeOutCubic(double t) {
		t = clamp01(t);
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
		t = clamp01(t);
		double u = t - 1;
		final double c = 1.1;
		return 1 + (c + 1) * u * u * u + c * u * u;
	}

	private static double easeInCubic(double t) {
		t = clamp01(t);
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
		if (s == null || s.isEmpty()) {
			return "";
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String clipText(FontMetrics fm, String text, int maxWidth) {
		if (fm.stringWidth(text) <= maxWidth) {
			return text;
		}
		String drawn = text;
		while (fm.stringWidth(drawn) > maxWidth && drawn.length() > 3) {
			drawn = drawn.substring(0, drawn.length() - 2);
		}
		return drawn + "...";
	}

	private static void drawCenteredText(Graphics2D g, String text, int cx, int cy, Font font,
		Color color, boolean shadow) {
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
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
