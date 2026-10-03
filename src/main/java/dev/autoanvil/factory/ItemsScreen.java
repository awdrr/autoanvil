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

/** What the factory makes: how many of each item, and whether it's bought from a villager or crafted from the chest. */
public final class ItemsScreen extends Screen {
	private static final int ROW = 22, W = 300;
	private final Screen parent;
	private int scroll;
	private final List<String> ids = new ArrayList<>();
	private final Map<String, EditBox> amounts = new HashMap<>();
	private final Map<String, Button> sources = new HashMap<>();

	public ItemsScreen(Screen parent) {
		super(Component.literal("Kit Factory - items"));
		this.parent = parent;
	}

	private int visible() {
		return Math.max(1, (height - 36 - 58) / ROW);
	}

	@Override
	protected void init() {
		FactoryConfig cfg = Factory.cfg;
		ids.clear();
		ids.addAll(cfg.targets.keySet());
		amounts.clear();
		sources.clear();
		int left = (width - W) / 2, top = 36;
		scroll = Math.max(0, Math.min(scroll, ids.size() - visible()));
		for (int i = scroll; i < Math.min(ids.size(), scroll + visible()); i++) {
			String id = ids.get(i);
			int y = top + (i - scroll) * ROW;
			EditBox box = new EditBox(font, left + 140, y + 2, 36, 16, Component.literal("amount"));
			box.setValue(String.valueOf(cfg.targets.get(id)));
			box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,4}"));
			box.setResponder(s -> cfg.targets.put(id, s.isEmpty() ? 0 : Integer.parseInt(s)));
			addRenderableWidget(box);
			amounts.put(id, box);
			Button how = Button.builder(source(id), b -> {
				if (!cfg.buy.remove(id)) cfg.buy.add(id);
				b.setMessage(source(id));
			}).bounds(left + 184, y + 1, 70, 18)
					.tooltip(Tooltip.create(Component.literal("Buy: traded from a villager, never crafted.\nCraft: from the diamonds and sticks in the input chest.")))
					.build();
			addRenderableWidget(how);
			sources.put(id, how);
		}
		int by = height - 28;
		addRenderableWidget(Button.builder(Component.literal("All to 27"), b -> {
			cfg.targets.replaceAll((k, v) -> 27);
			rebuildWidgets();
		}).bounds(width / 2 - 154, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 54, by, 100, 20).build());
	}

	private Component source(String id) {
		return Component.literal(Factory.cfg.buy.contains(id) ? "Buy" : "Craft");
	}

	/** The amount box of an item (null when scrolled out of view). */
	public EditBox amountBox(String id) {
		return amounts.get(id);
	}

	/** The Buy / Craft button of an item (null when scrolled out of view). */
	public Button sourceButton(String id) {
		return sources.get(id);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		FactoryConfig cfg = Factory.cfg;
		int left = (width - W) / 2, top = 36;
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		g.drawString(font, "item", left + 20, 24, 0xFF888888, false);
		g.drawString(font, "amount", left + 140, 24, 0xFF888888, false);
		g.drawString(font, "how", left + 184, 24, 0xFF888888, false);
		g.drawString(font, "done", left + 262, 24, 0xFF888888, false);
		for (int i = scroll; i < Math.min(ids.size(), scroll + visible()); i++) {
			String id = ids.get(i);
			int y = top + (i - scroll) * ROW;
			ItemStack stack = new ItemStack(Kit.item(id));
			g.renderItem(stack, left, y + 3);
			int c = cfg.targets.getOrDefault(id, 0) > 0 ? 0xFFFFFFFF : 0xFF777777;
			g.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), 115), left + 20, y + 7, c, false);
			g.drawString(font, cfg.done.getOrDefault(id, 0) + "/" + cfg.targets.getOrDefault(id, 0), left + 262, y + 7, 0xFFAAAAAA, false);
		}
		g.drawCenteredString(font, "Buy = traded from a villager. Craft = from diamonds + sticks in the input chest.", width / 2, height - 44, 0xFF999999);
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
