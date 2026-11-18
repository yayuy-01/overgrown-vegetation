package com.yayuy.overgrownvegetation.gear.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.ModGear;
import com.yayuy.overgrownvegetation.gear.PiercingCrossbowItem;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 段 1・段 2 の弓・クロスボウの引きの絵(2026-11-17)。バニラの弓・クロスボウと同じ述語({@code pull}・{@code pulling}・{@code charged}・{@code firework})を、
 * 自分の物にも付ける(付けないと引いても絵が変わらない)。クロスボウの引きの割合は自前の装填の長さで出す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GearClient {

    private GearClient() {
    }

    /** 地脈の杖の光の弾(2026-11-18)。絵は【仮】で光る棘の先のアイテム。 */
    @SubscribeEvent
    public static void registerRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModGear.LEY_BOLT.get(), ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.0F, true));
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation pull = ResourceLocation.withDefaultNamespace("pull");
            ResourceLocation pulling = ResourceLocation.withDefaultNamespace("pulling");
            ItemProperties.register(ModGear.SPORE_BOW.get(), pull, (stack, level, entity, seed) -> {
                if (entity == null || entity.getUseItem() != stack) return 0.0F;
                return (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F;
            });
            ItemProperties.register(ModGear.SPORE_BOW.get(), pulling,
                    (stack, level, entity, seed) -> entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);

            ItemProperties.register(ModGear.PIERCING_CROSSBOW.get(), pull, (stack, level, entity, seed) -> {
                if (entity == null || CrossbowItem.isCharged(stack)) return 0.0F;
                return (float) (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / PiercingCrossbowItem.chargeTicks(stack, entity);
            });
            ItemProperties.register(ModGear.PIERCING_CROSSBOW.get(), pulling, (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack && !CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
            ItemProperties.register(ModGear.PIERCING_CROSSBOW.get(), ResourceLocation.withDefaultNamespace("charged"),
                    (stack, level, entity, seed) -> CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
            ItemProperties.register(ModGear.PIERCING_CROSSBOW.get(), ResourceLocation.withDefaultNamespace("firework"), (stack, level, entity, seed) -> {
                ChargedProjectiles c = stack.get(DataComponents.CHARGED_PROJECTILES);
                return c != null && c.contains(Items.FIREWORK_ROCKET) ? 1.0F : 0.0F;
            });
        });
    }
}
