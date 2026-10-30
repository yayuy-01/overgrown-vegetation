package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorWorkshop;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.item.ArmorAccessory;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 装飾品・拡張パーツ(順 1 の着手順 9、2026-10-03)のテスト。効き目の数値は {@code gear.json} の {@code accessories} から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AccessoryGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AccessoryGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "accessory";

    private static GearTable.Accessories cfg() {
        return DesignData.gear().accessories();
    }

    /** 装飾品は通常の防具の決まった部位に付き、入れ替えると前の物が返り、外すと戻る。部位違い・寄生植物装備は弾く。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void accessoryOnNormalArmor(GameTestHelper helper) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        ItemStack cushion = new ItemStack(ModItems.CUSHION_ACCESSORY.get(), 2);
        ItemStack old = PoweredArmorWorkshop.attachAny(boots, cushion);
        helper.assertTrue(old != null && old.isEmpty(), "鉄のブーツに緩衝の装飾品が付かない");
        helper.assertTrue(ArmorAccessory.attached(boots, ArmorAccessory.Kind.ACCESSORY) == ArmorAccessory.CUSHION, "付いた中身が違う");
        helper.assertTrue(cushion.getCount() == 1, "付けた装飾品が 1 つ減っていない");

        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helper.assertTrue(PoweredArmorWorkshop.canAttach(helmet, new ItemStack(ModItems.CUSHION_ACCESSORY.get()))
                == PoweredArmorWorkshop.Result.WRONG_PART, "ブーツ用がヘルメットに付く判定");
        helper.assertTrue(PoweredArmorWorkshop.attachAny(helmet, new ItemStack(ModItems.CUSHION_ACCESSORY.get())) == null,
                "部位違いなのに付いた");
        ItemStack ironChest = new ItemStack(Items.IRON_CHESTPLATE);
        helper.assertTrue(PoweredArmorWorkshop.canAttach(ironChest, new ItemStack(ModItems.MINING_EXTENSION.get()))
                == PoweredArmorWorkshop.Result.NOT_POWERED, "拡張パーツが通常の防具に付く判定");
        helper.assertTrue(PoweredArmorWorkshop.canAttach(ironChest, new ItemStack(ModItems.CARTRIDGE.get()))
                == PoweredArmorWorkshop.Result.NOT_POWERED, "カートリッジが通常の防具に付く判定");
        ItemStack plant = new ItemStack(ModItems.PLANT_ARMOR.get());
        helper.assertFalse(ArmorAccessory.acceptsAccessories(plant), "寄生植物装備が装飾品を受け付ける");
        helper.assertTrue(ArmorAccessory.SWIFT.canHold(plant) == ArmorAccessory.Fit.NOT_ACCEPTED, "寄生植物装備に付く判定");

        ItemStack diamond = new ItemStack(Items.DIAMOND_HELMET);
        PoweredArmorWorkshop.attachAny(diamond, new ItemStack(ModItems.NIGHT_VISION_ACCESSORY.get()));
        ItemStack back = PoweredArmorWorkshop.attachAny(diamond, new ItemStack(ModItems.ENEMY_SENSE_ACCESSORY.get()));
        helper.assertTrue(back != null && back.is(ModItems.NIGHT_VISION_ACCESSORY.get()), "入れ替えで前の装飾品が返らない");
        helper.assertTrue(ArmorAccessory.attached(diamond, ArmorAccessory.Kind.ACCESSORY) == ArmorAccessory.ENEMY_SENSE,
                "入れ替えた後の中身が違う");
        ItemStack off = PoweredArmorWorkshop.detachAccessory(diamond, ArmorAccessory.Kind.ACCESSORY);
        helper.assertTrue(off != null && off.is(ModItems.ENEMY_SENSE_ACCESSORY.get()), "外した物が違う");
        helper.assertTrue(diamond.get(ArmorAccessory.Kind.ACCESSORY.component()) == null, "外したのにコンポーネントが残っている");
        helper.assertTrue(PoweredArmorWorkshop.detachAccessory(diamond, ArmorAccessory.Kind.ACCESSORY) == null,
                "付いていないのに外せた");
        helper.succeed();
    }

    /** 拡張パーツはパワードアーマーの決まった部位に付く。装飾品・カートリッジとは別の差し口で、3 つとも同時に付く。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void extensionOnPoweredArmor(GameTestHelper helper) {
        ItemStack chest = new ItemStack(ModItems.POWERED_ARMOR_CHESTPLATE.get());
        ItemStack r1 = PoweredArmorWorkshop.attachAny(chest, new ItemStack(ModItems.MINING_EXTENSION.get()));
        ItemStack r2 = PoweredArmorWorkshop.attachAny(chest, new ItemStack(ModItems.SWIFT_ACCESSORY.get()));
        ItemStack r3 = PoweredArmorWorkshop.attachAny(chest, new ItemStack(ModItems.CARTRIDGE.get()));
        helper.assertTrue(r1 != null && r1.isEmpty() && r2 != null && r2.isEmpty() && r3 != null && r3.isEmpty(),
                "パワードアーマーの胸に 3 つ付かない");
        helper.assertTrue(ArmorAccessory.attached(chest, ArmorAccessory.Kind.EXTENSION) == ArmorAccessory.MINING
                && ArmorAccessory.attached(chest, ArmorAccessory.Kind.ACCESSORY) == ArmorAccessory.SWIFT
                && PoweredArmorItem.cartridge(chest) != null, "差し口の中身が違う");
        helper.assertTrue(PoweredArmorWorkshop.canAttach(chest, new ItemStack(ModItems.ANCHOR_EXTENSION.get()))
                == PoweredArmorWorkshop.Result.WRONG_PART, "レギンス用の拡張パーツが胸に付く判定");
        ItemStack off = PoweredArmorWorkshop.detachAccessory(chest, ArmorAccessory.Kind.EXTENSION);
        helper.assertTrue(off != null && off.is(ModItems.MINING_EXTENSION.get()), "外した拡張パーツが違う");
        helper.assertTrue(ArmorAccessory.attached(chest, ArmorAccessory.Kind.ACCESSORY) == ArmorAccessory.SWIFT,
                "拡張パーツを外したら装飾品まで消えた");
        helper.succeed();
    }

    /**
     * 属性で効くものは、防具の属性の修飾に部位の枠で出る(数値は gear.json から)。防具自身の修飾は残る。
     * 見た目だけのもの(暗視)は修飾を足さない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void attributeModifiers(GameTestHelper helper) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        PoweredArmorWorkshop.attachAny(boots, new ItemStack(ModItems.CUSHION_ACCESSORY.get()));
        ItemAttributeModifiers mods = boots.getAttributeModifiers();
        helper.assertTrue(mods.modifiers().stream().anyMatch(e -> e.attribute().value() == Attributes.FALL_DAMAGE_MULTIPLIER.value()
                        && e.slot() == EquipmentSlotGroup.FEET && Math.abs(e.modifier().amount() + cfg().cushionFall()) < 1e-9),
                "緩衝の装飾品の落下ダメージの修飾が無い: " + mods.modifiers());
        helper.assertTrue(mods.modifiers().stream().anyMatch(e -> e.attribute().value() == Attributes.ARMOR.value()),
                "ブーツ自身の防御点の修飾が消えた");

        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        int before = helmet.getAttributeModifiers().modifiers().size();
        PoweredArmorWorkshop.attachAny(helmet, new ItemStack(ModItems.NIGHT_VISION_ACCESSORY.get()));
        helper.assertTrue(helmet.getAttributeModifiers().modifiers().size() == before, "暗視の装飾品が属性を足した");
        helper.succeed();
    }

    /** 着ると効いて、脱ぐと戻る: 踏ん張りの拡張パーツ付きのレギンスは、付いていないものよりノックバック耐性が設計値だけ高い。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void wearingAppliesModifier(GameTestHelper helper) {
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(3, 2, 3));
        husk.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.POWERED_ARMOR_LEGGINGS.get()));
        helper.runAfterDelay(3, () -> {
            double plain = husk.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            ItemStack legs = new ItemStack(ModItems.POWERED_ARMOR_LEGGINGS.get());
            PoweredArmorWorkshop.attachAny(legs, new ItemStack(ModItems.ANCHOR_EXTENSION.get()));
            husk.setItemSlot(EquipmentSlot.LEGS, legs);
            helper.runAfterDelay(3, () -> {
                double anchored = husk.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
                double expected = Math.min(1.0D, plain + cfg().anchorKnockback());
                helper.assertTrue(Math.abs(anchored - expected) < 1e-6,
                        "ノックバック耐性が違う: 付けていない " + plain + " / 付けた " + anchored + " / 期待 " + expected);
                husk.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
                helper.runAfterDelay(3, () -> {
                    helper.assertTrue(husk.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) < plain,
                            "脱いでもノックバック耐性が残っている");
                    helper.succeed();
                });
            });
        });
    }

    /** 装着タブ: 作業枠に通常の防具、付ける物の枠に装飾品を置ける。付ける → 外すで戻り、枠が埋まっていると外せない。shift クリックで付ける物の枠へ。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void fitTabWithAccessories(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.POWERED_ARMOR_TABLE.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)));
        BlockPos abs = helper.absolutePos(rel);
        AbstractContainerMenu opened = helper.getLevel().getBlockState(abs).getMenuProvider(helper.getLevel(), abs)
                .createMenu(1, player.getInventory(), player);
        PoweredArmorTableMenu menu = (PoweredArmorTableMenu) opened;
        menu.clickMenuButton(player, PoweredArmorTableMenu.TAB_FIT);
        Slot work = menu.getSlot(PoweredArmorTableMenu.WORK_SLOT);
        Slot attach = menu.getSlot(PoweredArmorTableMenu.ATTACH_SLOT);
        helper.assertTrue(work.mayPlace(new ItemStack(Items.IRON_BOOTS)), "作業枠に通常の防具を置けない");
        helper.assertFalse(work.mayPlace(new ItemStack(ModItems.PLANT_ARMOR.get())), "作業枠に寄生植物装備を置けた");
        helper.assertFalse(work.mayPlace(new ItemStack(Items.DIRT)), "作業枠に土を置けた");
        helper.assertTrue(attach.mayPlace(new ItemStack(ModItems.CUSHION_ACCESSORY.get()))
                && attach.mayPlace(new ItemStack(ModItems.CARTRIDGE.get())), "付ける物の枠に装飾品かカートリッジを置けない");
        helper.assertFalse(attach.mayPlace(new ItemStack(Items.DIRT)), "付ける物の枠に土を置けた");

        work.set(new ItemStack(Items.IRON_BOOTS));
        attach.set(new ItemStack(ModItems.CUSHION_ACCESSORY.get()));
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_ATTACH), "装飾品を付けられない");
        helper.assertTrue(menu.attachStack().isEmpty()
                && ArmorAccessory.attached(menu.workStack(), ArmorAccessory.Kind.ACCESSORY) == ArmorAccessory.CUSHION, "付いていない");
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_DETACH_EXTENSION), "拡張パーツが無いのに外せた");
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_DETACH_ACCESSORY), "装飾品を外せない");
        helper.assertTrue(menu.attachStack().is(ModItems.CUSHION_ACCESSORY.get())
                && ArmorAccessory.attached(menu.workStack(), ArmorAccessory.Kind.ACCESSORY) == null, "外した物が枠へ戻らない");

        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_ATTACH), "付け直せない");
        attach.set(new ItemStack(ModItems.CARTRIDGE.get()));
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_DETACH_ACCESSORY), "枠が埋まっているのに外せた");
        helper.assertFalse(menu.clickMenuButton(player, PoweredArmorTableMenu.BUTTON_ATTACH), "カートリッジが通常の防具に付いた");

        attach.set(ItemStack.EMPTY);
        menu.getSlot(0).set(new ItemStack(ModItems.SWIFT_ACCESSORY.get(), 3));
        menu.quickMoveStack(player, 0);
        helper.assertTrue(menu.attachStack().is(ModItems.SWIFT_ACCESSORY.get()) && menu.attachStack().getCount() == 1,
                "shift クリックで付ける物の枠へ 1 つ入らない: " + menu.attachStack());
        helper.succeed();
    }

    /** 6 つのレシピがあり、同梱の研究ツリーの装飾品のノードが 6 つとも封印する。言語ファイルに名前・短い名前・効き目がある。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void recipesResearchAndLang(GameTestHelper helper) {
        var manager = helper.getLevel().getServer().getRecipeManager();
        Set<String> ids = new HashSet<>();
        for (ArmorAccessory a : ArmorAccessory.values()) {
            String id = BuiltInRegistries.ITEM.getKey(a.item()).getPath();
            ids.add(id);
            helper.assertTrue(manager.byKey(OvergrownVegetation.id(id)).isPresent(), "レシピが無い: " + id);
        }
        JsonObject nodes = read("/overgrown_vegetation/design/research.json").getAsJsonObject("nodes");
        Set<String> locked = new HashSet<>();
        for (String node : nodes.keySet()) {
            JsonArray unlocks = nodes.getAsJsonObject(node).getAsJsonArray("unlocks");
            if (unlocks != null) {
                for (JsonElement u : unlocks) {
                    locked.add(u.getAsString());
                }
            }
        }
        helper.assertTrue(locked.containsAll(ids), "研究ツリーが封印していないレシピがある: " + ids);
        for (String lang : new String[]{"ja_jp", "en_us"}) {
            JsonObject json = read("/assets/overgrown_vegetation/lang/" + lang + ".json");
            for (ArmorAccessory a : ArmorAccessory.values()) {
                String id = BuiltInRegistries.ITEM.getKey(a.item()).getPath();
                for (String key : new String[]{"item.overgrown_vegetation." + id, a.shortKey(),
                        "accessory.overgrown_vegetation." + a.id() + ".effect"}) {
                    helper.assertTrue(json.has(key), lang + " に無い: " + key);
                }
            }
        }
        helper.succeed();
    }

    private static JsonObject read(String path) {
        try (InputStream in = AccessoryGameTests.class.getResourceAsStream(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(path + " を読めない: " + e);
        }
    }

    private AccessoryGameTests() {
    }
}
