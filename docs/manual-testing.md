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
   golden final kill), side bets incl. a sealed reveal.
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
    contracts** off. Fighting Weight: a mixed-level party's offers must be sized to the
    average, and a party containing an older client must fall back to the lowest.
13. Charter Office: only monsters with 25+ banked kills are listed, the quoted contract is
    the one you receive, the deed appears on the board as a fifth offer, and the GC comes
    back in full if you let it expire. Log out with a deed held and back in — the first
    tick must settle it, never eat it. The daily lock lifts at UTC midnight.
14. The Ante (turn the setting on first): the offer appears only with an INSANE contract on
    the board, arming takes two confirmations, disarming takes none, the purse drops by the
    stake at signing, completion pays it back doubled, and death takes it. In a party, one
    member declining must leave the contract intact and unstaked for everyone.
15. Double Docket on an actual Slayer assignment (accepted before and picked up mid-
    contract both count); Dossier rows and lifetime totals; a card crossing 100 kills of
    service picks up its first kintsugi seam in the Album.
