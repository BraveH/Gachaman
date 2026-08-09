package com.gachaman.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The Firsts Journal: one-time stamps for the early game's inevitable
 * milestones. Each pays a small GC bounty once, and its explainer line
 * doubles as a just-in-time tutorial for the rule it touches.
 */
@Getter
@RequiredArgsConstructor
public enum FirstStamp
{
	FIRST_KILL("First Blood", "Credited kills pay GC — tougher foes pay more."),
	FIRST_TASK("Contract Complete", "Completion bonuses are the income backbone."),
	FIRST_SIDE_BET("Side Hustle", "Side bets pay bonus GC on top of contract rewards."),
	FIRST_CHEST("Chest Cracker", "Chests turn GC into equipment cards."),
	FIRST_ASSIGN("Suited Up", "Assigning a card unlocks wearing that equipment."),
	// accented: both glyphs are in runescape_small.ttf and runescape_bold.ttf, so
	// this renders properly everywhere the journal draws it
	FIRST_DUPE("Déjà Vu", "Duplicate cards auto-convert to GC."),
	FIRST_UNCOMMON("Green Glow", "Uncommon: the first step up the rarity ladder."),
	FIRST_RARE("Blue Blood", "Rares roll near what your levels can wield."),
	FIRST_EPIC("Purple Reign", "Epic or better resets the pity counter."),
	FIRST_SHINY("Star Struck", "Shinies unlock every lower tier of the same piece."),
	FIRST_RECORD("Record Breaker", "New personal bests pay +250 GC every time."),
	FIRST_TAINT_CLEARED("Cleansed", "Compliant kills work taint off; income halves until clear."),
	FIRST_CYCLE("Fate Survived", "Every 5 contracts fate re-rolls your combat style."),
	FIRST_DEED("Landowner", "Slot Deeds unlock new gear slots."),
	FIRST_REROLL_SPENT("Second Chance", "Reroll tokens re-flip one card mid-reveal; +1 per 10 combat levels.");

	private final String displayName;
	private final String explainer;
}
