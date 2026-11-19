package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.block.BranchJointBlock;
import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import com.yayuy.overgrownvegetation.block.SigillariaConeBlock;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.block.TreeFernFrondBlock;
import com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 木生シダ(プサロニウス)の木(2026-11-01)。高さ 8〜15 の中層を埋める役(コルダイテス・封印木の下)。形はユーザー決定「A と C を混ぜる」:
 * <ul>
 *     <li><b>A 一本幹 + 傘</b>: 幹 1 本、頂に芯の 3×3、1 段下がった腕先の芯 4 つ(十字の先。冠の半径 3)</li>
 *     <li><b>C 株立ち</b>({@code clump_chance}): 高い幹と低い幹を斜めに 1 マス空けて寄り添わせる。各冠は十字の芯 + 外へ下がる腕先 2 つ</li>
 * </ul>
 * 幹の下 {@code base_height} 段と根元の張り出しは太い根の絵({@link TreeFernTrunkBlock#BASE})。芯の外の 1 層は向き付きの板
 * ({@link TreeFernFrondBlock}。封印木の板と同じ仕組み): 芯の下 = 垂れる古い葉 > 頂の芯の上 = ゼンマイ > 横 = 横へ伸びる羽状の葉 > 角。
 * 角は既定で頂の段だけ({@code corners_top_only})。
 *
 * <p>封印木と違い幹は上書きしない: 幹の列の空き(空気か置き換えられる物)を下から数え、冠と頂の芽が収まる高さまで縮める。
 * {@code min_height} より低くしか立てられなければ生えない(C の 2 本目が立たなければ A にする)。葉はほかの木の原木・葉を上書きしない。
 * 苗木からは生えない: {@code CorruptionSpreader} が苗木を植える代わりに直接置く(仮決め 15%)。
 */
public class TreeFernFeature extends Feature<TreeFernFeature.Config> {

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    public TreeFernFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Config config = context.config();
        BlockPos origin = context.origin();
        // 構造物にかかる所の苗は構造物に合わせた形で育つ(11-19 構造物と森の融合 段 2b。欠けて浮いた木を作らない)
        Boolean fusionTree = com.yayuy.overgrownvegetation.organ.StructureSaplingTrees.tryGrow(level, origin, random);
        if (fusionTree != null) return fusionTree;
        if (TreePlacementRules.isEnclosed(level, origin)) {
            return false; // 高さ 8 以上なので洞窟の中には立てない
        }
        Tree tree = new Tree(level, config, origin);
        boolean built = random.nextDouble() < config.clumpChance() && buildClump(tree, random);
        if (!built) {
            tree.reset();
            built = buildSingle(tree, random);
        }
        if (!built) {
            return false;
        }
        tree.place(random);
        return true;
    }

    /** A: 一本幹 + 傘。 */
    private static boolean buildSingle(Tree tree, RandomSource random) {
        Config c = tree.config;
        int h = tree.fit(0, 0, between(random, c.singleMinHeight(), c.singleMaxHeight()));
        if (h < c.minHeight()) {
            return false;
        }
        tree.trunk(0, 0, h);
        // 根元: 1 段目は十字、2 段目は隣り合う 2 方向(偏らせて板根らしく)
        for (Direction d : SIDES) {
            tree.flare(d.getStepX(), 0, d.getStepZ());
        }
        Direction d1 = SIDES[random.nextInt(4)];
        Direction d2 = random.nextBoolean() ? d1.getClockWise() : d1.getCounterClockWise();
        tree.flare(d1.getStepX(), 1, d1.getStepZ());
        tree.flare(d2.getStepX(), 1, d2.getStepZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                tree.core(dx, h, dz, true);
            }
        }
        for (Direction d : SIDES) {
            if (random.nextDouble() < c.armChance()) {
                tree.core(d.getStepX() * 2, h - 1, d.getStepZ() * 2, false);
            }
        }
        tree.apex(0, h, 0);
        return true;
    }

    /** C: 2 本の株立ち。下絵の向き(高い方 (0,0)・低い方 (2,1))を 4 通りに回す。 */
    private static boolean buildClump(Tree tree, RandomSource random) {
        Config c = tree.config;
        tree.rotation = random.nextInt(4);
        int ht = tree.fit(0, 0, between(random, c.clumpTallMinHeight(), c.clumpTallMaxHeight()));
        int hs = tree.fit(2, 1, between(random, c.clumpShortMinHeight(), c.clumpShortMaxHeight()));
        if (hs < c.minHeight() || ht < hs + 2) {
            return false;
        }
        tree.trunk(0, 0, ht);
        tree.trunk(2, 1, hs);
        // 根元は 2 本まとめて太く(1 段目 5×4 の角を落とす、2〜3 段目は 2 本の間を埋める)
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 2; z++) {
                if ((x == -1 || x == 3) && (z == -1 || z == 2)) {
                    continue;
                }
                tree.flare(x, 0, z);
            }
        }
        tree.flare(1, 1, 0);
        tree.flare(1, 1, 1);
        tree.flare(0, 1, 1);
        tree.flare(2, 1, 0);
        tree.flare(1, 2, 1);
        // 高い方: 頂の十字、腕先は外(−x と −z)へ下がる
        tree.crossCrown(0, ht, 0);
        // 低い方: 頂の十字、腕先は +x と +z へ下がる(高い方を避けて外へ)
        tree.crossCrown(2, hs, 1);
        tree.core(-2, ht - 1, 0, false);
        tree.core(0, ht - 1, -2, false);
        tree.core(4, hs - 1, 1, false);
        tree.core(2, hs - 1, 3, false);
        tree.apex(0, ht, 0);
        tree.apex(2, hs, 1);
        return true;
    }

    private static int between(RandomSource random, int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + random.nextInt(hi - lo + 1);
    }

    /** 置く前の木 1 本(形の座標は origin からの相対で、{@link #rotation} で回す)。 */
    private static final class Tree {
        final WorldGenLevel level;
        final Config config;
        final BlockPos origin;
        int rotation;
        /** 幹のマス → 根元の絵か。 */
        final Long2BooleanOpenHashMap trunk = new Long2BooleanOpenHashMap();
        /** 芯のマス → 冠の頂の段か(角の板を頂の段だけにするため)。 */
        final Long2BooleanOpenHashMap core = new Long2BooleanOpenHashMap();
        final LongOpenHashSet apex = new LongOpenHashSet();
        final List<BlockPos> columns = new ArrayList<>();

        Tree(WorldGenLevel level, Config config, BlockPos origin) {
            this.level = level;
            this.config = config;
            this.origin = origin;
        }

        void reset() {
            rotation = 0;
            trunk.clear();
            core.clear();
            apex.clear();
            columns.clear();
        }

        BlockPos at(int x, int y, int z) {
            int rx = x, rz = z;
            for (int i = 0; i < rotation; i++) {
                int t = rx;
                rx = -rz;
                rz = t;
            }
            return origin.offset(rx, y, rz);
        }

        /**
         * 幹 {@code (x,z)} を高さ {@code want} で立てたいとき、立てられる高さ(幹の列の空きが冠 1 段と頂の芽 1 段の上まで続く所まで縮める)。
         * 根元(y = 0)が空いていなければ 0。
         */
        int fit(int x, int z, int want) {
            int free = 0;
            while (free < want + 2 && free(at(x, free, z))) {
                free++;
            }
            return Math.min(want, free - 2);
        }

        boolean free(BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return (s.isAir() || s.canBeReplaced()) && !TreePlacementRules.treeKeeps(s);
        }

        void trunk(int x, int z, int h) {
            for (int y = 0; y < h; y++) {
                trunk.put(at(x, y, z).asLong(), y < config.baseHeight());
            }
            columns.add(at(x, 0, z));
        }

        /** 根元の張り出し(空いている所だけ)。 */
        void flare(int x, int y, int z) {
            BlockPos p = at(x, y, z);
            if (!trunk.containsKey(p.asLong()) && free(p)) {
                trunk.put(p.asLong(), true);
            }
        }

        void core(int x, int y, int z, boolean top) {
            long k = at(x, y, z).asLong();
            if (!trunk.containsKey(k)) {
                core.put(k, top);
            }
        }

        void crossCrown(int x, int y, int z) {
            core(x, y, z, true);
            for (Direction d : SIDES) {
                core(x + d.getStepX(), y, z + d.getStepZ(), true);
            }
        }

        void apex(int x, int y, int z) {
            apex.add(at(x, y, z).asLong());
        }

        void place(RandomSource random) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockState log = config.trunkState();
            for (var e : trunk.long2BooleanEntrySet()) {
                p.set(e.getLongKey());
                BlockState s = log.hasProperty(TreeFernTrunkBlock.BASE) ? log.setValue(TreeFernTrunkBlock.BASE, e.getBooleanValue()) : log;
                if (s.hasProperty(RotatedPillarBlock.AXIS)) {
                    s = s.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
                }
                if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) continue; // 構造物の部品の箱には置かない(11-19 融合 段 1)
                level.setBlock(p, s, Block.UPDATE_ALL);
            }
            LongOpenHashSet placed = new LongOpenHashSet();
            for (long l : core.keySet()) {
                p.set(l);
                if (leaf(p, config.leavesState(), true)) {
                    placed.add(l);
                }
            }
            // 外の 1 層: 芯の上下と横 4 つ
            LongOpenHashSet fringe = new LongOpenHashSet();
            for (long l : placed) {
                BlockPos c = BlockPos.of(l);
                for (Direction d : Direction.values()) {
                    long n = c.relative(d).asLong();
                    if (!core.containsKey(n) && !trunk.containsKey(n)) {
                        fringe.add(n);
                    }
                }
            }
            for (long l : fringe) {
                p.set(l);
                Direction facing = fringeFacing(p, placed);
                if (facing == null || facing != Direction.DOWN && random.nextDouble() < config.fringeSkip()) {
                    continue;
                }
                leaf(p, frondState(facing, false), false);
            }
            // 角: 同じ高さの斜め(既定は冠の頂の段だけ)
            LongOpenHashSet corners = new LongOpenHashSet();
            for (long l : placed) {
                if (config.cornersTopOnly() && !core.get(l)) {
                    continue;
                }
                BlockPos c = BlockPos.of(l);
                for (Direction d : SIDES) {
                    long n = c.relative(d).relative(d.getClockWise()).asLong();
                    if (!core.containsKey(n) && !trunk.containsKey(n) && !fringe.contains(n)) {
                        corners.add(n);
                    }
                }
            }
            for (long l : corners) {
                if (random.nextDouble() < config.fringeSkip()) {
                    continue;
                }
                p.set(l);
                Direction facing = cornerFacing(p, placed);
                if (facing != null) {
                    leaf(p, frondState(facing, true), false);
                }
            }
        }

        private BlockState frondState(Direction facing, boolean corner) {
            BlockState s = config.frondState();
            if (!s.hasProperty(SigillariaFringeBlock.FACING)) {
                return s;
            }
            return s.setValue(SigillariaFringeBlock.FACING, facing).setValue(SigillariaFringeBlock.CORNER, corner);
        }

        /** 板の付け根の向き: 芯が上(垂れる)> 頂の芯が下(ゼンマイ)> 横(幹に近い向き)。接する芯が無ければ null。 */
        private Direction fringeFacing(BlockPos pos, LongOpenHashSet placedCore) {
            if (placedCore.contains(pos.above().asLong())) {
                return Direction.UP;
            }
            if (apex.contains(pos.below().asLong()) && placedCore.contains(pos.below().asLong())) {
                return Direction.DOWN;
            }
            Direction best = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction d : SIDES) {
                BlockPos n = pos.relative(d);
                if (placedCore.contains(n.asLong())) {
                    double dist = trunkDistSq(n);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = d;
                    }
                }
            }
            return best;
        }

        /** 角の板の向き(封印木と同じ): 斜めに接する芯が {@code f} と {@code f} の左隣の間にあるときの {@code f}。 */
        private Direction cornerFacing(BlockPos pos, LongOpenHashSet placedCore) {
            Direction best = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction f : SIDES) {
                BlockPos n = pos.relative(f).relative(f.getCounterClockWise());
                if (placedCore.contains(n.asLong())) {
                    double dist = trunkDistSq(n);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = f;
                    }
                }
            }
            return best;
        }

        private double trunkDistSq(BlockPos n) {
            double best = Double.MAX_VALUE;
            for (BlockPos c : columns) {
                double dx = n.getX() - c.getX(), dz = n.getZ() - c.getZ();
                best = Math.min(best, dx * dx + dz * dz);
            }
            return best;
        }

        /** 葉を置く。原木・幹・ほかの木の葉・球果・枝・根付いた萌芽体は上書きしない。外の層は空気か置き換えられる所にだけ。 */
        private boolean leaf(BlockPos pos, BlockState state, boolean forced) {
            BlockState here = level.getBlockState(pos);
            if (TreePlacementRules.treeKeeps(here) || here.is(BlockTags.LOGS) || here.getBlock() instanceof TreeFernTrunkBlock
                    || here.getBlock() instanceof OvergrownLeavesBlock || here.is(BlockTags.LEAVES)
                    || here.getBlock() instanceof SigillariaConeBlock || here.getBlock() instanceof BranchJointBlock) {
                return false;
            }
            if (!forced && !here.isAir() && !here.canBeReplaced()) {
                return false;
            }
            if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, pos)) {
                return false; // 構造物の部品の箱には置かない(11-19 融合 段 1)
            }
            level.setBlock(pos, state, Block.UPDATE_ALL);
            return true;
        }
    }

    /** 木生シダの設定(数はすべて仮決め 2026-11-01)。 */
    public record Config(
            BlockState trunkState,
            BlockState leavesState,
            BlockState frondState,
            double clumpChance,
            int singleMinHeight,
            int singleMaxHeight,
            int clumpTallMinHeight,
            int clumpTallMaxHeight,
            int clumpShortMinHeight,
            int clumpShortMaxHeight,
            int minHeight,
            int baseHeight,
            double armChance,
            double fringeSkip,
            boolean cornersTopOnly
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("trunk_state").forGetter(Config::trunkState),
                BlockState.CODEC.fieldOf("leaves_state").forGetter(Config::leavesState),
                BlockState.CODEC.fieldOf("frond_state").forGetter(Config::frondState),
                Codec.DOUBLE.optionalFieldOf("clump_chance", 1.0 / 3).forGetter(Config::clumpChance),
                Codec.INT.optionalFieldOf("single_min_height", 10).forGetter(Config::singleMinHeight),
                Codec.INT.optionalFieldOf("single_max_height", 13).forGetter(Config::singleMaxHeight),
                Codec.INT.optionalFieldOf("clump_tall_min_height", 12).forGetter(Config::clumpTallMinHeight),
                Codec.INT.optionalFieldOf("clump_tall_max_height", 13).forGetter(Config::clumpTallMaxHeight),
                Codec.INT.optionalFieldOf("clump_short_min_height", 8).forGetter(Config::clumpShortMinHeight),
                Codec.INT.optionalFieldOf("clump_short_max_height", 10).forGetter(Config::clumpShortMaxHeight),
                Codec.INT.optionalFieldOf("min_height", 6).forGetter(Config::minHeight),
                Codec.INT.optionalFieldOf("base_height", 3).forGetter(Config::baseHeight),
                Codec.DOUBLE.optionalFieldOf("arm_chance", 0.85).forGetter(Config::armChance),
                Codec.DOUBLE.optionalFieldOf("fringe_skip", 0.1).forGetter(Config::fringeSkip),
                Codec.BOOL.optionalFieldOf("corners_top_only", true).forGetter(Config::cornersTopOnly)
        ).apply(i, Config::new));

        /** 型(A / C)・角の置き方を決めた写し(GameTest と撮影用)。 */
        public Config fixed(boolean clump, boolean cornersTopOnly) {
            return new Config(trunkState, leavesState, frondState, clump ? 1.0 : 0.0, singleMinHeight, singleMaxHeight,
                    clumpTallMinHeight, clumpTallMaxHeight, clumpShortMinHeight, clumpShortMaxHeight, minHeight, baseHeight,
                    armChance, fringeSkip, cornersTopOnly);
        }
    }
}
