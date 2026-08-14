package com.gachaman.model;

import java.util.*;
import lombok.*;

@Value
public class TaskOffer {
	TaskDifficulty difficulty;
	String monsterName;
	int monsterCombatLevel;
	int killsRequired;
	int perKillGc;
	int completionGc;
	List<SideBet> sideBets;
	boolean redemption; // clears all taint, no per-kill pay
	/**
	 * Part of a party roll: clicking VOTES; a majority accepts for everyone.
	 *
	 * <p>Field-level {@code @With} so only withPartyRoll is generated — this is
	 * the one field anything flips on an existing offer, and it is flipped in
	 * both directions: PartyRollService.executeRoll promotes a freshly generated
	 * board to the party's, and TaskService.demotePartyOffers hands a dissolved
	 * roll's contracts back as personal ones. Both are one-field changes to an
	 * immutable @Value, which is exactly what the wither is for.
	 */
	@With
	boolean partyRoll;
}
