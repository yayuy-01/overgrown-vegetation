package com.yayuy.overgrownvegetation.entity.crawler;

import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * 「小さな地這いの虫」の歩き ⇄ 走り ⇄ 待機の選び方(2026-11-16 コードの見直し ①14 でグレガリア・ストロビラ・ヴェシカリアの写しをまとめた。
 * 数値は種ごとのまま)。描画の下の層 {@code main}({@link GaitAnimationController})から毎フレーム呼ぶ。
 * <ul>
 *   <li>速さ {@code v} が {@code walkMinSpeed} 未満なら待機(再生 1 倍)。</li>
 *   <li>{@code runOn}(× 描画倍率)以上で走りに入り、{@code runOff} 以下で歩きへ戻る(行き来しないよう幅を持たせる)。{@code forceRun} の間は走り(ヴェシカリアの突撃)。</li>
 *   <li>再生の速さは地面の速さ ÷ 1 倍の地上速度(walkRef・runRef × 描画倍率)を範囲で切る = 足が滑らない。</li>
 *   <li>walk と run は同じ脚の並びなので、1 巡の中の位置を保って補間なしで移る({@link GaitAnimationController#switchGait})。</li>
 * </ul>
 */
public final class WalkRunGait {

    public static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    public static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    public static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");

    private final double walkMinSpeed, runOn, runOff, walkRef, runRef, walkSpeedMin, walkSpeedMax, runSpeedMin, runSpeedMax;
    private boolean running;

    public WalkRunGait(double walkMinSpeed, double runOn, double runOff, double walkRef, double runRef,
                       double walkSpeedMin, double walkSpeedMax, double runSpeedMin, double runSpeedMax) {
        this.walkMinSpeed = walkMinSpeed;
        this.runOn = runOn;
        this.runOff = runOff;
        this.walkRef = walkRef;
        this.runRef = runRef;
        this.walkSpeedMin = walkSpeedMin;
        this.walkSpeedMax = walkSpeedMax;
        this.runSpeedMin = runSpeedMin;
        this.runSpeedMax = runSpeedMax;
    }

    /** 走っているか(前のフレームの選び)。 */
    public boolean running() {
        return this.running;
    }

    /** 走りをやめた扱いにする(死んだ・弾けたなど、ほかのアニメへ移るとき)。 */
    public void stop() {
        this.running = false;
    }

    /**
     * 今のフレームのアニメを選び、再生の速さと歩き方の切り替えを {@code c} に入れる。返したアニメを {@code setAndContinue} に渡す。
     *
     * @param v        地面の速さ(ブロック/tick)
     * @param scale    描画倍率
     * @param moving   動いてよいか(死んでいない)
     * @param forceRun 速さに依らず走る(突撃の間)
     */
    public <T extends GeoAnimatable> RawAnimation pick(GaitAnimationController<T> c, double v, double scale, boolean moving, boolean forceRun) {
        if (moving && (forceRun || v >= this.walkMinSpeed)) {
            if (forceRun || (this.running ? v > this.runOff * scale : v >= this.runOn * scale)) {
                this.running = true;
                c.setAnimationSpeed(Mth.clamp(v / (this.runRef * scale), this.runSpeedMin, this.runSpeedMax));
                c.switchGait(RUN, "run", "walk");
                return RUN;
            }
            this.running = false;
            c.setAnimationSpeed(Mth.clamp(v / (this.walkRef * scale), this.walkSpeedMin, this.walkSpeedMax));
            c.switchGait(WALK, "walk", "run");
            return WALK;
        }
        this.running = false;
        c.setAnimationSpeed(1.0D);
        c.switchGait(IDLE, "idle", "-");
        return IDLE;
    }
}
