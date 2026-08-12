package com.gachaman.service;

import com.gachaman.*;
import com.gachaman.overlay.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.*;

/**
 * Combat safety for modal ceremonies: if the local player takes damage or is
 * targeted while a full-canvas ceremony is consuming input, the ceremony is
 * aborted (a pending chest is still committed and summarized in chat). The
 * plugin registers this on the EventBus; only @Subscribe methods here.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SafeModeService {
	private final Client client;
	private final RevealOverlay revealOverlay;
	private final GachamanConfig config;

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) {
		if (event.getActor() != client.getLocalPlayer()
			|| event.getHitsplat() == null
			|| event.getHitsplat().getAmount() <= 0) {
			return;
		}
		abortIfModal("damage taken");
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event) {
		if (event.getTarget() != client.getLocalPlayer()
			|| client.getLocalPlayer() == null
			|| !(event.getSource() instanceof NPC)) {
			return;
		}
		abortIfModal("targeted by an NPC");
	}

	private void abortIfModal(String reason) {
		if (!config.safeModeAbort() || !revealOverlay.isModalActive()) {
			return;
		}
		log.debug("Gachaman safe-mode: aborting ceremony ({})", reason);
		revealOverlay.abortActiveCeremony();
	}
}
