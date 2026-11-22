package com.yayuy.overgrownvegetation;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OvergrownVegetation — 寄生植物が世界を侵食していく MOD(Forge 1.20.1 版)。
 *
 * <p>2026-10-10 時点では土台だけ(起動してタイトル画面まで行くことの確認用)。
 * 登録は 1.21.1 版の同じクラス(DesignData.load → ShapedRecipePattern の 5×5 → ModBlocks … ModDataComponents → config)の順に、
 * 移植したものから足していく。1.20.1 での主な読み替え:
 * <ul>
 *     <li>{@code ModAttachments}(NeoForge の attachment)→ Forge の Capability</li>
 *     <li>{@code ModDataComponents} → アイテムの NBT(1.20.5 より前はデータコンポーネントが無い)</li>
 *     <li>{@code ShapedRecipePattern.setCraftingSize(5, 5)} → {@code ShapedRecipe.setCraftingSize(5, 5)}(Forge が足したもの)</li>
 *     <li>{@code container.registerConfig} → {@code ModLoadingContext.get().registerConfig}</li>
 * </ul>
 */
@Mod(OvergrownVegetation.MODID)
public class OvergrownVegetation {

    public static final String MODID = "overgrown_vegetation";
    public static final Logger LOGGER = LoggerFactory.getLogger("OvergrownVegetation");

    public OvergrownVegetation(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        // 互換の層(attachment・通信・tick のイベント)。ほかの登録より先に
        com.yayuy.overgrownvegetation.port.PortSetup.init(modBus);

        LOGGER.info("OvergrownVegetation (Forge 1.20.1) loaded.");
    }

    public static ResourceLocation id(String path) {
        // Forge 47.4 には 1.21 の作り方が逆輸入されている(new ResourceLocation(..) は非推奨)
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
