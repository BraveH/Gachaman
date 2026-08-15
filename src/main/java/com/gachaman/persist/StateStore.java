package com.gachaman.persist;

import com.gachaman.*;
import com.gachaman.model.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
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
	/**
	 * Was 10s. A debounce is still wanted — a kill, a card and a GC award can
	 * all land in the same instant and there is no sense writing three times —
	 * but ten seconds is ten seconds of progress a crash can take with it, and
	 * a lost contract kill count is exactly the kind of loss a player notices
	 * and resents. At one second the coalescing still works and the worst case
	 * is a single event. The write is off-thread and the state is ~6 KB gzipped,
	 * so the cost of the shorter window is not worth measuring.
	 */
	private static final long DISK_FLUSH_DELAY_MS = 1_000;

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
		if (profile == null)
			return;
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
		if (pending == null)
			return;
		writeDisk(pending[0], pending[1]);
	}

	/** @return loaded state or null when nothing valid exists (fresh profile). */
	/**
	 * Loads the NEWEST surviving copy, not simply the config one.
	 *
	 * <p>Config used to win outright and disk was consulted only when config was
	 * missing or corrupt. That loses data whenever the client dies without a
	 * clean shutdown: the plugin's own disk write is debounced and lands, but
	 * RuneLite never flushes its in-memory config, so the next launch reads a
	 * config blob OLDER than state.dat, decodes it fine, and quietly rolls the
	 * player back — a reassigned loadout or a contract's kill count simply gone.
	 * Comparing the stamps makes the disk copy the safety net it was meant to be.
	 */
	public GachaState load() {
		String blob = configManager.getRSProfileConfiguration(GachamanConfig.GROUP, KEY_STATE);
		String best = blob;
		long bestAt = codec.savedAt(blob);
		for (String name : new String[]{"state.dat", "state.dat.bak"}) {
			File f = diskFile(configManager.getRSProfileKey(), name);
			if (f == null || !f.exists())
				continue;
			try {
				String disk = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
				long diskAt = codec.savedAt(disk);
				// strictly newer only: on a tie config wins, which keeps the old
				// behaviour for saves written before the stamp existed
				if (diskAt > bestAt) {
					best = disk;
					bestAt = diskAt;
					log.info("Gachaman state: {} is newer than config, preferring it", name);
				}
			}
			catch (IOException e) {
				log.warn("Failed reading {}", f, e);
			}
		}
		GachaState state = codec.decode(best);
		if (state != null)
			return state;
		// the newest copy did not survive verification — fall back through the
		// rest, newest first, rather than starting the player from nothing
		for (String candidate : candidatesOldestLast(blob)) {
			state = codec.decode(candidate);
			if (state != null) {
				log.info("Gachaman state recovered from a fallback copy");
				return state;
			}
		}
		return null;
	}

	/** Every surviving blob except the one already tried, newest first. */
	private List<String> candidatesOldestLast(String configBlob) {
		List<String> out = new ArrayList<>();
		out.add(configBlob);
		for (String name : new String[]{"state.dat", "state.dat.bak"}) {
			File f = diskFile(configManager.getRSProfileKey(), name);
			if (f == null || !f.exists())
				continue;
			try {
				out.add(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			}
			catch (IOException e) {
				log.warn("Failed reading {}", f, e);
			}
		}
		out.sort((a, b) -> Long.compare(codec.savedAt(b), codec.savedAt(a)));
		return out;
	}

	private void writeDisk(String profileKey, String blob) {
		File f = diskFile(profileKey, "state.dat");
		if (f == null)
			return;
		try {
			File dir = f.getParentFile();
			if (!dir.exists() && !dir.mkdirs())
				return;
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
		if (profileKey == null)
			return null;
		return new File(new File(BASE_DIR, profileKey.replaceAll("[^A-Za-z0-9_.-]", "_")), name);
	}
}
