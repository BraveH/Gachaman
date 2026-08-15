package com.gachaman.service;

import java.util.Deque;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.google.gson.*;
import com.google.gson.reflect.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.eventbus.*;

/**
 * Judges the attack style of every offensive action, IMMEDIATELY at the
 * attack's own tick (kill tainting depends on the verdict existing before the
 * death event is processed — deferred judgement provably breaks it).
 *
 * Four signals, strongest first:
 *
 * 1. Spell-cast ANIMATIONS are unambiguous: a cast animation is magic no
 *    matter what the stance varps say. Utility animations (alch, teleport,
 *    eat, block) are equally unambiguously NOT attacks and are never judged.
 * 2. The "Cast <spell> -> <target>" menu click marks the upcoming attack on
 *    THAT target as magic — covering casts whose animation the client missed.
 *    The mark is bound to the clicked target, survives long pathing walks,
 *    and dies when the player clicks something else instead.
 * 3. The weapon-stance varps (COMBAT_WEAPON_CATEGORY + COM_MODE + autocast)
 *    judge everything else instantly on the attack animation.
 * 4. XP drops are ground truth for the style actually used — and the ONLY
 *    signal that can also RETRACT: a Magic XP drop arriving a few ticks after
 *    a stance-sourced melee/ranged verdict (spell XP is delayed by distance;
 *    melee XP is same-tick) proves the verdict wrong and pardons it.
 *    (Defence and Hitpoints are never used: Defence is shared by melee
 *    defensive, ranged longrange AND defensive casting.)
 *
 * Unresolvable styles are never judged — an API hiccup must never fine a
 * compliant player.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StyleTracker {
	private static final int LOGIN_SETTLE_TICKS = 5;
	/** Weapon categories whose WEAPON_STYLES enum entry is -1 (client special cases). */
	private static final int CAT_BLUE_MOON_SPEAR = 22;
	private static final int CAT_PARTISAN = 30;
	/**
	 * Absolute lifetime of a Cast-click mark. Target-binding makes a long cap
	 * safe: the old 6-tick TTL expired mid-path on long walks to the target,
	 * letting the eventual cast be judged from a melee stance instead.
	 */
	private static final int CAST_MARK_CAP_TICKS = 25;
	/** Fallback TTL for a mark whose target could not be resolved at click time. */
	private static final int CAST_MARK_UNBOUND_TTL_TICKS = 6;
	/** XP fallback only fires when no judgement happened this recently. */
	private static final int XP_FALLBACK_QUIET_TICKS = 2;
	/**
	 * How far back a Magic XP drop may retract a stance verdict. Spell XP
	 * lands 2-5 ticks after the cast (1 + (1+distance)/3 hit delay, +1
	 * processing); melee XP is same-tick, which is what makes the
	 * contradiction provable.
	 */
	private static final int PARDON_WINDOW_TICKS = 5;
	/**
	 * How long a weapon sample is kept.
	 *
	 * <p>Sized against the KILL path, not the pardon window, because that is who
	 * reads it: KillTracker holds every kill back for the loot oracle and emits it
	 * up to PENDING_TIMEOUT_TICKS (30) after the death. A sample has to outlive
	 * that whole window or the award would find nothing and quietly pay no bonus
	 * for a kill that genuinely earned one — and "quietly" is the problem, since a
	 * missing bonus looks exactly like a bonus the player never qualified for.
	 */
	private static final int WEAPON_SAMPLE_TTL_TICKS = 40;
	/**
	 * Hard cap, the same belt-and-braces {@link #MAX_RECENT_VERDICTS} is. At most
	 * one judgement lands per tick (the anim path refuses a second, and the XP
	 * fallback waits out XP_FALLBACK_QUIET_TICKS), so the TTL above already bounds
	 * this to TTL + 1 and the cap can only bite if that invariant ever breaks.
	 */
	private static final int MAX_WEAPON_SAMPLES = WEAPON_SAMPLE_TTL_TICKS + 1;

	/** What evidence produced a judgement. Pardons only ever touch STANCE. */
	enum JudgementSource {
		MARK, ANIM, STANCE, XP
	}

	/**
	 * What was in the player's hands at one judged attack, stamped with the tick
	 * it was read on.
	 *
	 * <p>The stamp is the whole value of this. A kill reaches the award code
	 * several ticks after the killing blow, so a varbit read there reports what is
	 * equipped NOW — and a player who swapped the preferred weapon in during the
	 * loot-oracle window would collect a bonus the fight never earned. Sampling at
	 * the attack and carrying the tick with it lets the award insist the sample
	 * belongs to the kill it is paying for.
	 *
	 * <p>comMode rides along because the taxonomy's one pseudo-type — casting from
	 * the autocast slot — is not a weapon category at all; see
	 * {@link WeaponTypeService#SPELL_CAST_KEY}. Read at the same instant as the
	 * category so the pair can never describe two different moments.
	 *
	 * <p>One known imprecision, accepted rather than papered over: the category
	 * varbit updates a tick BEHIND an equip (the same lag that makes the first
	 * cast after swapping to a staff read as melee, which is what the pardon
	 * machinery above exists to absorb), so a sample taken on the very tick of a
	 * swap can name the weapon just put away. It is one attack wide, it can only
	 * ever hand out a bonus rather than withhold one, and closing it would mean
	 * deferring the sample — which is exactly the late read this whole mechanism
	 * exists to avoid.
	 */
	@Value
	public static class WeaponSample {
		int tick;
		int category;
		int comMode;
	}

	/**
	 * How StyleTracker classifies an animation, loaded from attack-anims.json.
	 *
	 * <p>The ids are authored as {@link net.runelite.api.gameval.AnimationID}
	 * constants by com.gachaman.tools.AttackAnims, so the compiler still checks
	 * every name against the live API, and AttackAnimResourceTest pins the
	 * shipped resource to those lists. Data in a resource, names checked in
	 * code, and a test between them.
	 *
	 * <p>offensiveMagic: unambiguous spell casts — judge MAGIC whatever the
	 * stance says. neverJudge: provably not attacks (utility, consumables,
	 * blocks); these also do NOT consume a pending Cast mark. magicUtility: the
	 * neverJudge subset that pays Magic XP, whose drop must never grant a
	 * pardon.
	 */
	private Map<String, Set<Integer>> animGroups;

	/**
	 * Loaded on first judgement rather than at class-init: the Gson is
	 * injected, so it does not exist until Guice has built this service.
	 */
	private Set<Integer> anims(String group) {
		if (animGroups == null) {
			animGroups = DataJson.load(gson, "attack-anims",
				new TypeToken<Map<String, Set<Integer>>>() {
				}.getType(), Collections.emptyMap());
		}
		return animGroups.getOrDefault(group, Collections.emptySet());
	}

	public interface AttackListener {
		/** One offensive action, judged at its own tick. */
		void onAttack(AttackStyle style, int tick);

		/**
		 * A stance-sourced verdict at judgedTick was retracted: the delayed
		 * Magic XP drop proved the attack was actually a spell cast.
		 */
		default void onAttackPardoned(int judgedTick) {
		}
	}

	private final Client client;
	private final Gson gson;
	private final List<AttackListener> listeners = new ArrayList<>();

	private int tick;
	private int settleUntilTick;
	/**
	 * Tick of the most recent judgement, for the two places that only need
	 * "was anything judged just now": the one-verdict-per-tick guard and the
	 * XP fallback's quiet period. Pardons read {@link #recentVerdicts} instead.
	 */
	private int lastJudgedTick = -1;

	/** One judged attack, kept until it falls out of the pardon window. */
	@RequiredArgsConstructor
	static final class Verdict {
		final int tick;
		final AttackStyle style;
		final JudgementSource source;
		boolean pardoned;
	}

	/**
	 * Recent verdicts, oldest first.
	 *
	 * <p>This was a single slot, and a single slot cannot survive its own pardon
	 * window. Spell XP lands 2-5 ticks after the cast, and across a gap that wide
	 * the player has attacked again — every attack overwrote the slot. So the
	 * verdict the Magic XP was coming to retract had already been replaced, and the
	 * pardon either found a MAGIC verdict and declined (source is not STANCE) or
	 * retracted a later, innocent stance verdict in its place.
	 *
	 * <p>The first case is the "cast a spell and still get a tainted kill" report:
	 * COMBAT_WEAPON_CATEGORY updates a tick behind the equip, so the first cast
	 * after swapping to a staff is judged MELEE from the weapon just put away, and
	 * the Magic XP that would have cleared it arrives to find the SECOND cast — a
	 * clean ANIM MAGIC verdict — sitting in the slot. Nothing is pardoned, the
	 * melee verdict stands, and the kill is tainted by a spell.
	 *
	 * <p>Bounded twice: pruned past PARDON_WINDOW_TICKS on every push, and
	 * hard-capped, so a long fight cannot grow it.
	 */
	private final Deque<Verdict> recentVerdicts = new ArrayDeque<>();
	private static final int MAX_RECENT_VERDICTS = 8;
	/**
	 * Weapon samples, oldest first — a deque for exactly the reason
	 * {@link #recentVerdicts} is one, and it is worth spelling out because a
	 * single slot looks sufficient here and is not.
	 *
	 * <p>The reader is the kill award, which arrives 2-30 ticks after the death.
	 * By then the player has almost always started on the next monster, and every
	 * one of those attacks would have overwritten a single slot with a tick past
	 * the end of the finished kill's window. The bonus would then fail to pay on
	 * the ordinary case — fighting continuously — and pay only when the player
	 * stopped attacking after the kill, which is backwards.
	 */
	private final Deque<WeaponSample> weaponSamples = new ArrayDeque<>();
	/** Cast-click mark: when it was set and the actor it was aimed at. */
	private int castMarkTick = -1;
	private Actor castMarkTarget;
	/**
	 * Tick the local player last took a hitsplat. Incoming hits play a BLOCK
	 * animation on the player — indistinguishable from an attack by animation
	 * alone — which must never be judged (it both fined casters under fire
	 * and consumed the Cast-click magic mark before the real cast animated).
	 */
	private int lastDamagedTick = -1;
	/** Tick a Magic-XP-granting utility animation (alch etc.) last played. */
	private int lastUtilityMagicTick = -100;
	/** Tick of the last Attack/Strength/Ranged XP gain (confirms melee/ranged verdicts). */
	private int lastMeleeRangedXpTick = -1;

	// XP baselines so only GAINS count (login floods are settled separately)
	private long lastAttackXp = -1;
	private long lastStrengthXp = -1;
	private long lastRangedXp = -1;
	private long lastMagicXp = -1;

	public void addListener(AttackListener listener) {
		if (!listeners.contains(listener))
			listeners.add(listener);
	}

	public void removeListener(AttackListener listener) {
		listeners.remove(listener);
	}

	public int currentTick() {
		return tick;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			settleUntilTick = tick + LOGIN_SETTLE_TICKS;
			lastAttackXp = -1;
			lastStrengthXp = -1;
			lastRangedXp = -1;
			lastMagicXp = -1;
			clearCastMark();
			// a verdict from before the hop belongs to a fight that is over; the
			// login XP flood must not be able to pardon it
			recentVerdicts.clear();
			// same reasoning, and it keeps the invariant local rather than resting
			// on "the tick counter never resets, so old samples fall out of every
			// future window anyway" — true today, and a trap for whoever changes it
			weaponSamples.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		tick++;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) {
		if (event.getActor() == client.getLocalPlayer())
			lastDamagedTick = tick;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		MenuAction action = event.getMenuAction();
		if ((action == MenuAction.WIDGET_TARGET_ON_NPC || action == MenuAction.WIDGET_TARGET_ON_PLAYER)
			&& event.getMenuOption() != null && event.getMenuOption().startsWith("Cast")) {
			// a manual spell cast was just clicked — the upcoming attack on
			// THIS target is magic (a newer Cast click simply re-marks)
			castMarkTick = tick;
			castMarkTarget = event.getMenuEntry() == null ? null : event.getMenuEntry().getActor();
			return;
		}
		if (castMarkTick >= 0 && supersedesCast(action)) {
			// the player changed their mind — walked away, attacked something
			// the normal way, picked something up — the pending cast is dead
			clearCastMark();
		}
	}

	/** Clicks that abandon a pending manual cast (widget clicks — eat, spellbook — do not). */
	private static boolean supersedesCast(MenuAction action) {
		if (action == null)
			return false;
		if (action == MenuAction.WALK)
			return true;
		String name = action.name();
		return name.startsWith("NPC_") || name.startsWith("PLAYER_") || name.startsWith("GROUND_ITEM_");
	}

	private boolean castMarkActiveOn(Actor interacting) {
		if (castMarkTick < 0 || tick - castMarkTick > CAST_MARK_CAP_TICKS)
			return false;
		if (castMarkTarget == null) {
			// target unresolvable at click time — fall back to the old short
			// TTL against whatever we are now interacting with
			return interacting != null && tick - castMarkTick <= CAST_MARK_UNBOUND_TTL_TICKS;
		}
		return castMarkTarget == interacting;
	}

	private void clearCastMark() {
		castMarkTick = -1;
		castMarkTarget = null;
	}

	/**
	 * Signals 1-3: the attack animation, judged instantly. Unambiguous cast
	 * animations beat everything; the target-bound Cast mark beats the stance
	 * varps. Judging here — not at end of tick — is what lets a final blow
	 * taint its own kill.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event) {
		if (event.getActor() != client.getLocalPlayer() || tick < settleUntilTick || tick == lastJudgedTick)
			return;
		int animation = client.getLocalPlayer().getAnimation();
		Actor interacting = client.getLocalPlayer().getInteracting();
		if (animation == -1 || interacting == null)
			return;
		if (anims("neverJudge").contains(animation)) {
			// utility cast / consumable / block pose — not an attack, and the
			// pending Cast mark (if any) survives untouched
			if (anims("magicUtility").contains(animation))
				lastUtilityMagicTick = tick;
			return;
		}
		boolean markMatch = castMarkActiveOn(interacting);
		if (anims("offensiveMagic").contains(animation)) {
			// unambiguous spell cast: magic no matter the stance, and provably
			// not a block animation — so it is judged even inside the damage
			// window below
			logVerdict(animation, AttackStyle.MAGIC, JudgementSource.ANIM, markMatch);
			clearCastMark();
			judge(AttackStyle.MAGIC, JudgementSource.ANIM);
			return;
		}
		if (tick - lastDamagedTick <= 1) {
			// almost certainly the BLOCK animation from the hit we just took —
			// never judge it (the XP path still catches a real wrong-style
			// attack that lands damage on the same tick)
			return;
		}
		if (markMatch) {
			logVerdict(animation, AttackStyle.MAGIC, JudgementSource.MARK, true);
			clearCastMark();
			judge(AttackStyle.MAGIC, JudgementSource.MARK);
			return;
		}
		AttackStyle style = predictStyle();
		if (style != null) {
			logVerdict(animation, style, JudgementSource.STANCE, false);
			judge(style, JudgementSource.STANCE);
		}
	}

	/**
	 * Signal 4: XP is ground truth for the style actually used. It fires as a
	 * fallback judgement only when nothing was judged in the last
	 * {@link #XP_FALLBACK_QUIET_TICKS} ticks (so it can never double-judge an
	 * attack the animation path already handled) — but a Magic XP drop also
	 * checks whether it CONTRADICTS a recent stance verdict and pardons it.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event) {
		AttackStyle xpStyle;
		long previous;
		long current = event.getXp();
		switch (event.getSkill()) {
			case ATTACK:
				previous = lastAttackXp;
				lastAttackXp = current;
				xpStyle = AttackStyle.MELEE;
				break;
			case STRENGTH:
				previous = lastStrengthXp;
				lastStrengthXp = current;
				xpStyle = AttackStyle.MELEE;
				break;
			case RANGED:
				previous = lastRangedXp;
				lastRangedXp = current;
				xpStyle = AttackStyle.RANGED;
				break;
			case MAGIC:
				previous = lastMagicXp;
				lastMagicXp = current;
				xpStyle = AttackStyle.MAGIC;
				break;
			default:
				return; // Defence/HP are shared across styles — never evidence
		}
		if (previous < 0 || current <= previous || tick < settleUntilTick) {
			return; // baseline settle or no gain
		}
		if (xpStyle == AttackStyle.MAGIC)
			maybePardonStanceVerdict();
		else {
			lastMeleeRangedXpTick = tick;
		}
		if (lastJudgedTick >= 0 && tick - lastJudgedTick <= XP_FALLBACK_QUIET_TICKS) {
			return; // the animation path already judged this attack
		}
		log.debug("style verdict: tick={} skill={} verdict={} source=XP", tick, event.getSkill(), xpStyle);
		judge(xpStyle, JudgementSource.XP);
	}

	/**
	 * A Magic XP gain just landed: if the last verdict was a stance-sourced
	 * melee/ranged within the pardon window, unconfirmed by any melee/ranged
	 * XP, and not explained by a utility cast, that verdict was wrong — the
	 * "attack" was a manual cast judged from a stale melee stance. Retract it.
	 */
	private void maybePardonStanceVerdict() {
		Verdict verdict = pardonTarget(recentVerdicts, tick, lastMeleeRangedXpTick,
			lastUtilityMagicTick);
		if (verdict == null)
			return;
		verdict.pardoned = true;
		log.debug("style pardon: retracting {} verdict from tick {} (magic xp at tick {})",
			verdict.style, verdict.tick, tick);
		Listeners.fire(listeners, l -> l.onAttackPardoned(verdict.tick), "attack listener failed");
	}

	/**
	 * Which of the recent verdicts a Magic XP drop retracts, or null for none.
	 *
	 * <p>Oldest candidate first, {@code recent} being in judgement order. Magic XP
	 * is the DELAYED signal, so among the verdicts still inside the window the
	 * earliest is the one the cast produced; taking them in order also means two
	 * casts in quick succession pardon two verdicts in the order they were judged
	 * rather than both reaching for the newest. One XP drop retracts at most one.
	 *
	 * <p>Pure and static for the same reason {@link #shouldPardon} is: the tracker
	 * around it needs a live Client, so choosing ACROSS verdicts could not otherwise
	 * be tested — and choosing across verdicts is the whole of the fix.
	 */
	@Nullable
	static Verdict pardonTarget(Iterable<Verdict> recent, int tick, int lastMeleeRangedXpTick,
		int lastUtilityMagicTick) {
		for (Verdict verdict : recent) {
			if (shouldPardon(verdict.source, verdict.style, verdict.pardoned, verdict.tick,
				tick, lastMeleeRangedXpTick, lastUtilityMagicTick)) {
				return verdict;
			}
		}
		return null;
	}

	/**
	 * Pure pardon decision (unit-testable). Only STANCE verdicts are ever
	 * pardonable: MARK/ANIM verdicts are already magic and XP verdicts are
	 * ground truth. Melee/ranged XP at or after the verdict confirms it as
	 * genuine (melee XP is same-tick — an auto-retaliate staff bash that lands
	 * is a REAL melee attack and stays convicted), and Magic XP right after a
	 * utility cast proves nothing about combat.
	 */
	static boolean shouldPardon(JudgementSource source, AttackStyle style, boolean alreadyPardoned,
		int judgedTick, int tick, int lastMeleeRangedXpTick, int lastUtilityMagicTick) {
		return source == JudgementSource.STANCE
			&& (style == AttackStyle.MELEE || style == AttackStyle.RANGED)
			&& !alreadyPardoned
			&& judgedTick >= 0
			&& tick - judgedTick <= PARDON_WINDOW_TICKS
			&& lastMeleeRangedXpTick < judgedTick
			&& tick - lastUtilityMagicTick > 1;
	}

	private void judge(AttackStyle style, JudgementSource source) {
		lastJudgedTick = tick;
		sampleWeapon();
		// prune first, so the cap can only ever discard verdicts that are still
		// young enough to matter — and at one judgement per tick over a 5-tick
		// window it never reaches the cap in the first place
		while (!recentVerdicts.isEmpty()
			&& tick - recentVerdicts.peekFirst().tick > PARDON_WINDOW_TICKS) {
			recentVerdicts.pollFirst();
		}
		recentVerdicts.addLast(new Verdict(tick, style, source));
		while (recentVerdicts.size() > MAX_RECENT_VERDICTS) {
			recentVerdicts.pollFirst();
		}
		fire(style, tick);
	}

	private void fire(AttackStyle style, int judgedTick) {
		Listeners.fire(listeners, l -> l.onAttack(style, judgedTick), "attack listener failed");
	}

	/**
	 * Record what the attack judged this tick was made with.
	 *
	 * <p>Called from {@link #judge} and deliberately NOT from
	 * {@link #predictStyle}: the ANIM and MARK branches of
	 * {@link #onAnimationChanged} both judge and return before predictStyle is
	 * ever reached, so a spell-cast kill — the one whose entire verdict comes from
	 * the animation — would never sample its own weapon and could never earn the
	 * preference it was fought with.
	 *
	 * <p>On the XP-fallback path the judged tick is the tick the XP LANDED, which
	 * for a spell is a few ticks after the cast. That is still the right evidence
	 * to keep — it is what the player was holding while that attack resolved, and
	 * it is the only reading available for an attack the animation path never saw.
	 *
	 * <p>A read that throws records NOTHING rather than a placeholder, and both
	 * reads have to succeed before anything is pushed (the arguments are evaluated
	 * before the call). That is the same direction every other unresolvable case
	 * in this file takes: an unsampled attack simply pays no weapon bonus, and the
	 * bonus is only ever additive to the player's fortunes, so failing to pay it
	 * is a non-event where paying it for a weapon nobody can prove was in hand
	 * would make the preference meaningless.
	 */
	private void sampleWeapon() {
		try {
			recordWeapon(tick, client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY),
				client.getVarpValue(VarPlayerID.COM_MODE));
		}
		catch (Exception ignored) {
			// no client, or the varbit is unreadable — this attack goes unsampled
		}
	}

	/**
	 * Push one sample, pruning by age first and capping second — the same order
	 * {@link #judge} prunes its verdicts in, and for the same reason: the cap can
	 * then only ever discard entries that are already too old to matter.
	 *
	 * <p>Package-private, and separated from the client read above, so the
	 * retention rule that kills actually depend on can be exercised without a live
	 * Client — exactly why {@link #resolve} and {@link #shouldPardon} are pure
	 * statics.
	 */
	void recordWeapon(int atTick, int category, int comMode) {
		while (!weaponSamples.isEmpty()
			&& atTick - weaponSamples.peekFirst().getTick() > WEAPON_SAMPLE_TTL_TICKS) {
			weaponSamples.pollFirst();
		}
		weaponSamples.addLast(new WeaponSample(atTick, category, comMode));
		while (weaponSamples.size() > MAX_WEAPON_SAMPLES) {
			weaponSamples.pollFirst();
		}
	}

	/**
	 * What was in hand at the last judged attack inside [fromTick, toTick], or
	 * null when no attack this tracker judged falls in that window.
	 *
	 * <p>Callers pass a kill's own [engagementStartTick, deathTick]. The NEWEST
	 * match is returned because that is the killing blow — a player who swings
	 * their preferred weapon for the last hit has genuinely landed the kill with
	 * it, while one who swaps it in after the death lands outside the window and
	 * gets nothing.
	 *
	 * <p>Null for a kill with no judged attack inside its own engagement — a
	 * thrall's kill, damage dealt off-screen, a monster something else finished —
	 * rather than the category from whatever fight came before it. That is what
	 * makes the rule airtight instead of merely usually right.
	 */
	@Nullable
	public WeaponSample weaponAt(int fromTick, int toTick) {
		WeaponSample best = null;
		for (WeaponSample sample : weaponSamples) {
			if (sample.getTick() >= fromTick && sample.getTick() <= toTick) {
				best = sample; // pushed oldest-first, so the last match is the newest
			}
		}
		return best;
	}

	private void logVerdict(int animation, AttackStyle verdict, JudgementSource source, boolean markMatch) {
		if (!log.isDebugEnabled())
			return;
		int category = -1;
		int comMode = -1;
		try {
			category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
			comMode = client.getVarpValue(VarPlayerID.COM_MODE);
		}
		catch (Exception ignored) {
		}
		log.debug("style verdict: tick={} anim={} category={} comMode={} verdict={} source={}"
				+ " markTick={} markMatch={} lastDamagedTick={}",
			tick, animation, category, comMode, verdict, source, castMarkTick, markMatch, lastDamagedTick);
	}

	/** The style the next attack would use, or null when unresolvable. */
	@Nullable
	public AttackStyle predictStyle() {
		try {
			int category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
			int comMode = client.getVarpValue(VarPlayerID.COM_MODE);
			boolean defensiveAutocast = client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE) == 1;
			return resolve(category, comMode, defensiveAutocast, this::styleNameFor);
		}
		catch (Exception e) {
			return null;
		}
	}

	/** Lookup of the style-name param for (category, styleIndex); null when absent. */
	@Nullable
	private String styleNameFor(int category, int styleIndex) {
		EnumComposition weaponStyles = client.getEnum(EnumID.WEAPON_STYLES);
		int structsEnumId = weaponStyles.getIntValue(category);
		if (structsEnumId == -1)
			return null;
		EnumComposition structs = client.getEnum(structsEnumId);
		int[] structIds = structs.getIntVals();
		if (styleIndex < 0 || styleIndex >= structIds.length)
			return null;
		StructComposition struct = client.getStructComposition(structIds[styleIndex]);
		return struct == null ? null : struct.getStringValue(ParamID.ATTACK_STYLE_NAME);
	}

	interface StyleNameLookup {
		@Nullable
		String get(int category, int styleIndex);
	}

	/**
	 * Pure resolution function (unit-testable). comMode 4 = the autocast SLOT
	 * (an explicitly selected autocast spell). Powered staves (trident,
	 * sanguinesti, warped sceptre...) do NOT use it — they fight from comMode
	 * 0/1/3 — so they are identified by probing style 0: a category whose
	 * first style is "Casting" is all-magic in every mode.
	 */
	@Nullable
	static AttackStyle resolve(int category, int comMode, boolean defensiveAutocast, StyleNameLookup lookup) {
		if (comMode == 4) {
			// the autocast slot; defensiveAutocast irrelevant to style
			return AttackStyle.MAGIC;
		}
		if (category == CAT_BLUE_MOON_SPEAR || category == CAT_PARTISAN) {
			// client special-cases these; their non-autocast styles are melee
			return AttackStyle.MELEE;
		}
		if ("Casting".equals(lookup.get(category, 0))) {
			// powered stave: every combat mode of this category is a cast
			return AttackStyle.MAGIC;
		}
		String name = lookup.get(category, comMode);
		return name == null ? null : mapStyleName(name);
	}

	@Nullable
	static AttackStyle mapStyleName(String name) {
		switch (name) {
			case "Accurate":
			case "Aggressive":
			case "Defensive":
			case "Controlled":
				return AttackStyle.MELEE;
			case "Ranging":
			case "Longrange":
				return AttackStyle.RANGED;
			case "Casting":
			case "Defensive Casting":
				return AttackStyle.MAGIC;
			default:
				return null;
		}
	}
}
