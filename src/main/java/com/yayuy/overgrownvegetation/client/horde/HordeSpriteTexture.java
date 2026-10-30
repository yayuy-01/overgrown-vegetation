package com.yayuy.overgrownvegetation.client.horde;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * {@link SpriteSheet} を GPU へ上げたテクスチャ(ミップマップの段ごとに上げる。バニラの {@code DynamicTexture} は 0 段目しか
 * 上げないので、遠くで縮んだときにちらつく)。描画スレッドで作ること。
 */
final class HordeSpriteTexture extends AbstractTexture {

    HordeSpriteTexture(SpriteSheet sheet) {
        RenderSystem.assertOnRenderThreadOrInit();
        TextureUtil.prepareImage(this.getId(), SpriteSheet.MIP_LEVELS, sheet.width(), sheet.height());
        for (int level = 0; level <= SpriteSheet.MIP_LEVELS; level++) {
            int w = sheet.width() >> level;
            int h = sheet.height() >> level;
            int[] argb = sheet.levels()[level];
            try (NativeImage image = new NativeImage(w, h, false)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        image.setPixelRGBA(x, y, toAbgr(argb[y * w + x]));
                    }
                }
                this.bind();
                image.upload(level, 0, 0, false);
            }
        }
    }

    /** ARGB → NativeImage の並び(ABGR)。 */
    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    @Override
    public void load(ResourceManager manager) {
        // 中身は作ったときに上げてある(資源から読むものは無い)。
    }
}
