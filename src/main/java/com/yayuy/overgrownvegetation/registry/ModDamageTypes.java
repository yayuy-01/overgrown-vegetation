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
 */
public final class ModDamageTypes {

    /**
     * 「胞子感染」の満了で死亡(胞子感染3 が切れたプレイヤー)。防具・効果・エンチャントを貫通する
     * ({@code tags/damage_type/bypasses_*.json})が、クリエイティブ/不死のトーテムは貫通しない。
     * 死亡メッセージは {@code death.attack.overgrown_vegetation.spore_infection}。
     */
    public static final ResourceKey<DamageType> SPORE_INFECTION =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("spore_infection"));

    public static DamageSource sporeInfection(ServerLevel level) {
        return level.damageSources().source(SPORE_INFECTION);
    }

    /**
     * 機関砲台の弾(2026-09-21、{@code block/GunTurretBlockEntity})。防具で軽減される普通のダメージ。
     * 死亡メッセージは {@code death.attack.overgrown_vegetation.autocannon}。
     */
    public static final ResourceKey<DamageType> AUTOCANNON =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("autocannon"));

    public static DamageSource autocannon(ServerLevel level) {
        return level.damageSources().source(AUTOCANNON);
    }

    /** 有刺鉄線(2026-09-19、{@code block/BarbedWireBlock})。防具で軽減される普通の接触ダメージ。 */
    public static final ResourceKey<DamageType> BARBED_WIRE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("barbed_wire"));

    public static DamageSource barbedWire(ServerLevel level) {
        return level.damageSources().source(BARBED_WIRE);
    }

    /**
     * 「水ぐされ」({@link ModMobEffects#WATER_ROT})の毎秒ダメージ。防具・効果・エンチャントを貫通する
     * ({@code tags/damage_type/bypasses_*.json}) —— 「毎秒 1」という設計値をそのまま通したいので、
     * 防具値や耐性で目減りさせない。死亡メッセージは {@code death.attack.overgrown_vegetation.water_rot}。
     *
     * <p><b>ノックバックしない</b>({@code tags/damage_type/no_knockback.json}、ユーザー指定 2026-09-17)。
     * 1.21 の {@code LivingEntity#hurt} は、加害者のいないダメージでも {@code minecraft:no_knockback} に
     * 入っていなければ {@code knockback(0.4F, 0, 0)} を通す —— 向きが無いので横には飛ばないが、
     * <b>横の速度が毎回半分になり、接地していれば上に跳ね上がる</b>。毎秒入るダメージでこれをやられると
     * 水から上がろうとしている Mob({@link com.yayuy.overgrownvegetation.entity.EscapeWaterGoal})が
     * その場で跳ねるだけになってしまうので、タグで切ってある。
     */
    public static final ResourceKey<DamageType> WATER_ROT =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("water_rot"));

    public static DamageSource waterRot(ServerLevel level) {
        return level.damageSources().source(WATER_ROT);
    }

    /**
     * 攻城戦ボス(ドルガ=アーケロウス・ティタニカ)の花のビーム・触手ビーム。防具を貫通する
     * ({@code tags/damage_type/bypasses_armor.json})。死亡メッセージは
     * {@code death.attack.overgrown_vegetation.siege_beam}。
     */
    public static final ResourceKey<DamageType> SIEGE_BEAM =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("siege_beam"));

    public static DamageSource siegeBeam(ServerLevel level) {
        return level.damageSources().source(SIEGE_BEAM);
    }

    /**
     * 砲台の砲弾の直撃。防具は貫通<b>しない</b> — ボスに対しては {@code siegeArmor} のしきい値が
     * 差し引かれる({@link com.yayuy.overgrownvegetation.siege.SiegeData#hurt(ServerLevel, float, DamageSource)})。
     * 死亡メッセージは {@code death.attack.overgrown_vegetation.siege_shell}。
     */
    public static final ResourceKey<DamageType> SIEGE_SHELL =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("siege_shell"));

    public static DamageSource siegeShell(ServerLevel level) {
        return level.damageSources().source(SIEGE_SHELL);
    }

    /**
     * 攻城戦ボスの<b>胞子爆弾</b>(触手から落とすものと、山なりに曲射してくるものの両方)の爆発と直撃。
     * 防具は貫通しない。死亡メッセージは {@code death.attack.overgrown_vegetation.siege_bomb}。
     *
     * <p>爆発にわざわざ専用のダメージタイプを与えているのは、「ボスの攻撃で死んだ」ことを
     * あとから見分けられるようにするため({@link com.yayuy.overgrownvegetation.ModTags#SIEGE_BOSS_ATTACK})。
     * 既定の爆発ダメージのままだと TNT で死んだ場合と区別がつかない。
     */
    public static final ResourceKey<DamageType> SIEGE_BOMB =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("siege_bomb"));

    public static DamageSource siegeBomb(ServerLevel level) {
        return level.damageSources().source(SIEGE_BOMB);
    }

    /**
     * フィロセンス({@link com.yayuy.overgrownvegetation.entity.PhyllosensEntity})のビーム。
     * ティタニカのものと違い<b>防具は貫通しない</b>。攻撃した本人が残るので、死亡メッセージには
     * 相手の名前が出る({@code death.attack.overgrown_vegetation.phyllosens_beam.player} 系)。
     */
    public static final ResourceKey<DamageType> PHYLLOSENS_BEAM =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("phyllosens_beam"));

    public static DamageSource phyllosensBeam(ServerLevel level, net.minecraft.world.entity.Entity source) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(PHYLLOSENS_BEAM), source);
    }

    /**
     * ファルキムネスが掴んだ獲物への噛みつき(2026-09-23)。<b>防具を貫通する</b>(ユーザー指定「防具関係なく」)。
     * エンチャントと耐性エフェクトは効く —— 止めを刺すのは {@link #FALCIMNES_EXECUTION} のほう。
     */
    public static final ResourceKey<DamageType> FALCIMNES_BITE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_bite"));

    /**
     * ファルキムネスの掴みの止め(2026-09-23)。防具・エンチャント・耐性エフェクトをすべて無視して即死させる。
     * <b>不死のトーテムは効く</b>({@code bypasses_invulnerability} は付けていない。{@code /kill} と同じ扱いにはしない)。
     */
    public static final ResourceKey<DamageType> FALCIMNES_EXECUTION =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("falcimnes_execution"));

    public static DamageSource falcimnes(ServerLevel level, ResourceKey<DamageType> type,
                                         net.minecraft.world.entity.Entity attacker) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), attacker);
    }

    /**
     * ヴェロフィル({@link com.yayuy.overgrownvegetation.entity.VelophyllEntity})が落とす胞子爆弾。
     * 爆風も直撃も同じダメージタイプ。<b>防具は効く</b>。
     *
     * <p>爆発ではなく通常ダメージとして入れているのは、「爆風は一律 12・直撃は 45」という
     * 距離で減衰しない配り方をしているため({@link com.yayuy.overgrownvegetation.entity.VelophyllBombEntity} 参照)。
     */
    public static final ResourceKey<DamageType> VELOPHYLL_BOMB =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("velophyll_bomb"));

    /**
     * @param bomb   爆弾そのもの(直接の加害者)
     * @param owner  落とした Mob。消えていれば {@code null} で、その場合は死亡メッセージが
     *               相手の名前を出さない形になる。
     */
    public static DamageSource velophyllBomb(ServerLevel level, net.minecraft.world.entity.Entity bomb,
                                             @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(VELOPHYLL_BOMB), bomb, owner);
    }

    private ModDamageTypes() {
    }
}
