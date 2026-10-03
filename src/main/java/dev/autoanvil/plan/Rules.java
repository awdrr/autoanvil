package dev.autoanvil.plan;

/**
 * What the anvil needs to know about enchantments, with enchantments as small integer ids. The mod backs this with the
 * game's enchantment registry; tests use a table.
 */
public interface Rules {
	/** {@code Enchantment.getAnvilCost()}: the per-level price on an item; a book pays half of it (at least 1). */
	int anvilCost(int id);

	int maxLevel(int id);

	/** {@code Enchantment.areCompatible}: false for an enchantment and itself, and for exclusive pairs. */
	boolean compatible(int a, int b);

	/** {@code Enchantment.canEnchant(target)}: whether the target item supports the enchantment at all. */
	boolean canEnchant(int id);

	/** Creative mode: no XP cost, no "Too Expensive!" cap and every enchantment fits every item. */
	default boolean infiniteMaterials() {
		return false;
	}
}
