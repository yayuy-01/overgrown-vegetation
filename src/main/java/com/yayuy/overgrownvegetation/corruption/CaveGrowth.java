package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 *     <li><b>湿地・菌湿地帯</b>({@link Mode#SWAMP}) — 植物はいつも通りで、天井のアーチを
 *     ({@code caveRootArchInJungle} に関わらず)建てる。壁のキノコの傘・床の菌の柱は 2026-10-31 に parked へ退避(U11)、
 *     洞窟専用の植物(微光の胞子嚢・苔の塊)は 2026-11-01 に削除(地下の植生は作り直す)。</li>
 *     <li><b>密林</b>({@link Mode#JUNGLE}) — 植物はいつも通りで、天井のアーチだけを建てる
 *     ({@code caveRootArchInJungle}、既定 true)。</li>
 * </ul>
 *
 * <p>天井のアーチには 2 種類ある({@link ArchStyle})。{@code caveVineArchShare} の割合で
 * <b>蔦</b>のアーチ(樹皮だけの蔓 {@code overgrown_wood} を {@code overgrown_leaves} の葉が包む。
 * 湿地系の専用の木は 2026-10-31 に退避したので同じ)、残りが今までどおりの
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
        /** 湿地・菌湿地帯の洞窟: 天井のアーチ(caveRootArchInJungle に関わらず)。 */
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
        int bottom = Math.max(pos.getY(), surface - roofScanLimit);
        LevelChunk chunk = level instanceof ServerLevel server
                ? server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) : null;
        if (chunk != null) {
            return realRoofYBySection(chunk, pos.getX(), pos.getZ(), surface, bottom);
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = surface - 1; y > bottom; y--) {
            cursor.set(pos.getX(), y, pos.getZ());
            if (isRealRoof(level.getBlockState(cursor))) {
                return y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * {@link #realRoofY} の、読み込み済みのチャンクを<b>区画(16×16×16 のセクション)単位</b>で下る版(2026-10-06、③)。結果は 1 マスずつ読むのと同じ。
     *
     * <p>天井探しは木の下の列で樹冠を最大 48 段読み下ろす。地表の書き換えで植物を生やすたびに走り、計測で植物を生やす時間の半分を占めていた。
     * 区画の<b>ブロックの種類表(パレット)に天井になれるものが 1 つも無ければ</b>(空・葉・幹・空気だけの区画)、その区画は 1 マスも読まずに飛ばす。
     * 種類表に候補があるときだけ、その区画の中を 1 マスずつ見る(区画を直に読むので、マスごとにチャンクを引き直さない)。
     */
    private static int realRoofYBySection(LevelChunk chunk, int x, int z, int surface, int bottom) {
        int lx = x & 15;
        int lz = z & 15;
        int y = surface - 1;
        while (y > bottom) {
            int index = chunk.getSectionIndex(y);
            if (index < 0 || index >= chunk.getSectionsCount()) {
                y--; // 高さの外(地表の高さ地図が上端のとき)。空気と同じ
                continue;
            }
            LevelChunkSection section = chunk.getSection(index);
            int sectionBottom = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            if (section.hasOnlyAir() || !section.maybeHas(CaveGrowth::isRealRoof)) {
                y = sectionBottom - 1;
                continue;
            }
            for (; y >= sectionBottom && y > bottom; y--) {
                if (isRealRoof(section.getBlockState(lx, y & 15, lz))) {
                    return y + 1;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * 天井になれるブロックか。木(NOT_CAVE_ROOF)・空気に加えて、すり抜けられるもの(下草・蔦・垂れ植物・水など)も
     * 天井ではない。樹冠から垂れた植物 1 マスで「屋根がある」と誤判定しないため。
     */
    private static boolean isRealRoof(BlockState state) {
        return !state.isAir() && !state.is(ModTags.NOT_CAVE_ROOF) && state.blocksMotion();
    }

    // ------------------------------------------------------------------
    // 構造物
    // ------------------------------------------------------------------

    /**
     * 汚染したばかりの洞窟のブロック {@code host} から、確率 {@code caveStructureChance} で構造物を 1 つ建てる。
     * 天井(下面が空気)→ 根 / 蔦のアーチ。湿地系の壁のキノコの傘・床の菌の柱は 2026-10-31 に parked へ退避(U11)。
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

        boolean ceiling = isAirLike(level, host.below());
        boolean swamp = mode == Mode.SWAMP;
        int wArch = ceiling && (swamp || cfg.caveRootArchInJungle.get()) ? Math.max(0, cfg.caveArchWeight.get()) : 0;
        if (wArch <= 0) {
            return;
        }
        buildCeilingArch(level, host, random, mode);
    }

    /**
     * 天井のアーチを 1 つ建てる。種類は {@code caveVineArchShare} の割合で {@link ArchStyle#VINE}(蔦)、
     * 残りが {@link ArchStyle#ROOT}(根)。
     *
     * @return 建てられたら true
     */
    public static boolean buildCeilingArch(ServerLevel level, BlockPos host, RandomSource random, Mode mode) {
        // アーチの代わりに天井から斜めに垂れる根の先端(2026-10-09、ユーザー指定「アーチ:根の先端 = 7:3、合計は今のアーチと同じ」)。
        if (random.nextDouble() < CorruptionRootTip.SHARE) {
            return buildHangingRootTip(level, host, random, mode);
        }
        // 蔦のアーチ(ArchStyle.VINE)は 2026-11-07 に止めた(ユーザー決定): 蔦は壁と壁の隙間に渡る太い蔦(VineSpan、
        // CorruptionSpreader#growSurfacePlants から)に置き換え。caveVineArchShare の既定も 0。値が残っていても自然には建てない。
        return buildCeilingArch(level, host, random, mode, ArchStyle.ROOT);
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
        // 向きはどの水平の角度でも(2026-10-09、ユーザー指定。それまでは東西南北のどれか)。
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double ux = Math.cos(angle);
        double uz = Math.sin(angle);
        int minT = Math.max(0, cfg.caveArchMinThickness.get());
        int maxT = Math.max(minT, cfg.caveArchMaxThickness.get());
        double dip = span * Math.max(0.0D, (vine ? cfg.caveVineArchDip : cfg.caveArchDip).get());
        int leafR = vine ? Math.max(0, cfg.caveVineArchLeafRadius.get()) : 0;

        int ax = host.getX();
        int ay = host.getY();
        int az = host.getZ();
        int bx = ax + (int) Math.round(ux * span);
        int bz = az + (int) Math.round(uz * span);
        int dx = bx - ax;
        int dz = bz - az;
        // 縦横の長い方の軸で 1 マスずつ進む(南北・東西なら今までと同じ歩み)。
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps < 2) {
            return false;
        }
        int by = findCeilingY(level, bx, bz, ay);
        if (by == Integer.MIN_VALUE) {
            return false;
        }

        // 経路上に空間があるか(数点サンプル)。最下点の下も空いていること(床に着いた根・蔦にしない)。
        for (double s : new double[]{0.15D, 0.3D, 0.5D, 0.7D, 0.85D}) {
            int x = ax + (int) Math.round(dx * s);
            int z = az + (int) Math.round(dz * s);
            int y = archY(s, ay, by, dip);
            if (!isAirLike(level, new BlockPos(x, y, z))) {
                return false;
            }
        }
        int midX = ax + (int) Math.round(dx * 0.5D);
        int midZ = az + (int) Math.round(dz * 0.5D);
        if (!isAirLike(level, new BlockPos(midX, archY(0.5D, ay, by, dip) - maxT - leafR - 1, midZ))) {
            return false;
        }

        Direction.Axis axis = Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
        BlockState core = vine
                ? ModBlocks.OVERGROWN_WOOD.get()
                        .defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis)
                : ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        List<BlockPos> shell = new ArrayList<>();
        int px = ax;
        int pz = az;
        for (int i = 0; i <= steps; i++) {
            double s = (double) i / steps;
            int cx = ax + (int) Math.round(dx * s);
            int cz = az + (int) Math.round(dz * s);
            int cy = archY(s, ay, by, dip);
            int r = (int) Math.round(maxT + (minT - maxT) * Math.sin(Math.PI * s));
            if (r == 0 && i > 0 && cx != px && cz != pz) {
                // 1 マスの細さで斜めに 1 歩 —— 角だけで接すると隙間に見えるので間に 1 マス足して面でつなぐ。
                blob(level, cx, cy, pz, 0, core, shell);
            }
            blob(level, cx, cy, cz, r, core, shell);
            px = cx;
            pz = cz;
        }
        if (shell.isEmpty()) {
            return false;
        }
        // 蔦は芯の周りを葉が包む。芯はもう置いてあるので、空気になっているマスだけが葉になる。
        if (leafR > 0) {
            BlockState leaves = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
            double leafChance = cfg.caveVineArchLeafChance.get();
            for (int i = 0; i <= steps; i++) {
                double s = (double) i / steps;
                int cx = ax + (int) Math.round(dx * s);
                int cz = az + (int) Math.round(dz * s);
                int cy = archY(s, ay, by, dip);
                int r = (int) Math.round(maxT + (minT - maxT) * Math.sin(Math.PI * s));
                blob(level, cx, cy, cz, r + leafR, leaves, shell, random, leafChance);
            }
        }
        // 両端の天井ブロックを芯のブロックに(岩から根・蔦が生えている見た目)。
        replaceAnchor(level, host, core);
        replaceAnchor(level, new BlockPos(bx, by, bz), core);
        decorateShell(level, shell, random);
        return true;
    }

    /**
     * 天井から斜めに垂れ下がる根の先端を 1 本({@link CorruptionRootTip}、2026-10-09 ユーザー指定)。宿主 {@code host}(天井のブロック)に
     * 根元を埋め、どの水平の向きへでも斜めに垂れて、先ほど下を向く。経路が空いていなければ(床・壁・水に当たる)建てない。
     * 天井のブロックは根のブロックに変える(アーチの両端と同じ、岩から生えている見た目)。
     *
     * @return 建てられたら true
     */
    public static boolean buildHangingRootTip(ServerLevel level, BlockPos host, RandomSource random, Mode mode) {
        CorruptionRootTip.Shape shape = CorruptionRootTip.rollHanging(random);
        List<CorruptionRootTip.Cell> cells = CorruptionRootTip.plan(host.getX() + 0.5D, host.getY() + 0.5D, host.getZ() + 0.5D, shape);
        if (!CorruptionRootTip.pathIsOpen(cells, pos -> isAirLike(level, pos))) {
            return false;
        }
        BlockState log = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
        List<BlockPos> shell = new ArrayList<>();
        if (!CorruptionRootTip.place(level, cells, log, new it.unimi.dsi.fastutil.longs.LongOpenHashSet(), shell)) {
            return false;
        }
        replaceAnchor(level, host, log);
        decorateShell(level, shell, random);
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
     * 建てた構造物(根・蔦のアーチ。蔦は葉の部分も含む)の下面に垂れ植物を添える。
     * 各ブロックの下面について確率 {@code caveStructurePlantChance} で。
     * 湿地系の洞窟植物(胞子嚢・苔の塊)は 2026-11-01 に削除したので、どの洞窟でも同じ。
     */
    private static void decorateShell(ServerLevel level, List<BlockPos> shell, RandomSource random) {
        double chance = OvergrownVegetationConfig.COMMON.caveStructurePlantChance.get();
        if (chance <= 0.0D || !OvergrownVegetationConfig.COMMON.spawnGrowthPlants.get()) {
            return;
        }
        for (BlockPos pos : shell) {
            if (random.nextDouble() >= chance) {
                continue;
            }
            BlockPos target = pos.below();
            if (!level.isLoaded(target) || !level.getBlockState(target).isAir()) {
                continue;
            }
            BlockState hanging = CorruptionGrowth.randomHanging(random);
            if (hanging.canSurvive(level, target)) {
                level.setBlock(target, hanging, Block.UPDATE_ALL);
            }
        }
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
