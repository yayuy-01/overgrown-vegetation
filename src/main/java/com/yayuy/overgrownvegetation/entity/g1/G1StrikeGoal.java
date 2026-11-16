package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * G1 段階 0 の 3 段の 1 撃(2026-11-08、11-08 ユーザー決定「移動はバニラの地上の経路探し、攻撃は G1 専用の小さな 3 段の 1 撃」)。
 * バニラの {@code MeleeAttackGoal}(前触れも隙も無い)の代わり。
 *
 * <ol>
 *   <li>追う: 標的へバニラの経路探しで歩く(速さは移動速度そのまま)。間合い({@code strikeInRange}。既定は {@code Mob#isWithinMeleeAttackRange})に入って、
 *   前の技から {@link #GAP_MIN}〜{@link #GAP_MAX} tick 空いていれば前触れへ。</li>
 *   <li>前触れ {@value #WINDUP} tick(アニメ {@code attack_windup}): 止まる。{@value #AIM_UNTIL} tick までは標的の方を向き、
 *   そこから向きを止める(横へ出ればよけられる。仕様「対処: 横へ出る・盾・下がる」)。</li>
 *   <li>実行 {@value #STRIKE} tick(アニメ {@code attack}): 終わりの tick に、間合い(+{@value #REACH_SLACK} ブロックの余裕)の中で
 *   向いた方の ±{@value #HIT_CONE_DEG}° にいれば当てる({@code doHurtTarget}。盾が効く)。Mob に当たったら必ず感染(11-15。{@code ParasitizedAttackInfectionHandler})。</li>
 *   <li>隙 {@value #RECOVER} tick(アニメ {@code attack_recover}): 動かない。</li>
 * </ol>
 * 段の長さはアニメと同じ(生成器の記録)。技の途中で標的が死んだ・消えても、振り出した技は最後まで振る(途中で止めると絵が飛ぶ)。
 * 被弾しても技は止まらない(植物に動かされている体は痛がらない、の読み)。
 *
 * <p>【仮】: 隙の後の間 10〜20 tick(共通の仕様書「技と技の間に 10〜20 tick の乱数」)・当たり判定の余裕 0.5・向きの円錐 ±60°・
 * 向きを止める 9 tick 目・Mob への暴露量 +300(11-05 ユーザー「入れる」、量は仮)・経路が無いまま 100 tick で標的を諦める。
 */
public class G1StrikeGoal extends Goal {

    public static final int WINDUP = 12;
    public static final int STRIKE = 3;
    public static final int RECOVER = 14;
    static final int AIM_UNTIL = 9;
    static final int GAP_MIN = 10;
    static final int GAP_MAX = 20;
    static final double REACH_SLACK = 0.5D;
    static final float HIT_CONE_DEG = 60.0F;
    static final int GIVE_UP_NO_PATH_TICKS = 100;

    private final AbstractParasitizedMonster mob;
    /** 0 = 追う、それ以外は技の中の tick(1 から数える)。 */
    private int t;
    private int gap;
    private int repath;
    private int noPath;
    private float lockedYaw;

    public G1StrikeGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 技の 1 撃の長さ(前触れ + 実行 + 隙)。 */
    public static int sequenceTicks() {
        return WINDUP + STRIKE + RECOVER;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.canAttack(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (t > 0) {
            return true; // 振り出した技は最後まで
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.canAttack(target);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        t = 0;
        repath = 0;
        noPath = 0;
        mob.setAggressive(true);
    }

    @Override
    public void stop() {
        t = 0;
        // 行動の段を空に戻すのは、1 撃の自分の段(前触れ・実行・隙)のときだけ(2026-11-13)。1 撃が当たって始まった取り付き・食らいつきの
        // 段を消さない: 噛んでいる最中に標的が消える(別の相手を標的に渡され、その相手がいなくなる)と、この Goal だけが止まり、
        // 噛んだまま行動の段が空になっていた(豚の食らいつきのテストが回によって落ちた原因)。
        int a = mob.action();
        if (a == AbstractParasitizedMonster.ACTION_WINDUP || a == AbstractParasitizedMonster.ACTION_STRIKE
                || a == AbstractParasitizedMonster.ACTION_RECOVER) {
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        }
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (gap > 0) {
            gap--;
        }
        LivingEntity target = mob.getTarget();
        if (t > 0) {
            tickSequence(target);
            return;
        }
        if (target == null) {
            return;
        }
        if (mob.busy()) {
            // よろけ・乗っている・1 段目の取り付きや種の技の間は追わず振り出さない(2026-11-12)
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (gap <= 0 && mob.strikeReady() && mob.strikeInRange(target) && mob.getSensing().hasLineOfSight(target)) {
            beginSequence();
            return;
        }
        if (mob.strikeHoldsDistance() && mob.strikeInRange(target) && mob.getSensing().hasLineOfSight(target)) {
            // 届く所では寄らずに次の 1 撃を待つ(1 段目の牛・羊・鶏。段階 0 は今までどおり、間を待つ間も寄り続ける)
            mob.getNavigation().stop();
            return;
        }
        chase(target);
    }

    private void chase(LivingEntity target) {
        PathNavigation nav = mob.getNavigation();
        if (--repath <= 0) {
            repath = 4 + mob.getRandom().nextInt(7);
            double d = mob.distanceToSqr(target);
            if (d > 1024.0D) {
                repath += 10;
            } else if (d > 256.0D) {
                repath += 5;
            }
            Path path = nav.createPath(target, 0);
            if (path == null || !path.canReach() && path.getNodeCount() <= 1) {
                noPath += repath;
            } else {
                noPath = 0;
            }
            nav.moveTo(path, 1.0D);
        }
        if (noPath > GIVE_UP_NO_PATH_TICKS) {
            // たどり着けない相手(壁・水の向こう)は諦めて、しばらく選ばない
            G1TargetGoal.ignore(mob, target);
            mob.setTarget(null);
            noPath = 0;
        }
    }

    private void beginSequence() {
        t = 1;
        mob.getNavigation().stop();
        mob.setAction(AbstractParasitizedMonster.ACTION_WINDUP);
        lockedYaw = mob.getYRot();
    }

    private void tickSequence(LivingEntity target) {
        mob.getNavigation().stop();
        if (t <= AIM_UNTIL && target != null && target.isAlive()) {
            mob.getLookControl().setLookAt(target, 60.0F, 30.0F);
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            lockedYaw = Mth.approachDegrees(mob.getYRot(), want, 30.0F);
        }
        // 向きを体ごと止める(前触れの後半から隙の終わりまで)
        mob.setYRot(lockedYaw);
        mob.setYBodyRot(lockedYaw);
        mob.yHeadRot = lockedYaw;

        if (t > WINDUP && t <= WINDUP + STRIKE) {
            // 実行の段: 当たりの判定の前に種の動きを挟む(1 段目の牛・羊の踏み込み。段階 0 は何もしない)
            mob.onStrikeTick(t - WINDUP, lockedYaw, target != null && target.isAlive() ? target : null);
        }
        if (t == WINDUP + 1) {
            mob.setAction(AbstractParasitizedMonster.ACTION_STRIKE);
        } else if (t == WINDUP + STRIKE) {
            if (target != null && target.isAlive() && canHit(target)) {
                hit(target);
            }
        } else if (t == WINDUP + STRIKE + 1) {
            if (mob.action() != AbstractParasitizedMonster.ACTION_STRIKE) {
                // 当たった 1 撃が行動を替えた(1 段目のチビゾンビが取り付いた)→ 隙を流さずに終える(2026-11-12)
                t = 0;
                gap = GAP_MIN + mob.getRandom().nextInt(GAP_MAX - GAP_MIN + 1);
                mob.onStrikeEnd(gap);
                return;
            }
            mob.setAction(AbstractParasitizedMonster.ACTION_RECOVER);
        }
        if (t >= WINDUP + STRIKE + mob.recoverTicks()) {
            t = 0;
            gap = GAP_MIN + mob.getRandom().nextInt(GAP_MAX - GAP_MIN + 1);
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
            repath = 0;
            mob.onStrikeEnd(gap);
            return;
        }
        t++;
    }

    /** 実行の終わりに、標的が間合い(+余裕)の中で向いた方の円錐にいるか。 */
    private boolean canHit(LivingEntity target) {
        if (!mob.getAttackBoundingBoxForG1().inflate(REACH_SLACK, 0.0D, REACH_SLACK).intersects(target.getHitbox())) {
            return false;
        }
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        if (dx * dx + dz * dz < 0.25D) {
            return true; // 重なっている
        }
        float to = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        return Math.abs(Mth.wrapDegrees(to - lockedYaw)) <= HIT_CONE_DEG;
    }

    private void hit(LivingEntity target) {
        land(mob, target);
    }

    /**
     * 1 撃を相手に当てる(盾が効く)。当たれば暴露量を足して {@code onStrikeHit}、盾で防がれたら {@code onStrikeBlocked}(2026-11-13、1 段目の (a))。
     * GameTest からも呼ぶ。
     *
     * @return 当たったか
     */
    public static boolean land(AbstractParasitizedMonster mob, LivingEntity target) {
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        // 盾で防がれるかは当てる前に見る(当てた後は、構えが解けていることがある)
        boolean shielded = target.isDamageSourceBlocked(mob.damageSources().mobAttack(mob));
        if (mob.doHurtTarget(target)) {
            // 噛まれた家畜・村人も G1 になる鎖(11-05 ユーザー)。プレイヤーは
            // ParasitizedAttackInfectionHandler(グループのタグ)が胞子感染を付ける。
            // Mob への感染は ParasitizedAttackInfectionHandler(傷を付けたら必ず感染。11-15 に 1 撃 +300 の暴露から置き換えた)
            mob.onStrikeHit(target); // 1 段目のチビゾンビはここで取り付く(2026-11-12)
            return true;
        }
        if (shielded) {
            mob.onStrikeBlocked(target); // 1 段目のゾンビ・村人は盾ごしに押す
        }
        return false;
    }
}
