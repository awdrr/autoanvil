package dev.autoanvil.ui;

import dev.autoanvil.AutoAnvil;
import dev.autoanvil.plan.Planner;
import dev.autoanvil.plan.Xp;
import dev.autoanvil.run.Analysis;
import dev.autoanvil.run.ItemQueue;
import dev.autoanvil.run.Runner;
import dev.autoanvil.run.Target;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * The panel beside the anvil: pick the target (an item in your inventory, or a combined book), tick the enchantments
 * you want, see the plan and its XP cost, Start. Everything it shows comes from {@link AutoAnvil#analysis} and the
 * {@link Runner}.
 */
public final class Panel extends AbstractWidget {
	private static final int MAX_W = 166, MIN_W = 116;
	private static final int PAD = 5, ROW = 11, LINE = 10;
	private static final int BG = 0xE0101418, BORDER = 0xFF5A5040, GOLD = 0xFFFFAA00, WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA,
			DARK = 0xFF707070, GREEN = 0xFF55FF55, RED = 0xFFFF5555, ORANGE = 0xFFFFAA33, YELLOW = 0xFFFFE066;

	/** Last placed panel, for the click-outside check and the self-test. */
	public static Panel current;
	/** Steps tab open (kept while the game runs). */
	private static boolean showSteps;

	private final AnvilScreen screen;
	private final Font font;
	private int scroll;
	private final List<Hit> hits = new ArrayList<>();
	private int rowsTop, rowsBottom;

	/** A clickable or hoverable region from the last frame. */
	public record Hit(String id, int x0, int y0, int x1, int y1, Runnable action, List<Component> tooltip) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	public Panel(AnvilScreen screen) {
		super(0, 0, MAX_W, 40, Component.literal("Auto Anvil"));
		this.screen = screen;
		this.font = Minecraft.getInstance().font;
		current = this;
		place();
	}

	/** Beside the anvil on whichever side has more room, narrowed to fit; on a tiny window it overlaps. */
	private void place() {
		int left = screen.leftPos, top = screen.topPos;
		int roomLeft = left - 6, roomRight = screen.width - (left + screen.imageWidth) - 6;
		int room = Math.max(roomLeft, roomRight);
		W = Math.max(MIN_W, Math.min(MAX_W, room));
		int x = roomLeft >= roomRight ? left - W - 4 : left + screen.imageWidth + 4;
		x = Math.max(2, Math.min(x, screen.width - W - 2));
		setX(x);
		setWidth(W);
		setY(Math.max(2, Math.min(top, 20)));
	}

	private int W = MAX_W;

	public List<Hit> hits() {
		return hits;
	}

	public boolean covers(double x, double y) {
		return visible && x >= getX() && x < getX() + width && y >= getY() && y < getY() + height;
	}

	@Override
	public boolean shouldTakeFocusAfterInteraction() {
		return false;
	}

	// ---- layout: ops are collected first so the background can be sized to the content ----

	private interface Op {
		void draw(GuiGraphics g);
	}

	private final List<Op> ops = new ArrayList<>();

	private void text(String s, int x, int y, int color) {
		ops.add(g -> g.drawString(font, s, x, y, color, false));
	}

	private int wrapped(String s, int x, int y, int width, int color) {
		List<FormattedCharSequence> lines = font.split(Component.literal(s), width);
		for (FormattedCharSequence l : lines) {
			int yy = y;
			ops.add(g -> g.drawString(font, l, x, yy, color, false));
			y += LINE;
		}
		return y;
	}

	private void button(String id, String label, int x, int y, int w, boolean enabled, Runnable action, String tip, int mx, int my) {
		boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + 14;
		ops.add(g -> {
			g.fill(x, y, x + w, y + 14, enabled ? (hover ? 0xFF6A6A6A : 0xFF3C3C3C) : 0xFF262626);
			g.renderOutline(x, y, w, 14, enabled ? 0xFF000000 : 0xFF1A1A1A);
			g.drawCenteredString(font, label, x + w / 2, y + 3, enabled ? WHITE : DARK);
		});
		hits.add(new Hit(id, x, y, x + w, y + 14, enabled ? action : null, tip == null ? List.of() : List.of(Component.literal(tip))));
	}

	private void divider(int y) {
		int x = getX();
		ops.add(g -> g.fill(x + PAD, y, x + W - PAD, y + 1, 0xFF3A3530));
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mx, int my, float partialTick) {
		place();
		current = this;
		ops.clear();
		hits.clear();
		int x = getX(), y = getY() + PAD, inner = W - 2 * PAD;
		Analysis a = AutoAnvil.analysis;
		Runner run = AutoAnvil.runner;
		boolean running = AutoAnvil.running();
		Target t = AutoAnvil.current();

		text("Auto Anvil", x + PAD, y, GOLD);
		if (running) text(run.waitingForXp ? "waiting" : "running", x + W - PAD - font.width(run.waitingForXp ? "waiting" : "running"), y, run.waitingForXp ? YELLOW : GREEN);
		y += 12;

		if (t == null) {
			y = wrapped("Put enchanted books (and the armor or tools to put them on) in your inventory. Worn armor can't go in an anvil: take it off first.",
					x + PAD, y, inner, GRAY);
		} else {
			// target selector
			int ty = y;
			boolean many = AutoAnvil.targets.size() > 1 && !running;
			button("prev", "<", x + PAD, ty + 1, 12, many, () -> AutoAnvil.cycle(-1), "Previous target", mx, my);
			button("next", ">", x + W - PAD - 12, ty + 1, 12, many, () -> AutoAnvil.cycle(1), "Next target", mx, my);
			ops.add(gg -> gg.renderItem(t.stack, x + PAD + 15, ty));
			String label = font.plainSubstrByWidth(AutoAnvil.displayName(t), inner - 15 - 18 - 14);
			text(label, x + PAD + 33, ty + 4, WHITE);
			hits.add(new Hit("target", x + PAD + 15, ty, x + W - PAD - 14, ty + 16, null,
					t.isBook() ? List.of(t.label(), Component.literal("Combines the ticked enchantments into one book"))
							: List.of(Component.literal(AutoAnvil.displayName(t)), Component.literal(AutoAnvil.where(t)).withColor(0xAAAAAA),
									Component.literal("Books from your inventory go onto this item"))));
			y += 19;
			int itemCount = 0, itemIdx = 0;
			for (Target o : AutoAnvil.targets) {
				if (!o.isBook()) {
					if (o == t) itemIdx = itemCount;
					itemCount++;
				}
			}
			if (t.isBook()) {
				text("Combine into one book", x + PAD, y, GRAY);
			} else {
				text("Item " + (itemIdx + 1) + " of " + itemCount, x + PAD, y, GRAY);
				boolean inc = AutoAnvil.included(t);
				int bx = x + W - PAD - 52;
				int by = y;
				ops.add(gg -> {
					gg.renderOutline(bx, by, 8, 8, GRAY);
					if (inc) gg.fill(bx + 2, by + 2, bx + 6, by + 6, GREEN);
					gg.drawString(font, "in All", bx + 11, by, GRAY, false);
				});
				hits.add(new Hit("include", bx, by - 1, x + W - PAD, by + 9, running ? null : AutoAnvil::toggleInclude,
						List.of(Component.literal(inc ? "Included in \"All items\"" : "Skipped by \"All items\""), Component.literal("Click to change"))));
			}
			y += 12;
			divider(y - 2);

			// tabs: the enchantments to tick, or the anvil steps of the plan
			Analysis shownJob = running && run.job() != null ? run.job() : a;
			Planner.Plan shown = shownJob == null ? null : shownJob.plan;
			int tabW = (inner - 4) / 2;
			tab("tab:enchants", "Enchants", x + PAD, y, tabW, !showSteps, () -> {
				showSteps = false;
				scroll = 0;
			}, "What goes on: tick what you want", mx, my);
			tab("tab:steps", "Steps" + (shown == null ? "" : " (" + shown.steps.size() + ")"), x + PAD + tabW + 4, y, tabW, showSteps, () -> {
				showSteps = true;
				scroll = 0;
			}, "The anvil steps, in the order they will be done", mx, my);
			y += 15;

			List<String> missing = new ArrayList<>();
			if (!showSteps && a != null) {
				for (Analysis.Row r : a.rows) {
					int max = r.ench().value().getMaxLevel();
					if (r.wanted() && r.state() != Analysis.State.BLOCKED && Math.max(r.have(), r.best()) < max) missing.add(name(r.ench(), max));
				}
			}
			if (showSteps) {
				String order = AutoAnvil.CONFIG.combineBooksFirst ? "Order: books first" : "Order: cheapest";
				int oy = y;
				boolean canToggle = !running && !t.isBook();
				ops.add(gg -> gg.drawString(font, order + (canToggle ? "  \u21C4" : ""), x + PAD, oy, canToggle ? 0xFFB0C8FF : GRAY, false));
				hits.add(new Hit("order", x + PAD, oy - 1, x + W - PAD, oy + 9, canToggle ? AutoAnvil::toggleOrder : null, List.of(
						Component.literal(AutoAnvil.CONFIG.combineBooksFirst ? "Books first: all books into one book, then one step onto the item" : "Cheapest: the order that needs the least XP"),
						Component.literal(canToggle ? "Click to switch" : "").withColor(0x777777))));
				y += LINE + 1;
			}

			int count = showSteps ? (shown == null ? 0 : shown.steps.size()) : (a == null ? 0 : a.rows.size());
			int reserved = 5 * LINE + 18 + 2 * LINE + PAD + 6 + (missing.isEmpty() ? 0 : 2 * LINE)
					+ (ItemQueue.isEmpty() ? LINE : (Math.min(ItemQueue.ENTRIES.size(), 4) + 2) * LINE);
			int maxRows = Math.max(3, (screen.height - 4 - y - reserved) / ROW);
			int visible = Math.min(count, maxRows);
			scroll = Math.max(0, Math.min(scroll, count - visible));
			rowsTop = y;
			if (count == 0) {
				text(showSteps ? "No plan yet" : "No enchantments fit this", x + PAD, y + 1, DARK);
				y += ROW;
			}
			for (int i = scroll; i < scroll + visible; i++) {
				if (showSteps) stepRow(i, shown, shownJob, running ? run : null, x, y, mx, my);
				else row(a.rows.get(i), x, y, running, mx, my);
				y += ROW;
			}
			rowsBottom = y;
			if (count > visible) {
				String more = (scroll > 0 ? "\u25B2 " : "") + (scroll + visible < count ? "\u25BC " : "") + "scroll for more";
				text(more, x + W - PAD - font.width(more), y, DARK);
				y += LINE;
			}
			if (!missing.isEmpty()) {
				int my0 = y + 1;
				y = wrapped("To max it, get: " + String.join(", ", missing), x + PAD, y + 1, inner, 0xFFD8C890);
				List<Component> tip = new ArrayList<>();
				tip.add(Component.literal("Books you don't have (at max level) for what you ticked:"));
				for (String m : missing) tip.add(Component.literal("  " + m).withColor(0xD8C890));
				hits.add(new Hit("missing", x + PAD, my0, x + W - PAD, y, null, tip));
			}
			divider(y + 1);
			y += 4;

			// plan / progress
			if (running) {
				Analysis job = run.job();
				if (job != null && job.plan != null) {
					y = wrapped("Step " + Math.min(run.stepIndex() + 1, job.plan.steps.size()) + " of " + job.plan.steps.size() + " - " + job.target.label().getString(), x + PAD, y, inner, WHITE);
				}
			} else if (a != null && a.plan != null) {
				var p = a.plan;
				y = wrapped(p.steps.size() + " step" + (p.steps.size() == 1 ? "" : "s") + ", " + p.totalLevels + " levels total", x + PAD, y, inner, WHITE);
				y = wrapped("Priciest step: " + p.maxStep + " levels", x + PAD, y, inner, GRAY);
				if (!t.isBook()) {
					int onItem = 0;
					for (var s : p.steps) if (!s.piece.book) onItem++;
					if (p.booksFirst && p.steps.size() > 1) {
						y = wrapped("Books first: " + Integer.bitCount(p.bookMask) + " books into 1, then onto the item", x + PAD, y, inner, GRAY);
					} else if (AutoAnvil.CONFIG.combineBooksFirst && onItem > 1) {
						y = wrapped("Too much for one book (40+): goes on in " + onItem + " parts", x + PAD, y, inner, ORANGE);
					}
				}
				if (a.xpNeeded > 0) {
					long bottles = Math.round(Math.ceil(a.xpNeeded / Xp.POINTS_PER_BOTTLE));
					y = wrapped("Need ~" + a.xpNeeded + " XP (~" + bottles + " bottles)", x + PAD, y, inner, YELLOW);
				} else {
					y = wrapped("You have enough XP", x + PAD, y, inner, GREEN);
				}
				boolean dropped = false;
				for (Analysis.Row r : a.rows) dropped |= r.state() == Analysis.State.TOO_EXPENSIVE;
				if (dropped) y = wrapped("Not all fit: some are left out to avoid \"Too Expensive!\"", x + PAD, y, inner, ORANGE);
			} else if (a != null && a.problem != null) {
				y = wrapped(a.problem, x + PAD, y, inner, ORANGE);
			}
			y += 3;

			// the queue: items marked with the queue key, done in that order
			if (!ItemQueue.isEmpty()) {
				y = queueList(x, y, inner, running);
			}

			// buttons
			int bw = (inner - 4) / 2;
			if (running) {
				button("stop", "Stop", x + PAD, y, inner, true, AutoAnvil::stop, "Stops after putting anything in the anvil back", mx, my);
			} else {
				boolean can = a != null && a.plan != null;
				button("start", "Start", x + PAD, y, bw, can, () -> AutoAnvil.start(false),
						t.isBook() ? "Make one combined book" : "Enchant this item; waits for XP between steps", mx, my);
				if (!ItemQueue.isEmpty()) {
					button("queue", "Queue (" + ItemQueue.ENTRIES.size() + ")", x + PAD + bw + 4, y, bw, true, AutoAnvil::startQueue,
							"Enchant the queued items in order; items you queue while it runs are added", mx, my);
				} else if (t.isBook()) {
					button("all", "Make all", x + PAD + bw + 4, y, bw, can, () -> AutoAnvil.start(true),
							"Keep making combined books until the books run out", mx, my);
				} else {
					int n = AutoAnvil.includedItems().size();
					button("all", "All items (" + n + ")", x + PAD + bw + 4, y, bw, n > 0, () -> AutoAnvil.start(true),
							"Enchant every item ticked \"in All\", one after another", mx, my);
				}
			}
			y += 18;

			if (run != null && run.status != null) {
				y = wrapped(run.status, x + PAD, y, inner, run.statusColor);
			} else if (ItemQueue.isEmpty() && !t.isBook() && !AutoAnvil.queueKey.isUnbound()) {
				y = wrapped("Tip: hover items and press " + AutoAnvil.queueKey.getTranslatedKeyMessage().getString() + " to queue them",
						x + PAD, y, inner, DARK);
			}
		}
		y += PAD - 2;

		height = y - getY();
		int h = height;
		g.fill(x, getY(), x + W, getY() + h, BG);
		g.renderOutline(x, getY(), W, h, BORDER);
		for (Op op : ops) op.draw(g);
		for (Hit hit : hits) {
			if (hit.contains(mx, my) && !hit.tooltip().isEmpty()) g.setComponentTooltipForNextFrame(font, hit.tooltip(), mx, my);
		}
	}

	/** "Queue (3)  clear" and one line per queued item; click a line to take it out. */
	private int queueList(int x, int y, int inner, boolean running) {
		var inv = Minecraft.getInstance().player.getInventory();
		int n = ItemQueue.ENTRIES.size();
		text("Queue (" + n + ")", x + PAD, y, GOLD);
		String clear = "clear";
		int cx = x + W - PAD - font.width(clear);
		int cy = y;
		ops.add(g -> g.drawString(font, clear, cx, cy, 0xFFB0C8FF, false));
		hits.add(new Hit("queue:clear", cx - 2, cy - 1, x + W - PAD, cy + 9, () -> ItemQueue.ENTRIES.clear(),
				List.of(Component.literal("Empty the queue"))));
		y += LINE;
		int shownLines = Math.min(n, 4);
		for (int k = 0; k < shownLines; k++) {
			ItemQueue.Entry e = ItemQueue.ENTRIES.get(k);
			int idx = ItemQueue.locate(inv, e);
			String line = (k + 1) + ". " + e.stack().getHoverName().getString() + (idx >= 0 ? "  " + ItemQueue.where(idx) : "  (gone)");
			String cut = font.plainSubstrByWidth(line, inner);
			int ly = y, kk = k;
			ops.add(g -> g.drawString(font, cut, x + PAD, ly, idx >= 0 ? WHITE : RED, false));
			hits.add(new Hit("queue:" + (k + 1), x + PAD, ly - 1, x + W - PAD, ly + 9, () -> {
				if (kk < ItemQueue.ENTRIES.size()) ItemQueue.ENTRIES.remove(kk);
			}, List.of(Component.literal(e.stack().getHoverName().getString()), Component.literal("Click to take it out of the queue").withColor(0x777777))));
			y += LINE;
		}
		if (n > shownLines) {
			text("+" + (n - shownLines) + " more", x + PAD, y, DARK);
			y += LINE;
		}
		return y + 2;
	}

	private void tab(String id, String label, int x, int y, int w, boolean selected, Runnable action, String tip, int mx, int my) {
		boolean hover = !selected && mx >= x && mx < x + w && my >= y && my < y + 12;
		ops.add(g -> {
			g.fill(x, y, x + w, y + 12, selected ? 0xFF4A4030 : hover ? 0xFF3A3A3A : 0xFF242424);
			g.renderOutline(x, y, w, 12, selected ? 0xFF8A7050 : 0xFF161616);
			g.drawCenteredString(font, label, x + w / 2, y + 2, selected ? GOLD : GRAY);
		});
		hits.add(new Hit(id, x, y, x + w, y + 12, selected ? null : action, List.of(Component.literal(tip))));
	}

	/** One anvil step: "3. #1 + Mending   5" with the inputs and result in the tooltip; done/current while running. */
	private void stepRow(int i, Planner.Plan plan, Analysis job, Runner run, int x, int y, int mx, int my) {
		Planner.Node s = plan.steps.get(i);
		boolean done = run != null && i < run.stepIndex();
		boolean now = run != null && i == run.stepIndex();
		String num = (i + 1) + ".";
		String cost = s.cost + " lv";
		int numW = font.width("00.") + 2, costW = font.width(cost);
		int plusW = font.width(" + ");
		int side = (W - 2 * PAD - numW - costW - 4 - plusW) / 2;
		String l = font.plainSubstrByWidth(input(s.left, plan, job, true), side);
		String r = font.plainSubstrByWidth(input(s.right, plan, job, true), side);
		int color = done ? 0xFF6E9E6E : now ? YELLOW : WHITE;
		ops.add(g -> {
			if (now) g.fill(x + PAD - 2, y - 1, x + W - PAD + 2, y + ROW - 1, 0x30FFE066);
			g.drawString(font, done ? "\u2714" : now ? "\u25B6" : num, x + PAD, y + 1, done ? GREEN : now ? YELLOW : GRAY, false);
			g.drawString(font, l + " + " + r, x + PAD + numW, y + 1, color, false);
			g.drawString(font, cost, x + W - PAD - costW, y + 1, s.cost >= 30 ? ORANGE : GRAY, false);
		});
		List<Component> tip = new ArrayList<>();
		tip.add(Component.literal("Step " + (i + 1) + ": " + s.cost + " levels").withColor(0xFFAA00));
		tip.add(Component.literal("Left:  " + input(s.left, plan, job, false)));
		tip.add(Component.literal("Right: " + input(s.right, plan, job, false)));
		tip.add(Component.literal("Gives: " + (s.piece.book ? "book with " : "") + enchants(s.piece, job)).withColor(0x88FF88));
		tip.add(Component.literal("Prior-work penalty after: " + s.piece.repairCost).withColor(0x888888));
		hits.add(new Hit("step:" + (i + 1), x + PAD - 2, y - 1, x + W - PAD + 2, y + ROW - 1, null, tip));
	}

	private String input(Planner.Node n, Planner.Plan plan, Analysis job, boolean shortForm) {
		if (n.leaf == Planner.Node.ITEM) {
			if (shortForm) return dev.autoanvil.Catalog.shortItem(job.target.stack);
			String item = job.target.stack.getHoverName().getString();
			return n.piece.size() == 0 ? item : item + " (" + enchants(n.piece, job) + ")";
		}
		if (n.isStep()) {
			String ref = "#" + (plan.steps.indexOf(n) + 1);
			return shortForm ? ref : "result of step " + ref + " (" + enchants(n.piece, job) + ")";
		}
		if (shortForm) {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < n.piece.size(); k++) {
				if (k > 0) sb.append('+');
				sb.append(dev.autoanvil.Catalog.shortName(job.table.get(n.piece.id(k)), n.piece.levelAt(k)));
			}
			return sb.toString();
		}
		return "book: " + enchants(n.piece, job);
	}

	private static String enchants(dev.autoanvil.plan.Piece p, Analysis job) {
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < p.size(); k++) {
			if (k > 0) sb.append(", ");
			sb.append(name(job.table.get(p.id(k)), p.levelAt(k)));
		}
		return sb.length() == 0 ? "nothing" : sb.toString();
	}

	private void row(Analysis.Row r, int x, int y, boolean running, int mx, int my) {
		Holder<Enchantment> e = r.ench();
		int level = switch (r.state()) {
			case READY -> r.gets();
			case HAVE, TOO_EXPENSIVE -> Math.max(r.best(), r.have());
			case BLOCKED -> 0;
			case NO_BOOK -> e.value().getMaxLevel();
			case OFF -> r.have();
		};
		int nameColor, markColor;
		String mark;
		switch (r.state()) {
			case READY -> {
				nameColor = WHITE;
				mark = "✔";
				markColor = GREEN;
			}
			case HAVE -> {
				nameColor = GRAY;
				mark = "have";
				markColor = DARK;
			}
			case NO_BOOK -> {
				nameColor = DARK;
				mark = "no book";
				markColor = DARK;
			}
			case BLOCKED -> {
				nameColor = 0xFFC08080;
				mark = "✖";
				markColor = RED;
			}
			case TOO_EXPENSIVE -> {
				nameColor = 0xFFFFD0A0;
				mark = "40+";
				markColor = ORANGE;
			}
			default -> {
				nameColor = DARK;
				mark = r.note().isEmpty() ? "" : "book";
				markColor = DARK;
			}
		}
		boolean hover = !running && mx >= x + PAD && mx < x + W - PAD && my >= y && my < y + ROW;
		int markW = font.width(mark);
		String name = font.plainSubstrByWidth(name(e, level), W - 2 * PAD - 12 - markW - 4);
		boolean on = r.wanted();
		ops.add(g -> {
			if (hover) g.fill(x + PAD - 2, y - 1, x + W - PAD + 2, y + ROW - 1, 0x30FFFFFF);
			g.renderOutline(x + PAD, y + 1, 8, 8, on ? 0xFFD0D0D0 : 0xFF808080);
			if (on) g.fill(x + PAD + 2, y + 3, x + PAD + 6, y + 7, r.state() == Analysis.State.READY ? GREEN : GRAY);
			g.drawString(font, name, x + PAD + 12, y + 1, nameColor, false);
			g.drawString(font, mark, x + W - PAD - markW, y + 1, markColor, false);
		});
		List<Component> tip = new ArrayList<>();
		tip.add(Component.literal(name(e, level)));
		if (!r.note().isEmpty()) tip.add(Component.literal(r.note()).withColor(0xAAAAAA));
		tip.add(Component.literal(on ? "Click to untick" : "Click to tick").withColor(0x777777));
		hits.add(new Hit("ench:" + dev.autoanvil.Catalog.id(e), x + PAD - 2, y - 1, x + W - PAD + 2, y + ROW - 1,
				running ? null : () -> AutoAnvil.toggle(e), tip));
	}

	public static String name(Holder<Enchantment> e, int level) {
		String n = e.value().description().getString();
		if (level > 0 && (level != 1 || e.value().getMaxLevel() != 1)) n += " " + Component.translatable("enchantment.level." + level).getString();
		return n;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!covers(event.x(), event.y())) return false;
		if (event.button() != 0) return true;
		for (Hit h : hits) {
			if (h.contains(event.x(), event.y()) && h.action() != null) {
				playDownSound(Minecraft.getInstance().getSoundManager());
				h.action().run();
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (!covers(x, y) || y < rowsTop || y >= rowsBottom + LINE) return false;
		scroll -= (int) Math.signum(dy);
		if (scroll < 0) scroll = 0;
		return true;
	}

	@Override
	public boolean isMouseOver(double x, double y) {
		return covers(x, y);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		out.add(NarratedElementType.TITLE, Component.literal("Auto Anvil"));
	}

	/** Clicks the hit with this id as if the player had (self-test); false when it is not there or disabled. */
	public boolean press(String id) {
		for (Hit h : hits) {
			if (h.id().equals(id) && h.action() != null) {
				int cx = (h.x0() + h.x1()) / 2, cy = (h.y0() + h.y1()) / 2;
				MouseButtonEvent ev = new MouseButtonEvent(cx, cy, new net.minecraft.client.input.MouseButtonInfo(0, 0));
				boolean handled = screen.mouseClicked(ev, false);
				screen.mouseReleased(ev);
				return handled;
			}
		}
		return false;
	}
}
