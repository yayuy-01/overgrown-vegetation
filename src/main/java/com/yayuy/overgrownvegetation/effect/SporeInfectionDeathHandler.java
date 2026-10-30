package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
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
 *     <li>種族に対応する死体ブロックがある({@link SpeciesCorpses#corpseStateFor})→ 死体の姿勢へ崩れ落ちてから、それを置く
 *     ({@link com.yayuy.overgrownvegetation.corpse.CorpseCollapse})。</li>
 *     <li>ガスト・ブレイズ → 死体を作らず、その場で寄生体に入れ替わる({@link com.yayuy.overgrownvegetation.corpse.NetherReanimation}、
 *     2026-10-09 ユーザー指定)。</li>
 *     <li>対応が無い(子どもの村人・動物など)→ 代わりに汚染シダ({@link com.yayuy.overgrownvegetation.entity.BlightFernEntity})
 *     がその場から生える。</li>
 * </ul>
 * 2026-10-09 の G1 の作り直しで、ゾンビ(今までは死体を経由せず即「過繁茂したゾンビ」)とスケルトン(今までは死んだ所に骨粉の効果)も
 * 死体 → 起き上がりにそろえた(ユーザー指定)。
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

        // ガスト・ブレイズは死体を作らず、その場で寄生体に入れ替わる(2026-10-09 ユーザー指定)。湧かせてはいけない所なら汚染シダ。
        if (com.yayuy.overgrownvegetation.corpse.NetherReanimation.begin(entity)) {
            return;
        }
        BlockState corpse = SpeciesCorpses.corpseStateFor(entity);
        if (corpse != null) {
            // 崩れ落ちに対応した種は、死体の姿勢へ倒れ終わってから死体を置く(2026-10-09、corpse.CorpseCollapse)。
            // まだ対応していない種は今までどおり死んだ瞬間にその場へ置く。
            if (!com.yayuy.overgrownvegetation.corpse.CorpseCollapse.begin(entity, corpse)) {
                serverLevel.setBlock(deathPos, corpse, Block.UPDATE_ALL);
            }
        } else {
            spawnBlightFern(serverLevel, deathPos);
        }
    }

    /**
     * 対応する死体が無い種族が死んだ場所に、汚染シダ(水中なら汚染水草)をその場から生やす。
     * 近くに汚染シダがもう十分いれば生やさない(2026-09-25、密度の上限。{@link BlightFernPlacement#spawnFromDeath})。
     */
    private static void spawnBlightFern(ServerLevel level, BlockPos deathPos) {
        BlightFernPlacement.spawnFromDeath(level, deathPos);
    }

    private SporeInfectionDeathHandler() {
    }
}
