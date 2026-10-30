package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BuddingLeyCrystalBlock;
import com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.item.LeyCompassItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 地脈技術の 1 段目(2026-10-22、序盤の作り直し): 地脈の花・地脈の結晶・地脈探し。
 * <ul>
 *     <li>地脈の花は土と砂に根付き、石には根付かない。壊すと花が落ちる({@link #flowerSurvivesOnSoilAndDropsItself})。</li>
 *     <li>芽吹く地脈の結晶は芽を小 → 中 → 大 → 塊と育て、塊はつるはしで欠片 4 つ、芽と芽吹く結晶は何も落とさない({@link #crystalGrowsFromBudAndDropsShards})。</li>
 *     <li>地脈探しの針の先はいちばん近い線の上(場の関数だけで求まる)。線の上では線に沿った先を指す({@link #finderPointsToNearestLine})。</li>
 *     <li>世界生成の部品とレシピが読み込まれている({@link #worldgenAndRecipeAreLoaded})。花と結晶が線に沿って生える様子はテストクライアントで撮る。</li>
 *     <li>空の地脈電池を汲み上げ器に入れると雫の代わりに充電され、しゃがんで取り出せる({@link #pumpChargesLeyBattery})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyFlowerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyFlowerGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "ley_flowers";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void flowerSurvivesOnSoilAndDropsItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState flower = ModBlocks.LEY_FLOWER.get().defaultBlockState();
        Block[] soils = {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND, Blocks.STONE};
        boolean[] expect = {true, true, true, false};
        for (int i = 0; i < soils.length; i++) {
            BlockPos ground = new BlockPos(1 + i, 1, 2);
            helper.setBlock(ground, soils[i]);
            boolean ok = flower.canSurvive(level, helper.absolutePos(ground.above()));
            if (ok != expect[i]) {
                helper.fail("地脈の花が " + soils[i] + " の上に根付く = " + ok + "(期待 " + expect[i] + ")");
                return;
            }
        }
        List<ItemStack> drops = Block.getDrops(flower, level, helper.absolutePos(new BlockPos(1, 2, 2)), null);
        if (drops.size() != 1 || !drops.get(0).is(ModItems.LEY_FLOWER.get())) {
            helper.fail("地脈の花のドロップが違う: " + drops);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void crystalGrowsFromBudAndDropsShards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos coreRel = new BlockPos(3, 2, 3);
        helper.setBlock(coreRel, ModBlocks.BUDDING_LEY_CRYSTAL.get());
        BlockPos core = helper.absolutePos(coreRel);
        BlockPos above = core.above();
        // 横を塞いで上だけ空ける(芽は空いた面か自分の芽の面にだけ出る。下は床の石。床の高さ y=1 に置くと下が空いて下にも芽が出る)
        for (Direction d : Direction.Plane.HORIZONTAL) {
            helper.setBlock(coreRel.relative(d), Blocks.STONE);
        }
        RandomSource random = RandomSource.create(7L);
        Block[] stages = {ModBlocks.SMALL_LEY_CRYSTAL_BUD.get(), ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get(),
                ModBlocks.LARGE_LEY_CRYSTAL_BUD.get(), ModBlocks.LEY_CRYSTAL.get()};
        for (Block stage : stages) {
            if (!BuddingLeyCrystalBlock.grow(level, core, random)) {
                helper.fail("芽吹く地脈の結晶が育たなかった(" + stage + " の前)");
                return;
            }
            BlockState now = level.getBlockState(above);
            if (!now.is(stage) || now.getValue(AmethystClusterBlock.FACING) != Direction.UP) {
                helper.fail("上の芽の段が違う: " + now + "(期待 " + stage + ")");
                return;
            }
        }
        if (BuddingLeyCrystalBlock.grow(level, core, random)) {
            helper.fail("塊になった後も、塞がれた芽吹く結晶が育った");
            return;
        }
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        List<ItemStack> drops = Block.getDrops(level.getBlockState(above), level, above, null, null, pickaxe);
        int shards = drops.stream().filter(s -> s.is(ModItems.LEY_CRYSTAL_SHARD.get())).mapToInt(ItemStack::getCount).sum();
        if (shards != 4) {
            helper.fail("塊をつるはしで壊した欠片が 4 でない: " + drops);
            return;
        }
        List<ItemStack> budDrops = Block.getDrops(ModBlocks.LARGE_LEY_CRYSTAL_BUD.get().defaultBlockState(), level, above, null, null, pickaxe);
        if (!budDrops.isEmpty()) {
            helper.fail("芽がシルクタッチなしで何か落とした: " + budDrops);
            return;
        }
        List<ItemStack> coreDrops = Block.getDrops(ModBlocks.BUDDING_LEY_CRYSTAL.get().defaultBlockState(), level, core, null, null, pickaxe);
        if (!coreDrops.isEmpty()) {
            helper.fail("芽吹く地脈の結晶が何か落とした: " + coreDrops);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void finderPointsToNearestLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -600; dx <= 600; dx += 75) {
            for (int dz = -600; dz <= 600; dz += 75) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int[] p = LeyVeinField.nearestLinePoint(seed, x, z, LeyCompassItem.SEARCH_RADIUS);
                if (p == null) {
                    helper.fail("地脈の線が見つからない: " + x + ", " + z);
                    return;
                }
                if (!LeyVeinField.isVeinAt(seed, p[0], p[1])) {
                    helper.fail("針の先が線の上でない: " + p[0] + ", " + p[1] + "(強さ " + LeyVeinField.strengthAt(seed, p[0], p[1]) + ")");
                    return;
                }
                // 総当たりの輪の探索で見つかる線の点より遠くない(線の真ん中へ寄せる分 + 6 まで)。さまよって別の線へ飛んでいないこと
                int[] ring = LeyVeinField.nearestVein(seed, x, z, LeyCompassItem.SEARCH_RADIUS);
                double ringD = ring == null ? Double.MAX_VALUE : Math.hypot(ring[0] - x, ring[1] - z);
                double moved = Math.hypot(p[0] - x, p[1] - z);
                if (moved > ringD + 6.0D) {
                    helper.fail("針の先が遠すぎる: 輪の探索では " + ringD + " 先に線があるのに " + moved + " 先(" + x + ", " + z + ")");
                    return;
                }
                // その点に立つと、針は線に沿って 16 ブロック先を指す
                BlockPos on = new BlockPos(p[0], origin.getY(), p[1]);
                BlockPos t = LeyCompassItem.target(level, on);
                if (t == null || on.distSqr(t) < 100.0D) {
                    helper.fail("線の上で針が線に沿った先を指さない: " + on + " → " + t);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** 空の地脈電池を汲み上げ器に入れると、雫の代わりに充電され、しゃがんで素手で取り出すと残りのある地脈電池になる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 300)
    public static void pumpChargesLeyBattery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_PUMP.get());
        BlockPos pos = helper.absolutePos(rel);
        LeyPumpBlockEntity pump = (LeyPumpBlockEntity) helper.getBlockEntity(rel);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.EMPTY_BATTERY.get()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        level.getBlockState(pos).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
        if (!pump.hasBattery() || pump.batteryCharge() != 0 || !player.getMainHandItem().isEmpty()) {
            helper.fail("空の地脈電池が入らない: 中 " + pump.hasBattery() + " / " + pump.batteryCharge() + "・手 " + player.getMainHandItem());
            return;
        }
        helper.runAfterDelay(200, () -> {
            // 効率は場所しだい(最低 5%)。200 tick で少なくとも 200 × 0.05 × CHARGE_PER_TICK 近く入る
            int expectMin = (int) (200 * LeyPumpBlockEntity.MIN_EFFICIENCY * LeyPumpBlockEntity.CHARGE_PER_TICK * 0.8D);
            if (pump.batteryCharge() < expectMin || pump.stored() != 0) {
                helper.fail("充電されていない: 電気 " + pump.batteryCharge() + "(期待 " + expectMin + " 以上)・雫 " + pump.stored());
                return;
            }
            int charge = pump.batteryCharge();
            player.setShiftKeyDown(true);
            level.getBlockState(pos).useWithoutItem(level, player, hit);
            ItemStack got = ItemStack.EMPTY;
            for (ItemStack s : player.getInventory().items) {
                if (s.is(ModItems.BATTERY.get())) {
                    got = s;
                }
            }
            if (got.isEmpty() || pump.hasBattery() || Math.abs(BatteryItem.charge(got) - charge) > 120) {
                helper.fail("取り出した電池が違う: " + got + "(残り " + BatteryItem.charge(got) + "、中は " + charge + ")・中に残る " + pump.hasBattery());
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void worldgenAndRecipeAreLoaded(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ley_vein_plants");
        if (!registries.registryOrThrow(Registries.PLACED_FEATURE).containsKey(id)) {
            helper.fail("placed_feature ley_vein_plants が読み込まれていない");
            return;
        }
        var plains = registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        boolean inPlains = plains.value().getGenerationSettings().features().stream()
                .anyMatch(set -> set.stream().anyMatch(f -> f.is(id)));
        if (!inPlains) {
            helper.fail("平原に地脈の花の生成が入っていない");
            return;
        }
        var recipe = helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ley_compass"));
        if (recipe.isEmpty() || recipe.get().value().getIngredients().stream().noneMatch(i -> i.test(new ItemStack(ModItems.LEY_FLOWER.get())))) {
            helper.fail("地脈探しのレシピに地脈の花が無い");
            return;
        }
        helper.succeed();
    }
}
