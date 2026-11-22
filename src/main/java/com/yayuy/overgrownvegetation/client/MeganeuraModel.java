package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「メガネウラ」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\meganeura.bbmodel}(9 月製の形のまま、絵は 11-16 に MOD の色へ塗り直し)。
 * geo・アニメ・絵は 11-16 に正本から書き出した({@code tools/g5-models/g1/BbExport}。退避前の geo とは UV と絵の大きさ 64×32 だけが違う)。
 * 体の傾き・機首の上下は骨ではなく描画({@link MeganeuraRenderer})で全体を回す。
 */
public class MeganeuraModel extends GeoModel<MeganeuraEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/meganeura.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/meganeura.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/meganeura.animation.json");

    @Override
    public ResourceLocation getModelResource(MeganeuraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MeganeuraEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MeganeuraEntity animatable) {
        return ANIMATION;
    }

    /**
     * 翅を半透明に描く(9 月製と同じ。GeckoLib の既定は切り抜きで、半透明の画素が透けない)。11-18 ユーザーの指摘「翅が不透明になっている」。
     */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(MeganeuraEntity animatable, ResourceLocation texture) {
        return net.minecraft.client.renderer.RenderType.entityTranslucent(texture);
    }
}
