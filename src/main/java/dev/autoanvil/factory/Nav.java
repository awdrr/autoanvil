package dev.autoanvil.factory;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The route is a polyline: the base spot, then the walkway points you added. The factory only ever walks along it,
 * so it goes exactly where you walked when you marked it.
 */
public final class Nav {
	private Nav() {
	}

	/** Closest point of the route to {@code p} (horizontally), and how far along the route it is. */
	public record Proj(Vec3 point, double along, double off) {
	}

	public static Proj project(List<Vec3> route, Vec3 p) {
		if (route.size() == 1) return new Proj(route.get(0), 0, horiz(route.get(0), p));
		Proj best = null;
		double s = 0;
		for (int i = 0; i + 1 < route.size(); i++) {
			Vec3 a = route.get(i), b = route.get(i + 1);
			double dx = b.x - a.x, dz = b.z - a.z, len2 = dx * dx + dz * dz, len = Math.sqrt(len2);
			double t = len2 < 1e-9 ? 0 : ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2;
			t = Math.max(0, Math.min(1, t));
			Vec3 q = a.add(b.subtract(a).scale(t));
			double off = horiz(q, p);
			if (best == null || off < best.off - 1e-9) best = new Proj(q, s + t * len, off);
			s += len;
		}
		return best;
	}

	/** Length of the route along the ground. */
	public static double length(List<Vec3> route) {
		double s = 0;
		for (int i = 0; i + 1 < route.size(); i++) s += horiz(route.get(i), route.get(i + 1));
		return s;
	}

	/** The point {@code along} blocks down the route (clamped to its ends). */
	public static Vec3 pointAt(List<Vec3> route, double along) {
		double s = 0;
		for (int i = 0; i + 1 < route.size(); i++) {
			Vec3 a = route.get(i), b = route.get(i + 1);
			double len = horiz(a, b);
			if (along <= s + len) return len < 1e-9 ? a : a.add(b.subtract(a).scale(Math.max(0, along - s) / len));
			s += len;
		}
		return route.get(route.size() - 1);
	}

	/** Where to head next to get from {@code pos} to {@code target} along the route. */
	public static Vec3 next(List<Vec3> route, Vec3 pos, Vec3 target) {
		if (route.size() < 2) return target;
		Proj me = project(route, pos), goal = project(route, target);
		if (me.off > 1.0) return me.point; // step back onto the walkway first
		double[] along = new double[route.size()];
		for (int i = 1; i < route.size(); i++) along[i] = along[i - 1] + horiz(route.get(i - 1), route.get(i));
		if (goal.along > me.along) {
			for (int i = 0; i < route.size(); i++) if (along[i] > me.along + 0.3 && along[i] < goal.along) return route.get(i);
		} else {
			for (int i = route.size() - 1; i >= 0; i--) if (along[i] < me.along - 0.3 && along[i] > goal.along) return route.get(i);
		}
		return target;
	}

	/** Where to stand for something at {@code p}: the closest point of the route. */
	public static Vec3 spotFor(List<Vec3> route, Vec3 p) {
		return project(route, p).point;
	}

	public static double horiz(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Distance from a point to a box (0 inside). */
	public static double toBox(Vec3 p, AABB box) {
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
