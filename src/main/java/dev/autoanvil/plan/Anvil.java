package dev.autoanvil.plan;

import java.util.Arrays;

/**
 * The enchantment-combining half of vanilla {@code AnvilMenu.createResult} (1.21.11), line for line, without renaming
 * and durability repair (the mod never renames, and the sacrifice is always a book).
 */
public final class Anvil {
	/** In survival a cost of 40 or more shows "Too Expensive!" and the result cannot be taken. */
	public static final int TOO_EXPENSIVE = 40;

	private Anvil() {
	}

	public record Result(int cost, Piece piece) {
	}

	/** Left item + right sacrifice, or null when the anvil shows no result (or one that cannot be taken). */
	public static Result merge(Piece left, Piece right, Rules rules) {
		return merge(left, right, rules, true);
	}

	/** As {@link #merge(Piece, Piece, Rules)}; with {@code cap} false a cost of 40+ is returned instead of null. */
	public static Result merge(Piece left, Piece right, Rules rules, boolean cap) {
		if (!right.book) return null; // the mod only ever sacrifices books
		long tax = (long) left.repairCost + right.repairCost;
		int price = 0;
		int n = left.size();
		int[] ids = Arrays.copyOf(idsOf(left), n + right.size());
		int[] lv = Arrays.copyOf(levelsOf(left), n + right.size());
		boolean anyCompatible = false, anyIncompatible = false;
		for (int k = 0; k < right.size(); k++) {
			int id = right.id(k);
			int idx = indexOf(ids, n, id);
			int current = idx < 0 ? 0 : lv[idx];
			int level = right.levelAt(k);
			level = current == level ? level + 1 : Math.max(level, current);
			boolean compatible = rules.canEnchant(id);
			if (rules.infiniteMaterials() || left.book) compatible = true;
			for (int o = 0; o < n; o++) {
				if (ids[o] != id && !rules.compatible(id, ids[o])) {
					compatible = false;
					price++;
				}
			}
			if (!compatible) {
				anyIncompatible = true;
			} else {
				anyCompatible = true;
				level = Math.min(level, rules.maxLevel(id));
				if (idx < 0) {
					ids[n] = id;
					idx = n++;
				}
				lv[idx] = level;
				int fee = rules.anvilCost(id);
				fee = Math.max(1, fee / 2); // right is a book
				price += fee * level;
			}
		}
		if (anyIncompatible && !anyCompatible) return null;
		if (price <= 0) return null;
		long cost = Math.min(tax + price, Integer.MAX_VALUE);
		if (cap && cost >= TOO_EXPENSIVE && !rules.infiniteMaterials()) return null;
		int rc = (int) Math.min(Math.max(left.repairCost, right.repairCost) * 2L + 1L, Integer.MAX_VALUE);
		return new Result((int) cost, new Piece(left.book, rc, Arrays.copyOf(ids, n), Arrays.copyOf(lv, n)));
	}

	private static int indexOf(int[] ids, int n, int id) {
		for (int i = 0; i < n; i++) if (ids[i] == id) return i;
		return -1;
	}

	private static int[] idsOf(Piece p) {
		int[] a = new int[p.size()];
		for (int i = 0; i < a.length; i++) a[i] = p.id(i);
		return a;
	}

	private static int[] levelsOf(Piece p) {
		int[] a = new int[p.size()];
		for (int i = 0; i < a.length; i++) a[i] = p.levelAt(i);
		return a;
	}
}
