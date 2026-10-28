package com.example.camerapathaddon.entity;

import com.example.camerapathaddon.server.PlaybackManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.world.World;

/**
 * The invisible, collision-free "vehicle" a player rides while their view follows a camera path. The server moves it
 * along the path, so the player's body - and with it chunk loading and entity tracking - goes where the camera goes.
 * The camera itself is placed client-side from the path every frame (see the client's camera mixin), so the rig's
 * own network-synced position never shows.
 *
 * <p>Never saved (shouldSave() is false - not the entity type's disableSaving(), because vanilla won't let a player
 * ride an entity whose type can't be saved), and a rig the server finds without a running playback removes itself.
 */
public class CameraRigEntity extends Entity {

	public CameraRigEntity(EntityType<?> type, World world) {
		super(type, world);
		this.noClip = true;
		this.setNoGravity(true);
		this.setInvisible(true);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getEntityWorld() instanceof ServerWorld && !PlaybackManager.ownsRig(this)) {
			this.removeAllPassengers();
			this.discard();
		}
	}

	/** The rider's EYES sit exactly at the rig's position (the camera point of the path), so the server's view of where the player looks from - chunk loading, entity tracking, sounds - matches what they see. */
	@Override
	protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
		if (!this.hasPassenger(passenger)) {
			return;
		}
		positionUpdater.accept(passenger, this.getX(), this.getY() - passenger.getStandingEyeHeight(), this.getZ());
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (this.getEntityWorld() instanceof ServerWorld) {
			PlaybackManager.onRigPassengerRemoved(this, passenger);
		}
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengerList().isEmpty();
	}

	@Override
	public boolean shouldSave() {
		return false;
	}

	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean canHit() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isCollidable(Entity other) {
		return false;
	}

	@Override
	protected void readCustomData(ReadView view) {
	}

	@Override
	protected void writeCustomData(WriteView view) {
	}
}
