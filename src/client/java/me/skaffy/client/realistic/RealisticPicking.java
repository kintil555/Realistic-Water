package me.skaffy.client.realistic;

import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class RealisticPicking {
	private RealisticPicking() {
	}

	public static void refine(Minecraft mc, float partialTicks) {
		Entity camera = mc.getCameraEntity();
		if (camera == null || mc.level == null || mc.player == null) {
			return;
		}
		Vec3 eye = camera.getEyePosition(partialTicks);
		Vec3 dir = camera.getViewVector(partialTicks);
		double reach = Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange());
		HitResult current = mc.hitResult;
		boolean currentIsBody = current instanceof EntityHitResult e && e.getEntity() instanceof RealisticBlockEntity;
		double limit = current == null || current.getType() == HitResult.Type.MISS || currentIsBody ? reach : eye.distanceTo(current.getLocation());
		AABB area = new AABB(eye, eye.add(dir.scale(reach))).inflate(1.0);
		RealisticHit best = null;
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, area)) {
			e.syncClientBody(e.renderPosition(partialTicks), e.renderRotation(partialTicks));
			double[] hit = raycast(e.body, eye, dir, limit);
			if (hit != null) {
				limit = hit[0];
				best = new RealisticHit(e, eye.add(dir.scale(hit[0])), (int) hit[1], (int) hit[2], (int) hit[3]);
			}
		}
		if (best != null) {
			mc.hitResult = best;
			mc.crosshairPickEntity = best.getEntity();
		} else if (currentIsBody) {
			mc.hitResult = mc.player.pick(mc.player.blockInteractionRange(), partialTicks, false);
			mc.crosshairPickEntity = null;
		}
	}

	private static final double[][] CUBE = {{0, 0, 0, 1, 1, 1}};

	static double[] raycast(RigidBody body, Vec3 eye, Vec3 dir, double maxT) {
		if (body.cells.isEmpty()) {
			return null;
		}
		Vector3d o = body.toGrid(eye.x, eye.y, eye.z, new Vector3d());
		Vector3d e = body.toGrid(eye.x + dir.x, eye.y + dir.y, eye.z + dir.z, new Vector3d());
		double dx = e.x - o.x;
		double dy = e.y - o.y;
		double dz = e.z - o.z;
		double[] best = null;
		double bestT = maxT;
		double[] ori = {o.x, o.y, o.z};
		double[] d = {dx, dy, dz};
		for (RigidBody.Cell c : body.cells) {
			for (double[] box : c.boxes == null ? CUBE : c.boxes) {
				double t0 = 0.0;
				double t1 = bestT;
				double[] lo = {c.x + box[0], c.y + box[1], c.z + box[2]};
				double[] hi = {c.x + box[3], c.y + box[4], c.z + box[5]};
				boolean miss = false;
				for (int a = 0; a < 3 && !miss; a++) {
					if (Math.abs(d[a]) < 1e-12) {
						miss = ori[a] < lo[a] || ori[a] > hi[a];
						continue;
					}
					double ta = (lo[a] - ori[a]) / d[a];
					double tb = (hi[a] - ori[a]) / d[a];
					t0 = Math.max(t0, Math.min(ta, tb));
					t1 = Math.min(t1, Math.max(ta, tb));
					miss = t0 > t1;
				}
				if (!miss && t0 < bestT) {
					bestT = t0;
					best = new double[]{t0, c.x, c.y, c.z};
				}
			}
		}
		return best;
	}
}
