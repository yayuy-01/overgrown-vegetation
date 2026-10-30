package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.ArmorAccessoryClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 「暗視の装飾品」(順 1 の着手順 9)—— ライトマップの暗視の強さを、装飾品を着けている間だけ
 * {@code gear.json} の {@code accessories.nightVision}(暗視の効果の何割か)まで上げる。
 *
 * <p>{@code LightTexture#updateLightTexture} は「暗視の効果があれば {@code GameRenderer.getNightVisionScale}(0〜1)、
 * なければ水中の伝達の力(コンジット)の明るさ、どちらも無ければ 0」を暗視の強さにし、各明るさの色を真っ白へその割合だけ寄せる。
 * 割合はもともと 0〜1 の途中の値を取る(コンジットは水中で 0 から 1 へ育つ)ので、0.3 なら「少し見える」になる。
 * 暗視の効果そのものは付けないので、アイコンも切れ際の点滅も出ない。見た目だけで、湧きなどには効かない。
 *
 * <p>呼び出しを包む({@code @WrapOperation})のは、同じ場所を触る他の MOD と共存できるから。
 * {@code hasEffect} の呼び出しは 2 つ(暗視・コンジット)あるので、暗視のときだけ答えを変える。
 */
@Mixin(LightTexture.class)
public class LightTextureAccessoryMixin {

    @WrapOperation(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean overgrown_vegetation$lensCountsAsNightVision(LocalPlayer player, Holder<MobEffect> effect,
                                                                  Operation<Boolean> original) {
        if (original.call(player, effect)) {
            return true;
        }
        return effect == MobEffects.NIGHT_VISION && ArmorAccessoryClient.nightVisionScale(player) > 0.0F;
    }

    @WrapOperation(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float overgrown_vegetation$lensScale(LivingEntity entity, float partialTick, Operation<Float> original) {
        float lens = ArmorAccessoryClient.nightVisionScale(entity);
        if (!entity.hasEffect(MobEffects.NIGHT_VISION)) {
            // 暗視の効果は無く、装飾品で上の判定を通った(元の処理は効果が無いと落ちるので呼ばない)。
            // 上で暗視の枝に入ったぶん飛ばされたコンジットの明るさは、ここで拾い直す。
            float water = entity instanceof LocalPlayer p && p.hasEffect(MobEffects.CONDUIT_POWER) ? p.getWaterVision() : 0.0F;
            return Math.max(lens, water);
        }
        return Math.max(original.call(entity, partialTick), lens);
    }
}
