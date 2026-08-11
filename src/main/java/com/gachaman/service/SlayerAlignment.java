package com.gachaman.service;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.slayer.SlayerConfig;
import lombok.RequiredArgsConstructor;

/**
 * Double Docket: does a Gachaman contract's target intersect the player's live
 * Slayer assignment?
 *
 * The Slayer plugin's own {@code SlayerPluginService} is bound inside that
 * plugin's CHILD injector ({@code SlayerPlugin implements Module}), so it
 * cannot be injected from here at all — it would fail at injection time. The
 * assignment is therefore read out of the persisted config the Slayer plugin
 * writes, which is a public contract of the client.
 *
 * Everything that touches {@link Client} or {@link ConfigManager} lives in
 * {@link #liveTarget()}; the matching RULE below it is pure static and fully
 * unit tested, because no mocking framework is available in this project.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SlayerAlignment {
	private final Client client;
	private final ConfigManager configManager;

	/**
	 * The name of the LIVE Slayer assignment, or null when there is none.
	 *
	 * The remaining-count varp is the authority on liveness, NOT the config.
	 * SlayerPlugin.updateTask() writes setTask("", 0, 0) when the count drops
	 * to zero, so while that plugin is ENABLED the persisted name self-clears —
	 * but with it disabled the config freezes at the last assignment the player
	 * ever had, and a stale name with 0 remaining must never pay the bonus. The
	 * varp is server-written and correct whether or not the Slayer plugin is
	 * installed. The config is still needed for the NAME (the varp holds a
	 * count, not a string), so both are read: varp for "is it live", config for
	 * "what is it".
	 *
	 * SlayerPlugin persists these with setRSProfileConfiguration, so this MUST
	 * read them with getRSProfileConfiguration — getConfiguration("slayer",
	 * "taskName") returns null. That call also returns null when there is no RS
	 * profile key (not logged in), which is exactly the answer we want.
	 */
	@Nullable
	public String liveTarget() {
		if (client == null || configManager == null) {
			return null;
		}
		if (client.getVarpValue(VarPlayerID.SLAYER_COUNT) <= 0) {
			return null;
		}
		String name = configManager.getRSProfileConfiguration(
			SlayerConfig.GROUP_NAME, SlayerConfig.TASK_NAME_KEY);
		return name == null || name.isEmpty() ? null : name;
	}

	// ------------------------------------------------------------------
	// The rule. Pure statics — no Client, no ConfigManager, no state.
	// ------------------------------------------------------------------

	/**
	 * Does a contract target intersect a Slayer assignment?
	 *
	 * Contract targets are singular NPC names ("Fire giant"); Slayer
	 * assignments are plural category names ("Fire giants"), so the assignment
	 * is singularised and compared against the contract. Either side null or
	 * blank is NO match — no Slayer task means no bonus, never a default-on.
	 *
	 * KNOWN AND DELIBERATE GAP: set-valued assignments ("Metal dragons",
	 * "Bandits", "Fever spiders") name a category no NPC is called, so they
	 * cannot be resolved from the name alone. Slayer's own Task enum, which
	 * holds that mapping, is package-private and unreachable from here, and
	 * shipping a hand-written table would be asserting game data that cannot be
	 * verified against real code. The failure mode is a WITHHELD bonus, never a
	 * wrongly paid one, which is the correct direction to fail; the sidebar
	 * tooltip states the limitation with this exact example.
	 */
	static boolean matches(@Nullable String contractMonster, @Nullable String slayerTarget) {
		String contract = normalize(contractMonster);
		String target = normalize(slayerTarget);
		if (contract.isEmpty() || target.isEmpty()) {
			return false;
		}
		for (String candidate : singularCandidates(target)) {
			if (candidate.isEmpty()) {
				continue;
			}
			if (contract.equals(candidate)) {
				return true;
			}
			// One-directional, word-boundary prefix: an assignment BROADER than
			// the contract genuinely covers it ("Kalphites" covers "Kalphite
			// Worker", "Elves" covers "Elf archer"), while the reverse is not a
			// real relationship. The trailing space is LOAD-BEARING — a bare
			// startsWith would make "Rats" match "Ratcatcher".
			if (contract.startsWith(candidate + " ")) {
				return true;
			}
		}
		return false;
	}

	/** Lowercase, letters/digits/spaces only, whitespace collapsed, no edge spaces. */
	static String normalize(@Nullable String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		boolean pendingSpace = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				if (pendingSpace && sb.length() > 0) {
					sb.append(' ');
				}
				pendingSpace = false;
				sb.append(Character.toLowerCase(c));
			}
			else {
				pendingSpace = true;
			}
		}
		return sb.toString();
	}

	/**
	 * Every plausible singular of an already-normalized name, applied to the
	 * LAST word only ("fire giants" -> "fire giant").
	 *
	 * English pluralisation is not a function — the ies->y rule is right for
	 * "jellies" and wrong for "zombies" — so guessing one form guarantees a
	 * class of silent misses. Generating the whole candidate set is safe
	 * because a wrong candidate ("zomby") is a non-word that matches no
	 * monster. The name itself is always a candidate, so an assignment with no
	 * plural form ("Nechryael") still works.
	 */
	static List<String> singularCandidates(String normalized) {
		List<String> out = new ArrayList<>(5);
		out.add(normalized);
		int cut = normalized.lastIndexOf(' ');
		String head = cut < 0 ? "" : normalized.substring(0, cut + 1);
		String last = cut < 0 ? normalized : normalized.substring(cut + 1);
		if (last.length() > 1 && last.endsWith("s")) {
			out.add(head + last.substring(0, last.length() - 1));        // giants -> giant
		}
		if (last.length() > 3 && last.endsWith("ies")) {
			out.add(head + last.substring(0, last.length() - 3) + "y");  // jellies -> jelly
		}
		if (last.length() > 3 && last.endsWith("ves")) {
			out.add(head + last.substring(0, last.length() - 3) + "f");  // elves -> elf
			out.add(head + last.substring(0, last.length() - 3) + "fe"); // knives -> knife
		}
		if (last.length() > 2 && last.endsWith("es")) {
			out.add(head + last.substring(0, last.length() - 2));        // boxes -> box
		}
		return out;
	}
}
