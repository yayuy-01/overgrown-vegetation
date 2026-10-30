package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 灯りの呪符がともした明かり(2026-10-16、㉗)。火も熱も出さない見えない光で、{@link #LIFETIME} たつと消える(仮決め 1 分)。
 * 当たり判定は無く、ほかのブロックを置けば上書きされる。光の粒がときどき浮かぶ。
 */
public class CharmLightBlock extends Block {

    public static final MapCodec<CharmLightBlock> CODEC = simpleCodec(CharmLightBlock::new);
    public static final int LIFETIME = 1200;

    public CharmLightBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide() && !oldState.is(this)) {
            level.scheduleTick(pos, this, LIFETIME);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.removeBlock(pos, false);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.3D + random.nextDouble() * 0.4D, pos.getY() + 0.3D + random.nextDouble() * 0.4D,
                    pos.getZ() + 0.3D + random.nextDouble() * 0.4D, 0.0D, 0.005D, 0.0D);
        }
    }
}
