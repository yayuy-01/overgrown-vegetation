package com.yayuy.overgrownvegetation.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Mob の当たり判定の大きさ(幅・高さ。ブロック)。11-18 コードの見直し 第 2 回 ④1・ユーザー決定 6 で mobs.json から外し、Java に固定した。
 * 前はサーバーとクライアントがそれぞれ自分の mobs.json(実行時の写し)から読むので、専用サーバーで管理者が width・height を変えると
 * 当たり判定がずれた。値は 11-18 夜の design/mobs.json の width・height をそのまま写したもの。変えるときはここを直す(再起動)。
 * 表に無い種は、登録の所に書いた既定値。
 */
public final class MobSizes {

    private static final Map<String, float[]> SIZES = new HashMap<>();

    private MobSizes() {
    }

    private static void put(String id, float width, float height) {
        SIZES.put(id, new float[]{width, height});
    }

    static {
        put("acumnes", 1.2F, 3.0F);
        put("arbora", 0.98F, 0.98F);
        put("blight_fern", 1.0F, 2.0F);
        put("clipeimnes", 1.4F, 2.6F);
        put("coelurosauravus", 0.7F, 0.4F);
        put("developed_parasitized_baby_zombie", 0.6F, 0.8F);
        put("developed_parasitized_chicken", 0.5F, 1.0F);
        put("developed_parasitized_cow", 1.0F, 1.5F);
        put("developed_parasitized_creeper", 0.6F, 1.7F);
        put("developed_parasitized_enderman", 0.6F, 3.0F);
        put("developed_parasitized_pig", 1.0F, 1.0F);
        put("developed_parasitized_sheep", 0.9F, 1.7F);
        put("developed_parasitized_skeleton", 0.6F, 1.99F);
        put("developed_parasitized_spider", 1.4F, 1.2F);
        put("developed_parasitized_villager", 0.8F, 1.9F);
        put("developed_parasitized_zombie", 0.6F, 1.95F);
        put("falcimnes", 1.3F, 2.1F);
        put("gestatrix", 1.6F, 1.0F);
        put("gregaria", 0.7F, 0.45F);
        put("hylonomus", 0.4F, 0.3F);
        put("ianthasaurus", 0.7F, 0.6F);
        put("meganeura", 1F, 0.5F);
        put("nursery_parasitized_baby_zombie", 0.9F, 0.7F);
        put("nursery_parasitized_chicken", 0.8F, 0.6F);
        put("nursery_parasitized_cow", 1.3F, 1.2F);
        put("nursery_parasitized_creeper", 1.2F, 1.0F);
        put("nursery_parasitized_enderman", 1.2F, 1.0F);
        put("nursery_parasitized_pig", 1.1F, 1.0F);
        put("nursery_parasitized_sheep", 1.2F, 1.1F);
        put("nursery_parasitized_skeleton", 1.2F, 1.0F);
        put("nursery_parasitized_spider", 1.2F, 1.0F);
        put("nursery_parasitized_villager", 1.2F, 1.1F);
        put("nursery_parasitized_zombie", 1.2F, 1.0F);
        put("parasitized_baby_zombie", 0.3F, 0.975F);
        put("parasitized_chicken", 0.4F, 0.7F);
        put("parasitized_cow", 0.9F, 1.4F);
        put("parasitized_creeper", 0.6F, 1.7F);
        put("parasitized_enderman", 0.6F, 2.9F);
        put("parasitized_iron_golem", 1.4F, 2.7F);
        put("parasitized_pig", 0.9F, 0.9F);
        put("parasitized_sheep", 0.9F, 1.3F);
        put("parasitized_skeleton", 0.6F, 1.99F);
        put("parasitized_spider", 1.4F, 0.9F);
        put("parasitized_villager", 0.6F, 1.95F);
        put("parasitized_warden", 0.9F, 2.9F);
        put("parasitized_zombie", 0.6F, 1.95F);
        put("pedicula", 3.6F, 3.6F);
        put("petrolacosaurus", 0.6F, 0.4F);
        put("phyllosens", 2.6F, 13F);
        put("pilimnes", 1.3F, 1.6F);
        put("root_guardian", 1.3F, 1.9F);
        put("rooted_parasitized_baby_zombie", 0.8F, 2.5F);
        put("rooted_parasitized_chicken", 0.7F, 0.9F);
        put("rooted_parasitized_cow", 1.4F, 1.6F);
        put("rooted_parasitized_creeper", 0.8F, 1.8F);
        put("rooted_parasitized_enderman", 1.0F, 4.6F);
        put("rooted_parasitized_pig", 1.2F, 1.2F);
        put("rooted_parasitized_sheep", 1.3F, 1.6F);
        put("rooted_parasitized_skeleton", 1.0F, 5.2F);
        put("rooted_parasitized_spider", 1.0F, 1.8F);
        put("rooted_parasitized_villager", 1.0F, 4.6F);
        put("rooted_parasitized_zombie", 1.0F, 4.4F);
        put("saltimnes", 1.8F, 1F);
        put("seminaria", 0.8F, 1.0F);
        put("stenodictya_lobata", 0.6F, 0.4F);
        put("stenodictya_nymph", 0.45F, 0.3F);
        put("strobila", 0.95F, 1.2F);
        put("velophyll", 1.2F, 0.8F);
        put("venamnes", 1.2F, 2.4F);
        put("vesicaria", 0.8F, 0.6F);
    }

    public static float width(String id, float fallback) {
        float[] s = SIZES.get(id);
        return s != null ? s[0] : fallback;
    }

    public static float height(String id, float fallback) {
        float[] s = SIZES.get(id);
        return s != null ? s[1] : fallback;
    }
}
