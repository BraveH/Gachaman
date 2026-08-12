package com.gachaman.service;

import com.gachaman.data.*;
import com.google.gson.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;

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
@Singleton
public final class IronmanGear {
	/** Account type varbit value for a non-ironman account. */
	public static final int NORMAL = 0;

	/**
	 * {helm, platebody, platelegs} indexed by the account type varbit value,
	 * from ironman-gear.json. Index {@link #BODY} of each entry is the piece
	 * the body slot takes.
	 */
	private static class Table {
		List<List<String>> sets = Collections.emptyList();
	}

	private final List<List<String>> sets;

	@Inject
	IronmanGear(Gson gson) {
		this.sets = DataJson.load(gson, "ironman-gear", Table.class, new Table()).sets;
	}

	private static final int BODY = 1;

	/** The account type varbit, or {@link #NORMAL} if it cannot be read. */
	public static int accountType(Client client) {
		if (client == null) {
			return NORMAL;
		}
		try {
			return client.getVarbitValue(VarbitID.IRONMAN);
		}
		catch (Exception e) {
			return NORMAL;
		}
	}

	/**
	 * The armour cards this account type may actually wear — empty for a
	 * normal account, and empty for any future account type this build has
	 * never heard of (granting nothing beats granting the wrong set).
	 */
	public List<String> cardNames(int accountType) {
		if (accountType <= NORMAL || accountType >= sets.size()) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(sets.get(accountType));
	}

	/** The platebody of this account's set, or null if there is none to assign. */
	public String bodyCardName(int accountType) {
		List<String> set = cardNames(accountType);
		return set.isEmpty() ? null : set.get(BODY);
	}

	/**
	 * Every ironman armour name across all account types. The reconcile pass
	 * uses this to identify starter cards an account should never have been
	 * given, so it must stay exhaustive.
	 */
	public Set<String> allCardNames() {
		Set<String> all = new LinkedHashSet<>();
		for (List<String> set : sets) {
			all.addAll(set);
		}
		return Collections.unmodifiableSet(all);
	}
}
