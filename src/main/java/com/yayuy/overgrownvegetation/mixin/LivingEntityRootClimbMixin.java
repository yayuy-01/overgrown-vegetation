package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.cliff.RootClimb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 絡む根を登る(2026-11-20 崖の汚染。{@link RootClimb})。サーバーとクライアントの両方で効く(プレイヤーの動きはクライアントが決める)。
 * <ul>
 *   <li>{@code onClimbable}: 体の横が絡む根に触れていれば、はしごと同じ扱い(落ちるのが遅い・落下のダメージがない・押し付けると上る)。</li>
 *   <li>{@code handleRelativeFrictionAndCalculateMovement}: 絡む根での上りの縦 0.2 を、植物側・プレイヤーなどの倍率の値に置き換える。</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityRootClimbMixin {

    @Inject(method = "onClimbable", at = @At("RETURN"), cancellable = true)
    private void overgrown$rootClimbable(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && RootClimb.climbing((LivingEntity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handleRelativeFrictionAndCalculateMovement", at = @At("RETURN"), cancellable = true)
    private void overgrown$rootClimbSpeed(Vec3 deltaMovement, float friction, CallbackInfoReturnable<Vec3> cir) {
        Vec3 v = cir.getReturnValue();
        if (v.y != 0.2D) return; // バニラの上り(はしご・粉雪・絡む根)のときだけ 0.2 ちょうど
        LivingEntity self = (LivingEntity) (Object) this;
        if (!RootClimb.touching(self)) return;
        cir.setReturnValue(new Vec3(v.x, RootClimb.riseFor(self), v.z));
    }
}
