package com.gachaman.service;

import com.google.gson.*;

/**
 * One way to build a {@link StyleService} for a headless test, so that the
 * wheel's two collaborators are wired the way Guice wires them in production.
 *
 * <p>It exists because of the RNG. The wheel draws the style and then the weapon
 * category from the SAME injected singleton, and a test that handed each service
 * its own {@code new GachaRng(seed)} would give both the identical stream —
 * every style would arrive paired with the category at the same index of its
 * pool, forever, and a test asserting the pairing looked random would be
 * asserting nothing. Passing one instance through here is what keeps a seeded
 * harness honest.
 *
 * <p>The {@code null} Client is safe and deliberate: {@link WeaponTypeService}
 * dereferences the client only to read the category DB table, which is the
 * kill-time lookup, and never to roll — the taxonomy it rolls from is the shipped
 * JSON resource. See that class's "which thread" note.
 */
final class StyleFixture
{
	private StyleFixture()
	{
	}

	/** A StyleService over the real shipped taxonomy, sharing {@code rng} with it. */
	static StyleService styleService(GachaStateService stateService,
		ComplianceService complianceService, CeremonyBus ceremonyBus, GachaRng rng)
	{
		return styleService(stateService, complianceService, ceremonyBus, rng,
			new WeaponTypeService(null, new Gson(), rng));
	}

	/**
	 * The same, with the taxonomy supplied — for a test that needs to hold the
	 * service it is asserting against, or to substitute one that names nothing.
	 */
	static StyleService styleService(GachaStateService stateService,
		ComplianceService complianceService, CeremonyBus ceremonyBus, GachaRng rng,
		WeaponTypeService weaponTypeService)
	{
		return new StyleService(stateService, complianceService, ceremonyBus, rng,
			weaponTypeService);
	}
}
