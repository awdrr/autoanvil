package dev.autoanvil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;

/** {@code config/autoanvil.json}. Missing fields keep their defaults; the file is rewritten with all fields. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Extra ticks between clicks (0 = every tick), on top of your ping. Raise it if a server complains about fast clicking. */
	public int actionDelayTicks = 0;
	/** "xp": fewest XP points when XP is splashed as you go (cheap steps, balanced order). "levels": fewest levels. */
	public String optimizeFor = "xp";
	/**
	 * false (default): the cheapest order. true: onto armor/tools, first fuse all the books into one book, then put
	 * that on in one step; if that one step would be "Too Expensive!" the cheapest order is used instead, so nothing is
	 * left out. Switchable on the panel's Steps tab.
	 */
	public boolean combineBooksFirst = false;
	/** Progress and results in chat. */
	public boolean chatMessages = true;
	/**
	 * Your enchantment choices per kind ("helmet", "boots", "sword", "spear", "book_armor", ...): enchantment id ->
	 * on/off. Anything not listed uses the default.
	 */
	public Map<String, Map<String, Boolean>> profiles = new TreeMap<>();

	public static Config load() {
		Path file = path();
		Config c = new Config();
		try {
			if (Files.exists(file)) {
				Config read = GSON.fromJson(Files.readString(file), Config.class);
				if (read != null) c = read;
			}
		} catch (Exception e) {
			AutoAnvil.LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
		}
		if (c.profiles == null) c.profiles = new TreeMap<>();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			AutoAnvil.LOGGER.warn("Could not write {}: {}", path(), e.toString());
		}
	}

	public Map<String, Boolean> profile(String key) {
		return profiles.computeIfAbsent(key, k -> new TreeMap<>());
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autoanvil.json");
	}
}
