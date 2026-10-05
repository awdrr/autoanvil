package dev.autoanvil.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
//#if MC >= 26.2
//$$ import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//$$ import net.minecraft.world.entity.EntityTypes;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.gamerules.GameRules;
//#endif

/**
 * The client APIs that moved between 1.21.11 and 26.2: screens and overlays onto {@code Minecraft.gui}, chat messages
 * to {@code sendSystemMessage}/{@code sendOverlayMessage}, Fabric's key binding and screen helpers renamed. Everything
 * else in the mod is the same on both.
 */
public final class Ui {
	private Ui() {
	}

	public static Screen screen(Minecraft mc) {
		//#if MC >= 26.2
		//$$ return mc.gui.screen();
		//#else
		return mc.screen;
		//#endif
	}

	public static void setScreen(Minecraft mc, Screen screen) {
		//#if MC >= 26.2
		//$$ mc.gui.setScreen(screen);
		//#else
		mc.setScreen(screen);
		//#endif
	}

	public static Overlay overlay(Minecraft mc) {
		//#if MC >= 26.2
		//$$ return mc.gui.overlay();
		//#else
		return mc.getOverlay();
		//#endif
	}

	/** A chat line, or the action bar when {@code overlay}. */
	public static void message(LocalPlayer player, Component message, boolean overlay) {
		//#if MC >= 26.2
		//$$ if (overlay) player.sendOverlayMessage(message);
		//$$ else player.sendSystemMessage(message);
		//#else
		player.displayClientMessage(message, overlay);
		//#endif
	}

	public static KeyMapping registerKey(KeyMapping key) {
		//#if MC >= 26.2
		//$$ return KeyMappingHelper.registerKeyMapping(key);
		//#else
		return KeyBindingHelper.registerKeyBinding(key);
		//#endif
	}

	public static InputConstants.Key boundKey(KeyMapping key) {
		//#if MC >= 26.2
		//$$ return KeyMappingHelper.getBoundKeyOf(key);
		//#else
		return KeyBindingHelper.getBoundKeyOf(key);
		//#endif
	}

	/** A screen's buttons and other widgets, to add to (Fabric). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static List<AbstractWidget> widgets(Screen screen) {
		//#if MC >= 26.2
		//$$ return net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
		//#else
		return (List) net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen);
		//#endif
	}

	/** A number box: up to 4 digits; anything else typed or pasted is dropped before {@code then} hears of it. */
	public static void digitsOnly(net.minecraft.client.gui.components.EditBox box, java.util.function.Consumer<String> then) {
		box.setMaxLength(4);
		box.setResponder(s -> {
			String d = s.replaceAll("\\D", "");
			if (!d.equals(s)) {
				box.setValue(d); // comes back here with the digits
				return;
			}
			then.accept(d);
		});
	}

	/** One typed character, as the keyboard delivers it to a screen. */
	public static void type(ChatScreen screen, char c) {
		//#if MC >= 26.2
		//$$ screen.charTyped(new CharacterEvent(c));
		//#else
		screen.charTyped(new CharacterEvent(c, 0));
		//#endif
	}

	public static RenderTarget mainRenderTarget(Minecraft mc) {
		//#if MC >= 26.2
		//$$ return mc.gameRenderer.mainRenderTarget();
		//#else
		return mc.getMainRenderTarget();
		//#endif
	}

	// ---------------------------------------------------------------- the self-tests' worlds

	/** Settings for a peaceful survival world with cheats. */
	public static LevelSettings survivalWorld(String name) {
		//#if MC >= 26.2
		//$$ return new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
		//$$ 		WorldDataConfiguration.DEFAULT);
		//#else
		return new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(FeatureFlags.DEFAULT_FLAGS),
				WorldDataConfiguration.DEFAULT);
		//#endif
	}

	/** Superflat dimensions (the "flat" world preset). */
	public static WorldDimensions flatDimensions(HolderLookup.Provider registries) {
		return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
	}

	public static EntityType<Villager> villagerType() {
		//#if MC >= 26.2
		//$$ return EntityTypes.VILLAGER;
		//#else
		return EntityType.VILLAGER;
		//#endif
	}

	/** Open the single-player world to LAN, so the pause menu doesn't pause it (like a server). */
	public static void openToLan(MinecraftServer server, int port) {
		//#if MC >= 26.2
		//$$ server.publishServer(MinecraftServer.MultiplayerScope.LAN, GameType.SURVIVAL, false, port);
		//#else
		((net.minecraft.client.server.IntegratedServer) server).publishServer(GameType.SURVIVAL, false, port);
		//#endif
	}

	/**
	 * Pretend the window lost (or got back) focus. 1.21.11 has a switch for it; 26.2 asks the window itself, so the
	 * self-test flags it here and the pause-on-focus-loss check reads the flag.
	 */
	public static void setWindowActive(Minecraft mc, boolean active) {
		testUnfocused = !active;
		//#if MC < 26.2
		mc.setWindowActive(active);
		//#endif
	}

	/** Set by the self-test (26.2 has no way to fake the window's focus). */
	public static boolean testUnfocused;

	public static boolean windowActive(Minecraft mc) {
		return mc.isWindowActive() && !testUnfocused;
	}
}
