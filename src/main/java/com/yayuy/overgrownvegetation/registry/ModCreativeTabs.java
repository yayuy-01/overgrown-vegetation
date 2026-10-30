package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * クリエイティブタブ(2026-10-05 に 1 つから 3 つへ分けた。ユーザー指定)。
 *
 * <ul>
 *     <li><b>覆われたブロック</b>({@link #COVERED}) —— バニラのブロックが汚染されたときの対応ブロックと、
 *     バニラの建築タブの覆われた版({@link CoveredBlocks})。</li>
 *     <li><b>植物</b>({@link #PLANTS}) —— 汚染された土地に生える植物・構造物のブロック、植物側の Mob のスポーンエッグ、
 *     植物由来の素材(ドロップ・結晶)、寄生植物装備。</li>
 *     <li><b>人類</b>({@link #HUMANITY}) —— 機械・防衛設備・基地のブロック・同盟軍、加工した素材、装備とツール
 *     (寄生植物装備だけは植物)。</li>
 * </ul>
 *
 * <p>素材の分け方はユーザー指定の外の仮決め: <b>倒した Mob・掘った結晶そのもの = 植物、人が加工したもの(合金など) = 人類</b>。
 * {@link ModBlocks#displayOrder()} のブロックは、{@link #COVERED_ORDER} に挙げた対応ブロック以外を植物へ入れる
 * (新しい植物のブロックは displayOrder に足せば植物のタブに出る)。
 *
 * <p><b>植物・人類のタブの最終的な並びは {@link CreativeTabOrder} が決める</b>(2026-10-10 ユーザー指定: ブロック → 機械 → 植物 →
 * ドロップアイテムと中間素材 → スポーンエッグ → 武器防具 → その他)。ここでの並びは区分の中の順になるだけ。
 * 機械・その他など種類で見分けられないアイテムを足したら、{@link CreativeTabOrder} の一覧にも足す。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OvergrownVegetation.MODID);

    // ---- 覆われたブロックのタブの並び(2026-10-05 にユーザー要望で整理。バニラのタブに近い順: 土 → 石 → 鉱石 → 木 → ネザー・エンド) ----

    /** 土・砂・砂利。 */
    private static final List<DeferredBlock<Block>> COVERED_SOIL = List.of(
            ModBlocks.OVERGROWN_GRASS_BLOCK,
            ModBlocks.OVERGROWN_DIRT,
            ModBlocks.OVERGROWN_MUD,
            ModBlocks.OVERGROWN_MUD_SLAB,
            ModBlocks.OVERGROWN_GRAVEL,
            ModBlocks.OVERGROWN_SAND);

    /** 石とその加工品(汎用の汚染ブロックもここ)。 */
    private static final List<DeferredBlock<Block>> COVERED_STONE = List.of(
            ModBlocks.OVERGROWN_STONE,
            ModBlocks.OVERGROWN_COBBLESTONE,
            ModBlocks.OVERGROWN_STONE_STAIRS,
            ModBlocks.OVERGROWN_WALL,
            ModBlocks.OVERGROWN_GRANITE,
            ModBlocks.OVERGROWN_DIORITE,
            ModBlocks.OVERGROWN_ANDESITE,
            ModBlocks.OVERGROWN_DEEPSLATE,
            ModBlocks.OVERGROWN_COBBLED_DEEPSLATE,
            ModBlocks.OVERGROWN_DRIPSTONE_BLOCK,
            ModBlocks.OVERGROWN_BRICKS,
            ModBlocks.OVERGROWN_BLOCK,
            ModBlocks.OVERGROWN_BLOCK_SLAB);

    /** 鉱石(バニラと同じ並び: 石炭・鉄・銅・金・レッドストーン・エメラルド・ラピスラズリ・ダイヤモンド、深層岩の版と組)。 */
    private static final List<DeferredBlock<Block>> COVERED_ORES = List.of(
            ModBlocks.OVERGROWN_COAL_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_COAL_ORE,
            ModBlocks.OVERGROWN_IRON_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_IRON_ORE,
            ModBlocks.OVERGROWN_COPPER_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_COPPER_ORE,
            ModBlocks.OVERGROWN_GOLD_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_GOLD_ORE,
            ModBlocks.OVERGROWN_REDSTONE_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_REDSTONE_ORE,
            ModBlocks.OVERGROWN_EMERALD_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_EMERALD_ORE,
            ModBlocks.OVERGROWN_LAPIS_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_LAPIS_ORE,
            ModBlocks.OVERGROWN_DIAMOND_ORE,
            ModBlocks.OVERGROWN_DEEPSLATE_DIAMOND_ORE);

    /** 木(このあとに扉・チェスト・樽のアイテムが続く)。 */
    private static final List<DeferredBlock<Block>> COVERED_WOOD = List.of(
            ModBlocks.OVERGROWN_LOG,
            ModBlocks.OVERGROWN_WOOD,
            ModBlocks.OVERGROWN_LEAVES,
            ModBlocks.OVERGROWN_PLANKS,
            ModBlocks.OVERGROWN_STAIRS,
            ModBlocks.OVERGROWN_SLAB,
            ModBlocks.OVERGROWN_FENCE);

    /** ネザー・エンド。 */
    private static final List<DeferredBlock<Block>> COVERED_NETHER_END = List.of(
            ModBlocks.OVERGROWN_NETHERRACK,
            ModBlocks.OVERGROWN_SOUL_SAND,
            ModBlocks.OVERGROWN_SOUL_SOIL,
            ModBlocks.OVERGROWN_END_STONE);

    /** {@link ModBlocks#displayOrder()} のうち覆われたブロックのタブに入れるもの(上の 5 つを合わせたもの。植物のタブはこれ以外)。 */
    private static final List<DeferredBlock<Block>> COVERED_ORDER = java.util.stream.Stream.of(
                    COVERED_SOIL, COVERED_STONE, COVERED_ORES, COVERED_WOOD, COVERED_NETHER_END)
            .flatMap(List::stream).toList();

    /** 覆われたブロック: バニラのブロックが汚染されたときの対応ブロック + 建築タブの覆われた版。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> COVERED = TABS.register("covered",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + OvergrownVegetation.MODID + ".covered"))
                    .icon(() -> new ItemStack(ModBlocks.OVERGROWN_GRASS_BLOCK.get()))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .displayItems((params, output) -> {
                        COVERED_SOIL.forEach(block -> output.accept(block.get()));
                        COVERED_STONE.forEach(block -> output.accept(block.get()));
                        COVERED_ORES.forEach(block -> output.accept(block.get()));
                        COVERED_WOOD.forEach(block -> output.accept(block.get()));
                        // 汚染で専用の姿になる扉・チェスト・樽(木の続き)
                        output.accept(ModItems.OVERGROWN_DOOR_ITEM.get());
                        output.accept(ModItems.OVERGROWN_CHEST_ITEM.get());
                        output.accept(ModItems.OVERGROWN_BARREL_ITEM.get());
                        COVERED_NETHER_END.forEach(block -> output.accept(block.get()));
                        // バニラの建築タブの覆われた版(バニラの並び。最後に建築タブの外のレバー)
                        CoveredBlocks.entries().forEach(entry -> output.accept(entry.item().get()));
                    })
                    .build());

    /** 植物: 汚染された土地に生えるもの・植物側の Mob・植物由来の素材・寄生植物装備。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PLANTS = TABS.register("plants",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + OvergrownVegetation.MODID + ".plants"))
                    .icon(() -> new ItemStack(ModBlocks.OVERGROWN_SAPLING.get()))
                    .withTabsBefore(COVERED.getKey())
                    .displayItems((params, output) -> {
                        ModBlocks.displayOrder().stream()
                                .filter(block -> !COVERED_ORDER.contains(block))
                                .forEach(block -> output.accept(block.get()));
                        output.accept(ModItems.GIANT_OVERGROWN_SAPLING_ITEM.get());

                        // 植物側の Mob
                        output.accept(ModItems.HIVE_NODE_SPAWN_EGG.get());
                        output.accept(ModItems.BLIGHT_FERN_SPAWN_EGG.get());

                        // 植物から採れるもの
                        output.accept(ModItems.SPORE.get());
                        output.accept(ModItems.OVERGROWN_GLOWBERRY.get());
                        output.accept(ModItems.VINE_FRUIT.get());
                        output.accept(ModItems.UNDEVELOPED_SEED.get());
                        output.accept(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM.get());
                        output.accept(ModItems.PLANT_FIBER.get());

                        output.accept(ModItems.SPORE_GLAND_TISSUE.get());
                        output.accept(ModItems.SPORE_SAC.get());

                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
