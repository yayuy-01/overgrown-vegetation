package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>搾り殺された幹</b> {@code strangled_stem}(2026-11-12 ネザーの先駆けの植物 2「搾り殺し」)。森の根に巻き付かれて水を搾り取られ、
 * 白く乾いた菌の木の柄。段階 3 以降も白い枯れ木として残る。光らない。
 *
 * <p>幹の根元(下が幹でない縦の幹)から、ときどき搾られた水の滴が落ちる(クライアントの粒だけ。仮: 1/12 の tick)。
 */
public class StrangledStemBlock extends RotatedPillarBlock {


    public StrangledStemBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(AXIS) != Direction.Axis.Y || random.nextInt(12) != 0) return;
        if (level.getBlockState(pos.below()).is(this)) return; // 根元だけ
        // 幹の横の面の下の方から、外へ少し出た所に滴を付ける(下の地面へ落ちる)
        Direction side = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        double x = pos.getX() + 0.5 + side.getStepX() * 0.55 + (random.nextDouble() - 0.5) * 0.6 * Math.abs(side.getStepZ());
        double z = pos.getZ() + 0.5 + side.getStepZ() * 0.55 + (random.nextDouble() - 0.5) * 0.6 * Math.abs(side.getStepX());
        double y = pos.getY() + 0.2 + random.nextDouble() * 0.5;
        level.addParticle(ParticleTypes.DRIPPING_WATER, x, y, z, 0.0, 0.0, 0.0);
    }
}
