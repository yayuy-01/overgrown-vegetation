package com.yayuy.overgrownvegetation.gear.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.ModGear;
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
 * 導晶の弩の絵の切り替え(引き具合・込めた矢・花火)。バニラはこの 4 つを {@code Items.CROSSBOW} にだけ付けるので、同じものを付ける
 * (引き具合は {@code CrossbowItem.getChargeDuration} を見るので、力がある間の速い装填にも合う)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ConduitClient {

    private ConduitClient() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            var bow = ModGear.CONDUIT_CROSSBOW.get();
            ItemProperties.register(bow, ResourceLocation.withDefaultNamespace("pull"), (stack, level, entity, seed) -> {
                if (entity == null || CrossbowItem.isCharged(stack)) return 0.0F;
                return (float) (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks())
                        / (float) CrossbowItem.getChargeDuration(stack, entity);
            });
            ItemProperties.register(bow, ResourceLocation.withDefaultNamespace("pulling"), (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack && !CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
            ItemProperties.register(bow, ResourceLocation.withDefaultNamespace("charged"), (stack, level, entity, seed) ->
                    CrossbowItem.isCharged(stack) ? 1.0F : 0.0F);
            ItemProperties.register(bow, ResourceLocation.withDefaultNamespace("firework"), (stack, level, entity, seed) -> {
                ChargedProjectiles p = stack.get(DataComponents.CHARGED_PROJECTILES);
                return p != null && p.contains(Items.FIREWORK_ROCKET) ? 1.0F : 0.0F;
            });
        });
    }
}
