package com.gachaman.ui.loadout;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.MouseListener;
import lombok.RequiredArgsConstructor;

/**
 * Non-modal, selective mouse listener (the plugin registers it on
 * MouseManager). It ONLY ever consumes left clicks that land inside the
 * loadout button or the open loadout board; every other event passes through
 * untouched. When a press is consumed, the matching release/click events are
 * swallowed too so the game never sees half a click.
 *
 * <p>Both hit tests read overlay rects that were measured on the last painted
 * frame and are not cleared when painting stops, so the whole listener stands
 * down outside the game: a stale rect left over the login screen would eat
 * clicks on "Click here to play" with nothing on screen to explain it.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LoadoutInputListener implements MouseListener {
	private final Client client;
	private final LoadoutButtonOverlay buttonOverlay;
	private final LoadoutOverlay loadoutOverlay;

	private boolean swallowRelease;
	private boolean swallowClick;

	@Override
	public MouseEvent mousePressed(MouseEvent event) {
		if (event == null || event.isConsumed()
			|| event.getButton() != MouseEvent.BUTTON1
			|| event.isAltDown() // Alt = RuneLite overlay-managing mode (drag)
			|| client.getGameState() != GameState.LOGGED_IN) {
			return event;
		}
		Point point = event.getPoint();
		if (inBounds(buttonOverlay.getBounds(), point)) {
			loadoutOverlay.toggle();
			swallowRelease = true;
			swallowClick = true;
			event.consume();
			return event;
		}
		if (loadoutOverlay.containsCanvasPoint(point)) {
			loadoutOverlay.handleClick(point);
			swallowRelease = true;
			swallowClick = true;
			event.consume();
			return event;
		}
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event) {
		if (event != null && swallowRelease && event.getButton() == MouseEvent.BUTTON1) {
			swallowRelease = false;
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event) {
		if (event != null && swallowClick && event.getButton() == MouseEvent.BUTTON1) {
			swallowClick = false;
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event) {
		if (event != null) {
			// hover feedback only; never consumed
			loadoutOverlay.setHoverCanvasPoint(event.getPoint());
		}
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event) {
		return event;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event) {
		return event;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event) {
		if (event != null) {
			loadoutOverlay.setHoverCanvasPoint(null);
		}
		return event;
	}

	private static boolean inBounds(Rectangle bounds, Point point) {
		return bounds != null && bounds.width > 0 && bounds.height > 0 && bounds.contains(point);
	}
}
