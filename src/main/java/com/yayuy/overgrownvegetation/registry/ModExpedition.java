package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.expedition.CureDraughtItem;
import com.yayuy.overgrownvegetation.survival.expedition.SleepingBagBlock;
import com.yayuy.overgrownvegetation.survival.expedition.SporeEaterMushroomBlock;
import com.yayuy.overgrownvegetation.survival.vent.AirFilterItem;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import com.yayuy.overgrownvegetation.port.network.ByteBufCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

/**
 * <b>遠征の見返りと手当て</b>(2026-11-16 サバイバルの背骨 段 4)の登録: 焼いた胞子・胞子喰い茸・硬い樹皮片・菌糸のフィルター・寝袋。
 * 仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」の決定 38〜41。入れ物は共有の {@code DeferredRegister} を使い、
 * {@link #init()} を MOD の構築子から呼ぶ({@link ModVent}・{@link ModDecon} と同じ作り)。
 */
public final class ModExpedition {

    private ModExpedition() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
    }

    // ---- データコンポーネント ----

    /**
     * フィルターの格(0 = ふつう・1 = 菌糸のフィルター)。マスク・換気機の中のフィルターのように、フィルターのアイテムでない物が持つ。
     * フィルターのアイテムそのものは、アイテムの種類で格が決まる({@code AirFilters#grade})。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> FILTER_GRADE =
            ModDataComponents.COMPONENTS.registerComponentType("filter_grade", b -> b
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    // ---- ブロック ----

    /** 胞子喰い茸(過繁茂の森の暗い地面にだけ生える。決定 39 の採取の道)。 */
    public static final DeferredBlock<SporeEaterMushroomBlock> SPORE_EATER_MUSHROOM = ModBlocks.BLOCKS.registerBlock(
            "spore_eater_mushroom", SporeEaterMushroomBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_WHITE)
                    .noCollission()
                    .instabreak()
                    .sound(SoundType.FUNGUS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    /** 寝袋(決定 40)。 */
    public static final DeferredBlock<SleepingBagBlock> SLEEPING_BAG = ModBlocks.BLOCKS.registerBlock(
            "sleeping_bag", SleepingBagBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BROWN)
                    .strength(0.2F)
                    .noOcclusion()
                    .sound(SoundType.WOOL)
                    .pushReaction(PushReaction.DESTROY));

    // ---- アイテム ----

    /** 焼いた胞子(胞子をかまど・燻製器で焼く。薬の材料)。 */
    public static final DeferredItem<Item> ROASTED_SPORES = ModItems.ITEMS.registerSimpleItem("roasted_spores", new Item.Properties());

    public static final DeferredItem<BlockItem> SPORE_EATER_MUSHROOM_ITEM = ModItems.ITEMS.registerSimpleBlockItem(SPORE_EATER_MUSHROOM);

    /** 硬い樹皮片(G3 のドロップ。上位のフィルターの枠・後の版の道具)。 */
    public static final DeferredItem<Item> HARD_BARK_SHARD = ModItems.ITEMS.registerSimpleItem("hard_bark_shard", new Item.Properties());

    /** 菌糸のフィルター(上位のフィルター。容量 3 倍【仮】)。 */
    public static final DeferredItem<AirFilterItem> MYCELIAL_FILTER = ModItems.ITEMS.registerItem(
            "mycelial_filter", AirFilterItem::new, new Item.Properties().stacksTo(16));

    /** 休眠嚢(G2 のドロップ。G2 が胞子を眠らせて運ぶ袋。駆胞薬で茸の代わりに使える)。 */
    public static final DeferredItem<Item> DORMANCY_SAC = ModItems.ITEMS.registerSimpleItem("dormancy_sac", new Item.Properties());

    /** 駆胞薬(1 本で感染が 1 段下がる)。 */
    public static final DeferredItem<CureDraughtItem> CURE_DRAUGHT = ModItems.ITEMS.registerItem(
            "cure_draught", CureDraughtItem::new, new Item.Properties().stacksTo(16));

    public static final DeferredItem<BlockItem> SLEEPING_BAG_ITEM = ModItems.ITEMS.registerItem(
            "sleeping_bag", p -> new BlockItem(SLEEPING_BAG.get(), p), new Item.Properties().stacksTo(1));

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(ROASTED_SPORES.get());
        output.accept(SPORE_EATER_MUSHROOM_ITEM.get());
        output.accept(HARD_BARK_SHARD.get());
        output.accept(DORMANCY_SAC.get());
        output.accept(CURE_DRAUGHT.get());
        output.accept(MYCELIAL_FILTER.get());
        output.accept(SLEEPING_BAG_ITEM.get());
    }
}
