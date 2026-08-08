package com.gachaman.overlay;

import com.gachaman.model.ActiveTask;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskOffer;
import com.gachaman.service.TaskService;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Lightweight non-modal kill feedback: floating "+N GC" texts rising from the
 * NPC death point, a grey "x" for off-task kills, tainted text and a golden
 * burst on the final kill. Task progress itself lives in the movable
 * {@link TaskProgressOverlay} panel. The overlay is the
 * {@link TaskService.Listener} the plugin registers
 * ({@code taskService.addListener(killJuiceOverlay)}). Render allocates
 * nothing: floating texts live in a preallocated ring buffer and burst
 * particles are pure functions of elapsed time + seed.
 */
@Slf4j
@Singleton
public class KillJuiceOverlay extends Overlay implements TaskService.Listener
{
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
	private static final class Note
	{
		boolean active;
		int kind;
		long startMs;
		int x;
		int y;
		String text;
		int seed;
	}

	private final Client client;

	private final Note[] notes = new Note[POOL_SIZE];
	private int noteCursor;

	@Inject
	public KillJuiceOverlay(Client client)
	{
		this.client = client;
		for (int i = 0; i < notes.length; i++)
		{
			notes[i] = new Note();
		}
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(Overlay.PRIORITY_MED);
	}

	// --- TaskService.Listener (events arrive on the client thread) ---

	@Override
	public void onKillFeedback(TaskService.KillFeedback feedback)
	{
		if (feedback == null)
		{
			return;
		}
		// capture the canvas point NOW, while the death location is fresh
		net.runelite.api.Point canvas = null;
		if (feedback.getDeathLocation() != null)
		{
			try
			{
				canvas = Perspective.localToCanvas(client, feedback.getDeathLocation(),
					client.getTopLevelWorldView().getPlane());
			}
			catch (Exception e)
			{
				canvas = null;
			}
		}
		int x;
		int y;
		if (canvas != null)
		{
			x = canvas.getX();
			y = canvas.getY();
		}
		else
		{
			x = client.getCanvasWidth() / 2;
			y = client.getCanvasHeight() / 2;
		}
		long now = System.currentTimeMillis();

		if (!feedback.isOnTask())
		{
			spawn(KIND_OFF_TASK, x, y, "x", now);
			return;
		}
		if (feedback.isTainted())
		{
			spawn(KIND_TAINTED, x, y, "TAINTED +1 taint", now);
		}
		else if (feedback.getGcAwarded() > 0)
		{
			spawn(KIND_GC, x, y, "+" + feedback.getGcAwarded() + " GC", now);
		}
		if (feedback.isFinalKill())
		{
			spawn(KIND_BURST, x, y, null, now);
		}
	}

	@Override
	public void onSideBetHit(SideBet bet, String monsterName)
	{
		// side bets celebrate through the ceremony fanfare pipeline
	}

	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
	{
		// the docked TaskProgressOverlay tracks progress; completion fanfare
		// is the final-kill burst plus the ceremony pipeline
	}

	@Override
	public void onOffersRolled(List<TaskOffer> offers)
	{
		// nothing to juice
	}

	@Override
	public void onDuoProgress(ActiveTask task)
	{
		// partner progress pulses could go here later
	}

	private synchronized void spawn(int kind, int x, int y, String text, long now)
	{
		Note note = notes[noteCursor];
		noteCursor = (noteCursor + 1) % notes.length;
		note.active = true;
		note.kind = kind;
		note.startMs = now;
		note.x = x;
		note.y = y;
		note.text = text;
		note.seed = (int) (now & 0x7FFF) * 31 + x;
	}

	// --- render (zero allocation) ---

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.currentTimeMillis();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		drawNotes(g, now);
		return null;
	}

	private void drawNotes(Graphics2D g, long now)
	{
		for (int i = 0; i < notes.length; i++)
		{
			Note note = notes[i];
			if (!note.active)
			{
				continue;
			}
			long el = now - note.startMs;
			long life = note.kind == KIND_BURST ? BURST_MS : FLOAT_MS;
			if (el < 0 || el >= life)
			{
				note.active = false;
				continue;
			}
			if (note.kind == KIND_BURST)
			{
				drawBurst(g, note, el);
				continue;
			}
			float u = el / (float) life;
			float alpha = u < 0.6f ? 1f : 1f - (u - 0.6f) / 0.4f;
			int y = note.y - (int) (RISE_PX * easeOut(u));

			Font font;
			Color color;
			if (note.kind == KIND_GC)
			{
				font = FONT_GC;
				color = GC_GOLD;
			}
			else if (note.kind == KIND_TAINTED)
			{
				font = FONT_GC;
				color = TAINT_RED;
			}
			else
			{
				font = FONT_SMALL;
				color = OFF_GREY;
			}
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			int x = note.x - fm.stringWidth(note.text) / 2;
			g.setColor(withAlpha(Color.BLACK, alpha * 0.8f));
			g.drawString(note.text, x + 1, y + 1);
			g.setColor(withAlpha(color, alpha));
			g.drawString(note.text, x, y);
		}
	}

	private void drawBurst(Graphics2D g, Note note, long el)
	{
		float u = el / (float) BURST_MS;
		double ts = el / 1000.0;
		for (int p = 0; p < 14; p++)
		{
			float h1 = hash01(note.seed + p * 3);
			float h2 = hash01(note.seed + p * 3 + 1);
			float h3 = hash01(note.seed + p * 3 + 2);
			double ang = h1 * Math.PI * 2;
			double speed = 40 + h2 * 150;
			int px = note.x + (int) (Math.cos(ang) * speed * ts);
			int py = note.y + (int) (Math.sin(ang) * speed * ts * 0.8 + 240 * ts * ts);
			int size = 2 + (int) (h3 * 2);
			g.setColor(withAlpha(p % 3 == 0 ? Color.WHITE : GC_GOLD, 1f - u));
			g.fillRect(px, py, size, size);
		}
		// small expanding halo
		int r = (int) (10 + 34 * easeOut(u));
		g.setColor(withAlpha(GC_GOLD, (1f - u) * 0.7f));
		g.setStroke(STROKE_2);
		g.drawOval(note.x - r, note.y - r, r * 2, r * 2);
	}

	private static final BasicStroke STROKE_2 = new BasicStroke(2f);

	private static float easeOut(float t)
	{
		float inv = 1 - Math.max(0f, Math.min(1f, t));
		return 1 - inv * inv;
	}

	private static float hash01(int n)
	{
		int h = n * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static Color withAlpha(Color c, float alpha)
	{
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}
}
