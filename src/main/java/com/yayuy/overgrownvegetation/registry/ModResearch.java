package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.research.ResearchBenchBlock;
import com.yayuy.overgrownvegetation.research.ResearchBenchBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * <b>研究台と研究ツリー</b>(2026-11-17 強くなる道 決定 20・22・23)の登録: 研究台・段 2 の器具(拡大鏡と蒸留器)・段 3 の器具(共鳴器)。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ({@link ModDecon} と同じ作り)。
 * 研究台の FE の口は受け取りだけ。器具は研究台から {@code instrument_radius} 以内に置くだけで働く(ただのブロック)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModResearch {

    private ModResearch() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
    }

    public static final DeferredBlock<ResearchBenchBlock> BENCH = ModBlocks.BLOCKS.registerBlock(
            "research_bench", ResearchBenchBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F)
                    .sound(SoundType.WOOD));

    public static final DeferredItem<BlockItem> BENCH_ITEM = ModItems.ITEMS.registerSimpleBlockItem(BENCH);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchBenchBlockEntity>> BENCH_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("research_bench", () -> BlockEntityType.Builder.of(
                    ResearchBenchBlockEntity::new, BENCH.get()).build(null));

    /** 段 2 の器具: 拡大鏡と蒸留器【仮の名前】。 */
    public static final DeferredBlock<Block> LENS = ModBlocks.BLOCKS.registerSimpleBlock(
            "research_lens",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F)
                    .sound(SoundType.LANTERN)
                    .noOcclusion());

    public static final DeferredItem<BlockItem> LENS_ITEM = ModItems.ITEMS.registerSimpleBlockItem(LENS);

    /** 段 3 の器具: 共鳴器【仮の名前】。 */
    public static final DeferredBlock<Block> RESONATOR = ModBlocks.BLOCKS.registerSimpleBlock(
            "ley_resonator",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(3.0F)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(s -> 6)
                    .noOcclusion());

    public static final DeferredItem<BlockItem> RESONATOR_ITEM = ModItems.ITEMS.registerSimpleBlockItem(RESONATOR);

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BENCH_BE.get(), (be, side) -> be.energyPort());
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。実験的要素の「研究」がオフのワールドでは並べない(11-23 夜 ユーザー決定「オフの物はクリエイティブタブで消える」)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        if (!com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.enabledAnywhere(
                com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.Feature.RESEARCH)) return;
        output.accept(BENCH_ITEM.get());
        output.accept(LENS_ITEM.get());
        output.accept(RESONATOR_ITEM.get());
    }
}
