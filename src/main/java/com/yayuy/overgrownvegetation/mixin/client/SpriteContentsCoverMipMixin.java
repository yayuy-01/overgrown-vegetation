package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;

import com.yayuy.overgrownvegetation.corruption.CoverMipmap;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 覆いの絵({@code textures/block/cover/})のミップマップを、近くと同じ不透明の割合で作り直す({@link CoverMipmap})。バニラのミップは
 * 透ける所を段ごとに埋めるので、遠くの覆いが面全体の緑になる(作り直し側の D2 で測った: ユーザーの 5 枚は 2 段目で全部 100%)。2026-10-30 に作り直し側から持ってきた(前の SpriteContentsMossMipMixin = 1 段目から全部不透明、の置き換え)。0 段目(近く)は触らない。
 * 地形の描画ではないので Sodium がいても当てる(Sodium も同じアトラスを使う)。
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsCoverMipMixin {

    @Shadow
    @Final
    private ResourceLocation name;

    @Shadow
    public NativeImage[] byMipLevel;

    @Inject(method = "increaseMipLevel", at = @At("TAIL"))
    private void overgrown$coverMips(int mipLevel, CallbackInfo ci) {
        if (byMipLevel == null || byMipLevel.length < 2 || !CoverMipmap.isCoverSprite(name.getNamespace(), name.getPath())) {
            return;
        }
        NativeImage base = byMipLevel[0];
        int w = base.getWidth();
        int h = base.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = base.getPixelRGBA(x, y);
            }
        }
        com.yayuy.overgrownvegetation.data.CoverTable c = DesignData.cover();
        for (int level = 1; level < byMipLevel.length; level++) {
            NativeImage img = byMipLevel[level];
            int sw = img.getWidth();
            int sh = img.getHeight();
            if (sw != Math.max(1, w >> level) || sh != Math.max(1, h >> level)) {
                continue;
            }
            int[] out = CoverMipmap.level(pixels, w, h, level, c.mipCoverage(level));
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    img.setPixelRGBA(x, y, out[y * sw + x]);
                }
            }
        }
    }
}
