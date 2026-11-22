package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.IItemDecorator;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;

/**
 * 装備の汚れの見た目の登録(2026-11-14 サバイバルの背骨 段 3b、決定 33): アイコンの汚れ(すべてのアイテムに飾りを付け、汚れた物だけ描く)と、
 * 体に着た姿の汚れ(プレイヤーと防具立てに {@link DirtyArmorLayer})。数字は出さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DirtClient {

    private DirtClient() {
    }

    public static final ModelLayerLocation INNER = new ModelLayerLocation(OvergrownVegetation.id("gear_dirt"), "inner");
    public static final ModelLayerLocation OUTER = new ModelLayerLocation(OvergrownVegetation.id("gear_dirt"), "outer");

    /** アイコンの上に粉を重ねる(汚れていない物は何もしない)。 */
    static final IItemDecorator DECORATOR = (g, font, stack, x, y) -> {
        int stage = GearDirt.stage(stack);
        if (stage == 0) return false;
        DirtOverlays.Overlay o = DirtOverlays.itemOverlay(stack, stage);
        if (o == null) return false;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 200.0F);
        RenderSystem.enableBlend();
        g.blit(o.texture(), x, y, 16, 16, 0.0F, 0.0F, o.width(), o.height(), o.width(), o.height());
        g.pose().popPose();
        return false;
    };

    @SubscribeEvent
    public static void onItemDecorations(RegisterItemDecorationsEvent event) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (new net.minecraft.world.item.ItemStack(item).getMaxStackSize() == 1) {
                event.register(item, DECORATOR);
            }
        }
    }

    @SubscribeEvent
    public static void onLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        // バニラの防具(内 0.5・外 1.0)より少しだけふくらませる(重なってちらつかないように)
        event.registerLayerDefinition(INNER, () -> LayerDefinition.create(HumanoidArmorModel.createBodyLayer(new CubeDeformation(0.53F)), 64, 32));
        event.registerLayerDefinition(OUTER, () -> LayerDefinition.create(HumanoidArmorModel.createBodyLayer(new CubeDeformation(1.04F)), 64, 32));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        var models = event.getEntityModels();
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<?> r = event.getSkin(skin);
            if (r instanceof LivingEntityRenderer lr) {
                lr.addLayer(new DirtyArmorLayer(lr, new HumanoidArmorModel<>(models.bakeLayer(INNER)), new HumanoidArmorModel<>(models.bakeLayer(OUTER))));
            }
        }
        EntityRenderer<?> stand = event.getRenderer(EntityType.ARMOR_STAND);
        if (stand instanceof LivingEntityRenderer lr) {
            lr.addLayer(new DirtyArmorLayer(lr, new HumanoidArmorModel<>(models.bakeLayer(INNER)), new HumanoidArmorModel<>(models.bakeLayer(OUTER))));
        }
    }

    @SubscribeEvent
    public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> DirtOverlays.clear());
    }
}
