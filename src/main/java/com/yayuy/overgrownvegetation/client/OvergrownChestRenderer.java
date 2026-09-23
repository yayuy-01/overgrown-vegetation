package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownChestBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BrightnessCombiner;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 「過繫茂したチェスト」の描画。バニラの {@code ChestRenderer} と同じ手順(向き・ラージチェスト結合・
 * 蓋の開閉アニメ・明るさ結合)でチェストモデルを描くが、
 * <ul>
 *     <li>本体はバニラのチェストのテクスチャを<b>緑がかった色</b>({@link #BASE_TINT})で描き、</li>
 *     <li>覆われ具合({@link OvergrownContainers#OVERGROWTH})が 1 以上なら、同じモデルを 3% 大きくして
 *         蔦・コケのカットアウトテクスチャ({@link #OVERLAYS})でもう一度描く(オーバーレイ)。</li>
 * </ul>
 * バニラの {@code ChestRenderer} を継承しないのは、モデルパーツと描画メソッドが private で
 * 二度描きできないため。ワールド外(アイテム描画、{@code getLevel() == null})では南向き・単体・段階 3 として描く。
 */
public class OvergrownChestRenderer implements BlockEntityRenderer<OvergrownChestBlockEntity> {

    /** 本体の色(ARGB)。バニラのチェストのテクスチャに乗算される。 */
    private static final int BASE_TINT = 0xFF8FB77A;
    /** 覆われ具合 1〜3 に対応するオーバーレイテクスチャ(index 0 は未使用)。 */
    private static final ResourceLocation[] OVERLAYS = {
            null,
            OvergrownVegetation.id("textures/entity/chest/overgrowth_overlay_1.png"),
            OvergrownVegetation.id("textures/entity/chest/overgrowth_overlay_2.png"),
            OvergrownVegetation.id("textures/entity/chest/overgrowth_overlay_3.png"),
    };
    private static final float OVERLAY_SCALE = 1.03F;

    private final ModelPart lid;
    private final ModelPart bottom;
    private final ModelPart lock;
    private final ModelPart doubleLeftLid;
    private final ModelPart doubleLeftBottom;
    private final ModelPart doubleLeftLock;
    private final ModelPart doubleRightLid;
    private final ModelPart doubleRightBottom;
    private final ModelPart doubleRightLock;

    public OvergrownChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart single = context.bakeLayer(ModelLayers.CHEST);
        this.bottom = single.getChild("bottom");
        this.lid = single.getChild("lid");
        this.lock = single.getChild("lock");
        ModelPart left = context.bakeLayer(ModelLayers.DOUBLE_CHEST_LEFT);
        this.doubleLeftBottom = left.getChild("bottom");
        this.doubleLeftLid = left.getChild("lid");
        this.doubleLeftLock = left.getChild("lock");
        ModelPart right = context.bakeLayer(ModelLayers.DOUBLE_CHEST_RIGHT);
        this.doubleRightBottom = right.getChild("bottom");
        this.doubleRightLid = right.getChild("lid");
        this.doubleRightLock = right.getChild("lock");
    }

    @Override
    public void render(OvergrownChestBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        boolean inWorld = level != null;
        BlockState state = inWorld
                ? blockEntity.getBlockState()
                : ModBlocks.OVERGROWN_CHEST.get().defaultBlockState()
                        .setValue(ChestBlock.FACING, Direction.SOUTH)
                        .setValue(OvergrownContainers.OVERGROWTH, OvergrownContainers.MAX_LEVEL);
        if (!(state.getBlock() instanceof AbstractChestBlock<?> chestBlock)) {
            return;
        }
        ChestType chestType = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;

        poseStack.pushPose();
        float yRot = state.getValue(ChestBlock.FACING).toYRot();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        DoubleBlockCombiner.NeighborCombineResult<? extends ChestBlockEntity> combined = inWorld
                ? chestBlock.combine(state, level, blockEntity.getBlockPos(), true)
                : DoubleBlockCombiner.Combiner::acceptNone;
        float openness = combined.apply(ChestBlock.opennessCombiner(blockEntity)).get(partialTick);
        openness = 1.0F - openness;
        openness = 1.0F - openness * openness * openness;
        int light = combined.apply(new BrightnessCombiner<>()).applyAsInt(packedLight);

        Material material = Sheets.chooseMaterial(blockEntity, chestType, false);
        VertexConsumer base = material.buffer(bufferSource, RenderType::entityCutout);
        renderParts(poseStack, base, chestType, openness, light, packedOverlay, BASE_TINT);

        int overgrowth = state.hasProperty(OvergrownContainers.OVERGROWTH) ? state.getValue(OvergrownContainers.OVERGROWTH) : 0;
        if (overgrowth > 0) {
            ResourceLocation overlayTexture = OVERLAYS[Math.min(overgrowth, OVERLAYS.length - 1)];
            poseStack.pushPose();
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.scale(OVERLAY_SCALE, OVERLAY_SCALE, OVERLAY_SCALE);
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            VertexConsumer overlay = bufferSource.getBuffer(RenderType.entityCutoutNoCull(overlayTexture));
            renderParts(poseStack, overlay, chestType, openness, light, packedOverlay, 0xFFFFFFFF);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private void renderParts(PoseStack poseStack, VertexConsumer consumer, ChestType chestType, float openness,
                             int light, int packedOverlay, int color) {
        if (chestType == ChestType.LEFT) {
            renderParts(poseStack, consumer, doubleLeftLid, doubleLeftLock, doubleLeftBottom, openness, light, packedOverlay, color);
        } else if (chestType == ChestType.RIGHT) {
            renderParts(poseStack, consumer, doubleRightLid, doubleRightLock, doubleRightBottom, openness, light, packedOverlay, color);
        } else {
            renderParts(poseStack, consumer, lid, lock, bottom, openness, light, packedOverlay, color);
        }
    }

    private static void renderParts(PoseStack poseStack, VertexConsumer consumer, ModelPart lidPart, ModelPart lockPart,
                                    ModelPart bottomPart, float openness, int light, int packedOverlay, int color) {
        lidPart.xRot = -(openness * ((float) Math.PI / 2F));
        lockPart.xRot = lidPart.xRot;
        lidPart.render(poseStack, consumer, light, packedOverlay, color);
        lockPart.render(poseStack, consumer, light, packedOverlay, color);
        bottomPart.render(poseStack, consumer, light, packedOverlay, color);
    }
}
