package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * この MOD のダメージタイプ。データ駆動({@code data/overgrown_vegetation/damage_type/*.json})なので
 * ここでは {@link ResourceKey} と {@link DamageSource} の生成だけを持つ。
 *
 * <p>2026-11-04 に胞子感染の 1 つだけで戻した(10-30 に退避した前の版 {@code parked/.../registry/ModDamageTypes.java} は
 * Mob・装置・ボスのダメージも持っていた。戻すときはこの版に足す)。
 */
public final class ModDamageTypes {

    /**
     * 「胞子感染」。プレイヤーの段階が上がるときの 1 ダメージと感染10 の先の死、Mob の暴露量が満ちたときの 20 ダメージ(11-04)。
     * 防具・エンチャント・耐性の効果・盾・無敵時間を貫く({@code data/minecraft/tags/damage_type/bypasses_*.json}・
     * {@code bypasses_cooldown}・{@code no_knockback})。衝撃吸収(黄色いハート)は先に減る。クリエイティブと不死のトーテムは貫かない。
     * 死亡メッセージは {@code death.attack.overgrown_vegetation.spore_infection}。
     */
    public static final ResourceKey<DamageType> SPORE_INFECTION =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("spore_infection"));

    public static DamageSource sporeInfection(ServerLevel level) {
        return level.damageSources().source(SPORE_INFECTION);
    }

    /** ファルキムネスが掴んだ獲物への噛みつき。防具と盾を貫く(09-23 ユーザー指定「防具関係なく」)。 */
    public static final ResourceKey<DamageType> FALCIMNES_BITE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_bite"));

    /**
     * ファルキムネスの掴みの止め。11-05 ユーザー決定で即死をやめ<b>特大ダメージ</b>に。防具は効く(盾だけ貫く —— 掴まれていて構えられない)。
     */
    public static final ResourceKey<DamageType> FALCIMNES_EXECUTION =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_execution"));

    /** ファルキムネスの飛ぶ斬撃(11-05)。防具も盾も効く。 */
    public static final ResourceKey<DamageType> FALCIMNES_WAVE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_wave"));

    /** ファルキムネスの地走りの光の帯(11-05)。防具は効く・盾は貫く(足元から来る)。 */
    public static final ResourceKey<DamageType> FALCIMNES_RIFT =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_rift"));

    /** 飛ぶ斬撃(直接の出どころ = 斬撃。盾の向きの判定に使う)。 */
    public static DamageSource falcimnesWave(ServerLevel level, net.minecraft.world.entity.Entity wave,
                                             @javax.annotation.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(FALCIMNES_WAVE), wave, owner);
    }

    /** 地走りの光の帯。 */
    public static DamageSource falcimnesRift(ServerLevel level, net.minecraft.world.entity.Entity rift,
                                             @javax.annotation.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(FALCIMNES_RIFT), rift, owner);
    }

    public static DamageSource falcimnes(ServerLevel level, ResourceKey<DamageType> type,
                                         net.minecraft.world.entity.Entity attacker) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), attacker);
    }

    /**
     * アクムネスの狙撃・縫い止めの棘(2026-11-15 能力の段)。防具を貫く・盾は効く(11-15 ユーザー確認)。
     * 散弾の小さな棘はバニラの {@code mobProjectile}(防具が効く)。
     */
    public static final ResourceKey<DamageType> ACUMNES_SPINE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("acumnes_spine"));

    /** 狙撃・縫い止めの棘(直接の出どころ = 棘。盾の向きの判定に使う)。 */
    public static DamageSource acumnesSpine(ServerLevel level, net.minecraft.world.entity.Entity spine,
                                            @javax.annotation.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(ACUMNES_SPINE), spine, owner);
    }

    private ModDamageTypes() {
    }
}
