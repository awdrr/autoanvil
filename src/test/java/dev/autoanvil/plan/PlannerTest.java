package dev.autoanvil.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerTest {
	// Vanilla 1.21.11 values (anvil_cost, max level) for the enchantments used here.
	static final int PROT = 0, FIRE_PROT = 1, BLAST = 2, UNB = 3, MENDING = 4, VANISH = 5, FEATHER = 6, AQUA = 7, DEPTH = 8,
			RESP = 9, FROST = 10, SHARP = 11, LOOT = 12, FIRE_ASPECT = 13, SWEEP = 14, SMITE = 15, THORNS = 16, SOUL = 17;
	static final int[] ANVIL = {1, 2, 4, 2, 4, 8, 2, 4, 4, 4, 4, 1, 4, 4, 4, 2, 8, 8};
	static final int[] MAX = {4, 4, 4, 3, 1, 1, 4, 1, 3, 3, 2, 5, 3, 2, 3, 5, 3, 3};

	static Rules rules(Set<Integer> applicable) {
		return new Rules() {
			public int anvilCost(int id) {
				return ANVIL[id];
			}

			public int maxLevel(int id) {
				return MAX[id];
			}

			public boolean compatible(int a, int b) {
				if (a == b) return false;
				Set<Integer> armor = Set.of(PROT, FIRE_PROT, BLAST);
				Set<Integer> boots = Set.of(DEPTH, FROST);
				Set<Integer> dmg = Set.of(SHARP, SMITE);
				return !(armor.contains(a) && armor.contains(b)) && !(boots.contains(a) && boots.contains(b))
						&& !(dmg.contains(a) && dmg.contains(b));
			}

			public boolean canEnchant(int id) {
				return applicable.contains(id);
			}
		};
	}

	static final Set<Integer> HELMET = Set.of(PROT, FIRE_PROT, BLAST, UNB, MENDING, VANISH, AQUA, RESP, THORNS);
	static final Set<Integer> BOOTS = Set.of(PROT, FIRE_PROT, BLAST, UNB, MENDING, VANISH, FEATHER, DEPTH, FROST, THORNS, SOUL);

	@Test
	void singleStepsMatchVanilla() {
		Rules r = rules(HELMET);
		// Protection IV book on a fresh helmet: book fee max(1, 1/2) = 1, times level 4.
		assertEquals(4, Anvil.merge(Piece.item(0), Piece.book(0, PROT, 4), r).cost());
		// Mending: anvil cost 4, halved for a book.
		assertEquals(2, Anvil.merge(Piece.item(0), Piece.book(0, MENDING, 1), r).cost());
		// Prior-work penalties of both inputs are added; result penalty is max*2+1.
		Anvil.Result m = Anvil.merge(Piece.item(3), Piece.book(1, UNB, 3), r);
		assertEquals(3 + 1 + 3, m.cost());
		assertEquals(7, m.piece().repairCost);
		// Equal levels go up one: Prot III + Prot III = Prot IV, priced at the new level.
		m = Anvil.merge(Piece.item(0, PROT, 3), Piece.book(0, PROT, 3), r);
		assertEquals(4, m.cost());
		assertEquals(4, m.piece().level(PROT));
		// Already at max: still charged.
		assertEquals(3, Anvil.merge(Piece.item(0, UNB, 3), Piece.book(0, UNB, 3), r).cost());
		// Conflict with an enchantment already on the item: skipped, +1 penalty, no result if nothing else applies.
		assertNull(Anvil.merge(Piece.item(0, FIRE_PROT, 4), Piece.book(0, PROT, 4), r));
		m = Anvil.merge(Piece.item(0, FIRE_PROT, 4), Piece.book(0, PROT, 4, UNB, 3), r);
		assertEquals(1 + 3, m.cost());
		assertEquals(0, m.piece().level(PROT));
		// Enchantments the item does not support are ignored for free...
		m = Anvil.merge(Piece.item(0), Piece.book(0, FEATHER, 4, PROT, 4), r);
		assertEquals(4, m.cost());
		assertEquals(0, m.piece().level(FEATHER));
		// ...and on their own give no result.
		assertNull(Anvil.merge(Piece.item(0), Piece.book(0, FEATHER, 4), r));
		// Book + book: anything goes onto a book, only the right book's enchantments are paid for.
		m = Anvil.merge(Piece.book(0, FEATHER, 4), Piece.book(0, AQUA, 1), r);
		assertEquals(2, m.cost());
		assertEquals(4, m.piece().level(FEATHER));
		assertTrue(m.piece().book);
		// 40 is "Too Expensive!".
		assertNull(Anvil.merge(Piece.item(31), Piece.book(7, MENDING, 1), r));
		assertEquals(39, Anvil.merge(Piece.item(31), Piece.book(7, UNB, 1), r).cost());
	}

	@Test
	void xpCurve() {
		assertEquals(0, Xp.pointsAt(0));
		assertEquals(315, Xp.pointsAt(15));
		assertEquals(1395, Xp.pointsAt(30));
		assertEquals(2727, Xp.pointsAt(39));
		// from level 0, pay 5 then 12: collect to 5, spend, collect to 12
		assertEquals(Xp.pointsAt(5) + Xp.pointsAt(12), Xp.pointsToPay(0, 0f, List.of(5, 12)));
		// at level 30 both are already affordable
		assertEquals(0, Xp.pointsToPay(30, 0f, List.of(5, 12)));
	}

	@Test
	void helmetPlanUsesEveryBookAndIsOptimal() {
		Rules r = rules(HELMET);
		List<Piece> books = List.of(Piece.book(0, PROT, 4), Piece.book(0, UNB, 3), Piece.book(0, MENDING, 1),
				Piece.book(0, AQUA, 1), Piece.book(0, RESP, 3), Piece.book(0, VANISH, 1));
		Planner.Plan p = plan(Piece.item(0), books, r, Planner.Objective.LEVELS);
		assertNotNull(p);
		assertEquals(63, p.bookMask);
		assertEquals(brute(Piece.item(0), books, r, Planner.Objective.LEVELS), p.score);
		for (int id : new int[] {PROT, UNB, MENDING, AQUA, RESP, VANISH}) assertTrue(p.result().level(id) > 0);
		assertEquals(books.size(), p.steps.size());
		assertTrue(p.maxStep < 40);
	}

	@Test
	void armorGodBookFitsAndAppliesToBoots() {
		Rules book = rules(Set.of());
		List<Piece> books = List.of(Piece.book(0, PROT, 4), Piece.book(0, UNB, 3), Piece.book(0, VANISH, 1),
				Piece.book(0, FEATHER, 4), Piece.book(0, AQUA, 1), Piece.book(0, MENDING, 1), Piece.book(0, DEPTH, 3));
		Planner.Plan cheapest = plan(null, books, book, Planner.Objective.XP);
		assertEquals(15, cheapest.result().repairCost, "fewest XP alone builds a lopsided tree");
		Planner.Plan p = plan(null, books, book, new Planner.Options(Planner.Objective.XP, false, true, b -> true));
		assertNotNull(p);
		assertEquals(127, p.bookMask, "all seven fit on one book");
		assertTrue(p.score >= cheapest.score);
		assertEquals(7, p.result().repairCost, "balanced tree: three levels deep");
		assertEquals(6, p.steps.size());
		// That book then goes onto fresh boots in one step (Aqua Affinity is ignored there).
		Anvil.Result onBoots = Anvil.merge(Piece.item(0), p.result(), rules(BOOTS));
		assertNotNull(onBoots);
		assertEquals(0, onBoots.piece().level(AQUA));
		assertEquals(4, onBoots.piece().level(FEATHER));
		assertTrue(onBoots.cost() < 40);
	}

	@Test
	void booksFirstFusesEverythingThenOneStepOntoTheItem() {
		Rules r = rules(HELMET);
		List<Piece> books = List.of(Piece.book(0, PROT, 4), Piece.book(0, UNB, 3), Piece.book(0, MENDING, 1),
				Piece.book(0, AQUA, 1), Piece.book(0, RESP, 3), Piece.book(0, VANISH, 1));
		Planner.Plan p = plan(Piece.item(0), books, r, Planner.Options.booksFirst(Planner.Objective.XP));
		assertNotNull(p);
		assertTrue(p.booksFirst);
		assertEquals(63, p.bookMask);
		// the item appears in exactly one step: the last one
		Planner.Node last = p.steps.get(p.steps.size() - 1);
		assertEquals(Planner.Node.ITEM, last.left.leaf);
		for (Planner.Node s : p.steps) if (s != last) assertTrue(s.piece.book, "every earlier step fuses books");
		assertEquals(7, last.right.piece.repairCost, "six books fused three levels deep");
		// it may cost a little more than the free order, never less
		Planner.Plan free = plan(Piece.item(0), books, r, Planner.Objective.XP);
		assertTrue(p.score >= free.score);
	}

	@Test
	void booksFirstFallsBackWhenOneStepWouldBeTooExpensive() {
		Rules r = rules(BOOTS);
		// one fused book: 7 + 4+3+2+4+4+6+12 = 42 on fresh boots, so it has to go on in two parts
		List<Piece> books = List.of(Piece.book(0, PROT, 4), Piece.book(0, UNB, 3), Piece.book(0, MENDING, 1), Piece.book(0, VANISH, 1),
				Piece.book(0, FEATHER, 4), Piece.book(0, DEPTH, 3), Piece.book(0, SOUL, 3));
		Planner.Plan p = plan(Piece.item(0), books, r, Planner.Options.booksFirst(Planner.Objective.XP));
		assertNotNull(p);
		assertEquals(127, p.bookMask, "nothing is left out");
		assertTrue(!p.booksFirst);
		assertTrue(p.maxStep < 40);
	}

	@Test
	void tooExpensiveKeepsTheMostImportantSubset() {
		Rules r = rules(HELMET);
		// A helmet that has been on the anvil 5 times already (penalty 31): only cheap additions still fit.
		List<Piece> books = List.of(Piece.book(0, MENDING, 1), Piece.book(0, UNB, 3), Piece.book(0, THORNS, 3), Piece.book(0, VANISH, 1));
		long[] cover = {1, 2, 4, 8};
		long[] weights = {1100, 1090, 1010, 1000};
		Planner.Plan p = Planner.plan(Piece.item(31), books, cover, weights, r, Planner.Options.of(Planner.Objective.XP));
		assertNotNull(p);
		assertTrue(p.maxStep < 40);
		// Thorns alone is 31 + 12 = 43. Mending + Unbreaking fused first is 31 + 1 + 2 + 3 = 37; after that the
		// helmet's penalty is 63 and nothing more fits, and no three-book fusion stays under 40.
		assertEquals(0b0011, p.bookMask);
	}

	@Test
	void conflictingBooksArePenalisedNotMerged() {
		Rules r = rules(BOOTS);
		List<Piece> books = List.of(Piece.book(0, DEPTH, 3), Piece.book(0, FROST, 2), Piece.book(0, FEATHER, 4));
		long[] cover = {1, 0, 2};
		Planner.Plan p = Planner.plan(Piece.item(0), books, cover, new long[] {1000, 1000}, r, Planner.Options.of(Planner.Objective.XP));
		assertNotNull(p);
		assertEquals(0b101, p.bookMask, "the Frost Walker book adds nothing wanted and only costs");
	}

	@Test
	void dpMatchesBruteForceOnRandomSets() {
		Random rnd = new Random(42);
		int[] pool = {PROT, UNB, MENDING, VANISH, AQUA, RESP, THORNS, FIRE_PROT};
		int compared = 0;
		for (int trial = 0; trial < 400; trial++) {
			// What the mod feeds the planner: each wanted enchantment on one book, nothing mutually exclusive.
			List<Integer> ench = new ArrayList<>();
			for (int e : pool) ench.add(e);
			java.util.Collections.shuffle(ench, rnd);
			ench.remove((Integer) (rnd.nextBoolean() ? PROT : FIRE_PROT));
			int n = 2 + rnd.nextInt(4);
			List<Piece> books = new ArrayList<>();
			int next = 0;
			for (int i = 0; i < n && next < ench.size(); i++) {
				int rc = rnd.nextInt(4) == 0 ? 1 : 0;
				int a = ench.get(next++);
				if (rnd.nextInt(3) == 0 && next < ench.size()) {
					int b = ench.get(next++);
					books.add(Piece.book(rc, a, 1 + rnd.nextInt(MAX[a]), b, 1 + rnd.nextInt(MAX[b])));
				} else {
					books.add(Piece.book(rc, a, 1 + rnd.nextInt(MAX[a])));
				}
			}
			n = books.size();
			Piece item = rnd.nextBoolean() ? null : Piece.item(rnd.nextInt(3) == 0 ? 3 : 0);
			Planner.Objective obj = rnd.nextBoolean() ? Planner.Objective.XP : Planner.Objective.LEVELS;
			Rules r = rules(HELMET);
			long[] cover = new long[n]; // every book equally wanted: only the full set is compared below
			for (int i = 0; i < n; i++) cover[i] = 1L << i;
			long[] weights = new long[n];
			Arrays.fill(weights, 1000);
			Planner.Plan p = Planner.plan(item, books, cover, weights, r, Planner.Options.of(obj));
			long brute = brute(item, books, r, obj);
			if (brute == Long.MAX_VALUE) continue; // the full set is impossible; covered elsewhere
			assertNotNull(p, "trial " + trial);
			assertEquals((1 << n) - 1, p.bookMask, "trial " + trial);
			assertEquals(brute, p.score, "trial " + trial + " " + books + " item " + item);
			compared++;
		}
		assertTrue(compared > 300, "compared " + compared);
	}

	private static Planner.Plan plan(Piece item, List<Piece> books, Rules r, Planner.Objective obj) {
		return plan(item, books, r, Planner.Options.of(obj));
	}

	private static Planner.Plan plan(Piece item, List<Piece> books, Rules r, Planner.Options opts) {
		long[] cover = new long[books.size()];
		long[] weights = new long[books.size()];
		for (int i = 0; i < books.size(); i++) {
			cover[i] = 1L << i;
			weights[i] = 1000;
		}
		return Planner.plan(item, books, cover, weights, r, opts);
	}

	/** Every tree over all the books (item always on the left), no pruning: the true minimum score. */
	private static long brute(Piece item, List<Piece> books, Rules r, Planner.Objective obj) {
		long best = Long.MAX_VALUE;
		for (long[] res : all(item, books, (1 << books.size()) - 1, r, obj)) best = Math.min(best, res[0]);
		return best;
	}

	private record R(long score, Piece piece) {
	}

	private static List<long[]> all(Piece item, List<Piece> books, int mask, Rules r, Planner.Objective obj) {
		List<long[]> out = new ArrayList<>();
		for (R x : trees(item, books, mask, r, obj)) out.add(new long[] {x.score});
		return out;
	}

	private static List<R> trees(Piece item, List<Piece> books, int mask, Rules r, Planner.Objective obj) {
		List<R> out = new ArrayList<>();
		if (item == null && Integer.bitCount(mask) == 1) {
			out.add(new R(0, books.get(Integer.numberOfTrailingZeros(mask))));
			return out;
		}
		if (item != null && mask == 0) {
			out.add(new R(0, item));
			return out;
		}
		for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
			int rest = mask ^ sub;
			if (item == null && rest == 0) continue;
			List<R> lefts = trees(item, books, rest, r, obj);
			List<R> rights = trees(null, books, sub, r, obj);
			for (R a : lefts) {
				for (R b : rights) {
					Anvil.Result m = Anvil.merge(a.piece, b.piece, r);
					if (m == null) continue;
					long s = obj == Planner.Objective.XP ? Xp.pointsAt(m.cost()) : m.cost();
					out.add(new R(a.score + b.score + s, m.piece()));
				}
			}
		}
		return out;
	}
}
