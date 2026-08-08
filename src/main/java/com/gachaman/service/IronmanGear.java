package com.gachaman.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * Ironman armour is account-type identity gear: the game only lets you wear
 * the set matching your own account type. Granting every set to every profile
 * therefore handed a normal account eighteen cards it could never equip, and
 * parked an unwearable Hardcore ironman platebody in its one starter body slot.
 *
 * Varbit 1777 carries the account type. It is read raw rather than through
 * {@code client.getAccountType()} because unranked group ironman (value 6)
 * postdates RuneLite's AccountType enum, which stops at 5.
 */
public final class IronmanGear
{
	/** Account type varbit value for a non-ironman account. */
	public static final int NORMAL = 0;

	/**
	 * {helm, platebody, platelegs} indexed by the account type varbit value.
	 * Index {@link #BODY} of each entry is the piece the body slot takes.
	 */
	private static final String[][] SETS = {
		{}, // 0 normal — no identity armour
		{"Ironman helm", "Ironman platebody", "Ironman platelegs"},
		{"Ultimate ironman helm", "Ultimate ironman platebody", "Ultimate ironman platelegs"},
		{"Hardcore ironman helm", "Hardcore ironman platebody", "Hardcore ironman platelegs"},
		{"Group ironman helm", "Group ironman platebody", "Group ironman platelegs"},
		{"Hardcore group ironman helm", "Hardcore group ironman platebody",
			"Hardcore group ironman platelegs"},
		{"Unranked group ironman helm", "Unranked group ironman platebody",
			"Unranked group ironman platelegs"},
	};

	private static final int BODY = 1;

	private IronmanGear()
	{
	}

	/** The account type varbit, or {@link #NORMAL} if it cannot be read. */
	public static int accountType(Client client)
	{
		if (client == null)
		{
			return NORMAL;
		}
		try
		{
			return client.getVarbitValue(VarbitID.IRONMAN);
		}
		catch (Exception e)
		{
			return NORMAL;
		}
	}

	/**
	 * The armour cards this account type may actually wear — empty for a
	 * normal account, and empty for any future account type this build has
	 * never heard of (granting nothing beats granting the wrong set).
	 */
	public static List<String> cardNames(int accountType)
	{
		if (accountType <= NORMAL || accountType >= SETS.length)
		{
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(Arrays.asList(SETS[accountType]));
	}

	/** The platebody of this account's set, or null if there is none to assign. */
	public static String bodyCardName(int accountType)
	{
		List<String> set = cardNames(accountType);
		return set.isEmpty() ? null : set.get(BODY);
	}

	/**
	 * Every ironman armour name across all account types. The reconcile pass
	 * uses this to identify starter cards an account should never have been
	 * given, so it must stay exhaustive.
	 */
	public static Set<String> allCardNames()
	{
		Set<String> all = new LinkedHashSet<>();
		for (String[] set : SETS)
		{
			all.addAll(Arrays.asList(set));
		}
		return Collections.unmodifiableSet(all);
	}
}
