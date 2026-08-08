package com.gachaman.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@With
@Builder(toBuilder = true)
public class ActiveTask
{
	TaskDifficulty difficulty;
	String monsterName;
	int monsterCombatLevel;
	int killsRequired;
	int killsDone;
	int perKillGc;
	int completionGc;
	List<SideBet> sideBets;
	boolean redemption;
	long acceptedAtMs;
	/** COMPACTOR / EXTENDER charge consumed by this task, or null. */
	String appliedCharge;
	/**
	 * Ironman assisted-kill carry: an assisted kill counts half, so the first
	 * one banks a pending half and the second completes the count.
	 */
	boolean halfKillPending;

	// Duo contract fields (null/0 when solo)
	String duoPartnerName;
	long duoPartnerMemberId;
	int duoPartnerKills;
	AttackStyle duoPartnerStyle;
	boolean duoConvertedToSolo; // carry clause applied

	public boolean isDuo()
	{
		return duoPartnerName != null && !duoConvertedToSolo;
	}
}
