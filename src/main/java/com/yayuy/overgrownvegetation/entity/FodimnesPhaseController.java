package com.yayuy.overgrownvegetation.entity;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationController;

import java.util.Set;

/**
 * ループのアニメを<b>絶対の時刻に位相を合わせて</b>流す {@link AnimationController}(フォディムネスの頭・節・尾)。
 *
 * <h2>なぜ要るか</h2>
 * 蠕動(crawl)は「節 i は (i−1)×5 tick 進めて流す」約束({@code tools/g5-models/out/fodimnes/NOTES.md} の「位相の約束」)で、
 * 膨らみの波が尾から頭へ流れて見える。ところが GeckoLib 4.7.6 の {@code AnimationController#adjustTick} はアニメの時刻を
 * 「今の tick − 流し始めた tick」で出すので、{@code getTick} に「番号 × 5」を足しても、流し始めの tick にも同じだけ足されて
 * <b>打ち消し合う</b>(全部の節が同じ時刻で膨らむ)。NOTES の「getTick に足すのがいちばん楽」はこの理由で使えない。
 *
 * <h2>ここでの数え方</h2>
 * 名前が {@code locked} に入っているループのアニメは、流している間(RUNNING)だけ時刻を {@code (tick + offset) mod 長さ} にする。
 * tick は GeckoLib が渡す実体の時刻(頭も節も同じ {@code tickCount + partialTick})なので、頭と節 1 は同じ時刻、節 i は (i−1)×5 tick 先になる。
 * 補間の間(TRANSITIONING)と、それ以外のアニメ(lurk・burst・snap・flare など)は元の数え方のまま(0 秒目から)。
 * 補間が終わって RUNNING に入る瞬間は、補間の行き先(0 秒目の姿勢)から位相を合わせた時刻の姿勢へ飛ぶ(crawl の太さで最大 8%。小さいので許す)。
 */
public class FodimnesPhaseController<T extends GeoAnimatable> extends AnimationController<T> {

    private final double offsetTicks;
    private final Set<String> locked;

    public FodimnesPhaseController(T animatable, String name, int transitionTicks, AnimationStateHandler<T> handler,
                                   double offsetTicks, Set<String> locked) {
        super(animatable, name, transitionTicks, handler);
        this.offsetTicks = offsetTicks;
        this.locked = locked;
    }

    @Override
    protected double adjustTick(double tick) {
        if (this.animationState == State.RUNNING && this.currentAnimation != null
                && this.locked.contains(this.currentAnimation.animation().name())) {
            if (this.shouldResetTick) {
                this.shouldResetTick = false;
                this.tickOffset = tick;
            }
            double length = this.currentAnimation.animation().length();
            if (length <= 0.0D) {
                return 0.0D;
            }
            double t = (tick + this.offsetTicks) % length;
            return t < 0.0D ? t + length : t;
        }
        return super.adjustTick(tick);
    }
}
