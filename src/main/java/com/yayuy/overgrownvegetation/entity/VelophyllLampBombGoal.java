package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * ヴェロフィルが<b>忌避灯を爆撃する</b> Goal(2026-09-19)。{@link VelophyllBombGoal} の狙いを
 * 「いちばん近い点いている忌避灯のブロック座標」に差し替えただけで、接近・投下・旋回はまったく同じ。
 *
 * <p>忌避灯はグループ1〜4 を寄せ付けないが、ヴェロフィルだけは例外で
 * ({@code GuidedHuntInjector} が忌避 Goal を付けない)、{@code defense.json} の
 * {@code repellentLamp.velophyllDetectRange} 以内に点いている灯りがあれば爆撃しに来る。
 * 爆弾が灯りの {@code coverRadius} 以内で炸裂すると灯りは胞子と蔦に覆われて止まり
 * ({@code VelophyllBombEntity} → {@link RepellentLamps#coverNear})、台帳から外れてこの Goal も終わる。
 *
 * <p>優先度は生き物への爆撃と同じ 2 で、先に登録してあるので「灯りが見えているなら灯りを優先」。
 * 生き物の爆撃中に灯りが見つかっても、そちらが終わるまでは割り込まない(同じ優先度は入れ替わらない)。
 */
public class VelophyllLampBombGoal extends VelophyllBombGoal {

    private final VelophyllEntity velophyll;
    @Nullable
    private BlockPos lamp;

    public VelophyllLampBombGoal(VelophyllEntity mob) {
        super(mob);
        this.velophyll = mob;
    }

    @Nullable
    private BlockPos findLamp() {
        double range = DesignData.defense().lampVelophyllDetectRange();
        if (range <= 0.0D) {
            return null;
        }
        return RepellentLamps.nearestActive(this.velophyll.level(), this.velophyll.position(), range);
    }

    @Override
    public boolean canUse() {
        if (this.velophyll.isInWater()) {
            return false;
        }
        this.lamp = findLamp();
        return this.lamp != null;
    }

    @Override
    public boolean canContinueToUse() {
        // 灯りが覆われた(台帳から消えた)ら終わり。別の灯りが近ければそちらへ移る。
        return canUse();
    }

    @Override
    protected boolean hasAim() {
        return this.lamp != null;
    }

    @Override
    protected Vec3 aimPos() {
        return this.lamp != null ? Vec3.atCenterOf(this.lamp) : this.velophyll.position();
    }

    @Override
    protected boolean aimVisible() {
        if (this.lamp == null) {
            return false;
        }
        Vec3 eye = this.velophyll.getEyePosition();
        Vec3 to = Vec3.atCenterOf(this.lamp);
        BlockHitResult hit = this.velophyll.level().clip(
                new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.velophyll));
        // 灯りそのものに当たった = 見えている。
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(this.lamp);
    }

    @Override
    protected void lookAtAim() {
        if (this.lamp != null) {
            Vec3 c = Vec3.atCenterOf(this.lamp);
            this.velophyll.getLookControl().setLookAt(c.x, c.y, c.z, 30.0F, 30.0F);
        }
    }
}
