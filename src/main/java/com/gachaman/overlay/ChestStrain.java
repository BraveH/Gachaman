package com.gachaman.overlay;

import com.gachaman.Tuning;

/**
 * The chest fights before it opens, and how hard it fights is the tell. This is
 * the whole schedule for that fight: a shudder that starts the strain, a hold
 * whose amplitude climbs toward the give, a beat of total stillness, then the
 * give — at which point the existing lid/flash/shatter code takes over
 * untouched.
 * <p>
 * Every input here is the {@link Tuning.Chest} tier the player PAID for and
 * nothing else. No signature in this class can accept a roll result, so the
 * length of the fight physically cannot betray what is inside the box; it
 * reports only what was bought. That is the difference between a tell and a
 * spoiler, and it is enforced by the signatures rather than by care.
 * <p>
 * Pure function of its arguments — no state, no timers; the caller owns the
 * clock, exactly as {@link ChestPainter} does. It depends only on
 * {@link Tuning}, which pulls in nothing from the client, so the schedule is
 * testable without a running RuneLite.
 */
final class ChestStrain {
	/**
	 * Dead stillness immediately before the give. Sustained shaking reads as
	 * noise; shaking that suddenly STOPS reads as a lock about to lose. This
	 * beat is the point of the whole class — if any part of the strain is ever
	 * cut, cut the groans and keep this.
	 */
	static final long HELD_STILL_MS = 220;

	/** How long the extra jolt from a groan takes to bleed off. */
	private static final long KICK_MS = 180;

	// Rusty gets no groans on purpose: the bottom of the ladder has to be
	// conspicuously undramatic or "how hard it fights" tells you nothing.
	private static final long[] BEATS_RUSTY = {};
	private static final long[] BEATS_BATTERED = {300, 900};
	private static final long[] BEATS_GILDED = {600, 1400, 2200};
	private static final long[] BEATS_ORNATE = {4000, 4700, 5400, 6000};

	private ChestStrain() {
	}

	/**
	 * Total intro length. All four numbers are exactly what the ceremony
	 * already shipped with — this feature changes the SHAPE of the intro, never
	 * its duration, because every downstream boundary (the Gilded lid at 3200,
	 * the Ornate blast at 6400) is tuned against them.
	 */
	static long totalMs(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return 1400;
			case BATTERED:
				return 2000;
			case GILDED:
				return 4000;
			default:
				return 7000;
		}
	}

	/** When the lock first moves. */
	static long shudderMs(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return 300;
			case BATTERED:
				return 300;
			case GILDED:
				return 600;
			default:
				return 900;
		}
	}

	/** When the lock loses — the instant the existing lid/flash/shatter code owns. */
	static long giveMs(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return 900;
			case BATTERED:
				return 1400;
			case GILDED:
				return 2800;
			default:
				return 6400;
		}
	}

	/**
	 * Elapsed times at which the lock groans and visibly kicks. The returned
	 * array is the SHARED instance, not a copy: {@link #kick} walks it on every
	 * rendered frame and a defensive clone there would allocate at framerate.
	 * Callers must not write to it (Tuning.CHEST_ODDS hands out shared arrays
	 * the same way).
	 */
	static long[] beats(Tuning.Chest tier) {
		switch (tier) {
			case RUSTY:
				return BEATS_RUSTY;
			case BATTERED:
				return BEATS_BATTERED;
			case GILDED:
				return BEATS_GILDED;
			default:
				return BEATS_ORNATE;
		}
	}

	/** 0..1 build from the first shudder to the give; monotone and clamped. */
	static double load(long el, Tuning.Chest tier) {
		long from = shudderMs(tier);
		long to = giveMs(tier);
		if (to <= from) {
			return 0;
		}
		double t = (el - from) / (double) (to - from);
		return t <= 0 ? 0 : (t >= 1 ? 1 : t);
	}

	/**
	 * The lock only moves while it is losing, and goes dead still just before
	 * it gives. Draw code gates on this rather than on {@code load() > 0}
	 * because at {@code el == shudderMs} the load is exactly 0, so a {@code > 0}
	 * gate would silently swallow the first frame of movement.
	 */
	static boolean straining(long el, Tuning.Chest tier) {
		return el >= shudderMs(tier) && el < giveMs(tier) - HELD_STILL_MS;
	}

	/** Extra amplitude right after a groan, decaying over KICK_MS — the visible shudder. */
	static double kick(long el, Tuning.Chest tier) {
		double best = 0;
		for (long b : beats(tier)) {
			long d = el - b;
			if (d >= 0 && d < KICK_MS) {
				double k = 1.0 - d / (double) KICK_MS;
				if (k > best) {
					best = k;
				}
			}
		}
		return best;
	}
}
