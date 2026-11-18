package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * ピリムネスが標的を襲う Goal(2026-11-16 能力の段。「G4〜G6 の能力の決定(11-14)」のピリムネスの節と、Vault「ピリムネス」8 節(技のアニメの一覧・
 * 弾の tick と位置)・「技の能力の側の決定」(11-15))。技の段はここで進め、描画へは {@link PilimnesEntity#setAct} で送る。MOVE と LOOK を握る。
 * どの技も<b>止まって</b>出す(決定 11-15)。前触れは殴っても潰せない(決定 11-15。受けた傷で中断しない)。
 *
 * <h2>技【時刻はアニメと対・数値は仮】</h2>
 * <ul>
 *   <li><b>棘の弾き</b>(通常 ①、遠): {@link #FLICK_MIN}〜{@link #FLICK_MAX} ブロックの見える相手へ。{@code flick_windup}(補間 3 + 16。葉が逆立ち棘の列が光る)→
 *       {@code flick} の 2 tick 目にいちばん前の棘(sp0)を相手の足元へ 1 本 → {@code flick_recover}(14)。盾で受けなければ足が鈍る。</li>
 *   <li><b>扇の散らし撃ち</b>(通常 ②、中): {@link #FAN_MIN}〜{@link #FAN_MAX} ブロック。{@code fan_windup}(補間 3 + 12)→ {@code fan} の 2 tick 目に
 *       3 本を左右 {@link #FAN_DEG} 度の扇に(決定 11-15)→ {@code fan_recover}(14)。</li>
 *   <li><b>噛みつき</b>(通常 ③、近): 走って詰め、口の届く所(前 {@link #BITE_F_MIN}〜{@link #BITE_F_MAX}・左右 ±{@link #BITE_LAT})で止まって
 *       {@code bite_windup}(補間 3 + 8。T 字の口が光る)→ {@code bite} の 3 tick 目に噛む → {@code bite_recover}(12)。
 *       相手の足が鈍っている間は、撃つより先に詰めて噛みに行く(棘で足を止めて噛む)。</li>
 *   <li><b>棘の雨</b>(特殊): {@link SpecialPacing#standard()} が許したとき、{@link #RAIN_MIN}〜{@link #RAIN_MAX} ブロックの相手へ。{@code rain_windup}
 *       (補間 3 + 24。足を踏ん張る)→ {@code rain} の {@link #RAIN_SHOTS} tick 目に 1 本ずつ 8 本を空へ → {@link #RAIN_FALL_TICKS} tick 後に相手のまわり
 *       半径 {@link #RAIN_SPREAD} に落ちる → {@code rain_recover}(24)の後、降り終わるのを待たずに次へ(決定 11-15)。落ちる所の印は出さない。</li>
 * </ul>
 * 相手が届かない所では、相手へ寄る(走っては止まる)か、撃てる間合いで相手を向いて待つ。
 */
final class PilimnesHuntGoal extends Goal {

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

    /** 前触れの前の補間(tick)。 */
    static final int LEAD = 3;
    /** 時刻(tick)。アニメの長さと対(前触れは補間 3 込み)。 */
    static final int FLICK_WINDUP = LEAD + 16, FLICK = 6, FLICK_SHOT = 2, FLICK_RECOVER = 14,
            FAN_WINDUP = LEAD + 12, FAN = 6, FAN_SHOT = 2, FAN_RECOVER = 14,
            BITE_WINDUP = LEAD + 8, BITE = 6, BITE_HIT = 3, BITE_RECOVER = 12,
            RAIN_WINDUP = LEAD + 24, RAIN = 12, RAIN_RECOVER = 24;
    /** 棘の雨の 8 本を放つ tick(rain の頭から。アニメの 1.25 tick おき)。 */
    static final int[] RAIN_SHOTS = {1, 2, 4, 5, 6, 7, 9, 10};
    /** 棘の雨が放ってから落ちるまで(tick)・落ちる所の散らばり(相手のまわり、ブロック)【仮】。 */
    static final int RAIN_FALL_TICKS = 30;
    static final double RAIN_SPREAD = 2.5D;
    /** 間合い(体の中心から、ブロック)【仮】。 */
    static final double FLICK_MIN = 6.0D, FLICK_MAX = 44.0D, FAN_MIN = 3.5D, FAN_MAX = 22.0D, RAIN_MIN = 6.0D, RAIN_MAX = 22.0D;   // 11-16 ユーザー「遠距離はもっと射程があっていい」: 弾き 22 → 44・扇 9 → 18(2 倍)。雨は相手のまわりに降るのでそのまま
    /** 噛みつきの届く所(体の中心から前へ・左右、ブロック。鼻先の通り道 前 3.8〜4.5 のまわり)【仮】。 */
    static final double BITE_F_MIN = 2.4D, BITE_F_MAX = 5.0D, BITE_LAT = 1.3D;
    /** 撃ち始めてよい向きのずれ(度)。前触れの間に相手へ向き直る【仮】。 */
    static final float AIM_DEG = 60.0F, BITE_DEG = 30.0F;
    /** 扇の開き(度。決定 11-15)。 */
    static final float FAN_DEG = 25.0F;
    /** 棘の速さ(ブロック/tick)・傷・鈍足【仮】。 */
    /** 扇の散らし撃ちの届く端(11-18 夜 ユーザー 18 → 22。actions.json の pilimnes/fan の fanMax で直せる)。 */
    static double fanMax() {
        return com.yayuy.overgrownvegetation.data.DesignData.actions().num("pilimnes", "fan", "fanMax", FAN_MAX);
    }

    static final double FLICK_SPEED = 2.4D, FAN_SPEED = 1.8D;   // 11-16 射程 2 倍に合わせて速く(弾きは 44 ブロック先へ約 21 tick・扇は 18 ブロック先へ約 11 tick。空気の抵抗 0.99/tick 込み)
    static final float FLICK_DAMAGE = 13.33F, FAN_DAMAGE = 10.67F, RAIN_DAMAGE = 10.67F;   // 11-18 夜: 攻撃 6 → 16 と同じ比(× 16/6)。actions.json の pilimnes/flick・fan・rain
    static final int SLOW_TICKS = 80, SLOW_LEVEL = 1;
    /** 待ち(tick)【仮】。 */
    static final int FLICK_COOLDOWN = 40, FAN_COOLDOWN = 60, BITE_COOLDOWN = 24, RAIN_COOLDOWN = 300;
    /** 追う速さ・経路を引き直す間隔・見えるかを見直す間隔(tick)【仮】。 */
    static final double CHASE_SPEED = 1.0D;
    static final int REPATH = 10, SIGHT_EVERY = 5;
    /** 追う経路の探す数(バニラの何倍か)【仮。ヴェナムネスは 0.3】と、引き直しの部品(11-16 見直し ①4)。 */
    static final float NODE_MULT = 0.5F;
    private final ChasePath chase = new ChasePath();
    /** 撃てる間合いの外へ寄るとき、相手からこの距離(ブロック)まで寄って止まる【仮】。 */
    static final double HOLD_DIST = 40.0D;   // 11-16 射程 2 倍に合わせて 14 → 40(弾きの間合い 44 の少し内側で止まって撃つ)

    /** 技の番号(PilimnesEntity の ACT_*)。 */
    private final PilimnesEntity mob;
    private int act = PilimnesEntity.ACT_NONE, actTicks;
    private int flickCd, fanCd, biteCd, rainCd, repath, sightIn;
    private boolean sees;
    private final SpecialPacing pacing = SpecialPacing.standard();
    /** テスト用: 次の判断で出す技(ACT_NONE なら選ぶ)。 */
    int forceAct = PilimnesEntity.ACT_NONE;
    @Nullable
    private LivingEntity victim;
    /** 確かめ用: 出した技の数。 */
    int flicks, fans, bites, rains;

    PilimnesHuntGoal(PilimnesEntity mob) {
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
        return this.act != PilimnesEntity.ACT_NONE || canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (this.act != PilimnesEntity.ACT_NONE) endAct();
        this.mob.getNavigation().stop();
    }

    int act() {
        return this.act;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        LivingEntity t = this.mob.getTarget();
        this.pacing.tick(t);
        if (this.flickCd > 0) this.flickCd--;
        if (this.fanCd > 0) this.fanCd--;
        if (this.biteCd > 0) this.biteCd--;
        if (this.rainCd > 0) this.rainCd--;
        if (this.act != PilimnesEntity.ACT_NONE) {
            this.actTicks++;
            tickAct();
            return;
        }
        if (t == null || !t.isAlive()) return;
        if (--this.sightIn <= 0) {
            this.sightIn = SIGHT_EVERY;
            this.sees = this.mob.getSensing().hasLineOfSight(t);
        }
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
        double sc = Math.max(this.mob.getScale(), 0.05F);
        double d = l[3] / sc;
        float off = Math.abs(Mth.wrapDegrees(yawTo(t) - this.mob.getYRot()));
        int forced = this.forceAct;
        this.forceAct = PilimnesEntity.ACT_NONE;
        if (DEV && this.mob.tickCount % 20 == 0 && this.mob.hasCustomName() && this.mob.getCustomName().getString().contains("acts")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "PILI_DECIDE t %d 距離 %.1f ずれ %.0f 見える %s 待ち 弾き %d 扇 %d 噛み %d 雨 %d 経路 %s", this.mob.tickCount, d, off, this.sees,
                    this.flickCd, this.fanCd, this.biteCd, this.rainCd, this.mob.getNavigation().isDone() ? "なし" : "あり"));
        }
        boolean slowed = t.hasEffect(MobEffects.MOVEMENT_SLOWDOWN);
        boolean inBite = inBiteZone(t) && off <= BITE_DEG;

        if (forced == PilimnesEntity.ACT_RAIN_WINDUP) {
            startAttack(PilimnesEntity.ACT_RAIN_WINDUP, t);
            return;
        }
        if (forced == PilimnesEntity.ACT_BITE_WINDUP || this.biteCd == 0 && inBite) {
            startAttack(PilimnesEntity.ACT_BITE_WINDUP, t);
            this.pacing.onNormalAttack();
            return;
        }
        boolean canFan = this.fanCd == 0 && this.sees && d >= FAN_MIN && d <= fanMax() && off <= AIM_DEG;
        boolean canFlick = this.flickCd == 0 && this.sees && d >= FLICK_MIN && d <= FLICK_MAX && off <= AIM_DEG;
        boolean canRain = this.rainCd == 0 && this.sees && d >= RAIN_MIN && d <= RAIN_MAX && this.mob.onGround();
        // 足が鈍った相手には、撃つより先に詰めて噛む(近いうちは扇も撃たない)
        boolean hunting = slowed && this.biteCd == 0;
        if (forced == PilimnesEntity.ACT_FAN_WINDUP || forced == PilimnesEntity.ACT_FLICK_WINDUP
                || !hunting && (canFan || canFlick)) {
            if (forced == PilimnesEntity.ACT_NONE && canRain && this.pacing.roll(this.mob.getRandom())) {
                startAttack(PilimnesEntity.ACT_RAIN_WINDUP, t);
                return;
            }
            boolean fan = forced == PilimnesEntity.ACT_FAN_WINDUP || forced == PilimnesEntity.ACT_NONE && canFan;
            startAttack(fan ? PilimnesEntity.ACT_FAN_WINDUP : PilimnesEntity.ACT_FLICK_WINDUP, t);
            this.pacing.onNormalAttack();
            return;
        }
        move(t, d, hunting);
    }

    /**
     * 寄る・待つ: 足が鈍った相手と、噛める距離の近くにいる相手へは口の届く所まで詰める。撃てる間合いより遠い相手には {@link #HOLD_DIST} まで寄る。
     * 撃てる間合いの中で技を待つ間は止まって相手を向く。
     */
    private void move(LivingEntity t, double d, boolean hunting) {
        this.mob.wantFacing(yawTo(t), LongBodyRunner.TURN_HUNT);
        boolean close = hunting || d < FAN_MIN + 1.0D || !this.sees;
        boolean far = d > HOLD_DIST;
        if (close || far) {
            if (--this.repath <= 0) {
                // サーバー全体の予算・探す数の絞り・届かない探索の控えを通す(前は素の探索を 10 tick おき。届かない相手で 1 回最悪 23 ms。①4)
                this.repath = this.chase.repath(this.mob, t, CHASE_SPEED, NODE_MULT) ? REPATH : 1;
            }
        } else if (!this.mob.getNavigation().isDone()) {
            this.mob.getNavigation().stop();
        }
    }

    /** 相手が口の届く所(体の中心から前 {@link #BITE_F_MIN}〜{@link #BITE_F_MAX}・左右 ±{@link #BITE_LAT})にいるか。 */
    boolean inBiteZone(LivingEntity t) {
        double[] l = local(t);
        double sc = Math.max(this.mob.getScale(), 0.05F);
        return l[0] >= BITE_F_MIN * sc && l[0] <= BITE_F_MAX * sc && Math.abs(l[1]) <= BITE_LAT * sc + t.getBbWidth() * 0.5D
                && l[2] > -1.5D && l[2] < 2.5D * sc;
    }

    // ------------------------------------------------------------------ 技の段

    private void startAttack(int act, LivingEntity t) {
        this.victim = t;
        startAct(act);
    }

    private void startAct(int act) {
        this.act = act;
        this.actTicks = 0;
        this.mob.getNavigation().stop();
        this.mob.setAct(act);
    }

    private void endAct() {
        this.act = PilimnesEntity.ACT_NONE;
        this.actTicks = 0;
        this.victim = null;
        this.mob.setAct(PilimnesEntity.ACT_NONE);
    }

    private void face() {
        if (this.victim != null && this.victim.isAlive()) this.mob.wantFacing(yawTo(this.victim), LongBodyRunner.TURN_ACTION);
        else this.mob.wantFacing(this.mob.getYRot(), LongBodyRunner.TURN_ACTION);
    }

    private void hold() {
        this.mob.wantFacing(this.mob.getYRot(), LongBodyRunner.TURN_ACTION);   // 撃っている間・隙の間は向きを変えない
    }

    private void tickAct() {
        LivingEntity v = this.victim;
        switch (this.act) {
            case PilimnesEntity.ACT_FLICK_WINDUP -> {
                face();
                if (this.actTicks >= FLICK_WINDUP) startAct(PilimnesEntity.ACT_FLICK);
            }
            case PilimnesEntity.ACT_FLICK -> {
                hold();
                if (this.actTicks == FLICK_SHOT && v != null && v.isAlive()) {
                    this.mob.shootFlick(v, FLICK_SPEED, (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num("pilimnes", "flick", "damage", FLICK_DAMAGE), SLOW_TICKS, SLOW_LEVEL);
                    this.flicks++;
                }
                if (this.actTicks >= FLICK) startAct(PilimnesEntity.ACT_FLICK_RECOVER);
            }
            case PilimnesEntity.ACT_FLICK_RECOVER -> {
                hold();
                if (this.actTicks >= FLICK_RECOVER) {
                    endAct();
                    this.flickCd = FLICK_COOLDOWN;
                }
            }
            case PilimnesEntity.ACT_FAN_WINDUP -> {
                face();
                if (this.actTicks >= FAN_WINDUP) startAct(PilimnesEntity.ACT_FAN);
            }
            case PilimnesEntity.ACT_FAN -> {
                hold();
                if (this.actTicks == FAN_SHOT && v != null && v.isAlive()) {
                    this.mob.shootFan(v, FAN_DEG, FAN_SPEED, (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num("pilimnes", "fan", "damage", FAN_DAMAGE));
                    this.fans++;
                }
                if (this.actTicks >= FAN) startAct(PilimnesEntity.ACT_FAN_RECOVER);
            }
            case PilimnesEntity.ACT_FAN_RECOVER -> {
                hold();
                if (this.actTicks >= FAN_RECOVER) {
                    endAct();
                    this.fanCd = FAN_COOLDOWN;
                    this.flickCd = Math.max(this.flickCd, FLICK_COOLDOWN / 2);   // 扇の直後に弾きを続けない
                }
            }
            case PilimnesEntity.ACT_BITE_WINDUP -> {
                face();
                if (this.actTicks >= BITE_WINDUP) startAct(PilimnesEntity.ACT_BITE);
            }
            case PilimnesEntity.ACT_BITE -> {
                hold();
                if (this.actTicks == BITE_HIT && v != null && v.isAlive() && inBiteZone(v)) {
                    this.mob.bite(v);
                }
                if (this.actTicks == BITE_HIT) this.bites++;
                if (this.actTicks >= BITE) startAct(PilimnesEntity.ACT_BITE_RECOVER);
            }
            case PilimnesEntity.ACT_BITE_RECOVER -> {
                hold();
                if (this.actTicks >= BITE_RECOVER) {
                    endAct();
                    this.biteCd = BITE_COOLDOWN;
                }
            }
            case PilimnesEntity.ACT_RAIN_WINDUP -> {
                face();
                if (this.actTicks >= RAIN_WINDUP) {
                    startAct(PilimnesEntity.ACT_RAIN);
                    this.pacing.onSpecial();
                    this.rains++;
                }
            }
            case PilimnesEntity.ACT_RAIN -> {
                hold();
                for (int i = 0; i < RAIN_SHOTS.length; i++) {
                    if (this.actTicks == RAIN_SHOTS[i]) {
                        Vec3 at = v != null && v.isAlive() ? v.position() : this.mob.position().add(this.mob.getLookAngle().scale(10.0D));
                        this.mob.shootRain(i, at, i == 0 ? 0.0D : RAIN_SPREAD, RAIN_FALL_TICKS, (float) com.yayuy.overgrownvegetation.data.DesignData.actions().num("pilimnes", "rain", "damage", RAIN_DAMAGE));
                    }
                }
                if (this.actTicks >= RAIN) startAct(PilimnesEntity.ACT_RAIN_RECOVER);
            }
            case PilimnesEntity.ACT_RAIN_RECOVER -> {
                hold();
                if (this.actTicks >= RAIN_RECOVER) {
                    endAct();
                    this.rainCd = RAIN_COOLDOWN;
                }
            }
            default -> endAct();
        }
    }
}
