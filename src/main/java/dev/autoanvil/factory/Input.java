package dev.autoanvil.factory;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Everything the mod does, done as player input: mouse clicks and scrolls handed to the open screen at the place a
 * player would click, key presses through the game's own key bindings, mouse-look through {@code Entity.turn} (what
 * the mouse handler calls). The mod never builds or sends a packet itself; the game sends exactly what it would for
 * a player doing the same. Nothing here moves the player while a screen is open.
 */
public final class Input {
	private Input() {
	}

	/** A mouse click on a slot of the open container screen: press and release at the slot's centre. */
	public static boolean clickSlot(Minecraft mc, int slotIndex, int button, boolean shift) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> s)) return false;
		if (slotIndex < 0 || slotIndex >= s.getMenu().slots.size()) return false;
		Slot slot = s.getMenu().getSlot(slotIndex);
		return click(s, s.leftPos + slot.x + 8, s.topPos + slot.y + 8, button, shift);
	}

	/** Screen position of a slot's centre. */
	public static double[] slotCentre(AbstractContainerScreen<?> s, Slot slot) {
		return new double[] {s.leftPos + slot.x + 8, s.topPos + slot.y + 8};
	}

	/**
	 * Press on the first point, drag the mouse through all of them, release on the last: what a player does to spread
	 * the stack on the cursor evenly over several slots.
	 */
	public static void drag(Screen s, int button, java.util.List<double[]> points) {
		double[] a = points.get(0);
		s.mouseClicked(new MouseButtonEvent(a[0], a[1], new MouseButtonInfo(button, 0)), false);
		double px = a[0], py = a[1];
		for (double[] p : points) {
			s.mouseDragged(new MouseButtonEvent(p[0], p[1], new MouseButtonInfo(button, 0)), p[0] - px, p[1] - py);
			px = p[0];
			py = p[1];
		}
		s.mouseReleased(new MouseButtonEvent(px, py, new MouseButtonInfo(button, 0)));
	}

	/** Press and release a mouse button at a screen position. */
	public static boolean click(Screen s, double x, double y, int button, boolean shift) {
		MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(button, shift ? GLFW.GLFW_MOD_SHIFT : 0));
		s.mouseClicked(ev, false);
		s.mouseReleased(ev);
		return true;
	}

	/** One notch of the mouse wheel; positive scrolls up. */
	public static void scroll(Screen s, double x, double y, double notches) {
		s.mouseScrolled(x, y, 0, notches);
	}

	/** A key press (and release) delivered to the open screen. */
	public static void key(Screen s, int glfwKey) {
		KeyEvent ev = new KeyEvent(glfwKey, 0, 0);
		s.keyPressed(ev);
		s.keyReleased(ev);
	}

	/** Esc on the open screen (closes containers the way a player does). */
	public static void escape(Minecraft mc) {
		if (mc.screen != null) key(mc.screen, GLFW.GLFW_KEY_ESCAPE);
	}

	/** One press of the use key (right click by default), handled by the game on its next tick. */
	public static void pressUse(Minecraft mc) {
		KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyUse));
	}

	/** One press of a hotbar number key. */
	public static void pressHotbar(Minecraft mc, int slot) {
		KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyHotbarSlots[slot]));
	}

	/** Holds or releases the forward key. Refused while a screen is open: no walking in menus. */
	public static void forward(Minecraft mc, boolean down) {
		move(mc, down ? 0 : null);
	}

	/**
	 * Hold the movement keys that walk {@code rel} degrees off the way the player faces (0 W, 45 W+D, 90 D, 135 S+D,
	 * 180 S, -135 S+A, -90 A, -45 W+A), or release them all (null). Refused while a screen is open.
	 */
	public static void move(Minecraft mc, Integer rel) {
		boolean on = rel != null && mc.screen == null;
		int r = on ? Math.floorMod(rel, 360) : -1;
		mc.options.keyUp.setDown(r == 0 || r == 45 || r == 315);
		mc.options.keyRight.setDown(r == 45 || r == 90 || r == 135);
		mc.options.keyDown.setDown(r == 135 || r == 180 || r == 225);
		mc.options.keyLeft.setDown(r == 225 || r == 270 || r == 315);
	}

	public static void releaseMovement(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
		mc.options.keyShift.setDown(false);
	}

	/** Yaw and pitch from the player's eyes to a point. */
	public static float[] anglesTo(LocalPlayer p, Vec3 target) {
		Vec3 d = target.subtract(p.getEyePosition());
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, h));
		return new float[] {yaw, pitch};
	}

	/**
	 * Mouse-look toward yaw/pitch by at most {@code maxStep} degrees (eased near the end). Never while a screen is open.
	 * @return the angle still left to turn
	 */
	public static float turnToward(Minecraft mc, float yaw, float pitch, float maxStep) {
		LocalPlayer p = mc.player;
		float dy = Mth.wrapDegrees(yaw - p.getYRot()), dp = pitch - p.getXRot();
		float err = Math.max(Math.abs(dy), Math.abs(dp));
		if (mc.screen != null || err < 0.05f) return err;
		float step = Math.min(maxStep, Math.max(1.5f, err * 0.5f));
		float k = Math.min(1f, step / err);
		p.turn(dy * k / 0.15, dp * k / 0.15);
		return err * (1 - k);
	}
}
