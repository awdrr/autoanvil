package dev.autoanvil.factory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The route is a polyline: the base spot, then the walkway points you added. The factory walks along it, so it goes
 * where you walked when you marked it, except that it cuts straight across where the floor between two points is
 * clear (a walkway that loops round the hall isn't walked all the way round to get back).
 */
public final class Nav {
	private Nav() {
	}

	/** Closest point of the route to {@code p} (horizontally), how far along the route it is, and on which segment. */
	public record Proj(Vec3 point, double along, double off, int seg) {
	}

	public static Proj project(List<Vec3> route, Vec3 p) {
		if (route.size() == 1) return new Proj(route.get(0), 0, horiz(route.get(0), p), -1);
		Proj best = null;
		double s = 0;
		for (int i = 0; i + 1 < route.size(); i++) {
			Vec3 a = route.get(i), b = route.get(i + 1);
			double dx = b.x - a.x, dz = b.z - a.z, len2 = dx * dx + dz * dz, len = Math.sqrt(len2);
			double t = len2 < 1e-9 ? 0 : ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2;
			t = Math.max(0, Math.min(1, t));
			Vec3 q = a.add(b.subtract(a).scale(t));
			double off = horiz(q, p);
			if (best == null || off < best.off - 1e-9) best = new Proj(q, s + t * len, off, i);
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

	/**
	 * Waypoints from {@code from} to {@code to}, the last one being {@code to}: the shortest way along the walkway
	 * segments as walked, onto the walkway from {@code from} and off it to {@code to}, plus straight lines between
	 * any two of these points that {@code clear} says can be walked.
	 */
	public static List<Vec3> plan(List<Vec3> route, Vec3 from, Vec3 to, BiPredicate<Vec3, Vec3> clear) {
		if (route.size() < 2 || clear.test(from, to)) return List.of(to);
		route = refine(route, 2.0); // extra points so a shortcut can join the walkway part way along a long stretch
		Proj pf = project(route, from), pt = project(route, to);
		List<Vec3> nodes = new ArrayList<>(List.of(from, to));
		nodes.addAll(route);
		int nf = nodes.size(), nt = nf + 1, r0 = 2;
		nodes.add(pf.point());
		nodes.add(pt.point());
		int n = nodes.size();
		double[][] w = new double[n][n];
		for (double[] row : w) Arrays.fill(row, Double.POSITIVE_INFINITY);
		for (int i = 0; i + 1 < route.size(); i++) link(w, nodes, r0 + i, r0 + i + 1);
		link(w, nodes, 0, nf); // back onto the walkway
		link(w, nodes, 1, nt); // off it to the target
		link(w, nodes, nf, r0 + pf.seg());
		link(w, nodes, nf, r0 + pf.seg() + 1);
		link(w, nodes, nt, r0 + pt.seg());
		link(w, nodes, nt, r0 + pt.seg() + 1);
		if (pf.seg() == pt.seg()) link(w, nodes, nf, nt);
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				if (w[i][j] == Double.POSITIVE_INFINITY && horiz(nodes.get(i), nodes.get(j)) > 0.05 && clear.test(nodes.get(i), nodes.get(j))) link(w, nodes, i, j);
			}
		}
		// Dijkstra from 0 to 1
		double[] dist = new double[n];
		int[] prev = new int[n];
		boolean[] done = new boolean[n];
		Arrays.fill(dist, Double.POSITIVE_INFINITY);
		Arrays.fill(prev, -1);
		dist[0] = 0;
		for (int k = 0; k < n; k++) {
			int u = -1;
			for (int i = 0; i < n; i++) if (!done[i] && (u < 0 || dist[i] < dist[u])) u = i;
			if (u < 0 || dist[u] == Double.POSITIVE_INFINITY) break;
			done[u] = true;
			for (int v = 0; v < n; v++) {
				if (dist[u] + w[u][v] < dist[v]) {
					dist[v] = dist[u] + w[u][v];
					prev[v] = u;
				}
			}
		}
		if (prev[1] < 0) return List.of(to);
		List<Vec3> path = new ArrayList<>();
		for (int v = 1; v != 0; v = prev[v]) path.add(0, nodes.get(v));
		List<Vec3> out = new ArrayList<>();
		Vec3 last = from;
		for (Vec3 q : path) {
			if (horiz(q, last) > 0.05 || q == to) out.add(q);
			last = q;
		}
		return out;
	}

	/** The route with extra points every {@code step} blocks or less along each stretch. */
	static List<Vec3> refine(List<Vec3> route, double step) {
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i + 1 < route.size(); i++) {
			Vec3 a = route.get(i), b = route.get(i + 1);
			int k = Math.max(1, (int) Math.ceil(horiz(a, b) / step));
			for (int j = 0; j < k; j++) out.add(a.lerp(b, (double) j / k));
		}
		out.add(route.get(route.size() - 1));
		return out;
	}

	private static void link(double[][] w, List<Vec3> nodes, int a, int b) {
		double d = horiz(nodes.get(a), nodes.get(b));
		w[a][b] = Math.min(w[a][b], d);
		w[b][a] = Math.min(w[b][a], d);
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
