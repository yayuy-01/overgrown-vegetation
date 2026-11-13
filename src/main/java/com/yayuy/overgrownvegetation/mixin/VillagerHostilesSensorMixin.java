package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生きている村人は G1 から逃げる(2026-11-08、G1 村人の仕様書 11-05 ユーザーの答え 3「Mixin を 1 つ」)。
 *
 * <p>村人の脳は {@code NEAREST_HOSTILE}(このセンサーが立てる)があると逃げ出す(パニック)。バニラの「敵」の表
 * ({@code ACCEPTABLE_DISTANCE_FROM_HOSTILES}、ゾンビ 8 など)に無い G1 を、ゾンビと同じ {@value #FLEE_DISTANCE} ブロック【仮】で敵に数える。
 * 表は {@code isHostile} と {@code isClose} の両方で引かれ、{@code isClose} は表に無い種で落ちる(map.get が null)ので、
 * 両方を呼ぶ {@code isMatchingEntity} の頭で決める。グループは G1 だけ(仕様は「G1 から逃げる」)。
 */
@Mixin(VillagerHostilesSensor.class)
public abstract class VillagerHostilesSensorMixin {

    private static final float FLEE_DISTANCE = 8.0F;

    @Inject(method = "isMatchingEntity", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$fleeG1(LivingEntity villager, LivingEntity other, CallbackInfoReturnable<Boolean> cir) {
        if (other.getType().is(ModTags.GROUP_1)) {
            cir.setReturnValue(other.distanceToSqr(villager) <= (double) (FLEE_DISTANCE * FLEE_DISTANCE));
        }
    }
}
