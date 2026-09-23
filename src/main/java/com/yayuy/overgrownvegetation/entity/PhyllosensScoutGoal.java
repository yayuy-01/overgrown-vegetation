package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * フィロセンスの<b>索敵行動</b>(ユーザー要望 2026-09-14、作り直し版)。
 *
 * <h2>入る条件</h2>
 * <p>ひとつだけ —— <b>半径 {@code phyllosensAuraRange}(既定 32)にプレイヤーが
 * {@code phyllosensQuietTicks}(既定 1200 = 1 分)居なかったこと</b>
 * ({@link PhyllosensEntity#canBeginScout()})。見るのは<b>プレイヤーだけ</b>で、
 * 牛やコウモリは数に入らない。
 *
 * <h2>やること</h2>
 * <p>その場に留まって首を大きく上げ、花を開き({@code scout} アニメーション)、胞子を撒き散らして
 * 足元を汚染しながら、<b>ふだんよりずっと広い範囲へ 2 つの印を配る</b>:
 * <ul>
 *     <li><b>視線</b>({@code phyllosens_watch}) → 半径 {@code phyllosensScoutWatchRange}
 *     (既定 512)のプレイヤー全員。</li>
 *     <li><b>誘導</b>({@code phyllosens_guidance}) → 半径 {@code phyllosensScoutGuidanceRange}
 *     (既定 256)の味方(グループ1〜10)全員。</li>
 * </ul>
 * これを {@code phyllosensScoutDurationTicks}(既定 600 = 30 秒)続ける。
 * 配り直しは {@code phyllosensScanIntervalTicks} ごと —— 256 ブロックの走査は重いので、
 * <b>毎 tick はやらない</b>(読み込まれているチャンクぶんの全エンティティを舐めることになる)。
 *
 * <p>印を配るだけで、フィロセンス自身は誰も追わない。襲いに行くのは誘導を受けた味方
 * ({@link GuidedHuntGoal})で、<b>一体ずつ突っ込まず合流してから</b>向かう。
 *
 * <p>途中で終わるのは<b>殴られたとき</b>と<b>32 ブロックにプレイヤーが入ってきたとき</b>だけ。
 * 前者は棒立ちのまま殴られ続けないため、後者は「近くに居るならもう配る必要がない
 * (ふだんの輪が届いている)」ため。
 */
public class PhyllosensScoutGoal extends Goal {

    /** 胞子感染の判定間隔(tick)。 */
    private static final int INFECT_INTERVAL = 20;
    /** 遠景用の胞子の柱を吹き上げる間隔(tick)。 */
    private static final int PLUME_INTERVAL = 9;
    /** 殴られてから索敵に戻らない時間(tick)。 */
    private static final int HURT_LOCKOUT = 100;

    private final PhyllosensEntity mob;

    private int ticks;
    private int markTimer;
    private InvaderSporeField field;
    /** 索敵に入った瞬間の向き。30 秒のあいだこの向きに固定する。 */
    private float lockedYaw;

    public PhyllosensScoutGoal(PhyllosensEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel)) {
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        if (!this.mob.onGround() || this.mob.isInWater()) {
            return false;
        }
        if (recentlyHurt()) {
            return false;
        }
        return this.mob.canBeginScout();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mob.isInWater() || recentlyHurt()) {
            return false;
        }
        // 近くまでプレイヤーが来たらやめる(ふだんの輪が届いているので配る意味がない)。
        if (this.mob.getPlayerFreeTicks() <= 0) {
            return false;
        }
        return this.ticks < OvergrownVegetationConfig.COMMON.phyllosensScoutDurationTicks.get();
    }

    /** 直近 {@value #HURT_LOCKOUT} tick 以内に殴られたか。殴られている間は棒立ちしない。 */
    private boolean recentlyHurt() {
        return this.mob.getLastHurtByMob() != null
                && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < HURT_LOCKOUT;
    }

    @Override
    public void start() {
        this.ticks = 0;
        this.markTimer = 0;
        this.lockedYaw = this.mob.getYRot();
        this.field = new InvaderSporeField(this.mob.blockPosition(),
                this.mob.sporeCorruptionRadius(), InvaderSporeField.defaultInterval(), this.mob.getRandom());
        this.mob.getNavigation().stop();
        this.mob.setActionState(PhyllosensEntity.STATE_SCOUT);
        if (this.mob.level() instanceof ServerLevel level) {
            level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.0F, 1.4F);
        }
    }

    @Override
    public void stop() {
        this.field = null;
        if (this.mob.getActionState() == PhyllosensEntity.STATE_SCOUT) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
        this.mob.onScoutFinished();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
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

        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
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

    /**
     * 2 つの印を広範囲へ配る。ここがこの Mob の本体。
     *
     * <p>プレイヤー側の走査は {@code level.players()} を舐めるだけなので 512 ブロックでも安い。
     * <b>重いのは味方側</b>(256 ブロックの箱で全エンティティを見る)なので、
     * {@code phyllosensScanIntervalTicks} で間隔を空けてある。とはいえ実際に見るのは
     * <b>読み込まれているチャンクの中だけ</b>なので、上限は「そのサーバーで読み込み中の
     * エンティティ数」に収まる。
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
     * 花から胞子を吹き上げる。汚染シダと同じ粒子を、首の高さから広めに。
     *
     * <p><b>遠くからでも見えるように送る</b>(ユーザー要望 2026-09-14)。
     * {@code ServerLevel#sendParticles} の素の呼び方は<b>32 ブロックで切られる</b>ので
     * ({@code longDistance=false})、それだと「索敵しているのが遠目に分かる」にならない。
     * ここではプレイヤー 1 人ずつに {@code longDistance=true} で送って 512 ブロックまで届かせる。
     *
     * <p>近くで見たときの細かい霧に加えて、{@value #PLUME_INTERVAL} tick ごとに
     * <b>高く広い柱</b>を足してある —— 遠景では細かい粒は点にもならないので、
     * 「あそこで何か撒いている」と分かるだけの大きさが要る。
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

    /**
     * 512 ブロック先まで届く粒子の送り方。{@code longDistance} を立てられるのは
     * 「プレイヤーを 1 人指定する」オーバーロードだけなので、自分で回す。
     */
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
