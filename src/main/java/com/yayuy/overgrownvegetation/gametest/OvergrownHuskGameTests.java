package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
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
