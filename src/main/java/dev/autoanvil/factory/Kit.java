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

	/** A shaped recipe laid out from the grid's top-left; ' ' is empty. */
	public record Recipe(Item result, String[] rows, Map<Character, Item> key) {
		public int count(Item ingredient) {
			int n = 0;
			for (String r : rows) for (char c : r.toCharArray()) if (key.get(c) == ingredient) n++;
			return n;
		}

		/** Grid cells (row * 3 + column) that take this ingredient. */
		public List<Integer> cells(Item ingredient) {
			List<Integer> out = new ArrayList<>();
			for (int r = 0; r < rows.length; r++) {
				for (int c = 0; c < rows[r].length(); c++) if (key.get(rows[r].charAt(c)) == ingredient) out.add(r * 3 + c);
			}
			return out;
		}

		public List<Item> ingredients() {
			List<Item> out = new ArrayList<>();
			for (Item i : key.values()) if (!out.contains(i)) out.add(i);
			return out;
		}
	}

	private static Recipe r(Item result, String... rows) {
		return new Recipe(result, rows, Map.of('X', Items.DIAMOND, '#', Items.STICK));
	}

	public static final List<Recipe> RECIPES = List.of(
			r(Items.DIAMOND_HELMET, "XXX", "X X"),
			r(Items.DIAMOND_CHESTPLATE, "X X", "XXX", "XXX"),
			r(Items.DIAMOND_LEGGINGS, "XXX", "X X", "X X"),
			r(Items.DIAMOND_BOOTS, "X X", "X X"),
			r(Items.DIAMOND_SWORD, " X ", " X ", " # "),
			r(Items.DIAMOND_PICKAXE, "XXX", " # ", " # "),
			r(Items.DIAMOND_AXE, "XX ", "X# ", " # "),
			r(Items.DIAMOND_SPEAR, "  X", " # ", "#  "));

	public static Recipe recipe(Item result) {
		for (Recipe r : RECIPES) if (r.result() == result) return r;
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
		List<Holder<Enchantment>> wanted = Catalog.wanted(applicable, AutoAnvil.CONFIG.profiles.get(Catalog.itemKind(sample)));
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
