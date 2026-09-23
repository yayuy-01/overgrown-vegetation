package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.MobVariantPayload;
import com.yayuy.overgrownvegetation.variant.MobVariants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 亜種のテクスチャ解決(クライアント、2026-09-19)。
 *
 * <p>各 Mob の {@code GeoModel#getTextureResource} が {@link #of(Entity, String)} を呼ぶ。
 * 種別が通常以外なら {@code textures/entity/<base>_<variant>.png} を返し、その png が
 * リソースパックに無ければ通常のテクスチャへ戻す(存在確認の結果はキャッシュし、
 * リソースの再読み込みで捨てる)。
 *
 * <p>同期パケットの受け口 {@link #accept} もここ(クライアント専用クラスにまとめておく)。
 */
public final class MobVariantTextures {

    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    public static ResourceLocation of(Entity entity, String base) {
        String name = MobVariants.textureName(entity, base);
        return CACHE.computeIfAbsent(name, n -> resolve(n, base));
    }

    private static ResourceLocation resolve(String name, String base) {
        ResourceLocation candidate = OvergrownVegetation.id("textures/entity/" + name + ".png");
        if (name.equals(base)) {
            return candidate;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager().getResource(candidate).isPresent()) {
            return candidate;
        }
        return OvergrownVegetation.id("textures/entity/" + base + ".png");
    }

    /** リソースの再読み込み(F3+T)で存在確認をやり直す。 */
    public static void invalidate() {
        CACHE.clear();
    }

    public static void accept(MobVariantPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(payload.entityId());
        if (entity != null) {
            MobVariants.setClient(entity, payload.variant());
        }
    }

    private MobVariantTextures() {
    }
}
