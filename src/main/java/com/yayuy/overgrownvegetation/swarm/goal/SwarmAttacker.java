package com.yayuy.overgrownvegetation.swarm.goal;

/**
 * 群れの Goal が「殴った見た目」だけを出させるための口(群れ v2、2026-09-25)。
 *
 * <p>ふつうの近接は {@code doHurtTarget} が当たったときに攻撃アニメを付けるが、攻城(TD)で塊や壁を叩くときは
 * {@code doHurtTarget} を通らない。そこで {@link SwarmEngageGoal} の塊の輪は、TD のフック
 * ({@link SwarmSiegeHooks.BlockStrike})が当てたと答えたときにこれを呼ぶ。
 * 行動状態を直接 {@code STATE_ATTACK} にしない —— 飛びつき・掘り・うずくまりの最中に書き換えると、
 * その Goal の終わりの判定(状態が IDLE に戻ったか)が狂う。
 */
public interface SwarmAttacker {

    /** 攻撃アニメを 1 回見せる(待機中のときだけ。ほかの行動中は何もしない)。 */
    void playAttackAnimation();
}
