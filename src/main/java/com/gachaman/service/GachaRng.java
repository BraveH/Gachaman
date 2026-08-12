package com.gachaman.service;

import java.security.*;
import java.util.*;
import javax.inject.*;

/** Central RNG. Tests construct with a fixed seed; production uses SecureRandom seeding. */
@Singleton
public class GachaRng {
	private final Random random;

	public GachaRng() {
		this.random = new Random(new SecureRandom().nextLong());
	}

	public GachaRng(long seed) {
		this.random = new Random(seed);
	}

	public int nextInt(int bound) {
		return random.nextInt(bound);
	}

	public int between(int minInclusive, int maxInclusive) {
		return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
	}

	public double nextDouble() {
		return random.nextDouble();
	}

	public boolean chance(double probability) {
		return random.nextDouble() < probability;
	}

	public <T> T pick(List<T> list) {
		return list.get(random.nextInt(list.size()));
	}
}
