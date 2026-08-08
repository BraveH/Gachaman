package com.gachaman.ui.panel;

import com.gachaman.model.Rarity;
import com.gachaman.model.Variant;
import com.gachaman.ui.CardRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * How-to-play reference for the gamemode. Content is entirely static text and
 * procedurally drawn illustrations (no assets, no network) — but its WRAP
 * WIDTH is not static: the real viewport width depends on which scrollbar
 * width the look-and-feel actually honors, and assuming it from constants has
 * produced both a right-side gap and scrollbar-covered text. The tab measures
 * the live viewport instead and rebuilds whenever that measurement changes.
 */
@Singleton
public class HelpTab extends JPanel
{
	/**
	 * Pre-realization fallback only: the 242px non-wrapped PluginPanel minus
	 * its 6px borders and a full stock 17px scrollbar — the NARROWEST the
	 * viewport can plausibly be, so nothing clips even before measuring.
	 */
	private static final int FALLBACK_WIDTH = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH
		- 2 * PluginPanel.BORDER_OFFSET - PluginPanel.SCROLLBAR_WIDTH;

	/** Horizontal padding a GachamanPanel.section() adds (8px borders each side). */
	private static final int SECTION_PADDING = 16;
	/** Icon column width inside an iconRow (glyph up to 16px + 6px gap). */
	private static final int ICON_COLUMN = 22;

	private static final Color MELEE = new Color(214, 72, 56);
	private static final Color RANGED = new Color(80, 175, 68);
	private static final Color MAGIC = new Color(72, 118, 214);
	private static final Color BODY = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color MUTED = ColorScheme.MEDIUM_GRAY_COLOR;

	private static final int CARD_W = 56;
	private static final int CARD_H = 80;

	/** Wrap width the current content was built for; -1 = never built. */
	private int builtWidth = -1;
	private boolean viewportHooked;

	@Inject
	public HelpTab()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	/** Rebuilds only when the measured viewport width changed; no-op otherwise. */
	void rebuild()
	{
		int width = measuredWidth();
		if (width == builtWidth)
		{
			return;
		}
		builtWidth = width;
		removeAll();
		addSection(buildGamemodeSection(width), width);
		addSection(buildStyleSection(width), width);
		addSection(buildContractsSection(width), width);
		addSection(buildPartySection(width), width);
		addSection(buildCardsSection(width), width);
		addSection(buildSlotDeedsSection(width), width);
		addSection(buildChestsSection(width), width);
		addSection(buildEarlyGameSection(width), width);
		addSection(buildLongGameSection(width), width);
		addSection(buildQuestsSection(width), width);
		addSection(buildCommandsSection(width), width);
		revalidate();
		repaint();
	}

	/** The scroll viewport's ACTUAL extent width — the only trustworthy budget. */
	private int measuredWidth()
	{
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (ancestor instanceof JViewport)
		{
			int width = ((JViewport) ancestor).getExtentSize().width;
			if (width > 0)
			{
				return width;
			}
		}
		return FALLBACK_WIDTH;
	}

	@Override
	public void addNotify()
	{
		super.addNotify();
		Container ancestor = SwingUtilities.getAncestorOfClass(JViewport.class, this);
		if (!viewportHooked && ancestor instanceof JViewport)
		{
			// the viewport narrows when the scrollbar appears (and would widen
			// if the LAF ever changed its width) — re-measure and rebuild; the
			// equal-width check makes this settle in at most two passes
			viewportHooked = true;
			ancestor.addComponentListener(new ComponentAdapter()
			{
				@Override
				public void componentResized(ComponentEvent e)
				{
					SwingUtilities.invokeLater(HelpTab.this::rebuild);
				}
			});
		}
	}

	private void addSection(JPanel section, int width)
	{
		add(new WidthCap(section, width));
		add(Box.createVerticalStrut(6));
	}

	// --- Sections ---

	private static JPanel buildGamemodeSection(int w)
	{
		JPanel section = GachamanPanel.section("The Gamemode");
		paragraph(section, w, "Gachaman is an RNG-governed challenge: fate rolls your combat style, "
			+ "cards gate your equipment, kill contracts are your income, and chests are your "
			+ "progression.");
		paragraph(section, w, "Leaving Tutorial Island strips everything it gave you — none of it "
			+ "is card-unlocked yet. Your starter cards are already in your album and assigned.");
		paragraph(section, w, "Everything is client-side honor mode: disabling the plugin removes "
			+ "all restrictions.");
		return section;
	}

	private static JPanel buildStyleSection(int w)
	{
		JPanel section = GachamanPanel.section("Attack Style");
		JPanel styles = flowRow(w);
		styles.add(styleLabel("Melee", MELEE));
		styles.add(styleLabel("Ranged", RANGED));
		styles.add(styleLabel("Magic", MAGIC));
		section.add(styles);
		section.add(Box.createVerticalStrut(5));
		paragraph(section, w, "A roulette wheel decides melee, ranged or magic — and it can re-roll "
			+ "the same style. It re-rolls after 5 completed tasks. From the shop, a Style "
			+ "Compactor (400 GC) makes the current task count double toward those 5 AND makes "
			+ "each kill count twice toward the contract itself — the skipped count pays no GC, "
			+ "so you trade kill income for speed. An Extender (250 GC) makes the task count "
			+ "half toward the cycle. Every profile starts with one free Compactor voucher and "
			+ "one free Extender voucher — the first use of each costs nothing.");
		paragraph(section, w, "Attacking with a forbidden style costs GC per attack, and finishing "
			+ "a kill while violating pays nothing and adds taint: all income is halved until "
			+ "you work each taint off with a compliant kill, or clear it all with one "
			+ "Redemption contract (offered whenever tainted). The judge is fair: cast "
			+ "animations always count as magic whatever your stance, and if a delayed Magic "
			+ "XP drop proves a melee verdict wrong, the penalty is refunded automatically.");
		return section;
	}

	private static JPanel buildContractsSection(int w)
	{
		JPanel section = GachamanPanel.section("Contracts (Tasks)");
		paragraph(section, w, "You roll 4 contracts (Easy/Medium/Hard/Insane) scaled to your combat "
			+ "level. While tainted a 5th Redemption contract appears. Contracts cannot be "
			+ "abandoned.");
		paragraph(section, w, "Party rolls (RuneLite Party): any member with a clean slate (no "
			+ "contract, no undecided rolls) proposes; others join with ::gachaparty (or sit "
			+ "out with ::gachaparty no — busy members, members without the plugin, and "
			+ "members with the Party contracts setting off sit out "
			+ "automatically). The roll starts once everyone "
			+ "answers, after ~60s with whoever agreed (minimum 2), or the moment the HOST "
			+ "(the proposer) presses Start Roll Now. Participants then see "
			+ "IDENTICAL offers — all clients roll with the seed of the lowest member id, "
			+ "restricted to free-to-play monsters if any participant is free and sized to the "
			+ "party's FIGHTING WEIGHT: the AVERAGE combat level of everyone taking part, each "
			+ "level clamped to 3-126 and the mean rounded down. Slayer requirements still gate "
			+ "on the party's LOWEST slayer level, so nobody is offered something a member "
			+ "cannot legally kill. If any participant is on an older build the whole party "
			+ "falls back to the old rule and sizes to the lowest combat level instead — it is "
			+ "all-or-nothing, because two clients disagreeing about the target level would deal "
			+ "two different boards. Clicking a contract VOTES, and a MAJORITY (2 of 2 or 3, 3 of "
			+ "4 or 5) signs it for the whole party as a SHARED contract — everyone's kills fill "
			+ "one pooled quota. If no contract has a majority once every vote is in, or when the "
			+ "~2 minute clock runs out, the most-voted contract is taken instead — drawn at "
			+ "random between them if the lead is tied — and it binds only the members who "
			+ "actually voted; anyone who abstained keeps the rolled contracts as personal ones. "
			+ "Completion pays the 1.6x co-op bonus, plus a flat 0.25x if the party covers more "
			+ "than one attack style. Use \"View Rolled Tasks\" to change your vote.");
		paragraph(section, w, "You may only attack your contract's monster — everything else is "
			+ "blocked (quest targets excepted, see Tutorial & Quests).");
		paragraph(section, w, "Per-kill GC scales three ways: the difficulty base (8/16/32/64), a "
			+ "ratio multiplier for punching up (a monster twice your level pays big, capped at "
			+ "5x, while monsters more than 5 levels below you decay to 0.1x), and an "
			+ "early-game bonus (+150% at combat 3, tapering to nothing at combat 70).");
		paragraph(section, w, "Side bets are optional bonus objectives paying extra GC. A wiki "
			+ "button next to the task opens the monster's wiki page, and a config toggle "
			+ "highlights task NPCs in-game.");
		paragraph(section, w, "Double Docket: kill your real Slayer assignment while on contract "
			+ "and completion pays x1.2. It is checked when you accept AND on every kill, so "
			+ "picking the matching Slayer task up mid-contract still counts, and once it locks "
			+ "in it stays even if you finish the Slayer task first. Contracts are NEVER rolled "
			+ "to match your assignment — that would add RNG inside the seeded party roll and "
			+ "desync the party — so this is a happy accident the game pays for. Grouped "
			+ "assignments like Metal dragons name no single monster and cannot be detected. "
			+ "Every contract shows its docket state, earned or not.");
		paragraph(section, w, "The Charter Office (Overview tab): buy ONE contract a day instead "
			+ "of waiting for the board to offer it. The target must be familiar — 25 banked "
			+ "kills — and must pass every gate a normal roll applies, so a deed can never buy "
			+ "past a rule. It costs 800-2,500 GC depending on how far you are punching up, and "
			+ "the GC is HELD, not spent: the deed joins your board as an extra offer for 500 "
			+ "ticks and the money returns in full if you never sign it. The daily lock lifts at "
			+ "UTC midnight. The counter is closed while a party roll is live.");
		paragraph(section, w, "The Ante (off by default — turn it on in the config): before you "
			+ "accept an INSANE contract you may stake 10-50% of your purse, capped at 5,000 GC "
			+ "and never offered under a 250 GC purse. Finish the contract and the stake returns "
			+ "DOUBLED; die and it is gone. Arming takes two confirmations, disarming takes "
			+ "none, and GC only leaves your purse when a contract is actually signed. In a "
			+ "party it takes EVERY member: each stakes from their own purse and loses only "
			+ "their own, and one refusal means no Ante for anyone — the contract goes ahead "
			+ "either way. Contracts cannot be abandoned, so stake accordingly.");
		paragraph(section, w, "Rhythm Combo: consecutive on-task kills within ~25 seconds stack a "
			+ "kill-GC bonus — up to +30% at low combat, fading to a permanent +10% floor by "
			+ "combat 45. The chain cancels only after ~60 seconds with NO attacks at all "
			+ "(fighting something tanky keeps it alive); a forbidden attack or tainted kill "
			+ "breaks it instantly, and it resets between contracts.");
		paragraph(section, w, "Ironman honor rule: a kill another player attacked counts HALF — "
			+ "half a kill count (two assisted kills = one) and half the kill GC. With a "
			+ "Compactor an assisted kill lands back on exactly 1 count. On a SHARED party "
			+ "contract the rule stands down — teammates are supposed to pile on. Detection "
			+ "combines three signals: other players' hitsplats (even 0-damage splashes — "
			+ "those void ironman credit too), the game's \"might not receive kill-credit\" "
			+ "warning (keep it enabled in Activities settings), and the loot itself — loot "
			+ "received PROVES full credit and clears any suspicion, while no loot from a "
			+ "monster with a guaranteed drop convicts even when the damage happened before "
			+ "you arrived. Kill credit lands a moment after the death animation.");
		return section;
	}

	private static JPanel buildPartySection(int w)
	{
		JPanel section = GachamanPanel.section("The Party Page");
		paragraph(section, w, "A tab showing who is with you: each member's rolled style as a "
			+ "colour swatch, their combat level, their contract progress, and badges for "
			+ "taint and for your Patron's Mark with them. Turn the Party contracts setting "
			+ "off and the tab stays, but it broadcasts nothing and shows nothing — it says "
			+ "so rather than looking empty.");
		paragraph(section, w, "It is DISPLAY ONLY — no roll, payout or gate reads a line of it. "
			+ "Every value is self-reported by that member's own client and taken on trust; "
			+ "anything arriving over the party relay is clamped to a sane range before it is "
			+ "drawn. A row that stops reporting goes quiet after about a minute and comes back "
			+ "on the next heartbeat.");
		paragraph(section, w, "The Patron's Mark: a private tally of how many shared contracts "
			+ "you have finished alongside each partner, kept by display name so it survives "
			+ "logins. Marks land at 10, 25 and 100 — Patron I, II and III. Strictly cosmetic: "
			+ "it pays no GC and multiplies nothing, because a mark worth something would make "
			+ "farming a friend the correct play.");
		return section;
	}

	private static JPanel buildCardsSection(int w)
	{
		JPanel section = GachamanPanel.section("Cards & Equipment");
		paragraph(section, w, "Every combat-relevant piece of equipment in the game has a card "
			+ "(cosmetic gear is exempt and always wearable). You may only equip an item when "
			+ "you own its card and — with \"One card per slot\" on, the default — the card is "
			+ "assigned in your loadout, via the tile button on the worn-equipment page or the "
			+ "Loadout tab. With the setting off, owning the card is enough and the loadout UI "
			+ "hides.");
		section.add(iconRow(w, crossedCircleIcon(),
			textBlock("Blocked equipment shows a crossed circle.", BODY, w - SECTION_PADDING - ICON_COLUMN)));
		section.add(Box.createVerticalStrut(6));
		section.add(GachamanPanel.smallLine("Rarity ladder:", MUTED));
		section.add(Box.createVerticalStrut(2));
		for (Rarity rarity : Rarity.values())
		{
			section.add(GachamanPanel.smallLine(rarity.getDisplayName(), rarity.getColor()));
			section.add(Box.createVerticalStrut(1));
		}
		section.add(Box.createVerticalStrut(5));
		paragraph(section, w, "Shiny (1 in 64, only on gear with lower tiers) also unlocks every "
			+ "lower tier of the same piece. Hologram (1 in 256, the rarest pull) is a TIER "
			+ "card — assign it to one slot and that slot can wear ANY item of the tier.");
		JPanel cards = flowRow(w);
		cards.add(new JLabel(cardIcon(CardRenderer.CardView.builder()
			.name("Rune scimitar")
			.rarity(Rarity.RARE)
			.variant(Variant.NORMAL)
			.art(null)
			.build())));
		cards.add(new JLabel(cardIcon(CardRenderer.CardView.builder()
			.name("Rune scimitar")
			.rarity(Rarity.RARE)
			.variant(Variant.SHINY)
			.art(null)
			.build())));
		cards.add(new JLabel(cardIcon(CardRenderer.CardView.builder()
			.name("Dragon Hologram")
			.rarity(Rarity.EPIC)
			.variant(Variant.HOLOGRAM)
			.art(null)
			.subtitle("Dragon tier")
			.build())));
		section.add(cards);
		section.add(Box.createVerticalStrut(2));
		section.add(GachamanPanel.smallLine("Normal · Shiny · Hologram", MUTED));
		section.add(Box.createVerticalStrut(5));
		paragraph(section, w, "Service Record: every card counts the kills it was PRESENT for — "
			+ "assigned to a loadout slot when the kill landed — and the number is permanent. "
			+ "It counts real kills, not contract credit: a Compactor's doubled progress buys "
			+ "no extra service, and an ironman's half-credit kill still bought the card a "
			+ "whole kill of wear. The Album shows it as \"present for N kills\".");
		paragraph(section, w, "Past 100, 400 and 1,000 kills of service a card wears Hairline, "
			+ "then Cracked, then Shattered, still holding — drawn as gold-filled kintsugi "
			+ "repair, not damage. NOTHING reads it. A worn card rolls, equips, completes sets, "
			+ "burns at prestige and prices in the shop exactly like a pristine one. It is a "
			+ "veteran's stripe, not a durability system.");
		return section;
	}

	private static JPanel buildSlotDeedsSection(int w)
	{
		JPanel section = GachamanPanel.section("Slot Deeds");
		paragraph(section, w, "You start with only the weapon, body and ammo slots usable "
			+ "(Training sword + arrows come pre-assigned). On an ironman account you also "
			+ "get your OWN account type's armour cards — helm, platebody and platelegs — "
			+ "with the platebody assigned to the body slot. Normal accounts get none of "
			+ "them: the game would never let you wear another type's armour anyway.");
		section.add(iconRow(w, padlockIcon(), textBlock(
			"The other 8 slots each need a Slot Deed: a rare chest roll (1/25 Battered, "
				+ "1/18 Gilded, 1/12 Ornate — you choose the slot; the Rusty starter chest "
				+ "never rolls deeds) or guaranteed at 10/25/45/70/100/140/190/250/320 "
				+ "lifetime contracts — nine milestones for eight slots, so there is a "
				+ "spare.", BODY, w - SECTION_PADDING - ICON_COLUMN)));
		section.add(Box.createVerticalStrut(4));
		paragraph(section, w, "Once every slot is deeded the milestones stop, and a chest that "
			+ "rolls a deed after that pays 2,000 GC instead.");
		paragraph(section, w, "Deed Fragments: during your first 5 contracts, Medium/Hard/Insane "
			+ "completions pay 1/2/3 fragments. Ten fragments forge ONE bonus deed, ever — "
			+ "all-hard exactly forges it; anything easier misses. Difficulty is an "
			+ "equipment decision.");
		return section;
	}

	private static JPanel buildChestsSection(int w)
	{
		JPanel section = GachamanPanel.section("Chests & Luck");
		paragraph(section, w, "Rusty 150 GC (1 card, Common only, only 3 EVER): a cheap first "
			+ "pull. Cards come only from slots you have unlocked and gear you can already "
			+ "wield — but shinies are 4x more likely (1 in 16). No jackpot, no deed rolls, "
			+ "and it never moves the pity meter.");
		paragraph(section, w, "First Colours: the very first style roll on an account is followed "
			+ "by a FREE chest, steered toward a weapon your new style can actually swing. It "
			+ "is a Rusty chest and it counts as one of the three, so two are left to buy. If "
			+ "the client dies before it is dealt, it is still owed and arrives next login.");
		paragraph(section, w, "Then Battered 500 GC (1 card), Gilded 800 (2), Ornate 1,000 (3): "
			+ "better value AND better odds the higher you go. Slot Chests cost the Gilded "
			+ "price for exactly 1 card, guaranteed from a gear slot you choose. In roughly "
			+ "1 in 100 opens, the chest upgrades itself mid-animation to the next tier — the "
			+ "lid keeps the face of the tier you PAID for right up to the deal, so an upgrade "
			+ "is a reveal rather than a spoiler.");
		paragraph(section, w, "Strain: the chest fights before it opens, and how hard it fights "
			+ "is the tell. A shudder starts it, the shaking climbs toward the give, then "
			+ "everything goes dead still for a beat and the lid loses. Groans scale with what "
			+ "you paid — Rusty gets none at all, Battered two, Gilded three, Ornate four. It "
			+ "reports only the tier you BOUGHT and can never leak what is inside, and the "
			+ "intro is exactly as long as it always was.");
		paragraph(section, w, "The House Lean, stated plainly: when a chest picks WHICH item of a "
			+ "rarity you get, gear one tier above what you can wield today is weighted 0.35x "
			+ "against gear you can already use — so a card you can equip now is about 2.86x "
			+ "likelier than the equivalent tease. It touches item choice only. Rarity odds, "
			+ "the jackpot upgrade, hologram replacement and the first-card pity guarantee are "
			+ "all untouched, and Slot Chests roll Gilded odds. The Shop tab's Chest Odds panel "
			+ "prints the real percentages for every tier from the same numbers the roller "
			+ "uses, so you can check this rather than take it.");
		paragraph(section, w, "The pity meter counts CARDS revealed without an Epic+: past 12 your "
			+ "odds climb, and at 30 the next chest guarantees a Legendary (26 from prestige 2). "
			+ "Rusty chests never move it.");
		section.add(new GachamanPanel.MeterBar(12 / 30.0, ColorScheme.BRAND_ORANGE, "12 / 30"));
		section.add(Box.createVerticalStrut(5));
		paragraph(section, w, "Every 10 combat levels gained grants a reroll token — during any "
			+ "reveal, re-flip one card. Duplicates auto-convert to GC (25/60/150/400/1,000 by "
			+ "rarity); Shiny and Hologram dupes are kept.");
		paragraph(section, w, "Stardust: a shiny roll that only just missed fizzles visibly and "
			+ "banks 1 stardust (counter on the Album tab). At 8 the bank is consumed and your "
			+ "next chest is stardust-blessed — every card rolls its shiny twice.");
		return section;
	}

	private static JPanel buildEarlyGameSection(int w)
	{
		JPanel section = GachamanPanel.section("The Early Game");
		paragraph(section, w, "Firsts Journal (Journal tab): 15 one-time stamps for your inevitable "
			+ "firsts — first kill, first chest, first Rare, first taint cleared... Each pays a "
			+ "small GC bounty once; the full page totals 495 GC, just shy of one Battered "
			+ "chest.");
		paragraph(section, w, "Species Codex (Journal tab): the first on-task kill of each new "
			+ "species pays +25 GC, and milestones at 50/100/150 species pay 100/150/200 GC. "
			+ "Contracts always roll 4 distinct monsters — the unfamiliar one pays extra.");
		paragraph(section, w, "Graduation: the first time a gear slot's WORN tier climbs a rank "
			+ "(bronze to iron, iron to steel...) it celebrates with +25 GC — only through the "
			+ "early tiers, and never for your starting gear.");
		return section;
	}

	private static JPanel buildLongGameSection(int w)
	{
		JPanel section = GachamanPanel.section("The Long Game");
		paragraph(section, w, "Boss killcount milestones award themed chests rolling only that "
			+ "boss's cards — 62 bosses are tracked, from Obor to Sol Heredit — and completing "
			+ "themed card sets grants permanent perks (see the Sets tab). Bosses that share a "
			+ "card set, like the three Dagannoth Kings, each award their own milestones.");
		paragraph(section, w, "A personal weekly shop offers 3 direct-buy cards, seeded per player "
			+ "per week — always the same 3. The Journal tracks per-monster stats and personal "
			+ "bests (records pay 250 GC).");
		paragraph(section, w, "Prestige (250 contracts or 90% Common/Uncommon collection + 25,000 "
			+ "GC) burns your Common/Uncommon cards for a permanent rank: +5% GC per rank and "
			+ "better luck at rank 2-3.");
		paragraph(section, w, "The Dossier tab is the ledger: every contract you have finished or "
			+ "failed, newest first, with what it paid. It keeps the last 200 contracts, and "
			+ "the totals pinned at the top — count, clean rate, shared — are a fold over "
			+ "exactly that window, labelled \"(last 200 contracts)\" once it is full so they "
			+ "never read as lifetime figures. \"Lifetime earned\" is the one true unbounded "
			+ "number.");
		paragraph(section, w, "The Timeline tab audits everything else in order — style rolls, "
			+ "contract rolls and completions, chest opens with every card pulled, rerolls, "
			+ "charges and violations. Pick a from/to window and scrub it. Last 500 events.");
		return section;
	}

	private static JPanel buildQuestsSection(int w)
	{
		JPanel section = GachamanPanel.section("Tutorial & Quests");
		paragraph(section, w, "Tutorial Island has NO restrictions; the moment you leave, your "
			+ "first style roll and contracts fire.");
		paragraph(section, w, "Quest combat is protected: NPCs required by any IN-PROGRESS quest "
			+ "are attackable with any style, with no penalties (126 quests covered; Quest "
			+ "Helper's current step is also honored when that plugin runs).");
		return section;
	}

	private static JPanel buildCommandsSection(int w)
	{
		JPanel section = GachamanPanel.section("Commands");
		paragraph(section, w, "::gachaparty proposes or agrees to a party roll;"
			+ " ::gachaparty no declines it; ::gachaparty start / cancel are host-only.");
		section.add(textBlock("With Advanced > Debug commands enabled:", MUTED, w - SECTION_PADDING));
		section.add(Box.createVerticalStrut(3));
		String[] commands = {
			"::gachagive <amount>",
			"::gachachest <tier>",
			"::gachatask",
			"::gachastyle",
			"::gachatoken",
			"::gachacleartaint",
			"::gachacleartask",
			"::gachabutton (button diagnostics)",
			"::gachacosmetics (card audit)",
		};
		for (String command : commands)
		{
			section.add(GachamanPanel.smallLine(command, BODY));
			section.add(Box.createVerticalStrut(1));
		}
		return section;
	}

	// --- Layout helpers ---

	private static void paragraph(JPanel section, int w, String text)
	{
		section.add(textBlock(text, BODY, w - SECTION_PADDING));
		section.add(Box.createVerticalStrut(4));
	}

	/**
	 * Wrap-to-width body text WITHOUT the HTML renderer: Swing's CSS width is
	 * a preferred span, not a hard cap — stretched labels re-wrap wider than
	 * asked and then clip under the scrollbar. A JTextArea wraps at exactly
	 * the width it is given; sizing it up front makes its preferred height
	 * correct before the BoxLayout ever asks.
	 */
	private static JTextArea textBlock(String text, Color color, int width)
	{
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setBorder(null);
		area.setForeground(color);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		area.setSize(width, Short.MAX_VALUE);
		Dimension pref = area.getPreferredSize();
		area.setPreferredSize(new Dimension(width, pref.height));
		area.setMaximumSize(new Dimension(width, pref.height));
		return area;
	}

	/** A left-aligned flow row whose height never stretches in the BoxLayout. */
	private static JPanel flowRow(int w)
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(w, getPreferredSize().height);
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** A small glyph beside a wrapped text block, icon pinned to the top. */
	private static JPanel iconRow(int w, ImageIcon icon, JComponent text)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(w, getPreferredSize().height);
			}
		};
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel iconLabel = new JLabel(icon);
		iconLabel.setVerticalAlignment(SwingConstants.TOP);
		panel.add(iconLabel, BorderLayout.WEST);
		panel.add(text, BorderLayout.CENTER);
		return panel;
	}

	private static JLabel styleLabel(String name, Color color)
	{
		JLabel label = new JLabel(name, squareIcon(color), SwingConstants.LEFT);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setIconTextGap(4);
		return label;
	}

	/**
	 * Hard cap on a section's width: the sidebar is fixed-width and the
	 * scroll pane never scrolls horizontally, so no child may push a section
	 * past the measured viewport width.
	 */
	private static final class WidthCap extends JPanel
	{
		private final int cap;

		WidthCap(JComponent inner, int cap)
		{
			super(new BorderLayout());
			this.cap = cap;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			add(inner, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			return new Dimension(Math.min(d.width, cap), d.height);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(cap, getPreferredSize().height);
		}
	}

	// --- Procedural illustrations ---

	/** A small filled swatch for the attack-style legend. */
	private static ImageIcon squareIcon(Color color)
	{
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 10, 10);
		g.setColor(color.darker());
		g.drawRect(0, 0, 9, 9);
		g.dispose();
		return new ImageIcon(image);
	}

	/** The 14px crossed-circle glyph, mirroring ForbiddenItemOverlay's icon. */
	private static ImageIcon crossedCircleIcon()
	{
		int size = 14;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillOval(0, 0, size - 1, size - 1);
		g.setColor(new Color(232, 60, 60));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(1, 1, size - 3, size - 3);
		int inset = 3;
		g.drawLine(inset, size - 1 - inset, size - 1 - inset, inset);
		g.dispose();
		return new ImageIcon(image);
	}

	/** A small padlock glyph for the locked gear slots. */
	private static ImageIcon padlockIcon()
	{
		int size = 16;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(230, 190, 80));
		g.setStroke(new BasicStroke(2f));
		g.drawArc(4, 1, 7, 10, 0, 180);
		g.setColor(new Color(146, 126, 96));
		g.fillRoundRect(2, 7, 12, 8, 3, 3);
		g.setColor(new Color(230, 190, 80));
		g.drawRoundRect(2, 7, 11, 7, 3, 3);
		g.setColor(new Color(46, 40, 32));
		g.fillOval(7, 9, 3, 3);
		g.dispose();
		return new ImageIcon(image);
	}

	/** A mini card face rendered through the shared CardRenderer. */
	private static ImageIcon cardIcon(CardRenderer.CardView view)
	{
		BufferedImage image = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		CardRenderer.drawFace(g, 1, 1, CARD_W - 2, CARD_H - 2, view, 300L);
		g.dispose();
		return new ImageIcon(image);
	}
}
