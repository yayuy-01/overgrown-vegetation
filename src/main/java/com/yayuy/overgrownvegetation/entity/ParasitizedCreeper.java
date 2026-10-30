package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生されたクリーパー」— フェーズ4以降の進化型感染 Mob。バニラのクリーパーが元々持つ
 * 「プレイヤーだけを狙う」索敵 Goal は {@link ParasitizedTargetGoal#stripVanillaHostileGoals} で
 * 取り除く(自爆 Goal {@code SwellGoal} と膨張演出はそのまま残る)。代わりに
 * {@link ParasitizedTargetGoal} により近接攻撃と種族を問わない索敵を行うようになる
 * (攻撃されると「胞子感染2」に感染する)。
 *
 * <p>爆発の中身だけは {@link com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation} が
 * 差し替える — バニラの地形破壊爆発ではなく「地形は壊さず、周囲の Mob を胞子感染させ、周囲を
 * ノイズ入りの形で汚染する」独自の爆発になる。
 *
 * <p>見た目は GeckoLib 経由の専用モデル(Blockbench 製、寄生植物が生えたクリーパー。
 * {@link ParasitizedZombie} と同じ構成)に差し替えている。モデル/アニメーション/テクスチャの実体は
 * {@code assets/overgrown_vegetation/geo/overgrown_creeper.geo.json}・
 * {@code animations/overgrown_creeper.animation.json}・
 * {@code textures/entity/overgrown_creeper.png}(モデル・テクスチャはユーザー提供の本物。
 * アニメーションはバニラのクリーパーに準じた idle/walk を Claude が作成、体から生えたシダ
 * ({@code fern1}〜{@code fern6})には待機中・歩行中とも風で揺れる緩やかなスウェイを付けている)。
 * 差し替え時はこのクラスの変更は不要(アニメーション名を変える場合は下の
 * {@code IDLE_ANIM}/{@code WALK_ANIM} だけ合わせる)。
 */
public class ParasitizedCreeper extends Creeper implements ParasitizedMob, GeoEntity, com.yayuy.overgrownvegetation.corpse.RisingMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    /** 死体から起き上がる残り tick(2026-10-09、corpse.RisingMob)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> RISING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(ParasitizedCreeper.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    /**
     * 近接攻撃 Goal の優先度。バニラのクリーパーに合わせて 4(導火線 Goal {@code SwellGoal} の
     * 優先度 2 より<b>下</b>)にしておく必要がある — 既定の 2 のままだと {@code SwellGoal} と
     * 同じ優先度になり、先に走り出した近接攻撃から制御を奪えず永久に自爆しなくなる
     * ({@link ParasitizedTargetGoal#install(net.minecraft.world.entity.PathfinderMob, int)} 参照)。
     */
    private static final int MELEE_PRIORITY = 4;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedCreeper(EntityType<? extends ParasitizedCreeper> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripVanillaHostileGoals(this);
        ParasitizedTargetGoal.install(this, MELEE_PRIORITY);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(com.yayuy.overgrownvegetation.corpse.RisingSupport.moveController(this, IDLE_ANIM, WALK_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
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
