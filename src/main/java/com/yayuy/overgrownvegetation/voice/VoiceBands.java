package com.yayuy.overgrownvegetation.voice;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Set;

/**
 * 声を汚すときに通す帯(中型 350〜2600 Hz / 小型 700〜4200 Hz)の表(2026-11-14)。帯の選び方はここ 1 か所にまとめる。
 * <ul>
 *     <li>G1: 種ごとの表 {@link #small(G1Species)}(ユーザー指定: 小型は鶏・チビゾンビ)。</li>
 *     <li>汎用(将来の非対応 Mob 用の汎用感染システム): 元の Mob の種類 {@link #small(EntityType, boolean)}、
 *         知らない種類は体の大きさ {@link #small(Entity)} で決める。</li>
 * </ul>
 */
public final class VoiceBands {

    /** 小型の帯で汚す元の Mob の種類【仮】(高い声の小さな生き物)。子どもは種類によらず小型。 */
    private static final Set<EntityType<?>> SMALL_HOSTS = Set.of(
            EntityType.CHICKEN, EntityType.BAT, EntityType.RABBIT, EntityType.PARROT, EntityType.SILVERFISH,
            EntityType.ENDERMITE, EntityType.ALLAY, EntityType.VEX, EntityType.BEE, EntityType.FROG, EntityType.TADPOLE);

    /** 大きさで決めるときの境目【仮】: 当たり判定の幅 × 高さがこれより小さければ小型(鶏 0.4 × 0.7 = 0.28)。 */
    private static final float SMALL_AREA = 0.36F;

    private VoiceBands() {
    }

    /** G1 の種ごとの帯。段階 0 も 1 段目(元の段階 0 の種)もこれで決める。 */
    public static boolean small(G1Species species) {
        return switch (species) {
            case CHICKEN, BABY_ZOMBIE -> true;
            case ZOMBIE, VILLAGER, COW, PIG, SHEEP, CREEPER, SKELETON, SPIDER, ENDERMAN, IRON_GOLEM, WARDEN -> false;
        };
    }

    /** 元の Mob の種類で決める。 */
    public static boolean small(EntityType<?> host, boolean baby) {
        return baby || SMALL_HOSTS.contains(host);
    }

    /** 知らない Mob: 種類の表 → 無ければ体の大きさ。 */
    public static boolean small(Entity entity) {
        boolean baby = entity instanceof LivingEntity living && living.isBaby();
        if (small(entity.getType(), baby)) {
            return true;
        }
        return entity.getBbWidth() * entity.getBbHeight() < SMALL_AREA;
    }
}
