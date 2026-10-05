package dev.autoanvil.factory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.autoanvil.AutoAnvil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** {@code config/autoanvil-factory.json}: the hall layout you marked and how many of each item to make. */
public final class FactoryConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Where you stand to reach the anvil, crafting table and chests ({@code /kitfactory base}). */
	public double[] base;
	/** Walkway along the villagers, after the base ({@code /kitfactory path add}). */
	public List<double[]> path = new ArrayList<>();
	public int[] anvil;
	public int[] craftingTable;
	/** Optional: villager gear whose enchantments clash with yours (Fire Protection vs Protection IV) is ground clean here. */
	public int[] grindstone;
	/** Plain books, diamonds and sticks for crafted items, spare anvils. Spare emeralds are stored here too. */
	public List<int[]> inputChests = new ArrayList<>();
	/** Finished items go here, in order. */
	public List<int[]> outputChests = new ArrayList<>();
	/**
	 * The fisherman for levels only ({@code /kitfactory fisherman xp}): its emeralds are thrown out of the trading
	 * screen, into fire in front of it, so they never fill the inventory. Emeralds come from the other fisherman.
	 */
	public String xpFisherman;
	/** Server command that gives string (without the slash). */
	public String stringCommand = "string";
	/**
	 * Send the string command while the fisherman's screen stays open (like the old string macro: faster, no closing
	 * and reopening). That means the mod sends the command itself; a player can't type in chat with a screen open.
	 * false: close the screen and type it into chat like a player.
	 */
	public boolean stringInGui = true;
	/** Ask for more string when less than this is left, so it arrives before it runs out. */
	public int stringAskBelow = 128;
	/** At most one string command this often. */
	public int stringIntervalMs = 1000;
	/** If the command gave nothing, wait this long before trying again. */
	public int stringRetrySeconds = 30;
	/**
	 * Trade XP up to this level, then spend it at the anvil, then come back for more. Levels get dearer the higher
	 * you are, so spending them early is cheaper than saving up; around 30 balances that against walking.
	 */
	public int xpLevel = 30;
	/**
	 * Fewest of an item made, enchanted and stored together; the factory clears the inventory to fit them (packs
	 * spare emeralds into blocks, stores or throws the blocks, trades leftover string).
	 */
	public Map<String, Integer> batchMin = batch(3, 2, 3);
	/** Most of an item worked on together, when there's room. */
	public Map<String, Integer> batchMax = batch(3, 4, 4);
	/** Loose emeralds kept when packing the rest into blocks (more if the next books cost more). */
	public int keepEmeralds = 192;
	/** Spare emerald blocks: "chest" = into the input chests, thrown at the drop spot once they're full; "drop" = always thrown. */
	public String spareEmeralds = "chest";
	/** Where to throw spare emerald blocks ({@code /kitfactory dropspot}): x, y, z, yaw, pitch. */
	public double[] dropSpot;
	/**
	 * Bought from villagers, never crafted (a sale whose enchantment clashes with yours is ground clean on the
	 * grindstone). Everything else in {@link #targets} is crafted from the input chest.
	 */
	public List<String> buy = defaultBuy();
	/** Taken ready-made from the input chest (shields, netherite gear, anything): only enchanted here. */
	public List<String> take = new ArrayList<>();
	/** How many to make, in this order. */
	public Map<String, Integer> targets = defaultTargets();
	/** How many are finished and in the output chests. */
	public Map<String, Integer> done = new LinkedHashMap<>();

	static List<String> defaultBuy() {
		List<String> l = new ArrayList<>();
		for (String s : new String[] {"diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
				"diamond_sword", "diamond_pickaxe", "diamond_axe"}) {
			l.add("minecraft:" + s);
		}
		return l;
	}

	/** Everything the factory can make, in the order it works through them. */
	public static final List<String> ITEMS = List.of("minecraft:diamond_helmet", "minecraft:diamond_chestplate",
			"minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:diamond_sword", "minecraft:diamond_pickaxe",
			"minecraft:diamond_axe", "minecraft:diamond_spear");

	/** Per item: armor pieces, then sword / axe / spear, then pickaxe. */
	static Map<String, Integer> batch(int armor, int weapons, int pickaxe) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (String id : ITEMS) {
			m.put(id, id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots") ? armor
					: id.endsWith("_pickaxe") ? pickaxe : weapons);
		}
		return m;
	}

	public int batchMin(String id) {
		return Math.max(1, batchMin.getOrDefault(id, armor(id) ? 3 : id.endsWith("_pickaxe") ? 3 : 2));
	}

	public int batchMax(String id) {
		return Math.max(batchMin(id), batchMax.getOrDefault(id, armor(id) ? 3 : 4));
	}

	static boolean armor(String id) {
		return id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots");
	}

	/** Where an item comes from: "buy" (a villager), "craft" (the crafting table) or "take" (the input chest, ready-made). */
	public String source(String id) {
		if (take.contains(id)) return "take";
		if (buy.contains(id)) return "buy";
		return Kit.recipe(Kit.item(id)) != null ? "craft" : "take";
	}

	public void setSource(String id, String src) {
		buy.remove(id);
		take.remove(id);
		if (src.equals("buy")) buy.add(id);
		if (src.equals("take")) take.add(id);
	}

	/** For an item just added: bought if a villager in the hall sells it, else crafted if it can be, else from the chest. */
	public String defaultSource(String id) {
		for (TradeBook.Trader t : TradeBook.get().traders) for (TradeBook.Offer o : t.offers) if (o.enabled && o.result.equals(id)) return "buy";
		return Kit.recipe(Kit.item(id)) != null ? "craft" : "take";
	}

	static Map<String, Integer> defaultTargets() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (String s : ITEMS) m.put(s, 27);
		return m;
	}

	/**
	 * An item name to its id: "shield", "minecraft:shield", and for the diamond kit "pickaxe" too. Null if there's no
	 * such item, or it can't be enchanted.
	 */
	public static String resolve(String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		for (String id : ITEMS) if (id.equals("minecraft:diamond_" + n)) return id;
		var rl = net.minecraft.resources.Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
		if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) return null;
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(rl);
		return enchantable(item) ? rl.toString() : null;
	}

	/** Items that take at least one enchantment (what the factory can make). */
	public static boolean enchantable(net.minecraft.world.item.Item item) {
		var stack = new net.minecraft.world.item.ItemStack(item);
		if (stack.isEmpty() || item == net.minecraft.world.item.Items.BOOK || item == net.minecraft.world.item.Items.ENCHANTED_BOOK) return false;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return stack.isEnchantable();
		for (var e : dev.autoanvil.Catalog.all(mc.level.registryAccess())) if (e.value().canEnchant(stack)) return true;
		return false;
	}

	public Vec3 baseVec() {
		return base == null ? null : new Vec3(base[0], base[1], base[2]);
	}

	/** Base first, then the walkway points. */
	public List<Vec3> route() {
		List<Vec3> r = new ArrayList<>();
		if (base != null) r.add(baseVec());
		for (double[] p : path) r.add(new Vec3(p[0], p[1], p[2]));
		return r;
	}

	public static BlockPos pos(int[] a) {
		return a == null ? null : new BlockPos(a[0], a[1], a[2]);
	}

	public static int[] arr(BlockPos p) {
		return new int[] {p.getX(), p.getY(), p.getZ()};
	}

	public static FactoryConfig load() {
		Path file = path();
		FactoryConfig c = new FactoryConfig();
		try {
			if (Files.exists(file)) {
				FactoryConfig read = GSON.fromJson(Files.readString(file), FactoryConfig.class);
				if (read != null) c = read;
			}
		} catch (Exception e) {
			AutoAnvil.LOGGER.warn("Could not read {}: {}", file, e.toString());
		}
		if (c.path == null) c.path = new ArrayList<>();
		if (c.inputChests == null) c.inputChests = new ArrayList<>();
		if (c.outputChests == null) c.outputChests = new ArrayList<>();
		if (c.targets == null || c.targets.isEmpty()) c.targets = defaultTargets();
		if (c.done == null) c.done = new LinkedHashMap<>();
		if (c.buy == null) c.buy = defaultBuy();
		if (c.batchMin == null) c.batchMin = batch(3, 2, 3);
		if (c.batchMax == null) c.batchMax = batch(3, 4, 4);
		if (c.spareEmeralds == null) c.spareEmeralds = "chest";
		if (c.take == null) c.take = new ArrayList<>();
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

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autoanvil-factory.json");
	}
}
