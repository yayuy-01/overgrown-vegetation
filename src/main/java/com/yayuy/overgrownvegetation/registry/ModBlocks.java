package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlock;
import com.yayuy.overgrownvegetation.block.CaveSurfaceGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedFlushWallGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedGeoGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedGroundGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedHangingGrowthBlock;
import com.yayuy.overgrownvegetation.block.LargeUndevelopedFruitBlock;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlock;
import com.yayuy.overgrownvegetation.block.CorruptedLilyBlock;
import com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock;
import com.yayuy.overgrownvegetation.block.CorruptedTallGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedTallWaterGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWallGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWaterCeilingGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWaterColumnBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWaterColumnFlowerBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWaterGrowthBlock;
import com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock;
import com.yayuy.overgrownvegetation.block.OrganNodeBlock;
import com.yayuy.overgrownvegetation.block.SporeGlandBlock;
import com.yayuy.overgrownvegetation.block.LithopleuraHuskBlock;
import com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock;
import com.yayuy.overgrownvegetation.block.OvergrownBushBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVinesBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVinesPlantBlock;
import com.yayuy.overgrownvegetation.block.OvergrownDirtBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGrassBlock;
import com.yayuy.overgrownvegetation.block.OvergrownMossLayerBlock;
import com.yayuy.overgrownvegetation.block.OvergrownWaterMossLayerBlock;
import com.yayuy.overgrownvegetation.block.OvergrownSandBlock;
import com.yayuy.overgrownvegetation.block.UndevelopedBushBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

/**
 * 汚染ブロックの登録。
 *
 * <p>新しい汚染ブロックを足すときは
 * <ol>
 *     <li>ここに定義を追加</li>
 *     <li>{@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry} に変換ルールを追加</li>
 *     <li>blockstates / models / textures / loot_table / lang を追加</li>
 * </ol>
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(OvergrownVegetation.MODID);

    /**
     * 草ブロック → 苔と下草に覆われた土。
     *
     * <p>バニラの草ブロックと同じく、ランダムティックで隣接する {@link #OVERGROWN_DIRT} を
     * 汚染された草ブロックへ広げていく({@link OvergrownGrassBlock} 参照)。
     */
    public static final DeferredBlock<Block> OVERGROWN_GRASS_BLOCK = BLOCKS.registerBlock(
            "overgrown_grass_block",
            OvergrownGrassBlock::new,
            // 音(設置音・破壊音)は ofFullCopy(GRASS_BLOCK) が持つ元ブロックのものを
            // そのまま使う。汚染ブロックは「汚染元と同じ足音・破壊音」にする方針なので
            // ここで .sound(...) を上書きしない(以下の汚染変換先ブロックすべて同様)。
            BlockBehaviour.Properties.ofFullCopy(Blocks.GRASS_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /**
     * 土・粗い土・菌糸など → 苔むした土。
     *
     * <p>ランダムティックで、隣に汚染された草ブロックが無くても一定確率
     * ({@link OvergrownVegetationConfig.Common#dirtToGrassChance})で自分から
     * {@link #OVERGROWN_GRASS_BLOCK} へ変わる({@link OvergrownDirtBlock} 参照)。
     */
    public static final DeferredBlock<Block> OVERGROWN_DIRT = BLOCKS.registerBlock(
            "overgrown_dirt",
            OvergrownDirtBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /** 石・花崗岩などの基礎石 → 苔に覆われた石。 */
    public static final DeferredBlock<Block> OVERGROWN_STONE = BLOCKS.registerSimpleBlock(
            "overgrown_stone",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩系 → 苔に覆われた深層岩。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE)
                    .mapColor(MapColor.COLOR_GREEN));

    // ------------------------------------------------------------------
    // 石系の色付きバリエーション(花崗岩・閃緑岩・安山岩など)
    //
    // OVERGROWN_STONE / OVERGROWN_DEEPSLATE の汎用バケツにまとめず、
    // 元テクスチャの色味を保ったまま moss_template を乗算合成した専用テクスチャを持つ。
    // ------------------------------------------------------------------

    /** 安山岩・磨かれた安山岩 → 苔に覆われた安山岩。 */
    public static final DeferredBlock<Block> OVERGROWN_ANDESITE = BLOCKS.registerSimpleBlock(
            "overgrown_andesite",
            BlockBehaviour.Properties.ofFullCopy(Blocks.ANDESITE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 閃緑岩・磨かれた閃緑岩 → 苔に覆われた閃緑岩。 */
    public static final DeferredBlock<Block> OVERGROWN_DIORITE = BLOCKS.registerSimpleBlock(
            "overgrown_diorite",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIORITE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 花崗岩・磨かれた花崗岩 → 苔に覆われた花崗岩。 */
    public static final DeferredBlock<Block> OVERGROWN_GRANITE = BLOCKS.registerSimpleBlock(
            "overgrown_granite",
            BlockBehaviour.Properties.ofFullCopy(Blocks.GRANITE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 丸石・苔むした丸石 → 苔に覆われた丸石。 */
    public static final DeferredBlock<Block> OVERGROWN_COBBLESTONE = BLOCKS.registerSimpleBlock(
            "overgrown_cobblestone",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLESTONE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の丸石 → 苔に覆われた深層岩の丸石。 */
    public static final DeferredBlock<Block> OVERGROWN_COBBLED_DEEPSLATE = BLOCKS.registerSimpleBlock(
            "overgrown_cobbled_deepslate",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLED_DEEPSLATE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 鍾乳石ブロック → 苔に覆われた鍾乳石ブロック。 */
    public static final DeferredBlock<Block> OVERGROWN_DRIPSTONE_BLOCK = BLOCKS.registerSimpleBlock(
            "overgrown_dripstone_block",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DRIPSTONE_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN));

    /** レンガ → 苔に覆われたレンガ。 */
    public static final DeferredBlock<Block> OVERGROWN_BRICKS = BLOCKS.registerSimpleBlock(
            "overgrown_bricks",
            BlockBehaviour.Properties.ofFullCopy(Blocks.BRICKS)
                    .mapColor(MapColor.COLOR_GREEN));

    // ------------------------------------------------------------------
    // 鉱石ブロック
    //
    // 対応表に無いと汎用フォールバック(OVERGROWN_BLOCK)になってしまうため、
    // テクスチャを用意したものだけ専用の汚染ブロックとして個別に用意する。
    // ------------------------------------------------------------------

    /** ダイヤモンド鉱石 → 苔に覆われたダイヤモンド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DIAMOND_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_diamond_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIAMOND_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の石炭鉱石 → 苔に覆われた深層岩の石炭鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_COAL_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_coal_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_COAL_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の銅鉱石 → 苔に覆われた深層岩の銅鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_COPPER_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_copper_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_COPPER_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩のダイヤモンド鉱石 → 苔に覆われた深層岩のダイヤモンド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_DIAMOND_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_diamond_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_DIAMOND_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩のエメラルド鉱石 → 苔に覆われた深層岩のエメラルド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_EMERALD_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_emerald_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_EMERALD_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の金鉱石 → 苔に覆われた深層岩の金鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_GOLD_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_gold_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_GOLD_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の鉄鉱石 → 苔に覆われた深層岩の鉄鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_IRON_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_iron_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_IRON_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩のラピスラズリ鉱石 → 苔に覆われた深層岩のラピスラズリ鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_LAPIS_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_lapis_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_LAPIS_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 深層岩のレッドストーン鉱石 → 苔に覆われた深層岩のレッドストーン鉱石。
     *
     * <p>元ブロックは「起きたら光る」ための LIT プロパティ付き発光ロジックを持つが、
     * このブロックはそのプロパティを持たない単純な Block なので、
     * ofFullCopy で引き継がれる発光関数を明示的に上書き(常時消灯)しないと
     * ブロック状態生成時に IllegalArgumentException で起動クラッシュする。
     */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_REDSTONE_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_redstone_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_REDSTONE_ORE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel((BlockState state) -> 0));

    // ------------------------------------------------------------------
    // 通常(非深層岩)の鉱石ブロック
    // ------------------------------------------------------------------

    /** 石炭鉱石 → 苔に覆われた石炭鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_COAL_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_coal_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COAL_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 銅鉱石 → 苔に覆われた銅鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_COPPER_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_copper_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** エメラルド鉱石 → 苔に覆われたエメラルド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_EMERALD_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_emerald_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.EMERALD_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 金鉱石 → 苔に覆われた金鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_GOLD_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_gold_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.GOLD_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 鉄鉱石 → 苔に覆われた鉄鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_IRON_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_iron_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** ラピスラズリ鉱石 → 苔に覆われたラピスラズリ鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_LAPIS_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_lapis_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.LAPIS_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * レッドストーン鉱石 → 苔に覆われたレッドストーン鉱石。
     *
     * <p>OVERGROWN_DEEPSLATE_REDSTONE_ORE と同じ理由で、
     * LIT プロパティに依存する発光ロジックを明示的に上書きする必要がある。
     */
    public static final DeferredBlock<Block> OVERGROWN_REDSTONE_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_redstone_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_ORE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel((BlockState state) -> 0));

    /**
     * 砂・砂岩など → 菌糸に覆われた砂。
     *
     * <p>「広がる密林」のコンセプト上、砂地のままでは印象を損なうため、
     * 置かれてからおよそ 20 秒で自動的に {@link #OVERGROWN_DIRT} へ変わる
     * ({@link OvergrownSandBlock} 参照)。
     */
    public static final DeferredBlock<Block> OVERGROWN_SAND = BLOCKS.registerBlock(
            "overgrown_sand",
            OvergrownSandBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.SAND)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 砂利 → 汚染された砂利。 */
    public static final DeferredBlock<Block> OVERGROWN_GRAVEL = BLOCKS.registerSimpleBlock(
            "overgrown_gravel",
            BlockBehaviour.Properties.ofFullCopy(Blocks.GRAVEL)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 原木・樹皮を剥いだ原木など → 寄生された幹。 */
    public static final DeferredBlock<Block> OVERGROWN_LOG = BLOCKS.registerBlock(
            "overgrown_log",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * {@link #OVERGROWN_LOG} の側面(樹皮)テクスチャを6面すべてに使った「過繁茂した木」ブロック
     * (バニラの {@code oak_wood} 相当)。木口の年輪が見えない、樹皮だけの幹。
     * {@link com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature} の根はこのブロックで構成される。
     */
    public static final DeferredBlock<Block> OVERGROWN_WOOD = BLOCKS.registerBlock(
            "overgrown_wood",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_WOOD)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 巨大樹({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})の幹・枝・根専用。
     * 見た目・音・当たり判定は {@link #OVERGROWN_LOG} と完全に同じ(テクスチャも流用)だが、
     * ブロックとしては別物なので {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector}
     * が「連結蔦をつなぐ相手の木」として拾わない = <b>巨大樹には蔦が生えない</b>。
     * {@code displayOrder()} に入れてあるので自動で BlockItem + クリエイティブタブ入りになり、
     * 掘ると自身をドロップする(ユーザー要望でアイテム化)。
     */
    public static final DeferredBlock<Block> GIANT_OVERGROWN_LOG = BLOCKS.registerBlock(
            "giant_overgrown_log",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn(ModBlocks::noGiantTreeSpawn));


    /**
     * <b>地脈</b>({@code ley_vein}、2026-09-22)。極相バイオームの地表を走る、ゆっくり明滅する脈。
     *
     * <p>見た目は 2 層のモデル ——「光っていない」テクスチャの立方体の上に、脈のピクセルだけを
     * 取り出した発光オーバーレイ({@code ley_vein_glow}、8 コマのアニメーション)を重ねてある。
     * オーバーレイだけが {@code neoforge_data.block_light: 15} なので、
     * <b>周りを一切照らさずに自分だけが暗闇で光る</b>(光量は 0。{@code cave_spore_sac} と同じ考え方)。
     *
     * <p>明滅は<b>ハイヴノードと同じ作り</b>(8 コマ + {@code interpolate}、25 tick ごと = 10 秒周期)。
     * 変えているのは<b>脈の色だけ</b>で、アルファは全コマ一定にしてある —— 描画が cutout なので、
     * アルファを動かすとコマの切り替わりでピクセルがパチパチ現れて「ボヤっと」にならない。
     * 色だけなら {@code interpolate} がコマの間を埋めるので、なめらかに明るくなって暗くなる。
     */
    public static final DeferredBlock<Block> LEY_VEIN = BLOCKS.registerBlock(
            "ley_vein",
            Block::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.PLANT)
                    .requiresCorrectToolForDrops()
                    .strength(1.8F, 6.0F)
                    // 光量は 0 のまま。光って見えるのはモデルの自己発光だけで、周りは照らさない。
                    .sound(SoundType.STONE));

    /**
     * <b>器官の節</b>(2026-09-23、要望)。器官はここから生える。
     * <b>節が器官そのもの</b>で、立ち上がる塔はその体にすぎない ——
     * 世界への効きも、潰したときの手応えも、すべてこのブロックが握っている。
     *
     * <p><b>硬い。</b> 器官を潰すのは遠征であってほしいので、正しい道具でもしばらくかかる。
     * 爆発耐性を高くしてあるのは、TNT を投げ込んで遠くから終わらせられないようにするため。
     * 節は体の根元の塊に埋まっているので、辿り着くには掘る必要がある
     * (その途中で腺に触る)。
     */
    public static final DeferredBlock<Block> ORGAN_NODE = BLOCKS.registerBlock(
            "organ_node",
            OrganNodeBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_PURPLE)
                    .requiresCorrectToolForDrops()
                    .strength(18.0F, 1200.0F)
                    // 光量は 0 のまま。光って見えるのはモデルの自己発光だけ(地脈と同じ)。
                    .sound(SoundType.SCULK));

    /**
     * <b>器官の蔦</b>(2026-09-23、要望)。器官の体を編んでいる主材。
     * 汚染ブロックと混ぜて三つ編みの筋になる({@code organ/shape/SporeGlandTower})。
     *
     * <p>体は<b>節より柔らかい</b> —— 削って節まで掘り進めるようにするため。
     * 時間が掛かるのは掘る手間ではなく、表面に点在する腺
     * ({@link com.yayuy.overgrownvegetation.block.SporeGlandBlock})のほう。
     */
    public static final DeferredBlock<Block> ORGAN_VINE = BLOCKS.registerBlock(
            "organ_vine",
            Block::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.PLANT)
                    .strength(2.5F, 6.0F)
                    .sound(SoundType.VINE));

    /**
     * <b>胞子腺</b> —— 器官の表面に点在する腺(2026-09-23、要望)。
     * 器官そのものではなく、体の表面に浮いた吹き出し口。塔の先端のものが胞子を噴く。
     *
     * <p><b>壊すと浴びる。</b> 毒・吐き気・盲目・空腹5・胞子感染2・採掘速度低下 の
     * 6 つから重複なしで 2 つ(既定)が掛かる —— 節まで掘る道のりに値段を付けるもの。
     * 柔らかいのはわざと。硬いと「避けて通る」しか答えが無くなる。
     */
    public static final DeferredBlock<Block> SPORE_GLAND = BLOCKS.registerBlock(
            "spore_gland",
            SporeGlandBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(1.5F, 6.0F)
                    // 光量は 0 のまま。光って見えるのはモデルの自己発光だけ(地脈と同じ)。
                    .sound(SoundType.SCULK));

    /**
     * <b>覆われて動かなくなったブロック</b>(2026-09-22、要望)。汚染の対応表に無い
     * <b>他 MOD の機械</b>({@link net.minecraft.world.level.block.entity.BlockEntity} を持つもの)が
     * 植物に覆われたとき、これに差し替わる —— <b>見た目は元のブロックのまま、機能は全部無い</b>。
     * 中に入っていたアイテムは引き継ぎ、壊すと返ってくる
     * ({@link com.yayuy.overgrownvegetation.block.OvergrownHuskBlock} 参照)。
     *
     * <p>{@code displayOrder()} には<b>入れない</b> —— 自動で BlockItem が作られると
     * 「何にも化けていない覆われたブロック」がクリエイティブタブに並んでしまう。
     * 手に入らないブロックなので BlockItem 自体を作らない。
     *
     * <p>形は元のブロックから借りるので {@code dynamicShape()}(状態ごとの形キャッシュを切る)。
     * ピストンで押せると中身を持ったまま飛んでいくので {@code PushReaction.BLOCK}。
     */
    public static final DeferredBlock<Block> OVERGROWN_HUSK = BLOCKS.registerBlock(
            "overgrown_husk",
            com.yayuy.overgrownvegetation.block.OvergrownHuskBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.MOSS)
                    .dynamicShape()
                    .pushReaction(PushReaction.BLOCK));
    /**
     * 「硬化した巨大樹の幹」。巨大樹の幹の<b>真下</b>に埋まっている球状の空洞(将来の「hive node」用の
     * 広間)の殻を形づくるブロック({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature} の
     * {@code buildHiveChamber})。<b>ネザライト以上の斧でしか壊せず</b>、爆発でも壊れない
     * ({@link com.yayuy.overgrownvegetation.block.HardenedGiantTrunkBlock} 参照)。
     * 現状は見た目のみプレースホルダー(原木ベースの暗い樹皮)。
     */
    public static final DeferredBlock<Block> HARDENED_GIANT_TRUNK = BLOCKS.registerBlock(
            "hardened_giant_trunk",
            com.yayuy.overgrownvegetation.block.HardenedGiantTrunkBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_BROWN)
                    .requiresCorrectToolForDrops()
                    // 硬さはネザライトブロック級、爆発耐性は黒曜石級(クリーパー・TNT で穴が開かない)。
                    .strength(50.0F, 1200.0F)
                    .isValidSpawn(ModBlocks::noGiantTreeSpawn));

    /**
     * 巨大樹の<b>幹の内側</b>(空気に触れないブロック)を占める「形成層」。外殻は
     * {@link #GIANT_OVERGROWN_LOG} のまま、6方向すべてが幹のブロックだけがこれに置き換わる
     * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#buildPlan} の分割処理)。
     * 現状はプレースホルダー(見た目・プロパティは原木と同じ)。
     */
    public static final DeferredBlock<Block> GIANT_OVERGROWN_CAMBIUM = BLOCKS.registerSimpleBlock(
            "giant_overgrown_cambium",
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn(ModBlocks::noGiantTreeSpawn));

    /**
     * 「クロロフィライトの鉱石」。巨大樹({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})の
     * 幹の内側(形成層 = {@link #GIANT_OVERGROWN_CAMBIUM})に、ランダムな位置の鉱石ノードとして
     * <b>多めに</b>生成される。掘ると「クロロフィライトの原石」({@code raw_chlorophyllite})を落とす
     * (シルクタッチならブロックそのまま)。現状は見た目のみプレースホルダー。
     */
    public static final DeferredBlock<Block> CHLOROPHYLLITE_ORE = BLOCKS.registerSimpleBlock(
            "chlorophyllite_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.0F)
                    .isValidSpawn(ModBlocks::noGiantTreeSpawn));

    /**
     * 「硬化樹液結晶の鉱石」。{@link #CHLOROPHYLLITE_ORE} と同じく巨大樹の形成層に鉱石ノードとして
     * 生成されるが、出現は<b>まれ</b>。掘ると「硬化樹液結晶の原石」({@code raw_hardened_sap_crystal})を
     * 落とす(シルクタッチならブロックそのまま)。現状は見た目のみプレースホルダー。
     */
    public static final DeferredBlock<Block> HARDENED_SAP_CRYSTAL_ORE = BLOCKS.registerSimpleBlock(
            "hardened_sap_crystal_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.TERRACOTTA_ORANGE)
                    .requiresCorrectToolForDrops()
                    .strength(4.5F, 4.5F)
                    .lightLevel(state -> 4)
                    .isValidSpawn(ModBlocks::noGiantTreeSpawn));

    // ------------------------------------------------------------------
    // 素材の保管ブロック(9 個 ⇄ 1 ブロック)。汚染ブロックではないので
    // displayOrder() には乗せず、{@link ModItems} で BlockItem を個別登録し、
    // {@link ModCreativeTabs} で明示的にタブへ入れる。
    // 見た目は「バニラの塗り替え」: 結晶2種はアメジストブロック、合金は鉄ブロックの
    // ドット絵をそのまま使い、色だけ作者のアイテムアイコンの palette に差し替えてある
    // (textures/block/*.png)。
    // ------------------------------------------------------------------

    /** 「クロロフィライトのブロック」。{@code chlorophyllite} 9 個の保管用。見た目はアメジストブロック。 */
    public static final DeferredBlock<Block> CHLOROPHYLLITE_BLOCK = BLOCKS.registerSimpleBlock(
            "chlorophyllite_block",
            BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 「樹液結晶のブロック」。{@code sap_crystal} 9 個の保管用。見た目はアメジストブロック。 */
    public static final DeferredBlock<Block> SAP_CRYSTAL_BLOCK = BLOCKS.registerSimpleBlock(
            "sap_crystal_block",
            BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_BLOCK)
                    .mapColor(MapColor.COLOR_ORANGE));

    /** 「クロロフィライト合金のブロック」。{@code chlorophyllite_alloy} 9 個の保管用。見た目は鉄ブロック。 */
    public static final DeferredBlock<Block> CHLOROPHYLLITE_ALLOY_BLOCK = BLOCKS.registerSimpleBlock(
            "chlorophyllite_alloy_block",
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 葉 → 汚染された葉。 */
    public static final DeferredBlock<Block> OVERGROWN_LEAVES = BLOCKS.registerSimpleBlock(
            "overgrown_leaves",
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn((state, getter, pos, type) -> false));

    // ------------------------------------------------------------------
    // 湿地 / 菌湿地帯({@link OvergrowthVariant#SWAMP} / {@link OvergrowthVariant#FUNGAL_SWAMP})専用ブロック。
    // 汚染で変換されるのではなく、湿地環境の苗木から育つ木・浸水処理(SwampFlooder)が直接置く。
    // テクスチャは仮(TexGen で生成した 16x16)。ユーザーが後で差し替える。
    // ------------------------------------------------------------------

    /** 湿地の木(木生シダ / 蘆木のイメージ)の幹。節の入った淡い緑灰色の樹皮。 */
    public static final DeferredBlock<Block> OVERGROWN_SWAMP_LOG = BLOCKS.registerBlock(
            "overgrown_swamp_log",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_LIGHT_GREEN));

    /** {@link #OVERGROWN_SWAMP_LOG} の樹皮だけ版。湿地の木の根に使う。 */
    public static final DeferredBlock<Block> OVERGROWN_SWAMP_WOOD = BLOCKS.registerBlock(
            "overgrown_swamp_wood",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_WOOD)
                    .mapColor(MapColor.COLOR_LIGHT_GREEN));

    /** 湿地の木の葉(羽状の薄い黄緑のシダ葉)。 */
    public static final DeferredBlock<Block> OVERGROWN_SWAMP_LEAVES = BLOCKS.registerSimpleBlock(
            "overgrown_swamp_leaves",
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .isValidSpawn((state, getter, pos, type) -> false));

    /**
     * 菌類の柱。菌湿地帯に所々そびえる、先細りの暗い巨大菌類(プロトタキシテスのイメージ)。
     * {@link com.yayuy.overgrownvegetation.worldgen.FungalPillarFeature} が積み上げる。
     */
    public static final DeferredBlock<Block> FUNGAL_PILLAR = BLOCKS.registerBlock(
            "fungal_pillar",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.MUSHROOM_STEM)
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(1.2F));

    /**
     * 菌類の傘。湿地・菌湿地帯の洞窟の壁から張り出す巨大なキノコの傘
     * ({@link com.yayuy.overgrownvegetation.corruption.CaveGrowth#buildWallCap})。
     * 上面が傘の皮、下面がヒダ、側面が縁。
     */
    public static final DeferredBlock<Block> FUNGAL_CAP = BLOCKS.registerSimpleBlock(
            "fungal_cap",
            BlockBehaviour.Properties.ofFullCopy(Blocks.BROWN_MUSHROOM_BLOCK)
                    .mapColor(MapColor.TERRACOTTA_ORANGE)
                    .strength(0.6F));

    /**
     * 発光菌糸。洞窟の菌の柱({@link com.yayuy.overgrownvegetation.corruption.CaveGrowth#buildPillar})に
     * 所々混ざる、微かに光る菌糸のブロック。
     */
    public static final DeferredBlock<Block> GLOWING_MYCELIUM = BLOCKS.registerSimpleBlock(
            "glowing_mycelium",
            BlockBehaviour.Properties.ofFullCopy(Blocks.MUSHROOM_STEM)
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(1.0F)
                    .lightLevel(state -> 6));

    /**
     * 過繁茂している泥。湿地の池・水路・棚田の底、水際の湿った地面。
     * {@link com.yayuy.overgrownvegetation.corruption.SwampFlooder} だけが置く
     * (「既に処理済みの列」の目印も兼ねる)。苗木を植えられる土の一種。
     */
    public static final DeferredBlock<Block> OVERGROWN_MUD = BLOCKS.registerSimpleBlock(
            "overgrown_mud",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .sound(SoundType.MUD)
                    .strength(0.4F));

    /**
     * 過繁茂している泥のハーフブロック。棚田の水面(段の高さ + 1)に水没させた下付きハーフブロックを敷き、
     * 水深 0.5 の浅い段にする({@link com.yayuy.overgrownvegetation.corruption.SwampFlooder})。
     * バニラの {@link SlabBlock} なので水没(waterlogged)に対応。
     */
    public static final DeferredBlock<Block> OVERGROWN_MUD_SLAB = BLOCKS.registerBlock(
            "overgrown_mud_slab",
            SlabBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .sound(SoundType.MUD)
                    .strength(0.4F));

    /**
     * 汚染された木が育つ、汚染された草・土の上にだけ植えられる苗木。
     *
     * <p>{@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が
     * 汚染直後の草・土の露出した上面に確率で直接植えるほか、バニラの苗木
     * ({@code #minecraft:saplings} タグ)が汚染された際の変換先でもある
     * ({@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry} 参照)。
     * バニラの苗木と同じくランダムティックで自動的に育ち、
     * {@link #OVERGROWN_TREE_FEATURE}({@code data/overgrown_vegetation/worldgen/configured_feature/overgrown_tree.json}
     * で定義)に従って {@link #OVERGROWN_LOG} と {@link #OVERGROWN_LEAVES} でできた木になる。
     */
    public static final ResourceKey<ConfiguredFeature<?, ?>> OVERGROWN_TREE_FEATURE = ResourceKey.create(
            Registries.CONFIGURED_FEATURE,
            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "overgrown_tree"));

    private static final TreeGrower OVERGROWN_TREE_GROWER = new TreeGrower(
            "overgrown_vegetation_overgrown_tree",
            Optional.empty(),
            Optional.of(OVERGROWN_TREE_FEATURE),
            Optional.empty());

    public static final DeferredBlock<Block> OVERGROWN_SAPLING = BLOCKS.registerBlock(
            "overgrown_sapling",
            properties -> new CorruptedSaplingBlock(OVERGROWN_TREE_GROWER, properties),
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_SAPLING)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /**
     * 「巨大樹の苗木」が育つ先。{@code random_selector} を介さず
     * {@link GiantOvergrownSaplingBlock} の {@link TreeGrower} から直接指すので
     * {@code overgrown_tree_giant} の configured_feature をそのまま参照する
     * (この場合 placed_feature ラッパーは不要 — {@code overgrown_tree_main} と同じ)。
     * ごく低確率で自然に混ざる方の巨大樹({@code overgrown_tree.json} の {@code random_selector})とは
     * 同じ configured_feature を共有している。
     */
    public static final ResourceKey<ConfiguredFeature<?, ?>> GIANT_OVERGROWN_TREE_FEATURE = ResourceKey.create(
            Registries.CONFIGURED_FEATURE,
            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "overgrown_tree_giant"));

    private static final TreeGrower GIANT_OVERGROWN_TREE_GROWER = new TreeGrower(
            "overgrown_vegetation_giant_overgrown_tree",
            Optional.empty(),
            Optional.of(GIANT_OVERGROWN_TREE_FEATURE),
            Optional.empty());

    /**
     * 巨大樹({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})が確定で育つ特別な苗木。
     * クリエイティブタブからのみ入手でき、侵食システムからは参照されないので自然には生えない。
     * {@code displayOrder()} には入れず(自動 BlockItem を作らせず)、
     * {@link ModItems#GIANT_OVERGROWN_SAPLING_ITEM} で常時エンチャント発光する BlockItem を個別登録している。
     */
    public static final DeferredBlock<Block> GIANT_OVERGROWN_SAPLING = BLOCKS.registerBlock(
            "giant_overgrown_sapling",
            properties -> new GiantOvergrownSaplingBlock(GIANT_OVERGROWN_TREE_GROWER, properties),
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_SAPLING)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /**
     * 「研究台」— mob のドロップ素材を解析して研究ポイントに変え、研究ツリーを開放するためのブロック。
     * {@code displayOrder()} には入れず(汚染ブロック扱いにしない)、{@link ModItems} で個別に
     * BlockItem を登録し、{@link ModCreativeTabs} で明示的にタブへ入れる。
     */
    public static final DeferredBlock<Block> RESEARCH_TABLE = BLOCKS.registerBlock(
            "research_table",
            com.yayuy.overgrownvegetation.block.ResearchTableBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.CRAFTING_TABLE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 「胞子除去装置」({@link com.yayuy.overgrownvegetation.block.SporeDeviceBlock} / {@code PURIFIER})。
     * 周囲の胞子濃度を下げ、もともとの濃度に応じて「胞子」を産出する。汚染ブロックではないので
     * {@code displayOrder()} には乗せず、{@link ModItems#SPORE_PURIFIER_ITEM} を個別登録。
     */
    public static final DeferredBlock<Block> SPORE_PURIFIER = BLOCKS.registerBlock(
            "spore_purifier",
            props -> new com.yayuy.overgrownvegetation.block.SporeDeviceBlock(
                    com.yayuy.overgrownvegetation.block.SporeDeviceKind.PURIFIER, props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    // モデルが 1 マスを埋めきらない(側面が 1〜2px へこみ、上は 2 マス目まで伸びる)ので、
                    // 隣のブロックの面を消させない。
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(com.yayuy.overgrownvegetation.block.SporeDeviceBlock.LIT) ? 7 : 0));

    /**
     * 「胞子拡散装置」({@code DIFFUSER})。周囲の胞子濃度を上げる。燃料はレッドストーンダスト + 「胞子」。
     * テクスチャは胞子除去装置と共用({@code block/spore_purifier})で、モデルだけ別。
     */
    public static final DeferredBlock<Block> SPORE_DIFFUSER = BLOCKS.registerBlock(
            "spore_diffuser",
            props -> new com.yayuy.overgrownvegetation.block.SporeDeviceBlock(
                    com.yayuy.overgrownvegetation.block.SporeDeviceKind.DIFFUSER, props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_MAGENTA)
                    // モデルが 1 マスを埋めきらない(下半分は 12x12 の柱、上は 2 マス目まで伸びる)ので、
                    // 隣のブロックの面を消させない。
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(com.yayuy.overgrownvegetation.block.SporeDeviceBlock.LIT) ? 7 : 0));

    /**
     * 「除染機」({@link com.yayuy.overgrownvegetation.block.DecontaminatorBlock})— 入れておいた武器・道具・防具の
     * 「胞子汚染」を時間をかけて落とす。汚染ブロックではないので {@code displayOrder()} には乗せず、
     * {@link ModItems#DECONTAMINATOR_ITEM} を個別登録。
     */
    /** 忌避灯(2026-09-19)。点いているあいだグループ1〜4 が近寄らない。ヴェロフィルの爆撃で蔦に覆われて止まる。 */
    public static final DeferredBlock<Block> REPELLENT_LAMP = BLOCKS.registerBlock(
            "repellent_lamp",
            com.yayuy.overgrownvegetation.block.RepellentLampBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN)
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(com.yayuy.overgrownvegetation.block.RepellentLampBlock.COVERED) ? 3 : 15));

    /** 有刺鉄線(2026-09-19)。当たり判定なし。踏み込んだ生き物を減速させて傷つける。 */
    public static final DeferredBlock<Block> BARBED_WIRE = BLOCKS.registerBlock(
            "barbed_wire",
            com.yayuy.overgrownvegetation.block.BarbedWireBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .noCollission()
                    .noOcclusion()
                    .strength(2.0F, 6.0F));

    /** フェーズ計測機(2026-09-19)。紙を入れるとフェーズ記録紙が出る。 */
    public static final DeferredBlock<Block> PHASE_RECORDER = BLOCKS.registerBlock(
            "phase_recorder",
            com.yayuy.overgrownvegetation.block.PhaseRecorderBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN)
                    .noOcclusion());

    /** 胞子警報器(2026-09-19)。フェーズ移行・レイド・小規模レイドの警告を近くのプレイヤーへ流す。 */
    public static final DeferredBlock<Block> SPORE_ALARM = BLOCKS.registerBlock(
            "spore_alarm",
            com.yayuy.overgrownvegetation.block.SporeAlarmBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_RED)
                    .noOcclusion());

    /**
     * 機関砲台(2026-09-21)。弾({@link ModItems#AUTOCANNON_ROUND})を入れておくと、半径 32 ブロックに
     * 入った敵を自動で撃つ。ヴェロフィル・落下中の胞子爆弾・攻城戦ボスの曲射を優先して落とす。
     * 見た目は丸ごと GeckoLib が描くので、ブロックモデルには面が無い。
     */
    public static final DeferredBlock<Block> GUN_TURRET = BLOCKS.registerBlock(
            "gun_turret",
            com.yayuy.overgrownvegetation.block.GunTurretBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.METAL)
                    .noOcclusion()
                    .strength(4.0F, 10.0F));

    // --- アルボラの巣の中身(2026-09-20)。掘った巣に nest/NestContents が置く。 ---

    /** アルボラの卵。孵化室の床に並ぶ。孵る個体は必ず亜種。 */
    public static final DeferredBlock<Block> ARBORA_EGG = BLOCKS.registerBlock(
            "arbora_egg",
            com.yayuy.overgrownvegetation.block.ArboraEggBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.TURTLE_EGG)
                    .mapColor(MapColor.TERRACOTTA_LIGHT_GREEN)
                    .noOcclusion()
                    .strength(0.5F));

    /** アルボラの貯蔵胞。貯蔵室に並ぶ 27 スロットの入れ物。巣の中で死んだ持ち物もここへ移る。 */
    public static final DeferredBlock<Block> ARBORA_STORAGE_POD = BLOCKS.registerBlock(
            "arbora_storage_pod",
            com.yayuy.overgrownvegetation.block.ArboraStoragePodBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.SHROOMLIGHT)
                    .mapColor(MapColor.COLOR_GREEN)
                    .noOcclusion()
                    .lightLevel(state -> 4)
                    .strength(2.0F));

    /** 胞子ノード。噴気室の中心。噴出孔を育て、世界の汚染速度を上げる。 */
    public static final DeferredBlock<Block> SPORE_NODE = BLOCKS.registerBlock(
            "spore_node",
            com.yayuy.overgrownvegetation.block.SporeNodeBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.SCULK_CATALYST)
                    .mapColor(MapColor.COLOR_PURPLE)
                    .noOcclusion()
                    .lightLevel(state -> 7)
                    .strength(3.0F));

    /** 胞子の噴出孔。地上へ抜けた煙突の口。半径 16 に胞子濃度の上乗せとデバフを配る。 */
    public static final DeferredBlock<Block> SPORE_VENT = BLOCKS.registerBlock(
            "spore_vent",
            com.yayuy.overgrownvegetation.block.SporeVentBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.SCULK_SHRIEKER)
                    .mapColor(MapColor.COLOR_PURPLE)
                    .noOcclusion()
                    .lightLevel(state -> 5)
                    .strength(2.5F));

    public static final DeferredBlock<Block> DECONTAMINATOR = BLOCKS.registerBlock(
            "decontaminator",
            com.yayuy.overgrownvegetation.block.DecontaminatorBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN)
                    // モデルが高さ 12px しかないので、上に乗るブロックの底面を消させない。
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(com.yayuy.overgrownvegetation.block.DecontaminatorBlock.LIT) ? 7 : 0));


    /**
     * 「砲台」({@link com.yayuy.overgrownvegetation.block.SiegeCannonBlock})— 攻城戦ボスを 1000 ブロック先から
     * 撃つ大砲。汚染ブロックではないので {@code displayOrder()} には乗せず、{@link ModItems#SIEGE_CANNON_ITEM} を
     * 個別登録。ブロックモデルは台座だけで、砲身はブロックエンティティレンダラーが描く(全体を覆わないので
     * {@code noOcclusion})。
     */
    public static final DeferredBlock<Block> SIEGE_CANNON = BLOCKS.registerBlock(
            "siege_cannon",
            com.yayuy.overgrownvegetation.block.SiegeCannonBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(5.0F, 12.0F)
                    .noOcclusion()
                    .dynamicShape()
                    // 旋回で形が変わるので、まれに 1 マスまるごと塞がっても窒息させない。
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false));

    /**
     * 砲台・遮蔽盾の<b>見えない部品ブロック</b>({@link com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock})。
     * モデルが 1 ブロックに収まらないので、本体を置いたときにその占める範囲へ自動で敷き詰め、
     * 当たり判定と右クリックを担当させる。アイテムは持たない(本体を壊すとまとめて消える)。
     */
    public static final DeferredBlock<Block> SIEGE_STRUCTURE_PART = BLOCKS.registerBlock(
            "siege_structure_part",
            com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(5.0F, 12.0F)
                    .noOcclusion()
                    .dynamicShape()
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)
                    .noLootTable());

    /**
     * 「遮蔽盾」({@link com.yayuy.overgrownvegetation.block.BeamShieldBlock})— 攻城戦ボスの花のビームを
     * 部分的に防ぐ、幅 3・高さ 5.5 ブロックの展開式の盾。たたんだ状態でハンドルを回して向きを決め、
     * ボタンで展開する。見た目は丸ごと BlockEntityRenderer(GeckoLib)が描く。
     */
    public static final DeferredBlock<Block> BEAM_SHIELD = BLOCKS.registerBlock(
            "beam_shield",
            com.yayuy.overgrownvegetation.block.BeamShieldBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(5.0F, 12.0F)
                    .noOcclusion()
                    .dynamicShape()
                    // 旋回で形が変わるので、まれに 1 マスまるごと塞がっても窒息させない。
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false));

    /**
     * 「過繫茂したドア」— 木のドア({@link BlockTags#WOODEN_DOORS})が汚染されると上下 2 マスまとめてこれになる。
     * 蔦・コケに覆われて確率で開かない/ハサミで剥がす/時間で再繁茂({@link com.yayuy.overgrownvegetation.block.OvergrownContainers})。
     * {@code displayOrder()} には乗せず {@link ModItems#OVERGROWN_DOOR_ITEM}(2 段ブロック用 BlockItem)を個別登録。
     * 見た目はバニラのオークのドアを緑がかった色で描き(BlockColor)、覆われ具合に応じたオーバーレイを重ねる。
     */
    public static final DeferredBlock<Block> OVERGROWN_DOOR = BLOCKS.registerBlock(
            "overgrown_door",
            com.yayuy.overgrownvegetation.block.OvergrownDoorBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_DOOR)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /**
     * 「過繫茂したチェスト」— チェストが汚染されると<b>中身を保ったまま</b>これになる(ラージチェストは相方も)。
     * 挙動は {@link #OVERGROWN_DOOR} と同じ。描画は専用の BlockEntityRenderer
     * ({@link com.yayuy.overgrownvegetation.client.OvergrownChestRenderer})、アイテムも同じ立体モデル。
     */
    public static final DeferredBlock<Block> OVERGROWN_CHEST = BLOCKS.registerBlock(
            "overgrown_chest",
            com.yayuy.overgrownvegetation.block.OvergrownChestBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.CHEST)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /** 「過繫茂した樽」— 樽が汚染されると中身を保ったままこれになる。挙動は {@link #OVERGROWN_DOOR} と同じ。 */
    public static final DeferredBlock<Block> OVERGROWN_BARREL = BLOCKS.registerBlock(
            "overgrown_barrel",
            com.yayuy.overgrownvegetation.block.OvergrownBarrelBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL)
                    .mapColor(MapColor.COLOR_GREEN)
                    .randomTicks());

    /** 板材など加工木材 → 汚染された板材。 */
    public static final DeferredBlock<Block> OVERGROWN_PLANKS = BLOCKS.registerSimpleBlock(
            "overgrown_planks",
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 木の階段({@link BlockTags#WOODEN_STAIRS}) → 苔に覆われた階段。
     * 形状を保ったまま {@link StairBlock} で再現する({@link ModBlocks#OVERGROWN_BLOCK} 等への
     * フォールバックだと階段の形が失われてしまうため専用に用意)。石の階段は {@link #OVERGROWN_STONE_STAIRS}。
     */
    public static final DeferredBlock<Block> OVERGROWN_STAIRS = BLOCKS.registerBlock(
            "overgrown_stairs",
            // baseState は足音/ブロック破壊パーティクルの参照用なので、
            // 自分自身の登録順に依存しないよう既存のバニラブロックを渡す。
            properties -> new StairBlock(Blocks.OAK_PLANKS.defaultBlockState(), properties),
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 木以外の階段(石・深層岩・ネザーレンガなど、{@link BlockTags#STAIRS} のうち
     * {@link BlockTags#WOODEN_STAIRS} でないもの) → 苔に覆われた石の階段。
     */
    public static final DeferredBlock<Block> OVERGROWN_STONE_STAIRS = BLOCKS.registerBlock(
            "overgrown_stone_stairs",
            properties -> new StairBlock(Blocks.COBBLESTONE.defaultBlockState(), properties),
            BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLESTONE_STAIRS)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 木のフェンス({@link BlockTags#FENCES}。ネザーレンガフェンスもここに含む)→ 苔に覆われたフェンス。
     * 形状を保ったまま {@link FenceBlock} で再現する。石壁は {@link #OVERGROWN_WALL}。
     *
     * <p>{@link FenceBlock#connectsTo} はバニラの {@code #minecraft:fences}/{@code #minecraft:wooden_fences}
     * タグを見て隣接判定するため、この2タグへの追加を(データパック側で)忘れると
     * フェンス同士が繋がって見えなくなる。
     */
    public static final DeferredBlock<Block> OVERGROWN_FENCE = BLOCKS.registerBlock(
            "overgrown_fence",
            FenceBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_FENCE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 石壁({@link BlockTags#WALLS}) → 苔に覆われた塀。形状を保ったまま {@link WallBlock} で再現する。
     *
     * <p>{@link WallBlock} も {@code #minecraft:walls} タグを見て隣接判定するため、
     * データパック側でこのタグへの追加が必要(フェンスと同じ注意点)。
     */
    public static final DeferredBlock<Block> OVERGROWN_WALL = BLOCKS.registerBlock(
            "overgrown_wall",
            WallBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLESTONE_WALL)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 草・花などの薄い植物 → 汚染された下草(当たり判定なし)。 */
    public static final DeferredBlock<Block> OVERGROWN_GROWTH = BLOCKS.registerSimpleBlock(
            "overgrown_growth",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .replaceable()
                    .noOcclusion()
                    .sound(SoundType.GRASS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    /**
     * 過繁茂した茂み(当たり判定なしの葉ブロック)。
     *
     * <p>{@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が
     * ブロックを汚染した際、{@link com.yayuy.overgrownvegetation.corruption.LeafClumpGrowth}
     * が確率でこのブロックのいびつな塊(きれいな立方体ではなく、外周が乱数で欠けた
     * 横倒しの楕円体、高さ最大2ブロック)を空気中に生成する。見た目は葉ブロックだが
     * 当たり判定は無く通り抜けられる。ただし中に入っている間は移動速度が下がる
     * ({@link OvergrownBushBlock}、{@code bushSlowMultiplier} で調整可)。
     */
    public static final DeferredBlock<Block> OVERGROWN_BUSH = BLOCKS.registerBlock(
            "overgrown_bush",
            OvergrownBushBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(0.2F)
                    .sound(SoundType.GRASS)
                    .noCollission()
                    .noOcclusion()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .ignitedByLava()
                    .pushReaction(PushReaction.DESTROY));

    /**
     * 対応する汚染ブロックが無い場合の汎用フォールバック。
     * 汚染元が特定できない(様々なブロックが化けうる)ので、音は石ブロック相当のままにする。
     */
    public static final DeferredBlock<Block> OVERGROWN_BLOCK = BLOCKS.registerSimpleBlock(
            "overgrown_block",
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(1.5F, 6.0F));

    // ------------------------------------------------------------------
    // 露出面から生える装飾植物(シダ・苔・蔦)
    //
    // ブロックが汚染されたとき、露出した面(上/側面/下)から
    // com.yayuy.overgrownvegetation.corruption.CorruptionSpreader が確率で生やす。
    // 当たり判定・生存条件は3つのクラス(上向き/側面/下向き)で共通のため、
    // 見た目違いのバリエーションはテクスチャとプロパティだけを変えて量産している。
    // ------------------------------------------------------------------

    /**
     * 巨大樹を構成するブロック({@link #GIANT_OVERGROWN_LOG} の幹・枝・根、形成層、髄、核、鉱石、
     * {@link #HARDENED_GIANT_TRUNK})共通の「この上には Mob を自然湧きさせない」判定
     * ({@code BlockBehaviour.Properties#isValidSpawn})。
     *
     * <p>巨大樹の枝は幅があって暗いので、そのままだとバニラの湧き処理にとって格好の湧き場所になり、
     * 樹冠の上や枝の上にゾンビ・スケルトンが並ぶ(2026-09-11 のユーザー報告)。MOD 側の湧き処理
     * ({@link com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder})は元から木を掘り下げて
     * 地面を探すので、残っていたのはバニラの自然湧きの経路だけだった。
     */
    private static boolean noGiantTreeSpawn(BlockState state, net.minecraft.world.level.BlockGetter level,
                                            net.minecraft.core.BlockPos pos,
                                            net.minecraft.world.entity.EntityType<?> type) {
        return false;
    }

    private static BlockBehaviour.Properties crossGrowthProperties(SoundType sound) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .noCollission()
                .instabreak()
                .replaceable()
                .noOcclusion()
                .sound(sound)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties wallGrowthProperties(SoundType sound) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .noCollission()
                .instabreak()
                .replaceable()
                .noOcclusion()
                .sound(sound)
                .pushReaction(PushReaction.DESTROY);
    }

    /** 胞子キノコ(上面)。 */
    public static final DeferredBlock<Block> OVERGROWN_SPORE_BLOOM = BLOCKS.registerBlock(
            "overgrown_spore_bloom", CorruptedGroundGrowthBlock::new, crossGrowthProperties(SoundType.FUNGUS));

    /** ゼンマイ(上面)。 */
    public static final DeferredBlock<Block> OVERGROWN_ZENMAI = BLOCKS.registerBlock(
            "overgrown_zenmai", CorruptedGroundGrowthBlock::new, crossGrowthProperties(SoundType.SWEET_BERRY_BUSH));

    /**
     * 「未発達の茂み」(上面)。他の下草と違って<b>実が付く</b>ので専用クラス
     * ({@link UndevelopedBushBlock})で、実の付き直しのために {@code randomTicks()} が要る。
     * 生えるのは苗木より少し低い確率({@code undevelopedBushChance}、{@code CorruptionSpreader#growUp})。
     */
    public static final DeferredBlock<Block> UNDEVELOPED_BUSH = BLOCKS.registerBlock(
            "undeveloped_bush", UndevelopedBushBlock::new,
            crossGrowthProperties(SoundType.SWEET_BERRY_BUSH).randomTicks());

    /** 這い蔦(側面)。他の側面装飾と違い、壁からせり出さず表面に薄く張り付く見た目。 */
    public static final DeferredBlock<Block> OVERGROWN_CREEPING_VINE = BLOCKS.registerBlock(
            "overgrown_creeping_vine", CorruptedFlushWallGrowthBlock::new, wallGrowthProperties(SoundType.VINE));

    /** 棚苔癬(側面)。 */
    public static final DeferredBlock<Block> OVERGROWN_SHELF_LICHEN = BLOCKS.registerBlock(
            "overgrown_shelf_lichen", CorruptedWallGrowthBlock::new, wallGrowthProperties(SoundType.GLOW_LICHEN));

    /** 棘蔦(側面)。 */
    public static final DeferredBlock<Block> OVERGROWN_THORN_VINE = BLOCKS.registerBlock(
            "overgrown_thorn_vine", CorruptedWallGrowthBlock::new, wallGrowthProperties(SoundType.SWEET_BERRY_BUSH));

    /** 泣き蔦(下面)。 */
    public static final DeferredBlock<Block> OVERGROWN_WEEPING_TENDRIL = BLOCKS.registerBlock(
            "overgrown_weeping_tendril", CorruptedHangingGrowthBlock::new, crossGrowthProperties(SoundType.WEEPING_VINES));

    /**
     * 「大きな未発達な果実」(下面)。過繫茂した木・巨大樹の葉の下に低確率でぶら下がる
     * ({@link com.yayuy.overgrownvegetation.worldgen.UndevelopedFruitPlacement})。
     *
     * <p>{@code displayOrder()} には入れない —— 自動登録の BlockItem は食べられない普通の
     * {@link net.minecraft.world.item.BlockItem} になってしまうため。
     * 代わりに {@link ModItems#LARGE_UNDEVELOPED_FRUIT_ITEM}(食料つき BlockItem)を個別登録する。
     */
    public static final DeferredBlock<Block> LARGE_UNDEVELOPED_FRUIT = BLOCKS.registerBlock(
            "large_undeveloped_fruit", LargeUndevelopedFruitBlock::new,
            // 他の垂れ植物と同じ手触り(当たり判定なし・素手で一撃)だが、
            // replaceable() は付けない —— 数が少ない実なので、他の成長や設置で
            // 上書きされて消えてしまわないようにする。
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.SWEET_BERRY_BUSH)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    /** 滴る苔(下面)。 */
    public static final DeferredBlock<Block> OVERGROWN_DRIP_MOSS = BLOCKS.registerBlock(
            "overgrown_drip_moss", CorruptedHangingGrowthBlock::new, crossGrowthProperties(SoundType.MOSS));

    /** 胞子の触手(下面)。 */
    public static final DeferredBlock<Block> OVERGROWN_SPORE_TENDRIL = BLOCKS.registerBlock(
            "overgrown_spore_tendril", CorruptedHangingGrowthBlock::new, crossGrowthProperties(SoundType.CAVE_VINES));

    /** 大きなシダ(上面・高さ2ブロック)。 */
    public static final DeferredBlock<Block> OVERGROWN_TALL_FERN = BLOCKS.registerBlock(
            "overgrown_tall_fern", CorruptedTallGrowthBlock::new, crossGrowthProperties(SoundType.GRASS));

    /** 胞子の穂をつけた葦(上面・高さ2ブロック)。 */
    public static final DeferredBlock<Block> OVERGROWN_SPORE_REED = BLOCKS.registerBlock(
            "overgrown_spore_reed", CorruptedTallGrowthBlock::new, crossGrowthProperties(SoundType.FUNGUS));

    /** 丸まったシダ(上面・geo.json 由来の立体モデルを焼き込み。向き・大きさは座標から決まるので XZ オフセットは付けない)。 */
    public static final DeferredBlock<Block> OVERGROWN_CURLED_FERN = BLOCKS.registerBlock(
            "overgrown_curled_fern", CorruptedGeoGrowthBlock::new,
            crossGrowthProperties(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.NONE));

    /**
     * 薄く広がる苔(多面。積もった薄い雪と同じくらいの薄さ)。バニラのヒカリゴケ / ツタと同じく
     * 上面・側面・下面が同じ1マスの中で同時に存在できる({@link OvergrownMossLayerBlock})。
     * 汚染時に露出面(上/側面/下)へ生え、他の下草と違い {@link com.yayuy.overgrownvegetation.corruption.CorruptionGrowth}
     * の抽選には乗らず {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} が個別に生やす。
     */
    public static final DeferredBlock<Block> OVERGROWN_MOSS_LAYER = BLOCKS.registerBlock(
            "overgrown_moss_layer", OvergrownMossLayerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.MOSS)
                    .pushReaction(PushReaction.DESTROY));

    // ------------------------------------------------------------------
    // 洞窟の中にだけ生える汚染植物(湿地・菌湿地帯の洞窟)
    //
    // 地表より caveDepthMargin 以上深い場所が汚染されたとき、通常の下草・蔦の代わりに
    // com.yayuy.overgrownvegetation.corruption.CaveGrowth が床・壁・天井のどの面にも生やす。
    // ------------------------------------------------------------------

    private static BlockBehaviour.Properties caveGrowthProperties(SoundType sound) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .noCollission()
                .instabreak()
                .replaceable()
                .noOcclusion()
                .sound(sound)
                .pushReaction(PushReaction.DESTROY);
    }

    /**
     * 微光の胞子嚢(床・壁・天井)。周りを照らす明るさはごく僅か({@code lightLevel} 2)で、代わりにモデル側を
     * 自己発光({@code neoforge_data} の {@code block_light} 7)にしてあるので、真っ暗な洞窟でも胞子嚢だけが
     * ぼんやり浮かんで見える(周囲は暗いまま)。2026-09-21 に lightLevel 7 から引き下げ。
     */
    public static final DeferredBlock<Block> CAVE_SPORE_SAC = BLOCKS.registerBlock(
            "cave_spore_sac", props -> new CaveSurfaceGrowthBlock(props, 0.0D, 7.0D),
            caveGrowthProperties(SoundType.SPORE_BLOSSOM).lightLevel(state -> 2));

    /** 苔の塊(床・壁・天井)。面に沿って数個ずつ固まって生える。 */
    public static final DeferredBlock<Block> CAVE_MOSS_CLUMP = BLOCKS.registerBlock(
            "cave_moss_clump", props -> new CaveSurfaceGrowthBlock(props, 0.0D, 3.0D),
            caveGrowthProperties(SoundType.MOSS));

    // ------------------------------------------------------------------
    // 水中で生える汚染植物(汚染された海草・昆布・スイレンなど)
    //
    // 陸上の装飾植物と違い、露出面が「空気」ではなく「水」だったときに
    // com.yayuy.overgrownvegetation.corruption.CorruptionSpreader が生やす。
    // どれも常に水を含んだ状態として振る舞い(getFluidState が水源を返す)、
    // 置き換えても気泡が残らない。崩れるときは空気ではなく水へ戻る。
    // 見た目(モデル/テクスチャ)は現状すべて仮。
    // ------------------------------------------------------------------

    private static BlockBehaviour.Properties waterCrossGrowthProperties(SoundType sound) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .noCollission()
                .instabreak()
                .replaceable()
                .noOcclusion()
                .sound(sound)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties lilyProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .instabreak()
                .noOcclusion()
                .sound(SoundType.WET_GRASS)
                .pushReaction(PushReaction.DESTROY);
    }

    /** 汚染された海草(上面・高さ1マス)。 */
    public static final DeferredBlock<Block> OVERGROWN_WATER_GRASS = BLOCKS.registerBlock(
            "overgrown_water_grass", CorruptedWaterGrowthBlock::new, waterCrossGrowthProperties(SoundType.WET_GRASS));

    /** 汚染された背高海草(上面・高さ2マス)。 */
    public static final DeferredBlock<Block> OVERGROWN_TALL_WATER_GRASS = BLOCKS.registerBlock(
            "overgrown_tall_water_grass", CorruptedTallWaterGrowthBlock::new, waterCrossGrowthProperties(SoundType.WET_GRASS));

    /** 汚染された藻柱(上面から昆布のように縦へ伸びる)。 */
    public static final DeferredBlock<Block> OVERGROWN_WATER_COLUMN = BLOCKS.registerBlock(
            "overgrown_water_column", CorruptedWaterColumnBlock::new, waterCrossGrowthProperties(SoundType.WET_GRASS));

    /**
     * 汚染された藻柱の花。藻柱({@link #OVERGROWN_WATER_COLUMN})を積んでいく途中で
     * {@code waterColumnFlowerChance} を引くと先端がこの花になり、その藻柱の成長はそこで止まる。
     */
    public static final DeferredBlock<Block> OVERGROWN_WATER_COLUMN_FLOWER = BLOCKS.registerBlock(
            "overgrown_water_column_flower", CorruptedWaterColumnFlowerBlock::new,
            waterCrossGrowthProperties(SoundType.WET_GRASS));

    /**
     * 藻。汚染された地形の水中の露出面(側面)を薄く覆う多面ブロック。薄い苔
     * ({@link #OVERGROWN_MOSS_LAYER})の水中版({@link OvergrownWaterMossLayerBlock}、常に waterlogged)。
     * 汚染時に側面へ {@code waterMossLayerChance}(既定 0.9)の抽選で生える。
     */
    public static final DeferredBlock<Block> OVERGROWN_WATER_MOSS_LAYER = BLOCKS.registerBlock(
            "overgrown_water_moss_layer", OvergrownWaterMossLayerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.WET_GRASS)
                    .pushReaction(PushReaction.DESTROY));

    /** 汚染された水中垂れ蔓(下面)。 */
    public static final DeferredBlock<Block> OVERGROWN_WATER_CEILING_GROWTH = BLOCKS.registerBlock(
            "overgrown_water_ceiling_growth", CorruptedWaterCeilingGrowthBlock::new, waterCrossGrowthProperties(SoundType.WET_GRASS));

    /** 汚染された睡蓮(水面)。 */
    public static final DeferredBlock<Block> OVERGROWN_LILY = BLOCKS.registerBlock(
            "overgrown_lily", CorruptedLilyBlock::new, lilyProperties());

    // ------------------------------------------------------------------
    // 光るツタ(グロウベリーと同じ仕様。天井から下へ伸び、実が光り、右クリックで収穫、骨粉で成長)
    //
    // バニラの cave_vines / cave_vines_plant をほぼ写経し、収穫物だけ独自アイテム
    // OVERGROWN_GLOWBERRY に差し替えたもの。CorruptionSpreader が下面(天井)を汚染した際、
    // 通常の垂れ植物の代わりに確率で先端ブロックを生やす(以降の伸長はランダムティック任せ)。
    // バニラの cave_vines と同じくブロックアイテムは持たず(displayOrder() に入れない)、
    // クリエイティブタブには実アイテムだけを出す。
    // ------------------------------------------------------------------

    /** 光るツタの先端(下へ成長する。{@code BERRIES=true} で光量14)。 */
    public static final DeferredBlock<Block> OVERGROWN_GLOW_VINES = BLOCKS.registerBlock(
            "overgrown_glow_vines", OvergrownGlowVinesBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.CAVE_VINES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel(OvergrownGlowVines.emission(14)));

    /** 光るツタの本体(先端が下へ進んだあとに残る部分)。 */
    public static final DeferredBlock<Block> OVERGROWN_GLOW_VINES_PLANT = BLOCKS.registerBlock(
            "overgrown_glow_vines_plant", OvergrownGlowVinesPlantBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.CAVE_VINES_PLANT)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel(OvergrownGlowVines.emission(14)));

    // ------------------------------------------------------------------
    // 木と木をつなぐ蔦(リアナ)
    //
    // 上の装飾植物と違い CorruptionSpreader の抽選には参加しない。木が生え終わった直後に
    // com.yayuy.overgrownvegetation.worldgen.TreeVineConnector が近くの別の木を探し、
    // 見つかれば始点側の幹に隣接する1マスへこのブロックを1個だけ置く
    // (詳しくは同クラスと ForcedTreeFeature を参照)。見た目(曲線)は完全に
    // CorruptedLianaBlockEntity + 専用レンダラーが毎フレーム描画するので、このブロック自体は
    // ゲーム世界には何も描画しない見えないマーカー(BaseEntityBlock 既定の RenderShape.INVISIBLE)。
    // replaceable() を付けないのは、蔦全体がこのマーカー1個に懸かっているため、他の成長や
    // 設置で不用意に上書きされて曲線ごと消えてしまうのを避けるため。
    //
    // 硬さはバニラの蔦(Blocks.VINE)と同じ 0.2F(=道具を問わずすぐ壊れる)。
    // 剣は data/minecraft/tags/block/sword_efficient.json でこのブロックを追加しているので
    // バニラの蔦と同じく 1.5 倍速で壊せる(SwordItem 側のツールルールが読むタグ)。
    // ハサミにも同種の優遇(バニラの蔦は 2 倍速)を付けたいところだが、そちらは
    // ShearsItem 側のツールルールが `Blocks.VINE` を直接名指ししているだけでタグ経由ではなく、
    // MOD 側から汎用に差し込む口が無い(NeoForge の IShearable もブロックには未対応、
    // ソースコード上に "TODO: Implement support for Block" と明記されている)。
    // そのためハサミは(素手や他の道具と同じく)通常速度になるが、硬さ自体が低いので
    // 実用上は誰で壊してもほぼ一瞬で終わる。
    // ------------------------------------------------------------------

    /** 木と木の間に張られる蔦のアンカー(始点・終点・垂れ具合を持つ見えないマーカー)。 */
    public static final DeferredBlock<Block> OVERGROWN_LIANA = BLOCKS.registerBlock(
            "overgrown_liana", CorruptedLianaBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .strength(0.2F)
                    .noOcclusion()
                    .sound(SoundType.VINE)
                    .pushReaction(PushReaction.DESTROY));

    // ------------------------------------------------------------------
    // 汚染死体ブロック(種族専用)
    //
    // 「胞子感染」デバフ(com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect)にかかった
    // Mob が力尽きたとき、対応する種族の死体が死んだ座標に置かれる
    // (com.yayuy.overgrownvegetation.effect.SpeciesCorpses が種族→ブロックの対応表を持つ。
    // 対応表に無い種族は死体の代わりに汚染シダがその場から生える)。blight_fern と同じく
    // CorruptedCorpseBlockEntity 経由で侵食を広げる(小さめの半径。詳細は同クラス参照)。
    //
    // 見た目は2系統:
    //  - 羊・豚・クリーパー・クモ: GeoCorruptedCorpseBlock。Blockbench(GeckoLib)製の立体モデル
    //    (geo/overgrown_(種族)_corpse.geo.json)を CorruptedCorpseRenderer(GeoBlockRenderer)で
    //    1個体ずつ描画。テクスチャは「同名の過繁茂した Mob」と共用(textures/entity/overgrown_(種族).png)。
    //    丸まったシダと同じく描画距離を24ブロックへ縮めてあり「近づかないと描画されない」。
    //    アイテム(手持ち/インベントリ)も GeoCorpseBlockItem + CorruptedCorpseItemRenderer で
    //    同じ立体モデルを描画する(item モデル JSON は builtin/entity、見た目調整は display 部分で)。
    //  - 牛: 通常のブロックモデル(models/block/overgrown_cow_corpse.json、Blockbench製の Java モデル)。
    //  - ゾンビ: 色だけ変えた仮テクスチャの単純な立方体(専用モデル未着手)。
    //
    // .noOcclusion() が必須: 死体モデルはブロックの16x16x16の範囲を完全には埋めない不定形の
    // 見た目になりうるので、既定(ofFullCopy(STONE) 由来の「不透過な立方体」扱い)のままだと
    // 隣接ブロック側が「隣は不透過な壁で完全に隠れる」と誤認してその面を描画省略し、実際には
    // 隙間だらけのこのブロック越しに地形が透けて見えてしまう。全種族に付けておく。
    // ------------------------------------------------------------------

    private static final BlockBehaviour.Properties CORPSE_PROPERTIES = BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(1.5F, 6.0F)
            .sound(SoundType.MOSS)
            .noOcclusion();

    /**
     * <b>リトプレウラの抜け殻</b>。脱皮した個体がその場に残す(ユーザー指定 2026-09-21)。
     * 殺さずに採れる素材なので、プレイヤーの利益が「生き物を殺すこと」だけに寄らない。
     * 見た目はモデルの {@code kara} だけを抜き出した geo を GeoBlockRenderer で描く。
     */
    public static final DeferredBlock<Block> LITHOPLEURA_HUSK = BLOCKS.registerBlock(
            "lithopleura_husk", LithopleuraHuskBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.PLANT)
                    .strength(0.4F)
                    .sound(SoundType.BAMBOO)
                    .noOcclusion());

    /** 牛の死体(種族専用モデル)。対応: {@link net.minecraft.world.entity.animal.Cow}(MushroomCow は対象外)。 */
    public static final DeferredBlock<Block> OVERGROWN_COW_CORPSE = BLOCKS.registerBlock(
            "overgrown_cow_corpse", CorruptedCorpseBlock::new, CORPSE_PROPERTIES);

    /** 豚の死体。対応: {@link net.minecraft.world.entity.animal.Pig}。GeckoLib 立体モデル。 */
    public static final DeferredBlock<Block> OVERGROWN_PIG_CORPSE = BLOCKS.registerBlock(
            "overgrown_pig_corpse", GeoCorruptedCorpseBlock::new, CORPSE_PROPERTIES);

    /** 羊の死体。対応: {@link net.minecraft.world.entity.animal.Sheep}。GeckoLib 立体モデル。 */
    public static final DeferredBlock<Block> OVERGROWN_SHEEP_CORPSE = BLOCKS.registerBlock(
            "overgrown_sheep_corpse", GeoCorruptedCorpseBlock::new, CORPSE_PROPERTIES);

    /** クリーパーの死体。対応: {@link net.minecraft.world.entity.monster.Creeper}。GeckoLib 立体モデル。 */
    public static final DeferredBlock<Block> OVERGROWN_CREEPER_CORPSE = BLOCKS.registerBlock(
            "overgrown_creeper_corpse", GeoCorruptedCorpseBlock::new, CORPSE_PROPERTIES);

    /** クモの死体。対応: {@link net.minecraft.world.entity.monster.Spider}(CaveSpider は対象外)。GeckoLib 立体モデル。 */
    public static final DeferredBlock<Block> OVERGROWN_SPIDER_CORPSE = BLOCKS.registerBlock(
            "overgrown_spider_corpse", GeoCorruptedCorpseBlock::new, CORPSE_PROPERTIES);

    /** クリエイティブタブ表示用の並び順。 */
    public static List<DeferredBlock<Block>> displayOrder() {
        return List.of(
                OVERGROWN_GRASS_BLOCK,
                OVERGROWN_DIRT,
                OVERGROWN_MUD,
                OVERGROWN_MUD_SLAB,
                OVERGROWN_STONE,
                OVERGROWN_DEEPSLATE,
                OVERGROWN_ANDESITE,
                OVERGROWN_DIORITE,
                OVERGROWN_GRANITE,
                OVERGROWN_COBBLESTONE,
                OVERGROWN_COBBLED_DEEPSLATE,
                OVERGROWN_DRIPSTONE_BLOCK,
                OVERGROWN_BRICKS,
                OVERGROWN_DIAMOND_ORE,
                OVERGROWN_DEEPSLATE_COAL_ORE,
                OVERGROWN_DEEPSLATE_COPPER_ORE,
                OVERGROWN_DEEPSLATE_DIAMOND_ORE,
                OVERGROWN_DEEPSLATE_EMERALD_ORE,
                OVERGROWN_DEEPSLATE_GOLD_ORE,
                OVERGROWN_DEEPSLATE_IRON_ORE,
                OVERGROWN_DEEPSLATE_LAPIS_ORE,
                OVERGROWN_DEEPSLATE_REDSTONE_ORE,
                OVERGROWN_COAL_ORE,
                OVERGROWN_COPPER_ORE,
                OVERGROWN_EMERALD_ORE,
                OVERGROWN_GOLD_ORE,
                OVERGROWN_IRON_ORE,
                OVERGROWN_LAPIS_ORE,
                OVERGROWN_REDSTONE_ORE,
                OVERGROWN_SAND,
                OVERGROWN_GRAVEL,
                OVERGROWN_LOG,
                OVERGROWN_WOOD,
                GIANT_OVERGROWN_LOG,
                GIANT_OVERGROWN_CAMBIUM,
                HARDENED_GIANT_TRUNK,
                LEY_VEIN,
                ORGAN_NODE,
                ORGAN_VINE,
                SPORE_GLAND,
                CHLOROPHYLLITE_ORE,
                HARDENED_SAP_CRYSTAL_ORE,
                OVERGROWN_LEAVES,
                OVERGROWN_SWAMP_LOG,
                OVERGROWN_SWAMP_WOOD,
                OVERGROWN_SWAMP_LEAVES,
                FUNGAL_PILLAR,
                FUNGAL_CAP,
                GLOWING_MYCELIUM,
                OVERGROWN_BUSH,
                OVERGROWN_SAPLING,
                OVERGROWN_PLANKS,
                OVERGROWN_STAIRS,
                OVERGROWN_STONE_STAIRS,
                OVERGROWN_FENCE,
                OVERGROWN_WALL,
                OVERGROWN_GROWTH,
                OVERGROWN_BLOCK,
                OVERGROWN_SPORE_BLOOM,
                OVERGROWN_ZENMAI,
                UNDEVELOPED_BUSH,
                OVERGROWN_CREEPING_VINE,
                OVERGROWN_SHELF_LICHEN,
                OVERGROWN_THORN_VINE,
                OVERGROWN_WEEPING_TENDRIL,
                OVERGROWN_DRIP_MOSS,
                OVERGROWN_SPORE_TENDRIL,
                OVERGROWN_TALL_FERN,
                OVERGROWN_SPORE_REED,
                OVERGROWN_CURLED_FERN,
                OVERGROWN_MOSS_LAYER,
                CAVE_SPORE_SAC,
                CAVE_MOSS_CLUMP,
                OVERGROWN_WATER_GRASS,
                OVERGROWN_TALL_WATER_GRASS,
                OVERGROWN_WATER_COLUMN,
                OVERGROWN_WATER_COLUMN_FLOWER,
                OVERGROWN_WATER_MOSS_LAYER,
                OVERGROWN_WATER_CEILING_GROWTH,
                OVERGROWN_LILY,
                OVERGROWN_LIANA,
                OVERGROWN_COW_CORPSE,
                OVERGROWN_PIG_CORPSE,
                OVERGROWN_SHEEP_CORPSE,
                OVERGROWN_CREEPER_CORPSE,
                OVERGROWN_SPIDER_CORPSE,
                LITHOPLEURA_HUSK);
    }

    /**
     * 汚染時に露出面から自動で生える装飾植物一覧({@link com.yayuy.overgrownvegetation.corruption.CorruptionGrowth} 用)。
     * 上面に生える、高さ1ブロックの植物(GeckoLib立体モデルを含む)。
     */
    public static List<DeferredBlock<Block>> topGrowthBlocks() {
        return List.of(OVERGROWN_SPORE_BLOOM, OVERGROWN_ZENMAI, OVERGROWN_CURLED_FERN);
    }

    public static List<DeferredBlock<Block>> sideGrowthBlocks() {
        return List.of(OVERGROWN_CREEPING_VINE, OVERGROWN_SHELF_LICHEN, OVERGROWN_THORN_VINE);
    }

    public static List<DeferredBlock<Block>> hangingGrowthBlocks() {
        return List.of(OVERGROWN_WEEPING_TENDRIL, OVERGROWN_DRIP_MOSS, OVERGROWN_SPORE_TENDRIL);
    }

    /** 上面に生える、高さ2ブロックの植物一覧。 */
    public static List<DeferredBlock<Block>> tallGrowthBlocks() {
        return List.of(OVERGROWN_TALL_FERN, OVERGROWN_SPORE_REED);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    private ModBlocks() {
    }
}
