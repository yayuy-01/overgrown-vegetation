package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.block.SiegeStructurePartBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「元のブロック → 汚染ブロック」の対応表。
 *
 * <p>判定は次の順序で行う。
 * <ol>
 *     <li>特別扱い({@link #specialCorrupt} —— チェスト・樽・ドア・機関砲台・竹・積もった雪)</li>
 *     <li>個別指定 ({@link #EXACT})</li>
 *     <li>タグによるルール ({@link #TAG_RULES})</li>
 *     <li>置換可能な薄い植物 → 汚染された下草</li>
 *     <li>いずれにも当てはまらなければ {@link #corruptUnmapped}</li>
 * </ol>
 *
 * <p>最後の 1 つは 2026-09-22 に変えた。以前は汎用の「汚染されたブロック」
 * ({@link ModBlocks#OVERGROWN_BLOCK})へ潰していたが、コンクリートも鉄ブロックも尖った鍾乳石も
 * 一律で同じ緑の立方体になってしまう。いまは<b>元のブロックの見た目のまま</b>
 * {@link OvergrownHuskBlock}(覆われて動かなくなったブロック)へ差し替える。
 * 立方体だけは 3 割が {@link ModBlocks#OVERGROWN_BLOCK} に落ちる —— 壁の混ざりを作るため。
 */
public final class CorruptionRegistry {

    private record TagRule(TagKey<Block> tag, java.util.function.Supplier<Block> result) {
    }

    private static Map<Block, Block> EXACT;
    private static List<TagRule> TAG_RULES;
    /**
     * 「石系」とみなす汚染ブロック(変換先)の集合。ここに含まれる変換先に落ち着いたブロックは、
     * {@link OvergrownVegetationConfig.Common#stoneToGrassChance} の抽選に当たると
     * {@code overgrown_grass_block} へ差し替えられる({@link #isStoneLike} でも同じ判定を使う)。
     */
    private static Set<Block> STONE_RESULTS;

    private static synchronized void init() {
        if (EXACT != null) {
            return;
        }

        Map<Block, Block> exact = new IdentityHashMap<>();

        Block grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get();
        Block dirt = ModBlocks.OVERGROWN_DIRT.get();
        Block stone = ModBlocks.OVERGROWN_STONE.get();
        Block deepslate = ModBlocks.OVERGROWN_DEEPSLATE.get();
        Block sand = ModBlocks.OVERGROWN_SAND.get();
        Block gravel = ModBlocks.OVERGROWN_GRAVEL.get();
        Block growth = ModBlocks.OVERGROWN_GROWTH.get();
        Block andesite = ModBlocks.OVERGROWN_ANDESITE.get();
        Block diorite = ModBlocks.OVERGROWN_DIORITE.get();
        Block granite = ModBlocks.OVERGROWN_GRANITE.get();
        Block cobblestone = ModBlocks.OVERGROWN_COBBLESTONE.get();
        Block cobbledDeepslate = ModBlocks.OVERGROWN_COBBLED_DEEPSLATE.get();
        Block dripstoneBlock = ModBlocks.OVERGROWN_DRIPSTONE_BLOCK.get();
        Block bricks = ModBlocks.OVERGROWN_BRICKS.get();
        Block diamondOre = ModBlocks.OVERGROWN_DIAMOND_ORE.get();
        Block deepslateCoalOre = ModBlocks.OVERGROWN_DEEPSLATE_COAL_ORE.get();
        Block deepslateCopperOre = ModBlocks.OVERGROWN_DEEPSLATE_COPPER_ORE.get();
        Block deepslateDiamondOre = ModBlocks.OVERGROWN_DEEPSLATE_DIAMOND_ORE.get();
        Block deepslateEmeraldOre = ModBlocks.OVERGROWN_DEEPSLATE_EMERALD_ORE.get();
        Block deepslateGoldOre = ModBlocks.OVERGROWN_DEEPSLATE_GOLD_ORE.get();
        Block deepslateIronOre = ModBlocks.OVERGROWN_DEEPSLATE_IRON_ORE.get();
        Block deepslateLapisOre = ModBlocks.OVERGROWN_DEEPSLATE_LAPIS_ORE.get();
        Block deepslateRedstoneOre = ModBlocks.OVERGROWN_DEEPSLATE_REDSTONE_ORE.get();
        Block coalOre = ModBlocks.OVERGROWN_COAL_ORE.get();
        Block copperOre = ModBlocks.OVERGROWN_COPPER_ORE.get();
        Block emeraldOre = ModBlocks.OVERGROWN_EMERALD_ORE.get();
        Block goldOre = ModBlocks.OVERGROWN_GOLD_ORE.get();
        Block ironOre = ModBlocks.OVERGROWN_IRON_ORE.get();
        Block lapisOre = ModBlocks.OVERGROWN_LAPIS_ORE.get();
        Block redstoneOre = ModBlocks.OVERGROWN_REDSTONE_ORE.get();

        // --- 表土系 ---
        put(exact, grass, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MOSS_BLOCK);

        // --- 土系 ---
        put(exact, dirt,
                Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.FARMLAND,
                Blocks.MUD, Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.CLAY,
                Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);

        // --- 石系(専用テクスチャの無い種類だけ汎用の「苔石」にまとめる) ---
        put(exact, stone,
                Blocks.STONE, Blocks.SMOOTH_STONE, Blocks.TUFF, Blocks.POLISHED_TUFF, Blocks.CALCITE,
                Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                Blocks.CHISELED_STONE_BRICKS);

        // --- 石系(色付き石。元テクスチャの色味を残した専用の苔テクスチャを持つ) ---
        put(exact, andesite, Blocks.ANDESITE, Blocks.POLISHED_ANDESITE);
        put(exact, diorite, Blocks.DIORITE, Blocks.POLISHED_DIORITE);
        put(exact, granite, Blocks.GRANITE, Blocks.POLISHED_GRANITE);
        put(exact, cobblestone, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE);
        put(exact, dripstoneBlock, Blocks.DRIPSTONE_BLOCK);
        put(exact, bricks, Blocks.BRICKS);

        // --- 深層岩系(専用テクスチャの無い種類だけ汎用の「苔深層岩」にまとめる) ---
        put(exact, deepslate,
                Blocks.DEEPSLATE, Blocks.POLISHED_DEEPSLATE,
                Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.CHISELED_DEEPSLATE);
        put(exact, cobbledDeepslate, Blocks.COBBLED_DEEPSLATE);

        // --- 鉱石系(専用テクスチャを用意したものだけ個別に対応) ---
        put(exact, diamondOre, Blocks.DIAMOND_ORE);
        put(exact, deepslateCoalOre, Blocks.DEEPSLATE_COAL_ORE);
        put(exact, deepslateCopperOre, Blocks.DEEPSLATE_COPPER_ORE);
        put(exact, deepslateDiamondOre, Blocks.DEEPSLATE_DIAMOND_ORE);
        put(exact, deepslateEmeraldOre, Blocks.DEEPSLATE_EMERALD_ORE);
        put(exact, deepslateGoldOre, Blocks.DEEPSLATE_GOLD_ORE);
        put(exact, deepslateIronOre, Blocks.DEEPSLATE_IRON_ORE);
        put(exact, deepslateLapisOre, Blocks.DEEPSLATE_LAPIS_ORE);
        put(exact, deepslateRedstoneOre, Blocks.DEEPSLATE_REDSTONE_ORE);
        put(exact, coalOre, Blocks.COAL_ORE);
        put(exact, copperOre, Blocks.COPPER_ORE);
        put(exact, emeraldOre, Blocks.EMERALD_ORE);
        put(exact, goldOre, Blocks.GOLD_ORE);
        put(exact, ironOre, Blocks.IRON_ORE);
        put(exact, lapisOre, Blocks.LAPIS_ORE);
        put(exact, redstoneOre, Blocks.REDSTONE_ORE);

        // --- 砂系 ---
        put(exact, sand,
                Blocks.SAND, Blocks.RED_SAND,
                Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE,
                Blocks.RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE,
                Blocks.CHISELED_RED_SANDSTONE);

        // --- 砂利 ---
        put(exact, gravel, Blocks.GRAVEL, Blocks.SUSPICIOUS_GRAVEL, Blocks.SUSPICIOUS_SAND);

        // --- 溶岩 → 玄武岩(汚染が溶岩を固める) ---
        // canCorrupt() 側で convertLavaToBasalt 設定と「溶岩の源泉かどうか」を判定してからここへ来る。
        // 変換先は overgrown 系ではなくバニラの玄武岩そのもの。再汚染されないよう
        // data/overgrown_vegetation/tags/block/corrupted.json に minecraft:basalt を入れてある。
        put(exact, Blocks.BASALT, Blocks.LAVA);

        // --- 薄い植物 ---
        // Blocks.VINE はここにも入れてあるが、通常は corrupt() 冒頭の特別処理
        // (corruptVine) が先に横向きの面を見つけて OVERGROWN_CREEPING_VINE に変換する。
        // ここに残しているのは、上面(天井)にしか張り付いていない特殊な状態など
        // 横向きの面が無い場合のフォールバック用。
        // 積もった雪(SnowLayerBlock)はここには入れない。corrupt() 冒頭で「何にも変換せず
        // ただ削除する(空気にする)」特別扱いにしている(要望: 雪は下草にせず消すだけ)。
        // 竹(BambooStalkBlock / BambooSaplingBlock)もここには入れない。縦に積み重なる
        // 多ブロック植物なので、1段だけ下草へ変換すると残りの段が支えを失って壊れ、
        // 大量の竹アイテムをドロップし下草だけが空中に取り残される。corruptsToAir 扱いにして
        // placeCorrupted 側で茎まるごとドロップ無しで消す(要望)。
        put(exact, growth,
                Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
                Blocks.DEAD_BUSH, Blocks.SWEET_BERRY_BUSH, Blocks.SUGAR_CANE,
                Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.MOSS_CARPET,
                Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT);

        List<TagRule> rules = new ArrayList<>();
        rules.add(new TagRule(BlockTags.LOGS, ModBlocks.OVERGROWN_LOG));
        // 自然生成の葉(PERSISTENT=false)はそもそも canCorrupt() で弾かれるので、
        // ここに到達する葉はプレイヤーが設置したもの(PERSISTENT=true)だけ。
        rules.add(new TagRule(BlockTags.LEAVES, ModBlocks.OVERGROWN_LEAVES));
        rules.add(new TagRule(BlockTags.PLANKS, ModBlocks.OVERGROWN_PLANKS));
        rules.add(new TagRule(BlockTags.WOODEN_SLABS, ModBlocks.OVERGROWN_PLANKS));
        // 階段・フェンス・壁は木/石を問わず形状を保ったまま専用ブロックに変換する
        // (OVERGROWN_PLANKS 等に潰すと形が失われるため)。木か石かで見た目を分けるので、
        // より限定的な WOODEN_STAIRS を先に判定してから STAIRS 全体(=残りは石系)を見る。
        rules.add(new TagRule(BlockTags.WOODEN_STAIRS, ModBlocks.OVERGROWN_STAIRS));
        rules.add(new TagRule(BlockTags.STAIRS, ModBlocks.OVERGROWN_STONE_STAIRS));
        rules.add(new TagRule(BlockTags.WALLS, ModBlocks.OVERGROWN_WALL));
        rules.add(new TagRule(BlockTags.FENCES, ModBlocks.OVERGROWN_FENCE));
        rules.add(new TagRule(BlockTags.DIRT, ModBlocks.OVERGROWN_DIRT));
        rules.add(new TagRule(BlockTags.SAND, ModBlocks.OVERGROWN_SAND));
        rules.add(new TagRule(BlockTags.BASE_STONE_OVERWORLD, ModBlocks.OVERGROWN_STONE));
        rules.add(new TagRule(BlockTags.STONE_BRICKS, ModBlocks.OVERGROWN_STONE));
        rules.add(new TagRule(BlockTags.TERRACOTTA, ModBlocks.OVERGROWN_DIRT));
        rules.add(new TagRule(BlockTags.FLOWERS, ModBlocks.OVERGROWN_GROWTH));
        // バニラの苗木は下草には潰さず専用の汚染された苗木にする。侵食が既存の森を
        // 飲み込んだとき、そこに生えていた苗木も汚染された木へ育っていくようにするため
        // (CorruptedSaplingBlock 参照)。
        rules.add(new TagRule(BlockTags.SAPLINGS, ModBlocks.OVERGROWN_SAPLING));
        rules.add(new TagRule(BlockTags.CROPS, ModBlocks.OVERGROWN_GROWTH));
        rules.add(new TagRule(BlockTags.CORAL_PLANTS, ModBlocks.OVERGROWN_GROWTH));

        STONE_RESULTS = Set.of(stone, deepslate, andesite, diorite, granite,
                cobblestone, cobbledDeepslate, dripstoneBlock);

        TAG_RULES = List.copyOf(rules);
        EXACT = exact;
    }

    /**
     * 元のブロック({@link #EXACT} / {@link #TAG_RULES} / 薄い植物 の順)に対応する汚染ブロックを
     * 解決する。{@code random} 依存の特別処理(vine・randomBlockChance・stoneToGrassChance)は
     * 含まないので、{@link #corrupt} と {@link #isStoneLike} の両方から使える。
     *
     * @return どれにも当てはまらなければ {@code null}(= 対応表に無いブロック)。
     *         {@link #hasNoCorruptedForm} を参照
     */
    @Nullable
    private static Block resolveTarget(BlockState state) {
        Block target = EXACT.get(state.getBlock());

        if (target == null) {
            for (TagRule rule : TAG_RULES) {
                if (state.is(rule.tag())) {
                    target = rule.result().get();
                    break;
                }
            }
        }

        if (target == null && state.canBeReplaced()) {
            // 草・花などの「上書き可能な薄いブロック」は下草に
            target = ModBlocks.OVERGROWN_GROWTH.get();
        }

        // ここまでで決まらなければ「対応表に無いブロック」。null を返し、呼び出し元の
        // corrupt() は<b>元の状態をそのまま返す</b> —— 置き換えずに残し、見た目だけを
        // overgrown_layer のオーバーレイで覆う(2026-09-22 の仕様変更。以前はここで
        // 汎用の OVERGROWN_BLOCK へ潰していたので、コンクリートも鉄ブロックも
        // 尖った鍾乳石も一律で同じ緑の立方体になっていた)。
        // 差し替え先は block/OvergrownHuskBlock(見た目は元のまま・機能は無い)。

        return target;
    }

    /**
     * このブロックが汚染されると「石系」({@code overgrown_stone} など)へ変換されるか。
     * {@link CorruptionSpreader} が、石系 → {@code overgrown_grass_block} へ化けた直後に
     * 上面へ草を生やすかどうかの判断にも使う。
     */
    public static boolean isStoneLike(BlockState state) {
        init();
        return STONE_RESULTS.contains(resolveTarget(state));
    }

    /**
     * このブロックは<b>対応表に無い</b>(汚染されても変換先が無い)か(2026-09-22)。
     *
     * <p>以前はこの手のブロック —— コンクリート・羊毛・ガラス・鉄ブロック・尖った鍾乳石など ——
     * をまとめて {@link ModBlocks#OVERGROWN_BLOCK} へ潰していたが、
     * <b>形も色も違うものが一律で同じ緑の立方体になってしまう</b>ので、
     * 置き換えず<b>その場に残して見た目だけを覆う</b>ことにした(要望)。
     * 覆い方は {@link OvergrownHuskBlock} を参照。
     *
     * <p>{@link #corrupt} はこの手のブロックに対して<b>元の状態をそのまま返す</b>ので、
     * 呼び出し元は既存の {@link #noChange} で素通りする —— 汚染の経路を書き換える必要は無い。
     *
     * <p><b>クライアントからも呼ぶ</b>(描画の判定に使う)。対応表はブロックとタグだけを見るので、
     * サーバーと同じ答えになる。
     */
    public static boolean hasNoCorruptedForm(BlockState state) {
        init();
        return resolveTarget(state) == null;
    }

    /**
     * このブロックは汚染しても汚染ブロックには変換されず、ただ削除される(空気になる)か。
     * <ul>
     *     <li>積もった雪({@link SnowLayerBlock}。{@code Blocks.SNOW})
     *     (要望: 雪は過繁茂した下草などにせず、消すだけにする)。</li>
     *     <li>竹({@link #isColumnPlantClearedByCorruption})
     *     (要望: 竹はアイテムをドロップさせず、茎まるごと消す)。</li>
     * </ul>
     * {@link #corrupt} がこの場合 {@code Blocks.AIR} を返すので、呼び出し元の
     * {@code level.setBlock(pos, air)} がそのまま除去になる。竹については
     * {@link #placeCorrupted} が上下に連なる段もまとめて消す。
     */
    public static boolean corruptsToAir(BlockState state) {
        return state.getBlock() instanceof SnowLayerBlock
                || isColumnPlantClearedByCorruption(state);
    }

    /**
     * 竹({@link BambooStalkBlock} / {@link BambooSaplingBlock})のような、縦に積み重なる
     * 多ブロック植物で、汚染時に「1段ずつ下草へ変換」ではなく「茎まるごとドロップ無しで消す」
     * べきものか。{@link #corruptsToAir} が {@code true} を返し、{@link #placeCorrupted} が
     * その列の上下に連なる同種ブロックをすべてドロップ無しで空気化する。
     */
    public static boolean isColumnPlantClearedByCorruption(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock;
    }

    /**
     * バニラの蔦({@link VineBlock})を、対応する壁1面に薄く張り付く
     * {@link ModBlocks#OVERGROWN_CREEPING_VINE} へ変換する。
     *
     * <p>バニラの蔦は {@code NORTH}/{@code SOUTH}/{@code EAST}/{@code WEST}(+ {@code UP})の
     * 面を同時に複数持てるが、こちらは {@link net.minecraft.world.level.block.HorizontalDirectionalBlock}
     * で横向き1面しか持てないので、立っている面のうち最初に見つかったものだけを採用する。
     *
     * <p>方向の対応関係は元の {@link VineBlock#canSupportAtFace} と
     * {@link com.yayuy.overgrownvegetation.block.CorruptedWallGrowthBlock#canSurvive} を突き合わせて
     * 導いたもの: バニラの蔦は面が立っている方向 {@code d} の隣接ブロックにくっついているのに対し、
     * こちらの {@code FACING} は「くっついている面と反対側(壁から外を向く方向)」を表すため、
     * {@code d.getOpposite()} が対応する {@code FACING} になる。
     *
     * @return 横向きの面が1つも立っていない(天井にしか張り付いていない)場合は {@code null}
     */
    @Nullable
    private static BlockState corruptVine(BlockState state) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BooleanProperty faceProperty = VineBlock.getPropertyForFace(direction);
            if (state.getValue(faceProperty)) {
                return ModBlocks.OVERGROWN_CREEPING_VINE.get().defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, direction.getOpposite());
            }
        }
        return null;
    }

    /**
     * 汚染ブロックをワールドへ設置する。単なる {@code level.setBlock} と違い、変換で当たり判定が
     * 高くなるブロック(泥・耕地・土の道・粗い土の道など、上面が数ピクセル低いブロック →
     * 上面が満杯の汚染ブロック)については、その差分の空間に居るエンティティを先に押し上げてから
     * 設置する。これをしないと、その地面に立っていた汚染シダなどが変換後のブロックにめり込んで
     * 地面に埋まってしまう。
     *
     * <p>押し上げはバニラの「耕地を踏むと土に戻る」処理
     * ({@link net.minecraft.world.level.block.FarmBlock#turnToDirt})と同じ
     * {@link Block#pushEntitiesUp} を使う。当たり判定が変わらない大多数のブロック
     * (土 → 過繁茂した土 など、満杯 → 満杯)では {@code pushEntitiesUp} 内部の
     * 「増えた形状が空なら即 return」で弾かれるので、エンティティ探索は走らない。
     *
     * <p>汚染は「侵食に飲み込まれる」表現であって伐採ではないので、設置は必ず
     * {@link Block#UPDATE_SUPPRESS_DROPS}(ドロップ抑制)と {@link Block#UPDATE_KNOWN_SHAPE}
     * (形状更新の連鎖を止める)を立てて行う(要望: 汚染でアイテムをばらまかない)。
     * これをしないと、変換したブロックの隣で支えを失うバニラの植物 —— 海中の昆布・海草柱、
     * 二段植物(背高の草・大きなシダ)の上半分、過繁茂した土の上のシダ(小麦の種を落とす)、
     * サトウキビ・サボテンなど —— が形状更新の連鎖で壊され、地上・水面にアイテムが
     * 散らばってしまう。バニラの {@code Level#markAndNotifyBlock} は形状更新の連鎖へ
     * 渡すフラグから {@code UPDATE_SUPPRESS_DROPS} を落とす({@code flags & -34})ため、
     * ドロップ抑制だけでは連鎖先の破壊ドロップは止まらない。{@code UPDATE_KNOWN_SHAPE} で
     * 連鎖自体を打ち切る(支えを失った植物は再評価されずその場に残る)。
     *
     * @param previous  変換前のブロック状態(当たり判定の差分を求めるのに使う)
     * @param corrupted 実際に設置する変換後のブロック状態
     */
    public static void placeCorrupted(LevelAccessor level, BlockPos pos, BlockState previous,
                                      BlockState corrupted, int flags) {
        int safeFlags = flags | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;

        if (corrupted.isAir() && isColumnPlantClearedByCorruption(previous)) {
            // 竹など縦に連なる多ブロック植物は、当たった1段だけでなく茎まるごとを
            // ドロップ無し・近隣更新無しで消す(要望)。
            clearColumnPlant(level, pos);
            return;
        }
        if (corrupted.is(ModBlocks.OVERGROWN_HUSK.get())) {
            // 他 MOD の機械を「覆われて動かなくなったブロック」へ差し替える(2026-09-22)。
            // 中身を取り出して空にしてから置き換える —— バニラの入れ物は onRemove で中身を
            // 地面へ撒くので、空にしないと汚染がアイテムをばら撒いてしまう。
            OvergrownHuskBlock.encase(level, pos, previous, safeFlags);
            return;
        }

        if (OvergrownContainers.placeConverted(level, pos, previous, corrupted, safeFlags)) {
            // 過繫茂したドア(上下 2 マス)/チェスト(中身を保持、ラージなら相方も)/樽(中身を保持)。
            return;
        }

        if (previous.getBlock() instanceof DoublePlantBlock && previous.hasProperty(DoublePlantBlock.HALF)) {
            // 背高の草・大きなシダなどの二段植物。当たった側だけを差し替えると、もう片方の
            // 半分が支えを失って宙に取り残される(あるいはドロップして散らばる)。両方の半分を
            // ドロップ無しで消してから、変換後のブロックを必ず「下半分」の位置へ 1 つだけ置く(要望)。
            placeCorruptedDoublePlant(level, pos, previous, corrupted, safeFlags);
            return;
        }

        Block.pushEntitiesUp(previous, corrupted, level, pos);
        level.setBlock(pos, corrupted, safeFlags);
    }

    /**
     * 二段植物({@link DoublePlantBlock})が汚染されたときの設置。当たった {@code pos} が上半分でも
     * 下半分でも、まず上下 2 マスをドロップ無し({@link Block#UPDATE_SUPPRESS_DROPS})・形状更新の
     * 連鎖無し({@link Block#UPDATE_KNOWN_SHAPE})で空気化し、そのうえで変換後のブロックを
     * 「下半分」の位置へ 1 つだけ置く。これで二段植物の片割れが宙に残ることも、
     * 破壊ドロップが散らばることもない。
     */
    private static void placeCorruptedDoublePlant(LevelAccessor level, BlockPos pos, BlockState previous,
                                                  BlockState corrupted, int safeFlags) {
        boolean hitLower = previous.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
        BlockPos lowerPos = hitLower ? pos : pos.below();
        BlockPos upperPos = lowerPos.above();

        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
        BlockState air = Blocks.AIR.defaultBlockState();

        // 相手の半分が本当に同じ二段植物のときだけ空気化する(既に片割れだけの壊れた状態などで
        // 無関係なブロックを巻き込まないように)。
        BlockState lowerState = level.getBlockState(lowerPos);
        BlockState upperState = level.getBlockState(upperPos);
        if (upperState.getBlock() == previous.getBlock()) {
            level.setBlock(upperPos, air, clearFlags);
        }
        if (!lowerPos.equals(pos) && lowerState.getBlock() == previous.getBlock()) {
            level.setBlock(lowerPos, air, clearFlags);
        }

        BlockState lowerPrevious = lowerState.getBlock() == previous.getBlock() ? lowerState : previous;
        Block.pushEntitiesUp(lowerPrevious, corrupted, level, lowerPos);
        level.setBlock(lowerPos, corrupted, safeFlags);
    }

    /**
     * {@code origin} を含む、縦に連なる同種の多ブロック植物(竹の茎・竹の子)を上下方向へたどり、
     * すべてドロップ無し({@link Block#UPDATE_SUPPRESS_DROPS})・近隣更新無しでその場から消す。
     * 上の段から順に空気化するので、途中の段が「支えを失って壊れ、アイテム化する」ことがない。
     */
    private static void clearColumnPlant(LevelAccessor level, BlockPos origin) {
        BlockPos.MutableBlockPos cursor = origin.mutable();

        int topY = origin.getY();
        cursor.set(origin.getX(), origin.getY() + 1, origin.getZ());
        while (level.hasChunkAt(cursor)
                && isColumnPlantClearedByCorruption(level.getBlockState(cursor))) {
            topY = cursor.getY();
            cursor.move(Direction.UP);
        }

        int bottomY = origin.getY();
        cursor.set(origin.getX(), origin.getY() - 1, origin.getZ());
        while (level.hasChunkAt(cursor)
                && isColumnPlantClearedByCorruption(level.getBlockState(cursor))) {
            bottomY = cursor.getY();
            cursor.move(Direction.DOWN);
        }

        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = topY; y >= bottomY; y--) {
            cursor.set(origin.getX(), y, origin.getZ());
            level.setBlock(cursor, air, flags);
        }
    }

    private static void put(Map<Block, Block> map, Block result, Block... sources) {
        for (Block source : sources) {
            map.put(source, result);
        }
    }

    /**
     * このブロックを汚染してよいか。
     */
    public static boolean canCorrupt(LevelReader level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            // 溶岩の「源泉」ブロックだけは玄武岩へ「固める」対象にできる(EXACT: LAVA → BASALT)。
            // 流れている溶岩(source でない = LEVEL > 0)は対象外——固めると流れの途中に穴が空いた
            // まま残り不自然なので、源泉が固まって流れが自然に消えるのに任せる。
            // 水その他の液体は常に汚染対象外。
            return state.is(Blocks.LAVA)
                    && state.getFluidState().isSource()
                    && OvergrownVegetationConfig.COMMON.convertLavaToBasalt.get();
        }
        if (state.getFluidState().is(FluidTags.WATER) && !state.isCollisionShapeFullBlock(level, pos)) {
            // 水に浸かった非充填ブロック(海草・昆布・水没した装飾ブロックなど)は汚染対象外。
            // これらを「地上の植物」へ変換しても、水がすぐ流れ込んで壊れ、アイテムが水面に
            // 大量に散らばるだけになる(要望: 地上に生える植物は水中に生えないようにする)。
            // 水中用の汚染植物は今後追加予定。土・砂・石などの水を含まない地面ブロックは
            // fluidState が空なのでこのチェックを通らず、通常どおり汚染される。
            return false;
        }
        if (state.is(ModTags.CORRUPTED) || state.is(ModTags.CORRUPTION_IMMUNE)) {
            return false;
        }
        if (state.getBlock() instanceof SiegeStructurePartBlock) {
            // 砲台・遮蔽盾の見えない部品ブロック。部品は本体の一部でしかないので、
            // <b>本体と同じ可否に従わせる</b>。そうしないと本体(BlockEntity 持ち = 既定では
            // corruptBlockEntities=false で汚染されない)は無事なのに、代わりに部品 1 マスが汚染されて
            // その onRemove が本体を道連れにする、という形で結局まるごと壊れてしまう
            // (実際に攻城戦中、過繫茂バイオームの深部汚染で砲台と盾が壊れた)。
            BlockPos corePos = SiegeStructurePartBlock.corePos(state, pos);
            BlockState coreState = level.getBlockState(corePos);
            // 本体が見つからない孤児の部品は触らない(掃除は本体側の責務)。
            return !coreState.isAir() && !(coreState.getBlock() instanceof SiegeStructurePartBlock)
                    && canCorrupt(level, corePos, coreState);
        }
        if (state.is(BlockTags.LEAVES) && state.hasProperty(BlockStateProperties.PERSISTENT)
                && !state.getValue(BlockStateProperties.PERSISTENT)) {
            // 自然に生成された葉(ワールド生成の木が置いた葉)は PERSISTENT=false のまま。
            // プレイヤーが葉ブロックを手で設置すると、バニラの LeavesBlock#getStateForPlacement が
            // 常に PERSISTENT=true を付けるので、この既存プロパティを読むだけで
            // 「自然生成」か「プレイヤー設置」かを追加の記録なしに判別できる。
            // 自然生成の葉は汚染せずそのまま残す(要望: 汚染シダ・汚染死体が広げる汚染で
            // 木の葉は汚染されないようにする)。プレイヤーが設置した葉(PERSISTENT=true)は
            // このチェックを素通りし、以降の通常フロー(TAG_RULES の BlockTags.LEAVES →
            // OVERGROWN_LEAVES)で「過繁茂している葉」に変換される。
            return false;
        }
        if (state.getBlock().defaultDestroyTime() < 0.0F) {
            // 岩盤・バリアなど破壊不能ブロック
            return false;
        }
        if (state.is(ModBlocks.GUN_TURRET.get())) {
            // 機関砲台(2026-09-21)は BlockEntity を持つが、汚染されても<b>テクスチャが変わるだけ</b>で
            // 壊れも止まりもしない(ユーザー指定)。既に覆われていればもう何もしない。
            return !state.getValue(com.yayuy.overgrownvegetation.block.GunTurretBlock.OVERGROWN);
        }
        if (state.hasBlockEntity() && !OvergrownVegetationConfig.COMMON.corruptBlockEntities.get()
                && !OvergrownContainers.isConvertibleContainer(state)
                && !OvergrownHuskBlock.canEncase(state)) {
            // チェスト・樽だけは corruptBlockEntities が false でも「過繫茂したチェスト/樽」へ中身を保ったまま
            // 変換できる(OvergrownContainers)。それ以外の BlockEntity は従来どおり設定に従う。
            //
            // ただし<b>対応表に無い BlockEntity ブロック(= 他 MOD の機械)は別扱い</b>(2026-09-22)。
            // corruptBlockEntities は「チェストが中身ごと消えるのを防ぐ」ための設定だが、
            // 覆われたブロック(OvergrownHuskBlock)は<b>消さない</b> —— 見た目も中身も残して
            // 機能だけ止めるので、専用の設定(climax.json の overgrownLayer.encaseBlockEntities)で切り替える。
            return false;
        }
        if (level instanceof ServerLevel serverLevel
                && SporeDeviceData.inhibits(serverLevel, pos, SporeDeviceData.Inhibit.CORRUPTION)) {
            // 稼働中の胞子除去装置の効果範囲内。装置のチャンク(変化量 100%)では汚染は完全に止まり、
            // 末端(10%)では 10% の確率で汚染が不発になる。ここは全汚染経路の共通の関門なので 1 か所で済む。
            // (WorldGenLevel 経由の巨大樹生成などは ServerLevel ではないので対象外。)
            return false;
        }
        return true;
    }

    /**
     * 元のブロックに対応する汚染ブロックの状態を返す。
     *
     * <p>{@code random} は {@link OvergrownVegetationConfig.Common#randomBlockChance} の抽選に使う。
     * この抽選に当たった場合、対応表(個別指定・タグルール・薄い植物・蔦の特別処理を含む)を
     * すべて無視して汎用の「汚染ブロック」({@link ModBlocks#OVERGROWN_BLOCK})になる —
     * 元がどんなブロックであっても対象になる。
     *
     * <p>また、変換先が石系({@link #STONE_RESULTS})に落ち着いた場合は
     * {@link OvergrownVegetationConfig.Common#stoneToGrassChance} の確率で
     * {@link ModBlocks#OVERGROWN_GRASS_BLOCK} へ差し替える。
     */
    /**
     * {@link #corrupt(BlockState, RandomSource)} の位置付き版。汚染先が土・草({@link ModBlocks#OVERGROWN_DIRT} /
     * {@link ModBlocks#OVERGROWN_GRASS_BLOCK})に落ち着き、かつその場所の環境(地域マップ、バイオームの
     * 書き換えが済んでいなくてもよい)が湿地系なら {@link ModBlocks#OVERGROWN_MUD}(過繁茂している泥)にする。
     * 湿地・菌湿地帯の地面は泥、というルール。
     */
    public static BlockState corrupt(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        init();
        // チェスト・樽・ドア・機関砲台・竹・積もった雪は対応表より先に決まる。
        // ここを飛ばすと、下の「対応表に無いブロック」がそれらを横取りしてしまう。
        BlockState special = specialCorrupt(state);
        if (special != null) {
            return special;
        }
        if (hasNoCorruptedForm(state)) {
            // 対応表に無いブロック(= 他 MOD のブロックと、バニラでも対応表に載せていないもの)。
            // randomBlockChance(どんなブロックでも 5% で汎用の汚染ブロックになる)より<b>先に</b>決める ——
            // 後にすると、形のあるブロックや中身を持つ機械が汎用ブロックに化けて消える。
            return corruptUnmapped(level, pos, state);
        }
        BlockState result = corrupt(state, random);
        if (state.is(BlockTags.LOGS)) {
            // 元の森の木を残さない(消滅 or 倒木)。環境ごとの有効/無効と確率は tick 側(SwampTreeFall)で判定する。
            SwampTreeFall.enqueue(level, pos);
        }
        if ((result.is(ModBlocks.OVERGROWN_DIRT.get()) || result.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()))
                && OvergrowthRegionMap.isSwampAtBlended(level, pos.getX(), pos.getZ(), 2)) {
            return ModBlocks.OVERGROWN_MUD.get().defaultBlockState();
        }
        return result;
    }

    /**
     * <b>対応表に無いブロック</b>が汚染されたときにどうなるか(2026-09-22)。
     *
     * <p><b>「覆われたものは全部 {@link OvergrownHuskBlock} になる」</b>が基本の規則。
     * 見た目は元のブロックのまま、機能は一切無くなる。例外は 1 つだけ ——
     *
     * <ul>
     *     <li><b>6 面の実の立方体</b>(コンクリート・羊毛・ガラス・鉄ブロックなど)が
     *     {@code chance}(既定 70%)の抽選に<b>外れた</b>ときは {@link ModBlocks#OVERGROWN_BLOCK}。
     *     壁が一面まるごと同じ苔柄になると模様の繰り返しが目立つので、混ざりを作るため。
     *     <b>中身を持つブロックはこの抽選に掛けない</b> —— 外れたぶんを潰すと中のアイテムごと消える。
     *     立方体でないブロックも掛けない —— 潰すと形が失われるし、そもそも壁を作らない。</li>
     * </ul>
     *
     * <p><b>「動くものだけ差し替える」にしないこと。</b> 最初は {@code BlockEntity} を持つものだけを
     * 差し替えていたが、金床・砥石・作業台・ドア・音符ブロックはどれも {@code BlockEntity} を持たないのに
     * 動く。列挙で見分けようとすると必ず穴が残る({@link OvergrownHuskBlock} の説明を参照)。
     *
     * <p>差し替えられない事情({@code #husk_immune}・設定 off・この MOD 自身のブロック・
     * 模型を持たない描画)があるときだけ、<b>元の状態をそのまま返す</b>。
     * 呼び出し元は既存の {@link #noChange} で素通りする。
     */
    private static BlockState corruptUnmapped(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity()) {
            // 自分で動くもの(他 MOD の機械・かまどなど)は差し替えて完全に止める。抽選はしない ——
            // 外れたぶんを汎用ブロックに潰すと中のアイテムごと消える。
            return OvergrownHuskBlock.canEncase(state)
                    ? ModBlocks.OVERGROWN_HUSK.get().defaultBlockState()
                    : state;
        }
        if (!OvergrownLayerField.covered(level, pos, state, DesignData.climax().overgrownLayerChance())) {
            // 立方体が抽選に外れた。壁が一面まるごと同じ苔柄になるのを防ぐための混ざり。
            return ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        }
        // 覆われた。<b>ブロックは置き換えず</b>、覆ったことだけを記録する ——
        // 見た目の苔も右クリックの抑止もこの記録を見て動く(OvergrownLayerData)。
        // 置き換えないので呼び出し元は noChange で素通りする。
        if (OvergrownLayerData.canCover(state)) {
            OvergrownLayerData.cover(level, pos);
        }
        return state;
    }


    /**
     * <b>対応表より先に決まる特別な変換</b>。当てはまらなければ {@code null}。
     *
     * <p>ここに並ぶものは、対応表の有無にも {@code randomBlockChance} の抽選にも掛けてはいけない ——
     * チェストが汎用ブロックに化けると<b>中身が消える</b>し、機関砲台が別のブロックになると
     * 「テクスチャだけ変わる」という仕様が壊れる。
     *
     * <p>{@link #corrupt(BlockState, RandomSource)} と {@link #corrupt(ServerLevel, BlockPos,
     * BlockState, RandomSource)} の<b>両方</b>から、いちばん先に呼ぶこと。
     * 位置付きの方は「対応表に無いブロック」を先に横取りするので、ここを通さないと
     * <b>チェスト・樽・機関砲台・竹・積もった雪が全部まとめて横取りされる</b>
     * (実際に一度やって 6 本のテストが落ちた)。
     */
    @Nullable
    private static BlockState specialCorrupt(BlockState state) {
        if (corruptsToAir(state)) {
            // 積もった雪・竹は汚染しても何にもならず、ただ消える(空気になる)だけ(要望)。
            return Blocks.AIR.defaultBlockState();
        }
        if (state.is(ModBlocks.GUN_TURRET.get())) {
            // 機関砲台は別のブロックにならず、テクスチャだけが「過繫茂した機関砲台」に変わる
            // (ユーザー指定 2026-09-21)。中身(弾)も向きもそのまま —— 同じブロックなので
            // BlockEntity は生き残る。
            return state.setValue(com.yayuy.overgrownvegetation.block.GunTurretBlock.OVERGROWN, true);
        }
        // 木のドア・チェスト・樽は専用の過繫茂ブロックへ(向き・開閉状態を引き継ぐ)。
        return OvergrownContainers.corruptedStateFor(state);
    }
    public static BlockState corrupt(BlockState state, RandomSource random) {
        init();

        BlockState special = specialCorrupt(state);
        if (special != null) {
            return special;
        }

        double randomBlockChance = OvergrownVegetationConfig.COMMON.randomBlockChance.get();
        if (randomBlockChance > 0.0D && random.nextDouble() < randomBlockChance) {
            return copyMatchingProperties(state, ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState());
        }

        if (state.getBlock() instanceof VineBlock) {
            BlockState corruptedVine = corruptVine(state);
            if (corruptedVine != null) {
                return corruptedVine;
            }
            // 横向きの面を1つも持たない特殊な状態(天井にだけ張り付いている等)は、
            // このあとの通常の変換処理(EXACT の Blocks.VINE → growth)に任せる。
        }

        Block target = resolveTarget(state);
        if (target == null) {
            // 対応表に無いブロック。置き換えずにその場へ残す —— 呼び出し元は noChange() で
            // 素通りする(2026-09-22)。覆う側は corruptUnmapped。
            return state;
        }

        double stoneToGrassChance = OvergrownVegetationConfig.COMMON.stoneToGrassChance.get();
        if (stoneToGrassChance > 0.0D && STONE_RESULTS.contains(target)
                && random.nextDouble() < stoneToGrassChance) {
            // 石系ブロックは確率で「過繁茂した草ブロック」へ化ける(要望)。上に草を生やすのは
            // 呼び出し元の CorruptionSpreader#ensureGrassOnConvertedStone が受け持つ。
            target = ModBlocks.OVERGROWN_GRASS_BLOCK.get();
        }

        return copyMatchingProperties(state, target.defaultBlockState());
    }

    /**
     * 変換元のブロック状態が持つプロパティのうち、変換先のブロックも同じプロパティを持つものを
     * すべて引き継ぐ。原木の {@code AXIS} だけでなく、階段の {@code FACING}/{@code HALF}/{@code SHAPE}、
     * フェンス・壁の接続、{@code WATERLOGGED} なども汚染前と同じ向き・状態のまま維持される
     * (要望: 階段が汚染される前と同じ向き・状態になるように)。
     *
     * <p>同一の {@link Property} インスタンスを共有している場合だけコピーするので、取りうる値の
     * 範囲が食い違って {@code setValue} が例外を投げることは無い。
     */
    /**
     * 汚染の結果、本当に<b>何も変わらなかった</b>か({@code corrupt} の戻り値が元のままか)。
     *
     * <p>対応表に無いブロックは {@link #corrupt(BlockState, RandomSource)} が元の状態をそのまま返すので、
     * 呼び出し側はこれで「置き換える意味がない」ことを判定する。
     *
     * <p><b>「同じブロックかどうか」で見てはいけない</b>(2026-09-21 の修正) —— 機関砲台のように
     * <b>同じブロックの別の状態</b>へ汚染されるもの({@code GunTurretBlock.OVERGROWN} = テクスチャだけ
     * 過繫茂に変わる)があるため。ブロックで比べていたころは、その手のブロックが
     * <b>どの汚染経路からも素通り</b>され、いつまでも汚染されなかった。
     */
    public static boolean noChange(BlockState before, BlockState after) {
        return before == after;
    }

    public static BlockState copyMatchingProperties(BlockState original, BlockState result) {
        for (Property<?> property : original.getProperties()) {
            result = copyProperty(original, result, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState from, BlockState to, Property<T> property) {
        if (to.hasProperty(property)) {
            return to.setValue(property, from.getValue(property));
        }
        return to;
    }

    private CorruptionRegistry() {
    }
}
