package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.model.*;
import java.util.*;
import org.junit.*;

import static com.gachaman.service.LoadoutFixture.*;

/**
 * The unassign guard: a card cannot leave a loadout slot while the item it
 * unlocks is still on the player's back.
 *
 * <p>The hole it closes exists with or without the Toll. Unassigning is the
 * only way to lose a permission you are in the middle of exercising, and
 * nothing re-reads equipment when the loadout changes — so before the guard a
 * player could clear a slot, keep wearing the item, and only be caught out
 * whenever some unrelated container change forced a permission rebuild.
 *
 * <p>The plugin may not take the gear off for them, ever, so every test here
 * asserts a REFUSAL and an unchanged loadout, never an unequip.
 */
public class UnassignGuardTest
{
	private GachaStateService stateService;
	private LoadoutFixture.Cards cards;

	@Before
	public void setUp()
	{
		stateService = inMemoryStateService();
		cards = new LoadoutFixture.Cards();
		deedEverySlot(stateService);
	}

	private LoadoutService serviceWearing(boolean oneCardPerSlot, int... wornItemIds)
	{
		GachamanConfig config = config(oneCardPerSlot);
		PermissionService permissions =
			new PermissionService(stateService, cards, null, config);
		Set<Integer> worn = new HashSet<>();
		for (int id : wornItemIds)
		{
			worn.add(id);
		}
		return service(stateService, cards, permissions, config, worn);
	}

	private String inLoadout(GearSlot slot)
	{
		return stateService.get().getLoadout().get(slot.name());
	}

	/**
	 * THE case that proves isForbidden would have been the wrong call.
	 *
	 * <p>A dragon hologram in the weapon slot is what permits the dragon
	 * scimitar, and isForbidden(dragon scimitar) answers FALSE right now —
	 * precisely because the hologram is still assigned. A guard built on it
	 * would wave this through and look like it worked.
	 */
	@Test
	public void aHologramRefusesWhileAnythingOfItsTierIsWorn()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		giveCards(stateService, holo);
		putInLoadout(stateService, GearSlot.WEAPON, holo);

		LoadoutService loadout = serviceWearing(true, itemId(GearSlot.WEAPON, "dragon"));
		PermissionService permissions =
			new PermissionService(stateService, cards, null, config(true));
		permissions.refresh();
		Assert.assertFalse("isForbidden answers false here, which is the whole point",
			permissions.isForbidden(itemId(GearSlot.WEAPON, "dragon")));

		Assert.assertFalse("wearing a dragon weapon must block the hologram leaving",
			loadout.unassign(GearSlot.WEAPON));
		Assert.assertEquals("a refusal changes nothing", holo.getUuid(),
			inLoadout(GearSlot.WEAPON));
	}

	/** A hologram grants its tier only in the slot it sits in, so it may leave others. */
	@Test
	public void aHologramInAnotherSlotIsNotBlockedByWeaponryOfItsTier()
	{
		OwnedCard holo = hologram(GearSlot.BODY, "dragon");
		giveCards(stateService, holo);
		putInLoadout(stateService, GearSlot.BODY, holo);

		LoadoutService loadout = serviceWearing(true, itemId(GearSlot.WEAPON, "dragon"));
		Assert.assertTrue("a dragon weapon is not what the BODY hologram is permitting",
			loadout.unassign(GearSlot.BODY));
		Assert.assertNull(inLoadout(GearSlot.BODY));
	}

	/**
	 * A shiny unlocks its whole family DOWN the ladder, so the worn item that
	 * blocks it need not be the one the card is named for.
	 */
	@Test
	public void aShinyRefusesWhileALowerTierItUnlocksIsWorn()
	{
		OwnedCard shiny = shiny(GearSlot.WEAPON, "rune");
		giveCards(stateService, shiny);
		putInLoadout(stateService, GearSlot.WEAPON, shiny);

		LoadoutService loadout = serviceWearing(true, itemId(GearSlot.WEAPON, "adamant"));
		Assert.assertFalse("a shiny rune card is what permits the adamant scimitar",
			loadout.unassign(GearSlot.WEAPON));
		Assert.assertEquals(shiny.getUuid(), inLoadout(GearSlot.WEAPON));
	}

	/** The same card unshiny permits only its own tier, so adamant does not hold it. */
	@Test
	public void aPlainCardIsHeldOnlyByItsOwnTier()
	{
		OwnedCard plain = plain(GearSlot.WEAPON, "rune");
		giveCards(stateService, plain);
		putInLoadout(stateService, GearSlot.WEAPON, plain);

		Assert.assertTrue("a plain rune card never permitted the adamant scimitar",
			serviceWearing(true, itemId(GearSlot.WEAPON, "adamant"))
				.unassign(GearSlot.WEAPON));

		putInLoadout(stateService, GearSlot.WEAPON, plain);
		Assert.assertFalse("its own tier does hold it",
			serviceWearing(true, itemId(GearSlot.WEAPON, "rune"))
				.unassign(GearSlot.WEAPON));
	}

	/**
	 * Cards merge item variants — kitted, ornamented, charged — and the guard
	 * has to see all of them. A canonical-id-only comparison would let an
	 * ornament kit stay on the player's back.
	 */
	@Test
	public void aMergedVariantOfTheSameCardHoldsItToo()
	{
		OwnedCard plain = plain(GearSlot.WEAPON, "rune");
		giveCards(stateService, plain);
		putInLoadout(stateService, GearSlot.WEAPON, plain);

		Assert.assertFalse("the ornamented variant is the same card's permission",
			serviceWearing(true, variantItemId(GearSlot.WEAPON, "rune"))
				.unassign(GearSlot.WEAPON));
	}

	/**
	 * With one-card-per-slot OFF, PermissionService.rebuildOwnershipOnly grants
	 * from OWNERSHIP alone: the loadout permits nothing, so clearing a slot
	 * takes nothing away and a refusal would be pure obstruction.
	 */
	@Test
	public void oneCardPerSlotOffNeverRefusesWhateverIsWorn()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		OwnedCard shiny = shiny(GearSlot.BODY, "rune");
		giveCards(stateService, holo, shiny);
		putInLoadout(stateService, GearSlot.WEAPON, holo);
		putInLoadout(stateService, GearSlot.BODY, shiny);

		LoadoutService loadout = serviceWearing(false,
			itemId(GearSlot.WEAPON, "dragon"), itemId(GearSlot.BODY, "adamant"));
		Assert.assertTrue(loadout.unassign(GearSlot.WEAPON));
		Assert.assertTrue(loadout.unassign(GearSlot.BODY));
		Assert.assertTrue(stateService.get().getLoadout().isEmpty());
	}

	/** Nothing on the player at all: every slot clears. */
	@Test
	public void anEmptyEquipmentContainerNeverRefuses()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		giveCards(stateService, holo);
		putInLoadout(stateService, GearSlot.WEAPON, holo);

		Assert.assertTrue(serviceWearing(true).unassign(GearSlot.WEAPON));
		Assert.assertNull(inLoadout(GearSlot.WEAPON));
	}

	/** Gear in OTHER slots is somebody else's permission and holds nothing here. */
	@Test
	public void unrelatedWornGearNeverRefuses()
	{
		OwnedCard weapon = plain(GearSlot.WEAPON, "rune");
		giveCards(stateService, weapon);
		putInLoadout(stateService, GearSlot.WEAPON, weapon);

		Assert.assertTrue(serviceWearing(true,
			itemId(GearSlot.BODY, "rune"), itemId(GearSlot.LEGS, "dragon"),
			itemId(GearSlot.AMMO, "adamant")).unassign(GearSlot.WEAPON));
		Assert.assertNull(inLoadout(GearSlot.WEAPON));
	}

	/** An already-empty slot is not a refusal — there is nothing to explain. */
	@Test
	public void anEmptySlotReportsSuccess()
	{
		Assert.assertTrue(serviceWearing(true, itemId(GearSlot.WEAPON, "dragon"))
			.unassign(GearSlot.WEAPON));
	}

	/** A null slot is a caller bug, not a guard refusal: no message is owed. */
	@Test
	public void aNullSlotReportsSuccess()
	{
		Assert.assertTrue(serviceWearing(true).unassign(null));
	}

	/** Take the item off and the same call goes through — the guard is a gate, not a lock. */
	@Test
	public void takingTheItemOffLetsTheCardLeave()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		giveCards(stateService, holo);
		putInLoadout(stateService, GearSlot.WEAPON, holo);

		Assert.assertFalse(serviceWearing(true, itemId(GearSlot.WEAPON, "dragon"))
			.unassign(GearSlot.WEAPON));
		Assert.assertTrue(serviceWearing(true).unassign(GearSlot.WEAPON));
		Assert.assertNull(inLoadout(GearSlot.WEAPON));
	}

	/** Every slot behaves the same way — the rule is general, not weapon-shaped. */
	@Test
	public void theGuardHoldsInEverySlot()
	{
		for (GearSlot slot : GearSlot.values())
		{
			GachaStateService state = inMemoryStateService();
			deedEverySlot(state);
			OwnedCard card = plain(slot, "rune");
			giveCards(state, card);
			putInLoadout(state, slot, card);

			GachamanConfig config = config(true);
			PermissionService permissions = new PermissionService(state, cards, null, config);
			LoadoutService loadout = service(state, cards, permissions, config,
				Collections.singleton(itemId(slot, "rune")));
			Assert.assertFalse(slot + " must refuse while its own gear is worn",
				loadout.unassign(slot));
			Assert.assertEquals(slot + " must keep its card", card.getUuid(),
				state.get().getLoadout().get(slot.name()));
		}
	}

	/**
	 * A KNOWN GAP, pinned so it cannot be mistaken for closed.
	 *
	 * <p>The guard covers {@link LoadoutService#unassign} because that is what
	 * was commissioned. Two other paths vacate a slot without going through
	 * it, and both leave the same worn item unpermitted:
	 *
	 * <ul>
	 * <li>MOVING a hologram — assign() clears its previous slot first, since a
	 * hologram may occupy only one.
	 * <li>OVERWRITING a slot — assign() replaces whatever was there, and the
	 * replacement may not permit what the old card did.
	 * </ul>
	 *
	 * <p>Closing them means guarding assign() as well, which changes the
	 * contract of a method whose other caller (the chatbox card search)
	 * discards its result and lives outside this change's file group. That is
	 * an owner's call, not this test's — so the current behaviour is asserted
	 * exactly, and this test flips the day it is made.
	 */
	@Test
	public void knownGap_movingOrOverwritingStillVacatesASlotUnguarded()
	{
		OwnedCard holo = hologram(GearSlot.WEAPON, "dragon");
		OwnedCard body = plain(GearSlot.BODY, "rune");
		giveCards(stateService, holo, body);
		putInLoadout(stateService, GearSlot.WEAPON, holo);

		LoadoutService loadout = serviceWearing(true, itemId(GearSlot.WEAPON, "dragon"));
		Assert.assertFalse("unassign is guarded", loadout.unassign(GearSlot.WEAPON));
		Assert.assertTrue("but moving the same hologram elsewhere is not",
			loadout.assign(GearSlot.BODY, holo.getUuid()));
		Assert.assertNull("...and the weapon slot is empty with the item still worn",
			inLoadout(GearSlot.WEAPON));
	}
}
