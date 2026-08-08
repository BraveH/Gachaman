package com.gachaman.data;

import com.gachaman.model.Rarity;
import java.util.HashMap;
import java.util.Map;

/** Curated rarity overrides for iconic gear the stat heuristic misjudges. */
public final class RarityOverrides
{
	private static final Map<String, Rarity> OVERRIDES = new HashMap<>();

	static
	{
		// Legendary chase items
		put(Rarity.LEGENDARY, "Twisted bow", "Scythe of vitur", "Tumeken's shadow",
			"Elysian spirit shield", "Arcane spirit shield", "Spectral spirit shield",
			"Ghrazi rapier", "Sanguinesti staff", "Avernic defender", "Infernal cape",
			"Torva full helm", "Torva platebody", "Torva platelegs", "Ancestral hat",
			"Ancestral robe top", "Ancestral robe bottom", "Kodai wand", "Harmonised orb",
			"Volatile orb", "Eldritch orb", "Nightmare staff", "Inquisitor's great helm",
			"Inquisitor's hauberk", "Inquisitor's plateskirt", "Inquisitor's mace",
			"Dragon hunter crossbow", "Dragon hunter lance", "Dragon claws",
			"Bow of faerdhinen", "Blade of saeldor", "Zaryte crossbow");
		// Epic staples
		put(Rarity.EPIC, "Abyssal whip", "Toxic blowpipe", "Trident of the seas",
			"Trident of the swamp", "Serpentine helm", "Armadyl chestplate",
			"Armadyl chainskirt", "Armadyl helmet", "Bandos chestplate", "Bandos tassets",
			"Bandos boots", "Armadyl godsword", "Bandos godsword", "Saradomin godsword",
			"Zamorak godsword", "Staff of the dead", "Toxic staff of the dead",
			"Occult necklace", "Berserker ring", "Archers ring", "Seers ring",
			"Warrior ring", "Primordial boots", "Pegasian boots", "Eternal boots",
			"Amulet of torture", "Necklace of anguish", "Tormented bracelet",
			"Amulet of fury", "Dragonfire shield", "Spirit shield",
			"Dharok's helm", "Dharok's greataxe", "Dharok's platebody", "Dharok's platelegs",
			"Ahrim's hood", "Ahrim's staff", "Ahrim's robetop", "Ahrim's robeskirt",
			"Karil's coif", "Karil's crossbow", "Karil's leathertop", "Karil's leatherskirt",
			"Guthan's helm", "Guthan's warspear", "Guthan's platebody", "Guthan's chainskirt",
			"Torag's helm", "Torag's hammers", "Torag's platebody", "Torag's platelegs",
			"Verac's helm", "Verac's flail", "Verac's brassard", "Verac's plateskirt");
		// Rare notables
		put(Rarity.RARE, "Barrows gloves", "Fire cape", "Obsidian cape", "Berserker necklace",
			"Amulet of glory", "Combat bracelet", "Warrior helm", "Berserker helm",
			"Archer helm", "Farseer helm", "Helm of neitiznot", "Amulet of the damned",
			"Void knight top", "Void knight robe", "Void knight gloves", "Void melee helm",
			"Void ranger helm", "Void mage helm", "Dragon boots", "Dragon defender",
			"Toktz-xil-ak", "Toktz-xil-ul", "Toktz-mej-tal", "Tzhaar-ket-om",
			"Obsidian helmet", "Obsidian platebody", "Obsidian platelegs");

		// ---- 2026-08 full-database rarity audit (multi-agent sweep, adversarially
		// verified). The stat heuristic rates by melee-facing combat numbers, so
		// prestige gear with tiny stats (skill/max capes, 3rd age, gilded, raid
		// uniques, prayer/mage gear, high-tier ammo) crashed into Common/Uncommon
		// while strength-heavy junk (kitchen weapons, battlestaves, Bone mace)
		// inflated into Epic/Legendary. Sets always move as a unit; diary rewards
		// scale 1-4 Common->Epic; quest rewards cap at Rare; ornament variants
		// inherit their base item's tier. ----
		put(Rarity.LEGENDARY,
			"3rd age amulet", "3rd age bow", "3rd age cloak", "3rd age druidic cloak",
			"3rd age druidic robe bottoms", "3rd age druidic robe top", "3rd age druidic staff",
			"3rd age full helmet", "3rd age kiteshield", "3rd age mage hat", "3rd age platebody",
			"3rd age platelegs", "3rd age plateskirt", "3rd age range coif", "3rd age range legs",
			"3rd age range top", "3rd age robe", "3rd age robe top", "3rd age vambraces",
			"3rd age wand", "Accumulator max cape", "Ardougne max cape", "Assembler max cape",
			"Avernic treads", "Bellator ring", "Blessed dizana's quiver", "Dizana's max cape",
			"Dizana's quiver", "Dragon warhammer", "Eldritch nightmare staff", "Elidinis' ward",
			"Fire max cape", "Ghommal's avernic defender 5", "Ghommal's avernic defender 6",
			"Guthix max cape", "Harmonised nightmare staff", "Holy sanguinesti staff",
			"Imbued guthix max cape", "Imbued saradomin max cape", "Imbued zamorak max cape",
			"Infernal max cape", "Justiciar chestguard", "Justiciar faceguard",
			"Justiciar legguards", "Magma helm", "Magus ring", "Masori assembler max cape",
			"Masori body", "Masori chaps", "Masori mask", "Max cape", "Mythical max cape",
			"Oathplate chest", "Oathplate helm", "Oathplate legs", "Radiant oathplate chest",
			"Radiant oathplate helm", "Radiant oathplate legs", "Sanguine torva full helm",
			"Sanguine torva platebody", "Sanguine torva platelegs", "Saradomin max cape",
			"Tanzanite helm", "Twisted ancestral hat", "Twisted ancestral robe bottom",
			"Twisted ancestral robe top", "Twisted buckler", "Twisted slayer helmet",
			"Tzkal slayer helmet", "Ultor ring", "Venator ring", "Volatile nightmare staff",
			"Zamorak max cape", "Zaryte vambraces");
		put(Rarity.EPIC,
			"Abyssal bludgeon", "Abyssal dagger", "Abyssal tentacle", "Accursed sceptre",
			"Achievement diary cape", "Agility cape", "Amulet of blood fury",
			"Amulet of eternal glory", "Amulet of rancour", "Ancient sceptre",
			"Ancient wyvern shield", "Ardougne cloak 4", "Attack cape", "Blessed spirit shield",
			"Blood ancient sceptre", "Burning claws", "Construct. cape", "Cooking cape",
			"Crafting cape", "Crystal axe", "Crystal felling axe", "Crystal harpoon", "Crystal helm",
			"Crystal legs", "Crystal pickaxe", "Defence cape", "Dinh's bulwark", "Dragonfire ward",
			"Explorer's ring 4", "Falador shield 4", "Farming cape", "Ferocious gloves",
			"Firemaking cape", "Fishing cape", "Fletching cape", "Fremennik sea boots 4",
			"Frozen abyssal whip", "Gilded 2h sword", "Gilded boots", "Gilded chainbody",
			"Gilded coif", "Gilded d'hide body", "Gilded d'hide chaps", "Gilded d'hide vambraces",
			"Gilded full helm", "Gilded hasta", "Gilded kiteshield", "Gilded med helm",
			"Gilded platebody", "Gilded platelegs", "Gilded plateskirt", "Gilded scimitar",
			"Gilded spear", "Gilded sq shield", "Heavy ballista", "Herblore cape", "Hitpoints cape",
			"Hunter cape", "Hydra slayer helmet", "Ice ancient sceptre", "Infernal axe",
			"Infernal harpoon", "Infernal pickaxe", "Kandarin headgear 4", "Karamja gloves 4",
			"Keris partisan of amascut", "Keris partisan of breaching",
			"Keris partisan of corruption", "Keris partisan of the sun", "Magic cape",
			"Masori assembler", "Mining cape", "Morrigan's coif", "Morrigan's leather chaps",
			"Morytania legs 4", "Music cape", "Neitiznot faceguard", "Oathplate slayer helmet",
			"Prayer cape", "Purging staff", "Quest point cape", "Rada's blessing 4", "Ranger boots",
			"Rangers' tights", "Rangers' tunic", "Ranging cape", "Regen bracelet",
			"Ring of suffering", "Ring of the gods", "Runecraft cape", "Sailing cape",
			"Saradomin sword", "Saradomin's blessed sword", "Sara's blessed sword", "Scorching bow",
			"Shadow ancient sceptre", "Slayer cape", "Smithing cape", "Smoke ancient sceptre",
			"Staff of balance", "Staff of light", "Statius's full helm", "Statius's platelegs",
			"Strength cape", "Sunfire fanatic chausses", "Sunfire fanatic cuirass",
			"Sunfire fanatic helm", "Thammaron's sceptre", "Thieving cape", "Toxic staff",
			"Treasonous ring", "Tyrannical ring", "Uncharged toxic trident", "Uncharged trident",
			"Ursine chainmace", "Vampyric slayer helmet", "Varrock armour 4", "Vesta's chainbody",
			"Vesta's plateskirt", "Viggora's chainmace", "Virtus mask", "Virtus robe bottom",
			"Virtus robe top", "Volcanic abyssal whip", "Woodcut. cape", "Woodcutting cape",
			"Zamorakian hasta", "Zamorakian spear", "Zuriel's hood", "Zuriel's robe bottom");
		put(Rarity.RARE,
			"Amethyst arrow", "Amethyst broad bolts", "Amethyst dart", "Amethyst fire arrow",
			"Amethyst javelin", "Amulet of avarice", "Ancient bracers", "Ancient ceremonial boots",
			"Ancient ceremonial gloves", "Ancient ceremonial legs", "Ancient ceremonial mask",
			"Ancient ceremonial top", "Ancient chaps", "Ancient coif", "Ancient crozier",
			"Ancient d'hide boots", "Ancient d'hide shield", "Ancient full helm",
			"Ancient kiteshield", "Ancient platelegs", "Ancient plateskirt", "Ancient staff",
			"Aranea boots", "Araxyte slayer helmet", "Ardougne cloak 3", "Arkan blade",
			"Armadyl bracers", "Armadyl chaps", "Armadyl coif", "Armadyl crozier",
			"Armadyl d'hide boots", "Armadyl d'hide shield", "Armadyl full helm",
			"Armadyl kiteshield", "Armadyl platelegs", "Armadyl plateskirt", "Ava's assembler",
			"Bandos bracers", "Bandos chaps", "Bandos coif", "Bandos crozier", "Bandos d'hide boots",
			"Bandos d'hide shield", "Bandos full helm", "Bandos kiteshield", "Bandos platelegs",
			"Bandos plateskirt", "Barrelchest anchor", "Belle's folly", "Black chinchompa",
			"Black d'hide body", "Black d'hide chaps", "Black d'hide shield",
			"Black d'hide vambraces", "Black mask", "Black slayer helmet", "Black spiky vambraces",
			"Blisterwood flail", "Blisterwood sickle", "Blood moon helm", "Blood moon tassets",
			"Blue moon helm", "Blue moon spear", "Blue spiky vambraces", "Bonecrusher necklace",
			"Book of the dead", "Brimstone ring", "Brine sabre", "Bryophyta's staff",
			"Cape of legends", "Cleaver", "Colossal blade", "Confliction gauntlets", "Crystal bow",
			"Crystal halberd", "Dagon'hai hat", "Dagon'hai robe bottom", "Dark infinity bottoms",
			"Dark infinity hat", "Dark infinity top", "Decorative sword", "Devout boots",
			"Diamond bolts", "Diamond dragon bolts", "Dragon 2h sword", "Dragon arrow",
			"Dragon battleaxe", "Dragon bolts", "Dragon cane", "Dragon dagger", "Dragon dart",
			"Dragon fire arrow", "Dragon halberd", "Dragon hasta", "Dragon javelin", "Dragon knife",
			"Dragon longsword", "Dragon mace", "Dragon med helm", "Dragon platelegs",
			"Dragon plateskirt", "Dragon scimitar", "Dragon spear", "Dragon sword",
			"Dragon thrownaxe", "Dragonbone necklace", "Dragonstone bolts", "Dragonstone boots",
			"Dragonstone dragon bolts", "Dragonstone full helm", "Dragonstone gauntlets",
			"Dragonstone platelegs", "Dual macuahuitl", "Earthbound tecpatl", "Eclipse atlatl",
			"Eclipse moon helm", "Elite void robe", "Elite void top", "Emerald dragon bolts",
			"Explorer's ring 3", "Falador shield 3", "Fighter hat", "Fremennik sea boots 3",
			"Glacial temotli", "Green slayer helmet", "Guardian boots", "Guthix bracers",
			"Guthix chaps", "Guthix coif", "Guthix crozier", "Guthix d'hide boots",
			"Guthix d'hide shield", "Guthix full helm", "Guthix halo", "Guthix kiteshield",
			"Guthix platelegs", "Guthix plateskirt", "Hallowed flail", "Healer hat",
			"Hill giant club", "Hooded slayer helmet", "Hueycoatl hide chaps", "Hueycoatl hide coif",
			"Hueycoatl hide vambraces", "Hunters' sunlight crossbow", "Iban's staff",
			"Imbued guthix cape", "Imbued saradomin cape", "Imbued zamorak cape", "Ivandis flail",
			"Jade dragon bolts", "Kandarin headgear 3", "Karamja gloves 3", "Katana", "Keris",
			"Keris partisan", "Leaf-bladed battleaxe", "Leaf-bladed spear", "Leaf-bladed sword",
			"Light ballista", "Light infinity bottoms", "Light infinity hat", "Light infinity top",
			"Mage's book", "Magic comp bow", "Magic shortbow", "Master wand", "Meat tenderiser",
			"Moonlight antler bolts", "Morytania legs 3", "Mythical cape", "Onyx bolts",
			"Onyx dragon bolts", "Opal dragon bolts", "Pearl dragon bolts", "Penance gloves",
			"Penance skirt", "Purple slayer helmet", "Rada's blessing 3", "Radiant slayer helmet",
			"Ranger gloves", "Ranger hat", "Rapier", "Red chinchompa", "Red slayer helmet",
			"Red spiky vambraces", "Ring of shadows", "Robin hood hat", "Rock-shell boots",
			"Rock-shell gloves", "Rock-shell helm", "Rock-shell legs", "Rolling pin", "Ruby bolts",
			"Ruby dragon bolts", "Runite bolts", "Runner hat", "Salve amulet",
			"Sapphire dragon bolts", "Sarachnis cudgel", "Saradomin bracers", "Saradomin chaps",
			"Saradomin coif", "Saradomin crozier", "Saradomin d'hide boots",
			"Saradomin d'hide shield", "Saradomin full helm", "Saradomin halo",
			"Saradomin kiteshield", "Saradomin platelegs", "Saradomin plateskirt", "Skeletal boots",
			"Skeletal bottoms", "Skeletal gloves", "Skeletal helm", "Skeletal top", "Slayer helmet",
			"Slayer's staff", "Spined boots", "Spined chaps", "Spined gloves", "Spined helm",
			"Sulphur blades", "Sunspear", "Tecu salamander", "Toktz-xil-ek", "Tome of earth",
			"Tome of fire", "Tome of water", "Topaz dragon bolts", "Turquoise slayer helmet",
			"Tzhaar-ket-em", "Tztok slayer helmet", "Varrock armour 3", "Void knight mace",
			"Xeric's talisman", "Zamorak bracers", "Zamorak chaps", "Zamorak coif",
			"Zamorak crozier", "Zamorak d'hide boots", "Zamorak d'hide shield", "Zamorak full helm",
			"Zamorak halo", "Zamorak kiteshield", "Zamorak monk bottom", "Zamorak monk top",
			"Zamorak platelegs", "Zamorak plateskirt", "Zealot's boots", "Zealot's helm",
			"Zealot's robe bottom", "Zealot's robe top", "Zombie axe");
		put(Rarity.UNCOMMON,
			"Air battlestaff", "Ancient mace", "Ardougne cloak 2", "Ava's accumulator",
			"Barronite mace", "Battlestaff", "Black cane", "Blessed axe", "Bloodbark body",
			"Bloodbark boots", "Bloodbark gauntlets", "Bloodbark legs", "Bone mace",
			"Book of balance", "Book of war", "Chinchompa", "Comp ogre bow", "Dark squall hood",
			"Dark squall robe bottom", "Dark squall robe top", "Darklight", "Dorgeshuun crossbow",
			"Dragon gloves", "Dramen staff", "Earth battlestaff", "Elder chaos hood",
			"Elder chaos robe", "Emerald bolts", "Excalibur", "Explorer's ring 2",
			"Falador shield 2", "Fire battlestaff", "Fremennik blade", "Fremennik sea boots 2",
			"Gadderhammer", "Ghostly boots", "Ghostly cloak", "Ghostly gloves", "Ghostly hood",
			"Ghostly robe", "Guthix mjolnir", "Holy book", "Holy sandals", "Hunter's spear",
			"Kandarin headgear 2", "Karamja gloves 2", "Kharedst's memoirs", "Lava battlestaff",
			"Lunar amulet", "Lunar boots", "Lunar cape", "Lunar gloves", "Lunar helm", "Lunar ring",
			"Lunar staff - pt1", "Lunar staff - pt2", "Lunar staff - pt3", "Morytania legs 2",
			"Mud battlestaff", "Ogre bow", "Orange salamander", "Rada's blessing 2",
			"Red salamander", "Saradomin mjolnir", "Shadow sword", "Steam battlestaff",
			"Sunlight antler bolts", "Swamp lizard", "Swampbark body", "Swampbark boots",
			"Swampbark gauntlets", "Unholy book", "Water battlestaff", "Western banner 2",
			"Wilderness sword 2", "Zamorak mjolnir");
		put(Rarity.COMMON,
			"Amulet of magic", "Arceuus banner", "Barbed arrow", "Blunt arrow", "Blurite sword",
			"Bone club", "Bone shortbow", "Bone spear", "Bullet arrow", "Field arrow", "Frying pan",
			"Hosidius banner", "Kitchen knife", "Lovakengj banner", "Machete", "Magic staff",
			"Oak longbow", "Oak shortbow", "Piscarilius banner", "Rat pole", "Shayzien banner",
			"Silverlight", "Skewer", "Spatula", "Spork", "Staff", "Staff of air", "Staff of earth",
			"Staff of fire", "Staff of water", "Training sword", "Varrock armour 1",
			"Western banner 1", "Wilderness sword 1", "Wooden spoon", "Wooden sword");
	}

	private static void put(Rarity rarity, String... names)
	{
		for (String name : names)
		{
			OVERRIDES.put(name, rarity);
		}
	}

	private RarityOverrides()
	{
	}

	public static Rarity lookup(String cleanName)
	{
		return OVERRIDES.get(cleanName);
	}
}
