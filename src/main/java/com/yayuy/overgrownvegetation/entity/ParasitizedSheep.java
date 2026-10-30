package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生された羊」— フェーズ4以降の進化型感染 Mob。敵対化しており他の Mob や
 * プレイヤーを見つけ次第攻撃する({@link ParasitizedTargetGoal} 参照。攻撃されると「胞子感染2」に感染する)。
 *
 * <p>バニラの {@link Sheep} が持つ平和的な習性(殴られて逃げる/繁殖/餌でおびき寄せられる/親について歩く)は
 * 「ゾンビなどと同じ完全な敵対 Mob」というユーザー要望に反するため全て取り除いている
 * ({@link ParasitizedTargetGoal#stripPassiveAnimalGoals}）。草を食べて毛を再生する
 * {@code eatBlockGoal} は敵対性に直接関わらない資源系の習性なので残してある。
 *
 * <p>見た目は GeckoLib 経由の専用モデル(Blockbench 製、寄生植物が生えた羊。{@link ParasitizedPig} と
 * 同じ構成)に差し替えている。モデル/アニメーション/テクスチャの実体は
 * {@code assets/overgrown_vegetation/geo/overgrown_sheep.geo.json}・
 * {@code animations/overgrown_sheep.animation.json}・
 * {@code textures/entity/overgrown_sheep.png}（モデル・テクスチャはユーザー提供の本物。
 * アニメーションはバニラの羊に準じた idle/walk を Claude が作成、体から生えたシダ
 * ({@code fern1}〜{@code fern3})には待機中に風で揺れる緩やかなスウェイを付けている）。
 * 差し替え時はこのクラスの変更は不要（アニメーション名を変える場合は下の
 * {@code IDLE_ANIM}/{@code WALK_ANIM} だけ合わせる）。
 */
public class ParasitizedSheep extends Sheep implements ParasitizedMob, GeoEntity, com.yayuy.overgrownvegetation.corpse.RisingMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    /** 死体から起き上がる残り tick(2026-10-09、corpse.RisingMob)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> RISING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(ParasitizedSheep.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedSheep(EntityType<? extends ParasitizedSheep> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripPassiveAnimalGoals(this);
        ParasitizedTargetGoal.install(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(com.yayuy.overgrownvegetation.corpse.RisingSupport.moveController(this, IDLE_ANIM, WALK_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /**
     * 毛を刈られていなくても自分のドロップ表({@code loot_table/entities/parasitized_sheep.json})を使う(順 1 の着手順 11)。
     * バニラの羊は、刈られていないと毛の色ごとのバニラの表(羊毛 + 羊肉)に切り替えるので、汚染された羊毛・肉が落ちなかった。
     * 刈ったときに落ちる羊毛はバニラのまま({@code shear} は触らない)。
     */
    @Override
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable> getDefaultLootTable() {
        return this.getType().getDefaultLootTable();
    }

    // ------------------------------------------------------------------ 死体から起き上がる(2026-10-09)
    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(RISING, 0);
    }

    @Override
    public void startRising(int ticks) {
        this.entityData.set(RISING, ticks);
    }

    @Override
    public boolean isRising() {
        return this.entityData.get(RISING) > 0;
    }

    /** 起き上がりの間は AI を止める。 */
    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isRising();
    }

    @Override
    public void tick() {
        super.tick();
        com.yayuy.overgrownvegetation.corpse.RisingSupport.tick(this, RISING);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Rising", com.yayuy.overgrownvegetation.corpse.RisingSupport.remaining(this, RISING)); // 正確な残り(RISING は段階だけ、2026-10-10)
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(RISING, tag.getInt("Rising"));
    }
}
