package dev.autoanvil.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class NavTest {
	/** A walkway that loops round the hall: base, down one side, across, back up the other side to near the base. */
	static final List<Vec3> LOOP = List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 20), new Vec3(10, 0, 20), new Vec3(10, 0, 0), new Vec3(2, 0, 0));

	static double length(Vec3 from, List<Vec3> path) {
		double d = 0;
		for (Vec3 p : path) {
			d += Nav.horiz(from, p);
			from = p;
		}
		return d;
	}

	@Test
	void openFloorGoesStraightThere() {
		Vec3 from = new Vec3(2, 0, 0.5), to = new Vec3(0, 0, 0);
		assertEquals(List.of(to), Nav.plan(LOOP, from, to, (a, b) -> true));
	}

	@Test
	void noShortcutsFollowsTheWalkwayAllTheWayRound() {
		Vec3 from = new Vec3(2, 0, 0.5), to = new Vec3(0, 0, 0);
		List<Vec3> path = Nav.plan(LOOP, from, to, (a, b) -> false);
		assertTrue(length(from, path) > 45, "walks the loop back: " + path);
		assertEquals(to, path.get(path.size() - 1));
	}

	@Test
	void shortcutAcrossTheFarEndOnly() {
		// a wall down the middle (x = 5) except at the far end (z >= 19): cut across there, not all the way round
		Vec3 from = new Vec3(10, 0, 15), to = new Vec3(0, 0, 15);
		List<Vec3> path = Nav.plan(LOOP, from, to, (a, b) -> (a.x - 5) * (b.x - 5) > 0 || Math.min(a.z, b.z) >= 19);
		double d = length(from, path);
		assertTrue(d < 21, "cuts across the far end: " + path + " = " + d);
		assertEquals(to, path.get(path.size() - 1));
	}

	@Test
	void sameSegmentStaysOnIt() {
		Vec3 from = new Vec3(0, 0, 3), to = new Vec3(0, 0, 9);
		assertTrue(length(from, Nav.plan(LOOP, from, to, (a, b) -> false)) < 6.5);
	}
}
