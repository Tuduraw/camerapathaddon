package com.example.camerapathaddon.block;

import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Interpolation;
import com.example.camerapathaddon.path.Keyframe;
import com.example.camerapathaddon.registry.ModBlocks;
import com.example.camerapathaddon.screen.CameraControlScreenHandler;
import com.example.camerapathaddon.server.PlaybackManager;
import com.example.tudursvehiclemod.block.DroneRouteBookWaypoint;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import com.example.tudursvehiclemod.item.DroneRouteBookItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * One camera path setup: the path (keyframes and settings), the route book its keys were built from, and the
 * drone control stick naming the vehicle that vehicle playback drives. Each block is one independent setup, so
 * several paths are managed by placing several blocks.
 *
 * <p>Slot 0: tudursvehiclemod route book (source of the key positions - see {@link #importFromBook}).
 * Slot 1: tudursvehiclemod drone control stick (the vehicle playback target).
 *
 * <p>The whole state is sent to clients in the block entity update packet, so the editor screen and the in-world
 * preview always show the saved path.
 */
public class CameraControlBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory<BlockPos>, Inventory {

	public static final int SLOT_BOOK = 0;
	public static final int SLOT_STICK = 1;
	public static final int MAX_NAME_LENGTH = 32;

	/** Client-side instances currently loaded, for the in-world preview (weak: unloaded ones simply drop out). */
	private static final Set<CameraControlBlockEntity> CLIENT_LOADED = Collections.newSetFromMap(new WeakHashMap<>());

	private final DefaultedList<ItemStack> items = DefaultedList.ofSize(2, ItemStack.EMPTY);
	private CameraPath path = CameraPath.EMPTY;
	private String name = "";
	private boolean preview;
	/** Last redstone state seen, for detecting the rising edge (see redstoneTrigger). */
	private boolean powered;

	public CameraControlBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.CAMERA_CONTROL_ENTITY, pos, state);
	}

	public static Set<CameraControlBlockEntity> clientLoaded() {
		return CLIENT_LOADED;
	}

	// ------------------------------------------------------------------ state

	public CameraPath getPath() {
		return this.path;
	}

	public void setPath(CameraPath newPath) {
		this.path = newPath;
		this.sync();
	}

	public String getName() {
		return this.name;
	}

	public void setName(String newName) {
		String trimmed = newName == null ? "" : newName.strip();
		this.name = trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
		this.sync();
	}

	/** The name shown in screens and messages: the configured one, or "x, y, z" when none. */
	public Text getLabel() {
		BlockPos pos = this.getPos();
		return this.name.isEmpty() ? Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()) : Text.literal(this.name);
	}

	public boolean isPreview() {
		return this.preview;
	}

	public void setPreview(boolean value) {
		this.preview = value;
		this.sync();
	}

	public ItemStack getBook() {
		ItemStack stack = this.items.get(SLOT_BOOK);
		return stack.getItem() instanceof DroneRouteBookItem ? stack : ItemStack.EMPTY;
	}

	/** The vehicle registered to the stick in slot 1, or null. */
	public UUID getTargetVehicleId() {
		ItemStack stack = this.items.get(SLOT_STICK);
		return stack.getItem() instanceof DroneControlStickItem ? DroneControlStickItem.getRegisteredVehicleId(stack) : null;
	}

	/**
	 * Replaces the keys with ones built from the route book's patrol route: each waypoint (block centre, plus the
	 * book's coordinate offset) becomes a key facing along the route, timed for a constant speed in blocks per
	 * second, with the waypoint's roll. Rotations and timing can then be adjusted in the editor.
	 *
	 * @return number of keys built (0 = no book or an empty route)
	 */
	public int importFromBook(double blocksPerSecond) {
		ItemStack book = this.getBook();
		if (book.isEmpty()) {
			return 0;
		}
		List<DroneRouteBookWaypoint> waypoints = DroneRouteBookItem.tudursvehiclemod$getWaypoints(book);
		if (waypoints.isEmpty()) {
			return 0;
		}
		Vec3i offset = DroneRouteBookItem.tudursvehiclemod$getOffset(book);
		List<Keyframe> keys = keysFromRoute(waypoints, offset, blocksPerSecond);
		this.setPath(this.path.withKeys(keys));
		return this.path.keys().size();
	}

	static List<Keyframe> keysFromRoute(List<DroneRouteBookWaypoint> waypoints, Vec3i offset, double blocksPerSecond) {
		double blocksPerTick = Math.max(0.001, blocksPerSecond) / 20.0;
		List<Keyframe> keys = new ArrayList<>(waypoints.size());
		double tick = 0;
		Float previousYaw = null;
		for (int i = 0; i < waypoints.size() && i < CameraPath.MAX_KEYS; i++) {
			DroneRouteBookWaypoint wp = waypoints.get(i);
			DroneRouteBookWaypoint from = i + 1 < waypoints.size() ? wp : (i > 0 ? waypoints.get(i - 1) : wp);
			DroneRouteBookWaypoint to = i + 1 < waypoints.size() ? waypoints.get(i + 1) : wp;
			double dx = to.x() - from.x();
			double dy = to.y() - from.y();
			double dz = to.z() - from.z();
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			float yaw = horizontal > 1.0e-6 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : (previousYaw != null ? previousYaw : 0f);
			float pitch = (horizontal > 1.0e-6 || Math.abs(dy) > 1.0e-6) ? (float) -Math.toDegrees(Math.atan2(dy, horizontal)) : 0f;
			// Continuous with the previous key, so Euler interpolation turns the short way.
			if (previousYaw != null) {
				yaw = previousYaw + MathHelper.wrapDegrees(yaw - previousYaw);
			}
			previousYaw = yaw;
			if (i > 0) {
				DroneRouteBookWaypoint prev = waypoints.get(i - 1);
				double distance = Math.sqrt(Math.pow(wp.x() - prev.x(), 2) + Math.pow(wp.y() - prev.y(), 2) + Math.pow(wp.z() - prev.z(), 2));
				tick += Math.max(1.0, distance / blocksPerTick);
			}
			keys.add(new Keyframe((int) Math.round(tick),
					wp.x() + 0.5 + offset.getX(), wp.y() + offset.getY(), wp.z() + 0.5 + offset.getZ(),
					pitch, yaw, wp.rollAngle(), 0f, Interpolation.BEZIER));
		}
		return keys;
	}

	/** Called by the block on neighbour updates: a rising edge starts vehicle playback when the path asks for it. */
	public void onRedstone(ServerWorld world, boolean nowPowered) {
		boolean rising = nowPowered && !this.powered;
		if (nowPowered != this.powered) {
			this.powered = nowPowered;
			this.markDirty();
		}
		if (rising && this.path.settings().redstoneTrigger()) {
			PlaybackManager.startVehicleFromBlock(this, null, -1);
		}
	}

	private void sync() {
		this.markDirty();
		if (this.world != null && !this.world.isClient()) {
			BlockState state = this.getCachedState();
			this.world.updateListeners(this.pos, state, state, 3);
		}
	}

	// ------------------------------------------------------------------ lifecycle

	/** Breaking the block stops the playbacks it started (super drops the book and the stick). Runs while the block entity is still in place - see tudursvehiclemod's A-4 fix for why onStateReplaced() is too late. */
	@Override
	public void onBlockReplaced(BlockPos pos, BlockState oldState) {
		if (this.world instanceof ServerWorld serverWorld) {
			PlaybackManager.stopFromBlock(serverWorld, pos);
		}
		super.onBlockReplaced(pos, oldState);
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		this.items.set(0, ItemStack.EMPTY);
		this.items.set(1, ItemStack.EMPTY);
		Inventories.readData(view, this.items);
		this.path = view.read("CameraPath", CameraPath.CODEC).orElse(CameraPath.EMPTY);
		this.name = view.getString("Name", "");
		this.preview = view.getBoolean("Preview", false);
		this.powered = view.getBoolean("Powered", false);
		if (this.world != null && this.world.isClient()) {
			CLIENT_LOADED.add(this);
		}
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		Inventories.writeData(view, this.items, true);
		view.put("CameraPath", CameraPath.CODEC, this.path);
		view.putString("Name", this.name);
		view.putBoolean("Preview", this.preview);
		view.putBoolean("Powered", this.powered);
	}

	@Override
	public Packet<ClientPlayPacketListener> toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
		return this.createNbt(registries);
	}

	// ------------------------------------------------------------------ screen + inventory

	@Override
	public Text getDisplayName() {
		return Text.translatable("block.camerapathaddon.camera_control");
	}

	@Override
	public BlockPos getScreenOpeningData(ServerPlayerEntity player) {
		return this.getPos();
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new CameraControlScreenHandler(syncId, playerInventory, this, this.getPos());
	}

	@Override
	public int size() {
		return this.items.size();
	}

	@Override
	public boolean isEmpty() {
		return this.items.get(0).isEmpty() && this.items.get(1).isEmpty();
	}

	@Override
	public ItemStack getStack(int slot) {
		return this.items.get(slot);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack result = Inventories.splitStack(this.items, slot, amount);
		if (!result.isEmpty()) {
			this.sync();
		}
		return result;
	}

	@Override
	public ItemStack removeStack(int slot) {
		ItemStack result = Inventories.removeStack(this.items, slot);
		this.sync();
		return result;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		this.items.set(slot, stack);
		stack.capCount(this.getMaxCount(stack));
		this.sync();
	}

	@Override
	public int getMaxCountPerStack() {
		return 1;
	}

	/** Only a route book in slot 0 and a drone control stick in slot 1 - also for hoppers. */
	@Override
	public boolean isValid(int slot, ItemStack stack) {
		return slot == SLOT_BOOK ? stack.getItem() instanceof DroneRouteBookItem : stack.getItem() instanceof DroneControlStickItem;
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return Inventory.canPlayerUse(this, player);
	}

	@Override
	public void clear() {
		this.items.clear();
		this.sync();
	}
}
