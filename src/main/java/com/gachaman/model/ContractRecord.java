package com.gachaman.model;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * One filed contract in the Dossier: what was hunted, what it paid, and whether
 * it was run clean. Written once at completion and never revised.
 *
 * <p>Difficulty and style are stored as plain enum NAMES rather than the enums
 * themselves. Gson maps an unrecognised constant to null on the way back in, so
 * a record filed by a build with more styles would silently lose its style; a
 * string keeps the filed truth intact and pushes the only failure onto the
 * renderer, which falls back to a neutral colour.
 */
@Value
public class ContractRecord {
	long at; // epoch ms, completion
	String monsterName;
	/** TaskDifficulty name. */
	String difficulty;
	int kills;
	/** The full haul: completion award + side bets + per-kill pay. */
	long gc;
	long durationMs;
	/**
	 * AttackStyle name the contract was RUN under (read before the completion
	 * re-roll), or null for a contract finished before the first style roll.
	 */
	@Nullable
	String style;
	/**
	 * Kills landed while out of style. A COUNT, not a clean flag: a boolean
	 * would have to be named one way round or the other, and either polarity
	 * lies about the records this feature cannot see. Zero is the honest
	 * default for anything unmeasured, and zero reads as clean.
	 */
	int taintedKills;
	/** Party label at signing, or null when solo. */
	@Nullable
	String party;
	/** The party dissolved mid-contract and the carry clause applied. */
	boolean carried;
	boolean redemption;

	/** A contract is clean when every kill landed in the allowed style. */
	public boolean isClean() {
		return taintedKills <= 0;
	}

	public boolean isParty() {
		return party != null;
	}

	/**
	 * Appends {@code record} to a capped log, dropping the oldest entries so the
	 * result never exceeds {@code max}. Returns a NEW list — the state is
	 * immutable and the caller's list may be an unmodifiable view off the codec.
	 *
	 * <p>Trims in a loop rather than assuming the input was already within the
	 * cap: a save written when the cap was higher must converge, not sit
	 * permanently one over.
	 */
	public static List<ContractRecord> appendCapped(@Nullable List<ContractRecord> log,
		ContractRecord record, int max) {
		List<ContractRecord> next = log == null ? new ArrayList<>() : new ArrayList<>(log);
		next.add(record);
		if (max <= 0) {
			// a nonsensical cap must not produce a log that grows forever
			return new ArrayList<>();
		}
		while (next.size() > max) {
			next.remove(0);
		}
		return next;
	}
}
