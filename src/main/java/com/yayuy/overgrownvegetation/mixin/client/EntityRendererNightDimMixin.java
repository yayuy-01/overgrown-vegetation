package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yayuy.overgrownvegetation.client.light.MobNightDim;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 夜の過繁茂の森で、Mob などの明るさの空の光の段を下げる(2026-11-06、{@link MobNightDim})。ブロックの光の段(松明・洞窟の動く光)は触らない。
 * {@code getPackedLightCoords} は final なので、描き方ごとの上書き(GeckoLib の Mob も)と一人称の手に持った物もここを通る。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNightDimMixin {

    // エンティティごと・毎フレーム。夜の森の外では倍率 1 で何もしない
    @ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
    private int overgrown_vegetation$nightDim(int packed) {
        return MobNightDim.dim(packed);
    }
}
