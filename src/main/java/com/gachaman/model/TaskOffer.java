package com.gachaman.model;

import java.util.List;
import lombok.Value;

@Value
public class TaskOffer
{
	TaskDifficulty difficulty;
	String monsterName;
	int monsterCombatLevel;
	int killsRequired;
	int perKillGc;
	int completionGc;
	List<SideBet> sideBets;
	boolean redemption; // clears all taint, no per-kill pay
	/** Part of a party roll: clicking VOTES; a majority accepts for everyone. */
	boolean partyRoll;
}
