package dev.autoanvil.run;

import dev.autoanvil.Catalog;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/** What the books go onto: an item in the inventory, or a new combined book of some kind. */
public final class Target {
	public enum Kind { ITEM, BOOK }

	public final Kind kind;
	/** Menu slot of the item (ITEM), -1 for BOOK. */
	public final int slot;
	/** Copy of the item (ITEM), or a plain enchanted book as an icon (BOOK). */
	public final ItemStack stack;
	public final Catalog.BookKind bookKind;

	private Target(Kind kind, int slot, ItemStack stack, Catalog.BookKind bookKind) {
		this.kind = kind;
		this.slot = slot;
		this.stack = stack;
		this.bookKind = bookKind;
	}

	public static Target item(int slot, ItemStack stack) {
		return new Target(Kind.ITEM, slot, stack.copy(), null);
	}

	public static Target book(Catalog.BookKind kind) {
		return new Target(Kind.BOOK, -1, new ItemStack(Items.ENCHANTED_BOOK), kind);
	}

	public boolean isBook() {
		return kind == Kind.BOOK;
	}

	public String profileKey() {
		return isBook() ? bookKind.profileKey() : Catalog.itemKind(stack);
	}

	/** Whether the enchantment belongs in this target's list at all. */
	public boolean accepts(Holder<Enchantment> e) {
		return isBook() ? bookKind.accepts(e) : e.value().canEnchant(stack);
	}

	public Component label() {
		return isBook() ? Component.literal(bookKind.label()) : stack.getHoverName();
	}

	/** Identity across inventory rescans (an item target keeps it while it stays in its slot unchanged). */
	public String key() {
		return isBook() ? "book:" + bookKind.key() : "item:" + slot + ":" + ItemStack.hashItemAndComponents(stack);
	}

	public boolean sameAs(Target o) {
		if (o == null || o.kind != kind) return false;
		return isBook() ? o.bookKind == bookKind : o.slot == slot && ItemStack.matches(o.stack, stack);
	}
}
