package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 構造物の木(2026-11-19 構造物と森の融合 段 2。ユーザー決定「1 と 3 の組み合わせ」、試作 structure-fusion-proto.html 第 7 版の
 * {@code structTreeDims}・{@code scanPiece}・{@code chooseRuinAnchor}・{@code growRuinTree}・{@code tube}・{@code surfNormal}・{@code drapeRoot}・
 * {@code rootlets}・{@code runOut} をゲームへ移したもの)。全体の大きさが小・中の構造物に 1 本、建物から生える。
 * <ul>
 *   <li><b>中から</b>: 空へ開いた中(屋根の穴・開いた天辺)があれば、その床から生えて穴を抜ける。穴の中では幹は穴に合わせて細り、通り道も穴に収める。
 *       根の半分は床を這って内壁を登り、残りは壁の上を越えて外へ垂れる。</li>
 *   <li><b>てっぺんから</b>: 開いた中が無ければ、屋根の天辺(高さの分位で煙突を外した、真ん中に近い列)に根を下ろす。根は屋根を這い、壁を伝って地面まで垂れ、
 *       地面ではくねりながら建物から離れる。</li>
 *   <li>幹は {@code bendEvery} マスごとに向きを変えてうねる。根は道筋を全部決めてから管状に太らせる(断面は壁・地面から離れる向きへ半径ぶん浮かせる)。</li>
 * </ul>
 * 置くのは空気と置き換えられる草花のマスだけ(建物のブロック・地面・水には置かない)。入口の通り道は空け、部屋の中には葉を茂らせない。
 * 世界は読むだけで、置くのは {@link StructureTrees} が少しずつ。主スレッドで 1 回だけ呼ぶ。
 */
public final class StructureTree {
    private StructureTree() {
    }

    public static final byte TRUNK = 1, ROOT = 2, BRANCH = 3, LEAF = 4, GIANT_ROOT = 5;

    /** 計画。{@code pos} は {@link BlockPos#asLong}、下から順。 */
    public record Plan(long[] pos, byte[] mat, String mode, BlockPos anchor, int height, int cut) {
    }

    /** 作った結果。{@code plan} が null なら {@code why} が理由。 */
    public record Result(@Nullable Plan plan, String why) {
        static Result fail(String why) {
            return new Result(null, why);
        }
    }

    /** 計画を下から順に並べる(幹が根元から伸びて見えるように)。 */
    static Plan pack(W w, String mode, BlockPos anchor, int height) {
        long[] pos = w.planned.keySet().toLongArray();
        Long[] boxed = new Long[pos.length];
        for (int i = 0; i < pos.length; i++) boxed[i] = pos[i];
        Arrays.sort(boxed, (a, b) -> {
            int ya = BlockPos.getY(a), yb = BlockPos.getY(b);
            if (ya != yb) return Integer.compare(ya, yb);
            return Integer.compare(w.planned.get(a.longValue()), w.planned.get(b.longValue()));
        });
        byte[] mat = new byte[pos.length];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = boxed[i];
            mat[i] = w.planned.get(pos[i]);
        }
        return new Plan(pos, mat, mode, anchor, height, w.cut);
    }

    // ---------------------------------------------------------------- 建物を見る

    /** 足を置ける高さ(葉を除いた高さ地図 = 地面の上の最初のマス)。読み込まれていなければ Integer.MIN_VALUE。 */
    static int surfaceAt(OrganFusion.Ctx c, int x, int z) {
        LevelChunk ch = c.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (ch == null) return Integer.MIN_VALUE;
        int y = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        // 高さ地図は 1 ずれていることがあるので、そのまわりで「下が固く、そこが空いている」高さに合わせる
        int min = c.level.getMinBuildHeight() + 1, max = c.level.getMaxBuildHeight() - 2;
        for (int k = 0; k < 4 && y < max && c.state(x, y, z).blocksMotion(); k++) y++;
        for (int k = 0; k < 4 && y > min && !c.state(x, y - 1, z).blocksMotion(); k++) y--;
        return y;
    }

    /** 構造物のまわりの地面のブロックの高さ(全体の箱の 2 外側の列の高さ地図の中央値)。 */
    static int ground(OrganFusion.Ctx c, BoundingBox b) {
        List<Integer> hs = new ArrayList<>();
        int x0 = b.minX() - 2, x1 = b.maxX() + 2, z0 = b.minZ() - 2, z1 = b.maxZ() + 2;
        int stepX = Math.max(1, (x1 - x0) / 8), stepZ = Math.max(1, (z1 - z0) / 8);
        for (int x = x0; x <= x1; x += stepX) {
            for (int z : new int[]{z0, z1}) {
                int h = surfaceAt(c, x, z);
                if (h == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                hs.add(h - 1);
            }
        }
        for (int z = z0; z <= z1; z += stepZ) {
            for (int x : new int[]{x0, x1}) {
                int h = surfaceAt(c, x, z);
                if (h == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                hs.add(h - 1);
            }
        }
        hs.sort(Integer::compare);
        return hs.get(hs.size() / 2);
    }

    // ---------------------------------------------------------------- 置く物を決める

    /** 計画の入れ物と、置いてよいかの判定(試作の place)。 */
    static final class W {
        final OrganFusion.Ctx c;
        final int ground;
        final Random rnd;
        final Long2ByteOpenHashMap planned = new Long2ByteOpenHashMap();
        int cut;
        /** 根の道筋を全部決めてから太らせる(試作の tubeQueue)。 */
        final List<Object[]> tubes = new ArrayList<>();
        /** 管で置く根のブロック(巨大樹の抱える根は GIANT_ROOT)。 */
        byte rootMat = ROOT;
        /** ほかの根の道筋(根どうしを離す。11-19 段 2d「根の密度が高すぎて板が乗ってるみたい」)。 */
        final LongOpenHashSet rootPaths = new LongOpenHashSet();

        /** ほかの根の道筋の近く(横 ±2・縦 ±1)か。 */
        boolean nearOtherRoot(int x, int y, int z) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (rootPaths.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) return true;
                    }
                }
            }
            return false;
        }

        W(OrganFusion.Ctx c, int ground, Random rnd) {
            this.c = c;
            this.ground = ground;
            this.rnd = rnd;
        }

        byte at(int x, int y, int z) {
            return planned.get(BlockPos.asLong(x, y, z));
        }

        /** 世界が空いている(空気・置き換えられる草花・水なし)・予約の外・まだ計画していない。部屋の中も可。 */
        boolean open(int x, int y, int z) {
            if (!c.loaded(x, y, z)) return false;
            long k = BlockPos.asLong(x, y, z);
            if (c.reserved.contains(k) || planned.containsKey(k)) return false;
            BlockState s = c.state(x, y, z);
            return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
        }

        boolean place(int x, int y, int z, byte m) {
            if (!c.loaded(x, y, z)) return false;
            long k = BlockPos.asLong(x, y, z);
            if (c.reserved.contains(k)) return false;
            byte cur = planned.get(k);
            if (cur != 0) {
                if (cur == LEAF && m != LEAF) {
                    planned.put(k, m);
                    return true;
                }
                return false;
            }
            BlockState s = c.state(x, y, z);
            if (!(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty()) {
                if (c.struct(x, y, z)) cut++;
                return false;
            }
            planned.put(k, m);
            return true;
        }

        /** 壁・地面(建物のブロックか、動きを止める物)。試作の isStruct || GROUND。 */
        boolean solid(int x, int y, int z) {
            if (c.struct(x, y, z)) return true;
            return c.loaded(x, y, z) && c.state(x, y, z).blocksMotion();
        }
    }

    /** 這い方(試作の crawl の引数)。 */
    static final class Crawl {
        int steps;
        int sign = 1;
        double upW, wrapW, noise, towardW;
        @Nullable
        int[] toward;
        boolean free, ground;
        double[] center;
        @Nullable
        Random fixed; // 試作で () => 0.5 を渡していた所
        /** 根: ほかの根の道筋から離れて這う(始めの 3 歩は幹の根元なので見ない)。終わったら道筋を覚える。 */
        boolean spread;
    }

    /** 壁に沿って這い、道筋を積む(置くのは後で tube)。 */
    static void crawl(W w, int sx, int sy, int sz, Crawl o, List<int[]> path) {
        int x = sx, y = sy, z = sz;
        double px = 0, py = 0, pz = 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        int minY = w.ground - 2;
        for (int s = 0; s < o.steps; s++) {
            int[] best = null;
            double bs = -1.0E9D, bdx = 0, bdy = 0, bdz = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = x + dx, ny = y + dy, nz = z + dz;
                        if (ny < minY) continue;
                        long k = BlockPos.asLong(nx, ny, nz);
                        if (seen.contains(k) || !w.open(nx, ny, nz)) continue;
                        if (o.spread && s >= 3 && w.nearOtherRoot(nx, ny, nz)) continue;
                        boolean hug = w.c.structAdj(nx, ny, nz);
                        if (!hug && !o.free && !(o.ground && w.c.solidAdj(nx, ny, nz))) continue;
                        double rx = nx - o.center[0], rz = nz - o.center[1], rl = Math.max(1.0E-3D, Math.hypot(rx, rz));
                        double tx = -rz / rl * o.sign, tz = rx / rl * o.sign;
                        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double r = o.fixed != null ? 0.5D : w.rnd.nextDouble();
                        double sc = (dx * tx + dz * tz) / len * o.wrapW + dy / len * o.upW + (dx * px + dy * py + dz * pz) / len * 0.7D
                                + (r - 0.5D) * o.noise;
                        if (hug) sc += 0.9D;
                        if (o.toward != null) {
                            double qx = o.toward[0] - x, qy = o.toward[1] - y, qz = o.toward[2] - z;
                            double ql = Math.max(1.0E-3D, Math.sqrt(qx * qx + qy * qy + qz * qz));
                            sc += (qx * dx + qy * dy + qz * dz) / ql / len * o.towardW;
                        }
                        if (sc > bs) {
                            bs = sc;
                            best = new int[]{nx, ny, nz};
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
            path.add(best);
        }
        if (o.spread) for (int[] p : path) w.rootPaths.add(BlockPos.asLong(p[0], p[1], p[2]));
    }

    /** 試作の surfNormal: 壁・地面から離れる向き(まわり 26 マスの固い物の逆向きの和)。弱ければ null。 */
    @Nullable
    static double[] surfNormal(W w, int x, int y, int z) {
        double nx = 0, ny = 0, nz = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                for (int c = -1; c <= 1; c++) {
                    if (a == 0 && b == 0 && c == 0) continue;
                    if (!w.solid(x + a, y + b, z + c)) continue;
                    double l = Math.sqrt(a * a + b * b + c * c);
                    nx -= a / l;
                    ny -= b / l;
                    nz -= c / l;
                }
            }
        }
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return l < 0.3D ? null : new double[]{nx / l, ny / l, nz / l};
    }

    /** 試作の tube: 道筋をならし、各点で壁から半径ぶん浮かせた球を置く(太さは r0 → r1)。 */
    static void tube(W w, List<int[]> path, double r0, double r1) {
        int n = path.size();
        if (n == 0) return;
        double[][] sm = new double[n][3];
        for (int i = 0; i < n; i++) {
            double sx = 0, sy = 0, sz = 0;
            int k = 0;
            for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) {
                sx += path.get(j)[0];
                sy += path.get(j)[1];
                sz += path.get(j)[2];
                k++;
            }
            sm[i][0] = sx / k;
            sm[i][1] = sy / k;
            sm[i][2] = sz / k;
        }
        for (int i = 0; i < n; i++) {
            double r = r0 + (r1 - r0) * (n > 1 ? (double) i / (n - 1) : 0.0D);
            int big = (int) Math.ceil(r) + 1;
            int[] p = path.get(i);
            double[] nrm = surfNormal(w, p[0], p[1], p[2]);
            double off = Math.max(0.0D, r - 0.5D);
            double x = sm[i][0], y = sm[i][1], z = sm[i][2];
            if (nrm != null) {
                x += nrm[0] * off;
                y += nrm[1] * off;
                z += nrm[2] * off;
            }
            int bx = (int) Math.round(x), by = (int) Math.round(y), bz = (int) Math.round(z);
            for (int a = -big; a <= big; a++) {
                for (int b = -big; b <= big; b++) {
                    for (int c = -big; c <= big; c++) {
                        int px = bx + a, py = by + b, pz = bz + c;
                        if ((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) <= r * r + 0.15D) w.place(px, py, pz, w.rootMat);
                    }
                }
            }
        }
    }

    private static void queueTube(W w, List<int[]> path, double r0, double r1) {
        w.tubes.add(new Object[]{path, r0, r1});
    }

    @SuppressWarnings("unchecked")
    private static void flushTubes(W w) {
        for (Object[] a : w.tubes) tube(w, (List<int[]>) a[0], (Double) a[1], (Double) a[2]);
        w.tubes.clear();
    }

    /** 試作の runOut: 地面に着いた根は、建物から離れる向きにくねりながら少し這う。 */
    static void runOut(W w, List<int[]> path, double[] center, int n) {
        if (path.isEmpty()) return;
        int[] last = path.get(path.size() - 1);
        int surf = surfaceAt(w.c, last[0], last[2]);
        if (surf == Integer.MIN_VALUE || last[1] > surf + 1) return;
        double dx = last[0] - center[0], dz = last[2] - center[1], l = Math.max(1.0E-3D, Math.hypot(dx, dz));
        dx /= l;
        dz /= l;
        double x = last[0], z = last[2];
        int py = last[1];
        for (int s = 0; s < n; s++) {
            double wig = (w.rnd.nextDouble() - 0.5D) * 0.9D;
            double ndx = dx - dz * wig, ndz = dz + dx * wig, nl = Math.max(1.0E-3D, Math.hypot(ndx, ndz));
            dx = ndx / nl;
            dz = ndz / nl;
            x += dx;
            z += dz;
            int cx = (int) Math.round(x), cz = (int) Math.round(z);
            int y = surfaceAt(w.c, cx, cz);
            if (y == Integer.MIN_VALUE || Math.abs(y - py) > 1 || !w.open(cx, y, cz)) break;
            path.add(new int[]{cx, y, cz});
            py = y;
        }
    }

    /** 試作の rootlets: 細い根の枝分かれ。 */
    static void rootlets(W w, List<int[]> path, double[] center, int count, int sign, FusionTable t) {
        for (int k = 0; k < count && path.size() > 6; k++) {
            int[] q = path.get((int) Math.floor(path.size() * (0.25D + w.rnd.nextDouble() * 0.55D)));
            List<int[]> sp = new ArrayList<>();
            Crawl o = new Crawl();
            o.steps = 10 + w.rnd.nextInt(16);
            o.sign = -sign;
            o.upW = -0.5D;
            o.wrapW = 0.9D;
            o.noise = 1.0D;
            o.spread = true;
            o.ground = true;
            o.center = center;
            crawl(w, q[0], q[1], q[2], o, sp);
            runOut(w, sp, center, 2 + w.rnd.nextInt(2));
            queueTube(w, sp, t.treeRootletStart, t.treeRootletEnd);
        }
    }

    /** 試作の drapeRoot: まず一番近い壁の縁へ、そこから壁に沿って地面まで垂らす。 */
    static void drapeRoot(W w, int sx, int sy, int sz, BoundingBox p, double a, int steps, int sign, double r0, double r1, FusionTable t) {
        double[] center = {(p.minX() + p.maxX()) / 2.0D, (p.minZ() + p.maxZ()) / 2.0D};
        double half = Math.max(p.maxX() - p.minX(), p.maxZ() - p.minZ()) / 2.0D + 3.0D;
        int[] goal = {(int) Math.round(center[0] + Math.cos(a) * half), w.ground + 1, (int) Math.round(center[1] + Math.sin(a) * half)};
        List<int[]> path = new ArrayList<>();
        path.add(new int[]{sx, sy, sz});
        Crawl o = new Crawl();
        o.steps = steps;
        o.sign = sign;
        o.upW = -1.1D;
        o.wrapW = 0.35D;
        o.noise = 0.5D;
        o.toward = goal;
        o.towardW = 0.9D;
        o.ground = true;
        o.center = center;
        o.spread = true;
        crawl(w, sx, sy, sz, o, path);
        runOut(w, path, center, 3 + w.rnd.nextInt(4));
        queueTube(w, path, r0, r1);
        rootlets(w, path, center, w.rnd.nextDouble() < 0.5D ? 1 : 0, sign, t); // 段 2d で 1〜2 本から 0〜1 本へ(根が密すぎた)
    }

    static void leafBlob(W w, double cx, double cy, double cz, double rx, double ry) {
        for (int x = (int) Math.floor(cx - rx - 1); x <= cx + rx + 1; x++) {
            for (int y = (int) Math.floor(cy - ry - 1); y <= cy + ry + 1; y++) {
                for (int z = (int) Math.floor(cz - rx - 1); z <= cz + rx + 1; z++) {
                    double e = sq((x + 0.5D - cx) / rx) + sq((y + 0.5D - cy) / ry) + sq((z + 0.5D - cz) / rx);
                    if (e <= 1.0D - w.rnd.nextDouble() * 0.25D) w.place(x, y, z, LEAF);
                }
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double[] norm(double[] d) {
        double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return l < 1.0E-6D ? new double[]{0, 1, 0} : new double[]{d[0] / l, d[1] / l, d[2] / l};
    }

    // ---------------------------------------------------------------- 大きい構造物: 中くらいの木(段 2c。試作 第 9 版)

    private static double[] centerOf(BoundingBox b) {
        return new double[]{(b.minX() + b.maxX() + 1) / 2.0D, (b.minZ() + b.maxZ() + 1) / 2.0D};
    }

    @Nullable
    private static BoundingBox nearestPiece(OrganFusion.Ctx c, int x, int z) {
        BoundingBox best = null;
        double bd = Double.MAX_VALUE;
        for (StructureFusion.Hit h : c.hits) {
            double[] ctr = centerOf(h.piece());
            double d = sq(ctr[0] - x) + sq(ctr[1] - z);
            if (d < bd) {
                bd = d;
                best = h.piece();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 構造物の上・中に植わった苗の木(段 2b)

    /**
     * 構造物にかかる所で苗が育つときの形(11-19 ユーザー「構造物は普通に覆われて汚染される。そのうえで木が構造物に対応した生え方に」)。
     * 苗の場所 {@code origin}(空いている)から幹を伸ばす。幹は空気と草花のマスだけを通る(段 3 から入口・部屋の中も空けない)。
     * 苗が建物のブロックの上(屋根・床・浮いた構造物の床)なら、第 7 版の根(管状・壁を伝って垂れる)を小さく出す。
     * 幹が下のほうで通らなければ育てない(null。欠けて浮いた木を作らない)。
     */
    @Nullable
    public static Plan saplingTree(ServerLevel level, BlockPos origin, List<StructureFusion.Hit> hits, long seed, FusionTable t) {
        OrganFusion.Ctx c = new OrganFusion.Ctx(level, hits, t);
        c.allowInterior = true;
        // 段 3: 入口の通り道・部屋の中も空けない(11-19 夜 ユーザー「部屋の中まで入り込み、入口を塞いでもよい」)
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + 0x5A9L);
        int ground = ground(c, new BoundingBox(origin.getX() - 10, origin.getY() - 2, origin.getZ() - 10, origin.getX() + 10, origin.getY() + 2, origin.getZ() + 10));
        if (ground == Integer.MIN_VALUE) return null;
        W w = new W(c, ground, rnd);
        int ox0 = origin.getX(), oy = origin.getY(), oz0 = origin.getZ();
        int ht = t.saplingHeightMin + rnd.nextInt(t.saplingHeightMax - t.saplingHeightMin + 1);
        // 幹の芯の道筋(4 マスごとにうねる)。芯が通らなければ、下のほうなら育てない・上のほうならそこで止める
        double[][] off = new double[ht + 1][2];
        double ox = 0, oz = 0, vx = 0, vz = 0;
        int top = ht;
        for (int dy = 0; dy <= ht; dy++) {
            if (dy % 4 == 0) {
                double a = rnd.nextDouble() * Math.PI * 2.0D, k = (0.15D + rnd.nextDouble() * 0.2D) * t.treeBend;
                vx = vx * 0.45D + Math.cos(a) * k;
                vz = vz * 0.45D + Math.sin(a) * k;
            }
            if (dy >= 2) {
                ox += vx;
                oz += vz;
            }
            off[dy][0] = ox;
            off[dy][1] = oz;
            int cx = (int) Math.floor(ox0 + ox + 0.5D), cz = (int) Math.floor(oz0 + oz + 0.5D);
            if (!w.open(cx, oy + dy, cz)) {
                if (dy < Math.max(4, t.saplingHeightMin * 6 / 10)) return null;
                top = dy - 1;
                break;
            }
        }
        double r = t.saplingTrunkRadius;
        for (int dy = 0; dy <= top; dy++) {
            double cx = ox0 + 0.5D + off[dy][0], cz = oz0 + 0.5D + off[dy][1];
            for (int x = (int) Math.floor(cx - r - 1); x <= cx + r + 1; x++) {
                for (int z = (int) Math.floor(cz - r - 1); z <= cz + r + 1; z++) {
                    if (Math.hypot(x + 0.5D - cx, z + 0.5D - cz) <= r) w.place(x, oy + dy, z, TRUNK);
                }
            }
        }
        // 建物の上の苗: 根を屋根・床から壁を伝って垂らす(浮いた構造物では縁から垂れ下がる)
        if (c.struct(ox0, oy - 1, oz0)) {
            BoundingBox p = c.pieceAt(ox0, oy - 1, oz0);
            if (p == null) p = nearestPiece(c, ox0, oz0);
            if (p != null) {
                int rel = Math.max(1, oy - ground);
                for (int k = 0; k < t.saplingRoots; k++) {
                    double a = k * Math.PI * 2.0D / Math.max(1, t.saplingRoots) + (rnd.nextDouble() - 0.5D) * 0.5D;
                    int sx = (int) Math.round(ox0 + Math.cos(a) * (r + 0.6D)), sz = (int) Math.round(oz0 + Math.sin(a) * (r + 0.6D));
                    drapeRoot(w, sx, oy, sz, p, a, 24 + Math.min(rel, 40) * 3, k % 2 == 1 ? 1 : -1, t.saplingRootStart, t.saplingRootEnd, t);
                }
            }
        }
        flushTubes(w);
        // 樹冠: 先の大きな塊 + 短い枝の先の小さな塊
        double tx = ox0 + 0.5D + off[top][0], tz = oz0 + 0.5D + off[top][1];
        int crown = t.saplingCrown;
        for (int k = 0; k < 4; k++) {
            int y0 = oy + Math.max(2, (int) Math.round(top * (0.55D + rnd.nextDouble() * 0.35D)));
            double[] c0 = off[Math.max(0, Math.min(top, y0 - oy))];
            double[] pp = {ox0 + 0.5D + c0[0], y0 + 0.5D, oz0 + 0.5D + c0[1]};
            double a = k * Math.PI / 2.0D + rnd.nextDouble() * 0.8D;
            double[] d = norm(new double[]{Math.cos(a), 0.35D + rnd.nextDouble() * 0.3D, Math.sin(a)});
            int len = crown - 1 + rnd.nextInt(2);
            for (int s = 0; s < len; s++) {
                double[] np = {pp[0] + d[0], pp[1] + d[1], pp[2] + d[2]};
                int bx = (int) Math.floor(np[0]), by = (int) Math.floor(np[1]), bz = (int) Math.floor(np[2]);
                if (!w.open(bx, by, bz)) break;
                w.place(bx, by, bz, BRANCH);
                pp = np;
            }
            leafBlob(w, pp[0], pp[1] + 1, pp[2], crown * 0.6D, crown * 0.45D);
        }
        leafBlob(w, tx, oy + top + 1, tz, crown, crown * 0.6D);
        if (w.planned.isEmpty()) return null;
        return pack(w, "sapling", origin, top);
    }

    /** 計画を数える(報告・テスト用)。 */
    public static int count(Plan p, byte m) {
        int n = 0;
        for (byte b : p.mat) if (b == m) n++;
        return n;
    }

    static Long2ByteMap asMap(Plan p) {
        Long2ByteOpenHashMap m = new Long2ByteOpenHashMap(p.pos.length);
        for (int i = 0; i < p.pos.length; i++) m.put(p.pos[i], p.mat[i]);
        return m;
    }
}
