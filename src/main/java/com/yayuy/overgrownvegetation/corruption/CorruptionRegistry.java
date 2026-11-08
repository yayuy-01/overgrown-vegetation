package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
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
 *     <li>特別扱い({@link #specialCorrupt} —— 竹・積もった雪・松明・機関砲台・<b>記録で覆うバニラのブロック</b>
 *     ({@link #COVER_IN_PLACE}: 扉・板材・階段・原木など建築タブのブロック。2026-10-30 から元のまま記録で覆う)・チェスト・樽・他 MOD の木のドア)</li>
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
     * 汚染で<b>元のまま記録で覆う</b>バニラのブロック({@link CoverInPlace}。2026-10-30 ユーザー決定で、それまでの「覆われた版」{@code covered_<id>}
     * 366 個を消して記録({@link OvergrownLayerData#cover})に替えた。下の「覆われた版」は今は「記録で覆う」のこと)。
     *
     * <p>元は 2026-10-05 夜、ユーザー指定「汚染された扉が対応した扉にならず過繁茂したドアになった。
     * ほかにも対応したブロックにならないものがあれば直して」)。建築タブのブロックは汚染で<b>同じブロックの覆われた版</b>になる ——
     * 以前は木の扉は全部「過繁茂したドア」、板材・階段・柵・塀・原木は木の種類を問わず 1 種類の過繁茂ブロック、
     * 木のハーフは板材(形が消える)、石レンガ・砂岩などは苔石・過繁茂した砂、それ以外は苔の記録で覆うか汎用の汚染ブロックだった。
     *
     * <p><b>3 割は同じ形の過繁茂ブロック</b>({@link #overgrownCounterpart}、同じく夜のユーザー指定「すべてのブロックで 3 割で
     * 過繁茂しているブロックに」→ 形のあるものは同じ形で)。抽選は対応表に無い立方体の混ざりと同じ {@code overgrownLayer.chance}
     * (既定 0.7 = 3 割が外れ)を座標のハッシュで引く({@link OvergrownLayerField#covered(BlockPos, double)})。
     * <b>木材は先に半分が過繁茂した木材</b>({@link #overgrownWoodCounterpart}、夜 3 のユーザー指定。過繁茂した木材 5:過繁茂ブロック 1.5:覆われた版 3.5)。
     *
     * <p><b>前から専用の「過繁茂している〜」があるもの</b>(個別指定で {@code overgrown_<同じ名前>} へ行く 6 個: 石・深層岩・深層岩の丸石・
     * レンガ・ネザーラック・エンドストーン。丸石・花崗岩・閃緑岩・安山岩は 2026-10-31 に専用ブロックを消して覆う側へ)だけは入れず、今までどおりそちらへ({@link #build})。
     * {@link #resolveTarget} には入れない —— 扉と同じく対応表の外の特別扱い。「対応表に無い」({@link #hasNoCorruptedForm})の答えを
     * 変えると、前に苔の記録で覆ったブロックの記録が無効になって苔が消える({@link OvergrownLayerData#isCovered})。
     */
    private static Set<Block> COVER_IN_PLACE;
    /**
     * 「石系」とみなす汚染ブロック(変換先)の集合。ここに含まれる変換先に落ち着いたブロックは、
     * {@link OvergrownVegetationConfig.Common#stoneToGrassChance} の抽選に当たると
     * {@code overgrown_grass_block} へ差し替えられる({@link #isStoneLike} でも同じ判定を使う)。
     */
    private static Set<Block> STONE_RESULTS;

    /**
     * 3 つの表を組み終えたら立てる。{@link #init} はこれを見るだけで戻る ——
     * 汚染の 1 マスごとに何度も呼ばれ、クライアントの描画スレッド({@link #hasNoCorruptedForm})からも
     * 呼ばれるので、毎回ロックを取るとスレッド同士が取り合う。
     * <b>表を全部入れてから最後に立てる</b>(volatile の書き込みより前の代入は、これを読んだ側から必ず見える)。
     */
    private static volatile boolean ready;

    private static void init() {
        if (ready) {
            return;
        }
        build();
    }

    private static synchronized void build() {
        if (EXACT != null) {
            return;
        }

        Map<Block, Block> exact = new IdentityHashMap<>();

        Block grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get();
        Block dirt = ModBlocks.OVERGROWN_DIRT.get();
        Block stone = ModBlocks.OVERGROWN_STONE.get();
        Block deepslate = ModBlocks.OVERGROWN_DEEPSLATE.get();
        Block growth = ModBlocks.OVERGROWN_GROWTH.get();
        Block cobbledDeepslate = ModBlocks.OVERGROWN_COBBLED_DEEPSLATE.get();
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
                Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW);

        // --- ネザー(2026-10-02 ユーザー指定。ポータルを越えた侵食の地面) ---
        // ナイリウムも「草の生えたネザーラック」へまとめる。ソウルサンド・ソウルソイルは以前は汚染された土だった。
        put(exact, ModBlocks.OVERGROWN_NETHERRACK.get(), Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM);
        put(exact, ModBlocks.OVERGROWN_SOUL_SAND.get(), Blocks.SOUL_SAND);
        put(exact, ModBlocks.OVERGROWN_SOUL_SOIL.get(), Blocks.SOUL_SOIL);
        put(exact, ModBlocks.OVERGROWN_END_STONE.get(), Blocks.END_STONE);

        // --- 石系(専用テクスチャの無い種類だけ汎用の「苔石」にまとめる) ---
        put(exact, stone,
                Blocks.STONE, Blocks.SMOOTH_STONE, Blocks.TUFF, Blocks.POLISHED_TUFF, Blocks.CALCITE,
                Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                Blocks.CHISELED_STONE_BRICKS);

        // --- 石系(色付き石。元テクスチャの色味を残した専用の苔テクスチャを持つ) ---
        // 花崗岩・閃緑岩・安山岩・丸石(と磨いた版・苔むした丸石)・鍾乳石ブロックの専用ブロックは 2026-10-31 に削除(ユーザー指定)。
        // 対応表から外したので、建築タブのもの(CoverInPlace)は特別扱いで元のまま覆われ、鍾乳石ブロックは「対応表に無いブロック」として覆われる。
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

        // 砂・赤い砂・砂岩・砂利(と怪しげな砂・砂利)の専用ブロックも 2026-10-31 に削除(ユーザー指定)。砂岩は建築タブなので元のまま覆われ、
        // 砂・砂利は「対応表に無いブロック」として覆われる(落下ブロックだが覆いは記録だけなので落ちない限りそのまま)。

        // --- 溶岩 → 玄武岩(汚染が溶岩を固める) ---
        // canCorrupt() 側で convertLavaToBasalt 設定と「溶岩の源泉かどうか」を判定してからここへ来る。
        // 2026-10-05 夜から実際の行き先は特別扱い(specialCorrupt)が先に決める —— 玄武岩を汚染したときと同じ覆われた玄武岩
        // (3 割は過繁茂しているブロック)。素の玄武岩は #corrupted から外したので、それ自体も汚染される(ユーザー指定)。
        // この行は「対応表にある」(hasNoCorruptedForm が false)の答えのために残している。
        put(exact, Blocks.BASALT, Blocks.LAVA);

        // --- 薄い植物 ---
        // Blocks.VINE はここにも入れてあるが、通常は corrupt() 冒頭の特別処理
        // (corruptVine) が先に横向きの面を見つけて WALL_LYCOPSID に変換する。
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
        // 原木 → 過繁茂した原木(コルダイテス)の規則は 2026-11-01 に外した(ユーザー決定「バニラの原木・木の置き換えは覆いだけ」)。
        // バニラの原木は特別扱い(COVER_IN_PLACE)で記録で覆う。他 MOD の原木は対応表に無いブロックとして覆う。
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

        STONE_RESULTS = Set.of(stone, deepslate, cobbledDeepslate);

        Set<Block> inPlace = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block vanilla : CoverInPlace.blocks()) {
            if (!hasOwnOvergrownForm(exact, vanilla)) {
                inPlace.add(vanilla);
            }
        }

        COVER_IN_PLACE = inPlace;
        TAG_RULES = List.copyOf(rules);
        EXACT = exact;
        ready = true;
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
     * 汚染で<b>元のまま記録で覆う</b>建築タブのブロックか({@link CoverInPlace}、2026-10-30)。対応表にあるもの(板材・原木・階段など)も
     * 入るので、{@link OvergrownLayerData#canCover} は {@link #hasNoCorruptedForm} とこれの「または」で見る({@link #hasNoCorruptedForm}
     * の答えは変えない —— 変えると前に記録で覆ったブロックの記録が無効になる)。クライアントの描画スレッドからも呼ぶ。
     */
    public static boolean coversInPlace(BlockState state) {
        init();
        return COVER_IN_PLACE.contains(state.getBlock());
    }

    /**
     * 記録で覆う(扉は上下とも。2026-10-05〜10-30 の覆われた扉は上下いっしょに置き換えていた)。
     * 固めた溶岩の玄武岩({@link LavaSolidifier})もここを通す。
     */
    public static void coverInPlace(ServerLevel level, BlockPos pos, BlockState state) {
        if (!OvergrownLayerData.canCover(state)) {
            return;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                && state.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)) {
            BlockPos other = state.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = level.getBlockState(other);
            if (otherState.is(state.getBlock()) && OvergrownLayerData.canCover(otherState)) {
                // もう片方を先に覆う(takeNewlyCovered が答えるのは最後に覆ったマスなので、呼び出し元の pos を後に)
                OvergrownLayerData.cover(level, other);
            }
        }
        OvergrownLayerData.cover(level, pos);
    }

    /**
     * このブロックは汚染しても汚染ブロックには変換されず、ただ削除される(空気になる)か。
     * <ul>
     *     <li>積もった雪({@link SnowLayerBlock}。{@code Blocks.SNOW})
     *     (要望: 雪は過繁茂した下草などにせず、消すだけにする)。</li>
     *     <li>竹({@link #isColumnPlantClearedByCorruption})
     *     (要望: 竹はアイテムをドロップさせず、茎まるごと消す)。</li>
     *     <li>グロウベリー(洞窟のツタ。同じ {@link #isColumnPlantClearedByCorruption}。2026-10-16 ユーザー指定「洞窟が汚染される際グロウベリーが
     *     削除されずに残る」。以前は対応表に無いので苔で覆って残していた)。</li>
     *     <li>垂れ根・胞子の花(1 マス)と、しだれツタ・ねじれツタ・大きなドリップリーフ(連なる段ごと。{@link #isColumnPlantClearedByCorruption})。
     *     同じく 2026-10-16 ユーザー指定(グロウベリーの続き)。</li>
     * </ul>
     * {@link #corrupt} がこの場合 {@code Blocks.AIR} を返すので、呼び出し元の
     * {@code level.setBlock(pos, air)} がそのまま除去になる。竹については
     * {@link #placeCorrupted} が上下に連なる段もまとめて消す。
     */
    public static boolean corruptsToAir(BlockState state) {
        return state.getBlock() instanceof SnowLayerBlock
                || isColumnPlantClearedByCorruption(state)
                || state.getBlock() instanceof HangingRootsBlock || state.getBlock() instanceof SporeBlossomBlock;
    }

    /**
     * 竹({@link BambooStalkBlock} / {@link BambooSaplingBlock})のような、縦に積み重なる
     * 多ブロック植物で、汚染時に「1段ずつ下草へ変換」ではなく「茎まるごとドロップ無しで消す」
     * べきものか。{@link #corruptsToAir} が {@code true} を返し、{@link #placeCorrupted} が
     * その列の上下に連なる同種ブロックをすべてドロップ無しで空気化する。
     */
    public static boolean isColumnPlantClearedByCorruption(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock
                // グロウベリー(2026-10-16。この MOD の光る蔦は別の種類なので入らない)と、同日のしだれツタ・ねじれツタ・大きなドリップリーフ
                || block instanceof CaveVines
                || block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock
                || block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock
                || block instanceof BigDripleafBlock || block instanceof BigDripleafStemBlock;
    }

    /**
     * バニラの蔦({@link VineBlock})を、対応する壁1面に薄く張り付く
     * {@link ModBlocks#WALL_LYCOPSID}(壁の小葉類の扇。2026-11-08 まで這い蔦)へ変換する。
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
                return ModBlocks.WALL_LYCOPSID.get().defaultBlockState()
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
     * <p>設置の間は {@link CorruptionDropSuppressor} の範囲に入り、設置がその場で引き起こした破壊
     * (近隣通知で支えを失ったものなど)のドロップも消す。<b>その場に残った植物</b>(過繁茂した地面の上の
     * バニラの花・草)は、以前はあとで<b>別の処理</b>が隣を {@code UPDATE_ALL} で書き換えたときに再評価されて壊れ、
     * 隣り合う植物を連鎖で巻き込みながらアイテムと破壊音・粒子を出していた。2026-10-06 からは、置いた直後に
     * {@link StrandedPlants#clearAround} が<b>生き残れない植物だけ</b>を音・粒子・ドロップ無しで消す(一般の設置の経路)。
     * 読み込みの途中のチャンクにかかるものなど、そこで消せずに残ったものは従来どおりなので、
     * 後で隣を書き換える呼び出し側は引き続き {@link CorruptionDropSuppressor} で包む。
     *
     * @param previous  変換前のブロック状態(当たり判定の差分を求めるのに使う)
     * @param corrupted 実際に設置する変換後のブロック状態
     */
    public static void placeCorrupted(LevelAccessor level, BlockPos pos, BlockState previous,
                                      BlockState corrupted, int flags) {
        if (level instanceof ServerLevel && CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10)。corrupt() が元のまま返すので普通はここまで来ない
        }
        CorruptionDropSuppressor.enter();
        try {
            placeCorruptedInScope(level, pos, previous, corrupted, flags);
        } finally {
            CorruptionDropSuppressor.exit();
        }
        // 不透明な汚染ブロック(地面の草・土・overgrown_block など)になったら、上と横の建築ブロックに這い上がる苔を付ける
        // (2026-11-03。建物の壁は地面から 1 段ずつ覆われていく。覆いの記録で覆ったときは OvergrownLayerData#cover から)
        if (level instanceof ServerLevel serverLevel && CoverCreepData.source(level.getBlockState(pos))) {
            CoverCreepData.spreadFrom(serverLevel, pos);
        }
    }

    /** {@link #placeCorrupted} の中身。ドロップを消す範囲の中から呼ぶ。 */
    private static void placeCorruptedInScope(LevelAccessor level, BlockPos pos, BlockState previous,
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
        // 支えを失って生き残れなくなった周りの植物は、音・粒子・ドロップ無しでここで消す(2026-10-06。残すと後で一斉に壊れる)。
        StrandedPlants.clearAround(level, pos);
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
        for (int y = topY; y >= bottomY; y--) {
            cursor.set(origin.getX(), y, origin.getZ());
            // 水に浸かった大きなドリップリーフは水に戻す(2026-10-16。竹・ツタは水を持たないので空気)。
            level.setBlock(cursor, level.getBlockState(cursor).getFluidState().createLegacyBlock(), flags);
        }
    }

    private static void put(Map<Block, Block> map, Block result, Block... sources) {
        for (Block source : sources) {
            map.put(source, result);
        }
    }

    /**
     * そのバニラのブロックは<b>前から専用の過繁茂ブロック</b>を持つか —— 個別指定の行き先が {@code overgrown_<同じ名前>}
     * (石 → overgrown_stone など)。持つものは覆われたブロックにせず、今までどおりそちらへ({@link #COVER_IN_PLACE})。
     * 磨いた深層岩 → overgrown_deepslate のような「似たものへまとめた」行き先は専用ではない。
     */
    private static boolean hasOwnOvergrownForm(Map<Block, Block> exact, Block vanilla) {
        Block target = exact.get(vanilla);
        return target != null && BuiltInRegistries.BLOCK.getKey(target).getPath()
                .equals("overgrown_" + BuiltInRegistries.BLOCK.getKey(vanilla).getPath());
    }

    /**
     * このブロックを汚染してよいか。
     */
    public static boolean canCorrupt(LevelReader level, BlockPos pos, BlockState state) {
        return canCorrupt(level, pos, state, true);
    }

    /**
     * {@link #canCorrupt(LevelReader, BlockPos, BlockState)} の、<b>最後の胞子除去装置の判定を省ける</b>版
     * (2026-09-24 軽量化、報告 1472)。
     *
     * <p>{@code checkDevices} を false にしてよいのは、呼ぶ側がチャンクの先頭で
     * {@link SporeDeviceData#mayInhibitBlocksIn} を聞いて false だったとき(そのチャンクのどのブロックでも
     * 阻害率がちょうど 0 = 装置の判定は乱数を引かずに false を返す)だけ。結果も乱数の引き方も元と同じになる。
     * 深部の一括置換は 1 チャンクで数千回ここを通り、装置の判定が毎回 SavedData の表を引いていた。
     * 攻城戦の部品ブロックの「本体に従う」判定は、本体が別のチャンクにあることがあるので常に装置まで見る。
     */
    public static boolean canCorrupt(LevelReader level, BlockPos pos, BlockState state, boolean checkDevices) {
        if (level instanceof ServerLevel && CorruptionSwitch.isOff()) {
            // /ov corruption false(2026-10-10 ユーザー指定): どの経路の汚染も入らない。世界生成(WorldGenRegion)は止めない。
            return false;
        }
        if (state.isAir()) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            // 溶岩の「源泉」ブロックだけは玄武岩へ「固める」対象にできる(EXACT: LAVA → BASALT)。
            // 流れている溶岩(source でない = LEVEL > 0)は対象外——固めると流れの途中に穴が空いた
            // まま残り不自然なので、源泉が固まって流れが自然に消えるのに任せる。
            // 水その他の液体は常に汚染対象外。
            // 2026-10-02 ユーザー指定: 流れている溶岩に汚染が来たら、その流れの源泉を固める(LavaSolidifier)。
            // 流れている溶岩そのものは置き換えないので、ここは通して corrupt() の側で源泉を辿る。
            return state.is(Blocks.LAVA)
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
        if (state.hasBlockEntity() && !OvergrownVegetationConfig.COMMON.corruptBlockEntities.get()
                && !OvergrownContainers.isConvertibleContainer(state)
                && !OvergrownHuskBlock.canEncase(state)
                && !SpawnerCorruption.isSpawner(state)) {
            // スポナー・試練のスポナーは覆って湧く Mob を寄生体に替える(2026-10-09、ユーザー指定。SpawnerCorruption)。
            // チェスト・樽だけは corruptBlockEntities が false でも「過繫茂したチェスト/樽」へ中身を保ったまま
            // 変換できる(OvergrownContainers)。それ以外の BlockEntity は従来どおり設定に従う。
            //
            // ただし<b>対応表に無い BlockEntity ブロック(= 他 MOD の機械)は別扱い</b>(2026-09-22)。
            // corruptBlockEntities は「チェストが中身ごと消えるのを防ぐ」ための設定だが、
            // 覆われたブロック(OvergrownHuskBlock)は<b>消さない</b> —— 見た目も中身も残して
            // 機能だけ止めるので、専用の値(OvergrownLayerField#encase。もとは climax.json)で切り替える。
            return false;
        }
        if (checkDevices && level instanceof ServerLevel serverLevel
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
     * {@link #corrupt(BlockState, RandomSource)} の位置付き版。
     * (2026-10-31 まで: 土・草に落ち着いた先が湿地系なら過繁茂している泥にしていた。泥は U11 で parked へ退避)
     */
    public static BlockState corrupt(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (CorruptionSwitch.isOff()) {
            // /ov corruption false(2026-10-10 ユーザー指定): 置き換え・苔の記録・スポナー・ポータル・溶岩・倒木の積み込みを全部止める。
            return state;
        }
        init();
        // エンドポータルの枠の隣が汚染されたら、森がエンドへ渡る(2026-10-02。枠そのものは免疫なので隣で気付く)。
        PortalCorruption.noticeEndFrame(level, pos, state);
        if (MeltedIce.isIce(state)) {
            // 氷は覆わずに溶かす(水に接していれば水、なければ空気。2026-10-16 ユーザー指定「汚染地域に氷が残ってしまう」)。
            // 以前は対応表に無いので苔で覆って残し、凍った湖の上を苔が渡っていた。
            return MeltedIce.stateAt(level, pos);
        }
        if (LavaSolidifier.isFlowingLava(state)) {
            // 流れている溶岩は置き換えず、上流の源泉を玄武岩へ固める(流れはバニラの更新で消える。2026-10-02)。
            LavaSolidifier.solidifySourcesOf(level, pos);
            return state;
        }
        if (state.is(BlockTags.LOGS)) {
            // 元の森の木を残さない(消滅 or 倒木)。環境ごとの有効/無効と確率は tick 側(SwampTreeFall)で判定する。
            // 特別扱いより先に積む —— バニラの原木は 2026-10-05 夜から特別扱いで覆われた原木になり、下まで来ない
            // (以前は対応表の後ろで積んでいた。そのままだと湿地で元の森の木が片付かなくなる)。
            SwampTreeFall.enqueue(level, pos);
        }
        // チェスト・樽・ドア・機関砲台・竹・積もった雪・覆われた版のあるブロックは対応表より先に決まる。
        // ここを飛ばすと、下の「対応表に無いブロック」がそれらを横取りしてしまう。
        // 覆われた版の代わりに過繁茂ブロック・過繁茂した木材にする抽選は、対応表に無い立方体と同じ座標のハッシュ(OvergrownLayerField)で引く。
        BlockState special = specialCorrupt(state, () -> OvergrownLayerField.roll(pos));
        if (special != null) {
            if (special == state || state.is(Blocks.LAVA) && coversInPlace(special)) {
                // 記録で覆うブロック(建築タブのブロック、2026-10-30 から)。置き換えずに記録だけ書く。
                // 溶岩の源泉は玄武岩に置き換わってから覆われる(記録は置く前に書いてよい —— 読むときに玄武岩の資格を見る)。
                coverInPlace(level, pos, special);
            }
            return special;
        }
        // 対応表を引くのは 1 回だけにする(2026-09-24 軽量化、報告 1472)。以前は hasNoCorruptedForm と
        // corrupt(state, random) がそれぞれ resolveTarget を呼び、EXACT に無いブロックでは TAG_RULES を 2 回舐めていた。
        // resolveTarget はブロックとタグしか見ないので、先に引いた答えを渡しても結果も乱数の引き方も同じ。
        Block target = resolveTarget(state);
        if (target == null) {
            // 対応表に無いブロック(= 他 MOD のブロックと、バニラでも対応表に載せていないもの)。
            // randomBlockChance(どんなブロックでも 5% で汎用の汚染ブロックになる)より<b>先に</b>決める ——
            // 後にすると、形のあるブロックや中身を持つ機械が汎用ブロックに化けて消える。
            if (PortalCorruption.interceptNetherFrame(level, pos, state)) {
                // 火の付いたネザーポータルの枠(2026-10-02)。汎用の汚染ブロックへ置き換えるとポータルが消えるので、
                // 抽選に掛けず必ず覆うだけにする。向こう側への侵食は PortalCorruption が tick の終わりに起こす。
                if (OvergrownLayerData.canCover(state)) {
                    OvergrownLayerData.cover(level, pos);
                }
                return state;
            }
            return corruptUnmapped(level, pos, state);
        }
        return corruptMapped(state, random, target);
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
        if (SpawnerCorruption.isSpawner(state)) {
            // スポナー・試練のスポナーは置き換えず覆う。覆った瞬間に湧く Mob が寄生体に替わる(2026-10-09、SpawnerCorruption)。
            OvergrownLayerData.cover(level, pos);
            return state;
        }
        if (state.hasBlockEntity()) {
            // 自分で動くもの(他 MOD の機械・かまどなど)は差し替えて完全に止める。抽選はしない ——
            // 外れたぶんを汎用ブロックに潰すと中のアイテムごと消える。
            return OvergrownHuskBlock.canEncase(state)
                    ? ModBlocks.OVERGROWN_HUSK.get().defaultBlockState()
                    : state;
        }
        double chance = OvergrownLayerField.chance();
        if (!OvergrownLayerField.covered(level, pos, state, chance)) {
            // 立方体が抽選に外れた。壁が一面まるごと同じ苔柄になるのを防ぐための混ざり。
            return ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        }
        if (!OvergrownLayerField.covered(pos, chance)) {
            // 形のあるもの(対応表に無い他 MOD のハーフなど)も、同じ抽選に外れたら同じ形の過繁茂ブロック
            // (覆われた版と同じ規則、2026-10-05 夜。同じ形が無いものは下で覆う)。
            BlockState counterpart = overgrownCounterpart(state);
            if (counterpart != null) {
                return counterpart;
            }
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
     *
     * @param roll 覆われた版の代わりに過繁茂ブロック・過繁茂した木材にするかの抽選の値([0, 1))。覆われた版のあるブロックで、
     *             代わりのブロックがあるときだけ引く(位置付きは座標のハッシュ {@link OvergrownLayerField#roll}、位置無しは乱数)
     */
    @Nullable
    private static BlockState specialCorrupt(BlockState state, java.util.function.DoubleSupplier roll) {
        if (state.is(Blocks.LAVA)) {
            // 固めた溶岩は、玄武岩を汚染したときと同じ行き先(覆われた玄武岩、3 割は過繁茂しているブロック)。2026-10-05 夜、ユーザー指定
            // 「溶岩を玄武岩に変換した後、さらに玄武岩も汚染するように(玄武岩に植物が生えていて違和感)」。以前は素の玄武岩で止めていた。
            // ここへ来るのは源泉だけ(流れている溶岩は corrupt() の頭で上流の源泉を固めに行く)。
            state = Blocks.BASALT.defaultBlockState();
        }
        if (corruptsToAir(state)) {
            // 積もった雪・竹は汚染しても何にもならず、ただ消える(空気になる)だけ(要望)。
            return Blocks.AIR.defaultBlockState();
        }
        // 松明は覆われると火が消える(光らない別のブロックへ。2026-10-05 ユーザー要望)。ほかの光源は覆って 4 割の明るさ
        // (covered/CoveredLight)。覆う側(corruptUnmapped)より先に決めないと、松明が苔を被ったまま光り続ける。
        BlockState extinguished = com.yayuy.overgrownvegetation.block.ExtinguishedTorchBlock.extinguishedStateFor(state);
        if (extinguished != null) {
            return extinguished;
        }
        // 覆われた版のあるバニラのブロックは、その版へ(向き・形・開閉・水没などを引き継ぐ。2026-10-05 夜、ユーザー指定)。
        // ただし 3 割(対応表に無い立方体と同じ overgrownLayer.chance の外れ)は同じ形の過繁茂ブロックへ(同じく夜、ユーザー指定
        // 「すべてのブロックで 3 割で過繁茂しているブロックに」→ 形のあるものは同じ形で)。同じ形が無いもの(扉・ボタンなど)は必ず覆われた版。
        // randomBlockChance(何でも 5% で汎用ブロック)には掛けない —— 扉や階段が立方体に化けると形が消える。
        // 手で動かすもの(扉・ボタンなど)は覆われ具合が最大で来る。
        // 木材(板材・木の階段・ハーフ・フェンス・原木・木)は先に半分を過繁茂した木材へ(同じく夜 3、ユーザー指定「木材の場合は 50% で
        // 過繁茂した板材系に。木材の建物は過繁茂しているブロック・過繁茂した木材・覆われた木材が混ざった感じに」)。残りの半分は今までどおり
        // 3:7 で過繁茂ブロックと覆われた版 —— 合わせて過繁茂した木材 5:過繁茂ブロック 1.5:覆われた版 3.5(割合はユーザーが選んだ)。
        // 原木・木は 2026-11-01 から過繁茂した木材に化けない(覆われた版 7:過繁茂ブロック 3)。
        // 2026-10-30 から「覆われた版」は元のまま記録で覆うこと —— ここでは元の状態をそのまま返し、位置付きの corrupt() が記録を書く
        // (呼び出し元は noChange で素通りし、対応表に無いブロックを覆ったときと同じ後処理になる)。
        if (COVER_IN_PLACE.contains(state.getBlock())) {
            BlockState wood = overgrownWoodCounterpart(state);
            BlockState counterpart = overgrownCounterpart(state);
            if (wood != null || counterpart != null) {
                double u = roll.getAsDouble();
                if (wood != null) {
                    if (u < WOOD_SHARE) {
                        return wood;
                    }
                    u = (u - WOOD_SHARE) / (1.0D - WOOD_SHARE);
                }
                if (counterpart != null && u >= OvergrownLayerField.chance()) {
                    return counterpart;
                }
            }
            return state;
        }
        // チェスト・樽は専用の過繫茂ブロックへ(向き・開閉状態を引き継ぐ)。木のドアはバニラなら上で覆われた扉になるので、
        // ここへ来るのは他 MOD の木のドアだけ(覆われた版が無いので過繫茂したドアへ)。
        return OvergrownContainers.corruptedStateFor(state);
    }

    /**
     * 覆われた版の代わりに置く<b>同じ形の過繁茂ブロック</b>(2026-10-05 夜、ユーザー指定)。立方体 → 過繁茂しているブロック、
     * ハーフ → そのハーフ、階段 → 過繁茂した石階段、塀 → 過繁茂した塀(この 3 つは過繁茂しているブロックと同じ見た目)、
     * フェンス → 過繁茂したフェンス。向き・形・水没は引き継ぐ。同じ形の無いもの(扉・トラップドア・フェンスゲート・ボタン・
     * レバー・感圧板・鉄格子・鎖)は {@code null}。立方体かどうかは対応表に無い立方体の抽選と同じ当たり判定で見る。
     */
    @Nullable
    private static BlockState overgrownCounterpart(BlockState state) {
        Block block = state.getBlock();
        Block target;
        if (block instanceof SlabBlock) {
            target = ModBlocks.OVERGROWN_BLOCK_SLAB.get();
        } else if (block instanceof StairBlock) {
            target = ModBlocks.OVERGROWN_STONE_STAIRS.get();
        } else if (block instanceof WallBlock) {
            target = ModBlocks.OVERGROWN_WALL.get();
        } else if (block instanceof FenceBlock) {
            target = ModBlocks.OVERGROWN_FENCE.get();
        } else if (OvergrownLayerField.isPlainCube(state)) {
            return genericBlockFor(state);
        } else {
            return null;
        }
        return copyMatchingProperties(state, target.defaultBlockState());
    }

    /**
     * 汎用の「過繁茂しているブロック」。<b>原木から化けるときは見た目が同じで {@code #logs} に入った版</b>
     * ({@code block/OvergrownTrunkBlock}、2026-10-05 夜、ユーザー報告「木が過繁茂しているブロックになって、倒木や木を消すときに空中に残る」)
     * —— 普通の過繁茂しているブロックだと、木を片付ける処理(SwampTreeFall)が原木の繋がりをそこで見失い、幹の途中と上が宙に残っていた。
     */
    private static BlockState genericBlockFor(BlockState state) {
        return (state.is(BlockTags.LOGS) ? ModBlocks.OVERGROWN_TRUNK_BLOCK.get() : ModBlocks.OVERGROWN_BLOCK.get())
                .defaultBlockState();
    }

    /**
     * 木材が覆われた版の代わりに過繁茂した木材になる割合(2026-10-05 夜 3、ユーザー指定「木材の場合は 50% で過繁茂した板材系に」)。
     * 残りは {@code overgrownLayer.chance} で覆われた版と過繁茂ブロックに分ける(ユーザーが選んだ「過繁茂した木材 5:過繁茂ブロック 1.5:覆われた版 3.5」)。
     */
    private static final double WOOD_SHARE = 0.5D;

    /**
     * 木材の覆われた版の代わりに置く<b>過繁茂した木材</b>(2026-10-05 夜 3、ユーザー指定)。板材 → 過繁茂している板材、木の階段 → 過繁茂した階段、
     * 木のハーフ → 過繁茂したハーフブロック、木のフェンス → 過繁茂したフェンス。向き・形・水没は引き継ぐ。
     * 原木・木は 2026-11-01 から入れない(ユーザー決定「バニラの原木・木の置き換えは覆いだけ」。過繁茂している原木はコルダイテスという木の種になった)。
     * それまでは原木・樹皮を剥いだ原木・幹 → 過繁茂している原木、木・樹皮を剥いだ木・菌糸 → 過繁茂している木だった。
     * 原木に残るのは {@link #overgrownCounterpart} の 3 割(原木から化けた過繁茂しているブロック)だけ。
     * 扉は入れない(ユーザーが選ばなかった。木の扉は必ず覆われた扉)。トラップドア・フェンスゲート・ボタン・感圧板・竹ブロックも入れない。
     * 木材かどうかはバニラのタグで見る({@code #planks}・{@code #wooden_stairs}・{@code #wooden_slabs}・{@code #wooden_fences})。
     */
    @Nullable
    private static BlockState overgrownWoodCounterpart(BlockState state) {
        Block target;
        if (state.is(BlockTags.PLANKS)) {
            target = ModBlocks.OVERGROWN_PLANKS.get();
        } else if (state.is(BlockTags.WOODEN_STAIRS)) {
            target = ModBlocks.OVERGROWN_STAIRS.get();
        } else if (state.is(BlockTags.WOODEN_SLABS)) {
            target = ModBlocks.OVERGROWN_SLAB.get();
        } else if (state.is(BlockTags.WOODEN_FENCES)) {
            target = ModBlocks.OVERGROWN_FENCE.get();
        } else {
            return null;
        }
        return copyMatchingProperties(state, target.defaultBlockState());
    }

    /**
     * 汚染が固めた溶岩の源泉の行き先(上流の源泉を固める {@link LavaSolidifier} から)。源泉に直接汚染が来たときと同じ ——
     * 覆われた玄武岩、3 割は過繁茂しているブロック(座標で決まる)。2026-10-05 夜。
     */
    public static BlockState solidifiedLava(BlockPos pos) {
        init();
        BlockState result = specialCorrupt(Blocks.LAVA.defaultBlockState(), () -> OvergrownLayerField.roll(pos));
        return result != null ? result : Blocks.BASALT.defaultBlockState();
    }

    public static BlockState corrupt(BlockState state, RandomSource random) {
        init();

        BlockState special = specialCorrupt(state, random::nextDouble);
        if (special != null) {
            return special;
        }
        return corruptMapped(state, random, null);
    }

    /**
     * {@link #corrupt(BlockState, RandomSource)} の特別扱いより後ろ(2026-09-24 に切り出した。中身と乱数を引く順番
     * —— randomBlockChance → 蔦 → stoneToGrassChance —— は元のまま)。
     *
     * @param resolved 呼ぶ側が既に {@link #resolveTarget} で引いた変換先。{@code null} ならここで引く
     *                 (対応表に無いブロックの {@code null} を渡したいときは、この経路を通らないこと)
     */
    private static BlockState corruptMapped(BlockState state, RandomSource random, @Nullable Block resolved) {
        double randomBlockChance = OvergrownVegetationConfig.COMMON.randomBlockChance.get();
        if (randomBlockChance > 0.0D && random.nextDouble() < randomBlockChance) {
            // 他 MOD の原木などは木の一部として片付くよう #logs の版(genericBlockFor)。
            return genericBlockFor(state);
        }

        if (state.getBlock() instanceof VineBlock) {
            BlockState corruptedVine = corruptVine(state);
            if (corruptedVine != null) {
                return corruptedVine;
            }
            // 横向きの面を1つも持たない特殊な状態(天井にだけ張り付いている等)は、
            // このあとの通常の変換処理(EXACT の Blocks.VINE → growth)に任せる。
        }

        Block target = resolved != null ? resolved : resolveTarget(state);
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
