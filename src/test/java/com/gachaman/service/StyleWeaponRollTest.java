package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.persist.*;
import com.google.gson.*;
import org.junit.*;

/**
 * The wheel naming a weapon category alongside the style: that it happens in the
 * SAME write as the style, that it is drawn from the per-player RNG and never a
 * seeded one, and that the forced-style overload the Consignment needs commits
 * the same kind of roll the ordinary wheel does.
 *
 * <p>Real state, a real {@link WeaponTypeService} over the shipped taxonomy, and
 * a real {@link StyleService}. The only stub is the one collaborator that would
 * need a live client (the compliance service's chat/quest arms, which a null
 * client skips), because the thing under test is a persistence ordering and a
 * stubbed roll could not observe it.
 */
public class StyleWeaponRollTest
{
	/**
	 * Fixed so the ordinary wheel is predictable from a bare
	 * {@code new GachaRng(SEED)} — which is itself an invariant one of these tests
	 * pins, since ConsignmentServiceTest predicts the wheel exactly that way.
	 */
	private static final long SEED = 7L;

	private GachaStateService stateService;
	private GachaRng rng;
	private WeaponTypeService weaponTypes;
	private StyleService styleService;

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
	 * A taxonomy that can name nothing, standing in for the one failure the
	 * resource can actually have: the JSON missing or unparseable, which leaves
	 * every pool empty and {@code roll} returning null for every style.
	 */
	private static class NamesNothing extends WeaponTypeService
	{
		NamesNothing(GachaRng rng)
		{
			super(null, new Gson(), rng);
		}

		@Override
		public WeaponTypeService.WeaponType roll(AttackStyle style)
		{
			return null;
		}
	}

	/** Counts state writes; every real mutate notifies exactly once. */
	private static class WriteCounter implements GachaStateService.Listener
	{
		private int writes;

		@Override
		public void onStateChanged(GachaState newState)
		{
			writes++;
		}
	}

	/**
	 * Everything goes through {@link StyleFixture}, which is the harness every
	 * other StyleService test builds on — so a change that breaks the shared
	 * fixture fails here first, where the wheel itself is what is being asserted.
	 * One rng is shared with the taxonomy exactly as Guice shares the singleton.
	 */
	private StyleService serviceOver(WeaponTypeService types)
	{
		CreditSink creditSink = new CreditSink(stateService);
		ComplianceService compliance = new ComplianceService(stateService, creditSink, null, null);
		return StyleFixture.styleService(stateService, compliance, new CeremonyBus(), rng, types);
	}

	/** The fixture's own four-argument form: it builds the taxonomy itself. */
	private StyleService serviceOverTheShippedTaxonomy()
	{
		CreditSink creditSink = new CreditSink(stateService);
		ComplianceService compliance = new ComplianceService(stateService, creditSink, null, null);
		return StyleFixture.styleService(stateService, compliance, new CeremonyBus(), rng);
	}

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		rng = new GachaRng(SEED);
		weaponTypes = new WeaponTypeService(null, new Gson(), rng);
		styleService = serviceOver(weaponTypes);
	}

	private GachaState state()
	{
		return stateService.get();
	}

	/** An account past its first roll, so nothing under test is the firstEver path. */
	private void established()
	{
		stateService.mutate(s -> s.withAllowedStyle(AttackStyle.MELEE.name())
			.withPreferredWeaponType(null)
			.withFirstColoursChestOwed(false));
	}

	// --- The same-write requirement -------------------------------------------

	/**
	 * The requirement that a second write would break, and the only one that can
	 * be pinned at all: everything else about "the same mutate" is prose. A style
	 * committed in one write and a preference in another leaves a window where the
	 * new style is live under the previous cycle's category, and a crash there
	 * ships a preference the player can never satisfy.
	 */
	@Test
	public void aRollIsExactlyOneWriteToState()
	{
		established();
		WriteCounter counter = new WriteCounter();
		stateService.addListener(counter);

		StyleService.StyleRollResult result = styleService.roll(1);

		Assert.assertNotNull(result);
		Assert.assertEquals("the style and everything the wheel names beside it must land"
			+ " in ONE mutate — a second write is a window a crash can land in",
			1, counter.writes);
		Assert.assertNotNull("the shipped taxonomy must name something", result.getWeaponType());
		Assert.assertEquals("the result must announce what was persisted",
			result.getWeaponType().getKey(), state().getPreferredWeaponType());
	}

	/**
	 * The write is unconditional, null included. A guard that only wrote a
	 * category when it had one would leave the LAST cycle's preference live
	 * against a style it was never rolled for — the bonus would then pay on a
	 * weapon this cycle's wheel never named.
	 */
	@Test
	public void aRollThatNamesNothingCLEARSTheStaleCategory()
	{
		established();
		styleService.roll(1);
		String stale = state().getPreferredWeaponType();
		Assert.assertNotNull("precondition: a preference is live", stale);

		StyleService.StyleRollResult result = serviceOver(new NamesNothing(rng)).roll(2);

		Assert.assertNull("a roll that names no category must clear the previous one,"
			+ " never inherit it", state().getPreferredWeaponType());
		Assert.assertNull("and must say so in the result", result.getWeaponType());
	}

	// --- What gets named ------------------------------------------------------

	/**
	 * Over many rolls: the category is always one the rolled style's own wheel is
	 * allowed to name, and it always resolves back out of a save. The persisted
	 * form is a String, so a key that no longer resolves is indistinguishable from
	 * no preference at all — silently, and for the whole cycle.
	 */
	@Test
	public void theNamedCategoryAlwaysBelongsToTheRolledStyleAndSurvivesPersistence()
	{
		for (int seed = 0; seed < 300; seed++)
		{
			stateService = inMemoryStateService();
			rng = new GachaRng(seed);
			weaponTypes = new WeaponTypeService(null, new Gson(), rng);
			established();

			StyleService.StyleRollResult result = serviceOver(weaponTypes).roll(1);
			WeaponTypeService.WeaponType named = result.getWeaponType();

			Assert.assertNotNull("seed " + seed, named);
			Assert.assertTrue("the wheel may only name a category it is allowed to offer"
				+ " for the style it rolled (seed " + seed + ")",
				weaponTypes.pool(result.getRolled()).contains(named));
			Assert.assertTrue(named.isOfferable());

			// the round trip a save actually makes: key out, type back in
			String persisted = state().getPreferredWeaponType();
			Assert.assertEquals(named.getKey(), persisted);
			Assert.assertSame("a persisted key must resolve back through the taxonomy,"
				+ " or the whole cycle silently has no preference",
				named, weaponTypes.byKey(persisted));
			Assert.assertNotNull("and must be renderable without showing the key",
				weaponTypes.displayName(persisted));
		}
	}

	/**
	 * The style is still the FIRST number off the stream. Drawing the category
	 * first would shift it, and ConsignmentServiceTest predicts the ordinary wheel
	 * with a bare {@code new GachaRng(SEED).nextInt(3)} on an untouched copy of the
	 * seed — a prediction that would silently start naming the wrong style.
	 */
	@Test
	public void theStyleIsDrawnBeforeTheCategory()
	{
		established();
		AttackStyle predicted =
			AttackStyle.values()[new GachaRng(SEED).nextInt(AttackStyle.values().length)];

		Assert.assertEquals(predicted, styleService.roll(1).getRolled());
	}

	/** The bare overload is the forced one with nothing forced, and nothing else. */
	@Test
	public void rollWithoutAStyleIsRollWithANullStyle()
	{
		established();
		StyleService.StyleRollResult bare = styleService.roll(1);

		stateService = inMemoryStateService();
		rng = new GachaRng(SEED);
		established();
		StyleService.StyleRollResult explicit = serviceOverTheShippedTaxonomy().roll(1, null);

		Assert.assertEquals(bare.getRolled(), explicit.getRolled());
		Assert.assertEquals(bare.getWeaponType().getKey(), explicit.getWeaponType().getKey());
	}

	// --- The Consignment's deferred path --------------------------------------

	/**
	 * A forced roll is a whole roll: the house's style, a category rolled FOR that
	 * style, the cycle restarted and the clock stamped. This is the property that
	 * makes routing the Consignment through here worth the overload — a
	 * hand-written commit over there would set the first three and drop the fourth.
	 */
	@Test
	public void aForcedRollCommitsTheHousesStyleAndNamesACategoryForIt()
	{
		for (AttackStyle forced : AttackStyle.values())
		{
			stateService = inMemoryStateService();
			rng = new GachaRng(SEED);
			weaponTypes = new WeaponTypeService(null, new Gson(), rng);
			established();
			stateService.mutate(s -> s.withCycleProgress(4));

			StyleService.StyleRollResult result = serviceOver(weaponTypes).roll(1, forced);

			Assert.assertEquals(forced, result.getRolled());
			Assert.assertEquals(forced.name(), state().getAllowedStyle());
			Assert.assertTrue("the category must be rolled for the style the HOUSE named,"
				+ " not for the one the wheel would have",
				weaponTypes.pool(forced).contains(result.getWeaponType()));
			Assert.assertEquals(forced.name(), state().getAllowedStyle());
			Assert.assertEquals("a forced roll restarts the cycle like any other",
				0.0, state().getCycleProgress(), 0.0001);
			Assert.assertEquals(Tuning.CYCLE_TASKS, state().getCycleTarget());
			Assert.assertNotEquals(0L, state().getStyleRolledAtMs());
		}
	}

	/**
	 * The forced style is taken even when the wheel disagreed — the house names
	 * it, so a forced roll that quietly spun anyway would be selling the player a
	 * deal it does not honour.
	 */
	@Test
	public void aForcedRollDoesNotSpinTheWheel()
	{
		established();
		AttackStyle wheelWould =
			AttackStyle.values()[new GachaRng(SEED).nextInt(AttackStyle.values().length)];
		AttackStyle other = AttackStyle.values()[(wheelWould.ordinal() + 1) % 3];

		Assert.assertEquals(other, styleService.roll(1, other).getRolled());
		Assert.assertEquals(other.name(), state().getAllowedStyle());
	}

	// --- firstEver ------------------------------------------------------------

	/**
	 * The free First Colours chest is armed off the absent PREVIOUS style, not off
	 * the presence of a draw, so it survives the forced overload. It cannot happen
	 * in practice — ConsignmentService refuses to offer at all while
	 * {@code allowedStyle} is null, since an account with no album cannot be worst
	 * dressed — but the discriminator is what that exclusion is built on, and it is
	 * cheaper to pin it here than to discover it moved.
	 */
	@Test
	public void theFirstEverRollArmsTheFreeChestThroughEitherOverload()
	{
		Assert.assertNull("precondition: a fresh profile", state().getAllowedStyle());
		StyleService.StyleRollResult first = styleService.roll(1);
		Assert.assertNull("nothing preceded it", first.getPrevious());
		Assert.assertTrue(state().isFirstColoursChestOwed());

		stateService = inMemoryStateService();
		rng = new GachaRng(SEED);
		StyleService.StyleRollResult forced =
			serviceOverTheShippedTaxonomy().roll(1, AttackStyle.MAGIC);
		Assert.assertNull(forced.getPrevious());
		Assert.assertTrue("firstEver is the absent previous style, not the absent draw",
			state().isFirstColoursChestOwed());

		// and a SECOND roll never re-arms it, forced or not
		stateService.mutate(s -> s.withFirstColoursChestOwed(false));
		serviceOver(weaponTypes).roll(2);
		Assert.assertFalse(state().isFirstColoursChestOwed());
	}

	/** A roll on unloaded state is a no-op that returns null, both overloads. */
	@Test
	public void anUnloadedProfileRollsNothing()
	{
		stateService.unload();
		Assert.assertNull(styleService.roll(1));
		Assert.assertNull(styleService.roll(1, AttackStyle.RANGED));
	}
}
