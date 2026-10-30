package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.function.BiPredicate;

/**
 * ヴェロフィル用の索敵 Goal。{@link OvergrownLandTargetGoal} に対して、飛んでいる爆撃機ならではの
 * 2 点を足してある。
 *
 * <h2>1. 上下の探索範囲(2026-09-14「プレイヤーが近くにいても攻撃しに来ない」)</h2>
 * バニラの {@code NearestAttackableTargetGoal#getTargetSearchArea} は探索範囲を
 * <pre>boundingBox.inflate(range, <b>4.0</b>, range)</pre>
 * で作る —— <b>上下はどれだけ {@code FOLLOW_RANGE} を広げても 4 ブロックしか見ない</b>。
 * 地上 Mob なら問題にならないが、<b>巡航高度 28 ブロックを飛ぶこの Mob からは、真下にいる
 * プレイヤーが一生入ってこない</b>。ここでは上下も水平と同じだけ広げる。
 *
 * <h2>2. 地下の相手は狙わない(2026-09-14「地下の敵を狙い続けるのを直してほしい」)</h2>
 * 1 を直した副作用。上下 64 ブロックを見るようになったので、<b>洞窟の奥にいる相手まで
 * 立派な標的になってしまった</b>。爆弾は上から落とすものなので<b>岩盤の下の相手には物理的に届かない</b>
 * のに、いつまでもその相手を抱えて上空を旋回し続けることになる。
 *
 * <p>そこで「<b>爆弾が届く相手か</b>」({@link #isBombable})を条件に足した。判定は
 * <b>葉を無視した地表</b>({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})と相手の高さの差が
 * {@code velophyllMaxTargetDepth} 以内であること。葉を無視するのが肝で、
 * <b>森の木の下にいる相手はちゃんと狙える</b>(天蓋は爆弾が撃ち抜くので届く)。
 *
 * <p>この判定は<b>狙い始めるときだけでなく、狙い続けてよいかにも掛ける</b>。
 * バニラの {@code TargetGoal#canContinueToUse} は<b>距離しか見ず、
 * {@code targetConditions} を評価し直さない</b>ので、これが無いと
 * 「地上で狙った相手が洞窟へ逃げ込んでも追い続ける」ことになる。
 *
 * <h2>3. 空中の相手は噛みつきで襲う(2026-09-17)</h2>
 * ユーザー報告「メガネウラなどを攻撃しようとする際に爆弾を当てれずに空中で動き続け、
 * 何もない場所に爆弾を落とし続ける」への対処。
 *
 * <p>爆弾は<b>落とすもの</b>なので、<b>浮いている相手には原理的に当たらない</b> ——
 * 標的の真上へ回り込んで投下しても、爆弾は標的の下をすり抜けて地面で爆ぜる。
 * {@link #isBombable} は「地下に潜っていないか」しか見ていなかったので、
 * 空を飛ぶメガネウラは<b>文句なしに爆撃対象</b>だった。
 *
 * <p>そこで相手を 2 種類に分ける。
 * <ul>
 *     <li>{@link #isAirborne} —— 地表から {@code velophyllBiteMinAltitude} 以上浮いていて、
 *     かつ<b>接地していない</b>相手。{@link VelophyllBiteGoal} が<b>噛みつき</b>で襲う。
 *     接地も見るのは、跳んだだけの相手や木の枝の上の相手を巻き込まないため。</li>
 *     <li>それ以外の {@link #isBombable} な相手 —— 今までどおり {@link VelophyllBombGoal} が爆撃する。</li>
 * </ul>
 * 狙ってよいか({@link #isAttackable})は<b>どちらかに当てはまるか</b>。
 *
 * <h2>4. 群れの一員は自分で探さない(群れ v2、2026-09-25)</h2>
 * ユーザー指定(2026-09-25)「後続は自分で標的を探さない」。空の群れ({@code swarm/})の一員は、先頭も含めて
 * 標的を<b>司令塔から渡される</b>({@code SwarmController} が {@code setTarget}。探すときも上の {@link #isAttackable} で足切りする)。
 * そこでこの Goal は一員の間は動かない({@code canUse} は親の探索より<b>前</b>に落とす = 箱の走査を引かない)。
 * 一員になった時点で動いていた場合に<b>司令塔の渡した標的を消さない</b>よう、{@code stop} も一員の間は標的に触らない
 * (バニラの {@code TargetGoal#stop} は {@code setTarget(null)} する)。群れに居ない個体は今までどおり。
 */
public class VelophyllTargetGoal extends OvergrownLandTargetGoal {

    public VelophyllTargetGoal(Mob mob, BiPredicate<LivingEntity, LivingEntity> selector) {
        // selector を先に見る(2026-09-24、軽量化)。落ちる相手に地表の高さの読み取りを引かない。結果は同じ。
        super(mob, (self, candidate) -> selector.test(self, candidate) && isAttackable(self, candidate));
    }

    @Override
    public boolean canUse() {
        // 群れの一員は司令塔が標的を渡す(上の 4)。探索の前に落とす。
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        if (!super.canContinueToUse()) {
            return false;
        }
        LivingEntity current = this.mob.getTarget();
        return current == null || isAttackable(this.mob, current);
    }

    @Override
    public void stop() {
        if (SwarmMember.isInSwarm(this.mob)) {
            // 司令塔の渡した標的はそのまま。この Goal の控えだけ消す。
            this.targetMob = null;
            return;
        }
        super.stop();
    }

    @Override
    protected AABB getTargetSearchArea(double targetDistance) {
        // 親と同じ「FOLLOW_RANGE + 胞子感染の被検知ボーナス」を、上下にも同じだけ使う。
        double range = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE)
                + SporeInfectionDetection.maxBonus();
        return this.mob.getBoundingBox().inflate(range, range, range);
    }

    /**
     * 上から落とす爆弾が届く相手か —— つまり<b>地下に潜っていないか</b>。
     *
     * <p>基準は<b>葉を無視した地表</b>との高さの差。葉を無視するので、森の木の下にいる相手は
     * 「地表にいる」と数えられる(天蓋は爆弾が撃ち抜く)。洞窟・地下室・深い谷底の相手は外れる。
     */
    public static boolean isBombable(LivingEntity self, LivingEntity candidate) {
        if (isAirborne(self, candidate)) {
            // 浮いている相手の下を爆弾がすり抜ける。こちらは噛みつきの担当。
            return false;
        }
        return surfaceY(self, candidate) - candidate.getY()
                <= OvergrownVegetationConfig.COMMON.velophyllMaxTargetDepth.get();
    }

    /**
     * <b>空中の相手か</b> —— 噛みつきで襲う相手(ユーザー要望 2026-09-17)。
     *
     * <p>条件は 2 つで、<b>両方</b>要る。
     * <ul>
     *     <li>地表(葉を無視した高さ)から {@code velophyllBiteMinAltitude} 以上浮いている</li>
     *     <li><b>接地していない</b> —— 木の枝の上・高い塔の上に立っている相手を外すため。
     *     塔の上なら地表判定もその塔の天面になるので前者だけでも外れるが、
     *     枝(葉)の上は地表に数えられないのでここが効く。</li>
     * </ul>
     * 跳んだだけの相手は高さが足りないので入らない。落下中の相手は一時的に入るが、
     * 噛みに行っているうちに着地して爆撃へ戻るだけなので害はない。
     */
    public static boolean isAirborne(LivingEntity self, LivingEntity candidate) {
        if (candidate.onGround() || candidate.isInWater()) {
            return false;
        }
        double altitude = candidate.getY() - surfaceY(self, candidate);
        return altitude >= OvergrownVegetationConfig.COMMON.velophyllBiteMinAltitude.get();
    }

    /** 爆撃か噛みつきの<b>どちらかで</b>手が届く相手か。索敵はこれで足切りする。 */
    public static boolean isAttackable(LivingEntity self, LivingEntity candidate) {
        return isAirborne(self, candidate) || isBombable(self, candidate);
    }

    /** 相手の真下の地表(葉を無視した高さ)。 */
    private static int surfaceY(LivingEntity self, LivingEntity candidate) {
        return self.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(candidate.getX()), Mth.floor(candidate.getZ()));
    }
}
