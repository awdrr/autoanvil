package dev.autoanvil.factory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.autoanvil.AutoAnvil;
import dev.autoanvil.Catalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * What every villager in the hall trades, from the survey (walking past and opening each one), kept in
 * {@code config/autoanvil-trades.json}. Prices can be edited in the trades screen; an edited price is kept when the
 * villager is opened again, a price you did not touch follows what the villager asks.
 */
public final class TradeBook {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Offer {
		/** Position in the villager's list when last seen (looked up again by contents before trading). */
		public int index;
		public String costA = "";
		public int costACount;
		public String costB = "";
		public int costBCount;
		public String result = "";
		public int resultCount;
		/** For an enchanted book: its enchantment id and level. */
		public String enchant = "";
		public int level;
		/** Enchantments already on a sold item (diamond gear from armorers, toolsmiths, weaponsmiths): id -> level. */
		public Map<String, Integer> itemEnchants = new java.util.LinkedHashMap<>();
		/** Emeralds (or whatever costA is) the factory plans with; editable. */
		public int price;
		/** You changed {@link #price} by hand: surveys keep it. */
		public boolean manual;
		/** Off: the factory never uses this trade. */
		public boolean enabled = true;

		public boolean isBook() {
			return !enchant.isEmpty();
		}

		public boolean sells(String item) {
			return result.equals(item);
		}

		/** "Protection IV", "1 Emerald". */
		public String what() {
			if (isBook()) return enchantName(enchant, level);
			String s = (resultCount > 1 ? resultCount + " " : "") + itemName(result);
			if (itemEnchants != null && !itemEnchants.isEmpty()) {
				StringBuilder sb = new StringBuilder();
				for (var en : itemEnchants.entrySet()) {
					if (sb.length() > 0) sb.append(", ");
					sb.append(enchantName(en.getKey(), en.getValue()));
				}
				s += " (" + sb + ")";
			}
			return s;
		}

		public String cost() {
			String s = price + " " + itemName(costA);
			if (!costB.isEmpty()) s += " + " + costBCount + " " + itemName(costB);
			return s;
		}
	}

	public static final class Trader {
		public String uuid = "";
		public String profession = "";
		public double x, y, z;
		public List<Offer> offers = new ArrayList<>();

		public String label() {
			return capital(profession) + " at " + (int) Math.floor(x) + ", " + (int) Math.floor(z);
		}
	}

	public List<Trader> traders = new ArrayList<>();

	private static TradeBook instance;

	public static TradeBook get() {
		if (instance == null) instance = load();
		return instance;
	}

	public Trader find(String uuid) {
		for (Trader t : traders) if (t.uuid.equals(uuid)) return t;
		return null;
	}

	/** Records (or refreshes) a villager's trades as shown in its trading screen. */
	public Trader record(Villager v, MerchantOffers offers) {
		Trader t = find(v.getStringUUID());
		if (t == null) {
			t = new Trader();
			t.uuid = v.getStringUUID();
			traders.add(t);
		}
		t.profession = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("villager");
		t.x = v.getX();
		t.y = v.getY();
		t.z = v.getZ();
		List<Offer> old = t.offers;
		List<Offer> fresh = new ArrayList<>();
		for (int i = 0; i < offers.size(); i++) {
			Offer o = describe(offers.get(i), i);
			for (Offer prev : old) {
				if (same(prev, o)) {
					o.enabled = prev.enabled;
					if (prev.manual) {
						o.price = prev.price;
						o.manual = true;
					}
				}
			}
			fresh.add(o);
		}
		t.offers = fresh;
		save();
		return t;
	}

	static Offer describe(MerchantOffer mo, int index) {
		Offer o = new Offer();
		o.index = index;
		ItemStack a = mo.getCostA(), b = mo.getCostB(), r = mo.getResult();
		o.costA = id(a);
		o.costACount = a.getCount();
		o.price = a.getCount();
		if (!b.isEmpty()) {
			o.costB = id(b);
			o.costBCount = b.getCount();
		}
		o.result = id(r);
		o.resultCount = r.getCount();
		ItemEnchantments onItem = r.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (var en : onItem.entrySet()) o.itemEnchants.put(Catalog.id(en.getKey()), en.getIntValue());
		if (r.is(Items.ENCHANTED_BOOK)) {
			ItemEnchantments e = r.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			if (e.size() == 1) {
				var en = e.entrySet().iterator().next();
				o.enchant = Catalog.id(en.getKey());
				o.level = en.getIntValue();
			}
		}
		return o;
	}

	/** Same trade (ignoring price): same goods in and out. */
	static boolean same(Offer a, Offer b) {
		return a.costA.equals(b.costA) && a.costB.equals(b.costB) && a.result.equals(b.result) && a.enchant.equals(b.enchant) && a.level == b.level
				&& java.util.Objects.equals(a.itemEnchants == null ? Map.of() : a.itemEnchants, b.itemEnchants == null ? Map.of() : b.itemEnchants);
	}

	/** Does this live offer match the recorded one? */
	public static boolean matches(MerchantOffer mo, Offer o) {
		return same(describe(mo, o.index), o);
	}

	static String id(ItemStack s) {
		return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	static String itemName(String id) {
		if (id.isEmpty()) return "";
		var item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
		return new ItemStack(item).getHoverName().getString();
	}

	static String enchantName(String id, int level) {
		var reg = net.minecraft.client.Minecraft.getInstance().level == null ? null
				: Catalog.registry(net.minecraft.client.Minecraft.getInstance().level.registryAccess());
		if (reg != null) {
			var h = reg.get(net.minecraft.resources.Identifier.parse(id));
			if (h.isPresent()) return dev.autoanvil.ui.Panel.name(h.get(), level);
		}
		return id + " " + level;
	}

	static String capital(String s) {
		if (s.isEmpty()) return s;
		s = s.replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Cheapest enabled offer selling this enchantment at this level or higher, from a trader that is around. */
	public Map.Entry<Trader, Offer> cheapestBook(Holder<Enchantment> e, int level, java.util.function.Predicate<Trader> around) {
		String id = Catalog.id(e);
		Map.Entry<Trader, Offer> best = null;
		for (Trader t : traders) {
			if (!around.test(t)) continue;
			for (Offer o : t.offers) {
				if (!o.enabled || !o.enchant.equals(id) || o.level < level) continue;
				if (best == null || o.price < best.getValue().price) best = Map.entry(t, o);
			}
		}
		return best;
	}

	/** Highest level of this enchantment any enabled librarian sells (0 = nobody). */
	public int bestLevel(Holder<Enchantment> e) {
		String id = Catalog.id(e);
		int best = 0;
		for (Trader t : traders) for (Offer o : t.offers) if (o.enabled && o.enchant.equals(id)) best = Math.max(best, o.level);
		return best;
	}

	/** The trade that turns string into emeralds (fisherman), cheapest per emerald. */
	public Map.Entry<Trader, Offer> stringTrade(java.util.function.Predicate<Trader> around) {
		Map.Entry<Trader, Offer> best = null;
		for (Trader t : traders) {
			if (!around.test(t)) continue;
			for (Offer o : t.offers) {
				if (!o.enabled || !o.costA.equals("minecraft:string") || !o.costB.isEmpty() || !o.result.equals("minecraft:emerald")) continue;
				if (best == null || o.price < best.getValue().price) best = Map.entry(t, o);
			}
		}
		return best;
	}

	public static TradeBook load() {
		Path file = path();
		TradeBook b = new TradeBook();
		try {
			if (Files.exists(file)) {
				TradeBook read = GSON.fromJson(Files.readString(file), TradeBook.class);
				if (read != null && read.traders != null) b = read;
			}
		} catch (Exception e) {
			AutoAnvil.LOGGER.warn("Could not read {}: {}", file, e.toString());
		}
		return b;
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			AutoAnvil.LOGGER.warn("Could not write {}: {}", path(), e.toString());
		}
	}

	public static void reset() {
		instance = new TradeBook();
		instance.save();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autoanvil-trades.json");
	}
}
