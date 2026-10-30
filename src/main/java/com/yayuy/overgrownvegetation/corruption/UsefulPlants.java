package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.OvergrownFruitPlantBlock;
import com.yayuy.overgrownvegetation.block.WaymarkFlowerBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 汚染した地面の下草に混じって生える、役に立つ植物(2026-10-16、ユーザー指定「力をためた植物は過繁茂のバイオーム」)。
 * {@link CorruptionSpreader} の下草を生やす所から {@link #CHANCE} の確率で呼ぶ。生えるのは育ちかけ〜育ちきった株(仮決め)。
 */
public final class UsefulPlants {

    /** 下草 1 本ぶんの抽選で、代わりに役に立つ植物を生やす確率(仮決め)。 */
    public static final double CHANCE = 0.02D;

    private UsefulPlants() {
    }

    /** 1 本生やす。生えたら true。 */
    public static boolean tryPlace(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState state;
        int roll = random.nextInt(100);
        if (roll < 30) {
            state = fruit(ModBlocks.BURST_FRUIT_BUSH.get(), random);
        } else if (roll < 60) {
            state = fruit(ModBlocks.STICKY_VINE.get(), random);
        } else if (roll < 82) {
            state = fruit(ModBlocks.FLOAT_SAC_GRASS.get(), random);
        } else {
            state = ModBlocks.WAYMARK_FLOWER.get().defaultBlockState();
            var toward = WaymarkFlowerBlock.towardNearestNode(level, pos);
            if (toward != null) {
                state = state.setValue(WaymarkFlowerBlock.FACING, toward);
            }
        }
        if (!level.getBlockState(pos).isAir() || !state.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    private static BlockState fruit(Block block, RandomSource random) {
        return block.defaultBlockState().setValue(OvergrownFruitPlantBlock.AGE, 1 + random.nextInt(3));
    }
}
