package com.example.camerapathaddon.client.screen;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.PathSettings;
import com.example.tudursvehiclemod.asset.DummyPilotSkinRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A camera control block's name and path settings (interpolation, end behaviour, chunk loading, ...). "Done" sends them; "Delete all keys" acts at once after a confirming second click. */
public class CameraPathSettingsScreen extends Screen {

	private static final int COLOR_TEXT = 0xFFFFFFFF;

	private final Screen parent;
	private final BlockPos pos;

	private PathSettings.RotationMode rotationMode;
	private PathSettings.EndAction endAction;
	private boolean loop;
	private boolean waitForLoad;
	private boolean preloadWhole;
	private boolean prioritizeAhead;
	/** Seats with a dummy pilot (bit n = seat n). */
	private int dummyPilotSeats;
	private boolean redstoneTrigger;
	private String skin;
	private final List<String> skins = new ArrayList<>();

	private TextFieldWidget nameField;
	private TextFieldWidget aheadField;
	private TextFieldWidget widthField;
	private TextFieldWidget behindField;
	private TextFieldWidget keySpacingField;
	private final List<Runnable> labelUpdaters = new ArrayList<>();
	private boolean clearArmed;

	public CameraPathSettingsScreen(Screen parent, BlockPos pos) {
		super(Text.translatable("gui.camerapathaddon.settings_title"));
		this.parent = parent;
		this.pos = pos;
	}

	private int top() {
		return Math.max(16, this.height / 2 - 112);
	}

	@Override
	protected void init() {
		CameraControlBlockEntity block = CameraControlScreen.blockAt(this.client, this.pos);
		if (block == null) {
			this.close();
			return;
		}
		PathSettings s = block.getPath().settings();
		this.rotationMode = s.rotationMode();
		this.endAction = s.endAction();
		this.loop = s.loop();
		this.waitForLoad = s.waitForLoad();
		this.preloadWhole = s.preloadWhole();
		this.prioritizeAhead = s.prioritizeAhead();
		this.dummyPilotSeats = s.dummyPilotSeats();
		this.redstoneTrigger = s.redstoneTrigger();
		this.skin = s.dummyPilotSkin();
		this.skins.clear();
		this.skins.add("");
		for (String id : DummyPilotSkinRegistry.tudursvehiclemod$getAvailableSkinIds()) {
			if (!id.isEmpty() && !this.skins.contains(id)) {
				this.skins.add(id);
			}
		}
		this.labelUpdaters.clear();
		this.clearArmed = false;

		int cx = this.width / 2;
		int left = cx - 204;
		int right = cx + 4;
		int y = this.top();

		this.nameField = new TextFieldWidget(this.textRenderer, left + 70, y, 334, 18, Text.translatable("gui.camerapathaddon.name"));
		this.nameField.setMaxLength(CameraControlBlockEntity.MAX_NAME_LENGTH);
		this.nameField.setText(block.getName());
		this.addDrawableChild(this.nameField);

		int base = y + 24;
		this.toggle(left, base, () -> Text.translatable("gui.camerapathaddon.rotation_mode",
				Text.translatable("gui.camerapathaddon.rotation_mode." + this.rotationMode.asString())), () ->
				this.rotationMode = this.rotationMode == PathSettings.RotationMode.EULER ? PathSettings.RotationMode.QUATERNION : PathSettings.RotationMode.EULER);
		this.toggle(left, base + 21, () -> Text.translatable("gui.camerapathaddon.end_action",
				Text.translatable("gui.camerapathaddon.end_action." + this.endAction.asString())), () ->
				this.endAction = this.endAction == PathSettings.EndAction.HOLD ? PathSettings.EndAction.RELEASE : PathSettings.EndAction.HOLD);
		this.toggle(left, base + 42, () -> onOff("gui.camerapathaddon.loop", this.loop), () -> this.loop = !this.loop);
		this.toggle(left, base + 63, () -> onOff("gui.camerapathaddon.redstone_trigger", this.redstoneTrigger), () -> this.redstoneTrigger = !this.redstoneTrigger);
		// Dummy pilots: one toggle per seat (P = pilot seat, then 2..8).
		ButtonWidget pilotLabel = ButtonWidget.builder(Text.translatable("gui.camerapathaddon.dummy_pilot_seats"), b -> {
		}).dimensions(left, base + 84, 64, 20).build();
		pilotLabel.active = false;
		this.addDrawableChild(pilotLabel);
		for (int seat = 0; seat < PathSettings.MAX_PILOT_SEATS; seat++) {
			int bit = 1 << seat;
			String name = seat == 0 ? "P" : Integer.toString(seat + 1);
			ButtonWidget seatButton = ButtonWidget.builder(Text.empty(), b -> {
				this.dummyPilotSeats ^= bit;
				this.labelUpdaters.forEach(Runnable::run);
			}).dimensions(left + 66 + seat * 17, base + 84, 16, 20).build();
			this.labelUpdaters.add(() -> seatButton.setMessage(Text.literal(name).withColor((this.dummyPilotSeats & bit) != 0 ? 0xFF55FF55 : 0xFF707070)));
			this.addDrawableChild(seatButton);
		}
		this.toggle(left, base + 105, () -> Text.translatable("gui.camerapathaddon.dummy_pilot_skin",
				this.skin.isEmpty() ? Text.translatable("gui.camerapathaddon.default") : Text.literal(this.skin)), () -> {
			int index = Math.max(0, this.skins.indexOf(this.skin));
			this.skin = this.skins.get((index + 1) % this.skins.size());
		});
		this.keySpacingField = this.numberField(left + 150, base + 127, s.keySpacing());

		this.aheadField = this.numberField(right + 150, base, s.chunkAhead());
		this.widthField = this.numberField(right + 150, base + 21, s.chunkWidth());
		this.behindField = this.numberField(right + 150, base + 42, s.chunkBehind());
		this.toggle(right, base + 63, () -> onOff("gui.camerapathaddon.wait_for_load", this.waitForLoad), () -> this.waitForLoad = !this.waitForLoad);
		this.toggle(right, base + 84, () -> onOff("gui.camerapathaddon.preload_whole", this.preloadWhole), () -> this.preloadWhole = !this.preloadWhole);
		this.toggle(right, base + 105, () -> onOff("gui.camerapathaddon.prioritize_ahead", this.prioritizeAhead), () -> this.prioritizeAhead = !this.prioritizeAhead);

		ButtonWidget clear = ButtonWidget.builder(Text.translatable("gui.camerapathaddon.clear_keys"), b -> {
			if (this.clearArmed) {
				this.clearArmed = false;
				ClientPlayNetworking.send(new Payloads.BlockAction(this.pos, Payloads.Action.CLEAR_KEYS, -1, 0));
			} else {
				this.clearArmed = true;
			}
			this.labelUpdaters.forEach(Runnable::run);
		}).dimensions(right, base + 127, 200, 20).build();
		this.labelUpdaters.add(() -> clear.setMessage(Text.translatable(this.clearArmed ? "gui.camerapathaddon.confirm" : "gui.camerapathaddon.clear_keys")));
		this.addDrawableChild(clear);

		int bottom = base + 156;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> {
			this.sendSettings();
			this.client.setScreen(this.parent);
		}).dimensions(cx - 100, bottom, 98, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> this.client.setScreen(this.parent))
				.dimensions(cx + 2, bottom, 98, 20).build());
		this.labelUpdaters.forEach(Runnable::run);
	}

	private void toggle(int x, int y, Supplier<Text> label, Runnable action) {
		ButtonWidget button = ButtonWidget.builder(label.get(), b -> {
			action.run();
			this.labelUpdaters.forEach(Runnable::run);
		}).dimensions(x, y, 200, 20).build();
		this.labelUpdaters.add(() -> button.setMessage(label.get()));
		this.addDrawableChild(button);
	}

	private TextFieldWidget numberField(int x, int y, int value) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 1, 50, 18, Text.empty());
		field.setMaxLength(6);
		field.setText(Integer.toString(value));
		this.addDrawableChild(field);
		return field;
	}

	private static Text onOff(String key, boolean value) {
		return Text.translatable(key, Text.translatable(value ? "options.on" : "options.off"));
	}

	private static int intOf(TextFieldWidget field, int fallback) {
		Integer value = CameraControlScreen.parseInt(field.getText());
		return value != null ? value : fallback;
	}

	private void sendSettings() {
		CameraControlBlockEntity block = CameraControlScreen.blockAt(this.client, this.pos);
		PathSettings old = block != null ? block.getPath().settings() : PathSettings.DEFAULT;
		PathSettings settings = new PathSettings(this.rotationMode, this.endAction, this.loop,
				intOf(this.aheadField, old.chunkAhead()), intOf(this.widthField, old.chunkWidth()), intOf(this.behindField, old.chunkBehind()),
				this.waitForLoad, this.preloadWhole, this.prioritizeAhead,
				intOf(this.keySpacingField, old.keySpacing()),
				this.dummyPilotSeats, this.skin, this.redstoneTrigger).sanitized();
		ClientPlayNetworking.send(new Payloads.EditSettings(this.pos, settings, this.nameField.getText()));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int left = cx - 204;
		int right = cx + 4;
		int y = this.top();
		int base = y + 24;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, y - 12, COLOR_TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.name"), left, y + 5, COLOR_TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.key_spacing"), left, base + 133, COLOR_TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.chunk_ahead"), right, base + 6, COLOR_TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.chunk_width"), right, base + 27, COLOR_TEXT);
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.chunk_behind"), right, base + 48, COLOR_TEXT);
	}

	@Override
	public void tick() {
		super.tick();
		if (CameraControlScreen.blockAt(this.client, this.pos) == null) {
			this.close();
		}
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
