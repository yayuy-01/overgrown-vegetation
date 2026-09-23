package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.ClimaxDeepConverter;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.corruption.LeyVeinPlacer;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>極相の全層置換</b>({@link ClimaxDeepConverter})と、それに合わせて変えた
 * <b>「対応表に無いブロックは置き換えない」</b>({@link CorruptionRegistry#hasNoCorruptedForm})のテスト
 * (2026-09-22)。
 *
 * <p>守りたいのは 4 つ。
 * <ul>
 *     <li><b>岩盤の上から空まで、どの層も飛ばさない</b> —— カーソルの計算(1 次元の添字 →
 *     x/y/z)が狂うと、ある高さの帯だけが汚染されずに残る。空のセクションを 4096 マスまとめて
 *     飛ばす近道があるので、そこが 1 つずれると帯ができる。</li>
 *     <li><b>対応表に無いブロックはその場に残る</b> —— 以前は汎用の汚染ブロックへ潰していた。
 *     ここが戻ると、コンクリートも鉄ブロックも尖った鍾乳石も同じ緑の立方体になる。</li>
 *     <li><b>岩盤は触らない</b> —— 全層置換は下端まで走るので、ここが漏れると世界の底が抜ける。</li>
 *     <li><b>地脈は必ず見つかる</b> —— {@code /ov climax ley} の行き先。</li>
 * </ul>
 *
 * <p><b>テスト場ではなく遠くのチャンクを使う理由</b>: 全層置換は<b>チャンク 1 つを丸ごと</b>
 * 書き換える。テスト場のあるチャンクでやると、同じチャンクで並行して走っている別のテストの
 * 足場まで過繫茂ブロックに変えてしまう。テストごとに専用のチャンクを 1 つずつ持たせてある。
 *
 * <p><b>{@code randomBlockChance}(既定 5%)に注意</b>: どんなブロックでも 5% で汎用の汚染ブロックに
 * 化ける。1 ブロックだけ見て「変わっていないこと」を確かめるとたまに落ちるので、
 * 数を撒いて割合で見るか、「元のままではないこと」だけを見ること。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ClimaxDeepGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ClimaxDeepGameTests.class);
        }

        private Registration() {
        }
    }

    /** テスト専用のチャンク列の起点。テスト場からも、ワールド境界からも十分離れている。 */
    private static final int TEST_CHUNK_X = 30_000;

    /**
     * 岩盤の上から空の高さまで、<b>どの層も飛ばさない</b>。
     *
     * <p>砂を選んでいるのは、{@code stoneToGrassChance}(石系は 30% で草ブロックに化ける)と
     * {@code swamp}(土・草は湿地なら泥に化ける)の抽選に巻き込まれないため。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 600, batch = "climaxDeep")
    public static void deepConversionReachesEveryLayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 0);
        int[] heights = {level.getMinBuildHeight() + 1, -32, 0, 40, 100, 200,
                level.getMaxBuildHeight() - 2};

        for (int y : heights) {
            level.setBlock(at(pos, y), Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        ClimaxDeepConverter.convertNow(level, pos);

        for (int y : heights) {
            BlockState after = level.getBlockState(at(pos, y));
            if (after.is(Blocks.SAND)) {
                helper.fail("y=" + y + " の砂が置き換わっていない(この高さの層が飛ばされている)");
                return;
            }
            if (!after.is(ModBlocks.OVERGROWN_SAND.get()) && !after.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                helper.fail("y=" + y + " の砂が想定外のブロックになった: " + after);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>対応表に無いブロックの行き先</b>(2026-09-22 の仕様変更)。
     *
     * <p><b>6 面の実の立方体</b>(鉄ブロック)は、抽選に当たれば<b>覆われたブロック</b>に、
     * 外れれば<b>汎用の汚染ブロック</b>になる —— 壁が一面まるごと同じ苔柄になるのを防ぐための混ざり。
     * <b>元のまま残ることは無い</b>のが肝。
     *
     * <p><b>立方体でないブロック</b>(尖った鍾乳石)は抽選せず<b>必ず覆われる</b> ——
     * 汎用ブロックに潰すと形が失われる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 600, batch = "climaxDeep")
    public static void unmappedBlocksSplitByShape(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 1);
        int count = 64;
        int baseY = 60;

        for (int i = 0; i < count; i++) {
            level.setBlock(cubeAt(pos, baseY, i), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(cubeAt(pos, baseY, i).south(2),
                    Blocks.POINTED_DRIPSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        ClimaxDeepConverter.convertNow(level, pos);

        int covered = 0;
        int generic = 0;
        for (int i = 0; i < count; i++) {
            BlockPos at = cubeAt(pos, baseY, i);
            BlockState after = level.getBlockState(at);
            if (after.is(Blocks.IRON_BLOCK)) {
                if (!OvergrownLayerData.isCovered(level, at)) {
                    helper.fail("鉄ブロックが残っているのに『覆われた』記録が無い(苔も出ないし UI も止まらない)");
                    return;
                }
                covered++;
            } else if (after.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                generic++;
            } else {
                helper.fail("立方体が想定外のブロックになった: " + after);
                return;
            }
            BlockPos shapedAt = at.south(2);
            if (!level.getBlockState(shapedAt).is(Blocks.POINTED_DRIPSTONE)
                    || !OvergrownLayerData.isCovered(level, shapedAt)) {
                helper.fail("立方体でないブロックが覆われなかった / 潰された: "
                        + level.getBlockState(shapedAt));
                return;
            }
        }
        // 既定 70%。抽選は座標のハッシュなので毎回同じ答えになる(片側に寄っていたら仕掛けが壊れている)。
        if (covered == 0 || generic == 0) {
            helper.fail("立方体の抽選が片側に寄っている: 覆われた " + covered + " / 汎用ブロック " + generic);
            return;
        }
        helper.succeed();
    }

    /** 世界の底を抜かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 600, batch = "climaxDeep")
    public static void deepConversionLeavesBedrock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 2);
        BlockPos bedrock = at(pos, level.getMinBuildHeight() + 2);

        level.setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        ClimaxDeepConverter.convertNow(level, pos);

        if (!level.getBlockState(bedrock).is(Blocks.BEDROCK)) {
            helper.fail("岩盤が置き換えられた: " + level.getBlockState(bedrock));
            return;
        }
        helper.succeed();
    }

    /** 2 回目は何もしない(台帳が無くても結果は同じ = 冪等)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 600, batch = "climaxDeep")
    public static void deepConversionIsIdempotent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 3);

        for (int y = 60; y < 70; y++) {
            level.setBlock(at(pos, y), Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        int first = ClimaxDeepConverter.convertNow(level, pos);
        if (first <= 0) {
            helper.fail("1 回目で 1 ブロックも置き換わらなかった");
            return;
        }
        int second = ClimaxDeepConverter.convertNow(level, pos);
        if (second != 0) {
            helper.fail("2 回目でも " + second + " ブロック置き換えた(冪等でない = 無限に働き続ける)");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>立方体でないブロックは、どの座標でも必ず覆われる。</b>
     *
     * <p>汎用の汚染ブロックに潰すと<b>形が失われる</b>ので抽選に掛けない。ただ残すだけでも
     * いけない —— 記録が付かないと<b>金床や砥石の UI が開いてしまう</b>(実際に報告された)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "climaxDeep")
    public static void shapedBlocksAreAlwaysCovered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 4);
        Block[] shaped = {Blocks.ANVIL, Blocks.GRINDSTONE, Blocks.POINTED_DRIPSTONE, Blocks.GLASS_PANE};

        for (int i = 0; i < shaped.length; i++) {
            BlockState state = shaped[i].defaultBlockState();
            for (int y = 70; y < 86; y++) {
                BlockPos at = new BlockPos(pos.getMinBlockX() + 4 + i, y, pos.getMinBlockZ() + 4);
                level.setBlock(at, state, Block.UPDATE_CLIENTS);
                BlockState result = CorruptionRegistry.corrupt(level, at, state, level.random);
                if (!CorruptionRegistry.noChange(state, result)) {
                    helper.fail(shaped[i] + " が y=" + y + " で置き換えられた(形が失われる): " + result);
                    return;
                }
                if (!OvergrownLayerData.isCovered(level, at)) {
                    helper.fail(shaped[i] + " が y=" + y + " で覆われなかった(UI が開いたままになる)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * <b>看板・旗・頭は触らない。</b> どれもブロックモデルが空で中身を描画器が丸ごと描くので、
     * 差し替えると<b>見えないブロック</b>になり、書いた文字や模様も消える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void bannerLikeBlocksAreLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 5);
        Block[] fragile = {Blocks.OAK_SIGN, Blocks.OAK_HANGING_SIGN, Blocks.WHITE_BANNER,
                Blocks.SKELETON_SKULL, Blocks.DECORATED_POT};

        for (int i = 0; i < fragile.length; i++) {
            BlockState state = fragile[i].defaultBlockState();
            BlockPos at = new BlockPos(pos.getMinBlockX() + 6 + i, 72, pos.getMinBlockZ() + 6);
            level.setBlock(at, state, Block.UPDATE_CLIENTS);
            BlockState result = CorruptionRegistry.corrupt(level, at, state, level.random);
            if (!CorruptionRegistry.noChange(state, result)) {
                helper.fail(fragile[i] + " が置き換えられた(見えないブロックになる): " + result);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>「立方体か」を座標なしでも同じに答えられること。</b>
     *
     * <p>苔を<b>テクスチャの形に切り抜くか</b>はこの答えで決まる(2026-09-23)——
     * 松明や鍾乳石(立方体でない)は切り抜かないと<b>何も無い空中に苔の板</b>が浮き、
     * ガラス(立方体)を切り抜くと<b>窓が覆われなくなる</b>。
     *
     * <p>描画は 2 か所から呼ぶ。置き換えずに被せるマスは座標を持っているが、
     * 「覆われて動かなくなったブロック」の模型は<b>化けている元のブロック状態しか持っていない</b>。
     * 両方が食い違うと、同じブロックが置き方によって違う見た目になる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void cubeTestAgreesWithAndWithoutPosition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos pos = testChunk(level, 6);
        Block[] cubes = {Blocks.STONE, Blocks.GLASS, Blocks.WHITE_CONCRETE, Blocks.ICE, Blocks.OAK_LEAVES};
        Block[] shaped = {Blocks.TORCH, Blocks.POINTED_DRIPSTONE, Blocks.ANVIL, Blocks.GRINDSTONE,
                Blocks.IRON_BARS, Blocks.GLASS_PANE, Blocks.OAK_STAIRS, Blocks.LADDER};

        for (int i = 0; i < cubes.length + shaped.length; i++) {
            boolean expected = i < cubes.length;
            Block block = expected ? cubes[i] : shaped[i - cubes.length];
            BlockState state = block.defaultBlockState();
            BlockPos at = new BlockPos(pos.getMinBlockX() + 2 + i, 72, pos.getMinBlockZ() + 2);
            level.setBlock(at, state, Block.UPDATE_CLIENTS);

            if (OvergrownLayerField.isPlainCube(state) != expected) {
                helper.fail(block + " の「立方体か」が " + !expected + " になっている(座標なし)");
                return;
            }
            if (OvergrownLayerField.isPlainCube(level, at, state) != expected) {
                helper.fail(block + " の「立方体か」が " + !expected + " になっている(座標あり)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>地脈は木の上ではなく地面に敷く。</b>
     *
     * <p>高さの起点に使う {@code OCEAN_FLOOR} は「動きを止める最上段」なので、
     * <b>木の幹も葉も地表として拾ってしまう</b> —— 実際に地脈が木のてっぺんに生えた。
     * 起点から下へ降りて、{@code #ley_vein_replaceable} の地面に敷くこと。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "climaxDeep")
    public static void leyVeinLandsOnGroundNotTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 20, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        BlockPos ground = findVeinColumn(level, chunk);
        if (ground == null) {
            helper.succeed(); // このチャンクに地脈が通っていない。場の作りは LeyVeinGameTests が見ている。
            return;
        }

        level.setBlock(ground, ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int i = 1; i <= 4; i++) {
            level.setBlock(ground.above(i), ModBlocks.OVERGROWN_LOG.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(ground.above(5), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState(), Block.UPDATE_CLIENTS);

        LeyVeinPlacer.place(level, chunk);

        if (!level.getBlockState(ground).is(ModBlocks.LEY_VEIN.get())) {
            helper.fail("地面に地脈が敷かれていない: " + level.getBlockState(ground));
            return;
        }
        for (int i = 1; i <= 5; i++) {
            if (level.getBlockState(ground.above(i)).is(ModBlocks.LEY_VEIN.get())) {
                helper.fail("木("+ i +" マス上)が地脈に置き換えられた —— 木のてっぺんに生えている");
                return;
            }
        }
        helper.succeed();
    }

    /** そのチャンクの中で、地脈が通っていて周りが空の列を 1 つ探す。 */
    private static BlockPos findVeinColumn(ServerLevel level, ChunkPos chunk) {
        for (int dx = 2; dx < 14; dx++) {
            for (int dz = 2; dz < 14; dz++) {
                int x = chunk.getMinBlockX() + dx;
                int z = chunk.getMinBlockZ() + dz;
                if (LeyVeinField.isVeinAt(level.getSeed(), x, z)) {
                    return new BlockPos(x, 80, z);
                }
            }
        }
        return null;
    }

    /** 対応表の有無の判定そのもの。差し替えるかどうかの入口になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "climaxDeep")
    public static void unmappedBlocksAreRecognised(GameTestHelper helper) {
        Block[] unmapped = {Blocks.IRON_BLOCK, Blocks.WHITE_CONCRETE, Blocks.GLASS,
                Blocks.POINTED_DRIPSTONE, Blocks.OBSIDIAN};
        for (Block block : unmapped) {
            if (!CorruptionRegistry.hasNoCorruptedForm(block.defaultBlockState())) {
                helper.fail(block + " が「対応表にある」と判定された(覆われずに置き換えられてしまう)");
                return;
            }
        }
        Block[] mapped = {Blocks.STONE, Blocks.DIRT, Blocks.OAK_LOG, Blocks.SAND, Blocks.SHORT_GRASS};
        for (Block block : mapped) {
            if (CorruptionRegistry.hasNoCorruptedForm(block.defaultBlockState())) {
                helper.fail(block + " が「対応表に無い」と判定された(汚染されなくなる)");
                return;
            }
        }
        // 対応表に無いブロックは corrupt() がそのまま返す(呼び出し元は noChange で素通りする)。
        BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
        int unchanged = 0;
        for (int i = 0; i < 200; i++) {
            BlockState result = CorruptionRegistry.corrupt(iron, net.minecraft.util.RandomSource.create(i));
            if (CorruptionRegistry.noChange(iron, result)) {
                unchanged++;
            } else if (!result.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                helper.fail("対応表に無いブロックが想定外の変換先になった: " + result);
                return;
            }
        }
        if (unchanged < 100) {
            helper.fail("対応表に無いブロックがほとんど置き換えられている: " + unchanged + "/200 しか素通りしない");
            return;
        }
        helper.succeed();
    }

    /** {@code /ov climax ley} の行き先が必ず見つかり、本当に地脈の上であること。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void nearestVeinAlwaysFindsTheNetwork(GameTestHelper helper) {
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        long seed = 987654321L;
        for (int i = 0; i < 16; i++) {
            int x = i * 137 - 1000;
            int z = i * 211 + 500;
            int[] hit = LeyVeinField.nearestVein(seed, x, z, 1024);
            if (hit == null) {
                helper.fail("(" + x + ", " + z + ") の近くに地脈が見つからなかった");
                return;
            }
            if (!LeyVeinField.isVeinAt(seed, hit[0], hit[1])) {
                helper.fail("返ってきた点が地脈の上ではない: " + hit[0] + ", " + hit[1]);
                return;
            }
            int distance = Math.max(Math.abs(hit[0] - x), Math.abs(hit[1] - z));
            // 網目のセルの中に居れば、境界までは高々セル 1 つぶん。余裕を見て 2 倍を上限にする。
            if (distance > spec.cellSize() * 2.0D) {
                helper.fail("いちばん近い地脈のはずが遠すぎる: " + distance + " ブロック");
                return;
            }
        }
        helper.succeed();
    }

    /** そのテスト専用のチャンク。読み込んでから返す。 */
    private static ChunkPos testChunk(ServerLevel level, int index) {
        ChunkPos pos = new ChunkPos(TEST_CHUNK_X + index * 4, TEST_CHUNK_X);
        level.getChunk(pos.x, pos.z);
        return pos;
    }

    /** 抽選をばらけさせるため、16×4 に散らした位置。 */
    private static BlockPos cubeAt(ChunkPos pos, int baseY, int index) {
        return new BlockPos(pos.getMinBlockX() + (index & 15), baseY + (index >> 4),
                pos.getMinBlockZ() + 3);
    }

    /** そのチャンクの中の決まった 1 列(x/z は固定、y だけ変える)。 */
    private static BlockPos at(ChunkPos pos, int y) {
        return new BlockPos(pos.getMinBlockX() + 8, y, pos.getMinBlockZ() + 8);
    }

    private ClimaxDeepGameTests() {
    }
}
