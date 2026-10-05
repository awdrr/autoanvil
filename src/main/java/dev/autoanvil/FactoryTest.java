package dev.autoanvil;

import dev.autoanvil.factory.Factory;
import dev.autoanvil.factory.FactoryConfig;
import dev.autoanvil.factory.Input;
import dev.autoanvil.factory.ItemsScreen;
import dev.autoanvil.factory.TradeBook;
import dev.autoanvil.factory.TradesScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Kit Factory end to end, {@code ./gradlew runClient -Pselftest=factory}: a small trading hall in a survival world
 * (five librarians, one of them with nine trades so its list has to scroll, a fisherman buying string, a villager out
 * of reach), base with anvil, crafting table, input and output chest. Setup, survey, trades screen and the run all go
 * through the /kitfactory commands; the anvil is removed once mid-run. Checks the finished items in the output chest
 * on the server, and that nothing walked or turned while a screen was open.
 */
final class FactoryTest {
	private FactoryTest() {
	}

	static void register() {
		R r = new R();
		ClientTickEvents.END_CLIENT_TICK.register(r::tick);
		// like the real server: /string fills every free inventory slot with string
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(
				net.minecraft.commands.Commands.literal("string").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					int n = 0;
					for (int i = 0; i < 36; i++) {
						if (p.getInventory().getItem(i).isEmpty()) {
							p.getInventory().setItem(i, new ItemStack(Items.STRING, 64));
							n++;
						}
					}
					p.containerMenu.broadcastChanges();
					return n;
				})));
	}

	private static final class R {
		int phase, ticks, start, checks, y;
		final List<String> failures = new ArrayList<>();
		BlockPos anvil, table, input, output, grindstone;
		boolean brokeAnvil;
		int renderDistance, strafeTicks, blocksBefore;
		String xpFisher;
		BlockPos input2;
		int enchPhase, enchAt, grabbedTicks;
		boolean pauseOpened, sawOtherScreen, pauseBack, stopButton;
		int chatPhase, chatAt, chatWalkTicks, chatMoveTicks, decisionsAtChat;
		boolean pauseSeen, typedLeftAlone, junked;
		int decisionsBefore;
		int screenMoves, screenTurns, screenWalkKey;
		Vec3 lastPos;
		float lastYaw, lastPitch;
		boolean lastScreen;

		void check(boolean ok, String what) {
			checks++;
			AutoAnvil.LOGGER.info("[factorytest] {}: {}", ok ? "PASS" : "FAIL", what);
			if (!ok) failures.add(what);
		}

		void next() {
			phase++;
			start = ticks;
		}

		int in() {
			return ticks - start;
		}

		static <T> T server(Minecraft mc, Supplier<T> s) {
			return mc.getSingleplayerServer().submit(s).join();
		}

		void cmd(Minecraft mc, String c) {
			mc.player.connection.sendCommand(c);
		}

		void look(Minecraft mc, BlockPos p) {
			Vec3 d = Vec3.atCenterOf(p).subtract(mc.player.getEyePosition());
			mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
			mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
		}

		void tick(Minecraft mc) {
			ticks++;
			try {
				watch(mc);
				step(mc);
			} catch (Throwable t) {
				AutoAnvil.LOGGER.error("[factorytest] FAIL: exception", t);
				failures.add("exception " + t);
				finish(mc);
			}
		}

		/** Never walk, turn or move with a screen open. */
		void watch(Minecraft mc) {
			if (mc.player == null) return;
			boolean screen = !dev.autoanvil.factory.Input.free(mc); // the chat doesn't count: walking goes on under it
			if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen
					&& (mc.options.keyUp.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown() || mc.options.keyDown.isDown())) chatWalkTicks++;
			if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) pauseSeen = true;
			if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen && lastPos != null && mc.player.position().distanceTo(lastPos) > 0.03) chatMoveTicks++;
			if (screen && lastScreen) {
				if (lastPos != null && mc.player.position().distanceTo(lastPos) > 0.03) screenMoves++;
				if (Math.abs(mc.player.getYRot() - lastYaw) > 0.01 || Math.abs(mc.player.getXRot() - lastPitch) > 0.01) screenTurns++;
				if (mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()) screenWalkKey++;
			}
			if (!screen && (mc.options.keyLeft.isDown() || mc.options.keyRight.isDown())) strafeTicks++;
			lastScreen = screen;
			lastPos = mc.player.position();
			lastYaw = mc.player.getYRot();
			lastPitch = mc.player.getXRot();
		}

		static ItemStack book(ServerLevel l, ResourceKey<Enchantment> k, int lvl) {
			ItemStack s = new ItemStack(Items.ENCHANTED_BOOK);
			ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			m.set(l.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(k), lvl);
			s.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
			return s;
		}

		static MerchantOffer sellBook(ServerLevel l, ResourceKey<Enchantment> k, int lvl, int price) {
			return new MerchantOffer(new ItemCost(Items.EMERALD, price), Optional.of(new ItemCost(Items.BOOK, 1)), book(l, k, lvl), 0, 999999, 1, 0.0f);
		}

		static MerchantOffer gear(ServerLevel l, Item item, int price, ResourceKey<Enchantment> k, int lvl) {
			ItemStack s = new ItemStack(item);
			ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			m.set(l.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(k), lvl);
			s.set(DataComponents.ENCHANTMENTS, m.toImmutable());
			return new MerchantOffer(new ItemCost(Items.EMERALD, price), Optional.empty(), s, 0, 999999, 1, 0.0f);
		}

		static MerchantOffer simple(Item buy, int n, Item sell, int m) {
			return new MerchantOffer(new ItemCost(buy, n), Optional.empty(), new ItemStack(sell, m), 0, 999999, 1, 0.0f);
		}

		static void villager(ServerLevel l, double x, double y, double z, ResourceKey<VillagerProfession> prof, MerchantOffer... offers) {
			Villager v = new Villager(EntityType.VILLAGER, l);
			v.setPos(x, y, z);
			v.setYRot(0);
			v.setNoAi(true);
			v.setInvulnerable(true);
			v.setPersistenceRequired();
			v.setVillagerData(v.getVillagerData().withProfession(l.registryAccess(), prof).withLevel(5));
			MerchantOffers mo = new MerchantOffers();
			for (MerchantOffer o : offers) mo.add(o);
			v.setOffers(mo);
			l.addFreshEntity(v);
		}

		void step(Minecraft mc) {
			switch (phase) {
				case 0 -> {
					if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
					mc.options.pauseOnLostFocus = false;
					String name = "autoanvil-factorytest-" + (System.currentTimeMillis() / 1000);
					LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(99L, false, false),
							WorldPresets::createFlatWorldDimensions, mc.screen);
					next();
				}
				case 1 -> {
					if (mc.level == null || mc.player == null || mc.screen != null || in() < 40) return;
					// fresh factory state, whatever an earlier run saved
					TradeBook.reset();
					Factory.cfg = new FactoryConfig();
					Factory.cfg.save();
					AutoAnvil.CONFIG = new Config();
					cmd(mc, "time set noon");
					cmd(mc, "gamerule advance_time false");
					// open to LAN, like a server: the pause menu doesn't pause the game
					mc.getSingleplayerServer().publishServer(GameType.SURVIVAL, false, net.minecraft.util.HttpUtil.getAvailablePort());
					y = server(mc, () -> mc.getSingleplayerServer().overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
					anvil = new BlockPos(-1, y, 2);
					table = new BlockPos(0, y, 2);
					input = new BlockPos(1, y, 2);
					output = new BlockPos(-2, y, 0);
					grindstone = new BlockPos(-1, y, -1);
					server(mc, () -> {
						ServerLevel l = mc.getSingleplayerServer().overworld();
						l.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3);
						l.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
						l.setBlock(input, Blocks.CHEST.defaultBlockState(), 3);
						l.setBlock(output, Blocks.CHEST.defaultBlockState(), 3);
						l.setBlock(grindstone, Blocks.GRINDSTONE.defaultBlockState(), 3);
						Container in = (Container) l.getBlockEntity(input);
						in.setItem(0, new ItemStack(Items.DIAMOND, 64));
						in.setItem(1, new ItemStack(Items.STICK, 16));
						in.setItem(2, new ItemStack(Items.BOOK, 64));
						in.setItem(3, new ItemStack(Items.ANVIL, 5)); // (anvils wear out in a test world, unlike on the server)
						in.setItem(4, new ItemStack(Items.EMERALD_BLOCK, 2));
						double vz = -1.5;
						villager(l, 3.5, y, vz, VillagerProfession.LIBRARIAN,
								sellBook(l, Enchantments.PROTECTION, 4, 9), sellBook(l, Enchantments.UNBREAKING, 3, 4));
						villager(l, 5.5, y, vz, VillagerProfession.LIBRARIAN,
								sellBook(l, Enchantments.PROTECTION, 4, 5), sellBook(l, Enchantments.MENDING, 1, 6));
						villager(l, 7.5, y, vz, VillagerProfession.LIBRARIAN,
								sellBook(l, Enchantments.RESPIRATION, 3, 3), sellBook(l, Enchantments.AQUA_AFFINITY, 1, 3));
						villager(l, 9.5, y, vz, VillagerProfession.LIBRARIAN,
								sellBook(l, Enchantments.SHARPNESS, 5, 5), sellBook(l, Enchantments.LOOTING, 3, 4));
						villager(l, 11.5, y, vz, VillagerProfession.LIBRARIAN,
								simple(Items.PAPER, 24, Items.EMERALD, 1), simple(Items.EMERALD, 9, Items.BOOKSHELF, 1),
								simple(Items.EMERALD, 1, Items.LANTERN, 1), simple(Items.EMERALD, 1, Items.GLASS, 4),
								simple(Items.EMERALD, 5, Items.CLOCK, 1), simple(Items.EMERALD, 4, Items.COMPASS, 1),
								simple(Items.EMERALD, 20, Items.NAME_TAG, 1),
								sellBook(l, Enchantments.FIRE_ASPECT, 2, 5), sellBook(l, Enchantments.SWEEPING_EDGE, 3, 4));
						villager(l, 13.5, y, vz, VillagerProfession.FISHERMAN,
								simple(Items.STRING, 20, Items.EMERALD, 1), simple(Items.EMERALD, 1, Items.COOKED_COD, 6));
						// a second fisherman, for levels: netherrack and fire in front of it burn the emeralds thrown its way
						villager(l, 19.5, y, vz, VillagerProfession.FISHERMAN, simple(Items.STRING, 20, Items.EMERALD, 1));
						l.setBlock(new BlockPos(19, y, -1), Blocks.NETHERRACK.defaultBlockState(), 3);
						l.setBlock(new BlockPos(19, y + 1, -1), net.minecraft.world.level.block.BaseFireBlock.getState(l, new BlockPos(19, y + 1, -1)), 3);
						// gear: a cheap helmet with Fire Protection (would block Protection IV) and a clean one; only a Bane sword
						villager(l, 15.5, y, vz, VillagerProfession.ARMORER,
								gear(l, Items.DIAMOND_HELMET, 4, Enchantments.FIRE_PROTECTION, 2), gear(l, Items.DIAMOND_HELMET, 7, Enchantments.UNBREAKING, 2));
						villager(l, 17.5, y, vz, VillagerProfession.WEAPONSMITH, gear(l, Items.DIAMOND_SWORD, 3, Enchantments.BANE_OF_ARTHROPODS, 3));
						villager(l, 8.5, y, 7.5, VillagerProfession.LIBRARIAN, sellBook(l, Enchantments.THORNS, 3, 1)); // out of reach
						// 3.5 blocks off the walkway: reached by stepping off it (and the cheapest Mending, so buying goes there too)
						villager(l, 10.5, y, 4.3, VillagerProfession.LIBRARIAN, sellBook(l, Enchantments.MENDING, 1, 2),
								sellBook(l, Enchantments.SILK_TOUCH, 1, 3), sellBook(l, Enchantments.FORTUNE, 3, 3));
						// a fence post in front of the Respiration librarian blocks the middle of it: aim past the post
						l.setBlock(new BlockPos(7, y + 1, -1), Blocks.OAK_FENCE.defaultBlockState(), 3);
						// far down the walkway: not sent to the client until the survey walks there
						villager(l, 60.5, y, vz, VillagerProfession.CLERIC, simple(Items.EMERALD, 1, Items.REDSTONE, 2));
						ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
						p.getInventory().clearContent();
						p.setExperienceLevels(0);
						p.setExperiencePoints(0);
						p.teleportTo(18.5, y, 0.5);
						return null;
					});
					next();
				}
				case 2 -> { // the walkway: a corner at 18.5, the far end at 62.5
					if (in() == 20) cmd(mc, "kitfactory path add");
					if (in() == 21) { // look at the fisherman with the fire in front and mark it for levels
						for (var e : mc.level.entitiesForRendering()) {
							if (e instanceof Villager v && Math.abs(v.getX() - 19.5) < 0.3) {
								Vec3 d = v.getEyePosition().subtract(mc.player.getEyePosition());
								mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
								mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
							}
						}
					}
					if (in() == 24) {
						cmd(mc, "kitfactory fisherman xp");
						xpFisher = Factory.cfg.xpFisherman;
					}
					if (in() == 26) server(mc, () -> {
						mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).teleportTo(62.5, y, 0.5);
						return null;
					});
					if (in() == 40) cmd(mc, "kitfactory path add");
					if (in() == 42) server(mc, () -> {
						mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).teleportTo(0.5, y, 0.5);
						return null;
					});
					if (in() == 50) {
						renderDistance = mc.options.renderDistance().get();
						mc.options.renderDistance().set(2); // villagers more than 32 blocks off aren't sent, like a server's tracking range
					}
					if (in() == 60) cmd(mc, "kitfactory base");
					if (in() == 65) look(mc, input);
					if (in() == 70) cmd(mc, "kitfactory chest input");
					if (in() == 75) look(mc, output);
					if (in() == 80) cmd(mc, "kitfactory chest output");
					if (in() == 85) {
						cmd(mc, "kitfactory set all 0");
						cmd(mc, "kitfactory set diamond_helmet 2");
						cmd(mc, "kitfactory set diamond_sword 1");
						cmd(mc, "kitfactory set diamond_spear 1");
					}
					if (in() < 100) return;
					boolean farLoaded = false;
					for (var e : mc.level.entitiesForRendering()) if (e.getX() > 40) farLoaded = true;
					check(!farLoaded, "the villager 60 blocks down the walkway isn't loaded on the client before the survey");
					FactoryConfig f = Factory.cfg;
					check(f.base != null && f.path.size() == 2 && anvil.equals(FactoryConfig.pos(f.anvil)) && table.equals(FactoryConfig.pos(f.craftingTable))
							&& f.inputChests.size() == 1 && f.outputChests.size() == 1 && grindstone.equals(FactoryConfig.pos(f.grindstone)),
							"setup commands marked base, walkway, anvil, crafting table, grindstone and chests");
					cmd(mc, "kitfactory survey");
					next();
				}
				case 3 -> {
					if (in() < 10 || Factory.running()) return;
					TradeBook b = TradeBook.get();
					int fire = 0, sweep = 0, nine = 0, fisher = 0, thorns = 0;
					for (TradeBook.Trader t : b.traders) {
						if (t.offers.size() == 9) nine++;
						if (t.profession.equals("fisherman")) fisher++;
						for (TradeBook.Offer o : t.offers) {
							if (o.enchant.equals("minecraft:fire_aspect")) fire++;
							if (o.enchant.equals("minecraft:sweeping_edge")) sweep++;
							if (o.enchant.equals("minecraft:thorns")) thorns++;
						}
					}
					check(b.traders.size() == 11, "survey walked the hall and recorded the 11 reachable villagers (" + b.traders.size() + ")");
					check(xpFisher != null && b.find(xpFisher) != null && b.find(xpFisher).x > 19,
							"'/kitfactory fisherman xp' marked the fisherman with the fire in front");
					boolean stepped = false, pastPost = false, far = false;
					for (TradeBook.Trader t : b.traders) {
						if (t.z > 4) stepped = true;
						if (t.x > 60) far = true;
						for (TradeBook.Offer o : t.offers) if (o.enchant.equals("minecraft:respiration")) pastPost = true;
					}
					check(stepped, "the villager 3.5 blocks off the walkway was reached by stepping off it");
					check(pastPost, "the villager behind a fence post was opened by aiming past the post");
					check(far, "the far villager (not loaded at the start) was found by walking on down the walkway");
					check(nine == 1 && fire == 1 && sweep == 1, "all 9 trades of the long list recorded, incl. the two that need scrolling");
					check(fisher == 2 && thorns == 0, "both fishermen recorded; the out-of-reach villager skipped");
					check(Factory.lastMessage.startsWith("Survey done"), "survey reports done (" + Factory.lastMessage + ")");
					cmd(mc, "kitfactory trades");
					next();
				}
				case 4 -> {
					if (!(mc.screen instanceof TradesScreen ts)) {
						if (in() > 40) {
							check(false, "trades screen opened");
							next();
						}
						return;
					}
					if (in() < 50) return;
					EditBox first = null;
					for (var c : ts.children()) if (c instanceof EditBox e) {
						first = e;
						break;
					}
					check(first != null, "trades screen lists prices to edit");
					if (first != null) {
						String old = first.getValue();
						first.setValue("77");
						TradeBook.Offer o = TradeBook.get().traders.get(0).offers.get(0);
						check(o.price == 77 && o.manual, "editing a price in the screen changes the recorded price");
						first.setValue(old);
						o.manual = false;
					}
					ts.onClose();
					next();
				}
				case 5 -> { // start with the inventory full of emeralds: they get packed into blocks and the blocks stored
					if (in() < 10) return;
					fillWithEmeralds(mc);
					Factory.cfg.dropSpot = new double[] {5.5, y, 2.5, 0, -30}; // facing away from the walkway
					Factory.cfg.save();
					cmd(mc, "kitfactory start");
					next();
				}
				case 6 -> {
					chatAndTabOut(mc);
					// break the anvil once while it is combining
					if (!brokeAnvil && AutoAnvil.running() && AutoAnvil.runner.stepsDone >= 1) {
						brokeAnvil = true;
						server(mc, () -> mc.getSingleplayerServer().overworld().removeBlock(anvil, false));
						AutoAnvil.LOGGER.info("[factorytest] anvil removed mid-run");
					}
					if (in() % 200 == 0) AutoAnvil.LOGGER.info("[factorytest] {}s: {} | {}", in() / 20, Factory.status, Factory.progress());
					if (Factory.running() && in() < 20 * 60 * 12) return;
					check(!Factory.running(), "factory finished within 12 minutes");
					check(Factory.lastMessage.startsWith("All done"), "factory says all done (" + Factory.lastMessage + ")");
					check(brokeAnvil, "the anvil was removed mid-run");
					boolean anvilBack = server(mc, () -> mc.getSingleplayerServer().overworld().getBlockState(anvil).is(net.minecraft.tags.BlockTags.ANVIL));
					check(anvilBack, "a spare anvil was placed where the old one was");
					List<ItemStack> out = server(mc, () -> {
						List<ItemStack> l = new ArrayList<>();
						Container c = (Container) mc.getSingleplayerServer().overworld().getBlockEntity(output);
						for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) l.add(c.getItem(i).copy());
						return l;
					});
					int helmets = 0, swords = 0;
					for (ItemStack s : out) {
						AutoAnvil.LOGGER.info("[factorytest] output: {}", describe(s));
						if (s.is(Items.DIAMOND_HELMET) && lvl(mc, s, Enchantments.PROTECTION) == 4 && lvl(mc, s, Enchantments.UNBREAKING) == 3
								&& lvl(mc, s, Enchantments.MENDING) == 1 && lvl(mc, s, Enchantments.RESPIRATION) == 3 && lvl(mc, s, Enchantments.AQUA_AFFINITY) == 1) helmets++;
						if (s.is(Items.DIAMOND_SWORD) && lvl(mc, s, Enchantments.SHARPNESS) == 5 && lvl(mc, s, Enchantments.UNBREAKING) == 3
								&& lvl(mc, s, Enchantments.MENDING) == 1 && lvl(mc, s, Enchantments.LOOTING) == 3 && lvl(mc, s, Enchantments.FIRE_ASPECT) == 2
								&& lvl(mc, s, Enchantments.SWEEPING_EDGE) == 3) swords++;
					}
					check(helmets == 2, "output chest: 2 diamond helmets with Prot IV, Unbreaking III, Mending, Respiration III, Aqua Affinity (" + helmets + ")");
					int fireProt = 0;
					for (ItemStack s : out) if (lvl(mc, s, Enchantments.FIRE_PROTECTION) > 0 || lvl(mc, s, Enchantments.BANE_OF_ARTHROPODS) > 0) fireProt++;
					check(fireProt == 0, "no item with a clashing enchantment (Fire Protection helmet / Bane sword) was bought");
					int diamonds = diamonds(mc);
					check(diamonds == 63, "helmets and sword bought from villagers, only the spear crafted (1 of 64 diamonds used, " + (64 - diamonds) + ")");
					check(onPlayer(mc, Items.DIAMOND) == 0 && onPlayer(mc, Items.STICK) == 0,
							"leftover diamonds and sticks went back in the input chest (" + onPlayer(mc, Items.DIAMOND) + ", " + onPlayer(mc, Items.STICK) + " still carried)");
					check(Factory.packed >= 1, "spare emeralds were packed into emerald blocks at the crafting table (" + Factory.packed + " rounds)");
					check(emeraldBlocks(mc) >= 22, "spare emerald blocks stored in the input chest, the 2 that were there kept (" + emeraldBlocks(mc) + ")");
					check(Factory.batchSizes.getOrDefault("minecraft:diamond_helmet", 0) == 2,
							"both helmets enchanted together although the inventory started full of emeralds (" + Factory.batchSizes + ")");
					check(Factory.storedSizes.equals(List.of(2, 1, 1)), "each trip to the output chest stored a whole batch " + Factory.storedSizes);
					check(Factory.decisions.contains("xp trade") && Factory.thrownEmeralds >= 100,
							"levels traded at the fisherman for levels, its emeralds thrown out of the window (" + Factory.thrownEmeralds + ")");
					int unburnt = server(mc, () -> {
						int n = 0;
						for (var e : mc.getSingleplayerServer().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
								new net.minecraft.world.phys.AABB(19.5, y, -1, 19.5, y, -1).inflate(4))) {
							if (e.getItem().is(Items.EMERALD)) n += e.getItem().getCount();
						}
						return n;
					});
					AutoAnvil.LOGGER.info("[factorytest] emeralds thrown {}, still lying by the fire {}", Factory.thrownEmeralds, unburnt);
					check(unburnt < Factory.thrownEmeralds, "the thrown emeralds went into the fire (" + unburnt + " of " + Factory.thrownEmeralds + " left lying there)");
					check(strafeTicks > 20, "walked to villagers strafing with A/D while looking at them (" + strafeTicks + " ticks)");
					check(Factory.ground >= 1, "the Bane of Arthropods sword was ground clean before enchanting (" + Factory.ground + ")");
					int g = Factory.decisions.indexOf("grind minecraft:diamond_sword");
					String after = g >= 0 && g + 1 < Factory.decisions.size() ? Factory.decisions.get(g + 1) : "-";
					int helmetsDone = Factory.decisions.indexOf("deposit");
					boolean books = false, gear = false;
					for (int i = helmetsDone; i >= 0 && i < g; i++) {
						books |= Factory.decisions.get(i).contains("minecraft:sharpness");
						gear |= Factory.decisions.get(i).equals("buy minecraft:diamond_sword");
					}
					check(books && gear && after.equals("anvil minecraft:diamond_sword"),
							"sword: the sword and all its books bought on one round, then ground at the base right before the anvil (" + after + ")");
					int spears = 0;
					for (ItemStack s : out) if (s.is(Items.DIAMOND_SPEAR) && lvl(mc, s, Enchantments.SHARPNESS) == 5 && lvl(mc, s, Enchantments.UNBREAKING) == 3
							&& lvl(mc, s, Enchantments.MENDING) == 1) spears++;
					check(spears == 1, "output chest: 1 crafted diamond spear with Sharpness V, Unbreaking III, Mending (" + spears + ")");
					check(swords == 1, "output chest: 1 diamond sword with Sharpness V, Unbreaking III, Mending, Looting III, Fire Aspect II, Sweeping Edge III (" + swords + ")");
					check(Factory.cfg.done.getOrDefault("minecraft:diamond_helmet", 0) == 2 && Factory.cfg.done.getOrDefault("minecraft:diamond_sword", 0) == 1
									&& Factory.cfg.done.getOrDefault("minecraft:diamond_spear", 0) == 1,
							"progress counted: " + Factory.progress());
					check(screenWalkKey == 0 && screenTurns == 0, "never held forward or turned with a screen open (" + screenWalkKey + ", " + screenTurns + ")");
					check(screenMoves == 0, "never moved with a screen open (" + screenMoves + ")");
					next();
				}
				case 7 -> { // a pickaxe: on the buy list, but nobody here sells one
					if (in() < 10) return;
					cmd(mc, "kitfactory buy pickaxe 2");
					check(Factory.cfg.targets.get("minecraft:diamond_pickaxe") == 2 && Factory.cfg.buy.contains("minecraft:diamond_pickaxe"),
							"'/kitfactory buy pickaxe 2' sets the amount and Buy");
					cmd(mc, "kitfactory start");
					next();
				}
				case 8 -> {
					if (Factory.running() && in() < 20 * 90) return;
					check(!Factory.running() && Factory.lastMessage.startsWith("No villager sells Diamond Pickaxe"),
							"a pickaxe nobody sells is not crafted: the factory stops and says so (" + Factory.lastMessage + ")");
					boolean pickaxe = server(mc, () -> {
						ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
						for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.DIAMOND_PICKAXE)) return true;
						return false;
					});
					check(diamonds(mc) == 63 && !pickaxe, "no diamonds used for it (" + (64 - diamonds(mc)) + " used in all)");
					cmd(mc, "kitfactory items");
					next();
				}
				case 9 -> { // the items screen: switch the pickaxe to Craft, 1 of them
					if (!(mc.screen instanceof ItemsScreen is)) {
						if (in() > 40) {
							check(false, "items screen opened");
							finish(mc);
						}
						return;
					}
					if (in() < 50) return;
					String id = "minecraft:diamond_pickaxe";
					var box = is.amountBox(id);
					var how = is.sourceButton(id);
					check(box != null && how != null && how.getMessage().getString().equals("Buy") && box.getValue().equals("2"),
							"items screen shows the pickaxe: amount 2, Buy");
					if (box == null || how == null) {
						finish(mc);
						return;
					}
					box.setValue("");
					box.setValue("2");
					Input.click(is, how.getX() + how.getWidth() / 2.0, how.getY() + how.getHeight() / 2.0, 0, false);
					check(how.getMessage().getString().equals("Craft") && !Factory.cfg.buy.contains(id) && Factory.cfg.targets.get(id) == 2,
							"clicking Buy switches it to Craft");
					is.onClose();
					next();
				}
				case 10 -> { // full of emeralds again, and spare blocks thrown at the drop spot this time
					if (in() == 2) cmd(mc, "kitfactory spare drop");
					if (in() < 10) return;
					server(mc, () -> { // no emeralds left over: the books need a trip to the emerald fisherman
						ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
						for (int i = 0; i < 36; i++) if (sp.getInventory().getItem(i).is(Items.EMERALD)) sp.getInventory().setItem(i, ItemStack.EMPTY);
						sp.inventoryMenu.broadcastChanges();
						return null;
					});
					fillWithEmeralds(mc, 28, 0); // too many blocks to fit two pickaxes and their books, and no emeralds
					blocksBefore = emeraldBlocks(mc);
					decisionsBefore = Factory.decisions.size();
					cmd(mc, "kitfactory start");
					next();
				}
				case 11 -> {
					if (in() % 200 == 0) AutoAnvil.LOGGER.info("[factorytest] {}s: {} | {}", in() / 20, Factory.status, Factory.progress());
					// part way through buying the books, every free slot fills up with things it can't put away
					if (!junked && Factory.running() && Factory.status.startsWith("Walking to Librarian")) {
						junked = true;
						server(mc, () -> {
							ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
							int leave = 3;
							for (int i = 35; i >= 0; i--) {
								if (!sp.getInventory().getItem(i).isEmpty()) continue;
								if (leave > 0) leave--;
								else sp.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
							}
							sp.inventoryMenu.broadcastChanges();
							sp.setExperienceLevels(30); // levels on hand, like a player who's been splashing
							return null;
						});
					}
					if (Factory.running() && in() < 20 * 60 * 6) return;
					AutoAnvil.LOGGER.info("[factorytest] full inventory mid-shopping: combined first {} times", Factory.deferred);
					check(junked && Factory.lastMessage.startsWith("All done") && Factory.deferred > 0,
							"pickaxe set to Craft, inventory filled up part way through buying: combined what it had, bought the rest, finished ("
									+ Factory.lastMessage + ")");
					int pickaxes = server(mc, () -> {
						int n = 0;
						Container c = (Container) mc.getSingleplayerServer().overworld().getBlockEntity(output);
						for (int i = 0; i < c.getContainerSize(); i++) {
							ItemStack s = c.getItem(i);
							if (s.is(Items.DIAMOND_PICKAXE)) {
								AutoAnvil.LOGGER.info("[factorytest] output: {}", describe(s));
								if (lvl(mc, s, Enchantments.UNBREAKING) == 3 && lvl(mc, s, Enchantments.MENDING) == 1 && lvl(mc, s, Enchantments.SILK_TOUCH) == 1
										&& lvl(mc, s, Enchantments.FORTUNE) == 0) n++;
							}
						}
						return n;
					});
					check(pickaxes == 2, "output chest: 2 crafted diamond pickaxes with Unbreaking III, Mending, Silk Touch and no Fortune (" + pickaxes + ")");
					check(diamonds(mc) == 57, "the pickaxes took 6 diamonds from the chest (" + (64 - diamonds(mc)) + " used in all)");
					int dropped = server(mc, () -> {
						int k = 0;
						for (var e : mc.getSingleplayerServer().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
								new net.minecraft.world.phys.AABB(0, y - 2, -4, 63, y + 4, 8))) {
							if (e.getItem().is(Items.BOOK) || e.getItem().is(Items.EMERALD)) k += e.getItem().getCount();
						}
						return k;
					});
					check(dropped == 0, "no plain books or emeralds dropped on the walkway when the inventory was full (" + dropped + ")");
					check(onPlayer(mc, Items.DIAMOND) == 0 && onPlayer(mc, Items.STICK) == 0, "leftover pickaxe materials went back in the input chest");
					check(Factory.batchSizes.getOrDefault("minecraft:diamond_pickaxe", 0) == 2, "both pickaxes crafted and enchanted together (" + Factory.batchSizes + ")");
					int thrown = server(mc, () -> {
						int n = 0;
						for (var e : mc.getSingleplayerServer().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
								new net.minecraft.world.phys.AABB(5.5, y, 2.5, 5.5, y, 2.5).inflate(10))) {
							if (e.getItem().is(Items.EMERALD_BLOCK)) n += e.getItem().getCount();
						}
						return n;
					});
					check(thrown >= 64 * 28, "spare emerald blocks thrown at the drop spot (" + thrown + ")");
					List<String> late = Factory.decisions.subList(decisionsBefore, Factory.decisions.size());
					check(late.contains("string trade") && late.indexOf("string trade") < late.indexOf("xp trade") || late.contains("string trade") && !late.contains("xp trade"),
							"emeralds for the pickaxe books came from the other fisherman, kept (" + late + ")");
					check(emeraldBlocks(mc) == blocksBefore, "drop mode: no blocks put in or taken from the chest (" + emeraldBlocks(mc) + " vs " + blocksBefore + ")");
					next();
				}
				case 12 -> { // any item: a shield crafted from mixed planks, a bow taken ready-made, from a second input chest
					if (in() == 1) server(mc, () -> {
						ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
						for (int i = 0; i < 36; i++) if (sp.getInventory().getItem(i).is(Items.COBBLESTONE)) sp.getInventory().setItem(i, ItemStack.EMPTY);
						sp.inventoryMenu.broadcastChanges();
						ServerLevel l = mc.getSingleplayerServer().overworld();
						input2 = new BlockPos(2, y, 2);
						l.setBlock(input2, Blocks.CHEST.defaultBlockState(), 3);
						Container c = (Container) l.getBlockEntity(input2);
						c.setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
						c.setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 3));
						c.setItem(2, new ItemStack(Items.IRON_INGOT, 1));
						c.setItem(3, new ItemStack(Items.BOW));
						return null;
					});
					if (in() == 5) look(mc, input2);
					if (in() == 8) cmd(mc, "kitfactory chest input");
					if (in() == 10) {
						cmd(mc, "kitfactory set all 0");
						cmd(mc, "kitfactory add shield 1");
						cmd(mc, "kitfactory take bow 1");
					}
					if (in() < 15) return;
					check(Factory.cfg.inputChests.size() == 2, "second input chest marked");
					check(Factory.cfg.targets.getOrDefault("minecraft:shield", 0) == 1 && Factory.cfg.source("minecraft:shield").equals("craft")
							&& Factory.cfg.targets.getOrDefault("minecraft:bow", 0) == 1 && Factory.cfg.source("minecraft:bow").equals("take"),
							"'/kitfactory add shield 1' (crafted: nobody sells one) and '/kitfactory take bow 1' (ready-made from the chest)");
					cmd(mc, "kitfactory items");
					next();
				}
				case 13 -> { // the shield's Enchants button: untick Mending
					if (mc.screen instanceof ItemsScreen is && in() > 20 && enchPhase == 0) {
						for (int i = 0; i < 12; i++) is.mouseScrolled(0, 0, 0, -1); // the new items are at the bottom of the list
						var b = is.enchantsButton("minecraft:shield");
						check(b != null, "items screen has an Enchants button for the shield");
						if (b == null) {
							finish(mc);
							return;
						}
						Input.click(is, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0, false);
						enchPhase = 1;
						enchAt = in();
						return;
					}
					if (enchPhase == 1 && in() - enchAt > 10) {
						if (!(mc.screen instanceof dev.autoanvil.factory.EnchantsScreen es)) {
							check(false, "Enchants opens the enchantment list");
							finish(mc);
							return;
						}
						boolean before = es.isOn("minecraft:mending");
						double[] box = es.boxAt("minecraft:mending");
						if (box != null) Input.click(es, box[0], box[1], 0, false);
						check(before && !es.isOn("minecraft:mending") && es.isOn("minecraft:unbreaking")
										&& Boolean.FALSE.equals(AutoAnvil.CONFIG.profile("shield").get("minecraft:mending")),
								"clicking Mending in the shield's list unticks it (saved for shields)");
						es.onClose();
						enchPhase = 2;
						enchAt = in();
						return;
					}
					if (enchPhase == 2 && in() - enchAt > 5) {
						if (mc.screen != null) mc.screen.onClose();
						next();
					}
				}
				case 14 -> { // run it with the pause menu open part of the time, the window in focus: the cursor is never taken
					if (in() == 5) mc.setWindowActive(true);
					if (in() == 10) cmd(mc, "kitfactory start");
					if (in() < 12) return;
					if (Factory.running() && mc.mouseHandler.isMouseGrabbed()) grabbedTicks++;
					if (!pauseOpened && Factory.running() && mc.screen == null && Factory.status.startsWith("Walking")) {
						mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
						pauseOpened = true;
					}
					if (pauseOpened && mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen ps) {
						if (sawOtherScreen) pauseBack = true;
						for (var w : net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(ps)) {
							if (w.getMessage().getString().equals("Stop Kit Factory")) stopButton = true;
						}
					} else if (pauseOpened && mc.screen != null) {
						sawOtherScreen = true;
					}
					if (Factory.running() && in() < 20 * 60 * 6) return;
					if (mc.screen != null) mc.setScreen(null);
					check(Factory.lastMessage.startsWith("All done"), "shield and bow: factory finishes with the pause menu open part of the time (" + Factory.lastMessage + ")");
					check(pauseBack && stopButton, "pause menu came back after the factory's clicks, with a Stop Kit Factory button");
					check(grabbedTicks == 0, "the cursor was never taken while it ran (" + grabbedTicks + " ticks)");
					List<ItemStack> out = server(mc, () -> {
						List<ItemStack> l = new ArrayList<>();
						Container c = (Container) mc.getSingleplayerServer().overworld().getBlockEntity(output);
						for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) l.add(c.getItem(i).copy());
						return l;
					});
					int shields = 0, bows = 0;
					for (ItemStack s : out) {
						if (s.is(Items.SHIELD)) AutoAnvil.LOGGER.info("[factorytest] output: {}", describe(s));
						if (s.is(Items.BOW)) AutoAnvil.LOGGER.info("[factorytest] output: {}", describe(s));
						if (s.is(Items.SHIELD) && lvl(mc, s, Enchantments.UNBREAKING) == 3 && lvl(mc, s, Enchantments.MENDING) == 0) shields++;
						if (s.is(Items.BOW) && lvl(mc, s, Enchantments.UNBREAKING) == 3 && lvl(mc, s, Enchantments.MENDING) == 1) bows++;
					}
					check(shields == 1, "output chest: a shield crafted from oak + spruce planks and iron, Unbreaking III and no Mending (" + shields + ")");
					check(bows == 1, "output chest: the bow from the chest, with Unbreaking III and Mending (" + bows + ")");
					finish(mc);
				}
				default -> {
				}
			}
		}

		/** 20 stacks of emerald blocks and 12 of emeralds into the free slots, like after a lot of string trading. */
		void fillWithEmeralds(Minecraft mc) {
			fillWithEmeralds(mc, 20, 12);
		}

		void fillWithEmeralds(Minecraft mc, int blockStacks, int emeraldStacks) {
			server(mc, () -> {
				ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
				int blocks = blockStacks, ems = emeraldStacks;
				for (int i = 0; i < 36; i++) {
					if (!p.getInventory().getItem(i).isEmpty()) continue;
					if (blocks > 0) {
						p.getInventory().setItem(i, new ItemStack(Items.EMERALD_BLOCK, 64));
						blocks--;
					} else if (ems > 0) {
						p.getInventory().setItem(i, new ItemStack(Items.EMERALD, 64));
						ems--;
					}
				}
				p.inventoryMenu.broadcastChanges();
				return null;
			});
		}

		/**
		 * Part way through the run: chat open with something typed (left alone), then an empty chat and the window out
		 * of focus with pause-on-focus-loss on, for 20 seconds: no pause menu, and the factory keeps going.
		 */
		void chatAndTabOut(Minecraft mc) {
			switch (chatPhase) {
				case 0 -> {
					if (in() > 20 * 20 && mc.screen == null && Factory.running() && Factory.status.startsWith("Walking")) {
						mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("hello", false));
						chatAt = in();
						chatPhase = 1;
					}
				}
				case 1 -> {
					if (in() - chatAt < 60) return;
					typedLeftAlone = mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen cs && cs.input.getValue().equals("hello");
					mc.setScreen(null);
					chatPhase = 2;
				}
				case 2 -> {
					if (mc.screen != null || !Factory.status.startsWith("Walking")) return;
					mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false));
					mc.options.pauseOnLostFocus = true;
					pauseSeen = false;
					decisionsAtChat = Factory.decisions.size();
					chatAt = in();
					chatPhase = 3;
				}
				case 3 -> {
					mc.setWindowActive(false);
					if (in() - chatAt < 20 * 20) return;
					mc.setWindowActive(true);
					mc.options.pauseOnLostFocus = false;
					check(typedLeftAlone, "chat with something typed in it was left open for you");
					check(!pauseSeen && Factory.decisions.size() > decisionsAtChat,
							"chat open, then the window out of focus for 20 s: no pause menu, kept going (" + (Factory.decisions.size() - decisionsAtChat) + " decisions)");
					check(chatWalkTicks > 0 && chatMoveTicks > 0, "walked on with the chat open (" + chatWalkTicks + " ticks of keys, moved in " + chatMoveTicks + ")");
					chatPhase = 4;
				}
				default -> {
				}
			}
		}

		int onPlayer(Minecraft mc, Item item) {
			return server(mc, () -> {
				ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
				int n = 0;
				for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
				return n;
			});
		}

		int emeraldBlocks(Minecraft mc) {
			return server(mc, () -> {
				int n = 0;
				Container c = (Container) mc.getSingleplayerServer().overworld().getBlockEntity(input);
				for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.EMERALD_BLOCK)) n += c.getItem(i).getCount();
				return n;
			});
		}

		int diamonds(Minecraft mc) {
			return server(mc, () -> {
				int n = 0;
				Container c = (Container) mc.getSingleplayerServer().overworld().getBlockEntity(input);
				for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.DIAMOND)) n += c.getItem(i).getCount();
				ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
				for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.DIAMOND)) n += p.getInventory().getItem(i).getCount();
				return n;
			});
		}

		static int lvl(Minecraft mc, ItemStack s, ResourceKey<Enchantment> k) {
			Holder<Enchantment> h = mc.getSingleplayerServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(k);
			return EnchantmentHelper.getEnchantmentsForCrafting(s).getLevel(h);
		}

		static String describe(ItemStack s) {
			StringBuilder sb = new StringBuilder(s.getHoverName().getString()).append(" {");
			for (var e : EnchantmentHelper.getEnchantmentsForCrafting(s).entrySet()) sb.append(' ').append(Catalog.id(e.getKey())).append('=').append(e.getIntValue());
			return sb.append(" }").toString();
		}

		void finish(Minecraft mc) {
			AutoAnvil.LOGGER.info("[factorytest] villager-buying clicks the server undid: {}", Factory.corrected);
			if (renderDistance > 0) mc.options.renderDistance().set(renderDistance);
			if (failures.isEmpty()) AutoAnvil.LOGGER.info("[factorytest] PASS ({} checks)", checks);
			else AutoAnvil.LOGGER.error("[factorytest] FAIL: {}", failures);
			phase = 99;
			mc.stop();
		}
	}
}
