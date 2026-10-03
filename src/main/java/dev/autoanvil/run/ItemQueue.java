package dev.autoanvil.run;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Items you marked with the queue key, in the order you marked them. Entries remember the inventory index (0-8
 * hotbar, 9-35 main) and the exact item, so identical swords stay apart and an item you move is followed.
 */
public final class ItemQueue {
	public record Entry(int invIndex, ItemStack stack) {
	}

	public static final List<Entry> ENTRIES = new ArrayList<>();

	private ItemQueue() {
	}

	public static boolean isEmpty() {
		return ENTRIES.isEmpty();
	}

	/** The menu slot showing inventory index {@code idx}, or -1 when this menu does not show it. */
	public static int menuSlot(AbstractContainerMenu menu, Inventory inv, int idx) {
		for (Slot s : menu.slots) if (s.container == inv && s.getContainerSlot() == idx) return s.index;
		return -1;
	}

	/** Where the entry's item is now (inventory index), following it if it moved; -1 when it is not there. */
	public static int locate(Inventory inv, Entry e) {
		if (ItemStack.matches(inv.getItem(e.invIndex()), e.stack())) return e.invIndex();
		for (int i = 0; i < 36; i++) {
			if (!ItemStack.matches(inv.getItem(i), e.stack())) continue;
			boolean claimed = false;
			for (Entry o : ENTRIES) if (o != e && o.invIndex() == i && ItemStack.matches(o.stack(), e.stack())) claimed = true;
			if (!claimed) return i;
		}
		return -1;
	}

	/** Keeps entries pointing at their items when you shuffle the inventory. */
	public static void follow(Inventory inv) {
		for (int k = 0; k < ENTRIES.size(); k++) {
			Entry e = ENTRIES.get(k);
			int i = locate(inv, e);
			if (i >= 0 && i != e.invIndex()) ENTRIES.set(k, new Entry(i, e.stack()));
		}
	}

	public static int indexOf(Inventory inv, int invIndex) {
		for (int k = 0; k < ENTRIES.size(); k++) {
			Entry e = ENTRIES.get(k);
			if (e.invIndex() == invIndex && ItemStack.matches(e.stack(), inv.getItem(invIndex))) return k;
		}
		return -1;
	}

	/** Can this go on the left of an anvil to get books? */
	public static boolean queueable(ItemStack s) {
		return !s.isEmpty() && s.getCount() == 1 && !s.is(Items.ENCHANTED_BOOK) && !s.is(Items.BOOK);
	}

	/**
	 * Adds the item at {@code invIndex} to the end of the queue, or takes it out if it is already queued.
	 * @return what happened, for the action bar
	 */
	public static String toggle(Inventory inv, int invIndex) {
		ItemStack s = inv.getItem(invIndex);
		if (invIndex >= 36) return "Take it off first: worn armor can't go in an anvil";
		if (!queueable(s)) return s.isEmpty() ? "Nothing there to queue" : "Can't enchant " + s.getHoverName().getString() + " on an anvil";
		int k = indexOf(inv, invIndex);
		if (k >= 0) {
			ENTRIES.remove(k);
			return "Removed " + s.getHoverName().getString() + " from the queue (" + ENTRIES.size() + " left)";
		}
		ENTRIES.add(new Entry(invIndex, s.copy()));
		return "Queued " + s.getHoverName().getString() + " (#" + ENTRIES.size() + ")";
	}

	/** "hotbar 2", "inv 3:5" (row:slot). */
	public static String where(int invIndex) {
		if (invIndex < 9) return "hotbar " + (invIndex + 1);
		int i = invIndex - 9;
		return "inv " + (i / 9 + 1) + ":" + (i % 9 + 1);
	}
}
