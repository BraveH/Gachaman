package com.gachaman.model;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * One entry in the fortune timeline: an audit line for a roll, pull, equip or
 * event. Text is stored PLAIN (the tab escapes/colors it at render time);
 * {@code kind} keys the color and {@code meta} optionally refines it (an
 * AttackStyle / Rarity / TaskDifficulty name).
 */
@Value
public class TimelineEvent
{
	long at; // epoch ms
	String kind;
	String text;
	@Nullable
	String meta;

	// kind keys (plain strings so Gson stays trivially forward-compatible)
	public static final String KIND_STYLE = "STYLE";
	public static final String KIND_OFFERS = "OFFERS";
	public static final String KIND_ACCEPT = "ACCEPT";
	public static final String KIND_COMPLETE = "COMPLETE";
	public static final String KIND_CHEST = "CHEST";
	public static final String KIND_CARD = "CARD";
	public static final String KIND_EQUIP = "EQUIP";
	public static final String KIND_LUCK = "LUCK";
	public static final String KIND_REROLL = "REROLL";
	public static final String KIND_CHARGE = "CHARGE";
	public static final String KIND_VIOLATION = "VIOLATION";
	public static final String KIND_TAINT = "TAINT";
	public static final String KIND_CLEANSE = "CLEANSE";
}
