package com.gachaman.persist;

import com.gachaman.model.GachaState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes GachaState to a versioned, hash-stamped, gzip+base64 envelope
 * suitable for both RSProfile config storage and disk files.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StateCodec {
	private final Gson gson;

	public String encode(GachaState state) {
		String payload = gson.toJson(state);
		JsonObject envelope = new JsonObject();
		envelope.addProperty("version", GachaState.SCHEMA_VERSION);
		envelope.addProperty("sha256", sha256(payload));
		envelope.addProperty("payload", payload);
		return base64Gzip(envelope.toString());
	}

	/** @return decoded state, or null if the blob is missing/corrupt/tampered. */
	public GachaState decode(String blob) {
		if (blob == null || blob.isEmpty()) {
			return null;
		}
		try {
			String json = gunzipBase64(blob);
			JsonObject envelope = gson.fromJson(json, JsonObject.class);
			int version = envelope.get("version").getAsInt();
			if (version > GachaState.SCHEMA_VERSION) {
				log.warn("Gachaman state schema {} is newer than supported {}", version, GachaState.SCHEMA_VERSION);
				return null;
			}
			String payload = envelope.get("payload").getAsString();
			String expected = envelope.get("sha256").getAsString();
			if (!expected.equals(sha256(payload))) {
				log.warn("Gachaman state hash mismatch — refusing to load");
				return null;
			}
			return gson.fromJson(payload, GachaState.class);
		}
		catch (Exception e) {
			log.warn("Failed to decode Gachaman state", e);
			return null;
		}
	}

	static String sha256(String s) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String base64Gzip(String s) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bos), StandardCharsets.UTF_8)) {
				w.write(s);
			}
			return Base64.getEncoder().encodeToString(bos.toByteArray());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String gunzipBase64(String s) throws IOException {
		byte[] compressed = Base64.getDecoder().decode(s);
		try (InputStreamReader r = new InputStreamReader(
			new GZIPInputStream(new ByteArrayInputStream(compressed)), StandardCharsets.UTF_8)) {
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[8192];
			int n;
			while ((n = r.read(buf)) != -1) {
				sb.append(buf, 0, n);
			}
			return sb.toString();
		}
	}
}
