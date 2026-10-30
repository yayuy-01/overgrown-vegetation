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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * バニラのクラスを継がずに作る G1 の感染体(村人・スケルトン・エンダーマン・チビゾンビ、2026-10-09)の共通部分。
 *
 * <p>バニラの村人は脳(Brain)で動き、スケルトンは武器に合わせて Goal を足し直し、エンダーマンはテレポートする —— どれも G1 の
 * 「近くの生き物を追って殴る」Goal を差し込めないか、余計な動きが残る(調査 10-09)。ユーザー指定(10-09)「全員近接でそろえる」
 * (スケルトンは弓を捨てる、エンダーマンはテレポートしない)ので、{@link Monster} から作って {@link ParasitizedTargetGoal#install} だけ持たせる。
 * 日光で燃えない(Monster は燃えない)。死体から起き上がる({@link RisingMob})。
 */
public abstract class AbstractParasitizedMonster extends Monster implements ParasitizedMob, GeoEntity, RisingMob {

    protected static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    protected static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    private static final EntityDataAccessor<Integer> RISING =
            SynchedEntityData.defineId(AbstractParasitizedMonster.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected AbstractParasitizedMonster(EntityType<? extends AbstractParasitizedMonster> type, Level level) {
        super(type, level);
        ParasitizedTargetGoal.install(this);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ 起き上がり
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

    /** 起き上がりの間は AI を止める(Mob#isEffectiveAi が見る)。 */
    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isRising();
    }

    @Override
    public void tick() {
        super.tick();
        RisingSupport.tick(this, RISING);
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

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(RisingSupport.moveController(this, IDLE_ANIM, WALK_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
