package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.vent.AirFilterItem;
import com.yayuy.overgrownvegetation.survival.vent.VentBlock;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockItem;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import com.yayuy.overgrownvegetation.port.network.ByteBufCodecs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.capabilities.Capabilities;
import com.yayuy.overgrownvegetation.port.capabilities.RegisterCapabilitiesEvent;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

/**
 * <b>換気の機械とフィルター</b>(2026-11-14 サバイバルの背骨 段 2c)の登録: 地脈の換気機・フィルター。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ({@link ModLey} と同じ作り)。
 *
 * <p>換気機の FE の口は受け取りだけ(2b の報告どおり)。汲み上げ器の隣・柱からの線・ほかの MOD の発電機のどれからでも動く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModVent {

    private ModVent() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
    }

    // ---- データコンポーネント ----

    /**
     * フィルターを洗った回数(段 3 の洗い場で増える。容量が 1 回ごとに {@code vent.filterWashWear} 減る)。フィルターにもマスクにも付く。
     * 詰まり(吸い取った量 %秒)はマスクと同じ {@link ModDataComponents#MASK_FILTER_USED}。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> FILTER_WASHES =
            ModDataComponents.COMPONENTS.registerComponentType("filter_washes", b -> b
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    // ---- ブロック ----

    public static final DeferredBlock<VentBlock> VENTILATOR = ModBlocks.BLOCKS.registerBlock(
            "ley_ventilator", VentBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK) // 3 ブロックの機械はピストンで動かさない
                    .sound(SoundType.COPPER));

    // ---- アイテム ----

    /** 3 ブロックの機械を置くアイテム(置けない訳を出す・見た目は機械の模型を縮めた物)。 */
    public static final DeferredItem<VentBlockItem> VENTILATOR_ITEM = ModItems.ITEMS.register(
            "ley_ventilator", () -> new VentBlockItem(VENTILATOR.get(), new Item.Properties()));

    public static final DeferredItem<AirFilterItem> AIR_FILTER = ModItems.ITEMS.registerItem(
            "air_filter", AirFilterItem::new, new Item.Properties().stacksTo(16));

    // ---- ブロックの中身 ----

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VentBlockEntity>> VENTILATOR_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ley_ventilator", () -> BlockEntityType.Builder.of(
                    VentBlockEntity::new, VENTILATOR.get()).build(null));

    // ---- FE の口 ----

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, VENTILATOR_BE.get(), (be, side) -> be.energyPort());
        // 3 ブロックの機械(11-22): 上 2 つの部位(地脈の端子の結晶は中の段にある)に当てた線・隣の汲み上げ器も、本体の口へつなぐ
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, be, side) ->
                state.getValue(VentBlock.PART) != VentBlock.Part.LOWER
                        && level.getBlockEntity(VentBlock.basePos(pos, state)) instanceof VentBlockEntity base ? base.energyPort() : null,
                VENTILATOR.get());
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(VENTILATOR_ITEM.get());
        output.accept(AIR_FILTER.get());
    }
}
