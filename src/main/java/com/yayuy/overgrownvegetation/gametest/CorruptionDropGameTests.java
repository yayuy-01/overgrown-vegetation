package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.StrandedPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 汚染が植物のアイテムを撒かないことを確かめる GameTest({@link CorruptionDropSuppressor})。
 * {@code gradlew.bat runGameTestServer} で実行。テンプレートは {@code gametest_empty7}(床は相対 y=1)。
 *
 * <p>草ブロックの上の花・草は、草ブロックを汚染すると以前は<b>その場に残った</b>
 * ({@link CorruptionRegistry#placeCorrupted} は形状更新の連鎖を打ち切るので再評価されない)。
 * 過繁茂した地面は {@code #minecraft:dirt} に入っていないので、この植物はもう立っていられない。
 * 次に隣が {@code UPDATE_ALL} で書き換わったとき、植物は連鎖で壊れる(破壊音・粒子、範囲の外ならアイテムも)。
 * ワールドに溜まっていた小麦の種・花のアイテムはこの経路と推定した(2026-09-24 の調査)。
 * 2026-10-06 から、置いた直後に {@link StrandedPlants#clearAround} が生き残れない植物を音・粒子・ドロップ無しで消す
 * (ユーザー指定。ワールドに入った瞬間・飛行中に破壊が 1 秒に数百件出ていた)。
 *
 * <ul>
 *     <li>{@code groundCorruptionClearsStrandedPlantsQuietly} —— 地面の汚染そのもの。生き残れない植物はその場で消え、アイテムは出ない。</li>
 *     <li>{@code strandedColumnClearsWholeAndSurvivorsStay} —— 竹の茎は丸ごと消え、汚染した地面でも立っていられる植物(苔のカーペット)は残る。</li>
 *     <li>{@code strandedPlantsBreakWithoutDropsInScope} —— 別の経路で取り残された植物(石の上の花)の隣を、範囲の中で書き換える。
 *     植物は連鎖で壊れ、アイテムは出ない。</li>
 *     <li>{@code strandedPlantsStillDropOutsideScope} —— 同じことを範囲の外で。<b>従来どおりアイテムが出る</b>
 *     (抑止が汚染の外へ漏れていないことの確認)。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CorruptionDropGameTests {

    private static final String TEMPLATE = "gametest_empty7"; // 名前空間は @GameTestHolder(MODID) が付ける

    /**
     * 胞子除去装置のテストと時間帯を分けるバッチ。装置は<b>チャンク単位で</b> {@code canCorrupt} を止めるので、
     * 隣のテスト場に装置があると地面の汚染が弾かれる({@link OvergrownContainerGameTests} と同じ事情)。
     */
    private static final String BATCH = "corruption_drops";

    /** 花(1 本で必ず花 1 個を落とす)とその地面。 */
    private static final BlockPos FLOWER = new BlockPos(2, 2, 3);
    /** 草(小麦の種は確率なので数の判定には使わない)とその地面。花の隣。 */
    private static final BlockPos GRASS = new BlockPos(3, 2, 3);
    /** 花の隣の空きマス。ここを書き換えて植物を再評価させる。 */
    private static final BlockPos TRIGGER = new BlockPos(1, 2, 3);

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CorruptionDropGameTests.class);
        }
    }

    /** 地面を汚染すると、上の生き残れない植物はその場で消え(2026-10-06 から)、アイテムは出ない。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void groundCorruptionClearsStrandedPlantsQuietly(GameTestHelper helper) {
        plantAndCorrupt(helper);
        helper.assertTrue(!CorruptionDropSuppressor.isActive(), "drop suppression scope leaked after placeCorrupted");
        // 予定 tick も積まれていない(消したあとで「壊れる」が遅れて来ない)。
        helper.assertBlockPresent(Blocks.AIR, FLOWER);
        helper.assertBlockPresent(Blocks.AIR, GRASS);

        helper.runAfterDelay(2, () -> {
            helper.assertBlockPresent(Blocks.AIR, FLOWER);
            helper.assertBlockPresent(Blocks.AIR, GRASS);
            assertNoDrops(helper);
            helper.succeed();
        });
    }

    /**
     * 竹の根元の地面を汚染すると茎が丸ごと消える(連鎖を上へ追う)。汚染した地面でも立っていられる植物
     * (苔のカーペット: 下が空気でなければよい)は残る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void strandedColumnClearsWholeAndSurvivorsStay(GameTestHelper helper) {
        BlockPos bamboo = new BlockPos(2, 2, 3);
        BlockPos carpet = new BlockPos(4, 2, 3);
        helper.setBlock(bamboo.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        helper.setBlock(carpet.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int i = 0; i < 4; i++) {
            helper.setBlock(bamboo.above(i), Blocks.BAMBOO.defaultBlockState());
        }
        helper.setBlock(carpet, Blocks.MOSS_CARPET.defaultBlockState());

        corruptGround(helper, bamboo.below());
        corruptGround(helper, carpet.below());

        for (int i = 0; i < 4; i++) {
            helper.assertBlockPresent(Blocks.AIR, bamboo.above(i));
        }
        helper.assertBlockPresent(Blocks.MOSS_CARPET, carpet);
        helper.runAfterDelay(2, () -> {
            for (int i = 0; i < 4; i++) {
                helper.assertBlockPresent(Blocks.AIR, bamboo.above(i));
            }
            helper.assertBlockPresent(Blocks.MOSS_CARPET, carpet);
            assertNoDrops(helper);
            helper.succeed();
        });
    }

    /**
     * 取り残された植物(石の上の花・草。汚染以外の経路で残ったものの代わり)の隣を、範囲の中で書き換える
     * (汚染直後の装飾植物の設置と同じ {@code UPDATE_ALL})。花が壊れ、その空気化で隣の草も連鎖で壊れる(従来どおり)が、アイテムは出ない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void strandedPlantsBreakWithoutDropsInScope(GameTestHelper helper) {
        plantStranded(helper);

        CorruptionDropSuppressor.run(() -> helper.setBlock(TRIGGER, Blocks.STONE.defaultBlockState()));
        helper.assertTrue(!CorruptionDropSuppressor.isActive(), "drop suppression scope leaked after run");

        // 中で例外が出ても範囲は閉じる。
        try {
            CorruptionDropSuppressor.run(() -> {
                throw new IllegalStateException("expected");
            });
        } catch (IllegalStateException expected) {
            // 想定どおり
        }
        helper.assertTrue(!CorruptionDropSuppressor.isActive(), "drop suppression scope leaked after an exception");

        helper.runAfterDelay(2, () -> {
            helper.assertBlockPresent(Blocks.AIR, FLOWER);
            helper.assertBlockPresent(Blocks.AIR, GRASS);
            assertNoDrops(helper);
            helper.succeed();
        });
    }

    /** 範囲の外で同じことをすると、従来どおり花が落ちる(抑止が汚染の外へ漏れていない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void strandedPlantsStillDropOutsideScope(GameTestHelper helper) {
        plantStranded(helper);

        helper.setBlock(TRIGGER, Blocks.STONE.defaultBlockState());

        helper.runAfterDelay(2, () -> {
            helper.assertBlockPresent(Blocks.AIR, FLOWER);
            helper.assertBlockPresent(Blocks.AIR, GRASS);
            List<ItemEntity> drops = helper.getEntities(EntityType.ITEM);
            helper.assertTrue(drops.stream().anyMatch(e -> e.getItem().is(Items.DANDELION)),
                    "a dandelion should drop outside the corruption scope, got " + describe(drops));
            helper.succeed();
        });
    }

    // --- helpers ---

    /** 草ブロックの上に花と草を植え、両方の草ブロックを汚染する(全汚染経路が通る共通の設置処理)。 */
    private static void plantAndCorrupt(GameTestHelper helper) {
        helper.setBlock(FLOWER.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        helper.setBlock(GRASS.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        helper.setBlock(FLOWER, Blocks.DANDELION.defaultBlockState());
        helper.setBlock(GRASS, Blocks.GRASS.defaultBlockState());

        corruptGround(helper, FLOWER.below());
        corruptGround(helper, GRASS.below());

        helper.assertTrue(helper.getBlockState(FLOWER.below()).is(ModTags.CORRUPTED),
                "ground under the flower was not corrupted: " + helper.getBlockState(FLOWER.below()));
        helper.assertTrue(helper.getBlockState(GRASS.below()).is(ModTags.CORRUPTED),
                "ground under the grass was not corrupted: " + helper.getBlockState(GRASS.below()));
        assertNoDrops(helper);
    }

    /**
     * 石の上に花と草を置く(そこでは立っていられないが、置いただけでは再評価されないので残る)。
     * 汚染で取り残された植物は {@link StrandedPlants} がその場で消すようになったので、ドロップ抑止の範囲を見るテストはこちらで作る。
     */
    private static void plantStranded(GameTestHelper helper) {
        helper.setBlock(FLOWER.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(GRASS.below(), Blocks.STONE.defaultBlockState());
        // 形状更新を出さずに置く(出すと、後に置いた草の形状更新で先の花がその場で壊れてしまう)。
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        helper.getLevel().setBlock(helper.absolutePos(FLOWER), Blocks.DANDELION.defaultBlockState(), quiet);
        helper.getLevel().setBlock(helper.absolutePos(GRASS), Blocks.GRASS.defaultBlockState(), quiet);
        helper.assertBlockPresent(Blocks.DANDELION, FLOWER);
        helper.assertBlockPresent(Blocks.GRASS, GRASS);
        assertNoDrops(helper);
    }

    private static void corruptGround(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = level.getBlockState(abs);
        helper.assertTrue(CorruptionRegistry.canCorrupt(level, abs, state), "canCorrupt() rejected " + state);
        BlockState corrupted = CorruptionRegistry.corrupt(level, abs, state, level.random);
        helper.assertTrue(!CorruptionRegistry.noChange(state, corrupted), "corrupt() left " + state + " unchanged");
        CorruptionRegistry.placeCorrupted(level, abs, state, corrupted, Block.UPDATE_ALL);
    }

    private static void assertNoDrops(GameTestHelper helper) {
        List<ItemEntity> drops = helper.getEntities(EntityType.ITEM);
        helper.assertTrue(drops.isEmpty(), "items were dropped: " + describe(drops));
    }

    private static List<String> describe(List<ItemEntity> drops) {
        return drops.stream().map(e -> e.getItem().toString()).toList();
    }

    private CorruptionDropGameTests() {
    }
}
