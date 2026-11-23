package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * G1 の通常攻撃(追いながら殴る 1 撃)。バニラの {@code MeleeAttackGoal} の代わり。
 *
 * <p>2026-11-19 ユーザー決定「通常攻撃は溜め無し、強い大技だけ溜めを残す」で作り直した(Vault「Mob の攻撃の溜めを外す(11-19)」)。
 * それまでの 3 段(前触れ 12・実行 3・隙 14 tick で足を止める。11-08)は、雑魚には強敵向けの作りで、止まるので弱く当たりも少なかった。
 *
 * <ol>
 *   <li>追う: 標的へバニラの経路探しで歩く。振っている間も追うのを止めない。</li>
 *   <li>間合い({@code strikeInRange}。既定はバニラの {@code isWithinMeleeAttackRange})に入り、前の 1 撃から間が空いていれば、
 *   その tick に振り出し、その tick に当たりを判定する({@code doHurtTarget}。盾が効く)。前触れも隙も無い。</li>
 *   <li>実行の段 {@value #STRIKE} tick: 毎 tick 種の動き({@code onStrikeTick})の後に当たりを確かめ、当たれば 1 回だけ当てる。
 *   踏み込まない種は 1 tick 目に当たる。1 段目の牛・羊は、この段で向いた方へまっすぐ踏み込み、届いた tick に当たる
 *   (踏み込みだけは向きを止める = 横へ出ればよけられる、曲がらない)。</li>
 *   <li>振りのアニメ {@code attack} を {@value #SWING_TICKS} tick 見せる(行動の段 {@code ACTION_STRIKE})。その間も歩く。</li>
 *   <li>次の 1 撃は、振り出しから {@value #INTERVAL_MIN}〜{@value #INTERVAL_MAX} tick 後(バニラの近接 20 tick が目安)。</li>
 * </ol>
 * 1 撃が当たって行動の段が替わった(1 段目のチビゾンビの取り付き・豚の食らいつき)ら、その場で 1 撃を終える(替わった段は消さない)。
 * 被弾しても 1 撃は止まらない(植物に動かされている体は痛がらない、の読み)。
 *
 * <p>【仮】: 1 撃の間隔 18〜20 tick・振りを見せる長さ 6 tick・当たり判定の余裕 0.5・踏み込みの円錐 ±60°・経路が無いまま 100 tick で標的を諦める。
 */
public class G1StrikeGoal extends Goal {

    /** 実行の段(当たりを確かめる間・踏み込みの間。tick)。アニメ {@code attack} の長さと同じ。 */
    public static final int STRIKE = 3;
    /** 振りのアニメを見せる長さ(tick)。行動の段 {@code ACTION_STRIKE} の長さ。補間 2 + 振り 3 + 止め 1【仮】。 */
    public static final int SWING_TICKS = 6;
    /** 振り出しから次の振り出しまで(tick)【仮】。バニラの近接 20 tick が目安。群れの振りが揃わないように少し散らす。 */
    public static final int INTERVAL_MIN = 18;
    public static final int INTERVAL_MAX = 20;
    static final double REACH_SLACK = 0.5D;
    static final float HIT_CONE_DEG = 60.0F;
    static final int GIVE_UP_NO_PATH_TICKS = 100;

    private final AbstractParasitizedMonster mob;
    /** 0 = 追う、それ以外は 1 撃の中の tick(1 から数える)。 */
    private int t;
    private int gap;
    private int repath;
    private int noPath;
    /** この 1 撃で当てたか(1 撃で 1 回だけ)。 */
    private boolean landed;
    /** 踏み込む 1 撃か(向きを止め、足を止める)。 */
    private boolean lunging;
    private float lockedYaw;

    public G1StrikeGoal(AbstractParasitizedMonster mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.canAttack(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (t > 0) {
            return true; // 振り出した 1 撃は最後まで(短い)
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
        // 行動の段を空に戻すのは、1 撃の自分の段のときだけ(2026-11-13)。1 撃が当たって始まった取り付き・食らいつきの段を消さない。
        // (WINDUP・RECOVER は 11-19 から使わないが、古い保存・ほかの経路で残っていても空へ戻す)
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
            tickStrike(target);
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
            beginStrike(target);
            return;
        }
        if (mob.strikeHoldsDistance() && mob.strikeInRange(target) && mob.getSensing().hasLineOfSight(target)) {
            // 届く所では寄らずに次の 1 撃を待つ(1 段目の牛・羊・鶏)
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
            if (com.yayuy.overgrownvegetation.corruption.cliff.RootClimb.touching(mob)
                    && com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(mob)) {
                // 縄に取り付いている間は「経路が無い」を数えない(11-23 ユーザー決定「4」。縄の上で経路が付いたり消えたりして、
                // 100 tick で相手をあきらめ、上の縁の手前で止まっていた)
            } else if (path == null || !path.canReach() && path.getNodeCount() <= 1) {
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

    private void beginStrike(LivingEntity target) {
        t = 1;
        landed = false;
        lockedYaw = yawTo(target);
        // 踏み込むのは、素の間合いの外から振り出したときだけ(届いているなら踏み込みは 0 で終わる)
        lunging = !mob.isWithinMeleeAttackRange(target);
        if (lunging) {
            mob.getNavigation().stop();
            faceLocked();
        }
        mob.setAction(AbstractParasitizedMonster.ACTION_STRIKE);
        gap = INTERVAL_MIN + mob.getRandom().nextInt(INTERVAL_MAX - INTERVAL_MIN + 1);
        tickStrike(target); // 振り出した tick にそのまま 1 tick 目を処理する(踏み込まない種はこの tick に当たる)
    }

    private void tickStrike(LivingEntity target) {
        boolean alive = target != null && target.isAlive();
        if (t <= STRIKE) {
            if (lunging) {
                mob.getNavigation().stop();
                faceLocked();
            } else if (alive) {
                lockedYaw = yawTo(target);
                mob.getLookControl().setLookAt(target, 60.0F, 30.0F);
            }
            // 当たりの判定の前に種の動きを挟む(1 段目の牛・羊の踏み込み。段階 0 は何もしない)
            mob.onStrikeTick(t, lockedYaw, alive ? target : null);
            if (!landed && alive && canHit(target)) {
                landed = true;
                land(mob, target);
                if (mob.action() != AbstractParasitizedMonster.ACTION_STRIKE) {
                    // 当たった 1 撃が行動を替えた(1 段目のチビゾンビが取り付いた・豚が食らいついた)→ その場で終える(2026-11-12)
                    finish(false);
                    return;
                }
            }
        }
        if (!lunging && alive && !mob.busy()) {
            // 振っている間も追う(足を止めない)。届く所で待つ種(鶏)は待つ
            if (mob.strikeHoldsDistance() && mob.strikeInRange(target)) {
                mob.getNavigation().stop();
            } else {
                chase(target);
            }
        }
        if (t >= SWING_TICKS) {
            finish(true);
            return;
        }
        t++;
    }

    /** 1 撃を終える。{@code clearAction} = 行動の段を空へ戻す(まだ自分の段のときだけ)。 */
    private void finish(boolean clearAction) {
        t = 0;
        lunging = false;
        if (clearAction && mob.action() == AbstractParasitizedMonster.ACTION_STRIKE) {
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        }
        repath = 0;
        mob.onStrikeEnd(gap); // 次の 1 撃までの残り(1 段目は種の技の間をここから数える)
    }

    private void faceLocked() {
        mob.setYRot(lockedYaw);
        mob.setYBodyRot(lockedYaw);
        mob.yHeadRot = lockedYaw;
    }

    private float yawTo(LivingEntity target) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /**
     * 標的が間合い(+余裕)の中にいるか。踏み込みは、止めた向きの ±{@value #HIT_CONE_DEG}° にいるときだけ(まっすぐで曲がらない)。
     * 踏み込まない 1 撃は向きを見ない(追いながらだと体の向きが遅れて当たらなくなる)。
     */
    private boolean canHit(LivingEntity target) {
        if (!mob.getAttackBoundingBoxForG1().inflate(REACH_SLACK, 0.0D, REACH_SLACK).intersects(target.getHitbox())) {
            return false;
        }
        if (!lunging) {
            return true;
        }
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        if (dx * dx + dz * dz < 0.25D) {
            return true; // 重なっている
        }
        return Math.abs(Mth.wrapDegrees(yawTo(target) - lockedYaw)) <= HIT_CONE_DEG;
    }

    /**
     * 1 撃を相手に当てる(盾が効く)。当たれば {@code onStrikeHit}、盾で防がれたら {@code onStrikeBlocked}(2026-11-13、1 段目の (a))。
     * GameTest からも呼ぶ。
     *
     * @return 当たったか
     */
    public static boolean land(AbstractParasitizedMonster mob, LivingEntity target) {
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        // 盾で防がれるかは当てる前に見る(当てた後は、構えが解けていることがある)
        boolean shielded = target.isDamageSourceBlocked(mob.damageSources().mobAttack(mob));
        if (mob.doHurtTarget(target)) {
            // Mob への感染は ParasitizedAttackInfectionHandler(傷を付けたら必ず感染。11-15)。プレイヤーはグループのタグで胞子感染
            mob.onStrikeHit(target); // 1 段目のチビゾンビはここで取り付く(2026-11-12)
            return true;
        }
        if (shielded) {
            mob.onStrikeBlocked(target); // 1 段目のゾンビ・村人は盾ごしに押す
        }
        return false;
    }
}
