package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;

/**
 * 「メガネウラ」— 巨大なトンボのグループ3(原生消費種)の飛行 Mob。
 *
 * <ul>
 *     <li>普通に空を飛び回って({@link FlyerWanderGoal})獲物を探し、見つけたら直進して
 *     噛みつく({@link MeganeuraHuntGoal})。</li>
 *     <li>獲物を仕留めると、たまに近くの過繁茂した木の枝の上に降りて食事と休息をとる
 *     ({@link MeganeuraRestGoal})。</li>
 *     <li>仕留めた獲物のモデルを口元に咥えて枝まで運び、咀嚼が終わると同時に消す
 *     ({@link #getPreyDisplay()} / {@code MeganeuraPreyLayer})。</li>
 *     <li>攻撃力・移動速度が高いが、体力・防御力は低い。</li>
 *     <li>獲物を1体倒すごとに攻撃力・体力が 5% ずつ上昇する({@link PredatorMob})。</li>
 * </ul>
 *
 * <h2>アニメーション名(GeckoLib)</h2>
 * <ul>
 *     <li>{@code fly} — 飛行中(既定)。</li>
 *     <li>{@code chase} — 獲物を追いかけている間(羽ばたきのみ)。</li>
 *     <li>{@code bite} — 実際に噛みついた瞬間だけの一撃({@link OvergrownFlyerEntity#STATE_BITE})。</li>
 *     <li>{@code relax} → {@code chew} — 枝に降りて羽を下ろし、獲物を咀嚼する({@link OvergrownFlyerEntity#STATE_FEED})。</li>
 *     <li>{@code relax2} — 咀嚼のあと枝の上で休んでいる間のループ({@link OvergrownFlyerEntity#STATE_REST})。</li>
 * </ul>
 */
public class MeganeuraEntity extends OvergrownFlyerEntity implements PredatorMob {

    /** 獲物を仕留めたあとに枝へ降りて休息する確率。「たまに」なので半々くらい。 */
    private static final float REST_CHANCE_AFTER_KILL = 0.5F;

    /** 枝に止まっている間の当たり判定の実寸(胴体ぶん)。{@link #getDefaultDimensions} 参照。 */
    private static final EntityDimensions RESTING_DIMENSIONS = EntityDimensions.scalable(0.6F, 0.4F);

    /** 咥えている獲物なし({@link #DATA_PREY_TYPE} の既定値)。 */
    private static final int NO_PREY = -1;

    /**
     * 口元に咥えて見せる獲物の種類(エンティティレジストリの数値 ID、なければ {@link #NO_PREY})。
     * 見せるだけの一時的な演出なので NBT には保存しない。
     */
    private static final EntityDataAccessor<Integer> DATA_PREY_TYPE =
            SynchedEntityData.defineId(MeganeuraEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation FLY_ANIM = RawAnimation.begin().thenLoop("fly");
    /** 獲物を追いかけている間。羽ばたきだけで、頭も胴体も揺らさない(揺らすと酔う)。 */
    private static final RawAnimation CHASE_ANIM = RawAnimation.begin().thenLoop("chase");
    /** 実際に噛みついた瞬間だけの、頭を振り下ろす一撃。 */
    private static final RawAnimation BITE_ANIM = RawAnimation.begin().thenLoop("bite");
    /** 枝に降りて羽を下ろす {@code relax}(1回)→ そのまま獲物を咀嚼する {@code chew}(ループ)。 */
    private static final RawAnimation FEED_ANIM = RawAnimation.begin().thenPlay("relax").thenLoop("chew");
    /** 咀嚼が終わったあとの休息ループ。{@code relax} の終わりの姿勢から続くように作ってある。 */
    private static final RawAnimation REST_ANIM = RawAnimation.begin().thenLoop("relax2");

    private int preyKillCount;
    /**
     * 獲物を仕留めた直後だけ立つ「休みたい」フラグ。{@link MeganeuraRestGoal} が消費する。
     * その場限りの気分なので NBT には保存しない。
     */
    private boolean wantsRest;

    public MeganeuraEntity(EntityType<? extends MeganeuraEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward("meganeura", 8);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Mob.createMobAttributes(), "meganeura");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 食事・休息は狩りより優先。獲物を食べ終えるまで次の獲物を追いかけない。
        this.goalSelector.addGoal(1, new MeganeuraRestGoal(this, 1.1D));
        this.goalSelector.addGoal(2, new MeganeuraHuntGoal(this));
        this.goalSelector.addGoal(5, new FlyerWanderGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));

        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        // 視線を無視する索敵(茂った過繁茂バイオームでも相手を見失わない。OvergrownHunterTargetGoal 参照)。
        // 水中・水面にいる相手は狙わない(OvergrownLandTargetGoal / Predation#isLandTarget)。
        this.targetSelector.addGoal(2, new OvergrownLandTargetGoal(this, Predation::isPredatorOf));
        // 縄張りに入ってきた侵入者(プレイヤー・バニラの敵対 Mob)にも先制攻撃する。
        this.targetSelector.addGoal(3, new OvergrownLandTargetGoal(this, Predation::isIntruder));
    }

    // --- 食事と休息 ---

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PREY_TYPE, NO_PREY);
    }

    /**
     * いま口元に咥えて見せている獲物の種類。{@code null} なら何も咥えていない。
     * 獲物を仕留めた瞬間から、枝の上での咀嚼({@code chew})が終わるまでの間だけ入っている
     * ({@link MeganeuraRestGoal} が消す)。
     */
    @Nullable
    public EntityType<?> getPreyDisplay() {
        int id = this.entityData.get(DATA_PREY_TYPE);
        return id == NO_PREY ? null : BuiltInRegistries.ENTITY_TYPE.byId(id);
    }

    public void setPreyDisplay(@Nullable EntityType<?> type) {
        this.entityData.set(DATA_PREY_TYPE, type == null ? NO_PREY : BuiltInRegistries.ENTITY_TYPE.getId(type));
    }

    /** 獲物を仕留めた直後で、枝へ降りて休みたがっているか。 */
    public boolean wantsRest() {
        return this.wantsRest;
    }

    public void setWantsRest(boolean wantsRest) {
        this.wantsRest = wantsRest;
    }

    /**
     * 何かを仕留めたら、たまに「休みたい」フラグを立てる({@link MeganeuraRestGoal} が枝を探して降りる)。
     * プレイヤーを倒した場合は食事にはならないので対象外。
     */
    @Override
    public boolean killedEntity(ServerLevel level, LivingEntity killed) {
        boolean dropLoot = super.killedEntity(level, killed);
        if (!(killed instanceof Player) && this.random.nextFloat() < REST_CHANCE_AFTER_KILL) {
            this.wantsRest = true;
            // 枝まで咥えて運び、咀嚼が終わったところで消す(MeganeuraRestGoal)。
            setPreyDisplay(killed.getType());
        }
        return dropLoot;
    }

    /**
     * 枝に止まっている間だけ、当たり判定を {@link #RESTING_DIMENSIONS} に縮める。
     *
     * <p>飛行中の判定は {@code mobs.json} の {@code width}/{@code height} × 個体差スケール
     * (メガネウラは {@code scaleMax} が 1.8 なので最大 1.8×0.9 ブロック)で、これが枝の上の
     * 1 ブロックに収まらず、周りの葉や地面に埋まってしまう。止まっている間だけ胴体ぶん
     * (羽を除く)の大きさにしておけばどの個体でも枝に収まる。
     *
     * <p>{@code LivingEntity#getDimensions}(final)が {@code getDefaultDimensions(pose).scale(getScale())}
     * として個体差スケールを掛けてくるので、あらかじめ {@code 1 / getScale()} 倍して打ち消し、
     * 体格の大きい個体でも休息中の実寸が変わらないようにする({@link BlightFernEntity} と同じ手口)。
     * 見た目の大きさは GeckoLib が {@code Attributes.SCALE} を別途参照するので影響しない。
     */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        if (!isStationary()) {
            return super.getDefaultDimensions(pose);
        }
        float scale = this.getScale();
        return RESTING_DIMENSIONS.scale(scale > 0.0F ? 1.0F / scale : 1.0F);
    }

    /**
     * {@code spot} に止まったときに占める当たり判定。{@link MeganeuraRestGoal} が
     * 「そこに本当に収まるか」を着地前に確かめるのに使う。
     */
    public AABB getRestingBoundingBox(Vec3 spot) {
        return RESTING_DIMENSIONS.makeBoundingBox(spot);
    }

    // --- 捕食数(PredatorMob) ---

    @Override
    public int getPreyKillCount() {
        return this.preyKillCount;
    }

    @Override
    public void setPreyKillCount(int count) {
        this.preyKillCount = count;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PreyKills", this.preyKillCount);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.preyKillCount = tag.getInt("PreyKills");
        PredatorMob.applyPreyKillBonus(this, this.preyKillCount);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            // 少しだけ突き飛ばして噛みつきを繋ぐ。
            living.setDeltaMovement(living.getDeltaMovement().add(0.0D, 0.1D, 0.0D));
        }
        return hit;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", TRANSITION_TICKS, state -> {
            AnimationController<MeganeuraEntity> c = state.getController();
            c.setAnimationSpeed(1.0D);
            RawAnimation next = switch (getActionState()) {
                case STATE_FEED -> FEED_ANIM;
                case STATE_REST -> REST_ANIM;
                case STATE_BITE -> BITE_ANIM;
                case STATE_ATTACK -> CHASE_ANIM;
                default -> FLY_ANIM;
            };
            // 噛みつきの出入り(追跡 → 噛みつき → 追跡)だけは補間 0。bite の最初と最後の姿勢は頭と胴が 0・羽が chase と
            // 同じ羽ばたきなので飛ばない。補間を挟むと 0.25 秒の一撃のうち頭を振り下ろしきるのが 3 tick 遅れ、
            // 噛んだあとに頭が動いて見えた(2026-09-23)。
            boolean biteSwitch = next == BITE_ANIM || (next == CHASE_ANIM && c.getCurrentRawAnimation() == BITE_ANIM);
            c.transitionLength(biteSwitch ? 0 : TRANSITION_TICKS);
            return state.setAndContinue(next);
        }));
    }

    /** 枝で休んでいる間は羽音を出さない(羽を下ろして止まっているため)。 */
    @Override
    protected SoundEvent getAmbientSound() {
        return isStationary() ? null : SoundEvents.BEE_LOOP_AGGRESSIVE;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BEE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.BEE_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6F;
    }
}
