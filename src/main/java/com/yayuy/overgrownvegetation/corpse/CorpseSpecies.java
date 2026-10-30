package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 「崩れ落ち → 死体 → 起き上がり」に対応した死体ブロックと、その種の名前(2026-10-09)。
 *
 * <p>種の名前 = 感染体のテクスチャ・geo の名前(例 {@code overgrown_villager})。クライアントはこの名前で
 * 崩れ落ちの姿勢 {@code assets/overgrown_vegetation/corpse_poses/<名前>.json}(生成器 {@code tools/g5-models/mobs/G1*.java} の生成物)を読む。
 * ここに無い死体ブロックは今までどおり、死んだ瞬間にその場へ置く(作り直しの途中の種)。
 */
public final class CorpseSpecies {

    private CorpseSpecies() {
    }

    /**
     * 崩れ落ち・起き上がりに対応した種(死体ブロックは {@code <種>_corpse})。素材(geo・アニメの rise・corpse_poses)を
     * {@code tools/g5-models/g1/install.sh} で入れた種だけを足す(rise の無い古いアニメで起き上がらせない)。
     */
    private static final String[] READY = {
            "overgrown_villager", "overgrown_zombie", "overgrown_baby_zombie", "overgrown_cow", "overgrown_pig", "overgrown_sheep",
            "overgrown_chicken", "overgrown_creeper", "overgrown_skeleton", "overgrown_spider", "overgrown_enderman",
            // ネザーの媒介種(2026-10-09)
            "overgrown_piglin", "overgrown_piglin_brute", "overgrown_zombified_piglin", "overgrown_wither_skeleton",
            "overgrown_hoglin", "overgrown_zoglin", "overgrown_strider",
            // アイアンゴーレム・ウォーデン(2026-10-10)
            "overgrown_iron_golem", "overgrown_warden"};

    private static Map<Block, String> map;

    private static Map<Block, String> map() {
        if (map == null) {
            Map<Block, String> m = new java.util.HashMap<>();
            for (String id : READY) {
                m.put(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                        com.yayuy.overgrownvegetation.OvergrownVegetation.id(id + "_corpse")), id);
            }
            map = m;
        }
        return map;
    }

    /** 崩れ落ちに対応した死体ブロックなら種の名前、でなければ null。 */
    @Nullable
    public static String speciesOf(Block corpseBlock) {
        return map().get(corpseBlock);
    }
}
