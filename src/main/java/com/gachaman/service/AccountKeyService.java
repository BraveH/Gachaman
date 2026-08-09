package com.gachaman.service;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/**
 * The local player's {@link AccountKey}, cached and self-invalidating.
 *
 * <p>The hash itself is the cache key, so a profile switch, a logout or a
 * hop onto a different account is picked up by the next call with no event
 * subscription at all. Subscribing to AccountHashChanged instead would put
 * this service's correctness at the mercy of eventbus ORDERING — a party
 * broadcast handled before our listener ran would send the previous
 * account's key — and there is no ordering guarantee to lean on. Comparing
 * the value we were given is the version that cannot be raced.
 *
 * <p>Deliberately NOT persisted anywhere. The key is derived, not stored: a
 * cached copy in the save blob could outlive the account it names and would
 * then have to be reconciled with the live one, which is a whole class of bug
 * bought for the price of one SHA-256 per login.
 */
@Singleton
public class AccountKeyService
{
	private final Client client;

	/**
	 * Both fields are written together and only from the accessor below.
	 * Volatile because the party layer reads this from the client thread while
	 * the sidebar reads it from the EDT.
	 */
	private volatile long cachedHash = AccountKey.NO_ACCOUNT;
	@Nullable
	private volatile String cachedKey;

	@Inject
	public AccountKeyService(Client client)
	{
		this.client = client;
	}

	/**
	 * The local account's key, or null when nobody is logged in.
	 *
	 * <p>Null is a real and frequent answer — getAccountHash() returns -1 on
	 * the login screen, and the party layer can broadcast before the first
	 * login completes. Callers must send null through as "unknown" rather than
	 * substituting a placeholder, or every logged-out client in the party
	 * becomes the same person.
	 */
	@Nullable
	public String key()
	{
		long hash;
		try
		{
			hash = client.getAccountHash();
		}
		catch (Exception e)
		{
			// the API can be called before the client is fully constructed
			return null;
		}
		if (hash != cachedHash)
		{
			// key FIRST, then the hash that publishes it: a thread that sees the
			// new hash is guaranteed by the volatile write to see the matching
			// key, so a concurrent reader can never pair a fresh hash with the
			// previous account's key. Recomputing twice on a race is harmless —
			// AccountKey.of is a pure function of the hash.
			cachedKey = AccountKey.of(hash);
			cachedHash = hash;
		}
		return cachedKey;
	}
}
