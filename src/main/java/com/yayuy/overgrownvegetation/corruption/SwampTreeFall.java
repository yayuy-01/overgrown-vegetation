package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 森が湿地・菌湿地帯に汚染されるとき、元からあった木を残さない。
 *
 * <p>湿地系の地域({@link OvergrowthRegionMap})でバニラの原木が汚染された瞬間に
 * ({@link CorruptionRegistry#corrupt(ServerLevel, BlockPos, BlockState, RandomSource)} から)その位置を
 * 積んでおき、毎 tick 少しずつ処理する。処理では原木の繋がり(木 1 本)とその葉を丸ごと集め、
 * <ul>
 *     <li>{@code swampFallenTreeChance} の確率で<b>倒木</b>にする: 一番下の原木 1 個だけを切り株として残し、
 *     幹の長さぶんの汚染された原木を、地面の起伏に沿ってランダムな水平方向へ横倒しに並べる
 *     (途中で崖や壁にぶつかればそこで止まる)。倒木の上面にはまれに薄い苔が付く。</li>
 *     <li>それ以外は<b>消滅</b>させる(原木も葉もすべて空気に)。</li>
 * </ul>
 * どちらも元の葉はすべて消す(このMODの葉は自然に枯れないため)。ドロップは出ない。
 *
 * <p>木の判定はバニラの {@code #minecraft:logs} と、既にその木の一部が汚染されて
 * {@link ModBlocks#OVERGROWN_LOG} / {@link ModBlocks#OVERGROWN_WOOD} になっている原木を繋がりとして辿る。
 * 湿地地域で苗木から育つ木は湿地の原木({@link ModBlocks#OVERGROWN_SWAMP_LOG})や菌類の柱なので
 * 対象にならない。プレイヤーの建築(原木を使った家)も繋がりとして辿ってしまうので、繋がりが
 * {@code MAX_LOGS} を超えたら「木ではない」と見なして何もしない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwampTreeFall {

    /** これより大きな原木の繋がりは木ではなく建築物と見なす。 */
    private static final int MAX_LOGS = 320;
    /** 葉は原木からこの距離までを木の一部と見なす(バニラの葉の減衰距離 + 余裕)。 */
    private static final int LEAF_REACH = 7;
    private static final int MAX_LEAVES = 4096;
    private static final int MIN_FALLEN_LENGTH = 3;
    private static final int MAX_FALLEN_LENGTH = 14;
    private static final int GROUND_SEARCH = 3;
    /** ドロップを出さずに置き換える(UPDATE_SUPPRESS_DROPS)。 */
    private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;

    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> QUEUE = new HashMap<>();

    /** 湿地系の地域でバニラの原木が汚染された。あとで木ごと処理する。 */
    public static void enqueue(ServerLevel level, BlockPos pos) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.swampTreeFall.get() && !cfg.jungleTreeFall.get()) {
            return;
        }
        QUEUE.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(pos.asLong());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LongLinkedOpenHashSet queue = QUEUE.get(level.dimension());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        int budget = Math.max(1, OvergrownVegetationConfig.COMMON.swampTreeFallPerTick.get());
        while (budget-- > 0 && !queue.isEmpty()) {
            long packed = queue.removeFirstLong();
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                continue;
            }
            processTree(level, pos, queue);
        }
    }

    private static void processTree(ServerLevel level, BlockPos start, LongLinkedOpenHashSet queue) {
        if (!isTreeLog(level.getBlockState(start))) {
            return;
        }
        // 環境ごとの有効/無効。湿地系は swampTreeFall、密林は jungleTreeFall(2026-09-11 に密林にも拡張)。
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        boolean swamp = OvergrowthRegionMap.blendedVariantAt(level, start.getX(), start.getZ(), 2).isSwamp();
        if (swamp ? !cfg.swampTreeFall.get() : !cfg.jungleTreeFall.get()) {
            return;
        }
        double fallenChance = swamp ? cfg.swampFallenTreeChance.get() : cfg.jungleFallenTreeChance.get();
        // 0. 生成ストラクチャー(村・前哨基地など)のピース内にある原木は、その建物の一部なので触らない。
        //    ピース単位の判定なので、村の中に自然に生えた木(建物の箱の外)は普通に木として扱われる。
        if (isInsideStructurePiece(level, start)) {
            return;
        }
        // 1. 原木の繋がりを集める。人工物の兆候(樹皮を剥いだ原木、木材・柵・階段などと隣接)があれば木ではない。
        LongOpenHashSet logs = new LongOpenHashSet();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        logs.add(start.asLong());
        BlockPos lowest = start;
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        while (!frontier.isEmpty()) {
            BlockPos p = frontier.poll();
            if (p.getY() < lowest.getY()) {
                lowest = p;
            }
            if (isStrippedLog(level.getBlockState(p))) {
                return; // 樹皮を剥いだ原木は自然には生えない = 建築物。
            }
            for (Direction dir : Direction.values()) {
                scratch.setWithOffset(p, dir);
                if (level.isLoaded(scratch) && isBuildingBlock(level.getBlockState(scratch))) {
                    return; // 木材・柵・階段・ドアなどに接している = 建築物。
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (logs.contains(n.asLong()) || !level.isLoaded(n)) {
                            continue;
                        }
                        if (isTreeLog(level.getBlockState(n))) {
                            if (isInsideStructurePiece(level, n)) {
                                return; // 繋がりの先がストラクチャーの建物に入っている。
                            }
                            logs.add(n.asLong());
                            frontier.add(n);
                            if (logs.size() > MAX_LOGS) {
                                return; // 建築物などの巨大な繋がり。触らない。
                            }
                        }
                    }
                }
            }
        }
        // 同じ木の他の原木が積まれていても二重処理しない。
        for (long l : logs) {
            queue.remove(l);
        }

        // 2. 葉を集める(原木から LEAF_REACH 以内を葉づたいに)。
        LongOpenHashSet leaves = new LongOpenHashSet();
        ArrayDeque<long[]> leafFrontier = new ArrayDeque<>();
        for (long l : logs) {
            leafFrontier.add(new long[]{l, 0});
        }
        LongOpenHashSet seen = new LongOpenHashSet(logs);
        while (!leafFrontier.isEmpty() && leaves.size() < MAX_LEAVES) {
            long[] cur = leafFrontier.poll();
            BlockPos p = BlockPos.of(cur[0]);
            int dist = (int) cur[1];
            if (dist >= LEAF_REACH) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                long nl = n.asLong();
                if (seen.contains(nl) || !level.isLoaded(n)) {
                    continue;
                }
                seen.add(nl);
                if (isTreeLeaves(level.getBlockState(n))) {
                    leaves.add(nl);
                    leafFrontier.add(new long[]{nl, dist + 1});
                }
            }
        }

        // 葉が 1 枚も付いていない原木の塊は木ではない(丸太置き場・柱など)。
        if (leaves.isEmpty()) {
            return;
        }

        // 3. 幹の長さ(切り株の列に積まれた原木の本数)を数えてから、全部消す。
        int trunkLength = 0;
        for (int y = lowest.getY(); y < lowest.getY() + 64; y++) {
            if (!logs.contains(BlockPos.asLong(lowest.getX(), y, lowest.getZ()))) {
                break;
            }
            trunkLength++;
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        for (long l : leaves) {
            level.setBlock(BlockPos.of(l), air, FLAGS);
        }
        for (long l : logs) {
            level.setBlock(BlockPos.of(l), air, FLAGS);
        }

        // 4. 倒木にする / 消滅。
        RandomSource random = level.getRandom();
        if (random.nextDouble() >= fallenChance) {
            return;
        }
        layFallenTrunk(level, lowest, Math.max(1, trunkLength), random);
    }

    /** 切り株 1 個を残し、幹の長さぶんの原木を横倒しに並べる。 */
    private static void layFallenTrunk(ServerLevel level, BlockPos stump, int trunkLength, RandomSource random) {
        BlockState upright = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
        level.setBlock(stump, upright, FLAGS);

        int length = Math.min(MAX_FALLEN_LENGTH, Math.max(MIN_FALLEN_LENGTH, trunkLength - 1));
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockState lying = upright.setValue(RotatedPillarBlock.AXIS, dir.getAxis());
        double mossChance = OvergrownVegetationConfig.COMMON.swampFallenTreeMossChance.get();

        int x = stump.getX();
        int y = stump.getY();
        int z = stump.getZ();
        LongArrayList placed = new LongArrayList();
        for (int i = 1; i <= length; i++) {
            x += dir.getStepX();
            z += dir.getStepZ();
            int groundY = findGroundY(level, x, y - 1, z);
            if (groundY == Integer.MIN_VALUE) {
                break;
            }
            int logY = groundY + 1;
            if (logY > y + 1) {
                break; // 登り坂にぶつかった(倒木は上へは伸びない)。
            }
            BlockPos at = new BlockPos(x, logY, z);
            BlockState existing = level.getBlockState(at);
            if (!existing.isAir() && !existing.canBeReplaced() && !existing.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                break;
            }
            level.setBlock(at, lying, FLAGS);
            placed.add(at.asLong());
            y = logY;
        }

        // 倒木の上面に薄い苔。
        if (mossChance > 0.0D) {
            for (long l : placed) {
                if (random.nextDouble() >= mossChance) {
                    continue;
                }
                BlockPos above = BlockPos.of(l).above();
                if (level.getBlockState(above).isAir()) {
                    BlockState moss = ModBlocks.OVERGROWN_MOSS_LAYER.get().defaultBlockState()
                            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), Boolean.TRUE);
                    if (moss.canSurvive(level, above)) {
                        level.setBlock(above, moss, FLAGS);
                    }
                }
            }
        }
    }

    /**
     * {@code (x, z)} 列で {@code centerY ± GROUND_SEARCH} の範囲にある最上部の「動きを止めるブロック」(水面含む)の
     * Y。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int findGroundY(ServerLevel level, int x, int centerY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = centerY + GROUND_SEARCH; y >= centerY - GROUND_SEARCH; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() && !state.canBeReplaced()) {
                return y;
            }
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                return y - 1; // 水面: 倒木は水の中(水面の高さ)に横たわる。
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 生成ストラクチャーのピース(建物 1 棟ぶんの箱)の中か。 */
    private static boolean isInsideStructurePiece(ServerLevel level, BlockPos pos) {
        return level.structureManager().getStructureWithPieceAt(pos, holder -> true).isValid();
    }

    /** 樹皮を剥いだ原木・木(stripped_*)か。自然の木には現れない。 */
    private static boolean isStrippedLog(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
    }

    /** 建築に使われるブロック(木材・柵・階段・ハーフブロック・ドア・トラップドア・壁・ガラス・松明など)か。 */
    private static boolean isBuildingBlock(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.WALLS) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.CANDLES) || state.is(BlockTags.BANNERS)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.CHEST) || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.LADDER) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.BELL)
                || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.BRICKS);
    }

    private static boolean isTreeLog(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(ModBlocks.OVERGROWN_LOG.get()) || state.is(ModBlocks.OVERGROWN_WOOD.get());
    }

    private static boolean isTreeLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(ModBlocks.OVERGROWN_LEAVES.get());
    }

    private SwampTreeFall() {
    }
}
