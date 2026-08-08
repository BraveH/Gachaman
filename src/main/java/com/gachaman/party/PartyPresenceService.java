package com.gachaman.party;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.TaskService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;

/**
 * The party presence channel: one broadcast, one page.
 *
 * Every client sends a {@link GachaPresenceMessage} describing ITSELF whenever
 * any part of it changes, plus a slow heartbeat so a late joiner converges
 * without anyone doing anything. What comes back is rendered on the Party tab
 * and used for absolutely nothing else.
 *
 * This is deliberately a SEPARATE service from {@link PartyRollService} rather
 * than more methods on it. The seeded party roll must regenerate byte-identically
 * on every client from the propose/response handshake alone; if presence lived
 * next to the roll state, a future contributor could source a level or a
 * membership flag from a packet that arrives late, arrives never, or arrives
 * twice, and the whole party's offers would silently diverge. Here the roll
 * service holds no reference to presence and physically cannot read it. The
 * cost is four duplicated one-line helpers, which is the cheap side of that
 * trade.
 *
 * Nothing here is persisted: no field of GachaState is written and
 * stateService.mutate() is never called, so there is no legacy-save default to
 * get wrong and no gzip/SHA-256 encode on the tick path.
 */
@Slf4j
@Singleton
public class PartyPresenceService
{
	/** A remote display name is drawn in a fixed-width sidebar; bound it. */
	private static final int NAME_MAX = 40;
	/**
	 * Field separator for the change-detection signatures below. A pipe cannot
	 * appear in a monster name, a style name or a number, which is exactly the
	 * property {@link #signature} needs of it.
	 */
	private static final char SEP = '|';

	@Value
	private static class Heard
	{
		GachaPresenceMessage message;
		int heardAtTick;
	}

	/** One rendered line. Already sanitised — the UI trusts nothing itself. */
	@Value
	public static class Row
	{
		long memberId;
		String name;
		boolean loggedIn;
		boolean self;
		/** false = no plugin, setting off, never sent, or gone stale. */
		boolean heard;
		@Nullable
		AttackStyle style;
		int combatLevel;
		@Nullable
		String taskName;
		int killsDone;
		int killsRequired;
		boolean tainted;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final com.gachaman.GachamanConfig config;

	// --- transient; every field below is touched ONLY on the client thread ---
	private final Map<Long, Heard> presence = new HashMap<>();
	/** The line we last put on the wire; null = never sent / must re-announce. */
	private String lastSignature;
	private int lastSentTick;
	private boolean wasEnabled;
	/** Published from the client thread, read from the EDT. */
	private volatile List<Row> rows = Collections.emptyList();
	private String lastRowSignature;

	/** Plugin-wired: pokes the sidebar when the rendered roster actually changed. */
	@Nullable
	private Runnable refreshHook;

	@Inject
	public PartyPresenceService(Client client, ClientThread clientThread, PartyService partyService,
		TaskService taskService, GachaStateService stateService,
		com.gachaman.GachamanConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.taskService = taskService;
		this.stateService = stateService;
		this.config = config;
	}

	public void setRefreshHook(@Nullable Runnable hook)
	{
		this.refreshHook = hook;
	}

	private void refreshPanel()
	{
		if (refreshHook != null)
		{
			try
			{
				refreshHook.run();
			}
			catch (Exception e)
			{
				log.debug("panel refresh hook failed", e);
			}
		}
	}

	// =====================================================================
	// PURE RULES (no Client, no PartyService — the whole tested surface)
	// =====================================================================

	/**
	 * The broadcast line's identity. Fields are separated by a character a
	 * monster name cannot contain, never plain concatenation: "Goblin" at 1/12
	 * and "Goblin1" at 1/2 flatten to the same string without a separator, and
	 * the heartbeat would then swallow a change the party needed to see.
	 */
	static String signature(@Nullable String allowedStyle, int combatLevel,
		@Nullable String taskName, int killsDone, int killsRequired, boolean tainted)
	{
		return (allowedStyle == null ? "" : allowedStyle) + SEP + combatLevel + SEP
			+ (taskName == null ? "" : taskName) + SEP + killsDone + SEP
			+ killsRequired + SEP + tainted;
	}

	static boolean shouldBroadcast(int nowTick, int lastSentTick,
		@Nullable String nowSignature, @Nullable String lastSignature)
	{
		if (lastSignature == null || !lastSignature.equals(nowSignature))
		{
			return true;
		}
		// a relog or world hop restarts the client's tick counter: without this
		// the heartbeat would wait out a now-negative interval and we would look
		// silent to the whole party for as long as the old count was high
		if (nowTick < lastSentTick)
		{
			return true;
		}
		return nowTick - lastSentTick >= Tuning.PARTY_PRESENCE_HEARTBEAT_TICKS;
	}

	static boolean isStale(int nowTick, int lastHeardTick)
	{
		if (nowTick < lastHeardTick)
		{
			// OUR clock restarted, not theirs — do not blame the sender. The
			// asymmetry with shouldBroadcast is deliberate: we re-announce
			// ourselves, we do not grey out the entire party.
			return false;
		}
		return nowTick - lastHeardTick >= Tuning.PARTY_PRESENCE_STALE_TICKS;
	}

	/** Trust boundary: a remote name is drawn, so it cannot be unbounded. */
	@Nullable
	static String clip(@Nullable String text, int max)
	{
		if (text == null)
		{
			return null;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	/** public: PartyTab lives in another package and sizes its bar from this. */
	public static double progressFraction(int killsDone, int killsRequired)
	{
		if (killsRequired <= 0)
		{
			return 0;
		}
		return Math.max(0, Math.min(1, killsDone / (double) killsRequired));
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	// =====================================================================
	// BROADCAST
	// =====================================================================

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		int now = client.getTickCount();
		boolean enabled = config.partyRollsEnabled() && safeInParty();
		if (!enabled)
		{
			if (wasEnabled)
			{
				// leaving the party (or switching the setting off) must not leave
				// a frozen roster on the page, and re-joining has to re-announce
				// at once rather than sit out a heartbeat nobody heard
				presence.clear();
				lastSignature = null;
				wasEnabled = false;
				publishRows(Collections.emptyList());
			}
			return;
		}
		wasEnabled = true;
		GachaState state = stateService.get();
		if (state == null)
		{
			return;
		}
		GachaPresenceMessage mine = localPresence(state);
		String sig = signature(mine.getAllowedStyle(), mine.getCombatLevel(),
			mine.getActiveTaskName(), mine.getKillsDone(), mine.getKillsRequired(),
			mine.isTainted());
		if (shouldBroadcast(now, lastSentTick, sig, lastSignature) && safeSend(mine))
		{
			lastSignature = sig;
			lastSentTick = now;
		}
		publishRows(buildRows(now, mine));
	}

	/** Reads client skill levels, so client-thread only — onGameTick guarantees it. */
	private GachaPresenceMessage localPresence(GachaState state)
	{
		ActiveTask task = state.getActiveTask();
		return new GachaPresenceMessage(
			state.getAllowedStyle(),
			taskService.playerCombatLevel(),
			task == null ? null : task.getMonsterName(),
			task == null ? 0 : task.getKillsDone(),
			task == null ? 0 : task.getKillsRequired(),
			state.getTaint() > 0);
	}

	// =====================================================================
	// RECEIVE
	// =====================================================================

	@Subscribe
	public void onGachaPresenceMessage(GachaPresenceMessage msg)
	{
		if (msg == null || isSelfEcho(msg.getMemberId()))
		{
			return;
		}
		// store only: the next tick republishes, at most 600ms later, and one
		// publish site is far less to get wrong than two
		clientThread.invokeLater(() -> presence.put(msg.getMemberId(),
			new Heard(msg, client.getTickCount())));
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		// a member just joined and asked the party to re-announce: drop the
		// last-sent signature so the very next tick sends, instead of leaving
		// the joiner on an empty page until our heartbeat comes round
		clientThread.invokeLater(() -> lastSignature = null);
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invokeLater(() -> presence.remove(event.getMemberId()));
	}

	// =====================================================================
	// PUBLISH
	// =====================================================================

	/** Client thread only. Sanitises every remote value on the way in. */
	private List<Row> buildRows(int now, GachaPresenceMessage mine)
	{
		long selfId = safeMemberIdOrZero();
		List<PartyMember> members;
		try
		{
			members = new ArrayList<>(partyService.getMembers());
		}
		catch (Exception e)
		{
			log.debug("party roster read failed", e);
			return rows;
		}
		List<Row> out = new ArrayList<>(members.size());
		for (PartyMember member : members)
		{
			long id = member.getMemberId();
			String name = clip(member.getDisplayName(), NAME_MAX);
			if (name == null)
			{
				name = "A party member";
			}
			if (id == selfId)
			{
				// our own row reads the same source we send, not our echo (which
				// never arrives): the page shows exactly what the party is told
				out.add(new Row(id, name, true, true, true,
					PartyRollService.parseStyle(mine.getAllowedStyle()),
					mine.getCombatLevel(), mine.getActiveTaskName(),
					mine.getKillsDone(), mine.getKillsRequired(), mine.isTainted()));
				continue;
			}
			Heard heard = presence.get(id);
			boolean live = heard != null && !isStale(now, heard.getHeardAtTick());
			GachaPresenceMessage m = live ? heard.getMessage() : null;
			out.add(new Row(id, name, member.isLoggedIn(), false, live,
				live ? PartyRollService.parseStyle(m.getAllowedStyle()) : null,
				live ? clamp(m.getCombatLevel(), 0, 126) : 0,
				live ? clip(m.getActiveTaskName(), NAME_MAX) : null,
				live ? clamp(m.getKillsDone(), 0, 9999) : 0,
				live ? clamp(m.getKillsRequired(), 0, 9999) : 0,
				live && m.isTainted()));
		}
		// a stable order, so the page never reshuffles under the player on a
		// heartbeat that changed nothing they can see
		out.sort(Comparator.comparing((Row r) -> !r.isSelf())
			.thenComparing(r -> r.getName().toLowerCase(Locale.ROOT))
			.thenComparingLong(Row::getMemberId));
		return Collections.unmodifiableList(out);
	}

	private void publishRows(List<Row> next)
	{
		StringBuilder sb = new StringBuilder(next.size() * 24);
		for (Row r : next)
		{
			sb.append(r.getMemberId()).append(SEP)
				.append(r.isHeard()).append(SEP)
				.append(r.getStyle() == null ? "" : r.getStyle().name()).append(SEP)
				.append(r.getCombatLevel()).append(SEP)
				.append(r.getTaskName() == null ? "" : r.getTaskName()).append(SEP)
				.append(r.getKillsDone()).append(SEP)
				.append(r.getKillsRequired()).append(SEP)
				.append(r.isTainted()).append(SEP)
				.append(r.isLoggedIn()).append(SEP);
		}
		String sig = sb.toString();
		// always publish: the EDT must be able to read the current list even when
		// it happens to render identically
		rows = next;
		if (!sig.equals(lastRowSignature))
		{
			// GachamanPanel.refresh() marks every tab dirty and rebuilds the
			// selected one, so poking it every tick would rebuild whichever tab
			// the player is actually looking at ~1.7x/second for no visible change
			lastRowSignature = sig;
			refreshPanel();
		}
	}

	/** EDT-safe: an immutable list published from the client thread. */
	public List<Row> getRows()
	{
		return rows;
	}

	/**
	 * Profile switch / logout: the line we last broadcast describes a character
	 * that is gone, so force a re-announce. The ROSTER's presence is untouched —
	 * their lines are still true and every one of them re-heartbeats within
	 * seconds anyway, so clearing it would only blank the page for no reason.
	 */
	public void reset()
	{
		lastSignature = null;
		lastSentTick = 0;
	}

	// =====================================================================
	// Defensive party plumbing (private copies of PartyRollService's — see the
	// class javadoc: widening that class's API is what this service exists to
	// avoid)
	// =====================================================================

	private boolean safeInParty()
	{
		try
		{
			return partyService.isInParty();
		}
		catch (Exception e)
		{
			return false;
		}
	}

	@Nullable
	private PartyMember safeLocalMember()
	{
		try
		{
			return partyService.getLocalMember();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private long safeMemberIdOrZero()
	{
		PartyMember local = safeLocalMember();
		return local == null ? 0 : local.getMemberId();
	}

	private boolean isSelfEcho(long memberId)
	{
		PartyMember local = safeLocalMember();
		return local == null || memberId == local.getMemberId();
	}

	private boolean safeSend(net.runelite.client.party.messages.PartyMemberMessage msg)
	{
		try
		{
			partyService.send(msg);
			return true;
		}
		catch (Exception e)
		{
			log.debug("presence send failed", e);
			return false;
		}
	}
}
