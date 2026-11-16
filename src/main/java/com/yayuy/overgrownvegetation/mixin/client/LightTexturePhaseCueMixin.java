package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.client.atmosphere.PhaseCue;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * フェーズ 6 に上がったときの印「拠点の灯りが一瞬揺らいで暗くなる」(2026-11-16 ユーザー決定。森の力が拠点にまで届き始めた予告)。
 * ライトマップのブロックの光(松明・ランタンなど)を、その段の「空の光だけの色」へ寄せる({@link PhaseCue#lightDip})。空の光は変えないので、
 * 昼の屋外はほとんど変わらず、灯りで照らした室内・夜の拠点が暗くなる。
 *
 * <p>{@code setPixelRGBA(x = ブロックの光の段, y = 空の光の段, 色)} を包む({@code LightTextureCaveTintMixin} と同じ所。包みは重なる)。
 * バニラは空の光の段ごとに x = 0 から順に書くので、x = 0 の色(ブロックの光なし)を控えて、同じ段の残りをそこへ寄せる。
 * 合図が流れていない間は何もしない。地下モードの点光源(光る植物)と動く光は別の道なので、暗くならない。
 */
@Mixin(LightTexture.class)
public class LightTexturePhaseCueMixin {

    @Unique
    private int overgrown_vegetation$rowBase;

    @WrapOperation(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/NativeImage;setPixelRGBA(III)V"))
    private void overgrown_vegetation$phaseCueDip(NativeImage image, int x, int y, int abgr, Operation<Void> original) {
        float dip = PhaseCue.lightDip();
        if (dip <= 0.0F) {
            original.call(image, x, y, abgr);
            return;
        }
        if (x == 0) {
            this.overgrown_vegetation$rowBase = abgr;
            original.call(image, x, y, abgr);
            return;
        }
        int base = this.overgrown_vegetation$rowBase;
        int r = overgrown_vegetation$mix(abgr & 0xFF, base & 0xFF, dip);
        int g = overgrown_vegetation$mix((abgr >> 8) & 0xFF, (base >> 8) & 0xFF, dip);
        int b = overgrown_vegetation$mix((abgr >> 16) & 0xFF, (base >> 16) & 0xFF, dip);
        original.call(image, x, y, (abgr & 0xFF000000) | (b << 16) | (g << 8) | r);
    }

    @Unique
    private static int overgrown_vegetation$mix(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
