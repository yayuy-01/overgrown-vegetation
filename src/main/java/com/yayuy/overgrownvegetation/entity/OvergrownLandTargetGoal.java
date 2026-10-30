package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 陸生の過繁茂 Mob が「水に入っていない相手」だけを狙う索敵 Goal。
 *
 * <p>{@link OvergrownHunterTargetGoal} に {@link Predation#isLandTarget} の絞り込みを足したもので、
 * 水生の {@link OvergrownWaterborneTargetGoal}(陸に逃げた相手を追わない)とちょうど対になる。
 * 新規索敵で水中・水面の相手を選ばないだけでなく、<b>追跡中の相手が水に入った瞬間に見失う</b>
 * (ユーザー要望 2026-09-11:「陸上の過繁茂 Mob が水中の Mob を狙わないようにしてほしい」)。
 *
 * <p><b>自分が水に落ちている間も狙わない</b>(2026-09-17)。狙ってしまうと岸へ上がらなくなり
 * ({@link EscapeWaterGoal})、水ぐされで溶ける。例外は遠距離攻撃を持つ種だけ
 * ({@link ModTags#RANGED_ATTACKER}、{@link Predation#isLandTarget} 参照)。
 *
 * <p><b>ずっと深く潜っている相手も狙わない</b>({@link Predation#isWithinChaseDepth}、2026-09-20)。
 * 地表に立ったまま洞窟の中の相手を標的にすると、辿り着けないまま睨み続けることになる
 * ——アルボラの場合はそこから<b>坑道を掘り始めて地面を掘り続ける</b>(ユーザー報告)。
 * 水の規則と同じで、新規索敵だけでなく<b>追跡中の相手が深く潜った瞬間にも見失う</b>。
 *
 * <p>反撃({@link OvergrownHurtByTargetGoal})には掛からない。水中・地下から攻撃してきた相手には
 * 従来どおり反撃する。
 *
 * <p>G4 の群れの一員では動かない(群れ v2、2026-09-25。親の {@link OvergrownHunterTargetGoal#canUse} が群れを見て落とす。
 * 一員の標的は群れの司令塔が同じ条件 —— 水・深さ —— で探して渡す)。
 */
public class OvergrownLandTargetGoal extends OvergrownHunterTargetGoal {

    // selector を先に見る(2026-09-24、軽量化)。種類・グループの判定で落ちる相手には、
    // reachable の水・地表の高さの読み取りを引かない。どちらも副作用の無い判定なので結果は同じ。

    /** {@code selector}(自分, 候補)→ 対象にするか。 */
    public OvergrownLandTargetGoal(Mob mob, BiPredicate<LivingEntity, LivingEntity> selector) {
        super(mob, candidate -> selector.test(mob, candidate) && reachable(mob, candidate));
    }

    /** {@code selector}(候補)→ 対象にするか。 */
    public OvergrownLandTargetGoal(Mob mob, Predicate<LivingEntity> selector) {
        super(mob, candidate -> selector.test(candidate) && reachable(mob, candidate));
    }

    /** 陸生の足で届く相手か —— 水に入っておらず、自分よりずっと深く潜ってもいない。 */
    private static boolean reachable(Mob mob, LivingEntity candidate) {
        return Predation.isLandTarget(mob, candidate) && Predation.isWithinChaseDepth(mob, candidate);
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()) {
            return false;
        }
        LivingEntity current = this.mob.getTarget();
        return current == null || reachable(this.mob, current);
    }
}
