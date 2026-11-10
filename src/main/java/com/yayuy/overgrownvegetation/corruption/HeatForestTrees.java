package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.HeatStrandBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>熱水の森の大きな植物</b>(2026-11-10 木の段階の前半)。形はどれも仕様の控え「大きな植物・構造」の決定(下絵 1〜3 回目)。
 * <ul>
 *     <li>普通の木 B 案 2: 2×2 の幹(高さ {@link #TREE_TRUNK})・平たい傘(下の段は半径 {@link #TREE_RADIUS}、上の段 {@link #TREE_TOP_RADIUS})・縁から垂れる紫の房</li>
 *     <li>天井の逆さの木: 天井から下がる幹・下ほど広い樹冠・白っぽい青緑の房・樹冠の下から下りる熱の気根。
 *     長さは天井の高さの 1/3 まで、樹冠の下端は床(溶岩)から 15 以上、24 より低い天井には生やさない(決)</li>
 *     <li>管の樹 A(細い管の束)・B(太い管)。先に管の羽根</li>
 *     <li>煙突の塚 A(細る煙突)・B(棚のある煙突)。塚の上の天井に鉱の垂れ管を寄せる</li>
 * </ul>
 * 置き場所は、オーバーワールドの木の間隔の格子にならい、座標の格子 1 マスに 1 本(位置はシードで決まる)。格子の大きさと割合は仮決め。
 * 読み込まれていないチャンクにまたがる所・ふさがった所には置かない(置けるマスだけ置く。幹と土台が置けなければやめる)。
 */
public final class HeatForestTrees {

    // ---- 仮決めの値 ----
    static final int CELL_TREE = 10, CELL_TUBE = 14, CELL_MOUND = 24, CELL_SKY = 12;
    static final double TUBE_CHANCE = 0.6, MOUND_CHANCE = 0.7, SKY_CHANCE = 0.8, THICK_TUBE = 0.3, FLANGED_MOUND = 0.5;
    static final int TREE_TRUNK = 4;
    static final double TREE_RADIUS = 4.3, TREE_TOP_RADIUS = 2.6;
    /** 逆さの木: 樹冠の下端と床の間をこれ以上あける(決: 15)。生える天井の高さの下限(決: 24)。 */
    static final int SKY_FLOOR_GAP = 15, SKY_MIN_CEILING = 24;

    // ------------------------------------------------------------------ 入口

    /** 床(敷物)の上の空きマス {@code at} が格子の印なら、大きな植物を置く。置いたら true。 */
    public static boolean tryUp(ServerLevel level, BlockPos host, BlockPos at, BlockState hostState, RandomSource random) {
        if (!hostState.is(ModBlocks.HEAT_MAT.get()) && !hostState.is(ModBlocks.HEAT_MAT_STRANDS.get())) {
            return false;
        }
        int x = at.getX(), z = at.getZ();
        // 11-10 ユーザー決定(苗木方式): 完成形をその場に立てず、芽を植えて時間で育てる。格子の印と割合は今のまま(仮)
        if (marked(level, x, z, CELL_MOUND, 31) && roll(level, x, z, 32) < MOUND_CHANCE) {
            return plant(level, at, ModBlocks.HEAT_CHIMNEY_SPROUT.get().defaultBlockState());
        }
        if (marked(level, x, z, CELL_TUBE, 41) && roll(level, x, z, 42) < TUBE_CHANCE) {
            return plant(level, at, ModBlocks.HEAT_TUBE_SPROUT.get().defaultBlockState());
        }
        if (marked(level, x, z, CELL_TREE, 51)) {
            return plant(level, at, ModBlocks.HEAT_TREE_SPROUT.get().defaultBlockState());
        }
        return false;
    }

    /** 高い天井の下のマス {@code at}(真上が天井)が格子の印で、逆さの木の決まりを満たすなら、逆さの木の芽を下げる。植えたら true。 */
    public static boolean tryDown(ServerLevel level, BlockPos at, RandomSource random) {
        int x = at.getX(), z = at.getZ();
        if (!marked(level, x, z, CELL_SKY, 61) || roll(level, x, z, 62) >= SKY_CHANCE) {
            return false;
        }
        int gap = HeatForestGround.openBelow(level, at.above(), 160);
        if (gap < SKY_MIN_CEILING || Math.min(gap / 3, gap - SKY_FLOOR_GAP - 1) < 3) {
            return false; // 植えるときにも決まりを見る(育つときにももう一度見る)
        }
        return plant(level, at, ModBlocks.HEAT_SKY_SPROUT.get().defaultBlockState());
    }

    static boolean plant(ServerLevel level, BlockPos at, BlockState sprout) {
        if (!free(level, at) || !sprout.canSurvive(level, at)) return false;
        set(level, at, sprout);
        return true;
    }

    /** 芽が育つ({@link com.yayuy.overgrownvegetation.block.HeatSproutBlock} の 2 段目)。育てなければ芽を戻す。 */
    public static void growSprout(ServerLevel level, BlockPos pos, BlockState sprout, com.yayuy.overgrownvegetation.block.HeatSproutBlock.Kind kind, RandomSource random) {
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 4);
        boolean grown = switch (kind) {
            case TREE -> placeGroundTree(level, pos, pickSize(random), random);
            case TUBE -> placeTubeTree(level, pos, random.nextDouble() < THICK_TUBE, random);
            case SKY -> placeInvertedTree(level, pos, random);
            case MOUND -> placeMound(level, pos, random.nextDouble() < FLANGED_MOUND, random);
        };
        if (!grown && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, sprout, 4); // 芽のまま残す
        }
    }

    /** 普通の木の大きさ。割合は design の heat_forest.json(groundTree。11-10 ユーザー決定: 小 35%・中 50%・大 15%)。 */
    public enum Size { SMALL, MEDIUM, LARGE }


    static Size pickSize(RandomSource random) {
        double r = random.nextDouble();
        var t = com.yayuy.overgrownvegetation.data.DesignData.heatForest();
        return r < t.smallShare() ? Size.SMALL : r < 1 - t.largeShare() ? Size.MEDIUM : Size.LARGE;
    }

    static boolean marked(ServerLevel level, int x, int z, int cell, int salt) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        long h = Mth.getSeed(cx * 31 + salt, salt * 7, cz * 17 - salt) ^ level.getSeed();
        int dx = 2 + (int) Math.floorMod(h, (long) (cell - 4)), dz = 2 + (int) Math.floorMod(h >> 17, (long) (cell - 4));
        return x == cx * cell + dx && z == cz * cell + dz;
    }

    static double roll(ServerLevel level, int x, int z, int salt) {
        long h = Mth.getSeed(x, salt, z) ^ (level.getSeed() * 31L);
        return (Math.floorMod(h >> 9, 1000)) / 1000.0;
    }

    // ------------------------------------------------------------------ 置き方の部品

    /** 置けるマスか(読み込まれていて、空気か上書きできる草。液体は不可)。 */
    static boolean free(ServerLevel level, BlockPos p) {
        if (!level.hasChunkAt(p)) return false;
        BlockState s = level.getBlockState(p);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    static void set(ServerLevel level, BlockPos p, BlockState s) {
        level.setBlock(p, s, Block.UPDATE_CLIENTS);
    }

    static boolean solidBelow(ServerLevel level, BlockPos p) {
        BlockPos b = p.below();
        return level.hasChunkAt(b) && level.getBlockState(b).isFaceSturdy(level, b, Direction.UP);
    }

    // ------------------------------------------------------------------ 普通の木 B 案 2

    /** {@code base}(床の上のマス)から、2×2 の幹(base を北西の角)と平たい傘。置いたら true。 */
    public static boolean placeGroundTree(ServerLevel level, BlockPos base) {
        return placeGroundTree(level, base, TREE_TRUNK, 2, TREE_RADIUS, TREE_TOP_RADIUS);
    }

    /**
     * 大きさを引いて普通の木を置く(11-10 ユーザー決定: 高さをばらばらに)。数値は design の heat_forest.json の groundTree
     * (2026-11-10 追記 14 で Java から移した。既定: 小 = 幹 1×1・高さ 3〜5・傘の半径 2.2〜2.6 / 中 = 幹 2×2・高さ 4〜7・半径 4.0〜4.6 /
     * 大 = 幹 2×2・高さ 8〜11・半径 5.0〜5.6。上の段の半径は下の段の 6 割)。
     */
    public static boolean placeGroundTree(ServerLevel level, BlockPos base, Size size, RandomSource random) {
        var table = com.yayuy.overgrownvegetation.data.DesignData.heatForest();
        var s = switch (size) {
            case SMALL -> table.small();
            case MEDIUM -> table.medium();
            case LARGE -> table.large();
        };
        int h = s.minHeight() + random.nextInt(s.maxHeight() - s.minHeight() + 1);
        double r = s.radius() + (h - s.minHeight()) * s.radiusPerBlock();
        return placeGroundTree(level, base, h, s.trunk(), r, r * table.topRadiusRatio());
    }

    /** 幹 {@code width}×{@code width}(base を北西の角)・高さ {@code height} と、平たい傘(下の段 {@code radius}・上の段 {@code topRadius})・縁の下の紫の房。 */
    public static boolean placeGroundTree(ServerLevel level, BlockPos base, int height, int width, double radius, double topRadius) {
        for (int i = 0; i < height; i++) {
            for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < width; dz++) {
                BlockPos p = base.offset(dx, i, dz);
                if (!free(level, p)) return false;
                if (i == 0 && !solidBelow(level, p)) return false;
            }
        }
        BlockState bark = ModBlocks.HEAT_BARK.get().defaultBlockState();
        for (int i = 0; i < height; i++) for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < width; dz++) set(level, base.offset(dx, i, dz), bark);
        double cx = base.getX() + width / 2.0, cz = base.getZ() + width / 2.0;
        int cy = base.getY() + height;
        int reach = (int) Math.ceil(radius) + 1;
        BlockState leaves = ModBlocks.HEAT_LEAVES.get().defaultBlockState();
        Set<Long> bottom = new HashSet<>();
        List<BlockPos> bottomCells = new ArrayList<>();
        for (int bx = (int) Math.floor(cx) - reach; bx <= (int) Math.floor(cx) + reach; bx++) {
            for (int bz = (int) Math.floor(cz) - reach; bz <= (int) Math.floor(cz) + reach; bz++) {
                double d = Math.hypot(bx + 0.5 - cx, bz + 0.5 - cz);
                BlockPos p = new BlockPos(bx, cy, bz);
                if (d <= radius && free(level, p)) {
                    set(level, p, leaves);
                    bottom.add(BlockPos.asLong(bx, 0, bz));
                    bottomCells.add(p);
                }
                BlockPos up = p.above();
                if (d <= topRadius && free(level, up)) set(level, up, leaves);
            }
        }
        // 縁の下に紫の房
        BlockState fringe = ModBlocks.HEAT_LEAF_FRINGE.get().defaultBlockState();
        for (BlockPos p : bottomCells) {
            boolean edge = false;
            for (Direction d : Direction.Plane.HORIZONTAL) edge |= !bottom.contains(BlockPos.asLong(p.getX() + d.getStepX(), 0, p.getZ() + d.getStepZ()));
            BlockPos under = p.below();
            if (edge && free(level, under) && fringe.canSurvive(level, under)) set(level, under, fringe);
        }
        return true;
    }

    // ------------------------------------------------------------------ 天井の逆さの木

    /** {@code at}(真上が天井)から逆さの木を下げる。置いたら true。 */
    public static boolean placeInvertedTree(ServerLevel level, BlockPos at, RandomSource random) {
        int gap = HeatForestGround.openBelow(level, at.above(), 160);
        if (gap < SKY_MIN_CEILING) return false;
        int maxL = Math.min(gap / 3, gap - SKY_FLOOR_GAP - 1);
        if (maxL < 3) return false;
        int len = 3 + random.nextInt(Math.min(maxL, 8) - 2);
        BlockState bark = ModBlocks.HEAT_BARK.get().defaultBlockState();
        for (int i = 0; i < len; i++) if (!free(level, at.below(i))) return false;
        for (int i = 0; i < len; i++) set(level, at.below(i), bark);
        int by = at.getY() - len;               // 樹冠のいちばん上の段(幹の先のすぐ下)
        BlockState leaves = ModBlocks.HEAT_SKY_LEAVES.get().defaultBlockState();
        List<BlockPos> bottom = new ArrayList<>();
        Set<Long> bottomSet = new HashSet<>();
        for (int dy = 0; dy < 3; dy++) {
            int rr = 1 + dy;
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                if (Math.abs(dx) == rr && Math.abs(dz) == rr) continue;
                if (dy < 2 && Math.abs(dx) < rr && Math.abs(dz) < rr && !(dx == 0 && dz == 0)) continue;
                BlockPos p = new BlockPos(at.getX() + dx, by - dy, at.getZ() + dz);
                if (!free(level, p)) continue;
                set(level, p, leaves);
                if (dy == 2) { bottom.add(p); bottomSet.add(BlockPos.asLong(p.getX(), 0, p.getZ())); }
            }
        }
        int floorY = at.getY() - gap;           // 床(か溶岩)のブロック
        // 樹冠の下から熱の気根(床の 2 段手前で止める)と、縁の下に房
        BlockState root = ModBlocks.HEAT_AERIAL_ROOT.get().defaultBlockState();
        BlockState fringe = ModBlocks.HEAT_SKY_LEAF_FRINGE.get().defaultBlockState();
        int roots = 3 + random.nextInt(3);
        for (BlockPos p : bottom) {
            BlockPos under = p.below();
            if (!free(level, under)) continue;
            int room = under.getY() - floorY - 2;
            if (roots > 0 && room >= 3 && random.nextInt(3) == 0) {
                int rl = Math.min(room, 3 + random.nextInt(6));
                boolean clear = true;
                for (int i = 0; i < rl && clear; i++) clear = free(level, under.below(i));
                if (clear) { HeatStrandBlock.placeStrand(level, under, rl, root); roots--; continue; }
            }
            boolean edge = false;
            for (Direction d : Direction.Plane.HORIZONTAL) edge |= !bottomSet.contains(BlockPos.asLong(p.getX() + d.getStepX(), 0, p.getZ() + d.getStepZ()));
            if (edge && fringe.canSurvive(level, under)) set(level, under, fringe);
        }
        return true;
    }

    // ------------------------------------------------------------------ 管の樹

    public static boolean placeTubeTree(ServerLevel level, BlockPos base, boolean thick, RandomSource random) {
        List<int[]> tubes = new ArrayList<>();
        // 高さは design の heat_forest.json の tubeTree(2026-11-10 追記 14 で Java から移した。引き方は前と同じ)
        var th = thick ? com.yayuy.overgrownvegetation.data.DesignData.heatForest().thickTube() : com.yayuy.overgrownvegetation.data.DesignData.heatForest().thinTube();
        if (thick) {
            tubes.add(new int[]{0, 0, th.middleMin() + random.nextInt(th.middleMax() - th.middleMin() + 1)});
            int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}};
            for (int k = 0; k < 3; k++) { int[] o = around[random.nextInt(around.length)]; tubes.add(new int[]{o[0], o[1], th.sideMin() + random.nextInt(th.sideMax() - th.sideMin() + 1)}); }
        } else {
            // 細い管の樹 A の高さ: 2026-11-10 ユーザー決定で 3〜6 → 3〜9 ほど(上限 9 は仮)。真ん中 4〜9・まわり 3〜8
            tubes.add(new int[]{0, 0, th.middleMin() + random.nextInt(th.middleMax() - th.middleMin() + 1)});
            int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int k = 0; k < 4; k++) { int[] o = around[random.nextInt(around.length)]; tubes.add(new int[]{o[0], o[1], th.sideMin() + random.nextInt(th.sideMax() - th.sideMin() + 1)}); }
        }
        BlockState body = (thick ? ModBlocks.HEAT_TUBE : ModBlocks.HEAT_TUBE_STEM).get().defaultBlockState();
        BlockState plume = ModBlocks.HEAT_TUBE_PLUME.get().defaultBlockState();
        boolean any = false;
        Set<Long> done = new HashSet<>();
        for (int[] t : tubes) {
            BlockPos foot = base.offset(t[0], 0, t[1]);
            if (!done.add(foot.asLong()) || !solidBelow(level, foot)) continue;
            int h = t[2];
            boolean clear = true;
            for (int i = 0; i <= h && clear; i++) clear = free(level, foot.above(i));
            if (!clear) continue;
            for (int i = 0; i < h; i++) set(level, foot.above(i), body);
            set(level, foot.above(h), plume);
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------ 煙突の塚

    public static boolean placeMound(ServerLevel level, BlockPos base, boolean flanges, RandomSource random) {
        if (!solidBelow(level, base)) return false;
        int top = chimney(level, base, 8 + random.nextInt(7), 1.3 + random.nextDouble() * 0.4, flanges, random);
        if (top < 0) return false;
        if (flanges) {
            int[][] around = {{3, 2}, {-3, 2}, {3, -2}, {-3, -2}};
            for (int k = 0; k < 2; k++) {
                int[] o = around[random.nextInt(around.length)];
                BlockPos b = base.offset(o[0], 0, o[1]);
                if (solidBelow(level, b)) chimney(level, b, 5 + random.nextInt(5), 0.8, false, random);
            }
        }
        // 塚の上の天井に鉱の垂れ管を寄せる
        DeferredBlock<Block> straws = ModBlocks.HEAT_MINERAL_STRAWS;
        for (int k = 0; k < 6; k++) {
            BlockPos c = new BlockPos(base.getX() + random.nextInt(7) - 3, top + 1, base.getZ() + random.nextInt(7) - 3);
            for (int i = 0; i < 40; i++, c = c.above()) {
                if (!level.hasChunkAt(c)) break;
                if (!NetherSurface.isOpen(level.getBlockState(c.above()))) {
                    BlockState s = straws.get().defaultBlockState();
                    if (free(level, c) && s.canSurvive(level, c)) set(level, c, s);
                    break;
                }
            }
        }
        return true;
    }

    /** 1 本の煙突。上の端の Y を返す(置けなければ -1)。 */
    static int chimney(ServerLevel level, BlockPos base, int height, double r0, boolean flanges, RandomSource random) {
        BlockState body = ModBlocks.HEAT_CHIMNEY.get().defaultBlockState(), foot = ModBlocks.HEAT_CHIMNEY_BASE.get().defaultBlockState();
        if (!free(level, base)) return -1;
        int topY = -1;
        for (int y = 0; y < height; y++) {
            double t = (double) y / height;
            double rr = r0 * (1 - 0.65 * t) + (random.nextDouble() - 0.5) * 0.4;
            boolean flange = flanges && y > 2 && y < height - 2 && y % 4 == 0;
            if (flange) rr += 1.2;
            int r = (int) Math.ceil(rr);
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rr * rr + 0.3) continue;
                BlockPos p = base.offset(dx, y, dz);
                if (!free(level, p)) continue;
                set(level, p, y < 3 ? foot : body);
                topY = Math.max(topY, p.getY());
            }
        }
        return topY;
    }

    // ------------------------------------------------------------------ 適応の後: オーバーワールドの木の置き換え(15% 残す = 決)

    /** オーバーワールドの木のブロック(幹・葉・枝・垂れ物)。 */
    static Set<Block> owTree() {
        if (OW_TREE == null) {
            Set<Block> s = new HashSet<>();
            for (DeferredBlock<Block> b : List.of(ModBlocks.OVERGROWN_LOG, ModBlocks.OVERGROWN_WOOD, ModBlocks.OVERGROWN_BRANCH, ModBlocks.OVERGROWN_LEAVES,
                    ModBlocks.OVERGROWN_LEAVES_TUFT, ModBlocks.SIGILLARIA_LOG, ModBlocks.SIGILLARIA_BRANCH, ModBlocks.STIGMARIA, ModBlocks.SIGILLARIA_LEAVES,
                    ModBlocks.SIGILLARIA_LEAVES_FRINGE, ModBlocks.SIGILLARIA_CONE, ModBlocks.TREE_FERN_TRUNK, ModBlocks.TREE_FERN_LEAVES, ModBlocks.TREE_FERN_FROND,
                    ModBlocks.CORDAITES_BUTTRESS, ModBlocks.MEDULLOSA_TRUNK, ModBlocks.MEDULLOSA_CROWN, ModBlocks.AERIAL_ROOT, ModBlocks.AERIAL_ROOT_PILLAR,
                    ModBlocks.BEARD_LICHEN, ModBlocks.HANGING_SPORE_SAC, ModBlocks.LARGE_UNDEVELOPED_FRUIT)) {
                s.add(b.get());
            }
            OW_TREE = s;
            OW_TRUNK = Set.of(ModBlocks.OVERGROWN_LOG.get(), ModBlocks.OVERGROWN_WOOD.get(), ModBlocks.SIGILLARIA_LOG.get(),
                    ModBlocks.TREE_FERN_TRUNK.get(), ModBlocks.CORDAITES_BUTTRESS.get(), ModBlocks.MEDULLOSA_TRUNK.get());
        }
        return OW_TREE;
    }

    private static Set<Block> OW_TREE, OW_TRUNK;

    public static boolean isOverworldTrunk(BlockState state) {
        owTree();
        return OW_TRUNK.contains(state.getBlock());
    }

    /**
     * オーバーワールドの木の幹を見つけたら、その木を消して普通の木 B を植える。根元の座標で 15% は残す(決)。
     * 置き換えたら true(残すときは false)。
     */
    public static boolean convertOverworldTree(ServerLevel level, BlockPos trunk, int keepPercent) {
        Set<Block> family = owTree();
        BlockPos.MutableBlockPos p = trunk.mutable();
        int guard = 0;
        while (guard++ < 64 && level.hasChunkAt(p.below()) && OW_TRUNK.contains(level.getBlockState(p.below()).getBlock())) p.move(0, -1, 0);
        BlockPos base = p.immutable();
        if (Math.floorMod(Mth.getSeed(base) >> 13, 100) < keepPercent) return false;
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        // 1. この木の幹: 根元から幹づたい(斜めも含む)にたどれる幹
        Set<Long> own = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        own.add(base.asLong());
        while (!queue.isEmpty() && own.size() < OW_LIMIT) {
            BlockPos c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = c.offset(dx, dy, dz);
                if (!inReach(base, n, OW_REACH) || own.contains(n.asLong()) || !level.hasChunkAt(n)) continue;
                if (OW_TRUNK.contains(level.getBlockState(n).getBlock())) {
                    own.add(n.asLong());
                    queue.add(n);
                }
            }
        }
        // 2. 葉・枝などを木ごとに分ける: この木の幹と、まわりのほかの木の幹から同時に広げ、先に届いた木のものにする
        //    (隣の立っている木の葉は、その木の幹の方が近ければ消さない。2026-11-10 追記 13)
        Map<Long, Boolean> owner = new HashMap<>(); // true = この木
        ArrayDeque<long[]> front = new ArrayDeque<>();
        for (long l : own) front.add(new long[]{l, 1});
        int r = OW_REACH + 6;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -1; y <= OW_HEIGHT; y++) {
            m.set(base.getX() + x, base.getY() + y, base.getZ() + z);
            if (!own.contains(m.asLong()) && level.hasChunkAt(m) && OW_TRUNK.contains(level.getBlockState(m).getBlock())) {
                front.add(new long[]{m.asLong(), 0});
            }
        }
        for (long[] f : front) owner.put(f[0], f[1] == 1);
        while (!front.isEmpty() && owner.size() < OW_LIMIT * 4) {
            long[] f = front.poll();
            BlockPos c = BlockPos.of(f[0]);
            boolean mine = f[1] == 1;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                long nl = n.asLong();
                if (owner.containsKey(nl) || !inReach(base, n, mine ? OW_REACH : r) || !level.hasChunkAt(n)) continue;
                Block b = level.getBlockState(n).getBlock();
                if (!family.contains(b) || OW_TRUNK.contains(b)) continue;
                owner.put(nl, mine);
                front.add(new long[]{nl, f[1]});
            }
        }
        // 3. この木のものを消す
        Set<Long> removed = new HashSet<>();
        for (Map.Entry<Long, Boolean> e : owner.entrySet()) {
            if (!e.getValue()) continue;
            BlockPos c = BlockPos.of(e.getKey());
            level.setBlock(c, air, Block.UPDATE_CLIENTS);
            removed.add(e.getKey());
        }
        // 4. つながりの切れた葉: 消した所に触れる葉の塊で、どの幹にもつながらないものは同じ木の取り残しとして消す
        Set<Long> visited = new HashSet<>();
        for (long l : removed) {
            BlockPos c = BlockPos.of(l);
            for (Direction d : Direction.values()) {
                BlockPos s = c.relative(d);
                if (visited.contains(s.asLong()) || !level.hasChunkAt(s)) continue;
                Block b = level.getBlockState(s).getBlock();
                if (!family.contains(b) || OW_TRUNK.contains(b)) continue;
                List<BlockPos> comp = new ArrayList<>();
                ArrayDeque<BlockPos> q = new ArrayDeque<>();
                q.add(s);
                visited.add(s.asLong());
                boolean anchored = false;
                while (!q.isEmpty()) {
                    BlockPos leaf = q.poll();
                    comp.add(leaf);
                    if (comp.size() > OW_LIMIT) { anchored = true; break; } // 大きすぎる塊は触らない
                    for (Direction e : Direction.values()) {
                        BlockPos n = leaf.relative(e);
                        if (!level.hasChunkAt(n)) { anchored = true; continue; }
                        Block nb = level.getBlockState(n).getBlock();
                        if (OW_TRUNK.contains(nb)) { anchored = true; continue; }
                        if (family.contains(nb) && visited.add(n.asLong())) q.add(n);
                    }
                }
                if (anchored) continue;
                for (BlockPos leaf : comp) level.setBlock(leaf, air, Block.UPDATE_CLIENTS);
            }
        }
        placeGroundTree(level, base);
        return true;
    }

    /** オーバーワールドの木を消す範囲(根元から水平に。2026-11-10 ユーザー決定: 8 → 12)。 */
    static final int OW_REACH = 12;
    /** 上へ見る高さ(仮)。 */
    static final int OW_HEIGHT = 40;
    /** 1 本で消す・調べる数の上限(仮)。 */
    static final int OW_LIMIT = 4000;

    private static boolean inReach(BlockPos base, BlockPos n, int reach) {
        return Math.abs(n.getX() - base.getX()) <= reach && Math.abs(n.getZ() - base.getZ()) <= reach
                && n.getY() >= base.getY() - 1 && n.getY() <= base.getY() + OW_HEIGHT;
    }

    /** 幹の向き(RotatedPillar)を縦のまま使う(手で置いたときと同じ)。 */
    static BlockState verticalBark() {
        return ModBlocks.HEAT_BARK.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
    }

    private HeatForestTrees() {
    }
}
