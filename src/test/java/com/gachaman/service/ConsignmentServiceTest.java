package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.data.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import java.util.*;
import org.junit.*;

/**
 * The Consignment's spend rules, headless, on real in-memory state and a real
 * StyleService — the only stubs are the two collaborators that would otherwise
 * need a live game client (the card database reads item stats, the chest service
 * rolls from it).
 *
 * <p>The four rows of the spend table are one test each, because they are the
 * subtlest part of the feature and each one fails differently: an over-charged
 * key silently costs the player a day, an under-charged one makes the once-a-day
 * gate meaningless.
 */
public class ConsignmentServiceTest
{
	/**
	 * Fixed so the ORDINARY wheel is predictable. Nothing asserts on the seed
	 * itself — the tests derive what this seed would roll and then arrange the
	 * album so the house names a different style, which is what makes
	 * "accept commits the HOUSE's style" provable rather than lucky.
	 */
	private static final long SEED = 7L;

	private static final int MELEE_CARD = 1001;
	private static final int RANGED_CARD = 2001;
	private static final int MAGIC_CARD = 3001;

	private GachaStateService stateService;
	private StyleService styleService;
	private StubChests chestService;
	private StubCards cardDatabase;
	private HoldingPresenter presenter;
	private ConsignmentService consignment;

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

	/** Card database with hand-written weapon pools; never touches ItemManager. */
	private static class StubCards extends CardDatabase
	{
		private final Map<AttackStyle, Set<Integer>> pools = new EnumMap<>(AttackStyle.class);
		private boolean dbReady = true;

		StubCards()
		{
			super(null, null, null, null, null);
		}

		@Override
		public boolean isReady()
		{
			return dbReady;
		}

		@Override
		public Set<Integer> weaponCardIdsForStyle(AttackStyle style)
		{
			return pools.getOrDefault(style, Collections.emptySet());
		}
	}

	/**
	 * Chest service that only knows how to take the money. The GC round trip is
	 * real — the service under test funds the crate and this spends it back — so
	 * the "a free crate costs the purse nothing" invariant is genuinely exercised
	 * rather than assumed.
	 */
	private static class StubChests extends ChestService
	{
		private final CreditSink sink;
		private Tuning.Chest openedTier;
		private boolean deliverable = true;

		StubChests(GachaStateService stateService, CreditSink sink)
		{
			super(stateService, sink, null, null, null, null, null, null, null);
			this.sink = sink;
		}

		@Override
		public synchronized ChestOpenResult openChest(Tuning.Chest tier)
		{
			if (!deliverable)
			{
				return null;
			}
			long price = Tuning.CHEST_PRICE_GC.get(tier);
			if (!sink.spend(price))
			{
				return null;
			}
			openedTier = tier;
			// trailing null is tollTierKey: this stand-in deals the Consignment's
			// ordinary Gilded crate, which is not a Toll pull and is confined to no
			// tier ladder
			return new ChestOpenResult(tier, tier, false, false, false, null, null,
				Collections.emptyList(), price, false, null);
		}
	}

	/** Claims the offer and then just holds it, so the test decides the answer. */
	private static class HoldingPresenter implements ConsignmentService.Presenter
	{
		private ConsignmentService.Offer shown;
		private int presentCalls;
		private boolean claim = true;

		@Override
		public boolean present(ConsignmentService.Offer offer)
		{
			presentCalls++;
			shown = offer;
			return claim;
		}
	}

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		CreditSink creditSink = new CreditSink(stateService);
		ComplianceService complianceService =
			new ComplianceService(stateService, creditSink, null, null);
		CeremonyBus ceremonyBus = new CeremonyBus();
		styleService = StyleFixture.styleService(stateService, complianceService, ceremonyBus,
			new GachaRng(SEED));
		chestService = new StubChests(stateService, creditSink);
		cardDatabase = new StubCards();
		presenter = new HoldingPresenter();
		consignment = new ConsignmentService(stateService, styleService, chestService,
			cardDatabase, ceremonyBus);
		consignment.setPresenter(presenter);

		cardDatabase.pools.put(AttackStyle.MELEE, new HashSet<>(Arrays.asList(MELEE_CARD)));
		cardDatabase.pools.put(AttackStyle.RANGED, new HashSet<>(Arrays.asList(RANGED_CARD)));
		cardDatabase.pools.put(AttackStyle.MAGIC, new HashSet<>(Arrays.asList(MAGIC_CARD)));
	}

	/** What the ordinary wheel would land on first, from an untouched copy of the seed. */
	private static AttackStyle wheelWouldRollFirst()
	{
		return AttackStyle.values()[new GachaRng(SEED).nextInt(AttackStyle.values().length)];
	}

	private static OwnedCard card(int cardId)
	{
		return new OwnedCard(UUID.randomUUID().toString(), cardId, null, Variant.NORMAL,
			0L, "test", 0);
	}

	/**
	 * An established account, already on a style, whose album is worst dressed
	 * for exactly one style — the one the seeded wheel would NOT have picked, so
	 * "the house named it" and "the wheel happened to land there" cannot be
	 * confused for one another.
	 */
	private AttackStyle seedEstablishedAccount()
	{
		AttackStyle wheel = wheelWouldRollFirst();
		AttackStyle house = null;
		for (AttackStyle style : AttackStyle.values())
		{
			if (style != wheel)
			{
				house = style;
				break;
			}
		}
		// every OTHER style gets a weapon card; the house's style gets none, so it
		// is strictly worst dressed and no tie-break is involved
		final AttackStyle named = house;
		List<OwnedCard> album = new ArrayList<>();
		for (AttackStyle style : AttackStyle.values())
		{
			if (style == named)
			{
				continue;
			}
			for (int id : cardDatabase.pools.get(style))
			{
				album.add(card(id));
			}
		}
		stateService.mutate(s -> s
			.withAllowedStyle(AttackStyle.MELEE.name())
			.withCycleTarget(Tuning.CYCLE_TASKS)
			.withCycleProgress(Tuning.CYCLE_TASKS)
			.withStyleRolledAtMs(1L)
			// wiped deliberately, and it is load-bearing: the delegation pin below
			// re-seeds AFTER taking a control roll, and a value left over from that
			// control would make the pin pass no matter what the accept path did
			.withPreferredWeaponType(null)
			.withOwnedCards(album)
			.withGc(0));
		return named;
	}

	// --- The spend table ------------------------------------------------------

	@Test
	public void acceptingSpendsTheDayKeyAndCommitsTheHouseStyle()
	{
		AttackStyle named = seedEstablishedAccount();

		Assert.assertTrue("the roll must be deferred, not taken", consignment.offerOrRoll(10));
		Assert.assertEquals(1, presenter.presentCalls);
		Assert.assertEquals(named, presenter.shown.getStyle());
		Assert.assertEquals(Tuning.Chest.GILDED, presenter.shown.getChestTier());
		Assert.assertTrue("a deferred roll must be recorded as owed",
			stateService.get().isStyleRollOwed());
		Assert.assertNull("the key is spent on RESOLUTION, never on the offer",
			stateService.get().getConsignmentDayKey());

		Assert.assertTrue(consignment.accept(11));

		GachaState after = stateService.get();
		Assert.assertEquals("the key is spent", consignment.currentDayKey(),
			after.getConsignmentDayKey());
		Assert.assertFalse("the roll has been taken", after.isStyleRollOwed());
		Assert.assertEquals("the HOUSE named the style, not the wheel", named.name(),
			after.getAllowedStyle());
		Assert.assertEquals("the cycle restarts like any other roll", 0.0,
			after.getCycleProgress(), 0.0001);
		Assert.assertEquals(Tuning.Chest.GILDED, chestService.openedTier);
		Assert.assertEquals("a free crate must cost the purse exactly nothing",
			0L, after.getGc());
		Assert.assertNull("the offer is resolved and gone", consignment.pendingOffer());
	}

	/**
	 * The regression pin for the one mistake that would be invisible: committing
	 * the house's style with a hand-written mutate instead of routing it through
	 * StyleService. A duplicated commit still sets the style, the cycle and the
	 * timestamp — so nothing else in this file would notice — but it silently
	 * drops whatever else the wheel names alongside the style (the preferred
	 * weapon type, and anything added after it).
	 *
	 * <p>Written as an agreement between the two paths rather than a bare
	 * non-null assertion, so it stays honest headless: if an ordinary roll cannot
	 * name a weapon type without a game client, neither can this one, and the
	 * test still fails the moment the two paths stop matching.
	 */
	@Test
	public void anAcceptedConsignmentRollsTHROUGHStyleServiceRatherThanAroundIt()
	{
		seedEstablishedAccount();

		// control: what an ordinary roll leaves behind on this same harness
		styleService.roll(1);
		boolean ordinaryNamesAWeaponType = stateService.get().getPreferredWeaponType() != null;
		int ordinaryCycleTarget = stateService.get().getCycleTarget();

		// put the cycle back where it was and run the offer for real
		AttackStyle named = seedEstablishedAccount();
		Assert.assertTrue(consignment.offerOrRoll(10));
		Assert.assertTrue(consignment.accept(11));

		GachaState after = stateService.get();
		Assert.assertEquals(named.name(), after.getAllowedStyle());
		Assert.assertEquals("the house's roll must be the same kind of roll as the wheel's",
			ordinaryCycleTarget, after.getCycleTarget());
		Assert.assertEquals("an accepted Consignment must go THROUGH StyleService.roll —"
				+ " a hand-written commit would drop whatever the wheel names beside the style",
			ordinaryNamesAWeaponType, after.getPreferredWeaponType() != null);
	}

	@Test
	public void decliningAlsoSpendsTheDayKeyAndTakesTheOrdinaryWheel()
	{
		seedEstablishedAccount();
		// a purse with something IN it, so "the refusal moved no money" is a real
		// assertion rather than 0 == 0: a decline that wrongly funded a crate it
		// never deals would be invisible against an empty purse
		stateService.mutate(s -> s.withGc(1234));
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		Assert.assertTrue(consignment.offerOrRoll(10));
		Assert.assertTrue(consignment.decline(11));

		GachaState after = stateService.get();
		Assert.assertEquals("being ASKED is what is rationed, so a refusal still pays",
			consignment.currentDayKey(), after.getConsignmentDayKey());
		Assert.assertFalse(after.isStyleRollOwed());
		Assert.assertNotEquals("the wheel must actually have turned", styleRolledBefore,
			after.getStyleRolledAtMs());
		Assert.assertEquals("the cycle restarts", 0.0, after.getCycleProgress(), 0.0001);
		Assert.assertNull("no crate on a refusal", chestService.openedTier);
		Assert.assertEquals("a refusal must not move the purse by a single coin",
			1234L, after.getGc());
	}

	/**
	 * Declining must NOT re-queue the way a deed choice does. A deed that
	 * re-queues costs nothing; an offer that re-queues is re-offerable at will,
	 * which is the whole once-per-day gate gone.
	 */
	@Test
	public void aDeclinedOfferIsNotOfferedAgainTheSameDay()
	{
		seedEstablishedAccount();
		Assert.assertTrue(consignment.offerOrRoll(10));
		Assert.assertTrue(consignment.decline(11));

		// the next contract completion brings another roll due
		stateService.mutate(s -> s.withCycleProgress(s.getCycleTarget()));
		Assert.assertFalse("the day key is spent — this roll goes straight to the wheel",
			consignment.offerOrRoll(20));
		Assert.assertEquals("the player must not be asked twice in one day",
			1, presenter.presentCalls);
	}

	/**
	 * Safe mode pulled the ceremony down when something hit the player. They
	 * never got to answer, so they are not charged — and the roll they were about
	 * to be given is still owed.
	 */
	@Test
	public void anOfferAbortedByCombatSpendsNothingAndLeavesTheRollOwed()
	{
		seedEstablishedAccount();
		Assert.assertTrue(consignment.offerOrRoll(10));
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		consignment.abandon();

		GachaState after = stateService.get();
		Assert.assertNull("an offer the player never answered must not be charged",
			after.getConsignmentDayKey());
		Assert.assertTrue("the roll is still owed", after.isStyleRollOwed());
		Assert.assertEquals("no wheel turned", styleRolledBefore, after.getStyleRolledAtMs());
		Assert.assertNull(consignment.pendingOffer());
		Assert.assertNull(chestService.openedTier);
	}

	/**
	 * The client died with the offer on screen. Nothing in memory survives, so
	 * the persisted owed flag is the only witness — and the login drain settles
	 * it by taking the ordinary wheel, never by asking again (a login is an
	 * arbitrary time, and this offer is only ever made in the moment a roll comes
	 * due). The day key stays unspent, so the player can still be asked properly
	 * the next time the cycle turns over today.
	 */
	@Test
	public void loggingOutMidOfferSpendsNothingAndTheLoginDrainTakesTheWheel()
	{
		seedEstablishedAccount();
		Assert.assertTrue(consignment.offerOrRoll(10));
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		// the process is gone: a brand-new service sees only what was persisted
		ConsignmentService afterRestart = new ConsignmentService(stateService, styleService,
			chestService, cardDatabase, new CeremonyBus());
		HoldingPresenter freshPresenter = new HoldingPresenter();
		afterRestart.setPresenter(freshPresenter);

		Assert.assertNull("nothing about the question survives the client",
			afterRestart.pendingOffer());
		Assert.assertTrue(stateService.get().isStyleRollOwed());
		Assert.assertNull(stateService.get().getConsignmentDayKey());

		afterRestart.drainOwedRoll(5);

		GachaState after = stateService.get();
		Assert.assertFalse("the drain must settle the owed roll", after.isStyleRollOwed());
		Assert.assertNotEquals("the wheel turned", styleRolledBefore, after.getStyleRolledAtMs());
		Assert.assertNull("an unanswered offer is never charged, not even on recovery",
			after.getConsignmentDayKey());
		Assert.assertEquals("the drain must never re-ask", 0, freshPresenter.presentCalls);
	}

	@Test
	public void theLoginDrainIsANoOpWhenNothingIsOwed()
	{
		seedEstablishedAccount();
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		consignment.drainOwedRoll(5);

		Assert.assertEquals("no owed flag means no roll to settle", styleRolledBefore,
			stateService.get().getStyleRolledAtMs());
	}

	// --- Exclusions -----------------------------------------------------------

	/**
	 * An account with no album cannot be "worst dressed" for anything, and the
	 * free First Colours chest already rides the first roll. {@code allowedStyle
	 * == null} is the same firstEver discriminator StyleService uses.
	 */
	@Test
	public void theFirstEverRollIsNeverAConsignment()
	{
		Assert.assertNull("precondition: a fresh profile has no style yet",
			stateService.get().getAllowedStyle());

		Assert.assertFalse("the first roll is taken, never sold",
			consignment.offerOrRoll(1));

		Assert.assertEquals(0, presenter.presentCalls);
		Assert.assertNotNull("the roll still happened", stateService.get().getAllowedStyle());
		Assert.assertNull(stateService.get().getConsignmentDayKey());
		Assert.assertFalse(stateService.get().isStyleRollOwed());
	}

	/**
	 * Nobody can put it on screen (headless, or the overlay is busy). The wheel
	 * spins exactly as it would have without this feature, and the day key is
	 * untouched — an offer that never reached the player is not an offer.
	 */
	@Test
	public void anOfferNobodyCanPresentCostsNothingAndStillRolls()
	{
		seedEstablishedAccount();
		presenter.claim = false;
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		Assert.assertFalse(consignment.offerOrRoll(10));

		GachaState after = stateService.get();
		Assert.assertNull(after.getConsignmentDayKey());
		Assert.assertFalse("nothing may be left owed", after.isStyleRollOwed());
		Assert.assertNotEquals("the wheel must still turn", styleRolledBefore,
			after.getStyleRolledAtMs());
	}

	@Test
	public void noOfferUntilTheCardDatabaseCanSayWhoIsWorstDressed()
	{
		seedEstablishedAccount();
		cardDatabase.dbReady = false;

		Assert.assertFalse(consignment.offerOrRoll(10));

		Assert.assertEquals(0, presenter.presentCalls);
		Assert.assertNull(stateService.get().getConsignmentDayKey());
		Assert.assertFalse(stateService.get().isStyleRollOwed());
	}

	// --- Delivery failure -----------------------------------------------------

	/**
	 * The crate could not be dealt after the key was already spent. The funding
	 * stays in the purse: nothing is invented as a consolation prize, we simply
	 * decline to claw back GC the player can now see for a crate we failed to
	 * hand over.
	 */
	@Test
	public void anUndeliverableCrateLeavesItsPriceInThePurse()
	{
		seedEstablishedAccount();
		chestService.deliverable = false;

		Assert.assertTrue(consignment.offerOrRoll(10));
		Assert.assertTrue(consignment.accept(11));

		Assert.assertEquals((long) Tuning.CHEST_PRICE_GC.get(Tuning.Chest.GILDED),
			stateService.get().getGc());
		Assert.assertEquals("the key is still spent — the player was asked and answered",
			consignment.currentDayKey(), stateService.get().getConsignmentDayKey());
	}

	// --- The self-healing property --------------------------------------------

	/**
	 * Why losing the deferred roll is safe rather than lossy: advanceCycle never
	 * resets cycleProgress — only roll() does — so a dropped deferred roll leaves
	 * the state cycle-OVERDUE and the next advanceCycle returns true again.
	 *
	 * <p>This is the load-bearing assumption behind every failure path in
	 * ConsignmentService, so it is pinned here directly against StyleService
	 * rather than inferred.
	 */
	@Test
	public void aDroppedDeferredRollLeavesTheCycleOverdue()
	{
		stateService.mutate(s -> s
			.withAllowedStyle(AttackStyle.MELEE.name())
			.withCycleTarget(2)
			.withCycleProgress(0));

		Assert.assertFalse("one contract in, not due yet", styleService.advanceCycle(null));
		Assert.assertTrue("second contract: the roll comes due", styleService.advanceCycle(null));

		// the offer was armed and then lost — a crash, a kill -9, a profile switch
		stateService.mutate(s -> s.withStyleRollOwed(true));
		stateService.mutate(s -> s.withStyleRollOwed(false)); // dropped without rolling

		Assert.assertTrue("the cycle is still overdue, so the wheel is re-owed automatically",
			styleService.advanceCycle(null));
		Assert.assertTrue("progress kept climbing past the target — only roll() ever resets it",
			stateService.get().getCycleProgress() > stateService.get().getCycleTarget());

		// and the moment a roll actually happens, the cycle finally clears
		styleService.roll(1);
		Assert.assertEquals(0.0, stateService.get().getCycleProgress(), 0.0001);
		Assert.assertFalse(styleService.advanceCycle(null));
	}

	// --- Guards ---------------------------------------------------------------

	@Test
	public void resolvingWithNoOfferLiveDoesNothing()
	{
		seedEstablishedAccount();
		long styleRolledBefore = stateService.get().getStyleRolledAtMs();

		Assert.assertFalse(consignment.accept(1));
		Assert.assertFalse(consignment.decline(1));
		consignment.abandon();

		Assert.assertEquals(styleRolledBefore, stateService.get().getStyleRolledAtMs());
		Assert.assertNull(stateService.get().getConsignmentDayKey());
		Assert.assertNull(chestService.openedTier);
	}

	@Test
	public void anOfferCannotBeAnsweredTwice()
	{
		seedEstablishedAccount();
		Assert.assertTrue(consignment.offerOrRoll(10));
		Assert.assertTrue(consignment.accept(11));

		Assert.assertFalse("a second answer must not deal a second crate",
			consignment.accept(12));
		Assert.assertFalse(consignment.decline(12));
		Assert.assertEquals("a free crate must still have cost the purse nothing",
			0L, stateService.get().getGc());
	}
}
