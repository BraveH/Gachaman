package com.gachaman.model;

import lombok.Value;
import lombok.With;

@Value
@With
public class SideBet
{
	public enum Kind
	{
		BIG_HIT,        // land a single hit >= threshold
		DAMAGELESS_KILL,// complete one kill without taking damage
		SPEED_KILLS,    // K kills within T ticks
		CLUTCH_KILL     // finish a kill while under 25% HP
	}

	Kind kind;
	int threshold;   // BIG_HIT: min hit; SPEED_KILLS: kill count
	int windowTicks; // SPEED_KILLS only
	boolean sealed;  // objective hidden until satisfied
	boolean completed;
	int payoutGc;
}
