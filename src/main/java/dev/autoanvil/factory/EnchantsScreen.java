package dev.autoanvil.factory;

import dev.autoanvil.compat.Gfx;
import dev.autoanvil.compat.Ui;
import dev.autoanvil.AutoAnvil;
import dev.autoanvil.Catalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * The enchantments one item gets: tick the ones you want. Shared with the anvil panel's ticks for that kind of item
 * (all swords, all shields...). The factory makes each at the best level a librarian in the hall sells.
 */
public final class EnchantsScreen extends Screen {
	private static final int ROW = 14, W = 330;
	private final Screen parent;
	private final String itemId;
	private final ItemStack sample;
	private final String kind;
	private final List<Holder<Enchantment>> applicable = new ArrayList<>();
	private int scroll;

	public EnchantsScreen(Screen parent, String itemId) {
		super(Component.literal("Enchantments"));
		this.parent = parent;
		this.itemId = itemId;
		this.sample = new ItemStack(Kit.item(itemId));
		this.kind = Catalog.itemKind(sample);
	}

	private int visible() {
		return Math.max(1, (height - 44 - 40) / ROW);
	}

	@Override
	protected void init() {
		applicable.clear();
		for (Holder<Enchantment> e : Catalog.all(minecraft.level.registryAccess())) if (e.value().canEnchant(sample)) applicable.add(e);
		applicable.sort(Catalog.DISPLAY);
		scroll = Math.max(0, Math.min(scroll, applicable.size() - visible()));
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
	}

	/** Whether the item gets this enchantment: your tick, else the default (exclusive ones settled by importance). */
	public boolean isOn(Holder<Enchantment> e) {
		return Catalog.wanted(kind, applicable, AutoAnvil.CONFIG.profiles.get(kind)).contains(e);
	}

	public boolean isOn(String enchantId) {
		for (Holder<Enchantment> e : applicable) if (Catalog.id(e).equals(enchantId)) return isOn(e);
		return false;
	}

	/** Screen position of an enchantment's tick box (for the self-test), or null if it's scrolled out of view. */
	public double[] boxAt(String enchantId) {
		int left = (width - W) / 2;
		for (int i = scroll; i < Math.min(applicable.size(), scroll + visible()); i++) {
			if (Catalog.id(applicable.get(i)).equals(enchantId)) return new double[] {left + 8.5, 44 + (i - scroll) * ROW + 4.5};
		}
		return null;
	}

	/** Tick or untick; ticking one unticks the ones it can't go with (Fortune when you tick Silk Touch). */
	void toggle(Holder<Enchantment> e) {
		Map<String, Boolean> profile = AutoAnvil.CONFIG.profile(kind);
		boolean now = isOn(e);
		profile.put(Catalog.id(e), !now);
		if (!now) {
			for (Holder<Enchantment> o : applicable) if (o != e && !Enchantment.areCompatible(e, o)) profile.put(Catalog.id(o), false);
		}
		AutoAnvil.CONFIG.save();
	}

	@Override
	//#if MC >= 26.2
	//$$ public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
	//$$ 	super.extractRenderState(graphics, mouseX, mouseY, delta);
	//#else
	public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
	//#endif
		Gfx g = new Gfx(graphics);
		int left = (width - W) / 2;
		g.renderItem(sample, left, 8);
		g.drawString(font, sample.getHoverName().getString() + " - tick what it gets (shared with all " + kind.replace('_', ' ') + "s)", left + 20, 12, 0xFFFFFFFF, false);
		g.drawString(font, "enchantment", left + 18, 32, 0xFF888888, false);
		g.drawString(font, "best a librarian here sells", left + 160, 32, 0xFF888888, false);
		for (int i = scroll; i < Math.min(applicable.size(), scroll + visible()); i++) {
			Holder<Enchantment> e = applicable.get(i);
			int y = 44 + (i - scroll) * ROW;
			boolean on = isOn(e);
			g.renderOutline(left + 4, y, 9, 9, 0xFFB0B0B0);
			if (on) g.fill(left + 6, y + 2, left + 11, y + 7, 0xFF55FF55);
			int sold = TradeBook.get().bestLevel(e);
			g.drawString(font, e.value().description().getString(), left + 18, y + 1, on ? 0xFFFFFFFF : 0xFF888888, false);
			String where = sold > 0 ? dev.autoanvil.ui.Panel.name(e, Math.min(sold, e.value().getMaxLevel())) : "not sold here";
			g.drawString(font, where, left + 160, y + 1, sold > 0 ? (on ? 0xFF55FF55 : 0xFF888888) : (on ? 0xFFFF7755 : 0xFF666666), false);
		}
		if (applicable.isEmpty()) g.drawCenteredString(font, "Nothing can enchant this item.", width / 2, 60, 0xFFAAAAAA);
		if (applicable.size() > visible()) g.drawString(font, "scroll for more", left + W - 70, height - 40, 0xFF777777, false);
		g.drawCenteredString(font, "Ticked but not sold here: the factory leaves it off.", width / 2, height - 44 + (applicable.size() > visible() ? -12 : 0), 0xFF999999);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int left = (width - W) / 2;
		for (int i = scroll; i < Math.min(applicable.size(), scroll + visible()); i++) {
			int y = 44 + (i - scroll) * ROW;
			if (event.x() >= left + 2 && event.x() < left + W && event.y() >= y && event.y() < y + ROW - 2) {
				toggle(applicable.get(i));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, Math.min(applicable.size() - visible(), scroll - (int) Math.signum(dy) * 3));
		return true;
	}

	@Override
	public void onClose() {
		AutoAnvil.CONFIG.save();
		Ui.setScreen(minecraft, parent);
	}
}
