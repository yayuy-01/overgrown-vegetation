package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.client.OvergrownLayerQuads;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 苔の面のテクスチャ({@code overgrown_layer})の縮小した絵から穴を消す(2026-10-07、ユーザー報告「覆われたブロックを遠くから見るとちかちかする」)。
 *
 * <p>苔のテクスチャは 44% の画素が透明で、近くでは穴から元のブロックが見える。バニラの縮小(ミップマップ)は 2×2 に 1 つでも色があれば
 * 不透明にするので、1 段目(8×8)でも 1 面に穴が 2 つ残り、2 段目から無くなる。遠くでは段と段を混ぜて描く(と透明の境目 0.5 で切る)ので、
 * 視点がわずかに動くたびに残った穴から元のブロックの色が点々と出たり消えたりしていた(テスト用クライアントで 40〜140 ブロック先に確認)。
 *
 * <p>1 段目から先を全部不透明にする —— 色のある画素はそのまま、穴は同じ段の色のある画素の平均色で埋める。近くの見た目(0 段目)は変わらず、
 * 0 段目と 1 段目の中ほど(視野 70 度で 720p なら約 45 ブロック、1080p なら約 70 ブロック)から先は苔が全面を覆う。色はバニラの縮小のまま(遠くの苔の濃さは変わらない)。
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMossMipMixin {

    @Inject(method = "increaseMipLevel", at = @At("TAIL"))
    private void overgrown_vegetation$fillMossHoles(int mipLevel, CallbackInfo ci) {
        SpriteContents self = (SpriteContents) (Object) this;
        ResourceLocation name = self.name();
        if (!OvergrownLayerQuads.LAYER_TEXTURE.equals(name) || self.byMipLevel == null) {
            return;
        }
        for (int level = 1; level < self.byMipLevel.length; level++) {
            NativeImage image = self.byMipLevel[level];
            if (image == null) {
                continue;
            }
            long c0 = 0;
            long c1 = 0;
            long c2 = 0;
            int count = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    if ((pixel >>> 24) != 0) {
                        c0 += pixel & 0xFF;
                        c1 += (pixel >>> 8) & 0xFF;
                        c2 += (pixel >>> 16) & 0xFF;
                        count++;
                    }
                }
            }
            int fill = count == 0 ? 0 : (int) (c0 / count) | (int) (c1 / count) << 8 | (int) (c2 / count) << 16;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int rgb = (pixel >>> 24) != 0 ? pixel & 0x00FFFFFF : fill;
                    image.setPixelRGBA(x, y, 0xFF000000 | rgb);
                }
            }
        }
    }
}
