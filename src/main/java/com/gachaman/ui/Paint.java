package com.gachaman.ui;

import java.awt.*;

/**
 * The two paint helpers every animated surface in the plugin needs, in one
 * place instead of three.
 *
 * <p>{@link #hash01} in particular had drifted into CardRenderer, RevealOverlay
 * and KillJuiceOverlay as three byte-identical copies. That is worse than
 * wasteful: card wear, chest motes and kill sparks all seed their scatter from
 * it, and a "harmless" tweak to one copy would have silently re-rolled the
 * pattern on one surface while leaving the others alone.
 */
public final class Paint {
	private Paint() {
	}

	/**
	 * A deterministic 0..1 from an int seed — the same value for the same seed,
	 * forever and on every machine. Effects seeded from a card name or a
	 * particle index are then frame-stable and survive a restart.
	 */
	public static float hash01(int n) {
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	/** The same colour at a new alpha, clamped rather than wrapped. */
	public static Color withAlpha(Color c, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}
}
