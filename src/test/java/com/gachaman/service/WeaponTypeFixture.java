package com.gachaman.service;

import com.google.gson.*;

/**
 * A {@link WeaponTypeService} over the real shipped taxonomy, reachable from
 * OUTSIDE this package.
 *
 * <p>{@code StyleFixture} already builds one, but it and the service's own
 * constructor are both package-private, so a test in {@code com.gachaman.ui}
 * — where the taxonomy is rendered rather than rolled — cannot reach either.
 * This is the same object, exposed one visibility wider, and nothing else.
 *
 * <p>The null Client is safe for every method a panel calls: it is dereferenced
 * only to read the category DB table, which is the kill-time lookup.
 * {@code displayName}, {@code byKey}, {@code pool} and {@code roll} touch
 * nothing but the loaded JSON.
 */
public final class WeaponTypeFixture
{
	private WeaponTypeFixture()
	{
	}

	public static WeaponTypeService taxonomy()
	{
		return new WeaponTypeService(null, new Gson(), new GachaRng());
	}
}
