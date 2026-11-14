package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 司令塔が決めた変化の前兆を、キーのアニメの後に重ねる手続きの骨(2026-11-13、司令塔 第 1 段)。
 *
 * <p><b>進化の前の震え</b>(D-11「進化の前に立ち止まって震え、植物が膨らむ」。11-13 ユーザー「コードでいいよ」):
 * 体(骨 {@code root})が細かく震え、背の胞子嚢({@code sac})が脈打ちながら膨らみ、芽({@code sprout})が伸びる。
 * 強さは前兆の長さ({@code evolveOmenTicks})をかけて 0 → 1。骨の名は段階 0 の 11 種に共通({@code root}・{@code sac}・{@code sprout})。
 * 震えの幅・膨らみの倍率は【仮】。
 *
 * <p>骨はモデルごとの使い回しなので、書いた骨は必ず {@link GeoBone#resetStateChanges()}(記憶 geckolib-custom-anim-and-easing-traps)。
 */
final class G1OmenRig {

    /** 震えの幅: 傾き(度)と横ずれ(モデルの単位 = 1/16 ブロック)。 */
    private static final float SHAKE_ROLL = 2.2F;
    private static final float SHAKE_PITCH = 1.3F;
    private static final float SHAKE_SHIFT = 0.18F;
    /** 膨らみきったときの倍率(胞子嚢・芽)と、胞子嚢の脈の幅。 */
    private static final float SAC_SWELL = 0.35F;
    private static final float SAC_PULSE = 0.07F;
    private static final float SPROUT_SWELL = 0.2F;

    private G1OmenRig() {
    }

    /** 根を下ろすときの沈み(モデルの単位)と、ゆっくりした揺れ(度)。【仮】 */
    private static final float ROOT_SINK = 1.5F;
    private static final float ROOT_SWAY = 1.6F;

    /**
     * <b>根を下ろす</b>(D-11「根付く前に根を下ろし始める」): 立ち止まった体が少し沈み、ゆっくり左右に揺れ、胞子嚢がゆっくり脈打つ。
     * 進化の細かい震えと見分けがつくように、遅く重い動きにする。この後、根付きの姿に入れ替わって「足元から蔦が巻き上がる」(emerge)。
     */
    private static void applyRoot(GeoModel<?> model, AbstractParasitizedMonster mob, AnimationState<?> state) {
        float t = mob.omenClientTicks(state.getPartialTick());
        float k = Mth.clamp(t / Math.max(1, DesignData.director().rootOmenTicks), 0.0F, 1.0F);
        float ease = k * k * (3.0F - 2.0F * k);
        GeoBone root = model.getAnimationProcessor().getBone("root");
        if (root != null) {
            root.setPosY(root.getPosY() - ease * ROOT_SINK);
            root.setRotZ(root.getRotZ() + Mth.sin(t * 0.35F) * ease * ROOT_SWAY * Mth.DEG_TO_RAD);
            root.resetStateChanges();
        }
        GeoBone sac = model.getAnimationProcessor().getBone("sac");
        if (sac != null) {
            float s = 1.0F + Mth.sin(t * 0.45F) * ease * 0.1F;
            sac.setScaleX(sac.getScaleX() * s);
            sac.setScaleY(sac.getScaleY() * s);
            sac.setScaleZ(sac.getScaleZ() * s);
            sac.resetStateChanges();
        }
    }

    static void apply(GeoModel<?> model, AbstractParasitizedMonster mob, AnimationState<?> state) {
        if (mob.omen() == G1Fate.OMEN_ROOT) {
            applyRoot(model, mob, state);
            return;
        }
        if (mob.omen() != G1Fate.OMEN_EVOLVE) return;
        float t = mob.omenClientTicks(state.getPartialTick());
        float k = Mth.clamp(t / Math.max(1, DesignData.director().evolveOmenTicks), 0.0F, 1.0F);
        GeoBone root = model.getAnimationProcessor().getBone("root");
        if (root != null) {
            root.setRotZ(root.getRotZ() + Mth.sin(t * 2.9F) * k * SHAKE_ROLL * Mth.DEG_TO_RAD);
            root.setRotX(root.getRotX() + Mth.sin(t * 3.7F + 1.3F) * k * SHAKE_PITCH * Mth.DEG_TO_RAD);
            root.setPosX(root.getPosX() + Mth.sin(t * 4.3F + 0.6F) * k * SHAKE_SHIFT);
            root.resetStateChanges();
        }
        GeoBone sac = model.getAnimationProcessor().getBone("sac");
        if (sac != null) {
            float s = 1.0F + k * SAC_SWELL + Mth.sin(t * 0.8F) * k * SAC_PULSE;
            sac.setScaleX(sac.getScaleX() * s);
            sac.setScaleY(sac.getScaleY() * s);
            sac.setScaleZ(sac.getScaleZ() * s);
            sac.resetStateChanges();
        }
        GeoBone sprout = model.getAnimationProcessor().getBone("sprout");
        if (sprout != null) {
            float s = 1.0F + k * SPROUT_SWELL;
            sprout.setScaleX(sprout.getScaleX() * s);
            sprout.setScaleY(sprout.getScaleY() * s);
            sprout.setScaleZ(sprout.getScaleZ() * s);
            sprout.resetStateChanges();
        }
    }
}
