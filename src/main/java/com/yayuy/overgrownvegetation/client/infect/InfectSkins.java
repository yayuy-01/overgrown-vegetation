package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 汎用の感染体の、塗り替えた絵の置き場(元の絵 1 枚につき 1 回だけ作る。毎フレームの箱は増えない)。
 * <p><b>試作(2026-11-15)</b>: 塗りはまだ色分けだけ(上を向く面 = 緑・横 = 黄・下 = 赤)。差し替えと面の向きの読み取りが
 * バニラ・重ね着・GeckoLib のどれでも効くかを確かめるためのもの。塗りの決まり(苔・死体の色・名残)はこの後に移す。</p>
 */
final class InfectSkins {

    /** 元の絵 → 塗り替えた絵(作れなかった絵は empty = 元のまま描く)。 */
    private static final Map<ResourceLocation, Optional<ResourceLocation>> SKINS = new HashMap<>();
    private static int serial;

    private InfectSkins() {
    }

    static ResourceLocation skinFor(ResourceLocation texture, GenericInfectionClient.State state) {
        Optional<ResourceLocation> skin = SKINS.get(texture);
        return skin == null ? texture : skin.orElse(texture);
    }

    static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Optional<ResourceLocation> o : SKINS.values()) {
            o.ifPresent(mc.getTextureManager()::release);
        }
        SKINS.clear();
    }

    /** 覗いた頂点から、絵ごとに「どの画素がどの向きの面か」を出し、塗り替えた絵を作る。 */
    static void learn(Entity entity, InfectRender.QuadTap tap, double baseY) {
        for (Map.Entry<RenderType, List<InfectRender.Quad>> e : tap.quads.entrySet()) {
            ResourceLocation texture = InfectRender.TYPE_TEXTURE.get(e.getKey());
            if (texture == null || SKINS.containsKey(texture) || texture.getPath().startsWith("dynamic/infect_skin/")) {
                continue;
            }
            SKINS.put(texture, build(entity, texture, e.getValue(), (float) baseY));
        }
    }

    private static Optional<ResourceLocation> build(Entity entity, ResourceLocation texture, List<InfectRender.Quad> quads, float baseY) {
        Minecraft mc = Minecraft.getInstance();
        try {
            var res = mc.getResourceManager().getResource(texture);
            if (res.isEmpty()) {
                OvergrownVegetation.LOGGER.info("InfectSkins 絵を読めない(資源に無い): {} {}", entity.getType(), texture);
                return Optional.empty();
            }
            NativeImage img;
            try (InputStream in = res.get().open()) {
                img = NativeImage.read(in);
            }
            int w = img.getWidth(), h = img.getHeight();
            float[] top = new float[w * h];
            java.util.Arrays.fill(top, Float.NaN);
            int up = 0, side = 0, down = 0;
            for (InfectRender.Quad q : quads) {
                if (q.ny() > 0.5F) up++;
                else if (q.ny() < -0.5F) down++;
                else side++;
                int x0 = Math.max(0, (int) Math.floor(q.u0() * w + 0.01F)), x1 = Math.min(w, (int) Math.ceil(q.u1() * w - 0.01F));
                int y0 = Math.max(0, (int) Math.floor(q.v0() * h + 0.01F)), y1 = Math.min(h, (int) Math.ceil(q.v1() * h - 0.01F));
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int i = y * w + x;
                        // 同じ画素を使う面が重なったら、いちばん上を向く面を採る
                        if (Float.isNaN(top[i]) || q.ny() > top[i]) {
                            top[i] = q.ny();
                        }
                    }
                }
            }
            int painted = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float ny = top[y * w + x];
                    int abgr = img.getPixelRGBA(x, y);
                    if (Float.isNaN(ny) || (abgr >>> 24) < 16) {
                        continue;
                    }
                    int rgb = ny > 0.5F ? 0x3fa33a : ny < -0.5F ? 0xb03a2e : 0xc9b037;
                    int r = (abgr & 0xff), g = (abgr >> 8) & 0xff, b = (abgr >> 16) & 0xff;
                    r = (r + ((rgb >> 16) & 0xff)) / 2;
                    g = (g + ((rgb >> 8) & 0xff)) / 2;
                    b = (b + (rgb & 0xff)) / 2;
                    img.setPixelRGBA(x, y, (abgr & 0xff000000) | (b << 16) | (g << 8) | r);
                    painted++;
                }
            }
            ResourceLocation id = OvergrownVegetation.id("dynamic/infect_skin/" + (serial++));
            mc.getTextureManager().register(id, new DynamicTexture(img));
            OvergrownVegetation.LOGGER.info("InfectSkins 作った: {} {} {}x{} 四角 {}(上 {} 横 {} 下 {})塗った画素 {} / {}",
                    entity.getType(), texture, w, h, quads.size(), up, side, down, painted, w * h);
            return Optional.of(id);
        } catch (Exception ex) {
            OvergrownVegetation.LOGGER.warn("InfectSkins 絵を作れない: {}", texture, ex);
            return Optional.empty();
        }
    }
}
