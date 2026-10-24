package me.skaffy.realistic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.net.BodyStatePayload;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.physics.RigidBody;
import net.minecraft.world.entity.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.skaffy.block.WaveMakerBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

public class RealisticBlockEntity extends Entity {
	private static final EntityDataAccessor<String> DATA_CELLS = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Quaternionfc> DATA_ROT = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.QUATERNION);
	private static final EntityDataAccessor<Boolean> DATA_FLOATING = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_OWNER = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> DATA_VEL = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Vector3fc> DATA_SPIN = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.VECTOR3);
	public static final int FREE = -1;
	public static final int LOCKED = -2;
	private static final int LOCK_TICKS = 10;
	private static final int OWNER_TIMEOUT = 20;
	public static final double CLAIM_RANGE = 32.0;

	public final RigidBody body = new RigidBody();
	private boolean bodyNeedsPosition;
	int burningTicks;
	private @Nullable GridRedstone redstone;
	private final Quaternionf rotO = new Quaternionf();
	private final Quaternionf rotNow = new Quaternionf();
	private final Quaternionf rotTarget = new Quaternionf();
	private int lastStateTick;
	private int ownerTouchTick;
	private int lockTicks;
	private boolean simulated;
	public int claimAge;
	public int claimCooldown;
	public long touchedTick = Long.MIN_VALUE;
	private final Vector3d renderFrom = new Vector3d();
	private final Vector3d renderTo = new Vector3d();

	public RealisticBlockEntity(EntityType<? extends RealisticBlockEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static RealisticBlockEntity spawn(ServerLevel level, List<RigidBody.Cell> cells, Vector3d center, @Nullable RigidBody like) {
		RealisticBlockEntity e = new RealisticBlockEntity(ModContent.REALISTIC_BLOCK_ENTITY, level);
		e.body.setCells(cells);
		if (like != null) {
			e.body.rot.set(like.rot);
		}
		e.body.rebuild();
		e.body.pos.set(center);
		e.body.updateDerived();
		if (like != null) {
			like.velocityAt(center.x, center.y, center.z, e.body.vel);
			e.body.ang.set(like.ang);
		}
		e.syncShape();
		e.syncRotation();
		e.setPos(center.x, center.y, center.z);
		level.addFreshEntity(e);
		return e;
	}


	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		builder.define(DATA_CELLS, "");
		builder.define(DATA_ROT, new Quaternionf());
		builder.define(DATA_FLOATING, false);
		builder.define(DATA_OWNER, FREE);
		builder.define(DATA_VEL, new Vector3f());
		builder.define(DATA_SPIN, new Vector3f());
	}

	public int owner() {
		return this.entityData.get(DATA_OWNER);
	}

	private void setOwner(int owner) {
		this.entityData.set(DATA_OWNER, owner);
	}

	@Override
	public boolean isClientAuthoritative() {
		return this.owner() >= 0;
	}

	@Override
	protected boolean isLocalClientAuthoritative() {
		return this.simulated;
	}

	public boolean isSimulated() {
		return this.simulated;
	}

	public Vector3fc syncedVelocity() {
		return this.entityData.get(DATA_VEL);
	}

	public Vector3fc syncedSpin() {
		return this.entityData.get(DATA_SPIN);
	}

	void syncShape() {
		StringBuilder sb = new StringBuilder();
		for (RigidBody.Cell c : this.body.cells) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(c.x).append(',').append(c.y).append(',').append(c.z).append(',').append(Block.getId((BlockState) c.payload));
		}
		this.entityData.set(DATA_CELLS, sb.toString());
	}

	void syncRotation() {
		this.entityData.set(DATA_ROT, new Quaternionf((float) this.body.rot.x, (float) this.body.rot.y, (float) this.body.rot.z, (float) this.body.rot.w));
	}

	@Override
	public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (!this.level().isClientSide()) {
			return;
		}
		if (DATA_CELLS.equals(accessor)) {
			this.cellsChanged();
		} else if (DATA_ROT.equals(accessor)) {
			Quaternionfc q = this.entityData.get(DATA_ROT);
			boolean first = this.tickCount == 0;
			this.rotTarget.set(q);
			if (first && !this.simulated) {
				this.rotNow.set(q);
				this.rotO.set(q);
			}
		}
	}

	protected void cellsChanged() {
		List<RigidBody.Cell> cells = new ArrayList<>();
		for (String part : this.entityData.get(DATA_CELLS).split(";")) {
			String[] f = part.split(",");
			if (f.length == 4) {
				BlockState state = Block.stateById(Integer.parseInt(f[3]));
				cells.add(this.makeCell(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), state));
			}
		}
		if (this.sameCells(cells)) {
			boolean reshaped = false;
			for (RigidBody.Cell c : cells) {
				RigidBody.Cell old = this.body.cellAt(c.x, c.y, c.z);
				if (old.payload != c.payload) {
					reshaped |= !java.util.Arrays.deepEquals(old.boxes, c.boxes) || old.density != c.density;
					this.body.replaceCell(c);
				}
			}
			if (reshaped) {
				this.body.rebuild();
				if (this.simulated) {
					this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
				}
			}
			return;
		}
		this.stopSimulating();
		boolean had = !this.body.cells.isEmpty() && this.body.mass > 0.0;
		Vector3d oldCom = new Vector3d(this.body.com);
		this.body.setCells(cells);
		this.body.rebuild();
		if (!had) {
			this.setPos(this.position());
			return;
		}
		Vector3d shift = this.body.rot.transform(new Vector3d(this.body.com).sub(oldCom));
		this.xo += shift.x;
		this.yo += shift.y;
		this.zo += shift.z;
		this.setPos(this.getX() + shift.x, this.getY() + shift.y, this.getZ() + shift.z);
	}

	protected RigidBody.Cell makeCell(int x, int y, int z, BlockState state) {
		return BlockMaterials.cell(x, y, z, state);
	}

	protected double[][] playerBoxes(RigidBody.Cell cell) {
		return cell.payload instanceof BlockState state ? BlockMaterials.collisionBoxes(state) : CUBE;
	}

	public void addLoads(PhysicsWorld world, @Nullable Input driver) {
	}

	public byte stateFlags() {
		return 0;
	}

	protected void receivedFlags(byte flags) {
	}

	private boolean sameCells(List<RigidBody.Cell> cells) {
		if (cells.size() != this.body.cells.size() || cells.isEmpty()) {
			return false;
		}
		for (RigidBody.Cell c : cells) {
			if (!this.body.hasCell(c.x, c.y, c.z)) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return LinearInterpolationHandler.create(this, 1);
	}

	public void startSimulating() {
		this.syncClientBody(this.position(), this.rotNow);
		Vector3fc v = this.syncedVelocity();
		Vector3fc w = this.syncedSpin();
		this.body.vel.set(v.x(), v.y(), v.z());
		this.body.ang.set(w.x(), w.y(), w.z());
		this.renderFrom.set(this.body.pos);
		this.renderTo.set(this.body.pos);
		this.rotO.set(this.rotNow);
		this.claimAge = 0;
		this.simulated = true;
	}

	public void stopSimulating() {
		if (!this.simulated) {
			return;
		}
		this.simulated = false;
		this.rotTarget.set(this.rotNow);
	}

	public void afterClientStep() {
		this.renderFrom.set(this.renderTo);
		this.renderTo.set(this.body.pos);
		this.rotO.set(this.rotNow);
		this.rotNow.set((float) this.body.rot.x, (float) this.body.rot.y, (float) this.body.rot.z, (float) this.body.rot.w);
		this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
	}

	public Vec3 renderPosition(float partialTicks) {
		if (!this.simulated) {
			return this.getPosition(partialTicks);
		}
		return new Vec3(this.renderFrom.x + (this.renderTo.x - this.renderFrom.x) * partialTicks, this.renderFrom.y + (this.renderTo.y - this.renderFrom.y) * partialTicks,
			this.renderFrom.z + (this.renderTo.z - this.renderFrom.z) * partialTicks);
	}

	public Vector3d clientVelocity() {
		if (this.simulated) {
			return new Vector3d(this.body.vel);
		}
		return new Vector3d((this.getX() - this.xo) * 20.0, (this.getY() - this.yo) * 20.0, (this.getZ() - this.zo) * 20.0);
	}

	public Vector3d clientSpin() {
		Quaternionf dq = new Quaternionf(this.rotNow).mul(new Quaternionf(this.rotO).conjugate());
		if (dq.w < 0.0F) {
			dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
		}
		double s = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
		if (s < 1e-6) {
			return new Vector3d();
		}
		double angle = 2.0 * Math.atan2(s, dq.w) * 20.0;
		return new Vector3d(dq.x / s * angle, dq.y / s * angle, dq.z / s * angle);
	}

	public Quaternionf renderRotation(float partialTicks) {
		return new Quaternionf(this.rotO).slerp(this.rotNow, partialTicks);
	}

	public void syncClientBody(Vec3 position, Quaternionfc rotation) {
		if (this.simulated) {
			return;
		}
		this.body.pos.set(position.x, position.y, position.z);
		this.body.rot.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
		if (!this.body.cells.isEmpty()) {
			this.body.updateDerived();
		}
	}


	@Override
	public void tick() {
		if (this.level().isClientSide()) {
			if (!this.simulated) {
				this.rotO.set(this.rotNow);
				this.rotNow.set(this.rotTarget);
				this.setPos(this.position());
			}
			return;
		}
		if (this.body.cells.isEmpty()) {
			this.discard();
			return;
		}
		if (this.bodyNeedsPosition) {
			this.bodyNeedsPosition = false;
			this.body.pos.set(this.getX(), this.getY(), this.getZ());
			this.body.updateDerived();
		}
		if (this.burningTicks > 0) {
			this.burningTicks--;
		}
		this.checkOwner();
		if (this.redstone == null) {
			this.redstone = new GridRedstone(this);
		}
		this.redstone.tick();
		if (this.isRemoved() || this.body.cells.isEmpty()) {
			return;
		}
		this.setSharedFlagOnFire(this.burningTicks > 0);
		if (this.getY() < this.level().getMinY() - 64) {
			this.discard();
			return;
		}
		RealisticPhysics.track(this);
	}

	void afterPhysics() {
		this.afterPhysics(this.body.floating());
	}

	private void afterPhysics(boolean floating) {
		Vector3d p = this.body.pos;
		if (p.x != this.getX() || p.y != this.getY() || p.z != this.getZ()) {
			this.setPos(p.x, p.y, p.z);
		}
		this.syncRotation();
		this.entityData.set(DATA_FLOATING, floating);
		this.entityData.set(DATA_VEL, new Vector3f((float) this.body.vel.x, (float) this.body.vel.y, (float) this.body.vel.z));
		this.entityData.set(DATA_SPIN, new Vector3f((float) this.body.ang.x, (float) this.body.ang.y, (float) this.body.ang.z));
	}


	private int serverTick() {
		return ((ServerLevel) this.level()).getServer().getTickCount();
	}

	private void checkOwner() {
		int owner = this.owner();
		if (owner == LOCKED) {
			if (--this.lockTicks <= 0) {
				this.setOwner(FREE);
			}
		} else if (owner >= 0 && (this.serverTick() - this.lastStateTick > OWNER_TIMEOUT || !(this.level().getEntity(owner) instanceof ServerPlayer p)
			|| p.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE * 4.0)) {
			this.setOwner(FREE);
		}
	}

	void setCellStates(Long2ObjectMap<BlockState> changes) {
		boolean reshaped = false;
		for (Long2ObjectMap.Entry<BlockState> entry : changes.long2ObjectEntrySet()) {
			long k = entry.getLongKey();
			RigidBody.Cell old = this.body.cellAt(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
			if (old == null) {
				continue;
			}
			RigidBody.Cell next = this.makeCell(old.x, old.y, old.z, entry.getValue());
			reshaped |= !java.util.Arrays.deepEquals(old.boxes, next.boxes) || old.density != next.density;
			this.body.replaceCell(next);
		}
		if (reshaped) {
			this.body.rebuild();
			this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
		}
		this.syncShape();
	}

	void extendPiston(long base, BlockState extended, long front, BlockState head, List<Long> line, Direction facing, boolean kick) {
		this.lock();
		for (int i = line.size() - 1; i >= 0; i--) {
			long k = line.get(i);
			RigidBody.Cell c = this.body.removeCell(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
			if (c != null) {
				this.body.addCell(c.moved(c.x + facing.getStepX(), c.y + facing.getStepY(), c.z + facing.getStepZ()));
			}
		}
		this.body.replaceCell(BlockMaterials.cell(BlockPos.getX(base), BlockPos.getY(base), BlockPos.getZ(base), extended));
		this.body.addCell(BlockMaterials.cell(BlockPos.getX(front), BlockPos.getY(front), BlockPos.getZ(front), head));
		this.body.rebuild();
		this.syncShape();
		this.afterPhysics();
		Vector3d tip = this.body.toWorld(BlockPos.getX(front) + 0.5 + facing.getStepX() * 0.3, BlockPos.getY(front) + 0.5 + facing.getStepY() * 0.3,
			BlockPos.getZ(front) + 0.5 + facing.getStepZ() * 0.3, new Vector3d());
		this.level().playSound(null, tip.x, tip.y, tip.z, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, this.random.nextFloat() * 0.25F + 0.6F);
		if (!kick) {
			return;
		}
		Vector3d dir = this.body.dirToWorld(facing.getStepX(), facing.getStepY(), facing.getStepZ(), new Vector3d());
		BlockPos at = BlockPos.containing(tip.x, tip.y, tip.z);
		RealisticBlockEntity other = null;
		for (RealisticBlockEntity e : this.level().getEntitiesOfClass(RealisticBlockEntity.class, new AABB(at).inflate(0.5))) {
			if (e != this && e.body.containsWorld(tip.x, tip.y, tip.z)) {
				other = e;
				break;
			}
		}
		boolean wall = !this.level().getBlockState(at).getCollisionShape(this.level(), at).isEmpty();
		if (!wall && other == null) {
			return;
		}
		double impulse = Math.min(PISTON_IMPULSE, this.body.mass * 8.0);
		RealisticPhysics.push(this, tip.x, tip.y, tip.z, -dir.x, -dir.y, -dir.z, Double.POSITIVE_INFINITY, 0.0, impulse);
		if (other != null) {
			RealisticPhysics.push(other, tip.x, tip.y, tip.z, dir.x, dir.y, dir.z, Double.POSITIVE_INFINITY, 0.0, Math.min(PISTON_IMPULSE, other.body.mass * 8.0));
		}
	}

	private static final double PISTON_IMPULSE = 3000.0;

	private void lock() {
		this.setOwner(LOCKED);
		this.lockTicks = LOCK_TICKS;
	}

	public void acceptState(ServerPlayer player, BodyStatePayload s) {
		if (this.isRemoved() || this.body.cells.isEmpty() || this.owner() == LOCKED || player.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE * 4.0) {
			return;
		}
		int now = this.serverTick();
		int owner = this.owner();
		if (owner != player.getId()) {
			boolean free = owner == FREE || now - this.lastStateTick > OWNER_TIMEOUT;
			boolean idleOwner = s.has(BodyStatePayload.TOUCHING) && now - this.ownerTouchTick > 10 || this.getControllingPassenger() == player;
			if (s.has(BodyStatePayload.RELEASE) || !free && !idleOwner || player.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE) {
				return;
			}
		}
		double[] all = {s.x(), s.y(), s.z(), s.qx(), s.qy(), s.qz(), s.qw(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()};
		for (double d : all) {
			if (!Double.isFinite(d)) {
				return;
			}
		}
		if (this.body.pos.distanceSquared(s.x(), s.y(), s.z()) > 64.0) {
			return;
		}
		this.body.pos.set(s.x(), s.y(), s.z());
		this.body.rot.set(s.qx(), s.qy(), s.qz(), s.qw());
		this.body.vel.set(s.vx(), s.vy(), s.vz());
		this.body.ang.set(s.wx(), s.wy(), s.wz());
		double speed = this.body.vel.length();
		if (speed > 60.0) {
			this.body.vel.mul(60.0 / speed);
		}
		this.body.updateDerived();
		this.afterPhysics(s.has(BodyStatePayload.FLOATING));
		this.receivedFlags(s.flags());
		if (s.has(BodyStatePayload.RELEASE)) {
			this.setOwner(FREE);
			return;
		}
		this.setOwner(player.getId());
		this.lastStateTick = now;
		if (s.has(BodyStatePayload.TOUCHING)) {
			this.ownerTouchTick = now;
		}
	}

	public boolean isFloating() {
		return this.entityData.get(DATA_FLOATING);
	}

	@Override
	protected AABB makeBoundingBox(final Vec3 position) {
		if (this.body == null || this.body.cells.isEmpty()) {
			return super.makeBoundingBox(position);
		}
		if (this.level() != null && this.level().isClientSide()) {
			this.syncClientBody(position, this.rotNow);
		} else {
			this.body.pos.set(position.x, position.y, position.z);
			this.body.updateDerived();
		}
		return new AABB(this.body.minX, this.body.minY, this.body.minZ, this.body.maxX, this.body.maxY, this.body.maxZ);
	}

	public List<VoxelShape> collisionShapes(AABB area) {
		List<VoxelShape> out = new ArrayList<>();
		RigidBody b = this.body;
		if (b.cells.isEmpty()) {
			return out;
		}
		Vector3d c = new Vector3d();
		Vector3d ax = new Vector3d();
		Vector3d ay = new Vector3d();
		Vector3d az = new Vector3d();
		for (RigidBody.Cell cell : b.cells) {
			double[][] boxes = this.playerBoxes(cell);
			for (double[] box : boxes) {
				double sx = box[3] - box[0];
				double sy = box[4] - box[1];
				double sz = box[5] - box[2];
				b.dirToWorld(sx * 0.25, 0, 0, ax);
				b.dirToWorld(0, sy * 0.25, 0, ay);
				b.dirToWorld(0, 0, sz * 0.25, az);
				double ex = Math.abs(ax.x) + Math.abs(ay.x) + Math.abs(az.x);
				double ey = Math.abs(ax.y) + Math.abs(ay.y) + Math.abs(az.y);
				double ez = Math.abs(ax.z) + Math.abs(ay.z) + Math.abs(az.z);
				for (int s = 0; s < 8; s++) {
					b.toWorld(cell.x + box[0] + sx * (0.25 + 0.5 * (s & 1)), cell.y + box[1] + sy * (0.25 + 0.5 * ((s >> 1) & 1)),
						cell.z + box[2] + sz * (0.25 + 0.5 * (s >> 2)), c);
					AABB sub = new AABB(c.x - ex, c.y - ey, c.z - ez, c.x + ex, c.y + ey, c.z + ez);
					if (sub.intersects(area)) {
						out.add(Shapes.create(sub));
					}
				}
			}
		}
		return out;
	}

	public static boolean wallAhead(Entity mover, Vec3 movement) {
		if (mover instanceof RealisticBlockEntity || movement.horizontalDistanceSqr() < 1.0E-8) {
			return false;
		}
		AABB box = mover.getBoundingBox();
		AABB moved = box.move(movement.x, 0.0, movement.z).deflate(1.0E-4);
		for (RealisticBlockEntity e : mover.level().getEntitiesOfClass(RealisticBlockEntity.class, moved.inflate(0.1))) {
			for (VoxelShape shape : e.collisionShapes(moved)) {
				AABB b = shape.bounds();
				if (b.maxY > box.minY + 0.01 && b.intersects(moved)) {
					return true;
				}
			}
		}
		return false;
	}


	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(final @Nullable Entity other) {
		return !(other instanceof RealisticBlockEntity);
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean skipAttackInteraction(final Entity source) {
		return true;
	}

	@Override
	public void push(final Vec3 impulse) {
	}

	@Override
	public void push(final double xa, final double ya, final double za) {
	}

	@Override
	public void push(final Entity entity) {
	}

	@Override
	public void pushFromExplosion(final Vec3 impulse) {
		if (!this.level().isClientSide()) {
			RealisticPhysics.explosion(this, impulse);
		}
	}

	@Override
	public void move(final MoverType moverType, final Vec3 delta) {
		if (this.level().isClientSide() || moverType == MoverType.SELF || moverType == MoverType.PLAYER) {
			return;
		}
		this.lock();
		this.body.pos.add(delta.x, delta.y, delta.z);
		this.body.vel.set(delta.x * 5.0, Math.max(delta.y * 5.0, this.body.vel.y), delta.z * 5.0);
		this.body.updateDerived();
		this.afterPhysics();
	}

	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		ItemStack stack = player.getItemInHand(hand);
		Block block = RealisticBlockItem.blockOf(stack);
		Vec3 clicked = this.position().add(location);
		if (block == null) {
			return this.useCell(player, clicked);
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		int[] spot = this.faceAt(clicked);
		if (spot == null || this.body.hasCell(spot[0], spot[1], spot[2])) {
			return InteractionResult.FAIL;
		}
		Vector3d hit = this.body.toGrid(clicked.x, clicked.y, clicked.z, new Vector3d()).sub(spot[0], spot[1], spot[2]);
		Vec3 view = player.getViewVector(1.0F);
		Vector3d look = this.body.dirToGrid(view.x, view.y, view.z, new Vector3d());
		BlockState state = GridPlacement.stateFor(block, Direction.from3DDataValue(spot[3]), hit, look);
		if (state == null) {
			return InteractionResult.FAIL;
		}
		Vector3d center = this.body.toWorld(spot[0] + 0.5, spot[1] + 0.5, spot[2] + 0.5, new Vector3d());
		BlockPos at = BlockPos.containing(center.x, center.y, center.z);
		if (!this.level().getBlockState(at).getCollisionShape(this.level(), at).isEmpty()) {
			return InteractionResult.FAIL;
		}
		this.lock();
		this.body.addCell(BlockMaterials.cell(spot[0], spot[1], spot[2], state));
		this.body.rebuild();
		this.syncShape();
		this.afterPhysics();
		SoundType sound = state.getSoundType();
		this.level().playSound(null, center.x, center.y, center.z, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
			sound.getPitch() * 0.8F);
		if (!player.hasInfiniteMaterials()) {
			stack.shrink(1);
		}
		return InteractionResult.SUCCESS_SERVER;
	}

	private InteractionResult useCell(Player player, Vec3 clicked) {
		RigidBody.Cell cell = this.cellAt(clicked);
		if (cell == null || !(cell.payload instanceof BlockState state) || !(state.getBlock() instanceof WaveMakerBlock)) {
			return InteractionResult.PASS;
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		BlockState next = state.cycle(WaveMakerBlock.SPEED);
		Long2ObjectOpenHashMap<BlockState> change = new Long2ObjectOpenHashMap<>();
		change.put(BlockPos.asLong(cell.x, cell.y, cell.z), next);
		this.setCellStates(change);
		WaveMakerBlock.announceSpeed(this.level(), player, clicked.x, clicked.y, clicked.z, next);
		return InteractionResult.SUCCESS_SERVER;
	}

	public RigidBody.@Nullable Cell cellAt(Vec3 world) {
		Vector3d g = this.body.toGrid(world.x, world.y, world.z, new Vector3d());
		int bx = (int) Math.floor(g.x);
		int by = (int) Math.floor(g.y);
		int bz = (int) Math.floor(g.z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					RigidBody.Cell cell = this.body.cellAt(bx + dx, by + dy, bz + dz);
					if (cell == null) {
						continue;
					}
					for (double[] box : cell.boxes == null ? CUBE : cell.boxes) {
						if (g.x > cell.x + box[0] - 0.03 && g.x < cell.x + box[3] + 0.03 && g.y > cell.y + box[1] - 0.03 && g.y < cell.y + box[4] + 0.03
							&& g.z > cell.z + box[2] - 0.03 && g.z < cell.z + box[5] + 0.03) {
							return cell;
						}
					}
				}
			}
		}
		return null;
	}

	private static final double[][] CUBE = {{0, 0, 0, 1, 1, 1}};
	private static final Direction[] BOX_FACES = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

	public int @Nullable [] faceAt(Vec3 world) {
		Vector3d g = this.body.toGrid(world.x, world.y, world.z, new Vector3d());
		int[] best = null;
		double bestDist = 0.03;
		int bx = (int) Math.floor(g.x);
		int by = (int) Math.floor(g.y);
		int bz = (int) Math.floor(g.z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					RigidBody.Cell cell = this.body.cellAt(bx + dx, by + dy, bz + dz);
					if (cell == null) {
						continue;
					}
					for (double[] box : cell.boxes == null ? CUBE : cell.boxes) {
						double[] d = {g.x - cell.x - box[0], cell.x + box[3] - g.x, g.y - cell.y - box[1], cell.y + box[4] - g.y, g.z - cell.z - box[2],
							cell.z + box[5] - g.z};
						if (d[0] < -0.03 || d[1] < -0.03 || d[2] < -0.03 || d[3] < -0.03 || d[4] < -0.03 || d[5] < -0.03) {
							continue;
						}
						for (int f = 0; f < 6; f++) {
							double dist = Math.abs(d[f]);
							Direction face = BOX_FACES[f];
							int[] n = {cell.x + face.getStepX(), cell.y + face.getStepY(), cell.z + face.getStepZ(), face.get3DDataValue()};
							if (dist < bestDist && !this.body.hasCell(n[0], n[1], n[2])) {
								bestDist = dist;
								best = n;
							}
						}
					}
				}
			}
		}
		return best;
	}

	public void removeCell(int x, int y, int z) {
		if (this.body.removeCell(x, y, z) == null) {
			return;
		}
		this.lock();
		if (this.body.cells.isEmpty()) {
			this.discard();
			return;
		}
		List<List<RigidBody.Cell>> parts = this.body.components();
		if (parts.size() > 1 && this.level() instanceof ServerLevel server) {
			parts.sort(Comparator.comparingInt(List::size));
			for (List<RigidBody.Cell> part : parts.subList(0, parts.size() - 1)) {
				for (RigidBody.Cell c : part) {
					this.body.removeCell(c.x, c.y, c.z);
				}
				RigidBody probe = new RigidBody();
				probe.setCells(part);
				probe.rebuild();
				Vector3d center = this.body.toWorld(probe.com.x, probe.com.y, probe.com.z, new Vector3d());
				spawn(server, part, center, this.body);
			}
		}
		this.body.rebuild();
		this.syncShape();
		this.afterPhysics();
	}


	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		ValueOutput.ValueOutputList list = output.childrenList("cells");
		for (RigidBody.Cell c : this.body.cells) {
			ValueOutput o = list.addChild();
			o.putInt("x", c.x);
			o.putInt("y", c.y);
			o.putInt("z", c.z);
			o.store("state", BlockState.CODEC, (BlockState) c.payload);
			o.putDouble("burn", c.burn);
		}
		output.putDouble("qx", this.body.rot.x);
		output.putDouble("qy", this.body.rot.y);
		output.putDouble("qz", this.body.rot.z);
		output.putDouble("qw", this.body.rot.w);
		output.putDouble("vx", this.body.vel.x);
		output.putDouble("vy", this.body.vel.y);
		output.putDouble("vz", this.body.vel.z);
		output.putDouble("wx", this.body.ang.x);
		output.putDouble("wy", this.body.ang.y);
		output.putDouble("wz", this.body.ang.z);
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		List<RigidBody.Cell> cells = new ArrayList<>();
		input.childrenListOrEmpty("cells").stream().forEach(o -> o.read("state", BlockState.CODEC).ifPresent(state -> {
			RigidBody.Cell c = this.makeCell(o.getIntOr("x", 0), o.getIntOr("y", 0), o.getIntOr("z", 0), state);
			c.burn = o.getDoubleOr("burn", 0.0);
			cells.add(c);
		}));
		this.body.setCells(cells);
		this.body.rot.set(input.getDoubleOr("qx", 0.0), input.getDoubleOr("qy", 0.0), input.getDoubleOr("qz", 0.0), input.getDoubleOr("qw", 1.0));
		this.body.vel.set(input.getDoubleOr("vx", 0.0), input.getDoubleOr("vy", 0.0), input.getDoubleOr("vz", 0.0));
		this.body.ang.set(input.getDoubleOr("wx", 0.0), input.getDoubleOr("wy", 0.0), input.getDoubleOr("wz", 0.0));
		this.body.rebuild();
		this.bodyNeedsPosition = true;
		this.setPos(this.position());
		this.syncShape();
		this.syncRotation();
	}
}
