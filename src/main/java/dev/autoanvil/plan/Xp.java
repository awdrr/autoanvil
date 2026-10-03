package dev.autoanvil.plan;

/** Vanilla experience curve ({@code Player.getXpNeededForNextLevel}). */
public final class Xp {
	/** An XP bottle drops 3 + rand(5) + rand(5) points: 7 on average. */
	public static final double POINTS_PER_BOTTLE = 7.0;

	private Xp() {
	}

	public static int neededForNext(int level) {
		if (level >= 30) return 112 + (level - 30) * 9;
		if (level >= 15) return 37 + (level - 15) * 5;
		return 7 + level * 2;
	}

	/** Points from level 0 to the start of {@code level}. */
	public static long pointsAt(int level) {
		long sum = 0;
		for (int l = 0; l < level; l++) sum += neededForNext(l);
		return sum;
	}

	/**
	 * Points to collect to pay {@code costs} in order, starting at {@code level} + {@code progress}, paying each as soon
	 * as it can be afforded (what the mod does while someone splashes XP).
	 */
	public static long pointsToPay(int level, float progress, Iterable<Integer> costs) {
		long need = 0;
		double prog = progress;
		for (int c : costs) {
			if (level < c) {
				need += Math.round(pointsAt(c) - pointsAt(level) - prog * neededForNext(level));
				level = c;
				prog = 0;
			}
			level -= c;
		}
		return Math.max(0, need);
	}
}
