package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.organ.OrganEntities;
import com.yayuy.overgrownvegetation.organ.OrganTentacle;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 器官の触手の仮の描き方(2026-11-17): 汚染の幹のブロックを小さくして、{@link OrganTentacle#point} の節に並べる(先ほど細い)。
 * 枯れたら巨大樹の幹(暗い)に替える。モデル(Fable)ができたら差し替える。
 */
public class OrganTentacleRenderer extends EntityRenderer<OrganTentacle> {

    public OrganTentacleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(OrganTentacle e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockRenderDispatcher br = Minecraft.getInstance().getBlockRenderer();
        BlockState s = (e.limp() ? ModBlocks.GIANT_OVERGROWN_LOG.get() : ModBlocks.OVERGROWN_LOG.get()).defaultBlockState();
        float t = e.tickCount + partialTick;
        int n = e.segments();
        for (int i = 0; i <= n; i++) {
            Vec3 p = e.point(i, t);
            float size = (float) (0.95D - 0.55D * i / Math.max(1, n));
            pose.pushPose();
            pose.translate(p.x - size / 2.0F, p.y - size / 2.0F, p.z - size / 2.0F);
            pose.scale(size, size, size);
            br.renderSingleBlock(s, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(OrganTentacle e) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** 登録(共有の OvergrownVegetationClient を触らないよう、ここで)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(OrganEntities.TENTACLE.get(), OrganTentacleRenderer::new);
        }
    }
}
