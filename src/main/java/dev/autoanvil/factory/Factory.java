package dev.autoanvil.factory;

import dev.autoanvil.AutoAnvil;
import dev.autoanvil.run.ItemQueue;
import dev.autoanvil.run.Runner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Kit Factory: makes diamond gear and enchants it from the trading hall, one batch at a time — craft the items at
 * the base, trade string to a fisherman for emeralds (and XP), buy each missing enchanted book from the cheapest
 * librarian that sells it, combine them on the anvil with Auto Anvil, put the finished items in the output chests.
 *
 * <p>Every action is player input (see {@link Input}): it walks by holding forward along the route you marked,
 * looks at a villager or block and presses use, clicks slots and trade buttons in the screens, types the string
 * command into chat. It never walks or turns with a screen open.
 *
 * <p>{@link #decide} looks at the inventory each time and queues the next few steps, so stopping, a broken anvil
 * or a failed step just means deciding again from where things are.
 */
public final class Factory {
	public enum Status { RUNNING, DONE, FAILED }

	/** One small action spread over ticks. */
	abstract static class Step {
		final String label;
		int t, wait;
		String error;
		BooleanSupplier skipIf;

		Step(String label) {
			this.label = label;
		}

		Step skipIf(BooleanSupplier s) {
			skipIf = s;
			return this;
		}

		abstract Status run(Minecraft mc);

		Status fail(String msg) {
			error = msg;
			return Status.FAILED;
		}
	}

	public static FactoryConfig cfg = new FactoryConfig();
	private static boolean running, surveyOnly;
	private static final ArrayDeque<Step> steps = new ArrayDeque<>();
	public static String status = "";
	public static String lastMessage = "";
	private static int failures, sameDecision;
	private static String lastDecision = "";
	private static long lastSignature;
	private static final Set<String> exhausted = new HashSet<>();
	private static final Set<String> fullOutputs = new HashSet<>();
	private static final Set<String> surveyedThisRun = new HashSet<>();
	private static final Set<String> unreachable = new HashSet<>();
	/** Survey visits per villager this run, why the last one failed, and where it stood. */
	private static final Map<String, Integer> surveyTries = new HashMap<>();
	private static final Map<String, String> surveyErrors = new LinkedHashMap<>();
	private static final Map<String, BlockPos> surveyWhere = new HashMap<>();
	/** The villager the current plan is surveying (its problems don't count towards stopping). */
	private static String surveying;
	static final int SURVEY_TRIES = 3;
	/** Furthest the factory steps off the walkway toward a villager that's out of reach from it. */
	static final double MAX_STEP_OFF = 2.5;
	private static final Set<String> toldCrafting = new HashSet<>();
	private static final Set<String> toldGrinding = new HashSet<>();
	public static int ground;
	private static long lastCommandMs, commandCooldownUntil;
	private static int bought, crafted;
	public static KeyMapping toggleKey;

	private Factory() {
	}

	public static boolean running() {
		return running;
	}

	public static void init() {
		cfg = FactoryConfig.load();
	}

	// ---------------------------------------------------------------- start / stop

	public static void start(Minecraft mc, boolean survey) {
		if (running) return;
		if (cfg.base == null) {
			say("Mark the base first: stand where you can reach the anvil, crafting table and chests and type /kitfactory base");
			return;
		}
		running = true;
		surveyOnly = survey;
		steps.clear();
		exhausted.clear();
		fullOutputs.clear();
		surveyedThisRun.clear();
		unreachable.clear();
		surveyTries.clear();
		surveyErrors.clear();
		surveyWhere.clear();
		surveying = null;
		toldCrafting.clear();
		toldGrinding.clear();
		failures = 0;
		sameDecision = 0;
		lastDecision = "";
		if (survey) {
			say("Survey started: walking the hall and opening every villager. Esc stops.");
		} else {
			StringBuilder sb = new StringBuilder();
			for (var en : cfg.targets.entrySet()) {
				int left = en.getValue() - cfg.done.getOrDefault(en.getKey(), 0);
				if (left <= 0) continue;
				if (sb.length() > 0) sb.append(", ");
				sb.append(left).append(' ').append(new ItemStack(Kit.item(en.getKey())).getHoverName().getString())
						.append(cfg.buy.contains(en.getKey()) ? " (buy)" : " (craft)");
			}
			say("Started: " + (sb.length() == 0 ? "nothing left to make (/kitfactory items)" : sb) + ". Esc stops.");
		}
	}

	public static void stop(String why) {
		if (!running) return;
		running = false;
		steps.clear();
		Minecraft mc = Minecraft.getInstance();
		Input.releaseMovement(mc);
		if (AutoAnvil.running()) AutoAnvil.stop();
		status = why;
		say(why);
	}

	static void say(String msg) {
		lastMessage = msg;
		AutoAnvil.LOGGER.info("[Kit Factory] {}", msg);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[Kit Factory] ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(msg).withStyle(ChatFormatting.GRAY)), false);
		}
	}

	// ---------------------------------------------------------------- tick

	public static void tick(Minecraft mc) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				if (running) stop("Stopped.");
				else start(mc, false);
			}
		}
		if (!running) return;
		if (mc.player == null || mc.level == null) {
			stop("Left the world.");
			return;
		}
		if (mc.screen instanceof PauseScreen) {
			stop("Stopped (Esc).");
			return;
		}
		if (mc.player.isDeadOrDying()) {
			stop("You died.");
			return;
		}
		if (steps.isEmpty()) {
			decide(mc);
			if (!running) return;
			if (steps.isEmpty()) {
				stop("Nothing left to do.");
				return;
			}
		}
		Step s = steps.peekFirst();
		if (s.skipIf != null && s.t == 0 && s.skipIf.getAsBoolean()) {
			steps.pollFirst();
			return;
		}
		if (s.wait > 0) {
			s.wait--;
			return;
		}
		s.t++;
		if (s.t == 1) AutoAnvil.LOGGER.info("[Kit Factory] step: {}", s.label);
		status = s.label;
		Status st;
		try {
			st = s.run(mc);
		} catch (RuntimeException e) {
			AutoAnvil.LOGGER.error("[Kit Factory] step {} failed", s.label, e);
			st = s.fail(e.toString());
		}
		if (st == Status.DONE) {
			steps.pollFirst();
		} else if (st == Status.FAILED) {
			String sv = surveying;
			if (sv != null) {
				surveyErrors.put(sv, s.error);
				say("Problem: " + s.error + (surveyTries.getOrDefault(sv, 0) < SURVEY_TRIES ? " - trying again" : " - skipping that villager"));
			} else {
				failures++;
				say("Problem: " + s.error + (failures < 4 ? " - trying again" : ""));
			}
			StringBuilder inv = new StringBuilder();
			for (int i = 0; i < 36; i++) {
				ItemStack it = mc.player.getInventory().getItem(i);
				if (!it.isEmpty()) inv.append(i).append('=').append(it.getCount()).append(' ').append(Kit.id(it.getItem())).append("; ");
			}
			AutoAnvil.LOGGER.info("[Kit Factory] inventory at failure: {} screen={}", inv, mc.screen == null ? null : mc.screen.getClass().getSimpleName());
			steps.clear();
			Input.releaseMovement(mc);
			if (failures >= 4) {
				stop("Stopped after repeated problems: " + s.error);
				return;
			}
			steps.add(closeScreens());
			steps.add(pause(20));
		}
	}

	// ---------------------------------------------------------------- deciding

	private static void decide(Minecraft mc) {
		LocalPlayer p = mc.player;
		Inventory inv = p.getInventory();
		List<Vec3> route = cfg.route();
		long sig = signature(inv) * 31 + Kit.xpPoints(p);

		// 1. survey: every reachable villager not seen yet (all of them on a survey run); one that doesn't open is tried
		// again, then listed at the end
		Villager next = null;
		double bestAlong = Double.MAX_VALUE;
		Nav.Proj me = Nav.project(route, p.position());
		for (Villager v : villagers(mc)) {
			String id = v.getStringUUID();
			boolean known = TradeBook.get().find(id) != null;
			if (surveyedThisRun.contains(id) || unreachable.contains(id) || (known && !surveyOnly)) continue;
			if (surveyTries.getOrDefault(id, 0) >= SURVEY_TRIES) continue;
			surveyWhere.put(id, v.blockPosition());
			if (standFor(mc, route, v.position(), v.getBoundingBox()) == null) {
				unreachable.add(id);
				surveyErrors.put(id, "out of reach from the walkway, even " + MAX_STEP_OFF + " blocks off it - add a walkway point closer to it");
				say("Can't reach the villager at " + v.blockPosition().toShortString() + " from the walkway - add a walkway point closer to it.");
				continue;
			}
			double d = Math.abs(Nav.project(route, v.position()).along() - me.along());
			if (d < bestAlong) {
				bestAlong = d;
				next = v;
			}
		}
		if (next != null) {
			Villager v = next;
			int n = surveyTries.merge(v.getStringUUID(), 1, Integer::sum);
			plan("survey " + v.getStringUUID() + " #" + n, sig,
					walkTo(Nav.spotFor(route, v.position()), "Walking to a villager"),
					stepUp(v.getUUID()),
					openVillager(v.getUUID()),
					record(v.getUUID()),
					closeScreens());
			return;
		}
		if (surveyOnly) {
			// servers only send villagers near you: walk on down the walkway and look again, to the end
			double total = Nav.length(route);
			if (me.along() < total - 2) {
				double to = Math.min(total, me.along() + 24);
				plan("survey sweep " + (int) to, sig, walkTo(Nav.pointAt(route, to), "Walking down the walkway"));
				return;
			}
			int trades = 0;
			for (TradeBook.Trader t : TradeBook.get().traders) trades += t.offers.size();
			int skipped = 0;
			for (var en : surveyErrors.entrySet()) {
				if (surveyedThisRun.contains(en.getKey())) continue;
				skipped++;
				BlockPos at = surveyWhere.get(en.getKey());
				say("Skipped the villager at " + (at == null ? "?" : at.toShortString()) + ": " + en.getValue());
			}
			stop("Survey done: opened " + surveyedThisRun.size() + " villagers" + (skipped > 0 ? ", skipped " + skipped + " (listed above)" : "") + "; "
					+ TradeBook.get().traders.size() + " villagers, " + trades + " trades saved. Check prices with /kitfactory trades, then /kitfactory start.");
			return;
		}
		if (TradeBook.get().traders.isEmpty()) {
			stop("No villagers found along the walkway. Mark it with /kitfactory path add.");
			return;
		}

		// 2. the anvil must be there
		BlockPos anvilPos = FactoryConfig.pos(cfg.anvil);
		if (anvilPos == null) {
			stop("No anvil marked: stand at the base and type /kitfactory base");
			return;
		}
		if (!mc.level.getBlockState(anvilPos).is(BlockTags.ANVIL)) {
			if (count(inv, s -> s.is(Items.ANVIL) || s.is(Items.CHIPPED_ANVIL) || s.is(Items.DAMAGED_ANVIL)) == 0) {
				fetch(mc, "anvil", s -> s.is(Items.ANVIL), 1, sig);
				return;
			}
			plan("place anvil", sig, walkTo(cfg.baseVec(), "Walking to the base"), placeAnvil(anvilPos));
			return;
		}

		// 3. finished items go to the output chests
		Map<Item, Map<Holder<Enchantment>, Integer>> kits = new LinkedHashMap<>();
		for (String id : cfg.targets.keySet()) kits.put(Kit.item(id), Kit.enchants(mc.level.registryAccess(), Kit.item(id)));
		boolean anyFinished = false;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			Map<Holder<Enchantment>, Integer> kit = kits.get(s.getItem());
			if (kit != null && Kit.finished(s, kit)) anyFinished = true;
		}
		if (anyFinished) {
			int[] out = null;
			for (int[] c : cfg.outputChests) if (!fullOutputs.contains(key(c))) {
				out = c;
				break;
			}
			if (out == null) {
				stop(cfg.outputChests.isEmpty() ? "Mark an output chest: look at it and type /kitfactory chest output" : "The output chests are full.");
				return;
			}
			int[] chest = out;
			plan("deposit", sig, walkTo(cfg.baseVec(), "Walking to the base"),
					openBlock(FactoryConfig.pos(chest), "output chest", s -> s instanceof ContainerScreen),
					deposit(kits, chest),
					closeScreens());
			return;
		}

		// 4. the item kind being worked on
		Item type = null;
		for (var en : cfg.targets.entrySet()) {
			if (cfg.done.getOrDefault(en.getKey(), 0) < en.getValue()) {
				type = Kit.item(en.getKey());
				break;
			}
		}
		if (type == null) {
			stop("All done! Everything is in the output chests.");
			return;
		}
		Map<Holder<Enchantment>, Integer> kit = kits.get(type);
		if (kit.isEmpty()) {
			stop("No librarian sells anything you ticked for " + new ItemStack(type).getHoverName().getString() + ".");
			return;
		}
		String typeId = Kit.id(type);
		List<Integer> batch = new ArrayList<>();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(type) && inv.getItem(i).getCount() == 1) batch.add(i);

		// 5. a new batch: gear on the buy list from a villager (ground clean if every sale clashes), the rest crafted
		if (batch.isEmpty()) {
			int left = cfg.targets.get(typeId) - cfg.done.getOrDefault(typeId, 0);
			int perItem = 1 + kit.size();
			int room = free(inv) + count(inv, s -> s.is(Items.DIAMOND) || s.is(Items.STICK)) / 64 - 7;
			int want = Math.max(1, Math.min(Math.min(cfg.maxBatch, left), room / perItem));
			boolean buys = cfg.buy.contains(typeId);
			Gear gear = buys ? bestGear(mc, type, kit) : null;
			if (buys && gear == null) {
				String name = new ItemStack(type).getHoverName().getString();
				boolean sold = false;
				for (TradeBook.Trader t : TradeBook.get().traders) for (TradeBook.Offer o : t.offers) if (o.enabled && o.result.equals(typeId)) sold = true;
				stop(sold ? "Every " + name + " the villagers sell has an enchantment that clashes with yours. Put a grindstone at the base and run /kitfactory base again."
						: "No villager sells " + name + " (it's bought, not crafted). Add one that does and run /kitfactory survey, or tick its sale in /kitfactory trades.");
				return;
			}
			if (gear != null) {
				TradeBook.Offer offer = gear.offer();
				if (gear.grind() && !toldGrinding.contains(typeId)) {
					toldGrinding.add(typeId);
					say("Every " + new ItemStack(type).getHoverName().getString() + " the villagers sell has a clashing enchantment - buying the cheapest and grinding it clean.");
				}
				Item payA = Kit.item(offer.costA);
				Item payB = offer.costB.isEmpty() ? null : Kit.item(offer.costB);
				if (payB != null && count(inv, s -> s.is(payB) && !s.isEnchanted()) < offer.costBCount) {
					fetch(mc, offer.costB, s -> s.is(payB) && !s.isEnchanted(), offer.costBCount * want, sig);
					return;
				}
				int pay = count(inv, s -> s.is(payA));
				if (pay < offer.price) {
					if (payA != Items.EMERALD) {
						fetch(mc, offer.costA, s -> s.is(payA), offer.price * want, sig);
						return;
					}
					getEmeralds(mc, (int) Math.min((long) offer.price * want, roomFor(inv, Items.EMERALD)), sig);
					return;
				}
				int n = Math.min(want, pay / Math.max(1, offer.price));
				if (payB != null) n = Math.min(n, count(inv, s -> s.is(payB) && !s.isEnchanted()) / Math.max(1, offer.costBCount));
				n = Math.min(n, free(inv));
				if (n <= 0) {
					makeRoom(mc, sig, 0);
					return;
				}
				Item item = type;
				plan("buy " + typeId, sig,
						walkTo(spot(mc, route, gear.trader()), "Walking to " + gear.trader().label()),
						stepUp(UUID.fromString(gear.trader().uuid)),
						openVillager(UUID.fromString(gear.trader().uuid)),
						buy(gear.trader(), offer, n, s -> s.is(item)),
						closeScreens());
				return;
			}
			if (!toldCrafting.contains(typeId)) {
				toldCrafting.add(typeId);
				say("Crafting " + new ItemStack(type).getHoverName().getString() + " from the input chest.");
			}
			Kit.Recipe r = Kit.recipe(type);
			if (r == null) {
				stop("No crafting recipe for " + new ItemStack(type).getHoverName().getString() + ": set it to Buy in /kitfactory items.");
				return;
			}
			for (Item ing : r.ingredients()) {
				int need = r.count(ing) * want;
				if (count(inv, s -> s.is(ing)) < need) {
					fetch(mc, Kit.id(ing), s -> s.is(ing), need, sig);
					return;
				}
			}
			BlockPos table = FactoryConfig.pos(cfg.craftingTable);
			if (table == null) {
				stop("No crafting table marked: stand at the base and type /kitfactory base");
				return;
			}
			int n = want;
			plan("craft " + typeId, sig, walkTo(cfg.baseVec(), "Walking to the base"),
					openBlock(table, "crafting table", s -> s instanceof CraftingScreen),
					craft(r, n),
					closeScreens());
			return;
		}

		// 6. gear that came with a clashing enchantment goes through the grindstone first
		List<ItemStack> items = new ArrayList<>();
		for (int i : batch) items.add(inv.getItem(i));
		for (ItemStack it : items) {
			List<String> bad = clashes(it, kit);
			if (bad.isEmpty()) continue;
			if (cfg.grindstone == null) {
				stop(it.getHoverName().getString() + " has " + String.join(", ", bad) + ". Put a grindstone at the base and run /kitfactory base again.");
				return;
			}
			plan("grind " + typeId, sig, walkTo(cfg.baseVec(), "Walking to the base"),
					openBlock(FactoryConfig.pos(cfg.grindstone), "grindstone", s -> s instanceof net.minecraft.client.gui.screens.inventory.GrindstoneScreen),
					grind(s -> s.is(it.getItem()) && !clashes(s, kit).isEmpty()),
					closeScreens());
			return;
		}

		// 7. books still missing for the batch
		Holder<Enchantment> missingE = null;
		int missingN = 0;
		long emeraldsNeeded = 0;
		for (var en : kit.entrySet()) {
			int lacking = 0;
			for (ItemStack it : items) if (dev.autoanvil.run.Analysis.enchantments(it).getLevel(en.getKey()) < en.getValue()) lacking++;
			int have = count(inv, s -> Kit.bookHas(s, en.getKey(), en.getValue()));
			int miss = Math.max(0, lacking - have);
			if (miss > 0) {
				var offer = TradeBook.get().cheapestBook(en.getKey(), en.getValue(), around(mc));
				if (offer != null) emeraldsNeeded += (long) miss * offer.getValue().price;
				if (missingE == null) {
					missingE = en.getKey();
					missingN = miss;
				}
			}
		}
		if (missingE != null) {
			var entry = TradeBook.get().cheapestBook(missingE, kit.get(missingE), around(mc));
			if (entry == null) {
				stop("The librarian selling " + dev.autoanvil.ui.Panel.name(missingE, kit.get(missingE)) + " isn't here any more - run /kitfactory survey");
				return;
			}
			TradeBook.Trader trader = entry.getKey();
			TradeBook.Offer offer = entry.getValue();
			Item payA = Kit.item(offer.costA);
			Item payB = offer.costB.isEmpty() ? null : Kit.item(offer.costB);
			if (payB != null && count(inv, s -> s.is(payB) && !s.isEnchanted()) < offer.costBCount) {
				fetch(mc, offer.costB, s -> s.is(payB) && !s.isEnchanted(), Math.min(64, offer.costBCount * Math.max(missingN, 16)), sig);
				return;
			}
			int pay = count(inv, s -> s.is(payA));
			long want = Math.min(emeraldsNeeded, pay + roomFor(inv, Items.EMERALD) - 64L * 3);
			boolean trading = lastDecision.startsWith("string");
			if (pay < offer.price || (trading && payA == Items.EMERALD && pay < want)) {
				getEmeralds(mc, (int) Math.max(offer.price, want), sig);
				return;
			}
			int n = Math.min(missingN, pay / Math.max(1, offer.price));
			if (payB != null) n = Math.min(n, count(inv, s -> s.is(payB) && !s.isEnchanted()) / Math.max(1, offer.costBCount));
			n = Math.min(n, free(inv));
			if (n <= 0) {
				makeRoom(mc, sig, (int) emeraldsNeeded);
				return;
			}
			int buy = n;
			Holder<Enchantment> e = missingE;
			int lv = kit.get(e);
			plan("buy " + offer.enchant, sig,
					walkTo(spot(mc, route, trader), "Walking to " + trader.label()),
					stepUp(UUID.fromString(trader.uuid)),
					openVillager(UUID.fromString(trader.uuid)),
					buy(trader, offer, buy, s -> Kit.isBookOf(s, e, lv)),
					closeScreens());
			return;
		}

		// 8. levels for the anvil: up to cfg.xpLevel (or what the batch needs, if less), never below the dearest step;
		// the anvil spends them, and comes back here when the next step needs more than is left
		List<Integer> costs = Kit.anvilCosts(items, kit);
		if (!costs.isEmpty() && !p.hasInfiniteMaterials()) {
			int sum = 0, max = 0;
			for (int c : costs) {
				sum += c;
				max = Math.max(max, c);
			}
			int target = Math.max(max, Math.min(sum, Math.max(cfg.xpLevel, max)));
			if (p.experienceLevel < target) {
				getXp(mc, target, sig);
				return;
			}
		}

		// 9. combine on the anvil
		plan("anvil " + typeId, sig, walkTo(cfg.baseVec(), "Walking to the base"),
				openBlock(anvilPos, "anvil", s -> s instanceof AnvilScreen),
				anvil(batch),
				closeScreens());
	}

	/** Queue the steps for one decision; the same decision over and over without anything changing is a stop. */
	private static void plan(String what, long sig, Step... s) {
		AutoAnvil.LOGGER.info("[Kit Factory] decide: {}", what);
		if (what.equals(lastDecision) && sig == lastSignature) {
			if (++sameDecision >= 5) {
				stop("Stuck: '" + what + "' keeps not getting anywhere. " + (lastMessage == null ? "" : lastMessage));
				return;
			}
		} else {
			sameDecision = 0;
		}
		lastDecision = what;
		lastSignature = sig;
		surveying = what.startsWith("survey ") && !what.startsWith("survey sweep") ? what.substring(7, what.indexOf(" #")) : null;
		steps.addAll(List.of(s));
	}

	/** Take something from the input chests (all of them, stopping once there is enough). */
	private static void fetch(Minecraft mc, String what, Predicate<ItemStack> pred, int amount, long sig) {
		if (exhausted.contains(what)) {
			stop("Out of " + TradeBook.itemName(what.contains(":") ? what : "minecraft:" + what) + ": put more in an input chest and start again.");
			return;
		}
		if (cfg.inputChests.isEmpty()) {
			stop("Mark an input chest: look at it and type /kitfactory chest input");
			return;
		}
		Inventory inv = mc.player.getInventory();
		List<Step> s = new ArrayList<>();
		s.add(walkTo(cfg.baseVec(), "Walking to the base"));
		for (int[] c : cfg.inputChests) {
			BooleanSupplier enough = () -> count(inv, pred) >= amount;
			s.add(openBlock(FactoryConfig.pos(c), "input chest", x -> x instanceof ContainerScreen).skipIf(enough));
			s.add(take(pred, amount).skipIf(() -> !(Minecraft.getInstance().screen instanceof ContainerScreen)));
			s.add(closeScreens());
		}
		s.add(new Step("Checking supplies") {
			Status run(Minecraft m) {
				if (count(inv, pred) < amount) exhausted.add(what);
				return Status.DONE;
			}
		});
		plan("fetch " + what, sig, s.toArray(new Step[0]));
	}

	/** Emeralds: always string at a fisherman (the string command gives it), never from chests or emerald blocks. */
	private static void getEmeralds(Minecraft mc, int target, long sig) {
		stringTrade(mc, target, 0, sig);
	}

	private static void getXp(Minecraft mc, int targetLevel, long sig) {
		stringTrade(mc, 0, targetLevel, sig);
	}

	/** Get string with the server command if short, then trade it to the fisherman. */
	private static void stringTrade(Minecraft mc, int targetEmeralds, int targetLevel, long sig) {
		Inventory inv = mc.player.getInventory();
		var entry = TradeBook.get().stringTrade(around(mc));
		if (entry == null) {
			stop("No fisherman selling emeralds for string was found in the survey.");
			return;
		}
		TradeBook.Offer offer = entry.getValue();
		if (count(inv, s -> s.is(Items.STRING)) < offer.price && !cfg.stringInGui) {
			if (free(inv) < 3) {
				stop("Inventory too full to receive string.");
				return;
			}
			plan("string command", sig, closeScreens(), command(cfg.stringCommand));
			return;
		}
		if (roomFor(inv, Items.EMERALD) < 1 && count(inv, s -> s.is(Items.STRING)) < offer.price) {
			// (string is room too: the trade button moves a stack of it into the trade slot)
			makeRoom(mc, sig, targetEmeralds);
			return;
		}
		List<Vec3> route = cfg.route();
		plan("string trade", sig,
				walkTo(spot(mc, route, entry.getKey()), "Walking to " + entry.getKey().label()),
				stepUp(UUID.fromString(entry.getKey().uuid)),
				openVillager(UUID.fromString(entry.getKey().uuid)),
				tradeString(offer, targetEmeralds, targetLevel),
				closeScreens());
	}

	// ---------------------------------------------------------------- steps: moving and opening

	static Step pause(int ticks) {
		return new Step("Waiting") {
			Status run(Minecraft mc) {
				return t >= ticks ? Status.DONE : Status.RUNNING;
			}
		};
	}

	static Step closeScreens() {
		return new Step("Closing") {
			Status run(Minecraft mc) {
				if (mc.screen == null) return Status.DONE;
				if (t > 40) return fail("A screen would not close");
				Input.escape(mc);
				wait = 2;
				return Status.RUNNING;
			}
		};
	}

	/** Walk along the route to a point, holding forward and turning like a player. */
	static Step walkTo(Vec3 target, String why) {
		return walk(target, why, false);
	}

	/** {@code direct}: straight at the target instead of along the walkway. */
	static Step walk(Vec3 target, String why, boolean direct) {
		return new Step(why) {
			Vec3 last;
			int still;

			Status run(Minecraft mc) {
				if (mc.screen != null) {
					Input.forward(mc, false);
					Input.escape(mc);
					wait = 2;
					return Status.RUNNING;
				}
				LocalPlayer p = mc.player;
				Vec3 pos = p.position();
				double dist = Nav.horiz(pos, target);
				if (dist < 0.35) {
					Input.forward(mc, false);
					double v = p.getDeltaMovement().horizontalDistance();
					return v < 0.02 || t > 600 ? Status.DONE : Status.RUNNING;
				}
				if (t > 20 * 90) {
					Input.forward(mc, false);
					return fail("Walking took too long");
				}
				Vec3 next = direct ? target : Nav.next(cfg.route(), pos, target);
				float yaw = (float) Math.toDegrees(Math.atan2(-(next.x - pos.x), next.z - pos.z));
				Input.turnToward(mc, yaw, 12f, 25f);
				float off = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
				boolean go = off < 30 && !(dist < 0.9 && t % 2 == 0); // tap in the last block to stop on the spot
				Input.forward(mc, go);
				if (go && last != null && Nav.horiz(last, pos) < 0.01) still++;
				else still = 0;
				last = pos;
				if (still > 50) {
					Input.forward(mc, false);
					return fail("Stuck at " + BlockPos.containing(pos).toShortString() + " walking to " + BlockPos.containing(target).toShortString());
				}
				return Status.RUNNING;
			}
		};
	}

	/** Look at a villager and press use until its trading screen is open with offers. */
	static Step openVillager(UUID uuid) {
		return new Step("Opening a villager") {
			int aim, aligned, pressedAt = -1, tries;
			List<Vec3> points;

			Status run(Minecraft mc) {
				if (mc.screen instanceof MerchantScreen ms) {
					return ms.getMenu().getOffers().isEmpty() ? Status.RUNNING : Status.DONE;
				}
				if (mc.screen != null) {
					Input.escape(mc);
					wait = 2;
					return Status.RUNNING;
				}
				Entity e = entity(mc, uuid);
				if (e == null) return fail("That villager isn't loaded (or has moved)");
				if (pressedAt >= 0) {
					if (t - pressedAt < 30 + 2 * latencyTicks(mc)) return Status.RUNNING;
					pressedAt = -1;
					aligned = 0;
					if (++tries >= 4) return fail("The villager at " + e.blockPosition().toShortString() + " didn't open");
				}
				if (points == null) points = aimPoints(mc, e);
				if (points.isEmpty()) return fail(cantAim(mc, e));
				Vec3 target = points.get(aim % points.size());
				float[] a = Input.anglesTo(mc.player, target);
				float err = Input.turnToward(mc, a[0], a[1], 30f);
				if (err < 1.5f) aligned++;
				else aligned = 0;
				if (aligned >= 2) {
					if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == e) {
						Input.pressUse(mc);
						pressedAt = t;
					} else if (aligned >= 6) {
						aligned = 0;
						if (++aim >= Math.min(12, Math.max(6, points.size()))) return fail("Can't get the crosshair on the villager at " + e.blockPosition().toShortString() + " (too far or blocked)");
						points = aimPoints(mc, e);
						if (points.isEmpty()) return fail(cantAim(mc, e));
					}
				}
				return Status.RUNNING;
			}
		};
	}

	/** Look at a block and press use until the expected screen opens. */
	static Step openBlock(BlockPos pos, String what, Predicate<Screen> isIt) {
		return new Step("Opening the " + what) {
			int face, aligned, pressedAt = -1, tries, opened = -1;

			Status run(Minecraft mc) {
				if (isIt.test(mc.screen)) {
					if (opened < 0) opened = t;
					return t - opened >= 2 + latencyTicks(mc) ? Status.DONE : Status.RUNNING; // contents arrive just after
				}
				if (mc.screen != null) {
					Input.escape(mc);
					wait = 2;
					return Status.RUNNING;
				}
				if (pos == null) return fail("No " + what + " marked");
				if (pressedAt >= 0) {
					if (t - pressedAt < 30) return Status.RUNNING;
					pressedAt = -1;
					aligned = 0;
					if (++tries >= 4) return fail("The " + what + " at " + pos.toShortString() + " didn't open");
				}
				List<Vec3> faces = visibleFaces(mc, pos);
				if (faces.isEmpty()) return fail("The " + what + " at " + pos.toShortString() + " is out of reach from the base spot");
				Vec3 target = faces.get(face % faces.size());
				float[] a = Input.anglesTo(mc.player, target);
				float err = Input.turnToward(mc, a[0], a[1], 30f);
				if (err < 1.5f) aligned++;
				else aligned = 0;
				if (aligned >= 2) {
					if (mc.hitResult instanceof BlockHitResult bh && bh.getBlockPos().equals(pos)) {
						Input.pressUse(mc);
						pressedAt = t;
					} else if (aligned >= 6) {
						aligned = 0;
						if (++face >= faces.size() * 2) return fail("Can't get the crosshair on the " + what + " at " + pos.toShortString());
					}
				}
				return Status.RUNNING;
			}
		};
	}

	/** Face centres of a block the player's eyes are in front of and within reach of, nearest first. */
	static List<Vec3> visibleFaces(Minecraft mc, BlockPos pos) {
		Vec3 eye = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange();
		List<Vec3> out = new ArrayList<>();
		var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
		if (!shape.isEmpty()) {
			Vec3 c = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
			if (c.distanceTo(eye) <= reach - 0.1) out.add(c);
		}
		for (Direction d : Direction.values()) {
			Vec3 c = Vec3.atCenterOf(pos).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
			Vec3 toEye = eye.subtract(c);
			if (toEye.x * d.getStepX() + toEye.y * d.getStepY() + toEye.z * d.getStepZ() <= 0.05) continue;
			if (c.distanceTo(eye) > reach - 0.1) continue;
			out.add(c);
		}
		if (out.size() > 1) {
			List<Vec3> faces = new ArrayList<>(out.subList(shape.isEmpty() ? 0 : 1, out.size()));
			faces.sort(Comparator.comparingDouble(c -> c.distanceTo(eye)));
			if (!shape.isEmpty()) faces.add(0, out.get(0));
			return faces;
		}
		return out;
	}

	/** Type a command into chat the way a player does: the command key, the text, Enter. */
	static Step command(String cmd) {
		return new Step("Typing /" + cmd) {
			int phase;
			int stringBefore;
			boolean typeSlash;

			Status run(Minecraft mc) {
				long now = System.currentTimeMillis();
				switch (phase) {
					case 0 -> {
						if (mc.screen != null) {
							Input.escape(mc);
							wait = 2;
							return Status.RUNNING;
						}
						if (now < commandCooldownUntil || now - lastCommandMs < cfg.stringIntervalMs) {
							status = now < commandCooldownUntil ? "Waiting " + (commandCooldownUntil - now) / 1000 + "s before /" + cmd + " again" : label;
							return Status.RUNNING;
						}
						stringBefore = count(mc.player.getInventory(), s -> s.is(Items.STRING));
						// the "/" key opens chat with the slash typed; if it's unbound, the chat key and type the slash
						typeSlash = mc.options.keyCommand.isUnbound();
						KeyMapping.click(KeyBindingHelper.getBoundKeyOf(typeSlash ? mc.options.keyChat : mc.options.keyCommand));
						phase = 1;
					}
					case 1 -> {
						if (mc.screen instanceof ChatScreen cs) {
							for (char c : ((typeSlash ? "/" : "") + cmd).toCharArray()) cs.charTyped(new CharacterEvent(c, 0));
							phase = 2;
						} else if (t > 40) {
							return fail("The chat didn't open");
						}
					}
					case 2 -> {
						if (mc.screen instanceof ChatScreen cs) Input.key(cs, GLFW.GLFW_KEY_ENTER);
						lastCommandMs = now;
						phase = 3;
						wait = 5;
					}
					default -> {
						if (count(mc.player.getInventory(), s -> s.is(Items.STRING)) > stringBefore) return Status.DONE;
						if (t > 80) {
							commandCooldownUntil = System.currentTimeMillis() + cfg.stringRetrySeconds * 1000L;
							say("/" + cmd + " gave no string; trying again in " + cfg.stringRetrySeconds + "s.");
							return Status.DONE;
						}
					}
				}
				return Status.RUNNING;
			}
		};
	}

	// ---------------------------------------------------------------- steps: in screens

	/** Shift-click stacks out of the open chest until the inventory holds {@code amount}. */
	static Step take(Predicate<ItemStack> pred, int amount) {
		return new Step("Taking from a chest") {
			Status run(Minecraft mc) {
				if (!(mc.screen instanceof ContainerScreen cs)) return Status.DONE;
				Inventory inv = mc.player.getInventory();
				if (count(inv, pred) >= amount) return Status.DONE;
				ChestMenu menu = cs.getMenu();
				int size = menu.getRowCount() * 9;
				for (int i = 0; i < size; i++) {
					ItemStack s = menu.getSlot(i).getItem();
					if (!s.isEmpty() && pred.test(s)) {
						if (roomFor(inv, s.getItem()) <= 0) return Status.DONE;
						Input.clickSlot(mc, i, 0, true);
						wait = gap(mc);
						return Status.RUNNING;
					}
				}
				return Status.DONE;
			}
		};
	}

	/** Shift-click finished items into the open output chest; counts what actually went in. */
	static Step deposit(Map<Item, Map<Holder<Enchantment>, Integer>> kits, int[] chest) {
		return new Step("Putting finished items away") {
			Map<Item, Integer> before;
			int clicks;

			Status run(Minecraft mc) {
				if (!(mc.screen instanceof ContainerScreen cs)) return fail("The chest closed");
				Inventory inv = mc.player.getInventory();
				if (before == null) before = finishedCounts(inv, kits);
				for (int i = 0; i < 36; i++) {
					ItemStack s = inv.getItem(i);
					var kit = kits.get(s.getItem());
					if (kit == null || !Kit.finished(s, kit)) continue;
					if (clicks > 40) break;
					int ms = ItemQueue.menuSlot(cs.getMenu(), inv, i);
					if (ms < 0) continue;
					clicks++;
					Input.clickSlot(mc, ms, 0, true);
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (t < clicks * 2 + latencyTicks(mc) + 4) return Status.RUNNING; // let the server confirm
				Map<Item, Integer> after = finishedCounts(inv, kits);
				int moved = 0;
				for (var en : before.entrySet()) {
					int n = en.getValue() - after.getOrDefault(en.getKey(), 0);
					if (n > 0) {
						cfg.done.merge(Kit.id(en.getKey()), n, Integer::sum);
						moved += n;
					}
				}
				cfg.save();
				if (after.values().stream().mapToInt(Integer::intValue).sum() > 0) fullOutputs.add(key(chest));
				if (moved > 0) {
					failures = 0;
					say("Stored " + moved + " finished item" + (moved == 1 ? "" : "s") + ". " + progress());
				}
				return Status.DONE;
			}
		};
	}

	/** Lay a shaped recipe out in the crafting grid {@code n} deep, then shift-click the result. */
	static Step craft(Kit.Recipe r, int n) {
		return new Step("Crafting " + n + " " + new ItemStack(r.result()).getHoverName().getString()) {
			int phase, ingredient, before, placedFrom = -1;

			Status run(Minecraft mc) {
				if (!(mc.screen instanceof CraftingScreen cs)) return fail("The crafting table closed");
				CraftingMenu menu = cs.getMenu();
				Inventory inv = mc.player.getInventory();
				List<Slot> grid = menu.getInputGridSlots();
				ItemStack carried = menu.getCarried();
				if (phase == 0) {
					before = count(inv, s -> s.is(r.result()));
					phase = 1;
				}
				if (phase == 1) {
					List<Item> ings = r.ingredients();
					if (ingredient >= ings.size()) {
						if (!carried.isEmpty()) return putBack(mc, menu, inv);
						phase = 2;
						return Status.RUNNING;
					}
					Item ing = ings.get(ingredient);
					Slot target = null;
					for (int cell : r.cells(ing)) {
						Slot g = grid.get(cell);
						if (g.getItem().getCount() < n) {
							target = g;
							break;
						}
					}
					if (target == null) {
						if (!carried.isEmpty()) return putBack(mc, menu, inv);
						ingredient++;
						return Status.RUNNING;
					}
					if (carried.isEmpty()) {
						for (int i = 0; i < 36; i++) {
							if (!inv.getItem(i).is(ing)) continue;
							placedFrom = ItemQueue.menuSlot(menu, inv, i);
							Input.clickSlot(mc, placedFrom, 0, false); // pick up the stack
							wait = gap(mc);
							return Status.RUNNING;
						}
						return fail("Ran out of " + new ItemStack(ing).getHoverName().getString() + " while crafting");
					}
					if (!carried.is(ing)) return putBack(mc, menu, inv);
					Input.clickSlot(mc, target.index, 1, false); // right click: one item
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (phase == 2) {
					ItemStack res = menu.getResultSlot().getItem();
					if (res.is(r.result())) {
						Input.clickSlot(mc, menu.getResultSlot().index, 0, true);
						phase = 3;
						wait = gap(mc) + latencyTicks(mc) + 2;
					} else if (t > 200) {
						return fail("The crafting table shows no " + new ItemStack(r.result()).getHoverName().getString());
					}
					return Status.RUNNING;
				}
				// leftovers back to the inventory, then done
				for (Slot g : grid) {
					if (!g.getItem().isEmpty()) {
						Input.clickSlot(mc, g.index, 0, true);
						wait = gap(mc);
						return Status.RUNNING;
					}
				}
				int made = count(inv, s -> s.is(r.result())) - before;
				if (made <= 0) return fail("Crafting gave nothing");
				crafted += made;
				failures = 0;
				return Status.DONE;
			}

			private Status putBack(Minecraft mc, CraftingMenu menu, Inventory inv) {
				int slot = placedFrom;
				if (slot < 0 || !menu.getSlot(slot).getItem().isEmpty() && !ItemStack.isSameItemSameComponents(menu.getSlot(slot).getItem(), menu.getCarried())) {
					slot = emptyMenuSlot(menu, inv);
				}
				if (slot < 0) return fail("No free slot to put items back");
				Input.clickSlot(mc, slot, 0, false);
				wait = gap(mc);
				return Status.RUNNING;
			}
		};
	}

	/** One item at a time into the grindstone, take the clean result, until nothing matching is left. */
	static Step grind(Predicate<ItemStack> needs) {
		return new Step("Grinding off clashing enchantments") {
			Status run(Minecraft mc) {
				if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.GrindstoneScreen gs)) return fail("The grindstone closed");
				var menu = gs.getMenu();
				Inventory inv = mc.player.getInventory();
				if (!menu.getSlot(2).getItem().isEmpty()) {
					Input.clickSlot(mc, 2, 0, true);
					ground++;
					wait = gap(mc) + latencyTicks(mc) + 1;
					return Status.RUNNING;
				}
				if (!menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(1).getItem().isEmpty()) {
					if (t > 200) return fail("The grindstone shows no result");
					return Status.RUNNING;
				}
				for (int i = 0; i < 36; i++) {
					if (!needs.test(inv.getItem(i))) continue;
					Input.clickSlot(mc, ItemQueue.menuSlot(menu, inv, i), 0, true); // into the top slot, alone
					wait = gap(mc) + latencyTicks(mc) + 1;
					return Status.RUNNING;
				}
				failures = 0;
				return Status.DONE;
			}
		};
	}

	/** Shift-click emerald stacks into the open chest while more than {@code keep} would still be left. */
	static Step bankEmeralds(int keep) {
		return new Step("Storing spare emeralds") {
			Status run(Minecraft mc) {
				if (!(mc.screen instanceof ContainerScreen cs)) return Status.DONE;
				Inventory inv = mc.player.getInventory();
				int have = count(inv, s -> s.is(Items.EMERALD));
				for (int i = 0; i < 36; i++) {
					ItemStack s = inv.getItem(i);
					if (!s.is(Items.EMERALD) || have - s.getCount() < keep) continue;
					Input.clickSlot(mc, ItemQueue.menuSlot(cs.getMenu(), inv, i), 0, true);
					wait = gap(mc);
					return Status.RUNNING;
				}
				return Status.DONE;
			}
		};
	}

	/**
	 * The inventory is full (the string command fills every free slot): trade the string into emeralds (20 to 1), or
	 * put spare emeralds in the input chest.
	 */
	private static void makeRoom(Minecraft mc, long sig, int keepEmeralds) {
		Inventory inv = mc.player.getInventory();
		var st = TradeBook.get().stringTrade(around(mc));
		if (st != null && count(inv, s -> s.is(Items.STRING)) >= st.getValue().price) {
			stringTrade(mc, 0, 0, sig);
			return;
		}
		int emeralds = count(inv, s -> s.is(Items.EMERALD));
		if (emeralds - keepEmeralds >= 64 && !cfg.inputChests.isEmpty()) {
			List<Step> s = new ArrayList<>();
			s.add(walkTo(cfg.baseVec(), "Walking to the base"));
			for (int[] c : cfg.inputChests) {
				s.add(openBlock(FactoryConfig.pos(c), "input chest", x -> x instanceof ContainerScreen));
				s.add(bankEmeralds(Math.max(keepEmeralds, 64)));
				s.add(closeScreens());
			}
			plan("bank spare emeralds", sig, s.toArray(new Step[0]));
			return;
		}
		stop("Inventory full - make some room (the factory needs free slots for books and items).");
	}

	/** Record the open villager's trades. */
	static Step record(UUID uuid) {
		return new Step("Reading trades") {
			Status run(Minecraft mc) {
				if (!(mc.screen instanceof MerchantScreen ms)) return fail("The trading screen closed");
				Entity e = entity(mc, uuid);
				if (!(e instanceof Villager v)) return fail("Villager gone");
				MerchantOffers offers = ms.getMenu().getOffers();
				TradeBook.Trader tr = TradeBook.get().record(v, offers);
				surveyedThisRun.add(uuid.toString());
				failures = 0;
				StringBuilder sb = new StringBuilder();
				for (TradeBook.Offer o : tr.offers) {
					if (sb.length() > 0) sb.append(", ");
					sb.append(o.what()).append(" (").append(o.cost()).append(")");
				}
				say(tr.label() + ": " + (sb.length() == 0 ? "no trades" : sb));
				return Status.DONE;
			}
		};
	}

	/** Click the trade's button in the open villager screen, scrolling the list if needed; -1 = not offered. */
	static int selectTrade(Minecraft mc, MerchantScreen ms, TradeBook.Offer want) {
		MerchantOffers offers = ms.getMenu().getOffers();
		int idx = -1;
		for (int i = 0; i < offers.size(); i++) if (TradeBook.matches(offers.get(i), want)) idx = i;
		if (idx < 0) return -1;
		int row = idx - ms.scrollOff;
		double listX = ms.leftPos + 5 + 44;
		if (row < 0 || row > 6) {
			Input.scroll(ms, listX, ms.topPos + 18 + 70, row < 0 ? 1 : -1);
			return -2; // scrolled; click next time
		}
		Input.click(ms, listX, ms.topPos + 18 + 20 * row + 10, 0, false);
		return idx;
	}

	/** Buy {@code n} of a book: select the trade, take the result, put it in the inventory, repeat. */
	static Step buy(TradeBook.Trader trader, TradeBook.Offer offer, int n, Predicate<ItemStack> isIt) {
		return new Step("Buying " + offer.what() + " x" + n) {
			int phase, got, sinceSelect, before;

			Status run(Minecraft mc) {
				if (!(mc.screen instanceof MerchantScreen ms)) return fail("The trading screen closed");
				MerchantMenu menu = ms.getMenu();
				Inventory inv = mc.player.getInventory();
				if (t == 1) {
					before = count(inv, isIt);
					// the price the villager asks today
					for (MerchantOffer mo : menu.getOffers()) {
						if (TradeBook.matches(mo, offer)) {
							int live = mo.getCostA().getCount();
							if (!offer.manual && live != offer.price) {
								offer.price = live;
								TradeBook.get().save();
							}
							if (count(inv, s -> s.is(Kit.item(offer.costA))) < live) {
								say(trader.label() + " now wants " + live + " for " + offer.what() + ".");
								return Status.DONE;
							}
						}
					}
				}
				int have = count(inv, isIt) - before;
				got = Math.max(got, have);
				ItemStack carried = menu.getCarried();
				if (!carried.isEmpty()) { // put the bought book down
					int slot = emptyMenuSlot(menu, inv);
					if (slot < 0) return fail("No free slot for the book");
					Input.clickSlot(mc, slot, 0, false);
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (got >= n) {
					bought += got;
					failures = 0;
					return Status.DONE;
				}
				ItemStack result = menu.getSlot(2).getItem();
				if (phase == 1 && !result.isEmpty() && isIt.test(result)) {
					Input.clickSlot(mc, 2, 0, false); // take one
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (phase == 1 && sinceSelect++ < 10 + latencyTicks(mc)) return Status.RUNNING;
				int sel = selectTrade(mc, ms, offer);
				if (sel == -1) return fail(trader.label() + " doesn't offer " + offer.what() + " any more - run /kitfactory survey");
				phase = sel >= 0 ? 1 : 0;
				sinceSelect = 0;
				if (phase == 1 && !menu.getSlot(2).getItem().isEmpty() && isIt.test(menu.getSlot(2).getItem())) {
					Input.clickSlot(mc, 2, 0, false); // the result shows at once: take it this tick
				}
				wait = gap(mc);
				if (t > 400) return fail("Buying took too long (" + got + "/" + n + ")");
				return Status.RUNNING;
			}
		};
	}

	/** Trade string for emeralds until there are enough emeralds and enough XP (or the string runs out). */
	static Step tradeString(TradeBook.Offer offer, int targetEmeralds, int targetLevel) {
		return new Step("Trading string") {
			long pendingSince, lastSig = -1;
			int stringAtAsk, still;

			Status run(Minecraft mc) {
				if (!(mc.screen instanceof MerchantScreen ms)) return fail("The trading screen closed");
				MerchantMenu menu = ms.getMenu();
				Inventory inv = mc.player.getInventory();
				int emeralds = count(inv, s -> s.is(Items.EMERALD));
				int level = mc.player.experienceLevel;
				status = "Trading string: " + emeralds + (targetEmeralds > 0 ? "/" + targetEmeralds : "") + " emeralds, level "
						+ level + (targetLevel > 0 ? "/" + targetLevel : "");
				boolean met = emeralds >= targetEmeralds && level >= targetLevel;
				int inInv = count(inv, s -> s.is(Items.STRING));
				int string = inInv + (menu.getSlot(0).getItem().is(Items.STRING) ? menu.getSlot(0).getItem().getCount() : 0);
				if (met && string < offer.price) {
					failures = 0;
					return Status.DONE;
				}
				long now = System.currentTimeMillis();
				if (cfg.stringInGui) {
					// ask before it runs out, without leaving the screen
					if (pendingSince > 0 && string > stringAtAsk) pendingSince = 0;
					if (pendingSince > 0 && now - pendingSince > 4000) {
						pendingSince = 0;
						commandCooldownUntil = now + cfg.stringRetrySeconds * 1000L;
						say("/" + cfg.stringCommand + " gave no string; trying again in " + cfg.stringRetrySeconds + "s.");
					}
					if (!met && pendingSince == 0 && inInv < cfg.stringAskBelow && free(inv) >= 3 && now >= commandCooldownUntil
							&& now - lastCommandMs >= cfg.stringIntervalMs) {
						stringAtAsk = string;
						pendingSince = now;
						lastCommandMs = now;
						mc.player.connection.sendCommand(cfg.stringCommand);
					}
				}
				ItemStack paid = menu.getSlot(0).getItem();
				int inSlot = paid.is(Items.STRING) ? paid.getCount() : 0;
				long sig = emeralds * 100_003L + string;
				if (sig != lastSig || string < offer.price) {
					lastSig = sig;
					still = 0;
				} else if (++still > 100) {
					return fail("String trading isn't getting anywhere (" + string + " string, " + emeralds + " emeralds)");
				}
				if (menu.getSlot(2).getItem().is(Items.EMERALD)) {
					if (roomFor(inv, Items.EMERALD) < 1) {
						failures = 0;
						return Status.DONE; // nowhere for the emeralds: decide stores some
					}
					Input.clickSlot(mc, 2, 0, true); // shift-click: as many trades as the string in the slot pays for
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (string < offer.price) {
					if (met || !cfg.stringInGui || now < commandCooldownUntil) {
						failures = 0;
						return Status.DONE; // decide gets string the slow way / waits out the cooldown
					}
					status = "Waiting for string";
					return Status.RUNNING;
				}
				if (inSlot > 0 && roomFor(inv, Items.STRING) < inSlot) {
					// every slot is full (the string command fills them all), so the trade button can't put the
					// leftover back: top the trade slot up from a stack by hand and put the rest where it came from
					int from = -1;
					for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.STRING) && (from < 0 || inv.getItem(i).getCount() > inv.getItem(from).getCount())) from = i;
					if (from < 0) return fail("String stuck in the trade slot");
					int slot = ItemQueue.menuSlot(menu, inv, from);
					Input.clickSlot(mc, slot, 0, false);
					Input.clickSlot(mc, 0, 0, false);
					if (!menu.getCarried().isEmpty()) Input.clickSlot(mc, slot, 0, false);
				} else {
					// one round per tick: the trade button refills the trade slot from the inventory, then shift-click
					int sel = selectTrade(mc, ms, offer);
					if (sel == -1) return fail("The fisherman doesn't buy string any more - run /kitfactory survey");
					if (sel == -2) return Status.RUNNING; // scrolled the list; click next tick
				}
				if (menu.getSlot(2).getItem().is(Items.EMERALD) && roomFor(inv, Items.EMERALD) > 0) Input.clickSlot(mc, 2, 0, true);
				wait = gap(mc);
				return Status.RUNNING;
			}
		};
	}

	/** Auto Anvil does the combining: the batch goes in its queue, then wait until it is done. */
	static Step anvil(List<Integer> invSlots) {
		return new Step("Combining on the anvil") {
			boolean started, stoppedForXp;

			Status run(Minecraft mc) {
				if (!started) {
					if (!(mc.screen instanceof AnvilScreen)) return fail("The anvil closed");
					if (t < 3) return Status.RUNNING; // the panel's first refresh
					ItemQueue.ENTRIES.clear();
					Inventory inv = mc.player.getInventory();
					for (int i : invSlots) ItemQueue.ENTRIES.add(new ItemQueue.Entry(i, inv.getItem(i).copy()));
					AutoAnvil.startQueue();
					if (!AutoAnvil.running()) return fail("Auto Anvil didn't start");
					started = true;
					return Status.RUNNING;
				}
				if (AutoAnvil.running()) {
					Runner r = AutoAnvil.runner;
					status = "Anvil: " + r.status;
					if (r.waitingForXp && !stoppedForXp) {
						stoppedForXp = true; // out of levels: put things back, go trade for more, come back
						AutoAnvil.stop();
					}
					return Status.RUNNING;
				}
				if (stoppedForXp) {
					failures = 0;
					return Status.DONE;
				}
				Runner r = AutoAnvil.runner;
				if (r != null && r.error != null && !"Anvil closed".equals(r.error)) return fail("Anvil: " + r.error);
				failures = 0;
				return Status.DONE;
			}
		};
	}

	/** A new anvil where the old one broke: hotbar key, look at the block below, use. */
	static Step placeAnvil(BlockPos pos) {
		return new Step("Placing a new anvil") {
			int phase, aligned, pressedAt;

			Status run(Minecraft mc) {
				if (mc.level.getBlockState(pos).is(BlockTags.ANVIL)) {
					failures = 0;
					say("Placed a new anvil.");
					return Status.DONE;
				}
				Inventory inv = mc.player.getInventory();
				int hot = -1, main = -1;
				for (int i = 0; i < 36; i++) {
					if (!isAnvilItem(inv.getItem(i))) continue;
					if (i < 9) hot = i;
					else main = i;
				}
				boolean onCursor = mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen cur && isAnvilItem(cur.getMenu().getCarried());
				if (hot < 0 && main < 0 && !onCursor) return fail("No spare anvil in the inventory");
				if (hot < 0 || onCursor) {
					// open the inventory and swap it into the last hotbar slot: pick up, click the hotbar slot, put the other item back
					if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen is)) {
						if (mc.screen != null) Input.escape(mc);
						else KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyInventory));
						wait = 3;
						return Status.RUNNING;
					}
					var menu = is.getMenu();
					ItemStack carried = menu.getCarried();
					if (carried.isEmpty() && main >= 0) Input.clickSlot(mc, ItemQueue.menuSlot(menu, inv, main), 0, false);
					else if (isAnvilItem(carried)) Input.clickSlot(mc, ItemQueue.menuSlot(menu, inv, 8), 0, false);
					else Input.clickSlot(mc, emptyMenuSlot(menu, inv), 0, false);
					wait = gap(mc);
					return Status.RUNNING;
				}
				if (mc.screen != null) {
					if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen is && !is.getMenu().getCarried().isEmpty()) {
						Input.clickSlot(mc, emptyMenuSlot(is.getMenu(), inv), 0, false);
						wait = gap(mc);
						return Status.RUNNING;
					}
					Input.escape(mc);
					wait = 2;
					return Status.RUNNING;
				}
				if (inv.getSelectedSlot() != hot) {
					Input.pressHotbar(mc, hot);
					wait = 2;
					return Status.RUNNING;
				}
				if (pressedAt > 0) {
					if (t - pressedAt < 20) return Status.RUNNING;
					pressedAt = 0;
					aligned = 0;
					if (++phase >= 4) return fail("Couldn't place the anvil at " + pos.toShortString());
				}
				BlockPos below = pos.below();
				Vec3 top = new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
				if (top.distanceTo(mc.player.getEyePosition()) > mc.player.blockInteractionRange() - 0.2) return fail("The anvil spot is out of reach from the base");
				float[] a = Input.anglesTo(mc.player, top);
				if (Input.turnToward(mc, a[0], a[1], 30f) < 1.5f) aligned++;
				else aligned = 0;
				if (aligned >= 2 && mc.hitResult instanceof BlockHitResult bh && bh.getBlockPos().equals(below) && bh.getDirection() == Direction.UP) {
					Input.pressUse(mc);
					pressedAt = t;
				} else if (aligned >= 8) {
					return fail("Can't aim at the block under the anvil spot");
				}
				return Status.RUNNING;
			}
		};
	}

	// ---------------------------------------------------------------- helpers

	static boolean isAnvilItem(ItemStack s) {
		return s.is(Items.ANVIL) || s.is(Items.CHIPPED_ANVIL) || s.is(Items.DAMAGED_ANVIL);
	}

	static int latencyTicks(Minecraft mc) {
		PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? 0 : (int) Math.ceil(Math.max(0, info.getLatency()) / 50.0);
	}

	/** Ticks between two clicks. */
	static int gap(Minecraft mc) {
		return Math.max(0, AutoAnvil.CONFIG.actionDelayTicks);
	}

	static int count(Inventory inv, Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && p.test(s)) n += s.getCount();
		}
		return n;
	}

	static int free(Inventory inv) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) n++;
		return n;
	}

	/** How many more of an item fit (free slots and part stacks). */
	static int roomFor(Inventory inv, Item item) {
		int max = new ItemStack(item).getMaxStackSize(), n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) n += max;
			else if (s.is(item) && !s.isEnchanted()) n += max - s.getCount();
		}
		return n;
	}

	static int emptyMenuSlot(AbstractContainerMenu menu, Inventory inv) {
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) {
				int ms = ItemQueue.menuSlot(menu, inv, i);
				if (ms >= 0) return ms;
			}
		}
		return -1;
	}

	static Map<Item, Integer> finishedCounts(Inventory inv, Map<Item, Map<Holder<Enchantment>, Integer>> kits) {
		Map<Item, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			var kit = kits.get(s.getItem());
			if (kit != null && Kit.finished(s, kit)) m.merge(s.getItem(), 1, Integer::sum);
		}
		return m;
	}

	static long signature(Inventory inv) {
		long h = 1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			h = h * 31 + (s.isEmpty() ? 0 : ItemStack.hashItemAndComponents(s) * 7L + s.getCount());
		}
		return h;
	}

	static String key(int[] p) {
		return p[0] + "," + p[1] + "," + p[2];
	}

	/**
	 * The villager offer to buy this item from: none of its enchantments may clash with the kit (Fire Protection would
	 * block Protection IV for good); fewer unwanted extras first, then cheapest once the kit books it already has are
	 * counted as saved. Null when no villager sells a usable one.
	 */
	record Gear(TradeBook.Trader trader, TradeBook.Offer offer, boolean grind) {
	}

	static Gear bestGear(Minecraft mc, Item type, Map<Holder<Enchantment>, Integer> kit) {
		var clean = cleanGear(mc, type, kit);
		if (clean != null) return new Gear(clean.getKey(), clean.getValue(), false);
		if (cfg.grindstone == null) return null;
		// every offer clashes: buy the cheapest and grind it clean
		String id = Kit.id(type);
		Gear best = null;
		Predicate<TradeBook.Trader> here = around(mc);
		for (TradeBook.Trader t : TradeBook.get().traders) {
			if (!here.test(t)) continue;
			for (TradeBook.Offer o : t.offers) {
				if (o.enabled && o.result.equals(id) && !o.isBook() && (best == null || o.price < best.offer().price)) best = new Gear(t, o, true);
			}
		}
		return best;
	}

	/** Enchantments on an item that block one of the kit's (and so have to be ground off). */
	static List<String> clashes(ItemStack s, Map<Holder<Enchantment>, Integer> kit) {
		List<String> out = new ArrayList<>();
		for (var en : dev.autoanvil.run.Analysis.enchantments(s).entrySet()) {
			if (kit.containsKey(en.getKey())) continue;
			for (Holder<Enchantment> k : kit.keySet()) {
				if (!Enchantment.areCompatible(en.getKey(), k)) {
					out.add(dev.autoanvil.ui.Panel.name(en.getKey(), en.getIntValue()) + " (blocks " + dev.autoanvil.ui.Panel.name(k, kit.get(k)) + ")");
					break;
				}
			}
		}
		return out;
	}

	static java.util.Map.Entry<TradeBook.Trader, TradeBook.Offer> cleanGear(Minecraft mc, Item type, Map<Holder<Enchantment>, Integer> kit) {
		String id = Kit.id(type);
		var reg = dev.autoanvil.Catalog.registry(mc.level.registryAccess());
		java.util.Map.Entry<TradeBook.Trader, TradeBook.Offer> best = null;
		long bestScore = Long.MAX_VALUE;
		Predicate<TradeBook.Trader> here = around(mc);
		for (TradeBook.Trader t : TradeBook.get().traders) {
			if (!here.test(t)) continue;
			for (TradeBook.Offer o : t.offers) {
				if (!o.enabled || !o.result.equals(id) || o.isBook()) continue;
				boolean clash = false;
				int extras = 0;
				long saved = 0;
				for (var en : (o.itemEnchants == null ? Map.<String, Integer>of() : o.itemEnchants).entrySet()) {
					var h = reg.get(net.minecraft.resources.Identifier.parse(en.getKey()));
					if (h.isEmpty()) continue;
					Holder<Enchantment> he = h.get();
					Integer want = kit.get(he);
					if (want == null) {
						extras++;
						for (Holder<Enchantment> k : kit.keySet()) if (!Enchantment.areCompatible(he, k)) clash = true;
					} else if (en.getValue() >= want) {
						var book = TradeBook.get().cheapestBook(he, want, here);
						saved += book == null ? 0 : book.getValue().price;
					}
				}
				if (clash) continue;
				long score = extras * 100000L + o.price - saved;
				if (score < bestScore) {
					bestScore = score;
					best = java.util.Map.entry(t, o);
				}
			}
		}
		return best;
	}

	/** Recorded traders that are loaded right now (a villager that died or moved away is skipped). */
	/**
	 * Traders still there: loaded, or too far away for the server to have sent them to us yet (servers only send
	 * villagers within 16-64 blocks), in which case the survey is trusted. One missing close by is gone.
	 */
	static Predicate<TradeBook.Trader> around(Minecraft mc) {
		Vec3 me = mc.player.position();
		return t -> entity(mc, UUID.fromString(t.uuid)) != null || Nav.horiz(me, new Vec3(t.x, t.y, t.z)) > 16;
	}

	static Entity entity(Minecraft mc, UUID uuid) {
		for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(uuid)) return e;
		return null;
	}

	/** Villagers with a job, loaded around the route. */
	static List<Villager> villagers(Minecraft mc) {
		List<Villager> out = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Villager v) || !v.isAlive()) continue;
			String prof = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
			if (prof.equals("none") || prof.equals("nitwit")) continue;
			out.add(v);
		}
		return out;
	}

	static boolean inReach(Minecraft mc, Vec3 eye, AABB box) {
		return Nav.toBox(eye, box) <= mc.player.entityInteractionRange() - 0.3;
	}

	/**
	 * Where to stand to reach something at {@code at}: the closest point of the walkway, or if that's out of reach, a
	 * spot up to {@link #MAX_STEP_OFF} blocks off it toward the thing, over open floor. Null if neither reaches.
	 */
	static Vec3 standFor(Minecraft mc, List<Vec3> route, Vec3 at, AABB box) {
		Vec3 spot = Nav.spotFor(route, at);
		double eyeH = mc.player.getEyeHeight();
		if (inReach(mc, spot.add(0, eyeH, 0), box)) return spot;
		double dx = box.getCenter().x - spot.x, dz = box.getCenter().z - spot.z, len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-6) return null;
		for (double d = 0.25; d <= MAX_STEP_OFF + 1e-9; d += 0.25) {
			Vec3 q = new Vec3(spot.x + dx / len * d, spot.y, spot.z + dz / len * d);
			if (!standable(mc, q)) return null;
			if (Nav.toBox(q.add(0, eyeH, 0), box) <= mc.player.entityInteractionRange() - 0.6) return q;
		}
		return null;
	}

	/** Room for the player at {@code feet} with floor under it, so stepping there neither bumps into nor falls off anything. */
	static boolean standable(Minecraft mc, Vec3 feet) {
		AABB body = mc.player.getDimensions(Pose.STANDING).makeBoundingBox(feet).deflate(0.02);
		if (!mc.level.noCollision(mc.player, body)) return false;
		AABB under = new AABB(feet.x - 0.25, feet.y - 0.3, feet.z - 0.25, feet.x + 0.25, feet.y - 0.01, feet.z + 0.25);
		return !mc.level.noCollision(mc.player, under);
	}

	/** If the villager is out of reach from where the walkway left us, step straight off it toward the villager. */
	static Step stepUp(UUID uuid) {
		return new Step("Stepping up to the villager") {
			Step walk;

			Status run(Minecraft mc) {
				if (walk == null) {
					Entity e = entity(mc, uuid);
					if (e == null || inReach(mc, mc.player.getEyePosition(), e.getBoundingBox())) return Status.DONE; // (openVillager reports a missing one)
					Vec3 to = standFor(mc, cfg.route(), e.position(), e.getBoundingBox());
					if (to == null) return fail("The villager at " + e.blockPosition().toShortString() + " is out of reach from the walkway");
					walk = walk(to, label, true);
				}
				if (walk.wait > 0) {
					walk.wait--;
					return Status.RUNNING;
				}
				walk.t++;
				Status st = walk.run(mc);
				if (st == Status.FAILED) return fail(walk.error);
				return st;
			}
		};
	}

	static String cantAim(Minecraft mc, Entity e) {
		double d = Nav.toBox(mc.player.getEyePosition(), e.getBoundingBox());
		return d > mc.player.entityInteractionRange() - 0.05
				? "The villager at " + e.blockPosition().toShortString() + " is out of reach from here (" + String.format("%.1f", d) + " blocks, reach is 3)"
				: "Can't see the villager at " + e.blockPosition().toShortString() + ": blocks or another mob are in the way of every part of it";
	}

	/**
	 * Points to aim at on an entity that the crosshair reaches from here without a block or another mob in the way:
	 * the middle of it top to bottom, then its left and right edges (around a fence post or through a gap), best first.
	 */
	static List<Vec3> aimPoints(Minecraft mc, Entity e) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		AABB box = e.getBoundingBox();
		Vec3 c = box.getCenter();
		double tx = eye.x - c.x, tz = eye.z - c.z, tl = Math.sqrt(tx * tx + tz * tz);
		if (tl < 1e-6) return List.of();
		tx /= tl;
		tz /= tl;
		double half = Math.min(box.getXsize(), box.getZsize()) / 2;
		double reach = p.entityInteractionRange();
		List<Vec3> out = new ArrayList<>();
		for (double side : new double[] {0, -0.9, 0.9}) {
			for (double h : new double[] {0.83, 0.6, 0.4, 0.2, 0.06, 0.95}) {
				// on the side facing us, so the line of sight leans out as far as the box allows
				Vec3 q = new Vec3(c.x + tx * half * 0.8 - tz * half * side, box.minY + box.getYsize() * h, c.z + tz * half * 0.8 + tx * half * side);
				Vec3 far = eye.add(q.subtract(eye).normalize().scale(reach));
				var entry = box.clip(eye, far);
				if (entry.isEmpty() || entry.get().distanceTo(eye) > reach - 0.05) continue;
				Vec3 hit = entry.get();
				if (mc.level.clip(new ClipContext(eye, hit, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) continue;
				boolean other = false;
				for (Entity o : mc.level.getEntities(p, new AABB(eye, hit).inflate(1), o -> o != e && !o.isSpectator() && o.isPickable())) {
					if (o.getBoundingBox().inflate(o.getPickRadius()).clip(eye, hit).isPresent()) {
						other = true;
						break;
					}
				}
				if (!other) out.add(q);
			}
		}
		return out;
	}

	/** Where to stand for a recorded trader: next to where it is now (or was). */
	static Vec3 spot(Minecraft mc, List<Vec3> route, TradeBook.Trader t) {
		Entity e = entity(mc, UUID.fromString(t.uuid));
		Vec3 at = e != null ? e.position() : new Vec3(t.x, t.y, t.z);
		return Nav.spotFor(route, at);
	}

	public static String progress() {
		StringBuilder sb = new StringBuilder();
		for (var en : cfg.targets.entrySet()) {
			int d = cfg.done.getOrDefault(en.getKey(), 0);
			if (en.getValue() <= 0 && d <= 0) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(TradeBook.capital(en.getKey().replace("minecraft:", "").replace("diamond_", ""))).append(' ').append(d).append('/').append(en.getValue());
		}
		return sb.toString();
	}

	public static List<String> hudLines(Minecraft mc) {
		List<String> l = new ArrayList<>();
		if (!running) return l;
		l.add("Kit Factory" + (surveyOnly ? " (survey)" : "") + ": " + status);
		if (!surveyOnly) {
			l.add(progress());
			l.add("Emeralds " + count(mc.player.getInventory(), s -> s.is(Items.EMERALD)) + " | level " + mc.player.experienceLevel
					+ " | bought " + bought + " | crafted " + crafted);
		}
		return l;
	}

	public static int boughtCount() {
		return bought;
	}
}
