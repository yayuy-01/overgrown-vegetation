package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.defense.GunTurretGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 機関砲台の描画。モデルは丸ごと GeckoLib({@link GunTurretGeoModel})で、台座の旋回・銃身の俯仰・
 * 発砲の反動はそのモデルがボーンに入れる。ブロックモデル自体は描かない
 * ({@code GunTurretBlock#getRenderShape} = INVISIBLE)。
 *
 * <p>{@link #getFacing} を常に北にしてあるのは、方位をブロックの向き(4 方向)ではなくボーンの
 * 連続した回転で出すため。置いた向きは初期方位としてブロックエンティティに入るだけ。
 */
public class GunTurretRenderer extends GeoBlockRenderer<GunTurretBlockEntity> {

    /** 銃身が 1 マスの外へ 1.6 ブロックほど伸びる(倍率を掛ける前)ので、描画範囲を広めに取る。 */
    private static final double BOUNDS_INFLATE = 2.5D;

    public GunTurretRenderer(BlockEntityRendererProvider.Context context) {
        super(new GunTurretGeoModel());
    }

    /** 方位はボーン({@code base})で回すので、ブロックの向きでは回さない。 */
    @Override
    protected Direction getFacing(GunTurretBlockEntity block) {
        return Direction.NORTH;
    }

    /**
     * モデルの倍率({@code defense.json} の {@code gunTurret.modelScale})。銃口の位置を出す
     * {@link GunTurretGeometry#offset} と同じ値なので、大きくしても弾の出どころは銃口のまま。
     *
     * <p>原点(= 旋回軸)はブロックの底面中央のままなので、平行移動は要らない —— 拡大すると
     * 台座がその場で大きくなり、1 マスから少しはみ出す。
     */
    @Override
    public void preRender(PoseStack poseStack, GunTurretBlockEntity animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        if (!isReRender) {
            float scale = (float) GunTurretGeometry.scale();
            poseStack.scale(scale, scale, scale);
        }
    }

    @Override
    public AABB getRenderBoundingBox(GunTurretBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(BOUNDS_INFLATE * GunTurretGeometry.scale());
    }
}
