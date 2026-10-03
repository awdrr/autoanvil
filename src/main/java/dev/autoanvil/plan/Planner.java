package dev.autoanvil.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Finds the cheapest anvil order for a set of books, either onto an item or into one book.
 *
 * <p>Exact dynamic programme over subsets: for every subset of the books, the cheapest way to fuse them into one book,
 * kept per resulting prior-work penalty (a cheaper result with a higher penalty can still lose later, since every
 * later step pays that penalty). The item side is the same over "item + subset". 3^n merge pairs, so n is capped at
 * {@link #MAX_BOOKS}. When the whole set cannot be done without a step reaching "Too Expensive!", the subset with the
 * most (and most important) enchantments that can be done wins: "as many as fit".
 */
public final class Planner {
	public static final int MAX_BOOKS = 12;

	/**
	 * @param objective        what "cheapest" means
	 * @param booksFirst       onto an item: prefer fusing all the books into one book and putting that on in a
	 *                         single step, over any cheaper order that puts books on one at a time; only an order
	 *                         that fits more enchantments under "Too Expensive!" beats it
	 * @param lowPenaltyFirst  prefer the result with the lowest prior-work penalty before the cheapest one (for a
	 *                         book that still has to go onto an item, where the penalty is paid again)
	 * @param acceptResult     final results that fail this are not offered (e.g. a book too expensive to apply)
	 */
	public record Options(Objective objective, boolean booksFirst, boolean lowPenaltyFirst, Predicate<Piece> acceptResult) {
		public static Options of(Objective objective) {
			return new Options(objective, false, false, p -> true);
		}

		public static Options booksFirst(Objective objective) {
			return new Options(objective, true, false, p -> true);
		}
	}

	public enum Objective {
		/** Fewest XP points, assuming each step is paid as soon as it is affordable (XP being splashed). */
		XP,
		/** Fewest levels. */
		LEVELS
	}

	/** A leaf (an input) or an anvil step combining {@link #left} (left slot) with {@link #right} (right slot). */
	public static final class Node {
		/** Book index for a book leaf, {@link #ITEM} for the item leaf, -2 for a step. */
		public final int leaf;
		public final Node left, right;
		public final int cost;
		public final Piece piece;
		public static final int ITEM = -1;

		Node(int leaf, Piece piece) {
			this.leaf = leaf;
			this.left = this.right = null;
			this.cost = 0;
			this.piece = piece;
		}

		Node(Node left, Node right, int cost, Piece piece) {
			this.leaf = -2;
			this.left = left;
			this.right = right;
			this.cost = cost;
			this.piece = piece;
		}

		public boolean isStep() {
			return leaf == -2;
		}
	}

	public static final class Plan {
		public final Node root;
		/** Anvil steps in execution order: every step after the steps it uses, cheapest ready step first. */
		public final List<Node> steps;
		/** Bit i set when book i is used. */
		public final int bookMask;
		public final int totalLevels, maxStep;
		public final long score;
		/** Sum of the weights of the wanted enchantments the result gains. */
		public final long weight;
		/** Onto an item: all the books are fused into one first and the item is used only in the last step. */
		public final boolean booksFirst;

		Plan(Node root, int bookMask, int totalLevels, int maxStep, long score, long weight) {
			this.root = root;
			this.booksFirst = root.isStep() && root.left.leaf == Node.ITEM;
			this.bookMask = bookMask;
			this.totalLevels = totalLevels;
			this.maxStep = maxStep;
			this.score = score;
			this.weight = weight;
			this.steps = order(root);
		}

		public Piece result() {
			return root.piece;
		}

		public List<Integer> costs() {
			List<Integer> c = new ArrayList<>();
			for (Node n : steps) c.add(n.cost);
			return c;
		}

		private static List<Node> order(Node root) {
			List<Node> all = new ArrayList<>();
			collect(root, all);
			List<Node> out = new ArrayList<>();
			java.util.Set<Node> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			PriorityQueue<Node> ready = new PriorityQueue<>(Comparator.comparingInt((Node n) -> n.cost).thenComparingInt(all::indexOf));
			for (Node n : all) if (!n.left.isStep() && !n.right.isStep()) ready.add(n);
			while (!ready.isEmpty()) {
				Node n = ready.poll();
				out.add(n);
				done.add(n);
				for (Node m : all) {
					if (done.contains(m) || ready.contains(m)) continue;
					if ((!m.left.isStep() || done.contains(m.left)) && (!m.right.isStep() || done.contains(m.right))) ready.add(m);
				}
			}
			return out;
		}

		private static void collect(Node n, List<Node> out) {
			if (!n.isStep()) return;
			collect(n.left, out);
			collect(n.right, out);
			out.add(n);
		}
	}

	private record Entry(long score, int levels, int maxStep, Node node) {
		int rc() {
			return node.piece.repairCost;
		}

		boolean noWorseThan(Entry o) {
			if (score != o.score) return score < o.score;
			if (levels != o.levels) return levels < o.levels;
			return maxStep <= o.maxStep;
		}
	}

	private Planner() {
	}

	/**
	 * @param item     the item to enchant, or null to fuse the books into one book
	 * @param books    candidate books (at most {@link #MAX_BOOKS})
	 * @param cover    per book, the bits of the wanted enchantments it brings
	 * @param weights  per wanted-enchantment bit, how much it is worth (more enchantments first, then importance)
	 * @return the best plan, or null when not even one step is possible
	 */
	public static Plan plan(Piece item, List<Piece> books, long[] cover, long[] weights, Rules rules, Options options) {
		Objective objective = options.objective();
		int n = books.size();
		if (n > MAX_BOOKS) throw new IllegalArgumentException("too many books: " + n);
		int full = (1 << n) - 1;
		@SuppressWarnings("unchecked")
		List<Entry>[] bookBest = new List[full + 1];
		for (int i = 0; i < n; i++) {
			bookBest[1 << i] = new ArrayList<>(List.of(new Entry(0, 0, 0, new Node(i, books.get(i)))));
		}
		for (int mask = 1; mask <= full; mask++) {
			if (Integer.bitCount(mask) < 2) continue;
			List<Entry> out = new ArrayList<>(2);
			for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
				combine(bookBest[sub], bookBest[mask ^ sub], out, rules, objective);
			}
			bookBest[mask] = out;
		}

		long[] maskWeight = new long[full + 1];
		long[] maskCover = new long[full + 1];
		for (int mask = 1; mask <= full; mask++) {
			int low = Integer.numberOfTrailingZeros(mask);
			maskCover[mask] = maskCover[mask & (mask - 1)] | cover[low];
			long w = 0;
			for (long bits = maskCover[mask]; bits != 0; bits &= bits - 1) w += weights[Long.numberOfTrailingZeros(bits)];
			maskWeight[mask] = w;
		}

		List<Entry>[] finals;
		@SuppressWarnings("unchecked")
		List<Entry>[] oneStep = new List[full + 1]; // item + one fused book
		if (item == null) {
			finals = bookBest;
		} else {
			Entry bare = new Entry(0, 0, 0, new Node(Node.ITEM, item));
			if (options.booksFirst()) {
				for (int mask = 1; mask <= full; mask++) {
					List<Entry> out = new ArrayList<>(2);
					combine(List.of(bare), bookBest[mask], out, rules, objective);
					oneStep[mask] = out;
				}
			}
			@SuppressWarnings("unchecked")
			List<Entry>[] itemBest = new List[full + 1];
			itemBest[0] = new ArrayList<>(List.of(bare));
			for (int mask = 1; mask <= full; mask++) {
				List<Entry> out = new ArrayList<>(2);
				for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
					combine(itemBest[mask ^ sub], bookBest[sub], out, rules, objective);
				}
				itemBest[mask] = out;
			}
			finals = itemBest;
		}

		Entry best = null;
		int bestMask = 0;
		boolean bestOne = false;
		int minBooks = item == null ? 2 : 1;
		for (int pass = 0; pass < 2; pass++) {
			List<Entry>[] lists = pass == 0 ? finals : oneStep;
			boolean one = pass == 1;
			for (int mask = 1; mask <= full; mask++) {
				if (Integer.bitCount(mask) < minBooks || lists[mask] == null) continue;
				for (Entry e : lists[mask]) {
					if (!options.acceptResult().test(e.node.piece)) continue;
					long w = maskWeight[mask], bw = maskWeight[bestMask];
					boolean wins;
					if (best == null) wins = true;
					else if (w != bw) wins = w > bw;
					else if (one != bestOne) wins = one;
					else wins = better(w, e, bw, best, options.lowPenaltyFirst());
					if (wins) {
						best = e;
						bestMask = mask;
						bestOne = one;
					}
				}
			}
		}
		if (best == null) return null;
		return new Plan(best.node, bestMask, best.levels, best.maxStep, best.score, maskWeight[bestMask]);
	}

	private static boolean better(long w, Entry e, long bw, Entry b, boolean lowPenaltyFirst) {
		if (w != bw) return w > bw;
		if (lowPenaltyFirst && e.rc() != b.rc()) return e.rc() < b.rc();
		if (e.score != b.score) return e.score < b.score;
		if (e.levels != b.levels) return e.levels < b.levels;
		if (e.rc() != b.rc()) return e.rc() < b.rc();
		return e.maxStep < b.maxStep;
	}

	private static void combine(List<Entry> lefts, List<Entry> rights, List<Entry> out, Rules rules, Objective objective) {
		if (lefts == null || rights == null) return;
		for (Entry l : lefts) {
			for (Entry r : rights) {
				Anvil.Result m = Anvil.merge(l.node.piece, r.node.piece, rules);
				if (m == null) continue;
				long stepScore = objective == Objective.XP ? Xp.pointsAt(m.cost()) : m.cost();
				Entry e = new Entry(l.score + r.score + stepScore, l.levels + r.levels + m.cost(),
						Math.max(m.cost(), Math.max(l.maxStep, r.maxStep)), new Node(l.node, r.node, m.cost(), m.piece()));
				offer(out, e);
			}
		}
	}

	/** Keeps only entries not beaten by one with an equal or lower prior-work penalty. */
	private static void offer(List<Entry> list, Entry e) {
		for (Entry x : list) if (x.rc() <= e.rc() && x.noWorseThan(e)) return;
		list.removeIf(x -> e.rc() <= x.rc() && e.noWorseThan(x));
		list.add(e);
	}
}
