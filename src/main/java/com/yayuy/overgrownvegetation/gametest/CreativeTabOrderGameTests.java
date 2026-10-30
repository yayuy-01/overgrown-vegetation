package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ItemTable;
import com.yayuy.overgrownvegetation.registry.CreativeTabOrder;
import com.yayuy.overgrownvegetation.registry.CreativeTabOrder.Section;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * クリエイティブタブの並び。見るのは<b>仕組み</b>で、どのアイテムが何番目かは見ない
 * ——2026-10-15 から並びは {@code items.json} の {@code tabs}(mod マネージャーのアイテムタブで編集)
 * なので、中身を決め打ちするとユーザーが並べ替えるたびに落ちる
 * (メモリ「GameTest の期待値は設計値から引く」)。
 *
 * <p>確かめるのは 2 つ:
 * <ul>
 *     <li>{@code items.json} に書いた順がそのとおりに出ること。</li>
 *     <li>書いていないアイテムは、今までどおりの区分の順で、書いたものの<b>後ろ</b>に並ぶこと
 *     (2026-10-10 ユーザー指定: ブロック → 機械 → 植物 → ドロップ・中間素材 → スポーンエッグ → 武器防具 → その他)。
 *     Java へ新しいアイテムを足したときに効く道筋。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CreativeTabOrderGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CreativeTabOrderGameTests.class);
        }

        private Registration() {
        }
    }

    /** {@code items.json} に書いた順がそのまま出るか(3 つのタブとも)。 */
    @GameTest(template = "gametest_empty7")
    public static void writtenOrderIsUsed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemTable table = DesignData.items();
        Map<String, CreativeModeTab> tabs = Map.of(
                ItemTable.COVERED, ModCreativeTabs.COVERED.get(),
                ItemTable.PLANTS, ModCreativeTabs.PLANTS.get(),
                ItemTable.HUMANITY, ModCreativeTabs.HUMANITY.get());
        for (Map.Entry<String, CreativeModeTab> e : tabs.entrySet()) {
            List<Item> items = tabItems(level, e.getValue());
            int previous = -1;
            for (ResourceLocation id : table.order(e.getKey())) {
                Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null) {
                    continue;                      // 消したアイテムが json に残っているだけ。並びには関わらない
                }
                int index = items.indexOf(item);
                if (index < 0) {
                    helper.fail(e.getKey() + " に書いてある " + id + " がタブに出ていない");
                    return;
                }
                if (index < previous) {
                    helper.fail(e.getKey() + ": " + id + " が items.json の順より前に出ている");
                    return;
                }
                previous = index;
            }
        }
        helper.succeed();
    }

    /**
     * {@code items.json} に書いていないアイテムが、区分の順で、書いてあるものの後ろに並ぶか。
     * Java へ新しいアイテムを足したときに効く道筋(json を書き直さなくても、妥当な場所に出る)。
     */
    @GameTest(template = "gametest_empty7")
    public static void unwrittenItemsKeepSectionOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemTable table = DesignData.items();
        for (Map.Entry<String, CreativeModeTab> e : Map.of(
                ItemTable.PLANTS, ModCreativeTabs.PLANTS.get(),
                ItemTable.HUMANITY, ModCreativeTabs.HUMANITY.get()).entrySet()) {
            List<Item> items = tabItems(level, e.getValue());
            Set<ResourceLocation> written = new HashSet<>(table.order(e.getKey()));
            Section last = Section.BLOCK;
            boolean seenUnwritten = false;
            for (Item item : items) {
                if (written.contains(BuiltInRegistries.ITEM.getKey(item))) {
                    if (seenUnwritten) {
                        helper.fail(e.getKey() + ": 書いてある " + item + " が、書いていないものの後ろに出ている");
                        return;
                    }
                    continue;                      // 書いてあるものの並びは上のテストが見る
                }
                seenUnwritten = true;
                Section s = CreativeTabOrder.sectionOf(item);
                if (s.ordinal() < last.ordinal()) {
                    helper.fail(e.getKey() + ": " + item + "(" + s + ")が " + last + " の後ろにある");
                    return;
                }
                last = s;
            }
        }
        helper.succeed();
    }

    /** 区分の決め方そのもの(ユーザー指定の 7 区分に、代表的なアイテムが入るか)。並び順は見ない。 */
    @GameTest(template = "gametest_empty7")
    public static void sectionsAsSpecified(GameTestHelper helper) {
        Map<ItemLike, Section> expected = new java.util.LinkedHashMap<>();
        expected.put(ModBlocks.GIANT_OVERGROWN_LOG.get(), Section.BLOCK);
        expected.put(BaseBlocks.ASPHALT.get(), Section.BLOCK);
        expected.put(ModItems.GUN_TURRET_ITEM.get(), Section.MACHINE);
        expected.put(ModBlocks.OVERGROWN_SAPLING.get(), Section.PLANT);
        expected.put(ModItems.SPORE.get(), Section.MATERIAL);
        expected.put(ModItems.CHLOROPHYLLITE_ALLOY.get(), Section.MATERIAL);
        expected.put(ModItems.FALCIMNES_SPAWN_EGG.get(), Section.SPAWN_EGG);
        expected.put(ModItems.PLANT_ARMOR.get(), Section.GEAR);
        expected.put(AllyItems.HK416.get(), Section.GEAR);
        expected.put(ModItems.SPORE_METER.get(), Section.OTHER);
        for (Map.Entry<ItemLike, Section> e : expected.entrySet()) {
            Section got = CreativeTabOrder.sectionOf(e.getKey().asItem());
            if (got != e.getValue()) {
                helper.fail(e.getKey().asItem() + " の区分が " + got + "(" + e.getValue() + " のはず)");
                return;
            }
        }
        helper.succeed();
    }

    private static List<Item> tabItems(ServerLevel level, CreativeModeTab tab) {
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess()));
        List<Item> items = new ArrayList<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            items.add(stack.getItem());
        }
        return items;
    }

}
