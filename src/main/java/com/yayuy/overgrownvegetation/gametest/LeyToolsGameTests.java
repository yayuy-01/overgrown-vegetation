package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.block.LureLanternBlock;
import com.yayuy.overgrownvegetation.block.LureLanternBlockEntity;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.defense.SunlitLanterns;
import com.yayuy.overgrownvegetation.item.LeyWaystoneItem;
import com.yayuy.overgrownvegetation.item.SunlitLanternItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 地脈の燃料で動く道具(2026-10-16、サバイバル用アイテムのクラスター 3。ユーザー指定)。
 * <ul>
 *     <li>新しいレシピが全部読み込まれている({@link #recipesAreLoaded})。</li>
 *     <li>汲み上げ器は地脈の線の近くほど効率が良く、たまった雫は素手の右クリックで取れる({@link #pumpPrefersLeyLines})。</li>
 *     <li>地脈渡りの石は対の石で対になり、帰ると雫を 1 つ使い、対の石が無くなっていれば帰れず対が外れる({@link #waystonePairsAndSpendsDrop})。
 *     偽プレイヤーの teleport は何もしないので、動いたかはテストクライアントで見る。</li>
 *     <li>呪符の明かりは光り、時間が来ると消える({@link #charmLightExpires})。</li>
 *     <li>陽溜まりのランタン: 効く相手・燃料の足し方と減り方・灯す・消す({@link #sunlitLanternFuelAndTargets})。</li>
 *     <li>おとりの灯籠: 石炭で灯り、効く相手だけ呼び寄せる({@link #lureLanternCallsOnlyItsTargets})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyToolsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyToolsGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "ley_tools";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void recipesAreLoaded(GameTestHelper helper) {
        for (String id : new String[]{"ley_pump", "waystone_anchor", "ley_waystone", "sunlit_lantern", "light_charm", "lure_lantern"}) {
            if (helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id)).isEmpty()) {
                helper.fail("レシピ " + id + " が読み込まれていない");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void pumpPrefersLeyLines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // 周り 400 ブロックの中で、線にいちばん近いマスといちばん遠いマスを探す(場はシードと座標だけで決まる)
        BlockPos near = null;
        BlockPos far = null;
        double nearD = Double.MAX_VALUE;
        double farD = -1.0D;
        for (int dx = -200; dx <= 200; dx += 4) {
            for (int dz = -200; dz <= 200; dz += 4) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                double d = LeyVeinField.distanceToLine(level.getSeed(), x, z);
                if (d < nearD) {
                    nearD = d;
                    near = new BlockPos(x, origin.getY(), z);
                }
                if (d > farD) {
                    farD = d;
                    far = new BlockPos(x, origin.getY(), z);
                }
            }
        }
        double effNear = LeyPumpBlockEntity.efficiencyAt(level, near);
        double effFar = LeyPumpBlockEntity.efficiencyAt(level, far);
        if (!(effNear > effFar) || effNear < 0.9D || effFar > 0.5D) {
            helper.fail("効率の並びが違う: 線の近く " + effNear + "(距離 " + nearD + ")・遠く " + effFar + "(距離 " + farD + ")");
            return;
        }
        // たまった雫は素手の右クリックで全部取れる
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_PUMP.get());
        LeyPumpBlockEntity pump = (LeyPumpBlockEntity) helper.getBlockEntity(rel);
        pump.setStored(3);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = helper.absolutePos(rel);
        level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        int got = player.getInventory().countItem(ModItems.LEY_DROP.get());
        if (got != 3 || pump.stored() != 0) {
            helper.fail("雫を取り出した結果が違う: 手元 " + got + "・中 " + pump.stored());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void waystonePairsAndSpendsDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchorRel = new BlockPos(1, 2, 1);
        helper.setBlock(anchorRel, ModBlocks.WAYSTONE_ANCHOR.get());
        BlockPos anchor = helper.absolutePos(anchorRel);
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        player.getInventory().clearContent();
        try {
            ItemStack stone = new ItemStack(ModItems.LEY_WAYSTONE.get());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stone);
            stone.useOn(new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false)));
            GlobalPos home = stone.get(ModDataComponents.WAYSTONE_HOME.get());
            if (home == null || !home.pos().equals(anchor)) {
                helper.fail("対の石で対にならない: " + home);
                return;
            }
            if (LeyWaystoneItem.standingSpot(level, anchor) == null || !LeyWaystoneItem.standingSpot(level, anchor).equals(anchor.above())) {
                helper.fail("着く所が対の石の上でない: " + LeyWaystoneItem.standingSpot(level, anchor));
                return;
            }
            player.getInventory().add(new ItemStack(ModItems.LEY_DROP.get(), 2));
            if (!LeyWaystoneItem.travel(level, player, stone) || player.getInventory().countItem(ModItems.LEY_DROP.get()) != 1) {
                helper.fail("帰ったのに雫が 1 つ減らない: 残り " + player.getInventory().countItem(ModItems.LEY_DROP.get()));
                return;
            }
            helper.setBlock(anchorRel, Blocks.AIR);
            if (LeyWaystoneItem.travel(level, player, stone) || stone.has(ModDataComponents.WAYSTONE_HOME.get())
                    || player.getInventory().countItem(ModItems.LEY_DROP.get()) != 1) {
                helper.fail("対の石が無いのに帰れた・対が外れない・雫が減った");
                return;
            }
        } finally {
            player.getInventory().clearContent();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void charmLightExpires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 3, 3);
        helper.setBlock(rel, ModBlocks.CHARM_LIGHT.get());
        BlockPos pos = helper.absolutePos(rel);
        BlockState state = level.getBlockState(pos);
        if (state.getLightEmission(level, pos) < 14 || !level.getBlockTicks().hasScheduledTick(pos, ModBlocks.CHARM_LIGHT.get())) {
            helper.fail("呪符の明かりが光らない・消える予定が無い: 明るさ " + state.getLightEmission(level, pos));
            return;
        }
        state.tick(level, pos, level.getRandom());
        if (!level.getBlockState(pos).isAir()) {
            helper.fail("時間が来ても消えない: " + level.getBlockState(pos));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sunlitLanternFuelAndTargets(GameTestHelper helper) {
        Mob plant = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(1.5D, 2.0D, 1.5D));
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(5.5D, 2.0D, 5.5D));
        if (!SunlitLanterns.affects(plant) || SunlitLanterns.affects(husk)) {
            helper.fail("効く相手が違う: G1 " + SunlitLanterns.affects(plant) + "・ハスク " + SunlitLanterns.affects(husk));
            return;
        }
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack lantern = new ItemStack(ModItems.SUNLIT_LANTERN.get());
        if (SunlitLanternItem.toggle(lantern, player) || SunlitLanternItem.lit(lantern)) {
            helper.fail("燃料の無いランタンが灯った");
            return;
        }
        ItemStack berries = new ItemStack(Items.GLOW_BERRIES, 20);
        lantern.overrideOtherStackedOnMe(berries, new Slot(new SimpleContainer(1), 0, 0, 0), ClickAction.PRIMARY, player, SlotAccess.NULL);
        if (SunlitLanternItem.fuel(lantern) != SunlitLanternItem.MAX_FUEL || berries.getCount() != 20 - SunlitLanternItem.MAX_BERRIES) {
            helper.fail("グロウベリーで燃料が入らない: 燃料 " + SunlitLanternItem.fuel(lantern) + "・残りのベリー " + berries.getCount());
            return;
        }
        if (!SunlitLanternItem.toggle(lantern, player) || !SunlitLanternItem.burning(lantern)) {
            helper.fail("燃料があるのに灯らない");
            return;
        }
        SunlitLanterns.burn(player, lantern, SunlitLanternItem.MAX_FUEL);
        if (SunlitLanternItem.fuel(lantern) != 0 || SunlitLanternItem.lit(lantern)) {
            helper.fail("燃料が切れても消えない: 燃料 " + SunlitLanternItem.fuel(lantern));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void lureLanternCallsOnlyItsTargets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, ModBlocks.LURE_LANTERN.get());
        LureLanternBlockEntity lure = (LureLanternBlockEntity) helper.getBlockEntity(rel);
        if (lure.addCoal(1) != 1 || !helper.getBlockState(rel).getValue(LureLanternBlock.LIT)) {
            helper.fail("石炭を足しても灯らない: 燃料 " + lure.fuel());
            return;
        }
        Mob plant = helper.spawn(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(5.5D, 2.0D, 5.5D));
        Husk husk = helper.spawn(EntityType.HUSK, new Vec3(5.5D, 2.0D, 1.5D));
        // 湧いた直後は経路が引けない(足が床に着く前)ので、少し待ってから呼ぶ
        helper.runAfterDelay(5, () -> {
            plant.setTarget(null);
            husk.setTarget(null);
            int n = lure.lure(level, helper.absolutePos(rel));
            BlockPos target = plant.getNavigation().getTargetPos();
            if (n != 1 || target == null || target.distManhattan(helper.absolutePos(rel)) > 2) {
                helper.fail("呼び寄せた数 " + n + "(G1 だけのはず)・G1 の行き先 " + target + "・灯籠 " + helper.absolutePos(rel)
                        + "・ハスクの行き先 " + husk.getNavigation().getTargetPos());
                return;
            }
            helper.succeed();
        });
    }

    private LeyToolsGameTests() {
    }
}
