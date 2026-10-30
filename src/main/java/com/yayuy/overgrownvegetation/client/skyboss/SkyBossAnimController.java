package com.yayuy.overgrownvegetation.client.skyboss;

import software.bernie.geckolib.animation.AnimationController;

/**
 * 時刻を<b>ゲームの時刻</b>で決める GeckoLib のコントローラ(2026-10-18 夕方)。サーバーの当たり判定の形({@code skyboss/SkyBossPose})が
 * 同じ時刻でアニメを引くので、見えている体・扇・触手と当たり判定が合う。
 *
 * <p>GeckoLib はふつう、アニメの時刻を「描き始めてからの実時間」で数え、アニメが始まった・くり返したフレームを 0 にする(どのフレームかで少しずつずれる)。
 * ここでは {@link #adjustTick} を差し替えて:
 * <ul>
 *   <li>補間(切り替わり)の始まりは GeckoLib と同じ(そのフレームを 0。GeckoLib はそこで次のアニメを取り出すので 0 が要る)。</li>
 *   <li>流れ始め・くり返しは<b>時刻表</b>({@link Timing})の決まった tick にそろえる: アニメの時刻 = (今 − 区切りの始まり − 補間)を長さで割った余り。</li>
 * </ul>
 * 時刻表が無い(形の元が無い)ときは GeckoLib のふつうの数え方。
 */
final class SkyBossAnimController extends AnimationController<SkyBossImpostor> {

    /** 今流すべきアニメの {区切りの始まり, 補間の長さ, くり返しの長さ}(tick)。分からなければ false。 */
    interface Timing {
        boolean get(double[] out);
    }

    private final Timing timing;
    private final double[] tm = new double[3];

    SkyBossAnimController(SkyBossImpostor animatable, String name, int transitionTicks, AnimationStateHandler<SkyBossImpostor> handler,
                          Timing timing) {
        super(animatable, name, transitionTicks, handler);
        this.timing = timing;
    }

    @Override
    protected double adjustTick(double tick) {
        if (timing == null || !timing.get(tm)) {
            return super.adjustTick(tick);
        }
        double now = ClientSkyBoss.animClock();
        if (!this.shouldResetTick) {
            return Math.max(now - this.tickOffset, 0.0);
        }
        this.shouldResetTick = false;
        State st = getAnimationState();
        if (st == State.STOPPED) {
            return 0.0;
        }
        if (st == State.TRANSITIONING) {
            this.tickOffset = now;
            return 0.0;
        }
        double run0 = tm[0] + tm[1];
        double len = tm[2];
        double since = Math.max(0.0, now - run0);
        this.tickOffset = now - (len > 0.0 ? since % len : since);
        return now - this.tickOffset;
    }
}
