package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 種族専用の死体ブロック → 侵食完了時に孵化させる「過繁茂した(MOB名)」の対応表。
 * {@link CorruptedCorpseBlockEntity} 専用。新しい種族の死体ブロックを追加するときは、
 * ブロック本体の登録に加えてここにも1行追加する。
 */
public final class CorpseHatchTable {

    private static final Map<Block, Supplier<EntityType<?>>> MAP = Map.of(
            ModBlocks.OVERGROWN_COW_CORPSE.get(), ModEntities.PARASITIZED_COW::get,
            ModBlocks.OVERGROWN_PIG_CORPSE.get(), ModEntities.PARASITIZED_PIG::get,
            ModBlocks.OVERGROWN_SHEEP_CORPSE.get(), ModEntities.PARASITIZED_SHEEP::get,
            ModBlocks.OVERGROWN_CREEPER_CORPSE.get(), ModEntities.PARASITIZED_CREEPER::get,
            ModBlocks.OVERGROWN_SPIDER_CORPSE.get(), ModEntities.PARASITIZED_SPIDER::get
    );

    /** {@code corpseBlock} が孵化させる「過繁茂した(MOB名)」の {@link EntityType}。対応が無ければ {@code null}。 */
    @Nullable
    public static EntityType<?> hatchTypeFor(Block corpseBlock) {
        Supplier<EntityType<?>> supplier = MAP.get(corpseBlock);
        return supplier == null ? null : supplier.get();
    }

    private CorpseHatchTable() {
    }
}
