package dev.autoanvil;

import dev.autoanvil.compat.Ui;
import com.mojang.blaze3d.platform.InputConstants;
import dev.autoanvil.plan.Planner;
import dev.autoanvil.run.Analysis;
import dev.autoanvil.run.ItemQueue;
import dev.autoanvil.run.Runner;
import dev.autoanvil.run.Target;
import dev.autoanvil.ui.Panel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Anvil: a panel beside the anvil that combines the enchanted books in your inventory onto an item, or into one
 * book, in the cheapest order ({@link Planner}), and does the clicking, waiting for XP between steps ({@link Runner}).
 */
public final class AutoAnvil implements ClientModInitializer {
	public static final String MOD_ID = "autoanvil";
	public static final Logger LOGGER = LoggerFactory.getLogger("Auto Anvil");
	public static Config CONFIG = new Config();

	/** Combined books made this session: never used as ingredients for the next combined book. */
	public static final List<ItemStack> PRODUCTS = new ArrayList<>();

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	/** Hover an item in any inventory screen and press it to queue the item (again to unqueue); in the world: held item. */
	public static KeyMapping queueKey;

	public static AnvilScreen screen;
	public static List<Target> targets = List.of();
	public static int selected;
	public static Analysis analysis;
	public static Runner runner;
	/** "Start all" choices per item (by slot and contents); default: everything but wood/stone/iron/gold/... */
	private static final Map<String, Boolean> INCLUDE = new HashMap<>();
	private static String selectedKey;
	private static long signature = Long.MIN_VALUE;
	private static boolean dirty = true;

	@Override
	public void onInitializeClient() {
		CONFIG = Config.load();
		dev.autoanvil.factory.Factory.init();
		dev.autoanvil.factory.FactoryCommands.register();
		dev.autoanvil.factory.Factory.toggleKey = Ui.registerKey(new KeyMapping("key.autoanvil.factory",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "factory"), (graphics, delta) -> {
			dev.autoanvil.compat.Gfx g = new dev.autoanvil.compat.Gfx(graphics);
			Minecraft m = Minecraft.getInstance();
			if (m.player == null) return;
			int y = 4;
			for (String line : dev.autoanvil.factory.Factory.hudLines(m)) {
				g.drawString(m.font, line, 4, y, 0xFFFFFFFF, true);
				y += 10;
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(m -> {
			dev.autoanvil.factory.Factory.tick(m);
			if (dev.autoanvil.factory.FactoryCommands.openNextTick != null && Ui.screen(m) == null) {
				var open = dev.autoanvil.factory.FactoryCommands.openNextTick;
				dev.autoanvil.factory.FactoryCommands.openNextTick = null;
				Ui.setScreen(m, open.get());
			}
		});
		queueKey = Ui.registerKey(new KeyMapping("key.autoanvil.queue", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ItemQueue.ENTRIES::clear));
		ScreenEvents.AFTER_INIT.register((client, s, w, h) -> {
			// Esc doesn't stop the Kit Factory (it keeps going under the pause menu): this does
			if (s instanceof net.minecraft.client.gui.screens.PauseScreen && dev.autoanvil.factory.Factory.running()) {
				Ui.widgets(s).add(net.minecraft.client.gui.components.Button.builder(Component.literal("Stop Kit Factory"), b -> {
					dev.autoanvil.factory.Factory.stop("Stopped.");
					b.visible = false;
				}).bounds(w / 2 - 60, 6, 120, 20).build());
			}
			if (s instanceof AbstractContainerScreen<?> cs) {
				ScreenKeyboardEvents.allowKeyPress(cs).register((scr, key) -> !(queueKey.matches(key) && queueHovered(cs)));
				ScreenMouseEvents.allowMouseClick(cs).register((scr, click) -> !(queueKey.matchesMouse(click) && queueHovered(cs)));
			}
			if (!(s instanceof AnvilScreen anvil)) return;
			if (screen != anvil) {
				screen = anvil;
				dirty = true;
			}
			Ui.widgets(anvil).add(new Panel(anvil));
		});
		ClientTickEvents.END_CLIENT_TICK.register(AutoAnvil::tick);
		SelfTest.registerIfRequested();
	}

	public static void chat(Component msg) {
		Minecraft mc = Minecraft.getInstance();
		if (CONFIG.chatMessages && mc.player != null) {
			Ui.message(mc.player, Component.literal("[Auto Anvil] ").withStyle(ChatFormatting.GOLD).append(msg.copy().withStyle(ChatFormatting.GRAY)), false);
		}
		LOGGER.info(msg.getString());
	}

	public static boolean running() {
		return runner != null && !runner.finished();
	}

	/** The queue key over a slot of your inventory: queue / unqueue that item. False (key passes on) over anything else. */
	private static boolean queueHovered(AbstractContainerScreen<?> s) {
		Minecraft mc = Minecraft.getInstance();
		Slot slot = s.hoveredSlot;
		if (mc.player == null || slot == null || !slot.hasItem() || slot.container != mc.player.getInventory()) return false;
		actionBar(ItemQueue.toggle(mc.player.getInventory(), slot.getContainerSlot()));
		dirty = true;
		return true;
	}

	/** Gold queue number in the corner of a queued item's slot (called from the slot renderer, slot-local coords). */
	public static void drawQueueNumber(dev.autoanvil.compat.Gfx g, Slot slot) {
		Minecraft mc = Minecraft.getInstance();
		if (ItemQueue.isEmpty() || mc.player == null || slot.container != mc.player.getInventory() || !slot.hasItem()) return;
		int k = ItemQueue.indexOf(mc.player.getInventory(), slot.getContainerSlot());
		if (k < 0) return;
		g.drawString(mc.font, String.valueOf(k + 1), slot.x, slot.y, 0xFFFFAA00, true);
	}

	private static void actionBar(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) Ui.message(mc.player, Component.literal(msg).withStyle(ChatFormatting.GOLD), true);
	}

	private static void tick(Minecraft mc) {
		if (mc.player != null) {
			// in the world: the queue key queues the item in your hand
			while (queueKey.consumeClick()) {
				actionBar(ItemQueue.toggle(mc.player.getInventory(), mc.player.getInventory().getSelectedSlot()));
				dirty = true;
			}
			if (!ItemQueue.isEmpty()) ItemQueue.follow(mc.player.getInventory());
		}
		if (!(Ui.screen(mc) instanceof AnvilScreen anvil) || mc.player == null || mc.level == null) {
			if (screen != null) {
				if (running()) runner.closed();
				screen = null;
				dirty = true;
			}
			return;
		}
		if (anvil != screen) {
			screen = anvil;
			dirty = true;
		}
		AnvilMenu menu = anvil.getMenu();
		if (mc.player.containerMenu != menu) return;
		if (running()) {
			runner.tick(mc, menu, PRODUCTS);
			if (runner.finished()) dirty = true;
			return;
		}
		long sig = signature(mc, menu);
		if (dirty || sig != signature) {
			signature = sig;
			dirty = false;
			refresh(mc, menu);
		}
	}

	private static long signature(Minecraft mc, AnvilMenu menu) {
		long h = 17;
		for (int i = 0; i < Analysis.INV_END; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			h = h * 31 + (s.isEmpty() ? 0 : ItemStack.hashItemAndComponents(s) * 7L + s.getCount());
		}
		h = h * 31 + mc.player.experienceLevel;
		h = h * 31 + Float.floatToIntBits(mc.player.experienceProgress);
		h = h * 31 + (mc.player.hasInfiniteMaterials() ? 1 : 0);
		h = h * 31 + ItemQueue.ENTRIES.hashCode();
		return h;
	}

	public static void markDirty() {
		dirty = true;
	}

	public static Analysis analyze(Minecraft mc, AnvilMenu menu, Target t) {
		Planner.Objective obj = "levels".equalsIgnoreCase(CONFIG.optimizeFor) ? Planner.Objective.LEVELS : Planner.Objective.XP;
		return Analysis.of(mc.level.registryAccess(), mc.player, menu, t, CONFIG.profiles.get(t.profileKey()), obj, CONFIG.combineBooksFirst, PRODUCTS);
	}

	private static void refresh(Minecraft mc, AnvilMenu menu) {
		targets = scanTargets(mc, menu);
		int idx = -1;
		for (int i = 0; i < targets.size(); i++) if (targets.get(i).key().equals(selectedKey)) idx = i;
		if (idx < 0 && selectedKey != null && selectedKey.startsWith("item:")) {
			// the item moved or changed (e.g. just enchanted): stay on the first item rather than jumping to books
			for (int i = 0; i < targets.size() && idx < 0; i++) if (!targets.get(i).isBook()) idx = i;
		}
		selected = Math.max(0, idx);
		if (targets.isEmpty()) {
			analysis = null;
			return;
		}
		Target t = targets.get(selected);
		selectedKey = t.key();
		analysis = analyze(mc, menu, t);
	}

	/** Items a book in the inventory could enchant (best gear first), then the kinds of combined book you could make. */
	static List<Target> scanTargets(Minecraft mc, AnvilMenu menu) {
		List<Holder<Enchantment>> all = Catalog.all(mc.level.registryAccess());
		List<ItemStack> books = new ArrayList<>();
		for (int slot : Analysis.scanSlots()) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (Analysis.isBook(s)) books.add(s);
		}
		List<Target> items = new ArrayList<>();
		for (int slot : Analysis.scanSlots()) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (s.isEmpty() || s.getCount() != 1 || s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK)) continue;
			boolean any = false;
			for (ItemStack b : books) {
				for (Holder<Enchantment> e : Analysis.enchantments(b).keySet()) if (e.value().canEnchant(s)) any = true;
			}
			if (any) items.add(Target.item(slot, s));
		}
		items.sort(Comparator.comparingInt((Target t) -> Catalog.tierRank(t.stack)).thenComparingInt(t -> t.slot < 3 ? -1 : t.slot));
		List<Target> out = new ArrayList<>(items);

		// A combined-book kind is offered when 2+ books fit it and one of them is specific to it (not just
		// Mending/Unbreaking, which fit everything).
		List<Catalog.BookKind> kinds = Catalog.BOOK_KINDS;
		boolean anySpecific = false;
		for (Catalog.BookKind k : kinds) {
			int fit = 0;
			boolean specific = false;
			for (ItemStack b : books) {
				if (isProductBook(b)) continue;
				boolean fits = true, spec = false;
				for (Holder<Enchantment> e : Analysis.enchantments(b).keySet()) {
					if (!k.accepts(e)) fits = false;
					else if (!universal(e)) spec = true;
				}
				if (fits) {
					fit++;
					specific |= spec;
				}
			}
			if (fit >= 2 && specific) {
				out.add(Target.book(k));
				anySpecific = true;
			}
		}
		if (!anySpecific) {
			int universalBooks = 0;
			for (ItemStack b : books) {
				boolean u = !isProductBook(b);
				for (Holder<Enchantment> e : Analysis.enchantments(b).keySet()) u &= universal(e);
				if (u) universalBooks++;
			}
			if (universalBooks >= 2) out.add(Target.book(Catalog.ANY_BOOK));
		}
		return out;
	}

	private static boolean isProductBook(ItemStack b) {
		return Analysis.isProduct(b, PRODUCTS);
	}

	private static boolean universal(Holder<Enchantment> e) {
		for (Catalog.BookKind k : Catalog.BOOK_KINDS) if (!k.accepts(e)) return false;
		return true;
	}

	// ---- panel actions ----

	public static Target current() {
		return targets.isEmpty() ? null : targets.get(Math.min(selected, targets.size() - 1));
	}

	public static void cycle(int dir) {
		if (targets.isEmpty() || running()) return;
		if (runner != null && runner.finished()) runner = null; // the last run's message belongs to the last target
		selected = Math.floorMod(selected + dir, targets.size());
		selectedKey = targets.get(selected).key();
		dirty = true;
	}

	/** Tick or untick an enchantment for the current target's kind; ticking one unticks those exclusive with it. */
	public static void toggle(Holder<Enchantment> e) {
		Target t = current();
		if (t == null || running()) return;
		if (runner != null && runner.finished()) runner = null;
		Map<String, Boolean> profile = CONFIG.profile(t.profileKey());
		boolean now = false;
		if (analysis != null) for (Analysis.Row r : analysis.rows) if (r.ench().equals(e)) now = r.wanted();
		profile.put(Catalog.id(e), !now);
		if (!now && analysis != null) {
			for (Analysis.Row r : analysis.rows) {
				if (r.wanted() && !r.ench().equals(e) && !Enchantment.areCompatible(e, r.ench())) profile.put(Catalog.id(r.ench()), false);
			}
		}
		CONFIG.save();
		dirty = true;
	}

	public static boolean included(Target t) {
		if (t.isBook()) return false;
		return INCLUDE.getOrDefault(t.key(), !Catalog.lowTier(t.stack));
	}

	public static void toggleInclude() {
		Target t = current();
		if (t == null || t.isBook()) return;
		INCLUDE.put(t.key(), !included(t));
	}

	/** The item's name, plus "#2", "#3"... when several targets share it (three Netherite Swords). */
	public static String displayName(Target t) {
		String name = t.label().getString();
		if (t.isBook()) return name;
		int same = 0, index = 0;
		for (Target o : targets) {
			if (o.isBook() || !o.label().getString().equals(name)) continue;
			same++;
			if (o.sameAs(t)) index = same;
		}
		return same > 1 && index > 0 ? name + " #" + index : name;
	}

	/** "Hotbar slot 3", "Inventory row 2, slot 5", "In the anvil". */
	public static String where(Target t) {
		if (t.isBook()) return "";
		if (t.slot < Analysis.INV_START) return "In the anvil";
		if (t.slot >= 30) return "Hotbar slot " + (t.slot - 29);
		int i = t.slot - Analysis.INV_START;
		return "Inventory row " + (i / 9 + 1) + ", slot " + (i % 9 + 1);
	}

	public static List<Target> includedItems() {
		List<Target> out = new ArrayList<>();
		for (Target t : targets) if (included(t)) out.add(t);
		return out;
	}

	public static void start(boolean all) {
		Target t = current();
		if (t == null || running() || screen == null) return;
		List<Target> items = includedItems();
		if (all && !t.isBook() && items.isEmpty()) return;
		Target first = all && !t.isBook() ? items.get(0) : t;
		runner = new Runner(first, all, items);
	}

	/** Steps tab: cheapest order vs all books into one book first. */
	public static void toggleOrder() {
		if (running()) return;
		CONFIG.combineBooksFirst = !CONFIG.combineBooksFirst;
		CONFIG.save();
		dirty = true;
	}

	/** Work through the queue, in the order the items were marked. */
	public static void startQueue() {
		if (running() || screen == null || ItemQueue.isEmpty()) return;
		runner = Runner.forQueue();
	}

	public static void stop() {
		if (running()) runner.stop("Stopped by you");
	}
}
