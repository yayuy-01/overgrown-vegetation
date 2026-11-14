package com.yayuy.overgrownvegetation.entity.leap;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「跳ぶ型」の着地点と弧の選び方(2026-11-14、サルティムネスの段 1。種に依らない。値は {@link LeapSpec})。
 *
 * <h2>着地の場所選び</h2>
 * 目当ての点のまわり(半径 2 ブロックまで、近い順)を探り、次を全部満たす最初の所に下りる。無ければ跳ばない({@code null})。
 * <ol>
 *   <li>上に体の芯が入る固い上面がある(柵・塀の上 = 高さ 1 を超える当たりの上、葉の上には下りない)。高さは上へ {@link LeapSpec#maxUp}・下へ {@link LeapSpec#maxDown} まで。</li>
 *   <li>そこが歩ける地面(水・溶岩・火・サボテンなどでない。経路の種類で見る)。</li>
 *   <li>体の長さぶん(鼻先 〜 腹の先)が入り、両端の下に地面がある(崖へ体を突き出して着かない)。</li>
 *   <li>弧の通り道が空いている({@link #sweepClear})。</li>
 * </ol>
 *
 * <h2>弧の決め方</h2>
 * 頂点の上げを {@link LeapSpec#minLift} から 0.5 ずつ上げて試し、通り道が空いた最初の(一番低い)弧を使う。柵・塀は、低い弧が
 * 当たるので自然に高い弧になる。体の長さぶんは、鼻先・腹の先が芯の弧を少し先・少し後でなぞる(列車のように)として点検し、
 * 着いた後は真っすぐな体が入るかを見る(だから柵の手前から体長より短く跳ぶと、腹が柵に掛かって選ばれない)。
 */
public final class LeapPlanner {

    private LeapPlanner() {
    }

    /** 着地点を探る範囲(目当てからの横のずれ、近い順)。 */
    private static final int[][] OFFSETS;
    /** 弧の点検まで進める着地点の数の上限(重さ)。 */
    static final int MAX_SWEEPS = 6;
    /** 体の長さの点検の箱の高さ(ブロック、体格 1)と、地面から浮かせる量。 */
    static final double BODY_BOX_H = 0.7D, BOX_LIFT = 0.15D;
    /** 体の点検の点の間隔の上限(ブロック)。箱の幅 1.2 より狭く。 */
    static final double BODY_STEP = 0.9D;

    static {
        java.util.List<int[]> l = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 5) l.add(new int[]{dx, dz});
            }
        }
        l.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        OFFSETS = l.toArray(new int[0][]);
    }

    /**
     * {@code aim} の近くへ跳ぶ弧を作る(今いる所から)。跳べなければ {@code null}。
     */
    @Nullable
    public static LeapPlan plan(Mob mob, Vec3 aim, LeapSpec spec) {
        Level level = mob.level();
        Vec3 from = mob.position();
        int sweeps = 0;
        for (int[] o : OFFSETS) {
            double x = Mth.floor(aim.x) + o[0] + 0.5D;
            double z = Mth.floor(aim.z) + o[1] + 0.5D;
            double dist = Math.sqrt((x - from.x) * (x - from.x) + (z - from.z) * (z - from.z));
            if (dist < spec.minDist() || dist > spec.maxDist()) continue;
            double y = standY(mob, x, z, from.y + spec.maxUp(), from.y - spec.maxDown(), aim.y);
            if (Double.isNaN(y)) continue;
            Vec3 to = new Vec3(x, y, z);
            float yaw = (float) (Mth.atan2(z - from.z, x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
            if (!walkable(mob, to) || !bodyFits(mob, spec, to, yaw)) continue;
            if (++sweeps > MAX_SWEEPS) return null;
            for (double lift = spec.minLift(); lift <= spec.maxLift() + 1e-6; lift += 0.5D) {
                LeapPlan p = LeapPlan.of(from, to, lift, spec.gravity());
                if (sweepClear(mob, spec, p)) return p;
            }
        }
        return null;
    }

    /**
     * 列 (x, z) で、体の芯が立てる高さのうち {@code prefer} に一番近いもの({@code top}〜{@code bottom} の間)。無ければ NaN。
     * 柵・塀(当たりの高さが 1 を超える)と葉の上には立たない。
     */
    public static double standY(Mob mob, double x, double z, double top, double bottom, double prefer) {
        Level level = mob.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double best = Double.NaN;
        int bx = Mth.floor(x), bz = Mth.floor(z);
        for (int by = Mth.floor(top); by >= Mth.floor(bottom) - 1; by--) {
            p.set(bx, by, bz);
            BlockState st = level.getBlockState(p);
            VoxelShape shape = st.getCollisionShape(level, p, CollisionContext.of(mob));
            if (shape.isEmpty()) continue;
            double maxY = shape.max(net.minecraft.core.Direction.Axis.Y);
            if (maxY > 1.0D || st.is(BlockTags.LEAVES)) continue;
            double y = by + maxY;
            if (y > top + 1e-6 || y < bottom - 1e-6) continue;
            if (!level.noCollision(mob, coreBox(mob, x, y, z))) continue;
            if (Double.isNaN(best) || Math.abs(y - prefer) < Math.abs(best - prefer)) best = y;
        }
        return best;
    }

    /** 足元が歩ける地面か(経路の種類で見る。罰が負 = 通れない、火・溶岩・水・サボテンなど)。 */
    public static boolean walkable(Mob mob, Vec3 feet) {
        BlockPos pos = BlockPos.containing(feet.x, feet.y + 0.5D, feet.z);   // 半ブロックの上に立つときも、立つ空きマスを指す
        if (!mob.level().getFluidState(pos).isEmpty() || !mob.level().getFluidState(pos.below()).isEmpty()) return false;
        PathType t = WalkNodeEvaluator.getPathTypeStatic(mob, pos);
        // 水辺(WATER_BORDER)などの罰が正のものは下りてよい。火・溶岩・サボテン・粉雪・水そのもの・足場の無い所は下りない
        return switch (t) {
            case WALKABLE, WALKABLE_DOOR, WATER_BORDER -> true;
            default -> false;
        };
    }

    /**
     * 真っすぐな体(向き {@code yaw})が足元 {@code feet} に入り({@link #bodyPoints} の点ごとの細い箱)、鼻先・腹の先の下に地面がある(2.5 ブロック以内)か。
     */
    public static boolean bodyFits(Mob mob, LeapSpec spec, Vec3 feet, float yaw) {
        Level level = mob.level();
        double sc = mob.getScale();
        float r = yaw * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        double[] along = bodyPoints(spec, sc);
        for (int i = 0; i < along.length; i++) {
            double x = feet.x + fx * along[i], z = feet.z + fz * along[i];
            if (!level.noCollision(mob, bodyBox(mob, spec, x, feet.y, z))) return false;
            if (i == 0 || i == along.length - 1) {
                if (!groundBelow(mob, x, feet.y, z, 2.5D)) return false;
            }
        }
        return true;
    }

    /**
     * 弧の通り道が空いているか。芯は芯の箱で、体の前後(鼻先から腹の先まで {@link #bodyPoints})は細い箱で、
     * それぞれ弧を少し先・少し後でなぞる(弧の外 = まだ出ていない・もう着いた所は見ない)。
     */
    public static boolean sweepClear(Mob mob, LeapSpec spec, LeapPlan plan) {
        Level level = mob.level();
        double sc = mob.getScale();
        double dist = Math.max(plan.distance(), 1.0E-3D);
        float r = plan.yaw() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        double[] along = bodyPoints(spec, sc);
        for (int i = 1; i < plan.ticks(); i++) {
            double s = i / (double) plan.ticks();
            Vec3 c = plan.pos(s);
            for (int j = 0; j < along.length; j++) {
                double sj = s + along[j] / dist;
                if (sj <= 0.0D || sj >= 1.0D) continue;
                double x = c.x + fx * along[j], z = c.z + fz * along[j];
                double y = plan.heightAt(sj);
                AABB box = Math.abs(along[j]) < 1.0E-6D ? coreBox(mob, x, y, z) : bodyBox(mob, spec, x, y, z);
                if (!level.noCollision(mob, box)) return false;
            }
        }
        return true;
    }

    /**
     * 体の点検の点(芯の中心から前へ +、後ろへ −。ブロック)。鼻先から腹の先まで {@link #BODY_STEP} 以下の間隔で、0(芯)も含む。
     * 箱の幅(半幅 × 2)より間隔が狭いので、柵のような薄い物も間をすり抜けない(11-15: 4 点だと柵が腹の下に残った)。
     */
    static double[] bodyPoints(LeapSpec spec, double sc) {
        double front = spec.bodyFront() * sc, back = spec.bodyBack() * sc;
        int nf = (int) Math.ceil(front / BODY_STEP), nb = (int) Math.ceil(back / BODY_STEP);
        double[] p = new double[nf + nb + 1];
        int k = 0;
        for (int i = nb; i >= 1; i--) p[k++] = -back * i / nb;
        p[k++] = 0.0D;
        for (int i = 1; i <= nf; i++) p[k++] = front * i / nf;
        return p;
    }

    /** 下 {@code depth} ブロック以内に立てる上面があるか。 */
    static boolean groundBelow(Mob mob, double x, double y, double z, double depth) {
        Level level = mob.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        for (int by = Mth.floor(y + 0.5D); by >= Mth.floor(y - depth); by--) {
            p.set(bx, by, bz);
            if (!level.getBlockState(p).getCollisionShape(level, p, CollisionContext.of(mob)).isEmpty()) return true;
        }
        return false;
    }

    static AABB coreBox(Mob mob, double x, double y, double z) {
        double hw = mob.getBbWidth() / 2.0D;
        return new AABB(x - hw, y + BOX_LIFT, z - hw, x + hw, y + mob.getBbHeight(), z + hw);
    }

    static AABB bodyBox(Mob mob, LeapSpec spec, double x, double y, double z) {
        double hw = spec.bodyHalfWidth() * mob.getScale();
        return new AABB(x - hw, y + BOX_LIFT + 0.2D, z - hw, x + hw, y + BOX_LIFT + 0.2D + BODY_BOX_H * mob.getScale(), z + hw);
    }
}
