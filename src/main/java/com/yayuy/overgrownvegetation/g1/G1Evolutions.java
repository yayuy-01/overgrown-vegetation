package com.yayuy.overgrownvegetation.g1;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * G1 の進化の表(G1 の作り直し 第 1 段、2026-10-22。最初に通す 4 種 = ゾンビ・村人・牛・クリーパー、ユーザー決定)。
 * 段階 0 → 1 段目(G1 のまま)→ 2 段目(能力で分類: ゾンビ・村人 = G5、牛・クリーパー = G4。分類の正本はタグ group_N.json)。
 */
public final class G1Evolutions {

    private static Map<EntityType<?>, EntityType<? extends Mob>> next;
    private static Map<EntityType<?>, Integer> stage;

    private static void init() {
        if (next != null) return;
        Map<EntityType<?>, EntityType<? extends Mob>> n = new HashMap<>();
        Map<EntityType<?>, Integer> s = new HashMap<>();
        link(n, s, ModEntities.PARASITIZED_ZOMBIE.get(), ModEntities.VINEARM_ZOMBIE.get(), ModEntities.VINE_EXECUTIONER.get());
        link(n, s, ModEntities.PARASITIZED_VILLAGER.get(), ModEntities.HOLLOW_VILLAGER.get(), ModEntities.ROOT_STRIKER.get());
        link(n, s, ModEntities.PARASITIZED_COW.get(), ModEntities.SPORESAC_COW.get(), ModEntities.WALKING_SPORANGIUM.get());
        link(n, s, ModEntities.PARASITIZED_CREEPER.get(), ModEntities.PODDED_CREEPER.get(), ModEntities.BURST_POD.get());
        stage = s;
        next = n;
    }

    private static void link(Map<EntityType<?>, EntityType<? extends Mob>> n, Map<EntityType<?>, Integer> s,
                             EntityType<?> base, EntityType<? extends Mob> first, EntityType<? extends Mob> second) {
        n.put(base, first);
        n.put(first, second);
        s.put(base, 0);
        s.put(first, 1);
        s.put(second, 2);
    }

    /** 次の段の種。進化体の無い種・2 段目は null。 */
    @Nullable
    public static EntityType<? extends Mob> next(EntityType<?> type) {
        init();
        return next.get(type);
    }

    /** 進化の段(0 = 進化体のある種の段階 0、1、2)。表に無い種は -1。 */
    public static int stageOf(EntityType<?> type) {
        init();
        return stage.getOrDefault(type, -1);
    }

    private G1Evolutions() {
    }
}
