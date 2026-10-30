package com.example.camerapathaddon.client.screen;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Keyframe;
import com.example.camerapathaddon.path.VehicleAction;
import com.example.tudursvehiclemod.asset.WeaponDefinition;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The vehicle actions of one keyframe (see {@link VehicleAction}), done on the vehicle during vehicle playback
 * without anyone on board. The "Vehicle" tab holds what the pilot's controls do (parts, lights, VTOL mode, weapon
 * selection, flares, throttle, steering); one tab per seat holds that seat's weapons and aim.
 */
public class VehicleActionScreen extends Screen {

	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM = 0xFFA0A0A0;
	private static final int ROW = 21;
	private static final int TABS_Y = 38;
	private static final int TOP = 62;
	private static final int ROWS = 8;
	private static final int MAX_SEAT_TABS = 8;
	private static final List<VehicleAction.Type> VEHICLE_TYPES = List.of(
			VehicleAction.Type.GEAR, VehicleAction.Type.HATCH, VehicleAction.Type.WING_FOLD, VehicleAction.Type.SEARCH_LIGHT,
			VehicleAction.Type.NAV_LIGHTS, VehicleAction.Type.VTOL_MODE, VehicleAction.Type.SELECT_WEAPON, VehicleAction.Type.FLARES,
			VehicleAction.Type.THROTTLE, VehicleAction.Type.STEERING);
	private static final List<VehicleAction.Type> SEAT_TYPES = List.of(VehicleAction.Type.AIM, VehicleAction.Type.FIRE_WEAPON);

	private final Screen parent;
	private final BlockPos pos;
	private final Supplier<CameraPath> pathSource;
	private final int keyTick;
	/** -1 = the vehicle tab, else the seat whose tab is shown. */
	private int tab = -1;
	private List<VehicleAction> shown;
	/** Full-list indices of the rows on the current tab. */
	private final List<Integer> rowIndices = new ArrayList<>();
	private final List<TextFieldWidget> fields = new ArrayList<>();

	public VehicleActionScreen(Screen parent, BlockPos pos, Supplier<CameraPath> pathSource, int keyTick) {
		super(Text.translatable("gui.camerapathaddon.actions.title"));
		this.parent = parent;
		this.pos = pos;
		this.pathSource = pathSource;
		this.keyTick = keyTick;
	}

	private int index() {
		List<Keyframe> keys = this.pathSource.get().keys();
		for (int i = 0; i < keys.size(); i++) {
			if (keys.get(i).tick() == this.keyTick) {
				return i;
			}
		}
		return -1;
	}

	private List<VehicleAction> actions() {
		int index = this.index();
		return index < 0 ? List.of() : this.pathSource.get().keys().get(index).actions();
	}

	private boolean onTab(VehicleAction action) {
		return this.tab < 0 ? !action.type().isSeatAction() : action.type().isSeatAction() && action.seat() == this.tab;
	}

	private int left() {
		return this.width / 2 - 200;
	}

	/** Seats of the stick's vehicle, if this client has it loaded (else every tab up to MAX_SEAT_TABS). */
	private int seatCount() {
		AbstractVehicleEntity vehicle = this.target();
		return vehicle == null ? MAX_SEAT_TABS : Math.max(1, Math.min(MAX_SEAT_TABS, vehicle.getDefinition().seats().size()));
	}

	@Override
	protected void init() {
		this.shown = this.actions();
		this.rowIndices.clear();
		this.fields.clear();
		int x = this.left();

		// Tabs
		int seats = this.seatCount();
		int tabW = Math.min(60, (400 - 70) / Math.max(1, seats));
		ButtonWidget vehicleTab = this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.actions.tab.vehicle"), b -> this.switchTab(-1))
				.dimensions(x, TABS_Y, 68, 20).build());
		vehicleTab.active = this.tab != -1;
		for (int seat = 0; seat < seats; seat++) {
			int s = seat;
			ButtonWidget seatTab = this.addDrawableChild(ButtonWidget.builder(Text.translatable(seat == 0 ? "gui.camerapathaddon.actions.tab.pilot" : "gui.camerapathaddon.actions.tab.seat", seat + 1),
					b -> this.switchTab(s)).dimensions(x + 70 + seat * tabW, TABS_Y, tabW - 1, 20).build());
			seatTab.active = this.tab != seat;
		}

		for (int i = 0; i < this.shown.size(); i++) {
			if (this.onTab(this.shown.get(i)) && this.rowIndices.size() < ROWS) {
				this.rowIndices.add(i);
			}
		}
		for (int r = 0; r < this.rowIndices.size(); r++) {
			int full = this.rowIndices.get(r);
			VehicleAction action = this.shown.get(full);
			int y = TOP + r * ROW;
			List<VehicleAction.Type> types = this.tab < 0 ? VEHICLE_TYPES : SEAT_TYPES;
			this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.action." + action.type().asString()),
					b -> this.change(full, a -> {
						VehicleAction.Type next = types.get((types.indexOf(a.type()) + 1) % types.size());
						VehicleAction created = a.withType(next);
						return next == VehicleAction.Type.FIRE_WEAPON ? created.withValue(this.nextWeapon(-1, 1)) : created;
					})).dimensions(x, y, 110, 20).build());
			switch (action.type()) {
				case SELECT_WEAPON, FIRE_WEAPON -> {
					this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> this.change(full, a -> a.withValue(this.nextWeapon(a.value(), -1))))
							.dimensions(x + 114, y, 20, 20).build());
					this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> this.change(full, a -> a.withValue(this.nextWeapon(a.value(), 1))))
							.dimensions(x + 274, y, 20, 20).build());
					if (action.type() == VehicleAction.Type.FIRE_WEAPON) {
						this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.action.mode." + action.mode().asString()),
								b -> this.change(full, a -> a.withMode(a.mode().next()))).dimensions(x + 298, y, 78, 20).build());
					}
				}
				case THROTTLE, STEERING -> this.numberField(full, x + 160, y, action.x(), true);
				case AIM -> {
					this.numberField(full, x + 160, y, action.x(), true);
					this.numberField(full, x + 270, y, action.y(), false);
				}
				case FLARES -> {
				}
				default -> this.addDrawableChild(ButtonWidget.builder(
						Text.translatable("gui.camerapathaddon.action." + action.type().asString() + (action.value() != 0 ? ".on" : ".off")),
						b -> this.change(full, a -> a.withValue(a.value() != 0 ? 0 : 1))).dimensions(x + 114, y, 180, 20).build());
			}
			this.addDrawableChild(ButtonWidget.builder(Text.literal("×"), b -> {
				List<VehicleAction> list = new ArrayList<>(this.actions());
				if (full < list.size()) {
					list.remove(full);
					this.save(list);
				}
			}).dimensions(x + 380, y, 20, 20).build());
		}

		int bottom = TOP + ROWS * ROW + 4;
		ButtonWidget add = this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.camerapathaddon.actions.add"), b -> this.addAction())
				.dimensions(x, bottom, 196, 20).build());
		add.active = this.shown.size() < VehicleAction.MAX_PER_KEYFRAME && this.rowIndices.size() < ROWS;
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close()).dimensions(x + 204, bottom, 196, 20).build());
	}

	private void switchTab(int newTab) {
		this.tab = newTab;
		this.clearAndInit();
	}

	/** Adds the first type of this tab the keyframe doesn't have yet (states and continuous values are one per type and seat). */
	private void addAction() {
		List<VehicleAction> list = new ArrayList<>(this.actions());
		List<VehicleAction.Type> types = this.tab < 0 ? VEHICLE_TYPES : SEAT_TYPES;
		int seat = Math.max(0, this.tab);
		VehicleAction.Type type = this.tab < 0 ? VehicleAction.Type.FLARES : VehicleAction.Type.FIRE_WEAPON;
		for (VehicleAction.Type candidate : types) {
			boolean single = candidate.isState() || candidate.isContinuous();
			if (single && list.stream().noneMatch(a -> a.type() == candidate && (!candidate.isSeatAction() || a.seat() == seat))) {
				type = candidate;
				break;
			}
		}
		VehicleAction action = VehicleAction.create(type, seat);
		if (type == VehicleAction.Type.FIRE_WEAPON) {
			action = action.withValue(this.nextWeapon(-1, 1));
		}
		list.add(action);
		this.save(list);
	}

	/** The next weapon index from current in direction - on a seat tab, only the weapons of that seat (when the vehicle is known). */
	private int nextWeapon(int current, int direction) {
		AbstractVehicleEntity vehicle = this.target();
		if (vehicle == null) {
			return Math.max(0, current + direction);
		}
		List<WeaponDefinition> weapons = vehicle.getDefinition().weapons();
		int count = weapons.size();
		for (int step = 1; step <= count; step++) {
			int candidate = Math.floorMod(current + direction * step, Math.max(1, count));
			if (this.tab < 0 || weapons.get(candidate).seatIndex() == this.tab) {
				return candidate;
			}
		}
		return Math.max(0, current);
	}

	/** A number field for x (first) or y of the action at full; saved as typed (once it parses). */
	private void numberField(int full, int fx, int y, float value, boolean first) {
		TextFieldWidget field = new TextFieldWidget(this.textRenderer, fx, y + 1, 56, 18, Text.empty());
		field.setMaxLength(8);
		field.setText(fmt(value));
		field.setChangedListener(text -> {
			try {
				float parsed = Float.parseFloat(text.trim());
				if (Float.isFinite(parsed)) {
					this.change(full, a -> first ? a.withXY(parsed, a.y()) : a.withXY(a.x(), parsed));
				}
			} catch (NumberFormatException ignored) {
				// Still typing.
			}
		});
		this.fields.add(field);
		this.addDrawableChild(field);
	}

	private void change(int full, UnaryOperator<VehicleAction> edit) {
		List<VehicleAction> list = new ArrayList<>(this.actions());
		if (full < list.size()) {
			list.set(full, edit.apply(list.get(full)));
			this.save(list);
		}
	}

	private void save(List<VehicleAction> list) {
		int index = this.index();
		if (index < 0) {
			return;
		}
		Keyframe key = this.pathSource.get().keys().get(index);
		ClientPlayNetworking.send(new Payloads.EditKey(this.pos, index, key.withActions(list)));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.index() < 0) {
			this.close();
			return;
		}
		// Rebuilt when the keyframe changed - but not under the cursor of a field being typed into.
		if (!this.actions().equals(this.shown) && this.fields.stream().noneMatch(TextFieldWidget::isFocused)) {
			this.clearAndInit();
		}
	}

	private AbstractVehicleEntity target() {
		if (this.client == null || this.client.world == null || !(this.client.world.getBlockEntity(this.pos) instanceof CameraControlBlockEntity block)) {
			return null;
		}
		UUID id = block.getTargetVehicleId();
		if (id == null) {
			return null;
		}
		for (Entity entity : this.client.world.getEntities()) {
			if (entity.getUuid().equals(id) && entity instanceof AbstractVehicleEntity vehicle) {
				return vehicle;
			}
		}
		return null;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int x = this.left();
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.header", this.index() + 1, this.keyTick),
				this.width / 2, 10, COLOR_TEXT);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(this.tab < 0 ? "gui.camerapathaddon.actions.hint" : "gui.camerapathaddon.actions.seat_hint"),
				this.width / 2, 24, COLOR_DIM);
		if (this.rowIndices.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.none"), x, TOP + 6, COLOR_DIM);
		}
		AbstractVehicleEntity vehicle = this.target();
		for (int r = 0; r < this.rowIndices.size(); r++) {
			VehicleAction action = this.shown.get(this.rowIndices.get(r));
			int y = TOP + r * ROW + 6;
			switch (action.type()) {
				case SELECT_WEAPON, FIRE_WEAPON -> {
					String name = "#" + (action.value() + 1);
					if (vehicle != null && action.value() < vehicle.getDefinition().weapons().size()) {
						name += " " + vehicle.getDefinition().weapons().get(action.value()).weaponName();
					} else if (vehicle != null) {
						name += " " + Text.translatable("gui.camerapathaddon.actions.no_weapon").getString();
					}
					context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(name, 134)), x + 204, y, COLOR_TEXT);
				}
				case THROTTLE, STEERING -> {
					context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.value"), x + 116, y, COLOR_DIM);
					context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.range." + action.type().asString()), x + 222, y, COLOR_DIM);
				}
				case AIM -> {
					context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.short.rot_y"), x + 116, y, COLOR_DIM);
					context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.short.rot_x"), x + 226, y, COLOR_DIM);
					if (this.tab >= 0 && !this.pathSource.get().settings().hasDummyPilot(this.tab)) {
						// Only a seat with someone in it turns its turrets (tudursvehiclemod) - here, a dummy pilot from the settings.
						context.drawTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.aim_needs_pilot"), x + 330, y, 0xFFFFCC00);
					}
				}
				default -> {
				}
			}
		}
		int bottom = TOP + ROWS * ROW + 4;
		context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("gui.camerapathaddon.actions.state_hint"), this.width / 2, bottom + 24, COLOR_DIM);
	}

	private static String fmt(float value) {
		if (value == Math.rint(value)) {
			return Integer.toString((int) value);
		}
		return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
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
