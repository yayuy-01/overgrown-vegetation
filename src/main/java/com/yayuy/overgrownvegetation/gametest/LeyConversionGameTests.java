package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.MortarBlockEntity;
import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import com.yayuy.overgrownvegetation.block.RepellentLampBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlock;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 地脈技術の 3 段目: 転用(2026-10-24)。
 * <ul>
 *     <li>改造レシピ(地脈灯 + 忌避の精油 + 真鍮 → 忌避灯、上昇気流機 + 胞子の濾過膜 + 真鍮 → 胞子除去装置)・濾過膜・濾過膜からの胞子フィルター・
 *     乳鉢の忌避の精油が組め、4 つとも研究「転用」まで封印され、元の忌避灯のレシピは消えている。電池の入った地脈灯から作った忌避灯は電池を引き継ぐ
 *     ({@link #conversionRecipesCraftAndAreSealed})。</li>
 *     <li>胞子除去装置は電池なしで止まり、電池で動き、働いている間だけ電池が減る。拡散装置は電池と胞子の両方で動く
 *     ({@link #sporeDevicesRunOnBattery})。</li>
 *     <li>忌避灯は電池で点き(台帳に載る)、電池が切れると消えて台帳から外れ、光も 0 になる。切れた灯りは蔦を剥がしても点かない
 *     ({@link #repellentLampGoesOutWhenBatteryDies})。</li>
 * </ul>
 * 除染機の電池は {@code SporeFilterGameTests#decontaminatorWashesASpentFilter}(電池なしで止まり、電池で洗う)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class LeyConversionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(LeyConversionGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "ley_conversion";
    private static final String NODE = "ley_conversion";

    /** 置いた忌避灯に満タンの電池を入れる(ほかのテストが点いた忌避灯を要るとき用)。 */
    public static void powerLamp(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof RepellentLampBlockEntity be) {
            MachineBattery.swap(be, BatteryItem.withCharge(BatteryItem.CAPACITY));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path);
    }

    /** {@code rows} の並び(空白 = 空き)で作業台に置いたときの結果(無ければ空)とレシピ id。 */
    private static Object[] craft(ServerLevel level, int width, Item... cells) {
        List<ItemStack> list = new ArrayList<>();
        for (Item item : cells) {
            list.add(item == null ? ItemStack.EMPTY : new ItemStack(item));
        }
        CraftingInput input = CraftingInput.of(width, cells.length / width, list);
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(r -> new Object[]{r.value().assemble(input, level.registryAccess()), r.id()})
                .orElse(new Object[]{ItemStack.EMPTY, null});
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conversionRecipesCraftAndAreSealed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item brass = ModItems.BRASS_INGOT.get();
        Item oil = ModItems.REPELLENT_OIL.get();
        Item membrane = ModItems.SPORE_MEMBRANE.get();
        Object[][] cases = {
                // 結果, 個数, レシピ id, 幅, 並び
                {ModItems.REPELLENT_LAMP_ITEM.get(), 1, "repellent_lamp", 3,
                        new Item[]{null, oil, null, brass, ModItems.LEY_LAMP.get(), brass}},
                {ModItems.SPORE_PURIFIER_ITEM.get(), 1, "spore_purifier", 3,
                        new Item[]{membrane, ModItems.LEY_UPDRAFT_ITEM.get(), membrane, brass, null, brass}},
                {membrane, 2, "spore_membrane", 2,
                        new Item[]{ModItems.BREATHING_CLOTH.get(), ModItems.SPORE_SAC.get()}},
                {ModItems.SPORE_FILTER.get(), 1, "spore_filter", 3,
                        new Item[]{Items.IRON_NUGGET, membrane, Items.IRON_NUGGET, membrane, Items.STRING, membrane,
                                Items.IRON_NUGGET, membrane, Items.IRON_NUGGET}}};
        for (Object[] c : cases) {
            Object[] got = craft(level, (Integer) c[3], (Item[]) c[4]);
            ItemStack out = (ItemStack) got[0];
            if (!out.is((Item) c[0]) || out.getCount() != (Integer) c[1] || !id((String) c[2]).equals(got[1])) {
                helper.fail(c[2] + " のレシピが組めない: " + out + " / " + got[1]);
                return;
            }
        }
        // 元の忌避灯のレシピ(樹液結晶・繊維質・ランタン・鉄)は消えた
        Object[] old = craft(level, 3, null, ModItems.SAP_CRYSTAL.get(), null,
                ModItems.PLANT_FIBER.get(), Items.LANTERN, ModItems.PLANT_FIBER.get(), null, Items.IRON_INGOT, null);
        if (!((ItemStack) old[0]).isEmpty()) {
            helper.fail("元の忌避灯のレシピがまだ組める: " + old[0]);
            return;
        }
        // 封印: 4 つとも「転用」のノードだけが開く
        for (String r : List.of("repellent_lamp", "spore_purifier", "mortar_repellent_oil", "spore_membrane")) {
            String node = DesignData.research().nodeForRecipe(id(r));
            if (!NODE.equals(node)) {
                helper.fail(r + " を開くノードが「転用」でない: " + node);
                return;
            }
        }
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        if (!ResearchLogic.isRecipeLocked(player, id("repellent_lamp")) || !ResearchLogic.isRecipeLocked(player, id("spore_membrane"))) {
            helper.fail("研究の前なのに改造レシピが封印されていない");
            return;
        }
        // 乳鉢の忌避の精油: 寄生菌糸 + 薬草の粉(すりつぶす前の薬草でもよい)+ 樹液結晶の粉、封印は回収のとき
        BlockPos at = new BlockPos(3, 2, 3);
        helper.setBlock(at, ModBlocks.MORTAR.get());
        MortarBlockEntity mortar = (MortarBlockEntity) helper.getBlockEntity(at);
        for (Item herb : List.of(ModItems.MOONLIGHT_POWDER.get(), ModItems.PUNGENT_HERB.get())) {
            mortar.insert(new ItemStack(ModItems.PARASITIC_MYCELIUM.get()));
            mortar.insert(new ItemStack(herb));
            mortar.insert(new ItemStack(ModItems.SAP_CRYSTAL_POWDER.get()));
            if (!mortar.isLockedFor(level, player)) {
                helper.fail("研究の前なのに乳鉢の忌避の精油が封印されていない(" + herb + ")");
                return;
            }
            player.getData(ModAttachments.RESEARCH).unlock(NODE);
            ItemStack got = mortar.isLockedFor(level, player) ? ItemStack.EMPTY : mortar.collect(level);
            player.getData(ModAttachments.RESEARCH).unlocked().remove(NODE);
            if (!got.is(ModItems.REPELLENT_OIL.get()) || mortar.count() != 0) {
                helper.fail("乳鉢で忌避の精油が取れない(" + herb + "): " + got);
                return;
            }
        }
        player.getData(ModAttachments.RESEARCH).unlock(NODE);
        if (ResearchLogic.isRecipeLocked(player, id("spore_purifier")) || ResearchLogic.isRecipeLocked(player, id("mortar_repellent_oil"))) {
            helper.fail("「転用」を開いても封印が解けない");
            return;
        }
        // 電池の入った地脈灯から作ると、電池を引き継ぐ
        TransientCraftingContainer grid = new TransientCraftingContainer(new CraftingMenu(0, player.getInventory()), 3, 2);
        ItemStack lamp = new ItemStack(ModItems.LEY_LAMP.get());
        lamp.set(ModDataComponents.BATTERY_CHARGE.get(), 1234);
        grid.setItem(4, lamp);
        ItemStack result = new ItemStack(ModItems.REPELLENT_LAMP_ITEM.get());
        if (!MachineBattery.carryLampBattery(grid, result) || result.getOrDefault(ModDataComponents.BATTERY_CHARGE.get(), 0) != 1234) {
            helper.fail("地脈灯の電池が忌避灯へ引き継がれない: " + result.get(ModDataComponents.BATTERY_CHARGE.get()));
            return;
        }
        helper.succeed();
    }

    private static SporeDeviceBlockEntity device(GameTestHelper helper, BlockPos rel, net.minecraft.world.level.block.Block block) {
        helper.setBlock(rel, block);
        // 台帳からは外す(効果半径 48 で隣のテスト場の汚染を止めないように。SporeFilterGameTests と同じ)
        SporeDeviceData.get(helper.getLevel()).remove(helper.absolutePos(rel));
        return (SporeDeviceBlockEntity) helper.getBlockEntity(rel);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void sporeDevicesRunOnBattery(GameTestHelper helper) {
        BlockPos purifierAt = new BlockPos(1, 1, 1);
        BlockPos diffuserAt = new BlockPos(5, 1, 5);
        SporeDeviceBlockEntity purifier = device(helper, purifierAt, ModBlocks.SPORE_PURIFIER.get());
        SporeDeviceBlockEntity diffuser = device(helper, diffuserAt, ModBlocks.SPORE_DIFFUSER.get());
        purifier.setItem(SporeDeviceBlockEntity.SLOT_FILTER, new ItemStack(ModItems.SPORE_FILTER.get()));
        diffuser.setItem(SporeDeviceBlockEntity.SLOT_SPORE, new ItemStack(ModItems.SPORE.get(), 2));
        if (purifier.canPlaceItem(SporeDeviceBlockEntity.SLOT_BATTERY, new ItemStack(Items.REDSTONE))) {
            helper.fail("電池の枠にレッドストーンが入る");
            return;
        }
        helper.runAfterDelay(20, () -> {
            if (purifier.isActive() || helper.getBlockState(purifierAt).getValue(SporeDeviceBlock.LIT) || diffuser.isActive()) {
                helper.fail("電池が無いのに動いている");
                return;
            }
            MachineBattery.swap(purifier, BatteryItem.withCharge(BatteryItem.CAPACITY));
            MachineBattery.swap(diffuser, BatteryItem.withCharge(BatteryItem.CAPACITY));
            helper.runAfterDelay(20, () -> {
                int used = BatteryItem.CAPACITY - purifier.batteryCharge();
                if (!purifier.isActive() || !helper.getBlockState(purifierAt).getValue(SporeDeviceBlock.LIT) || used < 15 || used > 25) {
                    helper.fail("電池を入れた除去装置が動かないか、減り方が 1 tick に 1 でない: 動き " + purifier.isActive() + " / 減り " + used);
                    return;
                }
                if (!diffuser.isActive() || diffuser.getItem(SporeDeviceBlockEntity.SLOT_SPORE).getCount() != 1) {
                    helper.fail("拡散装置が電池と胞子で動かない(胞子 1 個を食べて動くはず): 動き " + diffuser.isActive()
                            + " / 胞子 " + diffuser.getItem(SporeDeviceBlockEntity.SLOT_SPORE).getCount());
                    return;
                }
                // 電池を抜くと止まる
                purifier.setHasBattery(false);
                helper.runAfterDelay(3, () -> {
                    if (purifier.isActive()) {
                        helper.fail("電池を抜いても止まらない");
                        return;
                    }
                    helper.setBlock(purifierAt, Blocks.AIR);
                    helper.setBlock(diffuserAt, Blocks.AIR);
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 120)
    public static void repellentLampGoesOutWhenBatteryDies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(3, 1, 3);
        BlockPos abs = helper.absolutePos(rel);
        helper.setBlock(rel, ModBlocks.REPELLENT_LAMP.get());
        RepellentLampBlockEntity lamp = (RepellentLampBlockEntity) helper.getBlockEntity(rel);
        helper.runAfterDelay(3, () -> {
            if (RepellentLampBlock.isActive(helper.getBlockState(rel)) || registered(level, abs)) {
                helper.fail("電池の無い忌避灯が点いている");
                return;
            }
            MachineBattery.swap(lamp, BatteryItem.withCharge(30));
            helper.runAfterDelay(3, () -> {
                if (!RepellentLampBlock.isActive(helper.getBlockState(rel)) || !registered(level, abs)
                        || level.getLightEmission(abs) != 15) {
                    helper.fail("電池を入れた忌避灯が点かない(台帳 " + registered(level, abs) + " / 光 " + level.getLightEmission(abs) + ")");
                    return;
                }
                helper.runAfterDelay(40, () -> {
                    if (RepellentLampBlock.isActive(helper.getBlockState(rel)) || registered(level, abs)
                            || level.getLightEmission(abs) != 0 || lamp.batteryCharge() != 0) {
                        helper.fail("電池が切れても忌避灯が消えない(台帳 " + registered(level, abs) + " / 光 " + level.getLightEmission(abs)
                                + " / 残り " + lamp.batteryCharge() + ")");
                        return;
                    }
                    // 覆われてから剥がしても、電池が無ければ点かない
                    RepellentLampBlock block = (RepellentLampBlock) ModBlocks.REPELLENT_LAMP.get();
                    block.setCovered(level, abs, helper.getBlockState(rel), true);
                    block.setCovered(level, abs, helper.getBlockState(rel), false);
                    if (registered(level, abs)) {
                        helper.fail("電池の切れた忌避灯が蔦を剥がすと台帳に戻った");
                        return;
                    }
                    helper.setBlock(rel, Blocks.AIR);
                    helper.succeed();
                });
            });
        });
    }

    private static boolean registered(ServerLevel level, BlockPos abs) {
        return abs.equals(RepellentLamps.nearestActive(level, Vec3.atCenterOf(abs), 0.5D));
    }

    private LeyConversionGameTests() {
    }
}
