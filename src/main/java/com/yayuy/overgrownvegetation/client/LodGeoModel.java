package com.yayuy.overgrownvegetation.client;

import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * この MOD の GeckoLib Mob 用の {@link GeoModel}。{@link MobRenderLod} が「このフレームは止めてよい」と
 * 言ったエンティティでは、キーフレーム補間も手続き的な動きも一切行わず、
 * <b>骨をレストポーズ(モデルの初期姿勢)へ戻して</b>描く。
 *
 * <h2>なぜ「何もせずに return」ではいけないのか</h2>
 * <p>GeckoLib の {@link GeoBone} は<b>モデル単位で使い回される</b> — 同じ種類の Mob が何体いても
 * 骨のオブジェクトは 1 セットしかなく、{@code handleAnimations} が毎回そこへ書き込んでいる。
 * 何もせずに戻ると、その個体は<b>直前に描かれた別の個体の姿勢</b>のまま描かれる。
 * 個体ごと・フレームごとに姿勢が飛び交うので、遠くの Mob が痙攣しているように見えていた
 * (2026-09-13 に判明)。レストポーズへ戻せば、どの個体を描いても必ず同じ姿勢になる。
 *
 * <p>戻すのは回転・位置・拡縮だけで、{@code hidden} には触れない —
 * モデルが意図して隠している骨を勝手に出してしまわないため。
 */
public abstract class LodGeoModel<T extends Entity & GeoAnimatable> extends GeoModel<T> {

    @Override
    public void handleAnimations(T animatable, long instanceId, AnimationState<T> animationState, float partialTick) {
        if (com.yayuy.overgrownvegetation.client.crowd.CrowdRenderDev.skipAnim) {
            restPose();   // 確かめ用(大群の描画の重さの切り分け)。ふだんは通らない
            return;
        }
        if (tickRateAnimation() && com.yayuy.overgrownvegetation.client.crowd.TickRatePose.applies(animatable)) {
            com.yayuy.overgrownvegetation.client.crowd.TickRatePose.handle(this, animatable, instanceId, animationState, partialTick);
            return;
        }
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
    }

    /**
     * アニメを 1 tick に 1 回だけ計算し、間を骨の値の補間で埋めるか(2026-11-18 大群の軽量化 C。{@link com.yayuy.overgrownvegetation.client.crowd.TickRatePose})。
     * 手続きの動き({@code setCustomAnimations})がフレームごとの値に頼る種では入れない。既定は入れない。
     */
    protected boolean tickRateAnimation() {
        return false;
    }

    /** GeckoLib のいつもの計算({@link TickRatePose} から決まった時刻で呼ぶ)。 */
    public void handleAnimationsNow(T animatable, long instanceId, AnimationState<T> animationState, float partialTick) {
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
    }

    /** 使い回しの骨をレストポーズへ戻す(まだ 1 度も描かれていなければ、登録済みの骨は空で何もしない)。 */
    private void restPose() {
        for (GeoBone bone : getAnimationProcessor().getRegisteredBones()) {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.updateRotation(rest.getRotX(), rest.getRotY(), rest.getRotZ());
            bone.updatePosition(rest.getOffsetX(), rest.getOffsetY(), rest.getOffsetZ());
            bone.updateScale(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ());
        }
    }
}
