package com.gachaman.service;

import com.gachaman.*;
import java.io.*;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.externalplugins.*;
import net.runelite.client.util.*;

/**
 * Update plumbing, which is two different jobs because the client already does
 * the one people expect.
 *
 * <p>RuneLite force-updates every Plugin Hub plugin at client start:
 * ExternalPluginManager.loadExternalPlugins downloads the hub manifest and
 * re-fetches any installed jar whose hash no longer matches. So the code
 * running here is ALWAYS the newest build for this client — there is nothing
 * for a plugin to force, and calling ExternalPluginManager.update() from
 * inside the plugin would ask the client to unload Gachaman while Gachaman is
 * the thing running. What is left is the two gaps that auto-update leaves:
 *
 * <ol>
 * <li>the player is never told they were updated, or what changed — announced
 * on login, keyed on the version the hub actually shipped;
 * <li>a client left open across a hub release keeps running the old jar until
 * it restarts — detected by comparing our jar hash against the manifest's.
 * </ol>
 *
 * <p>Both read their identity through the hub classloader, which only exists
 * for an installed hub build. Under an IDE both come back null and the whole
 * service goes quiet, which is what you want while developing.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class UpdateService {
	/** Config key holding the last version we announced. */
	private static final String SEEN_VERSION_KEY = "lastSeenVersion";

	/**
	 * What this release added, in RuneLite chat markup. Rewritten by hand each
	 * release; the version number in front of it is not, so the two can never
	 * drift out of step the way a hardcoded version string does.
	 *
	 * <p>Unread on 1.0.0 by design — a first install announces nothing, so this
	 * line is first seen by players updating from 1.0.0 to whatever follows.
	 * Rewrite it in the same commit that bumps the version.
	 */
	private static final String CHANGELOG = "See the plugin's GitHub for what changed.";

	private static final int CHECK_INITIAL_MINUTES = 10;
	private static final int CHECK_PERIOD_MINUTES = 180;

	private final Client client;
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final ConfigManager configManager;
	/**
	 * Deliberately a Provider: ExternalPluginClient is client-internal, with a
	 * private constructor and two @Named bindings. Resolving it eagerly would
	 * make a binding change upstream stop Gachaman from loading at all, over a
	 * courtesy notice. Resolved inside the check instead, where failing just
	 * means no notice.
	 */
	private final Provider<ExternalPluginClient> externalPluginClient;
	private final ScheduledExecutorService executor;

	/** Hub hash we have already nagged about, so a poll cannot repeat itself. */
	private String announcedHash;
	private ScheduledFuture<?> checkTask;

	public void start() {
		if (checkTask == null) {
			// same cadence the client uses for its own hub traffic
			checkTask = executor.scheduleWithFixedDelay(this::checkHub,
				CHECK_INITIAL_MINUTES, CHECK_PERIOD_MINUTES, TimeUnit.MINUTES);
		}
	}

	public void stop() {
		if (checkTask != null) {
			checkTask.cancel(false);
			checkTask = null;
		}
	}

	/**
	 * Announce a version change once. Idempotent through the stored key, so
	 * firing it on every login costs nothing after the first.
	 */
	public void onLoggedIn() {
		PluginHubManifest.DisplayData data =
			ExternalPluginManager.getDisplayData(GachamanPlugin.class);
		if (data == null || data.getVersion() == null) {
			return; // not a hub build
		}
		String version = data.getVersion();
		String seen = configManager.getConfiguration(GachamanConfig.GROUP, SEEN_VERSION_KEY);
		if (version.equals(seen))
			return;
		configManager.setConfiguration(GachamanConfig.GROUP, SEEN_VERSION_KEY, version);
		if (seen == null) {
			return; // a first install is not an update, and has no changelog to read
		}
		chat("<col=ff9040>Gachaman</col> updated to <col=ff9040>v" + version + "</col>. "
			+ CHANGELOG);
	}

	/**
	 * Compare the jar we are running against what the hub is serving. Runs off
	 * the client thread, and stays silent on any failure — a CDN blip is not
	 * worth a chat line, and the next poll retries anyway.
	 */
	private void checkHub() {
		PluginHubManifest.JarData mine = ExternalPluginManager.getJarData(GachamanPlugin.class);
		String internalName = ExternalPluginManager.getInternalName(GachamanPlugin.class);
		if (mine == null || internalName == null) {
			return; // not a hub build
		}
		try {
			for (PluginHubManifest.JarData jar : externalPluginClient.get().downloadManifestLite().getJars()) {
				if (!internalName.equals(jar.getInternalName()))
					continue;
				String hubHash = jar.getJarHash();
				if (hubHash == null || hubHash.equals(mine.getJarHash())) {
					return; // already running it
				}
				// hold the nag until they can actually read it; leaving
				// announcedHash unset means a logged-out player gets it next poll
				if (hubHash.equals(announcedHash) || client.getGameState() != GameState.LOGGED_IN)
					return;
				announcedHash = hubHash;
				chat("A new version of <col=ff9040>Gachaman</col> is on the Plugin Hub."
					+ " Restart your client to update.");
				return;
			}
		}
		// RuntimeException too: this runs on a shared executor, so an unchecked
		// throw here would kill the repeating task and take the notice with it
		catch (IOException | VerificationException | RuntimeException e) {
			log.debug("plugin hub version check failed", e);
		}
	}

	private void chat(String message) {
		clientThread.invokeLater(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build()));
	}
}
