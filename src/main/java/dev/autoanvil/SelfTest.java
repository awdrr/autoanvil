package dev.autoanvil;

import dev.autoanvil.compat.Ui;
import dev.autoanvil.run.Analysis;
import dev.autoanvil.run.ItemQueue;
import dev.autoanvil.run.Runner;
import dev.autoanvil.run.Target;
import dev.autoanvil.ui.Panel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * End-to-end test, {@code ./gradlew runClient -Pselftest}: a survival flat world, a real anvil, books and gear given
 * on the server, the panel clicked like a player would, XP bottles splashed while the mod waits. Every step's cost as
 * the server computed it is compared with the plan, and every result is read back from the server's inventory.
 * Logs {@code [selftest] PASS}/{@code FAIL} per check, saves screenshots to run/screenshots and quits.
 */
final class SelfTest {
	private SelfTest() {
	}

	static void registerIfRequested() {
		if ("factory".equals(System.getProperty("autoanvil.selftest"))) {
			FactoryTest.register();
			return;
		}
		if (!Boolean.getBoolean("autoanvil.selftest")) return;
		Runner0 r = new Runner0();
		ClientTickEvents.END_CLIENT_TICK.register(r::tick);
		// Anvils get chipped 12% of the time; keep this one whole so a long test does not break it at random.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (r.anvil == null || !r.keepAnvil) return;
			ServerLevel l = server.overworld();
			BlockState s = l.getBlockState(r.anvil);
			if (s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL)) {
				l.setBlock(r.anvil, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, s.getValue(AnvilBlock.FACING)), 2);
			}
		});
	}

	private interface Step {
		/** @return true when done; {@code t} counts ticks since the step began */
		boolean tick(Minecraft mc, int t) throws Exception;
	}

	private static final class Runner0 {
		volatile BlockPos anvil;
		volatile boolean keepAnvil = true;
		private final List<Step> steps = new ArrayList<>();
		private final List<String> failures = new ArrayList<>();
		private int stepIdx, stepStart, ticks, checks, bottles, shots;
		private boolean sawWaiting, started;
		private Runner lastRunner;
		private int mismatches;

		private void check(boolean ok, String what) {
			checks++;
			AutoAnvil.LOGGER.info("[selftest] {}: {}", ok ? "PASS" : "FAIL", what);
			if (!ok) failures.add(what);
		}

		private static <T> T onServer(Minecraft mc, Supplier<T> task) {
			return mc.getSingleplayerServer().submit(task).join();
		}

		private static ServerPlayer sp(Minecraft mc) {
			return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
		}

		private static Holder<Enchantment> ench(Minecraft mc, ResourceKey<Enchantment> key) {
			return mc.getSingleplayerServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		}

		private static ItemStack book(Minecraft mc, Object... keyLevel) {
			ItemStack s = new ItemStack(Items.ENCHANTED_BOOK);
			ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			for (int i = 0; i < keyLevel.length; i += 2) {
				@SuppressWarnings("unchecked")
				ResourceKey<Enchantment> k = (ResourceKey<Enchantment>) keyLevel[i];
				m.set(ench(mc, k), (Integer) keyLevel[i + 1]);
			}
			s.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
			return s;
		}

		private void setup(Minecraft mc, int levels, Supplier<List<ItemStack>> items) {
			onServer(mc, () -> {
				ServerPlayer p = sp(mc);
				p.getInventory().clearContent();
				p.getInventory().setSelectedSlot(8);
				for (ItemStack s : items.get()) p.getInventory().add(s);
				p.setExperienceLevels(levels);
				p.setExperiencePoints(0);
				return null;
			});
		}

		private static List<ItemStack> inventory(Minecraft mc) {
			return onServer(mc, () -> {
				List<ItemStack> out = new ArrayList<>();
				ServerPlayer p = sp(mc);
				for (int i = 0; i < 36; i++) if (!p.getInventory().getItem(i).isEmpty()) out.add(p.getInventory().getItem(i).copy());
				return out;
			});
		}

		private static ItemStack find(List<ItemStack> inv, Item item) {
			for (ItemStack s : inv) if (s.is(item)) return s;
			return ItemStack.EMPTY;
		}

		private static int level(Minecraft mc, ItemStack s, ResourceKey<Enchantment> key) {
			return EnchantmentHelper.getEnchantmentsForCrafting(s).getLevel(ench(mc, key));
		}

		private static int books(List<ItemStack> inv) {
			int n = 0;
			for (ItemStack s : inv) if (s.is(Items.ENCHANTED_BOOK)) n++;
			return n;
		}

		private static String describe(ItemStack s) {
			StringBuilder sb = new StringBuilder(s.getHoverName().getString()).append(" {");
			for (var e : EnchantmentHelper.getEnchantmentsForCrafting(s).entrySet()) {
				sb.append(' ').append(Catalog.id(e.getKey())).append('=').append(e.getIntValue());
			}
			return sb.append(" } rc=").append(s.getOrDefault(DataComponents.REPAIR_COST, 0)).toString();
		}

		private void openAnvil(Minecraft mc) {
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(anvil), Direction.UP, anvil, false));
		}

		/** Presses the panel's ">" until the predicate matches the selected target; true once it does. */
		private boolean select(Predicate<Target> want) {
			Target t = AutoAnvil.current();
			if (t != null && want.test(t)) return true;
			if (Panel.current != null) Panel.current.press("next");
			return false;
		}

		private boolean press(String id) {
			boolean ok = Panel.current != null && Panel.current.press(id);
			if (ok) {
				started = true;
				lastRunner = null;
			}
			return ok;
		}

		/** Waits for the run started by the last press to finish; collects its logs. */
		private boolean runDone() {
			if (AutoAnvil.runner != null) lastRunner = AutoAnvil.runner;
			if (AutoAnvil.running()) {
				sawWaiting |= AutoAnvil.runner.waitingForXp;
				return false;
			}
			return lastRunner != null;
		}

		private void checkRun(String what) {
			Runner r = lastRunner;
			check(r != null && r.error == null, what + ": run finished without error (" + (r == null ? "no run" : r.error == null ? r.status : r.error) + ")");
			if (r == null) return;
			boolean costs = true, levels = true, under40 = true;
			for (Runner.StepLog s : r.log) {
				costs &= s.planned() == s.actual();
				levels &= s.levelBefore() >= s.actual();
				under40 &= s.actual() < 40;
				AutoAnvil.LOGGER.info("[selftest]   step planned={} actual={} level {} -> {}", s.planned(), s.actual(), s.levelBefore(), s.levelAfter());
			}
			mismatches += r.costMismatches;
			check(costs && r.costMismatches == 0, what + ": every step cost exactly what the planner predicted (" + r.log.size() + " steps)");
			check(levels, what + ": no step was taken without enough levels");
			check(under40, what + ": no step reached Too Expensive");
		}

		/** Puts the real mouse cursor over a menu slot (the screen works out the hovered slot from it). */
		private static void hover(Minecraft mc, int menuSlot) throws Exception {
			AnvilScreen s = (AnvilScreen) Ui.screen(mc);
			net.minecraft.world.inventory.Slot slot = s.getMenu().getSlot(menuSlot);
			double gx = s.leftPos + slot.x + 8, gy = s.topPos + slot.y + 8;
			var w = mc.getWindow();
			java.lang.reflect.Method m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
			m.setAccessible(true);
			m.invoke(mc.mouseHandler, w.handle(), gx * w.getScreenWidth() / w.getGuiScaledWidth(), gy * w.getScreenHeight() / w.getGuiScaledHeight());
		}

		/** A real key press through the keyboard handler, as GLFW would deliver it. */
		private static void pressKey(Minecraft mc, int key) throws Exception {
			java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, net.minecraft.client.input.KeyEvent.class);
			m.setAccessible(true);
			m.invoke(mc.keyboardHandler, mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_PRESS, new net.minecraft.client.input.KeyEvent(key, 0, 0));
		}

		/** Hover each menu slot in turn and press the queue key on it, two ticks apart; true when all are done. */
		private boolean queueByKey(Minecraft mc, int t, int... menuSlots) throws Exception {
			int i = t / 4, phase = t % 4;
			if (i >= menuSlots.length) return true;
			if (phase == 0) hover(mc, menuSlots[i]);
			if (phase == 2) pressKey(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_R);
			return false;
		}

		private void screenshot(Minecraft mc, String name) {
			shots++;
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, "autoanvil-" + name + ".png", Ui.mainRenderTarget(mc), 1,
					c -> AutoAnvil.LOGGER.info("[selftest] screenshot: {}", c.getString()));
		}

		private void tick(Minecraft mc) {
			ticks++;
			if (stepIdx == 0 && steps.isEmpty()) build();
			if (stepIdx >= 2 && Ui.screen(mc) instanceof PauseScreen) Ui.setScreen(mc, null);
			if (stepIdx >= steps.size()) return;
			try {
				if (steps.get(stepIdx).tick(mc, ticks - stepStart)) {
					stepIdx++;
					stepStart = ticks;
				} else if (ticks - stepStart > 20 * 240) {
					check(false, "step " + stepIdx + " timed out");
					finish(mc);
				}
			} catch (Throwable t) {
				AutoAnvil.LOGGER.error("[selftest] FAIL: exception", t);
				failures.add("exception " + t);
				finish(mc);
			}
		}

		private void add(Step s) {
			steps.add(s);
		}

		private void build() {
			// 0: world
			add((mc, t) -> {
				if (!(Ui.screen(mc) instanceof TitleScreen) || Ui.overlay(mc) != null) return false;
				mc.options.pauseOnLostFocus = false;
				AutoAnvil.CONFIG = new Config(); // defaults, whatever earlier runs saved
				AutoAnvil.CONFIG.save();
				String name = "autoanvil-selftest-" + (System.currentTimeMillis() / 1000);
				LevelSettings settings = Ui.survivalWorld(name);
				mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(4321L, false, false),
						Ui::flatDimensions, Ui.screen(mc));
				return true;
			});
			// 1: anvil
			add((mc, t) -> {
				if (mc.level == null || mc.player == null || Ui.screen(mc) != null || t < 40) return false;
				mc.player.connection.sendCommand("time set noon");
				anvil = onServer(mc, () -> {
					ServerPlayer p = sp(mc);
					BlockPos pos = p.blockPosition().east(2);
					p.level().setBlock(pos, Blocks.ANVIL.defaultBlockState(), 3);
					return pos;
				});
				check(!mc.player.hasInfiniteMaterials(), "survival mode (XP costs and the 40-level cap apply)");
				return true;
			});

			// ---- S1: helmet, starts with 0 levels, XP bottles splashed while it waits ----
			add((mc, t) -> {
				setup(mc, 0, () -> List.of(new ItemStack(Items.NETHERITE_HELMET),
						book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1),
						book(mc, Enchantments.AQUA_AFFINITY, 1), book(mc, Enchantments.RESPIRATION, 3), book(mc, Enchantments.VANISHING_CURSE, 1),
						new ItemStack(Items.DIAMOND_BOOTS)));
				openAnvil(mc);
				return true;
			});
			add((mc, t) -> Ui.screen(mc) instanceof AnvilScreen && t > 10 && AutoAnvil.analysis != null);
			add((mc, t) -> t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_HELMET)));
			add((mc, t) -> {
				if (t < 5) return false;
				Analysis a = AutoAnvil.analysis;
				check(a != null && a.plan != null && a.inputs.size() == 6, "S1 plan uses all 6 helmet books (" + (a == null ? null : a.problem) + ")");
				int ready = 0;
				if (a != null) for (Analysis.Row r : a.rows) if (r.state() == Analysis.State.READY) ready++;
				check(ready == 6, "S1 panel marks 6 enchantments ready (" + ready + ")");
				check(!AutoAnvil.CONFIG.combineBooksFirst, "S1 default order is the cheapest one");
				check(a != null && a.xpNeeded > 0, "S1 panel shows the XP still needed (" + (a == null ? 0 : a.xpNeeded) + " points)");
				check(press("start"), "S1 Start button clicked");
				return true;
			});
			add((mc, t) -> {
				runDone();
				if (t < 60) return false;
				Runner r = AutoAnvil.runner;
				check(r != null && r.waitingForXp && r.stepsDone == 0, "S1 waits for XP at level 0 without taking anything (" + (r == null ? null : r.status) + ")");
				screenshot(mc, "waiting-for-xp");
				check(Panel.current.press("tab:steps"), "S1 Steps tab clicked");
				return true;
			});
			java.util.Set<String> stepIds = new java.util.TreeSet<>();
			add((mc, t) -> {
				// the test window is small, so the list scrolls: wheel through it like a player would
				if (t < 3) return false;
				for (Panel.Hit h : Panel.current.hits()) if (h.id().startsWith("step:")) stepIds.add(h.id());
				if (t == 3) screenshot(mc, "steps-tab");
				if (t < 20) {
					if (t % 3 == 0) {
						for (Panel.Hit h : Panel.current.hits()) {
							if (h.id().startsWith("step:")) {
								Panel.current.mouseScrolled(h.x0() + 5, h.y0() + 3, 0, -1);
								break;
							}
						}
					}
					return false;
				}
				check(stepIds.size() == 6, "S1 Steps tab lists the 6 anvil steps " + stepIds);
				Panel.current.press("tab:enchants");
				return true;
			});
			add((mc, t) -> {
				// a friend splashing bottles: a few every half second while the mod is waiting
				if (AutoAnvil.running() && AutoAnvil.runner.waitingForXp && t % 10 == 0) {
					for (int i = 0; i < 4; i++) {
						mc.player.connection.sendCommand("execute at @s run summon experience_bottle ~" + (i % 2 == 0 ? "0.5" : "-0.5") + " ~3 ~" + (i < 2 ? "0.5" : "-0.5") + " {Motion:[0.0,-0.2,0.0]}");
						bottles++;
					}
				}
				return runDone();
			});
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S1");
				check(sawWaiting, "S1 waited for XP between steps");
				List<ItemStack> inv = inventory(mc);
				ItemStack helmet = find(inv, Items.NETHERITE_HELMET);
				AutoAnvil.LOGGER.info("[selftest] S1 result {} after {} bottles", describe(helmet), bottles);
				check(level(mc, helmet, Enchantments.PROTECTION) == 4 && level(mc, helmet, Enchantments.UNBREAKING) == 3
								&& level(mc, helmet, Enchantments.MENDING) == 1 && level(mc, helmet, Enchantments.AQUA_AFFINITY) == 1
								&& level(mc, helmet, Enchantments.RESPIRATION) == 3 && level(mc, helmet, Enchantments.VANISHING_CURSE) == 1,
						"S1 helmet has Prot IV, Unbreaking III, Mending, Aqua Affinity, Respiration III, Vanishing (server copy)");
				check(books(inv) == 0, "S1 all six books used");
				check(EnchantmentHelper.getEnchantmentsForCrafting(find(inv, Items.DIAMOND_BOOTS)).isEmpty(), "S1 the boots in the inventory were left alone");
				return true;
			});

			// ---- S2: your choice - only Mending + Unbreaking on a chestplate, Protection book kept ----
			add((mc, t) -> {
				setup(mc, 60, () -> List.of(new ItemStack(Items.NETHERITE_CHESTPLATE),
						book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_CHESTPLATE)));
			add((mc, t) -> {
				if (t < 3) return false;
				check(Panel.current.press("ench:minecraft:protection"), "S2 Protection row clicked");
				return true;
			});
			add((mc, t) -> {
				if (t < 5) return false;
				Boolean p = AutoAnvil.CONFIG.profiles.getOrDefault("chestplate", java.util.Map.of()).get("minecraft:protection");
				check(Boolean.FALSE.equals(p), "S2 chestplate profile now has Protection off (saved to config)");
				Analysis a = AutoAnvil.analysis;
				check(a != null && a.plan != null && a.inputs.size() == 2, "S2 plan uses just the Unbreaking and Mending books");
				check(press("start"), "S2 Start clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S2");
				List<ItemStack> inv = inventory(mc);
				ItemStack chest = find(inv, Items.NETHERITE_CHESTPLATE);
				AutoAnvil.LOGGER.info("[selftest] S2 result {}", describe(chest));
				check(level(mc, chest, Enchantments.UNBREAKING) == 3 && level(mc, chest, Enchantments.MENDING) == 1
						&& level(mc, chest, Enchantments.PROTECTION) == 0, "S2 chestplate has exactly Unbreaking III + Mending");
				check(books(inv) == 1, "S2 the Protection book is still in the inventory");
				Panel.current.press("ench:minecraft:protection"); // tick it again for the next runs
				return true;
			});

			// ---- S3: everything into one armor book, then that book onto boots ----
			add((mc, t) -> {
				if (t < 5) return false;
				setup(mc, 100, () -> List.of(book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.UNBREAKING, 3),
						book(mc, Enchantments.VANISHING_CURSE, 1), book(mc, Enchantments.FEATHER_FALLING, 4), book(mc, Enchantments.AQUA_AFFINITY, 1),
						book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.DEPTH_STRIDER, 3)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> x.isBook() && x.bookKind.key().equals("armor")));
			add((mc, t) -> {
				if (t < 5) return false;
				Analysis a = AutoAnvil.analysis;
				check(a != null && a.plan != null && a.inputs.size() == 7, "S3 armor book plan uses all 7 books (" + (a == null ? null : a.problem) + ")");
				check(a != null && a.plan != null && a.plan.result().repairCost == 7, "S3 combined book gets the lowest possible prior-work penalty (7)");
				screenshot(mc, "armor-book-plan");
				boolean missing = false;
				for (Panel.Hit h : Panel.current.hits()) missing |= h.id().equals("missing");
				check(missing, "S3 panel says which books to get to max the armor book (Respiration, Soul Speed, Swift Sneak)");
				check(press("start"), "S3 Start (combine into one book) clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S3 book");
				List<ItemStack> inv = inventory(mc);
				check(books(inv) == 1, "S3 seven books became one (" + books(inv) + " books)");
				ItemStack b = find(inv, Items.ENCHANTED_BOOK);
				AutoAnvil.LOGGER.info("[selftest] S3 book {}", describe(b));
				check(level(mc, b, Enchantments.PROTECTION) == 4 && level(mc, b, Enchantments.UNBREAKING) == 3 && level(mc, b, Enchantments.MENDING) == 1
								&& level(mc, b, Enchantments.VANISHING_CURSE) == 1 && level(mc, b, Enchantments.FEATHER_FALLING) == 4
								&& level(mc, b, Enchantments.AQUA_AFFINITY) == 1 && level(mc, b, Enchantments.DEPTH_STRIDER) == 3,
						"S3 the book holds Prot IV, Unb III, Mending, Vanishing, Feather Falling IV, Aqua Affinity, Depth Strider III");
				onServer(mc, () -> sp(mc).getInventory().add(new ItemStack(Items.NETHERITE_BOOTS)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_BOOTS)));
			add((mc, t) -> {
				if (t < 5) return false;
				Analysis a = AutoAnvil.analysis;
				check(a != null && a.plan != null && a.plan.steps.size() == 1, "S3 the combined book goes onto the boots in one step");
				check(press("start"), "S3 Start (boots) clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S3 boots");
				ItemStack boots = find(inventory(mc), Items.NETHERITE_BOOTS);
				AutoAnvil.LOGGER.info("[selftest] S3 boots {}", describe(boots));
				check(level(mc, boots, Enchantments.PROTECTION) == 4 && level(mc, boots, Enchantments.FEATHER_FALLING) == 4
								&& level(mc, boots, Enchantments.DEPTH_STRIDER) == 3 && level(mc, boots, Enchantments.MENDING) == 1
								&& level(mc, boots, Enchantments.UNBREAKING) == 3 && level(mc, boots, Enchantments.VANISHING_CURSE) == 1
								&& level(mc, boots, Enchantments.AQUA_AFFINITY) == 0,
						"S3 boots got everything but Aqua Affinity (helmet-only, ignored by the anvil)");
				return true;
			});

			// ---- S4: "All items": sword, spear, pickaxe; the iron axe is skipped ----
			add((mc, t) -> {
				setup(mc, 250, () -> List.of(new ItemStack(Items.IRON_AXE), new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.NETHERITE_SPEAR),
						new ItemStack(Items.DIAMOND_PICKAXE),
						book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.LOOTING, 3),
						book(mc, Enchantments.FIRE_ASPECT, 2), book(mc, Enchantments.SWEEPING_EDGE, 3), book(mc, Enchantments.LUNGE, 3),
						book(mc, Enchantments.EFFICIENCY, 5), book(mc, Enchantments.SILK_TOUCH, 1), book(mc, Enchantments.FORTUNE, 3),
						book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.UNBREAKING, 3),
						book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.MENDING, 1)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_SWORD)));
			add((mc, t) -> {
				if (t < 5) return false;
				List<Target> inc = AutoAnvil.includedItems();
				boolean axe = false;
				for (Target x : inc) axe |= x.stack.is(Items.IRON_AXE);
				check(inc.size() == 3 && !axe, "S4 \"All items\" covers sword, spear, pickaxe and not the iron axe (" + inc.size() + ")");
				check(press("all"), "S4 All items clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S4");
				check(lastRunner != null && lastRunner.jobsDone == 3, "S4 three items enchanted (" + (lastRunner == null ? 0 : lastRunner.jobsDone) + ")");
				List<ItemStack> inv = inventory(mc);
				ItemStack sword = find(inv, Items.NETHERITE_SWORD), spear = find(inv, Items.NETHERITE_SPEAR), pick = find(inv, Items.DIAMOND_PICKAXE);
				AutoAnvil.LOGGER.info("[selftest] S4 sword {}", describe(sword));
				AutoAnvil.LOGGER.info("[selftest] S4 spear {}", describe(spear));
				AutoAnvil.LOGGER.info("[selftest] S4 pickaxe {}", describe(pick));
				check(level(mc, sword, Enchantments.SHARPNESS) == 5 && level(mc, sword, Enchantments.LOOTING) == 3
						&& level(mc, sword, Enchantments.FIRE_ASPECT) == 2 && level(mc, sword, Enchantments.SWEEPING_EDGE) == 3
						&& level(mc, sword, Enchantments.UNBREAKING) == 3 && level(mc, sword, Enchantments.MENDING) == 1,
						"S4 sword: Sharpness V, Looting III, Fire Aspect II, Sweeping Edge III, Unbreaking III, Mending");
				check(level(mc, spear, Enchantments.LUNGE) == 3 && level(mc, spear, Enchantments.SHARPNESS) == 5
						&& level(mc, spear, Enchantments.UNBREAKING) == 3 && level(mc, spear, Enchantments.MENDING) == 1,
						"S4 spear: Lunge III, Sharpness V, Unbreaking III, Mending");
				check(level(mc, pick, Enchantments.EFFICIENCY) == 5 && level(mc, pick, Enchantments.SILK_TOUCH) == 1 && level(mc, pick, Enchantments.FORTUNE) == 0
						&& level(mc, pick, Enchantments.UNBREAKING) == 3 && level(mc, pick, Enchantments.MENDING) == 1,
						"S4 pickaxe: Efficiency V, Silk Touch (over Fortune), Unbreaking III, Mending");
				boolean fortuneLeft = false;
				for (ItemStack s : inv) fortuneLeft |= s.is(Items.ENCHANTED_BOOK) && level(mc, s, Enchantments.FORTUNE) == 3;
				check(fortuneLeft, "S4 the Fortune book is left unused");
				check(EnchantmentHelper.getEnchantmentsForCrafting(find(inv, Items.IRON_AXE)).isEmpty(), "S4 iron axe untouched");
				return true;
			});

			// ---- S5: the anvil disappears mid-run (as if it broke); nothing is lost and Start resumes ----
			add((mc, t) -> {
				setup(mc, 100, () -> List.of(new ItemStack(Items.NETHERITE_LEGGINGS), book(mc, Enchantments.PROTECTION, 4),
						book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.SWIFT_SNEAK, 3)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_LEGGINGS)));
			add((mc, t) -> {
				if (t < 5) return false;
				check(press("start"), "S5 Start clicked");
				return true;
			});
			add((mc, t) -> {
				Runner r = AutoAnvil.runner;
				// break it while the second step's inputs are going into the anvil
				if (r == null || r.stepsDone < 1 || r.phase() != Runner.Phase.PUT_RIGHT) return false;
				keepAnvil = false;
				onServer(mc, () -> mc.getSingleplayerServer().overworld().removeBlock(anvil, false));
				return true;
			});
			add((mc, t) -> {
				if (Ui.screen(mc) instanceof AnvilScreen || t < 20) return false;
				Runner r = AutoAnvil.runner;
				check(r != null && r.finished() && "Anvil closed".equals(r.error), "S5 run stops when the anvil goes away (" + (r == null ? null : r.status) + ")");
				List<ItemStack> inv = inventory(mc);
				int pieces = books(inv) + (find(inv, Items.NETHERITE_LEGGINGS).isEmpty() ? 0 : 1);
				check(pieces == 4, "S5 nothing lost: leggings + 3 books (one combined) back in the inventory (" + pieces + " items)");
				onServer(mc, () -> mc.getSingleplayerServer().overworld().setBlock(anvil, Blocks.ANVIL.defaultBlockState(), 3));
				keepAnvil = true;
				return true;
			});
			add((mc, t) -> {
				if (t == 5) openAnvil(mc);
				return Ui.screen(mc) instanceof AnvilScreen && t > 15;
			});
			add((mc, t) -> t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_LEGGINGS)));
			add((mc, t) -> {
				if (t < 5) return false;
				check(press("start"), "S5 Start clicked again on the new anvil");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S5 resumed");
				ItemStack legs = find(inventory(mc), Items.NETHERITE_LEGGINGS);
				AutoAnvil.LOGGER.info("[selftest] S5 leggings {}", describe(legs));
				check(level(mc, legs, Enchantments.PROTECTION) == 4 && level(mc, legs, Enchantments.UNBREAKING) == 3
								&& level(mc, legs, Enchantments.MENDING) == 1 && level(mc, legs, Enchantments.SWIFT_SNEAK) == 3,
						"S5 leggings finished after resuming: Prot IV, Unbreaking III, Mending, Swift Sneak III");
				return true;
			});

			// ---- S6: an already-worked helmet: not everything fits under 40, the most important ones go on ----
			add((mc, t) -> {
				setup(mc, 100, () -> {
					ItemStack worked = new ItemStack(Items.DIAMOND_HELMET);
					worked.set(DataComponents.REPAIR_COST, 31); // been on an anvil 5 times
					return List.of(worked, book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1),
							book(mc, Enchantments.RESPIRATION, 3), book(mc, Enchantments.AQUA_AFFINITY, 1), book(mc, Enchantments.VANISHING_CURSE, 1));
				});
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.DIAMOND_HELMET)));
			add((mc, t) -> {
				if (t < 5) return false;
				Analysis a = AutoAnvil.analysis;
				int dropped = 0;
				if (a != null) for (Analysis.Row r : a.rows) if (r.state() == Analysis.State.TOO_EXPENSIVE) dropped++;
				check(a != null && a.plan != null && dropped > 0 && a.plan.maxStep < 40,
						"S6 plan leaves " + dropped + " out to stay under 40 (priciest step " + (a == null || a.plan == null ? -1 : a.plan.maxStep) + ")");
				screenshot(mc, "too-expensive");
				check(press("start"), "S6 Start clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S6");
				ItemStack h = find(inventory(mc), Items.DIAMOND_HELMET);
				AutoAnvil.LOGGER.info("[selftest] S6 helmet {}", describe(h));
				// penalty 31 leaves room for one step of at most 8: a Mending+Unbreaking book (1 + 2 + 3) is the best fit
				check(level(mc, h, Enchantments.MENDING) == 1 && level(mc, h, Enchantments.UNBREAKING) == 3,
						"S6 the most important ones made it: Mending, Unbreaking III");
				return true;
			});
			// ---- S7: "Order: books first" - all books into one book, then one step onto the helmet ----
			add((mc, t) -> {
				if (t < 5) return false;
				setup(mc, 100, () -> List.of(new ItemStack(Items.NETHERITE_HELMET),
						book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1),
						book(mc, Enchantments.AQUA_AFFINITY, 1), book(mc, Enchantments.RESPIRATION, 3), book(mc, Enchantments.VANISHING_CURSE, 1)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_HELMET)));
			add((mc, t) -> {
				if (t == 1) Panel.current.press("tab:steps");
				if (t == 4) check(Panel.current.press("order"), "S7 order switched on the Steps tab");
				return t > 8;
			});
			add((mc, t) -> {
				Analysis a = AutoAnvil.analysis;
				boolean shape = AutoAnvil.CONFIG.combineBooksFirst && a != null && a.plan != null && a.plan.booksFirst;
				if (shape) {
					var steps = a.plan.steps;
					for (int i = 0; i < steps.size() - 1; i++) shape &= steps.get(i).piece.book;
					shape &= steps.get(steps.size() - 1).left.leaf == dev.autoanvil.plan.Planner.Node.ITEM;
				}
				check(shape, "S7 books first: 5 steps fuse the 6 books into one, the last step puts it on the helmet");
				screenshot(mc, "books-first");
				check(press("start"), "S7 Start clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S7 books first");
				ItemStack h = find(inventory(mc), Items.NETHERITE_HELMET);
				check(level(mc, h, Enchantments.PROTECTION) == 4 && level(mc, h, Enchantments.RESPIRATION) == 3 && level(mc, h, Enchantments.MENDING) == 1
						&& level(mc, h, Enchantments.UNBREAKING) == 3 && level(mc, h, Enchantments.AQUA_AFFINITY) == 1 && level(mc, h, Enchantments.VANISHING_CURSE) == 1,
						"S7 helmet got all 6 from the one fused book");
				return true;
			});

			// ---- S8: books first, but 7 boot books in one book would cost 42 on the boots: cheapest order instead ----
			add((mc, t) -> {
				if (t < 5) return false;
				setup(mc, 150, () -> List.of(new ItemStack(Items.NETHERITE_BOOTS), book(mc, Enchantments.PROTECTION, 4),
						book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.VANISHING_CURSE, 1),
						book(mc, Enchantments.FEATHER_FALLING, 4), book(mc, Enchantments.DEPTH_STRIDER, 3), book(mc, Enchantments.SOUL_SPEED, 3)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.stack.is(Items.NETHERITE_BOOTS)));
			add((mc, t) -> {
				if (t < 5) return false;
				Analysis a = AutoAnvil.analysis;
				int onItem = 0;
				if (a != null && a.plan != null) for (var s : a.plan.steps) if (!s.piece.book) onItem++;
				check(a != null && a.plan != null && a.inputs.size() == 7 && !a.plan.booksFirst && onItem >= 2,
						"S8 too much for one book: all 7 books still used, put on the boots in " + onItem + " parts");
				screenshot(mc, "boots-parts");
				check(press("start"), "S8 Start clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S8");
				ItemStack b = find(inventory(mc), Items.NETHERITE_BOOTS);
				AutoAnvil.LOGGER.info("[selftest] S8 boots {}", describe(b));
				check(level(mc, b, Enchantments.PROTECTION) == 4 && level(mc, b, Enchantments.UNBREAKING) == 3 && level(mc, b, Enchantments.MENDING) == 1
								&& level(mc, b, Enchantments.VANISHING_CURSE) == 1 && level(mc, b, Enchantments.FEATHER_FALLING) == 4
								&& level(mc, b, Enchantments.DEPTH_STRIDER) == 3 && level(mc, b, Enchantments.SOUL_SPEED) == 3,
						"S8 boots got all 7: Prot IV, Unb III, Mending, Vanishing, Feather Falling IV, Depth Strider III, Soul Speed III");
				// back to the default order (the Steps-tab switch itself was clicked in S7; no books are left to show it now)
				AutoAnvil.CONFIG.combineBooksFirst = false;
				return true;
			});
			// ---- S9: three identical netherite swords; #2 left out of "All items", then done on its own ----
			add((mc, t) -> {
				if (t < 5) return false;
				setup(mc, 200, () -> List.of(new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.NETHERITE_SWORD),
						book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.SHARPNESS, 5),
						book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.UNBREAKING, 3), book(mc, Enchantments.UNBREAKING, 3),
						book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.MENDING, 1), book(mc, Enchantments.MENDING, 1),
						book(mc, Enchantments.LOOTING, 3), book(mc, Enchantments.LOOTING, 3), book(mc, Enchantments.FIRE_ASPECT, 2)));
				return true;
			});
			add((mc, t) -> t > 10 && t % 3 == 0 && select(x -> !x.isBook() && x.slot == 31)); // hotbar slot 2
			add((mc, t) -> {
				if (t < 3) return false;
				String name = AutoAnvil.displayName(AutoAnvil.current());
				check(name.endsWith("#2"), "S9 identical swords are told apart on the panel (" + name + ")");
				check(Panel.current.press("include"), "S9 sword #2 unticked from All items");
				return true;
			});
			add((mc, t) -> {
				if (t < 3) return false;
				int swords = 0;
				boolean second = false;
				for (Target x : AutoAnvil.includedItems()) {
					if (x.stack.is(Items.NETHERITE_SWORD)) swords++;
					if (x.slot == 31) second = true;
				}
				check(swords == 2 && !second, "S9 All items covers swords #1 and #3 only (" + swords + ")");
				check(press("all"), "S9 All items clicked");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S9 two swords");
				check(lastRunner != null && lastRunner.jobsDone == 2, "S9 two swords enchanted in one go");
				ItemStack second = onServer(mc, () -> sp(mc).getInventory().getItem(1).copy());
				check(second.is(Items.NETHERITE_SWORD) && EnchantmentHelper.getEnchantmentsForCrafting(second).isEmpty(),
						"S9 the unticked sword #2 was left alone");
				int full = 0, fire = 0;
				for (ItemStack s : inventory(mc)) {
					if (!s.is(Items.NETHERITE_SWORD)) continue;
					AutoAnvil.LOGGER.info("[selftest] S9 {}", describe(s));
					if (level(mc, s, Enchantments.SHARPNESS) == 5 && level(mc, s, Enchantments.UNBREAKING) == 3
							&& level(mc, s, Enchantments.MENDING) == 1 && level(mc, s, Enchantments.LOOTING) == 3) full++;
					if (level(mc, s, Enchantments.FIRE_ASPECT) == 2) fire++;
				}
				check(full == 2 && fire == 1, "S9 both swords got Sharpness V, Unbreaking III, Mending, Looting III; one Fire Aspect II (" + full + ", " + fire + ")");
				return true;
			});
			add((mc, t) -> t > 5 && t % 3 == 0 && select(x -> !x.isBook() && x.slot == 31));
			add((mc, t) -> {
				if (t < 3) return false;
				check(Panel.current.press("include"), "S9 sword #2 ticked again");
				return true;
			});
			add((mc, t) -> {
				if (t < 3) return false;
				check(press("all"), "S9 All items clicked again");
				return true;
			});
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S9 sword #2");
				check(lastRunner != null && lastRunner.jobsDone == 1, "S9 only sword #2 still had something to gain");
				List<ItemStack> inv = inventory(mc);
				int done = 0;
				for (ItemStack s : inv) {
					if (s.is(Items.NETHERITE_SWORD) && level(mc, s, Enchantments.SHARPNESS) == 5 && level(mc, s, Enchantments.UNBREAKING) == 3
							&& level(mc, s, Enchantments.MENDING) == 1) done++;
				}
				check(done == 3 && books(inv) == 0, "S9 all three swords have Sharpness V, Unbreaking III, Mending; every book used");
				return true;
			});
			// ---- S10: the queue key: hover items and press R; done in that order, one more queued while it runs ----
			add((mc, t) -> {
				if (t < 5) return false;
				setup(mc, 200, () -> List.of(new ItemStack(Items.NETHERITE_HELMET), new ItemStack(Items.DIAMOND_SWORD),
						new ItemStack(Items.NETHERITE_PICKAXE), new ItemStack(Items.IRON_AXE),
						book(mc, Enchantments.PROTECTION, 4), book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.SHARPNESS, 5), book(mc, Enchantments.EFFICIENCY, 5),
						book(mc, Enchantments.EFFICIENCY, 5), book(mc, Enchantments.FORTUNE, 3), book(mc, Enchantments.LOOTING, 3)));
				ItemQueue.ENTRIES.clear();
				return true;
			});
			// hotbar 0..3 = menu slots 30..33: helmet, sword, pickaxe, iron axe. Queue pickaxe, axe, sword, sword again
			// (takes it back out), helmet.
			add((mc, t) -> t > 10 && queueByKey(mc, t - 11, 32, 33, 31, 31, 30));
			add((mc, t) -> {
				if (t < 3) return false;
				List<Integer> order = new ArrayList<>();
				for (ItemQueue.Entry e : ItemQueue.ENTRIES) order.add(e.invIndex());
				check(order.equals(List.of(2, 3, 0)), "S10 R on hovered items queued pickaxe, iron axe, helmet; R twice on the sword left it out " + order);
				boolean listed = false, button = false;
				for (Panel.Hit h : Panel.current.hits()) {
					listed |= h.id().equals("queue:3");
					button |= h.id().equals("queue");
				}
				check(listed && button, "S10 panel lists the queue and offers the Queue button");
				screenshot(mc, "queue");
				check(press("queue"), "S10 Queue button clicked");
				return true;
			});
			add((mc, t) -> t > 4 && queueByKey(mc, t - 5, 31)); // the sword, while it runs
			add((mc, t) -> runDone());
			add((mc, t) -> {
				if (t < 5) return false;
				checkRun("S10 queue");
				List<String> done = new ArrayList<>();
				if (lastRunner != null) for (String r : lastRunner.results) done.add(r.substring(0, r.indexOf(':')));
				check(done.equals(List.of("Netherite Pickaxe", "Iron Axe", "Netherite Helmet", "Diamond Sword")),
						"S10 done in queue order, the sword queued mid-run last " + done);
				List<ItemStack> inv = inventory(mc);
				check(level(mc, find(inv, Items.IRON_AXE), Enchantments.EFFICIENCY) == 5, "S10 the iron axe was done because you queued it (\"All items\" skips iron)");
				check(level(mc, find(inv, Items.DIAMOND_SWORD), Enchantments.SHARPNESS) == 5, "S10 the sword queued mid-run got Sharpness V");
				check(ItemQueue.isEmpty(), "S10 queue empty afterwards");
				return true;
			});
			add((mc, t) -> {
				if (t < 40) return false;
				check(mismatches == 0, "no cost mismatches anywhere (" + mismatches + ")");
				finish(mc);
				return true;
			});
		}

		private void finish(Minecraft mc) {
			if (failures.isEmpty()) AutoAnvil.LOGGER.info("[selftest] PASS ({} checks)", checks);
			else AutoAnvil.LOGGER.error("[selftest] FAIL: {}", failures);
			stepIdx = Integer.MAX_VALUE / 2;
			mc.stop();
		}
	}
}
