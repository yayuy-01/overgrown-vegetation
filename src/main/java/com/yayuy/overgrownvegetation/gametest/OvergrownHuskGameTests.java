package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * <b>覆われて動かなくなったブロック</b>({@link OvergrownHuskBlock})のテスト(2026-09-22)。
 *
 * <p>他 MOD の機械が「植物に覆われているのに動き続ける」のを止めるための仕掛けなので、
 * 守りたいのは 4 つ。
 * <ul>
 *     <li><b>中身を持つブロックは差し替わる</b> —— 描画で苔を被せるだけでは動いてしまう。</li>
 *     <li><b>中のアイテムが消えない</b> —— 置き換えでその場にばら撒かれてもいけないし、
 *     壊したときに返ってこないのもいけない(要望)。</li>
 *     <li><b>差し替えてはいけないものを差し替えない</b> —— この MOD の設備、チェスト・樽
 *     (専用の過繫茂ブロックがある)、{@code #husk_immune} のブロック。</li>
 *     <li><b>見た目に使う元のブロック状態を覚えている</b> —— これを落とすと、
 *     世界中の覆われたブロックが同じ緑の立方体になる。</li>
 * </ul>
 *
 * <p>テスト場ではなく<b>遠くの専用チャンク</b>を使う。ブロックを壊してアイテムを撒くので、
 * 同じチャンクで並行して走る別のテストに拾われないようにするため。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OvergrownHuskGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OvergrownHuskGameTests.class);
        }

        private Registration() {
        }
    }

    /** {@link ClimaxDeepGameTests} と同じ列の続き。テストごとに 1 チャンクずつ。 */
    private static final int TEST_CHUNK_X = 30_100;

    /** かまどを覆うと、見た目はかまどのまま機能を失い、中のアイテムが残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void machineBecomesHusk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = coveredSpot(level, 0);
        BlockState furnace = Blocks.FURNACE.defaultBlockState();
        level.setBlock(pos, furnace, Block.UPDATE_CLIENTS);
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            helper.fail("かまどの中身が作られていない");
            return;
        }
        container.setItem(0, new ItemStack(Items.COAL, 5));

        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, furnace, level.random);
        if (!corrupted.is(ModBlocks.OVERGROWN_HUSK.get())) {
            helper.fail("かまどが『覆われたブロック』にならなかった: " + corrupted);
            return;
        }
        CorruptionRegistry.placeCorrupted(level, pos, furnace, corrupted, Block.UPDATE_CLIENTS);

        if (!level.getBlockState(pos).is(ModBlocks.OVERGROWN_HUSK.get())) {
            helper.fail("置き換わっていない: " + level.getBlockState(pos));
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof OvergrownHuskBlockEntity husk)) {
            helper.fail("覆われたブロックに中身が付いていない");
            return;
        }
        if (!husk.mimic().is(Blocks.FURNACE)) {
            helper.fail("元のブロックを覚えていない(見た目が予備の模型になる): " + husk.mimic());
            return;
        }
        if (husk.contentsSize() != 1) {
            helper.fail("中のアイテムを引き継いでいない: " + husk.contentsSize() + " スタック");
            return;
        }
        // 置き換えでその場にばら撒かれていないこと(汚染はドロップを出さない、が全体の決まり)。
        if (!itemsNear(level, pos).isEmpty()) {
            helper.fail("覆った瞬間に中身が地面へこぼれた");
            return;
        }
        helper.succeed();
    }

    /**
     * 覆われたブロックを壊すと、<b>元のブロックと中身がまとめて返ってくる</b>(要望)。
     *
     * <p><b>ここだけはテスト場の中でやる。</b> 遠くのチャンクを {@code level.getChunk} で
     * 読み込んだだけでは、そこに湧かせたエンティティが
     * {@code getEntitiesOfClass} に<b>見えない</b>(エンティティの区画が「追跡中」にならないため)。
     * ドロップを数えるテストは、必ずプレイヤー(テスト場)の近くでやること。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void huskDropsContentsWhenBroken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = coveredSpotInArena(helper);
        BlockState furnace = Blocks.FURNACE.defaultBlockState();
        level.setBlock(pos, furnace, Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        }
        CorruptionRegistry.placeCorrupted(level, pos, furnace,
                CorruptionRegistry.corrupt(level, pos, furnace, level.random), Block.UPDATE_CLIENTS);
        for (ItemEntity stray : itemsNear(level, pos)) {
            stray.discard(); // 念のため掃除(ここまでで落ちているはずはない)
        }

        level.removeBlock(pos, false);

        int ingots = 0;
        int furnaces = 0;
        for (ItemEntity item : itemsNear(level, pos)) {
            if (item.getItem().is(Items.IRON_INGOT)) {
                ingots += item.getItem().getCount();
            } else if (item.getItem().is(Items.FURNACE)) {
                furnaces += item.getItem().getCount();
            }
            item.discard();
        }
        if (ingots != 3) {
            helper.fail("壊しても中身が返ってこない(鉄インゴット " + ingots + " 個 / 期待 3 個)");
            return;
        }
        if (furnaces != 1) {
            helper.fail("壊しても元のブロックが返ってこない(かまど " + furnaces + " 個 / 期待 1 個)");
            return;
        }
        helper.succeed();
    }

    /** 差し替えてはいけないものを差し替えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "climaxDeep")
    public static void protectedBlocksAreNeverEncased(GameTestHelper helper) {
        // この MOD の設備(動いてもらわないと困る)。名前空間で弾いている。
        if (OvergrownHuskBlock.canEncase(ModBlocks.RESEARCH_TABLE.get().defaultBlockState())) {
            helper.fail("この MOD の研究台が覆われる対象になっている");
            return;
        }
        // チェスト・樽は専用の「過繫茂したチェスト/樽」がある。
        if (OvergrownHuskBlock.canEncase(Blocks.CHEST.defaultBlockState())) {
            helper.fail("チェストが覆われる対象になっている(過繫茂したチェストにならなくなる)");
            return;
        }
        if (OvergrownHuskBlock.canEncase(Blocks.BARREL.defaultBlockState())) {
            helper.fail("樽が覆われる対象になっている");
            return;
        }
        // #husk_immune(既定でスポナーなど。構造物の仕掛けを黙って壊さないため)。
        if (OvergrownHuskBlock.canEncase(Blocks.SPAWNER.defaultBlockState())) {
            helper.fail("スポナーが覆われる対象になっている(husk_immune タグが効いていない)");
            return;
        }
        // 対応表にあるブロックは普通に汚染される。
        if (OvergrownHuskBlock.canEncase(Blocks.STONE.defaultBlockState())) {
            helper.fail("石が覆われる対象になっている");
            return;
        }
        // <b>差し替えるのは中身を持つものだけ。</b> 金床・砥石・コンクリートは自分で動かないので、
        // 記録 + 右クリック抑止(OvergrownLayerData)に任せる —— BlockEntity を増やさないため。
        Block[] neverEncased = {Blocks.ANVIL, Blocks.GRINDSTONE, Blocks.WHITE_CONCRETE, Blocks.GLASS};
        for (Block block : neverEncased) {
            if (OvergrownHuskBlock.canEncase(block.defaultBlockState())) {
                helper.fail(block + " まで差し替えている(BlockEntity が無駄に増える)");
                return;
            }
        }
        // 看板・旗・頭はブロックモデルが空なので、差し替えると見えないブロックになる。
        Block[] fragile = {Blocks.OAK_SIGN, Blocks.OAK_HANGING_SIGN, Blocks.WHITE_BANNER,
                Blocks.SKELETON_SKULL, Blocks.DECORATED_POT};
        for (Block block : fragile) {
            if (OvergrownHuskBlock.canEncase(block.defaultBlockState())) {
                helper.fail(block + " が差し替えの対象になっている(見えないブロックになる)");
                return;
            }
        }
        // かまどは対象。ここが false になると仕掛け全体が死ぬ。
        if (!OvergrownHuskBlock.canEncase(Blocks.FURNACE.defaultBlockState())) {
            helper.fail("かまどが覆われる対象になっていない(encase が切れている?)");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>機械は抽選に掛けない。</b> 立方体のブロックは 30% で汎用の汚染ブロックになるが、
     * 中身を持つブロックで同じことをすると<b>中のアイテムごと消える</b>。どの座標でも必ず
     * 「覆われたブロック」になること。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void machinesAreNeverLeftToChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 8, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        BlockState furnace = Blocks.FURNACE.defaultBlockState();

        // 抽選に外れるマスを含む高さをまとめて試す。1 つでも別のブロックになったら仕様が崩れている。
        for (int y = 70; y < 102; y++) {
            BlockPos pos = new BlockPos(chunk.getMinBlockX() + 8, y, chunk.getMinBlockZ() + 8);
            level.setBlock(pos, furnace, Block.UPDATE_CLIENTS);
            BlockState result = CorruptionRegistry.corrupt(level, pos, furnace, level.random);
            if (!result.is(ModBlocks.OVERGROWN_HUSK.get())) {
                helper.fail("y=" + y + " のかまどが『覆われたブロック』にならなかった(抽選に掛けている?): "
                        + result);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>覆っただけのマスの上にも装飾植物が生える</b>(2026-09-29、要望)。
     *
     * <p>対応表に無いブロックは置き換わらず覆った記録が付くだけなので、汚染の各経路は
     * {@code noChange} で早々に折り返していた —— その結果、<b>覆われた壁にだけ下草も蔦も
     * 生えていなかった</b>。普通の汚染ブロックと同じ見た目になるかを見る。
     *
     * <p>使うのは白いコンクリート(2026-10-05 までは鉄ブロック。建築タブのブロックは汚染で覆われたブロックに置き換わるようになり、
     * 記録で覆う経路を通らなくなった)。<b>立方体でないブロックは足場にならない</b> —— 金床で書いたら
     * 1 本も生えなかった(上面が 16×10 で {@code isFaceSturdy} が false)。覆われた階段や金床に
     * 草が生えないのは仕様どおりで、生えるのは<b>面が固い覆われたブロック</b>だけ。
     * ただし立方体は 7 割の抽選に掛かるので、外れて汎用ブロックに化けたぶんは数から外す。
     *
     * <p><b>3 マス間隔で置くこと。</b> 1 マスおきだと、抽選に外れて汎用ブロックに化けた隣のマス
     * (こちらは普通の汚染なので元から植物が生える)と<b>隣接マスを共有</b>してしまい、
     * 覆ったブロックの手柄として数えてしまう —— 仕掛けを外しても通るテストになっていた(実際になった)。
     *
     * <p>生えるかどうかは抽選なので
     * <b>1 本でも生えたか</b>だけを見る。遠いチャンクは実行をまたいで残るので、
     * <b>先に空気へ戻して覆った記録を切らす</b>こと —— 残っていると「既に覆われていた」扱いになり、
     * 狙いどおり 1 本も生えない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "climaxDeep")
    public static void coveredBlocksGrowPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 16, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        int floorY = 74;

        // 更地に戻す(前回の金床と覆った記録を消す)。
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos at = new BlockPos(chunk.getMinBlockX() + dx, floorY + dy,
                            chunk.getMinBlockZ() + dz);
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    // 空気にしただけでは古い記録は消えない —— 記録が切れるのは isCovered を
                    // 通ったときだけ(OvergrownLayerData の仕掛け)。ここで 1 回通して切らないと、
                    // 前回の実行の記録が残ったまま「既に覆われていた」扱いになり、
                    // 仕掛けが効いていても 1 本も生えない(実際にこれで 2 回落とした)。
                    OvergrownLayerData.isCovered(level, at);
                }
            }
        }
        List<BlockPos> blocks = new java.util.ArrayList<>();
        for (int dx = 1; dx < 16; dx += 3) {
            for (int dz = 1; dz < 16; dz += 3) {
                BlockPos at = new BlockPos(chunk.getMinBlockX() + dx, floorY + 1, chunk.getMinBlockZ() + dz);
                level.setBlock(at.below(), Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(at, Blocks.WHITE_CONCRETE.defaultBlockState(), Block.UPDATE_CLIENTS);
                blocks.add(at);
            }
        }

        for (BlockPos at : blocks) {
            CorruptionSpreader.corruptSingle(level, at);
        }

        int covered = 0;
        int withPlants = 0;
        for (BlockPos at : blocks) {
            // 抽選に外れたぶんは汎用の汚染ブロックに化けている(覆われていない)。
            if (!level.getBlockState(at).is(Blocks.WHITE_CONCRETE) || !OvergrownLayerData.isCovered(level, at)) {
                continue;
            }
            covered++;
            // 真上と 4 方向。真下はバリアなので見ない。
            if (!level.getBlockState(at.above()).isAir()
                    || !level.getBlockState(at.north()).isAir() || !level.getBlockState(at.south()).isAir()
                    || !level.getBlockState(at.east()).isAir() || !level.getBlockState(at.west()).isAir()) {
                withPlants++;
            }
        }
        if (covered == 0) {
            helper.fail("コンクリートが 1 つも覆われていない(覆う経路そのものが通っていない)");
            return;
        }
        if (withPlants == 0) {
            helper.fail("覆った " + covered + " 個のブロックのまわりに植物が 1 本も生えていない");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>覆った記録</b>({@link com.yayuy.overgrownvegetation.corruption.OvergrownLayerData})の出し入れ。
     *
     * <p>中身を持たないブロックは置き換えないので、<b>この記録だけが「覆われている」証拠</b>になる ——
     * 苔を描くのも、右クリックを止めるのも、ここを見て決める。
     *
     * <p>もう 1 つ大事なのが<b>古い記録を引きずらないこと</b>。プレイヤーが覆われた金床を壊して
     * 石を置いたら、その石は覆われていない扱いにならなければいけない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void coverageRecordSurvivesAndExpires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 12, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        BlockPos pos = new BlockPos(chunk.getMinBlockX() + 8, 75, chunk.getMinBlockZ() + 8);

        level.setBlock(pos, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (OvergrownLayerData.isCovered(level, pos)) {
            helper.fail("まだ覆っていないのに覆われている");
            return;
        }
        if (!OvergrownLayerData.cover(level, pos)) {
            helper.fail("記録できなかった");
            return;
        }
        if (!OvergrownLayerData.isCovered(level, pos)) {
            helper.fail("記録したのに覆われていない扱いになっている");
            return;
        }
        if (OvergrownLayerData.cover(level, pos)) {
            helper.fail("同じマスが二重に記録された");
            return;
        }

        // 壊して別のブロックを置いたら、古い記録は効かない。
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (OvergrownLayerData.isCovered(level, pos)) {
            helper.fail("置き直したブロックが古い記録で覆われたままになっている");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>汚染の触手は、覆ったマスを足場にして先へ進む</b>(2026-09-24 のバグ)。
     *
     * <p>覆ったブロックは置き換わらず {@code #corrupted} タグも持たないので、以前の
     * {@link CorruptionSpreader} は覆った直後のマスを「汚染できない壁」とみなしていた。
     * 未対応ブロックの床に湧いた汚染シダは、足元のまわりを覆っただけで触手が全滅し、
     * 汚染できるマスが残っているのに完了扱いで消えていた。
     *
     * <p>床は金床(立方体でないので抽選に掛からず<b>必ず</b>覆われる)。下はバリア(汚染できない)、
     * 上は空気なので、触手はこの 1 枚の床の中しか進めない。修正前は 5 マス前後で止まっていた。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void spreaderCrossesCoveredBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 16, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        int radius = 5;
        int floorY = 200;
        BlockPos origin = new BlockPos(chunk.getMinBlockX() + 8, floorY + 1, chunk.getMinBlockZ() + 8);

        int floorCells = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(origin.getX() + dx, floorY, origin.getZ() + dz);
                level.setBlock(floor.below(), Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS);
                // 前回の実行で覆った記録がワールドに残っている(金床を置き直しても消えない)。
                // 覆う資格の無い石にしてから読むと、記録はそこで捨てられる。
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                OvergrownLayerData.isCovered(level, floor);
                level.setBlock(floor, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (dx * dx + dz * dz <= radius * radius) {
                    floorCells++;
                }
            }
        }

        CorruptionSpreader spreader = new CorruptionSpreader();
        spreader.seedGround(level, origin);
        for (int i = 0; i < 2000 && !spreader.isFinished(); i++) {
            spreader.tick(level, origin, radius, 0, 0, 4, 8);
        }
        if (!spreader.isFinished()) {
            helper.fail("2000 サイクル回しても汚染が終わらない");
            return;
        }

        int covered = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(origin.getX() + dx, floorY, origin.getZ() + dz);
                if (level.getBlockState(floor).is(Blocks.ANVIL) && OvergrownLayerData.isCovered(level, floor)) {
                    covered++;
                }
            }
        }
        // 触手はわざと隙間を残すので全部は埋まらない。止まっていれば 5 前後、進めていれば半径いっぱいまで届く。
        if (covered < 20) {
            helper.fail("覆ったマスで触手が止まっている: " + covered + " / " + floorCells + " マス");
            return;
        }
        if (spreader.getCorruptedCount() < covered) {
            helper.fail("覆ったマスが汚染数に入っていない(次世代の汚染シダが湧かなくなる): "
                    + spreader.getCorruptedCount() + " < " + covered);
            return;
        }
        helper.succeed();
    }

    /**
     * <b>コア半径 0 の侵食(汚染死体と同じ設定)が、種の 1 マスだけで終わらない</b>(2026-09-24 のバグ)。
     *
     * <p>根の出発点の候補は「起点そのもの」と「真下の地面」の 2 マスで、平らな床では起点から出た根は 1 歩も
     * 進めない。以前は 4 本とも起点を引くと(約 6%)その場で全滅していた。同じ床で 40 回回し、
     * 1 回も種だけで終わらないこと(修正前なら 40 回のどこかで止まる確率は約 92%)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "climaxDeep")
    public static void coreRadiusZeroSpreaderNeverStopsAtSeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + 20, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        int radius = 5;
        int floorY = 200;
        BlockPos origin = new BlockPos(chunk.getMinBlockX() + 8, floorY + 1, chunk.getMinBlockZ() + 8);

        for (int trial = 0; trial < 40; trial++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos floor = new BlockPos(origin.getX() + dx, floorY, origin.getZ() + dz);
                    level.setBlock(floor.below(), Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS);
                    // 前の回に覆った記録を捨てる(覆う資格の無い石にしてから読む)。
                    level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    OvergrownLayerData.isCovered(level, floor);
                    level.setBlock(floor, Blocks.ANVIL.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            CorruptionSpreader spreader = new CorruptionSpreader();
            spreader.seedGround(level, origin);
            for (int i = 0; i < 2000 && !spreader.isFinished(); i++) {
                spreader.tick(level, origin, radius, 0, 0, 4, 8);
            }
            if (spreader.getCorruptedCount() <= 1) {
                helper.fail(trial + " 回目: 根が全部起点から出て、種の 1 マスだけで終わった");
                return;
            }
        }
        helper.succeed();
    }

    /** 覆う/覆わないの抽選が、描画側と同じ「座標だけの決定的な関数」であること。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "climaxDeep")
    public static void coverageIsDeterministicAndSane(GameTestHelper helper) {
        int covered = 0;
        for (int i = 0; i < 4096; i++) {
            BlockPos pos = new BlockPos(i & 15, (i >> 4) & 15, i >> 8);
            boolean first = OvergrownLayerField.covered(pos, 0.7D);
            if (first != OvergrownLayerField.covered(pos, 0.7D)) {
                helper.fail("同じ座標で答えが変わった: " + pos);
                return;
            }
            if (first) {
                covered++;
            }
        }
        double ratio = covered / 4096.0D;
        if (ratio < 0.6D || ratio > 0.8D) {
            helper.fail("70% の抽選になっていない: " + String.format("%.1f%%", ratio * 100.0D));
            return;
        }
        if (OvergrownLayerField.covered(BlockPos.ZERO, 0.0D)
                || !OvergrownLayerField.covered(BlockPos.ZERO, 1.0D)) {
            helper.fail("確率 0 / 1 の端が効いていない");
            return;
        }
        helper.succeed();
    }

    /**
     * 抽選に当たっているマスを<b>テスト場の中から</b>探す。テスト場の絶対座標は実行ごとに変わるので、
     * 「ここなら必ず覆われる」と決め打ちできない。7×7 を舐めれば 1 マスも当たらない確率は 0.3^49。
     */
    private static BlockPos coveredSpotInArena(GameTestHelper helper) {
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                BlockPos pos = helper.absolutePos(new BlockPos(x, 2, z));
                if (OvergrownLayerField.covered(pos)) {
                    return pos;
                }
            }
        }
        return helper.absolutePos(new BlockPos(3, 2, 3));
    }

    /** 抽選に当たっている(= 覆われる)マスを、そのテスト専用のチャンクの中から探す。 */
    private static BlockPos coveredSpot(ServerLevel level, int index) {
        ChunkPos chunk = new ChunkPos(TEST_CHUNK_X + index * 4, TEST_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        for (int y = 70; y < 120; y++) {
            BlockPos pos = new BlockPos(chunk.getMinBlockX() + 8, y, chunk.getMinBlockZ() + 8);
            if (OvergrownLayerField.covered(pos)) {
                return pos;
            }
        }
        // 50 マス続けて外れるのは確率 0.3^50。まず起きないが、起きたら分かるように上端を返す。
        return new BlockPos(chunk.getMinBlockX() + 8, 119, chunk.getMinBlockZ() + 8);
    }

    private static List<ItemEntity> itemsNear(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0D));
    }

    private OvergrownHuskGameTests() {
    }
}
