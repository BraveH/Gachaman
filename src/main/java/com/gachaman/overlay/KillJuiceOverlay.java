package com.gachaman.overlay;

import net.runelite.api.Point;
import com.gachaman.ui.Paint;
import com.gachaman.service.*;
import com.gachaman.ui.*;
import java.awt.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.overlay.*;

/**
 * Lightweight non-modal kill feedback: floating "+N GC" texts rising from the
 * NPC death point, a grey "x" for off-task kills, tainted text and a golden
 * burst on the final kill. Task progress itself lives in the movable
 * {@link TaskProgressOverlay} panel. The overlay is the
 * {@link TaskService.Listener} the plugin registers
 * ({@code taskService.addListener(killJuiceOverlay)}).
 *
 * <p>Notes are pinned to the world, not the screen: each one keeps the
 * {@link LocalPoint} the NPC died on and is re-projected every frame, so the
 * number sits over the corpse while the camera turns, zooms and pans. Floating
 * texts live in a preallocated ring buffer and burst particles are pure
 * functions of elapsed time + seed; the only per-frame allocation is the
 * projected Point per ACTIVE note, which is almost always none or one.
 */
@Slf4j
@Singleton
public class KillJuiceOverlay extends Overlay implements TaskService.Listener {
	private static final int POOL_SIZE = 16;
	private static final long FLOAT_MS = 1500;
	private static final long BURST_MS = 1100;
	private static final int RISE_PX = 30;

	private static final int KIND_GC = 0;
	private static final int KIND_OFF_TASK = 1;
	private static final int KIND_TAINTED = 2;
	private static final int KIND_BURST = 3;

	private static final Font FONT_GC = new Font(Font.SANS_SERIF, Font.BOLD, 15);
	private static final Font FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	private static final Color GC_GOLD = new Color(255, 205, 70);
	private static final Color OFF_GREY = new Color(160, 160, 160, 190);
	private static final Color TAINT_RED = new Color(170, 30, 30);

	/** Preallocated floating-text slot; mutated in place, never allocated in render. */
	private static final class Note {
		boolean active;
		int kind;
		long startMs;
		/**
		 * The world spot this note is pinned to, re-projected every frame so the
		 * number stays over the corpse while the camera turns. Null only when the
		 * kill arrived without a death location, in which case the note falls
		 * back to the screen anchor below.
		 */
		LocalPoint world;
		int plane;
		int screenX;
		int screenY;
		String text;
		int seed;
	}

	private final Client client;

	private final Note[] notes = new Note[POOL_SIZE];
	private int noteCursor;

	@Inject
	public KillJuiceOverlay(Client client) {
		this.client = client;
		for (int i = 0; i < notes.length; i++) {
			notes[i] = new Note();
		}
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(Overlay.PRIORITY_MED);
	}

	// --- TaskService.Listener (events arrive on the client thread) ---

	@Override
	public void onKillFeedback(TaskService.KillFeedback feedback) {
		if (feedback == null) {
			return;
		}
		// keep the WORLD point, not a canvas point: projecting once at spawn
		// pinned the number to a screen pixel, so it slid off the corpse the
		// moment the camera moved. Projection happens per frame in drawNotes.
		LocalPoint world = feedback.getDeathLocation();
		int plane = client.getTopLevelWorldView().getPlane();
		long now = System.currentTimeMillis();

		if (!feedback.isOnTask()) {
			spawn(KIND_OFF_TASK, world, plane, "x", now);
			return;
		}
		if (feedback.isTainted()) {
			spawn(KIND_TAINTED, world, plane, "TAINTED +1 taint", now);
		}
		else if (feedback.getGcAwarded() > 0) {
			spawn(KIND_GC, world, plane, "+" + feedback.getGcAwarded() + " GC", now);
		}
		if (feedback.isFinalKill()) {
			spawn(KIND_BURST, world, plane, null, now);
		}
	}


	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary) {
		// the docked TaskProgressOverlay tracks progress; completion fanfare
		// is the final-kill burst plus the ceremony pipeline
	}


	private synchronized void spawn(int kind, LocalPoint world, int plane, String text, long now) {
		Note note = notes[noteCursor];
		noteCursor = (noteCursor + 1) % notes.length;
		note.active = true;
		note.kind = kind;
		note.startMs = now;
		note.world = world;
		note.plane = plane;
		// only consulted when there is no world point to project
		note.screenX = client.getCanvasWidth() / 2;
		note.screenY = client.getCanvasHeight() / 2;
		note.text = text;
		note.seed = (int) (now & 0x7FFF) * 31 + (world == null ? 0 : world.getX());
	}

	// --- render (zero allocation) ---

	@Override
	public Dimension render(Graphics2D g) {
		long now = System.currentTimeMillis();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		drawNotes(g, now);
		return null;
	}

	private void drawNotes(Graphics2D g, long now) {
		for (int i = 0; i < notes.length; i++) {
			Note note = notes[i];
			if (!note.active) {
				continue;
			}
			long el = now - note.startMs;
			long life = note.kind == KIND_BURST ? BURST_MS : FLOAT_MS;
			if (el < 0 || el >= life) {
				note.active = false;
				continue;
			}
			// re-project every frame: this is what keeps the number over the
			// spot the NPC died on rather than over a fixed screen pixel
			int anchorX = note.screenX;
			int anchorY = note.screenY;
			if (note.world != null) {
				Point p = null;
				try {
					p = Perspective.localToCanvas(client, note.world, note.plane);
				}
				catch (Exception e) {
					p = null;
				}
				if (p == null) {
					continue; // the spot is off-scene or behind the camera this frame
				}
				anchorX = p.getX();
				anchorY = p.getY();
			}
			if (note.kind == KIND_BURST) {
				drawBurst(g, note, el, anchorX, anchorY);
				continue;
			}
			float u = el / (float) life;
			float alpha = u < 0.6f ? 1f : 1f - (u - 0.6f) / 0.4f;
			int y = anchorY - (int) (RISE_PX * easeOut(u));

			Font font;
			Color color;
			if (note.kind == KIND_GC) {
				font = FONT_GC;
				color = GC_GOLD;
			}
			else if (note.kind == KIND_TAINTED) {
				font = FONT_GC;
				color = TAINT_RED;
			}
			else {
				font = FONT_SMALL;
				color = OFF_GREY;
			}
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			int x = anchorX - fm.stringWidth(note.text) / 2;
			g.setColor(Paint.withAlpha(Color.BLACK, alpha * 0.8f));
			g.drawString(note.text, x + 1, y + 1);
			g.setColor(Paint.withAlpha(color, alpha));
			g.drawString(note.text, x, y);
		}
	}

	private void drawBurst(Graphics2D g, Note note, long el, int anchorX, int anchorY) {
		float u = el / (float) BURST_MS;
		double ts = el / 1000.0;
		for (int p = 0; p < 14; p++) {
			float h1 = Paint.hash01(note.seed + p * 3);
			float h2 = Paint.hash01(note.seed + p * 3 + 1);
			float h3 = Paint.hash01(note.seed + p * 3 + 2);
			double ang = h1 * Math.PI * 2;
			double speed = 40 + h2 * 150;
			int px = anchorX + (int) (Math.cos(ang) * speed * ts);
			int py = anchorY + (int) (Math.sin(ang) * speed * ts * 0.8 + 240 * ts * ts);
			int size = 2 + (int) (h3 * 2);
			g.setColor(Paint.withAlpha(p % 3 == 0 ? Color.WHITE : GC_GOLD, 1f - u));
			g.fillRect(px, py, size, size);
		}
		// small expanding halo
		int r = (int) (10 + 34 * easeOut(u));
		g.setColor(Paint.withAlpha(GC_GOLD, (1f - u) * 0.7f));
		g.setStroke(STROKE_2);
		g.drawOval(anchorX - r, anchorY - r, r * 2, r * 2);
	}

	private static final BasicStroke STROKE_2 = new BasicStroke(2f);

	private static float easeOut(float t) {
		float inv = 1 - Math.max(0f, Math.min(1f, t));
		return 1 - inv * inv;
	}


}
