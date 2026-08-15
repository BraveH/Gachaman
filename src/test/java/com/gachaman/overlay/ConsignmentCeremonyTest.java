package com.gachaman.overlay;

import java.awt.Point;
import com.gachaman.*;
import com.gachaman.model.*;
import com.gachaman.service.*;
import com.google.gson.*;
import java.awt.*;
import java.awt.image.*;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.*;
import org.junit.*;

/**
 * The Consignment's offer ceremony, end to end through the real overlay.
 *
 * <p>Everything here exists because the offer is the one request on the
 * CeremonyBus that TAKES something. Every other ceremony is a reward already
 * banked: drop a fanfare and a banner is missed, abort a chest reveal and the
 * cards are still committed. Drop THIS one and the player is left with a style
 * roll owed, a day key in an ambiguous state, and no question on screen to
 * answer — so the four ways it can leave the screen (a click, Escape, an abort,
 * a teardown) each have to reach ConsignmentService, and each has to reach it
 * with the RIGHT one of accept / decline / abandon. Those three are not
 * interchangeable: two of them spend the day and one deliberately does not.
 *
 * <p>The overlay is driven exactly as the client drives it — a Client proxy
 * answering the four calls it makes, the real {@link CeremonyBus}, and a
 * ConsignmentService subclass that records which answer arrived. Nothing is
 * stubbed between the click and the service.
 *
 * <p>Explicit {@code java.awt.Point} import: {@code handleClick} takes the AWT
 * one, and {@code net.runelite.api} exports a Point of its own — the same
 * collision RevealOverlay itself resolves at the top of the file.
 */
public class ConsignmentCeremonyTest
{
	/** The fixed-mode canvas, which is the smallest one a real client ever has. */
	private static final int W = 765;
	private static final int H = 503;
	/** Any tick; the assertion is that this exact number reaches the service. */
	private static final int TICK = 4271;

	/**
	 * Records which answer arrived, and nothing else.
	 *
	 * <p>A subclass rather than an interface stub because {@code Presenter} is the
	 * seam pointing the other way — the overlay holds the concrete service, so the
	 * only way to watch what it says to it is to be that service.
	 */
	private static class Spy extends ConsignmentService
	{
		int accepts;
		int declines;
		int abandons;
		int lastTick = -1;

		Spy()
		{
			super(null, null, null, null, null);
		}

		@Override
		public boolean accept(int currentTick)
		{
			accepts++;
			lastTick = currentTick;
			return true;
		}

		@Override
		public boolean decline(int currentTick)
		{
			declines++;
			lastTick = currentTick;
			return true;
		}

		@Override
		public void abandon()
		{
			abandons++;
		}

		int answered()
		{
			return accepts + declines;
		}
	}

	/**
	 * A ClientThread that runs the job INLINE, on the caller's thread.
	 *
	 * <p>Every assertion below reads the spy on the line after the click, so a
	 * stub that queued the job would turn each of them green by never running the
	 * code under test. Inline keeps the real path — click, hop, service — intact
	 * and merely collapses the hop to a call.
	 *
	 * <p>{@code invoke(Runnable)} is the overload to override, not
	 * {@code invokeLater}: the overlay calls invoke(), and the real one asks its
	 * injected {@code client} field whether we are already on the client thread —
	 * a field no hand-built ClientThread has, so falling through to super would
	 * NPE rather than defer. (ShopOddsRequestTest's stub next door overrides
	 * invokeLater for the same reason in reverse: that is what ShopTab calls.)
	 */
	private static final class InlineClientThread extends ClientThread
	{
		@Override
		public void invoke(Runnable job)
		{
			job.run();
		}
	}

	/**
	 * A ClientThread that QUEUES instead of running, the way the real one does
	 * when the caller is not already on the client thread.
	 *
	 * <p>This is the stub that can tell a hop from a direct call. An inline stub
	 * cannot: the service is reached either way, so an overlay that dropped the
	 * hop entirely would keep every other test in this file green.
	 */
	private static final class QueueingClientThread extends ClientThread
	{
		private final Deque<Runnable> jobs = new ArrayDeque<>();

		@Override
		public void invoke(Runnable job)
		{
			jobs.add(job);
		}

		int run()
		{
			int ran = 0;
			// drained by index rather than by iterator: a job may post another
			// one (applyDeedClaim's dismissal does exactly that), and the real
			// queue would run those too
			while (!jobs.isEmpty())
			{
				jobs.poll().run();
				ran++;
			}
			return ran;
		}
	}

	/**
	 * A Client that answers the four things the ceremony asks it and nothing
	 * else: the game state it claims ceremonies on, the canvas it lays out
	 * against, and the tick it stamps an answer with.
	 *
	 * <p>The game state is read out of the caller's own one-element array so a
	 * test can log in and out under a live overlay, which is the only way to
	 * exercise the parking rule.
	 */
	private static Client client(GameState[] state)
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return state[0];
					case "getCanvasWidth":
						return W;
					case "getCanvasHeight":
						return H;
					case "getTickCount":
						return TICK;
					default:
						return blank(method.getReturnType());
				}
			});
	}

	/** Type-correct nothing, so an unexpected call cannot blow up the proxy. */
	private static Object blank(Class<?> type)
	{
		if (!type.isPrimitive() || type == void.class)
		{
			return null;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == double.class)
		{
			return 0d;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		return 0;
	}

	private static RevealOverlay overlay(CeremonyBus bus, ConsignmentService consignment)
	{
		return overlay(bus, consignment, new GameState[]{GameState.LOGGED_IN});
	}

	private static RevealOverlay overlay(CeremonyBus bus, ConsignmentService consignment,
		GameState[] state)
	{
		return overlay(bus, consignment, state, new InlineClientThread());
	}

	private static RevealOverlay overlay(CeremonyBus bus, ConsignmentService consignment,
		GameState[] state, ClientThread clientThread)
	{
		// ChestService is real but starved: handleClick and render both snapshot
		// the reroll tokens before touching the lock, and a null `pending` is the
		// state every non-chest ceremony is in anyway.
		ChestService chests = new ChestService(null, null, null, null, null, null, null, null, null);
		return new RevealOverlay(client(state), clientThread, bus, chests,
			null, null, null, null, null,
			new CeremonyPlayer(new Gson()), consignment);
	}

	private static ConsignmentService.Offer offer()
	{
		return new ConsignmentService.Offer(AttackStyle.MAGIC, 0, Tuning.Chest.GILDED);
	}

	/** Click the middle of answer {@code i}, exactly where the plate is drawn. */
	private static void clickAnswer(RevealOverlay overlay, int i)
	{
		Rectangle r = new Rectangle();
		RevealOverlay.consignRect(i, W, H, r);
		overlay.handleClick(new Point(r.x + r.width / 2, r.y + r.height / 2));
	}

	/** An overlay with the offer already on screen. */
	private static RevealOverlay showing(CeremonyBus bus, ConsignmentService consignment)
	{
		RevealOverlay overlay = overlay(bus, consignment);
		bus.addRenderer(overlay);
		Assert.assertTrue("the presenter must claim the offer", overlay.present(offer()));
		Assert.assertTrue("an unobstructed offer should be on screen", overlay.isModalActive());
		return overlay;
	}

	/**
	 * The presenter claims by ENQUEUEING, not by asking whether it is free.
	 *
	 * <p>This is the whole feature, and the way to lose it is to write the
	 * obvious presenter. {@code offerOrRoll} is called from a completed contract,
	 * by which point TASK_COMPLETE is already queued — and on every 10th, 25th,
	 * 45th... contract a DEED_CHOICE is too, because every deed milestone is a
	 * multiple of the style cycle. A presenter that answered "not now, a modal is
	 * up" would answer that essentially every time, the service would quietly take
	 * the ordinary roll, and the Consignment would never appear once. Nothing
	 * would log; the feature would simply not exist.
	 */
	@Test
	public void theOfferParksBehindTheDeedScreenAndPresentsWhenItClears()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = overlay(bus, consignment);
		bus.addRenderer(overlay);

		bus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
		Assert.assertTrue("the deed screen should have claimed the modal",
			overlay.isModalActive());

		Assert.assertTrue("an offer must be claimed even while a ceremony is up",
			overlay.present(offer()));
		Assert.assertEquals("claiming is not answering", 0, consignment.answered());

		// dismissing the deed drains the bus, which is where the parked offer
		// comes back — the same path every declined request takes
		overlay.handleEscape();
		Assert.assertTrue("the parked offer should be on screen now",
			overlay.isModalActive());

		// and it really is the offer, not a leftover deed screen: only the
		// Consignment answers a click on this rectangle
		clickAnswer(overlay, 0);
		Assert.assertEquals("the presented ceremony was not the offer", 1, consignment.accepts);
	}

	/**
	 * An offer raised while logged out waits for the login rather than being
	 * eaten by it.
	 *
	 * <p>Ceremonies are only ever drawn in game — one claimed at the login screen
	 * would sit invisible over "Click here to play" while its input listener ate
	 * every click — so the renderer refuses everything until LOGGED_IN. For most
	 * requests a refusal that went wrong would cost a banner. For this one it
	 * would cost the answer to a question, and the CeremonyBus has exactly two
	 * behaviours available to it: park the request, or hand it to a
	 * FallbackHandler that would have to answer a binding choice in chat. Nothing
	 * anywhere registers a fallback, so it parks — and that is the property this
	 * pins, because it is one {@code setFallback} call away from changing.
	 */
	@Test
	public void anOfferRaisedAtTheLoginScreenParksUntilTheGameIsIn()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		GameState[] state = {GameState.LOGIN_SCREEN};
		RevealOverlay overlay = overlay(bus, consignment, state);
		bus.addRenderer(overlay);

		Assert.assertTrue("the offer is claimed whatever the game state is",
			overlay.present(offer()));
		Assert.assertFalse("nothing may be drawn over the login screen",
			overlay.isModalActive());
		Assert.assertEquals("a parked offer must not answer itself", 0, consignment.answered());
		Assert.assertEquals(0, consignment.abandons);

		state[0] = GameState.LOGGED_IN;
		bus.drain(); // the plugin's own post-login drain
		Assert.assertTrue("the parked offer must present after login",
			overlay.isModalActive());
		clickAnswer(overlay, 0);
		Assert.assertEquals("the offer survived the logout intact", 1, consignment.accepts);
	}

	@Test
	public void takingTheDealAcceptsOnceAndReleasesTheScreen()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		clickAnswer(overlay, 0);
		Assert.assertEquals(1, consignment.accepts);
		Assert.assertEquals(0, consignment.declines);
		Assert.assertEquals(0, consignment.abandons);
		// released before the answer is sent, so the roulette the answer causes
		// can claim the screen immediately and the crate can park behind it
		Assert.assertFalse("the modal must be released by the answer",
			overlay.isModalActive());
		Assert.assertEquals("the answer is stamped with the CURRENT tick",
			TICK, consignment.lastTick);
	}

	@Test
	public void spinningTheWheelDeclines()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		clickAnswer(overlay, 1);
		Assert.assertEquals(1, consignment.declines);
		Assert.assertEquals(0, consignment.accepts);
		Assert.assertFalse(overlay.isModalActive());
	}

	/**
	 * Escape REFUSES; it does not dismiss.
	 *
	 * <p>The deed choice next door treats Escape as "not now" and re-queues,
	 * which costs nothing because the deed is still owed either way. An offer
	 * that behaved like that would be re-offerable at will — Escape, finish
	 * another contract, be asked again — and the once-per-day gate would be worth
	 * nothing. Refusing is an answer, and an answer spends the day.
	 */
	@Test
	public void escapeRefusesTheDealRatherThanDismissingIt()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		overlay.handleEscape();
		Assert.assertEquals("Escape must decline, not abandon", 1, consignment.declines);
		Assert.assertEquals(0, consignment.abandons);
		Assert.assertFalse(overlay.isModalActive());
	}

	/**
	 * Space is not an answer. It skips a beat in four other ceremonies, where it
	 * costs nothing; here the muscle memory would sign a deal.
	 */
	@Test
	public void spaceCannotAnswerABindingChoice()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		overlay.handleAdvance();
		Assert.assertEquals("space must answer nothing", 0, consignment.answered());
		Assert.assertEquals(0, consignment.abandons);
		Assert.assertTrue("the offer must still be waiting", overlay.isModalActive());
	}

	/**
	 * An abort ABANDONS — it never silently becomes a refusal.
	 *
	 * <p>Safe mode fires this on incoming damage, and the plugin fires it on a
	 * logout or a world hop. The player did not answer, so the day key must stay
	 * unspent and the roll must stay owed; turning it into a decline would charge
	 * them a day for a question they were never given the chance to hear.
	 */
	@Test
	public void anAbortAbandonsWithoutAnsweringForThePlayer()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		overlay.abortActiveCeremony();
		Assert.assertEquals(1, consignment.abandons);
		Assert.assertEquals("an abort is not an answer", 0, consignment.answered());
		Assert.assertFalse(overlay.isModalActive());
	}

	/** A type-scoped abort aimed at another ceremony must leave the offer alone. */
	@Test
	public void anAbortScopedToTheOfferScrollsLeavesTheOfferStanding()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		overlay.abortActiveCeremony(CeremonyBus.Type.TASK_OFFERS);
		Assert.assertEquals(0, consignment.abandons);
		Assert.assertTrue(overlay.isModalActive());
	}

	/**
	 * Teardown abandons an offer that was never even shown.
	 *
	 * <p>{@code reset()} runs in shutDown immediately before {@code bus.clear()},
	 * which drops whatever is still queued. An offer parked behind another
	 * ceremony at that moment is invisible to every other teardown path — the
	 * overlay never claimed it, so aborting the active ceremony cannot reach it —
	 * and it would leave ConsignmentService holding a live offer nobody can ever
	 * answer. Abandoning unconditionally is what closes that.
	 */
	@Test
	public void tearingDownAbandonsAnOfferStillParkedInTheQueue()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = overlay(bus, consignment);
		bus.addRenderer(overlay);

		bus.submit(CeremonyBus.Type.DEED_CHOICE, 0);
		Assert.assertTrue(overlay.present(offer()));
		Assert.assertFalse("the offer is parked, not shown",
			consignment.answered() > 0);

		overlay.reset();
		Assert.assertEquals("a parked offer must be abandoned on teardown",
			1, consignment.abandons);
		Assert.assertEquals(0, consignment.answered());
	}

	/**
	 * The answer travels to the service ON THE CLIENT THREAD, never straight off
	 * the AWT thread the click arrived on.
	 *
	 * <p>{@code handleClick} is called by RuneLite's MouseManager, which is AWT;
	 * the answer it raises reads live game state on the way down —
	 * {@code client.getTickCount()} stamps it, accepting opens a chest whose pool
	 * asks for the player's real skill levels, and every answer ends in a bus
	 * drain that reads the game state. Reading any of that from AWT races the game
	 * thread writing it.
	 *
	 * <p>The proof is the gap: with a ClientThread that queues rather than runs,
	 * the click must reach the service NOT AT ALL until the queue is drained. An
	 * overlay that called the service directly would answer on the first line and
	 * fail here, while still passing every other test in this file — which is
	 * exactly why the inline stub cannot be the only one.
	 */
	@Test
	public void theAnswerIsOnlyEverGivenFromTheClientThread()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		QueueingClientThread clientThread = new QueueingClientThread();
		RevealOverlay overlay = overlay(bus, consignment,
			new GameState[]{GameState.LOGGED_IN}, clientThread);
		bus.addRenderer(overlay);
		Assert.assertTrue(overlay.present(offer()));

		clickAnswer(overlay, 0);
		Assert.assertEquals("the answer was sent straight from the AWT thread",
			0, consignment.answered());
		// the screen is still released synchronously, and must be: releasing is
		// pure bookkeeping under this overlay's own lock, and it is what lets the
		// ceremonies the answer causes claim the screen the moment they arrive
		Assert.assertFalse("releasing the modal does not need the client thread",
			overlay.isModalActive());

		Assert.assertTrue("nothing was handed to the client thread at all",
			clientThread.run() > 0);
		Assert.assertEquals(1, consignment.accepts);
		Assert.assertEquals("the answer is stamped with the CURRENT tick",
			TICK, consignment.lastTick);
	}

	/**
	 * Teardown is the ONE service call this overlay makes without hopping, and
	 * that is deliberate rather than an oversight.
	 *
	 * <p>{@code abandon()} clears the service's own live offer and logs. It reads
	 * no game state, submits nothing to the bus and therefore drains nothing, so
	 * there is no Client anywhere beneath it. Hopping it would buy nothing and
	 * cost something real: {@code reset()} runs in shutDown, immediately before
	 * the bus is cleared, and a deferred abandon is a job queued against a plugin
	 * already being taken apart — the offer would stay live for good.
	 */
	@Test
	public void teardownAbandonsWithoutWaitingForTheClientThread()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		QueueingClientThread clientThread = new QueueingClientThread();
		RevealOverlay overlay = overlay(bus, consignment,
			new GameState[]{GameState.LOGGED_IN}, clientThread);
		bus.addRenderer(overlay);
		Assert.assertTrue(overlay.present(offer()));

		overlay.reset();
		Assert.assertEquals("teardown must not leave the offer live behind a hop",
			1, consignment.abandons);
		Assert.assertEquals("and it must not answer for the player either",
			0, consignment.answered());
	}

	/** A double click cannot answer twice: the modal is gone after the first. */
	@Test
	public void aSecondClickAfterTheAnswerChangesNothing()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		clickAnswer(overlay, 0);
		clickAnswer(overlay, 1);
		Assert.assertEquals(1, consignment.accepts);
		Assert.assertEquals(0, consignment.declines);
	}

	/** A click on neither plate answers nothing and leaves the question up. */
	@Test
	public void clickingBesideBothAnswersDecidesNothing()
	{
		CeremonyBus bus = new CeremonyBus();
		Spy consignment = new Spy();
		RevealOverlay overlay = showing(bus, consignment);

		overlay.handleClick(new Point(4, 4));
		Assert.assertEquals(0, consignment.answered());
		Assert.assertTrue(overlay.isModalActive());
	}

	/**
	 * The two plates never overlap and never leave the canvas.
	 *
	 * <p>A binding choice whose targets touched would take an answer the player
	 * did not give, and the pair is laid out by arithmetic rather than by a fixed
	 * table, so the sweep is the only thing that proves it at every size a
	 * resizable client can be dragged to.
	 */
	@Test
	public void theTwoAnswersNeverOverlapAtAnyCanvasSize()
	{
		Rectangle a = new Rectangle();
		Rectangle b = new Rectangle();
		int checked = 0;
		// from well below the client's own smallest canvas (765x503 fixed) up past
		// a maximised 4K window
		for (int cw = 320; cw <= 2600; cw += 7)
		{
			for (int ch = 300; ch <= 1600; ch += 11)
			{
				RevealOverlay.consignRect(0, cw, ch, a);
				RevealOverlay.consignRect(1, cw, ch, b);
				checked++;
				Assert.assertFalse(cw + "x" + ch + ": the two answers overlap",
					a.intersects(b));
				Assert.assertTrue(cw + "x" + ch + ": an answer ran off the left edge",
					a.x >= 0);
				Assert.assertTrue(cw + "x" + ch + ": an answer ran off the right edge",
					b.x + b.width <= cw);
				Assert.assertTrue(cw + "x" + ch + ": an answer ran off the bottom",
					a.y + a.height <= ch);
				Assert.assertEquals(cw + "x" + ch + ": the answers are different sizes",
					a.width, b.width);
			}
		}
		Assert.assertTrue("the sweep checked nothing", checked > 1000);
	}

	/**
	 * What is drawn is where the hit test looks.
	 *
	 * <p>The plates are painted from {@code consignRect} and clicked through
	 * {@code consignRect}, but nothing enforces that they stay the same call —
	 * a plate drawn at an offset would leave the player clicking a picture and
	 * getting nothing, or worse, clicking empty canvas and striking a deal. Both
	 * rectangles must therefore hold ink, and the gap between them must hold none:
	 * a full-canvas fill would satisfy the first half on its own.
	 */
	@Test
	public void bothAnswersArePaintedExactlyWhereTheyAreClicked()
	{
		CeremonyBus bus = new CeremonyBus();
		RevealOverlay overlay = showing(bus, new Spy());

		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, W, H);
			overlay.render(g);
		}
		finally
		{
			g.dispose();
		}

		// the dimmed backdrop, sampled where the ceremony draws nothing at all
		int backdrop = img.getRGB(4, 4);
		Assert.assertNotEquals("the modal did not dim the canvas", Color.WHITE.getRGB(), backdrop);

		Rectangle a = new Rectangle();
		Rectangle b = new Rectangle();
		RevealOverlay.consignRect(0, W, H, a);
		RevealOverlay.consignRect(1, W, H, b);
		Assert.assertNotEquals("nothing is drawn where TAKE THE DEAL is clicked",
			backdrop, img.getRGB(a.x + a.width / 2, a.y + a.height / 2));
		Assert.assertNotEquals("nothing is drawn where SPIN THE WHEEL is clicked",
			backdrop, img.getRGB(b.x + b.width / 2, b.y + b.height / 2));
		Assert.assertEquals("the gap between the two answers is not empty",
			backdrop, img.getRGB((a.x + a.width + b.x) / 2, a.y + a.height / 2));
	}
}
