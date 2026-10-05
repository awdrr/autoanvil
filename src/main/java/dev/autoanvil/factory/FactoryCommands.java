package dev.autoanvil.factory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** {@code /kitfactory}: mark the hall, survey it, start and stop. */
public final class FactoryCommands {
	private FactoryCommands() {
	}

	/** Screens can't be opened from inside the chat that ran the command; this one opens next tick. */
	public static java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> openNextTick;

	/** Every item that takes enchantments (shield, bow, netherite_sword...), and the diamond kit's short names. */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<FabricClientCommandSource> ITEMS = (c, b) -> {
		List<String> names = new ArrayList<>();
		for (String id : FactoryConfig.ITEMS) names.add(id.replace("minecraft:diamond_", ""));
		for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (!FactoryConfig.enchantable(item)) continue;
			var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
			if (key.getNamespace().equals("minecraft")) names.add(key.getPath());
		}
		return net.minecraft.commands.SharedSuggestionProvider.suggest(names, b);
	};

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("kitfactory")
				.executes(FactoryCommands::help)
				.then(ClientCommandManager.literal("start").executes(c -> run(c, () -> Factory.start(Minecraft.getInstance(), false))))
				.then(ClientCommandManager.literal("survey").executes(c -> run(c, () -> Factory.start(Minecraft.getInstance(), true))))
				.then(ClientCommandManager.literal("stop").executes(c -> run(c, () -> Factory.stop("Stopped."))))
				.then(ClientCommandManager.literal("status").executes(FactoryCommands::status))
				.then(ClientCommandManager.literal("trades").executes(c -> run(c, () -> openNextTick = () -> new TradesScreen(null))))
				.then(ClientCommandManager.literal("items").executes(c -> run(c, () -> openNextTick = () -> new ItemsScreen(null))))
				.then(ClientCommandManager.literal("buy")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(c -> source(c, "buy", -1))
								.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(0, 10000))
										.executes(c -> source(c, "buy", IntegerArgumentType.getInteger(c, "count"))))))
				.then(ClientCommandManager.literal("craft")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(c -> source(c, "craft", -1))
								.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(0, 10000))
										.executes(c -> source(c, "craft", IntegerArgumentType.getInteger(c, "count"))))))
				.then(ClientCommandManager.literal("take")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(c -> source(c, "take", -1))
								.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(0, 10000))
										.executes(c -> source(c, "take", IntegerArgumentType.getInteger(c, "count"))))))
				.then(ClientCommandManager.literal("add")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(c -> source(c, null, 27))
								.then(ClientCommandManager.argument("count", IntegerArgumentType.integer(0, 10000))
										.executes(c -> source(c, null, IntegerArgumentType.getInteger(c, "count"))))))
				.then(ClientCommandManager.literal("remove")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(FactoryCommands::remove)))
				.then(ClientCommandManager.literal("enchants")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(c -> {
							String id = FactoryConfig.resolve(StringArgumentType.getString(c, "item"));
							if (id == null) return notAnItem(c);
							openNextTick = () -> new EnchantsScreen(null, id);
							return 1;
						})))
				.then(ClientCommandManager.literal("base").executes(FactoryCommands::base))
				.then(ClientCommandManager.literal("path")
						.then(ClientCommandManager.literal("add").executes(FactoryCommands::pathAdd))
						.then(ClientCommandManager.literal("clear").executes(c -> {
							Factory.cfg.path.clear();
							Factory.cfg.save();
							c.getSource().sendFeedback(Component.literal("Walkway cleared."));
							return 1;
						})))
				.then(ClientCommandManager.literal("chest")
						.then(ClientCommandManager.literal("input").executes(c -> chest(c, true)))
						.then(ClientCommandManager.literal("output").executes(c -> chest(c, false)))
						.then(ClientCommandManager.literal("clear").executes(c -> {
							Factory.cfg.inputChests.clear();
							Factory.cfg.outputChests.clear();
							Factory.cfg.save();
							c.getSource().sendFeedback(Component.literal("Chests cleared."));
							return 1;
						})))
				.then(ClientCommandManager.literal("set")
						.then(ClientCommandManager.argument("item", StringArgumentType.word()).suggests((c, b) -> {
							b.suggest("all");
							return ITEMS.getSuggestions(c, b);
						}).then(ClientCommandManager.argument("count", IntegerArgumentType.integer(0, 10000)).executes(FactoryCommands::set))))
				.then(ClientCommandManager.literal("reset").executes(c -> {
					Factory.cfg.done.clear();
					Factory.cfg.save();
					c.getSource().sendFeedback(Component.literal("Progress reset: counting from 0 again."));
					return 1;
				}))
				.then(ClientCommandManager.literal("dropspot").executes(FactoryCommands::dropSpot)
						.then(ClientCommandManager.literal("clear").executes(c -> {
							Factory.cfg.dropSpot = null;
							Factory.cfg.save();
							c.getSource().sendFeedback(Component.literal("Drop spot cleared."));
							return 1;
						})))
				.then(ClientCommandManager.literal("fisherman")
						.then(ClientCommandManager.literal("xp").executes(FactoryCommands::xpFisherman))
						.then(ClientCommandManager.literal("clear").executes(c -> {
							Factory.cfg.xpFisherman = null;
							Factory.cfg.save();
							c.getSource().sendFeedback(Component.literal("No fisherman for levels: one fisherman does emeralds and levels."));
							return 1;
						})))
				.then(ClientCommandManager.literal("spare")
						.then(ClientCommandManager.literal("chest").executes(c -> spare(c, "chest")))
						.then(ClientCommandManager.literal("drop").executes(c -> spare(c, "drop"))))
				.then(ClientCommandManager.literal("forget").executes(c -> {
					TradeBook.reset();
					c.getSource().sendFeedback(Component.literal("All recorded trades forgotten."));
					return 1;
				}))
				.then(ClientCommandManager.literal("string")
						.then(ClientCommandManager.argument("command", StringArgumentType.greedyString()).executes(c -> {
							String cmd = StringArgumentType.getString(c, "command").trim();
							if (cmd.startsWith("/")) cmd = cmd.substring(1);
							Factory.cfg.stringCommand = cmd;
							Factory.cfg.save();
							c.getSource().sendFeedback(Component.literal("String command: /" + cmd));
							return 1;
						})))));
	}

	private static int run(CommandContext<FabricClientCommandSource> c, Runnable r) {
		r.run();
		return Command.SINGLE_SUCCESS;
	}

	private static int help(CommandContext<FabricClientCommandSource> c) {
		String[] lines = {
				"Kit Factory setup:",
				" 1. Stand where you reach the anvil, crafting table and chests: /kitfactory base",
				" 2. Walk the walkway; at each corner and at the far end: /kitfactory path add",
				" 3. Look at the supply chest(s): /kitfactory chest input  (output chest(s): /kitfactory chest output)",
				" 4. /kitfactory survey  - walks the hall and opens every villager once",
				" 5. /kitfactory trades  - check / edit prices",
				" 6. /kitfactory items  - how many of each, bought or crafted (27 each by default), then /kitfactory start",
				"Also: stop, status, buy <item> [n], craft <item> [n], set <item|all> <n>, reset, forget, string <command>,",
				"  dropspot (where to throw spare emerald blocks), spare chest|drop, fisherman xp|clear (look at it),",
				"  add <item> [n] (any item: shield, bow...), remove <item>, take <item> [n] (ready-made from the chest), enchants <item>"};
		for (String l : lines) c.getSource().sendFeedback(Component.literal(l));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c) {
		FactoryConfig f = Factory.cfg;
		c.getSource().sendFeedback(Component.literal("Base " + (f.base == null ? "not set" : BlockPos.containing(f.baseVec()).toShortString())
				+ ", walkway points " + f.path.size() + ", anvil " + (f.anvil == null ? "-" : FactoryConfig.pos(f.anvil).toShortString())
				+ ", crafting table " + (f.craftingTable == null ? "-" : FactoryConfig.pos(f.craftingTable).toShortString())
				+ ", input chests " + f.inputChests.size() + ", output chests " + f.outputChests.size()));
		c.getSource().sendFeedback(Component.literal("Villagers recorded: " + TradeBook.get().traders.size() + ". Progress: " + Factory.progress()));
		c.getSource().sendFeedback(Component.literal("String command: /" + f.stringCommand + (Factory.running() ? ". Running: " + Factory.status : ". Not running.")));
		return 1;
	}

	private static int base(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player.getEyePosition();
		double reach = mc.player.blockInteractionRange() - 0.3;
		BlockPos anvil = null, table = null, grind = null;
		double da = 99, dt = 99, dg = 99;
		for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(eye).offset(-5, -5, -5), BlockPos.containing(eye).offset(5, 5, 5))) {
			double d = Vec3.atCenterOf(p).distanceTo(eye);
			if (d > reach) continue;
			var st = mc.level.getBlockState(p);
			if (st.is(BlockTags.ANVIL) && d < da) {
				da = d;
				anvil = p.immutable();
			} else if (st.is(Blocks.CRAFTING_TABLE) && d < dt) {
				dt = d;
				table = p.immutable();
			} else if (st.is(Blocks.GRINDSTONE) && d < dg) {
				dg = d;
				grind = p.immutable();
			}
		}
		Factory.cfg.base = new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()};
		if (anvil != null) Factory.cfg.anvil = FactoryConfig.arr(anvil);
		if (table != null) Factory.cfg.craftingTable = FactoryConfig.arr(table);
		Factory.cfg.grindstone = grind == null ? null : FactoryConfig.arr(grind);
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Base set here. Anvil: " + (anvil == null ? "none in reach!" : anvil.toShortString())
				+ ", crafting table: " + (table == null ? "none in reach!" : table.toShortString())
				+ ", grindstone: " + (grind == null ? "none (optional)" : grind.toShortString())));
		return 1;
	}

	private static int xpFisherman(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh)
				|| !(eh.getEntity() instanceof net.minecraft.world.entity.npc.villager.Villager v)) {
			c.getSource().sendError(Component.literal("Look at the fisherman (the one with the fire in front) first."));
			return 0;
		}
		Factory.cfg.xpFisherman = v.getStringUUID();
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Fisherman for levels set: trading string there throws the emeralds out of the window,"
				+ " the way you look at it, so put fire (or lava) between you and it. Emeralds for books come from the other fisherman."
				+ (TradeBook.get().find(v.getStringUUID()) == null ? " Run /kitfactory survey so its trades are known." : "")));
		return 1;
	}

	private static int dropSpot(CommandContext<FabricClientCommandSource> c) {
		var p = Minecraft.getInstance().player;
		Factory.cfg.dropSpot = new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Drop spot set: spare emerald blocks get thrown from here, the way you're looking"
				+ " (" + (Factory.cfg.spareEmeralds.equals("drop") ? "always" : "once the input chests are full") + "). Keep it near the walkway"
				+ " and throw them somewhere the factory won't walk over them (lava, cactus, off an edge)."));
		return 1;
	}

	private static int spare(CommandContext<FabricClientCommandSource> c, String mode) {
		Factory.cfg.spareEmeralds = mode;
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal(mode.equals("chest")
				? "Spare emeralds: packed into blocks and stored in the input chests (thrown at the drop spot once they're full)."
				: "Spare emeralds: packed into blocks and thrown at the drop spot" + (Factory.cfg.dropSpot == null ? " - mark it with /kitfactory dropspot." : ".")));
		return 1;
	}

	private static int notAnItem(CommandContext<FabricClientCommandSource> c) {
		c.getSource().sendError(Component.literal("\"" + StringArgumentType.getString(c, "item") + "\" isn't an item that takes enchantments (try shield, bow, netherite_sword)."));
		return 0;
	}

	private static int remove(CommandContext<FabricClientCommandSource> c) {
		String id = FactoryConfig.resolve(StringArgumentType.getString(c, "item"));
		if (id == null || Factory.cfg.targets.remove(id) == null) return notAnItem(c);
		Factory.cfg.setSource(id, "craft");
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Not making " + new net.minecraft.world.item.ItemStack(Kit.item(id)).getHoverName().getString() + " any more."));
		return 1;
	}

	/**
	 * buy / craft / take / add: an item to make (any that takes enchantments), where it comes from (add: bought if a
	 * villager here sells it, else crafted if it can be, else from the chest), and optionally how many.
	 */
	private static int source(CommandContext<FabricClientCommandSource> c, String src, int n) {
		String id = FactoryConfig.resolve(StringArgumentType.getString(c, "item"));
		if (id == null) return notAnItem(c);
		FactoryConfig f = Factory.cfg;
		String name = new net.minecraft.world.item.ItemStack(Kit.item(id)).getHoverName().getString();
		if ("craft".equals(src) && Kit.recipe(Kit.item(id)) == null) {
			c.getSource().sendError(Component.literal(name + " can't be crafted at a crafting table: use buy or take (ready-made from the input chest)."));
			return 0;
		}
		boolean added = !f.targets.containsKey(id);
		f.setSource(id, src != null ? src : added ? f.defaultSource(id) : f.source(id));
		if (n >= 0) f.targets.put(id, n);
		else f.targets.putIfAbsent(id, 27);
		f.save();
		String from = switch (f.source(id)) {
			case "buy" -> "bought from villagers";
			case "craft" -> "crafted from materials in the input chest";
			default -> "taken ready-made from the input chest";
		};
		c.getSource().sendFeedback(Component.literal(name + ": " + f.targets.get(id) + ", " + from
				+ (f.done.getOrDefault(id, 0) > 0 ? " (" + f.done.get(id) + " done already)" : "")
				+ (added ? ". Pick its enchantments: /kitfactory enchants " + id.replace("minecraft:", "") : "")));
		return 1;
	}

	private static int pathAdd(CommandContext<FabricClientCommandSource> c) {
		Minecraft mc = Minecraft.getInstance();
		Factory.cfg.path.add(new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Walkway point " + Factory.cfg.path.size() + " added at " + mc.player.blockPosition().toShortString()
				+ ". The factory walks base -> point 1 -> point 2 ... in straight lines."));
		return 1;
	}

	private static int chest(CommandContext<FabricClientCommandSource> c, boolean input) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.hitResult instanceof BlockHitResult bh)) {
			c.getSource().sendError(Component.literal("Look at a chest first."));
			return 0;
		}
		BlockPos p = bh.getBlockPos();
		var block = mc.level.getBlockState(p).getBlock();
		if (!(block instanceof ChestBlock) && !(block instanceof BarrelBlock)) {
			c.getSource().sendError(Component.literal("That's not a chest or barrel."));
			return 0;
		}
		var list = input ? Factory.cfg.inputChests : Factory.cfg.outputChests;
		list.removeIf(a -> a[0] == p.getX() && a[1] == p.getY() && a[2] == p.getZ());
		list.add(FactoryConfig.arr(p));
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal((input ? "Input" : "Output") + " chest " + list.size() + " marked at " + p.toShortString()
				+ (input ? " (plain books, diamonds and sticks for crafted items, spare anvils)" : "")));
		return 1;
	}

	private static int set(CommandContext<FabricClientCommandSource> c) {
		String item = StringArgumentType.getString(c, "item");
		int n = IntegerArgumentType.getInteger(c, "count");
		var t = Factory.cfg.targets;
		if (item.equals("all")) {
			t.replaceAll((k, v) -> n);
		} else {
			String id = FactoryConfig.resolve(item);
			if (id == null) return notAnItem(c);
			if (!t.containsKey(id)) Factory.cfg.setSource(id, Factory.cfg.defaultSource(id));
			t.put(id, n);
		}
		Factory.cfg.save();
		c.getSource().sendFeedback(Component.literal("Targets: " + Factory.progress()));
		return 1;
	}
}
