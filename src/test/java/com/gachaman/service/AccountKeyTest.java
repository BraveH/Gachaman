package com.gachaman.service;

import java.util.*;
import org.junit.*;

/**
 * The party layer's identity, and the trust boundary around it.
 *
 * The key is the one thing in the party protocol that is claimed rather than
 * observed, so the tests that matter most here are the negative ones: what a
 * hostile or broken client can put on the wire must come back as "unknown",
 * never as a group, and never as an unbounded string headed for a save key.
 */
public class AccountKeyTest
{
	@Test
	public void aKeyIsSixteenLowercaseHexCharacters()
	{
		String key = AccountKey.of(123456789L);
		Assert.assertNotNull(key);
		Assert.assertEquals(AccountKey.KEY_LENGTH, key.length());
		Assert.assertTrue("the wire format is exactly what normalize accepts",
			key.matches("[0-9a-f]{16}"));
		Assert.assertEquals("already canonical, so normalizing is a no-op",
			key, AccountKey.normalize(key));
	}

	@Test
	public void theKeyIsNotTheAccountHash()
	{
		// the whole reason this hashes rather than forwarding: the account hash
		// is a permanent cross-session correlator AND the input RuneLite derives
		// the RS profile key from. Handing it to every member of every party is
		// strictly more than "this is the same person I rolled with last week".
		long hash = 4611686018427387904L;
		String key = AccountKey.of(hash);
		Assert.assertNotNull(key);
		Assert.assertNotEquals(Long.toString(hash), key);
		Assert.assertNotEquals(Long.toHexString(hash), key);
		Assert.assertFalse("no substring of the raw value may survive",
			Long.toString(hash).contains(key) || key.contains(Long.toHexString(hash)));
	}

	@Test
	public void theSameAccountAlwaysProducesTheSameKey()
	{
		// a mark that reset every login would put tier II out of reach entirely,
		// which is the exact failure the member id had
		Assert.assertEquals(AccountKey.of(777L), AccountKey.of(777L));
		Assert.assertNotEquals(AccountKey.of(777L), AccountKey.of(778L));
		Assert.assertNotEquals(AccountKey.of(Long.MIN_VALUE), AccountKey.of(Long.MAX_VALUE));
	}

	@Test
	public void distinctAccountsDoNotCollideAtPartySize()
	{
		// 16 hex characters is 64 bits. This does not prove the bound, it pins
		// the property the bound exists for: a party is single digits, and even
		// a few thousand consecutive accounts must all read as different people.
		Set<String> keys = new HashSet<>();
		for (long hash = 1; hash <= 5000; hash++)
		{
			Assert.assertTrue("collision at " + hash, keys.add(AccountKey.of(hash)));
		}
	}

	@Test
	public void loggedOutIsNoIdentityRatherThanASharedOne()
	{
		// getAccountHash() returns -1 with nobody logged in. A constant "logged
		// out" key would make every logged-out client in the party the same
		// person, merge their rows into one group and credit them one mark.
		Assert.assertNull(AccountKey.of(AccountKey.NO_ACCOUNT));
		Assert.assertNull("0 is the other not-an-account value", AccountKey.of(0L));
		Assert.assertFalse("two unknowns are not each other",
			AccountKey.same(AccountKey.of(-1L), AccountKey.of(-1L)));
	}

	@Test
	public void normalizeIsTheWireTrustBoundary()
	{
		String key = AccountKey.of(42L);
		Assert.assertEquals("casing is folded, so one account cannot key twice",
			key, AccountKey.normalize(key.toUpperCase(java.util.Locale.ROOT)));
		Assert.assertEquals(key, AccountKey.normalize("  " + key + "  "));

		Assert.assertNull(AccountKey.normalize(null));
		Assert.assertNull(AccountKey.normalize(""));
		Assert.assertNull("15 characters is not a key", AccountKey.normalize("00112233445566a"));
		Assert.assertNull("17 characters is not a key", AccountKey.normalize("00112233445566aaa"));
		Assert.assertNull("g is not hex", AccountKey.normalize("00112233445566ag"));
		Assert.assertNull(AccountKey.normalize("0011223344 5566a"));
		Assert.assertNull("a display name is not a key", AccountKey.normalize("Zezima"));
	}

	@Test
	public void aHostileKeyCannotGrowTheSaveFile()
	{
		// this string becomes a KEY in the persisted patron ledger, so an
		// unbounded remote value keyed into it is a way to grow somebody else's
		// save file one party message at a time
		StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 100_000; i++)
		{
			huge.append('a');
		}
		Assert.assertNull(AccountKey.normalize(huge.toString()));
		// and the length check runs before the alphabet scan, so the rejection
		// does not depend on finding a bad character
		Assert.assertNull(AccountKey.normalize(huge.toString().replace('a', 'z')));
	}

	@Test
	public void sameIsNotObjectsEquals()
	{
		String key = AccountKey.of(9L);
		Assert.assertTrue(AccountKey.same(key, key));
		Assert.assertTrue("both sides are normalized before comparing",
			AccountKey.same(key, "  " + key.toUpperCase(java.util.Locale.ROOT) + "  "));
		Assert.assertFalse(AccountKey.same(key, AccountKey.of(10L)));

		// the reason this method exists at all: a null is "I do not know who
		// this is", and two of them are not a match. Objects.equals says true.
		Assert.assertFalse(AccountKey.same(null, null));
		Assert.assertFalse(AccountKey.same(key, null));
		Assert.assertFalse(AccountKey.same(null, key));
		Assert.assertFalse("junk is unknown, not a group",
			AccountKey.same("not-a-key", "not-a-key"));
	}
}
