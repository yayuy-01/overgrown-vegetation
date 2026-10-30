package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownFruitPlantBlock;
import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.entity.StickyNet;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 役に立つ植物と道具(2026-10-16、サバイバル用アイテムのクラスター 2。ユーザー指定)。
 * <ul>
 *     <li>新しいレシピが全部読み込まれている(result の書き方を間違えると黙って消えるため)({@link #recipesAreLoaded})。</li>
 *     <li>繊維草・実の株・道しるべの花のルートテーブル({@link #plantLootTables})。</li>
 *     <li>実の株は採れる段で右クリックすると実が落ちて芽に戻る({@link #fruitPlantHarvestResetsToSprout})。</li>
 *     <li>熟した炸裂の実は植物側の Mob では爆ぜず、それ以外で爆ぜる({@link #ripeBurstFruitSparesPlantMobs} / {@link #ripeBurstFruitBurstsForOthers})。</li>
 *     <li>地雷は植物側の Mob だけで爆ぜる({@link #burstMineOnlyForPlantMobs})。</li>
 *     <li>投網は周りの生き物を絡め取り、投げた本人は除く({@link #stickyNetRootsAllButThrower})。</li>
 *     <li>機関砲台・遮蔽盾は電池が無いと動かない・基地の砲台は電池なしで動く({@link #turretNeedsBatteryUnlessBaseSupplied} /
 *     {@link #beamShieldNeedsBattery})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SurvivalGearGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SurvivalGearGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_gear";
    /** 機関砲台は 32 ブロック先の隣のテストの Mob を撃つので、自分だけのバッチ。 */
    private static final String TURRET_BATCH = "survival_gear_turret";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void recipesAreLoaded(GameTestHelper helper) {
        String[] ids = {"pebble", "slingshot", "burst_shot", "sticky_shot", "burst_mine", "sticky_net", "glider",
                "bandage", "battery", "ley_compass"};
        for (String id : ids) {
            if (helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id)).isEmpty()) {
                helper.fail("レシピ " + id + " が読み込まれていない");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void plantLootTables(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState fiber = ModBlocks.FIBER_GRASS_CROP.get().defaultBlockState();
        check(helper, drops(level, pos, fiber.setValue(OvergrownFruitPlantBlock.AGE, 3)), "繊維草(育ちきり)",
                ModItems.PLANT_FIBER.get(), true, ModItems.FIBER_GRASS_SEEDS.get(), true);
        check(helper, drops(level, pos, fiber), "繊維草(芽)", ModItems.PLANT_FIBER.get(), false, ModItems.FIBER_GRASS_SEEDS.get(), true);
        BlockState burst = ModBlocks.BURST_FRUIT_BUSH.get().defaultBlockState();
        check(helper, drops(level, pos, burst.setValue(OvergrownFruitPlantBlock.AGE, 2)), "炸裂の実の茂み(段 2)",
                ModItems.BURST_FRUIT.get(), true, ModItems.BURST_FRUIT.get(), true);
        check(helper, drops(level, pos, burst.setValue(OvergrownFruitPlantBlock.AGE, 3)), "炸裂の実の茂み(段 3)",
                ModItems.BURST_FRUIT.get(), false, ModItems.BURST_FRUIT.get(), false);
        check(helper, drops(level, pos, ModBlocks.WAYMARK_FLOWER.get().defaultBlockState()), "道しるべの花",
                ModItems.WAYMARK_FLOWER.get(), true, ModItems.WAYMARK_FLOWER.get(), true);
        helper.succeed();
    }

    private static List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState state) {
        return Block.getDrops(state, level, pos, null);
    }

    private static void check(GameTestHelper helper, List<ItemStack> drops, String what, Item a, boolean wantA, Item b, boolean wantB) {
        boolean hasA = drops.stream().anyMatch(s -> s.is(a));
        boolean hasB = drops.stream().anyMatch(s -> s.is(b));
        if (hasA != wantA || hasB != wantB) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(what + " のドロップが違う: " + drops);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fruitPlantHarvestResetsToSprout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.STICKY_VINE.get().defaultBlockState().setValue(OvergrownFruitPlantBlock.AGE, 3));
        BlockPos pos = helper.absolutePos(rel);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockState state = level.getBlockState(pos);
        state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        int age = level.getBlockState(pos).getValue(OvergrownFruitPlantBlock.AGE);
        if (age != 1) {
            helper.fail("採ったあと芽(段 1)に戻らない: 段 " + age);
            return;
        }
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D), e -> e.getItem().is(ModItems.STICKY_FRUIT.get()));
        if (items.isEmpty()) {
            helper.fail("粘りの実が落ちない");
            return;
        }
        // 育ちきっていない株からは採れない
        helper.setBlock(rel, ModBlocks.STICKY_VINE.get().defaultBlockState().setValue(OvergrownFruitPlantBlock.AGE, 2));
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (level.getBlockState(pos).getValue(OvergrownFruitPlantBlock.AGE) != 2) {
            helper.fail("段 2 の粘り蔦から採れてしまった");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void ripeBurstFruitSparesPlantMobs(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.BURST_FRUIT_BUSH.get().defaultBlockState().setValue(OvergrownFruitPlantBlock.AGE, 3));
        helper.spawn(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(3.5D, 2.0D, 3.5D));
        helper.runAfterDelay(20, () -> {
            int age = helper.getBlockState(rel).getValue(OvergrownFruitPlantBlock.AGE);
            if (age != 3) {
                helper.fail("植物側の Mob で炸裂の実が爆ぜた: 段 " + age);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void ripeBurstFruitBurstsForOthers(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.BURST_FRUIT_BUSH.get().defaultBlockState().setValue(OvergrownFruitPlantBlock.AGE, 3));
        helper.spawn(EntityType.HUSK, new Vec3(3.5D, 2.0D, 3.5D));
        helper.succeedWhen(() -> {
            BlockState s = helper.getBlockState(rel);
            if (!s.is(ModBlocks.BURST_FRUIT_BUSH.get()) || s.getValue(OvergrownFruitPlantBlock.AGE) != 0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("ハスクが入っても爆ぜない: " + s);
            }
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void burstMineOnlyForPlantMobs(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.BURST_MINE.get().defaultBlockState());
        Husk husk = helper.spawn(EntityType.HUSK, new Vec3(3.5D, 2.0D, 3.5D));
        helper.startSequence()
                .thenExecuteAfter(15, () -> {
                    if (!helper.getBlockState(rel).is(ModBlocks.BURST_MINE.get())) {
                        helper.fail("ハスクが踏んで地雷が爆ぜた");
                    }
                    husk.discard();
                    helper.spawn(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(3.5D, 2.0D, 3.5D));
                })
                .thenWaitUntil(() -> {
                    if (helper.getBlockState(rel).is(ModBlocks.BURST_MINE.get())) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("植物側の Mob が踏んでも爆ぜない");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stickyNetRootsAllButThrower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk thrower = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(2.5D, 2.0D, 3.5D));
        Husk near = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(4.5D, 2.0D, 3.5D));
        StickyNet net = new StickyNet(level, thrower);
        Vec3 at = helper.absoluteVec(new Vec3(3.5D, 2.5D, 3.5D));
        int n = StickyNet.entangle(level, net, at.x, at.y, at.z);
        if (n != 1 || !near.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) || thrower.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) {
            helper.fail("投網: 絡めた数 " + n + "・近くのハスク " + near.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                    + "・投げた本人 " + thrower.hasEffect(MobEffects.MOVEMENT_SLOWDOWN));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = TURRET_BATCH, timeoutTicks = 60)
    public static void turretNeedsBatteryUnlessBaseSupplied(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 2, 1);
        BlockPos b = new BlockPos(5, 2, 5);
        helper.setBlock(a, ModBlocks.GUN_TURRET.get().defaultBlockState());
        helper.setBlock(b, ModBlocks.GUN_TURRET.get().defaultBlockState());
        GunTurretBlockEntity player = (GunTurretBlockEntity) helper.getBlockEntity(a);
        GunTurretBlockEntity base = (GunTurretBlockEntity) helper.getBlockEntity(b);
        player.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 16));
        base.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 16));
        base.setBaseSupplied(true);
        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    if (player.isArmed()) {
                        helper.fail("電池の無い機関砲台が動いている");
                    }
                    if (!base.isArmed()) {
                        helper.fail("基地の機関砲台が電池なしで動かない");
                    }
                    ItemStack back = MachineBattery.swap(player, new ItemStack(ModItems.BATTERY.get()));
                    if (!back.isEmpty() || player.batteryCharge() != BatteryItem.CAPACITY) {
                        helper.fail("電池を入れた結果が違う: 戻り " + back + "・残り " + player.batteryCharge());
                    }
                })
                .thenExecuteAfter(5, () -> {
                    if (!player.isArmed()) {
                        helper.fail("電池を入れても機関砲台が動かない");
                    }
                    // 使い切った電池を抜くと空の電池が戻る
                    player.setBatteryCharge(0);
                    ItemStack back = MachineBattery.swap(player, new ItemStack(ModItems.BATTERY.get()));
                    if (!back.is(ModItems.EMPTY_BATTERY.get())) {
                        helper.fail("使い切った電池が空の電池で戻らない: " + back);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void beamShieldNeedsBattery(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.BEAM_SHIELD.get().defaultBlockState());
        BeamShieldBlockEntity shield = (BeamShieldBlockEntity) helper.getBlockEntity(rel);
        shield.setPowered(true);
        if (shield.isDeploying()) {
            helper.fail("電池の無い遮蔽盾が信号で展開し始めた");
            return;
        }
        // 信号が来ている間に電池を入れると、その場で展開する
        MachineBattery.swap(shield, new ItemStack(ModItems.BATTERY.get()));
        if (!shield.isDeploying()) {
            helper.fail("電池を入れても展開しない");
            return;
        }
        helper.runAfterDelay(10, () -> {
            if (shield.batteryCharge() >= BatteryItem.CAPACITY) {
                helper.fail("展開している間に電池が減らない: " + shield.batteryCharge());
                return;
            }
            helper.succeed();
        });
    }

    private SurvivalGearGameTests() {
    }
}
