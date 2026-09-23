package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ヴェロフィルの<b>噛みつき</b>(ユーザー要望 2026-09-17:
 * 「ファントムのようにかみついて攻撃できるようにしてほしい」)。
 *
 * <h2>なぜ要るか</h2>
 * 爆弾は<b>落とすもの</b>なので、<b>浮いている相手には原理的に当たらない</b>。
 * それまでの索敵({@link VelophyllTargetGoal#isBombable})は「地下に潜っていないか」しか
 * 見ていなかったので、空を飛ぶメガネウラは文句なしに爆撃対象だった。結果が
 * ユーザー報告の「空中で動き続け、何もない場所に爆弾を落とし続ける」。
 *
 * <p>いまは相手が {@link VelophyllTargetGoal#isAirborne 浮いている}なら、爆撃ではなく
 * この Goal が受け持つ。爆撃({@link VelophyllBombGoal})とは {@code canUse} が排他なので、
 * <b>同じ相手に両方が動くことはない</b>。
 *
 * <h2>動き(ファントムと同じ 2 拍子)</h2>
 * <ol>
 *     <li><b>突っ込む</b>({@link #tickChase}) —— 相手の<b>少し先</b>({@link #interceptPoint})を
 *     狙って直進する。相手の今の位置を指すと、到達するころには相手がそこに居ない ——
 *     旋回半径が {@code velophyllTurnRate} で決まっているこの機体では、後ろに付いて追い回すことが
 *     そもそもできないので、<b>先回りして擦れ違いざまに噛む</b>形にしてある。</li>
 *     <li><b>離脱する</b>({@link #tickBreakOff}) —— 噛んだら、あるいは擦れ違って通り過ぎたら、
 *     相手の向こう側の<b>上空</b>へ抜けて {@code velophyllBiteBreakOffTicks} だけ飛ぶ。
 *     こうしないと、相手の周りで小さく回ろうとして<b>旋回半径に負けてふらつく</b>。
 *     抜けたところで向き直り、また 1 へ。</li>
 * </ol>
 *
 * <h2>噛む判定</h2>
 * <b>体の中心から {@code velophyllBiteReach} ブロックの箱</b>に相手の当たり判定が重なったら噛む。
 * 噛んだあとは {@code velophyllBiteCooldownTicks} 空く。
 * ダメージは {@code mobs.json} の {@code attackDamage}(既定 6)。
 *
 * <p>当たり判定そのもの({@code getBoundingBox})を使わないのは、
 * 2026-09-17 に<b>翼端までの 5.75 ブロック</b>へ広げたから ——
 * そのまま使うと<b>翼の先が掠めただけで噛みつきになる</b>。噛むのは口なので、
 * 判定は胴体まわりに戻してある(広げる前の当たり判定と同じくらいの大きさ)。
 *
 * <p>見た目は {@link VelophyllEntity#STATE_DIVE}(突っ込むとき)と
 * {@link VelophyllEntity#STATE_GLIDE}(離脱)の使い分けだけ —— 専用の噛みつき
 * アニメーションはまだ無い。{@code velophyll.animation.json} に {@code bite} を足せば
 * ここで差し替えられる。
 */
public class VelophyllBiteGoal extends Goal {

    /** 突っ込むとき、相手の何 tick 先を狙うか(先回りの量)。 */
    private static final double LEAD_TICKS = 8.0D;
    /** 相手の向こう側へどれだけ抜けるか(ブロック)。ここまでは機首を上げない。 */
    private static final double OVERSHOOT = 14.0D;
    /** 離脱で上へ抜ける高さ(ブロック)。 */
    private static final double BREAK_OFF_CLIMB = 12.0D;
    /** 離脱で相手から離れる距離(ブロック)。旋回半径ぶんの余裕。 */
    private static final double BREAK_OFF_DISTANCE = 30.0D;
    /** 行き先を置き直す間隔(tick)。相手は動くので追従させる。 */
    private static final int REPATH_INTERVAL = 4;
    /** 「通り過ぎた」と判定してよい距離(ブロック)。これより遠いうちは判定しない。 */
    private static final double PASSED_DISTANCE = 20.0D;

    private final VelophyllEntity mob;

    /** 離脱中の残り tick。0 なら突っ込んでいる。 */
    private int breakOffTicks;
    /** 離脱の向き(コンパス角、度)。離脱に入った瞬間に決めて、その間は変えない。 */
    private double breakOffHeading;
    /** 次に行き先を置き直すまでの残り tick。 */
    private int repathTimer;

    public VelophyllBiteGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.mob.hasBiteTarget() && !this.mob.isInWater();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.breakOffTicks = 0;
        this.repathTimer = 0;
        // 地面にいたなら飛び立つところから(爆撃と同じ)。
        if (this.mob.isGrounded()) {
            this.mob.setGrounded(false);
            this.mob.beginTakeoff();
        }
    }

    @Override
    public void stop() {
        this.breakOffTicks = 0;
        this.mob.setMoveTargetPoint(null);
        if (this.mob.getActionState() == VelophyllEntity.STATE_DIVE) {
            this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        }
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        // 飛び立つモーションの間は動かさない(状態は本体が戻す)。
        if (this.mob.getActionState() == VelophyllEntity.STATE_TAKEOFF) {
            return;
        }

        if (this.breakOffTicks > 0) {
            tickBreakOff(target);
            return;
        }
        tickChase(target);
    }

    /**
     * 相手の少し先へ突っ込む。当たったら噛んで離脱へ、
     * <b>噛めないまま通り過ぎたらそれはそれで離脱へ</b>(回り込み直す)。
     *
     * <p>外したときも離脱するのが肝。そのまま追い続けると、相手が真後ろに居るのに
     * 前へ飛び続ける = <b>その場で小さく回ろうとしてふらつく</b>
     * (ユーザー報告の「空中で動き続ける」のがまさにこれ)。
     */
    private void tickChase(LivingEntity target) {
        this.mob.setActionState(VelophyllEntity.STATE_DIVE);
        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.mob.setMoveTargetPoint(interceptPoint(target));
        }
        if (tryBite(target) || hasPassed(target)) {
            beginBreakOff();
        }
    }

    /**
     * 相手を<b>通り過ぎた</b>か —— 近くに居るのに、もう機首の後ろ側に居る。
     *
     * <p>遠くに居るうちは判定しない({@value #PASSED_DISTANCE} ブロック以内だけ)。
     * 進入の途中で相手が横切っただけのときに離脱してしまうと、いつまでも近づけない。
     */
    private boolean hasPassed(LivingEntity target) {
        double toX = target.getX() - this.mob.getX();
        double toZ = target.getZ() - this.mob.getZ();
        double distance = Math.hypot(toX, toZ);
        if (distance > PASSED_DISTANCE || distance < 1.0E-4D) {
            return false;
        }
        double heading = Math.toRadians(this.mob.getYRot() + 90.0D);
        return (toX * Math.cos(heading) + toZ * Math.sin(heading)) / distance < 0.0D;
    }

    /**
     * 噛む間合いに入っていれば噛む。
     *
     * <p>見るのは<b>体の中心のまわり {@code velophyllBiteReach} ブロックの箱</b>と
     * 相手の当たり判定の重なり。{@code getBoundingBox()} をそのまま使わないのは、
     * こちらの当たり判定が<b>翼端まで 5.75 ブロック</b>あるから(2026-09-17 に広げた) ——
     * 翼の先が掠めただけで噛みつきになってしまう。噛むのは口なので胴体まわりで測る。
     */
    private boolean tryBite(LivingEntity target) {
        if (!this.mob.canBiteNow()) {
            return false;
        }
        double reach = OvergrownVegetationConfig.COMMON.velophyllBiteReach.get();
        if (!AABB.ofSize(this.mob.getBoundingBox().getCenter(), reach * 2.0D, reach * 2.0D, reach * 2.0D)
                .intersects(target.getBoundingBox())) {
            return false;
        }
        this.mob.startBiteCooldown();
        this.mob.swing(InteractionHand.MAIN_HAND);
        this.mob.doHurtTarget(target);
        this.mob.level().playSound(null, this.mob.getX(), this.mob.getY(), this.mob.getZ(),
                SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.0F, 0.8F);
        return true;
    }

    /**
     * 噛んだ(または擦れ違った)あとの離脱を始める。
     *
     * <p>向きは<b>いま飛んでいる向きのまま</b>。相手を軸に回り込もうとすると、
     * {@code velophyllTurnRate} で決まる旋回半径(およそ 20 ブロック)より内側を
     * 回ろうとしてふらつく。まっすぐ抜けてから大きく回って戻るほうが機体に合っている。
     */
    private void beginBreakOff() {
        this.breakOffTicks = OvergrownVegetationConfig.COMMON.velophyllBiteBreakOffTicks.get();
        this.breakOffHeading = this.mob.getYRot() + 90.0D;
        this.repathTimer = 0;
    }

    /** 相手の向こう側の上空へ抜ける。抜けきったら突っ込みへ戻る。 */
    private void tickBreakOff(LivingEntity target) {
        this.breakOffTicks--;
        this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        if (--this.repathTimer > 0) {
            return;
        }
        this.repathTimer = REPATH_INTERVAL;
        double heading = Math.toRadians(this.breakOffHeading);
        this.mob.setMoveTargetPoint(new Vec3(
                this.mob.getX() + Math.cos(heading) * BREAK_OFF_DISTANCE,
                Math.max(this.mob.getY(), target.getY()) + BREAK_OFF_CLIMB,
                this.mob.getZ() + Math.sin(heading) * BREAK_OFF_DISTANCE));
    }

    /**
     * 突っ込む先 —— <b>相手の {@value #LEAD_TICKS} tick 先</b>を、更に
     * {@value #OVERSHOOT} ブロック向こうまで延ばした点。
     *
     * <p>先回りするのは、相手の<b>今の位置</b>を指すと着くころには居ないから。
     * 更に向こうまで延ばすのは、相手のところを行き先にすると
     * <b>手前で機首を上げて減速してしまう</b>ため —— 通過点として扱うことで、
     * 速度を保ったまま擦れ違う。
     */
    private Vec3 interceptPoint(LivingEntity target) {
        Vec3 lead = target.position().add(target.getDeltaMovement().scale(LEAD_TICKS));
        Vec3 here = this.mob.position();
        Vec3 delta = lead.subtract(here);
        double distance = delta.length();
        if (distance < 1.0E-4D) {
            return lead;
        }
        return lead.add(delta.scale(OVERSHOOT / distance));
    }
}
