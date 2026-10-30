package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyUpdraftBlock;
import com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity;
import com.yayuy.overgrownvegetation.block.LeyFurnaceBlockEntity;
import com.yayuy.overgrownvegetation.block.LeyLampBlock;
import com.yayuy.overgrownvegetation.block.LeyLampBlockEntity;
import com.yayuy.overgrownvegetation.defense.ClapperRope;
import com.yayuy.overgrownvegetation.defense.ClapperRopeBlockEntity;
import com.yayuy.overgrownvegetation.defense.ClapperRopeItem;
import com.yayuy.overgrownvegetation.defense.LeyLamps;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 地脈技術の 2 段目: 生活用の機械 3 つ(2026-10-22)。
 * <ul>
 *     <li>地脈炉が燻製・溶鉱・精錬の 3 種を焼き、焼く時間は元のレシピの半分・同じ品は 燻製 &gt; 溶鉱 &gt; 精錬 で選ぶ
 *     ({@link #furnaceCooksAllThreeKindsAtDoubleSpeed})。電池が無いと進まない({@link #furnaceStopsWithoutBattery})。</li>
 *     <li>点いた地脈灯の周り 8 ブロックでバニラの敵対 Mob の自然湧きが弾かれ、9 ブロック先・この MOD の Mob・電池切れでは弾かれない
 *     ({@link #lampBlocksMonsterSpawnsWithinEight})。</li>
 *     <li>点いた焚き火・魂の焚き火の真上(煙の柱)で、紐に吊るした生肉と携行食の元が 1 分で燻製になり、焚き火の上でない物はそのまま
 *     ({@link #campfireSmokesHungMeatInAMinute})。</li>
 *     <li>上昇気流機の柱の中の物が昇り、天井で止まり、隣の列は動かない・柱の判定({@link #updraftLiftsToTheCeiling})。</li>
 *     <li>地脈炉の燃料枠にバケツがあると、電池を持って右クリックしても入れ替えない({@link #furnaceKeepsBucketInFuelSlot})。</li>
 *     <li>点いた地脈灯の隣のマスの明るさ(ブロックの光)が 14 になる({@link #lampBlocksMonsterSpawnsWithinEight})。</li>
 * </ul>
 * 期待値はレシピの cookingTime と機械の定数から引く(レシピを他 MOD・データパックが変えても落ちないように)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyMachineGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyMachineGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "ley_machines";

    private static LeyFurnaceBlockEntity furnace(GameTestHelper helper, BlockPos rel, ItemStack input, boolean battery) {
        helper.setBlock(rel, ModBlocks.LEY_FURNACE.get());
        LeyFurnaceBlockEntity be = (LeyFurnaceBlockEntity) helper.getBlockEntity(rel);
        if (battery) {
            be.setItem(1, BatteryItem.withCharge(BatteryItem.CAPACITY));
        }
        be.setItem(0, input);
        return be;
    }

    /** そのレシピ種での焼く時間(元のレシピの cookingTime)。 */
    private static int recipeTime(ServerLevel level, RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type, ItemStack stack) {
        @SuppressWarnings("unchecked")
        RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe> t = (RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe>) type;
        return level.getRecipeManager().getRecipeFor(t, new SingleRecipeInput(stack), level)
                .map(r -> r.value().getCookingTime()).orElseThrow(() -> new IllegalStateException("レシピが無い: " + stack));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void furnaceCooksAllThreeKindsAtDoubleSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 生の牛肉: 燻製・精錬の両方にある → 燻製の時間 / 2。原鉄: 溶鉱・精錬 → 溶鉱の時間 / 2。砂: 精錬だけ → 精錬の時間 / 2。
        int beefTime = Math.max(1, recipeTime(level, RecipeType.SMOKING, new ItemStack(Items.BEEF)) / LeyFurnaceBlockEntity.SPEED);
        int ironTime = Math.max(1, recipeTime(level, RecipeType.BLASTING, new ItemStack(Items.RAW_IRON)) / LeyFurnaceBlockEntity.SPEED);
        int sandTime = Math.max(1, recipeTime(level, RecipeType.SMELTING, new ItemStack(Items.SAND)) / LeyFurnaceBlockEntity.SPEED);
        LeyFurnaceBlockEntity beef = furnace(helper, new BlockPos(1, 2, 1), new ItemStack(Items.BEEF), true);
        LeyFurnaceBlockEntity iron = furnace(helper, new BlockPos(3, 2, 1), new ItemStack(Items.RAW_IRON), true);
        LeyFurnaceBlockEntity sand = furnace(helper, new BlockPos(5, 2, 1), new ItemStack(Items.SAND), true);
        helper.runAfterDelay(1, () -> {
            if (beef.totalTime() != beefTime || iron.totalTime() != ironTime || sand.totalTime() != sandTime) {
                helper.fail("焼く時間: 牛肉 " + beef.totalTime() + "(期待 " + beefTime + " = 燻製の半分)/ 原鉄 " + iron.totalTime()
                        + "(期待 " + ironTime + " = 溶鉱の半分)/ 砂 " + sand.totalTime() + "(期待 " + sandTime + " = 精錬の半分)");
            }
            if (!helper.getBlockState(new BlockPos(1, 2, 1)).getValue(AbstractFurnaceBlock.LIT)) {
                helper.fail("焼いているのに口が光っていない");
            }
        });
        // 1 個目が焼き上がる tick の直前ではまだ無く、直後には出ている。
        int last = Math.max(beefTime, Math.max(ironTime, sandTime));
        helper.runAfterDelay(beefTime - 2, () -> {
            if (!beef.getItem(2).isEmpty()) {
                helper.fail("牛肉が早すぎる: " + (beefTime - 2) + " tick で焼けた");
            }
        });
        helper.runAfterDelay(last + 3, () -> {
            check(helper, beef, Items.COOKED_BEEF, "牛肉(燻製)");
            check(helper, iron, Items.IRON_INGOT, "原鉄(溶鉱)");
            check(helper, sand, Items.GLASS, "砂(精錬)");
            int used = BatteryItem.CAPACITY - beef.batteryCharge();
            if (used < beefTime || used > beefTime + 5) {
                helper.fail("電池の減りが焼いた tick と合わない: " + used + "(焼いた " + beefTime + ")");
            }
            helper.succeed();
        });
    }

    private static void check(GameTestHelper helper, LeyFurnaceBlockEntity be, net.minecraft.world.item.Item expected, String what) {
        ItemStack out = be.getItem(2);
        if (!out.is(expected) || out.getCount() != 1) {
            helper.fail(what + " の出来た物が違う: " + out);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void furnaceStopsWithoutBattery(GameTestHelper helper) {
        LeyFurnaceBlockEntity none = furnace(helper, new BlockPos(1, 2, 1), new ItemStack(Items.BEEF), false);
        LeyFurnaceBlockEntity empty = furnace(helper, new BlockPos(3, 2, 1), new ItemStack(Items.BEEF), false);
        empty.setItem(1, new ItemStack(ModItems.EMPTY_BATTERY.get()));
        // 石炭は燃料の枠に入らない(ホッパーなどから)
        if (none.canPlaceItem(1, new ItemStack(Items.COAL)) || !none.canPlaceItem(1, new ItemStack(ModItems.BATTERY.get()))) {
            helper.fail("燃料の枠に入る物が違う(地脈電池だけのはず)");
        }
        helper.runAfterDelay(120, () -> {
            for (LeyFurnaceBlockEntity be : new LeyFurnaceBlockEntity[]{none, empty}) {
                if (!be.getItem(2).isEmpty() || be.progress() != 0) {
                    helper.fail("電池が無いのに焼けている: 出来た物 " + be.getItem(2) + " / 進み " + be.progress());
                }
                if (be.getBlockState().getValue(AbstractFurnaceBlock.LIT)) {
                    helper.fail("電池が無いのに口が光っている");
                }
            }
            helper.succeed();
        });
    }

    private static boolean blocked(ServerLevel level, EntityType<?> type, BlockPos pos) {
        MobSpawnEvent.SpawnPlacementCheck event = new MobSpawnEvent.SpawnPlacementCheck(type, level, MobSpawnType.NATURAL, pos, level.random, true);
        NeoForge.EVENT_BUS.post(event);
        return event.getResult() == MobSpawnEvent.SpawnPlacementCheck.Result.FAIL;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void lampBlocksMonsterSpawnsWithinEight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_LAMP.get());
        LeyLampBlockEntity lamp = (LeyLampBlockEntity) helper.getBlockEntity(rel);
        lamp.setBatteryCharge(40);
        lamp.setHasBattery(true);
        lamp.batteryChanged();
        BlockPos at = helper.absolutePos(rel);
        EntityType<?> modMonster = BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(t -> t.getCategory() == MobCategory.MONSTER
                        && OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace()))
                .findFirst().orElse(null);
        helper.runAfterDelay(2, () -> {
            if (!helper.getBlockState(rel).getValue(LeyLampBlock.LIT) || helper.getBlockState(rel).getLightEmission() != 15) {
                helper.fail("電池が入っているのに光源 15 で点いていない");
            }
            int r = LeyLamps.RADIUS;
            if (!blocked(level, EntityType.ZOMBIE, at.offset(r, 0, 0)) || !blocked(level, EntityType.SKELETON, at.offset(-r, r, r))
                    || !blocked(level, EntityType.CREEPER, at.offset(0, -r, -3))) {
                helper.fail("半径 " + r + " の中で敵対 Mob の湧きが弾かれていない");
            }
            if (blocked(level, EntityType.ZOMBIE, at.offset(r + 1, 0, 0)) || blocked(level, EntityType.ZOMBIE, at.offset(0, r + 1, 0))) {
                helper.fail("半径 " + (r + 1) + " の外の湧きまで弾いている");
            }
            if (blocked(level, EntityType.COW, at.offset(2, 0, 0))) {
                helper.fail("敵対でない Mob まで弾いている");
            }
            if (modMonster != null && blocked(level, modMonster, at.offset(2, 0, 0))) {
                helper.fail("この MOD の Mob(" + BuiltInRegistries.ENTITY_TYPE.getKey(modMonster) + ")まで弾いている");
            }
        });
        // 光の計算が本当に 15 で回っているか(夜の写真で暗く見えた件。2026-10-22)
        helper.runAfterDelay(15, () -> {
            int light = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at.east());
            if (light != 14) {
                helper.fail("地脈灯の隣のブロックの光が 14 でない: " + light);
            }
        });
        // 電池(40 tick ぶん)が切れたら消えて、湧き止めも止まる。
        helper.runAfterDelay(50, () -> {
            if (helper.getBlockState(rel).getValue(LeyLampBlock.LIT)) {
                helper.fail("電池が切れても点いている");
            }
            if (blocked(level, EntityType.ZOMBIE, at.offset(2, 0, 0))) {
                helper.fail("電池が切れても湧きを弾いている");
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1500)
    public static void campfireSmokesHungMeatInAMinute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 紐(柵 2 本の間、z 0 → 4、高さ 5)の下に焚き火(z 2)と魂の焚き火(z 3)。z 1 の下には何も無い。
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.CAMPFIRE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.SOUL_CAMPFIRE);
        helper.setBlock(new BlockPos(3, 5, 0), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(3, 5, 4), Blocks.OAK_FENCE);
        BlockPos marker = ClapperRopeItem.place(level, helper.absolutePos(new BlockPos(3, 5, 0)), helper.absolutePos(new BlockPos(3, 5, 4)));
        if (marker == null || !(level.getBlockEntity(marker) instanceof ClapperRopeBlockEntity rope)) {
            helper.fail("紐を張れない");
            return;
        }
        if (ClapperRope.smokingRecipe(level, new ItemStack(Items.COOKED_BEEF)).isPresent()
                || ClapperRope.smokingRecipe(level, new ItemStack(Items.BEEF)).isEmpty()) {
            helper.fail("吊るせる物の判定が違う(燻製器のレシピの入力だけ)");
            return;
        }
        // t = 0.25 / 0.5 / 0.75 → z 1.5(煙なし)/ 2.5(焚き火)/ 3.5(魂の焚き火)
        if (!rope.addHanging(0.25F, new ItemStack(Items.PORKCHOP)) || !rope.addHanging(0.5F, new ItemStack(Items.BEEF))
                || !rope.addHanging(0.75F, new ItemStack(ModItems.TRAIL_RATION_BASE.get()))) {
            helper.fail("紐に吊るせない");
            return;
        }
        for (int i = 0; i < 3; i++) {
            Vec3 point = rope.hangingPoint(rope.getHangings().get(i));
            if (ClapperRope.inCampfireSmoke(level, point) != (i > 0)) {
                helper.fail("煙の柱の判定が違う: " + i + " 番目(" + helper.relativePos(BlockPos.containing(point)) + ")");
                return;
            }
        }
        // 焚き火の上 SMOKE_COLUMN マスまでが煙の中
        BlockPos fire = helper.absolutePos(new BlockPos(3, 2, 2));
        if (ClapperRope.inCampfireSmoke(level, Vec3.atCenterOf(fire.above(ClapperRope.SMOKE_COLUMN + 1)))
                || !ClapperRope.inCampfireSmoke(level, Vec3.atCenterOf(fire.above(ClapperRope.SMOKE_COLUMN)))) {
            helper.fail("煙の柱の高さが " + ClapperRope.SMOKE_COLUMN + " でない");
            return;
        }
        int smokeTicks = ClapperRope.SMOKE_TICKS;
        helper.runAfterDelay(smokeTicks - 60, () -> {
            if (!rope.getHangings().get(1).stack().is(Items.BEEF)) {
                helper.fail("1 分より早く燻し上がった(" + (smokeTicks - 60) + " tick)");
            }
        });
        helper.runAfterDelay(smokeTicks + 45, () -> {
            ItemStack off = rope.getHangings().get(0).stack();
            ItemStack a = rope.getHangings().get(1).stack();
            ItemStack b = rope.getHangings().get(2).stack();
            if (!a.is(Items.COOKED_BEEF) || !b.is(ModItems.TRAIL_RATION.get())) {
                helper.fail("1 分で燻製にならない: " + a + " / " + b + "(進み " + rope.getHangings().get(1).smoke() + ")");
            }
            if (!off.is(Items.PORKCHOP)) {
                helper.fail("焚き火の上でない物まで燻製になった: " + off);
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void updraftLiftsToTheCeiling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_UPDRAFT.get());
        helper.setBlock(new BlockPos(3, 7, 3), Blocks.STONE); // 天井(柱は 3〜6 の 4 マス)
        LeyUpdraftBlockEntity be = (LeyUpdraftBlockEntity) helper.getBlockEntity(rel);
        BlockPos at = helper.absolutePos(rel);
        Vec3 inside = Vec3.atCenterOf(at.above(3));
        if (LeyUpdraftBlockEntity.isInColumn(level, inside)) {
            helper.fail("電池が無いのに柱がある");
            return;
        }
        be.setBatteryCharge(BatteryItem.CAPACITY);
        be.setHasBattery(true);
        ItemEntity lifted = new ItemEntity(level, at.getX() + 0.5D, at.getY() + 1.05D, at.getZ() + 0.5D, new ItemStack(Items.STICK));
        ItemEntity beside = new ItemEntity(level, at.getX() + 1.5D, at.getY() + 0.05D, at.getZ() + 0.5D, new ItemStack(Items.STICK));
        lifted.setDeltaMovement(Vec3.ZERO);
        beside.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(lifted);
        level.addFreshEntity(beside);
        double startY = lifted.getY();
        double besideY = beside.getY();
        helper.runAfterDelay(3, () -> {
            if (!helper.getBlockState(rel).getValue(LeyUpdraftBlock.POWERED)) {
                helper.fail("電池を入れても動かない");
            }
            if (LeyUpdraftBlockEntity.height(level, at) != 4) {
                helper.fail("天井で止まらない: 高さ " + LeyUpdraftBlockEntity.height(level, at));
            }
            if (!LeyUpdraftBlockEntity.isInColumn(level, inside)
                    || LeyUpdraftBlockEntity.isInColumn(level, Vec3.atCenterOf(at.above(5)))
                    || LeyUpdraftBlockEntity.isInColumn(level, Vec3.atCenterOf(at.above(8)))
                    || LeyUpdraftBlockEntity.isInColumn(level, Vec3.atCenterOf(at.above(2).east()))) {
                helper.fail("柱の判定が違う(中 / 天井の中 / 天井の上 / 隣の列)");
            }
        });
        helper.runAfterDelay(40, () -> {
            if (lifted.getY() < startY + 3.0D) { // 毎 tick 0.1 なら 37 tick ほどで天井(3.7 上)に届く
                helper.fail("柱の中の物が昇らない: " + startY + " → " + lifted.getY());
            }
            if (lifted.getY() > at.getY() + 6.0D) {
                helper.fail("天井を抜けた: " + lifted.getY());
            }
            if (beside.getY() > besideY + 0.1D) {
                helper.fail("隣の列の物まで昇った: " + besideY + " → " + beside.getY());
            }
            if (be.batteryCharge() >= BatteryItem.CAPACITY) {
                helper.fail("電池が減っていない");
            }
            lifted.discard();
            beside.discard();
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void furnaceKeepsBucketInFuelSlot(GameTestHelper helper) {
        LeyFurnaceBlockEntity be = furnace(helper, new BlockPos(3, 2, 3), ItemStack.EMPTY, false);
        if (!be.canSwapBattery() || be.hasBattery()) {
            helper.fail("空の燃料枠の扱いが違う");
            return;
        }
        be.setItem(1, new ItemStack(Items.BUCKET));
        if (be.canSwapBattery() || be.hasBattery()) {
            helper.fail("燃料枠のバケツを電池と入れ替えられる(バケツが消える)");
            return;
        }
        be.setItem(1, BatteryItem.withCharge(100));
        if (!be.canSwapBattery() || !be.hasBattery()) {
            helper.fail("電池が入っているのに入れ替えられない");
            return;
        }
        helper.succeed();
    }
}
