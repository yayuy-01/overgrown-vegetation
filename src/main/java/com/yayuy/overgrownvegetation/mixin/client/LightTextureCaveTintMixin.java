package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.client.atmosphere.CaveLights;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 地下モードのライトマップの色(11-04 洞窟の直し 3)。Mob・プレイヤー・アイテム・手は、点光源の強さだけライトマップのブロックの光を持ち上げて明るくしている
 * ({@code client/light/DynamicLights})が、色はバニラのブロックの光の暖色のままで、黄緑の点光源に照らされた周りの中で違う色の人形に見えた。
 * 地下モードの間は、ライトマップのブロックの光の列を点光源の色へ寄せる({@link CaveLights#tintLightmap})。
 *
 * <p>{@code setPixelRGBA(x = ブロックの光の段, y = 空の光の段, 色)} を包む(森の緑の照り返し {@code LightTextureCanopyLiftMixin} と同じ所。包みは重なる)。
 * ライトマップは地形とも共用なので、地下モードの間は松明などの本物のブロックの光も同じ色に寄る(報告に書いた制限)。
 */
@Mixin(LightTexture.class)
public class LightTextureCaveTintMixin {

    @WrapOperation(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/NativeImage;setPixelRGBA(III)V"))
    private void overgrown_vegetation$caveTint(NativeImage image, int x, int y, int abgr, Operation<Void> original) {
        original.call(image, x, y, CaveLights.tintLightmap(abgr, x, y));
    }
}
