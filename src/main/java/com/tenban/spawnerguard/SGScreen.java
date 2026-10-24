package com.tenban.spawnerguard;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Man hinh cai dat SpawnerGuard - mo bang lenh /sg */
public class SGScreen extends Screen {

	private static final int[] SELL_OPTIONS = {15, 30, 60, 90, 120};
	private static final int[] RADIUS_OPTIONS = {50, 75, 100};
	private static final int[] STAND_OPTIONS = {6, 10, 20, 30, 40};
	private static final int[] SIT_OPTIONS = {4, 6, 10, 20};

	public SGScreen() {
		super(Component.literal("SpawnerGuard"));
	}

	@Override
	protected void init() {
		int colW = 150, gap = 10, h = 20, step = 24;
		int left = this.width / 2 - colW - gap / 2;
		int right = this.width / 2 + gap / 2;
		int top = 40;

		// Cot trai: chuc nang chinh
		addToggle(left, top, "Bao ve spawner", () -> SpawnerGuard.guardEnabled, v -> SpawnerGuard.guardEnabled = v);
		addToggle(left, top + step, "Tu dong ban (1h)", () -> SpawnerGuard.autoSell, v -> {
			SpawnerGuard.autoSell = v;
			if (v) SpawnerGuard.idleSinceMs = System.currentTimeMillis();
		});
		addToggle(left, top + step * 2, "Tu vao lai", () -> SpawnerGuard.autoRejoin, v -> SpawnerGuard.autoRejoin = v);
		addToggle(left, top + step * 3, "Tu go /home 1", () -> SpawnerGuard.autoHome, v -> SpawnerGuard.autoHome = v);

		// Cot phai: phu tro
		addToggle(right, top, "Tieng bao dong", () -> SpawnerGuard.alarmSound, v -> SpawnerGuard.alarmSound = v);
		addToggle(right, top + step, "Cui khi dao", () -> SpawnerGuard.sneakWhileMining, v -> SpawnerGuard.sneakWhileMining = v);
		addToggle(right, top + step * 2, "Kiem tra truoc", () -> SpawnerGuard.preflightCheck, v -> SpawnerGuard.preflightCheck = v);
		addToggle(right, top + step * 3, "Chay nen", () -> SpawnerGuard.backgroundRun, v -> SpawnerGuard.backgroundRun = v);

		// Nut chon gia tri
		int y2 = top + step * 4 + 6;
		Button radius = Button.builder(radiusLabel(), b -> {
			SpawnerGuard.detectRadius = next(RADIUS_OPTIONS, (int) SpawnerGuard.detectRadius);
			b.setMessage(radiusLabel());
			SpawnerGuard.saveConfig();
		}).bounds(left, y2, colW, h).build();
		addRenderableWidget(radius);

		Button interval = Button.builder(intervalLabel(), b -> {
			SpawnerGuard.sellIntervalMin = next(SELL_OPTIONS, SpawnerGuard.sellIntervalMin);
			b.setMessage(intervalLabel());
			SpawnerGuard.saveConfig();
		}).bounds(right, y2, colW, h).build();
		addRenderableWidget(interval);

		int y2b = y2 + step;
		addRenderableWidget(Button.builder(standLabel(), b -> {
			SpawnerGuard.standTicks = next(STAND_OPTIONS, SpawnerGuard.standTicks);
			b.setMessage(standLabel());
			SpawnerGuard.saveConfig();
		}).bounds(left, y2b, colW, h).build());
		addRenderableWidget(Button.builder(sitLabel(), b -> {
			SpawnerGuard.sitTicks = next(SIT_OPTIONS, SpawnerGuard.sitTicks);
			b.setMessage(sitLabel());
			SpawnerGuard.saveConfig();
		}).bounds(right, y2b, colW, h).build());

		// Hanh dong
		int y3 = y2b + step + 6;
		addRenderableWidget(Button.builder(Component.literal("Luu tam can cu (dung giua khung)"), b -> SpawnerGuard.setCenterHere())
			.bounds(left, y3, colW * 2 + gap, h).build());
		addRenderableWidget(Button.builder(Component.literal("Ban thu ngay"), b -> {
			SpawnerGuard.sellNow();
			onClose();
		}).bounds(left, y3 + step, colW, h).build());
		addRenderableWidget(Button.builder(Component.literal("Dong"), b -> onClose())
			.bounds(right, y3 + step, colW, h).build());
	}

	private void addToggle(int x, int y, String name, BooleanSupplier get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(label(name, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(label(name, get.getAsBoolean()));
			SpawnerGuard.saveConfig();
		}).bounds(x, y, 150, 20).build());
	}

	private static Component label(String name, boolean on) {
		return Component.literal(name + ": ")
			.append(Component.literal(on ? "BAT" : "TAT").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private static Component radiusLabel() {
		return Component.literal("Ban kinh quet: " + (int) SpawnerGuard.detectRadius + " block");
	}

	private static Component intervalLabel() {
		return Component.literal("Chu ky ban: " + SpawnerGuard.sellIntervalMin + " phut");
	}

	private static Component standLabel() {
		return Component.literal(String.format("Cho dung len: %.1fs", SpawnerGuard.standTicks / 20.0));
	}

	private static Component sitLabel() {
		return Component.literal(String.format("Cho ngoi: %.1fs", SpawnerGuard.sitTicks / 20.0));
	}

	private static int next(int[] options, int current) {
		for (int i = 0; i < options.length; i++) {
			if (options[i] == current) return options[(i + 1) % options.length];
		}
		return options[0];
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawCenteredString(this.font, Component.literal("SpawnerGuard"), this.width / 2, 12, 0xFFFFFFFF);

		String center = SpawnerGuard.hasCenter
			? String.format("Tam can cu: %.0f %.0f %.0f", SpawnerGuard.cx, SpawnerGuard.cy, SpawnerGuard.cz)
			: "CHUA LUU TAM CAN CU";
		g.drawCenteredString(this.font, Component.literal(center), this.width / 2, 26, SpawnerGuard.hasCenter ? 0xFFAAAAAA : 0xFFFF5555);
		g.drawCenteredString(this.font, Component.literal("Trang thai: " + SpawnerGuard.state + "  |  Da dao: " + SpawnerGuard.minedCount),
			this.width / 2, this.height - 16, 0xFFAAAAAA);
	}
}
