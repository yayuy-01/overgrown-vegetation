package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * クリペイムネスが標的を襲う Goal(2026-11-15 段 3 = 能力。「G4〜G6 の能力の決定(11-14)」のクリペイムネスの節と、Vault「クリペイムネス」の
 * 6・7 節(アニメの一覧)・「能力の側の決定」)。技の段はここで進め、描画へは {@link ClipeimnesEntity#setAct} で送る。MOVE と LOOK を握る。
 *
 * <h2>技【時刻はアニメと対・数値は仮】</h2>
 * <ul>
 *   <li><b>盾打ち</b>(通常 ①): 前 ±{@link #BASH_DEG} 度・体の中心から {@link #BASH_REACH} ブロック以内の相手へ。{@code bash_<r|l>_windup}(補間 3 + 12)→
 *       {@code bash_<r|l>} の 2 tick 目に盾の面(前 2.8)で打ち、前へ弾き飛ばす → {@code bash_<r|l>_recover}(14 tick の隙)。続けて打つたびに左右を入れ替える
 *       (決定 11-15)。光らない。</li>
 *   <li><b>鉤の抱え込み</b>(通常 ②): 前 ±{@link #GRAB_DEG} 度・{@link #GRAB_REACH} ブロック以内の相手へ。{@code grab_windup}(補間 3 + 16。腕が開き葉脈と口が光る)
 *       → {@code grab} の 3 tick 目に鉤の輪(前 2.1)の中にいれば抱える → {@code hold}(20 tick)× 3 = 3 秒締め、締めるたび(各巡の 5・15)に噛む(決定 11-15)
 *       → 放す。外から {@link #HOLD_BREAK} の傷を受けたら放して {@code hold_recover}(22 tick のよろけ)。外したら {@code grab_recover}(16 tick)。</li>
 *   <li><b>盾で詰める</b>(通常 ③): 技の合間、相手が遠い・飛び道具を手にしている間は盾を構えて寄る({@link ClipeimnesEntity#setGuard})。
 *       構えている間は前 ±{@link ClipeimnesEntity#GUARD_DEG} 度から来た矢・近接を弾き(横・後ろは通る)、弾くたびに溜めが 1 段増える。</li>
 *   <li><b>弾除け</b>(決定 11-15): ほかの植物側の Mob が同じ相手を狙っていれば、相手と仲間の間へ割り込み、相手へ盾を向けて構える({@link #coverPoint})。
 *       相手が届く所に来れば盾打ち・抱え込みに移る。</li>
 *   <li><b>溜めの解放</b>(特殊): 溜めが {@link ClipeimnesEntity#CHARGE_MAX} 段で満ち、相手が前 ±{@link #RELEASE_DEG} 度・{@link #RELEASE_REACH} ブロック以内なら、
 *       {@code release_windup}(補間 3 + 14。光が満ちる)→ {@code release} の 2 tick 目に両の盾を打ち合わせ、前方の扇へ衝撃波 → {@code release_recover}
 *       (26 tick の隙。溜めは 0)。</li>
 * </ul>
 */
final class ClipeimnesHuntGoal extends Goal {

    /** 盾打ちの届く所(体の中心から、ブロック)・向き。当たりの箱は盾の面(前 2.8)のまわり【仮】。 */
    static final float BASH_DEG = 40.0F;
    static final double BASH_REACH = 3.8D, BASH_F_MIN = 1.4D, BASH_F_MAX = 4.2D, BASH_LAT = 1.8D;
    static final double BASH_PUSH = 1.4D, BASH_LIFT = 0.35D;
    /** 抱え込みの届く所・捕まえたとみる箱(閉じた鉤の先 前 2.1・左右 ±0.35 のまわり)【仮】。 */
    static final float GRAB_DEG = 30.0F;
    static final double GRAB_REACH = 3.4D, GRAB_F_MIN = 1.0D, GRAB_F_MAX = 3.4D, GRAB_LAT = 1.2D;
    /** 抱えた相手を置く所(体の中心から前へ、ブロック。鉤の輪の中)。 */
    static final double HOLD_FORWARD = 2.2D;
    /** 締めの傷(1 回)・締めの時刻(hold の 1 巡の中)・巡の数・放す傷【仮】。 */
    static final float SQUEEZE_DAMAGE = 2.0F;
    static final int[] SQUEEZES = {5, 15};
    static final int HOLD_LOOPS = 3;
    static final float HOLD_BREAK = 6.0F;
    /** 溜めの解放: 届く所・向き・扇(前へ、ブロック・度)・傷・弾き【仮】。 */
    static final float RELEASE_DEG = 45.0F;
    static final double RELEASE_REACH = 6.0D, WAVE_REACH = 7.0D;
    static final float WAVE_DEG = 45.0F, WAVE_DAMAGE = 14.0F;
    static final double WAVE_PUSH = 2.0D, WAVE_LIFT = 0.5D;
    /** 盾を構える: 相手がこれ(ブロック)より遠い間【仮】。 */
    static final double GUARD_FAR = 6.0D;
    /** 時刻(tick)。アニメの長さと対(前触れは補間 3 込み)。 */
    static final int LEAD = 3, BASH_WINDUP = LEAD + 12, BASH = 5, BASH_HIT = 2, BASH_RECOVER = 14,
            GRAB_WINDUP = LEAD + 16, GRAB = 5, GRAB_CATCH = 3, HOLD = 20, HOLD_RECOVER = 22, GRAB_RECOVER = 16,
            RELEASE_WINDUP = LEAD + 14, RELEASE = 6, RELEASE_HIT = 2, RELEASE_RECOVER = 26;
    /** 待ち(tick)【仮】。 */
    static final int BASH_COOLDOWN = 30, GRAB_COOLDOWN = 120;
    /** 抱え込みを選ぶ割合(両方出せるとき)【仮】。 */
    static final float GRAB_CHANCE = 0.35F;
    /** 追う速さ(移動の速さに掛ける)【仮】。 */
    static final double CHASE_SPEED = 1.0D;

    private final ClipeimnesEntity mob;
    private int act = ClipeimnesEntity.ACT_NONE, actTicks, loops;
    private int bashCd, grabCd, repath;
    /** 次の盾打ちが左の板か(打つたびに入れ替える。最初は右)。 */
    private boolean nextLeft;
    float holdDamage;
    /** テスト用: 次の判断で出す技(ACT_NONE なら選ぶ)。 */
    int forceAct = ClipeimnesEntity.ACT_NONE;
    @Nullable
    private LivingEntity victim;

    ClipeimnesHuntGoal(ClipeimnesEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = this.mob.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.act != ClipeimnesEntity.ACT_NONE || canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (this.act != ClipeimnesEntity.ACT_NONE) endAct();
        this.mob.setGuard(false);
        this.mob.getNavigation().stop();
    }

    int act() {
        return this.act;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        LivingEntity t = this.mob.getTarget();
        if (this.bashCd > 0) this.bashCd--;
        if (this.grabCd > 0) this.grabCd--;
        if (this.act != ClipeimnesEntity.ACT_NONE) {
            this.actTicks++;
            tickAct();
            return;
        }
        if (t == null || !t.isAlive()) return;
        decide(t);
    }

    /** 体から見た相手の位置 {前, 右, 上, 水平の距離}(ブロック)。 */
    double[] local(LivingEntity t) {
        float r = this.mob.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r), rx = -Mth.cos(r), rz = -Mth.sin(r);
        double dx = t.getX() - this.mob.getX(), dz = t.getZ() - this.mob.getZ();
        return new double[]{dx * fx + dz * fz, dx * rx + dz * rz, t.getY() - this.mob.getY(), Math.sqrt(dx * dx + dz * dz)};
    }

    private float yawTo(LivingEntity t) {
        return (float) (Mth.atan2(t.getZ() - this.mob.getZ(), t.getX() - this.mob.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private void decide(LivingEntity t) {
        double[] l = local(t);
        double sc = this.mob.getScale();
        float off = Math.abs(Mth.wrapDegrees(yawTo(t) - this.mob.getYRot()));
        int forced = this.forceAct;
        this.forceAct = ClipeimnesEntity.ACT_NONE;
        // 特殊: 溜めが満ちていて、相手が前の届く所
        if (forced == ClipeimnesEntity.ACT_RELEASE_WINDUP
                || this.mob.charge() >= ClipeimnesEntity.CHARGE_MAX && off <= RELEASE_DEG && l[3] <= RELEASE_REACH * sc && Math.abs(l[2]) < 2.5D) {
            startAttack(ClipeimnesEntity.ACT_RELEASE_WINDUP, t);
            return;
        }
        boolean canGrab = this.grabCd == 0 && off <= GRAB_DEG && l[3] <= GRAB_REACH * sc && Math.abs(l[2]) < 2.0D && ClipeimnesEntity.canHold(t);
        boolean canBash = this.bashCd == 0 && off <= BASH_DEG && l[3] <= BASH_REACH * sc && Math.abs(l[2]) < 2.5D;
        if (forced == ClipeimnesEntity.ACT_GRAB_WINDUP || canGrab && (!canBash || this.mob.devPreferGrab() || this.mob.getRandom().nextFloat() < GRAB_CHANCE)) {
            startAttack(ClipeimnesEntity.ACT_GRAB_WINDUP, t);
            return;
        }
        if (forced == ClipeimnesEntity.ACT_BASH_R_WINDUP || canBash) {
            startAttack(this.nextLeft ? ClipeimnesEntity.ACT_BASH_L_WINDUP : ClipeimnesEntity.ACT_BASH_R_WINDUP, t);
            this.nextLeft = !this.nextLeft;
            return;
        }
        approach(t, l);
    }

    /**
     * 寄る: 向きを相手へ。遠い・飛び道具を手にした相手には盾を構えたまま寄る(盾で詰める)。
     * ほかの植物側の Mob(仲間)が同じ相手を狙っていれば、相手と仲間の間へ割り込んで盾を構える(弾除け。決定 11-15)。
     */
    private void approach(LivingEntity t, double[] l) {
        net.minecraft.world.phys.Vec3 cover = coverPoint(t);
        this.mob.wantFacing(yawTo(t), LongBodyRunner.TURN_HUNT);
        if (cover != null) {
            this.mob.setGuard(true);
            this.covering = true;
            if (--this.repath <= 0) {
                this.repath = 10;
                if (cover.distanceToSqr(this.mob.position()) > COVER_NEAR * COVER_NEAR) {
                    this.mob.getNavigation().moveTo(cover.x, cover.y, cover.z, CHASE_SPEED);
                } else {
                    this.mob.getNavigation().stop();   // 割り込めた: 相手へ盾を向けて止まる
                }
            }
            return;
        }
        this.covering = false;
        this.mob.setGuard(shouldGuard(t, l[3]));
        if (--this.repath <= 0) {
            this.repath = 10;
            this.mob.getNavigation().moveTo(t, CHASE_SPEED);
        }
    }

    /** 弾除け: 仲間を探す範囲(ブロック)・割り込む所(仲間から相手の方へ、仲間と相手の距離の割合と上限)・着いたとみる距離・探し直す間隔(tick)【仮】。 */
    static final double COVER_RANGE = 24.0D, COVER_SHARE = 0.4D, COVER_MAX = 3.5D, COVER_NEAR = 1.2D;
    static final int COVER_EVERY = 20;
    /** 割り込む相手は、仲間がこれ(ブロック)より相手から離れているときだけ(近すぎる仲間はもう組み合っている)【仮】。 */
    static final double COVER_MIN_GAP = 4.0D;
    private int coverScan;
    @Nullable
    private net.minecraft.world.entity.Mob ally;
    /** 今、弾除けで割り込みに行っているか(テスト・確かめ用)。 */
    boolean covering;

    /**
     * 割り込む所: 相手を狙っている仲間(植物側の Mob。クリペイムネス自身を除く)のうち、相手に一番近い者と相手の間で、仲間から相手の方へ
     * 仲間と相手の距離の {@link #COVER_SHARE}(最大 {@link #COVER_MAX} ブロック)の所。仲間がいない・自分のほうが相手に近く間にいるなら null。
     */
    @Nullable
    net.minecraft.world.phys.Vec3 coverPoint(LivingEntity t) {
        if (--this.coverScan <= 0) {
            this.coverScan = COVER_EVERY;
            this.ally = null;
            double best = Double.MAX_VALUE;
            for (net.minecraft.world.entity.Mob m : this.mob.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                    this.mob.getBoundingBox().inflate(COVER_RANGE), m -> m != this.mob && m.isAlive() && m instanceof com.yayuy.overgrownvegetation.phase.ParasitizedMob
                            && !(m instanceof ClipeimnesEntity) && m.getTarget() == t)) {
                double d = m.distanceToSqr(t);
                if (d < best) {
                    best = d;
                    this.ally = m;
                }
            }
        }
        net.minecraft.world.entity.Mob a = this.ally;
        if (a == null || !a.isAlive() || a.getTarget() != t) return null;
        net.minecraft.world.phys.Vec3 ap = a.position(), tp = t.position();
        net.minecraft.world.phys.Vec3 at = tp.subtract(ap);
        double gap = Math.sqrt(at.x * at.x + at.z * at.z);
        if (gap < COVER_MIN_GAP) return null;
        double go = Math.min(COVER_MAX, gap * COVER_SHARE);
        return new net.minecraft.world.phys.Vec3(ap.x + at.x / gap * go, ap.y, ap.z + at.z / gap * go);
    }

    /** 盾を構える: 相手が {@link #GUARD_FAR} ブロックより遠いか、弓・弩・トライデントを手にしている間【仮】。 */
    static boolean shouldGuard(LivingEntity t, double dist) {
        return dist > GUARD_FAR || isRanged(t.getMainHandItem()) || isRanged(t.getOffhandItem());
    }

    private static boolean isRanged(ItemStack s) {
        return s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem || s.getItem() instanceof TridentItem;
    }

    // ------------------------------------------------------------------ 技の段

    private void startAttack(int act, LivingEntity t) {
        this.victim = t;
        this.mob.dropGuardNow();   // 全身の技の前に盾の層を外す(腕がぶつかる)
        startAct(act);
    }

    private void startAct(int act) {
        this.act = act;
        this.actTicks = 0;
        this.mob.getNavigation().stop();
        this.mob.setAct(act);
    }

    private void endAct() {
        if (this.mob.isHolding()) this.mob.releaseHold(0.3D);
        this.act = ClipeimnesEntity.ACT_NONE;
        this.actTicks = 0;
        this.victim = null;
        this.mob.setAct(ClipeimnesEntity.ACT_NONE);
    }

    private void face() {
        if (this.victim != null && this.victim.isAlive()) this.mob.wantFacing(yawTo(this.victim), LongBodyRunner.TURN_ACTION);
    }

    private void tickAct() {
        switch (this.act) {
            case ClipeimnesEntity.ACT_BASH_R_WINDUP, ClipeimnesEntity.ACT_BASH_L_WINDUP -> {
                face();
                if (this.actTicks >= BASH_WINDUP) startAct(this.act + 1);
            }
            case ClipeimnesEntity.ACT_BASH_R, ClipeimnesEntity.ACT_BASH_L -> {
                if (this.actTicks == BASH_HIT) this.mob.bash(BASH_F_MIN, BASH_F_MAX, BASH_LAT, BASH_PUSH, BASH_LIFT);
                if (this.actTicks >= BASH) startAct(this.act + 1);
            }
            case ClipeimnesEntity.ACT_BASH_R_RECOVER, ClipeimnesEntity.ACT_BASH_L_RECOVER -> {
                if (this.actTicks >= BASH_RECOVER) {
                    endAct();
                    this.bashCd = BASH_COOLDOWN;
                }
            }
            case ClipeimnesEntity.ACT_GRAB_WINDUP -> {
                face();
                if (this.actTicks >= GRAB_WINDUP) startAct(ClipeimnesEntity.ACT_GRAB);
            }
            case ClipeimnesEntity.ACT_GRAB -> {
                if (this.actTicks == GRAB_CATCH) {
                    LivingEntity v = this.victim;
                    if (v != null && v.isAlive() && inGrabZone(v) && this.mob.hold(v)) {
                        this.holdDamage = 0.0F;
                        this.loops = 0;
                        startAct(ClipeimnesEntity.ACT_HOLD);
                        return;
                    }
                }
                if (this.actTicks >= GRAB) {
                    startAct(ClipeimnesEntity.ACT_GRAB_RECOVER);
                }
            }
            case ClipeimnesEntity.ACT_HOLD -> {
                LivingEntity v = this.mob.held();
                if (v == null || !v.isAlive() || this.holdDamage >= HOLD_BREAK) {
                    this.mob.releaseHold(0.5D);
                    startAct(ClipeimnesEntity.ACT_HOLD_RECOVER);
                    this.grabCd = GRAB_COOLDOWN;
                    return;
                }
                int inLoop = this.actTicks % HOLD;
                for (int s : SQUEEZES) {
                    if (inLoop == s) this.mob.squeeze(v, SQUEEZE_DAMAGE);
                }
                if (this.actTicks >= HOLD * HOLD_LOOPS) {
                    // 締め終えた: 放す(よろけは無い。腕を開く隙は grab_recover)
                    this.mob.releaseHold(0.3D);
                    startAct(ClipeimnesEntity.ACT_GRAB_RECOVER);
                    this.grabCd = GRAB_COOLDOWN;
                }
            }
            case ClipeimnesEntity.ACT_HOLD_RECOVER -> {
                if (this.actTicks >= HOLD_RECOVER) endAct();
            }
            case ClipeimnesEntity.ACT_GRAB_RECOVER -> {
                if (this.actTicks >= GRAB_RECOVER) {
                    endAct();
                    if (this.grabCd == 0) this.grabCd = GRAB_COOLDOWN / 2;
                }
            }
            case ClipeimnesEntity.ACT_RELEASE_WINDUP -> {
                face();
                if (this.actTicks >= RELEASE_WINDUP) startAct(ClipeimnesEntity.ACT_RELEASE);
            }
            case ClipeimnesEntity.ACT_RELEASE -> {
                if (this.actTicks == RELEASE_HIT) {
                    this.mob.shockwave(WAVE_REACH, WAVE_DEG, WAVE_DAMAGE, WAVE_PUSH, WAVE_LIFT);
                    this.mob.resetCharge();
                }
                if (this.actTicks >= RELEASE) startAct(ClipeimnesEntity.ACT_RELEASE_RECOVER);
            }
            case ClipeimnesEntity.ACT_RELEASE_RECOVER -> {
                if (this.actTicks >= RELEASE_RECOVER) endAct();
            }
            default -> endAct();
        }
    }

    /** 相手が閉じた鉤の輪の中(捕まえられる所)にいるか。 */
    private boolean inGrabZone(LivingEntity t) {
        double[] l = local(t);
        double sc = this.mob.getScale();
        return l[0] >= GRAB_F_MIN * sc && l[0] <= GRAB_F_MAX * sc && Math.abs(l[1]) <= GRAB_LAT * sc && l[2] > -1.5D && l[2] < 2.0D;
    }
}
