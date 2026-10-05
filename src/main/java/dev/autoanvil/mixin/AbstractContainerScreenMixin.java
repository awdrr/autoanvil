package dev.autoanvil.mixin;

import dev.autoanvil.AutoAnvil;
import dev.autoanvil.ui.Panel;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click on the Auto Anvil panel is not a click "outside the window", which would throw the carried item; and queued
 * items get their queue number drawn with the slot (so tooltips still cover it).
 */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {
	//#if MC >= 26.2
	//$$ @Inject(method = "extractSlot", at = @At("RETURN"))
	//$$ private void autoanvil$queueNumber(net.minecraft.client.gui.GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
	//#else
	@Inject(method = "renderSlot", at = @At("RETURN"))
	private void autoanvil$queueNumber(net.minecraft.client.gui.GuiGraphics g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
	//#endif
		AutoAnvil.drawQueueNumber(new dev.autoanvil.compat.Gfx(g), slot);
	}

	@Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
	private void autoanvil$panelIsInside(double x, double y, int left, int top, CallbackInfoReturnable<Boolean> cir) {
		Panel p = Panel.current;
		if ((Object) this instanceof AnvilScreen && p != null && p.covers(x, y)) cir.setReturnValue(false);
	}
}
