package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 「過繁茂したスカトゥリウム」の突進攻撃。
 *
 * <p>対象に一定以上（{@link #MIN_TRIGGER_DISTANCE} 以上）引き離されると発動：
 * <ol>
 *     <li><b>溜め</b> {@link #WINDUP_TICKS}tick（約1.5秒）。その場で対象を向く。</li>
 *     <li><b>突進</b> {@link #CHARGE_MIN_TICKS}〜{@link #CHARGE_MAX_TICKS}tick（2〜3秒）。
 *         溜め終了時の対象方向へロックして直進。触れた {@link LivingEntity} へ毎tick
 *         {@link #TOUCH_DAMAGE} ダメージ（盾でガードされた場合はノーダメージで回数だけカウント）。</li>
 *     <li><b>スタン</b>。2ブロック以上の段差／壁への衝突なら {@link #WALL_STUN_TICKS}tick（約3秒）、
 *         突進が盾で {@link #SHIELD_BLOCKS_TO_STUN} 回防がれたなら {@link #SHIELD_STUN_TICKS}tick（約1.8秒）。</li>
 * </ol>
 */
public class ShielderChargeGoal extends Goal {

    private static final int WINDUP_TICKS = 30;          // 1.5s
    private static final int CHARGE_MIN_TICKS = 40;      // 2.0s
    private static final int CHARGE_MAX_TICKS = 60;      // 3.0s
    /** 段差・壁にぶつかったときのスタン。 */
    private static final int WALL_STUN_TICKS = 60;       // 3.0s
    /** 突進が盾で規定回数防がれたときのスタン。 */
    private static final int SHIELD_STUN_TICKS = 36;     // 1.8s
    /** 突進を撃ち切った／スタンから復帰したあと、次の突進まで（20秒）。 */
    private static final int COOLDOWN_AFTER_CHARGE = 400;
    private static final int COOLDOWN_AFTER_STUN = 400;
    /** 溜め中に対象を見失った・低い障害物や水際で不発に終わった場合の短いクールダウン（3秒）。 */
    private static final int COOLDOWN_ABORTED = 60;

    private static final double MIN_TRIGGER_DISTANCE = 3.0D;
    private static final double MAX_TRIGGER_DISTANCE = 24.0D;
    private static final double CHARGE_SPEED = 0.46D;
    private static final float TOUCH_DAMAGE = 3.0F;
    /** 突進ヒット時、進行方向へ軽く押す（前方に転がして毎tickの多段ヒットを繋ぐ）。 */
    private static final double CHARGE_HIT_KNOCKBACK = 0.4D;
    /** 盾で防がれても入る「少しだけ」のノックバック。 */
    private static final double SHIELD_BLOCK_KNOCKBACK = 0.35D;
    private static final int SHIELD_BLOCKS_TO_STUN = 10;
    /** 突進を盾で防いだ1回ごとに削る盾の耐久値。 */
    private static final int SHIELD_DURABILITY_COST = 5;

    private final OvergrownShielderEntity mob;

    private int windupLeft;
    private int chargeLeft;
    private int stunLeft;
    private double dirX;
    private double dirZ;
    private int nextChargeTime;
    private double lastX;
    private double lastZ;
    private int stuckTicks;

    public ShielderChargeGoal(OvergrownShielderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (mob.isBusy() || mob.tickCount < nextChargeTime) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // 引き離された相手への手は「突進」と「迫撃砲」の 2 つ（ユーザー要望 2026-09-15）。
        // 札が迫撃砲の順番なら譲る。ただし<b>迫撃砲がクールタイム中なら譲らない</b> ——
        // 譲ったまま両方出せない時間ができると、遠い相手にただ歩み寄るだけになってしまう。
        if (mob.shouldMortarNext() && mob.canMortarNow()) {
            return false;
        }
        // 水上／水中では突進しない（岸に上がってから）。落下中も不可。
        if (mob.isInWater() || mob.fallDistance > 0.6F) {
            return false;
        }
        double dist = mob.distanceTo(target);
        return dist >= MIN_TRIGGER_DISTANCE && dist <= MAX_TRIGGER_DISTANCE && mob.hasLineOfSight(target);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isAlive() && mob.isBusy();
    }

    @Override
    public void start() {
        windupLeft = WINDUP_TICKS;
        chargeLeft = 0;
        stunLeft = 0;
        mob.setActionState(OvergrownShielderEntity.STATE_WINDUP);
        mob.getNavigation().stop();
        mob.playSound(SoundEvents.RAVAGER_ROAR, 1.2F, 0.6F);
        // 突進したら次は迫撃砲。交互に出す。
        mob.setMortarNext(true);
    }

    @Override
    public void stop() {
        // canContinueToUse が false になる＝ actionState は既に STATE_NONE。念のため下限を保証。
        if (mob.getActionState() != OvergrownShielderEntity.STATE_NONE) {
            mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (mob.getActionState()) {
            case OvergrownShielderEntity.STATE_WINDUP:
                tickWindup();
                break;
            case OvergrownShielderEntity.STATE_CHARGE:
                tickCharge();
                break;
            case OvergrownShielderEntity.STATE_STUN:
                tickStun();
                break;
            default:
                break;
        }
    }

    private void tickWindup() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            endToIdle(COOLDOWN_ABORTED);
            return;
        }
        mob.getNavigation().stop();
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        float wantYaw = yawTo(target.getX() - mob.getX(), target.getZ() - mob.getZ());
        mob.setYRot(Mth.approachDegrees(mob.getYRot(), wantYaw, 12.0F));
        mob.yBodyRot = mob.getYRot();

        if (--windupLeft <= 0) {
            Vec3 to = new Vec3(target.getX() - mob.getX(), 0.0D, target.getZ() - mob.getZ());
            if (to.lengthSqr() < 1.0E-4D) {
                to = Vec3.directionFromRotation(0.0F, mob.getYRot());
                to = new Vec3(to.x, 0.0D, to.z);
            }
            to = to.normalize();
            dirX = to.x;
            dirZ = to.z;
            chargeLeft = CHARGE_MIN_TICKS + mob.getRandom().nextInt(CHARGE_MAX_TICKS - CHARGE_MIN_TICKS + 1);
            stuckTicks = 0;
            lastX = mob.getX();
            lastZ = mob.getZ();
            mob.setActionState(OvergrownShielderEntity.STATE_CHARGE);
            mob.playSound(SoundEvents.RAVAGER_ATTACK, 1.1F, 0.8F);
        }
    }

    private void tickCharge() {
        // 岸で止まる：水に踏み込む／前方が水なら、スタンせずそのまま突進終了。
        if (mob.isInWater() || waterAhead()) {
            mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D));
            endToIdle(COOLDOWN_ABORTED);
            return;
        }

        float yaw = yawTo(dirX, dirZ);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.setDeltaMovement(dirX * CHARGE_SPEED, mob.getDeltaMovement().y, dirZ * CHARGE_SPEED);
        mob.hasImpulse = true;

        // 触れた相手へダメージ／盾ガード判定
        AABB hitbox = mob.getBoundingBox().inflate(0.55D);
        for (LivingEntity victim : mob.level().getEntitiesOfClass(LivingEntity.class, hitbox,
                e -> e != mob && e.isAlive() && !e.isSpectator())) {
            if (isBlockingCharge(victim)) {
                mob.addShieldBlockedCount();
                // 盾で防いでも「少しだけ」押される（hurtMarked でクライアントへ速度を同期）。
                victim.knockback(SHIELD_BLOCK_KNOCKBACK, -dirX, -dirZ);
                victim.hurtMarked = true;
                damageBlockingShield(victim);
                mob.level().playSound(null, victim.blockPosition(), SoundEvents.SHIELD_BLOCK,
                        SoundSource.HOSTILE, 1.0F, 0.75F);
            } else {
                // 無敵時間を潰して毎tickヒットさせる
                if (victim.invulnerableTime > 4) {
                    victim.invulnerableTime = 4;
                }
                boolean hit = victim.hurt(mob.damageSources().mobAttack(mob), TOUCH_DAMAGE);
                if (hit) {
                    // 進行方向へ軽く押し出し、突進の前で転がしながら多段ヒットさせる
                    victim.knockback(CHARGE_HIT_KNOCKBACK, -dirX, -dirZ);
                    victim.hurtMarked = true;
                }
            }
        }

        // 実際にどれだけ進めたか（1マス段差なら STEP_HEIGHT で乗り越えるのでほぼ止まらない）。
        double movedSq = Mth.square(mob.getX() - lastX) + Mth.square(mob.getZ() - lastZ);
        stuckTicks = movedSq < 0.12D * 0.12D ? stuckTicks + 1 : 0;
        lastX = mob.getX();
        lastZ = mob.getZ();

        if (mob.getShieldBlockedCount() >= SHIELD_BLOCKS_TO_STUN) {
            enterStun(SHIELD_STUN_TICKS);
            return;
        }
        // 2ブロック以上の段差／壁に本当にぶつかって止まったときだけスタン。
        if (stuckTicks >= 2 && mob.horizontalCollision && hitTallObstacle()) {
            enterStun(WALL_STUN_TICKS);
            return;
        }
        // 低い障害物（フェンス・ハーフブロックの組み合わせ等）に嵌まっただけなら、スタンせず突進終了。
        if (stuckTicks >= 8) {
            endToIdle(COOLDOWN_ABORTED);
            return;
        }
        if (--chargeLeft <= 0) {
            endToIdle(COOLDOWN_AFTER_CHARGE);
        }
    }

    private void tickStun() {
        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().x * 0.6D, mob.getDeltaMovement().y, mob.getDeltaMovement().z * 0.6D);
        if (--stunLeft <= 0) {
            mob.resetShieldBlockedCount();
            endToIdle(COOLDOWN_AFTER_STUN);
        }
    }

    private void enterStun(int ticks) {
        stunLeft = ticks;
        mob.setActionState(OvergrownShielderEntity.STATE_STUN);
        mob.getNavigation().stop();
        // 壁にぶつかって跳ね返るような小さな反動
        mob.setDeltaMovement(-dirX * 0.35D, 0.22D, -dirZ * 0.35D);
        mob.hasImpulse = true;
        mob.playSound(SoundEvents.MOSS_BREAK, 1.4F, 0.6F);
        mob.playSound(SoundEvents.IRON_GOLEM_DAMAGE, 1.0F, 0.5F);
        if (mob.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.POOF,
                    mob.getX() + dirX * (mob.getBbWidth() / 2.0D), mob.getY() + 1.0D, mob.getZ() + dirZ * (mob.getBbWidth() / 2.0D),
                    30, 0.6D, 0.5D, 0.6D, 0.05D);
            server.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                    mob.getX(), mob.getY() + 1.4D, mob.getZ(), 24, 1.2D, 0.8D, 1.2D, 0.02D);
        }
    }

    private void endToIdle(int cooldown) {
        mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        nextChargeTime = mob.tickCount + cooldown;
    }

    /**
     * 進行方向の障害物が足元から数えて2ブロック以上の高さで塞がっている＝乗り越えられない段差／壁。
     * 1ブロックの段差（足元の1マスだけ塞がっている）は {@code STEP_HEIGHT}(1.5) で突進中も
     * そのまま乗り越えるので、ここでは false（スタンしない）。
     */
    private boolean hitTallObstacle() {
        double ahead = mob.getBbWidth() / 2.0D + 0.55D;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(
                Mth.floor(mob.getX() + dirX * ahead),
                Mth.floor(mob.getY() + 0.1D),
                Mth.floor(mob.getZ() + dirZ * ahead));
        int stackedSolid = 0;
        while (stackedSolid < 4 && mob.level().getBlockState(p).blocksMotion()) {
            stackedSolid++;
            p.move(Direction.UP);
        }
        return stackedSolid >= 2;
    }

    /**
     * ガードに使われている盾の耐久を {@link #SHIELD_DURABILITY_COST} 削る。
     * バニラの通常攻撃と違い突進のガードでは盾が消耗しないため、ここで手動で減らす。
     */
    private void damageBlockingShield(LivingEntity victim) {
        if (!victim.isUsingItem()) {
            return;
        }
        ItemStack shield = victim.getUseItem();
        if (shield.isEmpty() || !shield.isDamageableItem()) {
            return;
        }
        EquipmentSlot slot = victim.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        shield.hurtAndBreak(SHIELD_DURABILITY_COST, victim, slot);
    }

    /** 進行方向のすぐ先が水（足元 or その1つ下）＝ここで岸として止まる。 */
    private boolean waterAhead() {
        double ahead = mob.getBbWidth() / 2.0D + 0.7D;
        BlockPos ap = BlockPos.containing(
                mob.getX() + dirX * ahead, mob.getY() + 0.1D, mob.getZ() + dirZ * ahead);
        return mob.level().getFluidState(ap).is(FluidTags.WATER)
                || mob.level().getFluidState(ap.below()).is(FluidTags.WATER);
    }

    private boolean isBlockingCharge(LivingEntity victim) {
        if (!victim.isBlocking()) {
            return false;
        }
        Vec3 toMob = new Vec3(mob.getX() - victim.getX(), 0.0D, mob.getZ() - victim.getZ());
        if (toMob.lengthSqr() < 1.0E-4D) {
            return true;
        }
        Vec3 look = victim.getViewVector(1.0F);
        Vec3 lookFlat = new Vec3(look.x, 0.0D, look.z);
        if (lookFlat.lengthSqr() < 1.0E-4D) {
            return false;
        }
        return toMob.normalize().dot(lookFlat.normalize()) > 0.2D;
    }

    private static float yawTo(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
    }
}
