package com.gachaman.party;

import javax.annotation.Nullable;

/**
 * How a party roll picks the combat level it sizes contracts to.
 *
 * The HOST'S choice governs the whole roll. Not a majority, not each client's
 * own preference: the roll is seeded and must deal an identical board on every
 * screen, and two clients sizing to two different levels draw against two
 * different pools — which diverges from the first pick onward and would have
 * the party voting by INDEX on boards they never saw. One authority is the
 * only safe number of authorities, and the host is the one every client
 * already agrees on (see PartyRollService#proposerId).
 *
 * Transmitted by NAME on {@link PartyRollProposeMessage}, never by ordinal:
 * an older client omits the field and Gson leaves a String null, which
 * {@link #fromWire} reads as "unknown, use the default". An int would
 * deserialize to 0 and silently fabricate a real choice.
 */
public enum PartySizing
{
	/**
	 * The party's AVERAGE combat level. The default, and the only rule that
	 * existed before this setting.
	 */
	FIGHTING_WEIGHT("Fighting Weight"),
	/**
	 * The LOWEST combat level in the party — sizes every contract so the
	 * weakest member could have taken it alone.
	 */
	WEAKEST_MAN("Weakest Man");

	private final String label;

	PartySizing(String label)
	{
		this.label = label;
	}

	/**
	 * RuneLite renders enum config values through this; the constant names
	 * ({@code Fighting weight}, {@code Weakest man}) read acceptably too, so the
	 * dropdown is correct either way.
	 */
	@Override
	public String toString()
	{
		return label;
	}

	/**
	 * Parse a transmitted name, defaulting to {@link #FIGHTING_WEIGHT}.
	 *
	 * Every unrecognised input — null from an older client, an empty string, a
	 * constant from a future build, outright garbage — lands on the same value
	 * on every client, which is the only property that matters here. A roll
	 * that cannot agree on its rule is worse than a roll on the wrong rule.
	 */
	static PartySizing fromWire(@Nullable String name)
	{
		if (name == null)
		{
			return FIGHTING_WEIGHT;
		}
		for (PartySizing sizing : values())
		{
			if (sizing.name().equals(name))
			{
				return sizing;
			}
		}
		return FIGHTING_WEIGHT;
	}
}
