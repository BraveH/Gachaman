package com.gachaman.model;

import lombok.Value;
import lombok.With;

@Value
@With
public class MonsterStats
{
	long kills;
	long gcEarned;
	int tasksCompleted;
}
