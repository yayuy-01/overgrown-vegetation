package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.drift.DriftMimicEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

/**
 * 漂流する擬態 Mob の<b>仮の描画</b>(2026-11-20)。形はモデル制作(Fable)が並行して案を出している —— 決まったら GeckoLib のモデル
 * ({@code geo/drift_mimic.geo.json} など)と {@code GeoEntityRenderer} に差し替える(登録は {@code DriftClient} の 1 行)。
 *
 * <p>今は「遠目には流木」に見える箱: 汚染された丸太を細く縮めて 3 本つないだ幹 + 斜めの枝 1 本 + 幹の上に貼り付いた苔の薄板。
 * 水面で少し揺れる(描画だけ)。体の向き(yaw)に合わせて幹を回す。
 */
public class DriftMimicRenderer extends EntityRenderer<DriftMimicEntity> {

    public DriftMimicRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(DriftMimicEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockRenderDispatcher br = Minecraft.getInstance().getBlockRenderer();
        BlockState log = ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
        if (log.hasProperty(RotatedPillarBlock.AXIS)) log = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        BlockState moss = Blocks.MOSS_CARPET.defaultBlockState();
        float t = entity.tickCount + partialTick;
        float bodyYaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        // 水面の揺れ(描画だけ): ゆっくり転がり、前後に傾く
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.07F + entity.getId()) * 4.0F));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(t * 0.05F + entity.getId() * 0.7F) * 2.5F));
        // 幹: 0.55 角 × 長さ 1.0 を 3 本(前後に 0.1 ずつずらして継ぎ目を見せる)
        float w = 0.55F;
        for (int i = 0; i < 3; i++) {
            pose.pushPose();
            pose.translate(-w / 2.0F + (i == 1 ? 0.04F : 0.0F), 0.0F, -1.5F + i * 1.0F);
            pose.scale(w, w, 1.0F);
            br.renderSingleBlock(log, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        // 枝: 斜めに 1 本
        pose.pushPose();
        pose.translate(0.1F, 0.2F, 0.3F);
        pose.mulPose(Axis.YP.rotationDegrees(40.0F));
        pose.mulPose(Axis.XP.rotationDegrees(-12.0F));
        pose.scale(0.3F, 0.3F, 1.1F);
        br.renderSingleBlock(log, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        // 苔の薄板(藻の塊に見える所)
        pose.pushPose();
        pose.translate(-0.35F, w - 0.02F, -0.9F);
        pose.scale(0.7F, 1.0F, 1.4F);
        br.renderSingleBlock(moss, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(DriftMimicEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
