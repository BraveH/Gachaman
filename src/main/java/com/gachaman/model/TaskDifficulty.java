package com.gachaman.model;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TaskDifficulty
{
	EASY("Easy", new Color(120, 200, 120), 0.45, 15, 25),
	MEDIUM("Medium", new Color(240, 200, 80), 0.75, 30, 45),
	HARD("Hard", new Color(240, 130, 60), 1.05, 50, 75),
	INSANE("Insane", new Color(230, 60, 60), 1.35, 80, 120);

	private final String displayName;
	private final Color color;
	/** Max monster combat level as a fraction of the player's combat level. */
	private final double cbCapFraction;
	private final int minKills;
	private final int maxKills;
}
