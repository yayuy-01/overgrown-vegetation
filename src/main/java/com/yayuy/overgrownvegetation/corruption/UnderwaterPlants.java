package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.block.CharaBlock;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.block.WaterRootsBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * 水中の植物を生やす(2026-11-01、水中の植物の下絵の実装)。汚染し終えたブロックの<b>水に接した</b>面から呼ばれる
 * ({@link CorruptionSpreader#growUnderwaterPlants})。川・池と海で生えるものを分ける(海かどうかはバイオーム、{@link #isSea})。
 *
 * <ul>
 *     <li>上面(川・池): シャジクモの原(1〜3 段、A : B = 2 : 1)。真上の水面に藻の膜・胞子の膜(3 : 1)。膜の群れの外にも 1 枚ずつ散らす(11-01 夜、睡蓮を消した分)</li>
 *     <li>上面(海): 胞子昆布(4 ブロックの升に 1 点。11-01 夜に 7 から)。真上の水面に浮き藻の帯</li>
 *     <li>下面・岸の壁(水面の高さ): 岸のブロック(原木・土・草)なら垂れる根 2〜5 段。岸でない下面には何も生やさない(水中の垂れ蔓は 11-01 夜に削除)</li>
 *     <li>藻の覆い(川・池 3 枚・海底の丘 2 枚)はブロックでなく覆いの絵(覆われたブロックの水に接した面、{@code CoverRenderer})</li>
 * </ul>
 * 膜・帯・昆布の格子・シャジクモの群落は座標の雑音で決めるので、同じ水面はいつ汚染しても同じ形になる。数はすべて仮決め(2026-11-01)。
 */
public final class UnderwaterPlants {

    /** 川・池の水面のうち膜を浮かべる割合(雑音の閾値。0.72 で水面の約 17%)。 */
    static final double FILM_THRESHOLD = 0.72;
    /** 膜のうち胞子の膜の割合(0.67 で約 23% ≒ 藻 : 胞子 = 3 : 1)。 */
    static final double SPORE_FILM_THRESHOLD = 0.67;
    /** 膜の群れの外の水面に 1 枚ずつ散らす割合と水深の上限(11-01 夜 ユーザー「睡蓮を消す」: 睡蓮の lilyChance 0.25・lilyMaxDepth 10 をそのまま膜へ寄せた)。 */
    static final double FILM_SCATTER_CHANCE = 0.25;
    static final int FILM_SCATTER_MAX_DEPTH = 10;
    /**
     * 海の浮き藻の帯の太さ(雑音の 0.5 からの幅)と、縁に散る 1 ブロックの幅・割合。11-01 夜(ユーザー「帯が細い」)に 0.025 → 0.04:
     * 帯を横切る幅が 1〜2 → 2〜3 ブロック(軸に沿って切れずに続く長さの平均 2.7 → 4.1)。帯 13% + 散る 2% で海面の 15% ほど(雑音の分布を数えた値)。
     */
    static final double BAND_HALF_WIDTH = 0.04;
    static final double BAND_SCATTER_WIDTH = 0.065;
    static final double BAND_SCATTER_CHANCE = 0.25;
    /** シャジクモの群落(雑音がこれより上の所だけ生える)と、高さ 2・3 の閾値。B の閾値(A : B = 2 : 1)。 */
    static final double CHARA_MEADOW = 0.36;
    static final double CHARA_TALL2 = 0.48;
    static final double CHARA_TALL3 = 0.60;
    static final double CHARA_B = 0.61;
    /** 垂れる根: 岸の下面・岸の壁から垂れる割合と長さ。 */
    static final double ROOT_DOWN_CHANCE = 0.6;
    static final double ROOT_SIDE_CHANCE = 0.3;
    static final int ROOT_MIN = 2;
    static final int ROOT_MAX = 5;
    /**
     * 胞子昆布: 格子の間隔(点は升の中の 1〜2 にずらすので、隣との間は 3〜5、ならして 4)、高さ、立てるのに要る水深。
     * 11-01 夜(ユーザー「昆布を増やす」)に 7 → 4(密度 約 3 倍)。
     */
    static final int KELP_GRID = 4;
    /** 柱の横の葉を出す割合(11-01 夜 0.7 → 0.9)と、板 2 枚(東西 + 南北)の葉にする頂の段数。 */
    static final double KELP_SIDE_FROND = 0.9;
    static final int KELP_CROSS_TOP = 3;
    static final int KELP_MIN = 12;
    static final int KELP_MAX = 18;
    static final int KELP_MIN_STANDING = 8;
    /** 昆布の上に残す水(柱の頂は海面の 2 段下まで)。 */
    static final int KELP_HEADROOM = 2;
    /** 水面を探すときに上へたどる最大の水深。 */
    static final int SURFACE_SCAN = 48;

    private UnderwaterPlants() {
    }

    /** 海(過繁茂・繁茂の海、バニラの海)か。川・池と分ける。覆いの描画({@code CoverRenderer})も同じ判定を使う。 */
    public static boolean isSea(Holder<Biome> biome) {
        return ModBiomes.isOvergrownOcean(biome) || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
    }

    public static void grow(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (hostState.isAir() || hostState.is(Blocks.WATER)) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.spawnUnderwaterPlants.get()) {
            return;
        }
        RandomSource random = level.random;
        boolean hangingOnly = hostState.is(ModTags.GROWTH_HANGING_ONLY);
        boolean bank = isBank(hostState);
        for (Direction direction : Direction.values()) {
            if (hangingOnly && direction != Direction.DOWN) {
                continue;
            }
            BlockPos at = pos.relative(direction);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, at) || !isWaterSource(level.getBlockState(at))) {
                continue;
            }
            switch (direction) {
                case UP -> growUp(level, at, random);
                case DOWN -> {
                    if (bank && random.nextDouble() < ROOT_DOWN_CHANCE) {
                        hangRoots(level, at, random);
                    }
                }
                default -> {
                    // 岸の壁: 水面の高さ(真上が水でない)のマスからだけ垂らす
                    if (bank && !level.getFluidState(at.above()).is(Fluids.WATER) && random.nextDouble() < ROOT_SIDE_CHANCE) {
                        hangRoots(level, at, random);
                    }
                }
            }
        }
    }

    /** 根を垂らす岸のブロック(原木・木生シダの幹・土・草)。 */
    static boolean isBank(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.DIRT) || state.is(ModBlocks.TREE_FERN_TRUNK.get())
                || state.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || state.is(ModBlocks.OVERGROWN_DIRT.get());
    }

    static boolean isWaterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** 上面の水マス {@code water}(川・池・海の底)。 */
    private static void growUp(ServerLevel level, BlockPos water, RandomSource random) {
        boolean sea = isSea(level.getBiome(water));
        BlockPos surface = findSurface(level, water);
        if (surface != null) {
            if (sea) {
                floatBand(level, surface);
            } else if (!floatFilm(level, surface) && surface.getY() - water.getY() <= FILM_SCATTER_MAX_DEPTH) {
                scatterFilm(level, surface);
            }
        }
        if (sea) {
            if (isKelpPoint(water.getX(), water.getZ())) {
                growKelp(level, water, random);
            }
            return;
        }
        growBottom(level, water, surface, random);
    }

    // ------------------------------------------------------------------ 川・池の底(11-13 密林の水辺 ③④)

    /** 沈んだ倒木を寝かせる升の割合(座標の雑音)と、倒木の長さ・要る水深。仮決め。 */
    static final double SUNKEN_LOG_CHANCE = 0.015;
    static final int SUNKEN_LOG_MIN = 3;
    static final int SUNKEN_LOG_MAX = 5;
    static final int SUNKEN_LOG_DEPTH = 2;
    /** 水底の落ち葉の群れ(雑音がこれより上の所)と、積もる水深の上限。仮決め。 */
    static final double LITTER_PATCH = 0.56;
    static final int LITTER_MAX_DEPTH = 6;
    /** 沈んだ小枝を散らす割合。仮決め。 */
    static final double TWIG_CHANCE = 0.06;

    /**
     * 川・池の底の 1 升(2026-11-13、ユーザー選択「水底の倒木」「水底の落ち葉の層」「沈んだ枝と小枝」。理由: 森の木・枝・葉が水に落ちて沈み、底に溜まる)。
     * まれに倒木(林床と同じ {@code fallen_log} を横倒しに 3〜5 ブロック)、浅い所(水深 6 まで)の落ち葉の群れ、シャジクモの群落の中はシャジクモ、
     * ほかに小枝を散らす。どれも座標の雑音で決めるので、同じ底はいつ汚染しても同じ形になる(シャジクモの株の有無だけは前と同じく確率)。
     */
    static void growBottom(ServerLevel level, BlockPos water, BlockPos surface, RandomSource random) {
        int x = water.getX();
        int z = water.getZ();
        int depth = surface == null ? Integer.MAX_VALUE : surface.getY() - water.getY();
        if (depth >= SUNKEN_LOG_DEPTH && hash01(x, z, 0xD0C1L) < SUNKEN_LOG_CHANCE && sinkLog(level, water, random)) {
            return;
        }
        // 落ち葉の群れはシャジクモの群落より先(11-13: 群落が底の大部分を取り、落ち葉がほとんど出なかった。群落の中にも落ち葉の空き地ができる)。
        if (depth <= LITTER_MAX_DEPTH && noise(0x1EAFL, x / 4.0, z / 4.0) >= LITTER_PATCH) {
            placeSunken(level, water, ModBlocks.SUNKEN_LEAF_LITTER.get().defaultBlockState());
            return;
        }
        if (noise(0x5A1A, x / 5.0, z / 5.0) >= CHARA_MEADOW) {
            if (random.nextDouble() < OvergrownVegetationConfig.COMMON.underwaterGrowthChance.get()) {
                growChara(level, water, random);
            }
            return;
        }
        if (hash01(x, z, 0x7016L) < TWIG_CHANCE) {
            placeSunken(level, water, ModBlocks.SUNKEN_TWIGS.get().defaultBlockState());
        }
    }

    /** 水源の升に水底の敷物を置く(下の面が塞がっているときだけ)。 */
    private static boolean placeSunken(ServerLevel level, BlockPos pos, BlockState state) {
        if (!isWaterSource(level.getBlockState(pos)) || !state.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    /** 水底に倒木を横倒しに寝かせる。水平のどちらかの向きへ、水源で下が塞がった升が 3 つ以上続けば置いて true。 */
    public static boolean sinkLog(ServerLevel level, BlockPos water, RandomSource random) {
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        int want = SUNKEN_LOG_MIN + random.nextInt(SUNKEN_LOG_MAX - SUNKEN_LOG_MIN + 1);
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        for (int i = 0; i < want; i++) {
            BlockPos cell = water.relative(dir, i);
            BlockPos below = cell.below();
            // 先に生えた水底の敷物・シャジクモの上にも寝かせる(11-13: 隣の升が先に埋まり、倒木が 1 本も置けなかった)。
            BlockState at = com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cell) ? level.getBlockState(cell) : null;
            boolean free = at != null && (isWaterSource(at) || at.is(ModBlocks.SUNKEN_LEAF_LITTER.get()) || at.is(ModBlocks.SUNKEN_TWIGS.get())
                    || at.is(ModBlocks.CHARA.get()));
            if (!free || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                break;
            }
            cells.add(cell);
        }
        if (cells.size() < SUNKEN_LOG_MIN) {
            return false;
        }
        BlockState log = ModBlocks.FALLEN_LOG.get().defaultBlockState().setValue(com.yayuy.overgrownvegetation.block.FallenLogBlock.AXIS, dir.getAxis());
        for (BlockPos cell : cells) {
            level.setBlock(cell, log, Block.UPDATE_ALL);
        }
        return true;
    }

    /** 水マスから上へたどった水面(水源の真上の空気)。見つからなければ null。 */
    static BlockPos findSurface(ServerLevel level, BlockPos water) {
        BlockPos.MutableBlockPos cursor = water.mutable();
        for (int i = 0; i <= SURFACE_SCAN; i++) {
            cursor.move(Direction.UP);
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor)) {
                return null;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.getFluidState().is(Fluids.WATER)) {
                continue;
            }
            return state.isAir() && level.getFluidState(cursor.below()).isSource() ? cursor.immutable() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ 川・池

    /** シャジクモの株を 1 つ。群落の雑音で生える所・高さ・種類を決め、水が浅ければ低くする。 */
    static void growChara(ServerLevel level, BlockPos water, RandomSource random) {
        int x = water.getX();
        int z = water.getZ();
        double meadow = noise(0x5A1A, x / 5.0, z / 5.0);
        if (meadow < CHARA_MEADOW) {
            return;
        }
        int height = meadow >= CHARA_TALL3 ? 3 : meadow >= CHARA_TALL2 ? 2 : 1;
        if (random.nextDouble() < 0.3) {
            height = net.minecraft.util.Mth.clamp(height + (random.nextBoolean() ? 1 : -1), 1, 3);
        }
        CharaBlock.Kind kind = noise(0xB0B, x / 4.0, z / 4.0) >= CHARA_B ? CharaBlock.Kind.B : CharaBlock.Kind.A;
        Block chara = ModBlocks.CHARA.get();
        for (int h = height; h >= 1; h--) {
            if (CharaBlock.placeStack(level, water, h, kind, chara)) {
                return;
            }
        }
    }

    /** 川・池の水面に膜(雑音の高い所 = 1〜2 割)。置いたら true。 */
    static boolean floatFilm(ServerLevel level, BlockPos surface) {
        int x = surface.getX();
        int z = surface.getZ();
        if (noise(0xF11A, x / 6.0, z / 6.0) < FILM_THRESHOLD) {
            return false;
        }
        Block film = noise(0x5B0E, x / 10.0, z / 10.0) >= SPORE_FILM_THRESHOLD ? ModBlocks.SPORE_FILM.get() : ModBlocks.ALGAE_FILM.get();
        return place(level, surface, film.defaultBlockState());
    }

    /** 膜の群れの外に 1 枚(割合 {@value #FILM_SCATTER_CHANCE}、藻 : 胞子 = 3 : 1)。座標で決めるので同じ水面はいつ汚染しても同じ。 */
    static void scatterFilm(ServerLevel level, BlockPos surface) {
        int x = surface.getX();
        int z = surface.getZ();
        if (hash01(x, z, 0x5CA1) >= FILM_SCATTER_CHANCE) {
            return;
        }
        Block film = hash01(x, z, 0x5B0F) < 0.25 ? ModBlocks.SPORE_FILM.get() : ModBlocks.ALGAE_FILM.get();
        place(level, surface, film.defaultBlockState());
    }

    /** 根を {@code top} から下へ 2〜5 段(水源が続く所まで)。先の段は {@code tip}。 */
    public static void hangRoots(ServerLevel level, BlockPos top, RandomSource random) {
        Block roots = ModBlocks.WATER_ROOTS.get();
        if (!WaterRootsBlock.isSupported(level, top, roots)) {
            return;
        }
        int length = Mth.nextInt(random, ROOT_MIN, ROOT_MAX);
        int placed = 0;
        BlockPos.MutableBlockPos cursor = top.mutable();
        while (placed < length && com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, cursor) && isWaterSource(level.getBlockState(cursor))) {
            placed++;
            cursor.move(Direction.DOWN);
        }
        if (placed == 0) {
            return;
        }
        for (int i = 0; i < placed; i++) {
            level.setBlock(top.below(i), roots.defaultBlockState().setValue(WaterRootsBlock.TIP, i == placed - 1), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------ 海

    /** 海面の浮き藻: 雑音の 0.5 の等高線に沿う幅 2〜3 の帯 + 縁に散る 1 ブロック。 */
    static void floatBand(ServerLevel level, BlockPos surface) {
        int x = surface.getX();
        int z = surface.getZ();
        double ridge = Math.abs(noise(0xBA4D, x / 16.0, z / 16.0) - 0.5);
        boolean on = ridge < BAND_HALF_WIDTH
                || ridge < BAND_SCATTER_WIDTH && hash01(x, z, 0x5CA7) < BAND_SCATTER_CHANCE;
        if (on) {
            place(level, surface, ModBlocks.SEA_SPORE_BAND.get().defaultBlockState());
        }
    }

    /** 胞子昆布を立てる格子の点か(升 {@value #KELP_GRID} の中の 1 点)。 */
    static boolean isKelpPoint(int x, int z) {
        int cx = Math.floorDiv(x, KELP_GRID);
        int cz = Math.floorDiv(z, KELP_GRID);
        long h = mix(cx * 341873128712L + cz * 132897987541L + 0x6E1B);
        int ox = 1 + (int) Math.floorMod(h, KELP_GRID - 2L);
        int oz = 1 + (int) Math.floorMod(h >>> 8, KELP_GRID - 2L);
        return x == cx * KELP_GRID + ox && z == cz * KELP_GRID + oz;
    }

    /**
     * 胞子昆布を 1 本。根元に海底の丘(下の宿主と同じブロック、覆われていれば覆う)を 1〜2 段盛り、柱 12〜18 段(海面の 2 段下まで)、
     * 高さ 45%・75% に腕、腕の下に垂れる葉 1〜2 枚と腕の先に横の葉、柱の横に 1〜2 段おきの横の葉、頂の 3 段は 4 方に板 2 枚の葉。立てたら true。
     */
    public static boolean growKelp(ServerLevel level, BlockPos water, RandomSource random) {
        int depth = 0;
        while (depth < SURFACE_SCAN && com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, water.above(depth)) && isWaterSource(level.getBlockState(water.above(depth)))) {
            depth++;
        }
        BlockPos hostPos = water.below();
        BlockState host = level.getBlockState(hostPos);
        boolean mound = host.isCollisionShapeFullBlock(level, hostPos) && !host.hasBlockEntity();
        int moundHeight = mound ? 1 + random.nextInt(2) : 0;
        int height = Math.min(Mth.nextInt(random, KELP_MIN, KELP_MAX), depth - moundHeight - KELP_HEADROOM);
        if (height < KELP_MIN_STANDING) {
            return false;
        }
        if (mound) {
            boolean covered = OvergrownLayerData.isCovered(level, hostPos);
            buildMound(level, water, host, covered, moundHeight);
        }
        BlockPos base = water.above(moundHeight);
        BlockState stalk = ModBlocks.SPORE_KELP_STALK.get().defaultBlockState();
        for (int i = 0; i < height; i++) {
            level.setBlock(base.above(i), stalk, Block.UPDATE_ALL);
        }
        // 腕と、腕の下・先の葉
        Direction armA = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        Direction armB = random.nextBoolean() ? armA.getOpposite() : armA.getClockWise();
        placeArm(level, base.above(Math.round(height * 0.45F)), armA, random);
        placeArm(level, base.above(Math.round(height * 0.75F)), armB, random);
        // 柱の横の葉: 東西と南北を 1 段ごとに替え、それぞれ 9 割。頂の 3 段は 4 方とも、1 マスに板 2 枚(東西 + 南北)
        for (int i = 2; i < height; i++) {
            BlockPos at = base.above(i);
            boolean top = i >= height - KELP_CROSS_TOP;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                boolean turn = (d.getAxis() == Direction.Axis.X) == (i % 2 == 0);
                if (top || turn && random.nextDouble() < KELP_SIDE_FROND) {
                    frond(level, at.relative(d), d.getOpposite(), top);
                }
            }
        }
        return true;
    }

    private static void placeArm(ServerLevel level, BlockPos stalk, Direction d, RandomSource random) {
        BlockPos arm = stalk.relative(d);
        if (!isWaterSource(level.getBlockState(arm))) {
            return;
        }
        level.setBlock(arm, ModBlocks.SPORE_KELP_STALK.get().defaultBlockState(), Block.UPDATE_ALL);
        frond(level, arm.relative(d), d.getOpposite(), false);
        int hang = 1 + random.nextInt(2);
        for (int i = 1; i <= hang; i++) {
            if (!frond(level, arm.below(i), Direction.UP, false)) {
                break;
            }
        }
    }

    /** 葉を 1 枚。{@code cross} なら板 2 枚(東西 + 南北)の姿(封印木の板の性質 corner を流用し、blockstate で十字の模型にした)。 */
    private static boolean frond(ServerLevel level, BlockPos pos, Direction facing, boolean cross) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos) || !isWaterSource(level.getBlockState(pos))) {
            return false;
        }
        level.setBlock(pos, ModBlocks.SPORE_KELP_FROND.get().defaultBlockState()
                .setValue(SigillariaFringeBlock.FACING, facing).setValue(SigillariaFringeBlock.CORNER, cross), Block.UPDATE_ALL);
        return true;
    }

    /** 根元の丘: 1 段目は十字(5 マス)、2 段目は真ん中。宿主と同じブロックで、宿主が覆われていれば丘も覆う(海底の丘の絵が出る)。 */
    private static void buildMound(ServerLevel level, BlockPos water, BlockState host, boolean covered, int height) {
        for (int y = 0; y < height; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > (y == 0 ? 1 : 0)) {
                        continue;
                    }
                    BlockPos p = water.offset(dx, y, dz);
                    BlockPos below = p.below();
                    if (!isWaterSource(level.getBlockState(p)) || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                        continue;
                    }
                    level.setBlock(p, host, Block.UPDATE_ALL);
                    if (covered) {
                        OvergrownLayerData.cover(level, p);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 共通

    private static boolean place(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).isAir() || !state.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    /** 格子点の値を滑らかにつないだ雑音(0〜1)。同じ座標はいつも同じ値。 */
    static double noise(long salt, double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);
        double a = hash01(x0, z0, salt);
        double b = hash01(x0 + 1, z0, salt);
        double c = hash01(x0, z0 + 1, salt);
        double d = hash01(x0 + 1, z0 + 1, salt);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static double hash01(int x, int z, long salt) {
        return (mix(x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt) >>> 11) * 0x1.0p-53;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
