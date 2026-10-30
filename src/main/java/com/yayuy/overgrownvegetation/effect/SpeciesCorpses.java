package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
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
 * (対応表に無い種族は {@link #corpseStateFor} が {@code null} を返す)。ゾンビ・スケルトンも同じく
 * ハスク・ドラウンド・ゾンビ村人・ストレイ・ウィザースケルトンなどは外れる。
 *
 * <p>対応表に無い種族が死んだ場合、死体ブロックの代わりに汚染シダ
 * ({@link com.yayuy.overgrownvegetation.entity.BlightFernEntity})がその場に生える
 * ({@link SporeInfectionDeathHandler} 参照)。
 */
public final class SpeciesCorpses {

    // 2026-10-09 の G1 の作り直しで、ゾンビ(今までは即変身)・スケルトン(今までは骨粉)も死体 → 起き上がりにそろえた(ユーザー指定)。
    private static final Map<Class<? extends LivingEntity>, Supplier<Block>> MAP = Map.ofEntries(
            Map.entry(Cow.class, ModBlocks.OVERGROWN_COW_CORPSE::get),
            Map.entry(Pig.class, ModBlocks.OVERGROWN_PIG_CORPSE::get),
            Map.entry(Sheep.class, ModBlocks.OVERGROWN_SHEEP_CORPSE::get),
            Map.entry(Chicken.class, ModBlocks.OVERGROWN_CHICKEN_CORPSE::get),
            Map.entry(Creeper.class, ModBlocks.OVERGROWN_CREEPER_CORPSE::get),
            Map.entry(Spider.class, ModBlocks.OVERGROWN_SPIDER_CORPSE::get),
            Map.entry(Villager.class, ModBlocks.OVERGROWN_VILLAGER_CORPSE::get),
            Map.entry(Zombie.class, ModBlocks.OVERGROWN_ZOMBIE_CORPSE::get),
            Map.entry(Skeleton.class, ModBlocks.OVERGROWN_SKELETON_CORPSE::get),
            Map.entry(EnderMan.class, ModBlocks.OVERGROWN_ENDERMAN_CORPSE::get),
            // ネザーの媒介種(2026-10-09)。ガスト・ブレイズは死体を作らない(corpse/NetherReanimation)。
            Map.entry(net.minecraft.world.entity.monster.piglin.Piglin.class, ModBlocks.OVERGROWN_PIGLIN_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.piglin.PiglinBrute.class, ModBlocks.OVERGROWN_PIGLIN_BRUTE_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.ZombifiedPiglin.class, ModBlocks.OVERGROWN_ZOMBIFIED_PIGLIN_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.WitherSkeleton.class, ModBlocks.OVERGROWN_WITHER_SKELETON_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.hoglin.Hoglin.class, ModBlocks.OVERGROWN_HOGLIN_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.Zoglin.class, ModBlocks.OVERGROWN_ZOGLIN_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.Strider.class, ModBlocks.OVERGROWN_STRIDER_CORPSE::get),
            // 2026-10-10: アイアンゴーレム(プレイヤーが作ったものも)・ウォーデン
            Map.entry(net.minecraft.world.entity.animal.IronGolem.class, ModBlocks.OVERGROWN_IRON_GOLEM_CORPSE::get),
            Map.entry(net.minecraft.world.entity.monster.warden.Warden.class, ModBlocks.OVERGROWN_WARDEN_CORPSE::get)
    );

    /** {@code entity} の種族に対応する死体ブロックの状態。対応が無ければ {@code null}。 */
    @Nullable
    public static BlockState corpseStateFor(LivingEntity entity) {
        // 子どもの村人・動物は死体を残さず汚染シダ。チビゾンビだけ専用の小さい死体(2026-10-09 ユーザー指定)。
        if (entity.isBaby()) {
            return entity.getClass() == Zombie.class ? ModBlocks.OVERGROWN_BABY_ZOMBIE_CORPSE.get().defaultBlockState() : null;
        }
        Supplier<Block> supplier = MAP.get(entity.getClass());
        return supplier == null ? null : supplier.get().defaultBlockState();
    }

    private SpeciesCorpses() {
    }
}
