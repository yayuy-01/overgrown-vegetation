package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.pushback.AshLimeBandBlock;
import com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlock;
import com.yayuy.overgrownvegetation.corruption.pushback.LeyStakeBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

/**
 * <b>押し返す用の道具と方法</b>(2026-11-18 夜)の登録: 灰と石灰の帯・地脈の杭・石灰。正本は Vault「汚染を押し返す手段(浄化)」の末尾の節。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ({@link ModVent} と同じ作り)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModPushBack {

    private ModPushBack() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
        com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.init();
    }

    // ---- ブロック ----

    public static final DeferredBlock<AshLimeBandBlock> ASH_LIME_BAND = ModBlocks.BLOCKS.registerBlock(
            "ash_lime_band", AshLimeBandBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.QUARTZ)
                    .instabreak()
                    .noCollission()
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)
                    .sound(SoundType.SAND));

    public static final DeferredBlock<LeyStakeBlock> LEY_STAKE = ModBlocks.BLOCKS.registerBlock(
            "ley_stake", LeyStakeBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.5F, 6.0F)
                    .noOcclusion()
                    .lightLevel(s -> s.getValue(LeyStakeBlock.POWERED) ? 4 : 0)
                    .sound(SoundType.COPPER));

    /** 燻る灰ブロック(id は 10-08 からの burnt_soil。筒の火で焼けた汚染の土)。 */
    public static final DeferredBlock<com.yayuy.overgrownvegetation.corruption.pushback.SmolderingAshBlock> SMOLDERING_ASH = ModBlocks.BLOCKS.registerBlock(
            "burnt_soil", com.yayuy.overgrownvegetation.corruption.pushback.SmolderingAshBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(0.5F)
                    .randomTicks()
                    .lightLevel(s -> 3)
                    .sound(SoundType.SAND));

    /** 枯れた土(枯らし薬を浴びた汚染の地面。1 日【仮】で見直す)。 */
    public static final DeferredBlock<com.yayuy.overgrownvegetation.corruption.pushback.WitheredSoilBlock> WITHERED_SOIL = ModBlocks.BLOCKS.registerBlock(
            "withered_soil", com.yayuy.overgrownvegetation.corruption.pushback.WitheredSoilBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DIRT)
                    .strength(0.5F)
                    .sound(SoundType.ROOTED_DIRT));

    // ---- アイテム ----

    public static final DeferredItem<BlockItem> ASH_LIME_BAND_ITEM = ModItems.ITEMS.registerSimpleBlockItem(ASH_LIME_BAND);
    public static final DeferredItem<BlockItem> LEY_STAKE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(LEY_STAKE);
    public static final DeferredItem<BlockItem> SMOLDERING_ASH_ITEM = ModItems.ITEMS.registerSimpleBlockItem(SMOLDERING_ASH);
    public static final DeferredItem<BlockItem> WITHERED_SOIL_ITEM = ModItems.ITEMS.registerSimpleBlockItem(WITHERED_SOIL);
    /** 枯らし薬【仮の名前】(段 2。研究の節 t2_wither で開く)。 */
    public static final DeferredItem<com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtItem> WITHER_DRAUGHT = ModItems.ITEMS.registerItem(
            "wither_draught", com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtItem::new, new Item.Properties().stacksTo(16));
    /** 石灰(かまどで骨粉・方解石を焼く)。帯と枯らし薬の材料。 */
    public static final DeferredItem<Item> LIME = ModItems.ITEMS.registerSimpleItem("lime");

    // ---- 投げた瓶 ----

    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtEntity>> WITHER_DRAUGHT_ENTITY =
            ModEntities.ENTITY_TYPES.register("wither_draught", () -> net.minecraft.world.entity.EntityType.Builder
                    .<com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtEntity>of(com.yayuy.overgrownvegetation.corruption.pushback.WitherDraughtEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10)
                    .build(OvergrownVegetation.id("wither_draught").toString()));

    // ---- ブロックの中身 ----

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LeyStakeBlockEntity>> LEY_STAKE_BE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ley_stake", () -> BlockEntityType.Builder.of(
                    LeyStakeBlockEntity::new, LEY_STAKE.get()).build(null));

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, LEY_STAKE_BE.get(), (be, side) -> be.energyPort());
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(LIME.get());
        output.accept(ASH_LIME_BAND_ITEM.get());
        output.accept(LEY_STAKE_ITEM.get());
        output.accept(SMOLDERING_ASH_ITEM.get());
        output.accept(WITHER_DRAUGHT.get());
        output.accept(WITHERED_SOIL_ITEM.get());
    }
}
