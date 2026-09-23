package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.EnumSet;

/**
 * フィロセンスの<b>ビーム</b>(ユーザー要望 2026-09-14)。
 * ティタニカの精密ビームと同じ組み立てで、<b>より細く・当たり判定も狭い</b>。
 *
 * <h2>撃つまでの流れ</h2>
 * <ol>
 *     <li><b>溜め</b> {@code phyllosensBeamChargeTicks}(既定 60 = 3 秒) —
 *     頭を相手へ向けて追い続け、<b>花を大きく開く</b>({@code beam_charge})。相手が動けば狙点も動く。</li>
 *     <li><b>狙点固定</b> {@code phyllosensBeamLockTicks}(既定 6 = 0.3 秒) —
 *     ここで狙点が<b>その場に固定</b>される。以降どれだけ動かれても狙点は動かない
 *     (＝横へ走れば避けられる)。</li>
 *     <li><b>発射</b> — 固定した点へ向けて 1 回だけ判定。線の近く
 *     {@code phyllosensBeamRadius}(既定 0.45)に居た生き物へ
 *     {@code phyllosensBeamDamage}(既定 20)。防具は効く。
 *     撃った瞬間は<b>反動で首がのけぞる</b>({@code beam_fire})。</li>
 * </ol>
 *
 * <p>撃てるのは<b>索敵中以外</b>({@code STATE_SCOUT} でないとき)だけ。射程は
 * {@code phyllosensBeamRange}(<b>既定 80 ブロック</b>、2026-09-14 に 40 から倍)で、
 * それより遠い相手には近づいてから撃つ。{@code mobs.json} の {@code followRange} も 80 に揃えてある
 * —— そちらが小さいと、射程だけ伸ばしても<b>遠い相手はそもそも標的にならない</b>。
 *
 * <p><b>溜めに入ったら撃ち終わるまで一歩も動かない</b>(ユーザー要望 2026-09-14)。
 * 経路と入力を潰すだけでは、ノックバックや味方との押し合いでじりじりずれるので、
 * 索敵中と同じ {@link PhyllosensEntity#isRooted()} の扱いに乗せてある
 * ({@code travel} / {@code isPushable} / {@code pushEntities} の 3 段構え)。
 * 動いてよいのは<b>射程の外から歩いて近づいている間だけ</b>。
 *
 * <p>グループ1〜10 には当たらない({@link #canBeHit}) — 線の近くに居るだけの味方を
 * 巻き添えにしないため。取りこぼしても
 * {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} が最後に落とす。
 */
public class PhyllosensBeamGoal extends Goal {

    /**
     * 発射の姿勢を保つ長さ(tick)。{@code beam_fire}(0.7 秒 = 14 tick)の<b>のけぞりと戻り</b>を
     * 最後まで見せるための長さで、<b>光線そのものは先に消える</b> —
     * {@code PhyllosensBeamRenderer.FIRE_TICKS}(8)で細くなって消え、残りの 6 tick は
     * 体勢を立て直しているところ。
     */
    private static final int FIRE_TICKS = 14;
    /** 狙点から先へ伸ばす長さ(ブロック)。地形に刺さって見えるように。 */
    private static final double OVERSHOOT = 6.0D;
    /** 経路を引き直す間隔(tick)。 */
    private static final int REPATH_INTERVAL = 20;

    private final PhyllosensEntity mob;

    private LivingEntity victim;
    private int phaseTicks;
    private int fireTicks;
    private int repathTimer;
    private int nextTime;
    /** 固定した狙点(相手の目の高さ)。 */
    private Vec3 aim = Vec3.ZERO;

    public PhyllosensBeamGoal(PhyllosensEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel) || this.mob.tickCount < this.nextTime) {
            return false;
        }
        if (this.mob.getActionState() == PhyllosensEntity.STATE_SCOUT) {
            // 索敵中は撃たない(ユーザー指定)。
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive() && canBeHit(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.victim == null || !this.victim.isAlive()) {
            return false;
        }
        // 照射まで進んだら、見せ終わるまでは続ける。
        if (this.mob.getBeamPhase() == PhyllosensEntity.BEAM_FIRE) {
            return this.fireTicks < FIRE_TICKS;
        }
        return true;
    }

    @Override
    public void start() {
        this.victim = this.mob.getTarget();
        this.phaseTicks = 0;
        this.fireTicks = 0;
        this.repathTimer = 0;
        this.mob.setActionState(PhyllosensEntity.STATE_BEAM);
        this.mob.setBeamPhase(PhyllosensEntity.BEAM_CHARGE);
        this.mob.setBeamApproaching(false);
        this.mob.getNavigation().stop();
        if (this.mob.level() instanceof ServerLevel level) {
            level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                    SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.0F, 1.1F);
        }
    }

    @Override
    public void stop() {
        this.victim = null;
        this.mob.setBeamApproaching(false);
        this.mob.setBeamPhase(PhyllosensEntity.BEAM_NONE);
        if (this.mob.getActionState() == PhyllosensEntity.STATE_BEAM) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
        this.nextTime = this.mob.tickCount
                + MobStats.beam("phyllosens").cooldownTicks();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.victim == null || !(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        this.phaseTicks++;
        this.mob.getLookControl().setLookAt(this.victim, 30.0F, 30.0F);

        MobStat.Beam cfg = MobStats.beam("phyllosens");
        double range = cfg.range();
        int chargeTicks = cfg.chargeTicks();
        int lockTicks = cfg.lockTicks();

        switch (this.mob.getBeamPhase()) {
            case PhyllosensEntity.BEAM_CHARGE -> {
                // 射程の外なら近づく。溜めの時間は射程内に入ってから数える。
                if (this.mob.distanceTo(this.victim) > range) {
                    this.mob.setBeamApproaching(true);
                    approach();
                    this.phaseTicks = 0;
                    this.mob.setBeamTarget(eyesOf(this.victim));
                    return;
                }
                root();
                this.mob.setBeamTarget(eyesOf(this.victim));
                if (this.phaseTicks >= chargeTicks) {
                    // ここで狙点を固定する。以降は相手が動いても狙点は動かない。
                    this.aim = eyesOf(this.victim);
                    this.mob.setBeamTarget(this.aim);
                    this.mob.setBeamPhase(PhyllosensEntity.BEAM_LOCKED);
                    this.phaseTicks = 0;
                }
            }
            case PhyllosensEntity.BEAM_LOCKED -> {
                root();
                if (this.phaseTicks >= lockTicks) {
                    fire(level);
                    this.mob.setBeamPhase(PhyllosensEntity.BEAM_FIRE);
                    this.phaseTicks = 0;
                }
            }
            case PhyllosensEntity.BEAM_FIRE -> {
                root();
                this.fireTicks++;
            }
            default -> {
            }
        }
    }

    /**
     * <b>その場に根を張る</b>(ユーザー要望 2026-09-14:「チャージ中は動かないように」)。
     *
     * <p>経路・移動入力・水平の速度をここで潰す。ノックバックや押し合いのような
     * {@code serverAiStep} より後に入ってくる力は素通りするので、そちらは
     * {@link PhyllosensEntity#travel} と {@code isPushable} / {@code pushEntities} が受け持つ
     * ({@link PhyllosensEntity#isRooted()} が {@code STATE_BEAM} を含んでいる)。
     */
    private void root() {
        this.mob.setBeamApproaching(false);
        this.mob.getNavigation().stop();
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
    }

    /** 射程の外に居るあいだは近づく。 */
    private void approach() {
        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.mob.getNavigation().moveTo(this.victim, 1.0D);
        }
    }

    private static Vec3 eyesOf(LivingEntity entity) {
        return entity.getEyePosition();
    }

    /**
     * 固定した狙点へ 1 回だけ判定する。線分に近い生き物すべてに入る(狙った相手だけではない)
     * ので、避けた相手の後ろに居た者は巻き込まれる。
     */
    private void fire(ServerLevel level) {
        Vec3 from = this.mob.beamOrigin();
        Vec3 axis = this.aim.subtract(from);
        if (axis.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 dir = axis.normalize();
        Vec3 far = this.aim.add(dir.scale(OVERSHOOT));
        // 地形で切る(見た目と、当たり判定の箱を膨らませないため)。
        HitResult hit = level.clip(new ClipContext(from, far, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        Vec3 end = hit.getType() == HitResult.Type.MISS ? far : hit.getLocation();
        this.mob.setBeamTarget(end);

        MobStat.Beam cfg = MobStats.beam("phyllosens");
        double radius = cfg.radius();
        float damage = (float) cfg.damage();
        if (damage > 0.0F) {
            DamageSource source = ModDamageTypes.phyllosensBeam(level, this.mob);
            AABB box = new AABB(from, end).inflate(radius + 1.0D);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, this::canBeHit)) {
                double d = distanceToSegment(entity.getBoundingBox().getCenter(), from, end);
                if (d <= radius + entity.getBbWidth() * 0.5D) {
                    entity.invulnerableTime = 0;
                    entity.hurt(source, damage);
                }
            }
        }
        level.playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 0.9F, 1.6F);
    }

    /**
     * 撃ってよい相手か。グループ1〜10(汚染側)と自分自身は外す。
     *
     * <p>ただし<b>グループ1〜3 は獲物なので撃つ</b>(2026-09-17)。グループ4 がグループ1〜3 を狩る
     * 関係は 2026-09-15 に入れたが、ここの足切りが「グループに属していれば味方」のままだったので、
     * <b>フィロセンスは獲物を標的に掴んだまま一発も撃たなかった</b> ——
     * 近接攻撃も持たない種なので、その間まるごと固まっていた
     * (ヴェロフィルの胞子爆弾が獲物に当たらなかったのと同じ原因)。
     *
     * <p>ビームは線に沿って全員に入るが、通るのはグループ1〜3 とグループ外だけなので、
     * <b>群れの仲間(グループ4)を貫いても当たらない</b>。
     */
    private boolean canBeHit(LivingEntity entity) {
        if (entity == this.mob) {
            return false;
        }
        return MobGroup.of(entity) == null || Predation.isInvaderPrey(this.mob, entity);
    }

    /** 点 {@code p} から線分 {@code a}-{@code b} への距離。 */
    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();
        if (lenSq < 1.0E-6D) {
            return p.distanceTo(a);
        }
        double t = Math.max(0.0D, Math.min(1.0D, p.subtract(a).dot(ab) / lenSq));
        return p.distanceTo(a.add(ab.scale(t)));
    }
}
