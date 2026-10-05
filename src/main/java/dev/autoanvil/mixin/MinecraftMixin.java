package dev.autoanvil.mixin;

import dev.autoanvil.factory.Factory;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * Tabbing out while the Kit Factory runs doesn't open the pause menu (which would stop it), with or without chat
	 * open. Esc, with the window in focus, still pauses and stops it.
	 */
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void autoanvil$keepRunningUnfocused(boolean pauseOnly, CallbackInfo ci) {
		if (Factory.running() && !dev.autoanvil.compat.Ui.windowActive((Minecraft) (Object) this)) ci.cancel();
	}
}
