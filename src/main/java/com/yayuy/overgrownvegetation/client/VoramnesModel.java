package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import com.yayuy.overgrownvegetation.entity.VoramnesTerrain;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 「ヴォラムネス」のモデル定義。モデル・テクスチャ・アニメは {@code tools/g5-models/out/voramnes/} の低キューブ版
 * (生成物。手で json を直さない。直すなら生成器 {@code tools/g5-models/mobs/Voramnes*.java} を直して作り直す)。
 *
 * <p>コードで隠す骨(アニメは触らない。GeckoLib の骨はモデル単位で使い回されるので、<b>毎回かならず見せる/隠すの両方を書く</b>):
 * <ul>
 *     <li>{@code tail1} … 地面の下の胴と尾。地上に出ている間は隠す(NOTES の「実装の注意」。洞窟の天井・崖・水辺で見えるため)。
 *     出入り・地面の花・地中の間は描く({@link VoramnesEntity#hidesTail})。</li>
 *     <li>{@code trunk1_skin}〜{@code neck2_skin}(胴の箱)と {@code tail1} … 地中・地面の花の間は地面の下に埋まっているので隠す
 *     ({@link VoramnesEntity#hidesBuriedTrunk}。真下の洞窟から見上げたとき、天井から胴が垂れて見えないように。点検係 2026-10-01)。
 *     地表に出るのは頭(襟)と花弁・歯板だけ。</li>
 *     <li>{@code burrow} … 掘り返した土の塊。足元がシャベルで掘るブロック(土・砂・砂利・雪など)でなければ隠す(NOTES「土でない床の上なら隠してよい」)。</li>
 *     <li>{@code body} … 遠くでアニメを止める描画 LOD({@link MobRenderLod})はレストポーズ(= 地上に出た静止の姿)で描くので、
 *     地中・地面の花の個体はそのままだと地上に立った姿で見えてしまう。止められたフレームでは体ごと隠す(土の塊だけ残る)。</li>
 * </ul>
 */
public class VoramnesModel extends LodGeoModel<VoramnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/voramnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/voramnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/voramnes.animation.json");

    @Override
    public ResourceLocation getModelResource(VoramnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(VoramnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(VoramnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void handleAnimations(VoramnesEntity animatable, long instanceId, AnimationState<VoramnesEntity> animationState,
                                 float partialTick) {
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
        int action = animatable.getAction();
        boolean frozen = MobRenderLod.shouldStopAnimation(animatable);
        boolean buriedTrunk = VoramnesEntity.hidesBuriedTrunk(action);
        // 親を先に(setHidden は子の表示もまとめて決めるので、tail1 は body の後に書く)
        setHidden("body", frozen && VoramnesEntity.postureOf(action) != VoramnesEntity.Posture.SURFACED);
        setHidden("tail1", VoramnesEntity.hidesTail(action) || buriedTrunk);
        for (String skin : TRUNK_SKINS) {
            setHidden(skin, buriedTrunk); // 葉の骨(子が無い)なので、ほかの骨の表示には響かない
        }
        setHidden("burrow", !VoramnesTerrain.isSoil(animatable.level().getBlockState(animatable.blockPosition().below())));
    }

    /** 胴の節の箱を持つ骨(関節の骨 trunk1〜neck2 は箱を持たない。NOTES の「骨」)。 */
    private static final String[] TRUNK_SKINS = {
            "trunk1_skin", "trunk2_skin", "trunk3_skin", "trunk4_skin", "trunk5_skin", "trunk6_skin", "neck1_skin", "neck2_skin"
    };

    private void setHidden(String name, boolean hidden) {
        GeoBone bone = getAnimationProcessor().getBone(name);
        if (bone != null) {
            bone.setHidden(hidden);
        }
    }
}
