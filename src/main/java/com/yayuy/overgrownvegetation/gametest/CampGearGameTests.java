package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.HerbCropBlock;
import com.yayuy.overgrownvegetation.entity.AshSmokeBomb;
import com.yayuy.overgrownvegetation.item.AshFertilizerItem;
import com.yayuy.overgrownvegetation.item.CanvasBackpackItem;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.menu.BackpackMenu;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 野営の道具と投げ物(2026-10-16、サバイバル用アイテムのクラスター 4。ユーザー指定)。
 * <ul>
 *     <li>新しいレシピが全部読み込まれている({@link #recipesAreLoaded})。</li>
 *     <li>灰の肥料は役に立つ植物だけを 1 段育てる({@link #ashFertilizerOnlyUsefulPlants})。</li>
 *     <li>背嚢の画面: 中身が背嚢に残る・背嚢は入れられない・元の背嚢は動かせない・無くなったら閉じる({@link #backpackMenuKeepsContents})。</li>
 *     <li>胞子まといの外套: G1 は狙わない・ハスクは狙う・植物側を殴るとしばらく効かない({@link #sporeCloakFoolsPlantMobs})。</li>
 *     <li>灰煙玉: 周りの Mob が狙いを外し、しばらく狙えない({@link #smokeBombConfusesMobs})。</li>
 *     <li>受け流しの杭: 4 本で囲った中は汚染できない・1 本抜くと囲いが消える({@link #stakeWardStopsCorruption})。</li>
 *     <li>天幕の半径 3・天幕と寝袋はベッドになり、復活地点になるのは天幕だけ({@link #tentShelterAndBeds})。</li>
 *     <li>焼夷瓶: 火を付け、そばの生き物を燃やす({@link #incendiaryFlaskSetsFire})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CampGearGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CampGearGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "camp_gear";

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void recipesAreLoaded(GameTestHelper helper) {
        for (String id : new String[]{"canvas_backpack", "ash_fertilizer", "spore_cloak", "ash_smoke_bomb", "incendiary_flask", "bedroll", "spore_tent", "deflecting_stake"}) {
            if (helper.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id)).isEmpty()) {
                helper.fail("レシピ " + id + " が読み込まれていない");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void ashFertilizerOnlyUsefulPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(2, 2, 2), ModBlocks.PUNGENT_HERB_CROP.get().defaultBlockState());
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.WHEAT.defaultBlockState());
        BlockPos herb = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos wheat = helper.absolutePos(new BlockPos(4, 2, 2));
        if (!AshFertilizerItem.grow(level, herb) || level.getBlockState(herb).getValue(BlockStateProperties.AGE_3) != 1) {
            helper.fail("つんと草が育たない: " + level.getBlockState(herb));
            return;
        }
        if (AshFertilizerItem.accepts(level.getBlockState(wheat)) || AshFertilizerItem.grow(level, wheat)) {
            helper.fail("小麦にも効いてしまう");
            return;
        }
        if (!(level.getBlockState(herb).getBlock() instanceof HerbCropBlock)) {
            helper.fail("薬草の株が変わった");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void backpackMenuKeepsContents(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack backpack = new ItemStack(ModItems.CANVAS_BACKPACK.get());
        player.getInventory().setItem(2, backpack);
        BackpackMenu menu = new BackpackMenu(1, player.getInventory(), backpack, 2);
        menu.container().setItem(0, new ItemStack(Items.DIRT, 5));
        if (!CanvasBackpackItem.contents(backpack).get(0).is(Items.DIRT) || CanvasBackpackItem.contents(backpack).get(0).getCount() != 5) {
            helper.fail("中身が背嚢に残らない: " + CanvasBackpackItem.contents(backpack));
            return;
        }
        if (menu.getSlot(1).mayPlace(new ItemStack(ModItems.CANVAS_BACKPACK.get()))) {
            helper.fail("背嚢の中に背嚢が入る");
            return;
        }
        int locked = 18 + 27 + 2;
        menu.clicked(locked, 0, ClickType.PICKUP, player);
        if (!menu.getCarried().isEmpty() || player.getInventory().getItem(2) != backpack) {
            helper.fail("開いている背嚢を持ち上げられた");
            return;
        }
        menu.clicked(0, 2, ClickType.SWAP, player);
        if (player.getInventory().getItem(2) != backpack) {
            helper.fail("数字キーで開いている背嚢を入れ替えられた");
            return;
        }
        if (!menu.stillValid(player)) {
            helper.fail("背嚢が元の場所にあるのに閉じる");
            return;
        }
        player.getInventory().setItem(2, ItemStack.EMPTY);
        if (menu.stillValid(player)) {
            helper.fail("背嚢が無くなっても開いたまま");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void sporeCloakFoolsPlantMobs(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ExtraArmor.of(player).set(ExtraArmor.Part.BODY.ordinal(), new ItemStack(ModItems.SPORE_CLOAK.get()));
        Mob g1 = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(1.5D, 2.0D, 1.5D));
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(5.5D, 2.0D, 5.5D));
        g1.setTarget(player);
        husk.setTarget(player);
        if (g1.getTarget() != null || husk.getTarget() != player) {
            helper.fail("外套: G1 の狙い " + g1.getTarget() + "・ハスクの狙い " + husk.getTarget());
            return;
        }
        g1.hurt(player.damageSources().playerAttack(player), 1.0F);
        g1.setTarget(player);
        if (g1.getTarget() != player) {
            helper.fail("植物側を殴った後も外套が効いている");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void smokeBombConfusesMobs(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 3.5D));
        husk.setTarget(player);
        Vec3 at = helper.absoluteVec(new Vec3(3.5D, 2.0D, 4.5D));
        int n = AshSmokeBomb.burst(helper.getLevel(), at.x, at.y, at.z);
        husk.setTarget(player);
        if (n < 1 || husk.getTarget() != null || !AshSmokeBomb.confused(husk)) {
            helper.fail("灰煙玉: 見失わせた数 " + n + "・ハスクの狙い " + husk.getTarget());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void stakeWardStopsCorruption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(3, 1, 3));
        if (!com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, floor, level.getBlockState(floor), true)) {
            helper.fail("杭を置く前から床の石が汚染できない(前提が崩れている)");
            return;
        }
        BlockPos[] corners = {new BlockPos(1, 2, 1), new BlockPos(5, 2, 1), new BlockPos(1, 2, 5), new BlockPos(5, 2, 5)};
        for (BlockPos c : corners) {
            helper.setBlock(c, ModBlocks.DEFLECTING_STAKE.get());
        }
        if (com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, floor, level.getBlockState(floor), true)) {
            helper.fail("杭で囲った中の床が汚染できる");
            return;
        }
        helper.setBlock(corners[0], Blocks.AIR);
        if (com.yayuy.overgrownvegetation.defense.StakeWards.protects(level, floor)) {
            helper.fail("杭を 1 本抜いても囲いが残る");
            return;
        }
        for (BlockPos c : corners) {
            helper.setBlock(c, Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void tentShelterAndBeds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.SPORE_TENT.get());
        BlockPos tent = helper.absolutePos(rel);
        if (!com.yayuy.overgrownvegetation.spore.TentShelters.covers(level, tent.south(3))
                || com.yayuy.overgrownvegetation.spore.TentShelters.covers(level, tent.south(4))
                || com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, tent.south(2)) != 0.0D) {
            helper.fail("天幕の半径 3 が違う: 3 先 " + com.yayuy.overgrownvegetation.spore.TentShelters.covers(level, tent.south(3))
                    + "・4 先 " + com.yayuy.overgrownvegetation.spore.TentShelters.covers(level, tent.south(4)));
            return;
        }
        var tentState = level.getBlockState(tent);
        if (!tentState.isBed(level, tent, null) || tentState.getRespawnPosition(EntityType.PLAYER, level, tent, 0.0F).isEmpty()) {
            helper.fail("天幕がベッドにならない・復活地点にならない");
            return;
        }
        BlockPos rollRel = new BlockPos(1, 2, 1);
        helper.setBlock(rollRel, ModBlocks.BEDROLL.get());
        BlockPos roll = helper.absolutePos(rollRel);
        var rollState = level.getBlockState(roll);
        if (!rollState.isBed(level, roll, null) || rollState.getRespawnPosition(EntityType.PLAYER, level, roll, 0.0F).isPresent()) {
            helper.fail("寝袋がベッドにならない・復活地点になってしまう");
            return;
        }
        helper.setBlock(rel, Blocks.AIR);
        if (com.yayuy.overgrownvegetation.spore.TentShelters.covers(level, tent)) {
            helper.fail("天幕を壊しても胞子を払ったまま");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void incendiaryFlaskSetsFire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 4.5D));
        int lit = com.yayuy.overgrownvegetation.purify.IncendiaryFlask.burst(level, helper.absolutePos(new BlockPos(3, 2, 3)), null);
        if (lit < 1 || !husk.isOnFire()) {
            helper.fail("焼夷瓶: 付けた火 " + lit + "・ハスクが燃えている " + husk.isOnFire());
            return;
        }
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, 2, 0), new BlockPos(6, 3, 6))) {
            if (helper.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE)) {
                helper.setBlock(p, Blocks.AIR);
            }
        }
        husk.discard();
        helper.succeed();
    }

    private CampGearGameTests() {
    }
}
