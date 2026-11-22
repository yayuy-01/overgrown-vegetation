package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 天井の光源 A の垂れる房の<b>先</b>(光る玉、2026-11-03 地下の植生 1)。{@code ceiling_root_net}(光る根の網)の下に 2〜5 段垂れる。
 * 光る蔓({@link OvergrownGlowVinesBlock})と同じ先端 + 中間の 2 ブロックの作りで、上が無くなると落ちる。伸びない(置いた長さのまま)。
 * 見た目の光は地下モードの点光源({@code CaveLights} の TASSEL)、本物の光量は 3(仮)。
 */
public class GlowTasselBlock extends GrowingPlantHeadBlock {

    public static final MapCodec<GlowTasselBlock> CODEC = simpleCodec(GlowTasselBlock::new);
    private static final VoxelShape SHAPE = Block.box(4.0, 4.0, 4.0, 12.0, 16.0, 12.0);

    public GlowTasselBlock(BlockBehaviour.Properties properties) {
        super(properties, Direction.DOWN, SHAPE, false, 0.0);
    }

    @Override
    public MapCodec<GlowTasselBlock> codec() {
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
        return ModBlocks.GLOW_TASSEL.get();
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return false;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return hangsFrom(level, pos);
    }

    /** 真上が房・網・下向きの丈夫な面なら垂れていられる(房の先と中間で共通)。 */
    static boolean hangsFrom(LevelReader level, BlockPos pos) {
        BlockPos above = pos.above();
        BlockState up = level.getBlockState(above);
        return up.is(ModBlocks.GLOW_TASSEL.get()) || up.is(ModBlocks.GLOW_TASSEL_TIP.get()) || up.is(ModBlocks.CEILING_ROOT_NET.get())
                || up.isFaceSturdy(level, above, Direction.DOWN);
    }
}
