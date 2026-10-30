package com.yayuy.overgrownvegetation.client.critter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 体の各所の真下の地面の高さを直に測る(2026-10-22、段差の直し)。
 *
 * <h2>なぜ通ってきた道の高さではいけないのか</h2>
 * <p>はじめは体節の上下の傾きを「通ってきた道の高さ」から出していた。ところがマイクラの段差はほぼ一瞬で 1 ブロック上がるので、
 * 道の坂が数 tick だけ 70 度近くになり、体節が折れ曲がってばたついた(ユーザーが runClient で見つけた)。
 * しかも長い体は当たり判定(1.1 角)の前後へはみ出していて、頭は段の上・尾は段の下にいるのに、道には中心の高さしか残っていない。
 * そこで<b>骨ごとに、今その真下にある地面の高さ</b>を tick に 1 回測り、なめらかに追わせる。
 *
 * <p>測るのは「エンティティの足元の高さから上 1.25・下 2.5 ブロック」の範囲で、上から見て最初に当たる固い面
 * (当たり判定のある面の上の端)。見つからなければ下限(崖の上へ体が張り出す)。
 */
final class TerrainProbe {

    static final float UP = 1.25F;
    static final float DOWN = 2.5F;

    private TerrainProbe() {
    }

    /** (x, z) の真下の地面の高さ − 足元の高さ y(ブロック)。範囲は −{@link #DOWN}〜+{@link #UP}。 */
    static float heightAt(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int top = Mth.floor(y + UP);
        int bottom = Mth.floor(y - DOWN);
        for (int by = top; by >= bottom; by--) {
            p.set(bx, by, bz);
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            VoxelShape shape = s.getCollisionShape(level, p);
            if (shape.isEmpty()) {
                continue;
            }
            double surf = by + shape.max(Direction.Axis.Y);
            if (surf <= y + UP + 1.0E-3) {
                return Mth.clamp((float) (surf - y), -DOWN, UP);
            }
        }
        return -DOWN;
    }

    /**
     * 体の軸に沿った各点(モデルの z、1/16 ブロック)の真下の地面の高さを tick に 1 回測り、トレイルの配列に追わせる。
     * 体の向きは今の体の向き(曲がっていても段差の位置の誤差は小さい)。
     */
    static void update(LivingEntity e, CritterTrail t, float[] probeZ, float scale) {
        if (t.probeTick == e.tickCount && t.probeN == probeZ.length) {
            return;
        }
        boolean first = t.probeN != probeZ.length;
        t.probeTick = e.tickCount;
        t.probeN = probeZ.length;
        float yaw = e.yBodyRot * Mth.DEG_TO_RAD;
        // 前 = (−sin, 0, cos)。モデルの z+ は後ろ
        float fx = -Mth.sin(yaw);
        float fz = Mth.cos(yaw);
        for (int i = 0; i < probeZ.length; i++) {
            float back = probeZ[i] / 16.0F * scale;
            // 跳んでいる・落ちている間は地面に合わせない(体を地面へ引き下ろさない)
            // 高さはワールドの高さで持つ(エンティティが段を上がった瞬間に、測った値ごと 1 ブロックずれて体が浮かないように)
            float h = (float) e.getY() + (e.onGround() ? heightAt(e.level(), e.getX() - fx * back, e.getY(), e.getZ() - fz * back) : 0.0F);
            t.hPrev[i] = first ? h : t.hCur[i];
            // なめらかに追う(1 ブロックの段を 3〜4 tick で越える)
            t.hCur[i] = first ? h : t.hCur[i] + (h - t.hCur[i]) * 0.6F;
        }
    }
}
