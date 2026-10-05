package dev.autoanvil.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * What the factory makes: any enchantable item, how many, where it comes from (bought from a villager, crafted, or
 * taken ready-made from the input chest) and which enchantments it gets.
 */
public final class ItemsScreen extends Screen {
	private static final int ROW = 22, W = 372;
	private final Screen parent;
	private int scroll;
	private String note = "";
	private final List<String> ids = new ArrayList<>();
	private final Map<String, EditBox> amounts = new HashMap<>();
	private final Map<String, Button> sources = new HashMap<>();
	private final Map<String, Button> enchants = new HashMap<>();

	public ItemsScreen(Screen parent) {
		super(Component.literal("Kit Factory - items"));
		this.parent = parent;
	}

	private int visible() {
		return Math.max(1, (height - 36 - 58) / ROW);
	}

	private static String label(String source) {
		return switch (source) {
			case "buy" -> "Buy";
			case "craft" -> "Craft";
			default -> "Chest";
		};
	}

	@Override
	protected void init() {
		FactoryConfig cfg = Factory.cfg;
		ids.clear();
		ids.addAll(cfg.targets.keySet());
		amounts.clear();
		sources.clear();
		enchants.clear();
		int left = (width - W) / 2, top = 36;
		scroll = Math.max(0, Math.min(scroll, ids.size() - visible()));
		for (int i = scroll; i < Math.min(ids.size(), scroll + visible()); i++) {
			String id = ids.get(i);
			int y = top + (i - scroll) * ROW;
			EditBox box = new EditBox(font, left + 132, y + 2, 34, 16, Component.literal("amount"));
			box.setValue(String.valueOf(cfg.targets.get(id)));
			box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
			box.setResponder(s -> cfg.targets.put(id, s.isEmpty() ? 0 : Integer.parseInt(s)));
			addRenderableWidget(box);
			amounts.put(id, box);
			Button how = Button.builder(Component.literal(label(cfg.source(id))), b -> {
				String now = cfg.source(id);
				boolean craftable = Kit.recipe(Kit.item(id)) != null;
				String next = switch (now) {
					case "buy" -> craftable ? "craft" : "take";
					case "craft" -> "take";
					default -> "buy";
				};
				cfg.setSource(id, next);
				b.setMessage(Component.literal(label(cfg.source(id))));
			}).bounds(left + 170, y + 1, 46, 18)
					.tooltip(Tooltip.create(Component.literal("Buy: traded from a villager.\nCraft: at the crafting table, from materials in the input chest.\n"
							+ "Chest: taken ready-made from the input chest, only enchanted here.")))
					.build();
			addRenderableWidget(how);
			sources.put(id, how);
			Button ench = Button.builder(Component.literal("Enchants"), b -> minecraft.setScreen(new EnchantsScreen(this, id)))
					.bounds(left + 220, y + 1, 58, 18).build();
			addRenderableWidget(ench);
			enchants.put(id, ench);
			addRenderableWidget(Button.builder(Component.literal("x"), b -> {
				cfg.targets.remove(id);
				cfg.setSource(id, "craft");
				rebuildWidgets();
			}).bounds(left + 282, y + 1, 16, 18).tooltip(Tooltip.create(Component.literal("Stop making this item"))).build());
		}
		int by = height - 28;
		addRenderableWidget(Button.builder(Component.literal("Add held item"), b -> {
			ItemStack held = minecraft.player.getMainHandItem();
			String id = held.isEmpty() ? null : FactoryConfig.resolve(Kit.id(held.getItem()));
			if (id == null) {
				note = held.isEmpty() ? "Hold the item you want to add." : held.getHoverName().getString() + " can't be enchanted.";
			} else if (cfg.targets.containsKey(id)) {
				note = held.getHoverName().getString() + " is already on the list.";
			} else {
				cfg.targets.put(id, 27);
				cfg.setSource(id, cfg.defaultSource(id));
				note = "Added " + held.getHoverName().getString() + ": set how many, where from, and its enchantments.";
				scroll = Math.max(0, cfg.targets.size() - visible());
			}
			rebuildWidgets();
		}).bounds(width / 2 - 160, by, 100, 20).tooltip(Tooltip.create(Component.literal("Or type /kitfactory add <item> [count]"))).build());
		addRenderableWidget(Button.builder(Component.literal("All to 27"), b -> {
			cfg.targets.replaceAll((k, v) -> 27);
			rebuildWidgets();
		}).bounds(width / 2 - 50, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 60, by, 100, 20).build());
	}

	/** The amount box of an item (null when scrolled out of view). */
	public EditBox amountBox(String id) {
		return amounts.get(id);
	}

	/** The Buy / Craft / Chest button of an item (null when scrolled out of view). */
	public Button sourceButton(String id) {
		return sources.get(id);
	}

	/** The Enchants button of an item (null when scrolled out of view). */
	public Button enchantsButton(String id) {
		return enchants.get(id);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		FactoryConfig cfg = Factory.cfg;
		int left = (width - W) / 2, top = 36;
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		g.drawString(font, "item", left + 20, 24, 0xFF888888, false);
		g.drawString(font, "amount", left + 132, 24, 0xFF888888, false);
		g.drawString(font, "from", left + 170, 24, 0xFF888888, false);
		g.drawString(font, "done", left + 304, 24, 0xFF888888, false);
		for (int i = scroll; i < Math.min(ids.size(), scroll + visible()); i++) {
			String id = ids.get(i);
			int y = top + (i - scroll) * ROW;
			ItemStack stack = new ItemStack(Kit.item(id));
			g.renderItem(stack, left, y + 3);
			int c = cfg.targets.getOrDefault(id, 0) > 0 ? 0xFFFFFFFF : 0xFF777777;
			g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), 108), left + 20, y + 7, c, false);
			g.drawString(font, cfg.done.getOrDefault(id, 0) + "/" + cfg.targets.getOrDefault(id, 0), left + 304, y + 7, 0xFFAAAAAA, false);
		}
		if (ids.isEmpty()) g.drawCenteredString(font, "Nothing on the list: hold an item and click Add held item.", width / 2, 60, 0xFFAAAAAA);
		g.drawCenteredString(font, note.isEmpty() ? "Buy = from a villager. Craft = from materials in the input chest. Chest = ready-made from the input chest." : note,
				width / 2, height - 44, note.isEmpty() ? 0xFF999999 : 0xFFFFFF55);
		if (ids.size() > visible()) g.drawString(font, "scroll for more", left + W - 70, height - 56, 0xFF777777, false);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy));
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		Factory.cfg.save();
		minecraft.setScreen(parent);
	}
}
