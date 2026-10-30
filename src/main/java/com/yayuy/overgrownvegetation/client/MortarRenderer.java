package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.MortarBlock;
import com.yayuy.overgrownvegetation.block.MortarBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * 乳鉢の中身と乳棒(2026-10-16)。器はブロックの模型({@code models/block/mortar})、乳棒はここで別の模型
 * ({@code models/block/mortar_pestle}、z 0〜12 にまっすぐ伸び、z 10〜12 が太い頭)を描く。
 * <ul>
 *     <li>ふだん: ユーザーの模型のとおり、器の横に寝かせる(y 軸 −22.5 度、(4, 1, 6) を中心に)。</li>
 *     <li>すっている間({@link MortarBlockEntity#grindUntil()} まで): 頭を器の底に当て、少し傾けて器の中心のまわりを回す。</li>
 *     <li>中身: 器の底に小さく並べる(最大 6 つ)。</li>
 * </ul>
 */
public class MortarRenderer implements BlockEntityRenderer<MortarBlockEntity> {

    public static final ModelResourceLocation PESTLE = ModelResourceLocation.standalone(OvergrownVegetation.id("block/mortar_pestle"));

    public MortarRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MortarBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffer, int light, int overlay) {
        var level = be.getLevel();
        if (level == null) {
            return;
        }
        int turns = switch (be.getBlockState().getValue(MortarBlock.FACING)) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        pose.pushPose();
        // ブロックの向き(模型は北向きで作る。y 回転は上から見て時計回り)
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F * turns));
        pose.translate(-0.5F, 0.0F, -0.5F);

        // 中身
        List<ItemStack> items = be.contents();
        for (int i = 0; i < items.size(); i++) {
            double a = (Math.PI * 2.0D) * i / Math.max(1, items.size());
            float r = items.size() == 1 ? 0.0F : 1.3F / 16.0F;
            pose.pushPose();
            pose.translate(10.0F / 16.0F + r * (float) Math.cos(a), 1.25F / 16.0F + i * 0.002F, 8.0F / 16.0F + r * (float) Math.sin(a));
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(i * 47.0F));
            pose.scale(0.22F, 0.22F, 0.22F);
            Minecraft.getInstance().getItemRenderer().renderStatic(items.get(i), ItemDisplayContext.FIXED, light, overlay, pose, buffer,
                    level, (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }

        // 乳棒
        BakedModel pestle = Minecraft.getInstance().getModelManager().getModel(PESTLE);
        pose.pushPose();
        boolean grinding = level.getGameTime() < be.grindUntil();
        if (grinding) {
            float spin = (level.getGameTime() + partialTick) * 24.0F;
            pose.translate(10.0F / 16.0F, 1.6F / 16.0F, 8.0F / 16.0F);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.translate(0.9F / 16.0F, 0.0F, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(24.0F));
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose.translate(-4.0F / 16.0F, -1.05F / 16.0F, -11.0F / 16.0F); // 頭の中心を原点に
        } else {
            pose.translate(4.0F / 16.0F, 0.0F, 6.0F / 16.0F);
            pose.mulPose(Axis.YP.rotationDegrees(-22.5F));
            pose.translate(-4.0F / 16.0F, 0.0F, -6.0F / 16.0F);
        }
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(),
                buffer.getBuffer(Sheets.cutoutBlockSheet()), null, pestle, 1.0F, 1.0F, 1.0F, light, overlay,
                ModelData.EMPTY, Sheets.cutoutBlockSheet());
        pose.popPose();
        pose.popPose();
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Models {
        @SubscribeEvent
        public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
            event.register(PESTLE);
        }

        private Models() {
        }
    }
}
