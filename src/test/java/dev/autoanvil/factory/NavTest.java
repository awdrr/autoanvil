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

	/** The real hall: base north of the walkway, the walkway's first point east of the base, villagers west. */
	static final List<Vec3> HALL = List.of(new Vec3(-7389.0, 128.06, 9329.5), new Vec3(-7383.4, 128, 9333.7), new Vec3(-7425.5, 128, 9333.7));

	@Test
	void baseToAVillagerDoesNotGoViaTheWalkwayStart() {
		Vec3 base = HALL.get(0), armorer = new Vec3(-7396, 128, 9333.7);
		// something in the way of the straight line (a chest, a post): around it, joining the walkway near the villager
		Vec3 post = new Vec3(-7392.5, 128, 9331.6);
		List<Vec3> path = Nav.plan(HALL, base, armorer, (a, b) -> distToSegment(post, a, b) > 0.8);
		double d = length(base, path);
		assertTrue(d < 11, "around the post, not via the walkway start at -7383: " + path + " = " + d);
		for (Vec3 p : path) assertTrue(p.x < -7386, "never goes east to the walkway start: " + path);
	}

	static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
		double dx = b.x - a.x, dz = b.z - a.z, l2 = dx * dx + dz * dz;
		double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / l2));
		return Nav.horiz(p, new Vec3(a.x + t * dx, p.y, a.z + t * dz));
	}

	@Test
	void sameSegmentStaysOnIt() {
		Vec3 from = new Vec3(0, 0, 3), to = new Vec3(0, 0, 9);
		assertTrue(length(from, Nav.plan(LOOP, from, to, (a, b) -> false)) < 6.5);
	}
}
