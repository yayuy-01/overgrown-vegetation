package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.block.CaveSurfaceGrowthBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 洞窟の中にだけ生える汚染植物と、洞窟の中にだけ建つ構造物。
 *
 * <p>「洞窟」= その列の地表({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})より
 * {@code caveDepthMargin} ブロック以上深い場所。空に開けた谷・峡谷やオーバーハングの縁は洞窟とみなさない
 * ({@link com.yayuy.overgrownvegetation.worldgen.TreePlacementRules#isEnclosed} と同じ考え方)。
 *
 * <p>どの環境({@link OvergrowthVariant}、地域マップのぼかし付き)の洞窟かで内容が変わる:
 * <ul>
 *     <li><b>湿地・菌湿地帯</b>({@link Mode#SWAMP}) — 露出面の下草・蔦の抽選を丸ごと洞窟専用の植物に置き換える:
 *     微光を放つ胞子嚢({@code cave_spore_sac}、床・壁・天井どこにでも)と苔の塊({@code cave_moss_clump}、
 *     面に沿って数個の塊)。さらに構造物 3 種 — 壁から張り出す巨大なキノコの傘({@code fungal_cap})、
 *     天井から垂れて再び天井へ戻る太いアーチ、床から天井まで継ぐ菌の柱
 *     ({@code fungal_pillar} + 所々に微かに光る {@code glowing_mycelium})。</li>
 *     <li><b>密林</b>({@link Mode#JUNGLE}) — 植物はいつも通りで、天井のアーチだけを建てる
 *     ({@code caveRootArchInJungle}、既定 true)。</li>
 * </ul>
 *
 * <p>天井のアーチには 2 種類ある({@link ArchStyle})。{@code caveVineArchShare} の割合で
 * <b>蔦</b>のアーチ(樹皮だけの蔓 {@code overgrown_wood} を {@code overgrown_leaves} の葉が包む。
 * 湿地系では {@code overgrown_swamp_wood} / {@code overgrown_swamp_leaves})、残りが今までどおりの
 * <b>根</b>のアーチ({@code giant_overgrown_log})。蔦のほうが深く垂れる({@code caveVineArchDip})。
 * どちらも密林・湿地系のどちらの洞窟でも建つ。
 *
 * <p>すべて {@link CorruptionSpreader#growSurfacePlants} から呼ばれる(汚染直後の露出面の装飾と同じ経路
 * なので、触手・地表浸食・深部浸食・建築浸食のどの経路で洞窟の壁が汚染されても発動する)。
 * 構造物は汚染ブロック 1 つにつき {@code caveStructureChance} で 1 回だけ抽選し、露出している面
 * (床 / 壁 / 天井)に応じて建てられる種類を重みで選ぶ。空間が足りなければ何もしない。
 * 形のパラメータは config の {@code cave*} キー(プレビューで調整して貼る)。
 */
public final class CaveGrowth {

    /** その場所の洞窟の扱い。 */
    public enum Mode {
        /** 洞窟ではない / 機能オフ。 */
        NONE,
        /** 密林の洞窟: 天井のアーチのみ。 */
        JUNGLE,
        /** 湿地・菌湿地帯の洞窟: 洞窟植物 + 構造物 3 種。 */
        SWAMP
    }

    /** 天井のアーチの種類。{@code caveVineArchShare} で抽選する。 */
    public enum ArchStyle {
        /** 岩から生えた太い根(巨大樹の原木)。 */
        ROOT,
        /** 過繁茂した葉に包まれた巨大な蔦。根より深く垂れる。 */
        VINE
    }

    /** 地域マップのぼかし用ソルト(用途ごとに違う値を使う)。 */
    private static final int SALT_VARIANT = 6;
    /** 柱の根元を地面まで下へ伸ばす最大深さ。 */
    private static final int ANCHOR_DEPTH = 4;
    /** 天井アーチの終端側の天井を探す上下の範囲。 */
    private static final int ARCH_CEILING_SEARCH = 4;

    private CaveGrowth() {
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /**
     * 天井を探すときに木(幹・枝・葉)を読み飛ばす最大の段数。これを超えても本物の天井が
     * 見つからなければ「木に覆われているだけで洞窟ではない」と判断する。
     * 巨大樹(高さ120〜240)の下は必ずここで打ち切られる。
     */
    private static final int ROOF_SCAN_LIMIT = 48;

    /**
     * {@code pos}(汚染したブロック)がどの洞窟扱いか。洞窟でなければ {@link Mode#NONE}。
     *
     * <p>汚染したブロック自身({@code hostState}、不明なら null)が木・巨大樹の一部
     * ({@link ModTags#NOT_CAVE_ROOF})なら、それは洞窟の壁ではないので常に {@link Mode#NONE}。
     * 巨大樹は 15 万ブロックあるので、ここで先に弾いて {@link #isCave} の天井探しを省く。
     */
    public static Mode modeAt(ServerLevel level, BlockPos pos, BlockState hostState) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.caveGrowth.get()) {
            return Mode.NONE;
        }
        if (!isUnderground(level, pos, hostState)) {
            return Mode.NONE;
        }
        OvergrowthVariant variant = OvergrowthRegionMap.blendedVariantAt(level, pos.getX(), pos.getZ(), SALT_VARIANT);
        return variant.isSwamp() ? Mode.SWAMP : Mode.JUNGLE;
    }

    /**
     * {@code pos}(汚染したブロック)が地下(洞窟)か。{@link #modeAt} と違って {@code caveGrowth} の
     * オン/オフや環境(密林 / 湿地)は見ず、深さだけで判定する。洞窟専用の植物とは無関係に
     * 「地下では多め / 少なめ」を効かせたい抽選(光るツタなど)から呼ぶ。
     *
     * <p>汚染したブロック自身({@code hostState}、不明なら null)が木・巨大樹の一部
     * ({@link ModTags#NOT_CAVE_ROOF})なら、それは洞窟の壁ではないので常に false。
     *
     * <p><b>注意:</b> 地表付近でなければ天井を探すので安くはない。露出面ごとに呼ばず、
     * 汚染ブロック 1 つにつき 1 回までに留めること。
     */
    public static boolean isUnderground(ServerLevel level, BlockPos pos, BlockState hostState) {
        if (hostState != null && hostState.is(ModTags.NOT_CAVE_ROOF)) {
            return false;
        }
        return isCave(level, pos, OvergrownVegetationConfig.COMMON.caveDepthMargin.get());
    }

    /**
     * その列の<b>本物の天井</b>(木ではない地形・建築)より {@code margin} 以上深ければ洞窟。
     * 天井を探す範囲は地表から {@value #ROOF_SCAN_LIMIT} 段まで
     * ({@link #isCave(LevelReader, BlockPos, int, int)} でこの範囲を変えられる)。
     */
    public static boolean isCave(LevelReader level, BlockPos pos, int margin) {
        return isCave(level, pos, margin, ROOF_SCAN_LIMIT);
    }

    /**
     * その列の<b>本物の天井</b>(木ではない地形・建築)より {@code margin} 以上深ければ洞窟。
     *
     * <p>列の地表({@code MOTION_BLOCKING_NO_LEAVES})には木の幹も含まれる(過繁茂した葉のように
     * {@code #minecraft:leaves} に入っていない葉も含まれる)ので、地表の高さをそのまま使うと
     * 「木の下」がすべて洞窟になってしまう(巨大樹なら樹冠の上まで洞窟扱いになり、菌の柱や
     * キノコの傘が樹の上に生えていた)。そこで地表から下へ向かって {@link ModTags#NOT_CAVE_ROOF}
     * のブロックと空気を読み飛ばし、最初に見つかった「木以外の固体ブロック」を天井とみなす。
     * {@code roofScanLimit} 段読んでも見つからなければ木に覆われているだけなので洞窟ではない。
     *
     * @param roofScanLimit 天井を探して地表から下へ潜る最大の段数。汚染の装飾のように毎ブロック
     *                      呼ぶ経路では {@value #ROOF_SCAN_LIMIT} 程度に抑える。木の生成のように
     *                      たまにしか呼ばない経路では、巨大樹(高さ 120〜240)の樹冠を突き抜けて
     *                      地面まで届く大きな値を渡してよい。
     */
    public static boolean isCave(LevelReader level, BlockPos pos, int margin, int roofScanLimit) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() >= surface - margin) {
            return false; // 地表付近 —— 天井を探すまでもない(ほとんどの呼び出しはここで終わる)。
        }
        int roof = realRoofY(level, pos, surface, roofScanLimit);
        return roof != Integer.MIN_VALUE && pos.getY() < roof - margin;
    }

    /**
     * {@code surface} から下へ、木({@link ModTags#NOT_CAVE_ROOF})・空気・すり抜けられるもの
     * (下草・蔦・水など)を読み飛ばして最初の「本物の天井」の高さを返す。
     * 見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int realRoofY(LevelReader level, BlockPos pos, int surface, int roofScanLimit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int bottom = Math.max(pos.getY(), surface - roofScanLimit);
        for (int y = surface - 1; y > bottom; y--) {
            cursor.set(pos.getX(), y, pos.getZ());
            BlockState state = level.getBlockState(cursor);
            // 木(NOT_CAVE_ROOF)・空気に加えて、すり抜けられるもの(下草・蔦・垂れ植物・水など)も
            // 天井ではない。樹冠から垂れた植物 1 マスで「屋根がある」と誤判定しないため。
            if (state.isAir() || state.is(ModTags.NOT_CAVE_ROOF) || !state.blocksMotion()) {
                continue;
            }
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // 植物
    // ------------------------------------------------------------------

    /**
     * 湿地系の洞窟で、露出面に隣接する空気マス {@code pos} へ洞窟植物を 1 つ(苔の塊なら数個)生やす。
     *
     * @param exposed 宿主から見て {@code pos} がある方向(UP = 床の上、DOWN = 天井の下、水平 = 壁の横)
     */
    public static void placePlant(ServerLevel level, BlockPos pos, Direction exposed, RandomSource random) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (random.nextDouble() < cfg.caveSporeSacShare.get()) {
            placeSurfaceGrowth(level, pos, exposed, ModBlocks.CAVE_SPORE_SAC.get(), random);
            return;
        }
        int min = Math.max(1, cfg.caveMossClumpMinPatch.get());
        int max = Math.max(min, cfg.caveMossClumpMaxPatch.get());
        int count = min + random.nextInt(max - min + 1);
        placeMossPatch(level, pos, exposed, count, random);
    }

    /** 面に取り付く塊状の植物を 1 つ置く。取り付けなければ何もしない。 */
    private static boolean placeSurfaceGrowth(ServerLevel level, BlockPos pos, Direction exposed, Block block,
                                              RandomSource random) {
        if (!(block instanceof CaveSurfaceGrowthBlock growth)) {
            return false;
        }
        // キノコの傘・菌の柱・発光菌糸には取り付かない(それらの表面は薄い苔・ヒカリゴケだけ)。
        BlockPos support = pos.relative(exposed.getOpposite());
        if (level.isLoaded(support) && isFungalStructure(level.getBlockState(support))) {
            return false;
        }
        BlockState state = growth.stateFor(exposed, Direction.Plane.HORIZONTAL.getRandomDirection(random));
        if (!state.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    /**
     * 苔の塊を {@code pos} から面に沿って {@code count} 個まで広げる。面と平行な 4 方向へランダムに歩き、
     * 1 段の段差(取り付き面側 / 反対側)までは面を追う。空気でない・支えが無いマスは飛ばす。
     */
    private static void placeMossPatch(ServerLevel level, BlockPos pos, Direction exposed, int count, RandomSource random) {
        Block clump = ModBlocks.CAVE_MOSS_CLUMP.get();
        if (!placeSurfaceGrowth(level, pos, exposed, clump, random)) {
            return;
        }
        Direction attach = exposed.getOpposite();
        Direction[] tangents = tangentsOf(exposed);
        BlockPos current = pos;
        int placed = 1;
        for (int attempt = 0; attempt < count * 3 && placed < count; attempt++) {
            BlockPos next = current.relative(tangents[random.nextInt(tangents.length)]);
            BlockPos chosen = null;
            for (BlockPos candidate : new BlockPos[]{next, next.relative(attach), next.relative(exposed)}) {
                if (!level.isLoaded(candidate) || !level.getBlockState(candidate).isAir()) {
                    continue;
                }
                if (placeSurfaceGrowth(level, candidate, exposed, clump, random)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen != null) {
                placed++;
                current = chosen;
            } else if (level.isLoaded(next) && level.getBlockState(next).is(clump)) {
                current = next; // 既に置いた塊の上を歩いて先へ
            }
        }
    }

    /** {@code exposed} に垂直な 4 方向(面に沿って歩く方向)。 */
    private static Direction[] tangentsOf(Direction exposed) {
        return switch (exposed.getAxis()) {
            case Y -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case X -> new Direction[]{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
            default -> new Direction[]{Direction.UP, Direction.DOWN, Direction.WEST, Direction.EAST};
        };
    }

    // ------------------------------------------------------------------
    // 構造物
    // ------------------------------------------------------------------

    /**
     * 汚染したばかりの洞窟のブロック {@code host} から、確率 {@code caveStructureChance} で構造物を 1 つ建てる。
     * 露出している面で候補が決まる: 壁(水平の露出面)→ キノコの傘、床(上面が空気)→ 菌の柱、
     * 天井(下面が空気)→ 根 / 蔦のアーチ。密林の洞窟はアーチのみ。
     */
    public static void tryStructure(ServerLevel level, BlockPos host, BlockState hostState, RandomSource random, Mode mode) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double chance = cfg.caveStructureChance.get();
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return;
        }
        if (hostState.is(ModTags.GROWTH_HANGING_ONLY)) {
            return; // 葉などは宿主にしない
        }

        boolean floor = isAirLike(level, host.above());
        boolean ceiling = isAirLike(level, host.below());
        List<Direction> walls = new ArrayList<>(4);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isAirLike(level, host.relative(d))) {
                walls.add(d);
            }
        }

        boolean swamp = mode == Mode.SWAMP;
        int wCap = swamp && !walls.isEmpty() ? Math.max(0, cfg.caveCapWeight.get()) : 0;
        int wPillar = swamp && floor ? Math.max(0, cfg.cavePillarWeight.get()) : 0;
        int wArch = ceiling && (swamp || cfg.caveRootArchInJungle.get()) ? Math.max(0, cfg.caveArchWeight.get()) : 0;
        int total = wCap + wPillar + wArch;
        if (total <= 0) {
            return;
        }
        int roll = random.nextInt(total);
        if (roll < wCap) {
            buildWallCap(level, host, walls.get(random.nextInt(walls.size())), random, mode);
        } else if (roll < wCap + wPillar) {
            buildPillar(level, host.above(), random, mode);
        } else {
            buildCeilingArch(level, host, random, mode);
        }
    }

    /**
     * 壁から張り出す巨大なキノコの傘。宿主 {@code host}(壁のブロック)の {@code out} 側へ、壁に接した
     * 半円(半径 {@code caveCapMinRadius}〜{@code caveCapMaxRadius})の傘を出す。中心ほど厚く
     * ({@code caveCapThickness} 段)、縁へ向かって薄くなりながら {@code caveCapDroop} だけ垂れ下がる。
     * 傘の下、壁との接点に短い柄({@code fungal_pillar})を添える。
     *
     * @return 建てられたら true
     */
    public static boolean buildWallCap(ServerLevel level, BlockPos host, Direction out, RandomSource random, Mode mode) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int minR = Math.max(1, cfg.caveCapMinRadius.get());
        int maxR = Math.max(minR, cfg.caveCapMaxRadius.get());
        int radius = minR + random.nextInt(maxR - minR + 1);
        int thickness = Math.max(1, cfg.caveCapThickness.get());
        double droop = Math.max(0.0D, cfg.caveCapDroop.get());
        int sagMax = (int) Math.ceil(droop);

        // 壁であること(上下も壁)と、張り出す空間があること(中心線が半径 +1 まで空気)。
        if (!isSolid(level, host.above()) || !isSolid(level, host.below())) {
            return false;
        }
        for (int v = 1; v <= radius + 1; v++) {
            for (int dy = -sagMax; dy < thickness; dy++) {
                if (!isAirLike(level, host.relative(out, v).above(dy))) {
                    return false;
                }
            }
        }

        Direction side = out.getClockWise();
        BlockState cap = ModBlocks.FUNGAL_CAP.get().defaultBlockState();
        List<BlockPos> shell = new ArrayList<>();
        for (int v = 1; v <= radius; v++) {
            for (int u = -radius; u <= radius; u++) {
                double d = Math.sqrt((double) (u * u + v * v)) / radius;
                if (d > 1.0D) {
                    continue;
                }
                int rows = (int) Math.round(thickness * (1.0D - d * d));
                int sag = (int) Math.round(droop * d * d * d);
                int yBottom = host.getY() - sag;
                int yTop = yBottom + Math.max(0, rows - 1);
                for (int y = yBottom; y <= yTop; y++) {
                    BlockPos p = new BlockPos(host.getX() + out.getStepX() * v + side.getStepX() * u, y,
                            host.getZ() + out.getStepZ() * v + side.getStepZ() * u);
                    if (isAirLike(level, p)) {
                        level.setBlock(p, cap, Block.UPDATE_ALL);
                        shell.add(p);
                    }
                }
            }
        }
        if (shell.isEmpty()) {
            return false;
        }

        // 柄: 傘の下、壁との接点(v = 1〜2, |u| ≤ 1)を 1 段下まで菌の柱で埋めて壁につなぐ。
        BlockState stem = ModBlocks.FUNGAL_PILLAR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, out.getAxis());
        int stemReach = Math.min(2, radius);
        for (int v = 1; v <= stemReach; v++) {
            for (int u = -1; u <= 1; u++) {
                BlockPos p = new BlockPos(host.getX() + out.getStepX() * v + side.getStepX() * u, host.getY() - 1,
                        host.getZ() + out.getStepZ() * v + side.getStepZ() * u);
                if (isAirLike(level, p) && level.getBlockState(p.above()).is(ModBlocks.FUNGAL_CAP.get())) {
                    level.setBlock(p, stem, Block.UPDATE_ALL);
                }
            }
        }
        decorateShell(level, shell, random, mode, true);
        return true;
    }

    /**
     * 天井のアーチを 1 つ建てる。種類は {@code caveVineArchShare} の割合で {@link ArchStyle#VINE}(蔦)、
     * 残りが {@link ArchStyle#ROOT}(根)。
     *
     * @return 建てられたら true
     */
    public static boolean buildCeilingArch(ServerLevel level, BlockPos host, RandomSource random, Mode mode) {
        double vineShare = OvergrownVegetationConfig.COMMON.caveVineArchShare.get();
        ArchStyle style = vineShare > 0.0D && random.nextDouble() < vineShare ? ArchStyle.VINE : ArchStyle.ROOT;
        return buildCeilingArch(level, host, random, mode, style);
    }

    /**
     * 天井から垂れて再び天井へ戻る太いアーチ。宿主 {@code host}(天井のブロック)の真下から
     * ランダムな水平方向へ {@code caveArchMinSpan}〜{@code caveArchMaxSpan} 離れた天井まで、
     * 中央で {@code span × dip} だけ垂れ下がる正弦カーブを描く。芯の太さは両端
     * {@code caveArchMaxThickness}、中央 {@code caveArchMinThickness}(塊の半径)。
     * 両端の天井ブロックも芯のブロックに変えて、岩に食い込んで見えるようにする。
     *
     * <p>{@link ArchStyle#ROOT} は {@code giant_overgrown_log} の裸の根で、垂れ下がりは
     * {@code caveArchDip}。{@link ArchStyle#VINE} は樹皮だけの蔓({@code overgrown_wood} /
     * 湿地系では {@code overgrown_swamp_wood})を芯にして、その周り {@code caveVineArchLeafRadius}
     * マスを {@code caveVineArchLeafChance} の確率で過繁茂した葉が包む。蔦は自重で垂れる想定なので
     * 垂れ下がりは別キー {@code caveVineArchDip}(既定では根より深い)。
     *
     * @return 建てられたら true
     */
    public static boolean buildCeilingArch(ServerLevel level, BlockPos host, RandomSource random, Mode mode,
                                           ArchStyle style) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        boolean vine = style == ArchStyle.VINE;
        int minSpan = Math.max(2, cfg.caveArchMinSpan.get());
        int maxSpan = Math.max(minSpan, cfg.caveArchMaxSpan.get());
        int span = minSpan + random.nextInt(maxSpan - minSpan + 1);
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        int minT = Math.max(0, cfg.caveArchMinThickness.get());
        int maxT = Math.max(minT, cfg.caveArchMaxThickness.get());
        double dip = span * Math.max(0.0D, (vine ? cfg.caveVineArchDip : cfg.caveArchDip).get());
        int leafR = vine ? Math.max(0, cfg.caveVineArchLeafRadius.get()) : 0;

        int ax = host.getX();
        int ay = host.getY();
        int az = host.getZ();
        int bx = ax + dir.getStepX() * span;
        int bz = az + dir.getStepZ() * span;
        int by = findCeilingY(level, bx, bz, ay);
        if (by == Integer.MIN_VALUE) {
            return false;
        }

        // 経路上に空間があるか(数点サンプル)。最下点の下も空いていること(床に着いた根・蔦にしない)。
        for (double s : new double[]{0.15D, 0.3D, 0.5D, 0.7D, 0.85D}) {
            int x = ax + (int) Math.round(dir.getStepX() * span * s);
            int z = az + (int) Math.round(dir.getStepZ() * span * s);
            int y = archY(s, ay, by, dip);
            if (!isAirLike(level, new BlockPos(x, y, z))) {
                return false;
            }
        }
        int midX = ax + dir.getStepX() * (span / 2);
        int midZ = az + dir.getStepZ() * (span / 2);
        if (!isAirLike(level, new BlockPos(midX, archY(0.5D, ay, by, dip) - maxT - leafR - 1, midZ))) {
            return false;
        }

        BlockState core = vine
                ? (mode == Mode.SWAMP ? ModBlocks.OVERGROWN_SWAMP_WOOD : ModBlocks.OVERGROWN_WOOD).get()
                        .defaultBlockState().setValue(RotatedPillarBlock.AXIS, dir.getAxis())
                : ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        List<BlockPos> shell = new ArrayList<>();
        for (int i = 0; i <= span; i++) {
            double s = (double) i / span;
            int cx = ax + dir.getStepX() * i;
            int cz = az + dir.getStepZ() * i;
            int cy = archY(s, ay, by, dip);
            int r = (int) Math.round(maxT + (minT - maxT) * Math.sin(Math.PI * s));
            blob(level, cx, cy, cz, r, core, shell);
        }
        if (shell.isEmpty()) {
            return false;
        }
        // 蔦は芯の周りを葉が包む。芯はもう置いてあるので、空気になっているマスだけが葉になる。
        if (leafR > 0) {
            BlockState leaves = (mode == Mode.SWAMP ? ModBlocks.OVERGROWN_SWAMP_LEAVES : ModBlocks.OVERGROWN_LEAVES)
                    .get().defaultBlockState();
            double leafChance = cfg.caveVineArchLeafChance.get();
            for (int i = 0; i <= span; i++) {
                double s = (double) i / span;
                int cx = ax + dir.getStepX() * i;
                int cz = az + dir.getStepZ() * i;
                int cy = archY(s, ay, by, dip);
                int r = (int) Math.round(maxT + (minT - maxT) * Math.sin(Math.PI * s));
                blob(level, cx, cy, cz, r + leafR, leaves, shell, random, leafChance);
            }
        }
        // 両端の天井ブロックを芯のブロックに(岩から根・蔦が生えている見た目)。
        replaceAnchor(level, host, core);
        replaceAnchor(level, new BlockPos(bx, by, bz), core);
        decorateShell(level, shell, random, mode, false);
        return true;
    }

    /** 天井アーチの中心線の Y。両端の天井の 1 つ下を線形補間し、中央で {@code dip} 垂れる。 */
    private static int archY(double s, int ay, int by, double dip) {
        return (int) Math.round(Mth.lerp(s, ay, by) - 1.0D - dip * Math.sin(Math.PI * s));
    }

    /**
     * {@code (x, z)} 列で {@code refY ± }{@value #ARCH_CEILING_SEARCH} の範囲にある天井
     * (下面が空気に接した固体ブロック)の Y。低い方から探す。無ければ {@link Integer#MIN_VALUE}。
     */
    private static int findCeilingY(ServerLevel level, int x, int z, int refY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = refY - ARCH_CEILING_SEARCH; y <= refY + ARCH_CEILING_SEARCH; y++) {
            cursor.set(x, y, z);
            if (!isSolid(level, cursor)) {
                continue;
            }
            cursor.set(x, y - 1, z);
            if (isAirLike(level, cursor)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void replaceAnchor(ServerLevel level, BlockPos pos, BlockState log) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState current = level.getBlockState(pos);
        if (current.isAir() || current.getBlock().defaultDestroyTime() < 0.0F) {
            return;
        }
        level.setBlock(pos, log, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
    }

    /**
     * 床から立つ菌の柱。必ず天井と床を継ぐ(高さの上限は無い)。{@code base}(床の上の空気マス)から
     * 真上の天井までを 1 本の柱で埋め、根元と天井側の両方に裾の広がりを付けて岩に溶け込ませる。
     * 真上が天井で終わっていない場所(ワールド上限まで空、未ロード、水などで埋まっている)や、
     * 天井が {@code cavePillarMinHeight} より低い場所には立てない。
     *
     * <p>半径は根元 {@code cavePillarBaseRadius} → 先端 {@code cavePillarTopRadius} へ滑らかに
     * 細くなり、上下 {@code cavePillarFlareRows} 段は裾 {@code cavePillarFlare} で広がる。各ブロックは
     * {@code cavePillarGlowChance} で発光菌糸({@code glowing_mycelium})になる。根元の円盤は地面まで
     * 下へ埋める({@link #anchorToGround})。
     *
     * @return 建てられたら true
     */
    public static boolean buildPillar(ServerLevel level, BlockPos base, RandomSource random, Mode mode) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int minH = Math.max(1, cfg.cavePillarMinHeight.get());

        // 真上の天井までの空き高さ。高さの上限は設けず、天井(またはワールド上限)まで数える。
        int free = 0;
        int limit = level.getMaxBuildHeight() - base.getY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (free < limit) {
            cursor.set(base.getX(), base.getY() + free, base.getZ());
            if (!isAirLike(level, cursor)) {
                break;
            }
            free++;
        }
        // 柱は必ず天井と床を継ぐので、真上が天井で終わっていない場所(ワールド上限まで空、
        // 未ロード、水などで埋まっている)や、天井が minH より低い場所には立てない。
        if (free >= limit || free < minH || !isSolid(level, cursor)) {
            return false;
        }
        int height = free;

        double baseR = Math.max(0.0D, cfg.cavePillarBaseRadius.get());
        double topR = Math.max(0.0D, Math.min(baseR, cfg.cavePillarTopRadius.get()));
        int flareRows = Math.max(0, cfg.cavePillarFlareRows.get());
        double flare = Math.max(0.0D, cfg.cavePillarFlare.get());
        double glow = cfg.cavePillarGlowChance.get();

        BlockState pillar = ModBlocks.FUNGAL_PILLAR.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState mycelium = ModBlocks.GLOWING_MYCELIUM.get().defaultBlockState();
        List<BlockPos> shell = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            double t = height <= 1 ? 1.0D : (double) i / (double) (height - 1);
            double radius = baseR + (topR - baseR) * Math.pow(t, 0.8D);
            if (i < flareRows) {
                radius += flare * (1.0D - (double) i / Math.max(1, flareRows));
            }
            if (i >= height - flareRows) {
                radius += flare * (1.0D - (double) (height - 1 - i) / Math.max(1, flareRows));
            }
            int y = base.getY() + i;
            int ir = Mth.ceil(radius);
            double edgeSq = (radius + 0.5D) * (radius + 0.5D);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dz * dz > edgeSq) {
                        continue;
                    }
                    BlockPos p = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                    if (!isAirLike(level, p)) {
                        continue;
                    }
                    BlockState state = glow > 0.0D && random.nextDouble() < glow ? mycelium : pillar;
                    level.setBlock(p, state, Block.UPDATE_ALL);
                    shell.add(p);
                    if (i == 0) {
                        anchorToGround(level, p, pillar);
                    }
                }
            }
        }
        if (shell.isEmpty()) {
            return false;
        }
        decorateShell(level, shell, random, mode, true);
        return true;
    }

    /** 根元の円盤の各列を、地面に着くまで({@value #ANCHOR_DEPTH} まで)柱ブロックで下へ埋める。 */
    private static void anchorToGround(ServerLevel level, BlockPos base, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= ANCHOR_DEPTH; d++) {
            cursor.set(base.getX(), base.getY() - d, base.getZ());
            if (!isAirLike(level, cursor)) {
                return;
            }
            level.setBlock(cursor, state, Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    /** {@code (cx, cy, cz)} を中心に半径 {@code r}(+0.35)の角の落ちた塊を置く。空気(と草などの上書き可能ブロック)だけを埋める。 */
    private static void blob(ServerLevel level, int cx, int cy, int cz, int r, BlockState block, List<BlockPos> shellOut) {
        blob(level, cx, cy, cz, r, block, shellOut, null, 1.0D);
    }

    /**
     * {@link #blob(ServerLevel, int, int, int, int, BlockState, List)} の、マスごとに
     * {@code chance} で間引く版({@code random} が null なら間引かない)。蔦のアーチの葉を房状にするのに使う。
     */
    private static void blob(ServerLevel level, int cx, int cy, int cz, int r, BlockState block, List<BlockPos> shellOut,
                             RandomSource random, double chance) {
        double edgeSq = (r + 0.35D) * (r + 0.35D);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dy * dy + dz * dz) > edgeSq) {
                        continue;
                    }
                    if (random != null && chance < 1.0D && random.nextDouble() >= chance) {
                        continue;
                    }
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!isAirLike(level, p)) {
                        continue;
                    }
                    level.setBlock(p, block, Block.UPDATE_ALL);
                    shellOut.add(p);
                }
            }
        }
    }

    /**
     * 建てた構造物の表面に植物を添える。各ブロックの各面について確率 {@code caveStructurePlantChance} で:
     * <ul>
     *     <li>{@code fungal}(キノコの傘・菌の柱)— 薄い苔({@code overgrown_moss_layer})か、
     *     {@code caveStructureGlowLichenShare} の割合でヒカリゴケ({@code minecraft:glow_lichen})の面を足す。
     *     胞子嚢・苔の塊はキノコには付かない(要望)。</li>
     *     <li>根・蔦のアーチ(蔦は葉の部分も含む)— 湿地系なら洞窟植物({@link #placePlant})、
     *     密林なら下面の垂れ植物だけ。</li>
     * </ul>
     */
    private static void decorateShell(ServerLevel level, List<BlockPos> shell, RandomSource random, Mode mode,
                                      boolean fungal) {
        double chance = OvergrownVegetationConfig.COMMON.caveStructurePlantChance.get();
        if (chance <= 0.0D || !OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }
        double lichenShare = OvergrownVegetationConfig.COMMON.caveStructureGlowLichenShare.get();
        for (BlockPos pos : shell) {
            for (Direction direction : Direction.values()) {
                if (random.nextDouble() >= chance) {
                    continue;
                }
                BlockPos target = pos.relative(direction);
                if (!level.isLoaded(target)) {
                    continue;
                }
                if (fungal) {
                    Block face = random.nextDouble() < lichenShare ? Blocks.GLOW_LICHEN : ModBlocks.OVERGROWN_MOSS_LAYER.get();
                    placeMultifaceFace(level, target, direction.getOpposite(), face);
                    continue;
                }
                if (!level.getBlockState(target).isAir()) {
                    continue;
                }
                if (mode == Mode.SWAMP) {
                    placePlant(level, target, direction, random);
                } else if (direction == Direction.DOWN) {
                    BlockState hanging = CorruptionGrowth.randomHanging(random);
                    if (hanging.canSurvive(level, target)) {
                        level.setBlock(target, hanging, Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /**
     * {@code pos}(空気、または既に同じ多面ブロックがあるマス)へ、{@code attachDir} 側の面を 1 つ足す
     * ({@link CorruptionSpreader} の薄い苔の生やし方と同じ)。別種の多面ブロックが既にあるマスには何もしない。
     */
    private static void placeMultifaceFace(ServerLevel level, BlockPos pos, Direction attachDir, Block block) {
        BlockState existing = level.getBlockState(pos);
        BooleanProperty faceProp = MultifaceBlock.getFaceProperty(attachDir);
        BlockState next;
        if (existing.is(block)) {
            if (existing.getValue(faceProp)) {
                return;
            }
            next = existing.setValue(faceProp, true);
        } else if (existing.isAir()) {
            next = block.defaultBlockState().setValue(faceProp, true);
        } else {
            return;
        }
        BlockPos supportPos = pos.relative(attachDir);
        if (!MultifaceBlock.canAttachTo(level, attachDir, supportPos, level.getBlockState(supportPos))) {
            return;
        }
        level.setBlock(pos, next, Block.UPDATE_ALL);
    }

    /** キノコの傘・菌の柱・発光菌糸(洞窟植物が取り付いてはいけないブロック)。 */
    private static boolean isFungalStructure(BlockState state) {
        return state.is(ModBlocks.FUNGAL_CAP.get()) || state.is(ModBlocks.FUNGAL_PILLAR.get())
                || state.is(ModBlocks.GLOWING_MYCELIUM.get());
    }

    /** 空気、または草・下草のような上書き可能ブロック(液体は除く)。 */
    private static boolean isAirLike(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** 上に乗れる固体(壁・床・天井の岩)。 */
    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.blocksMotion() && !state.canBeReplaced();
    }
}
