package com.gachaman.party;

import com.gachaman.Tuning;
import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.GachaState;
import com.gachaman.service.AccountKey;
import com.gachaman.service.GachaStateService;
import com.gachaman.service.TaskService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
		/** Normalized AccountKey, or null when they did not claim one. */
		@Nullable
		String accountKey;
		/** Holding a dealt board they have signed nothing from — ineligible. */
		boolean undecidedOffers;
		/** Their shared contract's proposal id; null when they claim none. */
		@Nullable
		Long partyContractId;
		/**
		 * Their RuneLite party avatar, or null when they have none.
		 *
		 * <p>Carried on the row rather than fetched by the panel: the Party tab
		 * deliberately touches neither Client nor PartyService, so everything it
		 * draws arrives through here. This is the ONE field not self-reported over
		 * the Gachaman channel — RuneLite's own party layer supplies it — so it
		 * stays trustworthy even when the rest of the row is a stale claim.
		 *
		 * MUST stay last: {@code @Value} generates a positional constructor.
		 */
		@Nullable
		java.awt.image.BufferedImage avatar;

		/** Nothing signed and nothing pending: this member can join a roll. */
		public boolean isEligibleToRoll()
		{
			return heard && loggedIn && killsRequired <= 0 && !undecidedOffers;
		}
	}

	/**
	 * One block on the party page: either a set of members working the same
	 * shared contract, or a single member on their own.
	 *
	 * A GROUP rather than a flat member list because a party is routinely not
	 * one thing: two pairs can be on two different shared contracts while a
	 * fifth member is still mid-roll, and a flat list renders that as five
	 * unrelated lines that happen to repeat a monster name twice.
	 */
	@Value
	public static class Group
	{
		/** The shared contract's proposal id, or null for a solo/idle member. */
		@Nullable
		Long contractId;
		/** The quarry, or null when nobody in the group is on a contract. */
		@Nullable
		String taskName;
		/** Every member in display order; never empty. */
		List<Row> members;
		/**
		 * The pooled quota's progress: the HIGHEST count any member reports,
		 * not a sum and not the first row's.
		 *
		 * A shared contract has ONE quota that every member's kills fill, so
		 * every client should report the same number — but a client that has
		 * gone quiet, or has not yet received the latest kill message, reports
		 * a stale LOWER one. Summing would multiply the quota by the party
		 * size; taking the max reads the freshest client and is never worse
		 * than the truth.
		 */
		int killsDone;
		int killsRequired;

		/** A contract this member says is shared, however many are visible. */
		public boolean isShared()
		{
			return contractId != null;
		}

		public boolean isOnContract()
		{
			return killsRequired > 0;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final TaskService taskService;
	private final GachaStateService stateService;
	private final com.gachaman.service.AccountKeyService accountKeyService;
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
		com.gachaman.service.AccountKeyService accountKeyService,
		com.gachaman.GachamanConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.taskService = taskService;
		this.stateService = stateService;
		this.accountKeyService = accountKeyService;
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
		@Nullable String taskName, int killsDone, int killsRequired, boolean tainted,
		@Nullable String accountKey, boolean undecidedOffers, @Nullable Long partyContractId)
	{
		return (allowedStyle == null ? "" : allowedStyle) + SEP + combatLevel + SEP
			+ (taskName == null ? "" : taskName) + SEP + killsDone + SEP
			+ killsRequired + SEP + tainted + SEP
			+ (accountKey == null ? "" : accountKey) + SEP + undecidedOffers + SEP
			+ (partyContractId == null ? "" : partyContractId);
	}

	/**
	 * The party page's blocks: members working one shared contract collapse
	 * into a single group, everyone else gets a group of their own.
	 *
	 * Grouped on the proposal id AND the monster name, never the id alone. Two
	 * clients that agree on the id but disagree on the quarry are not on the
	 * same contract by any reading, and merging them would draw one pooled
	 * meter over two different jobs; splitting them renders the disagreement,
	 * which is the honest answer and is also self-limiting — every input here
	 * is self-reported, so a hostile client that guesses an id can at worst
	 * appear beside the party under the party's own monster name.
	 *
	 * A member who claims a contract id nobody else claims still forms a
	 * SHARED group of one: their partner may have left the party or gone
	 * quiet, and the carry clause has not fired yet. Saying "solo" there would
	 * be a guess about someone else's state.
	 */
	public static List<Group> group(@Nullable List<Row> rows)
	{
		if (rows == null || rows.isEmpty())
		{
			return Collections.emptyList();
		}
		// LinkedHashMap: the row order is already the display order, so the
		// group order falls out of first appearance and self stays on top
		Map<String, List<Row>> buckets = new LinkedHashMap<>();
		for (Row row : rows)
		{
			buckets.computeIfAbsent(groupKey(row), k -> new ArrayList<>()).add(row);
		}
		List<Group> out = new ArrayList<>(buckets.size());
		for (List<Row> members : buckets.values())
		{
			Long contractId = null;
			String taskName = null;
			int done = 0;
			int required = 0;
			for (Row row : members)
			{
				if (row.getKillsRequired() > 0)
				{
					contractId = row.getPartyContractId();
					taskName = row.getTaskName();
					done = Math.max(done, row.getKillsDone());
					required = Math.max(required, row.getKillsRequired());
				}
			}
			out.add(new Group(contractId, taskName,
				Collections.unmodifiableList(members), done, required));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * A row's bucket. Members on one shared contract share a key; everybody
	 * else keys on their own member id, which no other row can collide with.
	 *
	 * The separator matters here for the same reason it does in
	 * {@link #signature}: without it a contract id ending in 7 on "Goblin"
	 * and one ending in 70 on "oblin" would flatten to one bucket.
	 */
	private static String groupKey(Row row)
	{
		if (row.getPartyContractId() != null && row.getKillsRequired() > 0)
		{
			return "c" + SEP + row.getPartyContractId() + SEP
				+ (row.getTaskName() == null ? "" : row.getTaskName());
		}
		return "m" + SEP + row.getMemberId();
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

	/**
	 * A party member's display label, never empty.
	 *
	 * <p>PartyMember.displayName is initialised to the literal string
	 * {@code "<unknown>"} and stays that way until the member's own client
	 * announces — so it is NOT null, {@link #clip} passes it straight through,
	 * and the party page printed a placeholder from RuneLite's internals as if
	 * it were somebody's RSN. Treat it as absent and use the same wording as a
	 * genuinely missing name.
	 */
	static String memberName(@Nullable String displayName)
	{
		String clipped = clip(displayName, NAME_MAX);
		return clipped == null || "<unknown>".equals(clipped) ? "A party member" : clipped;
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
			mine.isTainted(), mine.getAccountKey(), mine.isUndecidedOffers(),
			mine.getPartyContractId());
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
			state.getTaint() > 0,
			accountKeyService.key(),
			taskService.hasPendingOffers(),
			// a contract that has CARRIED to solo is no longer shared, and
			// isParty() already says so — keeping the id past the carry would
			// group this member with a party that no longer exists
			task != null && task.isParty() ? task.getPartyProposalId() : null);
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
			String name = memberName(member.getDisplayName());
			if (id == selfId)
			{
				// our own row reads the same source we send, not our echo (which
				// never arrives): the page shows exactly what the party is told
				out.add(new Row(id, name, true, true, true,
					PartyRollService.parseStyle(mine.getAllowedStyle()),
					mine.getCombatLevel(), mine.getActiveTaskName(),
					mine.getKillsDone(), mine.getKillsRequired(), mine.isTainted(),
					AccountKey.normalize(mine.getAccountKey()), mine.isUndecidedOffers(),
					mine.getPartyContractId(), member.getAvatar()));
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
				live && m.isTainted(),
				// normalize, never trust: a remote key is self-reported, so a
				// malformed or over-long one becomes "claims nothing" rather
				// than a 4KB string handed to a tooltip
				live ? AccountKey.normalize(m.getAccountKey()) : null,
				live && m.isUndecidedOffers(),
				live ? m.getPartyContractId() : null,
				// NOT gated on `live`: the avatar comes from RuneLite's own party
				// layer, not our channel, so a member whose Gachaman broadcast has
				// gone stale still has a face — greying the row is the panel's job
				member.getAvatar()));
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
				.append(r.isLoggedIn()).append(SEP)
				// all three are drawn: the key picks a patron pip, the flag prints
				// "undecided", and the contract id decides which BLOCK the row
				// lands in. Omitting the id would leave two members visibly
				// merging into one group with no repaint to show it
				.append(r.getAccountKey() == null ? "" : r.getAccountKey()).append(SEP)
				.append(r.isUndecidedOffers()).append(SEP)
				.append(r.getPartyContractId() == null ? "" : r.getPartyContractId()).append(SEP);
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
