# Gachaman — working rules

## Never remove a feature without approval

Not to make room in the token budget, not to simplify, not for any reason. If a
change does not fit, **say so and stop** — present the options and the costs and
let the owner choose what goes. Raising a concern and then acting on it anyway
is not approval; it is deciding on someone else's behalf after telling them you
noticed.

This applies to anything player-visible: a config option, a chat notice, a
background check, a UI element. When the budget is the blocker, report the
shortfall and the candidates. Do not pick.

## The token budget is the binding constraint

The Plugin Hub's review bot refuses to run above **200,000 tokens**. It counts
`src/main/java` with **comments and blank lines stripped**. Everything else is
free: tests, `src/test`, the README, `docs/`, and every JSON resource.

Two consequences that drive every decision in this repo:

- **Comments cost nothing.** Never shorten a comment to save budget — explain
  freely. Only real code counts.
- **Tests cost nothing.** There is no budget argument against adding a test.
  When a change has an invariant worth pinning, pin it.

### Measuring it

```bash
python tools/tokens.py            # the working tree
python tools/tokens.py <git-ref>  # any commit
```

Run it before and after any sizeable change. It tokenises the stripped corpus
with `o200k_base` and applies a correction calibrated against the two figures
the Hub bot actually reported (`ca280de` → 249,632, `73f21a5` → ~206,000).

**The ~3% disagreement in this repo's history is not noise — it is two
different tools.** The `199,830` in `1ac9e3b` is the author's own measurement
and matches `cl100k_base` on the stripped corpus to 0.08%. The bot's numbers
run ~2% above `o200k_base` on the same corpus. Only the bot's figure decides
whether a submission is accepted, so `tools/tokens.py` calibrates to the bot
and reports a range. **Treat the high end as real.**

## Imports: wildcards, never inline fully-qualified names

Use `import java.util.*;` style throughout. An inline `java.util.List<String>`
or `@javax.annotation.Nullable` in the body is pure wasted budget — the same
mistake commit `ca280de` already cleaned up once.

**Explicit single-class imports in this codebase are deliberate collision
resolvers, not leftovers.** Do not "tidy" them away. Known collisions:

| Class | Collides between | Files affected |
|---|---|---|
| `Inject`, `Singleton` | `javax.inject.*` vs `com.google.inject.*` | `GachamanPlugin` |
| `List` | `java.util.*` vs `java.awt.*` | any overlay/UI class |
| `Point` | `java.awt.*` vs `net.runelite.api.*` | overlays |
| `InterfaceID`, `InventoryID` | `net.runelite.api.gameval.*` vs `net.runelite.api.*` / `net.runelite.api.widgets.*` | plugin, overlays |
| `Timer` | `javax.swing.*` vs `java.util.*` | panels |

Collapse first, let the compiler name the casualties, then restore each to the
package it actually came from — never guess.

## Persistence: write what the player would be angry to lose

State lives in two places — RuneLite's RSProfile config and the plugin's own
`state.dat`. Both are stamped with `savedAtMs` and `StateStore.load()` takes the
**newer**. Never reintroduce "config always wins": a client that dies without a
clean shutdown leaves config behind the disk copy, and preferring it silently
rolls the player back.

Routine mutations ride the 1s debounce. Anything the player would resent losing
calls `stateService.checkpoint()` immediately — already done for task
completion, chest commit, deed claim, and loadout assign/unassign. Add the call
for any new event of that weight.

## Plugin Hub rules

Read the [rejected features list](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Features)
before adding anything that touches menus, input or automation.

- **No automated game actions, ever.** No `client.menuAction`, no synthetic
  input. Every action must originate from a real click. The README's "no
  automated actions" disclosure has to stay true.
- **No reflection, JNI, subprocesses, or runtime code loading.** All Java.
- Conditional menu-entry removal is a grey area. `CombatBlockService` hides
  Attack on non-contract NPCs; hub precedent for that exists (`bronzeman-tcg`,
  `tcg-locked`) but **only for NPCs** — neither touches player targets, and both
  are config-gated with an off switch. Gachaman's is neither.

## Style

Tabs. Comments explain *why*, not what — the existing ones set the bar, and they
are free, so match their depth. Follow the surrounding file.
