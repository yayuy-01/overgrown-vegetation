package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
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

    static {
        // 全ての汚染ブロックに対応する BlockItem を自動登録する(死体・抜け殻の立体アイコンは 2026-10-30 に parked/ へ)。
        ModBlocks.displayOrder().forEach(block -> {
            if (block == ModBlocks.OVERGROWN_LIANA) {
                // 蔦は1マス置いても見えないので、始点→終点の2回クリックで張る専用アイテム。
                ITEMS.registerItem(block.getId().getPath(),
                        props -> new com.yayuy.overgrownvegetation.item.LianaItem(block.get(), props), new Item.Properties());
            } else if (block == ModBlocks.ALGAE_FILM || block == ModBlocks.SPORE_FILM || block == ModBlocks.SEA_SPORE_BAND) {
                // 水面の膜は睡蓮の葉と同じく水面を狙って置く(2026-11-01)。
                ITEMS.registerItem(block.getId().getPath(),
                        props -> new net.minecraft.world.item.PlaceOnWaterBlockItem(block.get(), props), new Item.Properties());
            } else {
                ITEMS.registerSimpleBlockItem(block);
            }
        });
    }

    /** 「ハイヴノード」のスポーンエッグ(テスト用。通常は巨大樹の生成で 1 体だけ湧く)。 */
    public static final DeferredItem<DeferredSpawnEggItem> HIVE_NODE_SPAWN_EGG = ITEMS.registerItem(
            "hive_node_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.HIVE_NODE, 0x2B1D14, 0x4CE05A, props),
            new Item.Properties());

    /** ファルキムネスのスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> FALCIMNES_SPAWN_EGG = ITEMS.registerItem(
            "falcimnes_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.FALCIMNES, 0x2F3A14, 0x9C8A2E, props),
            new Item.Properties());

    /** アルボラのスポーンエッグ(11-05 戻した。色は前と同じ)。 */
    public static final DeferredItem<DeferredSpawnEggItem> ARBORA_SPAWN_EGG = ITEMS.registerItem(
            "arbora_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.ARBORA, 0x4A5A2C, 0x8FA34C, props),
            new Item.Properties());

    /** blight_fern のスポーンエッグ。 */
    public static final DeferredItem<DeferredSpawnEggItem> BLIGHT_FERN_SPAWN_EGG = ITEMS.registerItem(
            "blight_fern_spawn_egg",
            props -> new DeferredSpawnEggItem(ModEntities.BLIGHT_FERN, 0x3F5A28, 0x7B2D8C, props),
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

    /** 「胞子腺の組織」— G4(汚染を広げる側)共通。胞子の因子の元。 */
    public static final DeferredItem<Item> SPORE_GLAND_TISSUE =
            ITEMS.registerSimpleItem("spore_gland_tissue", new Item.Properties());

    /** 「胞子嚢」— 寄生されたクリーパーが落とす。爆発の因子の元(火薬はそのまま落とす)。 */
    public static final DeferredItem<Item> SPORE_SAC =
            ITEMS.registerSimpleItem("spore_sac", new Item.Properties());

    /** 根付いた萌芽体(2026-10-23 胞子の場 第 2 段 F。クリエイティブ・確かめ用)。 */
    public static final DeferredItem<BlockItem> BLIGHT_FERN_ROOTED_ITEM = ITEMS.registerItem(
            "blight_fern_rooted", props -> new BlockItem(ModBlocks.BLIGHT_FERN_ROOTED.get(), props), new Item.Properties());

    /** 「胞子濃度計」— 浮遊・降り積もり・自分の暴露量の 3 本の針(2026-11-04 parked から戻した。レシピは材料が退避中なので無い)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.SporeMeterItem> SPORE_METER = ITEMS.registerItem(
            "spore_meter", com.yayuy.overgrownvegetation.item.SporeMeterItem::new, new Item.Properties().stacksTo(1));

    /** 「防護マスク」— フィルターが残っている間は胞子感染にかからない(2026-11-04 parked から戻した。レシピは革・植物繊維・紙)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.item.ProtectiveMaskItem> PROTECTIVE_MASK = ITEMS.registerItem(
            "protective_mask", com.yayuy.overgrownvegetation.item.ProtectiveMaskItem::new, new Item.Properties().stacksTo(1));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
