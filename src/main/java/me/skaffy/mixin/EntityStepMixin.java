package me.skaffy.mixin;

import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityStepMixin {
	@Unique
	private boolean realisticWater$wallAhead;

	@Inject(method = "collide", at = @At("HEAD"))
	private void realisticWater$checkWall(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		Entity self = (Entity) (Object) this;
		this.realisticWater$wallAhead = self instanceof LivingEntity && self.maxUpStep() > 0.0F && RealisticBlockEntity.wallAhead(self, movement);
	}

	@Redirect(method = "collide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;maxUpStep()F"))
	private float realisticWater$noStepOntoBodies(Entity self) {
		return this.realisticWater$wallAhead ? 0.0F : self.maxUpStep();
	}
}
