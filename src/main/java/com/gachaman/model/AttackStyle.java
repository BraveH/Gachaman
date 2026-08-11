package com.gachaman.model;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AttackStyle {
	MELEE("Melee", new Color(214, 72, 56)),
	RANGED("Ranged", new Color(80, 175, 68)),
	MAGIC("Magic", new Color(72, 118, 214));

	private final String displayName;
	private final Color color;
}
