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

    public static final byte TRUNK = 1, ROOT = 2, BRANCH = 3, LEAF = 4;

    /** 計画。{@code pos} は {@link BlockPos#asLong}、下から順。 */
    public record Plan(long[] pos, byte[] mat, String mode, BlockPos anchor, int height, int cut) {
    }

    /** 作った結果。{@code plan} が null なら {@code why} が理由。 */
    public record Result(@Nullable Plan plan, String why) {
        static Result fail(String why) {
            return new Result(null, why);
        }
    }

    /** 列ごとの屋根の高さと、空へ開いた中の床(試作の scanPiece)。 */
    record Col(int x, int z, int topS, int open) {
    }

    /** 根元(試作の chooseRuinAnchor)。 */
    record Anchor(boolean inside, int x, int z, int baseY, double openR) {
    }

    /** 計画を作る(読み込まれていない所があれば Result.fail("wait"))。 */
    public static Result plan(ServerLevel level, StructureFusion.Site site, long seed) {
        return plan(level, site, seed, StructureFusion.table(), null);
    }

    /** 生え方を決めて作る(テスト用。{@code force} = "top" / "inside" / null は自動)。 */
    public static Result plan(ServerLevel level, StructureFusion.Site site, long seed, FusionTable t, @Nullable String force) {
        List<StructureFusion.Hit> hits = StructureFusion.hits(site);
        if (hits.isEmpty()) return Result.fail("地表の部品が無い");
        OrganFusion.Ctx c = new OrganFusion.Ctx(level, hits, t);
        c.allowInterior = true;
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + 0x7EE5L);
        // 建物らしい部品のうち、建材のいちばん多い物に生やす
        BoundingBox piece = null;
        int bestN = 0;
        for (StructureFusion.Hit h : hits) {
            int n = countStruct(c, h.piece());
            if (n > bestN) {
                bestN = n;
                piece = h.piece();
            }
        }
        if (piece == null || bestN < OrganFusion.BUILDING_BLOCKS) return Result.fail("建物らしい部品が無い");
        int ground = ground(c, site.surfaceWhole());
        if (ground == Integer.MIN_VALUE) return Result.fail("wait");
        c.reserveEntrances();
        W w = new W(c, ground, rnd);
        // 寸法(試作の structTreeDims)。建物の高さは全部の部品の屋根の高さ(地面から)
        int top = ground;
        for (StructureFusion.Hit h : hits) top = Math.max(top, topStruct(c, h.piece()));
        int ht = (int) Math.round(Math.min(t.treeHeightMax, Math.max(t.treeHeightMin, (top - ground) * t.treeHeightMul + t.treeHeightAdd)));
        int size = StructureFusion.span(site.surfaceWhole());
        int r0 = t.treeTrunkRadius[t.treeTrunkRadius.length - 1];
        for (int i = 0; i < t.treeTrunkRadiusBelow.length && i < t.treeTrunkRadius.length; i++) {
            if (size < t.treeTrunkRadiusBelow[i]) {
                r0 = t.treeTrunkRadius[i];
                break;
            }
        }
        int crownR = (int) Math.round(Math.min(t.treeCrownMax, Math.max(t.treeCrownMin, t.treeCrownBase + ht * t.treeCrownPerHeight)));
        Anchor an = chooseAnchor(c, piece, rnd, t, force);
        if (an == null) return Result.fail(force != null && force.equals("inside") ? "中から生やせない(空へ開いた所が狭い)" : "根元が無い");
        grow(w, piece, an, ht, r0, r0 * t.treeFlare, crownR, t);
        if (w.planned.isEmpty()) return Result.fail("置ける所が無い");
        // 下から順に(幹が根元から伸びて見えるように)
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
        String mode = an.inside ? "inside" : "top";
        return new Result(new Plan(pos, mat, mode, new BlockPos(an.x, an.baseY, an.z), ht, w.cut), mode);
    }

    // ---------------------------------------------------------------- 建物を見る

    private static int countStruct(OrganFusion.Ctx c, BoundingBox b) {
        if ((long) b.getXSpan() * b.getYSpan() * b.getZSpan() > 400_000L) return 0;
        int n = 0;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    if (c.struct(x, y, z)) n++;
                }
            }
        }
        return n;
    }

    /** 部品の中の建物のブロックのいちばん高い所(無ければ Integer.MIN_VALUE)。 */
    private static int topStruct(OrganFusion.Ctx c, BoundingBox b) {
        for (int y = b.maxY(); y >= b.minY(); y--) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    if (c.struct(x, y, z)) return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

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

    /** 列のマスが四方を壁に囲まれているか(部品の箱の中で、その高さから 3 マス上までに建物のブロックがある)。 */
    private static boolean enclosed(OrganFusion.Ctx c, BoundingBox b, int x, int y, int z) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            boolean hit = false;
            for (int k = 1; !hit; k++) {
                int nx = x + d[0] * k, nz = z + d[1] * k;
                if (nx < b.minX() - 1 || nx > b.maxX() + 1 || nz < b.minZ() - 1 || nz > b.maxZ() + 1) break;
                for (int yy = y; yy <= y + 3 && !hit; yy++) hit = c.struct(nx, yy, nz);
            }
            if (!hit) return false;
        }
        return true;
    }

    /** 試作の scanPiece。open = 空へ開いた中の床の上のマス(上から見て最初に当たる物の上が、四方を壁に囲まれた空気)。 */
    static List<Col> scanPiece(OrganFusion.Ctx c, BoundingBox b) {
        List<Col> cols = new ArrayList<>();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                int topS = Integer.MIN_VALUE, open = Integer.MIN_VALUE;
                for (int y = b.maxY(); y >= b.minY(); y--) {
                    if (c.struct(x, y, z)) {
                        topS = y;
                        break;
                    }
                }
                for (int y = b.maxY() + 1; y >= b.minY() - 2; y--) { // 床は部品の箱の下にあることが多い
                    if (!c.loaded(x, y, z)) break;
                    BlockState s = c.state(x, y, z);
                    if ((s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty()) continue;
                    // 屋根の上は四方に壁が無いので外れる。水の床(井戸など)は外す
                    if (s.getFluidState().isEmpty() && y + 1 <= b.maxY() && y + 1 >= b.minY()
                            && !c.reserved.contains(BlockPos.asLong(x, y + 1, z)) && enclosed(c, b, x, y + 1, z)) {
                        open = y + 1;
                    }
                    break;
                }
                cols.add(new Col(x, z, topS, open));
            }
        }
        return cols;
    }

    /** 試作の chooseRuinAnchor。中から(開いた列が覆われた列から openMin 以上)→ だめならてっぺんから。 */
    @Nullable
    static Anchor chooseAnchor(OrganFusion.Ctx c, BoundingBox b, Random rnd, FusionTable t, @Nullable String force) {
        List<Col> cols = scanPiece(c, b);
        int w = b.getXSpan(), d = b.getZSpan();
        int[] dist = new int[w * d];
        Arrays.fill(dist, 999);
        int[] q = new int[w * d];
        int qn = 0;
        for (Col col : cols) {
            if (col.open == Integer.MIN_VALUE) {
                int i = (col.x - b.minX()) + w * (col.z - b.minZ());
                dist[i] = 0;
                q[qn++] = i;
            }
        }
        for (int h = 0; h < qn; h++) {
            int cur = q[h], lx = cur % w, lz = cur / w;
            int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] a : n4) {
                int nx = lx + a[0], nz = lz + a[1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                int j = nx + w * nz;
                if (dist[j] > dist[cur] + 1) {
                    dist[j] = dist[cur] + 1;
                    q[qn++] = j;
                }
            }
        }
        Col best = null;
        double bd = 0.0D;
        for (Col col : cols) {
            if (col.open == Integer.MIN_VALUE) continue;
            double v = dist[(col.x - b.minX()) + w * (col.z - b.minZ())] + rnd.nextDouble() * 0.3D;
            if (v > bd) {
                bd = v;
                best = col;
            }
        }
        boolean wantTop = "top".equals(force), wantInside = "inside".equals(force);
        if (best != null && bd >= t.treeOpenMin && !wantTop) {
            return new Anchor(true, best.x, best.z, best.open, Math.floor(bd) - 0.5D);
        }
        if (wantInside) return null;
        // てっぺんから: 屋根の高い所(煙突のような 1 本だけ高い所は分位で外す)の、真ん中に近い列
        int[] tops = cols.stream().mapToInt(Col::topS).filter(v -> v != Integer.MIN_VALUE).sorted().toArray();
        if (tops.length == 0) return null;
        int lvl = tops[Math.min(tops.length - 1, (int) Math.floor(tops.length * t.treeTopPercentile))];
        double cx = (b.minX() + b.maxX()) / 2.0D, cz = (b.minZ() + b.maxZ()) / 2.0D;
        Col pick = null;
        double pd = 1.0E9D;
        for (Col col : cols) {
            if (col.topS < lvl - 1 || col.topS > lvl) continue;
            if (!c.free(col.x, col.topS + 1, col.z)) continue;
            double dd = Math.hypot(col.x - cx, col.z - cz) + rnd.nextDouble() * 0.8D;
            if (dd < pd) {
                pd = dd;
                pick = col;
            }
        }
        if (pick == null) return null;
        return new Anchor(false, pick.x, pick.z, pick.topS + 1, 0.0D);
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
            if (m == LEAF && c.interior(x, y, z)) return false; // 部屋の中には葉を茂らせない
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
                        if ((px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z) <= r * r + 0.15D) w.place(px, py, pz, ROOT);
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
            o.ground = true;
            o.center = center;
            crawl(w, q[0], q[1], q[2], o, sp);
            runOut(w, sp, center, 2 + w.rnd.nextInt(2));
            queueTube(w, sp, t.treeRootletStart, t.treeRootletEnd);
        }
    }

    /** 試作の drapeRoot: まず一番近い壁の縁へ、そこから壁に沿って地面まで垂らす。 */
    static void drapeRoot(W w, int sx, int sy, int sz, BoundingBox p, double a, int steps, int sign, double r0, FusionTable t) {
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
        crawl(w, sx, sy, sz, o, path);
        runOut(w, path, center, 3 + w.rnd.nextInt(4));
        queueTube(w, path, r0, t.treeRootEnd);
        rootlets(w, path, center, 1 + (w.rnd.nextDouble() < 0.6D ? 1 : 0), sign, t);
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

    /** 試作の growRuinTree。 */
    static void grow(W w, BoundingBox p, Anchor an, int ht, int r0, double flare, int crownR, FusionTable t) {
        Random rnd = w.rnd;
        int bx = an.x, bz = an.z, by = an.baseY;
        int byRel = by - w.ground;
        int lt = Math.max(t.treeTrunkMin, ht - byRel);
        int pieceTop = topStruct(w.c, p);
        int outY = pieceTop + 1; // ここより上は建物の外
        int outRel = outY - w.ground;
        double[] center = {(p.minX() + p.maxX()) / 2.0D, (p.minZ() + p.maxZ()) / 2.0D};
        java.util.function.IntToDoubleFunction rAt = dy -> Math.max(1.0D, r0 * (1.0D - 0.5D * dy / lt)) + (dy < 3 ? flare * sq(1.0D - dy / 3.0D) : 0.0D);
        java.util.function.IntToDoubleFunction rFit = dy -> an.inside && by + dy <= outY ? Math.min(rAt.applyAsDouble(dy), an.openR + 0.3D) : rAt.applyAsDouble(dy);
        // 幹の道筋: bendEvery マスごとに向きを変えながらうねる。穴を抜けるまでは穴の中に収める
        double[][] raw = new double[lt + 1][2];
        double ox = 0, oz = 0, vx = 0, vz = 0;
        for (int dy = 0; dy <= lt; dy++) {
            if (dy % t.treeBendEvery == 0) {
                double a = rnd.nextDouble() * Math.PI * 2.0D, k = (0.25D + rnd.nextDouble() * 0.35D) * t.treeBend;
                vx = vx * 0.45D + Math.cos(a) * k;
                vz = vz * 0.45D + Math.sin(a) * k;
            }
            if (dy < 2) continue;
            ox += vx;
            oz += vz;
            if (an.inside && by + dy <= outY) {
                double lim = Math.max(0.0D, an.openR - rFit.applyAsDouble(dy)), l = Math.hypot(ox, oz);
                if (l > lim) {
                    double f = l > 0 ? lim / l : 0.0D;
                    ox *= f;
                    oz *= f;
                    vx *= 0.3D;
                    vz *= 0.3D;
                }
            }
            raw[dy][0] = ox;
            raw[dy][1] = oz;
        }
        double[][] path = new double[lt + 1][2];
        for (int i = 0; i <= lt; i++) {
            double sx = 0, sz = 0;
            int n = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(lt, i + 2); j++) {
                sx += raw[j][0];
                sz += raw[j][1];
                n++;
            }
            path[i][0] = sx / n;
            path[i][1] = sz / n;
        }
        if (an.inside) {
            for (int dy = 0; dy <= lt && by + dy <= outY; dy++) path[dy] = raw[dy]; // 穴の中はならさない
        }
        for (int dy = 0; dy <= lt; dy++) {
            int y = by + dy;
            double r = rFit.applyAsDouble(dy);
            double cx = bx + path[dy][0], cz = bz + path[dy][1];
            for (int x = (int) Math.floor(cx - r - 1); x <= cx + r + 1; x++) {
                for (int z = (int) Math.floor(cz - r - 1); z <= cz + r + 1; z++) {
                    if (Math.hypot(x - cx, z - cz) <= r) w.place(x, y, z, TRUNK);
                }
            }
        }
        double rg = rAt.applyAsDouble(0);
        int roots = t.treeRoots;
        if (!an.inside) {
            // 垂れる根: 根元から太く出て屋根を這い、縁を越えて壁を伝って地面まで(枝分かれしながら細る)
            for (int k = 0; k < roots; k++) {
                double a = k * Math.PI * 2.0D / Math.max(1, roots) + (rnd.nextDouble() - 0.5D) * 0.5D;
                int sx = (int) Math.round(bx + Math.cos(a) * (rg + 0.6D)), sz = (int) Math.round(bz + Math.sin(a) * (rg + 0.6D));
                drapeRoot(w, sx, by, sz, p, a, 40 + byRel * 4, k % 2 == 1 ? 1 : -1, t.treeRootStart, t);
            }
        } else {
            // 中から: 半分は床を這って内壁を登る。残りは壁の上を越えて外へ垂れる
            for (int k = 0; k < roots; k++) {
                double a = k * Math.PI * 2.0D / Math.max(1, roots) + (rnd.nextDouble() - 0.5D) * 0.5D;
                int sx = (int) Math.round(bx + Math.cos(a) * (rg + 0.6D)), sz = (int) Math.round(bz + Math.sin(a) * (rg + 0.6D));
                if (k % 2 == 0) {
                    List<int[]> rp = new ArrayList<>();
                    rp.add(new int[]{sx, by, sz});
                    Crawl o = new Crawl();
                    o.steps = 50 + rnd.nextInt(40);
                    o.sign = k % 4 != 0 ? 1 : -1;
                    o.upW = 0.7D;
                    o.wrapW = 0.9D;
                    o.noise = 0.6D;
                    o.ground = true;
                    o.center = center;
                    crawl(w, sx, by, sz, o, rp);
                    queueTube(w, rp, t.treeRootStart, t.treeRootEnd);
                    rootlets(w, rp, center, 1, o.sign, t);
                } else {
                    double ex = center[0] + Math.cos(a) * 30.0D, ez = center[1] + Math.sin(a) * 30.0D;
                    int[] best = null;
                    double bdd = 1.0E9D;
                    for (int x = p.minX() - 1; x <= p.maxX() + 1; x++) {
                        for (int z = p.minZ() - 1; z <= p.maxZ() + 1; z++) {
                            if (!w.open(x, outY, z) || !w.c.structAdj(x, outY, z)) continue;
                            double dd = Math.hypot(x - ex, z - ez);
                            if (dd < bdd) {
                                bdd = dd;
                                best = new int[]{x, outY, z};
                            }
                        }
                    }
                    if (best != null) {
                        List<int[]> arc = new ArrayList<>();
                        Crawl o = new Crawl();
                        o.steps = 30;
                        o.toward = best;
                        o.towardW = 3.0D;
                        o.noise = 0.3D;
                        o.free = true;
                        o.center = center;
                        o.fixed = rnd;
                        crawl(w, bx, outY, bz, o, arc);
                        queueTube(w, arc, 1.1D, 1.0D);
                        drapeRoot(w, best[0], best[1], best[2], p, a, 40 + outRel * 3, k % 4 == 1 ? 1 : -1, 1.35D, t);
                    }
                }
            }
        }
        flushTubes(w);
        // 枝と樹冠(建物の外に出てから)
        int topY = by + lt;
        double[] topC = {bx + path[lt][0] + 0.5D, topY, bz + path[lt][1] + 0.5D};
        int nb = 5 + Math.round(lt / 8.0F);
        for (int k = 0; k < nb; k++) {
            int y0 = (int) Math.round(Math.max(outY + 2, by + lt * (0.45D + rnd.nextDouble() * 0.45D)));
            double[] c0 = path[Math.max(0, Math.min(lt, y0 - by))];
            double[] pp = {bx + c0[0] + 0.5D, y0 + 0.5D, bz + c0[1] + 0.5D};
            double a = k * Math.PI * 2.0D / nb + rnd.nextDouble() * 0.8D;
            double[] d = norm(new double[]{Math.cos(a), 0.25D + rnd.nextDouble() * 0.35D, Math.sin(a)});
            int len = (int) Math.round(crownR * (0.6D + rnd.nextDouble() * 0.5D));
            for (int s = 0; s < len; s++) {
                double[] np = {pp[0] + d[0], pp[1] + d[1], pp[2] + d[2]};
                int cx = (int) Math.floor(np[0]), cy = (int) Math.floor(np[1]), cz = (int) Math.floor(np[2]);
                if (!w.c.loaded(cx, cy, cz) || w.c.struct(cx, cy, cz)) break;
                w.place(cx, cy, cz, BRANCH);
                if (s < len * 0.4D) w.place(cx, cy - 1, cz, BRANCH);
                pp = np;
                d = norm(new double[]{d[0] + (rnd.nextDouble() - 0.5D) * 0.25D, d[1] + (rnd.nextDouble() - 0.6D) * 0.12D, d[2] + (rnd.nextDouble() - 0.5D) * 0.25D});
            }
            leafBlob(w, pp[0], pp[1] + 1, pp[2], 3.5D + rnd.nextDouble() * 1.8D, 2.2D + rnd.nextDouble() * 0.8D);
        }
        leafBlob(w, topC[0], topC[1] + 1, topC[2], crownR * 0.55D, 3.4D);
        for (int k = 0; k < 6; k++) {
            double a = k * Math.PI / 3.0D + rnd.nextDouble() * 0.5D, rr = crownR * (0.55D + rnd.nextDouble() * 0.3D);
            leafBlob(w, topC[0] + Math.cos(a) * rr, topC[1] - 2 - rnd.nextDouble() * 3.0D, topC[2] + Math.sin(a) * rr, crownR * 0.35D, 2.4D);
        }
    }

    private static double[] norm(double[] d) {
        double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return l < 1.0E-6D ? new double[]{0, 1, 0} : new double[]{d[0] / l, d[1] / l, d[2] / l};
    }

    // ---------------------------------------------------------------- 大きい構造物: 中庭・広場

    /**
     * 試作の chooseTreeSite(大)。全体の箱の中で、建物のブロックのある列から一番離れた列(courtyardMinClear 以上・入口から幹の裾 + 4 離す)。
     * 道の部品などは地面ごと箱に入るので、離れは建物のブロック(地形でない物)から測る。見つからなければ null。
     */
    @Nullable
    public static BlockPos courtyard(ServerLevel level, StructureFusion.Site site, long seed) {
        return courtyard(level, site, seed, StructureFusion.table());
    }

    @Nullable
    static BlockPos courtyard(ServerLevel level, StructureFusion.Site site, long seed, FusionTable t) {
        List<StructureFusion.Hit> hits = StructureFusion.hits(site);
        OrganFusion.Ctx c = new OrganFusion.Ctx(level, hits, t);
        BoundingBox box = site.surfaceWhole();
        int w = box.getXSpan(), d = box.getZSpan();
        if ((long) w * d > 250_000L) return null;
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + 0xC0E7L);
        int[] dist = new int[w * d];
        Arrays.fill(dist, 9999);
        int[] q = new int[w * d];
        int qn = 0;
        for (StructureFusion.Hit h : hits) {
            BoundingBox b = h.piece();
            for (int x = Math.max(b.minX(), box.minX()); x <= Math.min(b.maxX(), box.maxX()); x++) {
                for (int z = Math.max(b.minZ(), box.minZ()); z <= Math.min(b.maxZ(), box.maxZ()); z++) {
                    int i = (x - box.minX()) + w * (z - box.minZ());
                    if (dist[i] == 0) continue;
                    for (int y = b.minY(); y <= b.maxY(); y++) {
                        if (c.struct(x, y, z)) {
                            dist[i] = 0;
                            q[qn++] = i;
                            break;
                        }
                    }
                }
            }
        }
        if (qn == 0) return null;
        for (int h = 0; h < qn; h++) {
            int cur = q[h], lx = cur % w, lz = cur / w;
            // 8 方向(離れは横・縦の大きい方。箱の縁までの距離と比べるため)
            for (int ax = -1; ax <= 1; ax++) {
                for (int az = -1; az <= 1; az++) {
                    int nx = lx + ax, nz = lz + az;
                    if ((ax == 0 && az == 0) || nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                    int j = nx + w * nz;
                    if (dist[j] > dist[cur] + 1) {
                        dist[j] = dist[cur] + 1;
                        q[qn++] = j;
                    }
                }
            }
        }
        c.reserveEntrances();
        int rg = t.courtyardTrunkRadius;
        int[] best = null;
        double bs = -1.0E9D;
        for (int x = box.minX() + 2; x <= box.maxX() - 2; x++) {
            for (int z = box.minZ() + 2; z <= box.maxZ() - 2; z++) {
                int dd = dist[(x - box.minX()) + w * (z - box.minZ())];
                if (dd < t.courtyardMinClear) continue;
                if (c.nearDoor(x, z, rg + 4)) continue;
                // 中庭・広場 = 建物に囲まれた所。まわりの 4 つの象限のうち 3 つ以上に建物の列があること(箱の隅 = 建物の外れを選ばない。11-19 クライアントで前哨基地・村の隅の谷を選んだ。真横・真縦 3 列の帯の建物は数えない)
                // 箱の縁までが建物までより近い所(= 建物の並びの外側)も外す
                int edge = Math.min(Math.min(x - box.minX(), box.maxX() - x), Math.min(z - box.minZ(), box.maxZ() - z));
                if (edge < dd) continue;
                if (enclosedBy(dist, w, d, x - box.minX(), z - box.minZ()) < 3) continue;
                double sc = dd + rnd.nextDouble() * 2.0D;
                if (sc > bs) {
                    bs = sc;
                    best = new int[]{x, z};
                }
            }
        }
        if (best == null) return null;
        int y = surfaceAt(c, best[0], best[1]);
        if (y == Integer.MIN_VALUE) return null;
        if (y < box.minY() - 4) return null; // 構造物より低い谷・水の底には植えない
        return new BlockPos(best[0], y, best[1]);
    }

    /** 列のまわりの 4 つの象限(北東・北西・南東・南西)のうち、建物の列(離れ 0)を含む象限の数。 */
    private static int enclosedBy(int[] dist, int w, int d, int lx, int lz) {
        int mask = 0;
        for (int z = 0; z < d && mask != 15; z++) {
            for (int x = 0; x < w; x++) {
                if (dist[x + w * z] != 0 || Math.abs(x - lx) < 3 || Math.abs(z - lz) < 3) continue; // 真横・真縦の細い帯は数えない(箱の隅で 1 列の家を拾わない)
                mask |= 1 << ((x >= lx ? 1 : 0) | (z >= lz ? 2 : 0));
            }
        }
        return Integer.bitCount(mask);
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
