# Gachaman

An RNG-governed challenge gamemode for Old School RuneScape, as a RuneLite plugin.
Fate decides what you may train. Cards decide what you may wear. Chests decide everything else.

## The rules

- **Your attack style is rolled for you.** A roulette wheel picks melee, ranged, or magic
  (it can land on the same style again — fate is like that). It re-rolls after you complete
  **5 kill tasks**.
  - **Style Compactors** (400 GC) make your current task count double toward the re-roll
    AND make each kill count twice toward the contract itself (the skipped count pays no
    GC — you trade kill income for speed) — for when you hate your style. **Style
    Extenders** (250 GC) make the task count half toward the cycle — for when you love it.
    Like expeditious and slaughter bracelets, but for fate.
- **Equipment is locked behind cards.** Every equipable item in the game has a card
  (the database is derived from the live item cache, so all of it is covered). You may only
  wear an item when you own its card **and** have assigned it to the matching loadout slot.
  Forbidden equipment has its Wield/Wear option removed and clicks consumed, with a
  crossed-circle icon in your inventory and bank.
  - **Shiny cards** (1/64, prismatic) also unlock every *lower-tier* version of the same
    piece — a shiny Rune scimitar card unlocks all scimitars rune and below. Lowest-tier
    gear has no shiny (there is nothing below it).
  - **Hologram cards** (1/256, the rarest pulls) represent an entire *tier*, not an item.
    Assign one to a single loadout slot and that slot may equip **any** item of the tier —
    a Dragon Hologram in your weapon slot permits any dragon weapon. Movable between slots.
- **You start bare.** Leaving Tutorial Island strips everything it handed you — none of it
  is card-unlocked, so keeping it would mean wearing gear you could never re-equip. Your
  starter cards and their auto-assigned loadout are waiting.
- **Gear slots themselves start locked.** A fresh profile can use only the weapon, body and
  ammo slots (so melee and ranged are both trainable from the start; your Training sword and
  arrows come pre-assigned). Ironman accounts also start with their **own** account type's
  armour cards — regular, ultimate, hardcore, group, hardcore group or unranked group — and
  that set's platebody fills the body slot; normal accounts get none of it, since the game
  would never let them wear it. The other eight need **Slot Deeds**: rare chest drops (you pick
  which slot to unlock) or guaranteed milestones at 10/25/45/70/100/140/190/250 completed tasks.
  During your first five tasks, Medium/Hard/Insane completions also pay **Deed Fragments**
  (1/2/3) — ten fragments forge one bonus deed, once per account (all-hard exactly forges).
- **The early game celebrates you.** A **Firsts Journal** stamps ~15 one-time firsts
  (first kill, first chest, first Rare...) for 495 GC total; **Graduation** fanfares fire
  the first time each slot's worn tier climbs a rank (early tiers only); and every profile
  starts with one free **Compactor voucher** and one free **Extender voucher**.
- **Kill tasks are your income.** Roll four offers — easy / medium / hard / insane — scaled
  to your combat level (never impossible). Each on-task kill pays Gacha Coins (GC); the final
  kill pays a completion bonus. Tasks come with optional **side bets** ("land a 20+ hit",
  "a kill without taking damage") — some are *sealed* and only revealed the moment you
  accidentally satisfy them. Consecutive on-task kills within ~25s build a **Rhythm Combo**
  (up to +30% kill GC at low combat, fading to a permanent +10% floor), and the first
  on-task kill of each new species pays a **Bestiary discovery bonus** with codex milestones
  at 50/100/150 species. On ironman accounts, a kill another player damaged counts **half**
  (half kc — two assisted kills = one count — and half GC), with a chat notice each time.
  The rule stands down entirely while you are on a **shared party contract** — teammates
  piling onto the same monster is the point — and re-arms if the carry clause converts
  the contract back to solo.
  Assist detection combines three signals: other players' hitsplats (including 0-damage
  splashes, which void ironman credit too), the game's own "might not receive kill-credit"
  warning (keep it enabled in the game's Activities settings), and a loot oracle — kills
  are credited a couple of ticks after despawn so the server's loot events can be observed:
  loot received proves full credit and overrides any suspicion (thralls, groupmates,
  NPC damage), while no loot from a guaranteed-drop monster convicts even for damage
  dealt entirely off-scene.
- **Chests.** The Rusty chest (150 GC, 1 card, max three purchases EVER — Common cards
  only, from your unlocked slots and strictly-wieldable gear, shiny odds boosted to 1/16,
  no jackpot/deed/pity) is the intended first purchase; after three it rusts away forever.
  Then Battered (500 GC, 1 card), Gilded (800 GC, 2 cards), Ornate (1,000 GC,
  3 cards) — better value and better odds the higher you go. Openings are full ceremonies,
  scaled to the price. ~1/100 opens the chest **upgrades itself mid-animation**. A visible
  **pity meter** boosts your odds during dry streaks and guarantees a Legendary at the cap.
  Every 10 combat levels gained grants a **reroll token**: during any reveal, re-flip one
  disappointing card. A shiny roll that only just misses banks **Stardust** — 8 stardust
  blesses your next chest with double shiny attempts on every card.
- **Breaking the style lock costs you.** When the wheel changes your style you are only
  *warned*. The violation starts when you actually attack with a forbidden style: every such
  attack deducts GC, and kills finished while violating pay **zero** and add **taint** —
  all income halved until you work it off (one compliant kill per taint) or complete a
  harder **Redemption Task** to clear it all at once. The judge is fair: spell-cast
  animations always count as magic no matter your weapon stance, powered staves count as
  magic in every combat mode, and if a delayed Magic XP drop proves a "melee" verdict
  wrong, the conviction is **pardoned** — penalty refunded, taint window cleared, with a
  chat notice. (An auto-retaliate staff bash that actually lands is genuine melee and
  stays convicted — set an autocast spell or turn auto-retaliate off; the plugin tips you
  when this happens.)
- **The long game.** Boss KC milestones award themed chests that roll only that boss's
  cards — 62 bosses across 64 card sets, from Obor to Sol Heredit, with 361 monsters in the
  contract pool. Completing themed sets grants permanent perks. A personal weekly shop offers three
  direct-buy cards (one biased toward what you're missing). Task journal tracks per-monster
  stats and personal bests. And when you've done everything: **Prestige** — burn your
  common/uncommon collection for a permanent rank and compounding bonuses.
- **Party Rolls.** In a RuneLite Party, any member with a clean slate (no active contract
  AND no undecided rolls — rolls can't be undone) can propose a shared roll. Others join
  with `::gachaparty` (or sit out with `::gachaparty no`); busy members, members not
  running the plugin, and members with the **Party contracts** setting turned off are all
  excused automatically (the setting also hides the party UI and blocks proposing). The
  roll starts once everyone answers, or after ~60s with **whoever agreed (minimum 2)** —
  and the proposer is the **host**: a "Start Roll Now" button (or `::gachaparty start`)
  starts immediately with the current agreers. The host fixes the final participant list
  so every client stays in sync. Membership
  status is exchanged only in this handshake. All participants then roll with the **same
  seed** (the lowest member id's), restricted to F2P monsters if any participant is free
  and scaled to the lowest combat level, so identical offers appear on every screen.
  Clicking a contract **votes**; a unanimous vote accepts it as a **shared contract**:
  everyone's kills fill one pooled quota, completion pays the 1.6× co-op bonus on every
  screen, and a carry clause converts the task to solo if the party leaves or goes quiet.
- **The Fortune Timeline.** A sidebar tab auditing every roll, pull, equip and event in
  chronological, color-coded order — style rolls, contract rolls/accepts/completions,
  chest opens with every card pulled, rerolls, charges, violations. Pick a from/to
  window and scrub through it (0 = from, 1 = to). Keeps the last 500 events.

## Honest disclosures

- **Client-side honor mode.** All state lives in your RuneLite profile. Disabling the
  plugin removes every restriction — the gamemode is a contract with yourself.
- **Menu modification.** The plugin removes Wield/Wear/Equip/Hold menu entries and consumes
  clicks on card-locked equipment.
- **One automated action.** Stepping off Tutorial Island fires the game's own "Remove"
  option on each worn item, once per account, to clear gear no card has unlocked yet
  (one item per tick; it stops if your inventory is full, and resumes on your next
  login until everything is off). Accounts that install the plugin after the tutorial
  are never stripped. Apart from that single one-shot, the plugin issues no actions on
  your behalf.
- **Party features are trust-based** (like all client-side party plugins).
- Card art uses the game's own item sprites via RuneLite's item manager. No external
  requests are made.

## Development

- `./gradlew build` — compile + full unit test suite (fixed-seed RNG tests, dataset
  integrity, codec round-trips, task generator bounds at every combat level).
- `./gradlew run` — launch a dev client with the plugin loaded.
## Commands

Always available:

- `::gachaparty` — propose a party roll, or agree to a live proposal; `::gachaparty no` sits
  out; `::gachaparty start` — host only: start immediately with whoever has agreed (min 2);
  `::gachaparty cancel` — host only: during the answer phase it aborts the roll outright;
  after the offers are rolled it dissolves the PARTY only — the rolled contracts remain
  for every participant as personal offers (rolls can't be undone); accepted contracts
  stay binding. Requires the **Party contracts** config setting (on by default)

Debug commands (enable *Advanced → Debug commands* in the plugin config first):

- `::gachagive <amount>` — grant GC (default 10,000)
- `::gachachest <rusty|battered|gilded|ornate>` — open a chest without paying (default battered)
- `::gachatask` — roll task offers
- `::gachastyle` — force a style roulette roll
- `::gachatoken` — grant a card reroll token
- `::gachacleartaint` — clear all taint debt
- `::gachacleartask` — wipe the active contract and rolled offers (testing party rolls)
- `::gachabutton` — print loadout-button render diagnostics (why/where it is or isn't drawing)
- `::gachacosmetics [maxTotal]` — audit: list untiered cards whose total combat bonuses are
  at or below the threshold (default 6) — candidates for the novelty denylist
