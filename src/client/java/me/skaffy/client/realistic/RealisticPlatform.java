package me.skaffy.client.realistic;

import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class RealisticPlatform {
	private static final double STEP = 0.6;
	private static @Nullable RealisticBlockEntity under;
	private static final Vector3d anchorGrid = new Vector3d();
	private static final Vector3d anchorWorld = new Vector3d();

	private RealisticPlatform() {
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.isPaused() || player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
			under = null;
			return;
		}
		if (under != null && !under.isRemoved()) {
			sync(under);
			Vector3d now = under.body.toWorld(anchorGrid.x, anchorGrid.y, anchorGrid.z, new Vector3d());
			double dx = now.x - anchorWorld.x;
			double dy = now.y - anchorWorld.y;
			double dz = now.z - anchorWorld.z;
			if (dx * dx + dy * dy + dz * dz < 1.0) {
				player.setPos(player.getX() + dx, player.getY() + dy, player.getZ() + dz);
			}
		}
		lift(player, mc);
		pushOut(player, mc);

		under = null;
		AABB box = player.getBoundingBox();
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, box.expandTowards(0.0, -0.3, 0.0))) {
			sync(e);
			if (Math.abs(topUnder(e, box) - box.minY) < 0.08) {
				under = e;
				Vec3 feet = player.position();
				anchorWorld.set(feet.x, feet.y, feet.z);
				e.body.toGrid(feet.x, feet.y, feet.z, anchorGrid);
				break;
			}
		}
	}

	private static void sync(RealisticBlockEntity e) {
		e.syncClientBody(e.position(), e.renderRotation(1.0F));
	}

	private static void lift(LocalPlayer player, Minecraft mc) {
		AABB box = player.getBoundingBox();
		double top = Double.NEGATIVE_INFINITY;
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, box)) {
			sync(e);
			top = Math.max(top, topUnder(e, box));
		}
		if (top > box.minY + 1.0E-4 && top < box.minY + STEP) {
			player.setPos(player.getX(), top, player.getZ());
			Vec3 v = player.getDeltaMovement();
			if (v.y < 0.0) {
				player.setDeltaMovement(v.x, 0.0, v.z);
			}
			player.setOnGround(true);
		}
	}

	private static void pushOut(LocalPlayer player, Minecraft mc) {
		for (int pass = 0; pass < 3; pass++) {
			AABB box = player.getBoundingBox().deflate(0.01);
			double bestX = 0.0;
			double bestZ = 0.0;
			double bestDepth = Double.MAX_VALUE;
			for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, box)) {
				sync(e);
				for (VoxelShape shape : e.collisionShapes(box)) {
					AABB b = shape.bounds();
					if (!b.intersects(box) || b.maxY <= box.minY + STEP) {
						continue;
					}
					double[] out = {b.maxX - box.minX, -(box.maxX - b.minX), b.maxZ - box.minZ, -(box.maxZ - b.minZ)};
					for (int k = 0; k < 4; k++) {
						double depth = Math.abs(out[k]);
						if (depth < bestDepth) {
							bestDepth = depth;
							bestX = k < 2 ? out[k] : 0.0;
							bestZ = k >= 2 ? out[k] : 0.0;
						}
					}
				}
			}
			if (bestDepth == Double.MAX_VALUE || bestDepth > 0.6) {
				return;
			}
			player.setPos(player.getX() + bestX * 1.02, player.getY(), player.getZ() + bestZ * 1.02);
		}
	}

	private static double topUnder(RealisticBlockEntity e, AABB box) {
		AABB column = new AABB(box.minX + 0.05, box.minY - 0.6, box.minZ + 0.05, box.maxX - 0.05, box.minY + STEP, box.maxZ - 0.05);
		double top = Double.NEGATIVE_INFINITY;
		for (VoxelShape shape : e.collisionShapes(column)) {
			AABB b = shape.bounds();
			if (b.maxY <= box.minY + STEP) {
				top = Math.max(top, b.maxY);
			}
		}
		return top;
	}
}
