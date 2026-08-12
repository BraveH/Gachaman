package com.gachaman.model;

import lombok.*;

@Value
@With
public class PersonalBest {
	long fastestTaskMs;   // 0 = none yet
	String fastestMonster;
	int biggestHaulGc;    // completion + side bets + per-kill sum for one task
	String biggestHaulMonster;
}
