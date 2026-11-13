package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * エンダーマンの 1 段目が抱えた汚染した土(2026-11-13、能力の第 4 回。仕様「抱えている間は両腕の間にブロックが見える(遠くから分かる)」)。
 * バニラのエンダーマンの持ったブロックと同じ描き方(半分の大きさのブロックを胸の前に)。モデルに持つ位置の骨は無いので、
 * 体の向きに合わせて決まった位置({@link #UP}・{@link #AHEAD})に描く(アニメ carry が両腕を胸の前へ寄せる)。
 */
public class G1CarriedBlockLayer<T extends LivingEntity & GeoEntity> extends GeoRenderLayer<T> {

    /** 抱える位置(足元からの高さ・体の前へ。ブロック)と大きさ。ゲームで見て決めた【仮】。 */
    static final float UP = 1.55F;
    static final float AHEAD = 0.62F;
    static final float SCALE = 0.5F;

    public G1CarriedBlockLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType, MultiBufferSource bufferSource,
                       VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        if (!(animatable instanceof DevelopedParasitized d)) return;
        BlockState state = d.carried();
        if (state == null || animatable.deathTime > 0) return;
        poseStack.pushPose();
        // 体の向き(GeoEntityRenderer と同じ 180 − 体の向き。モデルの前は −Z)
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - Mth.rotLerp(partialTick, animatable.yBodyRotO, animatable.yBodyRot)));
        poseStack.translate(0.0F, UP, -AHEAD);
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
