package me.skaffy.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import me.skaffy.RealisticWater;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public final class ShaderCompat {
	public static final boolean VITRAIL = FabricLoader.getInstance().isModLoaded("vitrail");
	public static final boolean SULKAN = FabricLoader.getInstance().isModLoaded("sulkan");
	public static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

	private static final String VITRAIL_CHAIN = "dev.vitrail.render.PackChain";
	private static final String VITRAIL_GAME_RENDER = "dev.vitrail.render.GameRender";
	private static final String VITRAIL_LEVEL_PASS = "dev.vitrail.render.LevelPass";
	private static final String VITRAIL_GEOMETRY_HOLD = "dev.vitrail.render.GeometryHold";
	private static final String SULKAN_SCENE_PASS = "com.sulkan.shaders.graph.SceneRenderPass";

	private static @Nullable MethodHandle drawingPack;
	private static @Nullable Field featuresColour;
	private static @Nullable Field featuresDepth;
	private static @Nullable MethodHandle vitrailSuspend;
	private static @Nullable MethodHandle geometryFlush;
	private static @Nullable Field levelPassCurrent;
	private static @Nullable Field levelPassUnder;
	private static @Nullable Method levelPassSuspend;
	private static @Nullable MethodHandle sulkanOutside;
	private static boolean vitrailLooked;
	private static boolean sulkanLooked;
	private static boolean openPassSaid;
	private static long skippedFrames;
	private static boolean layerMissingSaid;
	private static boolean drawn;

	private ShaderCompat() {
	}

	public static void logEnvironment() {
		RealisticWater.LOGGER.info("Realistic Water render compat: Sodium {}, Sulkan {}, Vitrail {}", SODIUM ? "present" : "absent",
			SULKAN ? "present" : "absent", VITRAIL ? "present" : "absent");
		if (SULKAN && VITRAIL) {
			RealisticWater.LOGGER.warn("Sulkan and Vitrail are both installed. They both wrap the level's render pass and together leave one open "
				+ "(the game then crashes in render3dHud). Use only one of them.");
		}
	}

	public static void beginLevel() {
		drawn = false;
		WaterWorld.get().beginLevel();
	}

	public static void inLevel() {
		if (!vitrailPackRunning()) {
			draw(FrameTarget.main());
		}
	}

	public static void vitrailFeaturesClosing() {
		if (!WaterSettings.vitrailInsidePack || drawn || !vitrailPackRunning()) {
			return;
		}
		GpuTextureView colour = read(featuresColour);
		if (colour == null) {
			if (!layerMissingSaid && featuresColour == null) {
				layerMissingSaid = true;
				RealisticWater.LOGGER.warn("Vitrail's feature layer wasn't found; drawing fluids over the finished pack image instead");
			}
			return;
		}
		GpuTextureView depth = read(featuresDepth);
		if (depth == null) {
			depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
		}
		draw(FrameTarget.of(colour, depth, true));
	}

	public static void afterLevel() {
		if (!drawn) {
			draw(FrameTarget.main());
		}
		if (WaterWorld.get().hasLate()) {
			FrameTarget main = FrameTarget.main();
			if (main != null) {
				outsidePasses(() -> WaterWorld.get().renderLate(main));
			} else {
				WaterWorld.get().beginLevel();
			}
		}
	}

	private static void draw(@Nullable FrameTarget target) {
		if (target == null) {
			return;
		}
		outsidePasses(() -> {
			drawn = true;
			WaterWorld.get().renderFrame(target);
		});
	}

	private static void outsidePasses(Runnable action) {
		if (!passOpen()) {
			action.run();
			return;
		}
		Runnable outside = () -> {
			if (passOpen()) {
				suspendVitrailPass();
			}
			if (passOpen()) {
				sayOpenPass();
				return;
			}
			action.run();
		};
		MethodHandle sulkan = sulkanOutside();
		if (sulkan == null) {
			outside.run();
			return;
		}
		try {
			sulkan.invokeExact(outside);
		} catch (Throwable e) {
			sulkanOutside = null;
			RealisticWater.LOGGER.warn("Sulkan's SceneRenderPass.outside failed; drawing without it", e);
			outside.run();
		}
	}

	private static boolean passOpen() {
		return RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder encoder && encoder.isInRenderPass();
	}

	private static void suspendVitrailPass() {
		if (!VITRAIL) {
			return;
		}
		lookUpVitrail();
		if (geometryFlush != null) {
			try {
				geometryFlush.invokeExact((Supplier<String>) () -> "Realistic Water fluids");
			} catch (Throwable e) {
				geometryFlush = null;
				RealisticWater.LOGGER.warn("Vitrail's GeometryHold.flush failed", e);
			}
		}
		if (!passOpen() || vitrailSuspend == null) {
			return;
		}
		try {
			vitrailSuspend.invokeExact();
		} catch (Throwable e) {
			vitrailSuspend = null;
			RealisticWater.LOGGER.warn("Vitrail's LevelPass.suspendCurrent failed", e);
		}
		if (!passOpen() || levelPassCurrent == null || levelPassUnder == null || levelPassSuspend == null) {
			return;
		}
		try {
			for (Object pass = levelPassCurrent.get(null); pass != null; pass = levelPassUnder.get(pass)) {
				levelPassSuspend.invoke(pass);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			levelPassSuspend = null;
			RealisticWater.LOGGER.warn("Couldn't step Vitrail's nested level passes aside", e);
		}
	}

	private static void sayOpenPass() {
		skippedFrames++;
		if (skippedFrames % 600 == 0) {
			RealisticWater.LOGGER.warn("Realistic fluids have skipped {} frames for an open render pass", skippedFrames);
		}
		if (openPassSaid) {
			return;
		}
		openPassSaid = true;
		String why = SULKAN && VITRAIL ? "Sulkan and Vitrail are both installed and leave a render pass open; use only one of them."
			: "another mod left a render pass open.";
		RealisticWater.LOGGER.warn("Realistic fluids skipped a frame: {}", why);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("Realistic Water can't draw: " + why));
		}
	}

	private static @Nullable MethodHandle sulkanOutside() {
		if (!SULKAN) {
			return null;
		}
		if (!sulkanLooked) {
			sulkanLooked = true;
			try {
				sulkanOutside = MethodHandles.publicLookup().findStatic(Class.forName(SULKAN_SCENE_PASS), "outside",
					MethodType.methodType(void.class, Runnable.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				RealisticWater.LOGGER.warn("Sulkan's SceneRenderPass.outside wasn't found", e);
			}
		}
		return sulkanOutside;
	}

	private static boolean vitrailPackRunning() {
		if (!VITRAIL) {
			return false;
		}
		lookUpVitrail();
		if (drawingPack == null) {
			return true;
		}
		try {
			return (boolean) drawingPack.invokeExact();
		} catch (Throwable e) {
			drawingPack = null;
			RealisticWater.LOGGER.warn("Couldn't ask Vitrail whether a pack is running; assuming one is", e);
			return true;
		}
	}

	private static void lookUpVitrail() {
		if (vitrailLooked) {
			return;
		}
		vitrailLooked = true;
		try {
			Class<?> chain = Class.forName(VITRAIL_CHAIN);
			drawingPack = MethodHandles.publicLookup().findStatic(chain, "drawingPack", MethodType.methodType(boolean.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			RealisticWater.LOGGER.warn("Vitrail's PackChain.drawingPack() wasn't found; fluids will always draw after the level", e);
		}
		try {
			try {
				geometryFlush = MethodHandles.publicLookup().findStatic(Class.forName(VITRAIL_GEOMETRY_HOLD), "flush",
					MethodType.methodType(void.class, Supplier.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				RealisticWater.LOGGER.info("Vitrail's GeometryHold.flush wasn't found ({})", e.toString());
			}
			Class<?> levelPass = Class.forName(VITRAIL_LEVEL_PASS);
			vitrailSuspend = MethodHandles.publicLookup().findStatic(levelPass, "suspendCurrent", MethodType.methodType(void.class));
			try {
				levelPassCurrent = levelPass.getDeclaredField("current");
				levelPassCurrent.setAccessible(true);
				levelPassUnder = levelPass.getDeclaredField("under");
				levelPassUnder.setAccessible(true);
				levelPassSuspend = levelPass.getDeclaredMethod("suspend");
				levelPassSuspend.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				levelPassSuspend = null;
				RealisticWater.LOGGER.info("Vitrail's nested level passes aren't reachable ({})", e.toString());
			}
		} catch (ReflectiveOperationException | LinkageError e) {
			RealisticWater.LOGGER.warn("Vitrail's LevelPass.suspendCurrent() wasn't found", e);
		}
		try {
			Class<?> gameRender = Class.forName(VITRAIL_GAME_RENDER);
			featuresColour = gameRender.getDeclaredField("featuresColour");
			featuresColour.setAccessible(true);
			featuresDepth = gameRender.getDeclaredField("featuresDepth");
			featuresDepth.setAccessible(true);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			featuresColour = null;
			featuresDepth = null;
			RealisticWater.LOGGER.info("Vitrail's feature layer isn't reachable; 'Inside Vitrail packs' will draw on top instead ({})", e.toString());
		}
	}

	private static @Nullable GpuTextureView read(@Nullable Field field) {
		if (field == null) {
			return null;
		}
		try {
			return field.get(null) instanceof GpuTextureView view ? view : null;
		} catch (IllegalAccessException e) {
			return null;
		}
	}
}
