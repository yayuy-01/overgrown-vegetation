package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import com.yayuy.overgrownvegetation.block.SigillariaConeBlock;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 封印木(シギラリア)の木(2026-11-01)。形はユーザー指定の 1881 年の復元図(Popular Science Monthly)。数はすべて仮決めで {@link Config}
 * ({@code configured_feature/sigillaria_tree.json})にある。
 * <ul>
 *     <li><b>腕の型</b>: 幹(太さ 1、根元 2 段は十字の板根)を高さ H の {@code fork_min}〜{@code fork_max} まで上げ、そこから 3〜4 本の腕。
 *     腕の階段の隙間と腕先の先細りは今の木と同じ枝の継ぎ目({@link BranchJoints}・{@code sigillaria_branch})。
 *     腕は水平の向きを等分(3 本なら 120°、4 本なら 90°)にずらし、1 歩で横 1・上 {@code arm_min_rise}〜{@code arm_max_rise} の急な斜め上へ、
 *     長さ H の {@code arm_min_length}〜{@code arm_max_length}。腕の上半分を葉で包み(芯は原木の四方の十字・外 1 層が細い葉。3×3 にすると隣の腕と塊になってブロッコリーに見えた)、
 *     腕先の上に房 2〜3 段。腕先の 2〜4 段下の側面に球果</li>
 *     <li><b>柱の型</b>({@code pillar_chance}): 枝分かれ無しの幹、頂に筒状の房(芯 3×3 + 外 1 層 = 幅 5)、房の下の幹に球果</li>
 * </ul>
 * {@link ForcedTreeFeature} と同じく原木は何があっても置き換える(根付いた萌芽体だけ残す)。葉はほかの木の原木・葉を上書きしない。
 * 蔦({@link TreeVineConnector})は巻かない(葉に包まれた腕と干渉するので。仮決め)。木を植える間隔・林冠の穴は植える側
 * ({@code CorruptionSpreader})が今の木と同じ設定で決める。
 */
public class SigillariaTreeFeature extends Feature<SigillariaTreeFeature.Config> {

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    public SigillariaTreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Config config = context.config();
        BlockPos origin = context.origin();

        boolean pillar = random.nextDouble() < config.pillarChance();
        int height = pillar ? between(random, config.pillarMinHeight(), config.pillarMaxHeight())
                : between(random, config.armMinHeight(), config.armMaxHeight());
        if (height > TreePlacementRules.CAVE_MAX_TREE_HEIGHT && TreePlacementRules.isEnclosed(level, origin)) {
            return false;
        }
        Tree tree = new Tree(level, config, origin);
        placeButtress(tree, origin);
        if (pillar) {
            placePillar(tree, random, origin, height);
        } else {
            placeArms(tree, random, origin, height);
        }
        tree.placeLeaves(random);
        tree.placeCones(random);
        // 継ぎ目の腕は葉・球果まで置き終えてから組み直す(生成中は隣の updateShape が来ないことがある)
        BranchJoints.reconnect(level, tree.joints);
        // 側面・底面に生えるもの(2026-11-01)。葉の座標は持たないので原木のまわりを探す。
        TreeSideGrowth.decorate(level, random, tree.logs, null, false);
        return true;
    }

    /** 根元 2 段の十字(板根の気配。仮決め)。 */
    private static void placeButtress(Tree tree, BlockPos origin) {
        for (int dy = 0; dy < tree.config.buttressHeight(); dy++) {
            for (Direction d : SIDES) {
                tree.log(origin.above(dy).relative(d));
            }
        }
    }

    private static void placePillar(Tree tree, RandomSource random, BlockPos origin, int height) {
        for (int i = 0; i < height; i++) {
            tree.log(origin.above(i));
        }
        BlockPos top = origin.above(height - 1);
        int crown = between(random, tree.config.pillarCrownMin(), tree.config.pillarCrownMax());
        // 房は幹の上 crown - 2 段を 3×3 で包み、頂の上に十字 1 段と芯 1 つ(外 1 層は細い葉。幅 5)。
        for (int i = 0; i < crown - 2; i++) {
            tree.coreRing(top.below(i), true);
        }
        tree.coreRing(top.above(), false);
        tree.core(top.above(2));
        int crownBottom = top.getY() - (crown - 3);
        int cones = between(random, tree.config.pillarConesMin(), tree.config.pillarConesMax());
        for (int i = 0; i < cones; i++) {
            tree.coneAt(new BlockPos(origin.getX(), crownBottom - 1 - random.nextInt(3), origin.getZ()));
        }
    }

    private static void placeArms(Tree tree, RandomSource random, BlockPos origin, int height) {
        int fork = Math.max(2, Math.round(height * (float) Mth.nextDouble(random, tree.config.forkMin(), tree.config.forkMax())));
        for (int i = 0; i < fork; i++) {
            tree.log(origin.above(i));
        }
        BlockPos forkTop = origin.above(fork - 1);
        int count = between(random, tree.config.armMinCount(), tree.config.armMaxCount());
        double step = Math.PI * 2 / count;
        double angle = random.nextDouble() * Math.PI * 2;
        for (int a = 0; a < count; a++) {
            // 等分の向きから ±8° ずらす(隣の腕とは 90〜120° 前後)。
            double dir = angle + a * step + (random.nextDouble() - 0.5) * Math.toRadians(16);
            double cos = Math.cos(dir), sin = Math.sin(dir);
            double rise = Mth.nextDouble(random, tree.config.armMinRise(), tree.config.armMaxRise());
            int length = Math.max(3, Math.round(height * (float) Mth.nextDouble(random, tree.config.armMinLength(), tree.config.armMaxLength())));
            List<BlockPos> arm = new ArrayList<>(length);
            for (int s = 1; s <= length; s++) {
                double d = (s + 1.0) / rise; // 1 歩目から開き始める(0.5 では最初の 1〜2 段が幹の続きになった)
                arm.add(new BlockPos(forkTop.getX() + (int) Math.round(cos * d), forkTop.getY() + s, forkTop.getZ() + (int) Math.round(sin * d)));
            }
            tree.placeArm(forkTop, arm);
            // 上半分を葉で包む(芯は十字)。腕先の上に房 2〜3 段。
            for (int s = length / 2; s < length; s++) {
                tree.coreRing(arm.get(s), false);
            }
            BlockPos tip = arm.get(length - 1);
            int tuft = 2 + random.nextInt(2);
            tree.coreRing(tip.above(), false);
            for (int t = 2; t <= tuft; t++) {
                tree.core(tip.above(t));
            }
            if (random.nextDouble() < tree.config.armConeChance()) {
                int cones = between(random, tree.config.armConesMin(), tree.config.armConesMax());
                for (int i = 0; i < cones; i++) {
                    int s = length - 1 - (2 + random.nextInt(3));
                    if (s >= 0) {
                        tree.coneAt(arm.get(s));
                    }
                }
            }
        }
    }

    private static int between(RandomSource random, int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + random.nextInt(hi - lo + 1);
    }

    /** 1 本の木を置く間に置いた所を覚える(葉はほかの木と見分け、外の 1 層は芯の外にだけ置く)。 */
    private static final class Tree {
        final WorldGenLevel level;
        final Config config;
        final BlockPos origin;
        final LongOpenHashSet logs = new LongOpenHashSet();
        final LongOpenHashSet core = new LongOpenHashSet();
        final List<BlockPos> coneLogs = new ArrayList<>();
        final List<BlockPos> joints = new ArrayList<>();

        Tree(WorldGenLevel level, Config config, BlockPos origin) {
            this.level = level;
            this.config = config;
            this.origin = origin;
        }

        void log(BlockPos pos) {
            put(pos, config.trunkState());
        }

        /** 原木か枝の継ぎ目を置き、葉に上書きされないよう覚える。 */
        void put(BlockPos pos, BlockState state) {
            logs.add(pos.asLong());
            if (!TreePlacementRules.treeKeeps(level.getBlockState(pos))) {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }
        }

        /**
         * 腕 1 本({@code arm} = 根元 → 先の原木のマス)を、今の木と同じ枝の継ぎ目の計画({@link BranchJoints}。設定 {@code branchJointFill}・{@code branchJointTaper})で置く:
         * 階段の隙間を継ぎ目で面で繋ぎ、腕先の 1〜2 マスを細くする(2026-11-01 ユーザー決定「今の木と封印木の両方に使う」)。
         * 隙間埋めを切っているときは、前の作り(横へずれた段の高さに原木を足す)で面で繋ぐ。
         */
        void placeArm(BlockPos forkTop, List<BlockPos> arm) {
            var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
            boolean fill = cfg.branchJointFill.get();
            List<BlockPos> path = new ArrayList<>(arm.size() + 1);
            path.add(forkTop);
            path.addAll(arm);
            BranchJoints.Plan plan = BranchJoints.plan(path, cfg.branchJointTaper.get(), fill,
                    p -> { BlockState s = level.getBlockState(p); return s.isAir() || s.canBeReplaced(); },
                    p -> logs.contains(p.asLong()) || com.yayuy.overgrownvegetation.block.BranchJointBlock.connectsTo(level.getBlockState(p)));
            if (!fill) {
                BlockPos prev = forkTop;
                for (BlockPos cell : arm) {
                    if (cell.getX() != prev.getX()) {
                        log(new BlockPos(cell.getX(), prev.getY(), prev.getZ()));
                    }
                    if (cell.getX() != prev.getX() || cell.getZ() != prev.getZ()) {
                        log(new BlockPos(cell.getX(), prev.getY(), cell.getZ()));
                    }
                    prev = cell;
                }
            }
            joints.addAll(BranchJoints.place(level, plan, config.trunkState(), ModBlocks.SIGILLARIA_BRANCH.get().defaultBlockState(), this::put));
        }

        void core(BlockPos pos) {
            if (!logs.contains(pos.asLong())) {
                core.add(pos.asLong());
            }
        }

        /** {@code center} のまわりの芯の葉(3×3 か十字)。真ん中は原木なら残す。 */
        void coreRing(BlockPos center, boolean square) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!square && dx != 0 && dz != 0) {
                        continue;
                    }
                    core(center.offset(dx, 0, dz));
                }
            }
        }

        void coneAt(BlockPos logPos) {
            coneLogs.add(logPos.immutable());
        }

        void placeLeaves(RandomSource random) {
            LongOpenHashSet placed = new LongOpenHashSet();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (long l : core) {
                p.set(l);
                if (leaf(p, config.leavesState(), true)) {
                    placed.add(l);
                }
            }
            // 外の 1 層: 芯の上下と横 4 つ(芯から生える板の葉。2 割は抜いて輪郭を崩す)。下は 11-01 昼の試作から(垂れる葉)。
            LongOpenHashSet fringe = new LongOpenHashSet();
            for (long l : core) {
                BlockPos c = BlockPos.of(l);
                for (Direction d : Direction.values()) {
                    long n = c.relative(d).asLong();
                    if (!core.contains(n) && !logs.contains(n)) {
                        fringe.add(n);
                    }
                }
            }
            for (long l : fringe) {
                if (random.nextInt(5) != 0) {
                    p.set(l);
                    Direction facing = fringeFacing(p, placed);
                    if (facing != null) {
                        leaf(p, fringeState(facing, false), false);
                    }
                }
            }
            // 芯の角(平面で斜め・面で接する芯が無いマス): 芯の縦の辺から開く葉で、梢が四角い柱に見えるのを和らげる(2026-11-01 昼。抜く割合は横と同じ 2 割、仮決め)
            LongOpenHashSet corners = new LongOpenHashSet();
            for (long l : placed) {
                BlockPos c = BlockPos.of(l);
                for (Direction d : SIDES) {
                    long n = c.relative(d).relative(d.getClockWise()).asLong();
                    if (!core.contains(n) && !logs.contains(n) && !fringe.contains(n)) {
                        corners.add(n);
                    }
                }
            }
            for (long l : corners) {
                if (random.nextInt(5) != 0) {
                    p.set(l);
                    Direction facing = cornerFacing(p, placed);
                    if (facing != null) {
                        leaf(p, fringeState(facing, true), false);
                    }
                }
            }
        }

        private BlockState fringeState(Direction facing, boolean corner) {
            BlockState s = config.fringeState();
            return s.hasProperty(SigillariaFringeBlock.FACING)
                    ? s.setValue(SigillariaFringeBlock.FACING, facing).setValue(SigillariaFringeBlock.CORNER, corner) : s;
        }

        /**
         * 角の葉の向き: 斜めに接する芯が {@code f} と {@code f} の左隣の間(north なら北西)にあるときの {@code f}。
         * 芯が複数の斜めにあれば幹に近いもの。無ければ null。
         */
        private Direction cornerFacing(BlockPos pos, LongOpenHashSet placedCore) {
            Direction best = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction f : SIDES) {
                BlockPos n = pos.relative(f).relative(f.getCounterClockWise());
                if (placedCore.contains(n.asLong())) {
                    double dx = n.getX() - origin.getX(), dz = n.getZ() - origin.getZ();
                    if (dx * dx + dz * dz < bestDist) {
                        bestDist = dx * dx + dz * dz;
                        best = f;
                    }
                }
            }
            return best;
        }

        /**
         * 外の葉の付け根の向き = 接している芯(置けた芯の葉・原木・枝)の向き。下 > 上 > 横(横が複数なら幹に近い向き)。接する芯が無ければ null(置かない)。
         * 芯が下 = 立ち上がる房、上 = 垂れる葉、横 = 芯の面から開く V 字({@link SigillariaFringeBlock})。
         */
        private Direction fringeFacing(BlockPos pos, LongOpenHashSet placedCore) {
            if (attaches(pos.below(), placedCore)) {
                return Direction.DOWN;
            }
            if (attaches(pos.above(), placedCore)) {
                return Direction.UP;
            }
            Direction best = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction d : SIDES) {
                BlockPos n = pos.relative(d);
                if (attaches(n, placedCore)) {
                    double dx = n.getX() - origin.getX(), dz = n.getZ() - origin.getZ();
                    if (dx * dx + dz * dz < bestDist) {
                        bestDist = dx * dx + dz * dz;
                        best = d;
                    }
                }
            }
            return best;
        }

        private boolean attaches(BlockPos pos, LongOpenHashSet placedCore) {
            return placedCore.contains(pos.asLong()) || logs.contains(pos.asLong());
        }

        /** 葉を置く。原木・ほかの木の葉・根付いた萌芽体は上書きしない。外の層は空気か置き換えられる所にだけ。 */
        private boolean leaf(BlockPos pos, BlockState state, boolean forced) {
            BlockState here = level.getBlockState(pos);
            if (TreePlacementRules.treeKeeps(here) || here.is(BlockTags.LOGS) || here.is(ModBlocks.GIANT_OVERGROWN_LOG.get())
                    || here.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock
                    || here.is(ModBlocks.GIANT_OVERGROWN_CAMBIUM.get()) || here.getBlock() instanceof OvergrownLeavesBlock
                    || here.is(BlockTags.LEAVES) || here.getBlock() instanceof SigillariaConeBlock) {
                return false;
            }
            if (!forced && !here.isAir() && !here.canBeReplaced()) {
                return false;
            }
            level.setBlock(pos, state, Block.UPDATE_ALL);
            return true;
        }

        /** 球果: 原木の空いた側面(葉か空気)に付け、外へ 1 つ先の細い葉は抜いて覗かせる。 */
        void placeCones(RandomSource random) {
            for (BlockPos logPos : coneLogs) {
                if (!level.getBlockState(logPos).is(config.trunkState().getBlock())) {
                    continue;
                }
                int start = random.nextInt(4);
                for (int k = 0; k < 4; k++) {
                    Direction d = SIDES[(start + k) % 4];
                    BlockPos at = logPos.relative(d);
                    BlockState here = level.getBlockState(at);
                    if (logs.contains(at.asLong()) || !(here.isAir() || here.is(config.leavesState().getBlock()) || here.is(config.fringeState().getBlock()))) {
                        continue;
                    }
                    level.setBlock(at, config.coneState().setValue(SigillariaConeBlock.FACING, d), Block.UPDATE_ALL);
                    BlockPos out = at.relative(d);
                    if (level.getBlockState(out).is(config.fringeState().getBlock())) {
                        level.setBlock(out, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    break;
                }
            }
        }
    }

    /** 封印木の設定(数はすべて仮決め 2026-11-01)。 */
    public record Config(
            BlockState trunkState,
            BlockState leavesState,
            BlockState fringeState,
            BlockState coneState,
            double pillarChance,
            int armMinHeight,
            int armMaxHeight,
            double forkMin,
            double forkMax,
            int armMinCount,
            int armMaxCount,
            double armMinRise,
            double armMaxRise,
            double armMinLength,
            double armMaxLength,
            double armConeChance,
            int armConesMin,
            int armConesMax,
            int pillarMinHeight,
            int pillarMaxHeight,
            int pillarCrownMin,
            int pillarCrownMax,
            int pillarConesMin,
            int pillarConesMax,
            int buttressHeight
    ) implements FeatureConfiguration {

        // RecordCodecBuilder の group は 16 個までなので、腕と柱の数を 2 つの塊に分けて読む。
        private record Arms(double pillarChance, int armMinHeight, int armMaxHeight, double forkMin, double forkMax,
                            int armMinCount, int armMaxCount, double armMinRise, double armMaxRise,
                            double armMinLength, double armMaxLength, double armConeChance, int armConesMin, int armConesMax) {
            static final com.mojang.serialization.MapCodec<Arms> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.DOUBLE.optionalFieldOf("pillar_chance", 0.3).forGetter(Arms::pillarChance),
                    Codec.INT.optionalFieldOf("arm_min_height", 16).forGetter(Arms::armMinHeight),
                    Codec.INT.optionalFieldOf("arm_max_height", 24).forGetter(Arms::armMaxHeight),
                    Codec.DOUBLE.optionalFieldOf("fork_min", 0.35).forGetter(Arms::forkMin),
                    Codec.DOUBLE.optionalFieldOf("fork_max", 0.45).forGetter(Arms::forkMax),
                    Codec.INT.optionalFieldOf("arm_min_count", 3).forGetter(Arms::armMinCount),
                    Codec.INT.optionalFieldOf("arm_max_count", 4).forGetter(Arms::armMaxCount),
                    Codec.DOUBLE.optionalFieldOf("arm_min_rise", 2.0).forGetter(Arms::armMinRise),
                    Codec.DOUBLE.optionalFieldOf("arm_max_rise", 3.0).forGetter(Arms::armMaxRise),
                    Codec.DOUBLE.optionalFieldOf("arm_min_length", 0.40).forGetter(Arms::armMinLength),
                    Codec.DOUBLE.optionalFieldOf("arm_max_length", 0.55).forGetter(Arms::armMaxLength),
                    Codec.DOUBLE.optionalFieldOf("arm_cone_chance", 0.6).forGetter(Arms::armConeChance),
                    Codec.INT.optionalFieldOf("arm_cones_min", 2).forGetter(Arms::armConesMin),
                    Codec.INT.optionalFieldOf("arm_cones_max", 4).forGetter(Arms::armConesMax)
            ).apply(i, Arms::new));
        }

        private record Pillar(int pillarMinHeight, int pillarMaxHeight, int pillarCrownMin, int pillarCrownMax,
                              int pillarConesMin, int pillarConesMax, int buttressHeight) {
            static final com.mojang.serialization.MapCodec<Pillar> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.INT.optionalFieldOf("pillar_min_height", 16).forGetter(Pillar::pillarMinHeight),
                    Codec.INT.optionalFieldOf("pillar_max_height", 22).forGetter(Pillar::pillarMaxHeight),
                    Codec.INT.optionalFieldOf("pillar_crown_min", 6).forGetter(Pillar::pillarCrownMin),
                    Codec.INT.optionalFieldOf("pillar_crown_max", 8).forGetter(Pillar::pillarCrownMax),
                    Codec.INT.optionalFieldOf("pillar_cones_min", 3).forGetter(Pillar::pillarConesMin),
                    Codec.INT.optionalFieldOf("pillar_cones_max", 5).forGetter(Pillar::pillarConesMax),
                    Codec.INT.optionalFieldOf("buttress_height", 2).forGetter(Pillar::buttressHeight)
            ).apply(i, Pillar::new));
        }

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("trunk_state").forGetter(Config::trunkState),
                BlockState.CODEC.fieldOf("leaves_state").forGetter(Config::leavesState),
                BlockState.CODEC.fieldOf("fringe_state").forGetter(Config::fringeState),
                BlockState.CODEC.fieldOf("cone_state").forGetter(Config::coneState),
                Arms.CODEC.forGetter(c -> new Arms(c.pillarChance, c.armMinHeight, c.armMaxHeight, c.forkMin, c.forkMax,
                        c.armMinCount, c.armMaxCount, c.armMinRise, c.armMaxRise, c.armMinLength, c.armMaxLength,
                        c.armConeChance, c.armConesMin, c.armConesMax)),
                Pillar.CODEC.forGetter(c -> new Pillar(c.pillarMinHeight, c.pillarMaxHeight, c.pillarCrownMin, c.pillarCrownMax,
                        c.pillarConesMin, c.pillarConesMax, c.buttressHeight))
        ).apply(instance, (t, l, f, c, a, p) -> new Config(t, l, f, c, a.pillarChance, a.armMinHeight, a.armMaxHeight, a.forkMin, a.forkMax,
                a.armMinCount, a.armMaxCount, a.armMinRise, a.armMaxRise, a.armMinLength, a.armMaxLength, a.armConeChance,
                a.armConesMin, a.armConesMax, p.pillarMinHeight, p.pillarMaxHeight, p.pillarCrownMin, p.pillarCrownMax,
                p.pillarConesMin, p.pillarConesMax, p.buttressHeight)));

        /** 型を決め、腕にも必ず球果を付ける写し(GameTest で腕の型・柱の型を確実に出す)。 */
        public Config fixedForTests(boolean pillar) {
            return new Config(trunkState, leavesState, fringeState, coneState, pillar ? 1.0 : 0.0, armMinHeight, armMaxHeight, forkMin, forkMax,
                    armMinCount, armMaxCount, armMinRise, armMaxRise, armMinLength, armMaxLength, 1.0, armConesMin, armConesMax,
                    pillarMinHeight, pillarMaxHeight, pillarCrownMin, pillarCrownMax, pillarConesMin, pillarConesMax, buttressHeight);
        }
    }
}
