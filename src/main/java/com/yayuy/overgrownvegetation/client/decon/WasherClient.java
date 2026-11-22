package com.yayuy.overgrownvegetation.client.decon;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;

/**
 * 地脈の洗い場のクライアント側の登録(2026-11-22): GeckoLib の描画と、槽の水の色。
 *
 * <p>水の面(地形の模型 {@code models/block/ley_washer.json} の {@code tintindex 1})は、本物の水と同じくバイオームの水の色で染める。
 * 染めの番号を 1 にしたのは、壊したときの粒(番号 0 で色を聞く)を青くしないため。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WasherClient {

    /** バイオームが分からないとき(アイテムの絵など)の水の色(バニラの既定の水の色)。 */
    private static final int DEFAULT_WATER = 0x3F76E4;

    private WasherClient() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModDecon.WASHER_BE.get(), context -> new WasherRenderer());
    }

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> {
            if (tintIndex != 1) {
                return -1;
            }
            return level != null && pos != null ? BiomeColors.getAverageWaterColor(level, pos) : DEFAULT_WATER;
        }, ModDecon.WASHER.get());
    }
}
