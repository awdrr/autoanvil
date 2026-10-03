package dev.autoanvil.run;

import dev.autoanvil.Catalog;
import dev.autoanvil.plan.Anvil;
import dev.autoanvil.plan.Piece;
import dev.autoanvil.plan.Planner;
import dev.autoanvil.plan.Rules;
import dev.autoanvil.plan.Xp;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * For one target: which enchantments it can get, which inventory books to use (the fewest that reach the best
 * available level of every wanted enchantment), and the cheapest anvil order for them.
 */
public final class Analysis {
	/** Anvil menu slots: 0 left, 1 right, 2 result, 3..38 player inventory (27 main + 9 hotbar). */
	public static final int INV_START = 3, INV_END = 39;

	public enum State {
		/** Will be added (or raised) by the plan. */
		READY,
		/** The item already has it at the best level you have a book for. */
		HAVE,
		/** No book for it (that would improve the item) in the inventory. */
		NO_BOOK,
		/** Something on the item is exclusive with it (e.g. Fire Protection blocks Protection). */
		BLOCKED,
		/** Wanted and available, but left out to stay under "Too Expensive!". */
		TOO_EXPENSIVE,
		/** Not ticked. */
		OFF
	}

	public record Row(Holder<Enchantment> ench, boolean wanted, int have, int best, int gets, State state, String note) {
	}

	/** An inventory book the plan uses; planner book index = position in {@link #inputs}. */
	public record Input(int slot, ItemStack stack) {
	}

	public final Target target;
	public final List<Row> rows;
	public final Planner.Plan plan;
	public final List<Input> inputs;
	public final List<Holder<Enchantment>> table;
	public final String problem;
	public final long xpNeeded;

	private Analysis(Target target, List<Row> rows, Planner.Plan plan, List<Input> inputs, List<Holder<Enchantment>> table,
			String problem, long xpNeeded) {
		this.target = target;
		this.rows = rows;
		this.plan = plan;
		this.inputs = inputs;
		this.table = table;
		this.problem = problem;
		this.xpNeeded = xpNeeded;
	}

	/** The registry-backed {@link Rules}: enchantment ids are indexes into {@code table}. */
	public static Rules rules(List<Holder<Enchantment>> table, ItemStack target, boolean creative) {
		return new Rules() {
			public int anvilCost(int id) {
				return table.get(id).value().getAnvilCost();
			}

			public int maxLevel(int id) {
				return table.get(id).value().getMaxLevel();
			}

			public boolean compatible(int a, int b) {
				return Enchantment.areCompatible(table.get(a), table.get(b));
			}

			public boolean canEnchant(int id) {
				return target != null && table.get(id).value().canEnchant(target);
			}

			public boolean infiniteMaterials() {
				return creative;
			}
		};
	}

	public static ItemEnchantments enchantments(ItemStack s) {
		return EnchantmentHelper.getEnchantmentsForCrafting(s);
	}

	public static boolean isBook(ItemStack s) {
		return s.is(Items.ENCHANTED_BOOK) && s.getCount() == 1 && !enchantments(s).isEmpty();
	}

	static Piece piece(ItemStack s, Map<Holder<Enchantment>, Integer> index, List<Holder<Enchantment>> table) {
		ItemEnchantments e = enchantments(s);
		int[] ids = new int[e.size()], lv = new int[e.size()];
		int i = 0;
		for (Object2IntMap.Entry<Holder<Enchantment>> en : e.entrySet()) {
			ids[i] = index.computeIfAbsent(en.getKey(), h -> {
				table.add(h);
				return table.size() - 1;
			});
			lv[i++] = en.getIntValue();
		}
		return new Piece(s.is(Items.ENCHANTED_BOOK), s.getOrDefault(DataComponents.REPAIR_COST, 0), ids, lv);
	}

	/** Level an anvil gives when a level {@code add} book meets level {@code have}. */
	static int combined(int have, int add, int max) {
		return Math.min(have == add ? add + 1 : Math.max(add, have), max);
	}

	public static Analysis of(RegistryAccess access, Player player, AbstractContainerMenu menu, Target t,
			Map<String, Boolean> profile, Planner.Objective objective, boolean booksFirst, List<ItemStack> products) {
		boolean creative = player.hasInfiniteMaterials();
		List<Holder<Enchantment>> applicable = new ArrayList<>();
		for (Holder<Enchantment> e : Catalog.all(access)) if (t.accepts(e)) applicable.add(e);
		List<Holder<Enchantment>> wanted = Catalog.wanted(t.profileKey(), applicable, profile);
		if (wanted.size() > 63) wanted = wanted.subList(0, 63);
		ItemEnchantments onItem = t.isBook() ? ItemEnchantments.EMPTY : enchantments(t.stack);

		// What each wanted enchantment could reach, and which are blocked by an exclusive one on the item.
		boolean[] blocked = new boolean[wanted.size()];
		String[] blockNote = new String[wanted.size()];
		int[] have = new int[wanted.size()];
		for (int j = 0; j < wanted.size(); j++) {
			Holder<Enchantment> e = wanted.get(j);
			have[j] = onItem.getLevel(e);
			for (Holder<Enchantment> k : onItem.keySet()) {
				if (!k.equals(e) && !Enchantment.areCompatible(e, k)) {
					blocked[j] = true;
					blockNote[j] = "Blocked by " + k.value().description().getString() + " on the item";
				}
			}
		}

		// Usable books and what each brings.
		List<Integer> slots = new ArrayList<>();
		List<int[]> gains = new ArrayList<>(); // per book: level reached per wanted index (0 = nothing)
		for (int slot : scanSlots()) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (!isBook(s)) continue;
			if (t.isBook() && isProduct(s, products)) continue;
			int[] gain = new int[wanted.size()];
			boolean usable = true;
			for (Object2IntMap.Entry<Holder<Enchantment>> en : enchantments(s).entrySet()) {
				Holder<Enchantment> e = en.getKey();
				int lv = en.getIntValue();
				int j = wanted.indexOf(e);
				if (j >= 0 && !blocked[j]) {
					int res = combined(have[j], lv, e.value().getMaxLevel());
					if (res > have[j]) gain[j] = res;
					continue;
				}
				if (t.isBook()) {
					usable = false; // a combined book gets nothing you did not tick
					break;
				}
				// On an item, other enchantments are fine as long as the anvil would not add them.
				boolean applies = creative || e.value().canEnchant(t.stack);
				if (!applies) continue;
				boolean conflicts = false;
				for (Holder<Enchantment> k : onItem.keySet()) if (!k.equals(e) && !Enchantment.areCompatible(e, k)) conflicts = true;
				if (conflicts) continue;
				int cur = onItem.getLevel(e);
				if (combined(cur, lv, e.value().getMaxLevel()) > cur) {
					usable = false;
					break;
				}
			}
			if (usable) {
				slots.add(slot);
				gains.add(gain);
			}
		}

		int[] best = bestLevels(gains, wanted.size());
		if (t.isBook()) {
			// A book that already holds everything on offer is a finished one, not an ingredient for the next.
			for (int b = gains.size() - 1; b >= 0; b--) {
				int[] g = gains.get(b);
				int count = 0;
				boolean complete = true;
				for (int j = 0; j < best.length; j++) {
					if (best[j] == 0) continue;
					count++;
					if (g[j] < best[j]) complete = false;
				}
				if (complete && count >= 2) {
					slots.remove(b);
					gains.remove(b);
				}
			}
			best = bestLevels(gains, wanted.size());
		}

		// Fewest books reaching the best level of every wanted enchantment: greedy set cover, most valuable first.
		long[] weights = new long[wanted.size()];
		for (int j = 0; j < wanted.size(); j++) weights[j] = 1000 + Math.max(0, 100 - Catalog.importance(wanted.get(j)));
		long needed = 0;
		for (int j = 0; j < best.length; j++) if (best[j] > 0) needed |= 1L << j;
		List<Integer> chosen = new ArrayList<>();
		long[] coverOf = new long[gains.size()];
		for (int b = 0; b < gains.size(); b++) {
			for (int j = 0; j < best.length; j++) if (best[j] > 0 && gains.get(b)[j] >= best[j]) coverOf[b] |= 1L << j;
		}
		long left = needed;
		while (left != 0 && chosen.size() < Planner.MAX_BOOKS) {
			int pick = -1;
			long pickW = 0;
			int pickSize = 0;
			for (int b = 0; b < gains.size(); b++) {
				if (chosen.contains(b)) continue;
				long w = 0;
				for (long bits = coverOf[b] & left; bits != 0; bits &= bits - 1) w += weights[Long.numberOfTrailingZeros(bits)];
				if (w == 0) continue;
				int size = enchantments(menu.getSlot(slots.get(b)).getItem()).size();
				if (w > pickW || (w == pickW && size < pickSize)) {
					pick = b;
					pickW = w;
					pickSize = size;
				}
			}
			if (pick < 0) break;
			chosen.add(pick);
			left &= ~coverOf[pick];
		}

		List<Holder<Enchantment>> table = new ArrayList<>();
		Map<Holder<Enchantment>, Integer> index = new HashMap<>();
		List<Input> inputs = new ArrayList<>();
		List<Piece> pieces = new ArrayList<>();
		long[] cover = new long[chosen.size()];
		Piece itemPiece = t.isBook() ? null : piece(t.stack, index, table);
		for (int i = 0; i < chosen.size(); i++) {
			int b = chosen.get(i);
			ItemStack s = menu.getSlot(slots.get(b)).getItem();
			inputs.add(new Input(slots.get(b), s.copy()));
			pieces.add(piece(s, index, table));
			cover[i] = coverOf[b];
		}

		Planner.Plan plan = null;
		String problem = null;
		int minBooks = t.isBook() ? 2 : 1;
		if (chosen.size() < minBooks) {
			if (t.isBook()) problem = chosen.isEmpty() ? "No books for this kind in your inventory" : "Need at least 2 books to combine";
			else problem = allMaxed(wanted, have, blocked) ? "Already has everything you ticked" : "No books that would add anything";
		} else {
			Rules rules = rules(table, t.isBook() ? null : t.stack, creative);
			Planner.Options options;
			if (t.isBook()) {
				List<ItemStack> reps = new ArrayList<>();
				for (var item : t.bookKind.items()) reps.add(new ItemStack(item));
				options = new Planner.Options(objective, false, true, p -> appliesUnder40(p, table, reps, creative));
			} else {
				options = booksFirst ? Planner.Options.booksFirst(objective) : Planner.Options.of(objective);
			}
			plan = Planner.plan(itemPiece, pieces, cover, weights, rules, options);
			if (plan == null) problem = "Too expensive: every combination costs 40+ levels";
		}

		// Rows for the panel.
		List<Row> rows = new ArrayList<>();
		for (Holder<Enchantment> e : applicable) {
			int j = wanted.indexOf(e);
			int cur = onItem.getLevel(e);
			if (j < 0) {
				boolean any = false;
				for (int slot : scanSlots()) {
					ItemStack s = menu.getSlot(slot).getItem();
					if (isBook(s) && enchantments(s).getLevel(e) > 0) any = true;
				}
				// unticked: shown only if the item has it or there is a book for it
				if (cur > 0 || any) rows.add(new Row(e, false, cur, 0, cur, State.OFF, any ? "Book in inventory (not ticked)" : ""));
				continue;
			}
			int gets = plan == null ? cur : resultLevel(plan, table, e, cur);
			State st;
			String note;
			if (blocked[j]) {
				st = State.BLOCKED;
				note = blockNote[j];
			} else if (gets > cur) {
				st = State.READY;
				note = cur > 0 ? "Raised from level " + cur : "Will be added";
			} else if (best[j] > cur) {
				st = State.TOO_EXPENSIVE;
				note = "Left out: would make a step cost 40+ levels";
			} else if (cur > 0 || anyBookFor(menu, e)) {
				st = State.HAVE;
				note = cur > 0 ? "Already on the item" : "Your book for it is no better than what is there";
			} else {
				st = State.NO_BOOK;
				note = "No book for it in your inventory";
			}
			rows.add(new Row(e, true, cur, best[j], gets, st, note));
		}

		long xp = plan == null || creative ? 0 : Xp.pointsToPay(player.experienceLevel, player.experienceProgress, plan.costs());
		return new Analysis(t, rows, plan, inputs, table, problem, xp);
	}

	private static boolean allMaxed(List<Holder<Enchantment>> wanted, int[] have, boolean[] blocked) {
		for (int j = 0; j < wanted.size(); j++) if (!blocked[j] && have[j] < wanted.get(j).value().getMaxLevel()) return false;
		return !wanted.isEmpty();
	}

	private static boolean anyBookFor(AbstractContainerMenu menu, Holder<Enchantment> e) {
		for (int slot : scanSlots()) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (isBook(s) && enchantments(s).getLevel(e) > 0) return true;
		}
		return false;
	}

	private static int resultLevel(Planner.Plan plan, List<Holder<Enchantment>> table, Holder<Enchantment> e, int fallback) {
		int id = table.indexOf(e);
		return id < 0 ? fallback : Math.max(fallback, plan.result().level(id));
	}

	private static int[] bestLevels(List<int[]> gains, int n) {
		int[] best = new int[n];
		for (int[] g : gains) for (int j = 0; j < n; j++) best[j] = Math.max(best[j], g[j]);
		return best;
	}

	/** A combined book is only useful if it can still go onto a fresh item of its kind without "Too Expensive!". */
	private static boolean appliesUnder40(Piece book, List<Holder<Enchantment>> table, List<ItemStack> reps, boolean creative) {
		if (creative) return true;
		for (ItemStack rep : reps) {
			Anvil.Result r = Anvil.merge(new Piece(false, 0, new int[0], new int[0]), book, rules(table, rep, false), false);
			if (r != null && r.cost() >= Anvil.TOO_EXPENSIVE) return false;
		}
		return true;
	}

	public static boolean isProduct(ItemStack s, List<ItemStack> products) {
		for (ItemStack p : products) if (ItemStack.matches(p, s)) return true;
		return false;
	}

	/** Anvil inputs first (books left there still count), then the inventory. */
	public static int[] scanSlots() {
		int[] out = new int[2 + INV_END - INV_START];
		out[0] = 0;
		out[1] = 1;
		for (int i = INV_START; i < INV_END; i++) out[2 + i - INV_START] = i;
		return out;
	}
}
