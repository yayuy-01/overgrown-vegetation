package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.RisingMob;
import com.yayuy.overgrownvegetation.corpse.RisingSupport;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 「寄生された鶏」(G1、2026-10-09)。{@link ParasitizedCow} と同じく動物の習性(逃げる・繁殖・餌)を取り除き、近くの生き物を追って突く
 * (ユーザー指定 10-09「全員近接でそろえる」)。卵は産まない。落ちるときの羽ばたき(ゆっくり落ちる)はバニラのまま。
 * モデル・絵・アニメは生成器 {@code tools/g5-models/mobs/G1Chicken.java}。
 */
public class ParasitizedChicken extends Chicken implements ParasitizedMob, GeoEntity, RisingMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final EntityDataAccessor<Integer> RISING =
            SynchedEntityData.defineId(ParasitizedChicken.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ParasitizedChicken(EntityType<? extends ParasitizedChicken> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.stripPassiveAnimalGoals(this);
        ParasitizedTargetGoal.install(this);
    }

    @Override
    public void tick() {
        // 卵を産まない(バニラの鶏は eggTime が 0 になると産む)
        this.eggTime = Integer.MAX_VALUE;
        super.tick();
        RisingSupport.tick(this, RISING);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
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

    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isRising();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Rising", com.yayuy.overgrownvegetation.corpse.RisingSupport.remaining(this, RISING)); // 正確な残り(RISING は段階だけ、2026-10-10)
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(RISING, tag.getInt("Rising"));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(RisingSupport.moveController(this, IDLE_ANIM, WALK_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
