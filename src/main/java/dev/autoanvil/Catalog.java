package dev.autoanvil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/** Enchantment ordering and defaults, and the item kinds profiles are kept per. */
public final class Catalog {
	private Catalog() {
	}

	/** Display order in the panel: the main enchantment of each kind first, utility after, curses last. */
	private static final List<String> ORDER = List.of(
			"protection", "blast_protection", "projectile_protection", "fire_protection",
			"sharpness", "smite", "bane_of_arthropods", "density", "breach", "efficiency", "power", "impaling",
			"loyalty", "riptide", "channeling", "multishot", "piercing", "quick_charge", "lunge",
			"fortune", "silk_touch", "looting", "fire_aspect", "sweeping_edge", "knockback", "wind_burst",
			"punch", "flame", "infinity", "luck_of_the_sea", "lure",
			"respiration", "aqua_affinity", "thorns", "feather_falling", "depth_strider", "frost_walker", "soul_speed",
			"swift_sneak", "unbreaking", "mending", "vanishing_curse", "binding_curse");

	/** Most important first: when not everything fits under "Too Expensive!", the end of this list is dropped first. */
	private static final List<String> IMPORTANCE = List.of(
			"mending", "unbreaking", "protection", "sharpness", "efficiency", "power", "density", "breach", "smite",
			"bane_of_arthropods", "lunge", "fortune", "silk_touch", "looting", "feather_falling", "depth_strider",
			"swift_sneak", "soul_speed", "fire_aspect", "sweeping_edge", "wind_burst", "loyalty", "impaling", "riptide",
			"channeling", "multishot", "piercing", "quick_charge", "respiration", "aqua_affinity", "blast_protection",
			"projectile_protection", "fire_protection", "frost_walker", "flame", "punch", "infinity", "luck_of_the_sea",
			"lure", "knockback", "thorns", "vanishing_curse", "binding_curse");

	/**
	 * Off unless ticked: curses of binding, situational ones, and the losing side of each exclusive group (Protection
	 * over the other protections, Sharpness over Smite, Depth Strider over Frost Walker, Fortune over Silk Touch,
	 * Mending over Infinity, Multishot over Piercing, Loyalty over Riptide, Density over Breach).
	 */
	private static final Set<String> DEFAULT_OFF = Set.of(
			"binding_curse", "thorns", "knockback", "frost_walker", "silk_touch", "infinity", "riptide", "piercing",
			"smite", "bane_of_arthropods", "breach", "fire_protection", "blast_protection", "projectile_protection");

	public static String id(Holder<Enchantment> e) {
		return e.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
	}

	private static String path(Holder<Enchantment> e) {
		return e.unwrapKey().map(k -> k.identifier().getNamespace().equals("minecraft") ? k.identifier().getPath() : k.identifier().toString()).orElse("?");
	}

	private static final Map<String, String> SHORT = Map.ofEntries(
			Map.entry("protection", "Prot"), Map.entry("fire_protection", "FireP"), Map.entry("blast_protection", "BlastP"),
			Map.entry("projectile_protection", "ProjP"), Map.entry("feather_falling", "Feather"), Map.entry("respiration", "Resp"),
			Map.entry("aqua_affinity", "Aqua"), Map.entry("thorns", "Thorns"), Map.entry("depth_strider", "Depth"),
			Map.entry("frost_walker", "Frost"), Map.entry("binding_curse", "Binding"), Map.entry("soul_speed", "Soul"),
			Map.entry("swift_sneak", "Swift"), Map.entry("sharpness", "Sharp"), Map.entry("smite", "Smite"),
			Map.entry("bane_of_arthropods", "Bane"), Map.entry("knockback", "KB"), Map.entry("fire_aspect", "FireA"),
			Map.entry("looting", "Loot"), Map.entry("sweeping_edge", "Sweep"), Map.entry("efficiency", "Eff"),
			Map.entry("silk_touch", "Silk"), Map.entry("unbreaking", "Unb"), Map.entry("fortune", "Fort"),
			Map.entry("power", "Power"), Map.entry("punch", "Punch"), Map.entry("flame", "Flame"), Map.entry("infinity", "Inf"),
			Map.entry("luck_of_the_sea", "Luck"), Map.entry("lure", "Lure"), Map.entry("loyalty", "Loyal"),
			Map.entry("impaling", "Impale"), Map.entry("riptide", "Rip"), Map.entry("channeling", "Chan"),
			Map.entry("multishot", "Multi"), Map.entry("quick_charge", "QC"), Map.entry("piercing", "Pierce"),
			Map.entry("density", "Dense"), Map.entry("breach", "Breach"), Map.entry("wind_burst", "Wind"),
			Map.entry("lunge", "Lunge"), Map.entry("mending", "Mend"), Map.entry("vanishing_curse", "Vanish"));

	/** Compact name for tight rows: "Prot4", "Mend", "Unb3". */
	public static String shortName(Holder<Enchantment> e, int level) {
		String s = SHORT.get(path(e));
		if (s == null) {
			String d = e.value().description().getString();
			s = d.length() > 6 ? d.substring(0, 6) : d;
		}
		return e.value().getMaxLevel() == 1 ? s : s + level;
	}

	/** "Helmet", "Sword", "Mace": the kind of item, for tight rows. */
	public static String shortItem(ItemStack s) {
		String k = itemKind(s).replace('_', ' ');
		return Character.toUpperCase(k.charAt(0)) + k.substring(1);
	}

	public static int importance(Holder<Enchantment> e) {
		int i = IMPORTANCE.indexOf(path(e));
		return i < 0 ? IMPORTANCE.size() / 2 : i;
	}

	public static boolean defaultOn(Holder<Enchantment> e) {
		return !DEFAULT_OFF.contains(path(e));
	}

	/** Default for one kind of item: pickaxes (and pickaxe books) take Silk Touch over Fortune. */
	public static boolean defaultOn(Holder<Enchantment> e, String kind) {
		if (kind != null && kind.endsWith("pickaxe")) {
			if (path(e).equals("silk_touch")) return true;
			if (path(e).equals("fortune")) return false;
		}
		return defaultOn(e);
	}

	public static final Comparator<Holder<Enchantment>> DISPLAY = Comparator
			.comparingInt((Holder<Enchantment> e) -> {
				int i = ORDER.indexOf(path(e));
				return i < 0 ? ORDER.indexOf("unbreaking") : i; // modded ones just before unbreaking
			})
			.thenComparing(Catalog::id);

	public static final Comparator<Holder<Enchantment>> BY_IMPORTANCE = Comparator
			.comparingInt(Catalog::importance).thenComparing(Catalog::id);

	public static Registry<Enchantment> registry(RegistryAccess access) {
		return access.lookupOrThrow(Registries.ENCHANTMENT);
	}

	public static List<Holder<Enchantment>> all(RegistryAccess access) {
		List<Holder<Enchantment>> out = new ArrayList<>(registry(access).listElements().toList());
		out.sort(DISPLAY);
		return out;
	}

	/** Profile key for an item: its kind, so every helmet (diamond, netherite...) shares one choice of enchantments. */
	public static String itemKind(ItemStack s) {
		if (s.is(ItemTags.HEAD_ARMOR)) return "helmet";
		if (s.is(ItemTags.CHEST_ARMOR)) return "chestplate";
		if (s.is(ItemTags.LEG_ARMOR)) return "leggings";
		if (s.is(ItemTags.FOOT_ARMOR)) return "boots";
		if (s.is(ItemTags.SWORDS)) return "sword";
		if (s.is(ItemTags.SPEARS)) return "spear";
		if (s.is(ItemTags.AXES)) return "axe";
		if (s.is(ItemTags.PICKAXES)) return "pickaxe";
		if (s.is(ItemTags.SHOVELS)) return "shovel";
		if (s.is(ItemTags.HOES)) return "hoe";
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	/** Wooden/stone/iron/gold/leather/chain/copper gear: listed, but left out of "Start all" unless ticked. */
	public static boolean lowTier(ItemStack s) {
		String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		for (String t : new String[] {"wooden_", "stone_", "iron_", "golden_", "leather_", "chainmail_", "copper_"}) {
			if (p.startsWith(t)) return true;
		}
		return false;
	}

	/** Sort key for item targets: netherite, diamond, other, low tier. */
	public static int tierRank(ItemStack s) {
		String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		if (p.startsWith("netherite_")) return 0;
		if (p.startsWith("diamond_")) return 1;
		return lowTier(s) ? 3 : 2;
	}

	/** "Combine into one book" kinds; a book kind accepts an enchantment that fits any of its items. */
	public record BookKind(String key, String label, List<Item> items) {
		public boolean accepts(Holder<Enchantment> e) {
			if (items.isEmpty()) return true;
			for (Item i : items) if (e.value().canEnchant(new ItemStack(i))) return true;
			return false;
		}

		public String profileKey() {
			return "book_" + key;
		}
	}

	public static final List<BookKind> BOOK_KINDS = List.of(
			new BookKind("armor", "Armor book", List.of(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS)),
			new BookKind("sword", "Sword book", List.of(Items.NETHERITE_SWORD)),
			new BookKind("spear", "Spear book", List.of(Items.NETHERITE_SPEAR)),
			new BookKind("axe", "Axe book", List.of(Items.NETHERITE_AXE)),
			new BookKind("pickaxe", "Pickaxe book", List.of(Items.NETHERITE_PICKAXE)),
			new BookKind("shovel", "Shovel book", List.of(Items.NETHERITE_SHOVEL)),
			new BookKind("hoe", "Hoe book", List.of(Items.NETHERITE_HOE)),
			new BookKind("mace", "Mace book", List.of(Items.MACE)),
			new BookKind("bow", "Bow book", List.of(Items.BOW)),
			new BookKind("crossbow", "Crossbow book", List.of(Items.CROSSBOW)),
			new BookKind("trident", "Trident book", List.of(Items.TRIDENT)),
			new BookKind("fishing_rod", "Fishing rod book", List.of(Items.FISHING_ROD)),
			new BookKind("elytra", "Elytra book", List.of(Items.ELYTRA)));

	/** Offered only when your books are all Mending/Unbreaking-style ones that fit any gear. */
	public static final BookKind ANY_BOOK = new BookKind("any", "Book (any gear)", List.of());

	/**
	 * The enchantments a profile wants, most important first, with no two mutually exclusive: user choices from
	 * {@code overrides}, defaults ({@link #defaultOn}) for the rest.
	 */
	public static List<Holder<Enchantment>> wanted(List<Holder<Enchantment>> applicable, Map<String, Boolean> overrides) {
		return wanted(null, applicable, overrides);
	}

	public static List<Holder<Enchantment>> wanted(String kind, List<Holder<Enchantment>> applicable, Map<String, Boolean> overrides) {
		List<Holder<Enchantment>> sorted = new ArrayList<>(applicable);
		// explicit choices win conflicts against defaults, then importance
		sorted.sort(Comparator.comparingInt((Holder<Enchantment> e) -> overrides != null && Boolean.TRUE.equals(overrides.get(id(e))) ? 0 : 1)
				.thenComparing(BY_IMPORTANCE));
		List<Holder<Enchantment>> out = new ArrayList<>();
		for (Holder<Enchantment> e : sorted) {
			Boolean o = overrides == null ? null : overrides.get(id(e));
			if (!(o != null ? o : defaultOn(e, kind))) continue;
			boolean ok = true;
			for (Holder<Enchantment> k : out) if (!Enchantment.areCompatible(e, k)) ok = false;
			if (ok) out.add(e);
		}
		out.sort(BY_IMPORTANCE);
		return out;
	}
}
