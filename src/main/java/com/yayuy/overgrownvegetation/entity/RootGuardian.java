package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.g1.G1TargetGoal;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * <b>根の守り手</b>(2026-11-13、司令塔 第 1 段の区切り 3。素材 {@code g1_root_guardian})。仕様は Vault「Mob の能力/G1 根付きの共通」の村人の欄:
 * 根付きの村人が攻撃されると、足元の根が寄り集まって 1 体立ち上がり、柱を守って戦う(同時に 1 体・倒されるとしばらくして育ち直す)。
 * 見た目は太い根が絡み合ったゴーレム(アイアンゴーレムの影。高さ 1.9 ブロック)。
 *
 * <ul>
 *   <li><b>立ち上がる</b>: 出た直後に {@code emerge}(32 tick。地面の根のかたまりから立つ)。その間は動かない・殴らない。</li>
 *   <li><b>柱を守る</b>: 持ち場(柱の足元)から {@link #GUARD_RADIUS} ブロックより遠くへは追わない。標的は、殴ってきた相手・柱を殴った相手
 *   (柱の側が {@link #setTarget} する)・持ち場の近くの獲物。</li>
 *   <li><b>殴る</b>: 前触れ 10 → 実行 4 → 隙 12(アニメ {@code attack_*})。当たりは実行の tick 2(拳が体の前 1.5 ブロックを通る)。</li>
 *   <li><b>歩き</b>: アニメ {@code walk} は 1 tick 0.0573 ブロックで足が滑らない。実際の速さに合わせて再生速度を変える。</li>
 *   <li><b>枯れて崩れる</b>: 倒すと {@code death_wither}(40 tick)で消える。柱(根付きの村人)がいなくなったら、守り手も枯れる【仮】。</li>
 * </ul>
 * 数(体力 40・攻撃 7・速さ 0.25・持ち場の半径 12・間合い)はどれも【仮】。卵・{@code /summon} で出したものは持ち場なしで、その場を守る。
 */
public class RootGuardian extends Monster implements ParasitizedMob, GeoEntity {

    public static final int ACTION_NONE = 0, ACTION_WINDUP = 1, ACTION_STRIKE = 2, ACTION_RECOVER = 3, ACTION_EMERGE = 4;
    /** アニメの長さ(tick。書き出しの記録)。 */
    public static final int EMERGE = 32, WITHER = 40, WINDUP = 10, STRIKE = 4, RECOVER = 12;
    /** 当たりを見る tick(実行の段の中)。 */
    static final int HIT_AT = 2;
    /** アニメ walk の足が滑らない速さ(ブロック/tick)。 */
    static final double WALK_REF = 0.0573D;
    /** 持ち場から離れてよい長さ・殴りの届く長さ(体の中心から相手の体の端まで)・振り出す長さ・次の 1 撃までの間。【仮】 */
    public static final double GUARD_RADIUS = 12.0D;
    static final double STRIKE_REACH = 2.1D;
    static final double STRIKE_TRIGGER = 1.7D;
    static final int STRIKE_GAP = 16;

    private static final EntityDataAccessor<Integer> ACTION =
            SynchedEntityData.defineId(RootGuardian.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation EMERGE_ANIM = RawAnimation.begin().thenPlayAndHold("emerge");
    private static final RawAnimation WITHER_ANIM = RawAnimation.begin().thenPlayAndHold("death_wither");
    private static final RawAnimation WINDUP_ANIM = RawAnimation.begin().thenPlayAndHold("attack_windup");
    private static final RawAnimation STRIKE_ANIM = RawAnimation.begin().thenPlayAndHold("attack");
    private static final RawAnimation RECOVER_ANIM = RawAnimation.begin().thenPlay("attack_recover");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 守っている柱(根付きの村人)。卵・コマンドで出したものは null。 */
    @Nullable
    private UUID owner;
    private int emergeLeft;

    public RootGuardian(EntityType<? extends RootGuardian> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    /** 柱の足元を持ち場にして、立ち上がり始める。 */
    public void guard(RootedParasitized pillar) {
        this.owner = pillar.getUUID();
        restrictTo(pillar.blockPosition(), (int) GUARD_RADIUS);
        startEmerge();
    }

    public void startEmerge() {
        emergeLeft = EMERGE;
        setAction(ACTION_EMERGE);
        // guard() からは addFreshEntity の前に呼ばれるので、場所を指して鳴らす。音量【仮】
        if (!level().isClientSide()) level().playSound(null, getX(), getY(), getZ(), ModSounds.G1_PLANT_EMERGE_SHORT.get(), getSoundSource(), 1.0F, 1.0F);
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new StrikeGoal(this));
        this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.9D));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
                e -> G1TargetGoal.isPrey(this, e) && isWithinRestriction(e.blockPosition())));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(ACTION, ACTION_NONE);
    }

    public int action() {
        return this.entityData.get(ACTION);
    }

    void setAction(int action) {
        this.entityData.set(ACTION, action);
    }

    public boolean isEmerging() {
        return action() == ACTION_EMERGE;
    }

    @Override
    public boolean isNoAi() {
        return super.isNoAi() || isEmerging();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || !isAlive()) return;
        if (emergeLeft > 0 && --emergeLeft == 0 && isEmerging()) setAction(ACTION_NONE);
        // 持ち場の外へ出た標的は追わない(柱を守る役)
        LivingEntity target = getTarget();
        if (target != null && hasRestriction() && !isWithinRestriction(target.blockPosition())) setTarget(null);
        // 柱がいなくなったら枯れる
        // 「いない」と決めるのは、持ち場(柱の足元)が読み込まれて動いているときだけ(読み込みの境目で、柱が見えないだけの間に枯れないように)
        if (owner != null && tickCount % 20 == 0 && level() instanceof ServerLevel level
                && (!hasRestriction() || level.isPositionEntityTicking(getRestrictCenter()))) {
            Entity pillar = level.getEntity(owner);
            if (pillar == null || !pillar.isAlive()) kill();
        }
    }

    /** 体の中心から相手の体の端までの水平の距離。 */
    double reachTo(LivingEntity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - e.getBbWidth() * 0.5D);
    }

    /** 足音(根が地面を踏む。3 本から選ぶ)。音量はアイアンゴーレムの足音と同じ 1.0【仮】。 */
    @Override
    protected void playStepSound(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        playSound(ModSounds.ROOT_GUARDIAN_STEP.get(), 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------ 枯れて崩れる
    @Override
    protected void tickDeath() {
        if (deathTime == 0 && !level().isClientSide()) playSound(ModSounds.G1_PLANT_WITHER.get(), 1.0F, 1.0F); // 音量【仮】
        ++deathTime;
        if (deathTime >= WITHER && !level().isClientSide() && !isRemoved()) {
            remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ 保存
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!super.isNoAi()) tag.remove("NoAI"); // 立ち上がりの途中で保存されても固まらない
        if (owner != null) tag.putUUID("Pillar", owner);
        if (hasRestriction()) {
            BlockPos home = getRestrictCenter();
            tag.putIntArray("Home", new int[]{home.getX(), home.getY(), home.getZ()});
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Pillar")) owner = tag.getUUID("Pillar");
        int[] home = tag.getIntArray("Home");
        if (home.length == 3) restrictTo(new BlockPos(home[0], home[1], home[2]), (int) GUARD_RADIUS);
    }

    // ------------------------------------------------------------------ アニメ
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "move", 4, state -> {
            AnimationController<RootGuardian> ctl = state.getController();
            ctl.setAnimationSpeed(1.0D);
            if (isDeadOrDying()) {
                ctl.transitionLength(2);
                return state.setAndContinue(WITHER_ANIM);
            }
            switch (action()) {
                case ACTION_EMERGE -> {
                    ctl.transitionLength(0);
                    return state.setAndContinue(EMERGE_ANIM);
                }
                case ACTION_WINDUP -> {
                    ctl.transitionLength(2);
                    return state.setAndContinue(WINDUP_ANIM);
                }
                case ACTION_STRIKE -> {
                    ctl.transitionLength(0);
                    return state.setAndContinue(STRIKE_ANIM);
                }
                case ACTION_RECOVER -> {
                    ctl.transitionLength(0);
                    return state.setAndContinue(RECOVER_ANIM);
                }
                default -> {
                }
            }
            ctl.transitionLength(4);
            double dx = getX() - xo, dz = getZ() - zo;
            if (dx * dx + dz * dz > 0.015D * 0.015D) {
                ctl.setAnimationSpeed(MobAnimation.walkSpeedFor(this, WALK_REF));
                return state.setAndContinue(WALK);
            }
            return state.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ 殴る
    /** 追って、届いたら 前触れ → 実行 → 隙。当たりは実行の tick {@link #HIT_AT}、止めた向きの前 ±60° で届く相手に。 */
    static final class StrikeGoal extends Goal {

        private final RootGuardian mob;
        private int stageLeft;
        private int stageAge;
        private int gap;
        private int repath;

        StrikeGoal(RootGuardian mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = mob.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() || mob.action() != ACTION_NONE;
        }

        @Override
        public void stop() {
            int a = mob.action();
            if (a == ACTION_WINDUP || a == ACTION_STRIKE || a == ACTION_RECOVER) mob.setAction(ACTION_NONE);
            mob.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        private void stage(int action, int ticks) {
            mob.setAction(action);
            stageLeft = ticks;
            stageAge = 0;
        }

        @Override
        public void tick() {
            LivingEntity t = mob.getTarget();
            int a = mob.action();
            if (gap > 0) gap--;
            if (a == ACTION_WINDUP || a == ACTION_STRIKE || a == ACTION_RECOVER) {
                mob.getNavigation().stop();
                if (a == ACTION_WINDUP && t != null) mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
                // 音の当たりは頭から約 5 tick。前触れの tick 7 で鳴らすと、実行の tick 2(当たり)に合う。音量【仮】
                if (a == ACTION_WINDUP && stageAge == WINDUP - 3) mob.playSound(ModSounds.ROOT_GUARDIAN_STRIKE.get(), 1.0F, 1.0F);
                if (a == ACTION_STRIKE && stageAge == HIT_AT) hit();
                stageAge++;
                if (--stageLeft > 0) return;
                if (a == ACTION_WINDUP) stage(ACTION_STRIKE, STRIKE);
                else if (a == ACTION_STRIKE) stage(ACTION_RECOVER, RECOVER);
                else {
                    mob.setAction(ACTION_NONE);
                    gap = STRIKE_GAP;
                }
                return;
            }
            if (t == null || a != ACTION_NONE) return;
            mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
            if (mob.reachTo(t) <= STRIKE_TRIGGER && Math.abs(t.getY() - mob.getY()) < 2.0D) {
                mob.getNavigation().stop();
                if (gap <= 0) stage(ACTION_WINDUP, WINDUP);
                return;
            }
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(t, 1.0D);
            }
        }

        private void hit() {
            float yaw = mob.yBodyRot * Mth.DEG_TO_RAD;
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(STRIKE_REACH + 1.0D, 0.5D, STRIKE_REACH + 1.0D))) {
                if (e == mob || !e.isAlive() || !G1TargetGoal.isPrey(mob, e) || mob.reachTo(e) > STRIKE_REACH) continue;
                double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
                double d = Math.max(1.0E-3D, Math.sqrt(dx * dx + dz * dz));
                if ((dx * fx + dz * fz) / d < 0.5D) continue; // 前 ±60° の外
                mob.doHurtTarget(e);
            }
        }
    }
}
