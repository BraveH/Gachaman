package com.gachaman.model;

import java.util.*;
import lombok.*;

/**
 * The totals line above the Dossier list. A pure fold over the contract log —
 * NEVER persisted, so it can be recomputed and reshaped freely without touching
 * the save schema. Derived on render rather than maintained incrementally,
 * because an incrementally-kept total drifts the moment the log evicts its
 * oldest record and nothing can ever reconcile it.
 */
@Value
public class DossierSummary {
	int contracts;
	int cleanContracts;
	int partyContracts;
	long totalKills;
	long totalGc;
	long totalDurationMs;
	/** Best single haul in the retained window, 0 when the log is empty. */
	long bestGc;

	public static DossierSummary of(List<ContractRecord> log) {
		int contracts = 0;
		int clean = 0;
		int party = 0;
		long kills = 0;
		long gc = 0;
		long duration = 0;
		long best = 0;
		if (log != null) {
			for (ContractRecord record : log) {
				if (record == null) {
					// Gson writes null ARRAY elements regardless of serializeNulls,
					// so a hole can genuinely come back off disk
					continue;
				}
				contracts++;
				if (record.isClean()) {
					clean++;
				}
				if (record.isParty()) {
					party++;
				}
				kills += record.getKills();
				gc += record.getGc();
				duration += Math.max(0, record.getDurationMs());
				best = Math.max(best, record.getGc());
			}
		}
		return new DossierSummary(contracts, clean, party, kills, gc, duration, best);
	}

	/**
	 * Whole-percent clean rate, FLOORED rather than rounded: 199 clean out of
	 * 200 must not display as "100%" while a blemish is still on the record.
	 */
	public int cleanPercent() {
		return contracts <= 0 ? 0 : (int) (cleanContracts * 100L / contracts);
	}

	/** 0..1 for the meter; 0 on an empty log rather than a divide by zero. */
	public double cleanFraction() {
		return contracts <= 0 ? 0d : (double) cleanContracts / contracts;
	}

	public long averageGc() {
		return contracts <= 0 ? 0 : totalGc / contracts;
	}

	public long averageDurationMs() {
		return contracts <= 0 ? 0 : totalDurationMs / contracts;
	}
}
