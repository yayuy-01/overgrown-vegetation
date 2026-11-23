package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterOvergrownContainerGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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
        RandomizableContainerBlockEntity chest = (RandomizableContainerBlockEntity) helper.<BlockEntity>getBlockEntity(rel);
        chest.setLootTable(BuiltInLootTables.VILLAGE_WEAPONSMITH, 0L); // 1.20.1: 種の引数が要る

        corruptNow(helper, rel);

        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(ModBlocks.OVERGROWN_CHEST.get(), rel);
            assertNoDrops(helper);
            RandomizableContainerBlockEntity fresh = (RandomizableContainerBlockEntity) helper.<BlockEntity>getBlockEntity(rel);
            helper.assertTrue(BuiltInLootTables.VILLAGE_WEAPONSMITH.equals(GtCompat.lootTable(fresh)),
                    "loot table not carried over: " + GtCompat.lootTable(fresh));
            // 開封(getItem)で 1 回だけ生成される。生成後はルートテーブルが消え、落下アイテムは依然として無い。
            int generated = 0;
            Container container = (Container) fresh;
            for (int i = 0; i < container.getContainerSize(); i++) {
                generated += container.getItem(i).getCount();
            }
            helper.assertTrue(generated > 0, "loot table did not generate on open");
            helper.assertTrue(GtCompat.lootTable(fresh) == null, "loot table should be consumed after open");
            assertNoDrops(helper);
            helper.succeed();
        });
    }

    /**
     * 実際の汚染経路: 根付いた萌芽体(blight_fern_rooted)を四方から囲む 4 個のチェストが、その株の侵食で変換されても、
     * 落下アイテムが無く中身がそのままであること。どれか 1 個が変換されたら成功。
     *
     * <p><b>2026-11-18 作り直し</b>(揺れるテストの調べ・全体管理了承): 前は汚染シダ(萌芽体)を湧かせて最大 24000 tick 待っていたが、
     * 10-23 から萌芽体は胞子の場の動きで歩き回ってから湿った所に根付く。どこに根付くか・触手の向きの運でチェストに届かない回があり
     * (11-18 夜の全件 3 回で 2 回、9 月から上限を 1200 → 24000 と積んでも直らなかった)。今は根付いた姿をチェストの真ん中に置き、
     * 株と同じ広がり({@code SporeSprouts#addRootedPoint} が株の位置から回す {@link CorruptionSpreader})を毎 tick 何回か直に回す。
     * 広がりは半径の中を塗り終えるまで続くので、隣のチェストには必ず届く(届かずに広げ終えたら、それは本物の不具合として落とす)。
     * 中身が複製されないこと自体は {@code singleChestKeepsContents} などが {@code corruptNow} で確かめている。
     * ここが受け持つのは<b>実際の侵食経路でも同じか</b>。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = BATCH)
    public static void blightFernCorruptsChestWithoutDuplication(GameTestHelper helper) {
        List<BlockPos> spots = List.of(new BlockPos(0, 1, 1), new BlockPos(2, 1, 1),
                new BlockPos(1, 1, 0), new BlockPos(1, 1, 2));
        for (BlockPos rel : spots) {
            helper.setBlock(rel, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
            Container chest = (Container) helper.<BlockEntity>getBlockEntity(rel);
            chest.setItem(4, new ItemStack(Items.DIAMOND, 13));
            chest.setItem(20, new ItemStack(Items.BREAD, 6));
        }
        // 株は真ん中の床(1, 1, 1)の上。株の侵食はこの床から始まり、床の高さのチェストへ広がる
        BlockPos rooted = new BlockPos(1, 2, 1);
        helper.setBlock(rooted, ModBlocks.BLIGHT_FERN_ROOTED.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(rooted);
        CorruptionSpreader spreader = new CorruptionSpreader();
        spreader.seedGround(level, origin);
        helper.onEachTick(() -> {
            for (int i = 0; i < 8 && !spreader.isFinished(); i++) spreader.tick(level, origin);
        });

        helper.succeedWhen(() -> {
            BlockPos converted = null;
            for (BlockPos rel : spots) {
                if (helper.getBlockState(rel).is(ModBlocks.OVERGROWN_CHEST.get())) {
                    converted = rel;
                    break;
                }
            }
            helper.assertTrue(converted != null, spreader.isFinished()
                    ? "株の侵食を広げ終えたのに、どのチェストも変換されていない(" + spreader.getCorruptedCount() + " マス)"
                    : "どのチェストもまだ変換されていない");
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
        List<ItemEntity> drops = GtCompat.entities(helper, EntityType.ITEM);
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
