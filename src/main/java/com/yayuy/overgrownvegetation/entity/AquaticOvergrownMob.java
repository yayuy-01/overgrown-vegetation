package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;

/**
 * 水生の「過繁茂 Mob」であることを示すマーカー。
 *
 * <ul>
 *     <li>{@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner} の生態系スポーンで、
 *     陸の地表ではなく過繁茂バイオームの水中に湧かせる。</li>
 *     <li>水生の捕食者({@link AbstractOvergrownAquaticPredatorEntity})が「水中・水面にいる相手だけを
 *     襲う」判定({@link Predation#isWaterborneTarget})に使う。</li>
 * </ul>
 *
 * <p>実装 Mob はエンティティタイプタグ {@code #minecraft:can_breathe_under_water} にも入れること
 * ({@code canBreatheUnderwater()} がバニラで final のため。{@link BlightWaterGrassEntity} と同じ)。
 */
public interface AquaticOvergrownMob {

    /**
     * {@code entity} が「水中、または水面にいる」か —— 体の一部が水に浸かっている、または
     * 足元(あるいはその 1 つ下)が水ブロックであること。海岸・川辺の陸上にいる相手は false。
     */
    static boolean inWaterOrAtSurface(LivingEntity entity) {
        if (entity.isInWater() || entity.isUnderWater()) {
            return true;
        }
        BlockPos feet = entity.blockPosition();
        return entity.level().getFluidState(feet).is(FluidTags.WATER)
                || entity.level().getFluidState(feet.below()).is(FluidTags.WATER);
    }
}
