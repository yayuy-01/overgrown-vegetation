package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 「過繫茂したチェスト/樽」への汚染変換が中身を<b>複製も消失もさせない</b>ことを確かめる GameTest。
 * {@code gradlew.bat runGameTestServer} で実行(ヘッドレス。build.gradle の {@code gameTestServer} run)。
 * テンプレートは {@code data/overgrown_vegetation/structure/gametest_empty7.nbt}(7×7×7、床は石)。
 *
 * <p>検査項目: 変換後のブロックが過繫茂版であること / 落下アイテム({@link ItemEntity})が 1 つも無いこと /
 * 中身の個数が変換前とぴったり同じであること。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OvergrownContainerGameTests {

    private static final String TEMPLATE = "gametest_empty7"; // 名前空間は @GameTestHolder(MODID) が付ける

    /**
     * <b>胞子除去装置のテストと同時に走らせない</b>ためのバッチ。装置の「汚染を止める」効果は
     * <b>チャンク単位で周囲に及ぶ</b>ので、隣のテスト場に装置が立っていると
     * {@code canCorrupt} がここのチェストを弾き、たまに落ちる(実際に何度か落ちた)。
     * バッチを分けると装置のテストと時間帯が重ならない。
     */
    private static final String BATCH = "containers";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OvergrownContainerGameTests.class);
        }
    }

    /** 単体チェスト: placeCorrupted 直呼び(全汚染経路が通る共通の設置処理)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void singleChestKeepsContents(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        Container chest = (Container) helper.<BlockEntity>getBlockEntity(rel);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        chest.setItem(13, new ItemStack(Items.APPLE, 3));
        chest.setItem(26, new ItemStack(Items.IRON_INGOT, 64));

        corruptNow(helper, rel);

        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_CHEST.get(), rel);
            assertNoDrops(helper);
            assertCount(helper, rel, Items.DIAMOND, 5);
            assertCount(helper, rel, Items.APPLE, 3);
            assertCount(helper, rel, Items.IRON_INGOT, 64);
            assertOvergrowth(helper, rel, OvergrownContainers.MAX_LEVEL);
            helper.succeed();
        });
    }

    /** ラージチェスト: 片方を汚染すると相方も変換され、それぞれの中身が保たれる。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void doubleChestKeepsContents(GameTestHelper helper) {
        BlockPos left = new BlockPos(2, 1, 3);   // facing NORTH の LEFT 半分 → 相方は東(x+1)
        BlockPos right = new BlockPos(3, 1, 3);
        helper.setBlock(left, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(right, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        Container a = (Container) helper.<BlockEntity>getBlockEntity(left);
        Container b = (Container) helper.<BlockEntity>getBlockEntity(right);
        a.setItem(0, new ItemStack(Items.DIAMOND, 7));
        b.setItem(5, new ItemStack(Items.GOLD_INGOT, 11));

        corruptNow(helper, left);

        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_CHEST.get(), left);
            helper.assertBlockPresent(ModBlocks.OVERGROWN_CHEST.get(), right);
            assertNoDrops(helper);
            assertCount(helper, left, Items.DIAMOND, 7);
            assertCount(helper, left, Items.GOLD_INGOT, 0);
            assertCount(helper, right, Items.GOLD_INGOT, 11);
            assertCount(helper, right, Items.DIAMOND, 0);
            helper.assertTrue(helper.getBlockState(left).getValue(ChestBlock.TYPE) == ChestType.LEFT, "left half lost TYPE");
            helper.assertTrue(helper.getBlockState(right).getValue(ChestBlock.TYPE) == ChestType.RIGHT, "right half lost TYPE");
            helper.succeed();
        });
    }

    /** 樽: 中身が保たれ、向きも引き継ぐ。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void barrelKeepsContents(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.EAST));
        Container barrel = (Container) helper.<BlockEntity>getBlockEntity(rel);
        barrel.setItem(2, new ItemStack(Items.EMERALD, 9));

        corruptNow(helper, rel);

        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_BARREL.get(), rel);
            assertNoDrops(helper);
            assertCount(helper, rel, Items.EMERALD, 9);
            helper.assertTrue(helper.getBlockState(rel).getValue(BarrelBlock.FACING) == Direction.EAST, "barrel lost FACING");
            helper.succeed();
        });
    }

    /**
     * 未開封のルートテーブル付きチェスト(村・ダンジョンなど): 変換時にルートが地面へばら撒かれず、
     * 新チェストがルートテーブルを引き継いで開封時に 1 回だけ生成される(2026-09-11 の複製バグの再現テスト)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void lootChestDoesNotDuplicate(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        RandomizableContainer chest = (RandomizableContainer) helper.<BlockEntity>getBlockEntity(rel);
        chest.setLootTable(BuiltInLootTables.VILLAGE_WEAPONSMITH);

        corruptNow(helper, rel);

        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_CHEST.get(), rel);
            assertNoDrops(helper);
            RandomizableContainer fresh = (RandomizableContainer) helper.<BlockEntity>getBlockEntity(rel);
            helper.assertTrue(BuiltInLootTables.VILLAGE_WEAPONSMITH.equals(fresh.getLootTable()),
                    "loot table not carried over: " + fresh.getLootTable());
            // 開封(getItem)で 1 回だけ生成される。生成後はルートテーブルが消え、落下アイテムは依然として無い。
            int generated = 0;
            Container container = (Container) fresh;
            for (int i = 0; i < container.getContainerSize(); i++) {
                generated += container.getItem(i).getCount();
            }
            helper.assertTrue(generated > 0, "loot table did not generate on open");
            helper.assertTrue(fresh.getLootTable() == null, "loot table should be consumed after open");
            assertNoDrops(helper);
            helper.succeed();
        });
    }

    /**
     * 実際の汚染経路: 汚染シダ(blight_fern)を隣に湧かせ、その侵食でチェストが変換されるのを待つ。
     * 変換後に落下アイテムが無く、中身がそのままであること。
     *
     * <p>チェストは汚染シダを<b>四方から囲むように 4 個</b>置き、<b>どれか 1 個</b>が変換されたら
     * 成功とする。1 個だけ置いていたときは「たまに落ちるテスト」だった —— 侵食は触手の伸びる向きを
     * 毎回抽選するので、隣に置いてもその 1 マスを選ぶまでの時間が大きくぶれる。
     * 距離を詰めても制限時間を 1200 → 2400 → 3600 と伸ばしても数回に 1 回落ち続けた
     * (2026-09-20)。<b>このテストが見たいのは「変換で中身が複製されないこと」であって
     * 侵食がどの向きへ伸びるかではない</b>ので、向きの運を持ち込まない形に変えた。
     * 中身が複製されないこと自体は {@code singleChestKeepsContents} などが
     * {@code corruptNow} で確かめている。ここが受け持つのは<b>実際の侵食経路でも同じか</b>。
     *
     * <p><b>制限時間も 3600 に戻した</b>(2026-09-21)。4 個に増やしてしばらく通っていたが、
     * テストが増えて走らせ方が変わったら再び数回に 1 回落ちた。
     * 方角の運(4 個)と時間の余裕(3 倍)は<b>別々に効く</b>ので、両方掛けておく。
     * 先に成功すればそこで終わるので、ふつうに通るときの所要時間は変わらない。
     *
     * <p><b>制限時間では直らないと分かった</b>(2026-09-21、テストが 35 本になった時点で測り直した)。
     * 実測の成功率は <b>3600 で 0/2、12000 で 5/6、24000 で 5/7</b> ——
     * 3600 から上げる効果はあったが、<b>12000 と 24000 に差が無い</b>。
     * 時間さえあれば必ず届く、という性質の失敗ではない。
     * 上限を 24000 にしてあるのは「上げても通るぶんには 1 tick も損しない」から
     * (落ちるときだけ上限を使い切る。実際、落ちた回 23.9 秒 対 通った回 20.8 秒)であって、
     * <b>これで直っているという意味ではない</b>。
     *
     * <p><b>残っている本当の原因は未特定</b>。侵食が一度も 4 個のどれにも向かないまま終わる回がある。
     * 上限を積むのは対症療法(1200 → 2400 → 3600 → 24000)なので、直すなら
     * <b>触手がどちらへ伸びるかの抽選をテストから追い出す</b> —— 侵食を狙った向きへ 1 回進める
     * 入口を用意して、そこを通して確かめる形にする。
     * <b>ここが見たいのは変換で中身が複製されないことであって、侵食の速さでも向きでもない</b>。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 24000, batch = BATCH)
    public static void blightFernCorruptsChestWithoutDuplication(GameTestHelper helper) {
        List<BlockPos> spots = List.of(new BlockPos(0, 1, 1), new BlockPos(2, 1, 1),
                new BlockPos(1, 1, 0), new BlockPos(1, 1, 2));
        for (BlockPos rel : spots) {
            helper.setBlock(rel, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
            Container chest = (Container) helper.<BlockEntity>getBlockEntity(rel);
            chest.setItem(4, new ItemStack(Items.DIAMOND, 13));
            chest.setItem(20, new ItemStack(Items.BREAD, 6));
        }
        helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(1, 1, 1));

        helper.succeedWhen(() -> {
            BlockPos converted = null;
            for (BlockPos rel : spots) {
                if (helper.getBlockState(rel).is(ModBlocks.OVERGROWN_CHEST.get())) {
                    converted = rel;
                    break;
                }
            }
            helper.assertTrue(converted != null, "どのチェストもまだ変換されていない");
            assertNoDrops(helper);
            assertCount(helper, converted, Items.DIAMOND, 13);
            assertCount(helper, converted, Items.BREAD, 6);
        });
    }

    // --- helpers ---

    private static void corruptNow(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = level.getBlockState(abs);
        helper.assertTrue(CorruptionRegistry.canCorrupt(level, abs, state), "canCorrupt() rejected " + state);
        BlockState corrupted = CorruptionRegistry.corrupt(level, abs, state, level.random);
        helper.assertTrue(corrupted.is(ModBlocks.OVERGROWN_CHEST.get()) || corrupted.is(ModBlocks.OVERGROWN_BARREL.get()),
                "corrupt() should map to an overgrown container, got " + corrupted);
        CorruptionRegistry.placeCorrupted(level, abs, state, corrupted, Block.UPDATE_ALL);
    }

    private static void assertNoDrops(GameTestHelper helper) {
        List<ItemEntity> drops = helper.getEntities(EntityType.ITEM);
        helper.assertTrue(drops.isEmpty(), "items were dropped on conversion: " + drops.stream()
                .map(e -> e.getItem().toString()).toList());
    }

    private static void assertCount(GameTestHelper helper, BlockPos rel, Item item, int expected) {
        BlockEntity be = helper.getBlockEntity(rel);
        helper.assertTrue(be instanceof Container, "no container at " + rel + " (" + be + ")");
        Container container = (Container) be;
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        helper.assertTrue(count == expected, item + " count at " + rel + " = " + count + ", expected " + expected);
    }

    private static void assertOvergrowth(GameTestHelper helper, BlockPos rel, int expected) {
        BlockState state = helper.getBlockState(rel);
        helper.assertTrue(state.hasProperty(OvergrownContainers.OVERGROWTH)
                && state.getValue(OvergrownContainers.OVERGROWTH) == expected, "overgrowth != " + expected + ": " + state);
    }

    private OvergrownContainerGameTests() {
    }
}
