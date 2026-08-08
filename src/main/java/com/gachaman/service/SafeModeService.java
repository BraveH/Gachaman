package com.gachaman.service;

import com.gachaman.overlay.RevealOverlay;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat safety for modal ceremonies: if the local player takes damage or is
 * targeted while a full-canvas ceremony is consuming input, the ceremony is
 * aborted (a pending chest is still committed and summarized in chat). The
 * plugin registers this on the EventBus; only @Subscribe methods here.
 */
@Slf4j
@Singleton
public class SafeModeService
{
	private final Client client;
	private final RevealOverlay revealOverlay;
	private final com.gachaman.GachamanConfig config;

	@Inject
	public SafeModeService(Client client, RevealOverlay revealOverlay,
		com.gachaman.GachamanConfig config)
	{
		this.client = client;
		this.revealOverlay = revealOverlay;
		this.config = config;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() != client.getLocalPlayer()
			|| event.getHitsplat() == null
			|| event.getHitsplat().getAmount() <= 0)
		{
			return;
		}
		abortIfModal("damage taken");
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getTarget() != client.getLocalPlayer()
			|| client.getLocalPlayer() == null
			|| !(event.getSource() instanceof NPC))
		{
			return;
		}
		abortIfModal("targeted by an NPC");
	}

	private void abortIfModal(String reason)
	{
		if (!config.safeModeAbort() || !revealOverlay.isModalActive())
		{
			return;
		}
		log.debug("Gachaman safe-mode: aborting ceremony ({})", reason);
		revealOverlay.abortActiveCeremony();
	}
}
