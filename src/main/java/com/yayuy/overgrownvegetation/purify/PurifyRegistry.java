package com.yayuy.overgrownvegetation.purify;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 浄化(順 2 の着手順 9、2026-10-08)の登録。共有の登録簿を太らせないよう、DeferredRegister だけ借りてここにまとめる
 * ({@link #init()} を {@code ModBlocks.register} / {@code ModItems.register} / {@code ModDataComponents.register} より前に呼ぶ ——
 * {@code ally.AllyItems#init} と同じ流儀)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class PurifyRegistry {

    /** 焼けた土。 */
    public static final DeferredBlock<Block> BURNT_SOIL = ModBlocks.BLOCKS.registerBlock("burnt_soil", BurntSoilBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.COARSE_DIRT).mapColor(MapColor.COLOR_GRAY).randomTicks());
    public static final DeferredItem<BlockItem> BURNT_SOIL_ITEM = ModItems.ITEMS.registerSimpleBlockItem(BURNT_SOIL,
            new Item.Properties());

    /** 火炎放射器(研究ノード「浄化」がレシピ {@code recipe/flamethrower.json} を開く)。ふつうの火をまく(2026-10-09 朝に専用の炎のブロックを外した)。 */
    public static final DeferredItem<Item> FLAMETHROWER = ModItems.ITEMS.registerItem("flamethrower", FlamethrowerItem::new,
            new Item.Properties().stacksTo(1));

    /** 火炎放射器の残りの燃料(tick)。ツールチップに出すのでクライアントにも送る。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> FLAMETHROWER_FUEL =
            ModDataComponents.COMPONENTS.registerComponentType("flamethrower_fuel", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    public static void init() {
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.HUMANITY.getKey()) {
            event.accept(FLAMETHROWER.get());
            event.accept(BURNT_SOIL_ITEM.get());
        }
    }

    private PurifyRegistry() {
    }
}
