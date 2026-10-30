package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownBarrelBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownChestBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OvergrownVegetation.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CorruptedLianaBlockEntity>> OVERGROWN_LIANA =
            BLOCK_ENTITY_TYPES.register("overgrown_liana", () -> BlockEntityType.Builder.of(
                    CorruptedLianaBlockEntity::new,
                    ModBlocks.OVERGROWN_LIANA.get()).build(null));

    /** 根付いた萌芽体(2026-10-23 萌芽体のモデル)。歩く萌芽体と同じ模型を GeckoLib で描くための中身(向きと根付いた時刻だけ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity>> BLIGHT_FERN_ROOTED =
            BLOCK_ENTITY_TYPES.register("blight_fern_rooted", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity::new,
                    ModBlocks.BLIGHT_FERN_ROOTED.get()).build(null));

    /**
     * <b>覆われて動かなくなったブロック</b>の中身(2026-09-22)。化けている元のブロック状態と、
     * 覆われる前にそこにあったアイテムを覚えているだけで、<b>何も動かさない</b>。
     * ticker を付けないこと —— 付けた瞬間に「覆われているのに動くブロック」に逆戻りする。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity>> OVERGROWN_HUSK =
            BLOCK_ENTITY_TYPES.register("overgrown_husk", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity::new,
                    ModBlocks.OVERGROWN_HUSK.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.OvergrownChestBlock} の中身(バニラのチェストと同じ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OvergrownChestBlockEntity>> OVERGROWN_CHEST =
            BLOCK_ENTITY_TYPES.register("overgrown_chest", () -> BlockEntityType.Builder.of(
                    OvergrownChestBlockEntity::new,
                    ModBlocks.OVERGROWN_CHEST.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.OvergrownBarrelBlock} の中身(バニラの樽と同じ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OvergrownBarrelBlockEntity>> OVERGROWN_BARREL =
            BLOCK_ENTITY_TYPES.register("overgrown_barrel", () -> BlockEntityType.Builder.of(
                    OvergrownBarrelBlockEntity::new,
                    ModBlocks.OVERGROWN_BARREL.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITY_TYPES.register(modBus);
    }

    private ModBlockEntities() {
    }
}
