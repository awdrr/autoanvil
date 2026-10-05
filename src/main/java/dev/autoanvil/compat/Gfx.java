package dev.autoanvil.compat;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
//#if MC >= 26.2
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif

/**
 * Drawing with the same calls on every version: 1.21.11 draws through {@code GuiGraphics}, 26.2 through
 * {@code GuiGraphicsExtractor} with renamed methods (text, centeredText, outline, item).
 */
public final class Gfx {
	//#if MC >= 26.2
	//$$ public final GuiGraphicsExtractor g;
	//$$
	//$$ public Gfx(GuiGraphicsExtractor g) {
	//$$ 	this.g = g;
	//$$ }
	//#else
	public final GuiGraphics g;

	public Gfx(GuiGraphics g) {
		this.g = g;
	}
	//#endif

	public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
		//#if MC >= 26.2
		//$$ g.text(font, text, x, y, color, shadow);
		//#else
		g.drawString(font, text, x, y, color, shadow);
		//#endif
	}

	public void drawString(Font font, String text, int x, int y, int color) {
		drawString(font, text, x, y, color, true);
	}

	public void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
		//#if MC >= 26.2
		//$$ g.text(font, text, x, y, color, shadow);
		//#else
		g.drawString(font, text, x, y, color, shadow);
		//#endif
	}

	public void drawString(Font font, Component text, int x, int y, int color) {
		drawString(font, text, x, y, color, true);
	}

	public void drawString(Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		//#if MC >= 26.2
		//$$ g.text(font, text, x, y, color, shadow);
		//#else
		g.drawString(font, text, x, y, color, shadow);
		//#endif
	}

	public void drawCenteredString(Font font, String text, int x, int y, int color) {
		//#if MC >= 26.2
		//$$ g.centeredText(font, text, x, y, color);
		//#else
		g.drawCenteredString(font, text, x, y, color);
		//#endif
	}

	public void drawCenteredString(Font font, Component text, int x, int y, int color) {
		//#if MC >= 26.2
		//$$ g.centeredText(font, text, x, y, color);
		//#else
		g.drawCenteredString(font, text, x, y, color);
		//#endif
	}

	public void fill(int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y1, color);
	}

	public void renderOutline(int x, int y, int w, int h, int color) {
		//#if MC >= 26.2
		//$$ g.outline(x, y, w, h, color);
		//#else
		g.renderOutline(x, y, w, h, color);
		//#endif
	}

	public void renderItem(ItemStack stack, int x, int y) {
		//#if MC >= 26.2
		//$$ g.item(stack, x, y);
		//#else
		g.renderItem(stack, x, y);
		//#endif
	}

	public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int x, int y) {
		g.setComponentTooltipForNextFrame(font, lines, x, y);
	}

	public org.joml.Matrix3x2fStack pose() {
		return g.pose();
	}
}
