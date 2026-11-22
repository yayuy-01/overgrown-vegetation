package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;

/**
 * <b>火の消えた松明</b>(2026-10-05、ユーザー要望「松明が覆われたら火が消えて発光しないブロックに」)。
 *
 * <p>汚染が松明(魂の松明・他 MOD の {@link TorchBlock} も)に届くと、覆う代わりにこれへ置き換わる
 * ({@code CorruptionRegistry#specialCorrupt} → {@link #extinguishedStateFor})。光らず、炎も煙も出さない。
 * 形・付き方はバニラの松明と同じ(壁の松明は向きを引き継ぐ)。壊すと棒が 1 本。アイテムは無い(汚染でしかできない)。
 * レッドストーントーチは松明ではない(回路の部品)ので、ほかの光源と同じく覆われて 4 割の明るさになる({@code corruption/CoveredLight})。
 */
public class ExtinguishedTorchBlock extends TorchBlock {

    public ExtinguishedTorchBlock(Properties properties) {
        super(ParticleTypes.SMOKE, properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // 火が消えているので炎も煙も出さない
    }

    /** 壁に付いた火の消えた松明。 */
    public static class Wall extends WallTorchBlock {

        public Wall(Properties properties) {
            super(ParticleTypes.SMOKE, properties);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        }
    }

    /** その松明が汚染されたときの姿。松明でなければ {@code null}。 */
    @Nullable
    public static BlockState extinguishedStateFor(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ExtinguishedTorchBlock || block instanceof Wall) {
            return null;
        }
        if (block instanceof WallTorchBlock) {
            return com.yayuy.overgrownvegetation.registry.ModBlocks.EXTINGUISHED_WALL_TORCH.get().defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (block instanceof TorchBlock) {
            return com.yayuy.overgrownvegetation.registry.ModBlocks.EXTINGUISHED_TORCH.get().defaultBlockState();
        }
        return null;
    }
}
