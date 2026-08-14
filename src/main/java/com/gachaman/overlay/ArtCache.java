package com.gachaman.overlay;

import java.awt.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;

/**
 * The one PNG cache every ceremony sprite is loaded through: the roulette
 * chrome, the scroll rollers and the parchment texture all name a file under
 * {@code /com/gachaman/art/} and all want the same "decode once, remember the
 * answer" behaviour, so they share this instead of each carrying a map and a
 * copy of the same try-with-resources.
 *
 * <p>Unbounded on purpose. These sprites are small, few, and wanted for as long
 * as the client runs. {@link CeremonyPlayer} deliberately does NOT use this
 * cache: its chest frames are large enough that holding them all costs tens of
 * MB, which is why that class keeps a bounded LRU of its own.
 */
final class ArtCache {
	/**
	 * Sprite name to decoded image, with an explicit null for a sprite that is
	 * not in the jar. Package-visible so the overlay tests can assert on the
	 * caching itself rather than on pixels.
	 */
	static final Map<String, Image> ART = new HashMap<>();

	private ArtCache() {
	}

	/**
	 * The named sprite, or null when it is missing or will not decode.
	 *
	 * <p>containsKey, not computeIfAbsent: a HashMap does not RECORD a null
	 * mapping, so computeIfAbsent treats a missing PNG as "not cached yet" and
	 * re-runs the loader on every single frame. Every sprite ships today, so
	 * this is latent rather than live — but on a partial or stripped jar the
	 * roulette would do four failed classloader lookups per frame for the whole
	 * spin, and each scroll five more per roller. An explicit null value is the
	 * "tried once" sentinel that holds a miss to exactly one lookup.
	 */
	static Image get(String name) {
		if (!ART.containsKey(name)) {
			Image loaded = null;
			try (InputStream in = ArtCache.class.getResourceAsStream(
				"/com/gachaman/art/" + name + ".png")) {
				if (in != null) {
					loaded = ImageIO.read(in);
				}
			}
			catch (Exception e) {
				// a truncated or corrupt PNG caches as a miss for the same
				// reason: re-decoding it every frame cannot make it decode
			}
			ART.put(name, loaded);
		}
		return ART.get(name);
	}

	/**
	 * Draws the named sprite stretched into (x, y, w, h); a miss draws nothing.
	 *
	 * <p>The size check comes first so a caller whose arithmetic has collapsed —
	 * a scroll drawn narrower than its own end caps — never reaches the cache at
	 * all, which is the order {@link ScrollPainter} has always used.
	 */
	static void blit(Graphics2D g, String name, int x, int y, int w, int h) {
		Image art = w > 0 && h > 0 ? get(name) : null;
		if (art != null) {
			g.drawImage(art, x, y, w, h, null);
		}
	}
}
