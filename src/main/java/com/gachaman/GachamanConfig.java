package com.gachaman;

import com.gachaman.party.PartySizing;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(GachamanConfig.GROUP)
public interface GachamanConfig extends Config {
	String GROUP = "gachaman";

	@ConfigSection(
		name = "General",
		description = "Sounds and celebration settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Enforcement",
		description = "Style-lock warning behavior",
		position = 1
	)
	String enforcementSection = "enforcement";

	@ConfigSection(
		name = "Party",
		description = "Shared party contract settings",
		position = 2
	)
	String partySection = "party";

	@ConfigSection(
		name = "Advanced",
		description = "Safety and developer settings",
		position = 3
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "chatPings",
		name = "Chat notifications",
		description = "Informational chat lines (starter grants, vouchers, milestones)."
			+ " Enforcement feedback — style penalties, pardons, tainted and assisted"
			+ " kills, blocked equips — always shows.",
		section = generalSection,
		position = 2
	)
	default boolean chatPings() {
		return true;
	}

	@ConfigItem(
		// keyName is the persisted setting key and stays "highlightTaskNpc" —
		// renaming it silently resets everyone who had turned this off
		keyName = "highlightTaskNpc",
		name = "Highlight contract NPCs",
		description = "Outline NPCs that match your active contract",
		section = generalSection,
		position = 3
	)
	default boolean highlightTaskNpc() {
		return true;
	}

	@ConfigItem(
		keyName = "oneCardPerSlot",
		name = "One card per slot",
		description = "ON: unlocked equipment is wearable only when its card is assigned to a loadout slot. OFF: owning the card is enough — the loadout page is hidden.",
		section = generalSection,
		position = 4
	)
	default boolean oneCardPerSlot() {
		return true;
	}

	@ConfigItem(
		keyName = "anteEnabled",
		name = "The Ante",
		description = "Offer a voluntary stake on INSANE contracts before you accept them:"
			+ " finish the contract and the stake returns doubled, die and it is gone."
			+ " Off by default — nothing is ever staked without an explicit confirmation,"
			+ " and turning this off simply hides the offer.",
		section = generalSection,
		position = 5
	)
	default boolean anteEnabled() {
		return false;
	}

	@Range(min = 10, max = 300)
	@ConfigItem(
		keyName = "styleWarningSeconds",
		name = "Style warning duration",
		description = "How long the 'style changed - switch gear' chip shows (seconds)",
		section = enforcementSection,
		position = 0
	)
	default int styleWarningSeconds() {
		return 60;
	}

	@ConfigItem(
		keyName = "partyRollsEnabled",
		name = "Party contracts",
		description = "Take part in shared party contract rolls while in a RuneLite Party."
			+ " When off, you count as busy: proposals excuse you automatically, you"
			+ " cannot propose or join, and your client broadcasts no presence — the"
			+ " Party tab shows nothing and tells you why.",
		section = partySection,
		position = 0
	)
	default boolean partyRollsEnabled() {
		return true;
	}

	@ConfigItem(
		keyName = "partySizing",
		name = "Party contract sizing",
		description = "Which combat level a party roll sizes its contracts to."
			+ " Fighting Weight: the party's AVERAGE, so a strong party gets contracts"
			+ " worth its weight. Weakest Man: the party's LOWEST, so every contract is"
			+ " one the weakest member could have taken alone."
			+ " ONLY THE HOST'S SETTING APPLIES — whoever proposes the roll sets the rule"
			+ " for everyone in it, because a seeded roll has to deal the same board on"
			+ " every screen. Yours matters on the rolls you propose.",
		section = partySection,
		position = 1
	)
	default PartySizing partySizing() {
		return PartySizing.FIGHTING_WEIGHT;
	}

	@ConfigItem(
		keyName = "safeModeAbort",
		name = "Combat aborts ceremonies",
		description = "Taking damage or being targeted closes reveals safely (outcome still granted)",
		section = advancedSection,
		position = 0
	)
	default boolean safeModeAbort() {
		return true;
	}

	@ConfigItem(
		keyName = "debugCommands",
		name = "Debug commands",
		description = "Enable ::gacha* developer commands",
		section = advancedSection,
		position = 1
	)
	default boolean debugCommands() {
		return false;
	}
}
