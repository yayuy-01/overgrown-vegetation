package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
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

    /** 植物(植物のブロックの種類で拾えないもの)。 */
    private static final List<Supplier<? extends ItemLike>> PLANTS = List.of(
            ModBlocks.OVERGROWN_GROWTH, ModBlocks.OVERGROWN_SPORE_BLOOM, ModBlocks.OVERGROWN_ZENMAI, ModBlocks.UNDEVELOPED_BUSH,
            ModBlocks.OVERGROWN_BUSH, ModBlocks.OVERGROWN_SAPLING, ModItems.GIANT_OVERGROWN_SAPLING_ITEM,
            ModBlocks.WALL_FERN, ModBlocks.WALL_FERN_LARGE, ModBlocks.WALL_LYCOPSID, ModBlocks.HANGING_MOSS,
            ModBlocks.WALL_SPORE_SACS, ModBlocks.WALL_BRACKET_FUNGUS,
            ModBlocks.AERIAL_ROOT, ModBlocks.AERIAL_ROOT_PILLAR, ModBlocks.BEARD_LICHEN, ModBlocks.HANGING_SPORE_SAC,
            ModBlocks.OVERGROWN_WEEPING_TENDRIL, ModBlocks.OVERGROWN_DRIP_MOSS, ModBlocks.OVERGROWN_SPORE_TENDRIL,
            ModBlocks.OVERGROWN_TALL_FERN, ModBlocks.OVERGROWN_SPORE_REED, ModBlocks.OVERGROWN_CURLED_FERN, ModBlocks.OVERGROWN_MOSS_LAYER,
            ModBlocks.SELAGINELLA, ModBlocks.CLUBMOSS, ModBlocks.LYCOPSID_CARPET, ModBlocks.LYCOPSID_UPRIGHT,
            ModBlocks.PEAT, ModBlocks.PEAT_POOL_BOTTOM, ModBlocks.PEAT_SPROUTS, ModBlocks.PEAT_LITTER,
            ModBlocks.FALLEN_LOG, ModBlocks.BRACKET_FUNGUS, ModBlocks.MEDULLOSA_TRUNK, ModBlocks.MEDULLOSA_CROWN, ModBlocks.HORSETAIL,
            ModBlocks.CHARA, ModBlocks.WATER_ROOTS, ModBlocks.ALGAE_FILM, ModBlocks.SPORE_FILM, ModBlocks.SEA_SPORE_BAND,
            ModBlocks.SPORE_KELP_STALK, ModBlocks.SPORE_KELP_FROND,
            ModBlocks.CEILING_ROOT_NET, ModBlocks.LEY_ROOT, ModBlocks.FOXFIRE, ModBlocks.PALE_CAVE_FERN, ModBlocks.PALE_CAVE_CAPS,
            ModBlocks.PALE_CAVE_MOSS, ModBlocks.CAVE_HORSETAIL, ModBlocks.CAVE_MYCELIUM,
            ModBlocks.DANGLING_ROOTS, ModBlocks.PROTOTAXITES_STEM, ModBlocks.PROTOTAXITES_KNOB, ModBlocks.PROTOTAXITES_CAP,
            ModBlocks.OVERGROWN_LIANA);

    /** 置けるがドロップ・素材として扱うもの(実った大きな未熟果)。 */
    private static final List<Supplier<? extends ItemLike>> MATERIALS = List.of(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM);

    private CreativeTabOrder() {
    }

    /** このイベントが作っているタブの鍵({@code items.json} の {@code tabs} のキー)。3 つ以外なら null。 */
    @javax.annotation.Nullable
    private static String tabKeyOf(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.PLANTS.getKey()) {
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBuildTab(BuildCreativeModeTabContentsEvent event) {
        String tab = tabKeyOf(event);
        if (tab == null) {
            return;
        }

        List<ItemStack> entries = new ArrayList<>(event.getParentEntries());
        // 逆に、このタブへ回すと書いてあるのにまだ居ないアイテムは連れてくる。
        Set<Item> present = new HashSet<>();
        for (ItemStack stack : entries) {
            present.add(stack.getItem());
        }

        List<ItemStack> sorted = new ArrayList<>(entries);
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
        private final Set<Item> plants = items(PLANTS);
        private final Set<Item> materials = items(MATERIALS);

        Section section(Item item) {
            if (item instanceof SpawnEggItem) {
                return Section.SPAWN_EGG;
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
