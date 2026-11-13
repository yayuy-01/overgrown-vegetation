package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 汚れの絵(2026-11-14 サバイバルの背骨 段 3b、決定 33)。黄緑の胞子の粉の点を、元の絵の形(不透明な画素)の上にだけ散らした絵を
 * その場で作って覚える(アイコン用はアイテムの絵ごと、体に着た姿用は防具の絵ごと × 段 3 つ)。資源の読み直しで忘れる。
 *
 * <p>粉の色【仮】は汚染シダの明るい黄緑(#ace300)の近くから。段が上がるほど点が増え、上の段の点は下の段の点を含む(段が上がっても点が跳ばない)。
 * アイコンは下ほど点が多い(粉が下へたまる)。3 段目だけ暗い汚れの点が混じる。
 */
public final class DirtOverlays {

    private DirtOverlays() {
    }

    /** 粉の色(RGB)。 */
    static final int[] DUST = {0xb6cf3a, 0x9fbb2c, 0xc9dc5c, 0x86a21f, 0xd6e27f};
    /** 3 段目に混じる暗い汚れ。 */
    static final int GRIME = 0x5f6b22;
    /** 段 1・2・3 の点の割合(不透明な画素に対して)。 */
    static final double[] DENSITY = {0.10D, 0.24D, 0.42D};

    private static final Map<String, Optional<ResourceLocation>> CACHE = new HashMap<>();

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Optional<ResourceLocation> o : CACHE.values()) {
            o.ifPresent(mc.getTextureManager()::release);
        }
        CACHE.clear();
    }

    /** アイテムのアイコンの上に重ねる汚れの絵(無ければ null)。大きさはアイテムの絵の幅(正方形)。 */
    public static Overlay itemOverlay(ItemStack stack, int stage) {
        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite sprite = mc.getItemRenderer().getModel(stack, null, null, 0).getParticleIcon();
        if (sprite == null) return null;
        NativeImage src = sprite.contents().getOriginalImage();
        int w = sprite.contents().width(), h = sprite.contents().height();
        String key = "item/" + sprite.contents().name() + "/" + stage;
        Optional<ResourceLocation> loc = CACHE.computeIfAbsent(key, k -> build(k, src, w, h, stage, true));
        return loc.map(l -> new Overlay(l, w, h)).orElse(null);
    }

    /** 防具の絵 {@code armorTexture} に合わせた汚れの絵(無ければ null)。 */
    public static ResourceLocation armorOverlay(ResourceLocation armorTexture, int stage) {
        String key = "armor/" + armorTexture + "/" + stage;
        Optional<ResourceLocation> cached = CACHE.get(key);
        if (cached != null) return cached.orElse(null);
        Optional<ResourceLocation> out = Optional.empty();
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(armorTexture);
            if (res.isPresent()) {
                try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                    out = build(key, img, img.getWidth(), img.getHeight(), stage, false);
                }
            }
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("汚れの絵を作れない(防具の絵 {} が読めない)", armorTexture, e);
        }
        CACHE.put(key, out);
        return out.orElse(null);
    }

    public record Overlay(ResourceLocation texture, int width, int height) {
    }

    /** {@code src} の不透明な画素の上にだけ粉の点を置いた絵を作り、登録する。 */
    private static Optional<ResourceLocation> build(String key, NativeImage src, int w, int h, int stage, boolean settle) {
        if (src == null || w <= 0 || h <= 0 || stage < 1 || stage > 3) return Optional.empty();
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        double dens = DENSITY[stage - 1];
        int salt = key.hashCode() & 0x7fffffff;
        int any = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = src.getPixelRGBA(x, y);
                if ((abgr >>> 24) < 128) continue;
                // 同じ画素は段が上がっても同じ値 → 上の段の点は下の段の点を含む
                double u = unit(x, y, salt ^ 0x51ed);
                double d = settle ? dens * (0.55D + 0.9D * y / Math.max(1, h - 1)) : dens;
                if (u >= d) continue;
                int rgb = stage == 3 && unit(x, y, salt ^ 0x7a3) < 0.18D ? GRIME
                        : DUST[(int) (unit(x, y, salt ^ 0x1234) * DUST.length) % DUST.length];
                out.setPixelRGBA(x, y, 0xff000000 | abgrOf(rgb));
                any++;
            }
        }
        if (any == 0) {
            out.close();
            return Optional.empty();
        }
        ResourceLocation id = OvergrownVegetation.id("dynamic/gear_dirt/" + sanitize(key));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(out));
        return Optional.of(id);
    }

    private static int abgrOf(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return (b << 16) | (g << 8) | r;
    }

    static double unit(int x, int y, int salt) {
        int hsh = x * 0x27D4EB2D ^ y * 0x165667B1 ^ salt * 0x9E3779B1;
        hsh ^= hsh >>> 15;
        hsh *= 0x2C1B3C6D;
        hsh ^= hsh >>> 12;
        hsh *= 0x297A2D39;
        hsh ^= hsh >>> 15;
        return (hsh & 0xffffff) / 16777216.0D;
    }

    private static String sanitize(String key) {
        StringBuilder b = new StringBuilder(key.length());
        for (char c : key.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' ? c : '_');
        }
        return b.toString();
    }
}
