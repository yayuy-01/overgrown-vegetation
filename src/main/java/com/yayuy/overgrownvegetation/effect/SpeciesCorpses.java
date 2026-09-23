package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 「胞子感染」で力尽きた Mob の種族 → 専用の「過繁茂した(種族名)の死体」ブロックの対応表。
 *
 * <p>{@code Cow.class} などの厳密一致にしているのは、例えば {@code MushroomCow}(キノコウシ、
 * {@code Cow} のサブクラス)は見た目が違いすぎて牛の死体モデルを流用できないため
 * (対応表に無い種族は {@link #corpseStateFor} が {@code null} を返す)。
 *
 * <p>対応表に無い種族(例: ニワトリ)が死んだ場合、死体ブロックの代わりに汚染シダ
 * ({@link com.yayuy.overgrownvegetation.entity.BlightFernEntity})がその場に生える
 * ({@link SporeInfectionDeathHandler} 参照)。
 */
public final class SpeciesCorpses {

    // ゾンビ(Zombie.class ちょうど)は死体ブロックを経由せず、胞子感染死した瞬間に
    // その場で「過繁茂したゾンビ」になる({@link SporeInfectionDeathHandler} で特別扱い)ため
    // この対応表には含めない(専用の死体ブロックも持たない)。
    private static final Map<Class<? extends LivingEntity>, Supplier<Block>> MAP = Map.of(
            Cow.class, ModBlocks.OVERGROWN_COW_CORPSE::get,
            Pig.class, ModBlocks.OVERGROWN_PIG_CORPSE::get,
            Sheep.class, ModBlocks.OVERGROWN_SHEEP_CORPSE::get,
            Creeper.class, ModBlocks.OVERGROWN_CREEPER_CORPSE::get,
            Spider.class, ModBlocks.OVERGROWN_SPIDER_CORPSE::get
    );

    /** {@code entity} の種族に対応する死体ブロックの状態。対応が無ければ {@code null}。 */
    @Nullable
    public static BlockState corpseStateFor(LivingEntity entity) {
        Supplier<Block> supplier = MAP.get(entity.getClass());
        return supplier == null ? null : supplier.get().defaultBlockState();
    }

    private SpeciesCorpses() {
    }
}
