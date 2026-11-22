package com.yayuy.overgrownvegetation.entity;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;

/**
 * 再生速度を毎フレーム変えても<b>位相が巻き戻らない</b> {@link AnimationController}(2026-09-24、スクティムネス・ファルキムネスで使う)。
 *
 * <h2>GeckoLib 4.7.6 の困るところ</h2>
 * <p>{@code AnimationController#adjustTick} はアニメの時刻を「<b>いまの</b>速さ × (いまの tick − 流し始めた tick)」で出す。
 * 速さを経過時間の全体に掛けるので、ループの途中で速さを下げると時刻そのものが後ろへ戻る —— 歩きのループを実際の速さに
 * 合わせて流している mob が止まると、脚が一瞬逆に歩き、接地した足が地面を滑っていた(スクティムネスの walk で最大 4.7 tick 戻る。
 * レビューの指摘)。補間({@code transitionLength})の長さにも速さが掛かるので、遅く流しているときは補間が 4 tick で終わらない。
 *
 * <h2>ここでの数え方</h2>
 * <ul>
 *     <li>流している間(RUNNING)は、フレームごとに「位相 += そのときの速さ × 前のフレームからの経過」と積み上げる。
 *     速さを変えても、変えた後の進み方が変わるだけで、それまでに進んだ位相は動かない。</li>
 *     <li>ループが一周したら、はみ出したぶんを次の周へ持ち越す(GeckoLib は 0 へ戻すので、1 周ごとに最大 1 フレームぶん遅れる)。</li>
 *     <li>補間の間(TRANSITIONING)は速さを掛けない —— 補間はいつも {@code transitionLength} tick で終わる
 *     (サーバーの「補間 + アニメの秒 × 20」の勘定は速さ 1 のアニメでしか使っていないので、それとも食い違わない)。</li>
 * </ul>
 * 速さ 1 のアニメ(動作のアニメ)では、元の {@code adjustTick} と同じ時刻になる。
 */
public class PhaseKeepingAnimationController<T extends GeoAnimatable> extends AnimationController<T> {

    /** 流し始めてから積み上げた位相(アニメの tick)。 */
    private double phase;
    /** 最後に位相を進めたときの tick(GeckoLib の seekTime)。 */
    private double lastSeek = Double.NaN;

    public PhaseKeepingAnimationController(T animatable, String name, int transitionTickTime,
                                           AnimationStateHandler<T> animationHandler) {
        super(animatable, name, transitionTickTime, animationHandler);
    }

    @Override
    protected double adjustTick(double tick) {
        if (this.shouldResetTick) {
            this.shouldResetTick = false;
            if (getAnimationState() != State.STOPPED) {
                this.tickOffset = tick;
            }
            double length = this.currentAnimation != null ? this.currentAnimation.animation().length() : 0.0D;
            if (this.animationState == State.RUNNING && length > 0.0D && this.phase >= length) {
                // ループが一周した(processCurrentAnimation が頭へ戻そうとしている)。はみ出したぶんを持ち越す。
                this.phase %= length;
            } else {
                this.phase = 0.0D;
            }
            this.lastSeek = tick;
            return this.phase;
        }
        if (this.animationState == State.TRANSITIONING) {
            // 補間は速さに関係なく transitionLength tick。
            this.phase = 0.0D;
            this.lastSeek = tick;
            return Math.max(tick - this.tickOffset, 0.0D);
        }
        double elapsed = Double.isNaN(this.lastSeek) ? 0.0D : Math.max(tick - this.lastSeek, 0.0D);
        this.lastSeek = tick;
        this.phase += this.animationSpeedModifier.apply(this.animatable) * elapsed;
        return this.phase;
    }
}
