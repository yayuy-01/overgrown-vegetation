package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.atmosphere.CaveLights;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地下モードの雲(11-04 洞窟の直し 1)。地下モードの間は地形の霧が黒・12〜16 なので、洞窟の中から口の向こうに見える雲まで黒く塗りつぶされた。
 * {@code renderLevel} の中の {@code renderClouds} の呼び出しの前後で、霧を地上のまま(距離と色)に戻して、また黒へ戻す
 * ({@link CaveLights#beginClouds})。呼び出しの側に差すのは、Sodium が {@code renderClouds} の先頭で自前の雲に差し替えて抜けるため
 * (中に差すと Sodium の雲では呼ばれないことがある)。
 *
 * <p>11-04 ユーザー決定: {@code require = 0}。Iris・Distant Horizons などがこの呼び出しを差し替えていて差せなくても起動は止めず、
 * {@code OvergrownMixinPlugin#postApply} が WARN を 1 回出す。そのときは雲が地下の黒い霧のまま描かれるだけ(beginClouds が呼ばれなければ
 * cloudPass は立たず、霧の値も差し替えない。endClouds は cloudPass が無ければ何もしない)。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCaveCloudsMixin {

    private static final String RENDER_CLOUDS =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V";

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS), require = 0)
    private void overgrown_vegetation$surfaceFogForClouds(CallbackInfo ci) {
        CaveLights.beginClouds();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_CLOUDS, shift = At.Shift.AFTER), require = 0)
    private void overgrown_vegetation$caveFogAfterClouds(CallbackInfo ci) {
        CaveLights.endClouds();
    }
}
