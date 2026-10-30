package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;

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
     * 積乱雲の中を走る雷に触れた(2026-10-17 ユーザー指定「触れたら即死」。{@link com.yayuy.overgrownvegetation.sky.CloudLightning})。
     * 防具・効果・エンチャント・耐性を貫通する({@code tags/damage_type/bypasses_*.json})が、クリエイティブと不死のトーテムは貫通しない(仮決め)。
     * 死亡メッセージは {@code death.attack.overgrown_vegetation.cloud_lightning}。
     */
    public static final ResourceKey<DamageType> CLOUD_LIGHTNING =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("cloud_lightning"));

    public static DamageSource cloudLightning(ServerLevel level) {
        return level.damageSources().source(CLOUD_LIGHTNING);
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

    /**
     * 撃った点(砲耳)つきの機関砲台の弾(2026-09-24)。スクティムネスの盾が「正面から撃たれたか」を見分けるのに使う
     * ({@code entity/ScutimnesEntity#hurt}。機関砲台の弾も飛び道具として正面なら弾く)。
     *
     * <p>点はバニラの {@link DamageSource#getSourcePosition()} には入れず、{@link AutocannonShot#origin()} でだけ渡す。
     * 入れると {@code LivingEntity#hurt} のノックバックが「撃った点から遠ざかる向き」になり、当たるたびに相手を砲台から押し返す
     * (いまは向きの無いノックバックで、その場で小さく跳ねるだけ)—— 砲台の強さが変わってしまうので、ほかの挙動は今のまま。
     */
    public static DamageSource autocannon(ServerLevel level, Vec3 from) {
        return new AutocannonShot(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(AUTOCANNON), from);
    }

    /** 撃った点を持つ機関砲台の弾({@link #autocannon(ServerLevel, Vec3)})。種類・加害者・直接の出どころは位置なしのものと同じ。 */
    public static final class AutocannonShot extends DamageSource {
        private final Vec3 origin;

        AutocannonShot(Holder<DamageType> type, Vec3 origin) {
            super(type);
            this.origin = origin;
        }

        /** 弾が出た点(砲耳、ワールド座標)。 */
        public Vec3 origin() {
            return this.origin;
        }
    }

    /** 有刺鉄線(2026-09-19、{@code block/BarbedWireBlock})。防具で軽減される普通の接触ダメージ。 */
    public static final ResourceKey<DamageType> BARBED_WIRE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("barbed_wire"));

    public static DamageSource barbedWire(ServerLevel level) {
        return level.damageSources().source(BARBED_WIRE);
    }

    /** 逆茂木(2026-10-16、{@code block/AbatisBlock})。有刺鉄線と同じ、防具で軽減される普通の接触ダメージ。 */
    public static final ResourceKey<DamageType> ABATIS =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("abatis"));

    public static DamageSource abatis(ServerLevel level) {
        return level.damageSources().source(ABATIS);
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
     * 空の巨大生物の体にぶつかった(2026-10-18 ユーザー指定「ぶつかるとダメージを受けて弾かれる」。{@link com.yayuy.overgrownvegetation.skyboss.SkyBossCombat})。
     * 防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss}。
     */
    public static final ResourceKey<DamageType> SKY_BOSS =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss"));

    public static DamageSource skyBoss(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS);
    }

    /** 空の巨大生物の技: 対空砲火の蕾の胞子の塊(直撃・炸裂)(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_spore}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_SPORE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_spore"));

    public static DamageSource skyBossSpore(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_SPORE);
    }

    /** 空の巨大生物の技: 背の腺の樹液の弾(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_sap}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_SAP =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_sap"));

    public static DamageSource skyBossSap(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_SAP);
    }

    /** 空の巨大生物の技: 羽ばたきの下降気流(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_gust}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_GUST =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_gust"));

    public static DamageSource skyBossGust(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_GUST);
    }

    /** 空の巨大生物の技: 腹の触手で打つ・絡め取る(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_lash}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_LASH =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_lash"));

    public static DamageSource skyBossLash(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_LASH);
    }

    /** 空の巨大生物の技: 種の爆弾(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_seed}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_SEED =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_seed"));

    public static DamageSource skyBossSeed(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_SEED);
    }

    /** 空の巨大生物の技: 転がりながらのビーム(大技 1)(10-19)。防具で軽減される普通のダメージ。死亡メッセージは {@code death.attack.overgrown_vegetation.sky_boss_beam}。 */
    public static final ResourceKey<DamageType> SKY_BOSS_BEAM =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("sky_boss_beam"));

    public static DamageSource skyBossBeam(ServerLevel level) {
        return level.damageSources().source(SKY_BOSS_BEAM);
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
     * アクムネス({@link com.yayuy.overgrownvegetation.entity.AcumnesEntity})が撃つ棘(2026-09-28)。
     * <b>防具を貫通する</b>(採用時の案「防具を貫通する棘」、{@code bypasses_armor})。飛び道具なので
     * ({@code is_projectile})盾で防げ、飛び道具耐性のエンチャントも効く —— 対処は「遮蔽物で射線を切る」のほかに盾でもよい(仮)。
     * 直接の加害者は棘、元の加害者はアクムネス(死亡メッセージに名前が出る)。
     */
    public static final ResourceKey<DamageType> ACUMNES_SPINE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("acumnes_spine"));

    public static DamageSource acumnesSpine(ServerLevel level, net.minecraft.world.entity.Entity spine,
                                            @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(ACUMNES_SPINE), spine, owner);
    }

    /**
     * ヴォラムネス({@link com.yayuy.overgrownvegetation.entity.VoramnesEntity})の胃の中の継続ダメージ(2026-10-01 足場)。
     * <b>防具を貫通する</b>({@code bypasses_armor}。採用の案「呑まれると内部で継続ダメージ」)。胃の中で盾は構えられないので
     * {@code bypasses_shield}、揺さぶらないよう {@code no_knockback}(この 2 つは仮)。エンチャントと耐性エフェクトは効く。
     * 加害者は呑んだヴォラムネス(死亡メッセージに名前が出る)。
     */
    public static final ResourceKey<DamageType> VORAMNES_DIGEST =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("voramnes_digest"));

    /** @param swallower 呑んだ本人(ヴォラムネス) */
    public static DamageSource voramnesDigest(ServerLevel level, net.minecraft.world.entity.Entity swallower) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(VORAMNES_DIGEST), swallower);
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

    /**
     * ネザーの媒介種(寄生されたガスト・ブレイズ、2026-10-09)が<b>撃った</b>胞子爆弾。効き方はヴェロフィルの爆弾と同じ
     * (距離で減衰しない通常ダメージ・防具は効く)で、死亡メッセージだけ「落とした」ではなく「撃った」。
     */
    public static final ResourceKey<DamageType> SPORE_BOMB_SHOT =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("spore_bomb_shot"));

    public static DamageSource sporeBombShot(ServerLevel level, net.minecraft.world.entity.Entity bomb,
                                             @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity owner) {
        return new DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SPORE_BOMB_SHOT), bomb, owner);
    }

    private ModDamageTypes() {
    }
}
