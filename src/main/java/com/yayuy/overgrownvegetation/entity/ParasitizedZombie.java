package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生されたゾンビ」— フェーズ4以降の進化型感染 Mob。バニラのゾンビが元々持つ
 * 「プレイヤーだけを狙う」索敵/近接攻撃 Goal は {@link ParasitizedTargetGoal#stripVanillaHostileGoals}
 * で取り除き、代わりに {@link ParasitizedTargetGoal} により他の Mob も種族を問わず見つけ次第攻撃する
 * (攻撃されると「胞子感染2」に感染する)。ユーザー要望により日光では燃えない({@link #isSunBurnTick}）。
 *
 * <p>見た目は GeckoLib 経由の専用モデル(Blockbench 製、寄生植物が生えたゾンビ。{@link ParasitizedSheep} と
 * 同じ構成)に差し替えている。モデル/アニメーション/テクスチャの実体は
 * {@code assets/overgrown_vegetation/geo/overgrown_zombie.geo.json}・
 * {@code animations/overgrown_zombie.animation.json}・
 * {@code textures/entity/overgrown_zombie.png}（モデル・テクスチャはユーザー提供の本物。
 * アニメーションはバニラのゾンビに準じた idle/walk を Claude が作成、体から生えたシダ
 * ({@code fern1}〜{@code fern6})には待機中・歩行中とも風で揺れる緩やかなスウェイを付けている）。
 * 差し替え時はこのクラスの変更は不要（アニメーション名を変える場合は下の
 * {@code IDLE_ANIM}/{@code WALK_ANIM} だけ合わせる）。
 *
 * <p>子供では絶対にスポーンしない({@link #finalizeSpawn} で {@code setBaby(false)})。バニラのゾンビは
 * 約5%の確率で子供になり、子ゾンビは移動速度 +50%(さらに乱数で最大 +5%)を持つ。加えて
 * {@link ParasitizedTargetGoal#install} は追跡速度の倍率をコンストラクタ時点(=大人)の移動速度から
 * 逆算するため、後から子供補正が乗ると追跡速度が想定を大きく超えてしまう。GeckoLib モデルは
 * 子供でも縮小表示しないので、見た目は大人と区別が付かないまま急に俊足になる — これを防ぐため。
 */
public class ParasitizedZombie extends Zombie implements ParasitizedMob, GeoEntity, com.yayuy.overgrownvegetation.corpse.RisingMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    /** 死体から起き上がる残り tick(2026-10-09、corpse.RisingMob)。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> RISING =
            net.minecraft.network.syncher.SynchedEntityData.defineId(ParasitizedZombie.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedZombie(EntityType<? extends ParasitizedZombie> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripVanillaHostileGoals(this);
        ParasitizedTargetGoal.install(this);
    }

    @Override
    protected boolean isSunBurnTick() {
        // 「過繁茂した(MOB名)」は日光で燃えない(ユーザー要望)。
        return false;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        // 子ゾンビ(俊足・GeckoLib では縮小表示されない)にはならない。理由はクラス javadoc 参照。
        this.setBaby(false);
        return data;
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
