package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableCrafting;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableRecipe;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * パワードアーマー作成テーブル(順 1 の着手順 5、2026-10-03)のサーバー側のテスト。
 *
 * <p>画面(クリック・描画)は GameTest では触れないので、テストクライアントの撮影と
 * ユーザーの runClient で見る。ここでは メニューの開き方・タブの切り替え・「作成」の素材の引き方・
 * レシピの読み書き・研究の封印の枠 を確かめる。
 *
 * <p>着手順 5 の時点ではこの種類のレシピが 1 つも無い(パワードアーマー本体は着手順 6)ので、
 * 「作成」はコードで組んだレシピで試す。研究の封印は、GameTest が読む {@code run/config} の写しが
 * 古いことがあるので、<b>同梱の設計データ</b>({@code /overgrown_vegetation/design/research.json})を見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PoweredArmorTableGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PoweredArmorTableGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "poweredArmorTable";

    private static PoweredArmorTableMenu openMenu(GameTestHelper helper, Player player, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = helper.getLevel().getBlockState(abs);
        MenuProvider provider = state.getMenuProvider(helper.getLevel(), abs);
        if (provider == null) {
            throw new AssertionError("テーブルが MenuProvider を返さない");
        }
        AbstractContainerMenu menu = provider.createMenu(1, player.getInventory(), player);
        if (!(menu instanceof PoweredArmorTableMenu table)) {
            throw new AssertionError("開いたメニューが作成テーブルのものでない: " + menu);
        }
        return table;
    }

    private static int countOf(Player player, Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** テーブルを右クリックで開くメニューは作成テーブルのもので、離れると閉じる(stillValid が false)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void tableOpensItsMenuAndClosesWhenFar(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.POWERED_ARMOR_TABLE.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)));
        PoweredArmorTableMenu menu = openMenu(helper, player, rel);
        // 持ち物 36 + 作業枠 + 設計図の枠(着手順 6)+ カートリッジ・因子の枠(着手順 8)
        helper.assertTrue(menu.slots.size() == 40, "枠が 40 でない: " + menu.slots.size());
        helper.assertTrue(menu.tab() == PoweredArmorTableMenu.TAB_CRAFT, "最初のタブが作成でない: " + menu.tab());
        helper.assertTrue(menu.stillValid(player), "目の前のテーブルで stillValid が false");
        player.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2.0, 30.5)));
        helper.assertFalse(menu.stillValid(player), "27 ブロック離れても stillValid が true");
        helper.succeed();
    }

    /** ボタン番号 0〜3 でタブが変わり、範囲外の番号は無視する。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void tabButtonsSwitchTabs(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.POWERED_ARMOR_TABLE.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        PoweredArmorTableMenu menu = openMenu(helper, player, rel);
        helper.assertTrue(menu.clickMenuButton(player, PoweredArmorTableMenu.TAB_TREE), "番号 2 を受け付けない");
        helper.assertTrue(menu.tab() == PoweredArmorTableMenu.TAB_TREE, "番号 2 でスキルツリーにならない: " + menu.tab());
        helper.assertFalse(menu.clickMenuButton(player, 4), "番号 4 を受け付けた");
        helper.assertFalse(menu.clickMenuButton(player, -1), "番号 -1 を受け付けた");
        helper.assertTrue(menu.tab() == PoweredArmorTableMenu.TAB_TREE, "範囲外の番号でタブが変わった: " + menu.tab());
        helper.succeed();
    }

    private static PoweredArmorTableRecipe ironAndDiamond() {
        return new PoweredArmorTableRecipe("", 0,
                List.of(SizedIngredient.of(Items.IRON_INGOT, 3), SizedIngredient.of(Items.DIAMOND, 1)),
                new ItemStack(Items.NETHERITE_SCRAP));
    }

    /** 素材は持ち物の複数の山からまとめて引き、結果は持ち物に入る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void craftTakesMaterialsFromSeveralStacks(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        player.getInventory().setItem(5, new ItemStack(Items.IRON_INGOT, 2));
        player.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 1));
        PoweredArmorTableCrafting.Result r = PoweredArmorTableCrafting.craft(player, ironAndDiamond());
        helper.assertTrue(r == PoweredArmorTableCrafting.Result.OK, "作れなかった: " + r);
        helper.assertTrue(countOf(player, Items.IRON_INGOT) == 1, "鉄が 1 残っていない: " + countOf(player, Items.IRON_INGOT));
        helper.assertTrue(countOf(player, Items.DIAMOND) == 0, "ダイヤが残っている");
        helper.assertTrue(countOf(player, Items.NETHERITE_SCRAP) == 1, "結果が持ち物に無い");
        helper.succeed();
    }

    /** 足りなければ何も引かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void craftRefusesWhenShortAndTakesNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 1));
        PoweredArmorTableCrafting.Result r = PoweredArmorTableCrafting.craft(player, ironAndDiamond());
        helper.assertTrue(r == PoweredArmorTableCrafting.Result.MISSING, "足りないのに MISSING でない: " + r);
        helper.assertTrue(countOf(player, Items.IRON_INGOT) == 2, "足りないのに鉄が減った");
        helper.assertTrue(countOf(player, Items.DIAMOND) == 1, "足りないのにダイヤが減った");
        helper.assertTrue(countOf(player, Items.NETHERITE_SCRAP) == 0, "足りないのに結果が出た");
        helper.succeed();
    }

    /** 2 つの素材の行が同じアイテムに当たるとき、1 つの山を二重に数えない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void oneStackIsNotCountedTwice(GameTestHelper helper) {
        PoweredArmorTableRecipe twoRows = new PoweredArmorTableRecipe("", 0,
                List.of(SizedIngredient.of(Items.IRON_INGOT, 2), SizedIngredient.of(Items.IRON_INGOT, 2)),
                new ItemStack(Items.NETHERITE_SCRAP));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        helper.assertFalse(PoweredArmorTableCrafting.hasAll(twoRows, player.getInventory().items), "鉄 3 で 2+2 を通した");
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        PoweredArmorTableCrafting.Result r = PoweredArmorTableCrafting.craft(player, twoRows);
        helper.assertTrue(r == PoweredArmorTableCrafting.Result.OK, "鉄 4 で 2+2 が作れない: " + r);
        helper.assertTrue(countOf(player, Items.IRON_INGOT) == 0, "鉄が残っている: " + countOf(player, Items.IRON_INGOT));
        helper.succeed();
    }

    /** レシピの JSON を読めて、通信の形に書いて戻しても同じ中身になる(着手順 5 では本物のレシピがまだ無いので)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void recipeJsonAndNetworkRoundTrip(GameTestHelper helper) {
        String json = """
                {
                  "type": "overgrown_vegetation:powered_armor_table",
                  "label": "gui.overgrown_vegetation.powered_armor_table.part.helmet",
                  "sort": 3,
                  "ingredients": [
                    { "ingredient": { "item": "minecraft:iron_ingot" }, "count": 5 },
                    { "ingredient": { "item": "minecraft:diamond" }, "count": 2 }
                  ],
                  "rebuild_extra": [
                    { "ingredient": { "item": "minecraft:gold_ingot" }, "count": 3 }
                  ],
                  "result": { "id": "minecraft:netherite_scrap", "count": 2 }
                }
                """;
        JsonElement el = JsonParser.parseString(json);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        PoweredArmorTableRecipe parsed = PoweredArmorTableRecipe.Serializer.CODEC.codec().parse(ops, el)
                .getOrThrow(msg -> new AssertionError("JSON を読めない: " + msg));
        helper.assertTrue(parsed.sort() == 3, "sort が 3 でない: " + parsed.sort());
        helper.assertTrue(parsed.label().endsWith("part.helmet"), "label が違う: " + parsed.label());
        helper.assertTrue(parsed.ingredients().size() == 2 && parsed.ingredients().get(0).count() == 5,
                "素材が違う: " + parsed.ingredients());
        helper.assertTrue(parsed.result().is(Items.NETHERITE_SCRAP) && parsed.result().getCount() == 2,
                "結果が違う: " + parsed.result());
        helper.assertTrue(parsed.rebuildExtra().size() == 1 && parsed.rebuildExtra().get(0).count() == 3
                && parsed.rebuildMaterials().size() == 3, "作り直しの追加が違う: " + parsed.rebuildExtra());

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        PoweredArmorTableRecipe.Serializer.STREAM_CODEC.encode(buf, parsed);
        PoweredArmorTableRecipe back = PoweredArmorTableRecipe.Serializer.STREAM_CODEC.decode(buf);
        helper.assertTrue(back.label().equals(parsed.label()) && back.sort() == parsed.sort(), "label/sort が戻らない");
        helper.assertTrue(back.ingredients().size() == 2 && back.ingredients().get(1).count() == 2
                        && back.ingredients().get(1).ingredient().test(new ItemStack(Items.DIAMOND)),
                "素材が戻らない: " + back.ingredients());
        helper.assertTrue(ItemStack.matches(back.result(), parsed.result()), "結果が戻らない: " + back.result());
        helper.assertTrue(back.rebuildExtra().size() == 1 && back.rebuildExtra().get(0).count() == 3,
                "作り直しの追加が戻らない: " + back.rebuildExtra());
        helper.succeed();
    }

    /**
     * テーブル自体のレシピ(バニラの作業台)があり、同梱の研究ツリーにそれを封印するノードの枠がある
     * (位置・費用・前提は仮置き。ユーザーが mod マネージャーで決める)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void tableRecipeExistsAndBundledResearchLocksIt(GameTestHelper helper) {
        RecipeHolder<?> holder = helper.getLevel().getServer().getRecipeManager()
                .byKey(OvergrownVegetation.id("powered_armor_table")).orElse(null);
        helper.assertTrue(holder != null, "テーブルのレシピが無い");
        helper.assertTrue(holder.value().getType() == RecipeType.CRAFTING, "テーブルのレシピが作業台のものでない");
        helper.assertTrue(holder.value().getResultItem(helper.getLevel().registryAccess())
                .is(ModItems.POWERED_ARMOR_TABLE_ITEM.get()), "テーブルのレシピの結果がテーブルでない");

        JsonObject nodes;
        try (InputStream in = PoweredArmorTableGameTests.class.getResourceAsStream("/overgrown_vegetation/design/research.json")) {
            if (in == null) {
                throw new AssertionError("同梱の research.json が無い");
            }
            nodes = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("nodes");
        } catch (java.io.IOException e) {
            throw new AssertionError("research.json を読めない: " + e);
        }
        boolean locked = false;
        for (String id : nodes.keySet()) {
            com.google.gson.JsonArray unlocks = nodes.getAsJsonObject(id).getAsJsonArray("unlocks");
            if (unlocks == null) {
                continue;
            }
            for (JsonElement u : unlocks) {
                String s = u.getAsString();
                if (s.equals("powered_armor_table") || s.equals(OvergrownVegetation.MODID + ":powered_armor_table")) {
                    locked = true;
                }
            }
        }
        helper.assertTrue(locked, "同梱の研究ツリーにテーブルのレシピを封印するノードが無い");
        helper.succeed();
    }

    private PoweredArmorTableGameTests() {
    }
}
