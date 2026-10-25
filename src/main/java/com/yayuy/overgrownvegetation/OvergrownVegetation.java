package com.yayuy.overgrownvegetation;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overgrown Vegetation — 古代の植物アルケフィタが地上を取り戻しに来る、植物の感染 MOD。
 *
 * <p>2026-10-25 から作り直し中。古い作りは {@code C:\minecraft_mod制作\overgrown\parasitica\parasitica}(参考書として残す)。
 * 段の順番は Vault の「作り直し」ノート。
 */
@Mod(OvergrownVegetation.MODID)
public final class OvergrownVegetation {

    public static final String MODID = "overgrown_vegetation";
    public static final Logger LOGGER = LoggerFactory.getLogger("OvergrownVegetation");

    public OvergrownVegetation(IEventBus modBus, ModContainer container) {
        // 設計データは登録より先に読む(体格・属性・効果の数値が登録の時点で要る)
        DesignData.load();

        ModBlocks.register(modBus);
        ModItems.register(modBus);
        ModAttachments.register(modBus);

        LOGGER.info("Overgrown Vegetation loaded.");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
