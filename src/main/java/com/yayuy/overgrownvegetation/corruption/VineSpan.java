package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 壁と壁の隙間に渡る太い蔦(2026-11-07、Vault「巨大樹の根と根のアーチの作り直し」の節「【決定】壁と壁の隙間に渡る太い蔦」)。
 *
 * <p>ユーザー決定(11-07 の 2 回目): <b>ブロックだけで作る</b>(描画の蔦は使わない)。撚り糸は丸く太く、糸と糸の間を少し空け、
 * <b>1 本ずつ上を歩いて渡れる</b>(段差は跳べば越えられる 1 段まで)。「一部が垂れる」はブロックで: 真ん中で下へ離れる細い撚り糸と、
 * 芯の下から垂れる細い房。ブロックは<b>汚染された葉 {@code overgrown_leaves} だけ</b>(11-07 ユーザー決定「蔦は草ブロックだけで構成されたものにしよう。
 * 蔦ブロックは削除」。それまでの蔦専用ブロック {@code overgrown_liana_trunk}・試作の細い蔦 {@code overgrown_liana_strand} は消した)。
 * 葉では木の葉と区別がつかないので、置いた組の両端は {@link VineSpanRecord} に覚え、近くに組があるかはそこで見る。
 *
 * <p>形: 両岸の壁の間の弦を、ゆるくたわませた中心線にする。歩ける撚り糸(幅で 1〜4 本、{@link #walkCountFor}。真ん中の主の糸を太く、ほかを細く、節で太さが揺れる。11-07 の 3 回目)は中心線の左右に並び、それぞれ自分の並びのまわりを
 * 小さく回る(隣どうしは逆の向き = 互いに撚れて見える)。回る幅は上下に小さく、上面の傾きを抑える。根元は太り、壁へ食い込む。
 *
 * <p>隙間の見つけ方は {@link #findGap}。自然には {@link #tryGrow}({@code CorruptionSpreader#growSurfacePlants} から、汚染した壁ごと)。
 *
 * <p>数値は仮決め(ユーザー未回答)。ユーザー指定は上の「ブロックだけ・丸く太く・間を空ける・1 本ずつ歩ける・一部が垂れる」と
 * 「崖・渓谷・洞窟・壁と壁の隙間で汚染された所だけ」。確率・間隔・幅は config({@code vineSpan*})。
 */
public final class VineSpan {

    /** 向かいの壁の高さのずれを許す範囲(今は水平の線だけ。高さのずれは食い込みで吸収)。 */
    private static final int FAR_DY = 0;

    // ---- 芯(仮決め) ----
    /** たわみ(長さ比)。組ごとに揺らす。端の傾きは 4×dip ≦ 0.44。 */
    private static final double DIP_MIN = 0.05D;
    private static final double DIP_MAX = 0.11D;
    /** 主の撚り糸(真ん中)の半径(ブロック)。長いほど太い。+0.35 で塗るので、半径 1.0 で 3 ブロック幅の丸。 */
    private static final double R_MAIN_MIN = 1.0D;
    private static final double R_MAIN_MAX = 1.5D;
    /** ほかの撚り糸の半径。主より細い。 */
    private static final double R_SIDE_MIN = 0.7D;
    private static final double R_SIDE_MAX = 1.2D;
    /** この半径より細い糸は渡れなくてよい(段差を測らない)。天井で細らせてこれを下回る糸は置かない。 */
    private static final double R_WALK = 0.8D;
    private static final double R_FLOOR = 0.7D;
    /** 1 本の中の太さの揺れ(節・瘤)。歩ける糸は ±KNOT_AMP、細い糸は少し小さく。 */
    private static final double KNOT_AMP = 0.25D;
    /** 糸の上面から天井まで、この高さ(ブロック)は空ける(立てる)。 */
    private static final int HEADROOM = 2;
    /** 天井を見ない壁ぎわの長さ(根元の太りと、崖の張り出しの脇。ここは壁そのものに近く、立って渡る所でない)。 */
    private static final double HEADROOM_END = 6.0D;
    /** 糸と糸の間(表面どうし、ブロック)。 */
    private static final double STRAND_GAP = 1.0D;
    /** 撚り: 自分の並びのまわりを回る幅(横・上下)と、1 周の長さ。上下は組ごとに揺らす(上面の傾き = 2π×上下/周 ≦ 0.35)。 */
    private static final double ORBIT_SIDE = 0.55D;
    private static final double ORBIT_UP_MIN = 0.35D;
    private static final double ORBIT_UP_MAX = 0.5D;
    private static final double ORBIT_PITCH_MIN = 10.0D;
    private static final double ORBIT_PITCH_MAX = 17.0D;
    /** 植物: 下面に垂れ植物、房の先、上面の苔・シダ(どれも汚染の成長と同じ種類。量は仮決め)。 */
    private static final double HANG_CHANCE = 0.35D;
    private static final double TUFT_TIP_HANG_CHANCE = 0.6D;
    private static final double TOP_MOSS_CHANCE = 0.10D;
    private static final double TOP_FERN_CHANCE = 0.03D;
    /** 洞窟(空が見えない)では、垂れ植物の一部を光るツタ(1〜3 段)にする。 */
    private static final double CAVE_GLOW_SHARE = 0.3D;
    /** 根元の太りと長さ・壁へ食い込む深さ。 */
    private static final double FLARE = 0.35D;
    private static final double FLARE_LEN = 3.0D;
    private static final double EMBED = 1.6D;
    /** 垂れる細い撚り糸: 半径・真ん中での下がり(長さ比、上限)。 */
    private static final double DROOP_R = 0.5D;
    private static final double DROOP_SAG = 0.12D;
    private static final double DROOP_SAG_MAX = 4.5D;
    /** 垂れる房: 長さ ÷ この数の本数、長さ 2〜6、床から 2 離す。 */
    private static final double TUFT_EVERY = 5.0D;
    private static final int TUFT_MIN = 2;
    private static final int TUFT_MAX = 6;

    private VineSpan() {
    }

    /** 置いた結果。{@code blocks} = 置いたブロック、{@code maxStep} = 歩ける撚り糸の上面の段差の最大(1 なら跳べば渡れる)。 */
    public record Result(boolean ok, String message, int blocks, int strands, int maxStep, double span, int walkable, int thin, int plants) {
        static Result fail(String why) {
            return new Result(false, why, 0, 0, 0, 0.0D, 0, 0, 0);
        }
    }

    /** 見つけた隙間。{@code a}・{@code b} は両岸の壁のブロック(芯が食い込む所)。 */
    public record Gap(BlockPos a, BlockPos b, double span) {
    }

    // ------------------------------------------------------------------
    // 自然に出す
    // ------------------------------------------------------------------

    /**
     * 汚染したばかりのブロック {@code host} から、確率 {@code vineSpanChance} で隙間を探して太い蔦を渡す
     * ({@code CorruptionSpreader#growSurfacePlants} から。洞窟の壁でも、空に開けた崖・渓谷の壁でも)。
     * 近く({@code vineSpanSpacing})にほかの太い蔦があれば渡さない。
     */
    public static boolean tryGrow(ServerLevel level, BlockPos host, RandomSource random) {
        return tryGrow(level, host, random, OvergrownVegetationConfig.COMMON.vineSpanChance.get());
    }

    /** 洞窟の壁を這う汚染から(確率は {@code vineSpanCaveChance}。11-07 ユーザー決定「洞窟だけ確率を上げる」)。 */
    public static boolean tryGrowCave(ServerLevel level, BlockPos host, RandomSource random) {
        CAVE_CALLS++;
        boolean ok = tryGrow(level, host, random, OvergrownVegetationConfig.COMMON.vineSpanCaveChance.get());
        if (ok) {
            CAVE_GROWN++;
        }
        return ok;
    }

    private static boolean tryGrow(ServerLevel level, BlockPos host, RandomSource random, double chance) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.vineSpan.get()) {
            return false;
        }
        CALLS++;
        if (chance <= 0.0D || random.nextDouble() >= chance) {
            return false;
        }
        long t0 = System.nanoTime();
        try {
            STATS[0]++;
            Gap g = findGap(level, host, random, true);
            if (g == null) {
                return false;
            }
            STATS[1]++;
            if (VineSpanRecord.get(level).near(g.a(), g.b(), cfg.vineSpanSpacing.get())) {
                STATS[2]++;
                return false;
            }
            Result res = build(level, g.a(), g.b(), random);
            if (!res.ok()) {
                STATS[3]++;
                if (res.message().contains("天井")) {
                    STATS[4]++;
                }
                LAST_FAIL = res.message();
            }
            boolean ok = res.ok();
            if (ok) {
                STATS[5]++;
                GROWN.add(g); // dev の書き出し用(自然に出た所)
                if (GROWN.size() > 64) {
                    GROWN.remove(0);
                }
            }
            return ok;
        } finally {
            long dt = System.nanoTime() - t0;
            NANOS[0] += dt;
            NANOS[1] = Math.max(NANOS[1], dt);
        }
    }

    /** dev の数え: 抽選に当たった・隙間があった・近くに太い蔦があった・置けなかった・そのうち天井が低い・置けた。と最後の置けなかった訳。 */
    public static final int[] STATS = new int[6];
    public static String LAST_FAIL = "";
    /** dev の数え: tryGrow が呼ばれた回数(抽選の前)、抽選に当たった後の時間の合計と最大(ナノ秒)。 */
    public static long CALLS;
    /** dev の数え: そのうち洞窟の壁を這う汚染(CaveWallCreep)から呼ばれた回数と、そこで置けた組の数。 */
    public static long CAVE_CALLS;
    public static int CAVE_GROWN;
    public static final long[] NANOS = new long[2];

    /** 自然に出た太い蔦(新しい順でなく出た順、最大 64)。dev の書き出し用で、保存しない。 */
    public static final List<Gap> GROWN = java.util.Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------
    // 隙間を探す
    // ------------------------------------------------------------------

    /**
     * {@code host}(壁のブロック)から向かいの壁を探す。見つからなければ null。
     *
     * @param requireCorrupted true なら両端が汚染されていること(自然に出すとき)。dev の試し置きでは false も使う
     */
    @Nullable
    public static Gap findGap(ServerLevel level, BlockPos host, RandomSource random, boolean requireCorrupted) {
        if (!isWall(level, host) || (requireCorrupted && !isCorrupted(level, host))) {
            return null;
        }
        List<Direction> faces = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isAirLike(level, host.relative(d))) {
                faces.add(d);
            }
        }
        if (faces.isEmpty()) {
            return null;
        }
        Direction face = faces.get(random.nextInt(faces.size()));
        double base = Math.atan2(face.getStepZ(), face.getStepX());
        Gap best = null;
        for (double off : new double[]{0.0D, Math.toRadians(25.0D), Math.toRadians(-25.0D)}) {
            Gap g = march(level, host, base + off, requireCorrupted);
            if (g != null && (best == null || Math.abs(g.span() - 18.0D) < Math.abs(best.span() - 18.0D))) {
                best = g;
            }
        }
        return best;
    }

    /** 1 本の水平線を進める。 */
    @Nullable
    private static Gap march(ServerLevel level, BlockPos host, double angle, boolean requireCorrupted) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int minGap = cfg.vineSpanMinGap.get();
        int maxGap = Math.max(minGap, cfg.vineSpanMaxGap.get());
        double ux = Math.cos(angle);
        double uz = Math.sin(angle);
        double sx = host.getX() + 0.5D;
        double sz = host.getZ() + 0.5D;
        int y = host.getY() + FAR_DY;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos far = null;
        double dist = 0.0D;
        for (double d = 1.0D; d <= maxGap + 1.0D; d += 0.5D) {
            m.set(Mth.floor(sx + ux * d), y, Mth.floor(sz + uz * d));
            if (m.getX() == host.getX() && m.getZ() == host.getZ()) {
                continue;
            }
            if (!level.isLoaded(m)) {
                return null;
            }
            if (isAirLike(level, m)) {
                continue;
            }
            far = m.immutable();
            dist = d;
            break;
        }
        if (far == null) {
            return null;
        }
        double centers = Math.sqrt(sq(far.getX() - host.getX()) + sq(far.getZ() - host.getZ())); // 幅 = 両岸の壁のブロックの中心どうし
        if (centers < minGap || centers > maxGap) {
            return null;
        }
        // 向かいは地形の壁であること(葉・幹・1 マスの浮いた石でない)
        if (!isWall(level, far) || (requireCorrupted && !isCorrupted(level, far))) {
            return null;
        }
        // 床ぎわの溝でなく隙間: 線の 1/4・1/2・3/4 の下が 4 + たわみ ぶん空いている。左右 2 も見る(撚り糸が並ぶ幅)
        int need = 4 + (int) Math.ceil(dist * DIP_MAX);
        double hx = -uz;
        double hz = ux;
        for (double s : new double[]{0.25D, 0.5D, 0.75D}) {
            for (int lane = -2; lane <= 2; lane += 2) {
                int x = Mth.floor(sx + ux * dist * s + hx * lane);
                int z = Mth.floor(sz + uz * dist * s + hz * lane);
                for (int dy = 1; dy <= need; dy++) {
                    m.set(x, y - dy, z);
                    if (!isAirLike(level, m)) {
                        return null;
                    }
                }
            }
        }
        return new Gap(host, far, Math.sqrt(sq(far.getX() - host.getX()) + sq(far.getZ() - host.getZ())));
    }

    /** 地形の壁: 固く(木・葉・太い蔦でない)、上か下にも固いブロックが続く。 */
    static boolean isWall(ServerLevel level, BlockPos pos) {
        if (!isSolidTerrain(level, pos)) {
            return false;
        }
        int around = 0;
        for (BlockPos p : new BlockPos[]{pos.above(), pos.below(), pos.above(2), pos.below(2)}) {
            if (isSolidTerrain(level, p)) {
                around++;
            }
        }
        return around >= 2;
    }

    private static boolean isSolidTerrain(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && s.blocksMotion() && !s.canBeReplaced() && !s.is(ModTags.NOT_CAVE_ROOF) && !s.hasBlockEntity();
    }

    /** dev の書き出し用。 */
    public static boolean isCorruptedPublic(ServerLevel level, BlockPos pos) {
        return isCorrupted(level, pos);
    }

    static boolean isCorrupted(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(ModTags.CORRUPTED) || OvergrownLayerData.isCovered(level, pos);
    }

    // ------------------------------------------------------------------
    // 置く
    // ------------------------------------------------------------------

    /** 1 本の撚り糸。点ごとの中心・半径・接線・弦の上の位置 t。{@code base} = 揺れと根元の太りを除いた半径。 */
    private record Strand(double[] x, double[] y, double[] z, double[] r, double[] tx, double[] ty, double[] tz, double[] t,
                          boolean walkable, double lane, double base) {
    }

    /** 撚り糸の形の値(組ごと・糸ごとに揺らす)。 */
    private record Shape(double side, double up, double pitch, double hand, double phase, double extraSag,
                         double knotAmp, double knotWave1, double knotPhase1, double knotWave2, double knotPhase2) {
    }

    /** 隙間の幅で歩ける撚り糸の本数を決める(11-07 ユーザー決定「隙間の大きさに合わせて本数を変える」。幅ごとの本数は仮決め)。 */
    public static int walkCountFor(double span) {
        if (span < 12.0D) {
            return 1;
        }
        if (span < 20.0D) {
            return 2;
        }
        if (span < 30.0D) {
            return 3;
        }
        return 4;
    }

    /**
     * 壁 {@code a} と壁 {@code b} の間に太い蔦を 1 組置く。乱数は呼び出し側から。
     */
    public static Result build(ServerLevel level, BlockPos a, BlockPos b, RandomSource random) {
        Vec3 pa = Vec3.atCenterOf(a);
        Vec3 pb = Vec3.atCenterOf(b);
        double span = Math.sqrt(sq(pb.x - pa.x) + sq(pb.z - pa.z));
        if (span < 4.0D) {
            return Result.fail("短すぎる " + span);
        }
        double len = pa.distanceTo(pb);
        // たわみ・撚りの周期・上下の回り幅は組ごとに揺らす(11-07 ユーザー「大きさとかもばらばらに」)
        Frame f = new Frame(pa, pb, span * Mth.lerp(random.nextDouble(), DIP_MIN, DIP_MAX));
        double embedT = EMBED / len;
        double pitch = Mth.lerp(random.nextDouble(), ORBIT_PITCH_MIN, ORBIT_PITCH_MAX);
        double up = Mth.lerp(random.nextDouble(), ORBIT_UP_MIN, ORBIT_UP_MAX);
        double hand = random.nextBoolean() ? 1.0D : -1.0D;

        // 歩ける撚り糸の本数は幅で決める。真ん中の 1 本(主)を太く、ほかを細く。
        int walk = walkCountFor(span);
        int main = walk % 2 == 1 ? walk / 2 : walk / 2 - random.nextInt(2);
        double[] radius = new double[walk];
        double rMain = Mth.clamp(R_MAIN_MIN + 0.015D * span + random.nextDouble() * 0.15D, R_MAIN_MIN, R_MAIN_MAX);
        for (int k = 0; k < walk; k++) {
            radius[k] = k == main ? rMain : Math.min(rMain - 0.1D, Mth.lerp(random.nextDouble(), R_SIDE_MIN, R_SIDE_MAX));
        }
        // 並び: 隣どうしの表面を STRAND_GAP 空ける(逆向きに回るので最も寄ると 2 × 横の幅)。全体を中心線の左右に振り分ける。
        double[] lane = new double[walk];
        for (int k = 1; k < walk; k++) {
            lane[k] = lane[k - 1] + (radius[k - 1] + 0.35D) + (radius[k] + 0.35D) + STRAND_GAP + 2.0D * ORBIT_SIDE;
        }
        double mid = (lane[0] + lane[walk - 1]) / 2.0D;
        for (int k = 0; k < walk; k++) {
            lane[k] -= mid;
        }
        // 主でない糸は、並びが地形に当たるなら落とす(崖・洞窟の壁に糸が刺さって途切れないように)。残った糸で並べ直して中心へ寄せる。
        boolean[] keep = new boolean[walk];
        for (int k = 0; k < walk; k++) {
            keep[k] = k == main || laneClear(level, f, lane[k], radius[k]);
        }
        double pos = 0.0D;
        int prevK = -1;
        double lo = 0.0D;
        for (int k = 0; k < walk; k++) {
            if (!keep[k]) {
                continue;
            }
            if (prevK >= 0) {
                pos += (radius[prevK] + 0.35D) + (radius[k] + 0.35D) + STRAND_GAP + 2.0D * ORBIT_SIDE;
            } else {
                lo = pos;
            }
            lane[k] = pos;
            prevK = k;
        }
        double centre = (lo + pos) / 2.0D;
        for (int k = 0; k < walk; k++) {
            lane[k] -= centre;
        }

        List<Strand> strands = new ArrayList<>();
        int thin = 0;
        int lowCeiling = 0;
        int laneDropped = 0;
        for (int k = 0; k < walk; k++) {
            if (!keep[k] || (k != main && !laneClear(level, f, lane[k], radius[k]))) {
                laneDropped++;
                continue;
            }
            // 隣どうしは逆の位相で回る(右の糸が上がるとき左の糸は下がる = 撚れて見える)
            Shape sh = new Shape(ORBIT_SIDE, up, pitch, hand, Math.PI * k + random.nextDouble() * 0.6D, 0.0D,
                    radius[k] >= R_WALK ? KNOT_AMP : KNOT_AMP * 0.6D,
                    Mth.lerp(random.nextDouble(), 5.0D, 9.0D), random.nextDouble() * Math.PI * 2.0D,
                    Mth.lerp(random.nextDouble(), 2.5D, 4.0D), random.nextDouble() * Math.PI * 2.0D);
            Strand s = strand(f, len, embedT, radius[k], lane[k], sh, true);
            // 天井の低い所(洞窟): 上面から天井まで HEADROOM 空かなければ細くする。細くしても足りない糸は置かない(主なら組ごと)。
            double shrink = headroomShrink(level, s, len);
            if (shrink > 0.0D) {
                lowCeiling++;
                double rk = radius[k] - shrink;
                if (rk < R_FLOOR) {
                    if (k == main) {
                        return Result.fail("天井が低い(主の撚り糸の上に " + HEADROOM + " マス空かない) " + lastCeiling);
                    }
                    continue;
                }
                s = strand(f, len, embedT, rk, lane[k], sh, true);
            }
            if (!s.walkable()) {
                thin++;
            }
            strands.add(s);
        }
        // 垂れる細い撚り糸: 中心線の下を通り、真ん中で下へ離れる(ユーザー指定「一部が垂れる」)。どの組でも 1 本。
        double droopSag = Math.min(DROOP_SAG_MAX, DROOP_SAG * span);
        double laneSpread = walk > 1 ? lane[walk - 1] - lane[0] : 2.0D;
        strands.add(strand(f, len, embedT, DROOP_R, (random.nextDouble() - 0.5D) * laneSpread,
                new Shape(0.4D, 0.4D, pitch * 0.8D, -hand, random.nextDouble() * Math.PI * 2.0D, droopSag + rMain,
                        0.1D, 4.0D, random.nextDouble() * Math.PI * 2.0D, 2.5D, 0.0D), false));

        // 塗る(先の撚り糸が勝つ)。壁の中は端の食い込みの所だけ置き換える。
        Long2ObjectMap<Direction.Axis> cells = new Long2ObjectOpenHashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Strand s : strands) {
            for (int i = 0; i < s.x().length; i++) {
                double rr = s.r()[i];
                double edge = (rr + 0.35D) * (rr + 0.35D);
                int ri = (int) Math.ceil(rr + 0.35D);
                int cx = Mth.floor(s.x()[i]);
                int cy = Mth.floor(s.y()[i]);
                int cz = Mth.floor(s.z()[i]);
                boolean inWall = s.t()[i] < embedT * 0.5D || s.t()[i] > 1.0D - embedT * 0.5D;
                Direction.Axis axis = axisOf(s.tx()[i], s.ty()[i], s.tz()[i]);
                for (int dx = -ri; dx <= ri; dx++) {
                    for (int dy = -ri; dy <= ri; dy++) {
                        for (int dz = -ri; dz <= ri; dz++) {
                            double ex = cx + dx + 0.5D - s.x()[i];
                            double ey = cy + dy + 0.5D - s.y()[i];
                            double ez = cz + dz + 0.5D - s.z()[i];
                            if (ex * ex + ey * ey + ez * ez > edge) {
                                continue;
                            }
                            m.set(cx + dx, cy + dy, cz + dz);
                            long key = m.asLong();
                            if (cells.containsKey(key)) {
                                continue;
                            }
                            // 木の葉は押しのけて通る(密林の樹冠を太い蔦が抜ける)
                            if (isAirLike(level, m) || level.getBlockState(m).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                                    || (inWall && isEmbeddable(level, m))) {
                                cells.put(key, axis);
                            }
                        }
                    }
                }
            }
        }
        if (cells.size() < span * 2) {
            return Result.fail("芯が置けない(塞がっている) " + cells.size());
        }
        // 垂れる房: 芯の下面から真下へ 1 マス幅で 2〜6(床から 2 離す)。房の先は植物を付けやすい。
        LongOpenHashSet tuftTips = new LongOpenHashSet();
        int tufts = (int) Math.round(span / TUFT_EVERY) + random.nextInt(2);
        for (int i = 0; i < tufts; i++) {
            double t = 0.1D + random.nextDouble() * 0.8D;
            Strand s = strands.get(random.nextInt(strands.size()));
            int idx = Mth.clamp((int) Math.round((t + embedT) / (1.0D + 2.0D * embedT) * (s.x().length - 1)), 0, s.x().length - 1);
            int x = Mth.floor(s.x()[idx] + (random.nextDouble() - 0.5D) * s.r()[idx]);
            int z = Mth.floor(s.z()[idx] + (random.nextDouble() - 0.5D) * s.r()[idx]);
            int y = Mth.floor(s.y()[idx]);
            while (cells.containsKey(BlockPos.asLong(x, y - 1, z)) && y > s.y()[idx] - 4) {
                y--;
            }
            int length = TUFT_MIN + random.nextInt(TUFT_MAX - TUFT_MIN + 1);
            int free = 0;
            while (free < length + 2 && isAirLike(level, m.set(x, y - 1 - free, z)) && !cells.containsKey(m.asLong())) {
                free++;
            }
            int put = Math.min(length, free - 2);
            for (int d = 1; d <= put; d++) {
                cells.put(BlockPos.asLong(x, y - d, z), Direction.Axis.Y);
            }
            if (put > 0) {
                tuftTips.add(BlockPos.asLong(x, y - put, z));
            }
        }

        // 糸は芯も表面も房もすべて汚染された葉(11-07 ユーザー決定「蔦は草ブロックだけで構成されたものにしよう」)。
        // 葉と植物は待ち行列に入れて何 tick かに分けて置く(11-07 ユーザー賛成「組を置くのを何 tick かに分ける」。前は 1 tick に 13〜18 ms)。
        // 記録は置き始めに入れる(置いている途中に同じ所へ 2 組目が出ないように)。
        VineSpanRecord.get(level).add(a, b);
        BlockPos midTop = BlockPos.containing(f.cx(0.5D), f.cy(0.5D) + 3.0D, f.cz(0.5D));
        boolean cave = level.isLoaded(midTop) && !level.canSeeSky(midTop);
        // 置く順は a の壁から b の壁へ(途中でも、片側から伸びていく糸に見える。ハッシュ順だと組じゅうにばらばらに現れた)
        long[] order = cells.keySet().toLongArray();
        double ox = b.getX() - a.getX(), oz = b.getZ() - a.getZ();
        order = java.util.Arrays.stream(order).boxed()
                .sorted(java.util.Comparator.comparingDouble(k -> (BlockPos.getX(k) - a.getX()) * ox + (BlockPos.getZ(k) - a.getZ()) * oz))
                .mapToLong(Long::longValue).toArray();
        VineSpanPlacer.enqueue(level, order, tuftTips, cave, random.nextLong());
        int plants = 0;

        int maxStep = 0;
        int walkable = 0;
        StringBuilder steps = new StringBuilder("ok 幅 ").append(String.format("%.1f", span))
                .append(" 歩ける糸 ").append(walk).append(" 本の予定");
        for (Strand s : strands) {
            if (s.walkable()) {
                walkable++;
                int st = walkStep(f, cells, s);
                maxStep = Math.max(maxStep, st);
                steps.append(String.format(" [並び %.1f 半径 %.2f: %s]", s.lane(), s.base(),
                        st >= 99 ? "途切れ(弦の " + (st - 100) + " ブロック目)" : "段差 " + st + "(弦の " + lastWorstD + " ブロック目)"));
            } else if (s != strands.get(strands.size() - 1)) {
                steps.append(String.format(" [並び %.1f 半径 %.2f: 細い(渡れなくてよい)]", s.lane(), s.base()));
            }
        }
        steps.append(" 細い糸 ").append(thin).append(" 地形で落とした ").append(laneDropped).append(" 天井で細らせた ").append(lowCeiling).append(" 植物は置きながら付ける");
        return new Result(true, steps.toString(), cells.size(), strands.size(), maxStep, span, walkable, thin, plants);
    }

    /**
     * 撚り糸の上面から天井まで {@link #HEADROOM} 空けるには、半径をどれだけ細くすればよいか(0 なら細くしなくてよい)。
     * 壁の面から 2.5 ブロック以内(根元が壁へ潜る所)は見ない。天井が無ければ(空が抜けていれば)0。
     */
    /** 直前の headroomShrink で当たった天井(dev の書き出し用)。 */
    private static String lastCeiling = "";

    private static double headroomShrink(ServerLevel level, Strand s, double len) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double need = 0.0D;
        double skip = Math.min(HEADROOM_END, len * 0.25D);
        for (int i = 0; i < s.x().length; i += 2) {
            double d = s.t()[i] * len;
            if (d < skip || d > len - skip) {
                continue;
            }
            double rr = s.r()[i];
            // 上面のマス = 中心から rr + 0.35 以内で中心がいちばん上のマス。その上 HEADROOM マスに立つ。
            int top = Mth.floor(s.y()[i] + rr + 0.35D - 0.5D);
            int ceiling = Integer.MAX_VALUE;
            for (double side : new double[]{0.0D, -rr * 0.5D, rr * 0.5D}) {
                int x = Mth.floor(s.x()[i] + side * (s.tz()[i]));
                int z = Mth.floor(s.z()[i] - side * (s.tx()[i]));
                for (int y = top + 1; y <= top + HEADROOM; y++) {
                    if (level.isLoaded(m.set(x, y, z)) && level.getBlockState(m).blocksMotion()
                            && !level.getBlockState(m).is(ModTags.NOT_CAVE_ROOF)) { // 苔の膜・垂れ植物はすり抜け、木と葉(NOT_CAVE_ROOF)は切って通れるので天井でない(天井 = 地形)
                        ceiling = Math.min(ceiling, y);
                        lastCeiling = m.toShortString() + " " + level.getBlockState(m).getBlock().getDescriptionId() + " 上面 " + top;
                        break;
                    }
                }
            }
            if (ceiling == Integer.MAX_VALUE) {
                continue;
            }
            // 立てる: top + HEADROOM < ceiling。上面を下げるには半径を (top + HEADROOM + 1 - ceiling) 以上細くする。
            int over = top + HEADROOM + 1 - ceiling;
            if (over > 0) {
                need = Math.max(need, over + 0.05D);
            }
        }
        return need;
    }

    /**
     * 太い蔦のマス 1 つに植物を付ける(11-07 ユーザー「この蔦に植物が垂れているように」)。汚染の成長が天井の下・上面に置くのと同じ種類:
     * 下面に垂れ植物({@link CorruptionGrowth#randomHanging} = 泣き蔦・滴る苔・胞子の触手。洞窟では一部を光るツタ 1〜3 段)、
     * 上面に薄い苔と、まれに下草のシダ(当たり判定が無いので渡るのを邪魔しない)。葉を全部置いた後に {@link VineSpanPlacer} が呼ぶ。
     *
     * @return 置いた植物の数
     */
    static int decorateCell(ServerLevel level, long key, LongOpenHashSet cells, LongOpenHashSet tuftTips, boolean cave,
                            RandomSource random) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.spawnGrowthPlants.get()) {
            return 0;
        }
        boolean glow = cave && cfg.spawnGlowVines.get();
        boolean moss = cfg.spawnMossLayer.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int placed = 0;
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        // 下面
        if (!cells.contains(BlockPos.asLong(x, y - 1, z)) && level.getBlockState(m.set(x, y - 1, z)).isAir()) {
            double chance = tuftTips.contains(key) ? TUFT_TIP_HANG_CHANCE : HANG_CHANCE;
            if (random.nextDouble() < chance) {
                if (glow && random.nextDouble() < CAVE_GLOW_SHARE) {
                    placed += placeGlowVines(level, x, y - 1, z, 1 + random.nextInt(3), random);
                } else {
                    BlockState hanging = CorruptionGrowth.randomHanging(random);
                    if (hanging.canSurvive(level, m)) {
                        level.setBlock(m, hanging, Block.UPDATE_CLIENTS);
                        placed++;
                    }
                }
            }
        }
        // 上面(真上と、その上も空いている所 = 人が立てる所)
        if (!cells.contains(BlockPos.asLong(x, y + 1, z)) && level.getBlockState(m.set(x, y + 1, z)).isAir()) {
            double roll = random.nextDouble();
            if (moss && roll < TOP_MOSS_CHANCE) {
                BlockState layer = ModBlocks.OVERGROWN_MOSS_LAYER.get().defaultBlockState()
                        .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
                if (layer.canSurvive(level, m)) {
                    level.setBlock(m, layer, Block.UPDATE_CLIENTS);
                    placed++;
                }
            } else if (roll < TOP_MOSS_CHANCE + TOP_FERN_CHANCE) {
                BlockState fern = CorruptionGrowth.randomTop(random);
                if (fern.canSurvive(level, m)) {
                    level.setBlock(m, fern, Block.UPDATE_CLIENTS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** 光るツタを {@code (x, top, z)} から下へ最大 {@code n} 段(胴 + 先)。空気の続く所まで。 */
    private static int placeGlowVines(ServerLevel level, int x, int top, int z, int n, RandomSource random) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int free = 0;
        while (free < n && level.getBlockState(m.set(x, top - free, z)).isAir()) {
            free++;
        }
        if (free == 0) {
            return 0;
        }
        BlockState body = ModBlocks.OVERGROWN_GLOW_VINES_PLANT.get().defaultBlockState();
        for (int d = 0; d < free - 1; d++) {
            level.setBlock(m.set(x, top - d, z), body, Block.UPDATE_CLIENTS);
        }
        BlockState head = ModBlocks.OVERGROWN_GLOW_VINES.get().defaultBlockState()
                .setValue(OvergrownGlowVines.BERRIES, random.nextFloat() < 0.11F);
        level.setBlock(m.set(x, top - free + 1, z), head, Block.UPDATE_CLIENTS);
        return free;
    }

    /** 並び {@code lane} の 30〜70%(根元で中心へ寄る所の外)が、糸の上半分の太さぶん空いているか。下側が地形に少し当たるのは削れるだけでよい。 */
    private static boolean laneClear(ServerLevel level, Frame f, double lane, double r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (double t = 0.3D; t <= 0.7D; t += 0.1D) {
            for (double dy : new double[]{0.0D, r}) {
                for (double side : new double[]{-r, r}) {
                    m.set(Mth.floor(f.cx(t) + f.hx * (lane + side)), Mth.floor(f.cy(t) + dy), Mth.floor(f.cz(t) + f.hz * (lane + side)));
                    if (!isAirLike(level, m)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 芯の中心線(たわんだ弦)と、その所の向きの枠。 */
    private static final class Frame {
        final Vec3 a;
        final Vec3 b;
        final double sag;
        /** 弦に直角な水平の向き(H)。 */
        final double hx;
        final double hz;

        Frame(Vec3 a, Vec3 b, double sag) {
            this.a = a;
            this.b = b;
            this.sag = sag;
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double l = Math.sqrt(dx * dx + dz * dz);
            this.hx = -dz / l;
            this.hz = dx / l;
        }

        double cx(double t) {
            return a.x + (b.x - a.x) * t;
        }

        double cz(double t) {
            return a.z + (b.z - a.z) * t;
        }

        /** 中心線の Y。両端の外(壁の中)は弦のまま。 */
        double cy(double t) {
            double tt = Mth.clamp(t, 0.0D, 1.0D);
            return a.y + (b.y - a.y) * t - 4.0D * sag * tt * (1.0D - tt);
        }
    }

    /**
     * 撚り糸 1 本。中心線から横へ {@code lane} ずらした並びのまわりを、横 {@code side}・上下 {@code up} の楕円で {@code pitch} ごとに 1 周する。
     * 根元では回る幅を半分に寄せ、太る。{@code extraSag} は真ん中でさらに下がる分(垂れる撚り糸)。
     * 太さは長さに沿って 2 つの波で揺れる(節・瘤。根元の太りとは別)。
     */
    private static Strand strand(Frame f, double len, double embedT, double r, double lane, Shape sh, boolean walkCandidate) {
        double stepT = 0.3D / len;
        int n = (int) Math.ceil((1.0D + 2.0D * embedT) / stepT) + 1;
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        double[] rr = new double[n];
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            double tt = -embedT + i * stepT;
            double along = tt * len;
            double fromEnd = Math.min(tt, 1.0D - tt) * len; // 壁の面からの距離(壁の中は負)
            double settle = 0.5D + 0.5D * smooth(Mth.clamp(fromEnd / FLARE_LEN, 0.0D, 1.0D));
            // 根元では並びも中心線へ寄せる(壁の所で 1 つの塊にまとまる)
            double laneHere = lane * (0.55D + 0.45D * smooth(Mth.clamp(fromEnd / (FLARE_LEN * 1.5D), 0.0D, 1.0D)));
            double theta = sh.phase() + sh.hand() * Math.PI * 2.0D * along / sh.pitch();
            double ttc = Mth.clamp(tt, 0.0D, 1.0D);
            double sideOff = laneHere + sh.side() * settle * Math.sin(theta);
            x[i] = f.cx(tt) + f.hx * sideOff;
            y[i] = f.cy(tt) + sh.up() * settle * Math.cos(theta) - sh.extraSag() * Math.sin(Math.PI * ttc);
            z[i] = f.cz(tt) + f.hz * sideOff;
            double flare = 1.0D + FLARE * Math.max(0.0D, 1.0D - Math.max(0.0D, fromEnd) / FLARE_LEN);
            // 節の揺れは根元(壁から FLARE_LEN × 2)で弱める(根元の太りと重なって段差が 2 になるのを防ぐ)
            double knot = sh.knotAmp() * smooth(Mth.clamp(fromEnd / (FLARE_LEN * 2.0D), 0.0D, 1.0D)) * (0.7D * Math.sin(Math.PI * 2.0D * along / sh.knotWave1() + sh.knotPhase1())
                    + 0.3D * Math.sin(Math.PI * 2.0D * along / sh.knotWave2() + sh.knotPhase2()));
            rr[i] = Math.max(0.45D, (r + knot) * flare * (fromEnd < 0.0D ? 0.85D : 1.0D));
            t[i] = tt;
        }
        double[] tx = new double[n];
        double[] ty = new double[n];
        double[] tz = new double[n];
        for (int i = 0; i < n; i++) {
            int p = Math.max(0, i - 1);
            int q = Math.min(n - 1, i + 1);
            double dx = x[q] - x[p];
            double dy = y[q] - y[p];
            double dz = z[q] - z[p];
            double l = Math.max(1.0E-6D, Math.sqrt(dx * dx + dy * dy + dz * dz));
            tx[i] = dx / l;
            ty[i] = dy / l;
            tz[i] = dz / l;
        }
        return new Strand(x, y, z, rr, tx, ty, tz, t, walkCandidate && r >= R_WALK, lane, r);
    }

    private static Direction.Axis axisOf(double tx, double ty, double tz) {
        double ax = Math.abs(tx);
        double ay = Math.abs(ty);
        double az = Math.abs(tz);
        // 撚りで傾いた所だけ縦の軸になる(木目の向きが変わって撚れて見える)
        if (ay > 0.5D) {
            return Direction.Axis.Y;
        }
        return ax >= az ? Direction.Axis.X : Direction.Axis.Z;
    }

    /** 直前の walkStep で段差が最大だった所(弦の何ブロック目)。dev の書き出し用。 */
    private static int lastWorstD;

    /** 撚り糸 {@code s} の中心に沿って 1 ブロックおきに上面の高さを取り、隣どうしの差の最大(1 以下なら跳べば渡れる)。途切れたら 100 + 弦の何ブロック目。 */
    private static int walkStep(Frame f, Long2ObjectMap<Direction.Axis> cells, Strand s) {
        double span = Math.sqrt(sq(f.b.x - f.a.x) + sq(f.b.z - f.a.z));
        int prev = Integer.MIN_VALUE;
        int max = 0;
        lastWorstD = 0;
        // 壁の面から 2.5 ブロックは除く(根元の太りが壁へ潜る所。そこから先は壁そのもので、渡る通り道ではない)
        for (double d = 2.5D; d <= span - 2.5D; d += 1.0D) {
            double t = d / span;
            int best = Integer.MIN_VALUE;
            int idx = Mth.clamp((int) Math.round((t - s.t()[0]) / (s.t()[s.t().length - 1] - s.t()[0]) * (s.t().length - 1)), 0, s.t().length - 1);
            // 並びのまわり ±1 の列で、いちばん高い上面(回る分を拾う)
            for (int o = -1; o <= 1; o++) {
                int x = Mth.floor(s.x()[idx] + f.hx * o);
                int z = Mth.floor(s.z()[idx] + f.hz * o);
                int base = Mth.floor(s.y()[idx]);
                for (int y = base - 7; y <= base + 7; y++) {
                    if (cells.containsKey(BlockPos.asLong(x, y, z)) && !cells.containsKey(BlockPos.asLong(x, y + 1, z))) {
                        best = Math.max(best, y);
                    }
                }
            }
            if (best == Integer.MIN_VALUE) {
                return 100 + (int) d; // 途切れ(100 + 弦の何ブロック目)
            }
            if (prev != Integer.MIN_VALUE) {
                if (Math.abs(best - prev) > max) {
                    max = Math.abs(best - prev);
                    lastWorstD = (int) d;
                }
            }
            prev = best;
        }
        return max;
    }

    // ---- 共通 ----

    private static boolean isEmbeddable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.hasBlockEntity() && s.getBlock().defaultDestroyTime() >= 0.0F && s.getFluidState().isEmpty();
    }

    private static boolean isAirLike(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.canBeReplaced()) {
            return true;
        }
        // この mod の当たり判定の無い植物(苔の膜・壁のツタ・垂れ植物・シダの葉)も空気と同じに扱う(通り抜けて、上書きしてよい)。
        // 汚染の育ちを重ねた壁は苔とツタで覆われ、これを壁と見ると隙間が見つからない(11-07、自然に 0 組だった)。
        return !state.blocksMotion() && !state.hasBlockEntity()
                && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("overgrown_vegetation");
    }

    private static double smooth(double t) {
        return t * t * (3.0D - 2.0D * t);
    }

    private static double sq(double v) {
        return v * v;
    }
}
