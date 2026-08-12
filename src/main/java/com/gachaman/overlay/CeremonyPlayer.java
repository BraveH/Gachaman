package com.gachaman.overlay;

import java.util.List;
import com.gachaman.*;
import com.google.gson.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import javax.imageio.*;
import javax.inject.*;
import lombok.extern.slf4j.*;

/**
 * Plays the pre-rendered chest ceremony: body, lid, chains, padlock, strain,
 * seam leak, glow and motes, as one frame sequence per tier.
 *
 * <p>The whole ceremony is a pure function of (tier, elapsed) - the chest and
 * camera shakes are translations the caller applies, and every other input is
 * derived from el - so it bakes exactly. Frames are cropped to their own alpha
 * bounds with the offset carried in the index, which is what keeps a whipping
 * chain or a blasted lid from paying for the empty space around it.
 *
 * <p>Authored by com.gachaman.overlay.CeremonyArt (test scope), which holds the
 * procedural source.
 */
@Slf4j
@Singleton
class CeremonyPlayer {
	/** Frames are authored against a 300x225 chest and scaled from there. */
	private static final int ART_W = 300;
	private static final int ART_H = 225;

	private final Gson gson;
	private Index index;

	@Inject
	CeremonyPlayer(Gson gson) {
		this.gson = gson;
	}

	/**
	 * Bounded frame cache.
	 *
	 * <p>A ceremony walks its frames in order and never looks back, so holding
	 * all of them is pure waste: the ORNATE sequence alone decodes to 55 MB.
	 * Eight is comfortably more than the one frame in flight, absorbs the
	 * upgrade crossfade (which touches two tiers' frame 0 together) and the
	 * deal phase (which parks on the last frame), and caps the player at a few
	 * MB. A miss costs one small PNG decode inside a 50ms frame budget.
	 */
	private static final int CACHE = 8;
	private final Map<String, Image> frames =
		new LinkedHashMap<String, Image>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
				return size() > CACHE;
			}
		};

	private static final class Index {
		int fps;
		Map<String, List<List<Integer>>> tiers;
	}

	private Index index() {
		if (index == null) {
			Index loaded = null;
			try (InputStream in = CeremonyPlayer.class.getResourceAsStream(
				"/com/gachaman/art/ceremony/chest-ceremony.json")) {
				if (in != null) {
					loaded = gson.fromJson(
						new InputStreamReader(in, StandardCharsets.UTF_8), Index.class);
				}
			}
			catch (Exception e) {
				log.warn("chest ceremony index missing", e);
			}
			if (loaded == null || loaded.tiers == null) {
				loaded = new Index();
				loaded.fps = 20;
				loaded.tiers = new HashMap<>();
			}
			index = loaded;
		}
		return index;
	}

	/** Frame count for a tier, or 0 when the art is unavailable. */
	int frames(Tuning.Chest tier) {
		List<List<Integer>> f = index().tiers.get(tier.name().toLowerCase(Locale.ROOT));
		return f == null ? 0 : f.size();
	}

	/** The frame index this many ms into the ceremony, clamped to the last one. */
	int frameAt(Tuning.Chest tier, long el) {
		int n = frames(tier);
		if (n == 0) {
			return -1;
		}
		int i = (int) (el * index().fps / 1000L);
		return Math.max(0, Math.min(n - 1, i));
	}

	int lastFrame(Tuning.Chest tier) {
		return Math.max(0, frames(tier) - 1);
	}

	/**
	 * Blits one ceremony frame with the chest centred on (cx, cy).
	 *
	 * @return false when the art could not be loaded, so the caller can fall
	 *         back rather than draw nothing
	 */
	boolean draw(Graphics2D g, int cx, int cy, int w, int h, Tuning.Chest tier, int frame,
		float alpha) {
		List<List<Integer>> offsets = index().tiers.get(tier.name().toLowerCase(Locale.ROOT));
		if (offsets == null || frame < 0 || frame >= offsets.size()) {
			return false;
		}
		String key = tier.name() + frame;
		Image art = frames.computeIfAbsent(key, k -> {
			try (InputStream in = CeremonyPlayer.class.getResourceAsStream(
				String.format("/com/gachaman/art/ceremony/chest-%s-%03d.png",
					tier.name().toLowerCase(Locale.ROOT), frame))) {
				return in == null ? null : ImageIO.read(in);
			}
			catch (Exception e) {
				return null;
			}
		});
		if (art == null) {
			return false;
		}
		double sx = w / (double) ART_W;
		double sy = h / (double) ART_H;
		List<Integer> at = offsets.get(frame);
		int dx = cx + (int) Math.round(at.get(0) * sx);
		int dy = cy + (int) Math.round(at.get(1) * sy);
		java.awt.Composite old = null;
		if (alpha < 0.999f) {
			old = g.getComposite();
			g.setComposite(java.awt.AlphaComposite.getInstance(
				java.awt.AlphaComposite.SRC_OVER, Math.max(0f, alpha)));
		}
		// index carries the AUTHORED size, which is not the stored size: a frame
		// whose decoded bytes would pass the Plugin Hub's 1 MiB image limit is
		// stored smaller and stretched back here, so the art keeps its extent
		g.drawImage(art, dx, dy,
			(int) Math.round(at.get(2) * sx),
			(int) Math.round(at.get(3) * sy), null);
		if (old != null) {
			g.setComposite(old);
		}
		return true;
	}
}
