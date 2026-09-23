package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptedBonemeal;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})にかかっている Mob が死んだとき、
 * 死因を問わず(寿命切れの自然死だけでなく、プレイヤーに倒された場合なども含めて)
 * 死んだ位置に死体を残す。
 * <ul>
 *     <li>プレーンなスケルトン({@code Skeleton.class} ちょうど)→ 死体も汚染シダも残さず、
 *     死亡地点に「骨粉を使ったのと同じ効果」({@link CorruptedBonemeal})を発生させる。</li>
 *     <li>プレーンなゾンビ({@code Zombie.class} ちょうど)→ 死体ブロックを経由せず、
 *     その場で即座に「過繁茂したゾンビ」({@link com.yayuy.overgrownvegetation.entity.ParasitizedZombie})になる。</li>
 *     <li>その他で種族に対応する死体ブロックがある({@link SpeciesCorpses#corpseStateFor}）→ それを設置する。</li>
 *     <li>対応が無い(例: ニワトリ)→ 代わりに汚染シダ({@link com.yayuy.overgrownvegetation.entity.BlightFernEntity})
 *     がその場から生える。</li>
 * </ul>
 *
 * <p>{@link SporeInfectionMobEffect} 側は寿命が切れたら {@code entity.kill()} を呼ぶだけで、
 * それも含めた「感染中の Mob の死亡」を全てここ({@code LivingDeathEvent})で一括して拾う。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionDeathHandler {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            // 死亡自体が取り消されたなら死体も置かない
            return;
        }

        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        if (!entity.hasEffect(ModMobEffects.SPORE_INFECTION)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos deathPos = entity.blockPosition();
        if (!serverLevel.isLoaded(deathPos)) {
            return;
        }

        // スケルトン: 死体も汚染シダも残さず、死亡地点に骨粉と同じ成長効果を出す。
        if (entity.getClass() == Skeleton.class) {
            CorruptedBonemeal.apply(serverLevel, deathPos.below());
            return;
        }

        // ゾンビ: 死体ブロックを経由せず、その場で即座に「過繁茂したゾンビ」になる。
        if (entity.getClass() == Zombie.class) {
            spawnParasitizedZombie(serverLevel, deathPos);
            return;
        }

        BlockState corpse = SpeciesCorpses.corpseStateFor(entity);
        if (corpse != null) {
            serverLevel.setBlock(deathPos, corpse, Block.UPDATE_ALL);
        } else {
            spawnBlightFern(serverLevel, deathPos);
        }
    }

    /** 対応する死体が無い種族が死んだ場所に、汚染シダ(水中なら汚染水草)をその場から生やす。 */
    private static void spawnBlightFern(ServerLevel level, BlockPos deathPos) {
        BlightFernPlacement.spawn(level, deathPos, MobSpawnType.MOB_SUMMONED);
    }

    /**
     * 胞子感染で死んだゾンビを、死体ブロックを経由せずその場で「過繁茂したゾンビ」に置き換える。
     * 死体ブロックの孵化と同じく、湧きルールを無視する {@code MOB_SUMMONED} で生成し、
     * 死亡地点そのものに足が来るようにする。
     */
    private static void spawnParasitizedZombie(ServerLevel level, BlockPos deathPos) {
        Entity zombie = ModEntities.PARASITIZED_ZOMBIE.get()
                .create(level, null, deathPos, MobSpawnType.MOB_SUMMONED, false, false);
        if (zombie != null) {
            level.addFreshEntity(zombie);
        }
    }

    private SporeInfectionDeathHandler() {
    }
}
