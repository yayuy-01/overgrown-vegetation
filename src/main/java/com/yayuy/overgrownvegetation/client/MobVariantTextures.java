package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.MobVariantPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.variant.MobVariant;
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

    /**
     * {@code base} ごとの解決済みテクスチャ。添字は亜種の番号({@link MobVariant#code()}。0 = 未抽選・範囲外)。
     *
     * <p>2026-09-24 軽量化(監査 1318 / 1406 / 2110): 描画される個体ごとに毎フレーム呼ばれるので、
     * 以前の「{@link MobVariants#textureName} で {@code base + "_" + suffix} を連結 → その文字列で
     * マップを引く(ラムダも確保)」をやめ、{@code base}(各 Model の文字列リテラルなのでハッシュは計算済み)で
     * 配列を引いて番号で取り出す。返す {@link ResourceLocation} と、png が無いときに通常へ戻す判定
     * ({@link #resolve})は前と同じ。
     */
    private static final Map<String, ResourceLocation[]> BY_BASE = new ConcurrentHashMap<>();
    private static final int SLOTS = MobVariant.values().length + 1;

    public static ResourceLocation of(Entity entity, String base) {
        // getExistingData は Optional を作るので、hasData → getData で読む(getData は無いと既定値を書き込むので必ず先に hasData)。
        int code = entity.hasData(ModAttachments.MOB_VARIANT) ? entity.getData(ModAttachments.MOB_VARIANT) : 0;
        if (code < 0 || code >= SLOTS) {
            code = 0; // MobVariant.byCode が null を返す番号 = 通常と同じ base
        }
        ResourceLocation[] slots = BY_BASE.get(base);
        if (slots == null) {
            slots = BY_BASE.computeIfAbsent(base, b -> new ResourceLocation[SLOTS]);
        }
        ResourceLocation texture = slots[code];
        if (texture == null) {
            // 名前の組み立ては MobVariants#textureName と同じ(通常個体・未抽選は base のまま)。
            MobVariant variant = MobVariant.byCode(code);
            String name = variant == null || variant == MobVariant.NORMAL ? base : base + "_" + variant.suffix();
            texture = resolve(name, base);
            slots[code] = texture;
        }
        return texture;
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
        BY_BASE.clear();
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
