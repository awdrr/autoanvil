package dev.autoanvil.factory;

import dev.autoanvil.AutoAnvil;
import dev.autoanvil.Catalog;
import dev.autoanvil.plan.Piece;
import dev.autoanvil.plan.Planner;
import dev.autoanvil.plan.Xp;
import dev.autoanvil.run.Analysis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** The items the factory makes: their crafting recipes, and which enchantments a finished one has. */
public final class Kit {
	private Kit() {
	}

	/** One kind of ingredient: an item, or any item of a tag (planks). */
	public record Ing(String name, String key, int stack, Predicate<ItemStack> match) {
		public boolean matches(ItemStack s) {
			return !s.isEmpty() && !s.isEnchanted() && match.test(s);
		}

		static Ing of(Item item) {
			return new Ing(new ItemStack(item).getHoverName().getString(), id(item), new ItemStack(item).getMaxStackSize(), s -> s.is(item));
		}

		static Ing tag(String name, TagKey<Item> tag) {
			return new Ing(name, "#" + tag.location(), 64, s -> s.is(tag));
		}
	}

	/** A shaped recipe laid out from the grid's top-left; ' ' is empty. */
	public record Recipe(Item result, String[] rows, Map<Character, Ing> key) {
		public int count(Ing ingredient) {
			int n = 0;
			for (String r : rows) for (char c : r.toCharArray()) if (key.get(c) == ingredient) n++;
			return n;
		}

		/** Grid cells (row * 3 + column) that take this ingredient. */
		public List<Integer> cells(Ing ingredient) {
			List<Integer> out = new ArrayList<>();
			for (int r = 0; r < rows.length; r++) {
				for (int c = 0; c < rows[r].length(); c++) if (key.get(rows[r].charAt(c)) == ingredient) out.add(r * 3 + c);
			}
			return out;
		}

		public List<Ing> ingredients() {
			List<Ing> out = new ArrayList<>();
			for (String r : rows) for (char c : r.toCharArray()) if (key.containsKey(c) && !out.contains(key.get(c))) out.add(key.get(c));
			return out;
		}

		/** Crafted one at a time: different kinds of a tag can't share a cell, and unstackable things one per cell. */
		public boolean oneAtATime() {
			for (Ing i : key.values()) if (i.key().startsWith("#") || i.stack() == 1) return true;
			return false;
		}

		public boolean uses(ItemStack s) {
			for (Ing i : key.values()) if (i.matches(s)) return true;
			return false;
		}
	}

	private static Recipe r(Item result, Ing x, String... rows) {
		Map<Character, Ing> key = new HashMap<>();
		key.put('X', x);
		key.put('#', Ing.of(Items.STICK));
		return new Recipe(result, rows, key);
	}

	private static Recipe r(Item result, Map<Character, Ing> key, String... rows) {
		return new Recipe(result, rows, key);
	}

	/** Armor and tool shapes, by the end of the item's name. */
	private static final Map<String, String[]> SHAPES = new LinkedHashMap<>();
	/** What armor and tools of a material are made of, by the start of the item's name. */
	private static final Map<String, java.util.function.Supplier<Ing>> MATERIALS = new LinkedHashMap<>();
	static {
		SHAPES.put("_helmet", new String[] {"XXX", "X X"});
		SHAPES.put("_chestplate", new String[] {"X X", "XXX", "XXX"});
		SHAPES.put("_leggings", new String[] {"XXX", "X X", "X X"});
		SHAPES.put("_boots", new String[] {"X X", "X X"});
		SHAPES.put("_sword", new String[] {" X ", " X ", " # "});
		SHAPES.put("_pickaxe", new String[] {"XXX", " # ", " # "});
		SHAPES.put("_axe", new String[] {"XX ", "X# ", " # "});
		SHAPES.put("_shovel", new String[] {" X ", " # ", " # "});
		SHAPES.put("_hoe", new String[] {"XX ", " # ", " # "});
		SHAPES.put("_spear", new String[] {"  X", " # ", "#  "});
		MATERIALS.put("diamond_", () -> Ing.of(Items.DIAMOND));
		MATERIALS.put("iron_", () -> Ing.of(Items.IRON_INGOT));
		MATERIALS.put("golden_", () -> Ing.of(Items.GOLD_INGOT));
		MATERIALS.put("copper_", () -> Ing.of(Items.COPPER_INGOT));
		MATERIALS.put("leather_", () -> Ing.of(Items.LEATHER));
		MATERIALS.put("stone_", () -> Ing.tag("Cobblestone", ItemTags.STONE_TOOL_MATERIALS));
		MATERIALS.put("wooden_", () -> Ing.tag("Planks", ItemTags.PLANKS));
	}

	/** How to craft an item at the crafting table, or null if the factory can't (netherite, chainmail, elytra, trident...). */
	public static Recipe recipe(Item result) {
		String p = BuiltInRegistries.ITEM.getKey(result).getPath();
		Ing stick = Ing.of(Items.STICK), string = Ing.of(Items.STRING), iron = Ing.of(Items.IRON_INGOT);
		switch (p) {
			case "shield":
				return r(result, Map.of('W', Ing.tag("Planks", ItemTags.PLANKS), 'o', iron), "WoW", "WWW", " W ");
			case "bow":
				return r(result, Map.of('#', stick, 'S', string), " #S", "# S", " #S");
			case "crossbow":
				return r(result, Map.of('#', stick, '$', iron, '~', string, '&', Ing.of(Items.TRIPWIRE_HOOK)), "#$#", "~&~", " # ");
			case "fishing_rod":
				return r(result, Map.of('#', stick, 'S', string), "  #", " #S", "# S");
			case "shears":
				return r(result, Map.of('X', iron), " X", "X ");
			case "flint_and_steel":
				return r(result, Map.of('X', iron, 'F', Ing.of(Items.FLINT)), "XF");
			case "brush":
				return r(result, Map.of('F', Ing.of(Items.FEATHER), 'C', Ing.of(Items.COPPER_INGOT), '#', stick), "F", "C", "#");
			case "turtle_helmet":
				return r(result, Map.of('X', Ing.of(Items.TURTLE_SCUTE)), "XXX", "X X");
			case "carrot_on_a_stick":
				return r(result, Map.of('#', Ing.of(Items.FISHING_ROD), 'X', Ing.of(Items.CARROT)), "# ", " X");
			case "warped_fungus_on_a_stick":
				return r(result, Map.of('#', Ing.of(Items.FISHING_ROD), 'X', Ing.of(Items.WARPED_FUNGUS)), "# ", " X");
			case "mace":
				return r(result, Map.of('H', Ing.of(Items.HEAVY_CORE), 'R', Ing.of(Items.BREEZE_ROD)), "H", "R");
			default:
		}
		for (var m : MATERIALS.entrySet()) {
			if (!p.startsWith(m.getKey())) continue;
			String[] shape = SHAPES.get(p.substring(m.getKey().length() - 1));
			if (shape == null) return null;
			boolean armor = p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots");
			boolean toolsOnly = m.getKey().equals("stone_") || m.getKey().equals("wooden_");
			if (armor && toolsOnly || !armor && m.getKey().equals("leather_")) return null; // no stone armor, no leather tools
			return r(result, m.getValue().get(), shape);
		}
		return null;
	}

	public static Item item(String id) {
		return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/**
	 * What a finished item of this kind carries: your Auto Anvil ticks for its kind, as far as a librarian in the
	 * hall sells them, at the best level sold.
	 */
	public static Map<Holder<Enchantment>, Integer> enchants(RegistryAccess access, Item item) {
		ItemStack sample = new ItemStack(item);
		List<Holder<Enchantment>> applicable = new ArrayList<>();
		for (Holder<Enchantment> e : Catalog.all(access)) if (e.value().canEnchant(sample)) applicable.add(e);
		String kind = Catalog.itemKind(sample);
		List<Holder<Enchantment>> wanted = Catalog.wanted(kind, applicable, AutoAnvil.CONFIG.profiles.get(kind));
		Map<Holder<Enchantment>, Integer> out = new LinkedHashMap<>();
		for (Holder<Enchantment> e : wanted) {
			int lvl = TradeBook.get().bestLevel(e);
			if (lvl > 0) out.put(e, Math.min(lvl, e.value().getMaxLevel()));
		}
		return out;
	}

	public static boolean finished(ItemStack s, Map<Holder<Enchantment>, Integer> kit) {
		if (kit.isEmpty()) return false;
		ItemEnchantments e = Analysis.enchantments(s);
		for (var en : kit.entrySet()) if (e.getLevel(en.getKey()) < en.getValue()) return false;
		return true;
	}

	/** A book holding exactly one enchantment at (at least) this level. */
	public static boolean isBookOf(ItemStack s, Holder<Enchantment> e, int level) {
		if (!s.is(Items.ENCHANTED_BOOK)) return false;
		ItemEnchantments st = s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		return st.size() == 1 && st.getLevel(e) >= level;
	}

	/** A book (any number of enchantments, e.g. half-combined) that carries this enchantment at this level. */
	public static boolean bookHas(ItemStack s, Holder<Enchantment> e, int level) {
		return s.is(Items.ENCHANTED_BOOK) && s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).getLevel(e) >= level;
	}

	/**
	 * The anvil steps (levels each) still ahead for these items, planned exactly as Auto Anvil will (one fresh book
	 * per missing enchantment).
	 */
	public static List<Integer> anvilCosts(List<ItemStack> items, Map<Holder<Enchantment>, Integer> kit) {
		List<Integer> costs = new ArrayList<>();
		Planner.Objective obj = "levels".equalsIgnoreCase(AutoAnvil.CONFIG.optimizeFor) ? Planner.Objective.LEVELS : Planner.Objective.XP;
		for (ItemStack item : items) {
			List<Holder<Enchantment>> table = new ArrayList<>();
			Map<Holder<Enchantment>, Integer> index = new HashMap<>();
			ItemEnchantments have = Analysis.enchantments(item);
			int[] ids = new int[have.size()], lv = new int[have.size()];
			int i = 0;
			for (var en : have.entrySet()) {
				ids[i] = idOf(en.getKey(), table, index);
				lv[i++] = en.getIntValue();
			}
			Piece itemPiece = new Piece(false, item.getOrDefault(DataComponents.REPAIR_COST, 0), ids, lv);
			List<Piece> books = new ArrayList<>();
			for (var en : kit.entrySet()) {
				if (have.getLevel(en.getKey()) >= en.getValue()) continue;
				books.add(new Piece(true, 0, new int[] {idOf(en.getKey(), table, index)}, new int[] {en.getValue()}));
			}
			if (books.isEmpty()) continue;
			long[] cover = new long[books.size()], weights = new long[books.size()];
			for (int b = 0; b < books.size(); b++) {
				cover[b] = 1L << b;
				weights[b] = 1000;
			}
			Planner.Options opts = AutoAnvil.CONFIG.combineBooksFirst ? Planner.Options.booksFirst(obj) : Planner.Options.of(obj);
			Planner.Plan plan = Planner.plan(itemPiece, books, cover, weights, Analysis.rules(table, item, false), opts);
			if (plan != null) costs.addAll(plan.costs());
		}
		return costs;
	}

	private static int idOf(Holder<Enchantment> e, List<Holder<Enchantment>> table, Map<Holder<Enchantment>, Integer> index) {
		return index.computeIfAbsent(e, h -> {
			table.add(h);
			return table.size() - 1;
		});
	}

	/** Total XP points the player has. */
	public static long xpPoints(Player p) {
		return Xp.pointsAt(p.experienceLevel) + Math.round(p.experienceProgress * Xp.neededForNext(p.experienceLevel));
	}
}
