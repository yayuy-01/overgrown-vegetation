package com.yayuy.overgrownvegetation.ecosystem.life;

import com.google.common.collect.ImmutableSet;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;

/**
 * 環境生物の棲み処のブロック・中身・目印(POI)の登録(2026-11-18、行動の段)。共有の ModBlocks を太らせないよう、ここで別に登録する。
 */
public final class EcoBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OvergrownVegetation.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OvergrownVegetation.MODID);
    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, OvergrownVegetation.MODID);

    /** 倒木の洞(倒木と同じ硬さ・音)。 */
    public static final DeferredBlock<EcoDenBlock> DEN = BLOCKS.registerBlock("fallen_log_den", EcoDenBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_GREEN).strength(2.0F).sound(SoundType.WOOD));

    /** 梢の繭(葉の下面にぶら下がる。羽化)。 */
    public static final DeferredBlock<EcoCocoonBlock> COCOON = BLOCKS.registerBlock("canopy_cocoon", EcoCocoonBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.2F).sound(SoundType.WOOL).noOcclusion()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EcoDenBlockEntity>> DEN_BE =
            BLOCK_ENTITIES.register("fallen_log_den", () -> BlockEntityType.Builder.of(EcoDenBlockEntity::new, DEN.get()).build(null));

    /** 洞を近い順に引くための目印(ハチの巣と同じ仕組み。毎 tick 動かさずに探せる)。 */
    public static final DeferredHolder<PoiType, PoiType> DEN_POI = POI_TYPES.register("fallen_log_den",
            () -> new PoiType(ImmutableSet.copyOf(DEN.get().getStateDefinition().getPossibleStates()), 0, 1));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        POI_TYPES.register(modBus);
    }

    private EcoBlocks() {
    }
}
