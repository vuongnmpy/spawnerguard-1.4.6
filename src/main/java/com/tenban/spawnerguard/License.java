package com.tenban.spawnerguard;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Kich hoat bang ma, kiem tra 1 lan voi may chu (Google Apps Script):
 * moi ma chi gan duoc voi 1 tai khoan Minecraft. Kich hoat xong luu vinh vien tren may, khong can nhap lai.
 */
final class License {
	private static final String SECRET = "75b9450174cfa8772a5fafd54ccd3bc3";
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spawnerguard.license");

	static boolean licensed = false;

	private License() {}

	static String sha256(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : d) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String uuid() {
		UUID u = Minecraft.getInstance().getUser().getProfileId();
		return u == null ? "none" : u.toString().toLowerCase();
	}

	private static String proofFor(String uuid) {
		return sha256(SECRET + ":" + uuid);
	}

	static void refresh() {
		licensed = check();
	}

	private static boolean check() {
		try {
			if (!Files.exists(FILE)) return false;
			return Files.readString(FILE).trim().equals(proofFor(uuid()));
		} catch (Exception e) {
			return false;
		}
	}

	private static String serverUrl() {
		try (InputStream in = License.class.getResourceAsStream("/spawnerguard-server.txt")) {
			if (in == null) return "";
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (Exception e) {
			return "";
		}
	}

	/** Chay o luong rieng de khong lam dung game. Ket qua: OK, USED, INVALID, NOCONFIG, ERR */
	static void activateAsync(String raw, Consumer<String> done) {
		final String uuid = uuid();
		final String code = raw.trim().toUpperCase().replace(" ", "");
		new Thread(() -> {
			String result = activate(code, uuid);
			Minecraft.getInstance().execute(() -> done.accept(result));
		}, "spawnerguard-license").start();
	}

	private static String activate(String code, String uuid) {
		try {
			String base = serverUrl();
			if (base.isEmpty() || base.contains("PASTE")) return "NOCONFIG";
			String url = base + (base.contains("?") ? "&" : "?")
				+ "key=" + URLEncoder.encode(code, "UTF-8") + "&uuid=" + URLEncoder.encode(uuid, "UTF-8");
			HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setInstanceFollowRedirects(true);
			c.setConnectTimeout(10000);
			c.setReadTimeout(15000);
			c.setRequestMethod("GET");
			String body;
			try (InputStream in = c.getInputStream()) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			}
			if (body.equals("OK:" + proofFor(uuid))) {
				Files.writeString(FILE, proofFor(uuid));
				licensed = true;
				return "OK";
			}
			if (body.equals("USED")) return "USED";
			if (body.equals("INVALID")) return "INVALID";
			return "ERR";
		} catch (Exception e) {
			return "ERR";
		}
	}
}
