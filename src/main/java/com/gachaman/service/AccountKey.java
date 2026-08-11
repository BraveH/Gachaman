package com.gachaman.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * A stable, per-account identifier for the party layer.
 *
 * Jagex gives every account a permanent id and RuneLite exposes it as
 * {@code Client.getAccountHash()}, but SELF-ONLY: a PartyMember carries a
 * member id, a display name, an avatar and a login flag, and nothing in the
 * API maps another player back to an account. So the key has to be sent, and
 * what is sent is what everyone in the party can read.
 *
 * That is why this hashes rather than forwarding the raw value. The account
 * hash is a permanent cross-session correlator and is also the input RuneLite
 * itself derives the RS profile key from; handing it to every member of every
 * party you ever join gives away more than "this is the same person I rolled
 * with last week". Truncating SHA-256 to 8 bytes keeps that one statement and
 * throws the rest away — 16 hex characters is far too wide to collide inside
 * a party, and is not the account hash.
 *
 * SELF-REPORTED AND UNAUTHENTICATED. The value arrives over the party relay
 * from another player's client, so a hostile client can claim any key it
 * likes — exactly the trust level of the display name it replaces. Fine as
 * the key for a cosmetic mark and for grouping rows on a page. Never let it
 * gate anything that pays.
 *
 * Pure statics, so every rule here is testable without a Client.
 */
public final class AccountKey {
	/** 8 bytes of SHA-256, hex-encoded. */
	public static final int KEY_LENGTH = 16;

	/** What getAccountHash() returns when nobody is logged in. */
	public static final long NO_ACCOUNT = -1L;

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private AccountKey() {
		return;
	}

	/**
	 * The key for an account hash, or null when there is no account.
	 *
	 * Null rather than a constant "logged out" string: a key is an identity
	 * claim, and every logged-out client sharing one would make them all the
	 * same person. Callers must treat null as "unknown", never as a group.
	 */
	@Nullable
	public static String of(long accountHash) {
		if (accountHash == NO_ACCOUNT || accountHash == 0L) {
			return null;
		}
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JRE; if it is genuinely absent the
			// right answer is "no identity", not a crash on the game thread
			return null;
		}
		byte[] hash = digest.digest(Long.toString(accountHash).getBytes(StandardCharsets.UTF_8));
		char[] out = new char[KEY_LENGTH];
		for (int i = 0; i < KEY_LENGTH / 2; i++) {
			out[i * 2] = HEX[(hash[i] >> 4) & 0xF];
			out[i * 2 + 1] = HEX[hash[i] & 0xF];
		}
		return new String(out);
	}

	/**
	 * A received key made safe to store and compare, or null if it is not one.
	 *
	 * The trust boundary for everything that arrives over the wire. Length and
	 * alphabet are both checked because this string becomes a KEY in the save
	 * blob: an unbounded remote string keyed into a persisted map is a way to
	 * grow somebody else's save file, and a mixed-case one would key twice.
	 */
	@Nullable
	public static String normalize(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String key = raw.trim().toLowerCase(Locale.ROOT);
		if (key.length() != KEY_LENGTH) {
			return null;
		}
		for (int i = 0; i < KEY_LENGTH; i++) {
			char c = key.charAt(i);
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
				return null;
			}
		}
		return key;
	}

	/**
	 * Whether two claims name the same account. Two unknowns are NOT the same
	 * account — that is the whole reason this is not {@code Objects.equals}.
	 */
	public static boolean same(@Nullable String a, @Nullable String b) {
		String left = normalize(a);
		String right = normalize(b);
		return left != null && left.equals(right);
	}
}
