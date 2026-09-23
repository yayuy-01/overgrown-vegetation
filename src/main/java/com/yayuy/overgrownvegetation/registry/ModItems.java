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
            ModBlocks.OVERGROWN_SHEEP_CORPSE,
            ModBlocks.OVERGROWN_PIG_CORPSE,
            ModBlocks.OVERGROWN_CREEPER_CORPSE,
            ModBlocks.OVERGROWN_SPIDER_CORPSE);

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

    /** blight_water_grass(汚染水草)のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> BLIGHT_WATER_GRASS_SPAWN_EGG = ITEMS.registerItem(
            "blight_water_grass_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.BLIGHT_WATER_GRASS, 0x3F5A28, 0x2E6E8C, props),
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
            "repellent_lamp", props -> new BlockItem(ModBlocks.REPELLENT_LAMP.get(), props), new Item.Properties());
    /** 有刺鉄線(2026-09-19)。 */
    public static final DeferredItem<BlockItem> BARBED_WIRE_ITEM = ITEMS.registerItem(
            "barbed_wire", props -> new BlockItem(ModBlocks.BARBED_WIRE.get(), props), new Item.Properties());
    /** フェーズ計測機(2026-09-19)。 */
    public static final DeferredItem<BlockItem> PHASE_RECORDER_ITEM = ITEMS.registerItem(
            "phase_recorder", props -> new BlockItem(ModBlocks.PHASE_RECORDER.get(), props), new Item.Properties());
    /** 胞子警報器(2026-09-19)。 */
    public static final DeferredItem<BlockItem> SPORE_ALARM_ITEM = ITEMS.registerItem(
            "spore_alarm", props -> new BlockItem(ModBlocks.SPORE_ALARM.get(), props), new Item.Properties());

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

    // --- アルボラの巣の中身(2026-09-20)。作れないが、確認用にクリエイティブへは出す。 ---

    public static final DeferredItem<BlockItem> ARBORA_EGG_ITEM = ITEMS.registerItem(
            "arbora_egg", props -> new BlockItem(ModBlocks.ARBORA_EGG.get(), props), new Item.Properties());

    public static final DeferredItem<BlockItem> ARBORA_STORAGE_POD_ITEM = ITEMS.registerItem(
            "arbora_storage_pod", props -> new BlockItem(ModBlocks.ARBORA_STORAGE_POD.get(), props), new Item.Properties());

    public static final DeferredItem<BlockItem> SPORE_NODE_ITEM = ITEMS.registerItem(
            "spore_node", props -> new BlockItem(ModBlocks.SPORE_NODE.get(), props), new Item.Properties());

    public static final DeferredItem<BlockItem> SPORE_VENT_ITEM = ITEMS.registerItem(
            "spore_vent", props -> new BlockItem(ModBlocks.SPORE_VENT.get(), props), new Item.Properties());

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
    //  装備・ツールの加工素材。原石やドロップ素材を精製した中間素材。
    //  レシピ(何から作るか)は後々レシピ側で定義する。
    // ─────────────────────────────────────────────────────────────────────

    /** 「爬虫類の革」— 爬虫類の甲板をなめして得た丈夫な革。 */
    public static final DeferredItem<Item> REPTILE_LEATHER =
            ITEMS.registerSimpleItem("reptile_leather", new Item.Properties());

    /** 「クロロフィライト合金」— クロロフィライトを他素材と混ぜて鋳込んだ合金インゴット。最上位の装備・ツール素材。 */
    public static final DeferredItem<Item> CHLOROPHYLLITE_ALLOY =
            ITEMS.registerSimpleItem("chlorophyllite_alloy", new Item.Properties());

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

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
