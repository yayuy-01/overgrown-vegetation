package com.yayuy.overgrownvegetation.organ;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 乗っ取りの器官の形(2026-11-20。ユーザーと全体管理が試作 structure-fusion-proto 第 13 版で決めた 5 種類)。
 * 遠くから輪郭で働きが読めるようにする:
 * <ul>
 *   <li>フェーズ: 壁を破って中から膨れ出す、脳のような襞の塊。溝と壁に光る脈。芯は塊の真ん中。</li>
 *   <li>塔: 屋根から空へ伸びる、体節のある角笛。先がラッパに開き縁が光る。芯は根元。</li>
 *   <li>嚢: 軒・壁の高い所から垂れる涙形の袋(大はぶどうの房)。芯はいちばん重い袋の底。</li>
 *   <li>苗床: 壁一面のフジツボのような莢の群れ(大は口の開いた莢と地面へのこぼれ)。芯は群れの真ん中。</li>
 *   <li>井戸: 壁の中ほどの瘤から太い根が壁を伝って地面へ潜り、刺さった所に根晶。芯は瘤の正面。</li>
 * </ul>
 * 試作との違い: 試作は地面が y = 0 で平ら・部屋の中の印がある。ここでは面の外のマスから下へ探した実際の地面と、空が見えるかで代える。
 * 試作の光る脈(VEIN)は 1 マスの塊だが、ゲームの {@code organ_vein} は面に貼り付く薄いブロックなので、脈のマスは「まわりの固い面に貼り付く筋」にする
 * (襞の溝・ラッパの縁・袋の筋は、1 マス凹んだ溝の内側が光る形になる)。大きさ・本数は試作の値のまま(Claude の仮置き。FusionTable にはまだ出していない)。
 */
public final class TakeoverShapes {
    private TakeoverShapes() {
    }

    // ---------------------------------------------------------------- 調整値(design の organs.json → fusion.takeover.shapes。どれも仮)

    /** design json の形(種類ごと)。 */
    public static final class ShapesJson {
        public KindJson phase, tower, sac, nursery, ley;
    }

    /**
     * 種類ごとの調整値。size は長さ・太さ・広がりにまとめて掛ける倍率。count は数の [最小, 最大](フェーズ = 襞の塊・苗床 = 莢・井戸 = 根)。
     * length は長さの [最小, 最大](塔 = 角笛の長さ・嚢 = 袋の縦の半径)。veins は壁を這う光る脈の本数(フェーズ・苗床)。
     * extraBig は大きい版にだけ付く物の数 [最小, 最大](塔 = 小さい角笛・嚢 = 房の袋)。
     */
    public static final class KindJson {
        public Double sizeSmall, sizeBig;
        public double[] countSmall, countBig, lengthSmall, lengthBig, extraBig;
        public Integer veinsSmall, veinsBig;
    }

    /** 読んだ後の値(範囲を直した物)。 */
    public static final class Kind {
        final double sizeS, sizeB;
        final double[] countS, countB, lenS, lenB, extraB;
        final int veinsS, veinsB;

        Kind(@Nullable KindJson j, double[] countS, double[] countB, double[] lenS, double[] lenB, double[] extraB, int veinsS, int veinsB) {
            KindJson k = j != null ? j : new KindJson();
            this.sizeS = Math.max(0.3D, Math.min(3.0D, k.sizeSmall != null ? k.sizeSmall : 1.0D));
            this.sizeB = Math.max(0.3D, Math.min(3.0D, k.sizeBig != null ? k.sizeBig : 1.0D));
            this.countS = two(k.countSmall, countS);
            this.countB = two(k.countBig, countB);
            this.lenS = two(k.lengthSmall, lenS);
            this.lenB = two(k.lengthBig, lenB);
            this.extraB = two(k.extraBig, extraB);
            this.veinsS = Math.max(0, Math.min(16, k.veinsSmall != null ? k.veinsSmall : veinsS));
            this.veinsB = Math.max(0, Math.min(32, k.veinsBig != null ? k.veinsBig : veinsB));
        }

        private static double[] two(@Nullable double[] v, double[] d) {
            return v != null && v.length == 2 && v[0] >= 0 && v[0] <= v[1] ? v.clone() : d;
        }

        double size(boolean big) {
            return big ? sizeB : sizeS;
        }

        int count(boolean big, Random r) {
            return irange(big ? countB : countS, r);
        }

        double len(boolean big, Random r) {
            double[] v = big ? lenB : lenS;
            return v[0] + r.nextDouble() * (v[1] - v[0]);
        }

        int extra(Random r) {
            return irange(extraB, r);
        }

        int veins(boolean big) {
            return big ? veinsB : veinsS;
        }

        private static int irange(double[] v, Random r) {
            int lo = (int) Math.round(v[0]), hi = (int) Math.round(v[1]);
            return lo + r.nextInt(Math.max(1, hi - lo + 1));
        }
    }

    /** 5 種類の調整値(既定は試作 第 13 版の値)。 */
    public static final class Tuning {
        final Kind phase, tower, sac, nursery, ley;

        public Tuning(@Nullable ShapesJson j) {
            ShapesJson s = j != null ? j : new ShapesJson();
            double[] none = {0, 0};
            phase = new Kind(s.phase, new double[]{3, 4}, new double[]{8, 11}, none, none, none, 3, 9);
            tower = new Kind(s.tower, none, none, new double[]{5, 9}, new double[]{18, 28}, new double[]{2, 3}, 0, 0);
            sac = new Kind(s.sac, none, none, new double[]{1.7D, 2.3D}, new double[]{4.5D, 6.0D}, new double[]{2, 4}, 0, 0);
            nursery = new Kind(s.nursery, new double[]{6, 9}, new double[]{26, 37}, none, none, none, 2, 6);
            ley = new Kind(s.ley, new double[]{3, 4}, new double[]{9, 12}, none, none, none, 0, 0);
        }
    }

    private static Tuning tuning() {
        return com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.table().takeoverShapes;
    }

    private static final int STALK = OrganBlueprint.M_STALK, SAC = OrganBlueprint.M_SAC, CORE = OrganBlueprint.M_CORE, VEIN = OrganBlueprint.M_VEIN,
            EGG = OrganBlueprint.M_EGG, CRYS = OrganBlueprint.M_CRYSTAL, CARVE = OrganBlueprint.M_CARVE;

    /** 撮影・確認用: -Dov.takeoverKind=phase|tower|sac|nursery|ley で種類を固定、-Dov.takeoverBig=1|0 で大きさを固定。 */
    @Nullable
    static OrganKind forcedKind() {
        String s = System.getProperty("ov.takeoverKind");
        return s == null ? null : OrganKind.byId(s);
    }

    @Nullable
    static Boolean forcedBig() {
        String s = System.getProperty("ov.takeoverBig");
        return s == null ? null : "1".equals(s);
    }

    // ---------------------------------------------------------------- 世界の読み取り

    private static final class Ctx {
        final ServerLevel level;
        final Random rnd;
        final Map<Long, Integer> cells = new LinkedHashMap<>();
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int reads;

        Ctx(ServerLevel level, Random rnd) {
            this.level = level;
            this.rnd = rnd;
        }

        @Nullable
        BlockState state(int x, int y, int z) {
            if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return null;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
            reads++;
            return level.getBlockState(m.set(x, y, z));
        }

        /** 物を置けるマス(空気・草花。水は不可)。 */
        boolean free(int x, int y, int z) {
            BlockState s = state(x, y, z);
            return s != null && (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
        }

        /** 這う物が掴める固い面(建物・地面。森の木や器官は数えない)。 */
        boolean solid(int x, int y, int z) {
            BlockState s = state(x, y, z);
            return s != null && s.blocksMotion() && !Takeover.forestPart(s);
        }

        boolean artificial(int x, int y, int z) {
            BlockState s = state(x, y, z);
            return s != null && Takeover.isArtificial(level, m.set(x, y, z), s);
        }

        boolean touchesSolid(int x, int y, int z) {
            for (Direction d : Direction.values()) if (solid(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) return true;
            return false;
        }

        boolean touchesArtificial(int x, int y, int z) {
            for (Direction d : Direction.values()) if (artificial(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) return true;
            return false;
        }

        /** 下の空き(このマスの下に続く置けるマスの数。max まで)。 */
        int drop(int x, int y, int z, int max) {
            int n = 0;
            while (n < max && free(x, y - 1 - n, z)) n++;
            return n;
        }

        /** 外か(その列か、空いたマスづたいに 3 マス以内の列で空が見える)。試作の「部屋の中でない」の代わり。 */
        boolean outdoors(int x, int y, int z) {
            if (sky(x, y, z)) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int k = 1; k <= 3; k++) {
                    int qx = x + d.getStepX() * k, qz = z + d.getStepZ() * k;
                    if (!free(qx, y, qz)) break;
                    if (sky(qx, y, qz)) return true;
                }
            }
            return false;
        }

        private boolean sky(int x, int y, int z) {
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return false;
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= y;
        }

        // ---- 置く物

        Integer get(int x, int y, int z) {
            return cells.get(BlockPos.asLong(x, y, z));
        }

        /** 先に入った物が勝つ(試作の putT: 空いたマスにだけ置く)。 */
        void put(int x, int y, int z, int mat) {
            cells.putIfAbsent(BlockPos.asLong(x, y, z), mat);
        }

        void remove(int x, int y, int z, int onlyMat) {
            long k = BlockPos.asLong(x, y, z);
            Integer cur = cells.get(k);
            if (cur != null && cur == onlyMat) cells.remove(k);
        }

        void ball(double cx, double cy, double cz, double r, int mat) {
            int big = (int) Math.ceil(r);
            for (int a = -big; a <= big; a++) {
                for (int b = -big; b <= big; b++) {
                    for (int c = -big; c <= big; c++) {
                        int x = fl(cx) + a, y = fl(cy) + b, z = fl(cz) + c;
                        if (sq(x + 0.5D - cx) + sq(y + 0.5D - cy) + sq(z + 0.5D - cz) <= r * r) put(x, y, z, mat);
                    }
                }
            }
        }
    }

    private static int fl(double v) {
        return (int) Math.floor(v);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double[] nrm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return l < 1.0E-9D ? new double[]{0, 1, 0} : new double[]{x / l, y / l, z / l};
    }

    /** 軸 n に直交する 2 本。 */
    private static double[][] tangents(double[] n) {
        double[] a = Math.abs(n[1]) > 0.9D ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] t1 = nrm(n[1] * a[2] - n[2] * a[1], n[2] * a[0] - n[0] * a[2], n[0] * a[1] - n[1] * a[0]);
        double[] t2 = {n[1] * t1[2] - n[2] * t1[1], n[2] * t1[0] - n[0] * t1[2], n[0] * t1[1] - n[1] * t1[0]};
        return new double[][]{t1, t2};
    }

    private static double gauss(Random r) {
        return (r.nextDouble() + r.nextDouble() + r.nextDouble() - 1.5D) / 1.5D;
    }

    // ---------------------------------------------------------------- 共通の道具(試作の stalkT・blobT・coreT・veins・holeT・crawl)

    private record Stalk(double[] p, double[] d) {
    }

    /** うねる茎。ring > 0 なら節を一定の間隔で太らせる(塔の体節)。 */
    private static Stalk stalk(Ctx c, double[] p, double[] d, double len, double r0, double r1, double wob, double sag, double ring) {
        double ph = c.rnd.nextDouble() * Math.PI * 2.0D;
        List<double[]> pts = new ArrayList<>();
        for (double s = 0; s <= len; s += 0.5D) {
            d = nrm(d[0] + Math.cos(ph + s * 0.7D) * wob, d[1] - sag, d[2] + Math.sin(ph + s * 0.9D) * wob);
            p = new double[]{p[0] + d[0] * 0.5D, p[1] + d[1] * 0.5D, p[2] + d[2] * 0.5D};
            double r = r0 + (r1 - r0) * (s / len);
            double node = ring > 0 ? (s % ring < 0.6D ? r * 0.35D : 0.0D) : (c.rnd.nextDouble() < 0.1D ? r * 0.45D : 0.0D);
            pts.add(new double[]{p[0], p[1], p[2], r + node});
        }
        for (double[] q : pts) c.ball(q[0], q[1], q[2], q[3], STALK);
        return new Stalk(p, d);
    }

    @FunctionalInterface
    private interface MatFn {
        /** 0 なら置かない。s = 軸に沿った位置、rad = 軸からの距離、ang = 軸まわりの角。 */
        int at(int x, int y, int z, double s, double rad, double ang);
    }

    @FunctionalInterface
    private interface Width {
        double at(double t);
    }

    /** 軸 d の楕円体。width で太さを変える(涙形など)。 */
    private static void blob(Ctx c, double[] cen, double[] d, double ra, double rb, MatFn mf, double bumpA, @Nullable Width width) {
        int big = (int) Math.ceil(Math.max(ra, rb) + 2);
        double[][] t = tangents(d);
        for (int x = fl(cen[0] - big); x <= cen[0] + big; x++) {
            for (int y = fl(cen[1] - big); y <= cen[1] + big; y++) {
                for (int z = fl(cen[2] - big); z <= cen[2] + big; z++) {
                    double qx = x + 0.5D - cen[0], qy = y + 0.5D - cen[1], qz = z + 0.5D - cen[2];
                    double s = qx * d[0] + qy * d[1] + qz * d[2];
                    double u = qx * t[0][0] + qy * t[0][1] + qz * t[0][2], v = qx * t[1][0] + qy * t[1][1] + qz * t[1][2], rad = Math.hypot(u, v);
                    double w = width != null ? width.at(s / ra) : 1.0D;
                    double bump = bumpA * Math.sin(x * 1.3D + y * 0.7D) * Math.cos(z * 1.1D - y * 0.5D);
                    if (sq(s / ra) + sq(rad / (rb * w)) <= 1.0D + bump) {
                        int mat = mf.at(x, y, z, s, rad, Math.atan2(v, u));
                        if (mat != 0) c.put(x, y, z, mat);
                    }
                }
            }
        }
    }

    /** 芯: 嚢・卵・脈の上に入れる(茎は残す)。 */
    private static void core(Ctx c, double[] k, double kr) {
        for (int a = -3; a <= 3; a++) {
            for (int b = -3; b <= 3; b++) {
                for (int e = -3; e <= 3; e++) {
                    int x = fl(k[0]) + a, y = fl(k[1]) + b, z = fl(k[2]) + e;
                    if (sq(x + 0.5D - k[0]) + sq(y + 0.5D - k[1]) + sq(z + 0.5D - k[2]) > kr * kr + 0.3D) continue;
                    Integer cur = c.get(x, y, z);
                    if (cur == null || cur == SAC || cur == EGG || cur == VEIN) c.cells.put(BlockPos.asLong(x, y, z), CORE);
                }
            }
        }
    }

    /**
     * 芯が外から届くようにする: 芯のマスのどれも外の空気に触れていなければ、芯の真ん中から out の向きへ、被さった肉を 1 列どける。
     * (試作は見た目だけだが、ゲームでは芯が壊せないと器官を枯らせない。)
     */
    private static void exposeCore(Ctx c, double[] k, double[] out) {
        for (Map.Entry<Long, Integer> e : c.cells.entrySet()) {
            if (e.getValue() != CORE) continue;
            int x = BlockPos.getX(e.getKey()), y = BlockPos.getY(e.getKey()), z = BlockPos.getZ(e.getKey());
            if (!c.free(x, y, z)) continue;
            for (Direction d : Direction.values()) {
                int qx = x + d.getStepX(), qy = y + d.getStepY(), qz = z + d.getStepZ();
                if (c.get(qx, qy, qz) == null && c.free(qx, qy, qz)) return; // もう届く
            }
        }
        double[] p = {k[0], k[1], k[2]};
        for (int s = 0; s < 24; s++) {
            p[0] += out[0] * 0.5D;
            p[1] += out[1] * 0.5D;
            p[2] += out[2] * 0.5D;
            int x = fl(p[0]), y = fl(p[1]), z = fl(p[2]);
            Integer cur = c.get(x, y, z);
            if (cur == null) {
                if (c.free(x, y, z)) return;
                continue;
            }
            if (cur != CORE && cur != CARVE) c.cells.remove(BlockPos.asLong(x, y, z));
        }
    }

    /** 突き破った穴(人工のブロックだけ。戸は上下とも。置くときにも見直す)。 */
    private static void hole(Ctx c, int[] f, int r) {
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                for (int e = -r; e <= r; e++) {
                    if (a * a + b * b + e * e > r * r + 0.5D || c.rnd.nextDouble() >= 0.85D) continue;
                    hole1(c, f[0] + a, f[1] + b, f[2] + e);
                }
            }
        }
    }

    private static void hole1(Ctx c, int x, int y, int z) {
        BlockState s = c.state(x, y, z);
        if (s == null || s.hasBlockEntity() || !Takeover.isArtificial(c.level, c.m.set(x, y, z), s)) return;
        c.cells.put(BlockPos.asLong(x, y, z), CARVE);
        if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && s.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)) {
            int oy = s.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? y + 1 : y - 1;
            BlockState o = c.state(x, oy, z);
            if (o != null && o.is(s.getBlock())) c.cells.put(BlockPos.asLong(x, oy, z), CARVE);
        }
    }

    /** 這う物の決まり。 */
    private static final class Crawl {
        int mat, steps, sign = 1, thick;
        /** > 0 なら、建物を離れて地面の上を進んだ歩数がこれに達したら止まる(井戸の根は地面へ潜る)。 */
        int groundStop;
        /** 地面の上も這ってよいか(井戸の根だけ。脈・塔の掴む根は建物の面だけ: 試作の structAdj)。 */
        boolean ground;
        double upW, wrapW = 0.6D, noise = 1.0D;
        int cx, cz;
        @Nullable
        List<int[]> path;
    }

    /** 固い面(建物・地面)に触れる空いたマスだけを進む。置いたマスの数を返す。 */
    private static int crawl(Ctx c, int sx, int sy, int sz, Crawl o) {
        int x = sx, y = sy, z = sz, n = 0, onGround = 0;
        double px = 0, py = 0, pz = 0;
        Set<Long> seen = new HashSet<>();
        for (int s = 0; s < o.steps; s++) {
            int[] best = null;
            double bs = -1.0E9D, bdx = 0, bdy = 0, bdz = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int qx = x + dx, qy = y + dy, qz = z + dz;
                        long k = BlockPos.asLong(qx, qy, qz);
                        if (seen.contains(k)) continue;
                        Integer cur = c.cells.get(k);
                        if (cur != null && cur != o.mat) continue; // 同じ材料の上は通れる(根どうしが束になる)
                        if (!c.free(qx, qy, qz) || !(o.ground ? c.touchesSolid(qx, qy, qz) : c.touchesArtificial(qx, qy, qz))) continue;
                        double rx = qx - o.cx, rz = qz - o.cz, rl = Math.max(1.0E-3D, Math.hypot(rx, rz));
                        double tx = -rz / rl * o.sign, tz = rx / rl * o.sign, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double sc = (dx * tx + dz * tz) / len * o.wrapW + dy / len * o.upW + (dx * px + dy * py + dz * pz) / len * 0.7D
                                + (c.rnd.nextDouble() - 0.5D) * o.noise + 0.9D;
                        if (sc > bs) {
                            bs = sc;
                            best = new int[]{qx, qy, qz};
                            bdx = dx / len;
                            bdy = dy / len;
                            bdz = dz / len;
                        }
                    }
                }
            }
            if (best == null) break;
            x = best[0];
            y = best[1];
            z = best[2];
            px = bdx;
            py = bdy;
            pz = bdz;
            seen.add(BlockPos.asLong(x, y, z));
            if (o.path != null) o.path.add(new int[]{x, y, z});
            if (o.groundStop > 0 && c.solid(x, y - 1, z) && !c.artificial(x, y - 1, z)) {
                if (++onGround >= o.groundStop) {
                    if (c.get(x, y, z) == null) n++;
                    c.put(x, y, z, o.mat);
                    break;
                }
            }
            if (c.get(x, y, z) == null) n++;
            c.put(x, y, z, o.mat);
            if (o.thick > 0 && s < o.thick) {
                // 太い根: 横の隣(固い面に触れるマス)にも
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int tx = x + d.getStepX(), tz = z + d.getStepZ();
                    if (c.free(tx, y, tz) && c.touchesSolid(tx, y, tz)) c.put(tx, y, tz, o.mat);
                }
            }
        }
        return n;
    }

    /** 光る脈が面から壁を這う。 */
    private static void veins(Ctx c, int[] f, int n, int stepsMin, int stepsRand) {
        for (int k = 0; k < n; k++) {
            Crawl o = new Crawl();
            o.mat = VEIN;
            o.steps = stepsMin + c.rnd.nextInt(Math.max(1, stepsRand));
            o.sign = k % 2 == 1 ? 1 : -1;
            o.upW = (c.rnd.nextDouble() - 0.5D) * 1.6D;
            o.wrapW = 0.5D;
            o.noise = 1.1D;
            o.cx = f[0];
            o.cz = f[2];
            crawl(c, f[0] + f[3], f[1] + f[4], f[2] + f[5], o);
        }
    }

    // ---------------------------------------------------------------- 5 種類の形

    /** フェーズ: 壁を破って中から膨れ出す、脳のような襞の塊。溝が光る脈。芯は塊の真ん中に半ば出る。壁一面に光る脈が走る。 */
    private static double[][] phase(Ctx c, int[] f, boolean big) {
        Random r = c.rnd;
        double[] n = {f[3], f[4], f[5]};
        double[][] t = tangents(n);
        hole(c, f, big ? 2 : 1);
        Kind tn = tuning().phase;
        double sz = tn.size(big);
        veins(c, f, tn.veins(big), big ? 18 : 8, big ? 40 : 14); // 塊より先に(塊の中からは這い出せない)
        int lobes = tn.count(big, r);
        double spread = (big ? 4.5D : 1.4D) * sz, out = (big ? 2.0D : 0.8D) * sz, up = f[4] == 0 ? (big ? 1.2D : 0.3D) * sz : 0.0D;
        double[] c0 = {f[0] + 0.5D + n[0] * out, f[1] + 0.5D + n[1] * out + up, f[2] + 0.5D + n[2] * out};
        MatFn fold = (x, y, z, s, rad, ang) -> {
            double g = Math.sin(x * 1.25D + y * 0.6D + z * 0.3D) * Math.sin(z * 1.15D - y * 0.95D + x * 0.2D) + 0.35D * Math.sin(y * 1.7D + x * 0.4D);
            return Math.abs(g) < 0.16D ? VEIN : SAC;
        };
        for (int k = 0; k < lobes; k++) {
            double a = gauss(r) * spread, b = gauss(r) * spread * 0.8D, e = (r.nextDouble() - 0.35D) * spread * 0.7D;
            double[] cen = {c0[0] + t[0][0] * a + t[1][0] * b + n[0] * e, c0[1] + t[0][1] * a + t[1][1] * b + n[1] * e, c0[2] + t[0][2] * a + t[1][2] * b + n[2] * e};
            double rr = (big ? 2.4D + r.nextDouble() * 1.8D : 0.9D + r.nextDouble() * 0.7D) * sz;
            blob(c, cen, nrm(n[0] + gauss(r) * 0.6D, n[1] + gauss(r) * 0.6D, n[2] + gauss(r) * 0.6D), rr * 1.1D, rr, fold, 0.2D, null);
        }
        double ko = (big ? 2.4D : 1.0D) * sz;
        double[] k = {c0[0] + n[0] * ko, c0[1] + n[1] * ko, c0[2] + n[2] * ko};
        core(c, k, big ? 1.5D : 0.65D);
        return new double[][]{k, n};
    }

    /** 塔: 屋根から空へ伸びる、体節のある固い角笛。先がラッパに開き、口の縁が光る。根元は屋根を掴む膨らみで、芯はそこ。大きい塔は小さい角笛 2〜3 本。 */
    private static double[][] tower(Ctx c, int[] f, boolean big, boolean sub) {
        Random r = c.rnd;
        int nx = f[3], ny = f[4], nz = f[5];
        if (big && !sub) hole(c, f, 1);
        double lean = big ? 0.25D : 0.45D;
        double[] d = ny == 1 ? nrm((r.nextDouble() - 0.5D) * lean * 2, 1, (r.nextDouble() - 0.5D) * lean * 2)
                : nrm(nx * 0.45D + (r.nextDouble() - 0.5D) * 0.3D, 1.4D, nz * 0.45D + (r.nextDouble() - 0.5D) * 0.3D);
        double[] base = {f[0] + 0.5D + nx * 0.7D, f[1] + 0.5D + ny * 0.7D, f[2] + 0.5D + nz * 0.7D};
        Kind tn = tuning().tower;
        double sz = tn.size(big);
        double footR = (big ? 2.8D : sub ? 0.9D : 1.2D) * sz;
        if (!sub) {
            for (int k = 0; k < (big ? 5 : 2); k++) { // 屋根を掴む根
                Crawl o = new Crawl();
                o.mat = STALK;
                o.steps = 6 + r.nextInt(big ? 14 : 6);
                o.sign = k % 2 == 1 ? 1 : -1;
                o.upW = -0.6D;
                o.wrapW = 0.7D;
                o.cx = f[0];
                o.cz = f[2];
                crawl(c, f[0] + nx, f[1] + ny, f[2] + nz, o);
            }
        }
        blob(c, base, new double[]{0, 1, 0}, footR * 0.8D, footR, (x, y, z, s, rad, ang) -> SAC, 0.3D, null);
        double len = sub ? (4 + r.nextDouble() * 4) * sz : tn.len(big, r);
        double r0 = (big ? 1.9D : sub ? 0.6D : 0.8D) * sz, r1 = (big ? 1.2D : sub ? 0.45D : 0.55D) * sz;
        Stalk st = stalk(c, base, d, len, r0, r1, big ? 0.05D : 0.09D, -0.004D, big ? 3 : 2);
        // ラッパの口: 筒の殻(中は空)。縁は光る脈
        double ml = (big ? 4.5D : 2.0D) * sz, rm = (big ? 3.6D : sub ? 1.2D : 1.6D) * sz;
        double[] p = st.p, dd = st.d;
        int big2 = (int) Math.ceil(rm + ml + 1);
        for (int x = fl(p[0] - big2); x <= p[0] + big2; x++) {
            for (int y = fl(p[1] - 1); y <= p[1] + big2; y++) {
                for (int z = fl(p[2] - big2); z <= p[2] + big2; z++) {
                    double qx = x + 0.5D - p[0], qy = y + 0.5D - p[1], qz = z + 0.5D - p[2], s = qx * dd[0] + qy * dd[1] + qz * dd[2];
                    if (s < -0.3D || s > ml) continue;
                    double rad = Math.sqrt(sq(qx - dd[0] * s) + sq(qy - dd[1] * s) + sq(qz - dd[2] * s));
                    double rt = r1 + (rm - r1) * Math.pow(Math.max(0, s) / ml, 1.6D);
                    if (rad <= rt + 0.5D && rad >= rt - 0.75D) c.put(x, y, z, s > ml - 0.9D ? VEIN : SAC);
                    else if (rad < rt - 0.75D) c.remove(x, y, z, STALK); // 喉を空ける
                }
            }
        }
        double[][] ret = null;
        if (!sub) {
            double[] k = {base[0] + nx * footR * 0.6D, base[1] + footR * 0.35D, base[2] + nz * footR * 0.6D};
            core(c, k, big ? 1.4D : 0.6D);
            ret = new double[][]{k, ny == 1 ? nrm(d[0] == 0 && d[2] == 0 ? 1 : -d[0], 0.3D, -d[2]) : new double[]{nx, 0.2D, nz}};
        }
        if (big && !sub) {
            for (int k = 0, m = tn.extra(r); k < m; k++) {
                double a = r.nextDouble() * Math.PI * 2.0D;
                tower(c, new int[]{(int) Math.round(f[0] + Math.cos(a) * 2.5D), f[1], (int) Math.round(f[2] + Math.sin(a) * 2.5D), nx, ny, nz}, false, true);
            }
        }
        return ret;
    }

    /** 嚢: 軒・壁の高い所から、重みで垂れ下がる涙形の袋。茎は重さで下へ曲がる。大はぶどうの房。いちばん重い袋の底に芯。縦の光る脈。 */
    private static double[][] sac(Ctx c, int[] f, boolean big) {
        Random r = c.rnd;
        int nx = f[3], ny = f[4], nz = f[5];
        if (big) hole(c, f, 1);
        double[] p = {f[0] + 0.5D + nx * 0.6D, f[1] + 0.5D + ny * 0.6D, f[2] + 0.5D + nz * 0.6D};
        double[] d = ny == -1 ? nrm((r.nextDouble() - 0.5D) * 0.4D, -1, (r.nextDouble() - 0.5D) * 0.4D)
                : nrm(nx + (r.nextDouble() - 0.5D) * 0.4D, ny == 1 ? 0.8D : 0.35D, nz + (r.nextDouble() - 0.5D) * 0.4D);
        double len = big ? 4 + r.nextDouble() * 3 : 1.5D + r.nextDouble() * 2;
        Kind tn = tuning().sac;
        double sz = tn.size(big);
        double want = tn.len(big, r);
        // 柄が長いと袋の下の空きが無くなって袋が小さくなる(実物の壁は試作より低い)。袋が入るぶんを先に取り、残りを柄にする
        int drop0 = c.drop(f[0] + nx, f[1] + ny, f[2] + nz, 24);
        len = Math.max(big ? 2.0D : 1.2D, Math.min(len, drop0 - 2.0D * want - 1.0D));
        Stalk st = stalk(c, p, d, len, (big ? 1.2D : 0.6D) * sz, (big ? 0.9D : 0.45D) * sz, 0.06D, big ? 0.16D : 0.22D, 0);
        Width tear = tt -> 0.55D + 0.45D * (tt + 1) / 2; // 軸は下向き: 上(t = -1)が細く下が太い
        MatFn ribs = (x, y, z, s, rad, ang) -> rad > 1.2D && Math.cos(ang * 5 + y * 0.15D) > 0.95D ? VEIN : SAC; // 筋は外側だけ(芯のまわりまで溝にすると袋が割れて見える)
        double[] down = {0, -1, 0};
        // 袋の大きさは、下の空き(地面まで)に合わせる。低い軒では小ぶりの袋が地面すれすれに垂れる
        int drop = c.drop(fl(st.p[0]), fl(st.p[1]), fl(st.p[2]), 16);
        double ra = Math.max(big ? 2.2D : 1.1D, Math.min(want, drop * 0.5D)), rb = ra * (big ? 0.7D : 0.66D) * sz;
        double[] top = clearOfWalls(c, st.p, ra, rb);
        double[] cen = {top[0], top[1] - ra * 0.85D, top[2]};
        // 下に空きが無い面(屋根の上・床のすぐ上)から生えた嚢は、垂れずにその面の上に座る(合う面が無いときはどこからでも生える)
        int room = c.drop(fl(top[0]), fl(top[1]), fl(top[2]), 24);
        boolean rests = room < ra * 1.5D;
        if (rests) cen[1] = Math.max(cen[1], top[1] - room + ra * 0.8D);
        blob(c, cen, down, ra, rb, ribs, 0.12D, tear);
        if (big) {
            for (int k = 0, m = tn.extra(r); k < m; k++) {
                double a = r.nextDouble() * Math.PI * 2.0D, h = r.nextDouble() * ra * 0.6D;
                double[] top2 = {top[0] + Math.cos(a) * rb * 0.9D, top[1] - h, top[2] + Math.sin(a) * rb * 0.9D};
                Stalk s2 = stalk(c, top2, nrm(Math.cos(a), 0.2D, Math.sin(a)), 1.5D + r.nextDouble() * 1.5D, 0.6D, 0.45D, 0.05D, 0.3D, 0);
                double ra2 = (1.8D + r.nextDouble() * 1.2D) * sz, rb2 = (1.2D + r.nextDouble() * 0.7D) * sz;
                blob(c, new double[]{s2.p[0], s2.p[1] - ra2 * 0.85D, s2.p[2]}, down, ra2, rb2, ribs, 0.12D, tear);
            }
        }
        if (rests) {
            // 座った袋は底が面に付くので、芯は横腹に出す
            double[] h = nrm(st.d[0], 0, st.d[2]);
            if (Math.abs(h[1]) > 0.5D) h = new double[]{1, 0, 0};
            double[] ks = {cen[0] + h[0] * rb * 0.7D, cen[1] + ra * 0.1D, cen[2] + h[2] * rb * 0.7D};
            core(c, ks, big ? 1.3D : 0.6D);
            return new double[][]{ks, h};
        }
        double[] k = {cen[0], cen[1] - ra * 0.82D, cen[2]};
        core(c, k, big ? 1.3D : 0.6D);
        return new double[][]{k, down};
    }

    /**
     * 袋の吊り元を壁から離す(試作の軒は広いが、実物の軒は 1 マスのことが多く、真下に下げると袋の半分が壁にめり込んで平たくなる)。
     * 袋のいちばん太い高さで四方の壁までの距離を見て、足りない分だけ外へずらし、元の吊り元とは短い茎でつなぐ。
     */
    private static double[] clearOfWalls(Ctx c, double[] p, double ra, double rb) {
        int x = fl(p[0]), y = fl(p[1] - ra * 1.1D), z = fl(p[2]);
        double sx = 0, sz = 0;
        int need = (int) Math.ceil(rb);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int k = 1; k <= need; k++) {
                if (c.solid(x + d.getStepX() * k, y, z + d.getStepZ() * k) || c.solid(x + d.getStepX() * k, fl(p[1]) - 1, z + d.getStepZ() * k)) {
                    sx -= d.getStepX() * (need - k + 1);
                    sz -= d.getStepZ() * (need - k + 1);
                    break;
                }
            }
        }
        if (sx == 0 && sz == 0) return p;
        double[] q = {p[0] + sx, p[1], p[2] + sz};
        int n = (int) Math.ceil(Math.hypot(sx, sz) * 2);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            c.ball(p[0] + sx * t, p[1] - Math.sin(t * Math.PI) * 0.6D, p[2] + sz * t, 0.75D, STALK);
        }
        return q;
    }

    /** 苗床: 茎なし。壁一面にフジツボのような莢が広がり、脈が莢をつなぐ。大は口の開いた大きな莢が混じり、壁の下の地面にもこぼれる。芯は群れの真ん中。 */
    private static double[][] nursery(Ctx c, int[] f, boolean big) {
        Random r = c.rnd;
        double[] n = {f[3], f[4], f[5]};
        double[][] t = tangents(n);
        Kind tn = tuning().nursery;
        double sz = tn.size(big);
        int pods = tn.count(big, r);
        double spread = (big ? 6.5D : 2.2D) * sz;
        veins(c, f, tn.veins(big), big ? 14 : 6, big ? 26 : 10);
        for (int k = 0; k < pods; k++) {
            double a = gauss(r) * spread, b = gauss(r) * spread;
            double[] p = {f[0] + 0.5D + t[0][0] * a + t[1][0] * b, f[1] + 0.5D + t[0][1] * a + t[1][1] * b, f[2] + 0.5D + t[0][2] * a + t[1][2] * b};
            int bx = fl(p[0]), by = fl(p[1]), bz = fl(p[2]);
            boolean onWall = c.solid(bx, by, bz);
            if (!onWall && r.nextDouble() < 0.7D) continue; // 面から外れた莢は少しだけ(縁からはみ出す)
            boolean mouth = big && r.nextDouble() < 0.18D;
            double rr = (mouth ? 1.6D + r.nextDouble() * 0.8D : 0.55D + r.nextDouble() * 0.6D) * sz;
            double[] dd = nrm(n[0] + gauss(r) * 0.5D, n[1] + gauss(r) * 0.5D + 0.15D, n[2] + gauss(r) * 0.5D);
            double off = 0.5D + rr * 0.8D;
            double[] cen = {p[0] + n[0] * off, p[1] + n[1] * off, p[2] + n[2] * off};
            final double fr = rr;
            blob(c, cen, dd, rr * 1.3D, rr, (x, y, z, s, rad, ang) -> mouth && s > fr * 0.35D && rad < fr * 0.55D ? 0 : (mouth && s > fr * 1.05D ? VEIN : EGG), 0.1D, null);
            if (mouth) { // 口の中を空ける(前の莢に埋まった所も)
                for (int x = fl(cen[0] - 2); x <= cen[0] + 2; x++) {
                    for (int y = fl(cen[1] - 2); y <= cen[1] + 2; y++) {
                        for (int z = fl(cen[2] - 2); z <= cen[2] + 2; z++) {
                            double qx = x + 0.5D - cen[0], qy = y + 0.5D - cen[1], qz = z + 0.5D - cen[2], s = qx * dd[0] + qy * dd[1] + qz * dd[2];
                            if (s > rr * 0.2D && Math.sqrt(sq(qx - dd[0] * s) + sq(qy - dd[1] * s) + sq(qz - dd[2] * s)) < rr * 0.5D) c.remove(x, y, z, EGG);
                        }
                    }
                }
            }
        }
        if (big && f[4] == 0) { // 壁の下の地面にこぼれる
            for (int k = 0; k < 8; k++) {
                int x = (int) Math.round(f[0] + f[3] * (1 + r.nextDouble() * 4) + t[0][0] * gauss(r) * 4);
                int z = (int) Math.round(f[2] + f[5] * (1 + r.nextDouble() * 4) + t[0][2] * gauss(r) * 4);
                int gy = groundBelow(c, x, f[1], z, 12);
                if (gy != Integer.MIN_VALUE) blob(c, new double[]{x + 0.5D, gy + 1.4D, z + 0.5D}, new double[]{0, 1, 0}, 1.1D, 0.8D, (xx, yy, zz, s, rad, ang) -> EGG, 0.1D, null);
            }
        }
        double[] k = {f[0] + 0.5D + n[0] * 1.1D, f[1] + 0.5D + n[1] * 1.1D, f[2] + 0.5D + n[2] * 1.1D};
        core(c, k, big ? 1.3D : 0.6D);
        return new double[][]{k, n};
    }

    /** 井戸: 塔の逆。壁の中ほどに瘤ができ、太い根が何本も壁を伝って下り、地面へ潜る。刺さった所に根晶の束。芯は瘤の正面。 */
    private static double[][] ley(Ctx c, int[] f, boolean big) {
        Random r = c.rnd;
        double[] n = {f[3], f[4], f[5]};
        if (big) hole(c, f, 1);
        Kind tn = tuning().ley;
        double sz = tn.size(big);
        double kr = (big ? 2.6D : 1.1D) * sz;
        double[] kc = {f[0] + 0.5D + n[0] * kr * 0.6D, f[1] + 0.5D + n[1] * kr * 0.6D, f[2] + 0.5D + n[2] * kr * 0.6D};
        // 根を先に伸ばしてから瘤を被せる(瘤の中からは這い出せないため)
        int roots = tn.count(big, r);
        for (int k = 0; k < roots; k++) {
            Crawl o = new Crawl();
            o.mat = STALK;
            o.path = new ArrayList<>();
            o.steps = (big ? 26 : 12) + r.nextInt(big ? 30 : 12);
            o.sign = k % 2 == 1 ? 1 : -1;
            o.upW = -1.5D;
            o.wrapW = 0.25D + r.nextDouble() * 0.4D;
            o.noise = 0.7D;
            o.thick = big && k < 5 ? 10 : 0;
            o.groundStop = 2;
            o.ground = true;
            o.cx = f[0];
            o.cz = f[2];
            crawl(c, f[0] + f[3], f[1] + f[4], f[2] + f[5], o);
            if (o.path.isEmpty()) continue;
            int[] end = o.path.get(o.path.size() - 1);
            // 地面(人工でない固い面)に着いた根だけ: 根晶の束を立てる
            if (!c.solid(end[0], end[1] - 1, end[2]) || c.artificial(end[0], end[1] - 1, end[2])) continue;
            int cn = big ? 3 + r.nextInt(4) : 1 + r.nextInt(2);
            for (int q = 0; q < cn; q++) {
                int x = end[0] + (int) Math.round(gauss(r) * 1.5D), z = end[2] + (int) Math.round(gauss(r) * 1.5D), h = 1 + r.nextInt(big ? 4 : 2);
                int gy = groundBelow(c, x, end[1] + 2, z, 5);
                if (gy == Integer.MIN_VALUE || c.artificial(x, gy, z)) continue;
                for (int y = gy + 1; y <= gy + h; y++) c.put(x, y, z, CRYS);
            }
        }
        MatFn knot = (x, y, z, s, rad, ang) -> Math.sin(x * 1.9D + y * 1.3D) * Math.cos(z * 1.7D - x * 0.8D) > 0.55D ? VEIN : STALK;
        blob(c, kc, nrm(n[0], n[1] - 0.4D, n[2]), kr * 1.15D, kr, knot, 0.3D, null);
        double[] k = {kc[0] + n[0] * kr * 0.75D, kc[1] + n[1] * kr * 0.75D, kc[2] + n[2] * kr * 0.75D};
        coreOverStalk(c, k, big ? 1.4D : 0.6D);
        return new double[][]{k, n};
    }

    /** 井戸の瘤は茎でできているので、芯は茎の上にも入れる。 */
    private static void coreOverStalk(Ctx c, double[] k, double kr) {
        for (int a = -3; a <= 3; a++) {
            for (int b = -3; b <= 3; b++) {
                for (int e = -3; e <= 3; e++) {
                    int x = fl(k[0]) + a, y = fl(k[1]) + b, z = fl(k[2]) + e;
                    if (sq(x + 0.5D - k[0]) + sq(y + 0.5D - k[1]) + sq(z + 0.5D - k[2]) > kr * kr + 0.3D) continue;
                    Integer cur = c.get(x, y, z);
                    if (cur == null || cur != CARVE) c.cells.put(BlockPos.asLong(x, y, z), CORE);
                }
            }
        }
    }

    /** (x, z) の列で、y から下へ max マスまでに見つかる「上が空いた固い面」の高さ。無ければ MIN_VALUE。 */
    private static int groundBelow(Ctx c, int x, int y, int z, int max) {
        for (int k = 0; k <= max; k++) {
            int gy = y - k;
            if (c.solid(x, gy, z) && c.free(x, gy + 1, z)) return gy;
        }
        return Integer.MIN_VALUE;
    }

    // ---------------------------------------------------------------- 生える面

    /** 種類ごとの生えやすい面(合う面が無ければ、どこからでも生えてよい)。 */
    static boolean faceFits(ServerLevel level, OrganKind kind, Takeover.Face f) {
        Ctx c = new Ctx(level, new Random(0L));
        int x = f.x() + f.nx(), y = f.y() + f.ny(), z = f.z() + f.nz(), ny = f.ny();
        switch (kind) {
            case TOWER:
                return ny == 1;
            case SAC: {
                // 外の軒・壁の高い所(下に 3 マス以上の空きがあり、部屋の中でない)
                if (ny == 1) return false;
                int drop = c.drop(x, y, z, 4);
                return drop >= 3 && c.outdoors(x, y, z) && (ny == -1 || drop >= 4);
            }
            case NURSERY:
                return ny == 0 && c.drop(x, y, z, 8) <= 7;
            case PHASE:
                return ny == 0;
            case LEY: {
                if (ny != 0) return false;
                int drop = c.drop(x, y, z, 13);
                return drop >= 3 && drop <= 12 && c.outdoors(x, y, z);
            }
            default:
                return true;
        }
    }

    // ---------------------------------------------------------------- 計画

    /** 直前の計画の時間(ms)とブロックの読み(重さの報告用)。 */
    static double lastPlanMs;
    static int lastPlanReads;

    /**
     * 器官の形を作る。返すのは設計図の列(穴 → 本体 → 光る脈)。芯の置けるマスが無ければ null。
     * 置くのは {@link OrganWorld#place} が空気・草花のマスにだけ(穴は人工のブロックだけ)。
     */
    @Nullable
    static long[] plan(ServerLevel level, ForestOrgans.Organ o) {
        int[] f = o.takeover;
        if (f == null || f.length < 6) return null;
        long t0 = System.nanoTime();
        boolean big = !o.small;
        Ctx c = new Ctx(level, new Random(o.seed * 0x9E3779B97F4A7C15L + 0x7A0EL));
        double[][] k = switch (o.kind) {
            case PHASE -> phase(c, f, big);
            case TOWER -> tower(c, f, big, false);
            case SAC -> sac(c, f, big);
            case NURSERY -> nursery(c, f, big);
            case LEY -> ley(c, f, big);
        };
        if (k != null) exposeCore(c, k[0], nrm(k[1][0], k[1][1], k[1][2]));
        // 芯の置けるマスがあるか(建物のブロックに埋もれて壊せない芯は作らない)
        boolean core = false;
        for (Map.Entry<Long, Integer> e : c.cells.entrySet()) {
            if (e.getValue() != CORE) continue;
            if (c.free(BlockPos.getX(e.getKey()), BlockPos.getY(e.getKey()), BlockPos.getZ(e.getKey()))) {
                core = true;
                break;
            }
        }
        lastPlanMs = (System.nanoTime() - t0) / 1.0E6D;
        lastPlanReads = c.reads;
        if (!core) return null;
        int ax = f[0], ay = f[1], az = f[2];
        LongArrayList out = new LongArrayList(c.cells.size());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 穴 → 本体 → 光る脈(脈は支えの面より後に置く)
        for (int pass = 0; pass < 3; pass++) {
            for (Map.Entry<Long, Integer> e : c.cells.entrySet()) {
                int mat = e.getValue();
                if ((pass == 0) != (mat == CARVE) || (pass == 2) != (mat == VEIN)) continue;
                long key = e.getKey();
                int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
                int dx = x - ax, dy = y - ay, dz = z - az;
                if (Math.abs(dx) > 120 || Math.abs(dy) > 120 || Math.abs(dz) > 120) continue;
                if (com.yayuy.overgrownvegetation.corruption.pushback.RingWards.guarded(level, m.set(x, y, z))) continue; // 押し返す道具の効く所には伸ばさない
                int faces = 0;
                if (mat == VEIN) {
                    // 脈は、まわりの固い面(器官の肉・建物・地面)に貼り付く。貼り付く面が無ければ肉にする
                    for (Direction d : Direction.values()) {
                        int qx = x + d.getStepX(), qy = y + d.getStepY(), qz = z + d.getStepZ();
                        Integer nb = c.get(qx, qy, qz);
                        boolean support = nb != null ? nb != VEIN && nb != CARVE && c.free(qx, qy, qz) : solidAny(c, qx, qy, qz);
                        if (support) faces |= 1 << d.get3DDataValue();
                    }
                    if (faces == 0) {
                        if (pass == 2) out.add(OrganBlueprint.pack(dx, dy, dz, SAC, 0));
                        continue;
                    }
                }
                out.add(OrganBlueprint.pack(dx, dy, dz, mat, faces));
            }
        }
        return out.toLongArray();
    }

    private static boolean solidAny(Ctx c, int x, int y, int z) {
        BlockState s = c.state(x, y, z);
        return s != null && s.blocksMotion();
    }
}
