package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.g1.VineWhip;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * <b>蔦の処刑者</b>(寄生されたゾンビの進化 2 段目、<b>G5</b>。G1 の作り直し 第 1 段、2026-10-22)。体が植物に置き換わった幹の人型。
 * 蔦の鞭 2 本で遠く({@code executionerWhipRange}、仮 7)から掴んで引き寄せ、棘の槍を投げる({@code executionerThornDamage}、仮 6)。
 * 近ければ殴る。対人特化なのでプレイヤーを狙う。<b>武器への適応</b>は G5 のタグにいるだけで既存の仕組み({@code adaptation/Adaptation})に乗る。
 * 見た目はユーザーの bbmodel(10-23 以降の正本、bbmodel_sources/vine_executioner_user_20261023.bbmodel)、アニメは生成器 tools/g5-models/mobs/G1ExecutionerUser。棘の槍は今は矢の見た目(仮)。
 *
 * <p>10-23 ユーザー指定で攻撃の動きをゾンビから変えた: <b>引き寄せ</b> = 左肩の触手の束から蔦を伸ばして相手に絡め({@link #CAST_REACH} tick で届く)、
 * 届いたら引き寄せる(引くのは今までの {@link VineWhip#tryPull})。伸びる蔦はクライアントの描画(client/VineCastRenderer)。
 * <b>近接</b> = 発達した右肩の束を剣・槍のように振る(斬る・突くを交互に)。間合いは腕の長さぶん {@link #REACH_EXTRA} 広い。数値はどれも仮決め。
 */
public class VineExecutioner extends Monster implements GeoEntity, ParasitizedMob {

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK_ANIM = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation CAST_ANIM = RawAnimation.begin().thenPlay("cast");
    private static final RawAnimation SLASH_ANIM = RawAnimation.begin().thenPlay("slash");
    private static final RawAnimation THRUST_ANIM = RawAnimation.begin().thenPlay("thrust");
    private static final RawAnimation THORN_ANIM = RawAnimation.begin().thenPlay("thorn");

    /** 【仮】蔦が相手に届くまで(tick)。cast のアニメの 0.3 秒に合わせる。 */
    public static final int CAST_REACH = 6;
    /** 【仮】届いてから蔦を引っ込めるまで(tick)。この間、蔦は相手に絡んだまま描く。 */
    public static final int CAST_HOLD = 8;
    /** 【仮】近接の間合いを広げる量(ブロック。発達した右腕の長さぶん)。 */
    public static final double REACH_EXTRA = 0.9D;

    /** 伸ばしている蔦の相手(エンティティの id、−1 = 伸ばしていない)。クライアントの描画が読む。 */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_CAST_TARGET =
            net.minecraft.network.syncher.SynchedEntityData.defineId(VineExecutioner.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private int whipCooldown;
    private int thornCooldown = 40;
    private int castTicks = -1;
    private boolean thrustNext;
    /** クライアント: 今の蔦の相手と、伸ばし始めてからの tick。 */
    private int clientCastTarget = -1;
    private int clientCastAge;

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_CAST_TARGET, -1);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            int t = entityData.get(DATA_CAST_TARGET);
            if (t != clientCastTarget) {
                clientCastTarget = t;
                clientCastAge = 0;
            } else if (t >= 0) {
                clientCastAge++;
            }
        }
    }

    /** 描画用: 蔦を伸ばしている相手(いなければ null)。 */
    public net.minecraft.world.entity.Entity castTarget() {
        return clientCastTarget < 0 ? null : level().getEntity(clientCastTarget);
    }

    /** 描画用: 伸ばし始めてからの時間(tick、partialTick 込み)。 */
    public float castAge(float partialTick) {
        return clientCastAge + partialTick;
    }

    /** 発達した右腕の長さぶん、近接の当たる範囲を前後左右に広げる。 */
    @Override
    protected net.minecraft.world.phys.AABB getAttackBoundingBox() {
        return super.getAttackBoundingBox().inflate(REACH_EXTRA, 0.0D, REACH_EXTRA);
    }

    public VineExecutioner(EntityType<? extends VineExecutioner> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 60.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.4)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1D, false));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // 標的は G5 共通の口(植物側のプレイヤー・過繁茂 Mob を外す。ファルキムネスと同じ)
        this.targetSelector.addGoal(1, new OvergrownHurtByTargetGoal(this));
        com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal.installTargeting(this, 2);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = getTarget();
        G1Table g = DesignData.g1();
        // 蔦を伸ばしている間: CAST_REACH tick で届いたら引き寄せ、CAST_HOLD tick 後に引っ込める(ほかの技は出さない)
        if (castTicks >= 0) {
            castTicks++;
            net.minecraft.world.entity.Entity e = level().getEntity(entityData.get(DATA_CAST_TARGET));
            if (castTicks == CAST_REACH && e instanceof LivingEntity caught) {
                // 伸ばしている間に少し離れても届く(+2)。届かなければ空振りで蔦だけ戻る
                VineWhip.tryPull(this, caught, g.executionerWhipRange + 2.0D, g.executionerWhipPull);
            }
            if (castTicks >= CAST_REACH + CAST_HOLD || e == null || !e.isAlive()) {
                castTicks = -1;
                entityData.set(DATA_CAST_TARGET, -1);
            }
            return;
        }
        if (whipCooldown > 0) {
            whipCooldown--;
        } else if (VineWhip.canPull(this, target, g.executionerWhipRange)) {
            castTicks = 0;
            entityData.set(DATA_CAST_TARGET, target.getId());
            triggerAnim("attack", "cast");
            playSound(SoundEvents.WEEPING_VINES_PLACE, 1.0F, 0.7F);
            whipCooldown = g.executionerWhipCooldownTicks;
            return;
        }
        if (thornCooldown > 0) {
            thornCooldown--;
        } else if (target != null && target.isAlive() && hasLineOfSight(target)) {
            double dist = distanceTo(target);
            if (dist > g.executionerWhipRange && dist < 20.0D) {
                throwThorn(target, g);
                thornCooldown = g.executionerThornCooldownTicks;
            }
        }
    }

    /** 棘の槍を投げる(弓矢と同じ弾道。拾えない)。 */
    private void throwThorn(LivingEntity target, G1Table g) {
        Arrow thorn = new Arrow(level(), this, new ItemStack(Items.ARROW), null);
        thorn.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = target.getX() - getX();
        double dy = target.getY(0.33D) - thorn.getY();
        double dz = target.getZ() - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float speed = 1.6F;
        thorn.shoot(dx, dy + horizontal * 0.2D, dz, speed, 6.0F);
        thorn.setBaseDamage(g.executionerThornDamage / speed);
        level().addFreshEntity(thorn);
        swing(InteractionHand.OFF_HAND);
        triggerAnim("attack", "thorn");
        playSound(SoundEvents.TRIDENT_THROW.value(), 1.0F, 0.7F);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            // 発達した右腕を剣・槍のように: 斬ると突くを交互に
            triggerAnim("attack", thrustNext ? "thrust" : "slash");
            thrustNext = !thrustNext;
        }
        return hit;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ROOTED_DIRT_BREAK;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_DEATH;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, state ->
                state.setAndContinue(MobAnimation.isWalking(state) ? WALK_ANIM : IDLE_ANIM)));
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("cast", CAST_ANIM).triggerableAnim("slash", SLASH_ANIM).triggerableAnim("thrust", THRUST_ANIM)
                .triggerableAnim("thorn", THORN_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
