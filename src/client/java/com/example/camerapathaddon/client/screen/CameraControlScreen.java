package com.example.camerapathaddon.client.screen;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.client.ClientPlaybackState;
import com.example.camerapathaddon.client.PathPreviewRenderer;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Interpolation;
import com.example.camerapathaddon.path.Keyframe;
import com.example.tudursvehiclemod.item.DroneRouteBookItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Editor of a camera control block: opened by right-clicking the block. Every change is sent to the server as one
 * small operation and comes back in the block entity update, and this screen always reads the block fresh - so what's
 * shown is what's saved.
 *
 * <p>The timeline bar along the top shows every key at its tick: click one to select it, drag it sideways to retime
 * it. The fields below edit the selected key (Apply or Enter sends them). The keys themselves come from the route
 * book in the block's slot ("Build from route book").
 *
 * <p><b>View check</b>: puts the player's own view on the path, paused at the selected key, and turns this screen
 * into a narrow side panel so the rest of the screen shows exactly what the camera sees there. Selecting another key
 * moves the view to it; edits show at once (the paused playback follows the block's path). The +/- buttons (or the
 * mouse wheel over a field) step a value. Closing the panel puts the player back where they were; "Play from here"
 * carries on along the path from the selected key instead.
 */
public class CameraControlScreen extends Screen {

	private static final int ROW = 21;
	private static final int LABEL_W = 62;
	private static final int FIELD_W = 120;
	private static final int BAR_H = 14;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM = 0xFFA0A0A0;
	private static final int COLOR_BAR = 0xC0202020;
	private static final int COLOR_BAR_BORDER = 0xFF808080;
	private static final int COLOR_MARK = 0xFFFFFFFF;
	private static final int COLOR_MARK_SELECTED = 0xFFFF2DFF;
	private static final String[] FIELD_KEYS = {"tick", "x", "y", "z", "rot_x", "rot_y", "rot_z", "fov"};
	/** Step sizes of the +/- buttons per field, for the fine / medium / coarse step setting. */
	private static final double[][] STEPS = {
			{1, 5, 20}, {0.1, 1, 10}, {0.1, 1, 10}, {0.1, 1, 10}, {1, 5, 15}, {1, 5, 15}, {1, 5, 15}, {1, 5, 10}};
	private static final String[] STEP_NAMES = {"fine", "medium", "coarse"};
	private static final int PANEL_X = 4;
	private static final int PANEL_W = 224;
	private static final int PANEL_BOTTOM = 222;
	private static final int COLOR_PANEL = 0xA0000000;

	private final BlockPos pos;

	private CameraPath shownPath;
	private int selected = -1;
	/** After an edit that may move the selected key to another index, the key at this tick is reselected once the update arrives. */
	private Integer pendingSelectTick;
	/** The user has typed into the key fields since they were last filled in - don't overwrite their input. */
	private boolean dirty;
	private boolean populating;

	private final TextFieldWidget[] keyFields = new TextFieldWidget[8];
	private ButtonWidget interpolationButton;
	private ButtonWidget previewButton;
	private ButtonWidget importButton;
	private TextFieldWidget shiftField;
	private TextFieldWidget scaleField;
	private TextFieldWidget speedField;
	private boolean importArmed;
	private int statusY;

	private int barX;
	private int barY;
	private int barW;
	private int dragKey = -1;
	private int dragTick;

	/** The layout in use was built for the view check (side panel) rather than the full editor. */
	private boolean panelLayout;
	/** The side panel is hidden to see the whole frame (any click or key brings it back). */
	private boolean panelHidden;
	private int stepLevel = 1;
	private int labelW = LABEL_W;
	private boolean startFromSelected;
	private ButtonWidget startFromButton;
	private ButtonWidget stepButton;
	/** The key tick the paused view was last moved to (view check). */
	private Integer inspectedTick;
	/** Closing without ending the view playback ("Play from here"). */
	private boolean keepPlaybackOnClose;

	public CameraControlScreen(BlockPos pos) {
		super(Text.translatable("block.camerapathaddon.camera_control"));
		this.pos = pos;
	}

	/** The client's copy of the camera control block at pos, or null. */
	static CameraControlBlockEntity blockAt(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null) {
			return null;
		}
		return client.world.getBlockEntity(pos) instanceof CameraControlBlockEntity block ? block : null;
	}

	private CameraControlBlockEntity block() {
		return blockAt(this.client, this.pos);
	}

	/** The player's own view playback, if it came from this block (they may be far from the block then). */
	private ClientPlaybackState.CameraPlayback awayPlayback() {
		ClientPlaybackState.CameraPlayback camera = ClientPlaybackState.camera;
		return camera != null && this.pos.equals(camera.source) ? camera : null;
	}

	/** Whether the player's view is held on this block's path (the view check, or paused there). */
	private boolean inspecting() {
		ClientPlaybackState.CameraPlayback camera = this.awayPlayback();
		return camera != null && camera.paused;
	}

	private CameraPath path() {
		ClientPlaybackState.CameraPlayback camera = this.awayPlayback();
		if (camera != null) {
			// Kept up to date by the server while paused - and the block itself may be out of the client's range.
			return camera.path();
		}
		CameraControlBlockEntity block = this.block();
		return block == null ? CameraPath.EMPTY : block.getPath();
	}

	private Text label() {
		CameraControlBlockEntity block = this.block();
		return block != null ? block.getLabel() : Text.literal(this.pos.getX() + ", " + this.pos.getY() + ", " + this.pos.getZ());
	}

	private int selectedTickOr(int fallback) {
		return this.hasSelection() ? this.path().keys().get(this.selected).tick() : fallback;
	}

	@Override
	protected void init() {
		this.panelLayout = this.inspecting();
		this.panelHidden = false;
		if (this.panelLayout) {
			this.initPanel();
		} else {
			this.initFull();
		}
		this.shownPath = null;
		this.refresh();
	}

	/** The view-check layout: everything in a narrow panel on the left, the rest of the screen is the camera's view. */
	private void initPanel() {
		int x = PANEL_X + 4;
		this.labelW = 34;
		this.barX = x;
		this.barW = PANEL_W - 8;
		this.barY = 30;
		int fieldsTop = this.barY + BAR_H + 6;
		for (int i = 0; i < 8; i++) {
			int column = i / 4;
			int row = i % 4;
			int cx = x + column * 110;
			int fy = fieldsTop + row * 19;
			TextFieldWidget field = this.newKeyField(i, cx + this.labelW, fy, 44, 16);
			int index = i;
			this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> this.nudge(index, -1)).dimensions(cx + this.labelW + 46, fy, 14, 16).build());
			this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> this.nudge(index, 1)).dimensions(cx + this.labelW + 61, fy, 14, 16).build());
			this.keyFields[i] = field;
		}
		int y = fieldsTop + 4 * 19 + 3;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> this.select(this.selected - 1)).dimensions(x, y, 18, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> this.select(this.selected + 1)).dimensions(x + 20, y, 18, 20).build());
		this.interpolationButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> this.cycleInterpolation()).dimensions(x + 40, y, 92, 20).build());
		this.stepButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			this.stepLevel = (this.stepLevel + 1) % STEPS[0].length;
			this.updateLabels();
		}).dimensions(x + 134, y, 82, 20).build());
		y += 22;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.apply"), b -> this.applyFields()).dimensions(x, y, 70, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.duplicate"), b -> this.duplicateSelected()).dimensions(x + 72, y, 72, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.delete"), b -> this.deleteSelected()).dimensions(x + 146, y, 70, 20).build());
		y += 22;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.play_here"), b -> {
			this.keepPlaybackOnClose = true;
			this.send(Payloads.Action.RESUME_CAMERA, this.selectedTickOr(-1), 0);
			this.close();
		}).dimensions(x, y, 107, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.hide_panel"), b -> this.panelHidden = true)
				.dimensions(x + 109, y, 107, 20).build());
		y += 22;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.end_inspect"), b -> this.close()).dimensions(x, y, 216, 20).build());
		this.previewButton = null;
		this.importButton = null;
		this.startFromButton = null;
		this.shiftField = null;
		this.scaleField = null;
		this.speedField = null;
	}

	private TextFieldWidget newKeyField(int i, int x, int y, int w, int h) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, w, h, Text.translatable("gui.camerapathaddon.field." + FIELD_KEYS[i]));
		field.setMaxLength(24);
		field.setChangedListener(text -> {
			if (!this.populating) {
				this.dirty = true;
			}
		});
		this.addDrawableChild(field);
		return field;
	}

	/** The full editor. */
	private void initFull() {
		this.labelW = LABEL_W;
		this.stepButton = null;
		int cx = this.width / 2;
		int top = Math.max(4, this.height / 2 - 134);
		this.barX = cx - 200;
		this.barW = 400;
		this.barY = top + 16;
		int x = cx - 202;

		int fieldsTop = this.barY + BAR_H + 6;
		for (int i = 0; i < 8; i++) {
			int column = i / 4;
			int row = i % 4;
			int fx = (column == 0 ? cx - 202 : cx + 4) + LABEL_W;
			this.keyFields[i] = this.newKeyField(i, fx, fieldsTop + row * ROW, FIELD_W, 18);
		}

		int y = fieldsTop + 4 * ROW + 1;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> this.select(this.selected - 1)).dimensions(x, y, 20, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> this.select(this.selected + 1)).dimensions(x + 22, y, 20, 20).build());
		this.interpolationButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> this.cycleInterpolation()).dimensions(x + 46, y, 110, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.apply"), b -> this.applyFields()).dimensions(x + 160, y, 78, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.duplicate"), b -> this.duplicateSelected())
				.dimensions(x + 242, y, 80, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.delete"), b -> this.deleteSelected())
				.dimensions(x + 326, y, 78, 20).build());

		y += ROW;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.shift"), b -> {
			Integer delta = parseInt(this.shiftField.getText());
			if (delta != null && this.hasSelection()) {
				this.pendingSelectTick = Math.max(0, this.path().keys().get(this.selected).tick() + delta);
				this.send(Payloads.Action.SHIFT_FROM, this.selected, delta);
			}
		}).dimensions(x, y, 128, 20).build());
		this.shiftField = new TextFieldWidget(this.textRenderer, x + 132, y + 1, 50, 18, Text.translatable("gui.camerapathaddon.shift"));
		this.shiftField.setText("20");
		this.addDrawableChild(this.shiftField);
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.scale"), b -> {
			Double factor = parseDouble(this.scaleField.getText());
			if (factor != null && factor > 0) {
				this.pendingSelectTick = null;
				this.send(Payloads.Action.SCALE_TIME, -1, factor);
			}
		}).dimensions(x + 222, y, 128, 20).build());
		this.scaleField = new TextFieldWidget(this.textRenderer, x + 354, y + 1, 50, 18, Text.translatable("gui.camerapathaddon.scale"));
		this.scaleField.setText("1.0");
		this.addDrawableChild(this.scaleField);

		y += ROW;
		this.importButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			if (!this.importArmed) {
				this.importArmed = true;
				this.updateLabels();
				return;
			}
			this.importArmed = false;
			Double speed = parseDouble(this.speedField.getText());
			if (speed != null && speed > 0) {
				this.pendingSelectTick = 0;
				this.dirty = false;
				this.send(Payloads.Action.IMPORT_ROUTE, -1, speed);
			}
			this.updateLabels();
		}).dimensions(x, y, 150, 20).build());
		this.speedField = new TextFieldWidget(this.textRenderer, x + 154, y + 1, 40, 18, Text.translatable("gui.camerapathaddon.import_speed"));
		this.speedField.setText("10");
		this.addDrawableChild(this.speedField);
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.slots"), b -> this.send(Payloads.Action.OPEN_SLOTS, -1, 0))
				.dimensions(x + 270, y, 66, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.settings"), b -> this.client.setScreen(new CameraPathSettingsScreen(this, this.pos)))
				.dimensions(x + 338, y, 66, 20).build());

		this.statusY = y + ROW + 3;
		y = this.statusY + 13;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.inspect"), b -> {
			if (this.hasSelection()) {
				this.send(Payloads.Action.INSPECT, this.selectedTickOr(0), 0);
			}
		}).dimensions(x, y, 132, 20).build());
		this.startFromButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
			this.startFromSelected = !this.startFromSelected;
			this.updateLabels();
		}).dimensions(x + 135, y, 132, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.pause"), b -> this.send(Payloads.Action.TOGGLE_PAUSE, -1, 0))
				.dimensions(x + 270, y, 134, 20).build());
		y += ROW + 1;
		int w = 78;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.play_camera"), b -> {
			this.send(Payloads.Action.PLAY_CAMERA, this.startTick(), 0);
			this.close();
		}).dimensions(x, y, w, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.play_vehicle"), b -> this.send(Payloads.Action.PLAY_VEHICLE, this.startTick(), 0))
				.dimensions(x + 81, y, w, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.stop"), b -> this.send(Payloads.Action.STOP, -1, 0))
				.dimensions(x + 162, y, w, 20).build());
		this.previewButton = this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> this.send(Payloads.Action.TOGGLE_PREVIEW, -1, 0))
				.dimensions(x + 243, y, w, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close()).dimensions(x + 324, y, 80, 20).build());
	}

	/** Where the play buttons start: the selected key, or -1 (the first key). */
	private int startTick() {
		return this.startFromSelected ? this.selectedTickOr(-1) : -1;
	}

	private void duplicateSelected() {
		if (this.hasSelection()) {
			Keyframe key = this.path().keys().get(this.selected);
			this.pendingSelectTick = key.tick() + this.path().settings().keySpacing();
			this.send(Payloads.Action.DUPLICATE_KEY, this.selected, 0);
		}
	}

	private void deleteSelected() {
		if (this.hasSelection()) {
			this.dirty = false;
			this.send(Payloads.Action.REMOVE_KEY, this.selected, 0);
		}
	}

	// ------------------------------------------------------------------ state

	private boolean hasSelection() {
		return this.selected >= 0 && this.selected < this.path().keys().size();
	}

	/** Picks up a changed block (after any edit, from here or elsewhere). */
	private void refresh() {
		CameraPath path = this.path();
		if (path != this.shownPath) {
			this.shownPath = path;
			List<Keyframe> keys = path.keys();
			if (this.pendingSelectTick != null) {
				int best = -1;
				for (int i = 0; i < keys.size(); i++) {
					if (best < 0 || Math.abs(keys.get(i).tick() - this.pendingSelectTick) < Math.abs(keys.get(best).tick() - this.pendingSelectTick)) {
						best = i;
					}
				}
				this.selected = best;
				this.pendingSelectTick = null;
				this.dirty = false;
			}
			if (keys.isEmpty()) {
				this.selected = -1;
			} else if (this.selected < 0 || this.selected >= keys.size()) {
				this.selected = Math.max(0, Math.min(keys.size() - 1, this.selected));
			}
			if (!this.dirty) {
				this.populate();
			}
		}
		this.updateLabels();
		this.syncInspectedKey();
	}

	/** During the view check, keeps the paused view on the selected key. */
	private void syncInspectedKey() {
		if (!this.inspecting() || !this.hasSelection()) {
			if (!this.inspecting()) {
				this.inspectedTick = null;
			}
			return;
		}
		int tick = this.path().keys().get(this.selected).tick();
		if (this.inspectedTick == null || this.inspectedTick != tick) {
			this.inspectedTick = tick;
			this.send(Payloads.Action.INSPECT_TIME, tick, 0);
		}
	}

	private void select(int index) {
		List<Keyframe> keys = this.path().keys();
		if (keys.isEmpty()) {
			return;
		}
		this.selected = Math.max(0, Math.min(keys.size() - 1, index));
		this.dirty = false;
		this.populate();
		this.updateLabels();
		this.syncInspectedKey();
	}

	private void populate() {
		this.populating = true;
		boolean has = this.hasSelection();
		Keyframe key = has ? this.path().keys().get(this.selected) : null;
		String[] values = has ? new String[]{
				Integer.toString(key.tick()), fmt(key.x()), fmt(key.y()), fmt(key.z()),
				fmt(key.rotX()), fmt(key.rotY()), fmt(key.rotZ()), fmt(key.fov())} : new String[]{"", "", "", "", "", "", "", ""};
		for (int i = 0; i < this.keyFields.length; i++) {
			this.keyFields[i].setText(values[i]);
			this.keyFields[i].setEditable(has);
		}
		this.populating = false;
		PathPreviewRenderer.setSelection(this.pos, has ? this.selected : -1);
	}

	private void updateLabels() {
		if (this.interpolationButton == null) {
			return;
		}
		Interpolation mode = this.hasSelection() ? this.path().keys().get(this.selected).interpolation() : Interpolation.BEZIER;
		this.interpolationButton.setMessage(Text.translatable("gui.camerapathaddon.interpolation",
				Text.translatable("gui.camerapathaddon.interpolation." + mode.asString())));
		this.interpolationButton.active = this.hasSelection();
		if (this.stepButton != null) {
			this.stepButton.setMessage(Text.translatable("gui.camerapathaddon.step",
					Text.translatable("gui.camerapathaddon.step." + STEP_NAMES[this.stepLevel])));
		}
		CameraControlBlockEntity block = this.block();
		if (this.previewButton != null) {
			boolean preview = block != null && block.isPreview();
			this.previewButton.setMessage(Text.translatable(preview ? "gui.camerapathaddon.preview_on" : "gui.camerapathaddon.preview_off"));
		}
		if (this.importButton != null) {
			this.importButton.setMessage(Text.translatable(this.importArmed ? "gui.camerapathaddon.confirm" : "gui.camerapathaddon.import_route"));
			this.importButton.active = block != null && !block.getBook().isEmpty();
		}
		if (this.startFromButton != null) {
			this.startFromButton.setMessage(Text.translatable(this.startFromSelected
					? "gui.camerapathaddon.start_from_selected" : "gui.camerapathaddon.start_from_first"));
		}
	}

	// ------------------------------------------------------------------ edits

	private void send(Payloads.Action action, int index, double value) {
		ClientPlayNetworking.send(new Payloads.BlockAction(this.pos, action, index, value));
	}

	private void applyFields() {
		if (!this.hasSelection()) {
			return;
		}
		Keyframe updated = this.fieldsKey(this.path().keys().get(this.selected));
		this.pendingSelectTick = updated.tick();
		this.dirty = false;
		ClientPlayNetworking.send(new Payloads.EditKey(this.pos, this.selected, updated));
	}

	/** The +/- buttons and the mouse wheel: steps one value of the selected key (on top of anything typed and not yet applied). */
	private void nudge(int field, int direction) {
		if (!this.hasSelection()) {
			return;
		}
		Keyframe old = this.path().keys().get(this.selected);
		Keyframe base = this.dirty ? this.fieldsKey(old) : old;
		double step = STEPS[field][this.stepLevel] * direction;
		Keyframe updated = switch (field) {
			case 0 -> base.withTick(Math.max(0, base.tick() + (int) step));
			case 1 -> new Keyframe(base.tick(), round(base.x() + step), base.y(), base.z(), base.rotX(), base.rotY(), base.rotZ(), base.fov(), base.interpolation());
			case 2 -> new Keyframe(base.tick(), base.x(), round(base.y() + step), base.z(), base.rotX(), base.rotY(), base.rotZ(), base.fov(), base.interpolation());
			case 3 -> new Keyframe(base.tick(), base.x(), base.y(), round(base.z() + step), base.rotX(), base.rotY(), base.rotZ(), base.fov(), base.interpolation());
			case 4 -> new Keyframe(base.tick(), base.x(), base.y(), base.z(), (float) round(base.rotX() + step), base.rotY(), base.rotZ(), base.fov(), base.interpolation());
			case 5 -> new Keyframe(base.tick(), base.x(), base.y(), base.z(), base.rotX(), (float) round(base.rotY() + step), base.rotZ(), base.fov(), base.interpolation());
			case 6 -> new Keyframe(base.tick(), base.x(), base.y(), base.z(), base.rotX(), base.rotY(), (float) round(base.rotZ() + step), base.fov(), base.interpolation());
			default -> new Keyframe(base.tick(), base.x(), base.y(), base.z(), base.rotX(), base.rotY(), base.rotZ(),
					(float) Math.max(0.0, round(base.fov() + step)), base.interpolation());
		};
		this.pendingSelectTick = updated.tick();
		this.dirty = false;
		ClientPlayNetworking.send(new Payloads.EditKey(this.pos, this.selected, updated));
	}

	/** Rounds away float noise from repeated steps (to 1/1000). */
	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	/** The selected key with the values typed in the fields (unparseable ones keep old's). */
	private Keyframe fieldsKey(Keyframe old) {
		Integer tick = parseInt(this.keyFields[0].getText());
		Double x = parseDouble(this.keyFields[1].getText());
		Double y = parseDouble(this.keyFields[2].getText());
		Double z = parseDouble(this.keyFields[3].getText());
		Double rx = parseDouble(this.keyFields[4].getText());
		Double ry = parseDouble(this.keyFields[5].getText());
		Double rz = parseDouble(this.keyFields[6].getText());
		Double fov = parseDouble(this.keyFields[7].getText());
		Keyframe updated = new Keyframe(
				tick != null ? Math.max(0, tick) : old.tick(),
				x != null ? x : old.x(), y != null ? y : old.y(), z != null ? z : old.z(),
				rx != null ? rx.floatValue() : old.rotX(), ry != null ? ry.floatValue() : old.rotY(), rz != null ? rz.floatValue() : old.rotZ(),
				fov != null ? fov.floatValue() : old.fov(), old.interpolation());
		return updated;
	}

	private void cycleInterpolation() {
		if (!this.hasSelection()) {
			return;
		}
		Keyframe old = this.path().keys().get(this.selected);
		this.pendingSelectTick = old.tick();
		ClientPlayNetworking.send(new Payloads.EditKey(this.pos, this.selected,
				new Keyframe(old.tick(), old.x(), old.y(), old.z(), old.rotX(), old.rotY(), old.rotZ(), old.fov(), old.interpolation().next())));
	}

	// ------------------------------------------------------------------ timeline

	private int timelineMax() {
		return Math.max(20, (int) Math.ceil(this.path().lastTick() * 1.05));
	}

	private int tickToX(int tick) {
		return this.barX + (int) Math.round((double) tick / this.timelineMax() * this.barW);
	}

	private int xToTick(double x) {
		return (int) Math.round(Math.max(0.0, Math.min(1.0, (x - this.barX) / this.barW)) * this.timelineMax());
	}

	private boolean inBar(double x, double y) {
		return x >= this.barX - 4 && x <= this.barX + this.barW + 4 && y >= this.barY - 2 && y <= this.barY + BAR_H + 2;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (this.panelHidden) {
			this.panelHidden = false;
			return true;
		}
		if (click.button() == 0 && this.inBar(click.x(), click.y())) {
			List<Keyframe> keys = this.path().keys();
			int best = -1;
			double bestDistance = 5.0;
			for (int i = 0; i < keys.size(); i++) {
				double distance = Math.abs(this.tickToX(keys.get(i).tick()) - click.x());
				if (distance <= bestDistance) {
					bestDistance = distance;
					best = i;
				}
			}
			if (best >= 0) {
				this.select(best);
				this.dragKey = best;
				this.dragTick = keys.get(best).tick();
			}
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (this.dragKey >= 0) {
			this.dragTick = this.xToTick(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (this.dragKey >= 0) {
			List<Keyframe> keys = this.path().keys();
			if (this.dragKey < keys.size() && keys.get(this.dragKey).tick() != this.dragTick) {
				this.pendingSelectTick = this.dragTick;
				this.dirty = false;
				ClientPlayNetworking.send(new Payloads.EditKey(this.pos, this.dragKey, keys.get(this.dragKey).withTick(this.dragTick)));
			}
			this.dragKey = -1;
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!this.panelHidden && verticalAmount != 0) {
			for (int i = 0; i < this.keyFields.length; i++) {
				if (this.keyFields[i].isMouseOver(mouseX, mouseY)) {
					this.nudge(i, verticalAmount > 0 ? 1 : -1);
					return true;
				}
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (this.panelHidden) {
			this.panelHidden = false;
			return true;
		}
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && this.isKeyFieldFocused()) {
			this.applyFields();
			return true;
		}
		return super.keyPressed(input);
	}

	private boolean isKeyFieldFocused() {
		for (TextFieldWidget field : this.keyFields) {
			if (field.isFocused()) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void tick() {
		super.tick();
		if (this.awayPlayback() == null) {
			CameraControlBlockEntity block = this.block();
			if (block == null || this.client.player == null
					|| this.client.player.squaredDistanceTo(this.pos.toCenterPos()) > 8.0 * 8.0) {
				this.close();
				return;
			}
		}
		if (this.inspecting() != this.panelLayout) {
			// The view check started or ended: switch between the full editor and the side panel.
			this.clearAndInit();
			return;
		}
		this.refresh();
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.panelLayout) {
			// No blur or dimming: the world behind is the camera's actual view.
			if (!this.panelHidden) {
				context.fill(PANEL_X, 2, PANEL_X + PANEL_W, PANEL_BOTTOM, COLOR_PANEL);
			}
			return;
		}
		super.renderBackground(context, mouseX, mouseY, delta);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (this.panelHidden) {
			this.renderBackground(context, mouseX, mouseY, delta);
			return;
		}
		super.render(context, mouseX, mouseY, delta);
		CameraControlBlockEntity block = this.block();
		CameraPath path = this.path();
		List<Keyframe> keys = path.keys();
		int cx = this.width / 2;
		int left = cx - 202;

		Text header = Text.translatable("gui.camerapathaddon.header", this.label(),
				this.hasSelection() ? this.selected + 1 : 0, keys.size(), path.duration(), fmt(path.duration() / 20.0));
		if (this.panelLayout) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.inspect_title"), PANEL_X + 4, 5, COLOR_MARK_SELECTED);
			context.drawTextWithShadow(this.textRenderer, header, PANEL_X + 4, 17, COLOR_TEXT);
		} else {
			context.drawCenteredTextWithShadow(this.textRenderer, header, cx, this.barY - 14, COLOR_TEXT);
		}

		// Timeline
		context.fill(this.barX, this.barY, this.barX + this.barW, this.barY + BAR_H, COLOR_BAR);
		context.drawStrokedRectangle(this.barX - 1, this.barY - 1, this.barW + 2, BAR_H + 2, COLOR_BAR_BORDER);
		int max = this.timelineMax();
		int step = max > 20 * 600 ? 1200 : (max > 20 * 120 ? 200 : (max > 20 * 20 ? 100 : 20));
		for (int t = 0; t <= max; t += step) {
			context.drawVerticalLine(this.tickToX(t), this.barY + BAR_H - 4, this.barY + BAR_H, COLOR_DIM);
		}
		for (int i = 0; i < keys.size(); i++) {
			int tick = i == this.dragKey ? this.dragTick : keys.get(i).tick();
			int x = this.tickToX(tick);
			context.fill(x - 1, this.barY + 1, x + 2, this.barY + BAR_H - 1, i == this.selected ? COLOR_MARK_SELECTED : COLOR_MARK);
		}
		if (this.dragKey >= 0) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.dragTick + "t"), this.tickToX(this.dragTick), this.barY + BAR_H + 2, COLOR_MARK_SELECTED);
		}

		for (int i = 0; i < 8; i++) {
			TextFieldWidget field = this.keyFields[i];
			context.drawTextWithShadow(this.textRenderer, Text.translatable((this.panelLayout ? "gui.camerapathaddon.short." : "gui.camerapathaddon.field.") + FIELD_KEYS[i]),
					field.getX() - this.labelW, field.getY() + (field.getHeight() - 8) / 2, COLOR_TEXT);
		}
		if (this.panelLayout || block == null) {
			if (this.panelLayout) {
				context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.inspect_hint"), PANEL_X + 4, PANEL_BOTTOM - 10, COLOR_DIM);
			}
			return;
		}
		context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.speed_unit"),
				this.speedField.getX() + 44, this.speedField.getY() + 5, COLOR_DIM);

		// Status: route book and target vehicle
		context.drawTextWithShadow(this.textRenderer, this.bookStatus(block), left, this.statusY, COLOR_DIM);
		Text target = Text.translatable("gui.camerapathaddon.target", this.targetName(block));
		context.drawTextWithShadow(this.textRenderer, target, cx + 4, this.statusY, COLOR_DIM);
	}

	private Text bookStatus(CameraControlBlockEntity block) {
		ItemStack book = block.getBook();
		if (book.isEmpty()) {
			return Text.translatable("gui.camerapathaddon.book_none");
		}
		return Text.translatable("gui.camerapathaddon.book", DroneRouteBookItem.tudursvehiclemod$getWaypoints(book).size());
	}

	private Text targetName(CameraControlBlockEntity block) {
		UUID id = block.getTargetVehicleId();
		if (id == null) {
			return Text.translatable("gui.camerapathaddon.target_none");
		}
		if (this.client.world != null) {
			for (Entity entity : this.client.world.getEntities()) {
				if (entity.getUuid().equals(id)) {
					return entity.getDisplayName();
				}
			}
		}
		return Text.translatable("gui.camerapathaddon.target_far");
	}

	@Override
	public void close() {
		// Closing the view check puts the player back (unless they chose to play on from here).
		if (!this.keepPlaybackOnClose && this.inspecting()) {
			this.send(Payloads.Action.END_CAMERA, -1, 0);
		}
		super.close();
	}

	@Override
	public void removed() {
		PathPreviewRenderer.setSelection(null, -1);
		super.removed();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	static String fmt(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0e9) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	static Integer parseInt(String text) {
		try {
			return (int) Math.round(Double.parseDouble(text.trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Double parseDouble(String text) {
		try {
			double value = Double.parseDouble(text.trim());
			return Double.isFinite(value) ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
