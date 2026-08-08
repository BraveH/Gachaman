package com.gachaman.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/** The four bundled datasets must parse and cross-reference cleanly. */
public class DatasetIntegrityTest
{
	private static final Set<String> MONSTER_TAGS = Set.of(
		"starter", "giant", "demon", "dragon", "undead", "slayer", "wilderness", "tzhaar",
		"kalphite", "spider", "reptile", "avian", "fremennik", "desert", "morytania",
		"kourend", "varlamore", "elemental", "fairy", "goblinoid", "boss", "sailing");

	private JsonObject load(String name)
	{
		try (InputStreamReader reader = new InputStreamReader(
			getClass().getResourceAsStream("/com/gachaman/data/" + name), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(reader, JsonObject.class);
		}
		catch (Exception e)
		{
			throw new AssertionError("failed to load " + name, e);
		}
	}

	@Test
	public void tiersParseWithLaddersAndHolograms()
	{
		JsonObject tiers = load("tiers.json");
		JsonArray ladders = tiers.getAsJsonArray("ladders");
		Assert.assertTrue(ladders.size() >= 3);
		Set<String> tierKeys = new HashSet<>();
		for (JsonElement ladderEl : ladders)
		{
			JsonObject ladder = ladderEl.getAsJsonObject();
			for (JsonElement tierEl : ladder.getAsJsonArray("tiers"))
			{
				JsonObject tier = tierEl.getAsJsonObject();
				Assert.assertTrue(tier.get("rank").getAsInt() >= 1);
				Assert.assertTrue(tier.getAsJsonArray("prefixes").size() >= 1);
				// a tier with no requirements fails closed and vanishes from gated rolls
				String key = tier.get("tierKey").getAsString();
				Assert.assertTrue(key + " needs reqLevel", tier.has("reqLevel"));
				Assert.assertTrue(key + " needs reqDefence", tier.has("reqDefence"));
				Assert.assertTrue(key + " reqLevel >= 1", tier.get("reqLevel").getAsInt() >= 1);
				Assert.assertTrue(key + " reqDefence >= 1", tier.get("reqDefence").getAsInt() >= 1);
				tierKeys.add(key);
			}
		}
		JsonArray holograms = tiers.getAsJsonArray("hologramTiers");
		Assert.assertTrue(holograms.size() >= 10);
		for (JsonElement holoEl : holograms)
		{
			JsonObject holo = holoEl.getAsJsonObject();
			Assert.assertTrue("holo tier unknown: " + holo.get("tierKey"),
				tierKeys.contains(holo.get("tierKey").getAsString()));
			com.gachaman.model.Rarity.valueOf(holo.get("rarity").getAsString()); // must parse
		}
	}

	@Test
	public void monstersParseWithLegalTagsAndLevels()
	{
		JsonArray monsters = load("monsters.json").getAsJsonArray("monsters");
		Assert.assertTrue(monsters.size() >= 200);
		Set<String> names = new HashSet<>();
		for (JsonElement monsterEl : monsters)
		{
			JsonObject monster = monsterEl.getAsJsonObject();
			String name = monster.get("name").getAsString();
			Assert.assertTrue("duplicate monster " + name, names.add(name));
			int level = monster.get("combatLevel").getAsInt();
			Assert.assertTrue("bad level " + name, level >= 1 && level <= 1600);
			for (JsonElement tag : monster.getAsJsonArray("tags"))
			{
				Assert.assertTrue("illegal tag " + tag + " on " + name,
					MONSTER_TAGS.contains(tag.getAsString()));
			}
		}
	}

	@Test
	public void bossesParseWithMilestones()
	{
		JsonArray bosses = load("bosses.json").getAsJsonArray("bosses");
		Assert.assertTrue(bosses.size() >= 15);
		for (JsonElement bossEl : bosses)
		{
			JsonObject boss = bossEl.getAsJsonObject();
			Assert.assertFalse(boss.get("chatName").getAsString().isEmpty());
			Assert.assertFalse(boss.get("setTag").getAsString().isEmpty());
			JsonArray milestones = boss.getAsJsonArray("kcMilestones");
			Assert.assertTrue(milestones.size() >= 2);
			int prev = 0;
			for (JsonElement milestone : milestones)
			{
				int m = milestone.getAsInt();
				Assert.assertTrue("milestones not ascending", m > prev);
				prev = m;
			}
		}
	}

	@Test
	public void setsParseWithLegalPerks()
	{
		JsonArray sets = load("sets.json").getAsJsonArray("sets");
		Assert.assertTrue(sets.size() >= 15);
		Set<String> keys = new HashSet<>();
		for (JsonElement setEl : sets)
		{
			JsonObject set = setEl.getAsJsonObject();
			Assert.assertTrue("dup set key", keys.add(set.get("setKey").getAsString()));
			Assert.assertTrue(set.getAsJsonArray("cardNames").size() >= 2);
			JsonObject perk = set.getAsJsonObject("perk");
			SetTable.PerkType.valueOf(perk.get("type").getAsString());
			SetTable.PerkScope scope = SetTable.PerkScope.valueOf(perk.get("scope").getAsString());
			int magnitude = perk.get("magnitudePercent").getAsInt();
			Assert.assertTrue(magnitude >= 1 && magnitude <= 10);
			if (scope != SetTable.PerkScope.GLOBAL)
			{
				Assert.assertTrue(perk.getAsJsonArray("scopeValues").size() >= 1);
			}
		}
	}

	/**
	 * Every boss setTag must resolve to a real set. A dangling tag makes
	 * ChestService.roll() fall through to the whole card pool, so the "themed"
	 * milestone chest silently rolls random gear.
	 */
	@Test
	public void everyBossSetTagResolvesToASet()
	{
		Set<String> setKeys = new HashSet<>();
		for (JsonElement setEl : load("sets.json").getAsJsonArray("sets"))
		{
			setKeys.add(setEl.getAsJsonObject().get("setKey").getAsString());
		}
		for (JsonElement bossEl : load("bosses.json").getAsJsonArray("bosses"))
		{
			JsonObject boss = bossEl.getAsJsonObject();
			String tag = boss.get("setTag").getAsString();
			Assert.assertTrue(
				boss.get("bossName").getAsString() + " has setTag '" + tag + "' with no set in sets.json",
				setKeys.contains(tag));
		}
	}

	/**
	 * Milestone claims are keyed by bossName and BossKcService stops at the first
	 * chatName match, so a repeat of either silently swallows another boss's chests.
	 * Set tags are deliberately shared (the three Dagannoth Kings, the Wilderness
	 * singles pairs) and are not checked here.
	 */
	@Test
	public void bossNamesAndChatNamesAreUnique()
	{
		Set<String> bossNames = new HashSet<>();
		Set<String> chatNames = new HashSet<>();
		for (JsonElement bossEl : load("bosses.json").getAsJsonArray("bosses"))
		{
			JsonObject boss = bossEl.getAsJsonObject();
			String bossName = boss.get("bossName").getAsString();
			Assert.assertTrue("duplicate bossName " + bossName, bossNames.add(bossName));
			String chatName = boss.get("chatName").getAsString().toLowerCase();
			Assert.assertTrue("duplicate chatName " + chatName + " on " + bossName,
				chatNames.add(chatName));
		}
	}

	/**
	 * BossKcService captures the name lazily, up to the counter noun, so a chatName
	 * that swallows the noun ("Barrows chest") can never match.
	 */
	@Test
	public void bossChatNamesExcludeTheCounterNoun()
	{
		for (JsonElement bossEl : load("bosses.json").getAsJsonArray("bosses"))
		{
			JsonObject boss = bossEl.getAsJsonObject();
			String chatName = boss.get("chatName").getAsString();
			for (String noun : new String[]{"kill", "chest", "harvest", "completion"})
			{
				Assert.assertFalse(chatName + " ends with the counter noun '" + noun + "'",
					chatName.toLowerCase().endsWith(" " + noun));
			}
		}
	}

	@Test
	public void loadersLoadTheRealFiles()
	{
		Gson gson = new Gson();
		Assert.assertFalse(MonsterTable.load(gson).getMonsters().isEmpty());
		Assert.assertFalse(BossTable.load(gson).getBosses().isEmpty());
		Assert.assertFalse(SetTable.load(gson).getSets().isEmpty());
		TierTable tiers = TierTable.load(gson);
		Assert.assertFalse(tiers.getHolograms().isEmpty());
		TierTable.Match match = tiers.match("Rune scimitar");
		Assert.assertNotNull(match);
		Assert.assertEquals("rune", match.getTierKey());
		Assert.assertEquals("scimitar", match.getFamilyKey());
		TierTable.Match dhide = tiers.match("Black d'hide body");
		Assert.assertNotNull(dhide);
		Assert.assertEquals("body", dhide.getFamilyKey());
		// black metal is rank 4, but non-metal "Black ..." items must stay untiered
		TierTable.Match blackMetal = tiers.match("Black platebody");
		Assert.assertNotNull(blackMetal);
		Assert.assertEquals(4, blackMetal.getRank());
		Assert.assertNull("Black mask is slayer gear, not metal", tiers.match("Black mask"));
		Assert.assertNull("Black wizard hat is robes, not metal", tiers.match("Black wizard hat"));
		Assert.assertNull("White apron is cosmetic, not metal", tiers.match("White apron"));
	}
}
