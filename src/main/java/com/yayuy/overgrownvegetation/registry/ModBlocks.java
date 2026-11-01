package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedFlushWallGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedGeoGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedGroundGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedHangingGrowthBlock;
import com.yayuy.overgrownvegetation.block.LargeUndevelopedFruitBlock;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlock;
import com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock;
import com.yayuy.overgrownvegetation.block.CorruptedTallGrowthBlock;
import com.yayuy.overgrownvegetation.block.CorruptedWallGrowthBlock;
import com.yayuy.overgrownvegetation.block.InertBlock;
import com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock;
import com.yayuy.overgrownvegetation.block.OvergrownBushBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVinesBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVinesPlantBlock;
import com.yayuy.overgrownvegetation.block.OvergrownDirtBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGrassBlock;
import com.yayuy.overgrownvegetation.block.OvergrownMossLayerBlock;
import com.yayuy.overgrownvegetation.block.UndevelopedBushBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
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
    // 石系の色付きバリエーション(深層岩の丸石・鉱石など)
    // 花崗岩・閃緑岩・安山岩・丸石・鍾乳石ブロック(と砂・砂利)の専用ブロックは 2026-10-31 に削除(ユーザー指定)。
    // 元のブロックは覆い(OvergrownLayerData の記録)になる。
    //
    // OVERGROWN_STONE / OVERGROWN_DEEPSLATE の汎用バケツにまとめず、
    // 元テクスチャの色味を保ったまま moss_template を乗算合成した専用テクスチャを持つ。
    // ------------------------------------------------------------------

    /** 深層岩の丸石 → 苔に覆われた深層岩の丸石。 */
    public static final DeferredBlock<Block> OVERGROWN_COBBLED_DEEPSLATE = BLOCKS.registerSimpleBlock(
            "overgrown_cobbled_deepslate",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COBBLED_DEEPSLATE)
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
    public static final DeferredBlock<Block> OVERGROWN_DIAMOND_ORE = BLOCKS.registerBlock(
            "overgrown_diamond_ore",
            props -> new DropExperienceBlock(UniformInt.of(3, 7), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIAMOND_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の石炭鉱石 → 苔に覆われた深層岩の石炭鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_COAL_ORE = BLOCKS.registerBlock(
            "overgrown_deepslate_coal_ore",
            props -> new DropExperienceBlock(UniformInt.of(0, 2), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_COAL_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩の銅鉱石 → 苔に覆われた深層岩の銅鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_COPPER_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_deepslate_copper_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_COPPER_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩のダイヤモンド鉱石 → 苔に覆われた深層岩のダイヤモンド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_DIAMOND_ORE = BLOCKS.registerBlock(
            "overgrown_deepslate_diamond_ore",
            props -> new DropExperienceBlock(UniformInt.of(3, 7), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_DIAMOND_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 深層岩のエメラルド鉱石 → 苔に覆われた深層岩のエメラルド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_EMERALD_ORE = BLOCKS.registerBlock(
            "overgrown_deepslate_emerald_ore",
            props -> new DropExperienceBlock(UniformInt.of(3, 7), props),
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
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_LAPIS_ORE = BLOCKS.registerBlock(
            "overgrown_deepslate_lapis_ore",
            props -> new DropExperienceBlock(UniformInt.of(2, 5), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_LAPIS_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 深層岩のレッドストーン鉱石 → 苔に覆われた深層岩のレッドストーン鉱石。
     *
     * <p>元ブロックは「起きたら光る」ための LIT プロパティ付き発光ロジックを持つが、
     * このブロックはそのプロパティを持たない単純な Block なので、
     * ofFullCopy で引き継がれる発光関数を明示的に上書き(常時消灯)しないと
     * ブロック状態生成時に IllegalArgumentException で起動クラッシュする。
     *
     * <p>同じく写ってくる「ランダムティックを受ける」印は {@link InertBlock} で外す
     * (randomTick は空なので何も変わらない。2026-09-24 軽量化、報告 2022)。
     */
    public static final DeferredBlock<Block> OVERGROWN_DEEPSLATE_REDSTONE_ORE = BLOCKS.registerBlock(
            "overgrown_deepslate_redstone_ore",
            props -> new InertBlock(props, UniformInt.of(1, 5)),
            BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE_REDSTONE_ORE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel((BlockState state) -> 0));

    // ------------------------------------------------------------------
    // 通常(非深層岩)の鉱石ブロック
    // ------------------------------------------------------------------

    /** 石炭鉱石 → 苔に覆われた石炭鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_COAL_ORE = BLOCKS.registerBlock(
            "overgrown_coal_ore",
            props -> new DropExperienceBlock(UniformInt.of(0, 2), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.COAL_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** 銅鉱石 → 苔に覆われた銅鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_COPPER_ORE = BLOCKS.registerSimpleBlock(
            "overgrown_copper_ore",
            BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /** エメラルド鉱石 → 苔に覆われたエメラルド鉱石。 */
    public static final DeferredBlock<Block> OVERGROWN_EMERALD_ORE = BLOCKS.registerBlock(
            "overgrown_emerald_ore",
            props -> new DropExperienceBlock(UniformInt.of(3, 7), props),
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
    public static final DeferredBlock<Block> OVERGROWN_LAPIS_ORE = BLOCKS.registerBlock(
            "overgrown_lapis_ore",
            props -> new DropExperienceBlock(UniformInt.of(2, 5), props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.LAPIS_ORE)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * レッドストーン鉱石 → 苔に覆われたレッドストーン鉱石。
     *
     * <p>OVERGROWN_DEEPSLATE_REDSTONE_ORE と同じ理由で、
     * LIT プロパティに依存する発光ロジックを明示的に上書きする必要がある。
     * ランダムティックの印も同じ理由で {@link InertBlock} が外す(2026-09-24 軽量化、報告 2022)。
     */
    public static final DeferredBlock<Block> OVERGROWN_REDSTONE_ORE = BLOCKS.registerBlock(
            "overgrown_redstone_ore",
            props -> new InertBlock(props, UniformInt.of(1, 5)),
            BlockBehaviour.Properties.ofFullCopy(Blocks.REDSTONE_ORE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel((BlockState state) -> 0));

    /**
     * ネザーラック・真紅/歪んだナイリウム → 過繁茂しているネザーラック(2026-10-02 ユーザー指定:
     * ナイリウムのようにネザーラックに草が生えたもの)。ポータルを越えてネザーへ広がった侵食の地面。
     */
    public static final DeferredBlock<Block> OVERGROWN_NETHERRACK = BLOCKS.registerSimpleBlock(
            "overgrown_netherrack",
            BlockBehaviour.Properties.ofFullCopy(Blocks.NETHERRACK)
                    .mapColor(MapColor.COLOR_GREEN)
                    .sound(SoundType.NYLIUM));

    /**
     * ソウルサンド → 植物の生えたソウルサンド(2026-10-02 ユーザー指定)。ソウルサンドのような減速は無く、普通のブロックと同じ扱い
     * (ユーザー指定: 足が遅くならない・ソウルスピードやソウルファイアの対象にもしない)。形も普通の立方体。
     */
    public static final DeferredBlock<Block> OVERGROWN_SOUL_SAND = BLOCKS.registerSimpleBlock(
            "overgrown_soul_sand",
            BlockBehaviour.Properties.ofFullCopy(Blocks.SOUL_SAND)
                    .mapColor(MapColor.COLOR_GREEN)
                    .speedFactor(1.0F));

    /**
     * エンドストーン → 植物の生えたエンドストーン(2026-10-02)。森がエンドへ着いたときに本島の地面になる({@code corruption/EndOvergrowth})。
     * エンダードラゴンが削れないよう {@code #minecraft:dragon_immune} に入れてある(エンドストーンと同じ)。
     */
    public static final DeferredBlock<Block> OVERGROWN_END_STONE = BLOCKS.registerSimpleBlock(
            "overgrown_end_stone",
            BlockBehaviour.Properties.ofFullCopy(Blocks.END_STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .sound(SoundType.NYLIUM));

    /** ソウルソイル → 植物の生えたソウルソイル(2026-10-02 ユーザー指定)。 */
    public static final DeferredBlock<Block> OVERGROWN_SOUL_SOIL = BLOCKS.registerSimpleBlock(
            "overgrown_soul_soil",
            BlockBehaviour.Properties.ofFullCopy(Blocks.SOUL_SOIL)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 汚染されたエンドポータルの枠(2026-10-02 ユーザー指定)。目の代わりに光る植物の腫瘍がはまっていて、
     * ポータルを無理やり開いている。壊せない(元の枠と同じ)。手に入らないので BlockItem は作らない
     * ({@code displayOrder()} に入れない)。
     */
    public static final DeferredBlock<Block> OVERGROWN_END_PORTAL_FRAME = BLOCKS.registerBlock(
            "overgrown_end_portal_frame",
            com.yayuy.overgrownvegetation.block.OvergrownEndPortalFrameBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.END_PORTAL_FRAME)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel(state -> 0)); // 光は模型の面の発光だけ(2026-10-23、光の更新を増やさない)

    /** 原木・樹皮を剥いだ原木など → 寄生された幹。 */
    public static final DeferredBlock<Block> OVERGROWN_LOG = BLOCKS.registerBlock(
            "overgrown_log",
            com.yayuy.overgrownvegetation.block.OvergrownLogBlock::new, // 2026-10-31 から幹の苔のスカート・木の洞の変種を持つ
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_GREEN));

    /**
     * 枝の継ぎ目(2026-11-01、{@link com.yayuy.overgrownvegetation.block.BranchJointBlock})。柵のように隣の原木へ腕を出し、
     * 階段状の枝の隙間埋めと枝先の先細りに使う。{@code #logs} に入れて木の一部として辿られる(mod の木なので {@code SwampTreeFall} では名指しで外す)。
     */
    public static final DeferredBlock<Block> OVERGROWN_BRANCH = BLOCKS.registerBlock(
            "overgrown_branch",
            com.yayuy.overgrownvegetation.block.BranchJointBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.COLOR_GREEN)
                    .noOcclusion()
                    .isValidSpawn((state, getter, pos, type) -> false));

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
     * <b>根付いた萌芽体</b>(2026-10-23 胞子の場と風 第 2 段 F)。萌芽体が湿った所で根を下ろした姿。胞子を噴き、まわりへ汚染の根を伸ばす。
     * 柔らかい(素手ですぐ壊せる)。壊すとその升の萌芽体の数が 1 減る。
     */
    public static final DeferredBlock<Block> BLIGHT_FERN_ROOTED = BLOCKS.registerBlock(
            "blight_fern_rooted",
            com.yayuy.overgrownvegetation.block.BlightFernRootedBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.WET_GRASS)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));

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
     * 葉 → 汚染された葉。
     *
     * <p>バニラの葉から写ってくる「ランダムティックを受ける」印は {@link InertBlock} で外す(素の Block なので
     * randomTick は空。巨大樹の樹冠のセクションが丸ごと抽選対象になっていた。2026-09-24 軽量化、報告 2022)。
     */
    public static final DeferredBlock<Block> OVERGROWN_LEAVES = BLOCKS.registerBlock(
            "overgrown_leaves",
            com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn((state, getter, pos, type) -> false));

    /**
     * 封印木(シギラリア)の原木(2026-11-01)。石炭紀の小葉類の木で、太い幹が 3〜4 本の腕に分かれる(1881 年の復元図の右 = ユーザー指定)。
     * 生やすのは {@link com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature}。スカート・洞の変種は持たない。
     * {@code #logs} に入れるので、木 1 本を辿る所({@code SwampTreeFall} など)では mod の原木として名指しで外す。
     */
    public static final DeferredBlock<Block> SIGILLARIA_LOG = BLOCKS.registerBlock(
            "sigillaria_log",
            RotatedPillarBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.TERRACOTTA_GREEN));

    /**
     * 封印木の枝(枝の継ぎ目 {@link com.yayuy.overgrownvegetation.block.BranchJointBlock}。2026-11-01 ユーザー決定「今の木と封印木の両方に使う」)。
     * 腕の階段の隙間埋めと腕先の先細りに使う。絵は封印木の幹の側面を全部の面に。
     */
    public static final DeferredBlock<Block> SIGILLARIA_BRANCH = BLOCKS.registerBlock(
            "sigillaria_branch",
            com.yayuy.overgrownvegetation.block.BranchJointBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .noOcclusion()
                    .isValidSpawn((state, getter, pos, type) -> false));

    /** 封印木の葉の芯(密な束)。中身は今の葉と同じ {@link com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock}。 */
    public static final DeferredBlock<Block> SIGILLARIA_LEAVES = BLOCKS.registerBlock(
            "sigillaria_leaves",
            com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn((state, getter, pos, type) -> false));

    /** 封印木の葉の外側 1 層(試作 11-01 昼: 芯から生える向き付きの板 {@link com.yayuy.overgrownvegetation.block.SigillariaFringeBlock})。 */
    public static final DeferredBlock<Block> SIGILLARIA_LEAVES_FRINGE = BLOCKS.registerBlock(
            "sigillaria_leaves_fringe",
            com.yayuy.overgrownvegetation.block.SigillariaFringeBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission().noOcclusion()
                    .isValidSpawn((state, getter, pos, type) -> false));

    /**
     * 封印木の球果(垂れる胞子嚢。案 7)。原木の側面に付き、ときどき胞子を吐く({@link com.yayuy.overgrownvegetation.block.SigillariaConeBlock})。
     * 当たり判定なし・すぐ壊せる。
     */
    public static final DeferredBlock<Block> SIGILLARIA_CONE = BLOCKS.registerBlock(
            "sigillaria_cone",
            com.yayuy.overgrownvegetation.block.SigillariaConeBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .noCollission()
                    .strength(0.2F)
                    .noOcclusion()
                    .randomTicks()
                    .sound(SoundType.SWEET_BERRY_BUSH)
                    .pushReaction(PushReaction.DESTROY));

    /**
     * 木生シダ(プサロニウス)の幹(2026-11-01)。根のマントで木材ではないので {@code #logs} に入れない({@link com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock})。
     * 生やすのは {@link com.yayuy.overgrownvegetation.worldgen.TreeFernFeature}。
     */
    public static final DeferredBlock<Block> TREE_FERN_TRUNK = BLOCKS.registerBlock(
            "tree_fern_trunk",
            com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .sound(SoundType.ROOTED_DIRT));

    /** 木生シダの葉の芯(密な羽状の葉の敷物)。 */
    public static final DeferredBlock<Block> TREE_FERN_LEAVES = BLOCKS.registerBlock(
            "tree_fern_leaves",
            com.yayuy.overgrownvegetation.block.TreeFernLeavesBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .isValidSpawn((state, getter, pos, type) -> false));

    /** 木生シダの葉の板(封印木と同じ向き付きの板。横 = 羽状の葉・下 = 垂れる古い葉・頂の上 = ゼンマイ)。 */
    public static final DeferredBlock<Block> TREE_FERN_FROND = BLOCKS.registerBlock(
            "tree_fern_frond",
            com.yayuy.overgrownvegetation.block.TreeFernFrondBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES)
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission().noOcclusion()
                    .isValidSpawn((state, getter, pos, type) -> false));

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

    /**
     * 板材など加工木材 → 覆われた板材(2026-11-01 に「過繁茂している板材」から改名。id はそのまま)。置き換えで得るもので、レシピは無い。
     * 絵は 3 変種(blockstate で抽選)。
     */
    public static final DeferredBlock<Block> OVERGROWN_PLANKS = BLOCKS.registerSimpleBlock(
            "overgrown_planks",
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.COLOR_GREEN));

    /*
     * 木ごとの板材(2026-11-01 ユーザー決定「木の名前と板材の作り分け」)。人の手で加工した材なので #corrupted には入れない。
     * 性質・燃え方はオークの板材と同じ。階段・ハーフ・フェンス・ドアはまだ作らない(ユーザー決定: 板材だけ先に)。
     */

    /** コルダイテス(過繁茂した木 overgrown_log / overgrown_wood)の板材。 */
    public static final DeferredBlock<Block> CORDAITES_PLANKS = BLOCKS.registerBlock(
            "cordaites_planks",
            com.yayuy.overgrownvegetation.block.WoodPlanksBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.TERRACOTTA_GREEN));

    /** 封印木(sigillaria_log)の板材。絵はユーザーの仮案のまま。 */
    public static final DeferredBlock<Block> SIGILLARIA_PLANKS = BLOCKS.registerBlock(
            "sigillaria_planks",
            com.yayuy.overgrownvegetation.block.WoodPlanksBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.TERRACOTTA_GREEN));

    /** 巨大樹の外皮(giant_overgrown_log)の板材。 */
    public static final DeferredBlock<Block> GIANT_BARK_PLANKS = BLOCKS.registerBlock(
            "giant_bark_planks",
            com.yayuy.overgrownvegetation.block.WoodPlanksBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.TERRACOTTA_BROWN));

    /** 巨大樹の板材(形成層 giant_overgrown_cambium から)。 */
    public static final DeferredBlock<Block> GIANT_PLANKS = BLOCKS.registerBlock(
            "giant_planks",
            com.yayuy.overgrownvegetation.block.WoodPlanksBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS)
                    .mapColor(MapColor.WOOD));

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
     * 過繁茂したハーフブロック(2026-10-05、ユーザー要望: 過繁茂している板材からハーフ・階段・扉を作れるように)。
     * 見た目は過繁茂している板材と同じテクスチャ。汚染の対応表には載せていない(木のハーフは今までどおり苔で覆う)。
     */
    public static final DeferredBlock<Block> OVERGROWN_SLAB = BLOCKS.registerBlock(
            "overgrown_slab",
            SlabBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_SLAB)
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

    /**
     * 原木から化けた過繁茂しているブロック(2026-10-05 夜、{@link com.yayuy.overgrownvegetation.block.OvergrownTrunkBlock})。見た目は
     * 過繁茂しているブロックと同じで、{@code #logs} に入っているので木の一部として片付く(幹の途中に残って宙に浮かない)。アイテムは無い。
     * 性質は過繁茂しているブロックの写しなので、その登録より後ろに置く。2026-10-30 に退避した covered/CoveredBlocks から移した。
     */
    public static final DeferredBlock<Block> OVERGROWN_TRUNK_BLOCK = BLOCKS.register("overgrown_trunk_block",
            () -> new com.yayuy.overgrownvegetation.block.OvergrownTrunkBlock(BlockBehaviour.Properties.ofFullCopy(OVERGROWN_BLOCK.get())));

    /**
     * 火の消えた松明(2026-10-05、{@link com.yayuy.overgrownvegetation.block.ExtinguishedTorchBlock})。汚染が松明に届くとこれになる。
     * 光らない・炎も煙も出ない。アイテムは無い(壊すと棒)。壁の松明は向きを引き継ぐ。2026-10-30 に covered/CoveredBlocks から移した。
     */
    public static final DeferredBlock<Block> EXTINGUISHED_TORCH = BLOCKS.register("extinguished_torch",
            () -> new com.yayuy.overgrownvegetation.block.ExtinguishedTorchBlock(
                    BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH).lightLevel(state -> 0)));
    public static final DeferredBlock<Block> EXTINGUISHED_WALL_TORCH = BLOCKS.register("extinguished_wall_torch",
            () -> new com.yayuy.overgrownvegetation.block.ExtinguishedTorchBlock.Wall(
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH).lightLevel(state -> 0)));

    /** 過繁茂しているブロックのハーフブロック(2026-10-05、ユーザー要望)。硬さ・道具は過繁茂しているブロックと同じ。 */
    public static final DeferredBlock<Block> OVERGROWN_BLOCK_SLAB = BLOCKS.registerBlock(
            "overgrown_block_slab",
            SlabBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_SLAB)
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

    // 側面・底面に生えるもの(2026-11-01)。どれも木の生成の後に worldgen.TreeSideGrowth が置く(S1 は汚染の広がりでも幹の面に生える)。

    /** 幹に巻き付く種子シダ(S1)。幹の面に貼り付く立体の模型({@link com.yayuy.overgrownvegetation.block.ClimbingSeedfernBlock})。 */
    public static final DeferredBlock<Block> CLIMBING_SEEDFERN = BLOCKS.registerBlock(
            "climbing_seedfern", com.yayuy.overgrownvegetation.block.ClimbingSeedfernBlock::new,
            wallGrowthProperties(SoundType.VINE));

    /** 気根のカーテン(S3)。林冠・腕の縁から 2〜6 段垂れる({@link com.yayuy.overgrownvegetation.block.AerialRootBlock})。 */
    public static final DeferredBlock<Block> AERIAL_ROOT = BLOCKS.registerBlock(
            "aerial_root", com.yayuy.overgrownvegetation.block.AerialRootBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .noCollission()
                    .strength(0.2F)
                    .noOcclusion()
                    .sound(SoundType.HANGING_ROOTS)
                    .pushReaction(PushReaction.DESTROY));

    /** 根付いた気根の柱(S3)。地面に届いた気根の先。満ちた 1 ブロック({@link com.yayuy.overgrownvegetation.block.AerialRootPillarBlock})。 */
    public static final DeferredBlock<Block> AERIAL_ROOT_PILLAR = BLOCKS.registerBlock(
            "aerial_root_pillar", com.yayuy.overgrownvegetation.block.AerialRootPillarBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(1.0F)
                    .noOcclusion()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .sound(SoundType.ROOTS));

    /** ひげ地衣(S4)。腕・枝の下面から 1〜2 段({@link com.yayuy.overgrownvegetation.block.BeardLichenBlock})。 */
    public static final DeferredBlock<Block> BEARD_LICHEN = BLOCKS.registerBlock(
            "beard_lichen", com.yayuy.overgrownvegetation.block.BeardLichenBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.MOSS)
                    .pushReaction(PushReaction.DESTROY));

    /** 胞子嚢の袋(S6)。小 → 大へ育つ({@link com.yayuy.overgrownvegetation.block.SporeSacBlock})。 */
    public static final DeferredBlock<Block> HANGING_SPORE_SAC = BLOCKS.registerBlock(
            "hanging_spore_sac", com.yayuy.overgrownvegetation.block.SporeSacBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .randomTicks()
                    .sound(SoundType.FUNGUS)
                    .pushReaction(PushReaction.DESTROY));

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
    // 林床(2026-11-01): 小葉類の下生え・泥炭・倒木と棚状の菌・メドゥロサ。生やすのは corruption/FloorGrowth
    // ------------------------------------------------------------------

    /** 小葉類の低い株(イワヒバ)。敷物付きの変種あり({@link com.yayuy.overgrownvegetation.block.LycopsidPlantBlock})。ずらしは模型の変種で。 */
    public static final DeferredBlock<Block> SELAGINELLA = BLOCKS.registerBlock(
            "selaginella", com.yayuy.overgrownvegetation.block.LycopsidPlantBlock::new,
            crossGrowthProperties(SoundType.MOSS).offsetType(BlockBehaviour.OffsetType.NONE));

    /** 小葉類の低い株(ヒカゲノカズラ。瓶洗いのブラシのような茎)。 */
    public static final DeferredBlock<Block> CLUBMOSS = BLOCKS.registerBlock(
            "clubmoss", com.yayuy.overgrownvegetation.block.LycopsidPlantBlock::new,
            crossGrowthProperties(SoundType.MOSS).offsetType(BlockBehaviour.OffsetType.NONE));

    /** 小葉類の敷物(高さ 1/16 の板。向きは blockstate の乱数)。 */
    public static final DeferredBlock<Block> LYCOPSID_CARPET = BLOCKS.registerBlock(
            "lycopsid_carpet", com.yayuy.overgrownvegetation.block.FloorCarpetBlock::new,
            crossGrowthProperties(SoundType.MOSS_CARPET).offsetType(BlockBehaviour.OffsetType.NONE));

    /** 立ち上がる小葉類(高さ 2 ブロック。二又の茎の先に胞子嚢穂)。 */
    public static final DeferredBlock<Block> LYCOPSID_UPRIGHT = BLOCKS.registerBlock(
            "lycopsid_upright", CorruptedTallGrowthBlock::new, crossGrowthProperties(SoundType.MOSS));

    /** 泥炭(石炭紀の森の黒い土)。低地・水辺に斑に出る。 */
    public static final DeferredBlock<Block> PEAT = BLOCKS.registerBlock(
            "peat", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.6F).sound(SoundType.MUD));

    /** 泥炭の水たまりの底(水の色はバイオームのまま、底の絵で暗く見せる)。 */
    public static final DeferredBlock<Block> PEAT_POOL_BOTTOM = BLOCKS.registerBlock(
            "peat_pool_bottom", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.6F).sound(SoundType.MUD));

    /** 泥炭の上の小さな芽。 */
    public static final DeferredBlock<Block> PEAT_SPROUTS = BLOCKS.registerBlock(
            "peat_sprouts", CorruptedGroundGrowthBlock::new, crossGrowthProperties(SoundType.NETHER_SPROUTS));

    /** 泥炭の上に散る倒れた茎の屑(敷く板)。 */
    public static final DeferredBlock<Block> PEAT_LITTER = BLOCKS.registerBlock(
            "peat_litter", com.yayuy.overgrownvegetation.block.FloorCarpetBlock::new,
            crossGrowthProperties(SoundType.MOSS_CARPET).offsetType(BlockBehaviour.OffsetType.NONE));

    /** 倒木(コルダイテスの幹。面ごとに絵が違う。{@code #logs} に入れない)。 */
    public static final DeferredBlock<Block> FALLEN_LOG = BLOCKS.registerBlock(
            "fallen_log", com.yayuy.overgrownvegetation.block.FallenLogBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_GREEN).strength(2.0F).sound(SoundType.WOOD));

    /** 倒木の側面に付く立体の棚状の菌(ユーザーの模型)。 */
    public static final DeferredBlock<Block> BRACKET_FUNGUS = BLOCKS.registerBlock(
            "bracket_fungus", com.yayuy.overgrownvegetation.block.BracketFungusBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_YELLOW).strength(0.3F).noOcclusion()
                    .sound(SoundType.FUNGUS).pushReaction(PushReaction.DESTROY));

    /** メドゥロサの幹(6 px 角の棒。flag = 根元)。 */
    public static final DeferredBlock<Block> MEDULLOSA_TRUNK = BLOCKS.registerBlock(
            "medullosa_trunk", com.yayuy.overgrownvegetation.block.MedullosaBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_GREEN).strength(1.0F).noOcclusion()
                    .sound(SoundType.BAMBOO).pushReaction(PushReaction.DESTROY));

    /** メドゥロサの冠(芯 + 箱の外へ張り出す板の葉。flag = 高さ 3 の冠)。 */
    public static final DeferredBlock<Block> MEDULLOSA_CROWN = BLOCKS.registerBlock(
            "medullosa_crown", com.yayuy.overgrownvegetation.block.MedullosaBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.2F).noOcclusion()
                    .sound(SoundType.AZALEA_LEAVES).pushReaction(PushReaction.DESTROY)
                    .isValidSpawn((state, getter, pos, type) -> false));

    // 洞窟の中にだけ生える汚染植物(湿地・菌湿地帯の洞窟の微光の胞子嚢・苔の塊)は 2026-11-01 に削除(地下の植生は作り直す)。

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

    // 2026-11-01: 汚染された海草・背高海草・藻柱・藻柱の花・藻(多面)を、水中の植物の下絵の 7 つに置き換えた(古い方は削除)。
    // 藻(多面)の代わりは覆いの「水中の組」(design/cover.json の waterTextures / seaTextures)。

    /** シャジクモ(W2)。川底・池底に 1〜3 段で立つ。水中専用({@link com.yayuy.overgrownvegetation.block.CharaBlock})。 */
    public static final DeferredBlock<Block> CHARA = BLOCKS.registerBlock(
            "chara", com.yayuy.overgrownvegetation.block.CharaBlock::new, waterCrossGrowthProperties(SoundType.WET_GRASS));

    /** 岸から垂れる根(W3)。岸の下面・壁から水の中へ 2〜5 段。水中専用({@link com.yayuy.overgrownvegetation.block.WaterRootsBlock})。 */
    public static final DeferredBlock<Block> WATER_ROOTS = BLOCKS.registerBlock(
            "water_roots", com.yayuy.overgrownvegetation.block.WaterRootsBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.HANGING_ROOTS)
                    .pushReaction(PushReaction.DESTROY));

    private static BlockBehaviour.Properties filmProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .noCollission()
                .instabreak()
                .noOcclusion()
                .sound(SoundType.WET_GRASS)
                .pushReaction(PushReaction.DESTROY);
    }

    /** 藻の膜(W5)。川・池の水面に浮く 1 枚板({@link com.yayuy.overgrownvegetation.block.FloatingFilmBlock})。 */
    public static final DeferredBlock<Block> ALGAE_FILM = BLOCKS.registerBlock(
            "algae_film", com.yayuy.overgrownvegetation.block.FloatingFilmBlock::new, filmProperties());

    /** 胞子の膜(W5)。藻の膜の胞子の版。 */
    public static final DeferredBlock<Block> SPORE_FILM = BLOCKS.registerBlock(
            "spore_film", com.yayuy.overgrownvegetation.block.FloatingFilmBlock::new, filmProperties());

    /** 海面の浮き藻(O4)。海の水面に蛇行する帯で浮く。 */
    public static final DeferredBlock<Block> SEA_SPORE_BAND = BLOCKS.registerBlock(
            "sea_spore_band", com.yayuy.overgrownvegetation.block.FloatingFilmBlock::new, filmProperties());

    /** 胞子昆布の柱(O2)。海底から 12〜18 段の立方体。腕も同じブロック({@link com.yayuy.overgrownvegetation.block.SporeKelpStalkBlock})。 */
    public static final DeferredBlock<Block> SPORE_KELP_STALK = BLOCKS.registerBlock(
            "spore_kelp_stalk", com.yayuy.overgrownvegetation.block.SporeKelpStalkBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(0.5F)
                    .sound(SoundType.WET_GRASS)
                    .isValidSpawn((state, getter, pos, type) -> false));

    /** 胞子昆布の葉(O2)。柱の横と腕の下の帯の板({@link com.yayuy.overgrownvegetation.block.SporeKelpFrondBlock})。 */
    public static final DeferredBlock<Block> SPORE_KELP_FROND = BLOCKS.registerBlock(
            "spore_kelp_frond", com.yayuy.overgrownvegetation.block.SporeKelpFrondBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noCollission()
                    .instabreak()
                    .noOcclusion()
                    .sound(SoundType.WET_GRASS)
                    .pushReaction(PushReaction.DESTROY)
                    .isValidSpawn((state, getter, pos, type) -> false));

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

    /** 地下モードの試作用の仮の光源(2026-11-01。地下の植生の実装で本物に置き換えて消す。見た目の光はシェーダーの点光源)。 */
    public static final DeferredBlock<Block> CAVE_LIGHT_PROBE = BLOCKS.registerBlock(
            "cave_light_probe", com.yayuy.overgrownvegetation.block.CaveLightProbeBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                    .mapColor(MapColor.COLOR_GREEN)
                    .lightLevel(state -> 2));

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

    /** クリエイティブタブ表示用の並び順。 */
    public static List<DeferredBlock<Block>> displayOrder() {
        return List.of(
                OVERGROWN_GRASS_BLOCK,
                OVERGROWN_DIRT,
                OVERGROWN_STONE,
                OVERGROWN_DEEPSLATE,
                OVERGROWN_COBBLED_DEEPSLATE,
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
                OVERGROWN_NETHERRACK,
                OVERGROWN_SOUL_SAND,
                OVERGROWN_SOUL_SOIL,
                OVERGROWN_END_STONE,
                OVERGROWN_LOG,
                OVERGROWN_WOOD,
                OVERGROWN_BRANCH,
                CORDAITES_PLANKS,
                GIANT_OVERGROWN_LOG,
                GIANT_OVERGROWN_CAMBIUM,
                HARDENED_GIANT_TRUNK,
                GIANT_BARK_PLANKS,
                GIANT_PLANKS,
                OVERGROWN_LEAVES,
                SIGILLARIA_LOG,
                SIGILLARIA_BRANCH,
                SIGILLARIA_LEAVES,
                SIGILLARIA_LEAVES_FRINGE,
                SIGILLARIA_CONE,
                SIGILLARIA_PLANKS,
                TREE_FERN_TRUNK,
                TREE_FERN_LEAVES,
                TREE_FERN_FROND,
                OVERGROWN_BUSH,
                OVERGROWN_SAPLING,
                OVERGROWN_PLANKS,
                OVERGROWN_STAIRS,
                OVERGROWN_SLAB,
                OVERGROWN_STONE_STAIRS,
                OVERGROWN_FENCE,
                OVERGROWN_WALL,
                OVERGROWN_GROWTH,
                OVERGROWN_BLOCK,
                OVERGROWN_BLOCK_SLAB,
                OVERGROWN_SPORE_BLOOM,
                OVERGROWN_ZENMAI,
                UNDEVELOPED_BUSH,
                OVERGROWN_CREEPING_VINE,
                OVERGROWN_SHELF_LICHEN,
                OVERGROWN_THORN_VINE,
                CLIMBING_SEEDFERN,
                AERIAL_ROOT,
                AERIAL_ROOT_PILLAR,
                BEARD_LICHEN,
                HANGING_SPORE_SAC,
                OVERGROWN_WEEPING_TENDRIL,
                OVERGROWN_DRIP_MOSS,
                OVERGROWN_SPORE_TENDRIL,
                OVERGROWN_TALL_FERN,
                OVERGROWN_SPORE_REED,
                OVERGROWN_CURLED_FERN,
                OVERGROWN_MOSS_LAYER,
                SELAGINELLA, CLUBMOSS, LYCOPSID_CARPET, LYCOPSID_UPRIGHT,
                PEAT, PEAT_POOL_BOTTOM, PEAT_SPROUTS, PEAT_LITTER,
                FALLEN_LOG, BRACKET_FUNGUS, MEDULLOSA_TRUNK, MEDULLOSA_CROWN,
                CHARA,
                WATER_ROOTS,
                ALGAE_FILM,
                SPORE_FILM,
                SEA_SPORE_BAND,
                SPORE_KELP_STALK,
                SPORE_KELP_FROND,
                OVERGROWN_LIANA);
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
