package com.gachaman.overlay;

import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.KeyListener;
import java.awt.event.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.client.input.*;

/**
 * Consumes ALL input while a MODAL ceremony is showing and routes clicks,
 * hover and keys to {@link RevealOverlay} in canvas coordinates. Fanfares are
 * non-modal: nothing is consumed and gameplay input passes straight through.
 *
 * <p>The gate is deliberately narrow. Every consume site asks for a modal that
 * is being <em>painted</em> ({@code isModalInteractive()}, not
 * {@code isModalActive()}) on a client that is <em>in game</em>. Input the user
 * cannot see a reason for losing is unrecoverable — there is no visible modal to
 * dismiss and, at the login screen, not even a plugin panel in reach.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RevealInputListener implements MouseListener, MouseWheelListener, KeyListener {
	private final Client client;
	private final RevealOverlay overlay;

	/** Whether this listener may swallow the event it was just handed. */
	private boolean shouldConsume() {
		return client.getGameState() == GameState.LOGGED_IN && overlay.isModalInteractive();
	}

	private void syncHover(MouseEvent e) {
		if (e == null)
			return;
		if (!shouldConsume()) {
			overlay.setPointer(null);
			return;
		}
		overlay.setPointer(e.getPoint());
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		if (e == null || !shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		if (e == null)
			return e;
		syncHover(e);
		if (!shouldConsume())
			return e;
		if (e.getButton() == MouseEvent.BUTTON1)
			overlay.handleClick(e.getPoint());
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		if (e == null || !shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		if (e == null || !shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		if (e == null || !shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		if (e == null)
			return e;
		syncHover(e);
		if (!shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		if (e == null)
			return e;
		syncHover(e);
		if (!shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		if (e == null)
			return e;
		syncHover(e);
		if (!shouldConsume())
			return e;
		e.consume();
		return e;
	}

	@Override
	public void keyTyped(KeyEvent e) {
		if (e == null || !shouldConsume())
			return;
		e.consume();
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (e == null || !shouldConsume())
			return;
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
			overlay.handleEscape();
		else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
			overlay.handleAdvance();
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {
		if (e == null || !shouldConsume())
			return;
		e.consume();
	}
}
