package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.EntityFogCull;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 過繁茂バイオームの濃い霧の中にいる間、地形霧の終端より奥にいるエンティティの描画を打ち切る。
 * 判定は {@link EntityFogCull}(理由・対象外はそちらの説明を参照)。
 *
 * <p>差し込む先は {@code EntityRenderer#shouldRender} —— バニラが毎フレーム
 * 「このエンティティを描くか」を決めている唯一の場所で、{@code LevelRenderer} が
 * 実際の描画に入る前に呼ぶ。ここで false を返せば、モデルの頂点生成にも
 * ポーズ行列の計算にも一切入らない。
 *
 * <p><b>{@code LivingEntityRenderer} ではなく {@code EntityRenderer} に差すこと。</b>
 * GeckoLib の {@code GeoEntityRenderer} は {@code EntityRenderer} を直接継承しており
 * ({@code LivingEntityRenderer} は経由しない)、NeoForge の {@code RenderLivingEvent} も
 * {@code LivingEntityRenderer} からしか発火しないため、この MOD の Mob には届かない。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererFogCullMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void overgrownvegetation$cullFoggedEntities(Entity entity, Frustum frustum,
            double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (EntityFogCull.shouldCull(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
        }
    }
}
