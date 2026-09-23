package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 壁のぼり / 天井這いの<b>地形判定</b>だけを切り出した共通処理。
 *
 * <p>{@link PhalangiotarbusEntity}(擬態して木を登る)と
 * {@link AbstractOvergrownInvaderEntity}(グループ4。アルボラが壁と天井を這う)で同じものを使う。
 * 「どう張り付いて、どう動くか」は種ごとに違うのでそれぞれの側に置き、ここには
 * 「いまぶつかっている壁はどっちか」「頭上に天井があるか」だけを置く。
 */
public final class WallCrawl {

    private WallCrawl() {
    }

    /**
     * いまぶつかっている壁(ブロックの種類は問わない)の水平方向。進行方向に最も沿った面を返す。
     * 隣に固い面が無ければ {@code null}(= 登れない)。
     */
    @Nullable
    public static Direction collidingWallDirection(Mob mob) {
        Vec3 v = mob.getDeltaMovement();
        double dirX;
        double dirZ;
        if (v.x * v.x + v.z * v.z > 1.0E-4D) {
            dirX = v.x;
            dirZ = v.z;
        } else {
            Vec3 look = mob.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
        }
        Direction best = null;
        double bestDot = -2.0D;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!hasSolidNeighbor(mob, d)) {
                continue;
            }
            double dot = d.getStepX() * dirX + d.getStepZ() * dirZ;
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    /** 体の高さぶん(最低 2 マス)、{@code d} の隣に固い面があるか。 */
    public static boolean hasSolidNeighbor(Mob mob, Direction d) {
        BlockPos base = mob.blockPosition();
        int height = Math.max(2, Mth.ceil(mob.getBbHeight()));
        for (int dy = 0; dy < height; dy++) {
            if (mob.level().getBlockState(base.above(dy).relative(d)).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /** 頭上 {@code gap} ブロック以内に固いブロック(天井)があるか。 */
    public static boolean ceilingWithin(Mob mob, double gap) {
        double headY = mob.getY() + mob.getBbHeight();
        return mob.level().getBlockState(
                BlockPos.containing(mob.getX(), headY + gap, mob.getZ())).blocksMotion();
    }
}
