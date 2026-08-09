# Manual verification checklist

The unit suite covers all roll math, state, persistence and dataset integrity. The
items below need human eyes in a logged-in client and cannot be automated.

Run `./gradlew run` to launch a dev client with the plugin loaded.

1. Card database builds on first login (sidebar shows scan progress, then card counts).
2. Equip block: Wield/Wear removed + click consumed in inventory, bank, deposit box, GE;
   crossed-circle icons render; unlocking the card immediately re-enables the item.
3. Chest ceremony (all four tiers + a jackpot upgrade; Rusty: muted ceremony, retires
   after 3 opens INCLUDING the free First Colours chest, pity bar must not move), card
   flips, hover charge glow, shiny/hologram effects, stardust near-miss fizzle, pity break,
   in-reveal token reroll. Strain: Rusty sits still, Ornate groans four times, and the lid
   still reads as the tier you paid for right up to a jackpot deal.
4. Style roulette lands on the announced style; warning chip appears; violating attack
   deducts GC; tainted kill pays zero; redemption task clears taint.
5. Task offers slot-reel, acceptance stamp, kill juice (+GC floaters, progress bar,
   golden final kill), side bets incl. a sealed reveal. On a quest-light account, reroll
   the board a dozen times and confirm nothing behind an unfinished quest is ever offered
   (Banshee and Nechryael need Priest in Peril, Vorkath needs Dragon Slayer II); the
   client log lists any gate name the dataset carries that this RuneLite build cannot
   resolve, and every one of those is a monster nobody will ever be offered.
6. Deed choice ceremony unlocks the chosen slot.
7. Loadout: sidebar tab and in-game overlay both assign/unassign; chatbox card search
   filters and assigns; hologram single-slot rule enforced.
8. Boss KC line queues a themed chest (all 62 bosses resolve to a real set — check a
   themed open actually rolls that boss's cards, that Barrows' "chest count" line fires,
   and that two bosses sharing a set tag each award their own chest); weekly shop rotates
   on Monday; prestige burn. Watch the client log on first login for any
   "names N card(s) with no match in the item cache" warning — that set can never complete.
9. Party roll: proposal + agreement (busy members auto-excused), identical offers on all
   screens, a majority vote accepts for everyone, a tied vote at the deadline is drawn and
   binds the voters, pooled kill count syncs, carry clause on party leaving.
10. Input safety: Escape closes **every** ceremony at **every** beat (a chest mid-reveal
    commits its cards; an accepted contract still accepts). Nothing is consumed unless it
    is actually on screen — log out mid-ceremony, or with the loadout board open, and the
    login and welcome screens must stay fully clickable.
11. Tutorial Island exit strips all worn gear one item per tick, chats the reason, and
    never repeats on later logins (nor on an account that installed the plugin later).
    Interrupt it — log out mid-strip, or step off with a full inventory — and the
    remaining pieces should come off on the next login, then stop for good.
12. Party page: rows appear for every member, go quiet ~1 minute after a client stops
    reporting, and come back on its next heartbeat; the whole tab hides with **Party
    contracts** off. Sizing: with the host on **Fighting Weight** a mixed-level party's
    offers must be sized to the average, with the host on **Weakest Man** to the lowest,
    and in both cases it is the HOST's setting that decides — flip a non-host member's
    setting mid-proposal and nothing about the board may change. A party containing a
    client that predates the choice falls back to the average and says so in chat; one
    that predates Fighting Weight falls back to the lowest. Quests: unlike sizing, EVERY
    member's answer counts — roll with one member missing a quest the others have (Priest
    in Peril is the easiest to stage) and no monster behind it may appear on ANY screen,
    then finish it and re-roll to see it come back. A client that predates quest gating
    turns the filter off party-wide and must say so in chat before the vote.
13. Charter Office: only monsters with 25+ banked kills are listed (and never one behind an
    unfinished quest, even with the kills banked), the quoted contract is
    the one you receive, the deed appears on the board as a fifth offer, and the GC comes
    back in full if you let it expire. Log out with a deed held and back in — the first
    tick must settle it, never eat it. The daily lock lifts at UTC midnight.
14. The Ante (turn the setting on first): the offer appears only with an INSANE contract on
    the board, arming takes two confirmations, disarming takes none, the purse drops by the
    stake at signing, completion pays it back doubled, and death takes it. In a party, one
    member declining must leave the contract intact and unstaked for everyone.
15. Double Docket on an actual Slayer assignment (accepted before and picked up mid-
    contract both count); Dossier rows and lifetime totals; a card crossing 100 kills of
    service starts showing wear in the Album. To see the later stages without
    the grind, turn on **Debug commands** and run `::gachawear cracked scimitar` — the
    Album cell must repaint without a tab click, and `::gachawear none` must take it back
    off. Check a card that is currently in the loadout DURING a contract too: the banked
    kills are flushed first, so the number must not creep past what you set.
16. Party grouping and identity — needs three clients, and mostly needs a second ACCOUNT,
    which is the part no unit test can stand in for.
    - Grouping: put two members on one shared contract and leave a third idle. Two blocks,
      one pooled meter over the pair, and the meter must read the same number on all three
      screens rather than the pair's sum. Land a kill and watch it move on every screen.
      Then run two shared contracts at once in a party of five — two blocks of two plus the
      idle member, and your own block is always the top one.
    - Eligibility: deal a board on one client and sign nothing. Their row on the other
      screens must say **Undecided board**, not "No contract", until they sign or the board
      clears. Close the client entirely and the row goes to **No signal** after ~1 minute.
    - Identity across a relog: finish a shared contract, then have your partner log out and
      back in. Their pip and their Patrons row must be the SAME row with the count intact —
      a second row at 1 means the ledger fell back to something per-session. Repeat with a
      world hop, and with them rejoining the party rather than just relogging.
    - Identity across a rename: the hard one, and the reason the ledger moved off names.
      Finish a shared contract, have the partner change their display name, finish another.
      One Patrons row reading 2, showing the NEW name — never two rows reading 1.
    - Dual-log: sign the same account into the party from two clients and finish a shared
      contract. One mark, not two. Do it with your OWN account as the second client too —
      you must not appear in your own Patrons list at all.
    - Tabs: on a fresh profile, **Dossier**, **Patrons** and **Loadout** are all absent.
      Finish one solo contract and Dossier appears; finish one shared one and Patrons
      appears; toggle **One card per slot** and Loadout comes and goes. Each one must show
      up without needing a tab click or a client restart, and switching **One card per
      slot** off while sitting ON the Loadout page must land you on Overview rather than a
      blank panel.
    - Patrons page: partners in descending contract order with the top one marked, the tier
      label matching the pip colour on the Party page, and "last" reading as a sane
      relative age. A partner whose name your client never read draws as "An unnamed
      patron" with the count intact.
