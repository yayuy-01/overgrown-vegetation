package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * ファルキムネスの性格の構え(段 3、2026-11-08。決定「性格は見た目の構えで分かるように。姿勢は大げさでよい」)。
 * 待機・歩き・旋回のアニメの上に、性格のつまみから計算した角度を足す(クライアントの描画だけ)。
 *
 * <p><b>値は生成器 {@code tools/falcimnes-anim/FalcPosture.java} と同じ</b>(2 か所。変えたら両方。生成器は性格の組み合わせ 50 通り ×
 * 待機・歩き・旋回で地面・首・脚・頭を検算している)。値はすべて【仮】。
 * <ul>
 *   <li>鎌と首の先: 技の間・待機の仕草の間は 0 へ消す(技のアニメは構え無しで検算してある)。どの距離でも描く。</li>
 *   <li>胴の高さ・傾き: {@link FalcimnesLegRig} が足の置き方と一緒に足す(足は地面に残る)。手続きの脚の届く距離(40 ブロック)の中だけ。</li>
 *   <li>歩幅: {@link FalcimnesEntity} が足の置き方に渡す。</li>
 * </ul>
 */
public final class FalcimnesPostureRig {

    // 右鎌(ファイル座標、度)。左鎌は鏡(y・z の符号を返す)。FalcPosture と同じ。
    static final float[] GUARD_WEPON = {6, 0, 10};
    static final float GUARD_B5 = -10, GUARD_B7 = 0;
    static final float[] OPEN_WEPON = {16, 0, 20};
    static final float OPEN_B5 = -16, OPEN_B7 = 15;
    static final float LOW_LIFT = -2.2F, LOW_PITCH = 3, HIGH_LIFT = 2.5F, HIGH_PITCH = -5;
    static final float LOW_NECK = 10, HIGH_NECK = -12;
    static final float FLANK_ROLL = 3, FLANK_WING = 0.35F, FLANK_LIFT = 5;
    /** 鎌・首の構えが技の頭で消える / 戻る速さ(1 秒あたり)。動作の切り替えの補間(4 tick)より速く。 */
    static final float FADE_RATE = 8.0F;

    private FalcimnesPostureRig() {
    }

    static float side(float v) {
        return Math.max(0.0F, (v - 0.5F) * 2.0F);
    }

    /** 畳む・開くの重み {g, o}(両方強い個体は打ち消し合う)。 */
    public static float[] weights(FalcimnesEntity e) {
        return com.yayuy.overgrownvegetation.entity.FalcimnesPersonality.postureWeights(e.caution(), e.aggression());
    }

    /** 胴の構え(ファイル座標): {高さ px、前後の傾き 度(+ で前が下がる)、横の傾き 度}。 */
    public static float[] body(FalcimnesEntity e) {
        float[] w = weights(e);
        float sc = (e.caution() - 0.5F) * 2.0F;
        float lift = LOW_LIFT * w[0] + HIGH_LIFT * w[1] + (sc < 0 ? -LOW_LIFT * 0.5F * -sc : 0);
        float pitch = LOW_PITCH * w[0] + HIGH_PITCH * w[1];
        float roll = FLANK_ROLL * side(e.flank()) * flankSide(e);
        return new float[]{lift, pitch, roll};
    }

    /** 回り込む側(±1、個体ごとに決まる。サーバーの回り込みの行き先と同じ決め方)。 */
    static int flankSide(FalcimnesEntity e) {
        return (e.getUUID().getLeastSignificantBits() & 1L) == 0L ? 1 : -1;
    }

    /** 鎌・首の先・翅の構えを足す({@code setCustomAnimations} から、毎フレーム)。 */
    public static void apply(GeoModel<FalcimnesEntity> model, FalcimnesEntity e, AnimationState<FalcimnesEntity> state) {
        float now = e.tickCount + state.getPartialTick();
        float dt = Mth.clamp(now - e.postureClock, 0.0F, 5.0F) / 20.0F;
        e.postureClock = now;
        int act = e.getAction();
        float target = act == FalcimnesEntity.ACT_NONE && !e.isDeadOrDying() ? 1.0F : 0.0F;
        e.postureFade += (target - e.postureFade) * Math.min(1.0F, dt * FADE_RATE);
        float k = e.postureFade * (1.0F - e.squeeze(state.getPartialTick()));   // 段 3: 狭い所の構えの間は薄める
        float[] w = weights(e);
        float of = side(e.flank());
        float g = w[0], o = w[1];
        float wx = (GUARD_WEPON[0] * g + OPEN_WEPON[0] * o + FLANK_LIFT * of * (1 - g) * (1 - o)) * k;
        float wy = (GUARD_WEPON[1] * g + OPEN_WEPON[1] * o) * k, wz = (GUARD_WEPON[2] * g + OPEN_WEPON[2] * o) * k;
        float b5 = (GUARD_B5 * g + OPEN_B5 * o) * k, b7 = (GUARD_B7 * g + OPEN_B7 * o) * k;
        add(model, "wepon", wx, wy, wz);
        add(model, "bone5", b5, 0, 0);
        add(model, "bone7", b7, 0, 0);
        add(model, "wepon2", wx, -wy, -wz);
        add(model, "bone9", b5, 0, 0);
        add(model, "bone11", b7, 0, 0);
        add(model, "bone2", (LOW_NECK * g + HIGH_NECK * o) * k, 0, 0);
        if (of > 0) {
            float ww = FLANK_WING * of * k;
            add(model, "wing", 10 * ww, 3 * ww, 18 * ww);
            add(model, "wing2", 10 * ww, -3 * ww, -18 * ww);
        }
    }

    /** ファイル座標の度を足す(GeckoLib の中では X・Y の向きが逆)。 */
    private static void add(GeoModel<FalcimnesEntity> model, String name, float x, float y, float z) {
        if (x == 0 && y == 0 && z == 0) return;
        GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        b.setRotX(b.getRotX() - x * Mth.DEG_TO_RAD);
        b.setRotY(b.getRotY() - y * Mth.DEG_TO_RAD);
        b.setRotZ(b.getRotZ() + z * Mth.DEG_TO_RAD);
        b.resetStateChanges();
    }
}
