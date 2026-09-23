package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 種族専用の GeckoLib モデルを持つ汚染死体({@link com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock}:
 * 羊・豚・クリーパー)の描画。
 *
 * <p>この BER は
 * チャンクメッシュにまとめて焼き込まれず 1 個体につき毎フレーム 1 回ずつ個別に描画される。
 * 描画距離を {@value #VIEW_DISTANCE} ブロックへ縮めて、遠くの死体は描画しない
 * (= 近づかないと描画されない)。
 *
 * <p>{@link com.yayuy.overgrownvegetation.registry.ModBlockEntities#CORRUPTED_CORPSE} は
 * 牛・ゾンビ・クモの死体(通常のブロックモデル描画)とも共用しているため、専用 geo モデルを
 * 持たないブロックでは何も描画しない({@link CorruptedCorpseModel#hasGeoModel})。
 */
public class CorruptedCorpseRenderer extends GeoBlockRenderer<CorruptedCorpseBlockEntity> {

    /** この BER が描画される最大距離(ブロック)。バニラ既定の 64 より大幅に短い。 */
    private static final int VIEW_DISTANCE = 24;

    public CorruptedCorpseRenderer() {
        super(new CorruptedCorpseModel());
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public void render(CorruptedCorpseBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!CorruptedCorpseModel.hasGeoModel(blockEntity.getBlockState().getBlock())) {
            // 専用モデルの無い死体(牛・ゾンビ・クモ)は通常のブロックモデルで描画済み。
            return;
        }
        super.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }
}
