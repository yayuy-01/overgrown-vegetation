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
                        output.accept(ModItems.OVERGROWN_SHIELDER_SPAWN_EGG.get());
                        output.accept(ModItems.FALCIMNES_SPAWN_EGG.get());
                        output.accept(ModItems.SCUTIMNES_SPAWN_EGG.get());
                        output.accept(ModItems.ACUMNES_SPAWN_EGG.get());
                        output.accept(ModItems.BATHYMNES_SPAWN_EGG.get());
                        output.accept(ModItems.EREBOMNES_SPAWN_EGG.get());
                        output.accept(ModItems.VORAMNES_SPAWN_EGG.get());
                        output.accept(ModItems.FODIMNES_SPAWN_EGG.get());
                        output.accept(ModItems.PHALANGIOTARBUS_SPAWN_EGG.get());
                        output.accept(ModItems.PROTOPHASMA_SPAWN_EGG.get());
                        output.accept(ModItems.ARTHROPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.SPELAEOPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.COELUROSAURAVUS_SPAWN_EGG.get());
                        output.accept(ModItems.LITHOPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.STENODICTYA_LOBATA_SPAWN_EGG.get());
                        output.accept(ModItems.MEGANEURA_SPAWN_EGG.get());
                        output.accept(ModItems.ARBORA_SPAWN_EGG.get());
                        output.accept(ModItems.PHYLLOSENS_SPAWN_EGG.get());
                        output.accept(ModItems.VELOPHYLL_SPAWN_EGG.get());
                        output.accept(ModItems.PEDICULA_SPAWN_EGG.get());
                        output.accept(ModItems.SPOROPHYLL_SPAWN_EGG.get());
                        output.accept(ModItems.PETROLACOSAURUS_SPAWN_EGG.get());
                        output.accept(ModItems.IANTHASAURUS_SPAWN_EGG.get());
                        output.accept(ModItems.HYLONOMUS_SPAWN_EGG.get());
                        output.accept(ModItems.RHIZODUS_SPAWN_EGG.get());
                        output.accept(ModItems.XENACANTHUS_SPAWN_EGG.get());
                        output.accept(ModItems.ORTHACANTHUS_SPAWN_EGG.get());
                        output.accept(ModItems.MEGARACHNE_SPAWN_EGG.get());
                        output.accept(ModItems.DIPLOCAULUS_SPAWN_EGG.get());
                        output.accept(ModItems.LUNGFISH_SPAWN_EGG.get());
                        output.accept(ModItems.HIBBERTOPTERUS_SPAWN_EGG.get());
                        output.accept(ModItems.GIANT_SNAIL_SPAWN_EGG.get());
                        output.accept(ModItems.STENODICTYA_NYMPH_SPAWN_EGG.get());
                        output.accept(ModItems.ERYOPS_SPAWN_EGG.get());
                        output.accept(ModItems.PULMONOSCORPIUS_SPAWN_EGG.get());
                        output.accept(ModItems.CRASSIGYRINUS_SPAWN_EGG.get());
                        output.accept(ModItems.OVERGROWN_NEXUS_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_COW_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_SHEEP_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_PIG_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_ZOMBIE_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_VILLAGER_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_CHICKEN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_SKELETON_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_ENDERMAN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_BABY_ZOMBIE_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_PIGLIN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_PIGLIN_BRUTE_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_ZOMBIFIED_PIGLIN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_WITHER_SKELETON_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_HOGLIN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_ZOGLIN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_STRIDER_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_GHAST_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_BLAZE_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_IRON_GOLEM_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_WARDEN_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_CREEPER_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_SPIDER_SPAWN_EGG.get());

                        // 植物から採れるもの
                        output.accept(ModItems.SPORE.get());
                        output.accept(ModItems.OVERGROWN_GLOWBERRY.get());
                        output.accept(ModItems.VINE_FRUIT.get());
                        output.accept(ModItems.UNDEVELOPED_SEED.get());
                        output.accept(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM.get());
                        output.accept(ModItems.PLANT_FIBER.get());
                        output.accept(ModItems.HARDENED_BARK.get());

                        // Mob のドロップ
                        output.accept(ModItems.ARTHROPOD_LIMB.get());
                        output.accept(ModItems.OVERGROWN_EXOSKELETON.get());
                        output.accept(ModItems.SCATURIUM_EXOSKELETON.get());
                        output.accept(ModItems.PHALANGIOTARBUS_FANG.get());
                        output.accept(ModItems.MEGANEURA_COMPOUND_EYE.get());
                        output.accept(ModItems.MEGANEURA_WING.get());
                        output.accept(ModItems.PROTOPHASMA_ANTENNA.get());
                        output.accept(ModItems.REPTILE_SCUTE.get());
                        output.accept(ModItems.SAIL_MEMBRANE.get());
                        output.accept(ModItems.FISH_SCALE.get());
                        output.accept(ModItems.MEGARACHNE_PINCER.get());
                        // グループ共通のドロップ・適応の記録・G1 の汚染されたバニラ素材(順 1 の着手順 11)
                        output.accept(ModItems.PARASITIC_MYCELIUM.get());
                        output.accept(ModItems.ARTHROPOD_SHELL.get());
                        output.accept(ModItems.PREDATOR_TENDON.get());
                        output.accept(ModItems.SPORE_GLAND_TISSUE.get());
                        output.accept(ModItems.SPORE_SAC.get());
                        output.accept(ModItems.ADAPTATION_RECORD_GRASP.get());
                        output.accept(ModItems.ADAPTATION_RECORD_SHIELD.get());
                        output.accept(ModItems.ADAPTATION_RECORD_AIM.get());
                        output.accept(ModItems.ADAPTATION_RECORD_WATER.get());
                        output.accept(ModItems.ADAPTATION_RECORD_DARK.get());
                        output.accept(ModItems.ADAPTATION_RECORD_DEVOUR.get());
                        output.accept(ModItems.ADAPTATION_RECORD_DIG.get());
                        output.accept(ModItems.CONTAMINATED_MEAT.get());
                        output.accept(ModItems.ROTTEN_SPORE_FLESH.get());
                        output.accept(ModItems.CONTAMINATED_WOOL.get());
                        output.accept(ModItems.CONTAMINATED_STRING.get());

                        // 汚染された土地の結晶
                        output.accept(ModItems.RAW_CHLOROPHYLLITE.get());
                        output.accept(ModItems.RAW_HARDENED_SAP_CRYSTAL.get());
                        output.accept(ModItems.CHLOROPHYLLITE.get());
                        output.accept(ModItems.SAP_CRYSTAL.get());
                        output.accept(ModItems.RAW_PHLOEMITE.get());
                        output.accept(ModItems.PHLOEMITE.get());
                        output.accept(ModItems.RAW_THYLAKITE.get());
                        output.accept(ModItems.THYLAKITE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_BLOCK_ITEM.get());
                        output.accept(ModItems.SAP_CRYSTAL_BLOCK_ITEM.get());

                        // 装備のうち寄生植物装備だけは植物(ユーザー指定)
                        output.accept(ModItems.PLANT_ARMOR.get());
                    })
                    .build());

    /** 人類: 機械・防衛設備・基地(BaseBlocks)・同盟軍(Ally*)、加工した素材、装備とツール。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> HUMANITY = TABS.register("humanity",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + OvergrownVegetation.MODID + ".humanity"))
                    .icon(() -> new ItemStack(ModItems.GUN_TURRET_ITEM.get()))
                    .withTabsBefore(PLANTS.getKey())
                    .displayItems((params, output) -> {
                        // 作業台・機械
                        output.accept(ModItems.RESEARCH_TABLE_ITEM.get());
                        output.accept(ModItems.POWERED_ARMOR_TABLE_ITEM.get());
                        output.accept(ModItems.FACTOR_EXTRACTOR_ITEM.get());
                        output.accept(ModItems.SPORE_PURIFIER_ITEM.get());
                        output.accept(ModItems.SPORE_DIFFUSER_ITEM.get());
                        output.accept(ModItems.DECONTAMINATOR_ITEM.get());

                        // 計測・道具
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_1.get());
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_2.get());
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_3.get());
                        output.accept(ModItems.SPORE_METER.get());
                        output.accept(ModItems.PHASE_METER.get());
                        output.accept(ModItems.GUIDEBOOK.get());
                        output.accept(ModItems.PACK_SCANNER.get());
                        output.accept(ModItems.SPORE_FILTER.get());
                        output.accept(ModItems.SPORE_MEMBRANE.get());
                        output.accept(ModItems.PROTECTIVE_MASK.get());
                        output.accept(ModItems.BREATHING_CLOTH.get());
                        output.accept(ModItems.GAMBESON.get());
                        output.accept(ModItems.AMBER_LANTERN.get());
                        output.accept(ModItems.TRAIL_RATION_BASE.get());
                        output.accept(ModItems.TRAIL_RATION.get());
                        output.accept(ModItems.MORTAR_ITEM.get());
                        output.accept(ModItems.MOONLIGHT_HERB_SEEDS.get());
                        output.accept(ModItems.PUNGENT_HERB_SEEDS.get());
                        output.accept(ModItems.BITTER_ROOT_SEEDS.get());
                        output.accept(ModItems.WARMBLOOM_SEEDS.get());
                        output.accept(ModItems.MOONLIGHT_HERB.get());
                        output.accept(ModItems.PUNGENT_HERB.get());
                        output.accept(ModItems.BITTER_ROOT.get());
                        output.accept(ModItems.WARMBLOOM.get());
                        output.accept(ModItems.MOONLIGHT_POWDER.get());
                        output.accept(ModItems.PUNGENT_POWDER.get());
                        output.accept(ModItems.BITTER_ROOT_POWDER.get());
                        output.accept(ModItems.WARMBLOOM_POWDER.get());
                        output.accept(ModItems.GROUND_HERB.get());
                        output.accept(ModItems.CHARCOAL_POWDER.get());
                        output.accept(ModItems.SAP_CRYSTAL_POWDER.get());
                        output.accept(ModItems.REPELLENT_OIL.get());
                        output.accept(ModItems.SPOREWARD_POWDER.get());
                        output.accept(ModItems.SPORE_STOPPING_ELIXIR.get());
                        output.accept(ModItems.SMELLING_SALTS.get());
                        output.accept(ModItems.STASIS_PILL.get());
                        output.accept(ModItems.WARMBLOOM_PILL.get());
                        output.accept(ModItems.FIBER_GRASS_SEEDS.get());
                        output.accept(ModItems.BURST_FRUIT.get());
                        output.accept(ModItems.STICKY_FRUIT.get());
                        output.accept(ModItems.FLOAT_SAC.get());
                        output.accept(ModItems.WAYMARK_FLOWER.get());
                        output.accept(ModItems.SLINGSHOT.get());
                        output.accept(ModItems.PEBBLE.get());
                        output.accept(ModItems.BURST_SHOT.get());
                        output.accept(ModItems.STICKY_SHOT.get());
                        output.accept(ModItems.STICKY_NET.get());
                        output.accept(ModItems.GLIDER.get());
                        output.accept(ModItems.BANDAGE.get());
                        output.accept(ModItems.LEY_FLOWER.get());
                        output.accept(ModItems.LEY_CRYSTAL_SHARD.get());
                        output.accept(ModItems.BUDDING_LEY_CRYSTAL.get());
                        output.accept(ModItems.SMALL_LEY_CRYSTAL_BUD.get());
                        output.accept(ModItems.MEDIUM_LEY_CRYSTAL_BUD.get());
                        output.accept(ModItems.LARGE_LEY_CRYSTAL_BUD.get());
                        output.accept(ModItems.LEY_CRYSTAL.get());
                        output.accept(ModItems.LEY_COMPASS.get());
                        output.accept(ModItems.LEY_DROP.get());
                        output.accept(ModItems.UNKNOWN_SAMPLE.get());
                        output.accept(ModItems.LEY_PUMP_ITEM.get());
                        output.accept(ModItems.LEY_FURNACE_ITEM.get());
                        output.accept(ModItems.LEY_LAMP.get());
                        output.accept(ModItems.LEY_UPDRAFT_ITEM.get());
                        output.accept(ModItems.LEY_METER.get());
                        output.accept(ModItems.WAYSTONE_ANCHOR_ITEM.get());
                        output.accept(ModItems.LEY_WAYSTONE.get());
                        output.accept(ModItems.SUNLIT_LANTERN.get());
                        output.accept(ModItems.LIGHT_CHARM.get());
                        output.accept(ModItems.LURE_LANTERN_ITEM.get());
                        output.accept(ModItems.CANVAS_BACKPACK.get());
                        output.accept(ModItems.ASH.get());
                        output.accept(ModItems.ASH_FERTILIZER.get());
                        output.accept(ModItems.SPORE_CLOAK.get());
                        output.accept(ModItems.ASH_SMOKE_BOMB.get());
                        output.accept(ModItems.INCENDIARY_FLASK.get());
                        output.accept(ModItems.BEDROLL_ITEM.get());
                        output.accept(ModItems.SPORE_TENT_ITEM.get());
                        output.accept(ModItems.WIND_VANE_ITEM.get());
                        output.accept(ModItems.BLIGHT_FERN_ROOTED_ITEM.get());
                        output.accept(ModItems.DEFLECTING_STAKE_ITEM.get());

                        // 防衛設備
                        output.accept(ModItems.REPELLENT_LAMP_ITEM.get());
                        output.accept(ModItems.BARBED_WIRE_ITEM.get());
                        output.accept(ModItems.ABATIS_ITEM.get());
                        output.accept(ModItems.BURST_MINE.get());
                        output.accept(ModItems.CLAPPER_ROPE.get());
                        output.accept(ModItems.CLAPPER_BOARD.get());
                        output.accept(ModItems.PHASE_RECORDER_ITEM.get());
                        output.accept(ModItems.SPORE_ALARM_ITEM.get());
                        output.accept(ModItems.TARGET_BLOCK.get());
                        output.accept(ModItems.GUN_TURRET_ITEM.get());
                        output.accept(ModItems.AUTOCANNON_ROUND.get());
                        output.accept(ModItems.SIEGE_CANNON_ITEM.get());
                        output.accept(ModItems.BEAM_SHIELD_ITEM.get());
                        output.accept(ModItems.BATTERY.get());
                        output.accept(ModItems.EMPTY_BATTERY.get());
                        output.accept(ModItems.SIEGE_SHELL.get());

                        // 加工素材
                        output.accept(ModItems.REPTILE_LEATHER.get());
                        output.accept(ModItems.COMPOSITE_FIBER.get());
                        output.accept(ModItems.SAP_CRYSTAL_SOFTENER.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_BLOCK_ITEM.get());
                        output.accept(ModItems.PHLOEMITE_ALLOY_BASE.get());
                        output.accept(ModItems.SUNLIGHT_ALLOY.get());

                        // 亜鉛・真鍮・媒介結晶(2026-10-15、汚染の前から作れるサバイバル用アイテムの素材)
                        output.accept(ModItems.ZINC_ORE_ITEM.get());
                        output.accept(ModItems.DEEPSLATE_ZINC_ORE_ITEM.get());
                        output.accept(ModItems.RAW_ZINC.get());
                        output.accept(ModItems.ZINC_INGOT.get());
                        output.accept(ModItems.ZINC_NUGGET.get());
                        output.accept(ModItems.RAW_ZINC_BLOCK_ITEM.get());
                        output.accept(ModItems.ZINC_BLOCK_ITEM.get());
                        output.accept(ModItems.BRASS_INGOT.get());
                        output.accept(ModItems.BRASS_NUGGET.get());
                        output.accept(ModItems.BRASS_BLOCK_ITEM.get());
                        output.accept(ModItems.MEDIUM_CRYSTAL.get());

                        // 防具一式(寄生植物装備は植物のタブ)
                        output.accept(ModItems.EXOSKELETON_HELMET.get());
                        output.accept(ModItems.EXOSKELETON_CHESTPLATE.get());
                        output.accept(ModItems.EXOSKELETON_LEGGINGS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_HELMET.get());
                        output.accept(ModItems.CHLOROPHYLLITE_CHESTPLATE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_LEGGINGS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_BOOTS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_HELMET.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_CHESTPLATE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_LEGGINGS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_BOOTS.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_HELMET.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_CHESTPLATE.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_LEGGINGS.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_BOOTS.get());
                        output.accept(ModItems.POWERED_ARMOR_HELMET.get());
                        output.accept(ModItems.POWERED_ARMOR_CHESTPLATE.get());
                        output.accept(ModItems.POWERED_ARMOR_LEGGINGS.get());
                        output.accept(ModItems.POWERED_ARMOR_BOOTS.get());
                        output.accept(ModItems.POWERED_ARMOR_BLUEPRINT.get());
                        output.accept(ModItems.CARTRIDGE.get());
                        output.accept(ModItems.FACTOR_IMPACT.get());
                        output.accept(ModItems.FACTOR_PIERCE.get());
                        output.accept(ModItems.FACTOR_BLAST.get());
                        output.accept(ModItems.FACTOR_TOXIN.get());
                        output.accept(ModItems.FACTOR_SPORE.get());
                        output.accept(ModItems.NIGHT_VISION_ACCESSORY.get());
                        output.accept(ModItems.ENEMY_SENSE_ACCESSORY.get());
                        output.accept(ModItems.SWIFT_ACCESSORY.get());
                        output.accept(ModItems.CUSHION_ACCESSORY.get());
                        output.accept(ModItems.MINING_EXTENSION.get());
                        output.accept(ModItems.ANCHOR_EXTENSION.get());

                        // ツール一式
                        output.accept(ModItems.CHLOROPHYLLITE_AXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_PICKAXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_SHOVEL.get());
                        output.accept(ModItems.CHLOROPHYLLITE_HOE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_SWORD.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_AXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_PICKAXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_SHOVEL.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_HOE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_SWORD.get());
                        // 武器(2026-10-09〜)
                        output.accept(ModItems.CHLOROPHYLLITE_ZWEIHANDER.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_ZWEIHANDER.get());
                        output.accept(ModItems.SUNLIGHT_ALLOY_ZWEIHANDER.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ESTOC.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_ESTOC.get());
                        output.accept(ModItems.SUNLIGHT_ALLOY_ESTOC.get());
                        // 基地のブロック・同盟軍の持ち物とスポーンエッグは各クラスの BuildCreativeModeTabContentsEvent で足す
                    })
                    .build());

    /**
     * 旧タブ(2026-10-05 まで 1 つだけだったタブ)の別名。並行セッションが {@code MAIN} を見るコードを足していても
     * コンパイルが通るように残す。中身は人類のタブ。<b>新しく足すときは 3 つのどれかを選ぶこと。</b>
     */
    @Deprecated
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = HUMANITY;

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
