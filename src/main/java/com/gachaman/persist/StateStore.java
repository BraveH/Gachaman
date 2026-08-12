package com.gachaman.persist;

import com.gachaman.*;
import com.gachaman.model.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.*;
import net.runelite.client.config.*;

/**
 * Dual-layer persistence: RSProfile config key (in-memory, batched flush by
 * RuneLite — synchronous and cheap) plus a disk file with one .bak rotation.
 * Disk writes are debounced onto the executor so file I/O never runs on the
 * client thread; checkpoints flush immediately.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StateStore {
	private static final String KEY_STATE = "state";
	private static final File BASE_DIR = new File(RuneLite.RUNELITE_DIR, "gachaman");
	private static final long DISK_FLUSH_DELAY_MS = 10_000;

	private final ConfigManager configManager;
	private final StateCodec codec;
	private final ScheduledExecutorService executor;

	/** blob + profile key captured at save time (profile may switch before the flush runs). */
	private final AtomicReference<String[]> pendingDisk = new AtomicReference<>();
	private volatile boolean flushScheduled;

	/** Regular save: config now, disk debounced. */
	public void save(GachaState state) {
		save(state, false);
	}

	/** Checkpoint save: config now, disk now (still off-thread, but immediate). */
	public void save(GachaState state, boolean flushDiskNow) {
		String blob = codec.encode(state);
		configManager.setRSProfileConfiguration(GachamanConfig.GROUP, KEY_STATE, blob);
		String profile = configManager.getRSProfileKey();
		if (profile == null) {
			return;
		}
		pendingDisk.set(new String[]{profile, blob});
		if (flushDiskNow) {
			executor.execute(this::flushDisk);
		}
		else if (!flushScheduled) {
			flushScheduled = true;
			executor.schedule(this::flushDisk, DISK_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
		}
	}

	private void flushDisk() {
		flushScheduled = false;
		String[] pending = pendingDisk.getAndSet(null);
		if (pending == null) {
			return;
		}
		writeDisk(pending[0], pending[1]);
	}

	/** @return loaded state or null when nothing valid exists (fresh profile). */
	public GachaState load() {
		String blob = configManager.getRSProfileConfiguration(GachamanConfig.GROUP, KEY_STATE);
		GachaState state = codec.decode(blob);
		if (state != null) {
			return state;
		}
		// Fall back to disk (config missing or corrupt)
		for (String name : new String[]{"state.dat", "state.dat.bak"}) {
			File f = diskFile(configManager.getRSProfileKey(), name);
			if (f != null && f.exists()) {
				try {
					state = codec.decode(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
					if (state != null) {
						log.info("Gachaman state recovered from disk: {}", f);
						return state;
					}
				}
				catch (IOException e) {
					log.warn("Failed reading {}", f, e);
				}
			}
		}
		return null;
	}

	private void writeDisk(String profileKey, String blob) {
		File f = diskFile(profileKey, "state.dat");
		if (f == null) {
			return;
		}
		try {
			File dir = f.getParentFile();
			if (!dir.exists() && !dir.mkdirs()) {
				return;
			}
			Path path = f.toPath();
			if (f.exists()) {
				Files.copy(path, diskFile(profileKey, "state.dat.bak").toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			}
			Path tmp = path.resolveSibling("state.dat.tmp");
			Files.write(tmp, blob.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e) {
			log.warn("Failed to write Gachaman disk state", e);
		}
	}

	private File diskFile(String profileKey, String name) {
		if (profileKey == null) {
			return null;
		}
		return new File(new File(BASE_DIR, profileKey.replaceAll("[^A-Za-z0-9_.-]", "_")), name);
	}
}
