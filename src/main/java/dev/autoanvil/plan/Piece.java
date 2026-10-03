package dev.autoanvil.plan;

import java.util.Arrays;

/**
 * One anvil input: the enchantments on an item or stored in a book, plus its prior-work penalty (the
 * {@code repair_cost} component, 2^uses - 1 for an item that has only ever been combined).
 */
public final class Piece {
	public final boolean book;
	public final int repairCost;
	private final int[] ids;
	private final int[] levels;

	public Piece(boolean book, int repairCost, int[] ids, int[] levels) {
		if (ids.length != levels.length) throw new IllegalArgumentException("ids/levels length");
		this.book = book;
		this.repairCost = repairCost;
		this.ids = ids.clone();
		this.levels = levels.clone();
	}

	public static Piece book(int repairCost, int... idLevelPairs) {
		return of(true, repairCost, idLevelPairs);
	}

	public static Piece item(int repairCost, int... idLevelPairs) {
		return of(false, repairCost, idLevelPairs);
	}

	private static Piece of(boolean book, int repairCost, int[] pairs) {
		int n = pairs.length / 2;
		int[] ids = new int[n], lv = new int[n];
		for (int i = 0; i < n; i++) {
			ids[i] = pairs[2 * i];
			lv[i] = pairs[2 * i + 1];
		}
		return new Piece(book, repairCost, ids, lv);
	}

	public int size() {
		return ids.length;
	}

	public int id(int i) {
		return ids[i];
	}

	public int levelAt(int i) {
		return levels[i];
	}

	/** Level of enchantment {@code id}, 0 when absent. */
	public int level(int id) {
		for (int i = 0; i < ids.length; i++) if (ids[i] == id) return levels[i];
		return 0;
	}

	public boolean sameEnchantments(Piece o) {
		if (o.ids.length != ids.length) return false;
		for (int i = 0; i < ids.length; i++) if (o.level(ids[i]) != levels[i]) return false;
		return true;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(book ? "book" : "item").append("[rc=").append(repairCost);
		for (int i = 0; i < ids.length; i++) sb.append(' ').append(ids[i]).append(':').append(levels[i]);
		return sb.append(']').toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Piece p && p.book == book && p.repairCost == repairCost && sameEnchantments(p);
	}

	@Override
	public int hashCode() {
		int[] sorted = new int[ids.length];
		for (int i = 0; i < ids.length; i++) sorted[i] = ids[i] * 64 + levels[i];
		Arrays.sort(sorted);
		return Arrays.hashCode(sorted) * 31 + repairCost * 2 + (book ? 1 : 0);
	}
}
