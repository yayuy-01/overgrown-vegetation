package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSpreadGoal;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * フィロセンスの<b>索敵 1 回ぶんの中身</b>(2026-09-25、群れ v2 で {@link PhyllosensScoutGoal} から切り出した)。
 *
 * <p>個体の索敵({@link PhyllosensScoutGoal}: 32 ブロックにプレイヤーが 1 分いなければ)と、群れが止まって全員で汚すときの
 * フィロセンスの動作({@link SwarmSpreadGoal}、ユーザー指定 2026-09-25)が同じ動き・同じ見た目になるように共通にした。
 * 中身は切り出す前と同じ: その場に根を張って首を上げ花を開き({@code STATE_SCOUT} = {@code scout} アニメ)、
 * 胞子を遠くからも見えるように撒き、足元を汚し、周りへ胞子感染を配り、{@code phyllosensScanIntervalTicks} ごとに
 * 視線(半径 {@code phyllosensScoutWatchRange})と誘導(半径 {@code phyllosensScoutGuidanceRange})を広く配る。
 * {@code phyllosensScoutDurationTicks}(既定 600)続けたら終わり。
 * <b>根を張る・遠くでも動いて見えるのは仕様</b>(ユーザー指定 2026-09-14「遠くから見て索敵をしているのが分かるように」)。
 */
public final class PhyllosensScoutAction implements SwarmSpreadGoal.Action {

    /** 胞子感染の判定間隔(tick)。 */
    private static final int INFECT_INTERVAL = 20;
    /** 遠景用の胞子の柱を吹き上げる間隔(tick)。 */
    private static final int PLUME_INTERVAL = 9;

    private final PhyllosensEntity mob;

    private int ticks;
    private int markTimer;
    private InvaderSporeField field;
    /** 索敵に入った瞬間の向き。索敵のあいだこの向きに固定する。 */
    private float lockedYaw;

    public PhyllosensScoutAction(PhyllosensEntity mob) {
        this.mob = mob;
    }

    /** 始めてからの tick。 */
    public int ticks() {
        return this.ticks;
    }

    @Override
    public boolean canStart() {
        return this.mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_IDLE
                && this.mob.onGround() && !this.mob.isInWater();
    }

    @Override
    public void start(ServerLevel level) {
        this.ticks = 0;
        this.markTimer = 0;
        this.lockedYaw = this.mob.getYRot();
        this.field = new InvaderSporeField(this.mob.blockPosition(),
                this.mob.sporeCorruptionRadius(), InvaderSporeField.defaultInterval(), this.mob.getRandom());
        this.mob.getNavigation().stop();
        this.mob.setActionState(PhyllosensEntity.STATE_SCOUT);
        level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.0F, 1.4F);
    }

    @Override
    public void tick(ServerLevel level) {
        this.ticks++;
        // 索敵中は一歩も動かない。水平の速度は PhyllosensEntity#travel でも潰しているが、
        // 経路と入力はここで止めるのが本筋。
        this.mob.getNavigation().stop();
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
        // 向きも固定する。首を真上に向けて根を張っている姿なので、体だけ回っていると台無しになる。
        this.mob.setYRot(this.lockedYaw);
        this.mob.yRotO = this.lockedYaw;
        this.mob.setYBodyRot(this.lockedYaw);
        this.mob.setYHeadRot(this.lockedYaw);
        this.mob.yHeadRotO = this.lockedYaw;
        this.mob.setXRot(0.0F);
        this.mob.xRotO = 0.0F;

        if (this.ticks % 3 == 0) {
            scatterSpores(level);
        }
        if (this.ticks % INFECT_INTERVAL == 0) {
            infectNearby();
        }
        if (this.field != null) {
            this.field.spread(level, this.mob.blockPosition());
        }
        if (--this.markTimer <= 0) {
            this.markTimer = Math.max(1, OvergrownVegetationConfig.COMMON.phyllosensScanIntervalTicks.get());
            broadcast(level);
        }
    }

    /** 時間いっぱい続けた(または水に落ちた)。 */
    @Override
    public boolean isFinished() {
        return this.ticks >= OvergrownVegetationConfig.COMMON.phyllosensScoutDurationTicks.get() || this.mob.isInWater();
    }

    /** 終える。途中でも時間いっぱいでも同じ(数え直しからやり直す = {@link PhyllosensEntity#onScoutFinished})。 */
    @Override
    public void stop(ServerLevel level, boolean completed) {
        this.field = null;
        if (this.mob.getActionState() == PhyllosensEntity.STATE_SCOUT) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
        this.mob.onScoutFinished();
    }

    /**
     * 2 つの印を広範囲へ配る。ここがこの Mob の本体。
     *
     * <p>プレイヤー側の走査は {@code level.players()} を舐めるだけなので 512 ブロックでも安い。
     * <b>重いのは味方側</b>(256 ブロックの箱で全エンティティを見る)なので、
     * {@code phyllosensScanIntervalTicks} で間隔を空けてある。
     */
    private void broadcast(ServerLevel level) {
        double watchRange = OvergrownVegetationConfig.COMMON.phyllosensScoutWatchRange.get();
        double guideRange = OvergrownVegetationConfig.COMMON.phyllosensScoutGuidanceRange.get();
        int marked = PhyllosensInfluence.markPlayers(level, this.mob, watchRange);
        PhyllosensInfluence.guideAllies(level, this.mob, guideRange);
        if (marked > 0 && this.ticks <= this.markTimer + 1) {
            // 最初の一巡で誰かを掴んだときだけ鳴らす(30 秒鳴りっぱなしにしない)。
            level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.2F, 0.8F);
        }
    }

    /**
     * 花から胞子を吹き上げる。<b>遠くからでも見えるように送る</b>(ユーザー要望 2026-09-14)。
     * {@code ServerLevel#sendParticles} の素の呼び方は 32 ブロックで切られるので、プレイヤー 1 人ずつに
     * {@code longDistance=true} で送って 512 ブロックまで届かせる。{@value #PLUME_INTERVAL} tick ごとに遠景用の柱も足す。
     */
    private void scatterSpores(ServerLevel level) {
        double x = this.mob.getX();
        double y = this.mob.getY() + this.mob.getBbHeight() * 0.9D;
        double z = this.mob.getZ();
        emit(level, ModParticles.FINE_SPORE.get(), x, y, z, 34, 1.4D, 0.6D, 1.4D, 0.02D);
        emit(level, ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 6, 1.8D, 0.8D, 1.8D, 0.0D);
        // 足元にも降り積もるぶん。
        emit(level, ModParticles.FINE_SPORE.get(),
                x, this.mob.getY() + 0.2D, z, 22, 2.2D, 0.3D, 2.2D, 0.01D);
        if (this.ticks % PLUME_INTERVAL == 0) {
            // 遠景用の柱。花の上へ吹き上げる。
            emit(level, ModParticles.FINE_SPORE.get(), x, y + 1.5D, z, 46, 3.2D, 3.0D, 3.2D, 0.06D);
            emit(level, ParticleTypes.SPORE_BLOSSOM_AIR, x, y + 3.0D, z, 10, 4.0D, 3.5D, 4.0D, 0.0D);
        }
    }

    /** 512 ブロック先まで届く粒子の送り方({@code longDistance} は「プレイヤーを 1 人指定する」オーバーロードだけ)。 */
    private static void emit(ServerLevel level, ParticleOptions type, double x, double y, double z,
                             int count, double dx, double dy, double dz, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, type, true, x, y, z, count, dx, dy, dz, speed);
        }
    }

    private void infectNearby() {
        double radius = OvergrownVegetationConfig.COMMON.invaderSporeInfectRadius.get();
        if (radius <= 0.0D) {
            return;
        }
        AABB box = this.mob.getBoundingBox().inflate(radius);
        for (LivingEntity entity : this.mob.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity != this.mob && entity.isAlive() && MobGroup.of(entity) == null
                    && !(entity instanceof net.minecraft.world.entity.player.Player)) {
                SporeInfectionEffects.infect(entity, 0);
            }
        }
    }
}
