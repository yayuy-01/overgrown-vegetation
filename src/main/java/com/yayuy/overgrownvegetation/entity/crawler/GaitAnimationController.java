package com.yayuy.overgrownvegetation.entity.crawler;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * 歩き ⇄ 走りを<b>1 巡の中の位置を保ったまま</b>切り替える {@link AnimationController}(2026-11-15、グレガリアの run で足した)。
 * 位相の数え方は {@code PhaseKeepingAnimationController} と同じ(速さを毎フレーム変えても時刻が巻き戻らない)。足したのは次の 2 つ:
 * <ul>
 *   <li>{@link #switchGait}: 同じ脚の並びで作った 2 つのループ(walk の 0.3 巡目 → run の 0.3 巡目)の間を、補間なしで移る。
 *       GeckoLib の切り替えは新しいアニメを頭(0)から流し、補間は頭の姿へ寄せるので、そのままだと切り替えの瞬間に脚が飛ぶ。</li>
 *   <li>そのほかの切り替え(止まる・動き出す)は {@link #normalTransition} tick の補間のまま。</li>
 * </ul>
 * 新しいアニメが走り出した最初のフレームで、覚えておいた 1 巡の中の位置 × 新しいアニメの長さ へ位相を合わせる。
 */
public class GaitAnimationController<T extends GeoAnimatable> extends AnimationController<T> {

    private double phase;
    private double lastSeek = Double.NaN;
    private final int normalTransition;
    /** 次に走り出すアニメの名前と、そこで始める 1 巡の中の位置(0〜1)。無ければ null。 */
    private String pendingName;
    private double pendingFraction;

    public GaitAnimationController(T animatable, String name, int transitionTickTime, AnimationStateHandler<T> handler) {
        super(animatable, name, transitionTickTime, handler);
        this.normalTransition = transitionTickTime;
    }

    /** 今流しているループの名前(無ければ null)。 */
    public String currentName() {
        return this.currentAnimation == null ? null : this.currentAnimation.animation().name();
    }

    /** 今の 1 巡の中の位置(0〜1)。 */
    public double fraction() {
        if (this.currentAnimation == null) return 0.0D;
        double len = this.currentAnimation.animation().length();
        return len > 0.0D ? (this.phase % len) / len : 0.0D;
    }

    /**
     * {@code to} へ切り替える。今流しているのが {@code gaitPeer}(同じ脚の並びのループ)なら、補間なしで同じ 1 巡の中の位置から始める。
     * そうでなければふつうの補間。同じアニメのままなら何もしない(呼ぶ側は毎フレーム呼んでよい)。
     */
    public void switchGait(RawAnimation to, String toName, String gaitPeer) {
        String now = currentName();
        if (toName.equals(now) && this.pendingName == null) {
            this.transitionLength(this.normalTransition);
            return;
        }
        if (gaitPeer.equals(now) && this.getAnimationState() == State.RUNNING) {
            this.pendingName = toName;
            this.pendingFraction = fraction();
            this.transitionLength(0);
        } else if (this.pendingName == null) {
            this.transitionLength(this.normalTransition);
        }
    }

    @Override
    protected double adjustTick(double tick) {
        if (this.pendingName != null && this.currentAnimation != null && this.pendingName.equals(this.currentAnimation.animation().name())
                && this.animationState != State.TRANSITIONING) {
            // 新しい歩き方が走り出した: 覚えた 1 巡の中の位置から
            this.shouldResetTick = false;
            this.tickOffset = tick;
            this.phase = this.pendingFraction * this.currentAnimation.animation().length();
            this.pendingName = null;
            this.lastSeek = tick;
            this.transitionLength(this.normalTransition);
            return this.phase;
        }
        if (this.shouldResetTick) {
            this.shouldResetTick = false;
            if (getAnimationState() != State.STOPPED) this.tickOffset = tick;
            double length = this.currentAnimation != null ? this.currentAnimation.animation().length() : 0.0D;
            if (this.animationState == State.RUNNING && length > 0.0D && this.phase >= length) {
                this.phase %= length;   // ループが一周した: はみ出しを持ち越す
            } else {
                this.phase = 0.0D;
            }
            this.lastSeek = tick;
            return this.phase;
        }
        if (this.animationState == State.TRANSITIONING) {
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
