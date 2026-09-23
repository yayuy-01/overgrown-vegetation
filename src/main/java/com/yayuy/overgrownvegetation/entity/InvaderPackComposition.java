package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>群れの編成</b>(ユーザー指定 2026-09-15):
 * アルボラ 8 / スポロフィル 2 / フィロセンス 1 / ヴェロフィル 3 / スカトゥリウム 2。
 *
 * <p><b>これは最低数であって上限ではない。</b> 足りない種があるあいだは
 * {@link InvaderPackGoal} が遠くの仲間まで迎えに行き({@code invaderPackRecruitRange})、
 * 揃ってからそろって動き出す。揃ったあとも近くに来た個体はそのまま群れに加わるので、
 * <b>編成より多い群れになることはある</b>(その場合に誰かを追い出したりはしない)。
 *
 * <h2>数え方</h2>
 * 「群れ」は {@code invaderPackScanRange}(既定 48)以内に居るグループ4 の一覧で、
 * <b>自分も含めて</b>種ごとに数える。飛んでいるヴェロフィルも数に入る ——
 * 重心の計算({@link InvaderPack#centroid})では高さが桁違いなので外しているが、
 * <b>編成としては立派な一員</b>なので、そちらとは別扱いにしてある。
 *
 * <h2>種を増やすとき</h2>
 * 新しいグループ4 を編成に加えるなら、{@code invaderPackMin...} を 1 つ足して
 * {@link #required()} に並べるだけでよい。ここに載っていない種は
 * 「居ても構わないが、揃うのを待つ対象ではない」という扱いになる。
 */
public final class InvaderPackComposition {

    /** 編成表(種 → 最低数)。0 以下の種は載せない。 */
    public static Map<EntityType<?>, Integer> required() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        Map<EntityType<?>, Integer> required = new LinkedHashMap<>();
        put(required, ModEntities.ARBORA.get(), cfg.invaderPackMinArbora.get());
        put(required, ModEntities.SPOROPHYLL.get(), cfg.invaderPackMinSporophyll.get());
        put(required, ModEntities.PHYLLOSENS.get(), cfg.invaderPackMinPhyllosens.get());
        put(required, ModEntities.VELOPHYLL.get(), cfg.invaderPackMinVelophyll.get());
        put(required, ModEntities.OVERGROWN_SHIELDER.get(), cfg.invaderPackMinShielder.get());
        return required;
    }

    private static void put(Map<EntityType<?>, Integer> map, EntityType<?> type, int count) {
        if (count > 0) {
            map.put(type, count);
        }
    }

    /**
     * 編成に対して<b>足りていない種と不足数</b>。すべて足りていれば空。
     *
     * @param self    数える本人(自分も 1 体として数える)
     * @param members {@code self} の周りの仲間({@link InvaderPack#membersNear})
     */
    public static Map<EntityType<?>, Integer> shortfall(Mob self, List<Mob> members) {
        Map<EntityType<?>, Integer> required = required();
        if (required.isEmpty()) {
            return Map.of();
        }
        Map<EntityType<?>, Integer> have = new HashMap<>();
        have.merge(self.getType(), 1, Integer::sum);
        for (Mob member : members) {
            have.merge(member.getType(), 1, Integer::sum);
        }
        Map<EntityType<?>, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<EntityType<?>, Integer> entry : required.entrySet()) {
            int lack = entry.getValue() - have.getOrDefault(entry.getKey(), 0);
            if (lack > 0) {
                missing.put(entry.getKey(), lack);
            }
        }
        return missing;
    }

    /** 編成が満たされているか(多いぶんには満たされている扱い)。 */
    public static boolean isComplete(Mob self, List<Mob> members) {
        return shortfall(self, members).isEmpty();
    }

    private InvaderPackComposition() {
    }
}
