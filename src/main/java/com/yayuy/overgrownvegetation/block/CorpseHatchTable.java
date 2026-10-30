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

    private static final Map<Block, Supplier<EntityType<?>>> MAP = Map.ofEntries(
            Map.entry(ModBlocks.OVERGROWN_COW_CORPSE.get(), ModEntities.PARASITIZED_COW::get),
            Map.entry(ModBlocks.OVERGROWN_PIG_CORPSE.get(), ModEntities.PARASITIZED_PIG::get),
            Map.entry(ModBlocks.OVERGROWN_SHEEP_CORPSE.get(), ModEntities.PARASITIZED_SHEEP::get),
            Map.entry(ModBlocks.OVERGROWN_CHICKEN_CORPSE.get(), ModEntities.PARASITIZED_CHICKEN::get),
            Map.entry(ModBlocks.OVERGROWN_CREEPER_CORPSE.get(), ModEntities.PARASITIZED_CREEPER::get),
            Map.entry(ModBlocks.OVERGROWN_SPIDER_CORPSE.get(), ModEntities.PARASITIZED_SPIDER::get),
            Map.entry(ModBlocks.OVERGROWN_VILLAGER_CORPSE.get(), ModEntities.PARASITIZED_VILLAGER::get),
            Map.entry(ModBlocks.OVERGROWN_ZOMBIE_CORPSE.get(), ModEntities.PARASITIZED_ZOMBIE::get),
            Map.entry(ModBlocks.OVERGROWN_BABY_ZOMBIE_CORPSE.get(), ModEntities.PARASITIZED_BABY_ZOMBIE::get),
            Map.entry(ModBlocks.OVERGROWN_SKELETON_CORPSE.get(), ModEntities.PARASITIZED_SKELETON::get),
            Map.entry(ModBlocks.OVERGROWN_ENDERMAN_CORPSE.get(), ModEntities.PARASITIZED_ENDERMAN::get),
            // ネザーの媒介種(2026-10-09)
            Map.entry(ModBlocks.OVERGROWN_PIGLIN_CORPSE.get(), ModEntities.PARASITIZED_PIGLIN::get),
            Map.entry(ModBlocks.OVERGROWN_PIGLIN_BRUTE_CORPSE.get(), ModEntities.PARASITIZED_PIGLIN_BRUTE::get),
            Map.entry(ModBlocks.OVERGROWN_ZOMBIFIED_PIGLIN_CORPSE.get(), ModEntities.PARASITIZED_ZOMBIFIED_PIGLIN::get),
            Map.entry(ModBlocks.OVERGROWN_WITHER_SKELETON_CORPSE.get(), ModEntities.PARASITIZED_WITHER_SKELETON::get),
            Map.entry(ModBlocks.OVERGROWN_HOGLIN_CORPSE.get(), ModEntities.PARASITIZED_HOGLIN::get),
            Map.entry(ModBlocks.OVERGROWN_ZOGLIN_CORPSE.get(), ModEntities.PARASITIZED_ZOGLIN::get),
            Map.entry(ModBlocks.OVERGROWN_STRIDER_CORPSE.get(), ModEntities.PARASITIZED_STRIDER::get),
            // アイアンゴーレム・ウォーデン(2026-10-10)。寄生体はこの死体からだけ現れる(自然には湧かない)
            Map.entry(ModBlocks.OVERGROWN_IRON_GOLEM_CORPSE.get(), ModEntities.PARASITIZED_IRON_GOLEM::get),
            Map.entry(ModBlocks.OVERGROWN_WARDEN_CORPSE.get(), ModEntities.PARASITIZED_WARDEN::get)
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
