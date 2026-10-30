package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 「リトプレウラ」のモデル定義。
 *
 * <h2>{@code kara} は脱皮中だけ出す</h2>
 * <p>モデルには殻({@code kara} と子の {@code bone7}〜{@code bone11})が体と<b>重なって</b>入っている。
 * 常に描くと体が二重に見えるので、<b>普段は隠す</b>(ユーザー指定 2026-09-21)。
 *
 * <p>隠すのは {@link #handleAnimations} の {@code super} の<b>後</b>
 * (2026-09-25 に {@code setCustomAnimations} から移した。理由はそのメソッドの説明)。
 * {@link GeoBone#setHidden} は<b>子の表示可否も一緒に落とす</b>ので、
 * {@code kara} 一本を切り替えれば殻 5 節ぶん全部が付いてくる。
 *
 * <p>テクスチャは抜け殻ブロック({@code LithopleuraHuskModel})と共用。
 * 脱いだ殻と脱ぐ前の殻が同じ絵になるので、「さっきのあれだ」と分かる。
 */
public class LithopleuraModel extends LodGeoModel<LithopleuraEntity> {

    /** 殻のまとめ役。ここを隠すと子(bone7〜bone11)も消える。 */
    private static final String BONE_HUSK = "kara";

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/lithopleura.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/lithopleura.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/lithopleura.animation.json");

    @Override
    public ResourceLocation getModelResource(LithopleuraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(LithopleuraEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(LithopleuraEntity animatable) {
        return ANIMATION;
    }

    /**
     * 殻の出し入れは {@code super} の<b>後</b>で、アニメを止めるフレームでも毎回書く(2026-09-25)。
     * 以前は {@code setCustomAnimations} で書いていたが、{@link LodGeoModel} がレストポーズで描くフレームでは
     * それが呼ばれない。骨は同じ種類の Mob 全員で使い回しなので、止まっている個体には<b>直前に描いた別の個体の</b>
     * 出し入れが移り、動く個体を 1 度も描いていなければ殻が出たまま(体が二重)に見えていた。
     * {@code MobRenderLod} の「近い数十体だけ動かす」予算で、近くでも止まる個体が出るようになったので直した
     * ({@code PediculaModel} の sub と同じ書き方)。
     */
    @Override
    public void handleAnimations(LithopleuraEntity animatable, long instanceId,
                                 AnimationState<LithopleuraEntity> animationState, float partialTick) {
        super.handleAnimations(animatable, instanceId, animationState, partialTick);
        GeoBone husk = getAnimationProcessor().getBone(BONE_HUSK);
        if (husk != null) {
            husk.setHidden(!animatable.isMolting());
        }
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(25, 15, "head", 1.0F)
            .body("body", 1.0F)
            .roll("body", 0.3F, 5)
            .origin(-6)
            .chain(1.0F, 0.9F, "bone4", "bone3", "bone2", "bone5", "bone6")
            .chain(1.0F, 0.9F, "bone7", "bone8", "bone9", "bone10", "bone11")
            // 脚 4 対(leg〜leg4 = 左の前から、leg8〜leg5 = 右の前から)。後ろほど先、左右は半周ずらす
            .legs(new LegSet(0.4F, 0.4F, 14, 6.0F)
                    .leg("leg", 0.00F).leg("leg2", 0.25F).leg("leg3", 0.50F).leg("leg4", 0.75F)
                    .leg("leg8", 0.50F).leg("leg7", 0.75F).leg("leg6", 0.00F).leg("leg5", 0.25F))
            .sway("head", 1, 4, 3.4F, 0.0F)
            .sway("head", 0, 3, 2.6F, 0.4F);

    @Override
    public void setCustomAnimations(LithopleuraEntity animatable, long instanceId, AnimationState<LithopleuraEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
