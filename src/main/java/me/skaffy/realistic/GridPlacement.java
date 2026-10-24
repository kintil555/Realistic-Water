package me.skaffy.realistic;

import me.skaffy.block.WaveMakerBlock;
import me.skaffy.mixin.StandingAndWallBlockItemAccessor;
import net.minecraft.core.Direction;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

final class GridPlacement {
	private GridPlacement() {
	}

	static @Nullable BlockState stateFor(Block block, Direction face, Vector3d hit, Vector3d look) {
		Direction horizontal = Math.abs(look.x) > Math.abs(look.z) ? (look.x > 0 ? Direction.EAST : Direction.WEST) : (look.z > 0 ? Direction.SOUTH : Direction.NORTH);
		Direction nearest = Direction.getApproximateNearest(look.x, look.y, look.z);
		boolean upperHalf = face == Direction.DOWN || face != Direction.UP && hit.y > 0.5;
		if (block.asItem() instanceof StandingAndWallBlockItem item) {
			if (face == Direction.UP) {
				return block.defaultBlockState();
			}
			if (face == Direction.DOWN) {
				return null;
			}
			BlockState wall = ((StandingAndWallBlockItemAccessor) item).realisticWater$wallBlock().defaultBlockState();
			return wall.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? wall.setValue(BlockStateProperties.HORIZONTAL_FACING, face) : wall;
		}
		BlockState state = block.defaultBlockState();
		if (block instanceof SlabBlock) {
			return state.setValue(SlabBlock.TYPE, upperHalf ? SlabType.TOP : SlabType.BOTTOM);
		}
		if (block instanceof StairBlock) {
			return state.setValue(StairBlock.FACING, horizontal).setValue(StairBlock.HALF, upperHalf ? Half.TOP : Half.BOTTOM);
		}
		if (block instanceof DiodeBlock) {
			return state.setValue(DiodeBlock.FACING, horizontal.getOpposite());
		}
		if (block instanceof PistonBaseBlock) {
			return state.setValue(PistonBaseBlock.FACING, nearest.getOpposite()).setValue(PistonBaseBlock.EXTENDED, false);
		}
		if (block instanceof WaveMakerBlock) {
			return state.setValue(WaveMakerBlock.FACING, WaveMakerBlock.placedFacing(nearest));
		}
		if (state.hasProperty(BlockStateProperties.AXIS)) {
			return state.setValue(BlockStateProperties.AXIS, face.getAxis());
		}
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return state.setValue(BlockStateProperties.HORIZONTAL_FACING, horizontal.getOpposite());
		}
		if (state.hasProperty(BlockStateProperties.FACING)) {
			return state.setValue(BlockStateProperties.FACING, nearest.getOpposite());
		}
		return state;
	}
}
