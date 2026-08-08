package com.gachaman.service;

import com.gachaman.model.ActiveTask;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.SideBet;
import com.gachaman.model.TaskOffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The Service Record: every card counts the kills it was present for, and the
 * number is permanent.
 *
 * <p>"Present for" = the owned card's uuid was assigned to a loadout slot at
 * the moment the kill was credited. Consequences, all deliberate:
 *
 * <ul>
 * <li>It counts EVERY kill {@link KillTracker} emits — off-task and tainted
 * kills included. The record measures how much fighting the card was carried
 * through, not how well the player obeyed a contract. This is why the tally
 * listens to KillTracker directly and never sits behind TaskService's off-task
 * early return.</li>
 * <li>It counts REAL kills, not contract credit. A Compactor doubles
 * killsDone but buys no extra service; an ironman's assisted half-credit still
 * bought the card a whole kill of wear.</li>
 * <li>It counts each assigned card ONCE per kill, distinct by uuid. A hologram
 * occupies exactly one slot today, but the set-based credit makes that
 * assumption non-load-bearing.</li>
 * <li>The loadout is read at EMIT time, not death time. Kills are emitted a few
 * ticks late (the loot oracle settles first) and there is no historical loadout
 * record; a deliberate reassignment inside that gap is rare enough to accept.
 * Do not build a loadout-snapshot ring buffer to close it.</li>
 * </ul>
 *
 * <p>The album says "present for N kills" — never "killed N" — so the wording
 * matches the rule.
 *
 * <p>Kills bank in a transient map and are written through
 * {@link GachaStateService#mutate} only at contract completion and at the
 * boundaries where the tally would otherwise die with the client. mutate()
 * saves on every call and save() gzips + SHA-256s the whole state
 * synchronously before the disk debounce, so a per-kill write is a genuine
 * hot-path hazard.
 */
@Slf4j
@Singleton
public class ServiceRecordService implements KillTracker.KillListener, TaskService.Listener
{
	private final GachaStateService stateService;

	/** owned-card uuid -> kills tallied since the last flush. Never persisted. */
	private final Map<String, Integer> pending = new HashMap<>();

	@Inject
	public ServiceRecordService(GachaStateService stateService)
	{
		this.stateService = stateService;
	}

	// --- KillTracker.KillListener ---

	@Override
	public synchronized void onKill(KillTracker.Kill kill)
	{
		GachaState state = stateService.get();
		if (state != null)
		{
			creditKill(pending, state.getLoadout());
		}
	}

	// --- TaskService.Listener ---

	@Override
	public void onTaskCompleted(TaskService.TaskCompletionSummary summary)
	{
		flush();
	}

	@Override
	public void onKillFeedback(TaskService.KillFeedback feedback)
	{
	}

	@Override
	public void onSideBetHit(SideBet bet, String monsterName)
	{
	}

	@Override
	public void onOffersRolled(List<TaskOffer> offers)
	{
	}

	@Override
	public void onPartyProgress(ActiveTask task)
	{
	}

	// --- flush boundaries ---

	/**
	 * Write the banked tally into the persisted records. Runs only at contract
	 * completion and at the exit boundaries (logout, plugin shutdown, client
	 * shutdown), never per kill.
	 */
	public synchronized void flush()
	{
		if (pending.isEmpty())
		{
			return;
		}
		Map<String, Integer> tally = new HashMap<>(pending);
		// cleared BEFORE the write: a failed write costs a handful of kills, a
		// re-runnable tally would over-count them permanently. For a cosmetic
		// odometer, under-counting is the correct failure direction.
		pending.clear();
		stateService.mutate(s -> {
			List<OwnedCard> updated = applyTally(s.getOwnedCards(), tally);
			return updated == null ? s : s.withOwnedCards(updated);
		});
	}

	/**
	 * Throw the tally away unwritten. RuneScapeProfileChanged fires AFTER the
	 * profile key has moved, so these kills can no longer be attributed to the
	 * account that earned them; writing them would credit another character's
	 * cards. The LOGIN_SCREEN handler already flushed under the right key.
	 */
	public synchronized void drop()
	{
		pending.clear();
	}

	/** Kills tallied for an owned card but not yet written. */
	public synchronized int pendingFor(String ownedCardUuid)
	{
		return pending.getOrDefault(ownedCardUuid, 0);
	}

	// --- pure rules ---

	/**
	 * +1 kill of service for every card assigned to a loadout slot, distinct by
	 * uuid: a card that somehow occupied two slots still served one kill.
	 */
	static void creditKill(Map<String, Integer> tally, @Nullable Map<String, String> loadout)
	{
		if (loadout == null || loadout.isEmpty())
		{
			return;
		}
		for (String uuid : new HashSet<>(loadout.values()))
		{
			if (uuid != null)
			{
				tally.merge(uuid, 1, Integer::sum);
			}
		}
	}

	/**
	 * Apply a tally, preserving list order. Returns null when nothing matched so
	 * the caller hands mutate() an unchanged state and skips the encode
	 * entirely. Tallied uuids no longer owned (burned, prestiged away) are
	 * dropped — their service died with them.
	 */
	@Nullable
	static List<OwnedCard> applyTally(@Nullable List<OwnedCard> cards, Map<String, Integer> tally)
	{
		if (cards == null || cards.isEmpty() || tally.isEmpty())
		{
			return null;
		}
		List<OwnedCard> out = new ArrayList<>(cards.size());
		boolean changed = false;
		for (OwnedCard card : cards)
		{
			int add = tally.getOrDefault(card.getUuid(), 0);
			if (add > 0)
			{
				out.add(card.withKillsServed(card.getKillsServed() + add));
				changed = true;
			}
			else
			{
				out.add(card);
			}
		}
		return changed ? out : null;
	}

	/**
	 * Card id -> highest service record among the owned copies. The album is a
	 * catalogue of card DEFINITIONS while the record is per copy; the veteran
	 * copy is the one the player means. MAX, not SUM: a summed number would not
	 * match any single card and would inflate a figure the player checks
	 * against their own kill count. Holograms are excluded — they carry cardId
	 * -1 and never enter the grid.
	 */
	public static Map<Integer, Integer> bestByCardId(@Nullable List<OwnedCard> cards)
	{
		Map<Integer, Integer> best = new HashMap<>();
		if (cards != null)
		{
			for (OwnedCard card : cards)
			{
				if (!card.isHologram())
				{
					best.merge(card.getCardId(), card.getKillsServed(), Math::max);
				}
			}
		}
		return best;
	}
}
