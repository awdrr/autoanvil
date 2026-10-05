package dev.autoanvil.factory;

import dev.autoanvil.compat.Gfx;
import dev.autoanvil.compat.Ui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Everything the survey found, per villager: what it sells, for what. Click a price to change it (the factory plans
 * with it and picks the cheapest villager for each book); untick a trade to never use it.
 */
public final class TradesScreen extends Screen {
	private static final int ROW = 14, W = 340;
	private final Screen parent;
	private int scroll;

	/** One line: a villager header (offer == null) or one of its trades. */
	private record Line(TradeBook.Trader trader, TradeBook.Offer offer) {
	}

	private final List<Line> lines = new ArrayList<>();
	private final List<int[]> checkboxes = new ArrayList<>(); // x, y, line index

	public TradesScreen(Screen parent) {
		super(Component.literal("Kit Factory - trades"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		lines.clear();
		for (TradeBook.Trader t : TradeBook.get().traders) {
			lines.add(new Line(t, null));
			for (TradeBook.Offer o : t.offers) lines.add(new Line(t, o));
		}
		checkboxes.clear();
		int left = (width - W) / 2, top = 32;
		int visible = Math.max(1, (height - top - 40) / ROW);
		scroll = Math.max(0, Math.min(scroll, lines.size() - visible));
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			Line l = lines.get(i);
			if (l.offer == null) continue;
			int y = top + (i - scroll) * ROW;
			TradeBook.Offer o = l.offer;
			EditBox box = new EditBox(font, left + 190, y - 1, 34, 12, Component.literal("price"));
			box.setValue(String.valueOf(o.price));
			Ui.digitsOnly(box, s -> {
				if (s.isEmpty()) return;
				int v = Integer.parseInt(s);
				if (v != o.price) {
					o.price = v;
					o.manual = true;
				}
			});
			addRenderableWidget(box);
			checkboxes.add(new int[] {left + 4, y, i});
		}
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
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
		int left = (width - W) / 2, top = 32;
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		if (lines.isEmpty()) {
			g.drawCenteredString(font, "Nothing recorded yet: run /kitfactory survey in the hall.", width / 2, 60, 0xFFAAAAAA);
			return;
		}
		g.drawString(font, "use", left, 22, 0xFF888888, false);
		g.drawString(font, "trade", left + 18, 22, 0xFF888888, false);
		g.drawString(font, "price", left + 190, 22, 0xFF888888, false);
		int visible = Math.max(1, (height - top - 40) / ROW);
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			Line l = lines.get(i);
			int y = top + (i - scroll) * ROW;
			if (l.offer == null) {
				g.drawString(font, l.trader.label(), left, y + 1, 0xFFFFAA00, false);
				continue;
			}
			TradeBook.Offer o = l.offer;
			g.renderOutline(left + 4, y, 9, 9, 0xFFB0B0B0);
			if (o.enabled) g.fill(left + 6, y + 2, left + 11, y + 7, 0xFF55FF55);
			int c = o.enabled ? 0xFFFFFFFF : 0xFF777777;
			g.drawString(font, font.plainSubstrByWidth(o.what(), 165), left + 18, y + 1, c, false);
			String rest = TradeBook.itemName(o.costA) + (o.costB.isEmpty() ? "" : " + " + o.costBCount + " " + TradeBook.itemName(o.costB));
			g.drawString(font, font.plainSubstrByWidth(rest, 110), left + 228, y + 1, c, false);
			if (o.manual) g.drawString(font, "edited", left + W - 30, y + 1, 0xFF8888FF, false);
		}
		if (lines.size() > visible) g.drawString(font, "scroll for more", left + W - 70, height - 40, 0xFF777777, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int[] cb : checkboxes) {
			if (event.x() >= cb[0] && event.x() < cb[0] + 9 && event.y() >= cb[1] && event.y() < cb[1] + 9) {
				TradeBook.Offer o = lines.get(cb[2]).offer;
				o.enabled = !o.enabled;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		TradeBook.get().save();
		Ui.setScreen(minecraft, parent);
	}
}
