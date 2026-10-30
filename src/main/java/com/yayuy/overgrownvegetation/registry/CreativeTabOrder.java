package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ItemTable;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.purify.PurifyRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 植物・人類のタブの並び(2026-10-10、ユーザー指定)。<b>ブロック → 機械 → 植物 → ドロップアイテムと中間素材 → スポーンエッグ → 武器防具 → その他</b>。
 *
 * <p>タブを作り終えた最後({@link EventPriority#LOWEST}。各クラスが {@link BuildCreativeModeTabContentsEvent} で足した分・
 * 実験的要素で外した後)に、並んでいる全部を区分で並べ直す。<b>区分の中は元の順のまま</b>(安定な並べ替え)なので、
 * {@link ModCreativeTabs} の並びや各クラスの足し方は今までどおりでよく、新しく足したアイテムも自動でその区分に入る。
 * 覆われたブロックのタブはブロックだけなので触らない。
 *
 * <p>区分の決め方(Claude 仮決め。違えば下の一覧を直す):
 * <ul>
 *     <li>スポーンエッグ: {@link SpawnEggItem}。</li>
 *     <li>武器防具: 防具・道具・剣・弓・盾({@link ArmorItem}・{@link TieredItem}・{@link ProjectileWeaponItem}・{@link ShieldItem})と
 *     {@link #GEAR}(銃・火炎放射器・寄生植物装備・防護マスク・パワードアーマーの設計図とカートリッジ・因子・装飾品・拡張パーツ)。道具(斧・つるはし)もここ。</li>
 *     <li>その他: {@link #OTHER}(注射器・計器・群れスキャナー・胞子フィルター・軍票・機関砲弾・砲弾)。</li>
 *     <li>機械: {@link #MACHINES}(作業台・装置・防衛設備・灯り・弾薬箱・管理端末・ターゲットブロック)。</li>
 *     <li>植物: {@link #PLANTS} と、草・苗木・蔦など植物のブロック({@link BushBlock}・{@link VineBlock}・{@link MultifaceBlock}・{@link GrowingPlantBlock})。</li>
 *     <li>ブロック: それ以外の置けるもの(地面・木・葉・巨大樹・鉱石・結晶ブロック・死体・基地の建材)。</li>
 *     <li>ドロップアイテムと中間素材: それ以外の置けないもの(植物・Mob のドロップ、結晶、合金、繊維など)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeTabOrder {

    /** 並べる順(ユーザー指定)。 */
    public enum Section {
        BLOCK, MACHINE, PLANT, MATERIAL, SPAWN_EGG, GEAR, OTHER
    }

    /**
     * 機械(置けるもの)。<b>足したら壊し方もそろえる</b>: {@code #minecraft:mineable/pickaxe}・{@code #minecraft:needs_stone_tool}・
     * {@code requiresCorrectToolForDrops}・自分を落とすルートテーブル(2026-10-10 ユーザー指定「適正ツールはつるはし、石以上で回収」。
     * {@code gametest/MachineDropGameTests} が見張る。基地の管理端末だけは落ちないまま)。
     */
    private static final List<Supplier<? extends ItemLike>> MACHINES = List.of(
            ModItems.RESEARCH_TABLE_ITEM, ModItems.POWERED_ARMOR_TABLE_ITEM, ModItems.FACTOR_EXTRACTOR_ITEM,
            ModItems.SPORE_PURIFIER_ITEM, ModItems.SPORE_DIFFUSER_ITEM, ModItems.DECONTAMINATOR_ITEM,
            ModItems.REPELLENT_LAMP_ITEM, ModItems.BARBED_WIRE_ITEM, ModItems.PHASE_RECORDER_ITEM, ModItems.SPORE_ALARM_ITEM,
            ModItems.TARGET_BLOCK, ModItems.GUN_TURRET_ITEM, ModItems.SIEGE_CANNON_ITEM, ModItems.BEAM_SHIELD_ITEM,
            BaseBlocks.AMMO_CRATE, BaseBlocks.FLOODLIGHT, BaseBlocks.JAMMING_LAMP, BaseBlocks.BASE_TERMINAL);

    /** 植物(植物のブロックの種類で拾えないもの)。 */
    private static final List<Supplier<? extends ItemLike>> PLANTS = List.of(
            ModBlocks.OVERGROWN_GROWTH, ModBlocks.OVERGROWN_SPORE_BLOOM, ModBlocks.OVERGROWN_ZENMAI, ModBlocks.UNDEVELOPED_BUSH,
            ModBlocks.OVERGROWN_BUSH, ModBlocks.OVERGROWN_SAPLING, ModItems.GIANT_OVERGROWN_SAPLING_ITEM,
            ModBlocks.OVERGROWN_CREEPING_VINE, ModBlocks.OVERGROWN_SHELF_LICHEN, ModBlocks.OVERGROWN_THORN_VINE,
            ModBlocks.OVERGROWN_WEEPING_TENDRIL, ModBlocks.OVERGROWN_DRIP_MOSS, ModBlocks.OVERGROWN_SPORE_TENDRIL,
            ModBlocks.OVERGROWN_TALL_FERN, ModBlocks.OVERGROWN_SPORE_REED, ModBlocks.OVERGROWN_CURLED_FERN, ModBlocks.OVERGROWN_MOSS_LAYER,
            ModBlocks.CAVE_SPORE_SAC, ModBlocks.CAVE_MOSS_CLUMP, ModBlocks.OVERGROWN_WATER_GRASS, ModBlocks.OVERGROWN_TALL_WATER_GRASS,
            ModBlocks.OVERGROWN_WATER_COLUMN, ModBlocks.OVERGROWN_WATER_COLUMN_FLOWER, ModBlocks.OVERGROWN_WATER_MOSS_LAYER,
            ModBlocks.OVERGROWN_WATER_CEILING_GROWTH, ModBlocks.OVERGROWN_LILY, ModBlocks.OVERGROWN_LIANA);

    /** 置けるがドロップ・素材として扱うもの(実った大きな未熟果)。 */
    private static final List<Supplier<? extends ItemLike>> MATERIALS = List.of(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM);

    private static final List<Supplier<? extends ItemLike>> GEAR = List.of(
            AllyItems.HK416, PurifyRegistry.FLAMETHROWER, ModItems.PLANT_ARMOR, ModItems.PROTECTIVE_MASK,
            ModItems.POWERED_ARMOR_BLUEPRINT, ModItems.CARTRIDGE,
            ModItems.FACTOR_IMPACT, ModItems.FACTOR_PIERCE, ModItems.FACTOR_BLAST, ModItems.FACTOR_TOXIN, ModItems.FACTOR_SPORE,
            ModItems.NIGHT_VISION_ACCESSORY, ModItems.ENEMY_SENSE_ACCESSORY, ModItems.SWIFT_ACCESSORY, ModItems.CUSHION_ACCESSORY,
            ModItems.MINING_EXTENSION, ModItems.ANCHOR_EXTENSION);

    private static final List<Supplier<? extends ItemLike>> OTHER = List.of(
            ModItems.SPORE_INFECTION_SYRINGE_1, ModItems.SPORE_INFECTION_SYRINGE_2, ModItems.SPORE_INFECTION_SYRINGE_3,
            ModItems.SPORE_METER, ModItems.PHASE_METER, ModItems.GUIDEBOOK, ModItems.PACK_SCANNER, ModItems.SPORE_FILTER, ModItems.SPORE_MEMBRANE,
            AllyItems.MILITARY_SCRIP, ModItems.AUTOCANNON_ROUND, ModItems.SIEGE_SHELL);

    private CreativeTabOrder() {
    }

    /** このイベントが作っているタブの鍵({@code items.json} の {@code tabs} のキー)。3 つ以外なら null。 */
    @javax.annotation.Nullable
    private static String tabKeyOf(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.COVERED.getKey()) {
            return ItemTable.COVERED;
        }
        if (event.getTabKey() == ModCreativeTabs.PLANTS.getKey()) {
            return ItemTable.PLANTS;
        }
        if (event.getTabKey() == ModCreativeTabs.HUMANITY.getKey()) {
            return ItemTable.HUMANITY;
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBuildTab(BuildCreativeModeTabContentsEvent event) {
        String tab = tabKeyOf(event);
        if (tab == null) {
            return;
        }
        ItemTable table = DesignData.items();
        // 覆われたブロックのタブはブロックだけなので区分で並べ直さない。items.json に並びを書いたときだけ触る。
        boolean sortBySection = !ItemTable.COVERED.equals(tab);
        if (!sortBySection && !table.hasOrder()) {
            return;
        }

        List<ItemStack> entries = new ArrayList<>(event.getParentEntries());
        // items.json が別のタブへ回したアイテムは、このタブからは外す。
        entries.removeIf(stack -> {
            String want = table.tabOf(idOf(stack.getItem()));
            return want != null && !want.equals(tab);
        });
        // 逆に、このタブへ回すと書いてあるのにまだ居ないアイテムは連れてくる。
        Set<Item> present = new HashSet<>();
        for (ItemStack stack : entries) {
            present.add(stack.getItem());
        }
        // 実験的要素がオフのときは、外されたアイテムを連れ戻さない。この処理と
        // ExperimentalFeatures.Tabs はどちらも LOWEST で、どちらが先に走るかは決まっていないため
        // (先に外された物をここで足し直すと、オフなのにタブに出る。2026-10-15 に
        // offworldhidesalliedarmy が落ちて気づいた)。
        boolean experimental = ExperimentalFeatures.Tabs.shownInTabs();
        for (ResourceLocation id : table.order(tab)) {
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR || !present.add(item)) {
                continue;
            }
            if (!experimental && ExperimentalFeatures.isExperimentalItem(item)) {
                continue;
            }
            entries.add(new ItemStack(item));
        }

        List<ItemStack> sorted = new ArrayList<>(entries);
        Lookup lookup = sortBySection ? new Lookup() : null;
        // 並べ方: items.json に書いたものが書いた順で先、書いていないものは今までどおり区分の順で後ろ
        // (区分の中も、このタブへ積まれた元の順のまま = 安定な並べ替え)。
        // こうしておくと、Java へ新しいアイテムを足しても消えず、ユーザーが並べた所も動かない。
        sorted.sort(Comparator
                .comparingInt((ItemStack stack) -> table.indexIn(tab, idOf(stack.getItem())))
                .thenComparingInt(stack -> lookup == null ? 0 : lookup.section(stack.getItem()).ordinal()));
        if (sorted.equals(new ArrayList<>(event.getParentEntries()))) {
            return;
        }
        for (ItemStack stack : event.getParentEntries().toArray(ItemStack[]::new)) {
            event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        }
        for (ItemStack stack : sorted) {
            event.accept(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        }
    }

    /** 登録 id(未登録なら {@code minecraft:air})。 */
    private static ResourceLocation idOf(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** {@code item} の区分。 */
    public static Section sectionOf(Item item) {
        return new Lookup().section(item);
    }

    /** 一覧を Item の集合にしたもの(タブを作るたびに作る。登録が済んでからしか引けないため)。 */
    private static final class Lookup {
        private final Set<Item> machines = items(MACHINES);
        private final Set<Item> plants = items(PLANTS);
        private final Set<Item> materials = items(MATERIALS);
        private final Set<Item> gear = items(GEAR);
        private final Set<Item> other = items(OTHER);

        Section section(Item item) {
            if (item instanceof SpawnEggItem) {
                return Section.SPAWN_EGG;
            }
            if (gear.contains(item) || item instanceof ArmorItem || item instanceof TieredItem
                    || item instanceof ProjectileWeaponItem || item instanceof ShieldItem) {
                return Section.GEAR;
            }
            if (other.contains(item)) {
                return Section.OTHER;
            }
            if (machines.contains(item)) {
                return Section.MACHINE;
            }
            if (plants.contains(item)) {
                return Section.PLANT;
            }
            if (materials.contains(item)) {
                return Section.MATERIAL;
            }
            if (item instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                return block instanceof BushBlock || block instanceof VineBlock || block instanceof MultifaceBlock
                        || block instanceof GrowingPlantBlock ? Section.PLANT : Section.BLOCK;
            }
            return Section.MATERIAL;
        }

        private static Set<Item> items(List<Supplier<? extends ItemLike>> list) {
            Set<Item> set = new HashSet<>();
            for (Supplier<? extends ItemLike> s : list) {
                set.add(s.get().asItem());
            }
            return set;
        }
    }
}
