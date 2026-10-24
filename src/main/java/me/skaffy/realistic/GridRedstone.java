package me.skaffy.realistic;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.block.WaveMakerBlock;
import me.skaffy.physics.RigidBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.block.state.properties.RedstoneSide;

final class GridRedstone {
	private static final Direction[] DIRS = Direction.values();
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	private static final int PUSH_LIMIT = 12;
	private static final int TORCH_DELAY = 2;
	private static final int COMPARATOR_DELAY = 2;

	private final RealisticBlockEntity owner;
	private RigidBody body;
	private final Long2IntOpenHashMap strong = new Long2IntOpenHashMap();
	private final Long2IntOpenHashMap dust = new Long2IntOpenHashMap();
	private final Long2ObjectOpenHashMap<int[]> pending = new Long2ObjectOpenHashMap<>();
	private final Long2IntOpenHashMap comparatorOut = new Long2IntOpenHashMap();

	GridRedstone(RealisticBlockEntity owner) {
		this.owner = owner;
		this.body = owner.body;
	}

	static boolean isRedstone(BlockState s) {
		return s.is(Blocks.REDSTONE_BLOCK) || s.getBlock() instanceof RedstoneWireBlock || s.getBlock() instanceof RedstoneTorchBlock
			|| s.getBlock() instanceof DiodeBlock || s.getBlock() instanceof PistonBaseBlock || s.getBlock() instanceof PistonHeadBlock
			|| s.getBlock() instanceof WaveMakerBlock;
	}


	private static long key(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static long key(long k, Direction d) {
		return BlockPos.offset(k, d);
	}

	private BlockState state(long k) {
		RigidBody.Cell c = this.body.cellAt(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
		return c != null && c.payload instanceof BlockState s ? s : null;
	}

	private static boolean conductor(BlockState s) {
		return s != null && !(s.getBlock() instanceof RedstoneWireBlock) && s.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	private static boolean isDust(BlockState s) {
		return s != null && s.getBlock() instanceof RedstoneWireBlock;
	}

	private static boolean isTorch(BlockState s) {
		return s != null && s.getBlock() instanceof RedstoneTorchBlock;
	}

	private static Direction attached(BlockState s) {
		return s.getBlock() instanceof RedstoneWallTorchBlock ? s.getValue(RedstoneWallTorchBlock.FACING).getOpposite() : Direction.DOWN;
	}

	private static Direction output(BlockState s) {
		return s.getValue(DiodeBlock.FACING).getOpposite();
	}

	private int diodeLevel(long k, BlockState s) {
		if (!s.getValue(DiodeBlock.POWERED)) {
			return 0;
		}
		return s.getBlock() instanceof ComparatorBlock ? Math.max(this.comparatorOut.getOrDefault(k, 15), 1) : 15;
	}

	private int emit(long k, BlockState s, Direction d) {
		if (s == null) {
			return 0;
		}
		if (s.is(Blocks.REDSTONE_BLOCK)) {
			return 15;
		}
		if (isTorch(s)) {
			return s.getValue(RedstoneTorchBlock.LIT) && d != attached(s) ? 15 : 0;
		}
		if (s.getBlock() instanceof DiodeBlock) {
			return d == output(s) ? this.diodeLevel(k, s) : 0;
		}
		return 0;
	}

	private int strongInto(long k, BlockState s, Direction d) {
		if (s == null) {
			return 0;
		}
		if (isTorch(s)) {
			return s.getValue(RedstoneTorchBlock.LIT) && d == Direction.UP ? 15 : 0;
		}
		if (s.getBlock() instanceof DiodeBlock) {
			return d == output(s) ? this.diodeLevel(k, s) : 0;
		}
		return 0;
	}

	private static boolean dustPoints(BlockState s, Direction d) {
		if (d == Direction.DOWN) {
			return true;
		}
		if (d == Direction.UP) {
			return false;
		}
		boolean dot = true;
		for (Direction h : HORIZONTAL) {
			dot &= s.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(h)) == RedstoneSide.NONE;
		}
		return dot || s.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(d)) != RedstoneSide.NONE;
	}

	private int input(long k, Direction d) {
		long n = key(k, d);
		BlockState s = this.state(n);
		if (s == null) {
			return 0;
		}
		if (conductor(s)) {
			return Math.max(this.strong.get(n), this.weakFromDust(n));
		}
		if (isDust(s)) {
			return dustPoints(s, d.getOpposite()) ? this.dust.get(n) : 0;
		}
		return this.emit(n, s, d.getOpposite());
	}

	private int weakFromDust(long k) {
		int best = 0;
		for (Direction d : DIRS) {
			if (d == Direction.DOWN) {
				continue;
			}
			long n = key(k, d);
			BlockState s = this.state(n);
			if (isDust(s) && dustPoints(s, d.getOpposite())) {
				best = Math.max(best, this.dust.get(n));
			}
		}
		return best;
	}


	void tick() {
		this.body = this.owner.body;
		boolean any = false;
		for (RigidBody.Cell c : this.body.cells) {
			if (c.payload instanceof BlockState s && isRedstone(s)) {
				any = true;
			}
		}
		if (!any) {
			this.pending.clear();
			return;
		}
		this.strong.clear();
		this.dust.clear();
		for (RigidBody.Cell c : this.body.cells) {
			BlockState s = (BlockState) c.payload;
			if (!conductor(s)) {
				continue;
			}
			long k = key(c.x, c.y, c.z);
			int best = 0;
			for (Direction d : DIRS) {
				long n = key(k, d);
				best = Math.max(best, this.strongInto(n, this.state(n), d.getOpposite()));
			}
			if (best > 0) {
				this.strong.put(k, best);
			}
		}
		ArrayDeque<Long> queue = new ArrayDeque<>();
		for (RigidBody.Cell c : this.body.cells) {
			BlockState s = (BlockState) c.payload;
			if (!isDust(s)) {
				continue;
			}
			long k = key(c.x, c.y, c.z);
			int best = 0;
			for (Direction d : DIRS) {
				long n = key(k, d);
				BlockState ns = this.state(n);
				if (conductor(ns)) {
					best = Math.max(best, this.strong.get(n));
				} else if (!isDust(ns)) {
					best = Math.max(best, this.emit(n, ns, d.getOpposite()));
				}
			}
			this.dust.put(k, best);
			if (best > 1) {
				queue.add(k);
			}
		}
		while (!queue.isEmpty()) {
			long k = queue.poll();
			int level = this.dust.get(k) - 1;
			for (long n : this.dustNeighbours(k)) {
				if (this.dust.get(n) < level) {
					this.dust.put(n, level);
					queue.add(n);
				}
			}
		}

		Long2ObjectOpenHashMap<BlockState> changes = new Long2ObjectOpenHashMap<>();
		List<long[]> pistons = new ArrayList<>();
		for (RigidBody.Cell c : this.body.cells) {
			BlockState s = (BlockState) c.payload;
			long k = key(c.x, c.y, c.z);
			if (isDust(s)) {
				BlockState next = this.dustState(k, s).setValue(RedstoneWireBlock.POWER, this.dust.get(k));
				if (next != s) {
					changes.put(k, next);
				}
			} else if (s.getBlock() instanceof WaveMakerBlock) {
				int power = 0;
				for (Direction d : DIRS) {
					power = Math.max(power, this.input(k, d));
				}
				if (power != s.getValue(WaveMakerBlock.POWER)) {
					changes.put(k, s.setValue(WaveMakerBlock.POWER, power));
				}
			} else if (isTorch(s)) {
				boolean lit = this.input(k, attached(s)) <= 0;
				this.delay(changes, k, s, lit ? 1 : 0, s.getValue(RedstoneTorchBlock.LIT) ? 1 : 0, TORCH_DELAY,
					v -> s.setValue(RedstoneTorchBlock.LIT, v == 1));
			} else if (s.getBlock() instanceof RepeaterBlock) {
				boolean on = this.input(k, s.getValue(DiodeBlock.FACING)) > 0;
				this.delay(changes, k, s, on ? 1 : 0, s.getValue(DiodeBlock.POWERED) ? 1 : 0, 2 * s.getValue(RepeaterBlock.DELAY),
					v -> s.setValue(DiodeBlock.POWERED, v == 1));
			} else if (s.getBlock() instanceof ComparatorBlock) {
				Direction back = s.getValue(DiodeBlock.FACING);
				int rear = this.input(k, back);
				int side = Math.max(this.input(k, back.getClockWise()), this.input(k, back.getCounterClockWise()));
				int out = s.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT ? Math.max(rear - side, 0) : rear >= side ? rear : 0;
				int now = s.getValue(DiodeBlock.POWERED) ? this.comparatorOut.getOrDefault(k, 15) : 0;
				this.delay(changes, k, s, out, now, COMPARATOR_DELAY, v -> {
					this.comparatorOut.put(k, v);
					return s.setValue(DiodeBlock.POWERED, v > 0);
				});
			} else if (s.getBlock() instanceof PistonBaseBlock) {
				Direction facing = s.getValue(PistonBaseBlock.FACING);
				boolean powered = false;
				for (Direction d : DIRS) {
					powered |= d != facing && this.input(k, d) > 0;
				}
				if (powered != s.getValue(PistonBaseBlock.EXTENDED)) {
					pistons.add(new long[]{k, powered ? 1 : 0});
				}
			} else if (s.getBlock() instanceof PistonHeadBlock) {
				BlockState base = this.state(key(k, s.getValue(PistonHeadBlock.FACING).getOpposite()));
				if (base == null || !(base.getBlock() instanceof PistonBaseBlock) || !base.getValue(PistonBaseBlock.EXTENDED)) {
					pistons.add(new long[]{k, -1});
				}
			}
		}
		if (!changes.isEmpty()) {
			this.owner.setCellStates(changes);
		}
		for (long[] p : pistons) {
			if (this.piston(p[0], (int) p[1])) {
				break;
			}
		}
	}

	private interface StateFor {
		BlockState of(int value);
	}

	private void delay(Long2ObjectOpenHashMap<BlockState> changes, long k, BlockState s, int want, int now, int ticks, StateFor next) {
		if (want == now) {
			this.pending.remove(k);
			return;
		}
		int[] p = this.pending.get(k);
		if (p == null || p[0] != want) {
			this.pending.put(k, new int[]{want, ticks});
			return;
		}
		if (--p[1] <= 0) {
			this.pending.remove(k);
			BlockState n = next.of(want);
			if (n != s) {
				changes.put(k, n);
			}
		}
	}

	private List<Long> dustNeighbours(long k) {
		List<Long> out = new ArrayList<>(4);
		boolean coveredAbove = conductor(this.state(key(k, Direction.UP)));
		for (Direction h : HORIZONTAL) {
			long side = key(k, h);
			BlockState s = this.state(side);
			if (isDust(s)) {
				out.add(side);
			} else if (!conductor(s) && isDust(this.state(key(side, Direction.DOWN)))) {
				out.add(key(side, Direction.DOWN));
			} else if (conductor(s) && !coveredAbove && isDust(this.state(key(side, Direction.UP)))) {
				out.add(key(side, Direction.UP));
			}
		}
		return out;
	}

	private BlockState dustState(long k, BlockState s) {
		boolean coveredAbove = conductor(this.state(key(k, Direction.UP)));
		RedstoneSide[] sides = new RedstoneSide[4];
		int count = 0;
		for (int i = 0; i < 4; i++) {
			Direction h = HORIZONTAL[i];
			long side = key(k, h);
			BlockState n = this.state(side);
			RedstoneSide r = RedstoneSide.NONE;
			if (conductor(n) && !coveredAbove && isDust(this.state(key(side, Direction.UP)))) {
				r = RedstoneSide.UP;
			} else if (isDust(n) || !conductor(n) && isDust(this.state(key(side, Direction.DOWN))) || connects(n, h.getOpposite())) {
				r = RedstoneSide.SIDE;
			}
			sides[i] = r;
			if (r != RedstoneSide.NONE) {
				count++;
			}
		}
		if (count == 1) {
			for (int i = 0; i < 4; i++) {
				if (sides[i] != RedstoneSide.NONE && sides[(i + 2) % 4] == RedstoneSide.NONE) {
					sides[(i + 2) % 4] = RedstoneSide.SIDE;
					break;
				}
			}
		}
		BlockState out = s;
		for (int i = 0; i < 4; i++) {
			out = out.setValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(HORIZONTAL[i]), sides[i]);
		}
		return out;
	}

	private static boolean connects(BlockState s, Direction toward) {
		if (s == null) {
			return false;
		}
		if (s.getBlock() instanceof RepeaterBlock) {
			return s.getValue(DiodeBlock.FACING).getAxis() == toward.getAxis();
		}
		return s.is(Blocks.REDSTONE_BLOCK) || isTorch(s) || s.getBlock() instanceof ComparatorBlock || s.getBlock() instanceof PistonBaseBlock
			|| s.getBlock() instanceof WaveMakerBlock;
	}


	private boolean piston(long k, int action) {
		int x = BlockPos.getX(k);
		int y = BlockPos.getY(k);
		int z = BlockPos.getZ(k);
		BlockState s = this.state(k);
		if (s == null) {
			return false;
		}
		if (action < 0) {
			this.owner.removeCell(x, y, z);
			return true;
		}
		Direction facing = s.getValue(PistonBaseBlock.FACING);
		long front = key(k, facing);
		if (action == 0) {
			BlockState head = this.state(front);
			this.owner.setCellStates(singleton(k, s.setValue(PistonBaseBlock.EXTENDED, false)));
			if (head != null && head.getBlock() instanceof PistonHeadBlock) {
				this.owner.removeCell(BlockPos.getX(front), BlockPos.getY(front), BlockPos.getZ(front));
			}
			return true;
		}
		List<Long> line = new ArrayList<>();
		long at = front;
		while (this.state(at) != null) {
			BlockState in = this.state(at);
			if (line.size() >= PUSH_LIMIT || in.getBlock() instanceof PistonHeadBlock
				|| in.getBlock() instanceof PistonBaseBlock && in.getValue(PistonBaseBlock.EXTENDED)) {
				return false;
			}
			line.add(at);
			at = key(at, facing);
		}
		BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, facing).setValue(PistonHeadBlock.TYPE, PistonType.DEFAULT);
		this.owner.extendPiston(k, s.setValue(PistonBaseBlock.EXTENDED, true), front, head, line, facing, line.isEmpty());
		return true;
	}

	private static Long2ObjectOpenHashMap<BlockState> singleton(long k, BlockState s) {
		Long2ObjectOpenHashMap<BlockState> m = new Long2ObjectOpenHashMap<>();
		m.put(k, s);
		return m;
	}
}
