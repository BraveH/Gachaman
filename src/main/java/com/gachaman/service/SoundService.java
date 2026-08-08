package com.gachaman.service;

import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Procedural ceremony audio: every effect is synthesized once on first use
 * (16-bit mono 22050 Hz PCM wrapped in a RIFF/WAV header), cached as bytes and
 * replayed through {@link AudioPlayer}. No bundled assets, never throws.
 */
@Slf4j
@Singleton
public class SoundService
{
	public enum Sfx
	{
		TICK,
		CHIME,
		SHATTER,
		WHOOSH,
		COIN,
		DEEP_HUM,
		FANFARE
	}

	private static final int SAMPLE_RATE = 22050;
	private static final float MIN_GAIN_DB = -80f;

	private final AudioPlayer audioPlayer;
	private final Map<Sfx, byte[]> cache = new EnumMap<>(Sfx.class);
	private final Map<Sfx, Boolean> failed = new EnumMap<>(Sfx.class);

	private volatile boolean enabled = true;
	/** 0..100; applied as a dB attenuation on every play. */
	private volatile int volumePercent = 100;

	/** Global trim: everything plays at half amplitude (-6 dB) of its synth level. */
	private static final float MASTER_TRIM_DB = -6.02f;
	/** The scroll-unroll whoosh was singled out as far too loud — extra trim. */
	private static final float WHOOSH_TRIM_DB = -6f;

	/** Groans are thick and close; they need less headroom than a chime. */
	private static final float STRAIN_TRIM_DB = -3f;
	private static final int STRAIN_STEPS = 4;
	private static final double STRAIN_BASE_HZ = 128;
	/** A whole tone per step: audibly tightening without leaving the low register. */
	private static final double STRAIN_STEP_RATIO = 1.122462;

	private final byte[][] strainCache = new byte[STRAIN_STEPS][];
	/**
	 * Deliberately NOT an entry in {@link #failed}: a strain that the mixer
	 * refuses must not be able to mute the tick that shares this service.
	 */
	private volatile boolean strainFailed;

	@Inject
	public SoundService(AudioPlayer audioPlayer)
	{
		this.audioPlayer = audioPlayer;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	public void setVolume(int percent)
	{
		this.volumePercent = Math.max(0, Math.min(100, percent));
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	// --- convenience one-shots ---

	public void playTick()
	{
		play(Sfx.TICK, 0f);
	}

	public void playChime()
	{
		play(Sfx.CHIME, 0f);
	}

	public void playShatter()
	{
		play(Sfx.SHATTER, 0f);
	}

	public void playWhoosh()
	{
		play(Sfx.WHOOSH, WHOOSH_TRIM_DB);
	}

	public void playCoin()
	{
		play(Sfx.COIN, 0f);
	}

	public void playDeepHum()
	{
		play(Sfx.DEEP_HUM, 0f);
	}

	public void playFanfare()
	{
		play(Sfx.FANFARE, 0f);
	}

	/**
	 * Metal under load, one whole tone higher per step, for the chest strain.
	 * Steps out of range are clamped rather than rejected so a mistuned
	 * schedule can only repeat a pitch, never throw inside a render pass.
	 * <p>
	 * This bypasses {@link Sfx} on purpose: {@link #synthesize} ends
	 * {@code case FANFARE: default:}, so a new enum constant whose case someone
	 * forgets would not fail to compile — it would silently blare a fanfare
	 * mid-ceremony. A parallel cache costs a few lines and removes that.
	 */
	public void playStrain(int step)
	{
		if (!enabled || volumePercent <= 0 || strainFailed)
		{
			return;
		}
		int k = Math.max(0, Math.min(STRAIN_STEPS - 1, step));
		try
		{
			byte[] wav;
			synchronized (strainCache)
			{
				if (strainCache[k] == null)
				{
					strainCache[k] = toWav(synthStrain(k));
				}
				wav = strainCache[k];
			}
			float gainDb = linearToDb(volumePercent / 100f) + STRAIN_TRIM_DB + MASTER_TRIM_DB;
			audioPlayer.play(new ByteArrayInputStream(wav), Math.max(MIN_GAIN_DB, gainDb));
		}
		catch (Exception e)
		{
			strainFailed = true;
			log.warn("Gachaman strain sound failed to play; muting it", e);
		}
	}

	/**
	 * Play a synthesized effect with an extra gain offset in dB. Failures are
	 * logged once per effect and never propagate.
	 */
	public void play(Sfx sfx, float gainOffsetDb)
	{
		if (!enabled || volumePercent <= 0 || Boolean.TRUE.equals(failed.get(sfx)))
		{
			return;
		}
		try
		{
			byte[] wav;
			synchronized (cache)
			{
				wav = cache.computeIfAbsent(sfx, SoundService::synthesize);
			}
			float gainDb = linearToDb(volumePercent / 100f) + gainOffsetDb + MASTER_TRIM_DB;
			audioPlayer.play(new ByteArrayInputStream(wav), Math.max(MIN_GAIN_DB, gainDb));
		}
		catch (Exception e)
		{
			if (!Boolean.TRUE.equals(failed.get(sfx)))
			{
				failed.put(sfx, Boolean.TRUE);
				log.warn("Gachaman sound {} failed to play; muting it", sfx, e);
			}
		}
	}

	private static float linearToDb(float linear01)
	{
		float v = Math.max(0f, Math.min(1f, linear01));
		if (v < 0.0005f)
		{
			return MIN_GAIN_DB;
		}
		return (float) (20.0 * Math.log10(v));
	}

	// --- synthesis ---

	private static byte[] synthesize(Sfx sfx)
	{
		double[] samples;
		switch (sfx)
		{
			case TICK:
				samples = synthTick();
				break;
			case CHIME:
				samples = synthChime();
				break;
			case SHATTER:
				samples = synthShatter();
				break;
			case WHOOSH:
				samples = synthWhoosh();
				break;
			case COIN:
				samples = synthCoin();
				break;
			case DEEP_HUM:
				samples = synthDeepHum();
				break;
			case FANFARE:
			default:
				samples = synthFanfare();
				break;
		}
		return toWav(samples);
	}

	private static double[] newBuffer(double seconds)
	{
		return new double[(int) (SAMPLE_RATE * seconds)];
	}

	/** Add a decaying tone; amplitudes accumulate so tones can overlap. */
	private static void addTone(double[] buf, double startSec, double lenSec,
		double freq, double amp, double attackSec, double decayRate)
	{
		int start = (int) (startSec * SAMPLE_RATE);
		int len = (int) (lenSec * SAMPLE_RATE);
		for (int i = 0; i < len; i++)
		{
			int idx = start + i;
			if (idx < 0 || idx >= buf.length)
			{
				continue;
			}
			double t = i / (double) SAMPLE_RATE;
			double env = Math.exp(-t * decayRate);
			if (attackSec > 0 && t < attackSec)
			{
				env *= t / attackSec;
			}
			// fade the tail so a truncated decay never clicks
			int remain = len - i;
			int fade = SAMPLE_RATE / 100;
			if (remain < fade)
			{
				env *= remain / (double) fade;
			}
			buf[idx] += Math.sin(2 * Math.PI * freq * t) * amp * env;
		}
	}

	private static double[] synthTick()
	{
		double[] buf = newBuffer(0.045);
		addTone(buf, 0, 0.045, 1900, 0.55, 0, 140);
		addTone(buf, 0, 0.030, 3800, 0.20, 0, 200);
		return buf;
	}

	private static double[] synthChime()
	{
		double[] buf = newBuffer(0.55);
		addTone(buf, 0.00, 0.35, 660, 0.42, 0.004, 9);
		addTone(buf, 0.00, 0.35, 1320, 0.12, 0.004, 12);
		addTone(buf, 0.14, 0.41, 880, 0.46, 0.004, 7);
		addTone(buf, 0.14, 0.41, 1760, 0.13, 0.004, 10);
		return buf;
	}

	private static double[] synthShatter()
	{
		double[] buf = newBuffer(0.38);
		Random noise = new Random(0x5AD5AD);
		double prev = 0;
		for (int i = 0; i < buf.length; i++)
		{
			double t = i / (double) SAMPLE_RATE;
			double white = noise.nextDouble() * 2 - 1;
			// first-difference = crude high-pass for a glassy edge
			double hp = white - prev;
			prev = white;
			buf[i] = hp * 0.65 * Math.exp(-t * 13);
		}
		addTone(buf, 0.0, 0.2, 2600, 0.12, 0, 30);
		addTone(buf, 0.0, 0.2, 3400, 0.10, 0, 34);
		return buf;
	}

	private static double[] synthWhoosh()
	{
		double[] buf = newBuffer(0.45);
		Random noise = new Random(0x7005);
		double smooth = 0;
		for (int i = 0; i < buf.length; i++)
		{
			double t = i / (double) SAMPLE_RATE;
			double u = t / 0.45;
			double white = noise.nextDouble() * 2 - 1;
			// low-pass whose cutoff opens then closes over the sweep
			double alpha = 0.04 + 0.30 * Math.sin(Math.PI * u);
			smooth += (white - smooth) * alpha;
			double env = Math.sin(Math.PI * u);
			buf[i] = smooth * env * env * 1.5;
		}
		return buf;
	}

	private static double[] synthCoin()
	{
		double[] buf = newBuffer(0.16);
		addTone(buf, 0.00, 0.07, 1320, 0.45, 0.002, 40);
		addTone(buf, 0.05, 0.11, 1760, 0.45, 0.002, 28);
		return buf;
	}

	private static double[] synthDeepHum()
	{
		double[] buf = newBuffer(0.95);
		addTone(buf, 0, 0.95, 70, 0.50, 0.20, 2.6);
		addTone(buf, 0, 0.95, 105, 0.28, 0.20, 3.0);
		addTone(buf, 0, 0.95, 140, 0.12, 0.25, 3.4);
		return buf;
	}

	/**
	 * A groan: low fundamental plus two partials, over a filtered-noise bed.
	 * The slow 50 ms attack and the noise are what make it read as metal taking
	 * strain rather than as a synth pad — a hard attack here just sounds like a
	 * second tick.
	 */
	static double[] synthStrain(int step)
	{
		double f = STRAIN_BASE_HZ * Math.pow(STRAIN_STEP_RATIO, step);
		double[] buf = newBuffer(0.42);
		addTone(buf, 0.00, 0.42, f, 0.34, 0.05, 5.0);
		addTone(buf, 0.00, 0.42, f * 2, 0.16, 0.06, 6.5);
		addTone(buf, 0.00, 0.34, f * 3, 0.09, 0.07, 8.0);
		Random noise = new Random(0x10CC + step);
		double smooth = 0;
		for (int i = 0; i < buf.length; i++)
		{
			double t = i / (double) SAMPLE_RATE;
			smooth += (noise.nextDouble() * 2 - 1 - smooth) * 0.05;
			buf[i] += smooth * 0.22 * Math.exp(-t * 4.0) * Math.min(1.0, t / 0.05);
		}
		return buf;
	}

	private static double[] synthFanfare()
	{
		double[] buf = newBuffer(1.0);
		double[] notes = {523.25, 659.25, 783.99, 1046.5};
		for (int n = 0; n < notes.length; n++)
		{
			double start = n * 0.13;
			double len = n == notes.length - 1 ? 0.55 : 0.22;
			double decay = n == notes.length - 1 ? 4 : 9;
			addTone(buf, start, len, notes[n], 0.34, 0.005, decay);
			addTone(buf, start, len, notes[n] * 2, 0.10, 0.005, decay + 3);
		}
		return buf;
	}

	/** 16-bit mono PCM RIFF/WAV; samples are clamped to [-1, 1]. */
	private static byte[] toWav(double[] samples)
	{
		int dataLen = samples.length * 2;
		byte[] out = new byte[44 + dataLen];
		writeAscii(out, 0, "RIFF");
		writeIntLe(out, 4, 36 + dataLen);
		writeAscii(out, 8, "WAVE");
		writeAscii(out, 12, "fmt ");
		writeIntLe(out, 16, 16);          // fmt chunk size
		writeShortLe(out, 20, 1);         // PCM
		writeShortLe(out, 22, 1);         // mono
		writeIntLe(out, 24, SAMPLE_RATE);
		writeIntLe(out, 28, SAMPLE_RATE * 2); // byte rate
		writeShortLe(out, 32, 2);         // block align
		writeShortLe(out, 34, 16);        // bits per sample
		writeAscii(out, 36, "data");
		writeIntLe(out, 40, dataLen);
		int pos = 44;
		for (double sample : samples)
		{
			double clamped = Math.max(-1.0, Math.min(1.0, sample));
			int v = (int) Math.round(clamped * 32767);
			out[pos++] = (byte) (v & 0xFF);
			out[pos++] = (byte) ((v >> 8) & 0xFF);
		}
		return out;
	}

	private static void writeAscii(byte[] out, int off, String text)
	{
		for (int i = 0; i < text.length(); i++)
		{
			out[off + i] = (byte) text.charAt(i);
		}
	}

	private static void writeIntLe(byte[] out, int off, int value)
	{
		out[off] = (byte) (value & 0xFF);
		out[off + 1] = (byte) ((value >> 8) & 0xFF);
		out[off + 2] = (byte) ((value >> 16) & 0xFF);
		out[off + 3] = (byte) ((value >> 24) & 0xFF);
	}

	private static void writeShortLe(byte[] out, int off, int value)
	{
		out[off] = (byte) (value & 0xFF);
		out[off + 1] = (byte) ((value >> 8) & 0xFF);
	}
}
