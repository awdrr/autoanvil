package dev.autoanvil.run;

import dev.autoanvil.AutoAnvil;
import dev.autoanvil.plan.Anvil;
import dev.autoanvil.plan.Planner;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Drives the anvil: for each planned step, shift-clicks the two inputs into the anvil, waits for the server's result
 * and cost, waits until you have the levels (someone splashing XP), then shift-clicks the result out. Every click is
 * checked against what the server sends back before the next one, so lag, a full inventory or an interfering click
 * stops it cleanly instead of losing items (anything left in the anvil returns to your inventory when it closes).
 */
public final class Runner {
	public enum Phase { CLEAR, START_JOB, PREP, PUT_LEFT, PUT_RIGHT, WAIT_RESULT, WAIT_XP, TAKE, CLEANUP, FINISHED }

	/** One completed anvil step, for the log and the self-test. */
	public record StepLog(int planned, int actual, int levelBefore, int levelAfter) {
	}

	public static final int COLOR_OK = 0xFF7CFC00, COLOR_WAIT = 0xFFFFD700, COLOR_BAD = 0xFFFF5555, COLOR_INFO = 0xFFDDDDDD;

	private final boolean all;
	private final Target first;
	/** Works through {@link ItemQueue} (items you marked with the queue key), including ones marked while running. */
	private final boolean fromQueue;
	/** Item targets still to do ("Start all"): where each was and what it was, so identical swords stay apart. */
	private final List<Queued> queue = new ArrayList<>();

	private record Queued(int slot, ItemStack stack) {
	}
	private Target target;
	private Analysis job;
	private final Map<Planner.Node, Integer> where = new IdentityHashMap<>();
	private final Map<Planner.Node, ItemStack> expect = new IdentityHashMap<>();
	private int stepIdx;
	private Phase phase = Phase.CLEAR;
	private int wait, phaseTicks, stableCost = -1, stableTicks, retries;
	private ItemStack[] snapshot;
	private int levelBefore;

	public String status = "Starting...";
	public int statusColor = COLOR_INFO;
	public boolean waitingForXp;
	public int jobsDone, stepsDone, costMismatches;
	public final List<StepLog> log = new ArrayList<>();
	public final List<String> results = new ArrayList<>();
	public String error;

	public Runner(Target first, boolean all, List<Target> itemTargets) {
		this.first = first;
		this.all = all;
		this.fromQueue = false;
		if (!first.isBook()) {
			if (all) for (Target t : itemTargets) queue.add(new Queued(t.slot, t.stack.copy()));
			else queue.add(new Queued(first.slot, first.stack.copy()));
		}
	}

	private Runner() {
		this.first = null;
		this.all = true;
		this.fromQueue = true;
	}

	public static Runner forQueue() {
		return new Runner();
	}

	public boolean isQueueRun() {
		return fromQueue;
	}

	public boolean finished() {
		return phase == Phase.FINISHED;
	}

	public Analysis job() {
		return job;
	}

	public int stepIndex() {
		return stepIdx;
	}

	public Phase phase() {
		return phase;
	}

	/** Stop now; anything in the anvil goes back to the inventory first. */
	public void stop(String why) {
		if (phase == Phase.FINISHED || phase == Phase.CLEANUP) return;
		fail(why);
	}

	/** The anvil closed under us (walked away, anvil broke): vanilla already returned its contents. */
	public void closed() {
		if (phase == Phase.FINISHED) return;
		error = "Anvil closed";
		status = "Stopped: anvil closed" + (stepsDone > 0 ? " - press Start again to continue" : "");
		statusColor = COLOR_BAD;
		phase = Phase.FINISHED;
		AutoAnvil.chat(Component.literal(status));
	}

	private void go(Phase p) {
		phase = p;
		phaseTicks = 0;
	}

	private void fail(String why) {
		error = why;
		status = why;
		statusColor = COLOR_BAD;
		AutoAnvil.chat(Component.literal("Stopped: " + why));
		AutoAnvil.LOGGER.warn("Stopped: {}", why);
		go(Phase.CLEANUP);
	}

	private static int latencyTicks(Minecraft mc) {
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
		int ms = info == null ? 0 : info.getLatency();
		return (int) Math.ceil(Math.max(0, ms) / 50.0);
	}

	private int delay(Minecraft mc) {
		return Math.max(0, AutoAnvil.CONFIG.actionDelayTicks) + latencyTicks(mc);
	}

	/** A real mouse click on the anvil screen (shift-click for QUICK_MOVE); the game handles it like a player's. */
	private void click(Minecraft mc, AnvilMenu menu, int slot, int button, boolean shift) {
		dev.autoanvil.factory.Input.clickSlot(mc, slot, button, shift);
		wait = delay(mc);
	}

	private static ItemStack at(AnvilMenu menu, int slot) {
		return menu.getSlot(slot).getItem();
	}

	public void tick(Minecraft mc, AnvilMenu menu, List<ItemStack> products) {
		if (phase == Phase.FINISHED) return;
		if (wait > 0) {
			wait--;
			return;
		}
		phaseTicks++;
		int timeout = 60 + 4 * latencyTicks(mc);
		boolean creative = mc.player.hasInfiniteMaterials();
		switch (phase) {
			case CLEAR -> {
				waitingForXp = false;
				if (!menu.getCarried().isEmpty()) {
					int empty = emptySlot(menu);
					if (empty < 0) {
						fail("Inventory full");
						return;
					}
					click(mc, menu, empty, 0, false);
					return;
				}
				for (int s = 0; s < 2; s++) {
					if (!at(menu, s).isEmpty()) {
						if (phaseTicks > timeout) {
							fail("Could not empty the anvil (inventory full?)");
							return;
						}
						click(mc, menu, s, 0, true);
						return;
					}
				}
				go(Phase.START_JOB);
			}
			case START_JOB -> startJob(mc, menu, products);
			case PREP -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				if (!locate(menu, step.left) || !locate(menu, step.right)) {
					fail("Inventory changed - press Start again");
					return;
				}
				if (!at(menu, 0).isEmpty() || !at(menu, 1).isEmpty() || !menu.getCarried().isEmpty()) {
					go(Phase.CLEAR); // something got in the way; START_JOB is not repeated, PREP resumes
					phase = Phase.CLEAR;
					resumeAfterClear = true;
					return;
				}
				status = "Step " + (stepIdx + 1) + "/" + job.plan.steps.size() + ": " + describe(step);
				statusColor = COLOR_INFO;
				click(mc, menu, where.get(step.left), 0, true);
				go(Phase.PUT_LEFT);
			}
			case PUT_LEFT -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				if (ItemStack.matches(at(menu, 0), expect.get(step.left))) {
					click(mc, menu, where.get(step.right), 0, true);
					go(Phase.PUT_RIGHT);
				} else if (phaseTicks > timeout) {
					fail("Could not put " + expect.get(step.left).getHoverName().getString() + " in the anvil");
				}
			}
			case PUT_RIGHT -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				if (ItemStack.matches(at(menu, 1), expect.get(step.right))) {
					stableCost = -1;
					go(Phase.WAIT_RESULT);
				} else if (phaseTicks > timeout) {
					fail("Could not put the book in the anvil");
				}
			}
			case WAIT_RESULT -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				int cost = menu.getCost();
				boolean has = !at(menu, AnvilMenu.RESULT_SLOT).isEmpty();
				if (cost >= Anvil.TOO_EXPENSIVE && !creative && phaseTicks > latencyTicks(mc) + 2) {
					fail("Too expensive on this server (" + cost + " levels, expected " + step.cost + ")");
					return;
				}
				if (has && cost > 0) {
					if (cost == stableCost) stableTicks++;
					else {
						stableCost = cost;
						stableTicks = 0;
					}
					if (stableTicks >= latencyTicks(mc) + 1) {
						if (cost != step.cost) {
							costMismatches++;
							AutoAnvil.LOGGER.warn("Step {} costs {} levels, planned {}", stepIdx + 1, cost, step.cost);
						}
						go(Phase.WAIT_XP);
					}
				} else if (phaseTicks > timeout) {
					fail("The anvil shows no result for " + describe(step));
				}
			}
			case WAIT_XP -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				if (!ItemStack.matches(at(menu, 0), expect.get(step.left)) || !ItemStack.matches(at(menu, 1), expect.get(step.right))) {
					fail("Anvil contents changed");
					return;
				}
				int cost = menu.getCost();
				if (cost != stableCost || at(menu, AnvilMenu.RESULT_SLOT).isEmpty()) {
					stableCost = -1;
					go(Phase.WAIT_RESULT);
					return;
				}
				int level = mc.player.experienceLevel;
				if (!creative && level < cost) {
					waitingForXp = true;
					status = "Waiting for XP: level " + level + " / " + cost + "  (step " + (stepIdx + 1) + "/" + job.plan.steps.size() + ")";
					statusColor = COLOR_WAIT;
					return;
				}
				waitingForXp = false;
				snapshot = new ItemStack[menu.slots.size()];
				for (int i = 0; i < snapshot.length; i++) snapshot[i] = at(menu, i).copy();
				levelBefore = level;
				click(mc, menu, AnvilMenu.RESULT_SLOT, 0, true);
				go(Phase.TAKE);
			}
			case TAKE -> {
				Planner.Node step = job.plan.steps.get(stepIdx);
				boolean emptied = at(menu, 0).isEmpty() && at(menu, 1).isEmpty() && at(menu, AnvilMenu.RESULT_SLOT).isEmpty();
				if (emptied && phaseTicks > latencyTicks(mc)) {
					int found = -1;
					for (int i = Analysis.INV_START; i < Analysis.INV_END; i++) {
						if (snapshot[i].isEmpty() && !at(menu, i).isEmpty()) found = i;
					}
					if (found < 0) {
						fail("Lost track of the result - check your inventory");
						return;
					}
					where.put(step, found);
					expect.put(step, at(menu, found).copy());
					log.add(new StepLog(step.cost, stableCost, levelBefore, mc.player.experienceLevel));
					stepsDone++;
					retries = 0;
					stepIdx++;
					if (stepIdx >= job.plan.steps.size()) finishJob(mc, menu, products, found);
					else go(Phase.PREP);
				} else if (phaseTicks > timeout) {
					if (++retries > 3) {
						fail("The server did not hand over the result");
						return;
					}
					stableCost = -1;
					go(Phase.WAIT_RESULT); // XP changed or a packet got lost: check again
				}
			}
			case CLEANUP -> {
				waitingForXp = false;
				for (int s = 0; s < 2; s++) {
					if (!at(menu, s).isEmpty() && phaseTicks < timeout) {
						click(mc, menu, s, 0, true);
						return;
					}
				}
				phase = Phase.FINISHED;
			}
			default -> {
			}
		}
	}

	private boolean resumeAfterClear;

	private void startJob(Minecraft mc, AnvilMenu menu, List<ItemStack> products) {
		if (resumeAfterClear && job != null) {
			resumeAfterClear = false;
			go(Phase.PREP);
			return;
		}
		Analysis a = null;
		Target t = null;
		if (fromQueue) {
			var inv = mc.player.getInventory();
			while (!ItemQueue.isEmpty()) {
				ItemQueue.Entry q = ItemQueue.ENTRIES.get(0);
				int idx = ItemQueue.locate(inv, q);
				ItemQueue.ENTRIES.remove(0);
				String name = q.stack().getHoverName().getString();
				int slot = idx < 0 ? -1 : ItemQueue.menuSlot(menu, inv, idx);
				if (slot < 0) {
					AutoAnvil.chat(Component.literal("Skipped " + name + ": not in your inventory any more"));
					continue;
				}
				Target cand = Target.item(slot, at(menu, slot));
				Analysis ca = AutoAnvil.analyze(mc, menu, cand);
				if (ca.plan != null) {
					t = cand;
					a = ca;
					break;
				}
				AutoAnvil.chat(Component.literal("Skipped " + name + ": " + ca.problem));
			}
			if (a == null) {
				done(jobsDone == 0 ? "Queue done: nothing could be added" : "Queue done: " + jobsDone + " item" + (jobsDone == 1 ? "" : "s") + " enchanted", jobsDone > 0);
				return;
			}
		} else if (first.isBook()) {
			t = first;
			a = AutoAnvil.analyze(mc, menu, t);
			if (a.plan == null) {
				done(jobsDone == 0 ? a.problem : "Made " + jobsDone + " combined book" + (jobsDone == 1 ? "" : "s"), jobsDone > 0);
				return;
			}
		} else {
			while (!queue.isEmpty()) {
				Queued q = queue.remove(0);
				// Its own slot first: with three identical swords, find() alone could pick one you left out.
				int slot = q.slot() >= Analysis.INV_START && ItemStack.matches(at(menu, q.slot()), q.stack()) ? q.slot() : find(menu, q.stack());
				if (slot < 0) continue;
				Target cand = Target.item(slot, at(menu, slot));
				Analysis ca = AutoAnvil.analyze(mc, menu, cand);
				if (ca.plan != null) {
					t = cand;
					a = ca;
					break;
				}
				if (!all) {
					done(ca.problem, false);
					return;
				}
			}
			if (a == null) {
				done(jobsDone == 0 ? "Nothing to do" : "Done: " + jobsDone + " item" + (jobsDone == 1 ? "" : "s") + " enchanted", jobsDone > 0);
				return;
			}
		}
		target = t;
		job = a;
		where.clear();
		expect.clear();
		assign(a.plan.root, a, t);
		stepIdx = 0;
		AutoAnvil.chat(Component.literal("Combining onto " + t.label().getString() + ": " + a.plan.steps.size() + " step"
				+ (a.plan.steps.size() == 1 ? "" : "s") + ", " + a.plan.totalLevels + " levels"));
		go(Phase.PREP);
	}

	private void assign(Planner.Node n, Analysis a, Target t) {
		if (n.isStep()) {
			assign(n.left, a, t);
			assign(n.right, a, t);
		} else if (n.leaf == Planner.Node.ITEM) {
			where.put(n, t.slot);
			expect.put(n, t.stack.copy());
		} else {
			Analysis.Input in = a.inputs.get(n.leaf);
			where.put(n, in.slot());
			expect.put(n, in.stack().copy());
		}
	}

	/** The expected stack is where we left it, or somewhere else in the inventory (we follow it). */
	private boolean locate(AnvilMenu menu, Planner.Node n) {
		Integer slot = where.get(n);
		ItemStack want = expect.get(n);
		if (slot != null && ItemStack.matches(at(menu, slot), want)) return true;
		int s = find(menu, want);
		if (s < 0) return false;
		where.put(n, s);
		return true;
	}

	private static int find(AnvilMenu menu, ItemStack want) {
		for (int i = Analysis.INV_START; i < Analysis.INV_END; i++) if (ItemStack.matches(at(menu, i), want)) return i;
		return -1;
	}

	private static int emptySlot(AnvilMenu menu) {
		for (int i = Analysis.INV_END - 1; i >= Analysis.INV_START; i--) if (at(menu, i).isEmpty()) return i;
		return -1;
	}

	private void finishJob(Minecraft mc, AnvilMenu menu, List<ItemStack> products, int slot) {
		ItemStack result = at(menu, slot);
		jobsDone++;
		StringBuilder sb = new StringBuilder();
		for (var e : Analysis.enchantments(result).entrySet()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(Enchantment.getFullname(e.getKey(), e.getIntValue()).getString());
		}
		String line = result.getHoverName().getString() + ": " + sb;
		results.add(line);
		AutoAnvil.chat(Component.literal("Done - " + line));
		if (target.isBook()) products.add(result.copy());
		if (all) go(Phase.CLEAR);
		else done("Done: " + line, true);
	}

	private void done(String msg, boolean ok) {
		status = msg;
		statusColor = ok ? COLOR_OK : COLOR_BAD;
		if (!ok) {
			error = msg;
			AutoAnvil.chat(Component.literal(msg));
		}
		waitingForXp = false;
		phase = Phase.FINISHED;
	}

	private String describe(Planner.Node step) {
		return name(step.left) + " + " + name(step.right) + " (" + step.cost + " lv)";
	}

	private String name(Planner.Node n) {
		if (n.leaf == Planner.Node.ITEM) return target.stack.getHoverName().getString();
		if (n.isStep() && !n.piece.book) return target.stack.getHoverName().getString();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n.piece.size(); i++) {
			if (i > 0) sb.append('+');
			Holder<Enchantment> e = job.table.get(n.piece.id(i));
			sb.append(Enchantment.getFullname(e, n.piece.levelAt(i)).getString());
		}
		return sb.toString();
	}
}
