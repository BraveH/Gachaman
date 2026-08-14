package com.gachaman.ui.panel;

import java.util.List;
// A nested-type import, not a static one. The com.gachaman.service.* wildcard
// below reaches that package's top-level types only — never the Unlock class
// nested inside QuestExemptionService, which is the sole reason that qualifier
// used to be spelled out in full eight times over. Unlock is a name unique to
// this repo, so it collides with nothing the other wildcards bring in.
import com.gachaman.service.QuestExemptionService.*;
import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.party.*;
import com.gachaman.service.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.border.*;
import net.runelite.client.callback.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;
// The three types this panel used to name on nearly every line. The rest of the
// package already sanctions the pattern — DossierTab, HelpTab, PartyTab,
// PatronsTab and TimelineTab all static-import GachamanPanel members today —
// and the on-demand form is what CLAUDE.md's wildcard rule asks for.
//
// No ambiguity is introduced: a static-import-on-demand only ever competes with
// another static-import-on-demand, and these three export disjoint simple names
// (GachamanPanel's widget helpers and width constants, ColorScheme's colours,
// QuantityFormatter's formatters). Anything OverviewTab inherits from JPanel
// wins over all of them anyway, since class members shadow imports.
//
// The bare LEFT_ALIGNMENT below comes from neither: it is java.awt.Component's
// public static float, inherited all the way down Component -> Container ->
// JComponent -> JPanel -> OverviewTab, so it is a member of this class and is
// in scope even inside the static helpers. That inheritance is also what makes
// it immune to the GachamanPanel static import, which re-exports the same
// constant through the same ancestor.
import static com.gachaman.ui.panel.GachamanPanel.*;
import static net.runelite.client.ui.ColorScheme.*;
import static net.runelite.client.util.QuantityFormatter.*;

/**
 * Overview: GC balances, style lock + cycle, the active task (with side bets
 * and abandon), taint warning, pity meter, and the Roll Tasks button.
 */
@Singleton
public class OverviewTab extends JPanel {
	private static final Color TAINT_RED = new Color(190, 60, 55);
	private static final Color SIDEBET_DONE = new Color(110, 200, 110);
	private static final Color DOCKET_GREEN = new Color(120, 220, 120);
	/** The Ante: warm, not festive — it marks money at risk, not a prize. */
	private static final Color ANTE_AMBER = new Color(214, 158, 74);

	private final GachaStateService stateService;
	private final TaskService taskService;
	private final ClientThread clientThread;
	private final PartyRollService partyRollService;
	private final GachamanConfig config;

	private final QuestExemptionService questExemptionService;

	private BooleanSupplier inPartySupplier = () -> false;
	/** The live Quest-unlocked section, so a late answer can tell if it is stale. */
	private JPanel questUnlocks;

	@Inject
	public OverviewTab(GachaStateService stateService, TaskService taskService,
		ClientThread clientThread,
		PartyRollService partyRollService,
		GachamanConfig config,
		QuestExemptionService questExemptionService) {
		this.stateService = stateService;
		this.taskService = taskService;
		this.clientThread = clientThread;
		this.partyRollService = partyRollService;
		this.config = config;
		this.questExemptionService = questExemptionService;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(new EmptyBorder(0, 0, 6, 0));
	}

	void setInPartySupplier(BooleanSupplier supplier) {
		if (supplier != null) {
			this.inPartySupplier = supplier;
		}
	}

	void rebuild() {
		removeAll();
		GachaState state = stateService.get();
		if (state == null) {
			// drop the reference too, or a snapshot still in flight would fill a
			// section that is no longer on screen
			questUnlocks = null;
			add(centeredNote("Log in to begin your Gachaman journey."));
			revalidate();
			repaint();
			return;
		}

		addSection(buildBalanceSection(state));
		addSection(buildStyleSection(state));
		addSection(buildTaskSection(state));
		if (!state.isFragmentDeedForged()
			&& state.getTotalTasksCompleted() < Tuning.FRAGMENT_WINDOW_TASKS) {
			addSection(buildFragmentSection(state));
		}
		if (state.getTaint() > 0) {
			addSection(buildTaintSection(state));
		}
		addSection(buildPitySection(state));
		// last on purpose: a player who does not care scrolls past everything
		// that matters first, and it hides itself entirely when empty
		questUnlocks = section("Quest-unlocked NPCs");
		questUnlocks.setVisible(false);
		addSection(questUnlocks);
		requestQuestUnlocks(state);

		revalidate();
		repaint();
	}

	/**
	 * Quest state lives behind a client script and a varp read, so it cannot be
	 * touched from the EDT. Hop to the client thread for the snapshot, hop back
	 * to fill the section in. The section is built empty and hidden, so a rebuild
	 * that finishes before the answer arrives simply shows nothing for a frame.
	 */
	private void requestQuestUnlocks(GachaState state) {
		JPanel target = questUnlocks;
		ActiveTask task = state.getActiveTask();
		String taskMonster = task == null ? null : task.getMonsterName();
		// keep this a BLOCK lambda. ClientThread overloads invokeLater on Runnable
		// and on BooleanSupplier, and only a body that cannot yield a value forces
		// the Runnable one; viewRolledContracts below documents what a task bound
		// to the BooleanSupplier overload costs.
		clientThread.invokeLater(() -> {
			List<Unlock> unlocks = questExemptionService.currentUnlocks();
			SwingUtilities.invokeLater(() -> fillQuestUnlocks(target, unlocks, taskMonster));
		});
	}

	private void fillQuestUnlocks(JPanel target, List<Unlock> unlocks, String taskMonster) {
		if (target != questUnlocks) {
			return; // a later rebuild replaced the section this answer was for
		}
		// the contract's own monster is not news - this section is for what the
		// player can hit BESIDES it
		List<Unlock> shown = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Unlock unlock : unlocks) {
			if (taskMonster != null && taskMonster.equalsIgnoreCase(unlock.getNpcName())) {
				continue;
			}
			// NUL joins the two halves of the dedupe key because no NPC or quest
			// name can contain it, so ("a", "b\0c") and ("a\0b", "c") stay
			// distinct. Write it as the escape \0 and NEVER as a raw 0x00 byte in
			// the literal: a raw NUL makes `file` call this source "data" and
			// makes grep/ripgrep treat it as binary and SKIP it, so a repo-wide
			// search silently misses every line of this class. That is not
			// hypothetical — it happened, and it made the live party-roll UI
			// (proposalGroups, PendingProposal, joinProposal, declineProposal)
			// look like dead code to an audit until the greps were re-run with -a.
			if (seen.add(unlock.getNpcName().toLowerCase(Locale.ROOT)
				+ "\0" + unlock.getQuestName())) {
				shown.add(unlock);
			}
		}
		if (shown.isEmpty()) {
			target.setVisible(false);
			return;
		}
		shown.sort(Comparator
			.comparing((Unlock u) -> !u.isManual())
			.thenComparing(u -> u.getQuestName() == null ? "" : u.getQuestName())
			.thenComparing(Unlock::getNpcName));
		// every one of them, never a "+N more": a player checking whether the
		// monster in front of them is unlocked needs to find it in this list
		for (Unlock unlock : shown) {
			target.add(unlockRow(unlock));
		}
		target.setVisible(true);
		target.revalidate();
		target.repaint();
	}

	/**
	 * NPC on the left, what unlocked it on the right, one line each.
	 *
	 * <p>Not {@link GachamanPanel#row}: that puts the left component in CENTER,
	 * so in a 205px column a long quest name squeezes the NPC name until Swing
	 * elides it — losing exactly the word the player is trying to match against
	 * the monster in front of them. Here the NPC holds its width and the quest
	 * gives way first.
	 */
	static JPanel unlockRow(Unlock unlock) {
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		row.add(smallLine(unlock.getNpcName(), Color.WHITE),
			BorderLayout.WEST);
		JLabel why = smallLine(
			unlock.isManual() ? "manual override" : unlock.getQuestName(),
			unlock.isManual() ? ANTE_AMBER : LIGHT_GRAY_COLOR);
		why.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(why, BorderLayout.CENTER);
		return row;
	}

	private void addSection(JPanel section) {
		add(section);
		gap(this, 6);
	}

	/**
	 * Every vertical gap on this panel goes through here. Twenty-six call sites
	 * spelled {@code panel.add(Box.createVerticalStrut(n))} out in full and the
	 * repetition bought nothing: {@code createVerticalStrut} hands back a fresh
	 * Filler on every call in either spelling, so each gap keeps its exact pixel
	 * height and its exact position in the parent's child order.
	 */
	private static void gap(JComponent panel, int height) {
		panel.add(Box.createVerticalStrut(height));
	}

	/**
	 * The four-beat button dance — make it, tooltip it, wire it, add it —
	 * collapsed into one call, because most of the buttons on this panel are
	 * purely declarative. The button still lands at exactly the same point in the
	 * parent's child order, which is the only order-sensitive step of the four.
	 *
	 * <p>A null {@code tooltip} skips {@code setToolTipText} rather than passing
	 * null down to it. {@code setToolTipText(null)} would in practice be a no-op
	 * on a component that was never registered with the ToolTipManager, but the
	 * two tooltip-less buttons here are then provably untouched rather than
	 * merely almost-untouched.
	 *
	 * <p>Deliberately NOT named {@code button}. A method declared in this class
	 * shadows a static import of the same NAME regardless of signature, so that
	 * spelling would hide {@link GachamanPanel#button(String)} from the three
	 * call sites that still need the plain form to call setEnabled on.
	 */
	private static void actionButton(JComponent parent, String text, String tooltip,
		ActionListener action) {
		JButton made = button(text);
		if (tooltip != null) {
			made.setToolTipText(tooltip);
		}
		made.addActionListener(action);
		parent.add(made);
	}

	/**
	 * The host's "Cancel Party Roll" button, which the Contract section offers
	 * from two different places — beside a board that has already been rolled,
	 * and beside the force-start control before it has — with only the tail of
	 * the tooltip differing between the two.
	 */
	private void addCancelRoll(JPanel section, String tail) {
		gap(section, 3);
		actionButton(section, "Cancel Party Roll",
			"Host only: abort this party roll for every member who joined it" + tail,
			e -> partyRollService.cancelRoll());
	}

	private JPanel buildBalanceSection(GachaState state) {
		// the GC readout itself is shared with the Shop tab; only the two lines
		// under it are this page's
		JPanel section = GachamanPanel.balanceSection(state);
		gap(section, 4);
		section.add(smallLine(
			"Lifetime earned: " + formatNumber(state.getLifetimeGcEarned()) + " GC",
			LIGHT_GRAY_COLOR));
		section.add(smallLine(
			"Reroll tokens: " + state.getRerollTokens(), LIGHT_GRAY_COLOR));
		return section;
	}

	private JPanel buildStyleSection(GachaState state) {
		JPanel section = GachamanPanel.section("Allowed Style");
		if (state.getAllowedStyle() == null) {
			section.add(smallLine("Not yet rolled — fate awaits.", LIGHT_GRAY_COLOR));
			return section;
		}
		AttackStyle style;
		try {
			style = AttackStyle.valueOf(state.getAllowedStyle());
		}
		catch (IllegalArgumentException e) {
			section.add(smallLine("Unknown style", LIGHT_GRAY_COLOR));
			return section;
		}
		section.add(line(style.getDisplayName(), style.getColor(),
			FontManager.getRunescapeBoldFont().deriveFont(16f)));
		gap(section, 5);
		int target = Math.max(1, state.getCycleTarget());
		double progress = state.getCycleProgress();
		String barLabel = trimDouble(progress) + " / " + target + " contracts";
		section.add(new MeterBar(progress / target, style.getColor(), barLabel));
		gap(section, 3);
		section.add(smallLine("Style re-rolls when the cycle completes.",
			MEDIUM_GRAY_COLOR));
		return section;
	}

	/**
	 * One card per party roll on offer, host first.
	 *
	 * <p>Adds nothing at all when there is nothing to answer — including while
	 * this client is committed to a roll or hosting one, which the SERVICE
	 * enforces rather than the panel, so the rule holds for the chat commands
	 * too. A player can be offered several rolls at once and can take exactly
	 * one; joining answers the rest for them.
	 */
	private void addProposalCards(JPanel section) {
		// the enhanced-for evaluates proposalGroups() exactly once, same as the
		// local it replaced
		for (PartyRollService.PendingProposal offer : partyRollService.proposalGroups()) {
			section.add(buildProposalCard(offer));
			gap(section, 6);
		}
	}

	private JComponent buildProposalCard(PartyRollService.PendingProposal offer) {
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(false);
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(90, 82, 60)),
			new EmptyBorder(5, 6, 6, 6)));
		card.setMaximumSize(new Dimension(SECTION_WIDTH, Integer.MAX_VALUE));

		String host = offer.getHostName()
			+ (offer.getHostCombatLevel() > 0 ? "  lvl " + offer.getHostCombatLevel() : "");
		card.add(line(host + "'s roll" + (offer.isMine() ? "  (yours)" : ""),
			new Color(230, 190, 80), FontManager.getRunescapeBoldFont()));
		gap(card, 2);

		// the rule the roll would ACTUALLY use, and how long the offer stands.
		// Seconds, not ticks: a countdown nobody can convert is not a countdown.
		card.add(wrapped("Sizing: " + escape(offer.getSizingLabel()),
			LIGHT_GRAY_COLOR));
		card.add(smallLine((offer.isVoting() ? "voting  ·  " : offer.getAgreed() + " in  ·  ")
				+ PartyRollService.ticksToSeconds(offer.getTicksLeft())
				+ "s left",
			MEDIUM_GRAY_COLOR));
		gap(card, 3);

		for (PartyRollService.ProposalMember member : offer.getMembers()) {
			// once voting opens the useful fact about a member is which contract
			// they backed, not that they agreed to roll — everyone here agreed
			// the contract they backed, by name — an index is meaningless next to
			// a player once the board is off screen
			String state = offer.isVoting()
				? (member.getVoteLabel() == null ? "no vote" : member.getVoteLabel())
				: stanceLabel(member.getResponse());
			boolean settled = offer.isVoting()
				? member.getVote() >= 0
				: member.getResponse() == PartyRollResponseMessage.AGREE;
			card.add(smallLine(
				"  " + member.getName() + (member.isSelf() ? " (you)" : "")
					+ (member.isHost() ? " — host" : "")
					+ "  ·  " + state,
				settled ? LIGHT_GRAY_COLOR : MUTED_MEMBER));
		}

		if (offer.isMine()) {
			// your own group is a roster, not an offer: there is nothing left to
			// answer, and the host's Start/Cancel controls live below the section
			return card;
		}

		gap(card, 4);
		final long id = offer.getProposalId();
		JButton join = button("Join");
		join.setEnabled(!offer.isBlocked());
		join.setToolTipText(offer.isBlocked()
			? "You cannot join this roll — you have a contract, undecided rolls, or party"
				+ " contracts are off in your settings."
			: "Join this roll. Any other roll you have been offered is answered for you.");
		join.addActionListener(e -> partyRollService.joinProposal(id));
		card.add(join);
		gap(card, 3);
		actionButton(card, "Decline",
			"Sit this one out. The rest of that party may still take a contract.",
			e -> partyRollService.declineProposal(id));
		return card;
	}

	private static final Color MUTED_MEMBER = new Color(140, 140, 140);

	/** How one member has answered a proposal, in words. */
	private static String stanceLabel(int response) {
		switch (response) {
			case PartyRollResponseMessage.AGREE:
				return "in";
			case PartyRollResponseMessage.DECLINE:
				return "sitting out";
			case PartyRollResponseMessage.BUSY:
				return "busy";
			default:
				return "no answer";
		}
	}

	/**
	 * Re-show the offers already on the board, from the "View Rolled Contracts"
	 * button. Exists as a named <b>void</b> method for one reason: to decide
	 * which {@code ClientThread.invokeLater} overload the call site binds to.
	 *
	 * <p>The obvious spelling, {@code invokeLater(taskService::presentOffers)},
	 * silently picks {@code invokeLater(BooleanSupplier)} instead of
	 * {@code invokeLater(Runnable)} — ClientThread declares both. For an exact
	 * method reference JLS 15.12.2.5 makes a functional interface with a
	 * non-void return MORE SPECIFIC than one returning void, and
	 * {@code presentOffers()} returns boolean. RuneLite's invoke queue only
	 * removes a BooleanSupplier task once it returns TRUE; a false one is logged
	 * as "Deferring task" and left queued, so it is retried on every game tick
	 * forever.
	 *
	 * <p>{@code presentOffers()} returns false whenever there is nothing to
	 * present. So a click that landed just after the offers were cleared — party
	 * roll cancelled, TTL expired, world hopped, or the offer accepted through
	 * the ceremony overlay itself — leaked a permanent tick task, which then
	 * fired the offer ceremony unprompted the next time ANY offer set existed,
	 * possibly stacked on the fresh roll's own ceremony.
	 *
	 * <p>A zero-arg expression lambda does NOT close this: a lambda with no
	 * parameters counts as explicitly typed, so the same most-specific rule
	 * applies. Only a void-returning target does, and a named method is the one
	 * spelling a test can check. The sibling call two blocks down,
	 * {@code invokeLater(taskService::rollOffers)}, is safe on its own —
	 * rollOffers returns {@code List<TaskOffer>}, which is not assignable to
	 * boolean, so BooleanSupplier is not even applicable there.
	 */
	void viewRolledContracts() {
		taskService.presentOffers();
	}

	private JPanel buildTaskSection(GachaState state) {
		JPanel section = GachamanPanel.section("Contract");
		ActiveTask task = state.getActiveTask();
		if (task == null) {
			boolean offersWaiting = state.getPendingOffers() != null && !state.getPendingOffers().isEmpty();
			if (offersWaiting) {
				boolean partyVote = state.getPendingOffers().get(0).isPartyRoll();
				section.add(wrapped(partyVote
						? "Party contracts rolled — click one to VOTE (a majority accepts)."
						: "Contracts rolled — view them and pick one.",
					LIGHT_GRAY_COLOR));
				gap(section, 4);
				// the group, through the vote: who is in it and how each of them
				// has voted. This branch used to return before the cards were
				// added, so the roster disappeared at the exact moment a majority
				// was being counted — which is when it matters most
				addProposalCards(section);
				// This button has never carried a tooltip, hence the null:
				// actionButton leaves setToolTipText uncalled for it.
				// NOT taskService::presentOffers — see viewRolledContracts above
				actionButton(section, "View Rolled Contracts", null,
					e -> clientThread.invokeLater(this::viewRolledContracts));
				if (partyVote && partyRollService.canCancelRoll()) {
					addCancelRoll(section, " (before a contract is accepted).");
				}
				addAnteControls(section, state, partyVote);
				return section;
			}
			section.add(smallLine("No active contract.", LIGHT_GRAY_COLOR));
			gap(section, 4);
			// every roll on offer, before the buttons that would start a rival one:
			// the whole point of a card is that answering it is the cheaper move
			addProposalCards(section);
			JButton roll = button("Roll Contracts");
			// canRollOffers() only knows about THIS client's contract state, and a
			// host who declined their own proposal is left in a live party vote
			// holding no offers — which reads here as "nothing going on". Rolling a
			// personal board in that window gets its scrolls force-closed the moment
			// the party vote settles, so the party's claim on this client counts too.
			roll.setEnabled(taskService.canRollOffers() && !partyRollService.committedSnapshot());
			roll.addActionListener(e -> clientThread.invokeLater(taskService::rollOffers));
			section.add(roll);
			if (inPartySupplier.getAsBoolean()) {
				// deliberately OUTSIDE the branches below, exactly where it has always
				// been: when a proposal is live and this client is not the host,
				// nothing follows it, and that bare 4px gap at the foot of the section
				// is what renders today. Flattening the old nested if must not quietly
				// close it — that would be a visible change.
				gap(section, 4);
				// the same truth table as the old `if (live) { if (canForceStart) X } else Y`,
				// one nesting level shallower. Both reads are pure, so the swapped
				// order costs nothing.
				if (!partyRollService.isProposalLive()) {
					JButton party = button("Propose Party Roll");
					party.setToolTipText("Party members without a contract join with ::gachaparty;"
						+ " the roll starts with whoever agrees (minimum 2), identical offers"
						+ " appear for everyone, and a majority vote picks the shared contract.");
					party.setEnabled(taskService.canRollOffers());
					party.addActionListener(e -> partyRollService.propose());
					section.add(party);
				}
				else if (partyRollService.canForceStart()) {
					// no status line in this branch any more: the group card above
					// already carries the host, the roster, the count and the countdown,
					// and two copies of one clock is one copy too many
					gap(section, 3);
					actionButton(section,
						"Start Roll Now (" + partyRollService.agreedCount() + " agreed)",
						"Host only: start immediately with everyone who has"
							+ " agreed so far instead of waiting out the 60s window.",
						e -> partyRollService.forceStart());
					addCancelRoll(section, ".");
				}
			}
			return section;
		}

		// Name only. The CENTER cell is ~161px once the Wiki button and the 6px gap
		// are taken out, and bold runs ~7px/char — "General Graardor  (lvl 624)"
		// measures 176px, so Swing ellipsised away the exact thing the suffix was
		// there to show. The level moves down to the difficulty line, which has the
		// full 205px to itself.
		JLabel monster = line(task.getMonsterName(),
			Color.WHITE, FontManager.getRunescapeBoldFont());
		monster.setToolTipText(task.getMonsterName() + " (level " + task.getMonsterCombatLevel() + ")");
		JButton wiki = new JButton("Wiki");
		wiki.setFont(FontManager.getRunescapeSmallFont());
		wiki.setMargin(new Insets(1, 6, 1, 6));
		wiki.setFocusPainted(false);
		wiki.setBackground(DARKER_GRAY_HOVER_COLOR);
		wiki.setForeground(new Color(240, 200, 90));
		wiki.setToolTipText("Open the OSRS wiki page for " + task.getMonsterName());
		final String wikiUrl = "https://oldschool.runescape.wiki/w/"
			+ task.getMonsterName().replace(' ', '_');
		wiki.addActionListener(e -> LinkBrowser.browse(wikiUrl));
		// row() is this header field for field: BorderLayout(6, 0), not opaque,
		// left-aligned, left child in CENTER and right child in EAST. The one extra
		// thing it does is pin a 30px maximum height — so the setMaximumSize below
		// MUST stay and MUST run after it, since that is what keeps the row the
		// height of its own tallest child rather than a flat 30.
		JPanel headerRow = row(monster, wiki);
		headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			Math.max(monster.getPreferredSize().height, wiki.getPreferredSize().height)));
		section.add(headerRow);
		section.add(smallLine(task.getDifficulty().getDisplayName()
				+ "  ·  lvl " + task.getMonsterCombatLevel()
				+ (task.isRedemption() ? " — REDEMPTION" : ""),
			task.getDifficulty().getColor()));
		if (task.isParty()) {
			// Its own line: appended to the difficulty it ran to 237px in a 205px
			// column, and the horizontal scrollbar is disabled, so the party label
			// was simply cut off.
			section.add(smallLine("SHARED: " + task.getPartyLabel(),
				task.getDifficulty().getColor()));
		}
		gap(section, 5);
		// shared contracts pool every participant's kills into the quota
		int pooled = task.getKillsDone() + (task.isParty() ? task.getPartyOtherKills() : 0);
		double killsDone = pooled + (task.isHalfKillPending() ? 0.5 : 0);
		double killFrac = task.getKillsRequired() <= 0 ? 0
			: killsDone / task.getKillsRequired();
		section.add(new MeterBar(killFrac, task.getDifficulty().getColor(),
			pooled + (task.isHalfKillPending() ? ".5" : "")
				+ " / " + task.getKillsRequired() + " kills"
				+ (task.isParty() ? " (yours: " + task.getKillsDone() + ")" : "")));
		gap(section, 4);
		section.add(smallLine(
			formatNumber(task.getPerKillGc()) + " GC / kill  ·  "
				+ formatNumber(task.getCompletionGc()) + " GC completion",
			LIGHT_GRAY_COLOR));
		if (task.getAppliedCharge() != null) {
			section.add(smallLine("Charge applied: " + prettyCharge(task.getAppliedCharge()),
				new Color(150, 190, 240)));
		}
		if (task.getAnteStake() > 0) {
			// Shown for as long as the stake exists, because it is the player's
			// own GC sitting outside the purse — the balance at the top of this
			// panel is short by exactly this much and needs the explanation.
			JLabel ante = smallLine(
				"Ante: " + formatNumber(task.getAnteStake()) + " GC staked",
				ANTE_AMBER);
			ante.setToolTipText("This GC left your purse when you signed. Complete the contract"
				+ " and " + formatNumber(
					(long) task.getAnteStake() * Tuning.ANTE_PAYOUT_MULT)
				+ " GC comes back; die and it is gone. Only your own stake is at risk —"
				+ " a party member dying does not cost you yours.");
			section.add(ante);
		}
		// Shown on EVERY contract, active or not. A bonus that only appears once
		// it is already earned is a bonus nobody goes looking for, and the "not
		// aligned" state is the only place the player can be told why they are
		// not getting it. The tooltip carries the exact when-is-it-fixed rule.
		JLabel docket = smallLine(
			task.isSlayerAligned()
				? "Double Docket: ACTIVE (x" + trimDouble(Tuning.DOUBLE_DOCKET_MULT) + ")"
				: "Double Docket: not your Slayer task",
			task.isSlayerAligned() ? DOCKET_GREEN : LIGHT_GRAY_COLOR);
		docket.setToolTipText("Kill your Slayer assignment on contract and completion pays x"
			+ trimDouble(Tuning.DOUBLE_DOCKET_MULT) + ". Checked when you accept AND on every kill, so"
			+ " picking up the matching task mid-contract still counts — and once it locks in it"
			+ " stays, even if you finish the Slayer task first. Contracts are never rolled to"
			+ " match your Slayer task; grouped assignments such as Metal dragons name no single"
			+ " monster and cannot be detected.");
		section.add(docket);

		if (task.getSideBets() != null && !task.getSideBets().isEmpty()) {
			gap(section, 5);
			section.add(smallLine("Side bets:", BRAND_ORANGE));
			for (SideBet bet : task.getSideBets()) {
				// The same three-way truth table the if/else/if here used to spell
				// out: a sealed bet hides its terms while it is still running but
				// never its payout, completing one both uncovers the terms and earns
				// the "* " marker, and describeSideBet is still called only on the
				// arm that already called it. The payout tail was written twice
				// because both old arms ended the same way.
				String text = (bet.isCompleted() ? "* " : "")
					+ (bet.isSealed() && !bet.isCompleted()
						? "???" : TaskService.describeSideBet(bet))
					+ " — " + formatNumber(bet.getPayoutGc()) + " GC";
				section.add(smallLine(text,
					bet.isCompleted() ? SIDEBET_DONE : LIGHT_GRAY_COLOR));
			}
		}

		gap(section, 4);
		section.add(wrapped(
			"A contract is a contract — it cannot be abandoned.",
			MEDIUM_GRAY_COLOR));
		return section;
	}

	private static boolean hasInsaneOffer(GachaState state) {
		if (state.getPendingOffers() == null) {
			return false;
		}
		for (TaskOffer offer : state.getPendingOffers()) {
			if (TaskService.anteEligible(offer)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The Ante's arming controls, shown only while an INSANE contract is
	 * actually on the board — an option to wager on contracts that are not there
	 * is an invitation, and this is meant to be an answer to a question the
	 * player asked.
	 *
	 * Arming is the ONLY route by which GC is ever staked. Nothing here is
	 * preselected, the button is never the obvious next click, and it takes TWO
	 * confirmations: one naming the amount, one naming what losing means.
	 * Disarming takes none — backing out of a wager should never be defended.
	 */
	private void addAnteControls(JPanel section, GachaState state, boolean partyVote) {
		if (!config.anteEnabled() || !hasInsaneOffer(state)) {
			return;
		}
		gap(section, 6);
		section.add(smallLine("The Ante", BRAND_ORANGE));

		int armed = taskService.getArmedAntePercent();
		if (armed > 0) {
			section.add(wrapped("ARMED at " + armed + "% — "
				+ formatNumber(taskService.previewAnteStake())
				+ " GC will be staked on the INSANE contract you "
				+ (partyVote
					? "vote for, and only if EVERY member agrees to their own stake."
					: "accept."),
				ANTE_AMBER));
			gap(section, 3);
			actionButton(section, "Disarm the Ante",
				"Take the wager off. Nothing has been staked yet —"
					+ " GC only leaves your purse when a contract is actually signed.",
				e -> {
					taskService.armAnte(0);
					rebuild();
				});
			return;
		}

		if (taskService.previewAnteStake(Tuning.ANTE_MIN_PERCENT) <= 0) {
			section.add(wrapped("Your purse is under "
				+ formatNumber(Tuning.ANTE_MIN_PURSE_GC)
				+ " GC, so no wager is offered.", MEDIUM_GRAY_COLOR));
			return;
		}

		section.add(wrapped(
			"Optional. Stake part of your purse against an INSANE contract before you take"
				+ " it: complete the contract and the stake returns doubled, die and it is"
				+ " gone. Contracts cannot be abandoned.",
			LIGHT_GRAY_COLOR));
		if (partyVote) {
			section.add(wrapped(
				"In a party it takes EVERY member. Each stakes from their own purse, and"
					+ " each loses only their own — one member declining means no Ante for"
					+ " anyone, but the contract goes ahead either way.",
				LIGHT_GRAY_COLOR));
		}
		gap(section, 4);

		// The GC figure is baked into each item rather than tracked by a listener:
		// the amount is the whole decision, so it must be readable at the moment
		// of choosing without a second glance elsewhere.
		JComboBox<String> percents = new JComboBox<>();
		// The ten-point stops across the legal band, both ends included. Enumerated
		// rather than materialised into an int[] first, because the progression IS
		// the index: item i is ANTE_MIN_PERCENT + 10*i — 10/20/30/40/50 for today's
		// 10..50 band — which is exactly how the listener below recovers the chosen
		// percent back out of the combo's selected index.
		for (int percent = Tuning.ANTE_MIN_PERCENT; percent <= Tuning.ANTE_MAX_PERCENT;
			percent += 10) {
			percents.addItem(percent + "%  —  "
				+ formatNumber(taskService.previewAnteStake(percent)) + " GC");
		}
		percents.setSelectedIndex(0);
		percents.setFont(FontManager.getRunescapeSmallFont());
		percents.setAlignmentX(LEFT_ALIGNMENT);
		percents.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			percents.getPreferredSize().height));
		section.add(percents);
		gap(section, 3);

		actionButton(section, "Arm the Ante",
			"Arms only. The GC leaves your purse when you sign an INSANE"
				+ " contract, and is priced from your purse at that moment (capped at "
				+ formatNumber(Tuning.ANTE_MAX_GC) + " GC).",
			e -> {
				// The inverse of the loop that filled the combo: index i is the i'th
				// ten-point stop up from the floor. Math.max keeps the no-selection
				// (-1) case pinned to the minimum, exactly as the array lookup did.
				int percent = Tuning.ANTE_MIN_PERCENT
					+ 10 * Math.max(0, percents.getSelectedIndex());
				int stake = taskService.previewAnteStake(percent);
				if (stake <= 0) {
					return;
				}
				String amount = formatNumber(stake);
				if (!confirm(this, "Arm the Ante?",
					"Stake " + amount + " GC (" + percent + "% of your purse) on the next INSANE"
						+ " contract you take? The exact amount is fixed when you sign.")) {
					return;
				}
				if (!confirm(this, "If you die, it is gone",
					"Dying while that contract is active loses the " + amount + " GC outright."
						+ " The contract itself is still binding and cannot be abandoned."
						+ " Arm the Ante?")) {
					return;
				}
				taskService.armAnte(percent);
				rebuild();
			});
	}


	private JPanel buildTaintSection(GachaState state) {
		JPanel section = GachamanPanel.section(null);
		section.setBackground(new Color(56, 26, 24));
		section.add(line("TAINT ACTIVE (" + state.getTaint() + ")",
			TAINT_RED, FontManager.getRunescapeBoldFont()));
		gap(section, 3);
		section.add(wrapped(
			"Forbidden gear or style has stained your record. All GC income is halved"
				+ " until the taint is worked off with clean kills (or a Redemption contract).",
			new Color(230, 170, 160)));
		return section;
	}

	/**
	 * Deed Fragments and the Pity Meter render the same six beats: a titled
	 * section, a MeterBar labelled "&lt;done&gt; / &lt;total&gt; &lt;unit&gt;", a
	 * 3px gap, then a grey footnote. Only the title, the bar colour and the note
	 * ever differ, so those are the arguments and the shape is written once.
	 *
	 * <p>The fraction keeps its {@code (double)} cast, which is load-bearing:
	 * both meters divide an int by an int, so dropping it would floor every bar
	 * to empty-or-full. The same trap sits at the Pity call site, where the 0.8
	 * colour threshold is computed from that same integer pair.
	 *
	 * <p>{@link GachamanPanel#section} already appends the title label AND a 6px
	 * strut of its own when the title is non-null, so nothing is added here
	 * before the bar.
	 */
	private static JPanel meterSection(String title, int done, int total, Color bar,
		String unit, String note) {
		JPanel section = GachamanPanel.section(title);
		section.add(new MeterBar((double) done / total, bar,
			done + " / " + total + " " + unit));
		gap(section, 3);
		section.add(wrapped(note, MEDIUM_GRAY_COLOR));
		return section;
	}

	private JPanel buildFragmentSection(GachaState state) {
		int tasksLeft = Tuning.FRAGMENT_WINDOW_TASKS - state.getTotalTasksCompleted();
		return meterSection("Deed Fragments", state.getDeedFragments(),
			Tuning.FRAGMENTS_REQUIRED, new Color(230, 190, 80), "fragments",
			"Medium+ contracts drop fragments during your first "
				+ Tuning.FRAGMENT_WINDOW_TASKS + " contracts (" + tasksLeft + " left). Collect "
				+ Tuning.FRAGMENTS_REQUIRED + " to forge a bonus Slot Deed.");
	}

	private JPanel buildPitySection(GachaState state) {
		int cap = Tuning.PITY_HARD_CAP;
		// warms from violet to amber over the last fifth of the run to the cap. The
		// (double) cast is the same one meterSection needs — int/int would only ever
		// clear 0.8 by hitting the cap exactly.
		Color barColor = (double) state.getOpensSinceEpic() / cap >= 0.8
			? new Color(226, 148, 62) : new Color(150, 120, 220);
		return meterSection("Pity Meter", state.getOpensSinceEpic(), cap, barColor, "cards",
			"Cards revealed without an Epic+. At the cap, a Legendary is guaranteed.");
	}

	private static String prettyCharge(String charge) {
		// the constant is the receiver of equals, so a null charge falls all the way
		// through to the identity return exactly as the old if/if/return did
		return "COMPACTOR".equals(charge) ? "Compactor"
			: "EXTENDER".equals(charge) ? "Extender" : charge;
	}

	/**
	 * One decimal place, and only when there is one worth showing.
	 *
	 * <p>{@link Locale#ROOT} is load-bearing, not decoration. Plain
	 * {@code String.format("%.1f", …)} formats in the DEFAULT locale, so a client
	 * running under a comma-decimal locale (de, fr, es, pt-BR — much of the EU)
	 * printed the style cycle as "2,5 / 3 contracts" and the Double Docket as
	 * "x1,5". Every other number in this panel comes from
	 * {@link QuantityFormatter#formatNumber}, which groups thousands with a
	 * comma, so the player saw "1,250 GC" and "x1,5" side by side with the two
	 * commas meaning opposite things. Pinning ROOT gives every player the string
	 * a UK/US player already sees, so nothing changes on the common locales.
	 *
	 * <p>Both live callers really do reach this line: the cycle-progress bar
	 * label (a half-kill leaves progress on x.5) and the Double Docket
	 * multiplier, which is 1.2. Whole values leave early through
	 * {@code String.valueOf((int) value)} and were locale-independent already.
	 *
	 * <p>Package-private rather than private so the locale can be pinned from a
	 * test — the same arrangement {@link ShopTab#pct} already uses.
	 */
	static String trimDouble(double value) {
		if (value == Math.floor(value)) {
			return String.valueOf((int) value);
		}
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
