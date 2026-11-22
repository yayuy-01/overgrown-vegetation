package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ley.LeyBatteryItem;
import com.yayuy.overgrownvegetation.ley.LeyFlowerBlock;
import com.yayuy.overgrownvegetation.ley.LeyFlowerFeature;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlock;
import com.yayuy.overgrownvegetation.ley.LeyPumpBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlock;
import com.yayuy.overgrownvegetation.ley.LeyPylonBlockEntity;
import com.yayuy.overgrownvegetation.ley.LeyWireItem;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.ComponentEnergyStorage;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * <b>地脈の最小限</b>(2026-11-13 サバイバルの背骨 段 2b)の登録: 地脈の花・汲み上げ器・柱・電池・線の巻き。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」。
 * 入れ物は共有の {@code DeferredRegister}({@link ModBlocks#BLOCKS} など)を使い、{@link #init()} を MOD の構築子から呼んで登録の前に読ませる
 * ({@link ModG1} と同じ作り。共有の登録ファイルを太らせない)。
 *
 * <p><b>エネルギーは FE</b>(決定 23、NeoForge の {@code IEnergyStorage})。汲み上げ器は取り出しだけの口、電池はアイテムの口
 * ({@link Capabilities.EnergyStorage#ITEM})。2c の換気の機械は {@link Capabilities.EnergyStorage#BLOCK} の受け取りの口を出せば、
 * 汲み上げ器の隣に置く・柱から線を張る・ほかの MOD の発電機、のどれからでも動く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModLey {

    private ModLey() {
    }

    /** MOD の構築子から呼ぶ(このクラスを読ませて、下の登録を共有の入れ物に積む)。 */
    public static void init() {
    }

    // ---- データコンポーネント ----

    /** 電池にたまっている FE(無ければ 0)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
            ModDataComponents.COMPONENTS.registerComponentType("ley_energy", b -> b
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 線の巻きで決めた 1 つ目の端(2 か所目のクリックで線を張る)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> WIRE_START =
            ModDataComponents.COMPONENTS.registerComponentType("ley_wire_start", b -> b
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));

    // ---- ブロック ----

    public static final DeferredBlock<LeyFlowerBlock> FLOWER = ModBlocks.BLOCKS.registerBlock(
            "ley_flower", LeyFlowerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .noCollission()
                    .instabreak()
                    .sound(SoundType.GRASS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    public static final DeferredBlock<LeyPumpBlock> PUMP = ModBlocks.BLOCKS.registerBlock(
            "ley_pump", LeyPumpBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.COPPER));

    public static final DeferredBlock<LeyPylonBlock> PYLON = ModBlocks.BLOCKS.registerBlock(
            "ley_pylon", LeyPylonBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()
                    .sound(SoundType.WOOD));

    // ---- アイテム ----

    public static final DeferredItem<BlockItem> FLOWER_ITEM = ModItems.ITEMS.registerSimpleBlockItem(FLOWER);
    public static final DeferredItem<BlockItem> PUMP_ITEM = ModItems.ITEMS.registerSimpleBlockItem(PUMP);
    /** 柱のアイテムは置いた柱と同じ GeckoLib の模型を描く(2026-11-22)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.ley.LeyPylonItem> PYLON_ITEM = ModItems.ITEMS.register(
            "ley_pylon", () -> new com.yayuy.overgrownvegetation.ley.LeyPylonItem(PYLON.get(), new Item.Properties()));

    public static final DeferredItem<LeyBatteryItem> BATTERY = ModItems.ITEMS.registerItem(
            "ley_battery", LeyBatteryItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<LeyWireItem> WIRE = ModItems.ITEMS.registerItem(
            "ley_wire", LeyWireItem::new, new Item.Properties().stacksTo(64));

    // ---- ブロックの中身 ----

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LeyPumpBlockEntity>> PUMP_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ley_pump", () -> BlockEntityType.Builder.of(
                    LeyPumpBlockEntity::new, PUMP.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LeyPylonBlockEntity>> PYLON_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ley_pylon", () -> BlockEntityType.Builder.of(
                    LeyPylonBlockEntity::new, PYLON.get()).build(null));

    // ---- 世界生成 ----

    /** 線の上に地脈の花を置く(決定 25 の「世界を作るとき」)。biome_modifier {@code ley_flowers} でオーバーワールドの全バイオームに足す。 */
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>, LeyFlowerFeature> FLOWER_FEATURE =
            ModFeatures.FEATURES.register("ley_flowers", () -> new LeyFlowerFeature(NoneFeatureConfiguration.CODEC));

    // ---- FE の口 ----

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, PUMP_BE.get(), (be, side) -> be.energyPort());
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, ctx) -> new ComponentEnergyStorage(
                stack, ENERGY.get(), LeyBatteryItem.capacity(), LeyBatteryItem.maxTransfer()), BATTERY.get());
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。満タンの電池も 1 つ(試す用)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(FLOWER_ITEM.get());
        output.accept(PUMP_ITEM.get());
        output.accept(PYLON_ITEM.get());
        output.accept(WIRE.get());
        output.accept(BATTERY.get());
        ItemStack full = new ItemStack(BATTERY.get());
        full.set(ENERGY.get(), LeyBatteryItem.capacity());
        output.accept(full);
    }

    /** 隣の向き(汲み上げ器が押し出す 6 方向)。 */
    public static final Direction[] DIRECTIONS = Direction.values();
}
