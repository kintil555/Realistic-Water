package me.skaffy.client.mixin;

import me.skaffy.client.compat.ShaderCompat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void realisticWater$renderWater(CallbackInfo ci) {
		ShaderCompat.inLevel();
	}
}
