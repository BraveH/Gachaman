package com.gachaman.service;

import com.gachaman.data.MonsterTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.util.Text;

/**
 * Attributes NPC kills to the local player via engagement windows:
 * InteractingChanged + own hitsplats refresh the window; ActorDeath within
 * ENGAGEMENT_TICKS credits the kill.
 *
 * Assist detection (the ironman half-credit rule) combines three signals:
 * (1) any other-player hitsplat on the NPC — including 0-damage splashes,
 *     which still void ironman credit — sticky for the NPC's lifetime;
 * (2) the game's own "might not receive kill-credit" warning at attack time
 *     (server-authoritative, covers damage dealt before the NPC entered the
 *     scene; toggleable in the game's Activities settings);
 * (3) the loot oracle: kills are emitted a couple of ticks AFTER despawn so
 *     the server's loot events can be observed. Loot received = proof of
 *     full credit (overrides any suspicion — rescues thralls, groupmates and
 *     NPC-inflicted damage); loot absent on a monster with a guaranteed drop
 *     = denial, catching fully off-scene assists even with the warning off.
 */
@Slf4j
@Singleton
public class KillTracker
{
	private static final int ENGAGEMENT_TICKS = 7;
	private static final int SWEEP_INTERVAL = 25;
	/**
	 * The game's ironman warning ("As an Iron Man, you might not receive
	 * kill-credit for this monster.") fires at ATTACK time.
	 */
	private static final String KILL_CREDIT_WARNING = "might not receive kill-credit";
	/** Emit a pending kill this many ticks after its despawn (loot has landed). */
	private static final int LOOT_SETTLE_TICKS = 2;
	/** Hard emission timeout from death (delayed-despawn bosses). */
	private static final int PENDING_TIMEOUT_TICKS = 30;

	@Value
	public static class Kill
	{
		String npcName;
		int npcCombatLevel;
		int npcIndex;
		int tick;
		int engagementStartTick;
		boolean tookDamageDuringEngagement;
		int maxHitDealt;
		/** Another player assisted this kill (final, loot-oracle-adjusted). */
		boolean assistedByOther;
		net.runelite.api.coords.LocalPoint deathLocation;
	}

	public interface KillListener
	{
		void onKill(Kill kill);

		/**
		 * The LOCAL player died. Default no-op so the many listeners that only
		 * care about kills are untouched.
		 *
		 * This rides on the death subscription rather than the "Oh dear, you are
		 * dead" chat line because a safe death (Castle Wars, Barbarian Assault,
		 * a minigame) prints no such line — an observer that watched for the
		 * message would silently miss exactly the deaths that cost nothing to
		 * arrange.
		 */
		default void onLocalPlayerDeath()
		{
		}
	}

	private static class Engagement
	{
		int lastRefreshTick;
		int startTick;
		int maxHit;
		String name;
		int combatLevel;
	}

	/** A credited kill awaiting the server's loot verdict. */
	private static class PendingKill
	{
		Kill kill;           // built at death with the SUSPICION verdict
		int npcId;
		int deathTick;
		int despawnTick = -1;
		boolean lootSeen;
	}

	private final Client client;
	private final MonsterTable monsterTable;
	private final List<KillListener> listeners = new ArrayList<>();
	private final Map<Integer, Engagement> engagements = new HashMap<>();
	/** npc index -> another player's hitsplat seen (sticky for the NPC's life). */
	private final Set<Integer> otherDamaged = new HashSet<>();
	/** npc index -> flagged by the game's ironman kill-credit warning. */
	private final Set<Integer> warnedAssist = new HashSet<>();
	private final List<PendingKill> pendingKills = new ArrayList<>();
	/** Monster name (lowercase) -> has no guaranteed drop (loot absence proves nothing). */
	private Map<String, Boolean> noGuaranteedDropByName;

	/**
	 * True once ANY loot event was observed this session — the absence-based
	 * denial only activates after the loot pipeline has proven itself alive,
	 * so a dead pipeline can never penalize every kill.
	 */
	private boolean lootPipelineLive;

	private int tick;
	private int lastPlayerDamagedTick = -1;

	@Inject
	public KillTracker(Client client, MonsterTable monsterTable)
	{
		this.client = client;
		this.monsterTable = monsterTable;
	}

	public void addListener(KillListener listener)
	{
		if (!listeners.contains(listener))
		{
			listeners.add(listener);
		}
	}

	public void removeListener(KillListener listener)
	{
		listeners.remove(listener);
	}

	public int currentTick()
	{
		return tick;
	}

	public int getLastPlayerDamagedTick()
	{
		return lastPlayerDamagedTick;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tick++;
		emitReadyKills();
		if (tick % SWEEP_INTERVAL == 0)
		{
			engagements.values().removeIf(e -> tick - e.lastRefreshTick > ENGAGEMENT_TICKS * 4);
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getSource() != client.getLocalPlayer() || !(event.getTarget() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getTarget();
		refresh(npc);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			// NPC hits on YOU are *_ME hitsplat types (they involve the local
			// player), so isMine() covers regular incoming damage; DoT types
			// (poison/venom/...) are neither mine nor others and count too.
			// Drains/heals are not HP damage.
			net.runelite.api.Hitsplat hitsplat = event.getHitsplat();
			int type = hitsplat.getHitsplatType();
			boolean hpDamage = hitsplat.isMine()
				|| type == net.runelite.api.HitsplatID.POISON
				|| type == net.runelite.api.HitsplatID.VENOM
				|| type == net.runelite.api.HitsplatID.DISEASE
				|| type == net.runelite.api.HitsplatID.BLEED
				|| type == net.runelite.api.HitsplatID.BURN
				|| type == net.runelite.api.HitsplatID.DOOM;
			if (hpDamage && hitsplat.getAmount() > 0)
			{
				lastPlayerDamagedTick = tick;
			}
			return;
		}
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		if (event.getHitsplat().isOthers())
		{
			// another player attacked this NPC — even a 0-damage splash voids
			// ironman credit, so no amount filter. Sticky until despawn: the
			// game's denial state never expires either.
			otherDamaged.add(npc.getIndex());
			return;
		}
		if (!event.getHitsplat().isMine())
		{
			return;
		}
		Engagement engagement = refresh(npc);
		engagement.maxHit = Math.max(engagement.maxHit, event.getHitsplat().getAmount());
	}

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		// only real game messages — a player could type the phrase in public chat
		if (event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE
			&& event.getType() != net.runelite.api.ChatMessageType.SPAM
			&& event.getType() != net.runelite.api.ChatMessageType.ENGINE)
		{
			return;
		}
		String text = Text.removeTags(event.getMessage()).toLowerCase(java.util.Locale.ROOT);
		if (!text.contains(KILL_CREDIT_WARNING))
		{
			return;
		}
		// the warning refers to the monster just attacked: the current target,
		// falling back to the most recently refreshed engagement
		NPC target = client.getLocalPlayer() != null
			&& client.getLocalPlayer().getInteracting() instanceof NPC
			? (NPC) client.getLocalPlayer().getInteracting() : null;
		if (target != null)
		{
			warnedAssist.add(target.getIndex());
			return;
		}
		int bestIndex = -1;
		int bestTick = -1;
		for (Map.Entry<Integer, Engagement> entry : engagements.entrySet())
		{
			if (entry.getValue().lastRefreshTick > bestTick)
			{
				bestTick = entry.getValue().lastRefreshTick;
				bestIndex = entry.getKey();
			}
		}
		if (bestIndex >= 0)
		{
			warnedAssist.add(bestIndex);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			// The one non-NPC death worth reporting: this player's own. Same
			// event, one branch, so death is observed exactly where kills are.
			if (client != null && event.getActor() instanceof Player
				&& event.getActor() == client.getLocalPlayer())
			{
				for (KillListener listener : new ArrayList<>(listeners))
				{
					try
					{
						listener.onLocalPlayerDeath();
					}
					catch (Exception e)
					{
						log.warn("death listener failed", e);
					}
				}
			}
			return;
		}
		NPC npc = (NPC) event.getActor();
		Engagement engagement = engagements.remove(npc.getIndex());
		boolean suspected = otherDamaged.remove(npc.getIndex()) | warnedAssist.remove(npc.getIndex());
		if (engagement == null || tick - engagement.lastRefreshTick > ENGAGEMENT_TICKS)
		{
			return;
		}
		boolean tookDamage = lastPlayerDamagedTick >= engagement.startTick;
		PendingKill pending = new PendingKill();
		pending.kill = new Kill(engagement.name, engagement.combatLevel, npc.getIndex(), tick,
			engagement.startTick, tookDamage, engagement.maxHit, suspected, npc.getLocalLocation());
		pending.npcId = npc.getId();
		pending.deathTick = tick;
		pendingKills.add(pending);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		int index = event.getNpc().getIndex();
		for (PendingKill pending : pendingKills)
		{
			if (pending.despawnTick < 0 && pending.kill.getNpcIndex() == index)
			{
				pending.despawnTick = tick;
				break;
			}
		}
		// a despawn (death or walk-off) ends the NPC instance: drop its flags
		// so a reused index can never inherit them
		otherDamaged.remove(index);
		warnedAssist.remove(index);
		engagements.remove(index);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		lootPipelineLive = true;
		int index = event.getNpc().getIndex();
		for (PendingKill pending : pendingKills)
		{
			if (!pending.lootSeen && pending.kill.getNpcIndex() == index)
			{
				pending.lootSeen = true;
				return;
			}
		}
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		lootPipelineLive = true;
		if (event.getComposition() == null)
		{
			return;
		}
		int npcId = event.getComposition().getId();
		// Composition, not index — this event carries no index, so several pendings of
		// the same monster are indistinguishable and one of them has to be chosen.
		// FIFO chose the OLDEST, which is the wrong end: loot lands with the death, so
		// the newest unmatched pending is the one it belongs to, while the oldest is
		// typically a delayed-despawn corpse still waiting out PENDING_TIMEOUT_TICKS.
		// Backwards costs TWO verdicts at once — kill an assisted monster, then a clean
		// one of the same type, and the clean kill's loot exonerates the assisted
		// pending while the clean kill is left unproven and convicted on absence. That
		// inversion is precisely what the loot oracle exists to prevent.
		//
		// Ties keep list (death) order, and there is no time window layered on top.
		// Two monsters that die on one tick send two loot events, so taking them
		// first-listed marks both, where refusing to choose would convict a clean kill
		// on the strength of our own ambiguity. And preferring the newest can never
		// refuse a match FIFO would have made — it only changes WHICH pending is
		// credited — so nothing proven today becomes unproven.
		PendingKill best = null;
		for (PendingKill pending : pendingKills)
		{
			if (!pending.lootSeen && pending.npcId == npcId
				&& (best == null || pending.deathTick > best.deathTick))
			{
				best = pending;
			}
		}
		if (best != null)
		{
			best.lootSeen = true;
		}
	}

	/**
	 * The final assist verdict once the loot window closed. Loot received is
	 * server PROOF of full credit and overrides any suspicion; loot absence
	 * convicts only when the pipeline is proven alive AND the monster has a
	 * guaranteed drop.
	 */
	static boolean finalAssisted(boolean lootSeen, boolean suspected, boolean pipelineLive,
		boolean guaranteedDrop)
	{
		if (lootSeen)
		{
			return false;
		}
		return suspected || (pipelineLive && guaranteedDrop);
	}

	private void emitReadyKills()
	{
		if (pendingKills.isEmpty())
		{
			return;
		}
		Iterator<PendingKill> it = pendingKills.iterator();
		while (it.hasNext())
		{
			PendingKill pending = it.next();
			boolean settled = pending.despawnTick >= 0 && tick - pending.despawnTick >= LOOT_SETTLE_TICKS;
			boolean timedOut = tick - pending.deathTick >= PENDING_TIMEOUT_TICKS;
			if (!settled && !timedOut)
			{
				continue;
			}
			it.remove();
			emit(pending);
		}
	}

	private void emit(PendingKill pending)
	{
		Kill draft = pending.kill;
		boolean assisted = finalAssisted(pending.lootSeen, draft.isAssistedByOther(),
			lootPipelineLive, hasGuaranteedDrop(draft.getNpcName()));
		Kill kill = assisted == draft.isAssistedByOther() ? draft
			: new Kill(draft.getNpcName(), draft.getNpcCombatLevel(), draft.getNpcIndex(),
				draft.getTick(), draft.getEngagementStartTick(), draft.isTookDamageDuringEngagement(),
				draft.getMaxHitDealt(), assisted, draft.getDeathLocation());
		for (KillListener listener : new ArrayList<>(listeners))
		{
			try
			{
				listener.onKill(kill);
			}
			catch (Exception e)
			{
				log.warn("kill listener failed", e);
			}
		}
	}

	/** Emit everything still pending with current verdicts (logout hygiene). */
	public void flushPending()
	{
		List<PendingKill> drain = new ArrayList<>(pendingKills);
		pendingKills.clear();
		for (PendingKill pending : drain)
		{
			emit(pending);
		}
		otherDamaged.clear();
		warnedAssist.clear();
		engagements.clear();
	}

	private boolean hasGuaranteedDrop(String npcName)
	{
		if (noGuaranteedDropByName == null)
		{
			noGuaranteedDropByName = new HashMap<>();
			for (MonsterTable.Monster monster : monsterTable.getMonsters())
			{
				noGuaranteedDropByName.put(monster.getName().toLowerCase(java.util.Locale.ROOT),
					monster.isNoGuaranteedDrop());
			}
		}
		Boolean noDrop = noGuaranteedDropByName.get(npcName == null ? ""
			: npcName.toLowerCase(java.util.Locale.ROOT));
		// unknown monsters (off-table) default to NOT convicting on absence
		return noDrop != null && !noDrop;
	}

	public boolean isPlayerLowHp(double fraction)
	{
		int boosted = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int real = client.getRealSkillLevel(Skill.HITPOINTS);
		return real > 0 && boosted <= real * fraction;
	}

	private Engagement refresh(NPC npc)
	{
		Engagement engagement = engagements.get(npc.getIndex());
		if (engagement == null || tick - engagement.lastRefreshTick > ENGAGEMENT_TICKS * 4)
		{
			engagement = new Engagement();
			engagement.startTick = tick;
			engagements.put(npc.getIndex(), engagement);
		}
		engagement.lastRefreshTick = tick;
		String name = npc.getName();
		if (name != null)
		{
			engagement.name = Text.removeTags(name);
		}
		engagement.combatLevel = npc.getCombatLevel();
		return engagement;
	}
}
