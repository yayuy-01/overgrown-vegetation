package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 「スペラエオプレウラ」— 洞窟に棲むアースロプレウラの近縁(グループ2・原生生産種)。
 *
 * <h2>発光菌糸を食べると光る</h2>
 * <p>この種の肝。{@link ModBlocks#GLOWING_MYCELIUM 発光菌糸}を食べると背の血管状の筋が光り、
 * 時間をかけて暗くなる。<b>群れが洞窟を移動すると、食べた個体だけが明るい「光の列」</b>になる。
 *
 * <p>明るさは {@link #getGlow()}(0〜1、クライアントへ同期)で、描画側の
 * {@code SpelaeopleuraGlowLayer} が発光テクスチャを重ねる濃さに使う。
 *
 * <h2>なぜ周期的な明滅にしなかったのか</h2>
 * <p>ハイヴノードのように <b>mcmeta の駒送り</b>で光らせる手もあった(10 秒周期で往復する)。
 * だがそれだと<b>何も食べていなくても決まった周期で光る</b> —— 「食べたから光る」という
 * この種の設定が画面に出ない。明るさを状態で持たせたのはそのため(ユーザー指定 2026-09-21)。
 *
 * <h2>プレイヤーを襲わない</h2>
 * <p>{@link AbstractOvergrownCritterEntity} の既定どおり、索敵も近接攻撃も持たない。
 * 殴られたら逃げ、グループ3 の捕食者からは距離を取る。
 *
 * <h2>アニメーション名(GeckoLib、差し替え用)</h2>
 * {@code idle} / {@code walk}。
 */
public class SpelaeopleuraEntity extends AbstractOvergrownCritterEntity {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");

    /**
     * 発光量 0〜1。<b>描画のためだけに同期する</b>ので、精度は float で足りる。
     * 蓄えそのもの({@link #litTicks})はサーバー側だけが持つ。
     */
    private static final EntityDataAccessor<Float> DATA_GLOW =
            SynchedEntityData.defineId(SpelaeopleuraEntity.class, EntityDataSerializers.FLOAT);

    /** 1 回の食事で光っていられる時間。既定 90 秒。 */
    private static final int LIT_TICKS = 1800;
    /**
     * 明るさが 0 → 1 に届くまでの時間(tick)。<b>ゆっくり切り替わる</b>ための値
     * (ユーザー指定 2026-09-21)。既定 3 秒。
     */
    private static final int RAMP_UP_TICKS = 60;
    /**
     * 明るさが 1 → 0 に落ちるまでの時間(tick)。上がるより<b>遅く</b>落とす ——
     * 生き物の発光は、点くのは早く消えるのは遅い。既定 8 秒。
     */
    private static final int RAMP_DOWN_TICKS = 160;
    /** 発光菌糸をこの距離まで近づいたら食べる。 */
    private static final double FEED_REACH_SQ = 4.0D;
    /** 食事の間隔(食べ続けて光りっぱなしにならないように)。 */
    private static final int FEED_COOLDOWN = 200;

    /** 残りの「光っている」時間。0 になると暗くなっていく。保存する。 */
    private int litTicks;
    private int feedCooldown;

    public SpelaeopleuraEntity(EntityType<? extends SpelaeopleuraEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "spelaeopleura");
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_GLOW, 0.0F);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // 発光菌糸を探しに行く。優先度は徘徊(既定 6)より前、逃走より後。
        this.goalSelector.addGoal(5, new SeekGlowingMyceliumGoal(this));
    }

    /** 描画用の発光量(0〜1)。 */
    public float getGlow() {
        return this.entityData.get(DATA_GLOW);
    }

    /** いま光っている(蓄えが残っている)か。 */
    public boolean isLit() {
        return this.litTicks > 0;
    }

    /** 発光菌糸を 1 回ぶん食べた。 */
    public void feed() {
        this.litTicks = LIT_TICKS;
        this.feedCooldown = FEED_COOLDOWN;
        if (this.level().isClientSide()) {
            return;
        }
        this.playSound(SoundEvents.GRASS_BREAK, 0.4F, 1.6F);
    }

    public boolean canFeed() {
        return this.feedCooldown <= 0;
    }

    public double feedReachSq() {
        return FEED_REACH_SQ;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (this.feedCooldown > 0) {
            this.feedCooldown--;
        }
        if (this.litTicks > 0) {
            this.litTicks--;
        }
        stepGlow();
    }

    /**
     * 明るさを目標へ少しずつ寄せる。<b>一気に切り替えない</b>のがこの種の見どころなので、
     * 上りと下りで別の速さを持たせてある。
     */
    private void stepGlow() {
        float now = getGlow();
        float want = this.litTicks > 0 ? 1.0F : 0.0F;
        if (now == want) {
            return;
        }
        float step = want > now ? 1.0F / RAMP_UP_TICKS : -1.0F / RAMP_DOWN_TICKS;
        float next = now + step;
        // 行き過ぎないように目標で止める。
        next = step > 0 ? Math.min(want, next) : Math.max(want, next);
        this.entityData.set(DATA_GLOW, next);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("LitTicks", this.litTicks);
        tag.putFloat("Glow", getGlow());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.litTicks = tag.getInt("LitTicks");
        this.entityData.set(DATA_GLOW, tag.getFloat("Glow"));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state -> {
            // 今している行動(食べる・休む・驚いて逃げ出す など)があればそのアニメ(CritterAction、2026-10-22)
            RawAnimation critterAct = CritterAction.animation(this);
            if (critterAct != null) {
                state.getController().setAnimationSpeed(1.0D);
                return state.setAndContinue(critterAct);
            }
            state.getController().setAnimationSpeed(1.0D);
            if (MobAnimation.isWalking(state)) {
                return state.setAndContinue(WALK_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        }));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.25F, 0.5F);
    }

    /**
     * 発光菌糸を探して歩いていき、着いたら食べる。
     *
     * <p>バニラの {@link MoveToBlockGoal} をそのまま使う —— 探索範囲・再探索間隔・
     * 「届かない場所を覚えて諦める」までまるごと面倒を見てくれる。
     */
    private static final class SeekGlowingMyceliumGoal extends MoveToBlockGoal {

        private final SpelaeopleuraEntity critter;

        private SeekGlowingMyceliumGoal(SpelaeopleuraEntity critter) {
            super(critter, 1.0D, 12, 6);
            this.critter = critter;
        }

        @Override
        public boolean canUse() {
            return this.critter.canFeed() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return this.critter.canFeed() && super.canContinueToUse();
        }

        @Override
        protected boolean isValidTarget(LevelReader level, BlockPos pos) {
            return level.getBlockState(pos).is(ModBlocks.GLOWING_MYCELIUM.get());
        }

        @Override
        public double acceptedDistance() {
            return 2.0D;
        }

        @Override
        public void tick() {
            super.tick();
            if (!isReachedTarget()) {
                return;
            }
            if (this.critter.distanceToSqr(this.blockPos.getX() + 0.5D, this.blockPos.getY() + 0.5D,
                    this.blockPos.getZ() + 0.5D) <= this.critter.feedReachSq()) {
                this.critter.feed();
            }
        }
    }
}
