package com.gachaman.model;

import lombok.*;

@Value
@With
public class MonsterStats {
	long kills;
	long gcEarned;
	int tasksCompleted;
}
