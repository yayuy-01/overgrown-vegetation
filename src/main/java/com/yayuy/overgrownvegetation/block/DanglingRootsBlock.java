package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天井から垂れる根の簾の<b>先</b>(2026-11-03 地下の植生 2・U5)。巨大樹・コルダイテスの真下の洞窟の天井と、洞窟の口の天井から 2〜6 段垂れる。
 * 房({@link GlowTasselBlock})と同じ先端 + 中間の 2 ブロックの作りで、上が無くなると落ちる。伸びない。光らない。
 * 先からときどき水滴が落ちる(バニラの dripping_water の粒。葉の滴 {@code RainDrops} と同じ粒)。
 */
public class DanglingRootsBlock extends GrowingPlantHeadBlock {

    public static final MapCodec<DanglingRootsBlock> CODEC = simpleCodec(DanglingRootsBlock::new);
    private static final VoxelShape SHAPE = Block.box(4.0, 2.0, 4.0, 12.0, 16.0, 12.0);
    /** animateTick 1 回(バニラが近くのブロックをまばらに選んで呼ぶ)ごとに先から水滴が出る確率(仮決め 11-03。0.015 では撮影で 1 滴も見えなかった)。 */
    private static final float DRIP_CHANCE = 0.3F;

    public DanglingRootsBlock(BlockBehaviour.Properties properties) {
        super(properties, Direction.DOWN, SHAPE, false, 0.0);
    }

    @Override
    public MapCodec<DanglingRootsBlock> codec() {
        return CODEC;
    }

    @Override
    protected int getBlocksToGrowWhenBonemealed(RandomSource random) {
        return 1;
    }

    @Override
    protected boolean canGrowInto(BlockState state) {
        return state.isAir();
    }

    @Override
    protected Block getBodyBlock() {
        return ModBlocks.DANGLING_ROOTS_PLANT.get();
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return false;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return hangsFrom(level, pos);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() >= DRIP_CHANCE || !level.getBlockState(pos.below()).isAir()) {
            return;
        }
        level.addParticle(ParticleTypes.DRIPPING_WATER, pos.getX() + 0.35 + random.nextDouble() * 0.3, pos.getY() + 0.1,
                pos.getZ() + 0.35 + random.nextDouble() * 0.3, 0.0, 0.0, 0.0);
    }

    /** 真上が根の簾・下向きの丈夫な面なら垂れていられる(先と中間で共通)。 */
    static boolean hangsFrom(LevelReader level, BlockPos pos) {
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        return up.is(ModBlocks.DANGLING_ROOTS.get()) || up.is(ModBlocks.DANGLING_ROOTS_PLANT.get()) || up.isFaceSturdy(level, above, Direction.DOWN);
    }
}
