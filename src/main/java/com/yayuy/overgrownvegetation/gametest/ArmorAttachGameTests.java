package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.FilterMask;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import com.yayuy.overgrownvegetation.gear.attach.AttachEffects;
import com.yayuy.overgrownvegetation.gear.attach.AttachBenchMenu;
import com.yayuy.overgrownvegetation.gear.attach.BeltPouchMenu;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import com.yayuy.overgrownvegetation.port.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.GameType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 防具の付け物(2026-11-21 夜)の確かめ: 付けられる防具・部位と数・付け外し・保存と復元・樹皮の当て板の防御・
 * 口覆いの布でフィルターが減り胞子を防ぐ・導晶装飾の布の減りの倍率・付け物の台の画面・ベルトの小物入れ・鍛冶台で残る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ArmorAttachGameTests {

    private static final String T = "gametest_empty7";
    private static final String BATCH = "armor_attach";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ArmorAttachGameTests.class);
        }

        private Registration() {
        }
    }

    private static ItemStack bark() {
        return new ItemStack(ModArmorAttach.BARK_PLATE.get());
    }

    /** バニラとこの MOD の防具には付く・ほかの MOD の防具・馬鎧・狼の鎧・防具でない物には付かない。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void onlyVanillaAndModArmor(GameTestHelper h) {
        h.assertTrue(ArmorAttachments.canHold(new ItemStack(Items.IRON_HELMET)), "鉄の兜に付かない");
        h.assertTrue(ArmorAttachments.canHold(new ItemStack(Items.LEATHER_BOOTS)), "革の靴に付かない");
        h.assertTrue(ArmorAttachments.canHold(new ItemStack(Items.TURTLE_HELMET)), "亀の甲羅に付かない");
        h.assertTrue(ArmorAttachments.canHold(new ItemStack(ModItems.PROTECTIVE_MASK.get())), "防護マスク(この MOD の防具)に付かない");
        h.assertFalse(ArmorAttachments.canHold(new ItemStack(Items.WOLF_ARMOR)), "狼の鎧に付いた");
        h.assertFalse(ArmorAttachments.canHold(new ItemStack(Items.IRON_HORSE_ARMOR)), "馬鎧に付いた");
        h.assertFalse(ArmorAttachments.canHold(new ItemStack(Items.ELYTRA)), "エリトラに付いた");
        h.assertFalse(ArmorAttachments.canHold(new ItemStack(Items.STICK)), "棒に付いた");
        // ほかの MOD の防具(同じ鉄の防具を、ほかの名前空間の物として問う)
        Item other = Items.IRON_CHESTPLATE; // 登録の後に新しい Item は作れないので、同じ物を別の名前空間として問う
        h.assertTrue(ArmorAttachments.canHold(other, "minecraft"), "名前空間 minecraft の防具に付かない");
        h.assertFalse(ArmorAttachments.canHold(other, "someothermod"), "ほかの MOD の防具に付いた");
        h.succeed();
    }

    /** 部位と数: ベルト・装飾の布は脚だけ、口覆いの布は頭だけ(防護マスクには付かない)、樹皮はどこでも 3 枚まで。枠は 3 つ。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void partsAndLimits(GameTestHelper h) {
        ItemStack helmet = new ItemStack(Items.IRON_HELMET), legs = new ItemStack(Items.IRON_LEGGINGS);
        ItemStack belt = new ItemStack(ModArmorAttach.STORAGE_BELT.get()), sash = new ItemStack(ModArmorAttach.CONDUIT_SASH.get());
        ItemStack cloth = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        h.assertFalse(ArmorAttachments.fits(helmet, belt), "ベルトが頭に付く");
        h.assertFalse(ArmorAttachments.fits(legs, cloth), "口覆いの布が脚に付く");
        h.assertFalse(ArmorAttachments.fits(new ItemStack(ModItems.PROTECTIVE_MASK.get()), cloth), "口覆いの布が防護マスクに付く");
        h.assertTrue(ArmorAttachments.fits(helmet, cloth), "口覆いの布が鉄の兜に付かない");
        h.assertFalse(ArmorAttachments.fits(legs, new ItemStack(Items.STICK)), "棒が付け物になった");
        h.assertTrue(ArmorAttachments.attach(legs, 0, belt) && ArmorAttachments.attach(legs, 1, sash) && ArmorAttachments.attach(legs, 2, bark()),
                "脚にベルト・装飾の布・樹皮が付かない");
        h.assertFalse(ArmorAttachments.attach(legs, 2, bark()), "埋まった枠に付いた");
        h.assertFalse(ArmorAttachments.fits(legs, new ItemStack(ModArmorAttach.STORAGE_BELT.get())), "ベルトが 2 本付く");
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        h.assertTrue(ArmorAttachments.attach(chest, 0, bark()), "樹皮の 1 枚目が付かない");
        h.assertFalse(ArmorAttachments.attach(chest, 1, bark()) || ArmorAttachments.fits(chest, bark()), "樹皮が 2 枚付く(11-22 ユーザー: 同じ物は 1 つの防具に 1 つまで)");
        // 外す
        ItemStack off = ArmorAttachments.detach(legs, 0);
        h.assertTrue(off.is(ModArmorAttach.STORAGE_BELT.get()) && ArmorAttachments.get(legs, 0).isEmpty(), "ベルトが外れない");
        h.assertTrue(ArmorAttachments.detach(legs, 0).isEmpty(), "空の枠から何か出た");
        ArmorAttachments.detach(legs, 1);
        ArmorAttachments.detach(legs, 2);
        h.assertFalse(OvItemData.has(legs, ModArmorAttach.ATTACHMENTS.get()), "全部外してもコンポーネントが残る(素の防具と重ならない)");
        h.assertTrue(ItemStack.isSameItemSameComponents(legs, new ItemStack(Items.IRON_LEGGINGS)), "外した防具が素の防具と同じにならない");
        h.succeed();
    }

    /** 保存と復元(NBT と通信の両方)で付け物と中身が残る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void saveAndLoad(GameTestHelper h) {
        var reg = h.getLevel().registryAccess();
        ItemStack legs = new ItemStack(Items.DIAMOND_LEGGINGS);
        ItemStack belt = new ItemStack(ModArmorAttach.STORAGE_BELT.get());
        OvItemData.set(belt, ModArmorAttach.BELT_CONTENTS.get(), net.minecraft.world.item.component.ItemContainerContents.fromItems(
                java.util.List.of(new ItemStack(Items.TORCH, 12), ItemStack.EMPTY, new ItemStack(Items.BREAD, 3))));
        ArmorAttachments.attach(legs, 1, belt);
        ArmorAttachments.attach(legs, 2, bark());
        Tag tag = legs.save(reg);
        ItemStack back = ItemStack.parseOptional(reg, (net.minecraft.nbt.CompoundTag) tag);
        h.assertTrue(ItemStack.isSameItemSameComponents(legs, back), "NBT の往復で変わった");
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), reg);
        ItemStack.STREAM_CODEC.encode(buf, legs);
        ItemStack net = ItemStack.STREAM_CODEC.decode(buf);
        h.assertTrue(ItemStack.isSameItemSameComponents(legs, net), "通信の往復で変わった");
        ItemStack belt2 = ArmorAttachments.get(back, 1);
        var c = OvItemData.get(belt2, ModArmorAttach.BELT_CONTENTS.get());
        h.assertTrue(c != null && c.getStackInSlot(0).getCount() == 12 && c.getStackInSlot(2).is(Items.BREAD), "ベルトの中身が残らない");
        h.succeed();
    }

    /** 鍛冶台(ネザライト化)で付け物が残る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void survivesNetheriteUpgrade(GameTestHelper h) {
        var level = h.getLevel();
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        ArmorAttachments.attach(chest, 0, bark());
        SmithingRecipeInput in = new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), chest, new ItemStack(Items.NETHERITE_INGOT));
        RecipeHolder<?> r = level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, in, level).orElse(null);
        h.assertTrue(r != null, "ネザライト化のレシピが見つからない");
        ItemStack out = ((net.minecraft.world.item.crafting.SmithingRecipe) r.value()).assemble(in, level.registryAccess());
        h.assertTrue(out.is(Items.NETHERITE_CHESTPLATE) && ArmorAttachments.count(out, ModArmorAttach.BARK_PLATE.get()) == 1, "ネザライト化で付け物が消えた");
        h.succeed();
    }

    /** 樹皮の当て板: 着ると 1 枚ごとに防御 +1(ハスクに着せる。偽プレイヤーは tick しない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 40)
    public static void barkPlateAddsArmor(GameTestHelper h) {
        Husk husk = h.spawnWithNoFreeWill(EntityType.HUSK, 3, 2, 3);
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        ArmorAttachments.attach(chest, 0, bark());
        ArmorAttachments.attach(chest, 1, bark());
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        ArmorAttachments.attach(boots, 0, bark());
        husk.setItemSlot(EquipmentSlot.CHEST, chest);
        husk.setItemSlot(EquipmentSlot.FEET, boots);
        // ハスクは素で防御 2。鉄の胸当て 6 + 靴 2 + 樹皮 3
        h.runAfterDelay(5, () -> {
            int expect = 2 + 6 + 2 + 2; // 樹皮は胸 1 枚(2 枚目は付かない)・靴 1 枚
            h.assertTrue(husk.getArmorValue() == expect, "防御 " + husk.getArmorValue() + "(期待 " + expect + ")");
            husk.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            h.runAfterDelay(3, () -> {
                h.assertTrue(husk.getArmorValue() == expect - 1, "樹皮のない靴に替えても防御が減らない: " + husk.getArmorValue());
                h.succeed();
            });
        });
    }

    /** 口覆いの布: 鉄の兜に付けるとフィルターを使う頭になり、胞子を防いでフィルターが減る。外すと詰まりが布へ戻る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void mouthClothFiltersSpores(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        h.assertFalse(FilterMask.isMask(helmet), "素の兜がフィルターを使う頭になった");
        ItemStack cloth = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        OvItemData.set(cloth, ModDataComponents.MASK_FILTER_USED, 100); // 少し詰まった布
        ArmorAttachments.attach(helmet, 0, cloth);
        h.assertTrue(FilterMask.isMask(helmet) && AirFilters.used(helmet) == 100, "布の詰まりが兜へ移らない: " + AirFilters.used(helmet));
        h.assertTrue(AirFilters.used(ArmorAttachments.get(helmet, 0)) == 0, "枠の中の布にも詰まりが残っている(二重に数える)");
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        h.assertTrue(ProtectiveMaskItem.isProtecting(player), "布の付いた兜が胞子を防がない");
        h.assertTrue(SporeExposureTracker.exposureFactor(player) == 0.0D, "暴露の倍率が 0 でない");
        boolean protecting = SporeExposureTracker.wearMask(player, 50.0D, 20);
        int used = AirFilters.used(player.getItemBySlot(EquipmentSlot.HEAD));
        h.assertTrue(protecting && used >= 100 + 49, "濃度 50% の 1 秒でフィルターが減らない: " + used);
        h.assertFalse(SporeExposureTracker.addExposure(player, 30.0D, true), "吸った胞子が暴露に積まれた");
        // 外す
        ItemStack back = ArmorAttachments.detach(player.getItemBySlot(EquipmentSlot.HEAD), 0);
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        h.assertTrue(AirFilters.used(back) >= used, "外した布に詰まりが戻らない: " + AirFilters.used(back));
        h.assertFalse(OvItemData.has(head, ModDataComponents.MASK_FILTER_USED) || FilterMask.isMask(head), "外した後も兜にフィルターが残る");
        h.assertFalse(ProtectiveMaskItem.isProtecting(player), "布を外しても防いでいる");
        // 詰まり切った布は防がない
        ItemStack spent = back.copy();
        OvItemData.set(spent, ModDataComponents.MASK_FILTER_USED, (int) Math.ceil(AirFilters.capacity(spent)));
        ArmorAttachments.attach(head, 0, spent);
        h.assertFalse(ProtectiveMaskItem.isProtecting(player), "詰まり切った布で防いでいる");
        h.succeed();
    }

    /** 導晶装飾の布: 脚に着けると地脈の力の減りが 1 割減る(100 → 90)。外すと戻る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void conduitSashCutsLeyDrain(GameTestHelper h) {
        Husk husk = h.spawnWithNoFreeWill(EntityType.HUSK, 3, 2, 3);
        h.assertTrue(ArmorAttachments.leyCost(husk, 100) == 100, "何も着ないのに減りが変わった");
        ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
        ArmorAttachments.attach(legs, 0, new ItemStack(ModArmorAttach.CONDUIT_SASH.get()));
        husk.setItemSlot(EquipmentSlot.LEGS, legs);
        h.assertTrue(Math.abs(ArmorAttachments.leyCostFactor(husk) - 0.9D) < 1e-9, "倍率 " + ArmorAttachments.leyCostFactor(husk));
        h.assertTrue(ArmorAttachments.leyCost(husk, 100) == 90 && ArmorAttachments.leyCost(husk, 1) == 1, "払う量 " + ArmorAttachments.leyCost(husk, 100));
        // 導晶のつるはしで 1 ブロック分払う(LeyCharge.spend から読まれている)
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setItemSlot(EquipmentSlot.LEGS, legs.copy());
        ItemStack pick = new ItemStack(com.yayuy.overgrownvegetation.gear.ModGear.CONDUIT_PICKAXE.get());
        int cap = com.yayuy.overgrownvegetation.gear.LeyCharge.capacity(pick);
        com.yayuy.overgrownvegetation.gear.LeyCharge.set(pick, cap);
        int cost = com.yayuy.overgrownvegetation.gear.LeyCharge.cost(pick);
        com.yayuy.overgrownvegetation.gear.LeyCharge.spend(pick, player);
        int paid = cap - com.yayuy.overgrownvegetation.gear.LeyCharge.stored(pick);
        h.assertTrue(paid == ArmorAttachments.leyCost(player, cost) && paid < cost, "つるはしの 1 回の減り " + paid + "(素の " + cost + ")");
        h.succeed();
    }

    /** 付け物の台の画面: 防具を置く → 付け物をはめる(手で置く・シフトで送る) → 外す → 閉じると防具が持ち物へ戻る。物は増えも減りもしない。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void benchMenuAttachDetach(GameTestHelper h) {
        // 閉じたときに持ち物へ戻すのは ServerPlayer だけ(バニラの clearContainer)なので、偽の ServerPlayer で
        Player player = net.minecraftforge.common.util.FakePlayerFactory.get(h.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("ov_armor_attach_bench".getBytes()), "ov_armor_attach_bench"));
        player.getInventory().clearContent();
        var inv = player.getInventory();
        inv.setItem(0, new ItemStack(Items.IRON_CHESTPLATE));
        inv.setItem(1, new ItemStack(ModArmorAttach.BARK_PLATE.get(), 5));
        AttachBenchMenu menu = new AttachBenchMenu(1, inv, ContainerLevelAccess.create(h.getLevel(), h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1))));
        int hot0 = AttachBenchMenu.INV_END - 9, hot1 = hot0 + 1;
        // 付け物の枠は防具が無い間は使えない
        h.assertFalse(menu.getSlot(AttachBenchMenu.FIRST_ATTACH).isActive(), "防具が無いのに付け物の枠が開いている");
        menu.quickMoveStack(player, hot0); // 胸当てを台へ
        h.assertTrue(menu.armor().is(Items.IRON_CHESTPLATE) && inv.getItem(0).isEmpty(), "シフトで防具が台へ行かない");
        // 手で 1 枚はめる: 樹皮をつかんで枠 1 で左クリック
        menu.clicked(hot1, 0, ClickType.PICKUP, player);
        menu.clicked(AttachBenchMenu.FIRST_ATTACH, 0, ClickType.PICKUP, player);
        h.assertTrue(ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) == 1 && menu.getCarried().getCount() == 4,
                "手で置いたら 1 枚だけはまらない: 付いた " + ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) + "・手 " + menu.getCarried().getCount());
        menu.clicked(hot1, 0, ClickType.PICKUP, player); // 手の残りを戻す
        // シフトで 2 枚目を入れようとしても入らない(同じ物は 1 つまで)
        for (int i = 0; i < 2; i++) menu.quickMoveStack(player, hot1);
        h.assertTrue(ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) == 1 && inv.getItem(1).getCount() == 4,
                "シフトで樹皮が 2 枚入った: 付いた " + ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) + "・残り " + inv.getItem(1).getCount());
        // シフトで外す → シフトで付け直す
        menu.quickMoveStack(player, AttachBenchMenu.FIRST_ATTACH);
        h.assertTrue(ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) == 0 && inv.getItem(1).getCount() == 5, "シフトで外れない");
        menu.quickMoveStack(player, hot1);
        h.assertTrue(ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) == 1 && inv.getItem(1).getCount() == 4, "シフトで付かない");
        // 閉じると付け物を付けたまま防具が戻る
        menu.removed(player);
        int found = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.IRON_CHESTPLATE)) found = i;
        h.assertTrue(found >= 0 && ArmorAttachments.count(inv.getItem(found), ModArmorAttach.BARK_PLATE.get()) == 1, "閉じた後に防具が戻らない・付け物が消えた");
        h.succeed();
    }

    /** 台で口覆いの布を別の付け物と入れ替えても、手に来た布にフィルターの詰まりが付いてくる(新品にすり替わらない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void benchSwapKeepsFilterState(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var inv = player.getInventory();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        ItemStack cloth = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        OvItemData.set(cloth, ModDataComponents.MASK_FILTER_USED, 5000);
        ArmorAttachments.attach(helmet, 0, cloth);
        inv.setItem(0, helmet);
        inv.setItem(1, bark());
        AttachBenchMenu menu = new AttachBenchMenu(1, inv, ContainerLevelAccess.NULL);
        int hot0 = AttachBenchMenu.INV_END - 9, hot1 = hot0 + 1;
        menu.quickMoveStack(player, hot0);
        menu.clicked(hot1, 0, ClickType.PICKUP, player);                          // 樹皮をつかむ
        menu.clicked(AttachBenchMenu.FIRST_ATTACH, 0, ClickType.PICKUP, player);   // 布の枠で入れ替え
        ItemStack carried = menu.getCarried();
        h.assertTrue(carried.is(ModArmorAttach.MOUTH_CLOTH.get()) && AirFilters.used(carried) == 5000,
                "入れ替えで手に来た布の詰まり " + AirFilters.used(carried) + "(期待 5000)・手 " + carried);
        h.assertTrue(ArmorAttachments.count(menu.armor(), ModArmorAttach.BARK_PLATE.get()) == 1 && !FilterMask.isMask(menu.armor()), "兜に樹皮が付かない・フィルターが残る");
        h.succeed();
    }

    /** ベルトの小物入れ: 入れた物がベルトに入り、開き直しても残る。ベルトを外すと中身ごと持ち運べる。脚を替えると閉じる。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void beltPouchStoresItems(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
        ArmorAttachments.attach(legs, 0, bark());
        ArmorAttachments.attach(legs, 1, new ItemStack(ModArmorAttach.STORAGE_BELT.get()));
        player.setItemSlot(EquipmentSlot.LEGS, legs);
        player.getInventory().setItem(0, new ItemStack(Items.TORCH, 20));
        BeltPouchMenu menu = BeltPouchMenu.server(1, player.getInventory(), player);
        int size = menu.size();
        h.assertTrue(size == BeltPouchMenu.configuredSize(), "枠の数 " + size);
        menu.quickMoveStack(player, size + 27); // 道具の段の先頭(松明)を小物入れへ
        h.assertTrue(player.getInventory().getItem(0).isEmpty(), "松明が小物入れへ行かない");
        BeltPouchMenu again = BeltPouchMenu.server(2, player.getInventory(), player);
        h.assertTrue(again.getSlot(0).getItem().is(Items.TORCH) && again.getSlot(0).getItem().getCount() == 20, "開き直すと中身が無い");
        h.assertFalse(BeltPouchMenu.allowed(new ItemStack(ModArmorAttach.STORAGE_BELT.get())), "ベルトの中にベルトが入る");
        h.assertFalse(BeltPouchMenu.allowed(new ItemStack(Items.SHULKER_BOX)), "シュルカーの箱が入る");
        // 脚を替えると閉じる
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        h.assertFalse(again.stillValid(player), "脚を替えても開いたまま");
        // 外したベルトは中身を持っている
        ItemStack belt = ArmorAttachments.detach(legs, 1);
        var c = OvItemData.get(belt, ModArmorAttach.BELT_CONTENTS.get());
        h.assertTrue(c != null && c.getStackInSlot(0).is(Items.TORCH), "外したベルトに中身が無い");
        h.succeed();
    }

    /**
     * 布の付いた兜(バニラの物)の上で、持ち物の画面からフィルターを右クリックすると入れ替わる(防護マスクと同じ手つき)。
     * 兜は新しいフィルター(詰まり 0)に、手には詰まったままの古いフィルターが来る。布の付いていない兜では何も起きない。
     */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void filterSwapsOnClothHelmet(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var menu = player.inventoryMenu;
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        ArmorAttachments.attach(helmet, 0, new ItemStack(ModArmorAttach.MOUTH_CLOTH.get()));
        int cap = (int) Math.ceil(AirFilters.capacity(helmet));
        OvItemData.set(helmet, ModDataComponents.MASK_FILTER_USED, cap); // 詰まり切った
        player.getInventory().setItem(9, helmet);
        menu.setCarried(new ItemStack(com.yayuy.overgrownvegetation.registry.ModVent.AIR_FILTER.get()));
        menu.clicked(9, 1, ClickType.PICKUP, player); // 兜の上で右クリック
        ItemStack after = player.getInventory().getItem(9);
        ItemStack hand = menu.getCarried();
        h.assertTrue(after.is(Items.IRON_HELMET) && AirFilters.used(after) == 0, "兜のフィルターが入れ替わらない: " + after + " 詰まり " + AirFilters.used(after));
        h.assertTrue(AirFilters.isFilter(hand) && AirFilters.used(hand) == cap, "手に古いフィルターが来ない: " + hand + " 詰まり " + AirFilters.used(hand));
        // 布の付いていない兜には効かない(兜とフィルターが入れ替わるだけ = バニラの持ち替え)
        ItemStack plain = new ItemStack(Items.IRON_HELMET);
        player.getInventory().setItem(10, plain);
        menu.setCarried(new ItemStack(com.yayuy.overgrownvegetation.registry.ModVent.AIR_FILTER.get()));
        menu.clicked(10, 1, ClickType.PICKUP, player);
        h.assertFalse(OvItemData.has(player.getInventory().getItem(10), ModDataComponents.MASK_FILTER_USED) && player.getInventory().getItem(10).is(Items.IRON_HELMET)
                && FilterMask.isMask(player.getInventory().getItem(10)), "布の無い兜がフィルターを使う頭になった");
        h.succeed();
    }

    /** 洗い場: 詰まった布そのもの・詰まった布の付いた兜は「フィルターを洗える物」。ベルトの中にベルトの付いた防具は入らない。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void washableAndNoNestedBelt(GameTestHelper h) {
        ItemStack cloth = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        OvItemData.set(cloth, ModDataComponents.MASK_FILTER_USED, 500);
        h.assertTrue(com.yayuy.overgrownvegetation.survival.decon.Decon.filterWashable(cloth), "詰まった布が洗えない");
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        ArmorAttachments.attach(helmet, 0, cloth);
        h.assertTrue(com.yayuy.overgrownvegetation.survival.decon.Decon.filterWashable(helmet), "詰まった布の付いた兜が洗えない");
        h.assertFalse(com.yayuy.overgrownvegetation.survival.decon.Decon.filterWashable(new ItemStack(Items.IRON_HELMET)), "素の兜が洗える物になった");
        ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
        ArmorAttachments.attach(legs, 0, new ItemStack(ModArmorAttach.STORAGE_BELT.get()));
        h.assertFalse(BeltPouchMenu.allowed(legs), "ベルトの付いた防具がベルトの中に入る");
        h.assertTrue(BeltPouchMenu.allowed(new ItemStack(Items.LEATHER_LEGGINGS)), "素の防具がベルトに入らない");
        h.succeed();
    }

    /** 体の見た目の設計値: 当て板は付けた部位の骨だけを見せる(1 つの geo に 4 部位ぶん入れても、ほかの部位が出ない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void barkShowsOnlyItsPartBones(GameTestHelper h) {
        var e = ArmorAttachments.entry(bark());
        h.assertTrue(e != null && e.bones.getOrDefault("legs", java.util.List.of()).contains("armorRightLeg")
                && !e.bones.getOrDefault("legs", java.util.List.of()).contains("armorBody")
                && e.bones.getOrDefault("chest", java.util.List.of()).contains("armorBody"), "当て板の部位ごとの骨が読めていない: " + (e == null ? null : e.bones));
        var belt = ArmorAttachments.entry(new ItemStack(ModArmorAttach.STORAGE_BELT.get()));
        h.assertTrue(belt != null && belt.bones.isEmpty(), "ベルトは全部の骨を見せる(脚の防具だが胴の骨に描く)");
        h.succeed();
    }

    // ---- 11-22 ユーザー: 防具が壊れたら付け物は手元へ・修理では消えない ----

    private static ItemStack helmetWith(int damage, ItemStack... attachments) {
        ItemStack h = new ItemStack(Items.IRON_HELMET);
        h.setDamageValue(damage);
        for (int i = 0; i < attachments.length; i++) ArmorAttachments.attach(h, i, attachments[i]);
        return h;
    }

    private static ItemStack usedCloth(int used) {
        ItemStack c = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        OvItemData.set(c, ModDataComponents.MASK_FILTER_USED, used);
        return c;
    }

    private static int countIn(Player p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
        }
        return n;
    }

    /** 着ている兜が壊れると、付け物(布はフィルターの詰まりごと)がプレイヤーの持ち物へ入る。Mob の物は足元に落ちる。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void brokenArmorDropsAttachments(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        int max = new ItemStack(Items.IRON_HELMET).getMaxDamage();
        ItemStack helmet = helmetWith(max - 1, bark(), usedCloth(300));
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        helmet.hurtAndBreak(5, h.getLevel(), player, item -> { });
        h.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "兜が壊れない");
        h.assertTrue(countIn(player, ModArmorAttach.BARK_PLATE.get()) == 1, "樹皮が持ち物に戻らない");
        ItemStack cloth = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModArmorAttach.MOUTH_CLOTH.get())) cloth = player.getInventory().getItem(i);
        }
        h.assertTrue(!cloth.isEmpty() && AirFilters.used(cloth) == 300, "布が戻らない・詰まりが消えた: " + cloth);
        Husk husk = h.spawnWithNoFreeWill(EntityType.HUSK, 3, 2, 3);
        ItemStack mobHelmet = helmetWith(max - 1, bark());
        husk.setItemSlot(EquipmentSlot.HEAD, mobHelmet);
        mobHelmet.hurtAndBreak(5, h.getLevel(), husk, item -> { });
        h.runAfterDelay(2, () -> {
            boolean dropped = !h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, husk.getBoundingBox().inflate(3),
                    e -> e.getItem().is(ModArmorAttach.BARK_PLATE.get())).isEmpty();
            h.assertTrue(dropped, "Mob の兜が壊れても樹皮が落ちない");
            h.succeed();
        });
    }

    /** 金床: 2 つの兜を合わせて直すと、両方の付け物が結果に載る(同じ物の 2 つ目は受け取ったときに持ち物へ返る)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void anvilRepairKeepsAttachments(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.CREATIVE);
        var access = ContainerLevelAccess.create(h.getLevel(), h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var menu = new net.minecraft.world.inventory.AnvilMenu(1, player.getInventory(), access);
        ItemStack left = helmetWith(100, bark());
        ItemStack right = helmetWith(100, bark(), usedCloth(200));
        menu.getSlot(0).set(left);
        menu.getSlot(1).set(right);
        menu.createResult();
        ItemStack out = menu.getSlot(2).getItem();
        h.assertTrue(out.is(Items.IRON_HELMET) && out.getDamageValue() < 100, "金床で直らない(前提): " + out);
        h.assertTrue(ArmorAttachments.count(out, ModArmorAttach.BARK_PLATE.get()) == 1 && ArmorAttachments.hasMouthCloth(out) && AirFilters.used(out) == 200,
                "結果に付け物が載らない・布の詰まりが違う: " + ArmorAttachments.list(out) + " 詰まり " + AirFilters.used(out));
        AttachEffects.onAnvilTake(new net.minecraftforge.event.entity.player.AnvilRepairEvent(player, left, right, out));
        h.assertTrue(countIn(player, ModArmorAttach.BARK_PLATE.get()) == 1, "載らなかった 2 枚目の樹皮が持ち物へ返らない");
        h.succeed();
    }

    /** 砥石: 2 つの兜を合わせると、両方の付け物が結果に載る。載らない物は受け取ったときに返る。1 つだけ置いても付け物は残る。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void grindstoneKeepsAttachments(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var access = ContainerLevelAccess.create(h.getLevel(), h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var menu = new net.minecraft.world.inventory.GrindstoneMenu(1, player.getInventory(), access);
        ItemStack top = helmetWith(100, bark());
        ItemStack bottom = helmetWith(100, bark(), usedCloth(200));
        menu.getSlot(0).set(top);
        menu.getSlot(1).set(bottom);
        ItemStack out = menu.getSlot(2).getItem();
        h.assertTrue(out.is(Items.IRON_HELMET) && ArmorAttachments.count(out, ModArmorAttach.BARK_PLATE.get()) == 1 && ArmorAttachments.hasMouthCloth(out)
                && AirFilters.used(out) == 200, "砥石の結果に付け物が載らない: " + out + " " + ArmorAttachments.list(out));
        AttachEffects.onGrindstoneTake(new net.minecraftforge.event.GrindstoneEvent.OnTakeItem(access, player, top, bottom, 0));
        h.assertTrue(countIn(player, ModArmorAttach.BARK_PLATE.get()) == 1, "載らなかった樹皮が返らない");
        menu.getSlot(1).set(ItemStack.EMPTY);
        ItemStack single = menu.getSlot(2).getItem();
        h.assertTrue(single.isEmpty() || ArmorAttachments.count(single, ModArmorAttach.BARK_PLATE.get()) == 1, "1 つだけ置いたら付け物が消えた: " + single);
        h.succeed();
    }

    /** 作業台の修理(兜 2 つ): 結果に両方の付け物を載せ、載らない物を返す。染め直し(防具 1 つ)は触らない(増えない)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void craftingRepairKeepsAttachments(GameTestHelper h) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        net.minecraft.world.SimpleContainer grid = new net.minecraft.world.SimpleContainer(4);
        grid.setItem(0, helmetWith(150, bark()));
        grid.setItem(1, helmetWith(150, bark(), usedCloth(50)));
        ItemStack out = new ItemStack(Items.IRON_HELMET);
        AttachEffects.onCrafted(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(player, out, grid));
        h.assertTrue(ArmorAttachments.count(out, ModArmorAttach.BARK_PLATE.get()) == 1 && ArmorAttachments.hasMouthCloth(out) && AirFilters.used(out) == 50,
                "作業台の修理の結果に付け物が載らない: " + ArmorAttachments.list(out));
        h.assertTrue(countIn(player, ModArmorAttach.BARK_PLATE.get()) == 1, "載らなかった樹皮が返らない");
        Player p2 = h.makeMockPlayer(GameType.SURVIVAL);
        net.minecraft.world.SimpleContainer dye = new net.minecraft.world.SimpleContainer(4);
        ItemStack leather = new ItemStack(Items.LEATHER_HELMET);
        ArmorAttachments.attach(leather, 0, bark());
        dye.setItem(0, leather);
        dye.setItem(1, new ItemStack(Items.RED_DYE));
        ItemStack dyed = leather.copy();
        AttachEffects.onCrafted(new net.minecraftforge.event.entity.player.PlayerEvent.ItemCraftedEvent(p2, dyed, dye));
        h.assertTrue(ArmorAttachments.count(dyed, ModArmorAttach.BARK_PLATE.get()) == 1 && countIn(p2, ModArmorAttach.BARK_PLATE.get()) == 0, "染め直しで樹皮が増えた");
        h.succeed();
    }

    // ---- 11-22 見直しの直し ----

    /** 設定の枠の数を減らしても、超えた所の付け物は消えず、持ち主へ返る(見直し 16)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void shrinkingSlotsReturnsOverflow(GameTestHelper h) {
        var table = ArmorAttachments.table();
        int before = table.slots;
        try {
            ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
            ArmorAttachments.attach(legs, 0, new ItemStack(ModArmorAttach.STORAGE_BELT.get()));
            ArmorAttachments.attach(legs, 1, new ItemStack(ModArmorAttach.CONDUIT_SASH.get()));
            ArmorAttachments.attach(legs, 2, bark());
            table.slots = 2;
            h.assertTrue(ArmorAttachments.list(legs).size() == 2, "枠が 2 つに見えない");
            ArmorAttachments.detach(legs, 1); // 枠の中を書き換えても、超えた所の樹皮は残る
            h.assertTrue(ArmorAttachments.count(legs, ModArmorAttach.BARK_PLATE.get()) == 1, "書き換えで超えた所の樹皮が消えた");
            Player player = h.makeMockPlayer(GameType.SURVIVAL);
            player.getInventory().setItem(5, legs);
            AttachEffects.onPlayerTick(new com.yayuy.overgrownvegetation.port.event.tick.PlayerTickEvent.Post(player));
            h.assertTrue(countIn(player, ModArmorAttach.BARK_PLATE.get()) == 1, "超えた所の樹皮が持ち主へ返らない");
            ItemStack after = player.getInventory().getItem(5);
            h.assertTrue(ArmorAttachments.count(after, ModArmorAttach.BARK_PLATE.get()) == 0
                    && ArmorAttachments.count(after, ModArmorAttach.STORAGE_BELT.get()) == 1, "返した後の防具が違う: " + ArmorAttachments.list(after));
            table.slots = before;
            h.assertTrue(ArmorAttachments.count(after, ModArmorAttach.BARK_PLATE.get()) == 0, "枠を戻したら樹皮が増えた(2 つになった)");
        } finally {
            table.slots = before;
        }
        h.succeed();
    }

    /** ベルトを入れた袋・箱(中にベルトの付いた防具を入れた物)は、小物入れに入らない(見直し 12)。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void beltCannotHideInsideAnotherContainer(GameTestHelper h) {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                new net.minecraft.world.item.component.BundleContents(java.util.List.of(new ItemStack(ModArmorAttach.STORAGE_BELT.get()))));
        h.assertFalse(BeltPouchMenu.allowed(bundle), "ベルトを入れた袋が小物入れに入る");
        ItemStack legs = new ItemStack(Items.LEATHER_LEGGINGS);
        ArmorAttachments.attach(legs, 0, new ItemStack(ModArmorAttach.STORAGE_BELT.get()));
        ItemStack chest = new ItemStack(Items.CHEST);
        chest.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(legs)));
        h.assertFalse(BeltPouchMenu.allowed(chest), "ベルトの付いた防具を入れた箱が小物入れに入る");
        ItemStack plainBundle = new ItemStack(Items.BUNDLE);
        plainBundle.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                new net.minecraft.world.item.component.BundleContents(java.util.List.of(new ItemStack(Items.TORCH, 4))));
        h.assertTrue(BeltPouchMenu.allowed(plainBundle), "ふつうの袋まで入らなくなった");
        h.succeed();
    }

    /** 台の枠の口覆いの布にフィルターを右クリックしても入れ替わらない(枠の物は写しなので、入れ替えるとフィルターが増える)。同じ物を 2 つまでにしても。 */
    @GameTest(template = T, batch = BATCH, timeoutTicks = 20)
    public static void benchClothSlotIgnoresFilterSwap(GameTestHelper h) {
        var e = ArmorAttachments.table().attachments.get("mouth_cloth");
        int before = e.max;
        try {
            e.max = 2; // 「1 つまで」に頼らずに確かめる
            Player player = h.makeMockPlayer(GameType.SURVIVAL);
            var inv = player.getInventory();
            inv.setItem(0, helmetWith(0, usedCloth(5000)));
            AttachBenchMenu menu = new AttachBenchMenu(1, inv, ContainerLevelAccess.NULL);
            menu.quickMoveStack(player, AttachBenchMenu.INV_END - 9);
            menu.setCarried(new ItemStack(com.yayuy.overgrownvegetation.registry.ModVent.AIR_FILTER.get()));
            menu.clicked(AttachBenchMenu.FIRST_ATTACH, 1, ClickType.PICKUP, player); // 枠の布の上で右クリック
            ItemStack hand = menu.getCarried();
            h.assertTrue(AirFilters.used(menu.armor()) == 5000 && AirFilters.isFilter(hand) && AirFilters.used(hand) == 0 && hand.getCount() == 1,
                    "枠の布でフィルターが入れ替わった: 兜の詰まり " + AirFilters.used(menu.armor()) + "・手 " + hand + " 詰まり " + AirFilters.used(hand));
        } finally {
            e.max = before;
        }
        h.succeed();
    }
}
