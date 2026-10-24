package com.tenban.spawnerguard;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

/**
 * SpawnerGuard - luon bat. Co nguoi lai gan -> cui xuong, dao spawner (Silk Touch) quanh minh,
 * tui day thi cat vao ender chest, dao xong thi thoat game, dem nguoc roi tu vao lai.
 */
public class SpawnerGuard implements ClientModInitializer {

	// ===== Thong so =====
	static final int SPAWNER_RADIUS = 30;        // dao spawner trong ban kinh nay
	static final double ACTIVE_RADIUS = 40.0;    // mod chi hoat dong trong ban kinh nay quanh tam can cu
	static final double REACH = 4.2;             // tam voi an toan
	static final int ENDER_RADIUS = 10;          // tim ender chest trong ban kinh nay
	static final int LEAVE_MIN_MIN = 5, LEAVE_MIN_MAX = 10;   // phut cho truoc khi vao lai
	static final int HOME_MIN_MIN = 2, HOME_MIN_MAX = 5;      // phut sau khi vao lai moi go /home 1
	static final int LAG_REJOIN_MIN_S = 15, LAG_REJOIN_MAX_S = 60; // vao lai khi bi van do lag/loi mang

	// ===== Cai dat (chinh trong GUI: go /sg) =====
	static boolean guardEnabled = true;      // bao ve spawner (quet nguoi + dao)
	static boolean alarmSound = true;        // tieng bao dong
	static boolean sneakWhileMining = true;  // cui khi dao
	static boolean autoRejoin = true;        // tu vao lai sau khi thoat / bi van
	static boolean autoHome = true;          // go /home 1 sau khi vao lai
	static boolean preflightCheck = true;    // kiem tra cup/ender chest khi vao can cu
	static boolean backgroundRun = true;     // khong tam dung khi mat focus
	static double detectRadius = 100.0;      // ban kinh quet nguoi (thuc te bi server gioi han ~48-64)
	static int sellIntervalMin = 60;         // phut giua 2 lan tu ban
	static int standTicks = 10;              // cho khi dung len giua 2 lan dao (20 tick = 1 giay)
	static int sitTicks = 6;                 // cho sau khi ngoi truoc khi dao
	static boolean openGui = false;

	enum State { IDLE, MINING, STORING, SELLING }

	// ===== Trang thai =====
	static final Random RNG = new Random();
	static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("spawnerguard.properties");

	static State state = State.IDLE;
	static long tick = 0;

	// tam can cu
	static boolean hasCenter = false;
	static double cx, cy, cz;
	static String cDim = "";

	// dao spawner
	static BlockPos target = null;
	static long targetSince = 0;
	static final Set<BlockPos> blacklist = new HashSet<>();
	static Vec3 stuckRef = null;
	static long stuckRefTick = 0;
	static int minedCount = 0;
	static int retryRounds = 0;
	static long goneSince = 0;
	static int digPhase = 0;      // 0 = ngoi chuan bi, 1 = dang dao, 2 = dung len/tha nut
	static long phaseTick = 0;
	static int phaseInv = 0;
	static int failStreak = 0;   // so lan lien tiep dao xong nhung spawner khong vao tui
	static double triggerDistance = 0;
	static long lastAlarm = 0;
	static long lastNoPickWarn = 0;

	// cat ender chest
	static BlockPos chestPos = null;
	static int storeStage = 0;
	static long storeTick = 0;
	static long lastUseTry = 0;
	static int lastClickedMenuSlot = -1;
	static int sameSlotTries = 0;
	static boolean justStored = false;

	// ket noi / vao lai
	static boolean wasConnected = false;
	static boolean wasInBase = false;
	static ServerData lastServer = null;
	static long rejoinAt = 0;
	static boolean rejoinPending = false; // true = da len lich vao lai (de go /home 1 sau do)
	static boolean modInitiatedRejoin = false;
	static long homeAt = 0;

	// tu dong ban (sell all) moi 1 gio treo
	static boolean autoSell = false;
	static long idleSinceMs = 0;
	static int sellStage = 0;
	static long sellTick = 0;
	static long lastInteractTry = 0;
	static int openTries = 0;
	static int soldCount = 0;
	static final Set<BlockPos> sellDone = new HashSet<>();

	@Override
	public void onInitializeClient() {
		loadConfig();
		try { License.refresh(); } catch (Exception ignored) { }

		ClientTickEvents.END_CLIENT_TICK.register(SpawnerGuard::onTick);

		// Dem nguoc tren man hinh "Mat ket noi"
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> {
					if (rejoinAt > 0) {
						long left = Math.max(0, rejoinAt - System.currentTimeMillis()) / 1000;
						String t = String.format("Tu vao lai sau %02d:%02d", left / 60, left % 60);
						g.drawCenteredString(client.font, Component.literal("[SpawnerGuard] " + t), s.width / 2, 20, 0xFFFFFF55);
					}
				});
			}
		});

		// Lenh: /sg center , /sg status
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("sg").executes(ctx -> { if (locked()) return 0; openGui = true; return Command.SINGLE_SUCCESS; })
				.then(ClientCommandManager.literal("center").executes(ctx -> {
					if (locked()) return 0;
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null || mc.level == null) return 0;
					LocalPlayer p = mc.player;
					cx = p.getX(); cy = p.getY(); cz = p.getZ();
					cDim = mc.level.dimension().toString();
					hasCenter = true;
					saveConfig();
					msg(String.format("Da luu tam can cu: %.1f %.1f %.1f. Mod hoat dong trong %d block quanh day.", cx, cy, cz, (int) ACTIVE_RADIUS));
					preflight(p, true);
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommandManager.literal("key")
					.then(ClientCommandManager.argument("code", StringArgumentType.greedyString()).executes(ctx -> {
						String code = StringArgumentType.getString(ctx, "code");
						if (License.licensed) { msg("Da kich hoat roi, khong can nhap lai."); return Command.SINGLE_SUCCESS; }
						msg("Dang kiem tra ma, vui long doi...");
						License.activateAsync(code, r -> {
							switch (r) {
								case "OK" -> msg("KICH HOAT THANH CONG! Luu vinh vien, khong can nhap lai. Go /sg de mo menu, nho go /sg center o giua khung obsidian.");
								case "USED" -> msg("Ma nay da duoc dung tren tai khoan khac, khong the dung lai.");
								case "INVALID" -> msg("Ma khong dung.");
								case "NOCONFIG" -> msg("Chua cau hinh may chu kich hoat trong mod (lien he nguoi ban).");
								default -> msg("Khong ket noi duoc may chu kich hoat, kiem tra mang va thu lai.");
							}
						});
						return Command.SINGLE_SUCCESS;
					})))
				.then(ClientCommandManager.literal("autosell")
					.then(ClientCommandManager.literal("on").executes(ctx -> {
					if (locked()) return 0;
						autoSell = true;
						idleSinceMs = System.currentTimeMillis();
						saveConfig();
						msg("Tu dong ban: BAT (moi 1 gio treo).");
						return Command.SINGLE_SUCCESS;
					}))
					.then(ClientCommandManager.literal("off").executes(ctx -> {
					if (locked()) return 0;
						autoSell = false;
						saveConfig();
						msg("Tu dong ban: TAT.");
						return Command.SINGLE_SUCCESS;
					})))
				.then(ClientCommandManager.literal("sellnow").executes(ctx -> {
					if (locked()) return 0;
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null || mc.level == null) return 0;
					if (state != State.IDLE) { msg("Dang ban viec, thu lai sau."); return 0; }
					if (!hasCenter) { msg("Chua luu tam can cu (/sg center)."); return 0; }
					startSelling(mc, mc.player);
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommandManager.literal("status").executes(ctx -> {
					if (locked()) return 0;
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null || mc.level == null) return 0;
					LocalPlayer p = mc.player;
					msg("Tam can cu: " + (hasCenter ? String.format("%.0f %.0f %.0f", cx, cy, cz) : "CHUA LUU (go /sg center)"));
					msg("Trang thai: " + state + ", da dao: " + minedCount);
					long sellLeft = (autoSell && idleSinceMs > 0) ? Math.max(0, (sellIntervalMin * 60L * 1000L) - (System.currentTimeMillis() - idleSinceMs)) / 60000 : -1;
					msg("Tu dong ban: " + (autoSell ? "BAT, con ~" + sellLeft + " phut" : "TAT"));
					double nd = nearestPlayerDistance(mc, p);
					msg("Nguoi gan nhat: " + (nd < 0 ? "khong thay ai" : String.format("%.1f block", nd)));
					preflight(p, true);
					return Command.SINGLE_SUCCESS;
				})));
		});
	}

	// =====================================================================
	// TICK CHINH
	// =====================================================================
	static void onTick(Minecraft mc) {
		tick++;
		long now = System.currentTimeMillis();

		// Chua kich hoat -> mod khong lam gi
		if (!License.licensed) {
			if (mc.player != null && mc.level != null && tick % 1200 == 20) {
				msg("CHUA KICH HOAT. Go /sg key <ma> de kich hoat (chi can 1 lan).");
			}
			return;
		}

		// Chay nen: khong tam dung khi mat focus
		if (backgroundRun && mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus = false;
		if (openGui && mc.screen == null && mc.level != null) { openGui = false; mc.setScreen(new SGScreen()); }

		handleConnection(mc, now);

		LocalPlayer p = mc.player;
		if (mc.level == null || p == null || mc.gameMode == null) {
			return;
		}

		// /home 1 sau khi vao lai
		if (homeAt > 0 && now >= homeAt) {
			homeAt = 0;
			boolean far = !cDim.equals(mc.level.dimension().toString())
				|| p.position().distanceTo(new Vec3(cx, cy, cz)) > ACTIVE_RADIUS;
			if (hasCenter && far) {
				p.connection.sendCommand("home 1");
				msg("Da go /home 1 (dang o xa can cu).");
			}
		}

		if (!hasCenter) {
			if (tick % 400 == 1) overlay("SpawnerGuard: CHUA LUU TAM CAN CU - dung giua khung obsidian roi go /sg center");
			return;
		}

		boolean inBase = cDim.equals(mc.level.dimension().toString())
			&& p.position().distanceTo(new Vec3(cx, cy, cz)) <= ACTIVE_RADIUS;

		if (!inBase) {
			if (state != State.IDLE) resetAll(mc);
			wasInBase = false;
			return;
		}
		if (!wasInBase) {
			wasInBase = true;
			idleSinceMs = now;
			if (preflightCheck) preflight(p, true);
		}

		if (!guardEnabled && (state == State.MINING || state == State.STORING)) resetAll(mc);

		switch (state) {
			case IDLE -> tickIdle(mc, p);
			case MINING -> tickMining(mc, p);
			case STORING -> tickStoring(mc, p);
			case SELLING -> tickSelling(mc, p);
		}
	}

	// =====================================================================
	// IDLE: quet nguoi choi
	// =====================================================================
	static void tickIdle(Minecraft mc, LocalPlayer p) {
		double nd = nearestPlayerDistance(mc, p);
		if (tick % 10 == 0) {
			overlay(!guardEnabled ? "SpawnerGuard: bao ve dang TAT" : nd < 0 ? "SpawnerGuard: dang quet - khong thay ai"
				: String.format("SpawnerGuard: nguoi gan nhat %.0f block", nd));
		}
		long nowMs = System.currentTimeMillis();
		if (autoSell && idleSinceMs > 0 && nowMs - idleSinceMs >= (sellIntervalMin * 60L * 1000L) && (!guardEnabled || nd < 0 || nd > detectRadius)) {
			startSelling(mc, p);
			return;
		}
		if (guardEnabled && nd >= 0 && nd <= detectRadius) {
			triggerDistance = nd;
			state = State.MINING;
			target = null;
			blacklist.clear();
			retryRounds = 0;
			goneSince = 0;
			digPhase = 0;
			phaseTick = tick;
			failStreak = 0;
			minedCount = 0;
			justStored = false;
			stuckRef = null;
			lastAlarm = 0;
			msg(String.format("CO NGUOI LAI GAN (%.0f block)! Bat dau dao spawner.", nd));
			alarm(mc);
		}
	}

	// =====================================================================
	// MINING: dao spawner
	// =====================================================================
	static void tickMining(Minecraft mc, LocalPlayer p) {
		var level = mc.level;

		if (tick - lastAlarm > 40) { lastAlarm = tick; alarm(mc); }

		// 1. Cup Silk Touch
		int slot = findSilkPickaxe(p);
		if (slot < 0) {
			releaseMove(mc);
			if (tick - lastNoPickWarn > 100) {
				lastNoPickWarn = tick;
				msg("!!! KHONG CO CUP SILK TOUCH TRONG HOTBAR - dung yen, khong dao !!!");
				alarm(mc);
			}
			// Neu nguoi la da di mat thi quay ve IDLE
			if (nearestPlayerDistance(mc, p) < 0) resetAll(mc);
			return;
		}
		if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);

		// 2. Cui xuong
		mc.options.keyShift.setDown(digPhase != 2 && digPhase != 4 && sneakWhileMining);

		// 3. Tui day?
		if (inventoryFull(p)) {
			if (justStored) {
				// vua cat xong ma van day (do vat pham khac) -> thoat de bao toan
				leave(mc, p, "Tui van day sau khi cat vao ender chest");
				return;
			}
			startStoring(mc, p);
			return;
		}

		// 3b. Kiem tra spawner da vao tui chua (sau moi lan dao)
		if (digPhase == 3) {
			releaseMove(mc);
			if (countSpawnerItems(p) > phaseInv) {
				minedCount++;
				failStreak = 0;
				targetSince = tick;      // co tien do -> reset bo dem 25 giay
				overlay("SpawnerGuard: da dao " + minedCount + " spawner (da vao tui)");
				digPhase = 2;            // dung len roi ngoi dao tiep
				phaseTick = tick;
			} else if (tick - phaseTick >= 30) {
				failStreak++;
				if (failStreak >= 3 && target != null) {
					blacklist.add(target);
					msg("Bo qua 1 spawner: dao 3 lan khong thay vao tui.");
					failStreak = 0;
					target = null;
					digPhase = 0;
					phaseTick = tick;
				} else {
					overlay("SpawnerGuard: spawner chua vao tui - nhay len, cho 2 giay roi dao lai");
					digPhase = 4;
					phaseTick = tick;
				}
			}
			return;
		}
		if (digPhase == 4) {             // nhay 1 cai, cho 2 giay, roi ngoi dao tiep
			releaseMove(mc);
			long dt = tick - phaseTick;
			if (dt >= 2 && dt <= 3) mc.options.keyJump.setDown(true);
			if (dt >= 40) { digPhase = 0; phaseTick = tick; }
			return;
		}

		// 4. Chon muc tieu
		if (target != null && !level.getBlockState(target).is(Blocks.SPAWNER)) {
			// Spawner dang gop (stack): sau moi lan dao server co the dat lai block.
			// Doi mot chut xem block co hien lai khong truoc khi ket luan da het.
			if (goneSince == 0) goneSince = tick;
			if (tick - goneSince < 12) return;
			stopDig(mc);
			target = null;
			stuckRef = null;
			goneSince = 0;
		} else {
			goneSince = 0;
		}
		if (target == null) {
			target = pickNearestSpawner(mc, p);
			if (target == null) {
				if (!blacklist.isEmpty() && retryRounds < 2) {
					retryRounds++;
					msg("Thu lai " + blacklist.size() + " spawner da bo qua (lan " + retryRounds + ").");
					blacklist.clear();
					return;
				}
				leave(mc, p, "Da dao " + minedCount + " spawner, bo qua " + blacklist.size());
				return;
			}
			targetSince = tick;
			digPhase = 0;
			phaseTick = tick;
			failStreak = 0;
			stuckRef = p.position();
			stuckRefTick = tick;
		}

		Vec3 eye = p.getEyePosition();
		Vec3 center = Vec3.atCenterOf(target);
		double dist = eye.distanceTo(center);

		if (tick - targetSince > 500) { // 25 giay khong xong -> bo qua
			blacklist.add(target);
			stopDig(mc);
			target = null;
			return;
		}

		if (dist <= REACH) {
			BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
			if (hit.getType() == HitResult.Type.BLOCK) {
				BlockPos hp = hit.getBlockPos();
				if (hp.equals(target)) {
					releaseMove(mc);
					aim(p, center);
					// Giong nguoi that: ngoi -> giu nut dao den khi vo 1 spawner -> dung len + tha nut -> ngoi lai
					if (digPhase == 0) {            // ngoi chuan bi
						if (tick - phaseTick >= sitTicks) {
							digPhase = 1;
							phaseTick = tick;
							phaseInv = countSpawnerItems(p);
						}
						return;
					}
					if (digPhase == 1) {            // giu nut dao (game tu dao theo crosshair)
						boolean broke = !level.getBlockState(target).is(Blocks.SPAWNER) || countSpawnerItems(p) > phaseInv;
						if (broke) {
							stopDig(mc);            // tha nut dao
							digPhase = 3;           // kiem tra spawner da vao tui chua
							phaseTick = tick;
							return;
						}
						if (tick - phaseTick > 80) { // 4 giay khong vo -> dung len, ngoi lai
							stopDig(mc);
							digPhase = 2;
							phaseTick = tick;
							overlay("SpawnerGuard: dao ket, dung len ngoi lai thu lai");
							return;
						}
						HitResult hr = mc.hitResult;
						boolean onTarget = hr instanceof BlockHitResult bh && bh.getBlockPos().equals(target);
						mc.options.keyAttack.setDown(onTarget); // chi dao khi crosshair dang tren dung spawner
						return;
					}
					// digPhase == 2: dung len, tha nut, cho roi ngoi lai
					if (tick - phaseTick >= standTicks) { digPhase = 0; phaseTick = tick; }
					return;
				} else if (level.getBlockState(hp).is(Blocks.SPAWNER) && !blacklist.contains(hp)) {
					// co spawner khac chan o truoc, dao cai do truoc
					stopDig(mc);
					target = hp;
					targetSince = tick;
					digPhase = 0;
					phaseTick = tick;
					return;
				}
			}
			// bi chan boi khoi khac -> thu di chuyen them (co phat hien ket ben duoi)
		}

		// 5. Di chuyen toi muc tieu
		walkToward(mc, p, center);
		if (tick - stuckRefTick >= 30) {
			if (stuckRef != null && p.position().distanceTo(stuckRef) < 0.3) {
				blacklist.add(target); // ket / bi che -> bo qua
				msg("Bo qua 1 spawner bi che/khong toi duoc.");
				stopDig(mc);
				target = null;
				releaseMove(mc);
			}
			stuckRef = p.position();
			stuckRefTick = tick;
		}
	}

	static BlockPos pickNearestSpawner(Minecraft mc, LocalPlayer p) {
		return pickNearestSpawner(mc, p, blacklist);
	}

	static BlockPos pickNearestSpawner(Minecraft mc, LocalPlayer p, Set<BlockPos> excl) {
		var level = mc.level;
		BlockPos base = p.blockPosition();
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int r = SPAWNER_RADIUS;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dy * dy + dz * dz > r * r) continue;
					m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
					if (!level.getBlockState(m).is(Blocks.SPAWNER)) continue;
					BlockPos im = m.immutable();
					if (excl.contains(im)) continue;
					double d = eye.distanceToSqr(Vec3.atCenterOf(im));
					if (d < bestD) { bestD = d; best = im; }
				}
			}
		}
		return best;
	}

	// =====================================================================
	// STORING: cat spawner vao ender chest
	// =====================================================================
	static void startStoring(Minecraft mc, LocalPlayer p) {
		releaseMove(mc);
		stopDig(mc);
		chestPos = findEnderChest(mc, p);
		if (chestPos == null) {
			leave(mc, p, "Tui day va khong thay ender chest");
			return;
		}
		state = State.STORING;
		storeStage = 0;
		storeTick = tick;
		lastUseTry = 0;
		lastClickedMenuSlot = -1;
		sameSlotTries = 0;
		stuckRef = p.position();
		stuckRefTick = tick;
		msg("Tui day - di cat spawner vao ender chest.");
	}

	static void tickStoring(Minecraft mc, LocalPlayer p) {
		var level = mc.level;
		boolean menuOpen = p.containerMenu != p.inventoryMenu;

		if (storeStage == 0) {
			if (menuOpen) { storeStage = 1; storeTick = tick; return; }

			if (chestPos == null || !level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)) {
				chestPos = findEnderChest(mc, p);
				if (chestPos == null) { leave(mc, p, "Mat ender chest"); return; }
			}
			Vec3 c = Vec3.atCenterOf(chestPos);
			double d = p.getEyePosition().distanceTo(c);

			if (d > REACH) {
				walkToward(mc, p, c);
				if (tick - stuckRefTick >= 30) {
					if (stuckRef != null && p.position().distanceTo(stuckRef) < 0.3) {
						leave(mc, p, "Khong toi duoc ender chest");
						return;
					}
					stuckRef = p.position();
					stuckRefTick = tick;
				}
				return;
			}

			releaseMove(mc);
			mc.options.keyShift.setDown(false); // nha cui de mo duoc ender chest
			if (tick - storeTick < 4) return;   // cho trang thai cui cap nhat
			aim(p, c);
			if (tick - lastUseTry >= 10) {
				lastUseTry = tick;
				BlockHitResult hit = new BlockHitResult(c, p.getDirection().getOpposite(), chestPos, false);
				mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			}
			if (tick - storeTick > 400) leave(mc, p, "Khong mo duoc ender chest");
			return;
		}

		// storeStage == 1: chuyen spawner vao ender chest
		if (!menuOpen) { storeStage = 0; storeTick = tick; return; }
		if (tick - storeTick < 6) return;
		if ((tick - storeTick) % 4 != 0) return;

		AbstractContainerMenu menu = p.containerMenu;
		int found = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (s.container == p.getInventory() && s.getItem().is(Items.SPAWNER)) { found = i; break; }
		}
		if (found < 0) {
			p.closeContainer();
			justStored = true;
			state = State.MINING;
			mc.options.keyShift.setDown(sneakWhileMining);
			target = null;
			digPhase = 0;
			phaseTick = tick;
			msg("Da cat spawner vao ender chest, tiep tuc dao.");
			return;
		}
		if (found == lastClickedMenuSlot) {
			sameSlotTries++;
			if (sameSlotTries >= 3) {
				p.closeContainer();
				leave(mc, p, "Ender chest day, giu spawner trong tui");
				return;
			}
		} else {
			lastClickedMenuSlot = found;
			sameSlotTries = 0;
		}
		mc.gameMode.handleInventoryMouseClick(menu.containerId, found, 0, ClickType.QUICK_MOVE, p);
	}

	static BlockPos findEnderChest(Minecraft mc, LocalPlayer p) {
		var level = mc.level;
		BlockPos base = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int r = ENDER_RADIUS;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
					if (!level.getBlockState(m).is(Blocks.ENDER_CHEST)) continue;
					double d = base.distSqr(m);
					if (d < bestD) { bestD = d; best = m.immutable(); }
				}
			}
		}
		return best;
	}

	// =====================================================================
	// SELLING: chuot phai spawner -> Sell All (phoi vang) -> xac nhan (kinh xanh)
	// =====================================================================
	static void startSelling(Minecraft mc, LocalPlayer p) {
		state = State.SELLING;
		sellStage = 0;
		sellTick = tick;
		sellDone.clear();
		target = null;
		lastInteractTry = 0;
		openTries = 0;
		soldCount = 0;
		stuckRef = null;
		msg("Du 1 gio treo - bat dau ban loot trong spawner.");
	}

	static void finishSelling(Minecraft mc, LocalPlayer p) {
		msg("Ban xong " + soldCount + " spawner. Hen 1 gio sau ban tiep.");
		idleSinceMs = System.currentTimeMillis();
		resetAll(mc);
	}

	static void tickSelling(Minecraft mc, LocalPlayer p) {
		var level = mc.level;

		// Co nguoi lai gan -> huy ban, quay ve che do bao ve
		double nd = nearestPlayerDistance(mc, p);
		if (guardEnabled && nd >= 0 && nd <= detectRadius) {
			msg("Co nguoi lai gan - huy ban, chuyen sang bao ve spawner.");
			resetAll(mc);
			return;
		}

		mc.options.keyShift.setDown(false); // phai nha cui moi mo duoc menu
		boolean menuOpen = p.containerMenu != p.inventoryMenu;

		if (sellStage == 0) {
			if (menuOpen) { sellStage = 1; sellTick = tick; return; }

			if (target == null || !level.getBlockState(target).is(Blocks.SPAWNER)) {
				target = pickNearestSpawner(mc, p, sellDone);
				if (target == null) { finishSelling(mc, p); return; }
				targetSince = tick;
				stuckRef = p.position();
				stuckRefTick = tick;
				openTries = 0;
				lastInteractTry = 0;
			}

			Vec3 eye = p.getEyePosition();
			Vec3 center = Vec3.atCenterOf(target);
			double dist = eye.distanceTo(center);

			if (tick - targetSince > 600) { sellDone.add(target); target = null; return; }

			if (dist <= REACH) {
				BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
				if (hit.getType() == HitResult.Type.BLOCK) {
					BlockPos hp = hit.getBlockPos();
					if (hp.equals(target)) {
						releaseMove(mc);
						aim(p, center);
						if (tick - sellTick >= 4 && tick - lastInteractTry >= 15) {
							lastInteractTry = tick;
							openTries++;
							if (openTries > 5) { sellDone.add(target); target = null; return; }
							mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
						}
						return;
					} else if (level.getBlockState(hp).is(Blocks.SPAWNER) && !sellDone.contains(hp)) {
						target = hp;
						targetSince = tick;
						openTries = 0;
						return;
					}
				}
			}

			walkToward(mc, p, center);
			if (tick - stuckRefTick >= 30) {
				if (stuckRef != null && p.position().distanceTo(stuckRef) < 0.3) {
					sellDone.add(target);
					target = null;
					releaseMove(mc);
				}
				stuckRef = p.position();
				stuckRefTick = tick;
			}
			return;
		}

		if (sellStage == 1) { // menu spawner dang mo: bam "Sell All" (phoi vang)
			if (!menuOpen) { sellStage = 0; return; }
			if (tick - sellTick < 8 || (tick - sellTick) % 4 != 0) return;
			AbstractContainerMenu menu = p.containerMenu;
			int pick = -1, fallback = -1, goldCount = 0;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot sl = menu.slots.get(i);
				if (sl.container == p.getInventory()) continue;
				ItemStack st = sl.getItem();
				if (!st.is(Items.GOLD_INGOT)) continue;
				goldCount++;
				fallback = i;
				if (st.getHoverName().getString().toLowerCase().contains("sell")) { pick = i; break; }
			}
			if (pick < 0 && goldCount == 1) pick = fallback;
			if (pick < 0) {
				p.closeContainer();
				sellDone.add(target);
				target = null;
				sellStage = 0;
				msg("Khong thay nut Sell All, bo qua spawner nay.");
				return;
			}
			mc.gameMode.handleInventoryMouseClick(menu.containerId, pick, 0, ClickType.PICKUP, p);
			sellStage = 2;
			sellTick = tick;
			return;
		}

		if (sellStage == 2) { // cho khung xac nhan, bam kinh xanh
			if (!menuOpen) { sellDone.add(target); target = null; sellStage = 0; return; }
			if (tick - sellTick < 6 || (tick - sellTick) % 4 != 0) return;
			AbstractContainerMenu menu = p.containerMenu;
			int pane = -1;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot sl = menu.slots.get(i);
				if (sl.container == p.getInventory()) continue;
				ItemStack st = sl.getItem();
				if (st.is(Items.LIME_STAINED_GLASS_PANE) || st.is(Items.GREEN_STAINED_GLASS_PANE)
					|| st.is(Items.LIME_STAINED_GLASS) || st.is(Items.GREEN_STAINED_GLASS)) { pane = i; break; }
			}
			if (pane >= 0) {
				mc.gameMode.handleInventoryMouseClick(menu.containerId, pane, 0, ClickType.PICKUP, p);
				sellStage = 3;
				sellTick = tick;
				return;
			}
			if (tick - sellTick > 80) { // khong thay khung xac nhan (co the khong co gi de ban)
				p.closeContainer();
				sellDone.add(target);
				target = null;
				sellStage = 0;
			}
			return;
		}

		// sellStage == 3: vua bam xac nhan, doi roi dong menu, qua spawner ke tiep
		if (tick - sellTick < 12) return;
		if (menuOpen) p.closeContainer();
		soldCount++;
		sellDone.add(target);
		target = null;
		sellStage = 0;
	}

	// =====================================================================
	// THOAT GAME + VAO LAI
	// =====================================================================
	static void leave(Minecraft mc, LocalPlayer p, String why) {
		msg("Thoat game: " + why);
		releaseMove(mc);
		mc.options.keyShift.setDown(false);
		stopDig(mc);
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();

		int minutes = LEAVE_MIN_MIN + RNG.nextInt(LEAVE_MIN_MAX - LEAVE_MIN_MIN + 1);
		long secs = minutes * 60L + RNG.nextInt(60);
		rejoinAt = autoRejoin ? System.currentTimeMillis() + secs * 1000L : 0;
		modInitiatedRejoin = autoRejoin;
		rejoinPending = autoRejoin;
		if (mc.getCurrentServer() != null) lastServer = mc.getCurrentServer();

		Component reason = Component.literal(String.format(
			"[SpawnerGuard] Co nguoi lai gan (%.0f block). Da dao %d spawner. Ly do thoat: %s.",
			triggerDistance, minedCount, why));
		state = State.IDLE;
		target = null;
		if (mc.getConnection() != null) {
			mc.getConnection().getConnection().disconnect(reason);
		}
	}

	static void handleConnection(Minecraft mc, long now) {
		boolean connected = mc.level != null && mc.player != null;

		if (connected) {
			if (mc.getCurrentServer() != null) lastServer = mc.getCurrentServer();
			if (!wasConnected) {
				// vua vao game
				wasConnected = true;
				rejoinAt = 0;
				if (modInitiatedRejoin && autoHome) {
					modInitiatedRejoin = false;
					homeAt = now + (HOME_MIN_MIN * 60L + RNG.nextInt((HOME_MIN_MAX - HOME_MIN_MIN) * 60 + 1)) * 1000L;
				}
				rejoinPending = false;
			}
			return;
		}

		if (wasConnected) {
			wasConnected = false;
				wasInBase = false;
			state = State.IDLE;
			target = null;
		}

		// Bi van do lag/loi: man hinh "Mat ket noi" ma chua co lich vao lai
		if (autoRejoin && rejoinAt == 0 && lastServer != null && mc.screen instanceof DisconnectedScreen) {
			rejoinAt = now + (LAG_REJOIN_MIN_S + RNG.nextInt(LAG_REJOIN_MAX_S - LAG_REJOIN_MIN_S + 1)) * 1000L;
			modInitiatedRejoin = true; // van go /home 1 neu xuat hien xa can cu
		}

		if (rejoinAt > 0 && now >= rejoinAt && lastServer != null && !(mc.screen instanceof ConnectScreen)) {
			rejoinAt = 0;
			ServerData sd = lastServer;
			ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(sd.ip), sd, false, null);
		}
	}

	// =====================================================================
	// TIEN ICH
	// =====================================================================
	static double nearestPlayerDistance(Minecraft mc, LocalPlayer p) {
		double best = -1;
		for (AbstractClientPlayer o : mc.level.players()) {
			if (o == p || o.getUUID().equals(p.getUUID())) continue;
			double d = o.position().distanceTo(p.position()); // 3D
			if (best < 0 || d < best) best = d;
		}
		return best;
	}

	static int findSilkPickaxe(LocalPlayer p) {
		Holder<Enchantment> silk = Minecraft.getInstance().level.registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
		Inventory inv = p.getInventory();
		// uu tien o dang cam
		int cur = inv.getSelectedSlot();
		if (isSilkPick(inv.getItem(cur), silk)) return cur;
		for (int i = 0; i < 9; i++) {
			if (isSilkPick(inv.getItem(i), silk)) return i;
		}
		return -1;
	}

	static boolean isSilkPick(ItemStack s, Holder<Enchantment> silk) {
		if (s.isEmpty() || !s.is(ItemTags.PICKAXES)) return false;
		if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() < 20) return false; // sap hong
		return EnchantmentHelper.getItemEnchantmentLevel(silk, s) > 0;
	}

	static int countSpawnerItems(LocalPlayer p) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (st.is(Items.SPAWNER)) n += st.getCount();
		}
		return n;
	}

	static boolean inventoryFull(LocalPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) return false;
			if (s.is(Items.SPAWNER) && s.getCount() < s.getMaxStackSize()) return false;
		}
		return true;
	}

	static void preflight(LocalPlayer p, boolean verbose) {
		List<String> warn = new ArrayList<>();
		if (findSilkPickaxe(p) < 0) warn.add("KHONG co cup Silk Touch (hotbar, do ben >= 20)");
		if (findEnderChest(Minecraft.getInstance(), p) == null) warn.add("Khong thay ender chest trong " + ENDER_RADIUS + " block");
		if (!hasCenter) warn.add("Chua luu tam can cu (/sg center)");
		if (warn.isEmpty()) {
			if (verbose) msg("Kiem tra: OK (cup Silk Touch + ender chest san sang).");
		} else {
			for (String w : warn) msg("CANH BAO: " + w);
			alarm(Minecraft.getInstance());
		}
	}

	static void aim(LocalPlayer p, Vec3 to) {
		Vec3 eye = p.getEyePosition();
		double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));
		p.setYRot(yaw);
		p.setXRot(pitch);
	}

	static void walkToward(Minecraft mc, LocalPlayer p, Vec3 to) {
		Vec3 eye = p.getEyePosition();
		double dx = to.x - eye.x, dz = to.z - eye.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		p.setYRot(yaw);
		p.setXRot(0);
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
	}

	static void stopDig(Minecraft mc) {
		mc.options.keyAttack.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	static void releaseMove(Minecraft mc) {
		mc.options.keyAttack.setDown(false);
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
	}

	static void resetAll(Minecraft mc) {
		releaseMove(mc);
		mc.options.keyShift.setDown(false);
		if (mc.gameMode != null) stopDig(mc);
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		state = State.IDLE;
		target = null;
		chestPos = null;
	}

	static void alarm(Minecraft mc) {
		if (!alarmSound) return;
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 1.0f, 1.0f));
	}

	static boolean locked() {
		if (License.licensed) return false;
		msg("CHUA KICH HOAT. Go /sg key <ma> de kich hoat.");
		return true;
	}

	static void setCenterHere() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		LocalPlayer p = mc.player;
		cx = p.getX(); cy = p.getY(); cz = p.getZ();
		cDim = mc.level.dimension().toString();
		hasCenter = true;
		saveConfig();
		msg(String.format("Da luu tam can cu: %.1f %.1f %.1f", cx, cy, cz));
		preflight(p, true);
	}

	static void sellNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		if (state != State.IDLE) { msg("Dang ban viec, thu lai sau."); return; }
		if (!hasCenter) { msg("Chua luu tam can cu."); return; }
		startSelling(mc, mc.player);
	}

	static void msg(String s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.displayClientMessage(Component.literal("[SpawnerGuard] " + s), false);
	}

	static void overlay(String s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal(s), false);
	}

	// =====================================================================
	// CONFIG
	// =====================================================================
	static void loadConfig() {
		try {
			if (!Files.exists(CONFIG_PATH)) return;
			Properties pr = new Properties();
			try (InputStream in = Files.newInputStream(CONFIG_PATH)) { pr.load(in); }
			hasCenter = Boolean.parseBoolean(pr.getProperty("hasCenter", "false"));
			cx = Double.parseDouble(pr.getProperty("cx", "0"));
			cy = Double.parseDouble(pr.getProperty("cy", "0"));
			cz = Double.parseDouble(pr.getProperty("cz", "0"));
			cDim = pr.getProperty("dim", "");
			autoSell = Boolean.parseBoolean(pr.getProperty("autoSell", "false"));
			guardEnabled = Boolean.parseBoolean(pr.getProperty("guardEnabled", "true"));
			alarmSound = Boolean.parseBoolean(pr.getProperty("alarmSound", "true"));
			sneakWhileMining = Boolean.parseBoolean(pr.getProperty("sneakWhileMining", "true"));
			autoRejoin = Boolean.parseBoolean(pr.getProperty("autoRejoin", "true"));
			autoHome = Boolean.parseBoolean(pr.getProperty("autoHome", "true"));
			preflightCheck = Boolean.parseBoolean(pr.getProperty("preflightCheck", "true"));
			backgroundRun = Boolean.parseBoolean(pr.getProperty("backgroundRun", "true"));
			detectRadius = Double.parseDouble(pr.getProperty("detectRadius", "100"));
			sellIntervalMin = Integer.parseInt(pr.getProperty("sellIntervalMin", "60"));
			standTicks = Integer.parseInt(pr.getProperty("standTicks", "10"));
			sitTicks = Integer.parseInt(pr.getProperty("sitTicks", "6"));
		} catch (Exception e) {
			hasCenter = false;
		}
	}

	static void saveConfig() {
		try {
			Properties pr = new Properties();
			pr.setProperty("hasCenter", Boolean.toString(hasCenter));
			pr.setProperty("cx", Double.toString(cx));
			pr.setProperty("cy", Double.toString(cy));
			pr.setProperty("cz", Double.toString(cz));
			pr.setProperty("dim", cDim);
			pr.setProperty("autoSell", Boolean.toString(autoSell));
			pr.setProperty("guardEnabled", Boolean.toString(guardEnabled));
			pr.setProperty("alarmSound", Boolean.toString(alarmSound));
			pr.setProperty("sneakWhileMining", Boolean.toString(sneakWhileMining));
			pr.setProperty("autoRejoin", Boolean.toString(autoRejoin));
			pr.setProperty("autoHome", Boolean.toString(autoHome));
			pr.setProperty("preflightCheck", Boolean.toString(preflightCheck));
			pr.setProperty("backgroundRun", Boolean.toString(backgroundRun));
			pr.setProperty("detectRadius", Double.toString(detectRadius));
			pr.setProperty("sellIntervalMin", Integer.toString(sellIntervalMin));
			pr.setProperty("standTicks", Integer.toString(standTicks));
			pr.setProperty("sitTicks", Integer.toString(sitTicks));
			try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) { pr.store(out, "SpawnerGuard"); }
		} catch (Exception ignored) {
		}
	}
}
