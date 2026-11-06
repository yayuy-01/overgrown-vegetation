package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 登る蔓(2026-11-03 試作、設定 {@code vineClimb})。地面(幹の根元の脇)から生え、幹の面を蛇行して登り、
 * 枝の高さで隣の木の幹へ斜め上か水平に渡り、渡った先で少し登って終わる。隣の木が無ければ幹の上の方で終わる。
 *
 * <p>ブロックは今の蔦の印 {@link ModBlocks#OVERGROWN_LIANA} を根元に 1 個(+ 渡る区間に切るための副アンカー)。
 * 経路はここで 1 回だけ決め、点の並びを {@link CorruptedLianaBlockEntity#setClimb} に持たせる(描くのは
 * {@code client.ClimbingVineRenderer})。乱数は呼び出し側から(DevBridge は根元の座標を種にする)。
 *
 * <p>幹の面の位置は「周の座標 u」で表す。u の 1 は面 1 枚 + 角の 1/4 円(角は丸めて回り込む)。0 = 北の面、
 * 1 = 東、2 = 南、3 = 西(上から見て時計回り)。点は面から {@link #GAP} + 半径だけ外に置く(揺れない)。
 */
public final class ClimbingVine {

    /**
     * 形は 2026-11-03 3 回目の比べで採用された B(ユーザー決定): 蛇行は節 {@link #KNOT_SPACING} ブロックおき・幅 ±0.15 周、
     * 細い蔓は本体の脇を並走 1 本(全体で 1〜2 回だけ本体の上を越える)、幹に寄せて細く。
     * 幹の面から蔓の裏までのすき間(1 回目のユーザー指定 1/16〜2/16 → B で 0.5/16)。
     */
    static final double GAP = 0.5D / 16.0D;
    /** 半径の元(radiusAt)。本体はこれに {@link #MAIN_SCALE} を掛ける: 太さ 0.3 → 先 0.18(B)。 */
    public static final float RADIUS_ROOT = 0.25F;
    public static final float RADIUS_TIP = 0.15F;
    private static final float MAIN_SCALE = 0.6F;
    /** 束に添う細い蔓の太さの倍率(太さ 0.12。B)。 */
    private static final float COMPANION_SCALE = 0.24F;
    /** 根元が地面に出る長さ(B)。 */
    private static final double GROUND_OUT = 0.3D;
    /** 蛇行の節の間隔と、節ごとの揺らぎの幅(周)(B)。 */
    private static final double KNOT_SPACING = 5.0D;
    private static final double WOBBLE = 0.3D;
    /** 幹を登る区間の点の高さの刻み(ブロック)。 */
    private static final float CLIMB_DY = 0.25F;
    /** 渡る区間の点の間隔(ブロック)。 */
    private static final float BRIDGE_STEP = 0.33F;
    /** 隣の木を探す水平の距離(ユーザー指定 8 以内)と、近すぎる木を外す距離(仮決め)。 */
    private static final double NEIGHBOR_MAX = 8.0D;
    private static final double NEIGHBOR_MIN = 2.5D;
    /** 渡る区間のたるみ(ユーザー指定 0.5 以内)。 */
    private static final float SAG_MIN = 0.12F;
    private static final float SAG_MAX = 0.45F;

    /** 垂れる綱の太さの倍率(2 回目のまま。仮決め)。 */
    private static final float ROPE_SCALE = 0.34F;
    /** 林冠の細い蔓の太さの倍率(太さ 0.08〜0.12。4 回目の比べ、相談のセッションの指定)。 */
    private static final float THIN_SCALE_MIN = 0.16F;
    private static final float THIN_SCALE_MAX = 0.24F;
    /** 林冠の細い蔓の点の間隔(2026-11-03 軽量化 0.3 → 0.5、ユーザー決定)。登る蔓・渡りから垂れる綱は 0.3 のまま。 */
    private static final double THIN_STEP = 0.5D;
    /** 垂れる綱の先を地面からこれだけ離す(仮決め)。 */
    private static final double ROPE_GROUND_GAP = 1.5D;
    /** 渡る線が塞がっているとき、高さを変えて試す回数(仮決め)。 */
    private static final int BRIDGE_TRIES = 4;
    /** 経路の点の 7 番目: どこに沿う点か。 */
    private static final double OWN = 0.0D;
    private static final double NEIGHBOR = 1.0D;
    private static final double BRIDGE = 2.0D;

    /** 置いた本体の蔓。点は x, y, z, nx, nz, u(周の座標)、どこに沿うか、幹からの距離。 */
    private record Placed(List<double[]> pts, int bridgeStart, int bridgeEnd, @Nullable Trunk neighbor, BlockPos source,
            BlockPos target) {
    }

    private ClimbingVine() {
    }

    /** 幹の太さ・根元・高さ。{@code minX, minZ} は根元の北西の角のマス、{@code width} は 1 か 2。 */
    public record Trunk(int minX, int minZ, int width, int baseY, int height) {
        double centerX() {
            return minX + width / 2.0D;
        }

        double centerZ() {
            return minZ + width / 2.0D;
        }

        int topY() {
            return baseY + height;
        }

        boolean contains(int x, int z) {
            return x >= minX && x < minX + width && z >= minZ && z < minZ + width;
        }
    }

    static boolean isTrunk(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_LOG.get());
    }

    /** 読み込み済みのマスだけ見る(世界生成の作業スレッドから、まだ揃っていないチャンクを読みに行かない。TreeVineConnector と同じ)。 */
    static boolean trunkAt(Level level, BlockPos pos) {
        return level.isLoaded(pos) && isTrunk(level.getBlockState(pos));
    }

    /** {@code pos} を含む幹を返す。原木でなければ null。根元は原木の列を下へたどり、太さは 2×2 が揃うかで見る。 */
    @Nullable
    public static Trunk findTrunk(Level level, BlockPos pos) {
        if (!trunkAt(level, pos)) {
            return null;
        }
        BlockPos.MutableBlockPos m = pos.mutable();
        while (trunkAt(level, m.move(0, -1, 0))) {
            // 下へ
        }
        int baseY = m.getY() + 1;
        int minX = pos.getX();
        int minZ = pos.getZ();
        int width = 1;
        search:
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                boolean all = true;
                for (int dx = 0; dx < 2 && all; dx++) {
                    for (int dz = 0; dz < 2 && all; dz++) {
                        all = trunkAt(level, new BlockPos(pos.getX() + ox + dx, baseY, pos.getZ() + oz + dz))
                                && trunkAt(level, new BlockPos(pos.getX() + ox + dx, baseY + 1, pos.getZ() + oz + dz));
                    }
                }
                if (all) {
                    minX = pos.getX() + ox;
                    minZ = pos.getZ() + oz;
                    width = 2;
                    break search;
                }
            }
        }
        int height = 0;
        while (height < 64) {
            boolean all = true;
            for (int dx = 0; dx < width && all; dx++) {
                for (int dz = 0; dz < width && all; dz++) {
                    all = trunkAt(level, new BlockPos(minX + dx, baseY + height, minZ + dz));
                }
            }
            if (!all) {
                break;
            }
            height++;
        }
        return new Trunk(minX, minZ, width, baseY, height);
    }

    // ---- 周の座標 u → 位置 ----

    private static final int[][] SIDE_START = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    private static final int[][] SIDE_DIR = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final int[][] SIDE_NORMAL = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** 周の座標 {@code u} の点(幹の面から {@code rho} 外)の x, z と外向きの nx, nz を {@code out} に書く。 */
    static void perimeterPoint(Trunk t, double u, double rho, double[] out) {
        double w = t.width();
        double straight = w / (w + Math.PI / 2.0D * rho);
        double uu = u - Math.floor(u / 4.0D) * 4.0D;
        int k = Math.min(3, (int) Math.floor(uu));
        double f = uu - k;
        int k1 = (k + 1) & 3;
        double nx;
        double nz;
        double bx;
        double bz;
        if (f < straight) {
            double s = f / straight * w;
            nx = SIDE_NORMAL[k][0];
            nz = SIDE_NORMAL[k][1];
            bx = t.minX() + SIDE_START[k][0] * w + SIDE_DIR[k][0] * s;
            bz = t.minZ() + SIDE_START[k][1] * w + SIDE_DIR[k][1] * s;
        } else {
            double theta = (f - straight) / (1.0D - straight) * Math.PI / 2.0D;
            nx = SIDE_NORMAL[k][0] * Math.cos(theta) + SIDE_NORMAL[k1][0] * Math.sin(theta);
            nz = SIDE_NORMAL[k][1] * Math.cos(theta) + SIDE_NORMAL[k1][1] * Math.sin(theta);
            bx = t.minX() + SIDE_START[k1][0] * w;
            bz = t.minZ() + SIDE_START[k1][1] * w;
        }
        out[0] = bx + nx * rho;
        out[1] = bz + nz * rho;
        out[2] = nx;
        out[3] = nz;
    }

    /** 面 {@code side} の真ん中寄りで、{@code toward}(水平)にいちばん近い位置の u。 */
    private static double facingU(Trunk t, int side, double towardX, double towardZ, double rho, RandomSource random) {
        double w = t.width();
        double straight = w / (w + Math.PI / 2.0D * rho);
        double sx = t.minX() + SIDE_START[side][0] * w;
        double sz = t.minZ() + SIDE_START[side][1] * w;
        double s = (towardX - sx) * SIDE_DIR[side][0] + (towardZ - sz) * SIDE_DIR[side][1];
        s = Mth.clamp(s + (random.nextDouble() - 0.5D) * 0.3D * w, 0.2D * w, 0.8D * w);
        return side + s / w * straight;
    }

    private static int facingSide(Trunk t, double towardX, double towardZ) {
        double dx = towardX - t.centerX();
        double dz = towardZ - t.centerZ();
        int best = 0;
        double bestDot = -Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            double dot = dx * SIDE_NORMAL[k][0] + dz * SIDE_NORMAL[k][1];
            if (dot > bestDot) {
                bestDot = dot;
                best = k;
            }
        }
        return best;
    }

    // ---- 置く ----

    /**
     * {@code base} の幹(原木ならどの段でもよい)に登る蔓を {@code count} 本置く。置けた本数を返す。
     * 2 本目は 1 本目と別の隣の木(無ければ渡らずに上で終わる)・別の面から登る。
     */
    public static int placeOnTree(ServerLevel level, BlockPos base, int count, RandomSource random) {
        Trunk own = findTrunk(level, base);
        if (own == null || own.height() < 4) {
            return 0;
        }
        List<Trunk> neighbors = findNeighbors(level, own);
        int placed = 0;
        double firstU = random.nextDouble() * 4.0D;
        for (int i = 0; i < count; i++) {
            Trunk neighbor = null;
            if (!neighbors.isEmpty()) {
                neighbor = neighbors.remove(random.nextInt(neighbors.size()));
            }
            double u0 = i == 0 ? firstU : firstU + 1.5D + random.nextDouble();
            Placed main = placeOne(level, own, neighbor, u0, random);
            if (main == null) {
                continue;
            }
            placed++;
            placeCompanion(level, own, main, random);
            placeBridgeRopes(level, own, main, random);
        }
        return placed;
    }

    /**
     * 林冠の細い蔓(2026-11-03 採用 = 4 回目の c)。木 1 本に合計 5〜10 本(ユーザー指定)、a(葉から真下に垂れる): b(葉から隣の木の葉へ
     * 浅い U + 底から垂れ)= 7 : 3(ユーザー指定)→ b 1〜3・a 4〜7(仮決め)。b の数が足りなければその分 a を増やす(a は 7 まで)。
     * 2 回目からの「林冠から垂れる綱 1〜2 本」はこの a に吸収した(二重に出さない)。置けた本数 {a, b} を返す。
     */
    public static int[] placeCanopyVines(ServerLevel level, BlockPos base, RandomSource random) {
        Trunk own = findTrunk(level, base);
        if (own == null || own.height() < 4) {
            return new int[]{0, 0};
        }
        BlockPos source = new BlockPos(own.minX(), own.baseY(), own.minZ());
        int total = 5 + random.nextInt(6);
        int us = placeCanopyUs(level, own, findNeighbors(level, own), source, 1 + random.nextInt(3), random);
        int drops = placeCanopyDrops(level, own, source, Mth.clamp(total - us, 4, 7), random);
        return new int[]{drops, us};
    }

    /** 束(B): 本体の脇に細い蔓を 1 本並走させる(全体で 1〜2 回だけ本体の上を越える)。 */
    private static void placeCompanion(ServerLevel level, Trunk own, Placed main, RandomSource random) {
        List<double[]> path = companionPath(main, own, main.neighbor(), RADIUS_ROOT * COMPANION_SCALE, random);
        if (path.size() < 6) {
            return;
        }
        boolean bridged = main.bridgeEnd() >= 0 && path.size() > main.bridgeEnd();
        commit(level, own, path, bridged ? main.bridgeStart() : -1, bridged ? main.bridgeEnd() : -1,
                main.source(), main.target(), COMPANION_SCALE, -1.0F, 0);
    }

    // ---- 林冠の細い蔓 ----

    /** a: 葉の下面から真下へ {@code count} 本。長さ 3〜12、2 割は地面まで届いて終わる。置けた本数を返す。 */
    private static int placeCanopyDrops(ServerLevel level, Trunk own, BlockPos source, int count, RandomSource random) {
        List<double[]> spots = canopyHangPoints(level, own, 5, true);
        int placed = 0;
        for (int r = 0; r < count && !spots.isEmpty(); r++) {
            double[] top = spots.remove(random.nextInt(spots.size()));
            // 葉の塊の中から出す(下面より 0.35 上から)
            double[] start = {top[0] + (random.nextDouble() - 0.5D) * 0.6D, top[1] + 0.35D, top[2] + (random.nextDouble() - 0.5D) * 0.6D};
            boolean toGround = random.nextFloat() < 0.2F;
            List<double[]> rope = ropePath(level, start, 3.0D + random.nextDouble() * 9.0D, toGround, THIN_STEP, random);
            if (rope != null && commit(level, own, rope, -1, -1, source, BlockPos.containing(top[0], top[1] + 1.0D, top[2]),
                    thinScale(random), 0.0F, CorruptedLianaBlockEntity.CLIMB_CANOPY) != null) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * b: 自分の木の葉と隣の木の葉(水平 4〜10・高さの差 1〜3 = 両端を非対称に)を浅い U で結ぶ。両端は葉の塊の中から出る。
     * たるみは長さの 1〜2 割。底の近くから 1〜2 本が真下へ 2〜6 垂れる。置けた U の本数を返す。
     */
    private static int placeCanopyUs(ServerLevel level, Trunk own, List<Trunk> neighbors, BlockPos source, int count,
            RandomSource random) {
        if (neighbors.isEmpty()) {
            return 0;
        }
        List<double[]> mine = canopyHangPoints(level, own, 5, true);
        List<double[][]> pairs = new ArrayList<>();
        for (Trunk n : neighbors) {
            for (double[] b : canopyHangPoints(level, n, 5, true)) {
                for (double[] a : mine) {
                    double h = Math.hypot(b[0] - a[0], b[2] - a[2]);
                    if (h >= 4.0D && h <= 10.0D && Math.abs(b[1] - a[1]) >= 1.0D && Math.abs(b[1] - a[1]) <= 3.0D) {
                        pairs.add(new double[][]{a, b, {n.minX(), n.baseY(), n.minZ()}});
                    }
                }
            }
        }
        java.util.Set<Long> usedEnds = new java.util.HashSet<>();
        int tries = 0;
        int r = 0;
        for (; r < count && !pairs.isEmpty() && tries < 80; tries++) {
            double[][] pair = pairs.remove(random.nextInt(pairs.size()));
            long ka = BlockPos.asLong(Mth.floor(pair[0][0]), Mth.floor(pair[0][1]), Mth.floor(pair[0][2]));
            long kb = BlockPos.asLong(Mth.floor(pair[1][0]), Mth.floor(pair[1][1]), Mth.floor(pair[1][2]));
            if (usedEnds.contains(ka) || usedEnds.contains(kb)) {
                continue;
            }
            double[] from = {pair[0][0], pair[0][1] + 0.35D, pair[0][2]};
            double[] to = {pair[1][0], pair[1][1] + 0.35D, pair[1][2]};
            double len = Math.sqrt(sq(to[0] - from[0]) + sq(to[1] - from[1]) + sq(to[2] - from[2]));
            double sag = len * (0.1D + random.nextDouble() * 0.1D);
            if (!uClear(level, from, to, sag)) {
                continue;
            }
            usedEnds.add(ka);
            usedEnds.add(kb);
            int steps = Math.max(6, (int) Math.ceil(len / THIN_STEP));
            double hx = to[0] - from[0];
            double hz = to[2] - from[2];
            double hl = Math.max(1.0E-4D, Math.hypot(hx, hz));
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                pts.add(new double[]{Mth.lerp(t, from[0], to[0]), Mth.lerp(t, from[1], to[1]) - 4.0D * sag * t * (1.0D - t),
                        Mth.lerp(t, from[2], to[2]), hz / hl, -hx / hl, Double.NaN, BRIDGE, 0.0D});
            }
            BlockPos target = BlockPos.containing(pair[2][0], pair[2][1], pair[2][2]);
            if (commit(level, own, pts, 0, pts.size() - 1, source, target, thinScale(random), -1.0F,
                    CorruptedLianaBlockEntity.CLIMB_CANOPY) == null) {
                continue;
            }
            r++;
            // 底の近くから真下へ 1〜2 本(上の端は U と一緒に揺れる。重みは描き手の式と同じ)
            double[] s = new double[pts.size()];
            for (int i = 1; i < pts.size(); i++) {
                double[] a = pts.get(i - 1);
                double[] b = pts.get(i);
                s[i] = s[i - 1] + Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            }
            int drops = 1 + random.nextInt(2);
            for (int d = 0; d < drops; d++) {
                int i = (int) Math.round((pts.size() - 1) * (0.35D + random.nextDouble() * 0.3D));
                double[] p = pts.get(i);
                double ramp = Math.min(s[i], s[s.length - 1] - s[i]) / 1.5D;
                float w = (float) Mth.clamp(ramp, 0.0D, 1.0D);
                w = w * w * (3.0F - 2.0F * w);
                List<double[]> rope = ropePath(level, new double[]{p[0], p[1] - 0.03D, p[2]}, 2.0D + random.nextDouble() * 4.0D,
                        false, THIN_STEP, random);
                if (rope != null) {
                    commit(level, own, rope, -1, -1, source, target, thinScale(random), w, CorruptedLianaBlockEntity.CLIMB_CANOPY);
                }
            }
        }
        return r;
    }

    private static float thinScale(RandomSource random) {
        return THIN_SCALE_MIN + random.nextFloat() * (THIN_SCALE_MAX - THIN_SCALE_MIN);
    }

    /** U の線(たるみ込み)の途中に原木・枝・固いブロックが無いか。葉は通す(両端は葉の中)。 */
    private static boolean uClear(Level level, double[] from, double[] to, double sag) {
        double len = Math.sqrt(sq(to[0] - from[0]) + sq(to[1] - from[1]) + sq(to[2] - from[2]));
        int steps = Math.max(4, (int) Math.ceil(len / 0.25D));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            BlockPos c = BlockPos.containing(Mth.lerp(t, from[0], to[0]), Mth.lerp(t, from[1], to[1]) - 4.0D * sag * t * (1.0D - t),
                    Mth.lerp(t, from[2], to[2]));
            if (!level.isLoaded(c)) {
                return false;
            }
            BlockState state = level.getBlockState(c);
            if (!(state.isAir() || state.canBeReplaced() || isLeaf(state)
                    || state.is(ModBlocks.OVERGROWN_LIANA.get()))) {
                return false;
            }
        }
        return true;
    }

    /** 渡る区間の真ん中寄りから綱を 1〜2 本垂らす(上の端は渡る蔓と一緒に揺れる)。 */
    private static void placeBridgeRopes(ServerLevel level, Trunk own, Placed main, RandomSource random) {
        int bs = main.bridgeStart();
        int be = main.bridgeEnd();
        if (bs < 0 || be - bs < 6) {
            return;
        }
        List<double[]> pts = main.pts();
        double[] s = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            s[i] = s[i - 1] + Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
        }
        int ropes = 1 + (random.nextFloat() < 0.5F ? 1 : 0);
        for (int r = 0; r < ropes; r++) {
            int i = bs + (int) Math.round((be - bs) * (0.25D + random.nextDouble() * 0.5D));
            double[] p = pts.get(i);
            // 渡る蔓の揺れの重み(描き手の式と同じ)。綱の上の端をこれに合わせ、離れて見えないように
            double ramp = Math.min(s[i] - s[bs], s[be] - s[i]) / 1.5D;
            float w = (float) Mth.clamp(ramp, 0.0D, 1.0D);
            w = w * w * (3.0F - 2.0F * w);
            double[] top = {p[0], p[1] - RADIUS_TIP * MAIN_SCALE * 0.6D, p[2]};
            List<double[]> rope = ropePath(level, top, 2.0D + random.nextDouble() * 3.0D, false, 0.3D, random);
            if (rope != null) {
                commit(level, own, rope, -1, -1, main.source(), main.target(), ROPE_SCALE, w, 0);
            }
        }
    }

    /** 枝の高さ(幹の 6〜8 割)の帯で、水平 {@link #NEIGHBOR_MAX} 以内の別の幹を集める(重複なし)。 */
    private static List<Trunk> findNeighbors(ServerLevel level, Trunk own) {
        List<Trunk> found = new ArrayList<>();
        int y = own.baseY() + Math.max(2, (int) (own.height() * 0.7F));
        int r = (int) Math.ceil(NEIGHBOR_MAX) + 1;
        int cx = Mth.floor(own.centerX());
        int cz = Mth.floor(own.centerZ());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (own.contains(x, z)) {
                    continue;
                }
                m.set(x, y, z);
                if (!level.isLoaded(m) || !trunkAt(level, m)) {
                    continue;
                }
                boolean known = false;
                for (Trunk t : found) {
                    known |= t.contains(x, z);
                }
                if (known) {
                    continue;
                }
                Trunk t = findTrunk(level, m.immutable());
                // 枝の先の原木を幹と取り違えないよう、3 段以上の柱だけ
                if (t == null || t.height() < 3 || t.topY() < y + 2) {
                    continue;
                }
                double d = Math.hypot(t.centerX() - own.centerX(), t.centerZ() - own.centerZ());
                if (d < NEIGHBOR_MIN || d > NEIGHBOR_MAX) {
                    continue;
                }
                found.add(t);
            }
        }
        return found;
    }

    /** 半径: 根元が太く、先へ細く。最後の 0.8 ブロックはさらに細めて先を尖らせる。 */
    public static float radiusAt(float s, float total) {
        float r = Mth.lerp(Mth.clamp(s / Math.max(total, 1.0E-3F), 0.0F, 1.0F), RADIUS_ROOT, RADIUS_TIP);
        float toTip = total - s;
        if (toTip < 0.8F) {
            r *= 0.35F + 0.65F * (toTip / 0.8F);
        }
        return r;
    }

    /** 本体の蔓を 1 本置く。置けたらその経路(束・垂れる綱を作るのに使う)、置けなければ null。 */
    @Nullable
    private static Placed placeOne(ServerLevel level, Trunk own, @Nullable Trunk neighbor, double u0, RandomSource random) {
        return placeOne(level, own, neighbor, u0, random, 0);
    }

    /** {@code attempt}: 渡る線が塞がっていたら、高さ(幹の 6〜8 割の中)と着く位置を引き直して {@link #BRIDGE_TRIES} 回まで試す。 */
    @Nullable
    private static Placed placeOne(ServerLevel level, Trunk own, @Nullable Trunk neighbor, double u0, RandomSource random, int attempt) {
        List<double[]> pts = new ArrayList<>();
        double[] tmp = new double[4];
        int dir = random.nextBoolean() ? 1 : -1;
        double rho = GAP + RADIUS_ROOT * MAIN_SCALE;

        // 1. 地面の区間: 幹の脇の地面から出て、根元へ寄る(マスが空いていて下が固いときだけ)。
        // 根の張り出し(十字の根)で塞がっていることが多いので、u をずらして空いた地面を探す(角の斜め向きが空きやすい)。
        boolean groundRun = false;
        for (int k = 0; k < 16 && !groundRun; k++) {
            double cand = u0 + (k % 2 == 0 ? 1 : -1) * (k / 2) * 0.25D;
            perimeterPoint(own, cand, rho, tmp);
            BlockPos groundCell = BlockPos.containing(tmp[0] + tmp[2] * 0.75D, own.baseY() + 0.1D, tmp[1] + tmp[3] * 0.75D);
            if (isOpen(level, groundCell) && level.isLoaded(groundCell.below())
                    && level.getBlockState(groundCell.below()).isFaceSturdy(level, groundCell.below(), net.minecraft.core.Direction.UP)) {
                groundRun = true;
                u0 = cand;
            }
        }
        double yClimbStart;
        if (groundRun) {
            double out = GROUND_OUT;
            for (int i = 0; i <= 4; i++) {
                double a = i / 4.0D;
                double e = out * (1.0D - a);
                perimeterPoint(own, u0, rho + e, tmp);
                // 先頭は土に少し潜らせる(生えているように)。
                double y = own.baseY() + (i == 0 ? -0.08D : RADIUS_ROOT * MAIN_SCALE * 0.55D + 0.3D * a * a * a);
                pts.add(new double[]{tmp[0], y, tmp[1], tmp[2], tmp[3], u0, OWN, rho + e});
            }
            yClimbStart = own.baseY() + RADIUS_ROOT * MAIN_SCALE * 0.55D + 0.3D + CLIMB_DY;
        } else {
            // 幹の根元から(根の張り出しの上面から出てすぐ幹に貼り付く)。脇のマスで最初に空いた高さを根元とみなす。
            perimeterPoint(own, u0, rho, tmp);
            int rootY = own.baseY();
            while (rootY < own.baseY() + 3 && !isOpen(level, BlockPos.containing(tmp[0], rootY + 0.1D, tmp[1]))) {
                rootY++;
            }
            pts.add(new double[]{tmp[0], rootY - 0.1D, tmp[1], tmp[2], tmp[3], u0, OWN, rho});
            yClimbStart = rootY + CLIMB_DY;
        }

        // 2. 幹を登る区間の終わり(渡る高さ)と、その位置の u。
        double yEnd;
        double uEnd;
        double bridgeYTarget = 0.0D;
        double arriveU = 0.0D;
        if (neighbor != null) {
            yEnd = own.baseY() + own.height() * (0.6D + random.nextDouble() * 0.2D);
            int side = facingSide(own, neighbor.centerX(), neighbor.centerZ());
            double target = facingU(own, side, neighbor.centerX(), neighbor.centerZ(), rho, random);
            uEnd = nearestWrap(target, u0 + dir * 1.5D);
            double dist = Math.hypot(neighbor.centerX() - own.centerX(), neighbor.centerZ() - own.centerZ());
            // 斜め上か水平(ユーザー指定)。上がり幅は距離の 3 割・2 ブロックまで(仮決め)。
            bridgeYTarget = yEnd + random.nextDouble() * Math.min(2.0D, dist * 0.3D);
            bridgeYTarget = Math.min(bridgeYTarget, neighbor.topY() - 1.2D);
            if (bridgeYTarget < yEnd) {
                neighbor = null;
            } else {
                int nSide = facingSide(neighbor, own.centerX(), own.centerZ());
                arriveU = facingU(neighbor, nSide, own.centerX(), own.centerZ(), rho, random);
            }
        } else {
            yEnd = 0.0D;
            uEnd = 0.0D;
        }
        if (neighbor == null) {
            yEnd = own.baseY() + own.height() * (0.85D + random.nextDouble() * 0.1D);
            uEnd = u0 + dir * (0.8D + random.nextDouble() * 1.2D);
        }
        yEnd = Math.min(yEnd, own.topY() - 0.6D);

        // 3. 蛇行の節(B: 5 ブロックおき)。u は始まりから終わりへ寄りつつ、節ごとに ±0.15 周だけ揺らぐ。
        int knots = Math.max(2, (int) Math.round((yEnd - yClimbStart) / KNOT_SPACING));
        double[] ku = new double[knots + 1];
        double[] ky = new double[knots + 1];
        for (int j = 0; j <= knots; j++) {
            double t = j / (double) knots;
            ky[j] = Mth.lerp(t, yClimbStart, yEnd);
            double wobble = (random.nextDouble() - 0.5D) * WOBBLE;
            double edge = Math.min(1.0D, Math.min(j, knots - j) * 0.7D);
            ku[j] = Mth.lerp(t, u0, uEnd) + wobble * edge;
            // 枝(や枝の継ぎ目)に刺さる節は、ずらしてみる
            for (int tries = 0; tries < 3 && j > 0 && j < knots && blockedAt(level, own, ku[j], ky[j], rho, tmp); tries++) {
                ku[j] += (tries % 2 == 0 ? 0.35D : -0.7D);
            }
        }
        int climbSteps = Math.max(1, (int) Math.ceil((yEnd - yClimbStart) / CLIMB_DY));
        for (int i = 0; i <= climbSteps; i++) {
            double t = i / (double) climbSteps;
            double y = Mth.lerp(t, yClimbStart, yEnd);
            double u = catmullRom(ku, t);
            perimeterPoint(own, u, rho, tmp);
            pts.add(new double[]{tmp[0], y, tmp[1], tmp[2], tmp[3], u, OWN, rho});
        }

        // 4. 渡る区間と、渡った先で少し登る区間。
        int bridgeStart = -1;
        int bridgeEnd = -1;
        BlockPos targetLog;
        if (neighbor != null) {
            double[] from = pts.get(pts.size() - 1);
            double nRho = GAP + RADIUS_TIP * MAIN_SCALE + 0.03D;
            perimeterPoint(neighbor, arriveU, nRho, tmp);
            double[] to = {tmp[0], bridgeYTarget, tmp[1], tmp[2], tmp[3]};
            if (!bridgeClear(level, from, to, own, neighbor)) {
                // 渡れない(途中に原木など)。高さを変えて試し直し、だめなら幹の上で終わらせる(2026-11-03、渡れたのが 10 本中 4 本だったので)。
                return placeOne(level, own, attempt + 1 < BRIDGE_TRIES ? neighbor : null, u0, random, attempt + 1);
            }
            double len = Math.sqrt(sq(to[0] - from[0]) + sq(to[1] - from[1]) + sq(to[2] - from[2]));
            float sag = SAG_MIN + random.nextFloat() * (SAG_MAX - SAG_MIN);
            int steps = Math.max(3, (int) Math.ceil(len / BRIDGE_STEP));
            bridgeStart = pts.size() - 1;
            // 水平の向き(渡る向きと直交)を、渡る区間の「外向き」に使う。
            double hx = to[0] - from[0];
            double hz = to[2] - from[2];
            double hl = Math.max(1.0E-4D, Math.hypot(hx, hz));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                double x = Mth.lerp(t, from[0], to[0]);
                double y = Mth.lerp(t, from[1], to[1]) - 4.0D * sag * t * (1.0D - t);
                double z = Mth.lerp(t, from[2], to[2]);
                pts.add(new double[]{x, y, z, hz / hl, -hx / hl, Double.NaN, BRIDGE, 0.0D});
            }
            bridgeEnd = pts.size() - 1;
            // 渡った先で 1.5〜3 ブロック登る(相手の幹の高さまで)
            double up = Math.min(1.5D + random.nextDouble() * 1.5D, neighbor.topY() - 0.4D - bridgeYTarget);
            int upSteps = Math.max(1, (int) Math.ceil(up / CLIMB_DY));
            double drift = (random.nextDouble() - 0.5D) * 0.5D;
            for (int i = 1; i <= upSteps; i++) {
                double t = i / (double) upSteps;
                perimeterPoint(neighbor, arriveU + drift * t * t, nRho, tmp);
                pts.add(new double[]{tmp[0], bridgeYTarget + up * t, tmp[1], tmp[2], tmp[3], arriveU + drift * t * t, NEIGHBOR, nRho});
            }
            targetLog = new BlockPos(Mth.floor(neighbor.centerX()), Mth.floor(bridgeYTarget), Mth.floor(neighbor.centerZ()));
            targetLog = nearestLogInTrunk(neighbor, targetLog);
        } else {
            targetLog = nearestLogInTrunk(own, new BlockPos(own.minX(), Mth.floor(yEnd), own.minZ()));
        }
        BlockPos source = new BlockPos(own.minX(), own.baseY(), own.minZ());
        if (commit(level, own, pts, bridgeStart, bridgeEnd, source, targetLog, MAIN_SCALE, -1.0F,
                CorruptedLianaBlockEntity.CLIMB_MAIN) == null) {
            return null;
        }
        return new Placed(pts, bridgeStart, bridgeEnd, neighbor, source, targetLog);
    }

    /**
     * 経路 {@code pts} の蔓を 1 本、印のブロックに書き込む。印は先頭の数点の近くの空いたマス(描画の基準と切る所なので、根元の点そのものでなくてよい)。
     * 渡る区間には切るための副アンカー(今の蔦と同じ。幹に沿う区間には置かない = 幹の脇のマスを塞がない)。置けた印、置けなければ null。
     */
    @Nullable
    private static BlockPos commit(ServerLevel level, Trunk own, List<double[]> pts, int bridgeStart, int bridgeEnd,
            BlockPos source, BlockPos target, float scale, float hangTop, int flags) {
        BlockPos marker = null;
        // 林冠の U は両端が葉の中なので、経路のどこでも探す(先頭の 10 点 = 3 ブロックでは葉の中のまま)
        int searchPts = (flags & CorruptedLianaBlockEntity.CLIMB_CANOPY) != 0 ? pts.size() : Math.min(pts.size(), 10);
        search:
        for (int i = 0; i < searchPts; i++) {
            double[] p = pts.get(i);
            for (int up = 0; up <= 2; up++) {
                for (double out : new double[]{0.0D, 0.6D}) {
                    BlockPos c = BlockPos.containing(p[0] + p[3] * out, p[1] + 0.12D + up, p[2] + p[4] * out);
                    if (isOpen(level, c) && !own.contains(c.getX(), c.getZ())) {
                        marker = c;
                        break search;
                    }
                }
            }
        }
        if (marker == null) {
            return null;
        }
        level.setBlock(marker, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(marker) instanceof CorruptedLianaBlockEntity liana)) {
            return null;
        }
        float[] packed = new float[pts.size() * CorruptedLianaBlockEntity.CLIMB_STRIDE];
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            int o = i * CorruptedLianaBlockEntity.CLIMB_STRIDE;
            packed[o] = (float) (p[0] - marker.getX());
            packed[o + 1] = (float) (p[1] - marker.getY());
            packed[o + 2] = (float) (p[2] - marker.getZ());
            packed[o + 3] = (float) p[3];
            packed[o + 4] = (float) p[4];
        }
        liana.setClimbStyle(scale, hangTop, flags);
        liana.setClimb(packed, bridgeStart, bridgeEnd, source, target);

        List<BlockPos> secondaries = new ArrayList<>();
        if (bridgeStart >= 0) {
            BlockPos previous = marker;
            for (int i = bridgeStart + 1; i < bridgeEnd; i++) {
                double[] p = pts.get(i);
                BlockPos c = BlockPos.containing(p[0], p[1], p[2]);
                if (c.equals(previous) || !isOpen(level, c)) {
                    continue;
                }
                previous = c;
                level.setBlock(c, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState(), Block.UPDATE_ALL);
                if (level.getBlockEntity(c) instanceof CorruptedLianaBlockEntity secondary) {
                    secondary.setPrimaryPos(marker);
                    secondaries.add(c);
                }
            }
        }
        liana.setSecondaryPositions(secondaries);
        return marker;
    }

    // ---- 束(本体に添う細い蔓)----

    /**
     * 本体の経路に添う細い蔓の経路(B)。本体の脇を離れずに並走し、全体で 1〜2 回だけ本体の上を越えて反対側へ移る
     * (越える所では本体の上に乗る)。渡る区間も巻かずに脇を並走する。
     */
    private static List<double[]> companionPath(Placed main, Trunk own, @Nullable Trunk neighbor, float thinR, RandomSource random) {
        List<double[]> pts = main.pts();
        int n = pts.size();
        double[] s = new double[n];
        for (int i = 1; i < n; i++) {
            double[] a = pts.get(i - 1);
            double[] b = pts.get(i);
            s[i] = s[i - 1] + Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
        }
        float total = (float) s[n - 1];
        double phase0 = random.nextBoolean() ? 0.0D : Math.PI;
        int crossings = 1 + random.nextInt(2);
        List<double[]> out = new ArrayList<>();
        double[] tmp = new double[4];
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            float mainR = radiusAt((float) s[i], total) * MAIN_SCALE;
            double d = (mainR + thinR + 0.04D) * Math.cos(phase0 + Math.PI * crossings * s[i] / Math.max(total, 1.0E-3D));
            if (p[6] == BRIDGE) {
                // 渡る区間: 本体の脇を並走(d の符号の側)
                double[] a = pts.get(Math.max(0, i - 1));
                double[] b = pts.get(Math.min(n - 1, i + 1));
                double tx = b[0] - a[0];
                double ty = b[1] - a[1];
                double tz = b[2] - a[2];
                double tl = Math.max(1.0E-4D, Math.sqrt(tx * tx + ty * ty + tz * tz));
                tx /= tl;
                ty /= tl;
                tz /= tl;
                double lx = p[3];
                double lz = p[4];
                // U = T × L(L は水平)
                double ux = ty * lz;
                double uy = tz * lx - tx * lz;
                double uz = -ty * lx;
                double th = d >= 0.0D ? 0.0D : Math.PI;
                double rr = (mainR + thinR) * 0.92D;
                out.add(new double[]{p[0] + (lx * Math.cos(th) + ux * Math.sin(th)) * rr, p[1] + (uy * Math.sin(th)) * rr,
                        p[2] + (lz * Math.cos(th) + uz * Math.sin(th)) * rr, p[3], p[4], Double.NaN, BRIDGE, 0.0D});
                continue;
            }
            Trunk t = p[6] == NEIGHBOR ? neighbor : own;
            if (t == null) {
                break;
            }
            // 周の座標 1 = 面 1 枚(+ 角)。ずれ d ブロックを周の座標へ(幅 w + 角)で割る。
            double unit = t.width() + Math.PI / 2.0D * p[7];
            double rhoMain = p[7];
            double reach = mainR + thinR;
            double bump = Math.abs(d) < reach ? 2.0D * mainR * (1.0D - Math.abs(d) / reach) : 0.0D;
            double rho = rhoMain + (thinR - mainR) + bump;
            perimeterPoint(t, p[5] + d / unit, rho, tmp);
            out.add(new double[]{tmp[0], p[1], tmp[1], tmp[2], tmp[3], p[5] + d / unit, p[6], rho});
        }
        return out;
    }

    // ---- 垂れる綱 ----

    /**
     * {@code top} から真下へ垂れる綱の経路(わずかにうねる)。地面から {@link #ROPE_GROUND_GAP} 以上離す。{@code toGround} なら長さに関わらず
     * 地面まで届いて終わる(先は地面に 0.05 潜る)。短すぎれば null。
     */
    @Nullable
    private static List<double[]> ropePath(ServerLevel level, double[] top, double length, boolean toGround, double step,
            RandomSource random) {
        BlockPos.MutableBlockPos m = BlockPos.containing(top[0], top[1], top[2]).mutable();
        int free = 0;
        while (free < 24 && isOpen(level, m.move(0, -1, 0))) {
            free++;
        }
        double floorY = m.getY() + 1.0D;
        double len = toGround ? top[1] - floorY + 0.05D : Math.min(length, top[1] - floorY - ROPE_GROUND_GAP);
        if (len < 1.2D) {
            return null;
        }
        List<double[]> out = new ArrayList<>();
        int steps = Math.max(3, (int) Math.ceil(len / step));
        double phase = random.nextDouble() * Math.PI * 2.0D;
        double lx = Math.cos(phase);
        double lz = Math.sin(phase);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double curl = 0.07D * Math.sin(phase + t * len * 1.7D) * t;
            out.add(new double[]{top[0] + lx * curl, top[1] - len * t, top[2] + lz * curl, -lz, lx, Double.NaN, BRIDGE, 0.0D});
        }
        return out;
    }

    /** 林冠の下(幹から水平 1〜{@code radius}、幹の半分より上)で、葉か枝の真下が空いたマス。綱を吊る所の候補(点は下面のすぐ下)。 */
    private static List<double[]> canopyHangPoints(ServerLevel level, Trunk own, int radius, boolean leavesOnly) {
        List<double[]> found = new ArrayList<>();
        int cx = Mth.floor(own.centerX());
        int cz = Mth.floor(own.centerZ());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = own.topY() + 2; y > own.baseY() + own.height() / 2; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int dd = Math.max(Math.abs(dx), Math.abs(dz));
                    if (dd < 1 || own.contains(cx + dx, cz + dz)) {
                        continue;
                    }
                    m.set(cx + dx, y, cz + dz);
                    if (!level.isLoaded(m)) {
                        continue;
                    }
                    BlockState holder = level.getBlockState(m);
                    if (!(isLeaf(holder) || !leavesOnly && holder.is(ModBlocks.OVERGROWN_BRANCH.get()))) {
                        continue;
                    }
                    if (isOpen(level, m.below()) && isOpen(level, m.below(2))) {
                        found.add(new double[]{cx + dx + 0.5D, y - 0.02D, cz + dz + 0.5D});
                    }
                }
            }
        }
        return found;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** {@code target} を 4 の倍数ずらして {@code want} にいちばん近くする(何周目の u か)。 */
    private static double nearestWrap(double target, double want) {
        return target + Math.round((want - target) / 4.0D) * 4.0D;
    }

    /** 一様な Catmull-Rom(両端は端の値を伸ばす)で、節の並び {@code v} の t∈[0,1] の値。 */
    private static double catmullRom(double[] v, double t) {
        int n = v.length - 1;
        double x = t * n;
        int i = Math.min(n - 1, (int) Math.floor(x));
        double f = x - i;
        double p0 = v[Math.max(0, i - 1)];
        double p1 = v[i];
        double p2 = v[i + 1];
        double p3 = v[Math.min(n, i + 2)];
        return 0.5D * (2.0D * p1 + (-p0 + p2) * f + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * f * f
                + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * f * f * f);
    }

    /** 葉(過繁茂の葉はバニラの #leaves を持たないので、自分の葉も見る。TreeSideGrowth と同じ)。 */
    private static boolean isLeaf(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(ModBlocks.OVERGROWN_LEAVES.get())
                || state.is(ModBlocks.SIGILLARIA_LEAVES.get());
    }

    private static boolean isOpen(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** 周 u・高さ y の点のマスに、枝(継ぎ目)か原木がある。 */
    private static boolean blockedAt(Level level, Trunk t, double u, double y, double rho, double[] tmp) {
        perimeterPoint(t, u, rho, tmp);
        BlockPos cell = BlockPos.containing(tmp[0], y, tmp[1]);
        if (!level.isLoaded(cell)) {
            return true;
        }
        BlockState state = level.getBlockState(cell);
        return state.is(ModBlocks.OVERGROWN_BRANCH.get()) || state.is(ModBlocks.OVERGROWN_LOG.get());
    }

    /** 渡る線の途中(両端 0.5 ブロックを除く)に、原木・枝・固いブロックが無いか。葉は通す。 */
    private static boolean bridgeClear(Level level, double[] from, double[] to, Trunk own, Trunk neighbor) {
        double len = Math.sqrt(sq(to[0] - from[0]) + sq(to[1] - from[1]) + sq(to[2] - from[2]));
        int steps = Math.max(2, (int) Math.ceil(len / 0.25D));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            if (t * len < 0.5D || (1.0D - t) * len < 0.5D) {
                continue;
            }
            BlockPos c = BlockPos.containing(Mth.lerp(t, from[0], to[0]), Mth.lerp(t, from[1], to[1]) - 0.2D,
                    Mth.lerp(t, from[2], to[2]));
            if (!level.isLoaded(c)) {
                return false;
            }
            BlockState state = level.getBlockState(c);
            if (state.isAir() || state.canBeReplaced() || isLeaf(state)
                    || state.is(ModBlocks.OVERGROWN_LIANA.get())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 幹の柱のうち {@code near} に近い原木のマス(幹の範囲に丸める)。 */
    private static BlockPos nearestLogInTrunk(Trunk t, BlockPos near) {
        int x = Mth.clamp(near.getX(), t.minX(), t.minX() + t.width() - 1);
        int z = Mth.clamp(near.getZ(), t.minZ(), t.minZ() + t.width() - 1);
        int y = Mth.clamp(near.getY(), t.baseY(), t.topY() - 1);
        return new BlockPos(x, y, z);
    }

    /** 先頭の点(根元の端)の絶対座標。GameTest 用。 */
    public static Vec3 rootPoint(CorruptedLianaBlockEntity liana) {
        float[] p = liana.getClimbPath();
        BlockPos m = liana.getBlockPos();
        return new Vec3(m.getX() + (double) p[0], m.getY() + (double) p[1], m.getZ() + (double) p[2]);
    }
}
