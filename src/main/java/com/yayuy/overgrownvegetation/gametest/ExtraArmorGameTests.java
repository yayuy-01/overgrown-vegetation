package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.item.BreathingClothItem;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.ExtraArmorSlot;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 追加防具の 4 枠(2026-10-15 ユーザー指定)と、そこに付ける濾し布の呼吸覆い(サバイバル用アイテム ㉞)。
 * <ul>
 *     <li>持ち物の画面に枠が 4 つ増え(番号 46〜49、絵の右の列)、左手の枠は作業枠の下へ動いた({@link #slotsAreAddedAndOffhandMoved})。</li>
 *     <li>枠ごとのタグが付いた物だけ入る・シフトクリックで出し入れできる({@link #onlyTaggedItemsFit})。</li>
 *     <li>保存の形で中身が戻る({@link #contentsSurviveSaving})。</li>
 *     <li>呼吸覆い: かかるまでの時間が 2 倍・濃い胞子の中ですり減り、尽きると枠が空く({@link #breathingClothDoublesDwellAndWears})。</li>
 *     <li>布鎧: 体の枠にだけ入り、着ると防御 +2・攻撃でかかる確率 15%(脱ぐと戻る。{@link #gambesonGivesArmorAndHalvesAttackInfection})。</li>
 *     <li>見た目のために周りへ配る形が往復で変わらない({@link #payloadRoundTrips})。</li>
 *     <li>琥珀の角灯: 腰の枠にだけ入り、動く光の明るさ 12・置くと明るさ 15・真鍮の塊 8 + 媒介結晶で作れる({@link #amberLanternHangsAtWaistAndLights})。
 *     呼吸覆いは防護マスクをかぶっている間すり減らない({@link #breathingClothDoublesDwellAndWears} の中)。</li>
 * </ul>
 * 死んだときに落とす・keepInventory で持ち越すのは、偽プレイヤーが死なないのでテストクライアントで確かめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ExtraArmorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ExtraArmorGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "extra_armor";

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        clear(player);
        return player;
    }

    private static void clear(FakePlayer player) {
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            ExtraArmor.of(player).set(i, ItemStack.EMPTY);
        }
        player.getInventory().clearContent();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void slotsAreAddedAndOffhandMoved(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_extra_armor_slots");
        InventoryMenu menu = player.inventoryMenu;
        if (menu.slots.size() != 50) {
            helper.fail("持ち物の画面の枠が 50 でない: " + menu.slots.size());
            return;
        }
        Slot offhand = menu.slots.get(45);
        if (offhand.x != 107 || offhand.y != 62) {
            helper.fail("左手の枠が作業枠の下にない: " + offhand.x + "," + offhand.y);
            return;
        }
        for (ExtraArmor.Part part : ExtraArmor.Part.values()) {
            Slot slot = menu.slots.get(46 + part.ordinal());
            if (!(slot instanceof ExtraArmorSlot extra) || extra.part() != part || slot.x != 77 || slot.y != 8 + part.ordinal() * 18) {
                helper.fail("追加防具の " + part + " の枠がおかしい: " + slot + " " + slot.x + "," + slot.y);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void onlyTaggedItemsFit(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_extra_armor_tags");
        try {
            InventoryMenu menu = player.inventoryMenu;
            ItemStack cloth = new ItemStack(ModItems.BREATHING_CLOTH.get());
            if (!menu.slots.get(46).mayPlace(cloth) || menu.slots.get(46).mayPlace(new ItemStack(Items.IRON_HELMET))) {
                helper.fail("頭の追加防具の枠: 呼吸覆いは入り、鉄のヘルメットは入らないはず");
                return;
            }
            for (int i = 47; i < 50; i++) {
                if (menu.slots.get(i).mayPlace(cloth)) {
                    helper.fail("呼吸覆いが頭以外の追加防具の枠(" + i + ")に入る");
                    return;
                }
            }
            // シフトクリック: 持ち物(画面の枠 9 = 持ち物の 9 番)→ 頭の追加防具 → 持ち物。
            player.getInventory().setItem(9, cloth.copy());
            menu.quickMoveStack(player, 9);
            if (!ExtraArmor.worn(player, ExtraArmor.Part.HEAD).is(ModItems.BREATHING_CLOTH.get()) || !player.getInventory().getItem(9).isEmpty()) {
                helper.fail("シフトクリックで呼吸覆いが頭の追加防具の枠に入らない");
                return;
            }
            menu.quickMoveStack(player, 46);
            if (!ExtraArmor.worn(player, ExtraArmor.Part.HEAD).isEmpty()
                    || player.getInventory().countItem(ModItems.BREATHING_CLOTH.get()) != 1) {
                helper.fail("シフトクリックで追加防具の枠から持ち物へ戻らない");
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void contentsSurviveSaving(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ExtraArmor armor = new ExtraArmor();
        ItemStack cloth = new ItemStack(ModItems.BREATHING_CLOTH.get());
        cloth.setDamageValue(17);
        armor.set(ExtraArmor.Part.HEAD.ordinal(), cloth);
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = ExtraArmor.CODEC.encodeStart(ops, armor).getOrThrow();
        ExtraArmor back = ExtraArmor.CODEC.parse(ops, tag).getOrThrow();
        ItemStack head = back.get(ExtraArmor.Part.HEAD.ordinal());
        if (!head.is(ModItems.BREATHING_CLOTH.get()) || head.getDamageValue() != 17 || !back.get(1).isEmpty()) {
            helper.fail("保存して読み直すと中身が変わる: " + head + " 耐久の減り " + head.getDamageValue());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void breathingClothDoublesDwellAndWears(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_extra_armor_cloth");
        try {
            if (BreathingClothItem.dwellFactor(player) != 1.0D) {
                helper.fail("付けていないのに倍率が 1 でない");
                return;
            }
            ExtraArmor.of(player).set(ExtraArmor.Part.HEAD.ordinal(), new ItemStack(ModItems.BREATHING_CLOTH.get()));
            double factor = DesignData.effects().playerInfection().breathingClothDwellFactor();
            if (BreathingClothItem.dwellFactor(player) != factor || factor != 2.0D) {
                helper.fail("呼吸覆いでかかるまでの時間が 2 倍にならない: " + BreathingClothItem.dwellFactor(player));
                return;
            }
            int every = DesignData.effects().playerInfection().breathingClothWearIntervalTicks();
            SporeExposure exposure = new SporeExposure();
            BreathingClothItem.wear(helper.getLevel(), player, exposure, every * 3);
            if (ExtraArmor.worn(player, ExtraArmor.Part.HEAD).getDamageValue() != 3) {
                helper.fail("濃い胞子の中で間隔 3 回ぶん耐久が 3 減らない: " + ExtraArmor.worn(player, ExtraArmor.Part.HEAD).getDamageValue());
                return;
            }
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(ModItems.PROTECTIVE_MASK.get()));
            BreathingClothItem.wear(helper.getLevel(), player, exposure, every * 5);
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
            if (ExtraArmor.worn(player, ExtraArmor.Part.HEAD).getDamageValue() != 3) {
                helper.fail("防護マスクをかぶっている間も呼吸覆いがすり減った");
                return;
            }
            BreathingClothItem.wear(helper.getLevel(), player, exposure, every * BreathingClothItem.DURABILITY);
            if (!ExtraArmor.worn(player, ExtraArmor.Part.HEAD).isEmpty()) {
                helper.fail("耐久が尽きても枠が空かない");
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void gambesonGivesArmorAndHalvesAttackInfection(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_extra_armor_gambeson");
        try {
            InventoryMenu menu = player.inventoryMenu;
            ItemStack gambeson = new ItemStack(ModItems.GAMBESON.get());
            if (!menu.slots.get(47).mayPlace(gambeson) || menu.slots.get(46).mayPlace(gambeson)
                    || menu.slots.get(47).mayPlace(new ItemStack(ModItems.BREATHING_CLOTH.get()))) {
                helper.fail("布鎧は体の追加防具の枠にだけ入るはず");
                return;
            }
            double base = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            double plain = com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.attackInfectChance(player);
            menu.slots.get(47).set(gambeson);
            double worn = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            if (worn - base != com.yayuy.overgrownvegetation.item.GambesonItem.DEFENSE) {
                helper.fail("布鎧を着ても防御が +2 にならない: " + base + " → " + worn);
                return;
            }
            var spec = DesignData.effects().playerInfection();
            double chance = com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.attackInfectChance(player);
            if (plain != spec.attackInfectChance() || chance != spec.gambesonAttackInfectChance() || chance != 0.15D) {
                helper.fail("攻撃でかかる確率: 着ていない " + plain + " / 着ている " + chance + "(期待 0.3 / 0.15)");
                return;
            }
            menu.slots.get(47).set(ItemStack.EMPTY);
            if (player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) != base) {
                helper.fail("布鎧を脱いでも防御が戻らない");
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
            ExtraArmor.syncAttributes(player);
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void payloadRoundTrips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        java.util.List<ItemStack> items = java.util.List.of(new ItemStack(ModItems.BREATHING_CLOTH.get()),
                new ItemStack(ModItems.GAMBESON.get()), ItemStack.EMPTY, ItemStack.EMPTY);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess(),
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        com.yayuy.overgrownvegetation.network.ExtraArmorPayload.STREAM_CODEC.encode(buf,
                new com.yayuy.overgrownvegetation.network.ExtraArmorPayload(42, items));
        var back = com.yayuy.overgrownvegetation.network.ExtraArmorPayload.STREAM_CODEC.decode(buf);
        if (back.entityId() != 42 || back.items().size() != 4 || !back.items().get(1).is(ModItems.GAMBESON.get())
                || !back.items().get(2).isEmpty()) {
            helper.fail("追加防具を配る形が往復で変わる: " + back);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void amberLanternHangsAtWaistAndLights(GameTestHelper helper) {
        FakePlayer player = player(helper, "ov_extra_armor_lantern");
        try {
            ItemStack lantern = new ItemStack(ModItems.AMBER_LANTERN.get());
            InventoryMenu menu = player.inventoryMenu;
            if (!menu.slots.get(48).mayPlace(lantern) || menu.slots.get(46).mayPlace(lantern) || menu.slots.get(47).mayPlace(lantern)) {
                helper.fail("琥珀の角灯は腰の追加防具の枠にだけ入るはず");
                return;
            }
            if (!(lantern.getItem() instanceof com.yayuy.overgrownvegetation.item.ExtraArmorItem e) || e.wornLightLevel() != 12) {
                helper.fail("腰に下げたときの動く光の明るさが 12 でない");
                return;
            }
            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(2, 2, 2);
            helper.setBlock(at, com.yayuy.overgrownvegetation.registry.ModBlocks.AMBER_LANTERN.get().defaultBlockState());
            int emission = helper.getBlockState(at).getLightEmission(helper.getLevel(), helper.absolutePos(at));
            if (emission != 15) {
                helper.fail("置いた琥珀の角灯の明るさが 15 でない: " + emission);
                return;
            }
            net.minecraft.world.item.crafting.CraftingInput grid = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, java.util.List.of(
                    nugget(), nugget(), nugget(), nugget(), new ItemStack(ModItems.MEDIUM_CRYSTAL.get()), nugget(), nugget(), nugget(), nugget()));
            ServerLevel level = helper.getLevel();
            ItemStack out = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, grid, level)
                    .map(h -> h.value().assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
            if (!out.is(ModItems.AMBER_LANTERN.get())) {
                helper.fail("真鍮の塊 8 + 媒介結晶で琥珀の角灯ができない: " + out);
                return;
            }
            helper.succeed();
        } finally {
            clear(player);
        }
    }

    private static ItemStack nugget() {
        return new ItemStack(ModItems.BRASS_NUGGET.get());
    }

    private ExtraArmorGameTests() {
    }
}
