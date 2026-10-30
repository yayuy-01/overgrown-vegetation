package com.yayuy.overgrownvegetation.base.gen;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 世界生成の同盟軍の基地(順 2 の着手順 2、2026-10-04)の構造物の型と部品の型の登録。
 * 構造物そのもの・間隔・生まれるバイオームはデータ({@code data/overgrown_vegetation/worldgen/structure/allied_base.json}・
 * {@code worldgen/structure_set/allied_bases.json}・{@code tags/worldgen/biome/has_structure/allied_base.json})。
 */
public final class BaseStructures {

    public static final DeferredRegister<StructureType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, OvergrownVegetation.MODID);
    public static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, OvergrownVegetation.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<AlliedBaseStructure>> ALLIED_BASE =
            TYPES.register("allied_base", () -> (StructureType<AlliedBaseStructure>) () -> AlliedBaseStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> ALLIED_BASE_PIECE =
            PIECES.register("allied_base", () -> (StructurePieceType) (context, tag) -> new AlliedBasePiece(tag));

    /** データの構造物の名前。 */
    public static final ResourceKey<Structure> ALLIED_BASE_STRUCTURE =
            ResourceKey.create(Registries.STRUCTURE, OvergrownVegetation.id("allied_base"));
    /** データの構造物の組(間隔と離れ)の名前。 */
    public static final ResourceKey<StructureSet> ALLIED_BASE_SET =
            ResourceKey.create(Registries.STRUCTURE_SET, OvergrownVegetation.id("allied_bases"));

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        PIECES.register(modBus);
    }

    private BaseStructures() {
    }
}
