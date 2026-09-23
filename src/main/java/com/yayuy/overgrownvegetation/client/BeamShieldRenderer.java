package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import com.yayuy.overgrownvegetation.siege.BeamShieldGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 遮蔽盾の描画。モデルは丸ごと GeckoLib({@link BeamShieldGeoModel})で、方位の旋回と展開の
 * せり上がりはそのモデルがボーンに入れる。ブロックモデル自体は描かない
 * ({@code BeamShieldBlock#getRenderShape} = INVISIBLE)。
 *
 * <p>{@link #getFacing} を常に北にしてあるのは、方位をブロックの向き(4 方向)ではなくボーンの
 * 連続した回転で出すため(砲台と同じ)。
 */
public class BeamShieldRenderer extends GeoBlockRenderer<BeamShieldBlockEntity> {

    /** 展開すると 5.5 ブロック立ち上がり、幅は 3 ブロック。 */
    private static final double BOUNDS_INFLATE = 6.0D;

    public BeamShieldRenderer(BlockEntityRendererProvider.Context context) {
        super(new BeamShieldGeoModel());
    }

    /** 方位はボーン({@code base})で回すので、ブロックの向きでは回さない。 */
    @Override
    protected Direction getFacing(BeamShieldBlockEntity block) {
        return Direction.NORTH;
    }

    @Override
    public void preRender(PoseStack poseStack, BeamShieldBlockEntity animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        if (!isReRender) {
            float scale = (float) BeamShieldGeometry.scale();
            poseStack.scale(scale, scale, scale);
        }
    }

    @Override
    public AABB getRenderBoundingBox(BeamShieldBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(BOUNDS_INFLATE * BeamShieldGeometry.scale());
    }
}
