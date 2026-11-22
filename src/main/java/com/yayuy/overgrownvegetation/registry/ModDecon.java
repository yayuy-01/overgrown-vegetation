package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.decon.DeconRecipe;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlock;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
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
 * <b>除染</b>(2026-11-14 サバイバルの背骨 段 3a)の登録: 除染の変換表のレシピの種類・地脈の洗い場。仕様の正本は Vault「サバイバルの背骨(籠城と遠征)」。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ({@link ModVent} と同じ作り)。
 *
 * <p>洗い場の口: FE は受け取りだけ(換気機と同じ。汲み上げ器の隣・柱からの線・ほかの MOD の発電機)。物の口は、入れる側 = 洗う前の 9 枠、
 * 取り出す側 = 洗い上がりの 9 枠(ホッパーでつなげる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModDecon {

    private ModDecon() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
    }

    // ---- 変換表のレシピ ----

    public static final DeferredHolder<RecipeType<?>, RecipeType<DeconRecipe>> RECIPE_TYPE =
            ModRecipes.TYPES.register("decontamination", () -> RecipeType.simple(OvergrownVegetation.id("decontamination")));

    public static final DeferredHolder<RecipeSerializer<?>, DeconRecipe.Serializer> RECIPE_SERIALIZER =
            ModRecipes.SERIALIZERS.register("decontamination", DeconRecipe.Serializer::new);

    // ---- 装備の汚れ(段 3b) ----

    /**
     * 装備の汚れ(0〜{@code dirt.full})。着ている物・手の耐久のある物に付く。数字では見せず、絵(アイコンと体に着た姿)でだけ見せる(決定 33)。
     * クライアントへも送る(絵のため)。
     */
    public static final DeferredHolder<com.yayuy.overgrownvegetation.port.component.DataComponentType<?>, com.yayuy.overgrownvegetation.port.component.DataComponentType<Integer>> GEAR_DIRT =
            ModDataComponents.COMPONENTS.registerComponentType("gear_dirt", b -> b
                    .persistent(com.mojang.serialization.Codec.INT)
                    .networkSynchronized(com.yayuy.overgrownvegetation.port.network.ByteBufCodecs.VAR_INT));

    // ---- 地脈の洗い場 ----

    public static final DeferredBlock<WasherBlock> WASHER = ModBlocks.BLOCKS.registerBlock(
            "ley_washer", WasherBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion() // 2026-11-22 から 1 ブロックいっぱいの箱ではない(槽・すのこ)
                    .sound(SoundType.COPPER));

    /** 洗い場のアイテムは置いた洗い場と同じ GeckoLib の模型を描く(2026-11-22)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.survival.decon.WasherBlockItem> WASHER_ITEM = ModItems.ITEMS.register(
            "ley_washer", () -> new com.yayuy.overgrownvegetation.survival.decon.WasherBlockItem(WASHER.get(), new net.minecraft.world.item.Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WasherBlockEntity>> WASHER_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ley_washer", () -> BlockEntityType.Builder.of(
                    WasherBlockEntity::new, WASHER.get()).build(null));

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, WASHER_BE.get(), (be, side) -> be.energyPort());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, WASHER_BE.get(), (be, side) -> be.itemPort());
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(WASHER_ITEM.get());
    }
}
