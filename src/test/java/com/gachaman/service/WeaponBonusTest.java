package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import org.junit.*;

/**
 * What a kill actually PAYS once the wheel names a preferred weapon category:
 * where the 1.5x sits in the multiplier chain, which kills may claim it, and
 * which may not.
 *
 * <p>Headless, on real in-memory state, a real StyleTracker (so the sampling
 * rule is genuinely exercised rather than stubbed out) and the real shipped
 * taxonomy. Two things are faked and only two: the player's combat level, by
 * overriding the one method that reads it, and a Client that answers the ironman
 * varbit — the award branch is the only place onKill touches a client, and the
 * nine older TaskService suites dodge it entirely by paying 0 per kill, which is
 * exactly the branch this file has to run.
 *
 * <p><b>Why every preference here is the spell-cast pseudo-type.</b> A real
 * weapon category resolves through the client's own DB table, which a headless
 * test has no way to answer, so {@code satisfies} would be false for every
 * category and every assertion below would pass for the wrong reason. The
 * autocast pseudo-type is decided by com mode alone and is therefore the one
 * preference that can be genuinely satisfied here. Nothing in the award path
 * cares which category it was — WeaponTypeServiceTest owns the resolution chain,
 * this file owns the arithmetic and the window.
 */
public class WeaponBonusTest
{
	/** EASY's base per-kill GC. The owner's pinned ladder is quoted in these. */
	private static final int BASE = Tuning.PER_KILL_GC.get(TaskDifficulty.EASY);
	private static final int PLAYER_CB = 50;
	/**
	 * One level BELOW the player, which is inside KILL_DIFF_GRACE and so pays the
	 * flat 1.0. Not an equal match: {@code killCbMultiplier} pays 1.1x at diff 0,
	 * and the ladder the owner chose is quoted against a level term of exactly 1.
	 */
	private static final int NPC_CB = PLAYER_CB - 1;
	/** The autocast slot; see the class comment for why the preference is this one. */
	private static final int AUTOCAST = 4;
	/** Any other com mode: a weapon being swung, which never satisfies spell-cast. */
	private static final int SWINGING = 0;

	private GachaStateService stateService;
	private ComplianceService complianceService;
	private StyleTracker styleTracker;
	private TaskService taskService;
	private RecordingConsignment consignment;
	private final List<TaskService.TaskCompletionSummary> completions = new ArrayList<>();
	/** Per-kill GC in the order it was credited, straight off the kill feedback. */
	private final List<Long> awards = new ArrayList<>();

	// --- Harness --------------------------------------------------------------

	private static GachaStateService inMemoryStateService()
	{
		StateStore store = new StateStore(null, null, null)
		{
			@Override
			public void save(GachaState state)
			{
			}

			@Override
			public void save(GachaState state, boolean flushDiskNow)
			{
			}

			@Override
			public GachaState load()
			{
				return null; // forces a fresh in-memory state
			}
		};
		GachaStateService service = new GachaStateService(store);
		service.load(3);
		return service;
	}

	/**
	 * The smallest Client {@link TaskService#onKill} can run against: it answers
	 * the ironman varbit and a type-appropriate zero for everything else.
	 *
	 * <p>A proxy rather than a hand-written stub because {@code Client} is
	 * hundreds of methods wide and this path needs exactly one of them. It is
	 * test-only: the plugin ships no reflection anywhere, which is a Plugin Hub
	 * rule about what is DISTRIBUTED, not about what verifies it.
	 */
	private static Client client(boolean ironman)
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				if ("getVarbitValue".equals(method.getName())
					&& args != null && Integer.valueOf(VarbitID.IRONMAN).equals(args[0]))
				{
					return ironman ? 1 : 0;
				}
				Class<?> type = method.getReturnType();
				// yields false / 0 / 0.0 / null for whatever the signature
				// promised, with no table of primitives to keep in sync
				return type == void.class ? null : Array.get(Array.newInstance(type, 1), 0);
			});
	}

	/**
	 * Stands in for the Consignment so the completion path can be asked WHO it
	 * handed a due roll to. It deliberately rolls nothing: a wheel that never
	 * turns is what tells "TaskService delegated" apart from "TaskService rolled
	 * the wheel itself", which is otherwise invisible.
	 */
	private static class RecordingConsignment extends ConsignmentService
	{
		private int calls;
		private int lastTick = -1;

		RecordingConsignment()
		{
			super(null, null, null, null, null);
		}

		@Override
		public boolean offerOrRoll(int currentTick)
		{
			calls++;
			lastTick = currentTick;
			return false;
		}
	}

	@Before
	public void setUp()
	{
		build(false);
	}

	private void build(boolean ironman)
	{
		awards.clear();
		completions.clear();
		stateService = inMemoryStateService();
		CreditSink creditSink = new CreditSink(stateService);
		complianceService = new ComplianceService(stateService, creditSink, null, null);
		CeremonyBus ceremonyBus = new CeremonyBus();
		StyleService styleService = StyleFixture.styleService(stateService, complianceService,
			ceremonyBus, new GachaRng(1L));
		styleTracker = new StyleTracker(null, null);
		consignment = new RecordingConsignment();
		taskService = new TaskService(client(ironman), stateService, creditSink, complianceService,
			styleService, ceremonyBus, new GachaRng(1L), MonsterTable.load(new Gson()),
			// quest unlocks and max hit are offer-roll collaborators; no offer is rolled here
			null, null,
			styleTracker, new WeaponTypeService(null, new Gson(), new GachaRng(1L)), consignment)
		{
			@Override
			public int playerCombatLevel()
			{
				return PLAYER_CB;
			}
		};
		taskService.addListener(new TaskService.Listener()
		{
			@Override
			public void onKillFeedback(TaskService.KillFeedback feedback)
			{
				awards.add(feedback.getGcAwarded());
			}

			@Override
			public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
			{
				completions.add(summary);
			}
		});
		Assert.assertEquals("the harness must carry no taint", 0, stateService.get().getTaint());
	}

	/** The wheel named the autocast slot; the state carries the key, as it does in play. */
	private void preferSpellCast()
	{
		stateService.mutate(s -> s.withPreferredWeaponType(WeaponTypeService.SPELL_CAST_KEY));
	}

	private void clearPreference()
	{
		stateService.mutate(s -> s.withPreferredWeaponType(null));
	}

	private void seedContract(int killsRequired, int perKillGc)
	{
		stateService.mutate(s -> s.withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.monsterCombatLevel(NPC_CB)
			.killsRequired(killsRequired)
			.killsDone(0)
			.perKillGc(perKillGc)
			.completionGc(100)
			.acceptedAtMs(1L)
			.build()));
	}

	private KillTracker.Kill kill(int tick, boolean assisted)
	{
		// engagement spans [tick - 3, tick], the same shape the other suites use
		return new KillTracker.Kill("Goblin", NPC_CB, 1, tick, tick - 3, false, 3, assisted, null);
	}

	/** One on-task kill at `tick`, with an attack sampled inside its own window. */
	private long killWith(int tick, int comMode)
	{
		styleTracker.recordWeapon(tick, 0, comMode);
		taskService.onKill(kill(tick, false));
		return awards.get(awards.size() - 1);
	}

	// --- A. the ladder the owner pinned ---------------------------------------

	@Test
	public void theLevelTermIsExactlyOneForThisPairing()
	{
		// guards every figure below: if this pairing ever stopped paying a flat
		// 1.0 the ladder would drift and the failure would look like a bonus bug
		Assert.assertEquals(1.0, Tuning.killCbMultiplier(PLAYER_CB, NPC_CB), 1e-9);
		Assert.assertEquals(4, BASE);
	}

	@Test
	public void theEasyComboLadderUnderTheWeaponBonus()
	{
		// 6, 8, 9, 11, 12, 14, 15 — LUMPY ON PURPOSE, and recorded here so it stays
		// a decision rather than becoming a bug report. Every base in PER_KILL_GC is
		// a multiple of 4 so the combo's quarter-steps land whole; 1.5 breaks that,
		// and only whole multipliers would not. The owner chose 1.5 knowing it.
		seedContract(200, BASE);
		preferSpellCast();
		List<Long> ladder = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			long paid = killWith(i * 2, AUTOCAST);
			if (i == 1 || i % 5 == 0)
			{
				ladder.add(paid);
			}
		}
		Assert.assertEquals(Arrays.asList(6L, 8L, 9L, 11L, 12L, 14L, 15L), ladder);
	}

	@Test
	public void theSameLadderWithoutTheBonusIsEven()
	{
		// the control that makes the lumpiness above legible: unpreferred, the same
		// thirty kills step 4, 5, 6, 7, 8, 9, 10 — whole GC all the way up
		seedContract(200, BASE);
		preferSpellCast();
		List<Long> ladder = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			long paid = killWith(i * 2, SWINGING);
			if (i == 1 || i % 5 == 0)
			{
				ladder.add(paid);
			}
		}
		Assert.assertEquals(Arrays.asList(4L, 5L, 6L, 7L, 8L, 9L, 10L), ladder);
	}

	// --- B. which kills may claim it ------------------------------------------

	@Test
	public void aWeaponSwappedInAfterTheDeathEarnsNothing()
	{
		// THE regression this feature was designed around. onKill runs several
		// ticks after the killing blow, so a varbit read there would report the
		// weapon the player swapped to during the loot-oracle window. The sample
		// that proves the preference was in hand lands at tick 11; the kill died at
		// 10 and spans [7, 10], so it is not this kill's evidence.
		seedContract(200, BASE);
		preferSpellCast();
		styleTracker.recordWeapon(11, 0, AUTOCAST);
		taskService.onKill(kill(10, false));
		Assert.assertEquals(BASE, awards.get(0).longValue());
	}

	@Test
	public void theBonusSurvivesFightingStraightOnToTheNextMonster()
	{
		// The other half of the same rule, and the one a single cached slot would
		// break: the player kills at tick 10 with the preferred weapon and is
		// already hitting the next monster at 11, 12, 13... while the kill sits in
		// KillTracker's loot-oracle queue. Those later attacks must not evict the
		// evidence for a kill that is still waiting to be credited.
		seedContract(200, BASE);
		preferSpellCast();
		styleTracker.recordWeapon(10, 0, AUTOCAST);
		for (int tick = 11; tick <= 25; tick++)
		{
			styleTracker.recordWeapon(tick, 0, SWINGING);
		}
		taskService.onKill(kill(10, false));
		Assert.assertEquals(6, awards.get(0).longValue());
	}

	@Test
	public void aKillWithNoJudgedAttackInsideItsWindowEarnsNothing()
	{
		// thralls, off-screen damage, a monster something else finished: nothing
		// was judged inside this engagement, so there is no evidence to pay on and
		// the last fight's weapon is not inherited
		seedContract(200, BASE);
		preferSpellCast();
		taskService.onKill(kill(10, false));
		Assert.assertEquals(BASE, awards.get(0).longValue());
	}

	@Test
	public void noPreferenceMeansNoBonusEvenHoldingTheRightThing()
	{
		// a fresh save, or a cycle the taxonomy could not name a category for:
		// null is simply no bonus, never an error and never a penalty
		seedContract(200, BASE);
		clearPreference();
		Assert.assertEquals(BASE, killWith(10, AUTOCAST));
	}

	@Test
	public void anUnrecognisedCategoryPaysTheORDINARYAwardRatherThanNothing()
	{
		// The owner's rule, stated as the number it has to be: a category the
		// taxonomy has never heard of pays 1x — the plain award, exactly as if the
		// feature were not installed. NOT zero, and NOT an exception on the kill
		// path, which is the way this would actually break a player's session.
		//
		// Garbage is fed deliberately: the varbit is six bits, so 0..63 is the
		// legal range and everything here is outside it or at the extremes. A
		// future Jagex category the JSON has not caught up with arrives looking
		// exactly like this, so it is the real case, not a synthetic one.
		// Asserted against a CONTROL run rather than against the flat base, because
		// the combo earns its first stack on the fifth kill and a flat expectation
		// would fail there for a reason that has nothing to do with weapons. What
		// has to hold is that the two runs are indistinguishable.
		int[] garbage = {64, 255, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};

		// SWINGING, not AUTOCAST: spell_cast is satisfied by the com mode alone and
		// would pay the bonus whatever the category is, hiding the thing under test.
		build(false);
		seedContract(200, BASE);
		preferSpellCast();
		List<Long> withGarbage = new ArrayList<>();
		for (int i = 0; i < garbage.length; i++)
		{
			styleTracker.recordWeapon((i + 1) * 2, garbage[i], SWINGING);
			taskService.onKill(kill((i + 1) * 2, false));
			withGarbage.add(awards.get(awards.size() - 1));
		}

		build(false);
		seedContract(200, BASE);
		clearPreference();
		List<Long> control = new ArrayList<>();
		for (int i = 0; i < garbage.length; i++)
		{
			styleTracker.recordWeapon((i + 1) * 2, garbage[i], SWINGING);
			taskService.onKill(kill((i + 1) * 2, false));
			control.add(awards.get(awards.size() - 1));
		}

		Assert.assertEquals("an unrecognised category must pay exactly what no"
			+ " preference at all pays", control, withGarbage);
		// and it is a real award, not a run of zeroes agreeing with itself
		Assert.assertEquals(Arrays.asList(4L, 4L, 4L, 4L, 5L), control);
	}

	// --- C. composition with the rest of the chain ----------------------------

	@Test
	public void aTaintedKillPaysNoWeaponBonus()
	{
		// Structural, not a rule anybody has to remember: the factor is applied
		// inside the non-tainted branch, and a tainted kill never reaches an award
		// at all. Pinned so a later refactor of that branch cannot quietly change
		// it — and pinned WITH the positive control below, because "tainted pays
		// nothing" would pass just as well if the bonus never worked at all.
		stateService.mutate(s -> s.withAllowedStyle(AttackStyle.MELEE.name()));
		seedContract(200, BASE);
		preferSpellCast();
		complianceService.onAttack(AttackStyle.MAGIC, 8); // convicts the kill at 10
		Assert.assertEquals(0, killWith(10, AUTOCAST));
		Assert.assertTrue("the kill must really have been tainted", stateService.get().getTaint() > 0);

		// the identical kill, uncontested: the bonus is alive and paying
		complianceService.resetTransient();
		stateService.mutate(s -> s.withTaint(0));
		Assert.assertEquals(6, killWith(20, AUTOCAST));
	}

	@Test
	public void theAssistHalvingLandsOnTopOfTheWeaponBonus()
	{
		// Both are multiplicative on the whole award, so the four combinations are
		// exactly base x 1 or 1.5, x 1 or 0.5. Asserting all four is what proves
		// they compose rather than one swallowing the other.
		build(true); // an ironman, so an assisted kill is only half theirs
		seedContract(200, BASE);
		clearPreference();
		Assert.assertEquals("plain", 4, killWith(2, AUTOCAST));
		preferSpellCast();
		Assert.assertEquals("preferred weapon", 6, killWith(4, AUTOCAST));
		clearPreference();
		styleTracker.recordWeapon(6, 0, AUTOCAST);
		taskService.onKill(kill(6, true));
		Assert.assertEquals("assisted", 2, awards.get(awards.size() - 1).longValue());
		preferSpellCast();
		styleTracker.recordWeapon(8, 0, AUTOCAST);
		taskService.onKill(kill(8, true));
		Assert.assertEquals("assisted, preferred weapon", 3,
			awards.get(awards.size() - 1).longValue());
	}

	@Test
	public void theBonusNeverTouchesTheCompletionReward()
	{
		// per-kill only. A completion bonus would be decided by whatever happened
		// to be equipped at the end, and could be had by swapping the preferred
		// weapon in for the final kill alone.
		seedContract(1, BASE);
		preferSpellCast();
		Assert.assertEquals(6, killWith(10, AUTOCAST));
		Assert.assertEquals(1, completions.size());
		Assert.assertEquals(100, completions.get(0).getCompletionGcAwarded());
	}

	// --- D. the Consignment owns a due roll -----------------------------------

	@Test
	public void aDueStyleRollGoesThroughTheConsignmentNotAroundIt()
	{
		// The Consignment may only be offered in the moment a roll comes due, so
		// this call site is the whole of its trigger. Routing it back through
		// styleService.roll would still change the style — nothing player-visible
		// would look broken — while quietly deleting the feature.
		stateService.mutate(s -> s.withAllowedStyle(AttackStyle.MELEE.name())
			.withCycleTarget(1)
			.withCycleProgress(0)
			.withStyleRolledAtMs(1L));
		seedContract(1, 0);
		taskService.onKill(kill(10, false));

		Assert.assertEquals("the cycle must really have tipped for this to mean anything",
			1, completions.size());
		Assert.assertTrue(completions.get(0).isCycleTriggered());
		Assert.assertEquals(1, consignment.calls);
		Assert.assertEquals("offered for the tick of the kill that finished the contract",
			10, consignment.lastTick);
		// this stand-in rolls nothing, so a style that moved could only have been
		// rolled by TaskService going around it
		Assert.assertEquals(AttackStyle.MELEE.name(), stateService.get().getAllowedStyle());
		Assert.assertEquals(1L, stateService.get().getStyleRolledAtMs());
	}

	@Test
	public void aContractThatDoesNotTipTheCycleOffersNothing()
	{
		// the once-a-day gate is not the only thing rationing the offer: it is only
		// ever put up when the wheel is actually due
		stateService.mutate(s -> s.withAllowedStyle(AttackStyle.MELEE.name())
			.withCycleTarget(5)
			.withCycleProgress(0));
		seedContract(1, 0);
		taskService.onKill(kill(10, false));
		Assert.assertFalse(completions.get(0).isCycleTriggered());
		Assert.assertEquals(0, consignment.calls);
	}
}
