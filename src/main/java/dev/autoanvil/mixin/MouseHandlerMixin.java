package dev.autoanvil.mixin;

import dev.autoanvil.factory.Factory;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	/**
	 * While the Kit Factory runs the game never takes the cursor (closing a screen or clicking the window would), so
	 * you can alt-tab and use the mouse elsewhere. It turns the camera itself; the mouse doesn't need to.
	 */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void autoanvil$leaveTheCursor(CallbackInfo ci) {
		if (Factory.running()) ci.cancel();
	}
}
