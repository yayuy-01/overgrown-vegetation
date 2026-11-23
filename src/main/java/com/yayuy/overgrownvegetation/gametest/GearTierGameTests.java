package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.gear.ConduitOre;
import com.yayuy.overgrownvegetation.gear.FungalOre;
import com.yayuy.overgrownvegetation.gear.GearTierTable;
import com.yayuy.overgrownvegetation.gear.LeyCharge;
import com.yayuy.overgrownvegetation.gear.ModGear;
import com.yayuy.overgrownvegetation.ley.LeyBatteryHolder;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlockEntity;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 装備・鉱石: 菌集鉱のでき方(2026-11-17。本物の装飾の口を通す)と、グレード 1 の導晶(2026-11-21: 導晶鉱が地脈の近くにだけできる・
 * 原石と精錬と板・道具の硬さと速さと壊れにくさ・充電で速くなり 1 ブロックで減り切れると戻る・汲み上げ器と鉄塔と電池から充電・
 * 飲み込まれると緑になり小さな根晶から 25%・保存と復元)。期待値は {@code gear_tiers.json}({@link GearTierTable})から引く。
 * 11-17〜18 の段 1〜3 の装備のテストは 11-21 に装備ごと消した。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GearTierGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GearTierGameTests.class);
        }

        private Registration() {
        }
    }

    private GearTierGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "gear_tiers";
    private static final double EPS = 1.0E-4D;

    private static GearTierTable t() {
        return DesignData.gearTiers();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.getAbilities().instabuild = false;
        return player;
    }


    /**
     * 菌集鉱: 汚染された岩(過繁茂の石・深層岩)だけが変わる。本物の装飾の口({@code growSurfacePlants}。植物の確率 0 でも)を通る。
     * 汚染の骨粉の装飾し直しでは増えない。確率と地脈の距離はこのテストの同期の間だけ開ける。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void fungalOreFormsFromCorruptedRock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Ore o = t().ore;
        double chance = o.chance, reach = o.leyReach;
        int vmin = o.veinMin, vmax = o.veinMax;
        try {
            o.chance = 1.0D;
            o.leyReach = 1.0E9D;
            o.veinMin = 1;
            o.veinMax = 1;
            BlockPos stone = new BlockPos(2, 2, 2);
            BlockPos deep = new BlockPos(4, 2, 4);
            BlockPos plain = new BlockPos(2, 2, 4);
            helper.setBlock(stone, ModBlocks.OVERGROWN_STONE.get());
            helper.setBlock(deep, ModBlocks.OVERGROWN_DEEPSLATE.get());
            helper.setBlock(plain, Blocks.STONE);
            for (BlockPos p : new BlockPos[]{stone, deep, plain}) {
                BlockState s = helper.getBlockState(p);
                CorruptionSpreader.growSurfacePlants(level, helper.absolutePos(p), s, 0.0D);
            }
            if (!helper.getBlockState(stone).is(ModGear.FUNGAL_ORE.get()) || !helper.getBlockState(deep).is(ModGear.DEEPSLATE_FUNGAL_ORE.get())
                    || !helper.getBlockState(plain).is(Blocks.STONE)) {
                helper.fail("鉱石: 石 " + helper.getBlockState(stone) + "・深層岩 " + helper.getBlockState(deep) + "・汚染されていない石 " + helper.getBlockState(plain));
                return;
            }
            BlockPos again = new BlockPos(3, 2, 2);
            helper.setBlock(again, ModBlocks.OVERGROWN_STONE.get());
            FungalOre.suppressed = true;
            try {
                CorruptionSpreader.growSurfacePlants(level, helper.absolutePos(again), helper.getBlockState(again), 0.0D);
            } finally {
                FungalOre.suppressed = false;
            }
            if (!helper.getBlockState(again).is(ModBlocks.OVERGROWN_STONE.get())) {
                helper.fail("骨粉の装飾し直しで鉱石ができた");
                return;
            }
        } finally {
            o.chance = chance;
            o.leyReach = reach;
            o.veinMin = vmin;
            o.veinMax = vmax;
        }
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(ModGear.FUNGAL_ORE.get().defaultBlockState(), level,
                helper.absolutePos(new BlockPos(2, 2, 2)), null, null, new ItemStack(Items.IRON_PICKAXE));
        if (drops.stream().noneMatch(d -> d.is(ModGear.RAW_FUNGAL_METAL.get()))) {
            helper.fail("菌集鉱から原石が落ちない: " + drops);
            return;
        }
        helper.succeed();
    }

    // ---- グレード 1: 導晶(2026-11-21) ----

    /** 導晶鉱は地脈の線の近く(leyReach 以内)の芯にだけ置かれる。鉱脈は石・深層岩だけを変え、原点のチャンクの外へ出ない。世界生成に足してある。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitOreOnlyNearLey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        double reach = t().conduit.leyReach;
        int near = 0, far = 0;
        for (int i = 0; i < 4000 && (near == 0 || far == 0); i++) {
            int x = (i * 977) % 40000 - 20000, z = (i * 1601) % 40000 - 20000;
            double d = LeyLines.distanceToLine(seed, x, z);
            if (ConduitOre.nearLey(seed, x, z) != (d <= reach)) {
                helper.fail("地脈の近さの判定が距離と合わない: (" + x + ", " + z + ") 距離 " + d);
                return;
            }
            if (d <= reach) near++;
            else far++;
        }
        if (near == 0 || far == 0) {
            helper.fail("地脈の近く・遠くの両方が見つからない(近く " + near + "・遠く " + far + ")");
            return;
        }
        // 鉱脈: 石・深層岩だけが変わる(土は変えない)
        BlockPos stone = new BlockPos(2, 2, 2), deep = new BlockPos(3, 2, 2), dirt = new BlockPos(4, 2, 2);
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(deep, Blocks.DEEPSLATE);
        helper.setBlock(dirt, Blocks.DIRT);
        if (ConduitOre.oreFor(Blocks.STONE.defaultBlockState()) != ModGear.CONDUIT_ORE.get()
                || ConduitOre.oreFor(Blocks.DEEPSLATE.defaultBlockState()) != ModGear.DEEPSLATE_CONDUIT_ORE.get()
                || ConduitOre.oreFor(Blocks.DIRT.defaultBlockState()) != null) {
            helper.fail("鉱脈が変える岩が違う");
            return;
        }
        BlockPos abs = helper.absolutePos(stone);
        ConduitOre.vein(level, abs, level.random, 1, abs.getX() >> 4, abs.getZ() >> 4);
        if (!helper.getBlockState(stone).is(ModGear.CONDUIT_ORE.get())) {
            helper.fail("芯の石が導晶鉱にならない: " + helper.getBlockState(stone));
            return;
        }
        BlockPos absDeep = helper.absolutePos(deep);
        int out = ConduitOre.vein(level, absDeep, level.random, 1, (absDeep.getX() >> 4) + 5, absDeep.getZ() >> 4);
        if (out != 0 || !helper.getBlockState(deep).is(Blocks.DEEPSLATE)) {
            helper.fail("原点のチャンクの外に鉱石を置いた");
            return;
        }
        var key = ResourceKey.create(Registries.PLACED_FEATURE, OvergrownVegetation.id("conduit_ore"));
        if (level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getHolder(key).isEmpty()) {
            helper.fail("導晶鉱の placed_feature が読み込まれていない");
            return;
        }
        helper.succeed();
    }

    /** 導晶鉱から原石が落ち、焼くと地金(かまど・溶鉱炉)、作業台で地金 1 → 板 1、板と棒で道具 4 本。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitDropsSmeltsAndCrafts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        // 汚染されていない導晶鉱 → 導晶の原石 / 飲み込まれた導晶鉱 → 黄緑の導晶の原石(別のアイテム。11-21 夜ユーザー決定)
        for (var ore : List.of(ModGear.CONDUIT_ORE, ModGear.DEEPSLATE_CONDUIT_ORE, ModGear.OVERGROWN_CONDUIT_ORE, ModGear.OVERGROWN_DEEPSLATE_CONDUIT_ORE)) {
            boolean engulfed = ore == ModGear.OVERGROWN_CONDUIT_ORE || ore == ModGear.OVERGROWN_DEEPSLATE_CONDUIT_ORE;
            var want = engulfed ? ModGear.OVERGROWN_RAW_CONDUIT.get() : ModGear.RAW_CONDUIT.get();
            var other = engulfed ? ModGear.RAW_CONDUIT.get() : ModGear.OVERGROWN_RAW_CONDUIT.get();
            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(ore.get().defaultBlockState(), level, at, null, null,
                    new ItemStack(Items.IRON_PICKAXE));
            if (drops.stream().noneMatch(d -> d.is(want)) || drops.stream().anyMatch(d -> d.is(other))) {
                helper.fail(ore.getId() + " から落ちる原石が違う: " + drops);
                return;
            }
        }
        for (var type : List.of(RecipeType.SMELTING, RecipeType.BLASTING)) {
            var r = level.getRecipeManager().getRecipeFor(type, new SingleRecipeInput(new ItemStack(ModGear.RAW_CONDUIT.get())), level);
            if (r.isEmpty() || !r.get().value().getResultItem(level.registryAccess()).is(ModGear.CONDUIT_INGOT.get())) {
                helper.fail(type + " で原石が地金にならない");
                return;
            }
            var g = level.getRecipeManager().getRecipeFor(type, new SingleRecipeInput(new ItemStack(ModGear.OVERGROWN_RAW_CONDUIT.get())), level);
            if (g.isEmpty() || !g.get().value().getResultItem(level.registryAccess()).is(ModGear.OVERGROWN_CONDUIT_INGOT.get())) {
                helper.fail(type + " で黄緑の原石が黄緑の地金にならない");
                return;
            }
            var go = level.getRecipeManager().getRecipeFor(type, new SingleRecipeInput(new ItemStack(ModGear.OVERGROWN_CONDUIT_ORE.get())), level);
            if (go.isEmpty() || !go.get().value().getResultItem(level.registryAccess()).is(ModGear.OVERGROWN_CONDUIT_INGOT.get())) {
                helper.fail(type + " で飲み込まれた導晶鉱が黄緑の地金にならない");
                return;
            }
        }
        // 道具は青・黄緑のどちらからでも: どちらの地金にも導晶の板になる作り方がある(数と形はユーザーが mod マネージャー 2 で決めるので見ない)
        for (var ingot : List.of(ModGear.CONDUIT_INGOT.get(), ModGear.OVERGROWN_CONDUIT_INGOT.get())) {
            if (!craftedFrom(level, ModGear.CONDUIT_PLATE.get(), ingot)) {
                helper.fail(ingot + " から板になる作り方が無い");
                return;
            }
        }
        if (!craftedFrom(level, ModGear.CONDUIT_PICKAXE.get(), ModGear.CONDUIT_PLATE.get())) {
            helper.fail("板からつるはしになる作り方が無い");
            return;
        }
        if (new ItemStack(ModGear.CONDUIT_ENGINE.get()).getMaxStackSize() != 64) {
            helper.fail("導晶機関のスタックが 64 でない");
            return;
        }
        for (String id : List.of("conduit_axe", "conduit_shovel", "conduit_hoe", "conduit_engine")) { // 導晶機関(11-21 夜)の作り方は【仮】。ユーザーが mod マネージャー 2 で決める
            if (level.getRecipeManager().byKey(OvergrownVegetation.id(id)).isEmpty()) {
                helper.fail("作り方が無い: " + id);
                return;
            }
        }
        helper.succeed();
    }

    /** 作業台の作り方のどれかが {@code result} を作り、材料に {@code ingredient} を使うか。 */
    static boolean craftedFrom(ServerLevel level, net.minecraft.world.item.Item result, net.minecraft.world.item.Item ingredient) {
        for (var holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            var r = holder.value();
            if (!r.getResultItem(level.registryAccess()).is(result)) continue;
            for (var ing : r.getIngredients()) if (ing.test(new ItemStack(ingredient))) return true;
        }
        return false;
    }

    /** 道具の硬さはダイヤと同じ(黒曜石を掘れる)・速さ 7・壊れにくさ 1,200(設計値)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitToolStats(GameTestHelper helper) {
        GearTierTable.Conduit c = t().conduit;
        ItemStack pick = new ItemStack(ModGear.CONDUIT_PICKAXE.get());
        if (!pick.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState()) || !pick.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())) {
            helper.fail("導晶のつるはしで黒曜石・ダイヤ鉱石を掘れない");
            return;
        }
        if (Math.abs(pick.getDestroySpeed(Blocks.STONE.defaultBlockState()) - c.speed) > EPS) {
            helper.fail("つるはしの速さ " + pick.getDestroySpeed(Blocks.STONE.defaultBlockState()) + " ≠ " + c.speed);
            return;
        }
        if (Math.abs(new ItemStack(ModGear.CONDUIT_AXE.get()).getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) - c.speed) > EPS
                || Math.abs(new ItemStack(ModGear.CONDUIT_SHOVEL.get()).getDestroySpeed(Blocks.DIRT.defaultBlockState()) - c.speed) > EPS
                || Math.abs(new ItemStack(ModGear.CONDUIT_HOE.get()).getDestroySpeed(Blocks.HAY_BLOCK.defaultBlockState()) - c.speed) > EPS) {
            helper.fail("斧・シャベル・クワの速さが設計値と違う");
            return;
        }
        for (var tool : List.of(ModGear.CONDUIT_PICKAXE, ModGear.CONDUIT_AXE, ModGear.CONDUIT_SHOVEL, ModGear.CONDUIT_HOE)) {
            if (new ItemStack(tool.get()).getMaxDamage() != c.durability) {
                helper.fail(tool.getId() + " の壊れにくさ " + new ItemStack(tool.get()).getMaxDamage() + " ≠ " + c.durability);
                return;
            }
        }
        if (Math.abs(pick.getDestroySpeed(Blocks.DIRT.defaultBlockState()) - 1.0F) > EPS) {
            helper.fail("つるはしが土で速い");
            return;
        }
        helper.succeed();
    }

    /** 力がある間は速さ 1.5 倍・1 ブロックで 100 FE 減る・足りなくなるとふつうの速さに戻る(壊れにくさは減る)。創造モードは減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitChargeBoostsAndDrains(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Conduit c = t().conduit;
        FakePlayer player = player(helper, "ov_conduit_miner");
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState stone = Blocks.STONE.defaultBlockState();
        ItemStack pick = new ItemStack(ModGear.CONDUIT_PICKAXE.get());
        LeyCharge.set(pick, c.capacity);
        float boosted = pick.getDestroySpeed(stone);
        if (Math.abs(boosted - c.speed * c.chargedSpeedMultiplier) > EPS) {
            helper.fail("充電中の速さ " + boosted + " ≠ " + c.speed * c.chargedSpeedMultiplier);
            return;
        }
        pick.mineBlock(level, stone, at, player);
        if (LeyCharge.stored(pick) != c.capacity - c.fePerBlock || pick.getDamageValue() != 1) {
            helper.fail("1 ブロックで減った力 " + (c.capacity - LeyCharge.stored(pick)) + "(期待 " + c.fePerBlock + ")・傷 " + pick.getDamageValue());
            return;
        }
        // 道具の効かないブロックでは減らない
        int before = LeyCharge.stored(pick);
        pick.mineBlock(level, Blocks.DIRT.defaultBlockState(), at, player);
        if (LeyCharge.stored(pick) != before) {
            helper.fail("土を掘って力が減った");
            return;
        }
        LeyCharge.set(pick, c.fePerBlock - 1);
        if (Math.abs(pick.getDestroySpeed(stone) - c.speed) > EPS) {
            helper.fail("力が足りないのに速い: " + pick.getDestroySpeed(stone));
            return;
        }
        pick.mineBlock(level, stone, at, player);
        if (LeyCharge.stored(pick) != c.fePerBlock - 1) {
            helper.fail("足りないのに力が減った");
            return;
        }
        player.getAbilities().instabuild = true;
        LeyCharge.set(pick, c.capacity);
        pick.mineBlock(level, stone, at, player);
        player.getAbilities().instabuild = false;
        if (LeyCharge.stored(pick) != c.capacity) {
            helper.fail("創造モードで力が減った");
            return;
        }
        helper.succeed();
    }

    /** 汲み上げ器・鉄塔の電池の口に差せて、口から受け取れる(出さない = 網に吸い出されない)。満ちた電池を右クリックで重ねると移る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitChargesFromPumpPylonAndBattery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Conduit c = t().conduit;
        FakePlayer player = player(helper, "ov_conduit_charger");
        BlockPos pumpPos = new BlockPos(1, 2, 1), pylonPos = new BlockPos(5, 2, 5);
        helper.setBlock(pumpPos, ModLey.PUMP.get());
        helper.setBlock(pylonPos, ModLey.PYLON.get());
        int n = 0;
        for (BlockPos rel : new BlockPos[]{pumpPos, pylonPos}) {
            BlockPos abs = helper.absolutePos(rel);
            BlockState state = level.getBlockState(abs);
            if (!(level.getBlockEntity(abs) instanceof LeyBatteryHolder holder)) {
                helper.fail("電池の口が無い: " + state);
                return;
            }
            ItemStack tool = new ItemStack(n == 0 ? ModGear.CONDUIT_PICKAXE.get() : ModGear.CONDUIT_SHOVEL.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
            if (!LeyBatteryHolder.insert(level, abs, state, player, player.getMainHandItem(), holder) || holder.battery().isEmpty()) {
                helper.fail("道具を電池の口に差せない: " + state);
                return;
            }
            IEnergyStorage port = holder.batteryStorage();
            if (port == null || !port.canReceive() || port.canExtract()) {
                helper.fail("差した道具の口が受け取るだけになっていない");
                return;
            }
            int got = port.receiveEnergy(Integer.MAX_VALUE, false);
            if (got != Math.min(c.maxReceive, c.capacity) || LeyCharge.stored(holder.battery()) != got || port.extractEnergy(100, false) != 0) {
                helper.fail("口からの充電 " + got + "・中身 " + LeyCharge.stored(holder.battery()));
                return;
            }
            if (Math.abs(LeyBatteryHolder.fraction(holder.battery()) - got / (double) c.capacity) > EPS) {
                helper.fail("たまり具合の表示が合わない");
                return;
            }
            n++;
        }
        // 電池から: 持ち物の画面で満ちた電池を重ねて右クリック
        ItemStack tool = new ItemStack(ModGear.CONDUIT_AXE.get());
        ItemStack battery = new ItemStack(ModLey.BATTERY.get());
        battery.set(ModLey.ENERGY.get(), com.yayuy.overgrownvegetation.ley.LeyBatteryItem.capacity());
        int batteryBefore = com.yayuy.overgrownvegetation.ley.LeyBatteryItem.stored(battery);
        boolean handled = LeyCharge.onStackedOn(tool, battery, net.minecraft.world.inventory.ClickAction.SECONDARY, player);
        int moved = LeyCharge.stored(tool);
        int expect = Math.min(Math.min(c.capacity, batteryBefore), c.pourPerClick);
        if (!handled || moved != expect || com.yayuy.overgrownvegetation.ley.LeyBatteryItem.stored(battery) != batteryBefore - moved) {
            helper.fail("電池から移らない: 移った " + moved + "(期待 " + expect + ")・電池 " + com.yayuy.overgrownvegetation.ley.LeyBatteryItem.stored(battery));
            return;
        }
        if (tool.getCapability(Capabilities.EnergyStorage.ITEM) == null) {
            helper.fail("道具に FE の口が無い");
            return;
        }
        helper.succeed();
    }

    /** 飲み込まれた導晶鉱は緑の版になり(菌集鉱にはならない)、置き換わった 1 回だけ空気に面した所に小さな根晶が生える(掘った後は生え直さない)。小さな根晶は 25% で根晶。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitOreTurnsGreenWithRootBuds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // randomBlockChance(既定 5%)で汎用の汚染ブロックになる引きを外す(level.random のままだと 3 回引くので 1 回の流しで約 14% 落ちた。11-23 揺れの調べ)。
        // 確かめたいのは対応表の「導晶鉱 → 緑の版」と根晶なので、乱数はいつも高い値を返す物にする
        net.minecraft.util.RandomSource noRandomBlock = new net.minecraft.world.level.levelgen.LegacyRandomSource(0L) {
            @Override
            public double nextDouble() {
                return 0.99D;
            }
        };
        if (!CorruptionRegistry.corrupt(ModGear.CONDUIT_ORE.get().defaultBlockState(), noRandomBlock).is(ModGear.OVERGROWN_CONDUIT_ORE.get())
                || !CorruptionRegistry.corrupt(ModGear.DEEPSLATE_CONDUIT_ORE.get().defaultBlockState(), noRandomBlock).is(ModGear.OVERGROWN_DEEPSLATE_CONDUIT_ORE.get())) {
            helper.fail("飲み込まれた導晶鉱が緑の版にならない");
            return;
        }
        GearTierTable.Conduit c = t().conduit;
        double chance = c.budChance;
        int max = c.budMax;
        try {
            c.budChance = 1.0D;
            c.budMax = 2;
            BlockPos ore = new BlockPos(3, 3, 3);
            BlockPos abs = helper.absolutePos(ore);
            helper.setBlock(ore, ModGear.CONDUIT_ORE.get());
            // 本物の置き換えの口(汚染が広がるときと同じ)で緑の版にする → その 1 回だけ生える
            BlockState before = helper.getBlockState(ore);
            CorruptionRegistry.placeCorrupted(level, abs, before, CorruptionRegistry.corrupt(before, noRandomBlock),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            if (buds(helper, ore) != 2 || !helper.getBlockState(ore).is(ModGear.OVERGROWN_CONDUIT_ORE.get())) {
                helper.fail("小さな根晶の数 " + buds(helper, ore) + "(期待 2)・鉱石 " + helper.getBlockState(ore));
                return;
            }
            // 掘った後、装飾の口(前触れ・染み下がり・骨粉など何度も来る)を通しても生え直さない
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                if (helper.getBlockState(ore.relative(d)).is(ModGear.SMALL_ROOT_CRYSTAL.get())) helper.setBlock(ore.relative(d), Blocks.AIR);
            }
            CorruptionSpreader.growSurfacePlants(level, abs, helper.getBlockState(ore), 0.0D);
            if (buds(helper, ore) != 0) {
                helper.fail("掘った後に装飾の口で小さな根晶が生え直した: " + buds(helper, ore));
                return;
            }
            // 既に緑の版の物を置き換え直しても生えない(導晶鉱から変わった 1 回だけ)
            if (ConduitOre.onConverted(level, abs, helper.getBlockState(ore), helper.getBlockState(ore)) != 0) {
                helper.fail("緑の版から緑の版で小さな根晶が生えた");
                return;
            }
        } finally {
            c.budChance = chance;
            c.budMax = max;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        int hits = 0, rolls = 800;
        for (int i = 0; i < rolls; i++) {
            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(ModGear.SMALL_ROOT_CRYSTAL.get().defaultBlockState(), level, at, null, null,
                    new ItemStack(Items.IRON_PICKAXE));
            if (drops.stream().anyMatch(d -> d.is(OrganBlocks.ROOT_CRYSTAL.get()))) hits++;
        }
        double rate = hits / (double) rolls;
        if (rate < 0.18D || rate > 0.32D) {
            helper.fail("小さな根晶から根晶が出る割合 " + rate + "(期待 0.25)");
            return;
        }
        helper.succeed();
    }

    private static int buds(GameTestHelper helper, BlockPos ore) {
        int n = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockState s = helper.getBlockState(ore.relative(d));
            if (s.is(ModGear.SMALL_ROOT_CRYSTAL.get())) n++;
        }
        return n;
    }

    /** 道具の力は保存して読み直しても残る(電池の口に差したまま汲み上げ器を保存しても)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitChargeSurvivesSave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var reg = level.registryAccess();
        ItemStack pick = new ItemStack(ModGear.CONDUIT_PICKAXE.get());
        LeyCharge.set(pick, 12345);
        pick.setDamageValue(7);
        ItemStack back = ItemStack.parseOptional(reg, (CompoundTag) pick.save(reg));
        if (!back.is(ModGear.CONDUIT_PICKAXE.get()) || LeyCharge.stored(back) != 12345 || back.getDamageValue() != 7) {
            helper.fail("道具を読み直すと力・傷が違う: " + LeyCharge.stored(back) + "・" + back.getDamageValue());
            return;
        }
        CompoundTag tag = new CompoundTag();
        LeyBatteryHolder.save(tag, reg, pick);
        ItemStack held = LeyBatteryHolder.load(tag, reg);
        if (LeyCharge.stored(held) != 12345) {
            helper.fail("電池の口に差したまま保存すると力が消える: " + LeyCharge.stored(held));
            return;
        }
        helper.succeed();
    }
}
