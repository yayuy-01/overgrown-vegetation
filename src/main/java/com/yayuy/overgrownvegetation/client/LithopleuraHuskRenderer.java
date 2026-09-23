package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.block.LithopleuraHuskBlock;
import com.yayuy.overgrownvegetation.block.LithopleuraHuskBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 抜け殻ブロックの描画。
 *
 * <p>死体ブロックと同じく<b>チャンクメッシュに焼き込まれず 1 個ずつ描かれる</b>ので、
 * 描画距離を {@value #VIEW_DISTANCE} ブロックへ縮めてある(遠くの抜け殻は描かない)。
 *
 * <p>向きは {@code FACING} に合わせて回す —— 脱いだリトプレウラが向いていた方向に
 * 殻が横たわる。全長 2.5 ブロックあるので、向きが合っていないと不自然に見える。
 */
public class LithopleuraHuskRenderer extends GeoBlockRenderer<LithopleuraHuskBlockEntity> {

    /** この描画が働く最大距離(ブロック)。バニラ既定の 64 より短い。 */
    private static final int VIEW_DISTANCE = 32;

    public LithopleuraHuskRenderer() {
        super(new LithopleuraHuskModel());
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public void render(LithopleuraHuskBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Direction facing = blockEntity.getBlockState().hasProperty(LithopleuraHuskBlock.FACING)
                ? blockEntity.getBlockState().getValue(LithopleuraHuskBlock.FACING) : Direction.NORTH;
        poseStack.pushPose();
        // ブロックの中心を軸に回す。隅を軸にすると、向きを変えるたびに位置がずれる。
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5F, 0.0F, -0.5F);
        super.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
