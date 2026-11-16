package com.yayuy.overgrownvegetation.entity.flyer;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 飛ぶ型が空から地形を見る道具(2026-11-15)。どれも読み込まれていないチャンクは読まない(読めば読み込みが走る)。
 * <ul>
 *   <li>林冠の高さ: 葉を含む高さ地図({@link Heightmap.Types#MOTION_BLOCKING})。</li>
 *   <li>前の障害: 体の芯の高さで、固いブロックまでの距離を 1 ブロックずつ見る(翼は葉を抜けてよい。ぶつかるのは芯だけ)。</li>
 *   <li>止まり木: 林冠のてっぺん(上が空いている固いブロック。葉を好む)を、間をあけて選ぶ。</li>
 * </ul>
 */
public final class SkyProbe {

    private SkyProbe() {
    }

    /** (x, z) の林冠の上の空いた高さ(てっぺんのブロックの 1 つ上)。読めなければ NaN。 */
    public static double canopy(Level level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return Double.NaN;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** 向き heading の先 dists の点の林冠の一番高い所。どれも読めなければ NaN。 */
    public static double canopyAhead(Level level, double x, double z, double heading, double[] dists) {
        double h = Math.toRadians(heading);
        double fx = -Math.sin(h), fz = Math.cos(h);
        double best = Double.NaN;
        for (double d : dists) {
            double c = canopy(level, (int) Math.floor(x + fx * d), (int) Math.floor(z + fz * d));
            if (!Double.isNaN(c) && (Double.isNaN(best) || c > best)) best = c;
        }
        return best;
    }

    /** (x, y, z) から向き heading へ len ブロックの間に固いブロックがあれば、その距離。無ければ +∞。 */
    public static double blockedWithin(Level level, double x, double y, double z, double heading, double len) {
        double h = Math.toRadians(heading);
        double fx = -Math.sin(h), fz = Math.cos(h);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double d = 1.0D; d <= len; d += 1.0D) {
            int bx = (int) Math.floor(x + fx * d), by = (int) Math.floor(y), bz = (int) Math.floor(z + fz * d);
            if (!level.hasChunk(bx >> 4, bz >> 4)) return Double.POSITIVE_INFINITY;
            // 高さ地図(葉と固いもの)より上は何も無い: ブロックを読まない(空を飛ぶ群れのほとんどの点。11-16 見直し 第 3 便)
            if (by >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)) continue;
            p.set(bx, by, bz);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) return d;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** 止まり木の上に空けておく箱(半幅・高さ)【仮】。体の芯(1.2 × 0.8)が収まり、真上から降りられる。 */
    public static final double CLEAR_HALF = 0.65D, CLEAR_UP = 6.0D;

    /** 止まり木の上(体の芯の幅で真上 {@link #CLEAR_UP})に固いものが無いか。 */
    public static boolean clearAbove(Level level, BlockPos top) {
        return clearAbove(level, top, CLEAR_UP);
    }

    /** 真上 {@code up} ブロックまで空いているか(種ごとの空き {@link SkyFlyer#perchClearUp}。2026-11-15 アクムネスで足した)。 */
    public static boolean clearAbove(Level level, BlockPos top, double up) {
        double cx = top.getX() + 0.5D, cz = top.getZ() + 0.5D, y = top.getY() + 1.0D;
        return level.noCollision(new net.minecraft.world.phys.AABB(cx - CLEAR_HALF, y + 0.01D, cz - CLEAR_HALF, cx + CLEAR_HALF, y + up, cz + CLEAR_HALF));
    }

    /** 止まってよいブロックか(上が空いた固いブロック。液体は不可)。 */
    public static boolean perchable(Level level, BlockPos top) {
        if (!level.hasChunk(top.getX() >> 4, top.getZ() >> 4)) return false;
        BlockState s = level.getBlockState(top);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.getCollisionShape(level, top).isEmpty()) return false;
        BlockPos above = top.above();
        BlockState a = level.getBlockState(above);
        return a.getCollisionShape(level, above).isEmpty() && a.getFluidState().isEmpty();
    }

    static boolean isLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.getBlock() instanceof LeavesBlock;
    }

    /**
     * 止まり木を n か所選ぶ: center のまわり radius の中を 2 ブロックおきに見て、林冠のてっぺんのうち高い所(葉は +4 の得点)から、
     * 互いに minGap 以上あけて取る。一番高い所から 6 ブロックより低い所は取らない(群れで同じ林冠に止まる)。
     *
     * @return 止まるブロック(この上に立つ)。見つからなければ空
     */
    public static List<BlockPos> findPerches(Level level, BlockPos center, int radius, int n, double minGap) {
        return findPerches(level, center, radius, n, minGap, CLEAR_UP);
    }

    /** 真上の空きを {@code clearUp} にして探す(種ごと。{@link SkyFlyer#perchClearUp})。 */
    public static List<BlockPos> findPerches(Level level, BlockPos center, int radius, int n, double minGap, double clearUp) {
        List<long[]> cands = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                double c = canopy(level, x, z);
                if (Double.isNaN(c)) continue;
                int y = (int) c - 1;
                p.set(x, y, z);
                if (y <= level.getMinBuildHeight() || !perchable(level, p) || !clearAbove(level, p, clearUp)) continue;
                int score = y * 4 + (isLeaves(level.getBlockState(p)) ? 16 : 0);
                cands.add(new long[]{score, p.asLong()});
            }
        }
        cands.sort((a, b) -> Long.compare(b[0], a[0]));
        List<BlockPos> out = new ArrayList<>();
        int topY = Integer.MIN_VALUE;
        double gap2 = minGap * minGap;
        for (long[] c : cands) {
            BlockPos b = BlockPos.of(c[1]);
            if (topY == Integer.MIN_VALUE) topY = b.getY();
            if (b.getY() < topY - 6) continue;
            boolean ok = true;
            for (BlockPos o : out) {
                double ddx = o.getX() - b.getX(), ddz = o.getZ() - b.getZ();
                if (ddx * ddx + ddz * ddz < gap2) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            out.add(b);
            if (out.size() >= n) break;
        }
        return out;
    }
}
