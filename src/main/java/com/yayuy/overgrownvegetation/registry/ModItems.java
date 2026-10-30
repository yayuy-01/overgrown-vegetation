package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.item.GeoCorpseBlockItem;
import com.yayuy.overgrownvegetation.item.LianaItem;
import com.yayuy.overgrownvegetation.item.LithopleuraHuskItem;
import com.yayuy.overgrownvegetation.item.SporeMeterItem;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;
import java.util.function.Function;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(OvergrownVegetation.MODID);

    /**
     * 種族専用の GeckoLib 立体モデルを持つ死体ブロック。BlockItem も専用の {@link GeoCorpseBlockItem}
     * にして、手持ち/インベントリでもブロックと同じ立体モデルを描画する(平らなアイコンにしない)。
     */
    private static final Set<DeferredBlock<Block>> GEO_CORPSE_BLOCKS = Set.of(
            ModBlocks.OVERGROWN_COW_CORPSE,
            ModBlocks.OVERGROWN_SHEEP_CORPSE,
            ModBlocks.OVERGROWN_PIG_CORPSE,
            ModBlocks.OVERGROWN_CREEPER_CORPSE,
            ModBlocks.OVERGROWN_SPIDER_CORPSE,
            ModBlocks.OVERGROWN_VILLAGER_CORPSE,
            ModBlocks.OVERGROWN_ZOMBIE_CORPSE,
            ModBlocks.OVERGROWN_BABY_ZOMBIE_CORPSE,
            ModBlocks.OVERGROWN_SKELETON_CORPSE,
            ModBlocks.OVERGROWN_ENDERMAN_CORPSE,
            ModBlocks.OVERGROWN_CHICKEN_CORPSE,
            ModBlocks.OVERGROWN_PIGLIN_CORPSE,
            ModBlocks.OVERGROWN_PIGLIN_BRUTE_CORPSE,
            ModBlocks.OVERGROWN_ZOMBIFIED_PIGLIN_CORPSE,
            ModBlocks.OVERGROWN_WITHER_SKELETON_CORPSE,
            ModBlocks.OVERGROWN_HOGLIN_CORPSE,
            ModBlocks.OVERGROWN_ZOGLIN_CORPSE,
            ModBlocks.OVERGROWN_STRIDER_CORPSE,
            ModBlocks.OVERGROWN_IRON_GOLEM_CORPSE,
            ModBlocks.OVERGROWN_WARDEN_CORPSE);

    static {
        // 全ての汚染ブロックに対応する BlockItem を自動登録する。
        // 立体モデルの死体だけは GeoCorpseBlockItem(3D アイコン)で、それ以外は通常の BlockItem。
        ModBlocks.displayOrder().forEach(block -> {
            if (block == ModBlocks.LITHOPLEURA_HUSK) {
                // 抜け殻も立体アイコンにする(死体と同じ理由)。
                ITEMS.registerItem(block.getId().getPath(),
                        props -> new LithopleuraHuskItem(block.get(), props), new Item.Properties());
            } else if (block == ModBlocks.OVERGROWN_LIANA) {
                // 蔦は1マス置いても見えないので、始点→終点の2回クリックで張る専用アイテム。
                ITEMS.registerItem(block.getId().getPath(),
                        props -> new LianaItem(block.get(), props), new Item.Properties());
            } else if (GEO_CORPSE_BLOCKS.contains(block)) {
                ITEMS.registerItem(block.getId().getPath(),
                        props -> new GeoCorpseBlockItem(block.get(), props), new Item.Properties());
            } else {
                ITEMS.registerSimpleBlockItem(block);
            }
        });
    }

    /** 「研究台」ブロックの BlockItem(汚染ブロックではないので個別登録)。 */
    public static final DeferredItem<BlockItem> RESEARCH_TABLE_ITEM = ITEMS.registerItem(
            "research_table",
            props -> new BlockItem(ModBlocks.RESEARCH_TABLE.get(), props),
            new Item.Properties());

    /** 「パワードアーマー作成テーブル」の BlockItem(研究台と同じく個別登録)。 */
    public static final DeferredItem<BlockItem> POWERED_ARMOR_TABLE_ITEM = ITEMS.registerItem(
            "powered_armor_table",
            props -> new BlockItem(ModBlocks.POWERED_ARMOR_TABLE.get(), props),
            new Item.Properties());

    /** 「因子の抽出設備」の BlockItem(順 1 の着手順 7)。 */
    public static final DeferredItem<BlockItem> FACTOR_EXTRACTOR_ITEM = ITEMS.registerItem(
            "factor_extractor",
            props -> new BlockItem(ModBlocks.FACTOR_EXTRACTOR.get(), props),
            new Item.Properties());

    /** 「ハイヴノード」のスポーンエッグ(テスト用。通常は巨大樹の生成で 1 体だけ湧く)。 */
    public static final DeferredItem<DeferredSpawnEggItem> HIVE_NODE_SPAWN_EGG = ITEMS.registerItem(
            "hive_node_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.HIVE_NODE, 0x2B1D14, 0x4CE05A, props),
            new Item.Properties());

    /** blight_fern のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> BLIGHT_FERN_SPAWN_EGG = ITEMS.registerItem(
            "blight_fern_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.BLIGHT_FERN, 0x3F5A28, 0x7B2D8C, props),
            new Item.Properties());

    /** 「過繁茂したスカトゥリウム」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> OVERGROWN_SHIELDER_SPAWN_EGG = ITEMS.registerItem(
            "overgrown_shielder_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.OVERGROWN_SHIELDER, 0x4F6E36, 0x7B2D8C, props),
            new Item.Properties());

    /** 「ファルキムネス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> FALCIMNES_SPAWN_EGG = ITEMS.registerItem(
            "falcimnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.FALCIMNES, 0x2F3A14, 0x9C8A2E, props),
            new Item.Properties());

    /** 「スクティムネス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> SCUTIMNES_SPAWN_EGG = ITEMS.registerItem(
            "scutimnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.SCUTIMNES, 0x3B4A22, 0x7A6A3E, props),
            new Item.Properties());

    /** 「アクムネス」のスポーンエッグ(地の色は胴の苔、斑は目の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> ACUMNES_SPAWN_EGG = ITEMS.registerItem(
            "acumnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ACUMNES, 0x3A4620, 0xBAFF00, props),
            new Item.Properties());

    // G5 の低キューブ 4 種(2026-10-01)。色はテクスチャから拾った(地 = 不透明画素の明るさの中ほど半分の平均、
    // 斑 = 発光マスクの平均。光の無いフォディムネスは明るい方 5% の平均 = 歯の色)。

    /** 「バティムネス」のスポーンエッグ(地は殻の苔、斑は目の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> BATHYMNES_SPAWN_EGG = ITEMS.registerItem(
            "bathymnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.BATHYMNES, 0x41441B, 0x8AD505, props),
            new Item.Properties());

    /** 「エレボムネス」のスポーンエッグ(地は胴の苔、斑は目のレンズの黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> EREBOMNES_SPAWN_EGG = ITEMS.registerItem(
            "erebomnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.EREBOMNES, 0x3D421A, 0xB1F400, props),
            new Item.Properties());

    /** 「ヴォラムネス」のスポーンエッグ(地は胴の苔、斑は光の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> VORAMNES_SPAWN_EGG = ITEMS.registerItem(
            "voramnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.VORAMNES, 0x3D4119, 0xA4EA02, props),
            new Item.Properties());

    /** 「フォディムネス」のスポーンエッグ(地は頭の樹皮と苔、斑は歯の淡い色)。 */
    public static final DeferredItem<DeferredSpawnEggItem> FODIMNES_SPAWN_EGG = ITEMS.registerItem(
            "fodimnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.FODIMNES, 0x41441B, 0x787553, props),
            new Item.Properties());

    /** 「ファランギオターブス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> PHALANGIOTARBUS_SPAWN_EGG = ITEMS.registerItem(
            "phalangiotarbus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PHALANGIOTARBUS, 0x5B4A2F, 0x8FA05A, props),
            new Item.Properties());

    /** 「プロトファスマ」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> PROTOPHASMA_SPAWN_EGG = ITEMS.registerItem(
            "protophasma_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PROTOPHASMA, 0x6E7C3A, 0x3B4A22, props),
            new Item.Properties());

    /** 「アースロプレウラ」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> ARTHROPLEURA_SPAWN_EGG = ITEMS.registerItem(
            "arthropleura_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ARTHROPLEURA, 0x5A4327, 0x2E5A3A, props),
            new Item.Properties());

    // 殻は苔むした岩の色、斑は形成層。
    public static final DeferredItem<DeferredSpawnEggItem> LITHOPLEURA_SPAWN_EGG = ITEMS.registerItem(
            "lithopleura_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.LITHOPLEURA, 0x36402A, 0x4F5220, props),
            new Item.Properties());

    // 体は過繫茂の緑、斑は翼膜の葉の色。
    public static final DeferredItem<DeferredSpawnEggItem> COELUROSAURAVUS_SPAWN_EGG = ITEMS.registerItem(
            "coelurosauravus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.COELUROSAURAVUS, 0x3C4A23, 0x132903, props),
            new Item.Properties());

    // 殻は暗い緑褐色、斑は発光菌糸の色(食べたものの色で光る)。
    public static final DeferredItem<DeferredSpawnEggItem> SPELAEOPLEURA_SPAWN_EGG = ITEMS.registerItem(
            "spelaeopleura_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.SPELAEOPLEURA, 0x2D351E, 0xACF9E0, props),
            new Item.Properties());

    /** 「ステノデクティア」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> STENODICTYA_LOBATA_SPAWN_EGG = ITEMS.registerItem(
            "stenodictya_lobata_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.STENODICTYA_LOBATA, 0x7FA8A0, 0xC9E0B4, props),
            new Item.Properties());

    /** 「メガネウラ」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> MEGANEURA_SPAWN_EGG = ITEMS.registerItem(
            "meganeura_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.MEGANEURA, 0x3C6E55, 0xB4E04A, props),
            new Item.Properties());

    /** 「アルボラ」(グループ4・拡大種)のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> ARBORA_SPAWN_EGG = ITEMS.registerItem(
            "arbora_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ARBORA, 0x4A5A2C, 0x8FA34C, props),
            new Item.Properties());

    /** 「フィロセンス」(グループ4・索敵役)のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> PHYLLOSENS_SPAWN_EGG = ITEMS.registerItem(
            "phyllosens_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PHYLLOSENS, 0x31502A, 0xC7E06A, props),
            new Item.Properties());

    /** 「ヴェロフィル」(グループ4・爆撃役)のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> VELOPHYLL_SPAWN_EGG = ITEMS.registerItem(
            "velophyll_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.VELOPHYLL, 0x3E4B26, 0x92A857, props),
            new Item.Properties());

    /** 「ペディキュラ」(グループ4・漂流種)のスポーンエッグ。色はテクスチャのまだらと、もやの黄緑。 */
    public static final DeferredItem<DeferredSpawnEggItem> PEDICULA_SPAWN_EGG = ITEMS.registerItem(
            "pedicula_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PEDICULA, 0x2B3216, 0xBDFF00, props),
            new Item.Properties());

    /** 「スポロフィル」(グループ4・支援役)のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> SPOROPHYLL_SPAWN_EGG = ITEMS.registerItem(
            "sporophyll_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.SPOROPHYLL, 0x4C5B2E, 0xD8C24A, props),
            new Item.Properties());

    /** 「ペトロラコサウルス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> PETROLACOSAURUS_SPAWN_EGG = ITEMS.registerItem(
            "petrolacosaurus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PETROLACOSAURUS, 0x7A5A3A, 0x9C4A3C, props),
            new Item.Properties());

    /** 「イアンタサウルス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> IANTHASAURUS_SPAWN_EGG = ITEMS.registerItem(
            "ianthasaurus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.IANTHASAURUS, 0x8A7B4A, 0xC9A24A, props),
            new Item.Properties());

    /** 「ヒロノムス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> HYLONOMUS_SPAWN_EGG = ITEMS.registerItem(
            "hylonomus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.HYLONOMUS, 0x5E7245, 0x3A4A2C, props),
            new Item.Properties());

    /** 「リゾドゥス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> RHIZODUS_SPAWN_EGG = ITEMS.registerItem(
            "rhizodus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.RHIZODUS, 0x4A4033, 0x6E5C46, props),
            new Item.Properties());

    /** 「クセナカンサス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> XENACANTHUS_SPAWN_EGG = ITEMS.registerItem(
            "xenacanthus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.XENACANTHUS, 0x9A8A6A, 0x5E7245, props),
            new Item.Properties());

    /** 「オルタカンタス」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> ORTHACANTHUS_SPAWN_EGG = ITEMS.registerItem(
            "orthacanthus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ORTHACANTHUS, 0x8A7B58, 0x3C5A4A, props),
            new Item.Properties());

    /** 「メガラクネ」のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> MEGARACHNE_SPAWN_EGG = ITEMS.registerItem(
            "megarachne_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.MEGARACHNE, 0xB5A487, 0x6E5C46, props),
            new Item.Properties());

    // G2・G3 の新しい 8 種のスポーンエッグ(2026-10-22。色は仮)。
    public static final DeferredItem<DeferredSpawnEggItem> DIPLOCAULUS_SPAWN_EGG = ITEMS.registerItem(
            "diplocaulus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.DIPLOCAULUS, 0x6E6A4C, 0xC8B98A, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> LUNGFISH_SPAWN_EGG = ITEMS.registerItem(
            "lungfish_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.LUNGFISH, 0x5A5238, 0x8C7A52, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> HIBBERTOPTERUS_SPAWN_EGG = ITEMS.registerItem(
            "hibbertopterus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.HIBBERTOPTERUS, 0x6B5A3A, 0x3C4A2E, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> GIANT_SNAIL_SPAWN_EGG = ITEMS.registerItem(
            "giant_snail_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.GIANT_SNAIL, 0x7A6440, 0xC8B07A, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> STENODICTYA_NYMPH_SPAWN_EGG = ITEMS.registerItem(
            "stenodictya_nymph_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.STENODICTYA_NYMPH, 0x5E7245, 0xA89A6A, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> ERYOPS_SPAWN_EGG = ITEMS.registerItem(
            "eryops_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ERYOPS, 0x4E5A36, 0x9A8A6A, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PULMONOSCORPIUS_SPAWN_EGG = ITEMS.registerItem(
            "pulmonoscorpius_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PULMONOSCORPIUS, 0x3E3A2C, 0x8A6E46, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> CRASSIGYRINUS_SPAWN_EGG = ITEMS.registerItem(
            "crassigyrinus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.CRASSIGYRINUS, 0x4A5438, 0x7A8A5E, props),
            new Item.Properties());

    /** レイドの「過繫茂の苗床」のスポーンエッグ(動作確認用。通常のプレイでは raid コマンド/自然発生を使う)。 */
    public static final DeferredItem<DeferredSpawnEggItem> OVERGROWN_NEXUS_SPAWN_EGG = ITEMS.registerItem(
            "overgrown_nexus_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.OVERGROWN_NEXUS, 0x2A3320, 0xC85A8E, props),
            new Item.Properties());

    // 「寄生された(MOB名)」のスポーンエッグ。主色はバニラ Mob のイメージ色、副色は感染を表す紫で統一。
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_COW_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_cow_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_COW, 0x4A3123, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_SHEEP_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_sheep_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_SHEEP, 0xE6E6E6, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_PIG_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_pig_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_PIG, 0xF0A5A5, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_ZOMBIE_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_zombie_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_ZOMBIE, 0x3B6642, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_VILLAGER_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_villager_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_VILLAGER, 0x56603A, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_CHICKEN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_chicken_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_CHICKEN, 0x9A9A86, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_SKELETON_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_skeleton_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_SKELETON, 0xA9A99A, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_ENDERMAN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_enderman_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_ENDERMAN, 0x1E2A1C, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_BABY_ZOMBIE_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_baby_zombie_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_BABY_ZOMBIE, 0x3B6642, 0x7B2D8C, props),
            new Item.Properties());
    // ネザーの媒介種(2026-10-09)
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_PIGLIN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_piglin_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_PIGLIN, 0x8E7262, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_PIGLIN_BRUTE_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_piglin_brute_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_PIGLIN_BRUTE, 0x4A2A18, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_ZOMBIFIED_PIGLIN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_zombified_piglin_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_ZOMBIFIED_PIGLIN, 0x9E7070, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_WITHER_SKELETON_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_wither_skeleton_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_WITHER_SKELETON, 0x1E211C, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_HOGLIN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_hoglin_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_HOGLIN, 0x8E5444, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_ZOGLIN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_zoglin_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_ZOGLIN, 0x9E7A70, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_STRIDER_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_strider_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_STRIDER, 0x6A4A5A, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_GHAST_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_ghast_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_GHAST, 0xB8B8A8, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_BLAZE_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_blaze_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_BLAZE, 0x8E7A3A, 0x7B2D8C, props),
            new Item.Properties());
    // 2026-10-10: アイアンゴーレム・ウォーデン(自然には湧かない。卵はクリエイティブと確認用)
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_IRON_GOLEM_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_iron_golem_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_IRON_GOLEM, 0x8A8A7E, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_WARDEN_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_warden_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_WARDEN, 0x0F2A2E, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_CREEPER_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_creeper_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_CREEPER, 0x3D8033, 0x7B2D8C, props),
            new Item.Properties());
    public static final DeferredItem<DeferredSpawnEggItem> PARASITIZED_SPIDER_SPAWN_EGG = ITEMS.registerItem(
            "parasitized_spider_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.PARASITIZED_SPIDER, 0x342D27, 0x7B2D8C, props),
            new Item.Properties());

    /**
     * 「巨大樹の苗木」。植えると必ず巨大樹に育つ、クリエイティブタブ限定の特別な苗木
     * ({@link ModBlocks#GIANT_OVERGROWN_SAPLING})。{@code ENCHANTMENT_GLINT_OVERRIDE} で
     * エンチャント済みアイテムのように常時発光させ、通常の苗木と一目で区別できるようにしている。
     * {@code displayOrder()} の自動 BlockItem 登録には乗せず、ここで個別に登録する。
     */
    public static final DeferredItem<BlockItem> GIANT_OVERGROWN_SAPLING_ITEM = ITEMS.registerItem(
            "giant_overgrown_sapling",
            props -> new BlockItem(ModBlocks.GIANT_OVERGROWN_SAPLING.get(), props),
            new Item.Properties().component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));

    /**
     * クリエイティブ専用のデバッグアイテム「胞子感染注射器 I/II/III」
     * ({@link com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem})— 右クリックで自分に
     * 指定段階の「胞子感染」を強制付与する。旧「胞子感染の棒」の置き換え。
     */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem> SPORE_INFECTION_SYRINGE_1 =
            syringe("spore_infection_syringe_1", 0);
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem> SPORE_INFECTION_SYRINGE_2 =
            syringe("spore_infection_syringe_2", 1);
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem> SPORE_INFECTION_SYRINGE_3 =
            syringe("spore_infection_syringe_3", 2);

    private static DeferredItem<com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem> syringe(String id, int amplifier) {
        return ITEMS.registerItem(id,
                props -> new com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem(amplifier, props),
                new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC));
    }

    /**
     * 「群れ検査器」— <b>クリエイティブ専用</b>のデバッグ道具
     * ({@link com.yayuy.overgrownvegetation.item.PackScannerItem})。右クリックで近くの群れ全員を光らせ、
     * 編成の内訳をチャットに出す。Shift + 右クリックで光を消す。
     */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.PackScannerItem> PACK_SCANNER =
            ITEMS.registerItem("pack_scanner",
                    com.yayuy.overgrownvegetation.item.PackScannerItem::new,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC));

    /** 「胞子濃度計」— 右クリックで今いるチャンクの胞子濃度を表示する計測アイテム。 */
    public static final DeferredItem<SporeMeterItem> SPORE_METER = ITEMS.registerItem(
            "spore_meter",
            SporeMeterItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * 「フェーズ計測器」({@link com.yayuy.overgrownvegetation.item.PhaseMeterItem})— 右クリックで
     * 現在のフェーズ・次のフェーズまでのポイントと過繫茂系チャンク数・今の収入と残り時間の目安を表示する。
     * Shift + 右クリックでポイントの獲得内訳も出す。
     */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.PhaseMeterItem> PHASE_METER = ITEMS.registerItem(
            "phase_meter",
            com.yayuy.overgrownvegetation.item.PhaseMeterItem::new,
            new Item.Properties().stacksTo(1));

    /** ガイドブック(2026-10-15、ユーザー指定)。右クリックで開く羊皮紙の本。今は Mob の章だけ。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.GuidebookItem> GUIDEBOOK = ITEMS.registerItem(
            "guidebook",
            com.yayuy.overgrownvegetation.item.GuidebookItem::new,
            new Item.Properties().stacksTo(1));

    /**
     * 「胞子」— 胞子除去装置({@link ModBlocks#SPORE_PURIFIER})が周囲のもともとの胞子濃度に応じて産出し、
     * 胞子拡散装置({@link ModBlocks#SPORE_DIFFUSER})がレッドストーンダストと一緒に燃料として消費する。
     */
    public static final DeferredItem<Item> SPORE = ITEMS.registerSimpleItem("spore", new Item.Properties());

    /**
     * <b>胞子フィルター</b> — 胞子除去装置({@link ModBlocks#SPORE_PURIFIER})の消耗品
     * (ユーザー指定 2026-09-21)。挿さっていないと除去装置は動かない。
     *
     * <p>減り方は<b>働いた量に比例する</b> —— 産出サイクル 1 回につき
     * {@code filterWearPerCycle}、さらに取れた胞子 1 個につき {@code filterWearPerSpore}。
     * きれいな空気なら何時間も保つが、濃い場所に置けば 30 分ほどで目詰まりする。
     * 「置けば永久に効く設備」から「濃いところほど維持費が高い設備」へ変える狙い。
     *
     * <p>耐久はバニラの仕組みをそのまま使う({@link #FILTER_DURABILITY}) ——
     * インベントリでも GUI でも残りが<b>バーで見える</b>ので、説明が要らない。
     */
    public static final int FILTER_DURABILITY = 256;

    public static final DeferredItem<Item> SPORE_FILTER = ITEMS.registerSimpleItem("spore_filter",
            new Item.Properties().durability(FILTER_DURABILITY));

    /**
     * 「防護マスク」({@link com.yayuy.overgrownvegetation.item.ProtectiveMaskItem})— 被っている間は
     * 「胞子感染」が胞子感染1 までしかかからなくなる頭装備。
     */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.ProtectiveMaskItem> PROTECTIVE_MASK = ITEMS.registerItem(
            "protective_mask",
            props -> new com.yayuy.overgrownvegetation.item.ProtectiveMaskItem(ModArmorMaterials.PROTECTIVE_MASK,
                    props.durability(ModArmorMaterials.durability("protective_mask", ArmorItem.Type.HELMET))),
            new Item.Properties());

    /** 「胞子除去装置」の BlockItem(汚染ブロックではないので個別登録)。 */
    public static final DeferredItem<BlockItem> SPORE_PURIFIER_ITEM = ITEMS.registerItem(
            "spore_purifier",
            props -> new BlockItem(ModBlocks.SPORE_PURIFIER.get(), props),
            new Item.Properties());

    /** 「胞子拡散装置」の BlockItem。 */
    public static final DeferredItem<BlockItem> SPORE_DIFFUSER_ITEM = ITEMS.registerItem(
            "spore_diffuser",
            props -> new BlockItem(ModBlocks.SPORE_DIFFUSER.get(), props),
            new Item.Properties());

    /** 忌避灯(2026-09-19)。 */
    public static final DeferredItem<BlockItem> REPELLENT_LAMP_ITEM = ITEMS.registerItem(
            "repellent_lamp", props -> new com.yayuy.overgrownvegetation.item.RepellentLampItem(ModBlocks.REPELLENT_LAMP.get(), props), new Item.Properties());
    /** 有刺鉄線(2026-09-19)。 */
    public static final DeferredItem<BlockItem> BARBED_WIRE_ITEM = ITEMS.registerItem(
            "barbed_wire", props -> new BlockItem(ModBlocks.BARBED_WIRE.get(), props), new Item.Properties());
    /** 逆茂木(2026-10-16)。 */
    public static final DeferredItem<BlockItem> ABATIS_ITEM = ITEMS.registerItem(
            "abatis", props -> new BlockItem(ModBlocks.ABATIS.get(), props), new Item.Properties());
    /** フェーズ計測機(2026-09-19)。 */
    public static final DeferredItem<BlockItem> PHASE_RECORDER_ITEM = ITEMS.registerItem(
            "phase_recorder", props -> new BlockItem(ModBlocks.PHASE_RECORDER.get(), props), new Item.Properties());
    /** 胞子警報器(2026-09-19)。 */
    public static final DeferredItem<BlockItem> SPORE_ALARM_ITEM = ITEMS.registerItem(
            "spore_alarm", props -> new BlockItem(ModBlocks.SPORE_ALARM.get(), props), new Item.Properties());

    /** ターゲットブロック(TD イベント、2026-09-25)。研究で封印しない(最初から作れる。仮決め 2026-09-25)。 */
    public static final DeferredItem<BlockItem> TARGET_BLOCK = ITEMS.registerItem(
            "target_block", props -> new BlockItem(ModBlocks.TARGET_BLOCK.get(), props), new Item.Properties());

    /** 機関砲台(2026-09-21)。研究「対空火器」で開放。 */
    public static final DeferredItem<BlockItem> GUN_TURRET_ITEM = ITEMS.registerItem(
            "gun_turret", props -> new BlockItem(ModBlocks.GUN_TURRET.get(), props), new Item.Properties());

    /**
     * 機関砲弾(2026-09-21)。機関砲台({@link ModBlocks#GUN_TURRET})の弾で、<b>1 個 = 1 発</b>
     * (ユーザー指定)。弾倉は 9 スタックなので、満載 576 発 = 毎分 240 発で 2 分半ぶん。
     * 研究「対空火器」で開放。
     */
    public static final DeferredItem<Item> AUTOCANNON_ROUND = ITEMS.registerSimpleItem("autocannon_round",
            new Item.Properties());

    /** 「除染機」({@link ModBlocks#DECONTAMINATOR})の BlockItem。 */
    public static final DeferredItem<BlockItem> DECONTAMINATOR_ITEM = ITEMS.registerItem(
            "decontaminator",
            props -> new BlockItem(ModBlocks.DECONTAMINATOR.get(), props),
            new Item.Properties());


    /** 「砲台」({@link ModBlocks#SIEGE_CANNON})の BlockItem。研究「攻城砲術」で開放。 */
    public static final DeferredItem<BlockItem> SIEGE_CANNON_ITEM = ITEMS.registerItem(
            "siege_cannon",
            props -> new BlockItem(ModBlocks.SIEGE_CANNON.get(), props),
            new Item.Properties());

    /**
     * 「砲弾」— 砲台({@link ModBlocks#SIEGE_CANNON})の弾。1 発で攻城戦ボスに {@code cannonShellDamage}
     * (既定 60000、防御しきい値 10000 を引いて 50000)。研究「攻城砲術」で開放。
     */
    public static final DeferredItem<Item> SIEGE_SHELL = ITEMS.registerSimpleItem("siege_shell",
            new Item.Properties().stacksTo(16));

    /** 「遮蔽盾」（{@link ModBlocks#BEAM_SHIELD}）の BlockItem。研究「攻城砲術」で開放。 */
    public static final DeferredItem<BlockItem> BEAM_SHIELD_ITEM = ITEMS.registerItem(
            "beam_shield",
            props -> new BlockItem(ModBlocks.BEAM_SHIELD.get(), props),
            new Item.Properties());

    /** 「過繫茂したドア」の BlockItem(2 段ブロックなので {@link DoubleHighBlockItem})。置くと段階 0(きれい)。 */
    public static final DeferredItem<BlockItem> OVERGROWN_DOOR_ITEM = ITEMS.registerItem(
            "overgrown_door",
            props -> new DoubleHighBlockItem(ModBlocks.OVERGROWN_DOOR.get(), props),
            new Item.Properties());

    /**
     * 「過繫茂したチェスト」の BlockItem。アイテムモデルは {@code builtin/entity} で、
     * {@link com.yayuy.overgrownvegetation.client.OvergrownChestItemRenderer} がブロックと同じ立体モデルを描く。
     */
    public static final DeferredItem<BlockItem> OVERGROWN_CHEST_ITEM = ITEMS.registerItem(
            "overgrown_chest",
            props -> new BlockItem(ModBlocks.OVERGROWN_CHEST.get(), props),
            new Item.Properties());

    /** 「過繫茂した樽」の BlockItem。 */
    public static final DeferredItem<BlockItem> OVERGROWN_BARREL_ITEM = ITEMS.registerItem(
            "overgrown_barrel",
            props -> new BlockItem(ModBlocks.OVERGROWN_BARREL.get(), props),
            new Item.Properties());

    /**
     * 「熟した垂れ草の光る果実」。バニラのグロウベリーと同じく、
     * {@link ModBlocks#OVERGROWN_GLOW_VINES}(光るツタ)の設置アイテム兼食料。
     *
     * <p>食べ終わると「栄養満点！」(バフ)と「香ばしいにおい」(デバフ)が対で付く
     * ({@link com.yayuy.overgrownvegetation.effect.FruitEffects})。
     */
    public static final DeferredItem<ItemNameBlockItem> OVERGROWN_GLOWBERRY = ITEMS.registerItem(
            "overgrown_glowberry",
            props -> new ItemNameBlockItem(ModBlocks.OVERGROWN_GLOW_VINES.get(), props),
            new Item.Properties().food(Foods.GLOW_BERRIES));

    /**
     * 「蔦の実」。木と木をつなぐ蔦({@code overgrown_liana})の経路から鉛直方向に垂れる蔦のうち、
     * 時間経過で発光した(=熟した)ものを素手で右クリックすると1個収穫できる。収穫すると
     * その垂れ蔦は発光しない状態に戻り、しばらくするとまた発光する……を繰り返す
     * ({@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity} 参照)。
     * 食料としてはバニラのグロウベリー相当(空腹回復2・隠し満腹度0.4)。
     */
    public static final DeferredItem<Item> VINE_FRUIT = ITEMS.registerSimpleItem(
            "vine_fruit", new Item.Properties().food(Foods.GLOW_BERRIES));

    /**
     * 「未発達な種子」。{@link ModBlocks#UNDEVELOPED_BUSH 未発達の茂み}に実が付いているとき、
     * 右クリックで 1 個収穫できる({@link com.yayuy.overgrownvegetation.block.UndevelopedBushBlock})。
     *
     * <p>食料としてはバニラのグロウベリー相当だが、食べ終わると<b>再生・耐性</b>(バフ)と
     * <b>香ばしいにおい</b>(デバフ)が付く
     * ({@link com.yayuy.overgrownvegetation.effect.FruitEffects})。
     */
    public static final DeferredItem<Item> UNDEVELOPED_SEED = ITEMS.registerSimpleItem(
            "undeveloped_seed", new Item.Properties().food(Foods.GLOW_BERRIES));

    /**
     * 「大きな未発達な果実」。過繫茂した木・巨大樹の葉の下に低確率でぶら下がっている
     * {@link ModBlocks#LARGE_UNDEVELOPED_FRUIT} を壊すと手に入る。
     *
     * <p>{@link BlockItem} なので、ブロックの面に向けて右クリックすれば<b>設置</b>でき、
     * 何もない所へ右クリックすれば<b>食べられる</b>(バニラのグロウベリーと同じ二役)。
     *
     * <p><b>満腹でも食べられる</b>({@code alwaysEdible}) —— この実が配る「栄養満点！」で
     * 食料ゲージが常に満タンになるため、そうしないと 2 個目以降を口にできなくなってしまう。
     *
     * <p>食べたときの効き目({@link com.yayuy.overgrownvegetation.effect.FruitEffects})は
     * 再生・耐性の強力なバフと引き換えに、<b>胞子感染が一段進み</b>、しばらく経ってから
     * 激しい空腹に襲われる。数値はすべて {@code effects.json} の {@code largeUndevelopedFruit}。
     */
    public static final DeferredItem<BlockItem> LARGE_UNDEVELOPED_FRUIT_ITEM = ITEMS.registerItem(
            "large_undeveloped_fruit",
            props -> new BlockItem(ModBlocks.LARGE_UNDEVELOPED_FRUIT.get(), props),
            new Item.Properties().food(new FoodProperties.Builder()
                    .nutrition(6)
                    .saturationModifier(0.6F)
                    .alwaysEdible()
                    .build()));

    // ─────────────────────────────────────────────────────────────────────
    //  Mob ドロップ素材。過繫茂バイオームの生物を倒すと得られるクラフト素材群。
    //  どの Mob が何を落とすかは data/…/loot_table/entities/<mob>.json 側で定義する。
    // ─────────────────────────────────────────────────────────────────────

    /** 「繊維質」— 過繫茂した生物の体を貫く、丈夫な植物繊維の束。ほぼ全ての対象種が落とす共通素材。 */
    public static final DeferredItem<Item> PLANT_FIBER =
            ITEMS.registerSimpleItem("plant_fiber", new Item.Properties());

    /** 「硬化樹皮」— スカトゥリウムが全身にまとう、木質化して硬くなった外皮。 */
    public static final DeferredItem<Item> HARDENED_BARK =
            ITEMS.registerSimpleItem("hardened_bark", new Item.Properties());

    /** 「節足」— 木質化した節のある脚。地表性の大型節足種から得られる。 */
    public static final DeferredItem<Item> ARTHROPOD_LIMB =
            ITEMS.registerSimpleItem("arthropod_limb", new Item.Properties());

    /** 「覆われた外骨格」— 苔と菌糸に覆われて硬化した外骨格の板。 */
    public static final DeferredItem<Item> OVERGROWN_EXOSKELETON =
            ITEMS.registerSimpleItem("overgrown_exoskeleton", new Item.Properties());

    /**
     * 「スカトゥリウムの外骨格」— スカトゥリウムの背を覆う、分厚く反り返った装甲板。
     * <b>過繫茂したスカトゥリウムからしか手に入らない</b>(loot_table/entities/overgrown_shielder.json)。
     * 「スカトゥリウムの外骨格」防具一式の主材料 兼 修理材。
     */
    public static final DeferredItem<Item> SCATURIUM_EXOSKELETON =
            ITEMS.registerSimpleItem("scaturium_exoskeleton", new Item.Properties());

    /** 「ファランギオタープスの牙」— 獲物に齧りつくための鋭い牙。 */
    public static final DeferredItem<Item> PHALANGIOTARBUS_FANG =
            ITEMS.registerSimpleItem("phalangiotarbus_fang", new Item.Properties());

    /** 「メガネウラの複眼」— 玉虫色に輝く巨大な複眼。 */
    public static final DeferredItem<Item> MEGANEURA_COMPOUND_EYE =
            ITEMS.registerSimpleItem("meganeura_compound_eye", new Item.Properties());

    /** 「メガネウラの羽」— 葉脈状の翅脈が走る大きな翅。 */
    public static final DeferredItem<Item> MEGANEURA_WING =
            ITEMS.registerSimpleItem("meganeura_wing", new Item.Properties());

    /** 「プロトファスマの触覚」— 長く伸びた感覚器の触角。 */
    public static final DeferredItem<Item> PROTOPHASMA_ANTENNA =
            ITEMS.registerSimpleItem("protophasma_antenna", new Item.Properties());

    /** 「爬虫類の甲板」— 原始的な小型爬虫類/単弓類の鱗状の皮膚板。ペトロラコサウルス等が落とす。 */
    public static final DeferredItem<Item> REPTILE_SCUTE =
            ITEMS.registerSimpleItem("reptile_scute", new Item.Properties());

    /** 「帆膜」— イアンタサウルスが背に張る、体温調節用の薄い膜。 */
    public static final DeferredItem<Item> SAIL_MEMBRANE =
            ITEMS.registerSimpleItem("sail_membrane", new Item.Properties());

    /** 「魚鱗」— 過繫茂した水域の古代魚から剥がれる硬い鱗。 */
    public static final DeferredItem<Item> FISH_SCALE =
            ITEMS.registerSimpleItem("fish_scale", new Item.Properties());

    /** 「メガラクネの鋏」— ウミサソリの頑丈な鋏脚。 */
    public static final DeferredItem<Item> MEGARACHNE_PINCER =
            ITEMS.registerSimpleItem("megarachne_pincer", new Item.Properties());

    // ── グループ共通のドロップ(順 1 の着手順 11、2026-10-04)。そのグループを狩った証。名前・確率は仕様書 ORDER1_IMPL.md の 8 節 ──

    /** 「寄生菌糸」— G1(寄生された家畜・獣)共通。寄生されていることがこのグループの定義。毒の因子の元。 */
    public static final DeferredItem<Item> PARASITIC_MYCELIUM =
            ITEMS.registerSimpleItem("parasitic_mycelium", new Item.Properties());

    /** 「節足の殻」— G2 の節足動物 5 体の共通(魚の 2 体は落とさない。ユーザー指定)。 */
    public static final DeferredItem<Item> ARTHROPOD_SHELL =
            ITEMS.registerSimpleItem("arthropod_shell", new Item.Properties());

    /** 「捕食者の腱」— G3(狩る側)共通。衝撃の因子の元。 */
    public static final DeferredItem<Item> PREDATOR_TENDON =
            ITEMS.registerSimpleItem("predator_tendon", new Item.Properties());

    /** 「胞子腺の組織」— G4(汚染を広げる側)共通。胞子の因子の元。 */
    public static final DeferredItem<Item> SPORE_GLAND_TISSUE =
            ITEMS.registerSimpleItem("spore_gland_tissue", new Item.Properties());

    /** 「胞子嚢」— 寄生されたクリーパーが落とす。爆発の因子の元(火薬はそのまま落とす)。 */
    public static final DeferredItem<Item> SPORE_SAC =
            ITEMS.registerSimpleItem("spore_sac", new Item.Properties());

    /**
     * 「適応の記録」7 種 — G5 がそれぞれ自分の記録を落とす。パワードアーマーのスキルツリーの支払い
     * (どの本に使えるかはタグ {@code #overgrown_vegetation:adaptation_records/<本>})。
     */
    public static final DeferredItem<Item> ADAPTATION_RECORD_GRASP = adaptationRecord("grasp", "falcimnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_SHIELD = adaptationRecord("shield", "scutimnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_AIM = adaptationRecord("aim", "acumnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_WATER = adaptationRecord("water", "bathymnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_DARK = adaptationRecord("dark", "erebomnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_DEVOUR = adaptationRecord("devour", "voramnes");
    public static final DeferredItem<Item> ADAPTATION_RECORD_DIG = adaptationRecord("dig", "fodimnes");

    private static DeferredItem<Item> adaptationRecord(String kind, String sourceEntity) {
        return ITEMS.registerItem("adaptation_record_" + kind,
                props -> new com.yayuy.overgrownvegetation.item.AdaptationRecordItem(
                        "entity.overgrown_vegetation." + sourceEntity, props),
                new Item.Properties());
    }

    // ── G1 の汚染されたバニラ素材(同)。「食べられるが代償がある」—— 食べると胞子感染の確率(仕様書の 8 節)──

    /** 「汚染された肉」— 寄生された牛・豚・羊。生の牛肉と同じ満腹度、食べると 30% で胞子感染。 */
    public static final DeferredItem<Item> CONTAMINATED_MEAT = ITEMS.registerItem("contaminated_meat",
            props -> new com.yayuy.overgrownvegetation.item.ContaminatedFoodItem(0.3D, props),
            new Item.Properties().food(new FoodProperties.Builder().nutrition(3).saturationModifier(0.3F).build()));

    /** 「腐った胞子肉」— 寄生されたゾンビ。腐った肉と同じ(空腹 80%)、食べると 50% で胞子感染。 */
    public static final DeferredItem<Item> ROTTEN_SPORE_FLESH = ITEMS.registerItem("rotten_spore_flesh",
            props -> new com.yayuy.overgrownvegetation.item.ContaminatedFoodItem(0.5D, props),
            new Item.Properties().food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.1F)
                    .effect(() -> new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.HUNGER, 600, 0), 0.8F)
                    .build()));

    /** 「汚染された羊毛」— 寄生された羊が倒されると落とす(刈ったときはバニラの羊毛)。使い道は順 6.5。 */
    public static final DeferredItem<Item> CONTAMINATED_WOOL =
            ITEMS.registerSimpleItem("contaminated_wool", new Item.Properties());

    /** 「汚染された糸」— 寄生されたクモ。使い道は順 6.5。 */
    public static final DeferredItem<Item> CONTAMINATED_STRING =
            ITEMS.registerSimpleItem("contaminated_string", new Item.Properties());

    // ─────────────────────────────────────────────────────────────────────
    //  巨大樹の形成層に埋まる鉱石ノードの原石。{@link ModBlocks#CHLOROPHYLLITE_ORE} /
    //  {@link ModBlocks#HARDENED_SAP_CRYSTAL_ORE} を(シルクタッチ無しで)掘ると得られる。
    // ─────────────────────────────────────────────────────────────────────

    /** 「クロロフィライトの原石」— 巨大樹の形成層から採れる、葉緑を宿した鉱石の塊。 */
    public static final DeferredItem<Item> RAW_CHLOROPHYLLITE =
            ITEMS.registerSimpleItem("raw_chlorophyllite", new Item.Properties());

    /** 「硬化樹液結晶の原石」— 長い年月をかけて樹液が結晶化した、琥珀色の希少な塊。 */
    public static final DeferredItem<Item> RAW_HARDENED_SAP_CRYSTAL =
            ITEMS.registerSimpleItem("raw_hardened_sap_crystal", new Item.Properties());

    /**
     * 「クロロフィライト」— {@link #RAW_CHLOROPHYLLITE クロロフィライトの原石}を精錬して得た葉緑色の金属塊。
     * クロロフィライト装備・ツール階層の基礎素材(修理素材)。かまど/溶鉱炉で原石から精錬する。
     */
    public static final DeferredItem<Item> CHLOROPHYLLITE =
            ITEMS.registerSimpleItem("chlorophyllite", new Item.Properties());

    /**
     * 「樹液結晶」— {@link #RAW_HARDENED_SAP_CRYSTAL 硬化樹液結晶の原石}を精錬して不純物を飛ばした、
     * 澄んだ琥珀色の結晶。かまど/溶鉱炉で原石から精錬する。
     */
    public static final DeferredItem<Item> SAP_CRYSTAL =
            ITEMS.registerSimpleItem("sap_crystal", new Item.Properties());

    // ─────────────────────────────────────────────────────────────────────
    //  極相の地下の鉱石の原石と精錬品(順 1、2026-10-03 ユーザー指定)。
    //  精錬はかまどでも溶鉱炉でも。合金({@link #PHLOEMITE_ALLOY_BASE} → {@link #SUNLIGHT_ALLOY})の材料。
    // ─────────────────────────────────────────────────────────────────────

    /** 「フロエマイトの原石」— 地脈に沿った極相の地下で採れる、師管のような筋の走る鉱石の塊。 */
    public static final DeferredItem<Item> RAW_PHLOEMITE =
            ITEMS.registerSimpleItem("raw_phloemite", new Item.Properties());

    /** 「フロエマイト」— {@link #RAW_PHLOEMITE フロエマイトの原石}を精錬した金属。 */
    public static final DeferredItem<Item> PHLOEMITE =
            ITEMS.registerSimpleItem("phloemite", new Item.Properties());

    /** 「チラコイトの原石」— 結晶地帯の地下で採れる、光を溜め込んだ結晶の塊。 */
    public static final DeferredItem<Item> RAW_THYLAKITE =
            ITEMS.registerSimpleItem("raw_thylakite", new Item.Properties());

    /** 「チラコイト」— {@link #RAW_THYLAKITE チラコイトの原石}を精錬した結晶。 */
    public static final DeferredItem<Item> THYLAKITE =
            ITEMS.registerSimpleItem("thylakite", new Item.Properties());

    // ─────────────────────────────────────────────────────────────────────
    //  装備・ツールの加工素材。原石やドロップ素材を精製した中間素材。
    //  レシピ(何から作るか)は後々レシピ側で定義する。
    // ─────────────────────────────────────────────────────────────────────

    /** 「爬虫類の革」— 爬虫類の甲板をなめして得た丈夫な革。 */
    public static final DeferredItem<Item> REPTILE_LEATHER =
            ITEMS.registerSimpleItem("reptile_leather", new Item.Properties());

    /** 「クロロフィライト合金」— クロロフィライトを他素材と混ぜて鋳込んだ合金インゴット。最上位の装備・ツール素材。 */
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY =
            ITEMS.registerSimpleItem("chlorophyllite_alloy", new Item.Properties());

    /**
     * 「サンライト合金の元」(順 1、2026-10-03 ユーザー指定。10-09 にユーザー指定で「フロエマイト合金の元」から改名、id はそのまま)—
     * フロエマイトとネザライトの破片から作る、まだ粗い合金塊。{@link #SUNLIGHT_ALLOY サンライト合金}の材料。作業台で作る。
     */
    public static final DeferredItem<Item> PHLOEMITE_ALLOY_BASE =
            ITEMS.registerSimpleItem("phloemite_alloy_base", new Item.Properties().fireResistant());

    /**
     * 「サンライト合金」(順 1、2026-10-03 ユーザー指定)— サンライト合金の元にチラコイトと樹液結晶を
     * 混ぜた合金。パワードアーマー(装備の 5 段目)とサンライト合金のツヴァイヘンダーの素材。作業台で作る。
     */
    public static final DeferredItem<Item> SUNLIGHT_ALLOY =
            ITEMS.registerSimpleItem("sunlight_alloy", new Item.Properties().fireResistant());

    // ─────────────────────────────────────────────────────────────────────
    //  素材の保管ブロックの BlockItem。汚染ブロックではないので displayOrder() の
    //  自動登録には乗せず、ここで個別に登録して {@link ModCreativeTabs} で素材の隣に並べる。
    //  レシピは 9 個 ⇄ 1 ブロック(研究による封印なし)。
    // ─────────────────────────────────────────────────────────────────────

    /** 「クロロフィライトのブロック」({@link ModBlocks#CHLOROPHYLLITE_BLOCK})。 */
    public static final DeferredItem<BlockItem> CHLOROPHYLLITE_BLOCK_ITEM = ITEMS.registerItem(
            "chlorophyllite_block",
            props -> new BlockItem(ModBlocks.CHLOROPHYLLITE_BLOCK.get(), props),
            new Item.Properties());

    /** 「樹液結晶のブロック」({@link ModBlocks#SAP_CRYSTAL_BLOCK})。 */
    public static final DeferredItem<BlockItem> SAP_CRYSTAL_BLOCK_ITEM = ITEMS.registerItem(
            "sap_crystal_block",
            props -> new BlockItem(ModBlocks.SAP_CRYSTAL_BLOCK.get(), props),
            new Item.Properties());

    /** 「クロロフィライト合金のブロック」({@link ModBlocks#CHLOROPHYLLITE_ALLOY_BLOCK})。 */
    public static final DeferredItem<BlockItem> CHLOROPHYLLITE_ALLOY_BLOCK_ITEM = ITEMS.registerItem(
            "chlorophyllite_alloy_block",
            props -> new BlockItem(ModBlocks.CHLOROPHYLLITE_ALLOY_BLOCK.get(), props),
            new Item.Properties());

    /** 「複合繊維質」— 繊維質を編み固めて樹脂で固めた複合材。外骨格装備の補強に使う。 */
    public static final DeferredItem<Item> COMPOSITE_FIBER =
            ITEMS.registerSimpleItem("composite_fiber", new Item.Properties());

    /** 「樹液結晶軟化剤」— 硬化樹液結晶を溶かして作る、金属の靭性を高める添加剤。 */
    public static final DeferredItem<Item> SAP_CRYSTAL_SOFTENER =
            ITEMS.registerSimpleItem("sap_crystal_softener", new Item.Properties());

    // ─── 亜鉛・真鍮・媒介結晶(2026-10-15、サバイバル用アイテムの前提の素材)───
    // 亜鉛を使うレシピは c: タグ(c:ingots/zinc など)で書く —— Create の亜鉛でも作れるように(ユーザー指定)。

    /** 「亜鉛の原石」。 */
    public static final DeferredItem<Item> RAW_ZINC = ITEMS.registerSimpleItem("raw_zinc", new Item.Properties());
    /** 「亜鉛インゴット」。 */
    public static final DeferredItem<Item> ZINC_INGOT = ITEMS.registerSimpleItem("zinc_ingot", new Item.Properties());
    /** 「亜鉛の塊」。 */
    public static final DeferredItem<Item> ZINC_NUGGET = ITEMS.registerSimpleItem("zinc_nugget", new Item.Properties());
    /** 「真鍮インゴット」— 銅 + 亜鉛。人類側の灯具・計器・呼吸器の金具(デザイン方針: 真鍮と鋳鉄の機械)。 */
    public static final DeferredItem<Item> BRASS_INGOT = ITEMS.registerSimpleItem("brass_ingot", new Item.Properties());
    /** 「真鍮の塊」。 */
    public static final DeferredItem<Item> BRASS_NUGGET = ITEMS.registerSimpleItem("brass_nugget", new Item.Properties());
    /**
     * 「媒介結晶」— アメジストの欠片 + グロウストーンダスト(ユーザー指定)。地脈から漏れたエネルギーを人が扱うための器で、
     * 琥珀色に光る(人類の小さな魔法の色)。琥珀の角灯などの材料。
     */
    public static final DeferredItem<Item> MEDIUM_CRYSTAL = ITEMS.registerSimpleItem("medium_crystal", new Item.Properties());

    /** 「濾し布の呼吸覆い」(2026-10-15、サバイバル用アイテム ㉞)。追加防具・頭の枠に付ける。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.BreathingClothItem> BREATHING_CLOTH = ITEMS.registerItem(
            "breathing_cloth", com.yayuy.overgrownvegetation.item.BreathingClothItem::new,
            new Item.Properties().durability(com.yayuy.overgrownvegetation.item.BreathingClothItem.DURABILITY));

    /** 「布鎧(ギャンベゾン)」(2026-10-15、サバイバル用アイテム ㉟)。追加防具・体の枠に付ける。防御 2。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.GambesonItem> GAMBESON = ITEMS.registerItem(
            "gambeson", com.yayuy.overgrownvegetation.item.GambesonItem::new,
            new Item.Properties().durability(com.yayuy.overgrownvegetation.item.GambesonItem.DURABILITY));

    /** 「鳴子の紐」(2026-10-15、サバイバル用アイテム ㊵)。2 回クリックで柵・塀・柱の間に張る。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.defense.ClapperRopeItem> CLAPPER_ROPE = ITEMS.registerItem(
            "clapper_rope", com.yayuy.overgrownvegetation.defense.ClapperRopeItem::new, new Item.Properties());
    /** 「鳴子の板」(2026-10-15)。持って紐を右クリックすると、その場所に吊る。 */
    public static final DeferredItem<Item> CLAPPER_BOARD = ITEMS.registerSimpleItem("clapper_board", new Item.Properties());

    /** 「琥珀の角灯」(2026-10-15、サバイバル用アイテム ㊳)。腰の追加防具の枠に下げると動く光、置けば灯り。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.AmberLanternItem> AMBER_LANTERN = ITEMS.registerItem(
            "amber_lantern", props -> new com.yayuy.overgrownvegetation.item.AmberLanternItem(ModBlocks.AMBER_LANTERN.get(), props),
            new Item.Properties());

    /**
     * 「燻製肉の元」(2026-10-16、サバイバル用アイテム ㊷)。どの生肉 1 + 薬草(タグ {@code #overgrown_vegetation:herbs})1 → 1(ユーザー指定)。
     * 燻製器でだけ焼けて携行食になる。食べられない。
     */
    public static final DeferredItem<Item> TRAIL_RATION_BASE = ITEMS.registerItem("trail_ration_base", props -> new Item(props) {
        @Override
        public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip,
                                    net.minecraft.world.item.TooltipFlag flag) {
            tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.overgrown_vegetation.trail_ration_base")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }, new Item.Properties());

    /**
     * 「携行食」(2026-10-16、㊷)。満腹度 10・隠し満腹度 10・32 個まで・焼き昆布と同じ速さで食べられる(ユーザー指定)。
     * 隠し満腹度 = 満腹度 × 係数 × 2 なので係数 0.5。
     */
    public static final DeferredItem<Item> TRAIL_RATION = ITEMS.registerSimpleItem("trail_ration",
            new Item.Properties().stacksTo(32)
                    .food(new FoodProperties.Builder().nutrition(10).saturationModifier(0.5F).fast().build()));

    // --- 乳鉢・薬草・粉・薬(2026-10-16、サバイバル用アイテム。ユーザー指定) ---

    public static final DeferredItem<BlockItem> MORTAR_ITEM = ITEMS.registerItem(
            "mortar", props -> new BlockItem(ModBlocks.MORTAR.get(), props), new Item.Properties());
    /** 薬草 4 つ(タグ #overgrown_vegetation:herbs)。種を耕した畑に蒔く。 */
    public static final DeferredItem<Item> MOONLIGHT_HERB = ITEMS.registerSimpleItem("moonlight_herb", new Item.Properties());
    public static final DeferredItem<Item> PUNGENT_HERB = ITEMS.registerSimpleItem("pungent_herb", new Item.Properties());
    public static final DeferredItem<Item> BITTER_ROOT = ITEMS.registerSimpleItem("bitter_root", new Item.Properties());
    public static final DeferredItem<Item> WARMBLOOM = ITEMS.registerSimpleItem("warmbloom", new Item.Properties());
    public static final DeferredItem<ItemNameBlockItem> MOONLIGHT_HERB_SEEDS = ITEMS.registerItem("moonlight_herb_seeds",
            props -> new ItemNameBlockItem(ModBlocks.MOONLIGHT_HERB_CROP.get(), props), new Item.Properties());
    public static final DeferredItem<ItemNameBlockItem> PUNGENT_HERB_SEEDS = ITEMS.registerItem("pungent_herb_seeds",
            props -> new ItemNameBlockItem(ModBlocks.PUNGENT_HERB_CROP.get(), props), new Item.Properties());
    public static final DeferredItem<ItemNameBlockItem> BITTER_ROOT_SEEDS = ITEMS.registerItem("bitter_root_seeds",
            props -> new ItemNameBlockItem(ModBlocks.BITTER_ROOT_CROP.get(), props), new Item.Properties());
    public static final DeferredItem<ItemNameBlockItem> WARMBLOOM_SEEDS = ITEMS.registerItem("warmbloom_seeds",
            props -> new ItemNameBlockItem(ModBlocks.WARMBLOOM_CROP.get(), props), new Item.Properties());
    /** 乳鉢ですりつぶした物(4 つの薬草はそれぞれの粉。ほかの薬草タグの草花は「すりつぶした薬草」。ユーザー指定)。 */
    public static final DeferredItem<Item> MOONLIGHT_POWDER = ITEMS.registerSimpleItem("moonlight_powder", new Item.Properties());
    public static final DeferredItem<Item> PUNGENT_POWDER = ITEMS.registerSimpleItem("pungent_powder", new Item.Properties());
    public static final DeferredItem<Item> BITTER_ROOT_POWDER = ITEMS.registerSimpleItem("bitter_root_powder", new Item.Properties());
    public static final DeferredItem<Item> WARMBLOOM_POWDER = ITEMS.registerSimpleItem("warmbloom_powder", new Item.Properties());
    public static final DeferredItem<Item> GROUND_HERB = ITEMS.registerSimpleItem("ground_herb", new Item.Properties());
    public static final DeferredItem<Item> CHARCOAL_POWDER = ITEMS.registerSimpleItem("charcoal_powder", new Item.Properties());
    public static final DeferredItem<Item> SAP_CRYSTAL_POWDER = ITEMS.registerSimpleItem("sap_crystal_powder", new Item.Properties());
    /** 地脈技術の 3 段目の中間素材(2026-10-24。転用の改造レシピの「2 つ目の鍵」)。忌避の精油 = 乳鉢、胞子の濾過膜 = 作業台。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.DescribedItem> REPELLENT_OIL = ITEMS.registerItem("repellent_oil",
            com.yayuy.overgrownvegetation.item.DescribedItem::new, new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.DescribedItem> SPORE_MEMBRANE = ITEMS.registerItem("spore_membrane",
            com.yayuy.overgrownvegetation.item.DescribedItem::new, new Item.Properties());
    /** 薬 5 つ(丸薬・粉薬。食べて使う)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.MedicineItem> SPOREWARD_POWDER = ITEMS.registerItem("sporeward_powder",
            props -> new com.yayuy.overgrownvegetation.item.MedicineItem(com.yayuy.overgrownvegetation.item.MedicineItem.Kind.SPORE_WARD, props),
            new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.MedicineItem> SPORE_STOPPING_ELIXIR = ITEMS.registerItem("spore_stopping_elixir",
            props -> new com.yayuy.overgrownvegetation.item.MedicineItem(com.yayuy.overgrownvegetation.item.MedicineItem.Kind.ELIXIR, props),
            new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.MedicineItem> SMELLING_SALTS = ITEMS.registerItem("smelling_salts",
            props -> new com.yayuy.overgrownvegetation.item.MedicineItem(com.yayuy.overgrownvegetation.item.MedicineItem.Kind.SMELLING_SALTS, props),
            new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.MedicineItem> STASIS_PILL = ITEMS.registerItem("stasis_pill",
            props -> new com.yayuy.overgrownvegetation.item.MedicineItem(com.yayuy.overgrownvegetation.item.MedicineItem.Kind.STASIS_PILL, props),
            new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.MedicineItem> WARMBLOOM_PILL = ITEMS.registerItem("warmbloom_pill",
            props -> new com.yayuy.overgrownvegetation.item.MedicineItem(com.yayuy.overgrownvegetation.item.MedicineItem.Kind.WARMBLOOM_PILL, props),
            new Item.Properties());

    // --- 役に立つ植物と、それを使う道具(2026-10-16、サバイバル用アイテム。ユーザー指定) ---

    public static final DeferredItem<ItemNameBlockItem> FIBER_GRASS_SEEDS = ITEMS.registerItem("fiber_grass_seeds",
            props -> new ItemNameBlockItem(ModBlocks.FIBER_GRASS_CROP.get(), props), new Item.Properties());
    public static final DeferredItem<Item> BURST_FRUIT = ITEMS.registerSimpleItem("burst_fruit", new Item.Properties());
    public static final DeferredItem<Item> STICKY_FRUIT = ITEMS.registerSimpleItem("sticky_fruit", new Item.Properties());
    public static final DeferredItem<Item> FLOAT_SAC = ITEMS.registerSimpleItem("float_sac", new Item.Properties());
    public static final DeferredItem<BlockItem> WAYMARK_FLOWER = ITEMS.registerItem(
            "waymark_flower", props -> new BlockItem(ModBlocks.WAYMARK_FLOWER.get(), props), new Item.Properties());
    /** ⑯ パチンコと弾(小石・⑰ 炸裂弾・粘り弾)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SlingshotItem> SLINGSHOT = ITEMS.registerItem("slingshot",
            com.yayuy.overgrownvegetation.item.SlingshotItem::new, new Item.Properties().durability(256));
    public static final DeferredItem<Item> PEBBLE = ITEMS.registerSimpleItem("pebble", new Item.Properties());
    public static final DeferredItem<Item> BURST_SHOT = ITEMS.registerSimpleItem("burst_shot", new Item.Properties());
    public static final DeferredItem<Item> STICKY_SHOT = ITEMS.registerSimpleItem("sticky_shot", new Item.Properties());
    /** ⑱ 炸裂の実の地雷。 */
    public static final DeferredItem<BlockItem> BURST_MINE = ITEMS.registerItem(
            "burst_mine", props -> new BlockItem(ModBlocks.BURST_MINE.get(), props), new Item.Properties());
    /** ⑳ 粘りの投網。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.StickyNetItem> STICKY_NET = ITEMS.registerItem("sticky_net",
            com.yayuy.overgrownvegetation.item.StickyNetItem::new, new Item.Properties().stacksTo(16));
    /** 布のグライダー(両手持ち。2026-10-22、浮き袋の落下傘を消してこれに統合)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.GliderItem> GLIDER = ITEMS.registerItem(
            "glider", com.yayuy.overgrownvegetation.item.GliderItem::new,
            new Item.Properties().durability(com.yayuy.overgrownvegetation.item.GliderItem.DURABILITY));
    /** ㉒ 包帯。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.BandageItem> BANDAGE = ITEMS.registerItem("bandage",
            com.yayuy.overgrownvegetation.item.BandageItem::new, new Item.Properties());
    /** ㉔ 地脈電池と空の地脈電池(2026-10-22 に蓄電の実の電池から改名。蓄電の実は削除)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.BatteryItem> BATTERY = ITEMS.registerItem("battery",
            com.yayuy.overgrownvegetation.item.BatteryItem::new, new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.EmptyBatteryItem> EMPTY_BATTERY = ITEMS.registerItem("empty_battery",
            com.yayuy.overgrownvegetation.item.EmptyBatteryItem::new, new Item.Properties().stacksTo(16));
    /** ⑦ 地脈の羅針盤。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.LeyCompassItem> LEY_COMPASS = ITEMS.registerItem("ley_compass",
            com.yayuy.overgrownvegetation.item.LeyCompassItem::new, new Item.Properties());

    // --- 地脈の燃料で動く道具(2026-10-16、クラスター 3。ユーザー指定) ---

    /** 地脈の雫(魔法の道具の燃料。㉙ 地脈の汲み上げ器でたまる)。 */
    public static final DeferredItem<Item> LEY_DROP = ITEMS.registerSimpleItem("ley_drop", new Item.Properties());
    public static final DeferredItem<BlockItem> LEY_PUMP_ITEM = ITEMS.registerItem(
            "ley_pump", props -> new BlockItem(ModBlocks.LEY_PUMP.get(), props), new Item.Properties());
    /** 地脈技術の 2 段目: 生活用の機械(2026-10-22)。地脈灯は腰の枠にも付けられる。 */
    public static final DeferredItem<BlockItem> LEY_FURNACE_ITEM = ITEMS.registerItem(
            "ley_furnace", props -> new BlockItem(ModBlocks.LEY_FURNACE.get(), props), new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.LeyLampItem> LEY_LAMP = ITEMS.registerItem(
            "ley_lamp", props -> new com.yayuy.overgrownvegetation.item.LeyLampItem(ModBlocks.LEY_LAMP.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> LEY_UPDRAFT_ITEM = ITEMS.registerItem(
            "ley_updraft", props -> new BlockItem(ModBlocks.LEY_UPDRAFT.get(), props), new Item.Properties());
    /** 地脈計と未知の試料(2026-10-24 地脈技術の 4 段目)。試料は前兆の頃から汲み上げ器の雫に混じる。 */
    public static final DeferredItem<BlockItem> LEY_METER = ITEMS.registerItem(
            "ley_meter", props -> new BlockItem(ModBlocks.LEY_METER.get(), props), new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.DescribedItem> UNKNOWN_SAMPLE = ITEMS.registerItem("unknown_sample",
            com.yayuy.overgrownvegetation.item.DescribedItem::new, new Item.Properties());
    /** 地脈の花と結晶(2026-10-22、序盤の作り直し)。結晶の欠片は塊を壊すと落ちる研究ポイントの素材。 */
    public static final DeferredItem<BlockItem> LEY_FLOWER = ITEMS.registerItem(
            "ley_flower", props -> new BlockItem(ModBlocks.LEY_FLOWER.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> BUDDING_LEY_CRYSTAL = ITEMS.registerItem(
            "budding_ley_crystal", props -> new BlockItem(ModBlocks.BUDDING_LEY_CRYSTAL.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> SMALL_LEY_CRYSTAL_BUD = ITEMS.registerItem(
            "small_ley_crystal_bud", props -> new BlockItem(ModBlocks.SMALL_LEY_CRYSTAL_BUD.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> MEDIUM_LEY_CRYSTAL_BUD = ITEMS.registerItem(
            "medium_ley_crystal_bud", props -> new BlockItem(ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> LARGE_LEY_CRYSTAL_BUD = ITEMS.registerItem(
            "large_ley_crystal_bud", props -> new BlockItem(ModBlocks.LARGE_LEY_CRYSTAL_BUD.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> LEY_CRYSTAL = ITEMS.registerItem(
            "ley_crystal", props -> new BlockItem(ModBlocks.LEY_CRYSTAL.get(), props), new Item.Properties());
    public static final DeferredItem<Item> LEY_CRYSTAL_SHARD = ITEMS.registerSimpleItem("ley_crystal_shard", new Item.Properties());
    /** ⑩ 対の石と、手の石(地脈渡りの石)。 */
    public static final DeferredItem<BlockItem> WAYSTONE_ANCHOR_ITEM = ITEMS.registerItem(
            "waystone_anchor", props -> new BlockItem(ModBlocks.WAYSTONE_ANCHOR.get(), props), new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.LeyWaystoneItem> LEY_WAYSTONE = ITEMS.registerItem("ley_waystone",
            com.yayuy.overgrownvegetation.item.LeyWaystoneItem::new, new Item.Properties());
    /** ① 陽溜まりのランタン(腰の追加防具)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SunlitLanternItem> SUNLIT_LANTERN = ITEMS.registerItem("sunlit_lantern",
            com.yayuy.overgrownvegetation.item.SunlitLanternItem::new, new Item.Properties());
    /** ㉗ 灯りの呪符(使い捨て)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.LightCharmItem> LIGHT_CHARM = ITEMS.registerItem("light_charm",
            com.yayuy.overgrownvegetation.item.LightCharmItem::new, new Item.Properties());
    /** ㉘ おとりの灯籠。 */
    public static final DeferredItem<BlockItem> LURE_LANTERN_ITEM = ITEMS.registerItem(
            "lure_lantern", props -> new BlockItem(ModBlocks.LURE_LANTERN.get(), props), new Item.Properties());

    // --- 野営の道具(2026-10-16、クラスター 4。ユーザー指定) ---

    /** ㊱ 帆布の背嚢(体の追加防具・18 枠)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.CanvasBackpackItem> CANVAS_BACKPACK = ITEMS.registerItem("canvas_backpack",
            com.yayuy.overgrownvegetation.item.CanvasBackpackItem::new, new Item.Properties());
    /** ㉜ 灰(焼けた土をシャベルで掘ると出る)と灰の肥料。 */
    public static final DeferredItem<Item> ASH = ITEMS.registerSimpleItem("ash", new Item.Properties());
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.AshFertilizerItem> ASH_FERTILIZER = ITEMS.registerItem("ash_fertilizer",
            com.yayuy.overgrownvegetation.item.AshFertilizerItem::new, new Item.Properties());
    /** ② 胞子まといの外套(体の追加防具)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SporeCloakItem> SPORE_CLOAK = ITEMS.registerItem("spore_cloak",
            com.yayuy.overgrownvegetation.item.SporeCloakItem::new, new Item.Properties());
    /** ⑪ 灰煙玉。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.ThrowableGearItem> ASH_SMOKE_BOMB = ITEMS.registerItem("ash_smoke_bomb",
            props -> new com.yayuy.overgrownvegetation.item.ThrowableGearItem(props.stacksTo(16), com.yayuy.overgrownvegetation.entity.AshSmokeBomb::new,
                    "tooltip.overgrown_vegetation.ash_smoke_bomb"), new Item.Properties());
    /** ⑧ 焼夷瓶。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.ThrowableGearItem> INCENDIARY_FLASK = ITEMS.registerItem("incendiary_flask",
            props -> new com.yayuy.overgrownvegetation.item.ThrowableGearItem(props.stacksTo(16), com.yayuy.overgrownvegetation.purify.IncendiaryFlask::new,
                    "tooltip.overgrown_vegetation.incendiary_flask"), new Item.Properties());
    public static final DeferredItem<BlockItem> BEDROLL_ITEM = ITEMS.registerItem(
            "bedroll", props -> new BlockItem(ModBlocks.BEDROLL.get(), props), new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> SPORE_TENT_ITEM = ITEMS.registerItem(
            "spore_tent", props -> new BlockItem(ModBlocks.SPORE_TENT.get(), props), new Item.Properties().stacksTo(1));

    /** 風見(2026-10-23 胞子の場 第 2 段 H)。 */
    public static final DeferredItem<BlockItem> WIND_VANE_ITEM = ITEMS.registerItem(
            "wind_vane", props -> new BlockItem(ModBlocks.WIND_VANE.get(), props), new Item.Properties());

    /** 根付いた萌芽体(2026-10-23 胞子の場 第 2 段 F。クリエイティブ・確かめ用)。 */
    public static final DeferredItem<BlockItem> BLIGHT_FERN_ROOTED_ITEM = ITEMS.registerItem(
            "blight_fern_rooted", props -> new BlockItem(ModBlocks.BLIGHT_FERN_ROOTED.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> DEFLECTING_STAKE_ITEM = ITEMS.registerItem(
            "deflecting_stake", props -> new BlockItem(ModBlocks.DEFLECTING_STAKE.get(), props), new Item.Properties());

    public static final DeferredItem<BlockItem> ZINC_ORE_ITEM = ITEMS.registerItem("zinc_ore",
            props -> new BlockItem(ModBlocks.ZINC_ORE.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> DEEPSLATE_ZINC_ORE_ITEM = ITEMS.registerItem("deepslate_zinc_ore",
            props -> new BlockItem(ModBlocks.DEEPSLATE_ZINC_ORE.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> RAW_ZINC_BLOCK_ITEM = ITEMS.registerItem("raw_zinc_block",
            props -> new BlockItem(ModBlocks.RAW_ZINC_BLOCK.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> ZINC_BLOCK_ITEM = ITEMS.registerItem("zinc_block",
            props -> new BlockItem(ModBlocks.ZINC_BLOCK.get(), props), new Item.Properties());
    public static final DeferredItem<BlockItem> BRASS_BLOCK_ITEM = ITEMS.registerItem("brass_block",
            props -> new BlockItem(ModBlocks.BRASS_BLOCK.get(), props), new Item.Properties());

    // ─────────────────────────────────────────────────────────────────────
    //  防具一式(兜・胸当て・レギンス・ブーツ)。防具値・耐久などの数値は
    //  gear.json / ModArmorMaterials 側。テクスチャは textures/item/<id>.png と
    //  textures/models/armor/<set>_layer_{1,2}.png。
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 「スカトゥリウムの外骨格」一式だけは、平らな防具レイヤーではなく GeckoLib の立体モデルで
     * 描画する({@link com.yayuy.overgrownvegetation.item.ScaturiumExoskeletonArmorItem})。
     */
    public static final DeferredItem<ArmorItem> EXOSKELETON_HELMET =
            geoArmor("exoskeleton", ModArmorMaterials.EXOSKELETON, ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> EXOSKELETON_CHESTPLATE =
            geoArmor("exoskeleton", ModArmorMaterials.EXOSKELETON, ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> EXOSKELETON_LEGGINGS =
            geoArmor("exoskeleton", ModArmorMaterials.EXOSKELETON, ArmorItem.Type.LEGGINGS);

    /**
     * 「寄生植物装備」— ハイヴノードの儀式で受け取る、外せない胸の装備。段階制(体力・防御・耐久が変わる)。
     * 仕様は {@link com.yayuy.overgrownvegetation.item.PlantArmor}。クリエイティブから出すと 1 段階目・呪い無し
     * (儀式で受け取ったものだけ束縛の呪いが付く)。
     */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.PlantArmorItem> PLANT_ARMOR = ITEMS.registerItem(
            "plant_armor",
            props -> new com.yayuy.overgrownvegetation.item.PlantArmorItem(ModArmorMaterials.PLANT_ARMOR,
                    props.durability(com.yayuy.overgrownvegetation.item.PlantArmor.Stage.ONE.durability).stacksTo(1)),
            new Item.Properties());

    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_HELMET =
            armor("chlorophyllite", ModArmorMaterials.CHLOROPHYLLITE, ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_CHESTPLATE =
            armor("chlorophyllite", ModArmorMaterials.CHLOROPHYLLITE, ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_LEGGINGS =
            armor("chlorophyllite", ModArmorMaterials.CHLOROPHYLLITE, ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_BOOTS =
            armor("chlorophyllite", ModArmorMaterials.CHLOROPHYLLITE, ArmorItem.Type.BOOTS);

    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_ALLOY_HELMET =
            armor("chlorophyllite_alloy", ModArmorMaterials.CHLOROPHYLLITE_ALLOY, ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_ALLOY_CHESTPLATE =
            armor("chlorophyllite_alloy", ModArmorMaterials.CHLOROPHYLLITE_ALLOY, ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_ALLOY_LEGGINGS =
            armor("chlorophyllite_alloy", ModArmorMaterials.CHLOROPHYLLITE_ALLOY, ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> CHLOROPHYLLITE_ALLOY_BOOTS =
            armor("chlorophyllite_alloy", ModArmorMaterials.CHLOROPHYLLITE_ALLOY, ArmorItem.Type.BOOTS);

    public static final DeferredItem<ArmorItem> REINFORCED_CHLOROPHYLLITE_ALLOY_HELMET =
            reinforcedAlloyArmor(ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> REINFORCED_CHLOROPHYLLITE_ALLOY_CHESTPLATE =
            reinforcedAlloyArmor(ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> REINFORCED_CHLOROPHYLLITE_ALLOY_LEGGINGS =
            reinforcedAlloyArmor(ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> REINFORCED_CHLOROPHYLLITE_ALLOY_BOOTS =
            reinforcedAlloyArmor(ArmorItem.Type.BOOTS);

    /** 「パワードアーマー」(順 1 の着手順 6)。部位ごとに 1 個。Mk とスキルツリーはスタックが持つ。作るのは作成テーブル。 */
    public static final DeferredItem<ArmorItem> POWERED_ARMOR_HELMET = poweredArmor(ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> POWERED_ARMOR_CHESTPLATE = poweredArmor(ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> POWERED_ARMOR_LEGGINGS = poweredArmor(ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> POWERED_ARMOR_BOOTS = poweredArmor(ArmorItem.Type.BOOTS);

    /** 「パワードアーマーの設計図」(救済措置)。白紙は 16 まで重なる(写すと中身で分かれる)。 */
    public static final DeferredItem<Item> POWERED_ARMOR_BLUEPRINT = ITEMS.registerItem(
            "powered_armor_blueprint",
            com.yayuy.overgrownvegetation.item.PoweredArmorBlueprintItem::new,
            new Item.Properties().stacksTo(16));

    /** 「カートリッジ」(順 1 の着手順 8)。因子を入れてパワードアーマーに差す。中身ごとに 1 つずつ(重ならない)。 */
    public static final DeferredItem<Item> CARTRIDGE = ITEMS.registerItem(
            "cartridge",
            com.yayuy.overgrownvegetation.item.CartridgeItem::new,
            new Item.Properties().stacksTo(1));

    /** 「因子」5 種(順 1 の着手順 8)。抽出設備でドロップから取り出す。 */
    public static final DeferredItem<Item> FACTOR_IMPACT = factor("impact");
    public static final DeferredItem<Item> FACTOR_PIERCE = factor("pierce");
    public static final DeferredItem<Item> FACTOR_BLAST = factor("blast");
    public static final DeferredItem<Item> FACTOR_TOXIN = factor("toxin");
    public static final DeferredItem<Item> FACTOR_SPORE = factor("spore");

    private static DeferredItem<Item> factor(String id) {
        return ITEMS.registerItem("factor_" + id,
                props -> new com.yayuy.overgrownvegetation.item.FactorItem(
                        java.util.Objects.requireNonNull(com.yayuy.overgrownvegetation.item.CartridgeFactor.byId(id)), props),
                new Item.Properties());
    }

    /**
     * 「装飾品」4 種(どの防具にも)と「拡張パーツ」2 種(パワードアーマー専用)(順 1 の着手順 9)。
     * 作成テーブルの装着タブで付ける。種類と付く部位は {@link com.yayuy.overgrownvegetation.item.ArmorAccessory}。
     */
    public static final DeferredItem<Item> NIGHT_VISION_ACCESSORY = accessory("night_vision_accessory", "night_vision");
    public static final DeferredItem<Item> ENEMY_SENSE_ACCESSORY = accessory("enemy_sense_accessory", "enemy_sense");
    public static final DeferredItem<Item> SWIFT_ACCESSORY = accessory("swift_accessory", "swift");
    public static final DeferredItem<Item> CUSHION_ACCESSORY = accessory("cushion_accessory", "cushion");
    public static final DeferredItem<Item> MINING_EXTENSION = accessory("mining_extension", "mining");
    public static final DeferredItem<Item> ANCHOR_EXTENSION = accessory("anchor_extension", "anchor");

    private static DeferredItem<Item> accessory(String itemId, String accessoryId) {
        return ITEMS.registerItem(itemId,
                props -> new com.yayuy.overgrownvegetation.item.ArmorAccessoryItem(java.util.Objects.requireNonNull(
                        com.yayuy.overgrownvegetation.item.ArmorAccessory.byId(accessoryId)), props),
                new Item.Properties().stacksTo(16));
    }

    /** 部位の型からパワードアーマーのアイテム。 */
    public static Item poweredArmorItem(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> POWERED_ARMOR_HELMET.get();
            case CHESTPLATE -> POWERED_ARMOR_CHESTPLATE.get();
            case LEGGINGS -> POWERED_ARMOR_LEGGINGS.get();
            case BOOTS -> POWERED_ARMOR_BOOTS.get();
            default -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ツール一式(斧・つるはし・シャベル・鍬・剣)。数値は gear.json / ModTiers 側。
    //  各ツール種の基準攻撃力/攻撃速度はバニラ準拠でここに固定(階層の攻撃力ボーナスが加算される)。
    // ─────────────────────────────────────────────────────────────────────

    public static final DeferredItem<Item> CHLOROPHYLLITE_AXE = axe("chlorophyllite_axe", ModTiers.CHLOROPHYLLITE);
    public static final DeferredItem<Item> CHLOROPHYLLITE_PICKAXE = pickaxe("chlorophyllite_pickaxe", ModTiers.CHLOROPHYLLITE);
    public static final DeferredItem<Item> CHLOROPHYLLITE_SHOVEL = shovel("chlorophyllite_shovel", ModTiers.CHLOROPHYLLITE);
    public static final DeferredItem<Item> CHLOROPHYLLITE_HOE = hoe("chlorophyllite_hoe", ModTiers.CHLOROPHYLLITE);
    public static final DeferredItem<Item> CHLOROPHYLLITE_SWORD = sword("chlorophyllite_sword", ModTiers.CHLOROPHYLLITE);

    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_AXE = axe("chlorophyllite_alloy_axe", ModTiers.CHLOROPHYLLITE_ALLOY);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_PICKAXE = pickaxe("chlorophyllite_alloy_pickaxe", ModTiers.CHLOROPHYLLITE_ALLOY);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_SHOVEL = shovel("chlorophyllite_alloy_shovel", ModTiers.CHLOROPHYLLITE_ALLOY);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_HOE = hoe("chlorophyllite_alloy_hoe", ModTiers.CHLOROPHYLLITE_ALLOY);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_SWORD = sword("chlorophyllite_alloy_sword", ModTiers.CHLOROPHYLLITE_ALLOY);

    // ─────────────────────────────────────────────────────────────────────
    //  武器(2026-10-09〜、ユーザー指定「G6 の Mob ができるまでは武器を作る」)。数値は gear.json の perTool.<種別>。
    // ─────────────────────────────────────────────────────────────────────

    /** 「ツヴァイヘンダー」— 両手で振る大剣({@link com.yayuy.overgrownvegetation.item.ZweihanderItem})。絵はユーザー、合金 2 本の絵は塗り替えの仮。 */
    public static final DeferredItem<Item> CHLOROPHYLLITE_ZWEIHANDER = zweihander("chlorophyllite_zweihander", ModTiers.CHLOROPHYLLITE, false);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_ZWEIHANDER = zweihander("chlorophyllite_alloy_zweihander", ModTiers.CHLOROPHYLLITE_ALLOY, false);
    /** 素材のサンライト合金と同じく燃えない。 */
    public static final DeferredItem<Item> SUNLIGHT_ALLOY_ZWEIHANDER = zweihander("sunlight_alloy_zweihander", ModTiers.SUNLIGHT_ALLOY, true);

    /** 「エストック」— 鎧の隙間を突く刺突剣({@link com.yayuy.overgrownvegetation.item.EstocItem}。2026-10-10)。絵は 3 本とも仮。 */
    public static final DeferredItem<Item> CHLOROPHYLLITE_ESTOC = estoc("chlorophyllite_estoc", ModTiers.CHLOROPHYLLITE, false);
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY_ESTOC = estoc("chlorophyllite_alloy_estoc", ModTiers.CHLOROPHYLLITE_ALLOY, false);
    public static final DeferredItem<Item> SUNLIGHT_ALLOY_ESTOC = estoc("sunlight_alloy_estoc", ModTiers.SUNLIGHT_ALLOY, true);

    // --- 登録ヘルパー ---

    private static DeferredItem<ArmorItem> armor(String set, Holder<ArmorMaterial> material, ArmorItem.Type type) {
        return ITEMS.registerItem(set + "_" + type.getName(),
                props -> new ArmorItem(material, type,
                        props.durability(ModArmorMaterials.durability(set, type))),
                new Item.Properties());
    }

    /** {@link #armor} と同じだが、描画が GeckoLib 立体モデルになる防具。 */
    /** 「強化クロロフィライト合金装備」。専用の geo モデルで描くので {@code armor()} ではなくこちら。 */
    private static DeferredItem<ArmorItem> reinforcedAlloyArmor(ArmorItem.Type type) {
        String set = "reinforced_chlorophyllite_alloy";
        return ITEMS.registerItem(set + "_" + type.getName(),
                props -> new com.yayuy.overgrownvegetation.item.ReinforcedChlorophylliteAlloyArmorItem(
                        ModArmorMaterials.REINFORCED_CHLOROPHYLLITE_ALLOY, type,
                        props.durability(ModArmorMaterials.durability(set, type))),
                new Item.Properties());
    }

    /** 「パワードアーマー」。GeckoLib の立体モデル(仮。ユーザーが差し替える)で描く。燃えない(サンライト合金と同じ)。 */
    private static DeferredItem<ArmorItem> poweredArmor(ArmorItem.Type type) {
        String set = "powered_armor";
        return ITEMS.registerItem(set + "_" + type.getName(),
                props -> new com.yayuy.overgrownvegetation.item.PoweredArmorItem(
                        ModArmorMaterials.POWERED_ARMOR, type,
                        props.durability(ModArmorMaterials.durability(set, type)).fireResistant()),
                new Item.Properties());
    }

    private static DeferredItem<ArmorItem> geoArmor(String set, Holder<ArmorMaterial> material, ArmorItem.Type type) {
        return ITEMS.registerItem(set + "_" + type.getName(),
                props -> new com.yayuy.overgrownvegetation.item.ScaturiumExoskeletonArmorItem(material, type,
                        props.durability(ModArmorMaterials.durability(set, type))),
                new Item.Properties());
    }

    private static DeferredItem<Item> tool(String id, Function<Item.Properties, Item> factory) {
        return ITEMS.registerItem(id, factory, new Item.Properties());
    }

    /** gear.json の {@code perTool.<type>} を反映したツール({@code createAttributes} と耐久)。 */
    private static DeferredItem<Item> digger(String id, ModTiers tier, String type,
            java.util.function.BiFunction<Tier, Item.Properties, Item> factory) {
        return tool(id, p -> {
            GearTable.ToolSpec s = DesignData.gear().tool(tier.key());
            Tier t = tier.forTool(type);
            return factory.apply(t, p.attributes(
                    DiggerItem.createAttributes(t, s.attackDamage(type), s.attackSpeed(type))));
        });
    }

    private static DeferredItem<Item> axe(String id, ModTiers tier) {
        return digger(id, tier, "axe", (t, p) -> new AxeItem(t, p));
    }

    private static DeferredItem<Item> pickaxe(String id, ModTiers tier) {
        return digger(id, tier, "pickaxe", (t, p) -> new PickaxeItem(t, p));
    }

    private static DeferredItem<Item> shovel(String id, ModTiers tier) {
        return digger(id, tier, "shovel", (t, p) -> new ShovelItem(t, p));
    }

    private static DeferredItem<Item> hoe(String id, ModTiers tier) {
        return digger(id, tier, "hoe", (t, p) -> new HoeItem(t, p));
    }

    private static DeferredItem<Item> sword(String id, ModTiers tier) {
        return tool(id, p -> {
            GearTable.ToolSpec s = DesignData.gear().tool(tier.key());
            Tier t = tier.forTool("sword");
            return new SwordItem(t, p.attributes(
                    SwordItem.createAttributes(t, Math.round(s.attackDamage("sword")), s.attackSpeed("sword"))));
        });
    }

    /** 攻撃力は剣と違って丸めない(gear.json の小数をそのまま)。 */
    private static DeferredItem<Item> zweihander(String id, ModTiers tier, boolean fireResistant) {
        return tool(id, p -> {
            GearTable.ToolSpec s = DesignData.gear().tool(tier.key());
            Tier t = tier.forTool("zweihander");
            if (fireResistant) {
                p.fireResistant();
            }
            return new com.yayuy.overgrownvegetation.item.ZweihanderItem(t, p.attributes(
                    com.yayuy.overgrownvegetation.item.ZweihanderItem.createAttributes(
                            t, s.attackDamage("zweihander"), s.attackSpeed("zweihander"))));
        });
    }

    private static DeferredItem<Item> estoc(String id, ModTiers tier, boolean fireResistant) {
        return tool(id, p -> {
            GearTable.ToolSpec s = DesignData.gear().tool(tier.key());
            Tier t = tier.forTool("estoc");
            if (fireResistant) {
                p.fireResistant();
            }
            return new com.yayuy.overgrownvegetation.item.EstocItem(t, p.attributes(
                    com.yayuy.overgrownvegetation.item.EstocItem.createAttributes(t, s.attackDamage("estoc"), s.attackSpeed("estoc"))));
        });
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
