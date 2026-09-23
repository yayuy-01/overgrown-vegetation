package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 砲台の描画。モデルは丸ごと GeckoLib({@link SiegeCannonGeoModel})で、台座の旋回・砲身の俯仰・
 * 発射の反動はそのモデルがボーンに入れる。ブロックモデル自体は描かない
 * ({@code SiegeCannonBlock#getRenderShape} = INVISIBLE)。
 *
 * <p>{@link #getFacing} を常に北にしてあるのは、方位をブロックの向き(4 方向)ではなくボーンの
 * 連続した回転で出すため。置いた向きは初期方位としてブロックエンティティに入るだけ。
 *
 * <p>モデルの原点(旋回軸)がブロックの中心に来るように、{@link SiegeCannonGeometry#TURN_AXIS_X}
 * ぶん平行移動してから描く。砲口・座席のワールド座標を出す {@link SiegeCannonGeometry} と同じ基準。
 */
public class SiegeCannonRenderer extends GeoBlockRenderer<SiegeCannonBlockEntity> {

    /** 砲身がブロックから 9 ブロック近く伸びるので、描画範囲・描画距離ともに広く取る。 */
    private static final double BOUNDS_INFLATE = 12.0D;
    private static final int VIEW_DISTANCE = 192;

    public SiegeCannonRenderer(BlockEntityRendererProvider.Context context) {
        super(new SiegeCannonGeoModel());
    }

    /** 方位はボーン({@code dodai})で回すので、ブロックの向きでは回さない。 */
    @Override
    protected Direction getFacing(SiegeCannonBlockEntity block) {
        return Direction.NORTH;
    }

    @Override
    public void preRender(PoseStack poseStack, SiegeCannonBlockEntity animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        if (!isReRender) {
            float scale = (float) SiegeCannonGeometry.scale();
            poseStack.scale(scale, scale, scale);
            // 旋回軸をブロックの中心へ。モデル空間の X は geo と逆向き(GeckoLib がベイク時に反転する)。
            poseStack.translate(SiegeCannonGeometry.TURN_AXIS_X / 16.0D, 0.0D, -SiegeCannonGeometry.TURN_AXIS_Z / 16.0D);
        }
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public AABB getRenderBoundingBox(SiegeCannonBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(BOUNDS_INFLATE * SiegeCannonGeometry.scale());
    }
}
