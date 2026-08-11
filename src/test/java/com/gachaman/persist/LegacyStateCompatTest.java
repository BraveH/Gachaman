package com.gachaman.persist;

import com.gachaman.model.GachaState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.Assert;
import org.junit.Test;

/**
 * A profile saved by an older build must still load after a feature is removed.
 *
 * <p>Removing Prestige and the Charter Office deleted three PERSISTED fields —
 * {@code prestigeRank}, {@code charterHold}, {@code charterDayKey}. Every
 * existing player's stored blob still contains them, and one of them
 * ({@code charterHold}) is an object whose class no longer exists at all.
 *
 * <p>Nothing else in the suite covers this: every other codec test round-trips
 * a state the CURRENT build wrote, which by construction can never carry a
 * retired field. If this assumption is wrong the failure is silent and total —
 * {@code decode} returns null, the profile reads as a fresh account, and the
 * player's cards and GC are gone on first launch.
 */
public class LegacyStateCompatTest
{
	private final StateCodec codec = new StateCodec(new Gson());

	/** Rebuilds the envelope exactly as an older StateCodec would have written it. */
	private static String legacyBlob(String payload) throws Exception
	{
		JsonObject envelope = new JsonObject();
		envelope.addProperty("version", GachaState.SCHEMA_VERSION);
		envelope.addProperty("sha256", StateCodec.sha256(payload));
		envelope.addProperty("payload", payload);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8))
		{
			w.write(envelope.toString());
		}
		return Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	@Test
	public void aProfileCarryingRetiredFieldsStillLoads() throws Exception
	{
		// a state the current build wrote, with the three retired fields spliced
		// back in exactly where an older build would have put them
		String current = new Gson().toJson(
			GachaState.fresh(42).withGc(98765).withTaint(2).withAllowedStyle("RANGED"));
		Assert.assertTrue("expected a JSON object", current.startsWith("{"));
		String legacy = "{"
			+ "\"prestigeRank\":3,"
			+ "\"charterDayKey\":\"2026-D220\","
			+ "\"charterHold\":{\"monsterName\":\"Gargoyle\",\"priceGc\":1500,"
			+ "\"expiresAtMs\":1750000000000},"
			+ current.substring(1);

		GachaState decoded = codec.decode(legacyBlob(legacy));

		Assert.assertNotNull("a pre-removal profile failed to decode — every existing"
			+ " player would lose their save", decoded);
		Assert.assertEquals(98765, decoded.getGc());
		Assert.assertEquals(2, decoded.getTaint());
		Assert.assertEquals("RANGED", decoded.getAllowedStyle());
	}

	@Test
	public void theRetiredFieldsAreGoneFromWhatWeWriteNow() throws Exception
	{
		// the other half: once loaded, the next save must not resurrect them
		String written = new Gson().toJson(GachaState.fresh(1));
		Assert.assertFalse(written.contains("prestigeRank"));
		Assert.assertFalse(written.contains("charterHold"));
		Assert.assertFalse(written.contains("charterDayKey"));
	}
}
