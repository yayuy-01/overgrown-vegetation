package com.yayuy.overgrownvegetation.entity.runner;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 「まっすぐ走ってよいか」の下見(2026-11-15、ヴェナムネスの段 A。種に依らない・状態を持たない)。
 *
 * <p>平地を長く追わせると、経路の点(ブロックの角)をまたぐたびに左右へ ±8 度振れて速さが 0.12〜0.39 で揺れ、走りが 2〜3 秒ごとに
 * 歩きへ落ちて見えた(段 1b の記録)。標的が見えていて、間に体を止めるものが無いときは、経路の点を追わずに標的へ真っすぐ舵を取る。
 * その「間に何も無い」を、体の幅の帯(真ん中と左右の端の 3 本の線)を {@link #STEP} ブロックおきに見て確かめる:
 * <ul>
 *   <li>足元から体の高さ {@link #BODY_ROWS} 段が空いていて、1 段下が足場(当たりがあり、水・溶岩でない)。</li>
 *   <li>足場の高さは 1 歩ごとに ±1 段まで(1 段の段差は越えられる。それより大きい段差・穴・崖は経路に任せる)。</li>
 * </ul>
 * 1 回の下見はブロックを (長さ / 0.8) × 3 列 × 4〜12 個読む(24 ブロックで約 400〜1100 個)。呼ぶ側は数 tick おきに。
 */
public final class ChaseSteer {

    /** 線の上を見る間隔(ブロック)。 */
    static final double STEP = 0.8D;
    /** 体の高さ(足元から空いている段の数)。 */
    static final int BODY_ROWS = 3;

    private ChaseSteer() {
    }

    /**
     * {@code from}(体の足元)から {@code to} へ、半幅 {@code halfWidth} の体がまっすぐ進めるか。見るのは {@code maxLen} ブロックまで
     * (それより先は、近づいたときにもう一度見る)。読み込まれていない所は「進めない」。
     */
    public static boolean clear(Level level, Vec3 from, Vec3 to, double halfWidth, double maxLen) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return true;
        double ux = dx / len, uz = dz / len;
        double px = -uz, pz = ux;
        double upto = Math.min(len, maxLen);
        int y = Mth.floor(from.y + 0.01D);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (double s = STEP; s <= upto + 1.0E-6D; s += STEP) {
            int centre = Integer.MIN_VALUE, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int o = -1; o <= 1; o++) {
                double x = from.x + ux * s + px * halfWidth * o, z = from.z + uz * s + pz * halfWidth * o;
                int h = walkLevel(level, m, Mth.floor(x), y, Mth.floor(z));
                if (h == Integer.MIN_VALUE) return false;
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (o == 0) centre = h;
            }
            if (hi - lo > 1) return false;   // 体の左右で 2 段以上違う(崖の縁・段の角)
            y = centre;
        }
        return true;
    }

    /** 列 (x, z) で、高さ {@code y} の ±1 段に立てる高さ(足元の段)。無ければ {@link Integer#MIN_VALUE}。今の高さを先に見る。 */
    static int walkLevel(Level level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        if (standable(level, m, x, y, z)) return y;
        if (standable(level, m, x, y + 1, z)) return y + 1;
        if (standable(level, m, x, y - 1, z)) return y - 1;
        return Integer.MIN_VALUE;
    }

    private static boolean standable(Level level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        for (int r = 0; r < BODY_ROWS; r++) {
            m.set(x, y + r, z);
            BlockState s = level.getBlockState(m);
            if (!s.getCollisionShape(level, m).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        m.set(x, y - 1, z);
        BlockState floor = level.getBlockState(m);
        return !floor.getCollisionShape(level, m).isEmpty() && floor.getFluidState().isEmpty();
    }
}
