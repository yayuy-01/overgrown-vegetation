package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 胞子爆弾({@link VelophyllBombEntity})を<b>決まった初速で</b>狙った所へ届かせる撃ち出しの向き(2026-10-09、ネザーの媒介種の撃ち方)。
 *
 * <p>爆弾は重さ {@link VelophyllBombEntity#GRAVITY} と空気抵抗 {@link VelophyllBombEntity#DRAG} で弧を描いて落ちるので、
 * 撃ち上げの角度を下から順に試し、狙点の水平距離に来たときの高さが狙点に合う<b>いちばん低い弧</b>を選ぶ
 * (迫撃砲 {@link ShielderMortarGoal} は角度を決めて速さを探すが、こちらは速さを決めて角度を探す)。
 */
public final class SporeBombAim {

    /** 試す角度の下限・上限・刻み(度)。下向きにも撃てる(ガストは上から撃ち下ろす)。 */
    private static final int MIN_PITCH = -80;
    private static final int MAX_PITCH = 60;
    private static final int STEP = 2;
    /** これだけ合えば当たりとみなす高さのずれ(ブロック)。 */
    private static final double TOLERANCE = 0.75D;
    private static final int MAX_FLIGHT_TICKS = 200;

    /**
     * @param delta 撃つ所 → 狙点
     * @param speed 初速(ブロック/tick)
     * @return 初速のベクトル。どの角度でも届かなければ、いちばん近くへ落ちる角度。真下なら真下へ落とす
     */
    public static Vec3 solve(Vec3 delta, double speed) {
        double horizontal = delta.horizontalDistance();
        if (horizontal < 0.5D) {
            return new Vec3(0.0D, delta.y < 0.0D ? -speed * 0.5D : speed * 0.5D, 0.0D);
        }
        double dirX = delta.x / horizontal;
        double dirZ = delta.z / horizontal;
        Vec3 best = null;
        double bestError = Double.MAX_VALUE;
        for (int deg = MIN_PITCH; deg <= MAX_PITCH; deg += STEP) {
            double pitch = Math.toRadians(deg);
            double flat = Math.cos(pitch) * speed;
            Vec3 v = new Vec3(dirX * flat, Math.sin(pitch) * speed, dirZ * flat);
            Double height = heightAt(v, horizontal);
            if (height == null) {
                continue;
            }
            double error = Math.abs(height - delta.y);
            if (error < TOLERANCE) {
                return v;
            }
            if (error < bestError) {
                bestError = error;
                best = v;
            }
        }
        return best != null ? best : new Vec3(dirX * speed * 0.7D, speed * 0.7D, dirZ * speed * 0.7D);
    }

    /** 初速 {@code v} の爆弾が水平距離 {@code horizontal} に来たときの高さ。来なければ {@code null}。式は {@link VelophyllBombEntity#tick} と同じ。 */
    @Nullable
    static Double heightAt(Vec3 v, double horizontal) {
        double vx = v.x;
        double vy = v.y;
        double vz = v.z;
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            vy = (vy - VelophyllBombEntity.GRAVITY) * VelophyllBombEntity.DRAG;
            vx *= VelophyllBombEntity.DRAG;
            vz *= VelophyllBombEntity.DRAG;
            x += vx;
            y += vy;
            z += vz;
            if (Math.sqrt(x * x + z * z) >= horizontal) {
                return y;
            }
        }
        return null;
    }

    private SporeBombAim() {
    }
}
