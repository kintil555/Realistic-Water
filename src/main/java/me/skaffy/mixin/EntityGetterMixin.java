package me.skaffy.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
	@Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
	private void realisticWater$cellShapes(@Nullable Entity source, AABB testArea, CallbackInfoReturnable<List<VoxelShape>> cir) {
		if (cir.getReturnValue().isEmpty()) {
			return;
		}
		EntityGetter self = (EntityGetter) this;
		Predicate<Entity> canCollide = source == null ? EntitySelector.CAN_BE_COLLIDED_WITH : EntitySelector.NO_SPECTATORS.and(source::canCollideWith);
		List<Entity> entities = self.getEntities(source, testArea.inflate(1.0E-7), canCollide);
		boolean any = false;
		for (Entity e : entities) {
			if (e instanceof RealisticBlockEntity) {
				any = true;
				break;
			}
		}
		if (!any) {
			return;
		}
		boolean serverPlayer = source instanceof ServerPlayer;
		List<VoxelShape> shapes = new ArrayList<>(entities.size());
		for (Entity e : entities) {
			if (e instanceof RealisticBlockEntity body) {
				if (!serverPlayer) {
					shapes.addAll(body.collisionShapes(testArea.inflate(0.5)));
				}
			} else {
				shapes.add(Shapes.create(e.getBoundingBox()));
			}
		}
		cir.setReturnValue(shapes);
	}
}
